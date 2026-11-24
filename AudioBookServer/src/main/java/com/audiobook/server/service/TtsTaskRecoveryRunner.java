package com.audiobook.server.service;

import com.audiobook.server.model.SegmentStatus;
import com.audiobook.server.model.Task;
import com.audiobook.server.model.TaskStatus;
import com.audiobook.server.repository.AudioSegmentRepository;
import com.audiobook.server.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动时恢复未完成的 TTS 任务。
 *
 * 场景：
 * 1. 因「事务未提交就派发异步任务」的竞态被静默丢弃、永远停在 pending 的任务；
 * 2. 服务被重启/崩溃时正处于 processing、之后再没人接手的任务；
 * 3. 已落 partial 终态、但仍有段落停在 pending 的任务 —— 合成节点故障中断会留下
 *    这种任务（见 {@code app.tts.recover-partial}），只靠用户手动点重试补完太容易漏。
 *
 * 已完成且音频文件仍在的段落会被跳过，所以恢复是断点续跑而不是从头重来。
 */
@Component
public class TtsTaskRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TtsTaskRecoveryRunner.class);

    private final TaskRepository taskRepository;
    private final AudioSegmentRepository audioSegmentRepository;
    private final TtsService ttsService;

    @Value("${app.tts.recover-on-startup:true}")
    private boolean recoverOnStartup;

    /**
     * 单次启动最多恢复多少个任务。
     * 线程池用 AbortPolicy：超出队列容量的派发会被拒绝并记日志（由下方 try/catch 吸收），
     * 不会阻塞启动线程。保持与 queue-capacity 对齐，可一次性恢复全部积压任务。
     */
    @Value("${app.tts.recover-max:50}")
    private int recoverMax;

    /**
     * 是否把「部分成功（partial）」的任务也纳入启动恢复。
     *
     * <p>partial 是终态，默认不会被恢复 —— 但合成节点故障导致任务被中断时，会留下
     * 「已完成一部分、剩余段仍是 pending」的 partial
     * （见 {@code VoiceCloningTtsService#awaitHealthyNode}）。那批剩余段本该被继续合成，
     * 只靠用户手动点重试补完太容易漏。
     *
     * <p>筛选条件是「该任务是否真的有 pending 片段」，而不是时间窗或重试次数：
     * 有 pending 段说明它确实被中断过、重跑必然有意义，因此天然自限 ——
     * 某段的失败一旦被固化（状态由 pending 变成 failed），下次启动就不会再命中，
     * 不存在无限重跑。
     */
    @Value("${app.tts.recover-partial:true}")
    private boolean recoverPartial;

    public TtsTaskRecoveryRunner(TaskRepository taskRepository,
                                 AudioSegmentRepository audioSegmentRepository,
                                 TtsService ttsService) {
        this.taskRepository = taskRepository;
        this.audioSegmentRepository = audioSegmentRepository;
        this.ttsService = ttsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!recoverOnStartup) {
            log.info("已关闭启动恢复(app.tts.recover-on-startup=false)，跳过未完成任务的重新派发");
            return;
        }

        // 限量下沉到 SQL 层（每状态最多取 recoverMax 条）：
        // 无 LIMIT 全量加载后再在内存截断时，任务大量积压会在启动即内存暴涨。
        List<Task> unfinished = new ArrayList<>();
        int perStatusLimit = Math.max(1, recoverMax);
        for (String status : TaskStatus.unfinishedValues()) {
            unfinished.addAll(taskRepository.findByStatusOrderByIdAsc(status, PageRequest.of(0, perStatusLimit)));
        }
        unfinished.addAll(findPartialTasksWithPendingSegments(perStatusLimit));

        if (unfinished.isEmpty()) {
            log.info("✅ 没有需要恢复的 TTS 任务");
            return;
        }

        unfinished.sort(Comparator.comparing(Task::getId));

        int totalFound = unfinished.size();
        if (totalFound > recoverMax) {
            log.warn("⚠️ 未完成任务 {} 个，超过单次恢复上限 {}，本次只恢复最早的 {} 个；" +
                            "重启服务可继续恢复剩余任务，或调大 app.tts.recover-max",
                    totalFound, recoverMax, recoverMax);
            unfinished = unfinished.subList(0, recoverMax);
        }

        log.info("========== 恢复未完成的 TTS 任务，共 {} 个（累计发现 {} 个）==========",
                unfinished.size(), totalFound);
        for (Task task : unfinished) {
            log.info("♻️ 重新派发任务: taskId={}, 状态={}, 进度={}/{}",
                    task.getId(), task.getStatus(), task.getCompletedSegments(), task.getTotalSegments());
            try {
                ttsService.processTtsTaskAsync(task.getId());
            } catch (Exception e) {
                log.error("重新派发任务失败: taskId={}", task.getId(), e);
            }
        }
    }

    /**
     * 筛出「已落 partial 终态、但仍留有 pending 片段」的任务。
     *
     * <p><b>为什么先按状态捞候选、再用一条 SQL 精确过滤，而不是直接遍历全部 partial 任务。</b>
     * 一个没有任何待处理片段的 partial 任务被重新派发后，工作线程会逐段做文件存在性检查、
     * 全部走「跳过」分支，但每段仍要各写一次任务进度（updateRuntime + Redis + SSE）——
     * 一本 2500 段的书就是 2500 次无效 IO 与写库。用 pending 片段做前置过滤后，
     * 只有真正被中断过的任务才会进入恢复列表，代价是一次数百行的 id 查询。
     *
     * <p>顺带覆盖了「用户手动重试后又中断」的任务：剩余段同样是 pending，同样会被救回来。
     */
    private List<Task> findPartialTasksWithPendingSegments(int limit) {
        if (!recoverPartial) {
            return List.of();
        }

        List<Task> candidates = taskRepository.findByStatusOrderByIdAsc(
                TaskStatus.PARTIAL.value(), PageRequest.of(0, Math.max(1, limit)));
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> candidateIds = new ArrayList<>(candidates.size());
        for (Task candidate : candidates) {
            candidateIds.add(candidate.getId());
        }

        Set<Long> stillPending = new HashSet<>(audioSegmentRepository.findTaskIdsHavingSegmentInStatus(
                candidateIds, SegmentStatus.PENDING.value()));
        if (stillPending.isEmpty()) {
            return List.of();
        }

        List<Task> result = new ArrayList<>(stillPending.size());
        for (Task candidate : candidates) {
            if (stillPending.contains(candidate.getId())) {
                result.add(candidate);
            }
        }
        log.info("♻️ 部分完成(partial)的任务中，{} 个仍留有未合成片段，一并纳入恢复", result.size());
        return result;
    }
}
