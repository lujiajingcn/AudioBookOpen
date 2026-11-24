package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 参考音频实体。
 * 由管理员上传后入库，普通用户可在 TTS 合成时引用。
 */
@Entity
@Table(name = "reference_audio")
public class ReferenceAudio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 音频名称（展示用） */
    @Column(nullable = false, length = 200)
    private String name;

    /** 音频描述 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 文件名（上传目录下的相对路径） */
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /**
     * 所属主题 id（{@code reference_audio_topic.id}），可为空表示「未归类」。
     * 存主键而非主题名快照：主题允许改名，用 id 才能保证归属判定稳定。
     */
    @Column(name = "topic_id")
    private Long topicId;

    /** 文件大小（字节） */
    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ReferenceAudio() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ---------- Getters & Setters ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getTopicId() {
        return topicId;
    }

    public void setTopicId(Long topicId) {
        this.topicId = topicId;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
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
