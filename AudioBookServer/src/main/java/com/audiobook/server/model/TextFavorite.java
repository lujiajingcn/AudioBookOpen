package com.audiobook.server.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 听书（正文）收藏（用户维度，跨端同步）。
 *
 * 与讨论区的 {@link PostFavorite}（收藏帖子）不同：这里收藏的是「某本书第 N 段正文」，
 * 即 Web 播放器 / Android 阅读页的「收藏本段」。
 *
 * 唯一身份 = (user_id, book_name, segment_number)：
 * 同一用户对「同一本书的同一段」只应有一条收藏。taskId 只是「去听」深链的辅助字段，
 * 不参与去重——Web 端收藏时能拿到 taskId、Android 端拿不到，若把 taskId 纳入唯一键，
 * 两端对「同一段」会各存一条、跨端永远去不掉重。故 taskId 可空且可被回填。
 */
@Entity
@Table(name = "text_favorites", uniqueConstraints = {
    @UniqueConstraint(name = "uk_text_fav_user_book_seg",
        columnNames = {"user_id", "book_name", "segment_number"})
})
public class TextFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 所属书籍名（Web 端为路由 query.name；Android 端为书本 displayName） */
    @Column(name = "book_name", nullable = false, length = 255)
    private String bookName;

    /**
     * 该收藏所属的 TTS 任务 id（可空）。
     * 有值时可从「我的收藏」一键跳回播放页对应任务；Android 端收藏时无此信息，留空。
     * 同一 (book, segment) 若先由 Android 端创建（无 taskId），后由 Web 端再次收藏（有 taskId），
     * 服务端会把 taskId 回填到已有记录上，使「去听」深链可用，而不是新增一条重复收藏。
     */
    @Column(name = "task_id")
    private Long taskId;

    /** 段落号（1-based，与 AudioSegment.segmentNumber / Android 端 lineNumber 对应） */
    @Column(name = "segment_number", nullable = false)
    private int segmentNumber;

    /** 被收藏的正文内容 */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public TextFavorite() {
    }

    public TextFavorite(Long userId, String bookName, Integer segmentNumber, String content, Long taskId) {
        this.userId = userId;
        this.bookName = bookName;
        // 段号是 1-based 的身份组成部分（唯一键 user_id + book_name + segment_number）。
        // 原先的 null → 0 静默兜底会写入「第 0 段」这种非法身份，与「第 1 段」在唯一键上
        // 不相等，跨端同步/去重永远合并不掉，留下两行指向同一段的收藏。这里显式拒绝：
        // 入口 DTO（FavoriteItemRequest）已有 @NotNull + @Min(1)，正常路径不会触发。
        if (segmentNumber == null || segmentNumber < 1) {
            throw new IllegalArgumentException("段落号必须从 1 开始: " + segmentNumber);
        }
        this.segmentNumber = segmentNumber;
        this.content = content;
        this.taskId = taskId;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
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

    public String getBookName() {
        return bookName;
    }

    public void setBookName(String bookName) {
        this.bookName = bookName;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public int getSegmentNumber() {
        return segmentNumber;
    }

    public void setSegmentNumber(int segmentNumber) {
        this.segmentNumber = segmentNumber;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
