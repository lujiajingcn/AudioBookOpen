package com.audiobook.server.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

/**
 * 帖子收藏（用户维度）。
 *
 * 一个用户对同一帖子只应有一条收藏记录，用唯一约束 (user_id, post_id) 兜底，
 * 避免重复收藏导致计数与列表出现重复。
 */
@Entity
@Table(name = "post_favorites", uniqueConstraints = {
    @UniqueConstraint(name = "uk_fav_user_post", columnNames = {"user_id", "post_id"})
})
public class PostFavorite {

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

    public PostFavorite() {
    }

    public PostFavorite(Long userId, Long postId) {
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
