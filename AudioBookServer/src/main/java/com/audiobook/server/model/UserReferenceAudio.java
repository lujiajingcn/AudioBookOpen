package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户级参考音频（音色克隆素材）。
 * 与管理员共享库 ReferenceAudio 不同，本实体按 userId 隔离，供普通用户上传一次、多次复用。
 */
@Entity
@Table(name = "user_reference_audio", indexes = {
    @Index(name = "idx_user_ref_audio_user", columnList = "user_id, created_at"),
    // 分享排行榜：WHERE shared = 1 ORDER BY use_count DESC
    @Index(name = "idx_user_ref_audio_rank", columnList = "shared, use_count")
})
public class UserReferenceAudio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 展示名称（用户自定义或原始文件名） */
    @Column(nullable = false, length = 200)
    private String name;

    /** 上传目录下的相对文件名（复用 Task.referenceAudioPath 的语义，可直接作为 reference_id） */
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /** 音频描述（用户自定义，可选） */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "file_size")
    private Long fileSize;

    /**
     * 是否已分享到「发现 - 参考音频」排行榜。
     * 分享后所有登录用户可见，并可下载到自己的参考音频库使用。
     *
     * <p>这是<b>用户自己的开关</b>，与 {@link #adminHidden} 正交，两者不可互相替代。
     */
    @Column(name = "shared", nullable = false)
    private Boolean shared = Boolean.FALSE;

    /**
     * 是否被管理员从分享列表强制隐藏（内容审核下架）。
     *
     * <p><b>为什么不能复用 {@link #shared}</b>：{@code shared} 表达的是「用户想不想分享」，
     * {@code adminHidden} 表达的是「管理员判定内容违规」。把两者混在一个字段上会出现
     * 这种错误：用户被下架后又手动取消再重新分享（shared 回到 true），违规内容就重新
     * 出现在排行榜里 —— 管理员的处置被用户的一次点击撤销了。
     *
     * <p>分开后：管理员下架只改 {@code adminHidden}，用户再点分享也仍然对前台隐藏，
     * 必须由管理员显式取消下架才会恢复。
     *
     * <p>DB 里是 {@code bit(1)}，用 JDBC 查它必须写 {@code admin_hidden + 0}，
     * 直接 {@code WHERE admin_hidden = 1} 不会命中（MySQL 位字段与数字比较的经典坑）。
     */
    @Column(name = "admin_hidden", nullable = false)
    private Boolean adminHidden = Boolean.FALSE;

    /**
     * 使用量：被其他用户「使用」（下载进自己的参考音频库）的次数。
     * 排行榜按此字段倒序，同分时分享早的在前。
     */
    @Column(name = "use_count", nullable = false)
    private Long useCount = 0L;

    /** 最近一次开启分享的时间；取消分享不清空，便于排序与审计 */
    @Column(name = "shared_at")
    private LocalDateTime sharedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public UserReferenceAudio() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    /** 兼容历史代码：null 视为未分享 */
    public boolean isShared() {
        return Boolean.TRUE.equals(shared);
    }

    public Boolean getShared() {
        return shared;
    }

    public void setShared(Boolean shared) {
        this.shared = shared;
    }

    /** 兼容历史代码：null 视为未被管理员下架 */
    public boolean isAdminHidden() {
        return Boolean.TRUE.equals(adminHidden);
    }

    public Boolean getAdminHidden() {
        return adminHidden;
    }

    public void setAdminHidden(Boolean adminHidden) {
        this.adminHidden = adminHidden;
    }

    /**
     * 是否应出现在「发现 - 参考音频分享」列表里。
     * 用户自己分享了、且没被管理员下架 —— 两个条件缺一不可。
     */
    public boolean isVisibleInShareList() {
        return isShared() && !isAdminHidden();
    }

    public Long getUseCount() {
        return useCount;
    }

    public void setUseCount(Long useCount) {
        this.useCount = useCount;
    }

    public LocalDateTime getSharedAt() {
        return sharedAt;
    }

    public void setSharedAt(LocalDateTime sharedAt) {
        this.sharedAt = sharedAt;
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
