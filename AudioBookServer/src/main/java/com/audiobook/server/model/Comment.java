package com.audiobook.server.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

/**
 * 帖子评论（支持对帖子的评论，以及对评论的嵌套回复）。
 *
 * parentId 为 null 表示一级评论；非 null 表示对另一条评论的回复。
 * parentUsername 冗余存储被回复者的用户名，便于前端直接展示「回复 @xxx」。
 */
@Entity
@Table(name = "comments", indexes = {
    @Index(name = "idx_comment_post", columnList = "post_id"),
    @Index(name = "idx_comment_parent", columnList = "parent_id")
})
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 500)
    private String content;

    /** 父评论 id；为 null 表示一级评论 */
    @Column(name = "parent_id")
    private Long parentId;

    /** 被回复者用户名（冗余，便于展示「回复 @xxx」）；一级评论为 null */
    @Column(name = "parent_username", length = 100)
    private String parentUsername;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public Comment() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getParentUsername() {
        return parentUsername;
    }

    public void setParentUsername(String parentUsername) {
        this.parentUsername = parentUsername;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
