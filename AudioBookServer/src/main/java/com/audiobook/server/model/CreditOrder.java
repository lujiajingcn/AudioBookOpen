package com.audiobook.server.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充值订单。
 *
 * 下单时对套餐做快照（名称/积分/赠送/金额），此后套餐改价或下架都不影响已存在的订单，
 * 避免用户支付的金额与到账积分被后台改动「追溯性篡改」。
 *
 * 订单号 {@link #orderNo} 全局唯一，既是对外支付单号，也是支付回调的幂等键。
 */
@Entity
@Table(name = "credit_orders", indexes = {
        @Index(name = "idx_credit_order_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_credit_order_status", columnList = "status")
})
public class CreditOrder {

    /** 待支付 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已支付并已发放积分 */
    public static final String STATUS_PAID = "PAID";
    /** 已关闭（用户取消或超时） */
    public static final String STATUS_CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户订单号，全局唯一，支付回调据此幂等 */
    @Column(name = "order_no", nullable = false, unique = true, length = 64)
    private String orderNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "username", length = 50)
    private String username;

    /** 下单时的套餐 ID（套餐被删除后仍保留快照字段可用） */
    @Column(name = "package_id")
    private Long packageId;

    /** 套餐名称快照 */
    @Column(name = "package_name", length = 100)
    private String packageName;

    /** 基础积分快照 */
    @Column(name = "credits", nullable = false)
    private Integer credits;

    /** 赠送积分快照 */
    @Column(name = "bonus_credits", nullable = false)
    private Integer bonusCredits = 0;

    /** 应付金额（元）快照 */
    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** 订单状态，取值见本类 STATUS_* 常量 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_PENDING;

    /** 支付渠道：ALIPAY / WECHAT / MOCK / ADMIN */
    @Column(name = "pay_channel", length = 20)
    private String payChannel;

    /** 支付平台返回的交易号（支付宝 trade_no / 微信 transaction_id） */
    @Column(name = "pay_trade_no", length = 128)
    private String payTradeNo;

    /** 支付成功时间 */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** 订单失效时间，超时未支付则关闭 */
    @Column(name = "expire_at")
    private LocalDateTime expireAt;

    /** 订单关闭/失败原因（用户取消、超时、支付渠道下单失败等），用于前端给出明确失败提示 */
    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 订单并发安全仅依赖 findByOrderNoForUpdate 悲观行锁时，任何新增写路径
     * 一旦忘记加锁，旧快照覆盖新状态就会产生「已付款却显示已关闭」类资损脏数据。
     * 加上 @Version 后，即使漏加行锁，
     * 并发提交也会有一方抛 OptimisticLockException 触发回滚，绝不会静默覆盖。
     *
     * DDL 带 not null default 0：dev 的 ddl-auto=update 给存量行加列时，存量订单
     * 自动回填 0（若为 NULL，Hibernate 会把合并的实体误判为新对象执行 INSERT，
     * 撞主键报错）；prod（ddl-auto=validate）需先执行迁移语句（见 init_database.sql）。
     */
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "bigint not null default 0")
    private Long version;

    public CreditOrder() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** 与 @PreUpdate 对齐：落库时以回调时间为准，避免构造与落库间的时间差 */
    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 支付成功后应到账的总积分 */
    @Transient
    public int getTotalCredits() {
        return (credits != null ? credits : 0) + (bonusCredits != null ? bonusCredits : 0);
    }

    // ---------- Getters & Setters ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Long getPackageId() {
        return packageId;
    }

    public void setPackageId(Long packageId) {
        this.packageId = packageId;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public Integer getCredits() {
        return credits;
    }

    public void setCredits(Integer credits) {
        this.credits = credits;
    }

    public Integer getBonusCredits() {
        return bonusCredits;
    }

    public void setBonusCredits(Integer bonusCredits) {
        this.bonusCredits = bonusCredits;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPayChannel() {
        return payChannel;
    }

    public void setPayChannel(String payChannel) {
        this.payChannel = payChannel;
    }

    public String getPayTradeNo() {
        return payTradeNo;
    }

    public void setPayTradeNo(String payTradeNo) {
        this.payTradeNo = payTradeNo;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        // 防御存量行读出 null（迁移前导入的数据），统一按 0 处理
        return version == null ? 0L : version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
