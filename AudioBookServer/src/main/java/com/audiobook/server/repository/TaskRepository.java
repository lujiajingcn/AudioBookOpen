package com.audiobook.server.repository;

import com.audiobook.server.model.Task;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * 数据库级分页查询用户任务（替代全量加载后内存 subList 分页）。
     * 不提供无 LIMIT 的按用户查询入口，防止再次引入全表加载。
     */
    Page<Task> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * 删除用户时的任务清理投影：只取 id（登记删除/级联删片段）与
     * bookFilePath（事务外清理磁盘源文件），不加载完整实体行。
     * 与 AudioSegmentRepository 的 SegmentMeta 投影模式一致：
     * 该场景合法需要「某用户全部任务」，但只需要两个字段。
     */
    @Query("select t.id as id, t.bookFilePath as bookFilePath from Task t where t.userId = :userId")
    List<TaskCleanupMeta> findCleanupMetaByUserId(@Param("userId") Long userId);

    interface TaskCleanupMeta {
        Long getId();
        String getBookFilePath();
    }

    /**
     * 按用户批量删除全部任务（删除用户场景）。
     * JPQL 批量删除，避免派生删除逐行加载实体；与 AudioSegmentRepository.deleteByTaskId 同款模式。
     * 调用方需已先删除这些任务下的片段（外键/业务顺序约束）。
     */
    @Transactional
    @Modifying
    @Query("delete from Task t where t.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /**
     * 按状态限量查询任务（按 id 升序）。
     * 启动恢复专用：限量下沉到 SQL 层——按状态全量查询后再在内存截断，
     * 任务积压时启动即 OOM 风险。
     */
    List<Task> findByStatusOrderByIdAsc(String status, Pageable pageable);

    /**
     * 加行锁读取任务。用于「重试补扣积分」的判定-扣费原子化：
     * 无锁读 creditsRefunded 后再扣费时，双击/前端超时重试等并发请求能同时
     * 通过判定各扣一次，而任务再次失败时只退一次——用户净亏一份积分（资损）。
     * PESSIMISTIC_WRITE 让并发重试在同一任务行上串行，第二个请求拿锁后看到的是
     * 第一个事务提交后的最新 creditsRefunded，不会重复收费。
     *
     * 注意：调用方必须处于事务中（与 UserRepository.findByIdForUpdate 同理）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :id")
    Optional<Task> findByIdForUpdate(@Param("id") Long id);

    /** 未完成任务总数（pending/queued/processing），用于建任务前的队列容量前置校验 */
    @Query("select count(t) from Task t where t.status in :statuses")
    long countByStatusIn(@Param("statuses") List<String> statuses);

    /**
     * 工作线程进度更新（运行中）。
     * 用 JPQL UPDATE 而非 save()：异步工作线程持有脱管实体，save() 走 merge，
     * 若任务行在运行期间被删除（用户删任务/管理员删用户），merge 会重新 INSERT
     * 把任务"复活"并留下悬空 user_id。UPDATE 对不存在的行只返回 0，绝不插入。
     *
     * REQUIRES_NEW：调用方包括 afterCommit 回调（原事务已提交）和 @Async 工作线程
     * （无事务），两者都没有活动事务，@Modifying 更新会直接抛
     * TransactionRequiredException。独立事务保证任何上下文都能执行。
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Task t set t.status = :status, t.completedSegments = :completed, " +
           "t.totalSegments = :total where t.id = :id")
    int updateRuntime(@Param("id") Long id, @Param("status") String status,
                      @Param("completed") int completed, @Param("total") int total);

    /**
     * 落库书级识别语种。同 {@link #updateRuntime}：用 UPDATE 而非 save()，
     * 免得工作线程持有脱管实体时把已并发删除的任务行 merge 复活；REQUIRES_NEW
     * 保证在 @Async 线程池 / afterCommit 这类「无活动事务」上下文里也能提交。
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Task t set t.language = :language where t.id = :id")
    int updateLanguage(@Param("id") Long id, @Param("language") String language);

    /**
     * 段落级进度心跳，**不覆盖 paused 状态**。
     *
     * <p>为什么不能直接用 {@link #updateRuntime}：管理员点「暂停」后工作线程可能正处在
     * 本段与下一段之间，这次心跳会把 status 从 paused 写回 processing —— 暂停随即失效，
     * 且工作线程已在下一轮检查点收手，任务永远停在「看起来在跑、实际没人跑」的 processing。
     * 所以 WHERE 里排除 paused：暂停中的任务进度照常落库（已完成段要留痕），
     * 但状态不被踩回 processing；返回 0 即表示「任务已被暂停或已被删除」，
     * 调用方据此停止 Redis/SSE 广播并退出循环。
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Task t set t.status = :status, t.completedSegments = :completed, " +
           "t.totalSegments = :total where t.id = :id and t.status <> :paused")
    int updateRuntimeUnlessPaused(@Param("id") Long id, @Param("status") String status,
                                  @Param("completed") int completed, @Param("total") int total,
                                  @Param("paused") String paused);

    /**
     * 挂起任务：仅当任务仍处于 {@code from} 中的状态时才改写为 paused。
     *
     * <p>用 WHERE 做条件更新而非「先查后写」：管理员点暂停时任务可能刚好被工作线程
     * 跑到终态（completed/partial/failed），此时返回 0，调用方据此提示「状态已变化」，
     * 绝不会把一个已经完成的合成改写成暂停。
     *
     * @return 1 = 挂起成功；0 = 状态已变化或任务已不存在
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Task t set t.status = :paused where t.id = :id and t.status in :from")
    int pauseIfInStatus(@Param("id") Long id, @Param("paused") String paused,
                        @Param("from") List<String> from);

    /**
     * 继续合成：仅当任务确实处于 paused 时改写回 queued。
     *
     * <p>条件同样落在 WHERE 上：连点两次「继续」时第二次返回 0，
     * 调用方不会把已经重新入队的任务又写一遍状态。
     *
     * @return 1 = 已改回 queued；0 = 状态已变化或任务已不存在
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Task t set t.status = :queued where t.id = :id and t.status = :paused")
    int resumeIfPaused(@Param("id") Long id, @Param("paused") String paused,
                       @Param("queued") String queued);

    /**
     * 条件改状态：仅当任务仍处于 {@code from} 中的状态时才改写为 {@code to}，并清空错误文案。
     *
     * <p>两条使用路径，语义都是「状态没被别人抢先改动才写」：
     *  - 补齐缺失段（partial / failed → queued）；
     *  - 派发被队列拒绝后回滚（queued → 原来的 partial / failed）。
     *
     * <p>顺带清空 {@code errorMessage}：这次失败的原因属于上一轮，留着会让界面在
     * 「已重新排队」的徽标上继续挂着一条过时错误。放在同一条 UPDATE 里而不是
     * 「查实体 + save()」，是因为后者是脱管实体 merge 整行写回，会把并发写入的
     * {@code completedSegments} 覆盖回去。
     *
     * @return 1 = 已改写；0 = 状态已变化或任务已不存在
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Task t set t.status = :to, t.errorMessage = null " +
           "where t.id = :id and t.status in :from")
    int setStatusIfInStatus(@Param("id") Long id, @Param("to") String to,
                            @Param("from") List<String> from);

    /**
     * 工作线程终态更新。同 updateRuntime，UPDATE 不会复活已删除的任务；
     * 返回 0 表示任务已被删除，调用方应跳过后续终态副作用（Redis/退款等）。
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Task t set t.status = :status, t.completedSegments = :completed, " +
           "t.totalSegments = :total, t.errorMessage = :errorMessage where t.id = :id")
    int updateFinal(@Param("id") Long id, @Param("status") String status,
                    @Param("completed") int completed, @Param("total") int total,
                    @Param("errorMessage") String errorMessage);

    /**
     * 持久化任务级默认音色与段落级音色映射。
     *
     * 同 updateRuntime，用 JPQL UPDATE 而非 save()：本方法在事务外被调用，
     * save() 对脱管实体走 merge，若任务行此刻已被并发删除，merge 会重新 INSERT
     * 把任务"复活"并留下悬空 user_id。UPDATE 对不存在的行只返回 0，绝不插入。
     *
     * @return 更新的行数；0 表示任务已被删除，调用方应跳过后续重合成
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Task t set t.defaultVoiceId = :defaultVoiceId, " +
           "t.segmentVoices = :segmentVoices where t.id = :id")
    int updateVoiceConfig(@Param("id") Long id, @Param("defaultVoiceId") String defaultVoiceId,
                          @Param("segmentVoices") String segmentVoices);

    /**
     * 清空任务级失败原因（重新排入队列前调用，避免旧失败文案残留到新一轮合成）。
     * 同样用 UPDATE 而非 save()，理由同 updateRuntime。
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update Task t set t.errorMessage = null where t.id = :id")
    int clearErrorMessage(@Param("id") Long id);

    /** 某用户在指定状态集合内的任务数（磁盘配额：限制单用户未完成任务堆积） */
    long countByUserIdAndStatusIn(Long userId, java.util.Collection<String> statuses);
}
