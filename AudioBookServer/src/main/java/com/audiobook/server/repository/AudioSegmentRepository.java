package com.audiobook.server.repository;

import com.audiobook.server.model.AudioSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AudioSegmentRepository extends JpaRepository<AudioSegment, Long> {
    // 不提供按任务的「全量实体查询」入口：那会把每段的 @Lob audio_data 大字段
    // 整条加载进堆（长书直接 OOM）。列表与重试场景分别走
    // findMetaByTaskIdOrderBySegmentNumberAsc / findIdsByTaskIdAndStatus。
    Optional<AudioSegment> findByTaskIdAndSegmentNumber(Long taskId, Integer segmentNumber);
    long countByTaskIdAndStatus(Long taskId, String status);

    /**
     * 只查片段 id（重试路径专用，按状态过滤）。
     * 用实体查询会把 @Lob audio_data 一并加载进堆，且拿到的脱管实体后续 save()
     * 会经 merge 整行写回，任务被并发删除时还会把片段行重新 INSERT 复活。
     * 调用方拿到 id 后走 {@link #updateState} 回写即可。
     */
    @Query("select s.id from AudioSegment s where s.taskId = :taskId and s.status = :status")
    List<Long> findIdsByTaskIdAndStatus(@Param("taskId") Long taskId, @Param("status") String status);

    /**
     * 只查片段 id，按状态集合过滤（重试路径专用）。
     *
     * <p>重试范围必须同时包含 failed 与 pending：合成节点不可用导致任务被中断时，
     * 未处理的段刻意保持 pending（没有被标成 failed，避免把「还没轮到」误报成「合成失败」），
     * 它们恰恰是这次重试最需要救回来的部分。只查 failed 会让这类任务重试时报
     * 「没有失败的片段需要重试」。
     *
     * <p>同 {@link #findIdsByTaskIdAndStatus}：实体查询会把 @Lob audio_data 一并拉进堆。
     */
    @Query("select s.id from AudioSegment s where s.taskId = :taskId and s.status in :statuses")
    List<Long> findIdsByTaskIdAndStatusIn(@Param("taskId") Long taskId, @Param("statuses") List<String> statuses);

    /**
     * 从给定任务集合中筛出「仍存在指定状态片段」的任务 id（partial 启动恢复专用）。
     *
     * <p>用一条 SQL 拿到，避免逐任务查一次段数（N+1）。调用方必须保证
     * {@code taskIds} 非空：JPQL 的 {@code in ()} 空集合在部分数据库上会报语法错。
     */
    @Query("select distinct s.taskId from AudioSegment s where s.taskId in :taskIds and s.status = :status")
    List<Long> findTaskIdsHavingSegmentInStatus(@Param("taskIds") Collection<Long> taskIds,
                                                @Param("status") String status);

    /**
     * 按任务批量删除片段（用于删除用户时清理其合成产物）。
     * 使用 JPQL 批量删除，避免派生删除先把 @Lob audio_data 大字段逐条加载进堆内存。
     * 自带事务：@Modifying 必须在事务中执行，调用方可能无事务（Controller/异步线程）。
     */
    @Transactional
    @Modifying
    @Query("delete from AudioSegment s where s.taskId = :taskId")
    void deleteByTaskId(@Param("taskId") Long taskId);

    /**
     * 工作线程更新片段状态（processing/completed/failed）。
     * 用 JPQL UPDATE 而非 save()：工作线程持有脱管实体，任务被并发删除后
     * save() 会经 merge 重新 INSERT 复活片段行；UPDATE 对不存在的行返回 0。
     *
     * REQUIRES_NEW：调用方是 @Async 工作线程（无事务），@Modifying 更新缺少
     * 事务会直接抛 TransactionRequiredException，导致每一段还没调 TTS 就被判失败。
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update AudioSegment s set s.status = :status, s.audioFilePath = :audioFilePath, " +
           "s.errorMessage = :errorMessage where s.id = :id")
    int updateState(@Param("id") Long id, @Param("status") String status,
                    @Param("audioFilePath") String audioFilePath,
                    @Param("errorMessage") String errorMessage);

    /**
     * 把某任务下所有处于 {@code from} 状态的片段重置为 {@code to}（补齐缺失段专用）。
     *
     * <p>为什么不是逐段调 {@link #updateState}：一次「补齐」可能涉及上万段
     * （任务整体失败时全部片段都是 failed），逐段一次 UPDATE 等于上万次往返，
     * 请求必然超时。这里一条语句搞定，返回受影响行数供日志核对。
     *
     * <p>{@code audioFilePath} 一并置空，语义与 {@code updateState(id, 待处理, null, null)} 一致：
     * failed 段本就没有可用文件，pending 段也还没有。
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update AudioSegment s set s.status = :to, s.audioFilePath = null, " +
           "s.errorMessage = null where s.taskId = :taskId and s.status in :from")
    int resetStatesByTaskId(@Param("taskId") Long taskId, @Param("to") String to,
                            @Param("from") List<String> from);

    /**
     * 修改某段的绑定音色并把状态重置为待处理，触发重新合成。
     * voice 传 null 表示清除预设音色、回退到任务默认 / 克隆参考音频。
     * 与 updateState 同用 REQUIRES_NEW：调用方是普通 Controller/异步线程（无事务），
     * @Modifying 缺事务会抛 TransactionRequiredException，导致写不进去。
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update AudioSegment s set s.voice = :voice, s.status = :status, " +
           "s.audioFilePath = null, s.errorMessage = null " +
           "where s.taskId = :taskId and s.segmentNumber = :number")
    int updateVoiceAndReset(@Param("taskId") Long taskId, @Param("number") int number,
                            @Param("voice") String voice, @Param("status") String status);

    /**
     * 只取下载/列表所需的元数据列（不含 @Lob audio_data 大字段）。
     * audio_data 当前恒为 NULL（音频只落磁盘），此处仍用投影以减少无关列加载。
     */
    @Query("select s.id as id, s.segmentNumber as segmentNumber, s.status as status, " +
           "s.audioFilePath as audioFilePath, s.textContent as textContent, s.errorMessage as errorMessage, " +
           "s.voice as voice " +
           "from AudioSegment s " +
           "where s.taskId = :taskId order by s.segmentNumber asc")
    List<SegmentMeta> findMetaByTaskIdOrderBySegmentNumberAsc(@Param("taskId") Long taskId);

    interface SegmentMeta {
        Long getId();
        Integer getSegmentNumber();
        String getStatus();
        String getAudioFilePath();
        String getTextContent();
        String getErrorMessage();
        /** 本段绑定的预设音色 voiceId（null 表示沿用任务默认/克隆参考音频） */
        String getVoice();
    }

    /**
     * 单片段下载所需的元数据（不含 @Lob audio_data）。
     * 下载接口绝大多数情况直接流式回传磁盘文件，根本不需要 BLOB；
     * 用 findById 取实体会把整段音频先整份读进堆内存再丢弃。
     */
    @Query("select s.id as id, s.taskId as taskId, s.segmentNumber as segmentNumber, " +
           "s.status as status, s.audioFilePath as audioFilePath " +
           "from AudioSegment s where s.id = :id")
    Optional<SegmentDownloadMeta> findDownloadMetaById(@Param("id") Long id);

    /** 磁盘缺失时尝试读取 audio_data（当前恒为 NULL，实际只会返回空，用于触发缺失报错） */
    @Query("select s.audioData from AudioSegment s where s.id = :id")
    byte[] findAudioDataById(@Param("id") Long id);

    interface SegmentDownloadMeta {
        Long getId();
        Long getTaskId();
        Integer getSegmentNumber();
        String getStatus();
        String getAudioFilePath();
    }
}
