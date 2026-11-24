package com.audiobook.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 参考音频主题。
 *
 * <p>用于把一批同源的参考音频归到一起管理，典型场景是「某部电视剧/电影」：
 * 同一个主题下挂着各个角色的台词片段（同一剧集里的不同音色）。
 *
 * <p>与 {@link ReferenceAudio#topicId} 的关系刻意<b>不建数据库外键</b>，与本仓
 * {@code shelf_books.store_book_id}、{@code posts.ref_audio_id} 保持同一风格：
 * 外键会让「删主题」与「删音频」互相纠缠（级联删音频会丢素材，
 * 限制删主题又会因历史脏数据卡死），而这里的主题只是一个可空的分类标签，
 * 校验放在 Service 层做，语义更明确也更易演进（后续要加「主题软删除」也不用改表）。
 */
@Entity
@Table(name = "reference_audio_topic",
        uniqueConstraints = @UniqueConstraint(name = "uk_ref_topic_name", columnNames = {"name"}),
        indexes = @Index(name = "idx_ref_topic_sort", columnList = "sort_order, id"))
public class ReferenceAudioTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 主题名称（展示用），全局唯一 */
    @Column(nullable = false, length = 200)
    private String name;

    /** 主题描述（如「电视剧雍正王朝 - 角色台词」） */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 排序值，越小越靠前；为 null 时按 0 处理，避免历史数据排到末尾 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    void preUpdate() {
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

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
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
