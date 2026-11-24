package com.audiobook.server.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 充值套餐创建/更新表单（替代直接把 {@code CreditPackage} 实体当 @RequestBody）。
 *
 * 创建与更新共用一个表单：创建时由 {@code CreditPackageService.create} 强制校验必填项；
 * 更新时走「传什么改什么」部分更新，所以必填字段不设 {@code @NotNull}。
 * 范围类约束（{@code @Min}/{@code @DecimalMin}）只在字段被提交时才生效，不破坏部分更新。
 */
public class CreditPackageForm {

    @Size(max = 100, message = "套餐名称不能超过 100 字")
    private String name;

    @Min(1)
    private Integer credits;

    @Min(0)
    private Integer bonusCredits;

    @DecimalMin("0.01")
    private BigDecimal price;

    @Size(max = 500, message = "套餐说明不能超过 500 字")
    private String description;

    private Boolean enabled;

    @Min(0)
    private Integer sortOrder;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
