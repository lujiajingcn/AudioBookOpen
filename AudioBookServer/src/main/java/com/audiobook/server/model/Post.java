package com.audiobook.server.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

/**
 * 发现页帖子。
 *
 * 基础字段（username / content / createdAt）保持与旧版的兼容；扩展字段支持：
 * - title：可选标题（纯文字帖可留空）
 * - imageUrls：图片 URL 列表（JSON 字符串存储，发布时由 /api/posts/upload-image 上传得到）
 * - refAudioId：嵌入的「分享参考音频」id（可直接在帖子里分享好声音）
 * - mentions：@提醒的用户名列表（JSON 字符串存储，由正文里的 @用户名 解析得到）
 * - commentCount / favoriteCount：冗余计数，避免列表查询时 N+1
 *
 * username 冗余存储：发现页要按时间顺序列出帖子，若每次都 join users 表取用户名，
 * 列表查询会随帖子数线性变重。本项目不改用户名，以冗余换查询效率。
 */
@Entity
@Table(name = "posts", indexes = {
    @Index(name = "idx_post_created", columnList = "created_at"),
    // 内容审核按 status 过滤（前台只看 active，后台可切到 removed）走这条索引
    @Index(name = "idx_post_status_created", columnList = "status, created_at")
})
public class Post {

    /** 正常状态：前台发现页可见 */
    public static final String STATUS_ACTIVE = "active";
    /** 已下架：管理员判定违规后隐藏，数据保留、可恢复 */
    public static final String STATUS_REMOVED = "removed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    /** 帖子标题（可选）。纯文字/随手记可留空 */
    @Column(name = "title", length = 200)
    private String title;

    /** 帖子正文（纯文字，支持 @用户名 提及）。与 CreatePostRequest 的 @Size(max=500) 口径一致 */
    @Column(nullable = false, length = 500)
    private String content;

    /** 图片 URL 列表（JSON 字符串），如 ["/api/posts/image/xxx.jpg"]。无图则为 null */
    @Column(name = "image_urls", columnDefinition = "TEXT")
    private String imageUrls;

    /** 嵌入的分享参考音频 id（user_reference_audio 表 id）；未嵌入则为 null */
    @Column(name = "ref_audio_id")
    private Long refAudioId;

    /** @提醒的用户名列表（JSON 字符串）。无提及则为 null */
    @Column(name = "mentions", columnDefinition = "TEXT")
    private String mentions;

    /** 评论数（冗余计数，评论新增/删除时同步维护） */
    @Column(name = "comment_count")
    private Integer commentCount = 0;

    /** 收藏数（冗余计数，收藏/取消收藏时同步维护） */
    @Column(name = "favorite_count")
    private Integer favoriteCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 内容审核状态：{@code active}（正常）/ {@code removed}（管理员下架）。
     *
     * <p>下架走<b>逻辑删除</b>而非物理删除：内容审核是「先隐藏、留证据、必要时恢复」
     * 的动作，误判时能一键还原，也便于事后追溯是谁在何时下架了什么。
     * 真正要彻底清掉时走 {@code DELETE /api/admin/content/posts/{id}}。
     *
     * <p>用 varchar 而非 boolean：后续要加 {@code pending}（待审）等状态时不必再改列类型。
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    /** 管理员下架的时刻；未下架为 null。冗余存一份，便于「最近下架」排序与排查 */
    @Column(name = "removed_at")
    private LocalDateTime removedAt;

    public Post() {
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(String imageUrls) {
        this.imageUrls = imageUrls;
    }

    public Long getRefAudioId() {
        return refAudioId;
    }

    public void setRefAudioId(Long refAudioId) {
        this.refAudioId = refAudioId;
    }

    public String getMentions() {
        return mentions;
    }

    public void setMentions(String mentions) {
        this.mentions = mentions;
    }

    public Integer getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(Integer commentCount) {
        this.commentCount = commentCount;
    }

    public Integer getFavoriteCount() {
        return favoriteCount;
    }

    public void setFavoriteCount(Integer favoriteCount) {
        this.favoriteCount = favoriteCount;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getRemovedAt() {
        return removedAt;
    }

    public void setRemovedAt(LocalDateTime removedAt) {
        this.removedAt = removedAt;
    }

    /** 是否已被管理员下架 */
    public boolean isRemoved() {
        return STATUS_REMOVED.equals(status);
    }
}
