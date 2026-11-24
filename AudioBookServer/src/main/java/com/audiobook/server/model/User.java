package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(length = 100)
    private String email;

    /** 是否为管理员 */
    @Column(name = "is_admin", nullable = false)
    private Boolean isAdmin = false;

    /** 封禁截止时间；为 null 或已过去表示未被封禁 */
    @Column(name = "banned_until")
    private LocalDateTime bannedUntil;

    /**
     * 积分余额。合成音频按后台规则扣减，充值成功后增加。
     * 非空且默认 0，避免历史用户读出 null 后参与算术运算抛 NPE。
     */
    @Column(name = "credits", nullable = false)
    private Integer credits = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Token 版本号：改密 / 管理员重置密码时递增。
     * 签发的 JWT 携带该版本号，JwtAuthenticationFilter 每次请求比对库中版本，
     * 不一致即拒绝——密码一旦变更，此前签发的所有旧 token 立即全部失效。
     * DDL 带 default 0：dev 的 ddl-auto update 给存量行加列时不会因非空无默认值报错；
     * prod（ddl-auto validate）需先执行迁移：
     *   ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0;
     */
    @Column(name = "token_version", nullable = false, columnDefinition = "integer not null default 0")
    private Integer tokenVersion = 0;

    public User() {
    }

    public User(String username, String password, String email) {
        this.username = username;
        this.password = password;
        this.email = email;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 时间戳统一由 JPA 生命周期回调维护：
     * 默认构造函数创建的 User（如管理后台建号）落库后时间戳可能为空，
     * 改密/解封等更新路径也不会刷新 updated_at。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getIsAdmin() {
        return isAdmin;
    }

    public void setIsAdmin(Boolean isAdmin) {
        this.isAdmin = isAdmin;
    }

    public LocalDateTime getBannedUntil() {
        return bannedUntil;
    }

    public void setBannedUntil(LocalDateTime bannedUntil) {
        this.bannedUntil = bannedUntil;
    }

    public Integer getCredits() {
        return credits;
    }

    public void setCredits(Integer credits) {
        this.credits = credits;
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

    public Integer getTokenVersion() {
        // 防御存量行读出 null（迁移前导入的数据），统一按 0 处理
        return tokenVersion == null ? 0 : tokenVersion;
    }

    public void setTokenVersion(Integer tokenVersion) {
        this.tokenVersion = tokenVersion;
    }
}
