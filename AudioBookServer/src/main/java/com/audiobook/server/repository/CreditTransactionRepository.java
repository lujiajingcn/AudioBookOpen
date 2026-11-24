package com.audiobook.server.repository;

import com.audiobook.server.model.CreditTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, Long> {

    /** 我的积分流水（分页） */
    Page<CreditTransaction> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 后台按用户筛选流水 */
    Page<CreditTransaction> findByUserId(Long userId, Pageable pageable);

    /** 后台按类型筛选流水 */
    Page<CreditTransaction> findByType(String type, Pageable pageable);

    /** 后台按用户 + 类型筛选流水 */
    Page<CreditTransaction> findByUserIdAndType(Long userId, String type, Pageable pageable);

    /**
     * 按任务 + 类型查已存在的流水，用于退款幂等兜底校验
     * （任务上的 creditsRefunded 是主判据，这里作为二次保险）。
     */
    List<CreditTransaction> findByTaskIdAndType(Long taskId, String type);

    /** 充值订单是否已入账（回调幂等的兜底校验） */
    List<CreditTransaction> findByOrderNoAndType(String orderNo, String type);

    /**
     * 统计某用户在时间区间内、指定类型的流水条数。
     *
     * 用于「每日免费试用额度」的用量统计：按自然日零点到当前时刻，
     * 数一下今天已经记了几条 FREE_TRIAL 流水，再用规则里的上限减去它得到剩余次数。
     */
    long countByUserIdAndTypeAndCreatedAtBetween(Long userId, String type,
                                                 LocalDateTime start, LocalDateTime end);

    /**
     * 按类型汇总积分变动值，供后台概览统计。
     *
     * 注意 CONSUME 类型的 credits 是负数，前端展示时需取绝对值；
     * 用 COALESCE 兜底空表返回 0，否则 SUM 为 null 会导致拆箱 NPE。
     */
    @Query("SELECT COALESCE(SUM(t.credits), 0) FROM CreditTransaction t WHERE t.type = :type")
    long sumCreditsByType(@Param("type") String type);
}
