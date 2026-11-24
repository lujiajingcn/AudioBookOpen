package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 积分流水（只追加，永不修改/删除）。
 *
 * 每一次余额变动都必须留下一条流水，{@link #balanceAfter} 记录变动后的余额快照，
 * 便于对账时逐条回放校验，定位「余额与流水不一致」这类最难查的资损问题。
 */
@Entity
@Table(name = "credit_transactions", indexes = {
        @Index(name = "idx_credit_tx_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_credit_tx_type", columnList = "type"),
        @Index(name = "idx_credit_tx_task", columnList = "task_id")
})
public class CreditTransaction {

    /** 充值到账 */
    public static final String TYPE_RECHARGE = "RECHARGE";
    /** 合成消耗 */
    public static final String TYPE_CONSUME = "CONSUME";
    /** 合成失败退还 */
    public static final String TYPE_REFUND = "REFUND";
    /** 管理员手动调整 */
    public static final String TYPE_ADMIN_ADJUST = "ADMIN_ADJUST";
    /** 注册赠送 */
    public static final String TYPE_REGISTER_BONUS = "REGISTER_BONUS";
    /** 免费试用合成（每日额度内，不计费但占用一次试用次数，便于统计与展示） */
    public static final String TYPE_FREE_TRIAL = "FREE_TRIAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 冗余用户名，便于后台流水列表直接展示，无需回表 join */
    @Column(name = "username", length = 50)
    private String username;

    /** 流水类型，取值见本类 TYPE_* 常量 */
    @Column(name = "type", nullable = false, length = 20)
    private String type;

    /** 积分变动值：正数为增加，负数为扣减 */
    @Column(name = "credits", nullable = false)
    private Integer credits;

    /** 变动后的积分余额快照 */
    @Column(name = "balance_after", nullable = false)
    private Integer balanceAfter;

    /** 关联的合成任务（消耗/退款流水） */
    @Column(name = "task_id")
    private Long taskId;

    /** 关联的充值订单号（充值流水） */
    @Column(name = "order_no", length = 64)
    private String orderNo;

    /** 备注说明 */
    @Column(name = "remark", length = 255)
    private String remark;

    /** 操作人（管理员调整时记录管理员用户名，系统行为为 null） */
    @Column(name = "operator", length = 50)
    private String operator;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public CreditTransaction() {
        this.createdAt = LocalDateTime.now();
    }

    public CreditTransaction(Long userId, String username, String type,
                            Integer credits, Integer balanceAfter, String remark) {
        this.userId = userId;
        this.username = username;
        this.type = type;
        this.credits = credits;
        this.balanceAfter = balanceAfter;
        this.remark = remark;
        this.createdAt = LocalDateTime.now();
    }

    // ---------- Getters & Setters ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getCredits() {
        return credits;
    }

    public void setCredits(Integer credits) {
        this.credits = credits;
    }

    public Integer getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(Integer balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
