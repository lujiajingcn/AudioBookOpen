package com.audiobook.server.repository;

import com.audiobook.server.model.CreditOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreditOrderRepository extends JpaRepository<CreditOrder, Long> {

    Optional<CreditOrder> findByOrderNo(String orderNo);

    /**
     * 加行锁读取订单，供支付回调入账使用。
     *
     * 支付平台会重复投递同一笔通知（且可能并发），不加锁时两条回调可能同时读到
     * PENDING 状态、各自发放一次积分 —— 用户付一次钱到账两次（资损）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM CreditOrder o WHERE o.orderNo = :orderNo")
    Optional<CreditOrder> findByOrderNoForUpdate(@Param("orderNo") String orderNo);

    /** 我的订单列表（分页） */
    Page<CreditOrder> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 后台按状态筛选订单 */
    Page<CreditOrder> findByStatus(String status, Pageable pageable);

    /**
     * 超时未支付订单的订单号快照（限量 + 按过期时间升序），由定时任务关闭。
     * 只取 orderNo 不加载整行，且必须带 Pageable 限量，
     * 防止订单积压时一次打爆内存；漏关的下一轮自然重试。
     */
    @Query("SELECT o.orderNo FROM CreditOrder o WHERE o.status = :status AND o.expireAt < :time ORDER BY o.expireAt ASC")
    List<String> findExpiredOrderNos(@Param("status") String status, @Param("time") LocalDateTime time, Pageable pageable);

    /** 后台概览：按状态统计订单数 */
    long countByStatus(String status);

    /** 后台概览：已支付订单的总金额（空表返回 0，避免 null 拆箱） */
    @Query("SELECT COALESCE(SUM(o.amount), 0) FROM CreditOrder o WHERE o.status = 'PAID'")
    BigDecimal sumPaidAmount();
}
