package com.audiobook.server.controller;

import com.audiobook.server.model.ReadingProgress;
import com.audiobook.server.model.Task;
import com.audiobook.server.model.User;
import com.audiobook.server.repository.ReadingProgressRepository;
import com.audiobook.server.service.BookProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 播放进度跨端同步接口（<b>任务口径，老接口</b>）。
 *
 * 以服务端 TTS 任务 id（= Android 端 server_task_id）作为跨端同步键：
 * 同一账号、同一 TTS 任务可在多设备/多端继续收听。两端都有进度时以「段号较大 / 时间较新」
 * 为准（由客户端在拉取后合并，服务端只负责持久化最近一次上报）。
 *
 * <p><b>与新接口 {@code /api/book-progress} 的关系</b>：本接口是<b>任务</b>口径，
 * 要求任务归属本人（{@code getOwnedTask}），所以只适用于「自己的合成任务」；
 * 书城书的音频常由管理员合成 ⇒ 普通用户调它必然 403，进度传不上去。
 * 新接口是<b>书</b>口径（{@code book_key}），与合成状态、任务归属都无关。
 *
 * <p><b>本接口额外镜像到新表</b>：老客户端（已在用的 APK / Web）只会调这个端点，
 * 若不镜像，它们读写的进度就只落在老表里，新客户端读不到 ⇒ 进度不互通。
 * 镜像让「老客户端写入 → 新客户端能读到」成立，是渐进迁移的关键一环。
 * 镜像失败不影响老接口本身（它已落库成功），只记日志。
 */
@RestController
@RequestMapping("/api/progress")
public class ProgressController {

    private static final Logger log = LoggerFactory.getLogger(ProgressController.class);

    private final TtsControllerSupport support;
    private final ReadingProgressRepository progressRepository;
    private final BookProgressService bookProgressService;

    public ProgressController(TtsControllerSupport support,
                              ReadingProgressRepository progressRepository,
                              BookProgressService bookProgressService) {
        this.support = support;
        this.progressRepository = progressRepository;
        this.bookProgressService = bookProgressService;
    }

    /**
     * 把「任务口径」的进度镜像到「书口径」的新表。
     *
     * <p>任务的 book_key 取 {@code t:{taskId}}：任务就是这本书在服务端合成出来的那份产物，
     * 三端对「同一本书由服务端合成」算出的 key 正是 {@code t:}（见 BookKeys 的优先级
     * s: &gt; t: &gt; n:：书城书走 s:，本地书在服务端合成过则走 t:）。
     *
     * <p>纯失败不影响主流程：老表已落库，这里只是额外同步一份。
     */
    private void mirrorToBookKey(Long userId, Long taskId, int segment, int offsetMs) {
        if (userId == null || taskId == null) {
            return;
        }
        try {
            bookProgressService.save(userId, "t:" + taskId, segment, offsetMs);
        } catch (Exception e) {
            log.warn("镜像进度到 book_key 表失败（不影响主流程）: taskId={}, err={}", taskId, e.getMessage());
        }
    }

    /**
     * 拉取某任务的播放进度。
     * 返回 {success, hasProgress, segment, offsetMs, updatedAt}；尚无进度时 hasProgress=false。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getProgress(@RequestParam("taskId") Long taskId,
                                                           Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
            Task task = support.getOwnedTask(taskId, authentication);
            if (task == null) {
                response.put("success", false);
                response.put("message", "任务不存在或无权访问");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
            ReadingProgress progress =
                    progressRepository.findByUserIdAndTaskId(user.getId(), taskId).orElse(null);
            response.put("success", true);
            if (progress == null) {
                // 老表没有：回落到新表（book_key 口径）。
                // 可能是「别的终端用新接口写、新客户端用老接口读」的组合，
                // 或镜像写入后老表被清（任务重建）。两种情况都该让用户看到进度。
                var byBookKey = bookProgressService.load(user.getId(), "t:" + taskId).orElse(null);
                if (byBookKey == null) {
                    response.put("hasProgress", false);
                } else {
                    response.put("hasProgress", true);
                    response.put("segment", byBookKey.getSegmentNumber());
                    response.put("offsetMs", byBookKey.getOffsetMs());
                    response.put("updatedAt", byBookKey.getUpdatedAt() == null
                            ? null : byBookKey.getUpdatedAt().toString());
                }
            } else {
                response.put("hasProgress", true);
                response.put("segment", progress.getSegmentNumber());
                response.put("offsetMs", progress.getOffsetMs());
                response.put("updatedAt", progress.getUpdatedAt() == null ? null : progress.getUpdatedAt().toString());
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询播放进度失败 taskId={}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "查询失败，请稍后重试"));
        }
    }

    /**
     * 保存/更新某任务的播放进度。
     * 入参（JSON body）：taskId(Long)、segment(int)、offsetMs(int)。
     * 归属校验通过后才写入，避免越权写入他人任务进度。
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> saveProgress(@RequestBody Map<String, Object> body,
                                                           Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
            Long taskId = parseLong(body.get("taskId"));
            Integer segment = parseInt(body.get("segment"));
            Integer offsetMs = parseInt(body.get("offsetMs"));
            if (taskId == null || segment == null || offsetMs == null) {
                response.put("success", false);
                response.put("message", "参数缺失：taskId / segment / offsetMs 均为必填");
                return ResponseEntity.badRequest().body(response);
            }
            if (segment < 0 || offsetMs < 0) {
                response.put("success", false);
                response.put("message", "参数非法：segment / offsetMs 不能为负");
                return ResponseEntity.badRequest().body(response);
            }
            Task task = support.getOwnedTask(taskId, authentication);
            if (task == null) {
                response.put("success", false);
                response.put("message", "任务不存在或无权访问");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
            ReadingProgress progress = progressRepository.findByUserIdAndTaskId(user.getId(), taskId)
                    .orElseGet(() -> {
                        ReadingProgress p = new ReadingProgress();
                        p.setUserId(user.getId());
                        p.setTaskId(taskId);
                        return p;
                    });
            progress.setSegmentNumber(segment);
            progress.setOffsetMs(offsetMs);
            progress.setUpdatedAt(LocalDateTime.now());
            progressRepository.save(progress);
            // 额外镜像到 book_key 口径的新表：老客户端只会调本端点，
            // 不镜像的话新客户端读不到这些进度 ⇒ 跨端不互通。
            mirrorToBookKey(user.getId(), taskId, segment, offsetMs);
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("保存播放进度失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "保存失败，请稍后重试"));
        }
    }

    private Long parseLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(o.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
