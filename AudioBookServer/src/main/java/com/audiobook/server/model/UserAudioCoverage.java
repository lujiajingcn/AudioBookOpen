package com.audiobook.server.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 用户「有声覆盖图」（Phase3 跨端同步，**仅元数据、不含音频字节**）。
 *
 * <p>一行 = 某用户在某本书上、某一来源（{@code server}/{@code local}）的"哪些段已合成"的
 * 全局感知记录。身份口径 = {@code (user_id, book_key, source)}：
 * <ul>
 *   <li>{@code book_key} 由客户端按统一规则生成并保证三端逐字符一致：
 *       {@code s:{storeBookId}}（书城）/ {@code t:{serverTaskId}}（在线任务）/ {@code n:{书名}}（纯本地）。</li>
 *   <li>{@code source}：{@code server}=音频在服务端（书城音频 / 在线合成完成段），任意登录设备可经
 *       现有下载/流式接口取到 → 跨端真能播；{@code local}=音频仅在某端本地合成落盘、服务端无字节 →
 *       只作"另一设备/历史已合成"提示与断点续合，**不宣称可播**。</li>
 * </ul>
 *
 * <p>{@code done_ranges} 是 1-based 闭区间的压缩串（{@code "1-40,41,45-50"}，单点写 {@code "41"}），
 * 合并语义 = 同 {@code (bookKey, source)} 跨设备做**并集、只增不减**（v1 无删除）。服务端只当字符串
 * 搬运并按 {@link com.audiobook.server.service.AudioCoverageService} 的规则并集，客户端负责 encode/decode
 * （Java/Python 用同一算法，输入相同则输出串完全一致）。
 *
 * <p>本表**不存音频**，与 {@code audio_segments} 无任何外键/字节关系；它是纯元数据感知层。
 */
@Entity
@Table(name = "user_audio_coverage", uniqueConstraints = {
    @UniqueConstraint(name = "uk_cov_user_book_src",
        columnNames = {"user_id", "book_key", "source"})
}, indexes = {
    @Index(name = "idx_cov_user_updated", columnList = "user_id, updated_at")
})
public class UserAudioCoverage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 跨端书名身份键（含前缀 s:/t:/n:），客户端生成、三端一致 */
    @Column(name = "book_key", nullable = false, length = 255)
    private String bookKey;

    /** 来源：server（服务端可下）/ local（仅本地合成） */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    /** 映射回书城条目（可空），便于 UI 直接定位 */
    @Column(name = "store_book_id")
    private Long storeBookId;

    /** 映射回在线合成任务（可空），便于 UI 直接定位 */
    @Column(name = "server_task_id")
    private Long serverTaskId;

    /** 该书总段数（取各端上报的最大值），仅用于展示/进度，不作可播判定 */
    @Column(name = "total_segments")
    private Integer totalSegments;

    /** 已完成段覆盖串（1-based 闭区间，逗号分隔，升序合并） */
    @Column(name = "done_ranges", columnDefinition = "TEXT")
    private String doneRanges;

    /** done_ranges 的段数冗余，便于排序/展示 */
    @Column(name = "done_count")
    private Integer doneCount;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public UserAudioCoverage() {
    }

    public UserAudioCoverage(Long userId, String bookKey, String source) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (bookKey == null || bookKey.isEmpty()) {
            throw new IllegalArgumentException("bookKey 不能为空");
        }
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("source 不能为空");
        }
        this.userId = userId;
        this.bookKey = bookKey;
        this.source = source;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
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

    public String getBookKey() {
        return bookKey;
    }

    public void setBookKey(String bookKey) {
        this.bookKey = bookKey;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getStoreBookId() {
        return storeBookId;
    }

    public void setStoreBookId(Long storeBookId) {
        this.storeBookId = storeBookId;
    }

    public Long getServerTaskId() {
        return serverTaskId;
    }

    public void setServerTaskId(Long serverTaskId) {
        this.serverTaskId = serverTaskId;
    }

    public Integer getTotalSegments() {
        return totalSegments;
    }

    public void setTotalSegments(Integer totalSegments) {
        this.totalSegments = totalSegments;
    }

    public String getDoneRanges() {
        return doneRanges;
    }

    public void setDoneRanges(String doneRanges) {
        this.doneRanges = doneRanges;
    }

    public Integer getDoneCount() {
        return doneCount;
    }

    public void setDoneCount(Integer doneCount) {
        this.doneCount = doneCount;
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
