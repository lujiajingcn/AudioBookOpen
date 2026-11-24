package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 临时参考音频归属登记（仅落盘、不入「我的库」）。
 * /api/tts/upload-reference 为批量合成避免重复上传，只把参考音频保存到 uploads/ 目录、
 * 不写 user_reference_audio；这里单独登记「文件名 → 归属用户」，
 * 供 Controller.resolveReferenceFileName 校验第三种来源（临时上传文件）时防越权引用。
 */
@Entity
@Table(name = "temp_reference_audio", indexes = {
    @Index(name = "idx_temp_ref_audio_user", columnList = "user_id, created_at")
})
public class TempReferenceAudio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 上传目录下的相对文件名（与 Task.referenceAudioPath / UserReferenceAudio.fileName 同语义） */
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public TempReferenceAudio() {
        this.createdAt = LocalDateTime.now();
    }

    public TempReferenceAudio(Long userId, String fileName) {
        this();
        this.userId = userId;
        this.fileName = fileName;
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

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
