package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audio_segments", indexes = {
    @Index(name = "idx_segment_task_number", columnList = "task_id, segment_number")
})
public class AudioSegment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "segment_number", nullable = false)
    private Integer segmentNumber;

    @Column(name = "text_content", nullable = false, columnDefinition = "TEXT")
    private String textContent;

    @Lob
    @Column(name = "audio_data")
    private byte[] audioData;

    @Column(name = "audio_file_path", length = 500)
    private String audioFilePath;

    @Column(name = "duration_seconds")
    private Float durationSeconds;

    @Column(nullable = false, length = 20)
    private String status;

    /** 合成失败时的错误原因（供用户排查/重试，仅 failed 状态有意义） */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /**
     * 本段绑定的预设音色 voiceId（按段/按角色绑定不同音色）。
     * 为空表示沿用任务的默认音色 / 克隆参考音频。
     */
    @Column(name = "voice", length = 100)
    private String voice;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AudioSegment() {
        this.status = SegmentStatus.PENDING.value();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public AudioSegment(Long taskId, Integer segmentNumber, String textContent) {
        this.taskId = taskId;
        this.segmentNumber = segmentNumber;
        this.textContent = textContent;
        this.status = SegmentStatus.PENDING.value();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Integer getSegmentNumber() {
        return segmentNumber;
    }

    public void setSegmentNumber(Integer segmentNumber) {
        this.segmentNumber = segmentNumber;
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }

    public byte[] getAudioData() {
        return audioData;
    }

    public void setAudioData(byte[] audioData) {
        this.audioData = audioData;
    }

    public String getAudioFilePath() {
        return audioFilePath;
    }

    public void setAudioFilePath(String audioFilePath) {
        this.audioFilePath = audioFilePath;
    }

    public Float getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Float durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /** 枚举类型重载：推荐业务代码使用，编译期拦截非法状态 */
    public void setStatus(SegmentStatus status) {
        this.status = status != null ? status.value() : null;
    }

    /** 状态反解为枚举；未知值返回 null */
    public SegmentStatus getStatusEnum() {
        return SegmentStatus.of(status);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getVoice() {
        return voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
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
