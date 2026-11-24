package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 积分规则配置（全局单行，固定 id = 1）。
 *
 * 由管理员在 AudioBookManager 后台维护，服务端所有扣费/赠送逻辑都以本表为唯一事实来源，
 * 不再散落在 application.yml 里，改规则无需重启。
 *
 * 计费口径：按「合成任务」固定扣费——一次合成任务扣 {@link #creditsPerTask} 积分，
 * 与书籍字数、切分出的音频片段数无关。
 */
@Entity
@Table(name = "credit_rule")
public class CreditRule {

    /** 全局唯一配置行的主键值 */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    /** 积分功能总开关：关闭后合成不扣积分、也不校验余额（等同于免费模式） */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 每个合成任务消耗的积分 */
    @Column(name = "credits_per_task", nullable = false)
    private Integer creditsPerTask = 10;

    /** 新用户注册赠送积分 */
    @Column(name = "free_credits_on_register", nullable = false)
    private Integer freeCreditsOnRegister = 50;

    /**
     * 每日免费试用合成次数（每日 0 点重置）。
     * 0 表示不提供试用，用户必须充值后才能合成；大于 0 表示新用户/所有用户每天可免费合成 N 次，
     * 用于降低首充门槛、让用户在付费前先尝鲜。默认 2 次。
     */
    @Column(name = "daily_free_tasks", nullable = false)
    private Integer dailyFreeTasks = 2;

    /** 任务整体失败（无任何片段合成成功）时是否全额退还积分 */
    @Column(name = "refund_on_task_failure", nullable = false)
    private Boolean refundOnTaskFailure = true;

    /** 管理员账号合成是否免扣积分 */
    @Column(name = "admin_exempt", nullable = false)
    private Boolean adminExempt = true;

    /** 面向用户展示的规则说明（充值页/合成页提示文案） */
    @Column(name = "description", length = 500)
    private String description = "每次合成音频消耗固定积分，积分不足请先充值。";

    /** 最后修改人（管理员用户名），便于审计 */
    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public CreditRule() {
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ---------- null 安全访问器 ----------
    // 后台可能只提交部分字段、老数据也可能存在 null，业务代码里直接对包装类型做
    // 布尔判断或算术运算会 NPE。所有判定逻辑统一走下面这些方法，缺值时回退到安全默认：
    // 开关类默认「按启用/最宽松」处理，数值类默认 0（即免费/不赠送），
    // 保证配置异常时不会误扣用户积分。

    @Transient
    public boolean getEnabledSafe() {
        return enabled == null || enabled;
    }

    @Transient
    public int getCreditsPerTaskSafe() {
        return creditsPerTask == null ? 0 : creditsPerTask;
    }

    @Transient
    public int getFreeCreditsOnRegisterSafe() {
        return freeCreditsOnRegister == null ? 0 : freeCreditsOnRegister;
    }

    @Transient
    public boolean getRefundOnTaskFailureSafe() {
        return refundOnTaskFailure == null || refundOnTaskFailure;
    }

    @Transient
    public boolean getAdminExemptSafe() {
        return adminExempt == null || adminExempt;
    }

    @Transient
    public int getDailyFreeTasksSafe() {
        return dailyFreeTasks == null ? 0 : dailyFreeTasks;
    }

    // ---------- Getters & Setters ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getCreditsPerTask() {
        return creditsPerTask;
    }

    public void setCreditsPerTask(Integer creditsPerTask) {
        this.creditsPerTask = creditsPerTask;
    }

    public Integer getFreeCreditsOnRegister() {
        return freeCreditsOnRegister;
    }

    public void setFreeCreditsOnRegister(Integer freeCreditsOnRegister) {
        this.freeCreditsOnRegister = freeCreditsOnRegister;
    }

    public Integer getDailyFreeTasks() {
        return dailyFreeTasks;
    }

    public void setDailyFreeTasks(Integer dailyFreeTasks) {
        this.dailyFreeTasks = dailyFreeTasks;
    }

    public Boolean getRefundOnTaskFailure() {
        return refundOnTaskFailure;
    }

    public void setRefundOnTaskFailure(Boolean refundOnTaskFailure) {
        this.refundOnTaskFailure = refundOnTaskFailure;
    }

    public Boolean getAdminExempt() {
        return adminExempt;
    }

    public void setAdminExempt(Boolean adminExempt) {
        this.adminExempt = adminExempt;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
