package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 播放进度（跨端同步）。
 *
 * 以 (user_id, task_id) 为唯一键：task_id 对应服务端 TTS 任务 Task.id，
 * 亦即 Android 端 tasks 表的 server_task_id。同一账号、同一 TTS 任务可在多端续听。
 */
@Entity
@Table(name = "reading_progress", indexes = {
    @Index(name = "idx_rp_user_task", columnList = "user_id, task_id", unique = true)
})
public class ReadingProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 当前播放到的段落号（1-based，与 Android 端 seg_ 进度键一致） */
    @Column(name = "segment_number", nullable = false)
    private int segmentNumber;

    /** 段内播放偏移（毫秒） */
    @Column(name = "offset_ms", nullable = false)
    private int offsetMs;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ReadingProgress() {
    }

    /**
     * 时间戳统一由 JPA 生命周期回调维护（与 Task 实体一致）：
     * 落库时以本回调写入的值为准，避免多处手工 setUpdatedAt 脱节。
     */
    @PrePersist
    void prePersist() {
        this.updatedAt = LocalDateTime.now();
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public int getOffsetMs() {
        return offsetMs;
    }

    public void setOffsetMs(int offsetMs) {
        this.offsetMs = offsetMs;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
