package com.audiobook.server.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 积分规则更新表单（替代直接把 {@code CreditRule} 实体当 @RequestBody）。
 *
 * 规则是单行配置、走「传什么改什么」的部分更新语义，所以字段全部用包装类型、
 * 不设 {@code @NotNull}（缺省即不改）。范围类约束（{@code @Min}）只在字段被提交时才校验，
 * 不会破坏部分更新。{@code @Valid} 由 {@code AdminCreditController} 触发。
 */
public class CreditRuleForm {

    private Boolean enabled;

    @Min(0)
    private Integer creditsPerTask;

    @Min(0)
    private Integer freeCreditsOnRegister;

    private Boolean refundOnTaskFailure;

    private Boolean adminExempt;

    /**
     * 每日免费试用次数。
     *
     * DB（credit_rule.daily_free_tasks）、实体（CreditRule.dailyFreeTasks）与
     * CreditService 里的校验/赋值三处都有这项配置，但表单原先没有对应字段——
     * toRuleEntity 是逐字段拷贝，无字段可拷即静默忽略，导致后台「积分规则」页面
     * 永远改不了每日免费额度（只能直接改数据库），且没有任何报错提示。
     */
    @Min(0)
    private Integer dailyFreeTasks;

    @Size(max = 500, message = "规则说明不能超过 500 字")
    private String description;

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

    public Boolean getRefundOnTaskFailure() {
        return refundOnTaskFailure;
    }

    public void setRefundOnTaskFailure(Boolean refundOnTaskFailure) {
        this.refundOnTaskFailure = refundOnTaskFailure;
    }

    public Integer getDailyFreeTasks() {
        return dailyFreeTasks;
    }

    public void setDailyFreeTasks(Integer dailyFreeTasks) {
        this.dailyFreeTasks = dailyFreeTasks;
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
}
