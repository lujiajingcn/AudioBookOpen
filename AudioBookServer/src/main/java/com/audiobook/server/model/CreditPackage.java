package com.audiobook.server.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充值套餐（积分商品）。由管理员在后台维护，用户下单时按套餐快照写入订单。
 *
 * 到账积分 = {@link #credits} + {@link #bonusCredits}（赠送部分单独列出，便于前端展示「买 100 送 20」）。
 */
@Entity
@Table(name = "credit_packages")
public class CreditPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 套餐名称，如「基础包」 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 基础积分数 */
    @Column(name = "credits", nullable = false)
    private Integer credits;

    /** 赠送积分数 */
    @Column(name = "bonus_credits", nullable = false)
    private Integer bonusCredits = 0;

    /** 售价（元），精确到分 */
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** 套餐说明 */
    @Column(name = "description", length = 500)
    private String description;

    /** 是否上架；下架后用户端不可见、不可下单 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 展示排序，越小越靠前 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public CreditPackage() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 实际到账积分（基础 + 赠送） */
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
}
