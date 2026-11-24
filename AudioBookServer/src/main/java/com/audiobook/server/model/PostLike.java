package com.audiobook.server.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 帖子点赞（用户维度）。
 *
 * <p><b>为什么另建一张表，而不复用 {@link PostFavorite}</b>：收藏与点赞是两个动作，
 * 语义不同 —— 收藏是「存起来以后看」（列表在「我的收藏」里，用户会回来找），
 * 点赞是「表个态」（不产生任何列表）。两者共表的话，「我的收藏」里会混进
 * 一堆只为点赞的帖子，而取消点赞又会把收藏一起删掉。
 *
 * <p><b>为什么不像 {@code posts.favorite_count} 那样冗余一个 like_count 列</b>：
 * 冗余计数需要 `+1` 的原子 UPDATE 加一次回写，一旦有并发或异常回滚不同步，
 * 计数就会永久偏移（本工程的 favoriteCount 就吃过 lost update 的补丁）。
 * 点赞数直接从本表 COUNT 出来，只有一个事实来源；发现页一次最多 200 条，
 * 按 post_id IN (...) 批量取一次即可，不是 N+1。
 */
@Entity
@Table(name = "post_likes", uniqueConstraints = {
    @UniqueConstraint(name = "uk_like_user_post", columnNames = {"user_id", "post_id"})
})
public class PostLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public PostLike() {
    }

    public PostLike(Long userId, Long postId) {
        this.userId = userId;
        this.postId = postId;
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

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
