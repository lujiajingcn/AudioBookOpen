package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tasks", indexes = {
    @Index(name = "idx_task_status", columnList = "status"),
    @Index(name = "idx_task_user_created", columnList = "user_id, created_at")
})
public class Task {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "book_file_path", nullable = false, length = 500)
    private String bookFilePath;

    /**
     * 参考音频路径。可为 null：仅指定预设音色（voice_id）、不上传参考音频时无需该字段，
     * 合成走「预设音色」通道（见 VoiceCloningTtsService 的 segVoice 分支），不再依赖克隆参考音频。
     */
    @Column(name = "reference_audio_path", length = 500)
    private String referenceAudioPath;

    @Column(name = "original_book_name", length = 500)
    private String originalBookName;

    /**
     * 本任务对应的书城书籍 id（store_books.id）；用户自行上传的书为 null。
     *
     * <p>为什么存在 tasks 上而不是反查 store_books：书城书与任务的绑定时序是
     * 「先建任务 → 再条件 UPDATE 回写 store_books.task_id」，异步工作线程可能在回写**之前**
     * 就开始提取正文；靠反查会拿不到书 id，快照就漏了。写在本行上没有这个竞态。
     */
    @Column(name = "store_book_id")
    private Long storeBookId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "total_segments")
    private Integer totalSegments;

    @Column(name = "completed_segments")
    private Integer completedSegments;

    /**
     * 本次合成实际扣除的积分（创建任务时按后台规则一次性扣除）。
     * 记录在任务上，任务整体失败退款时以此为准，避免规则被后台改动后退错金额。
     */
    @Column(name = "credits_cost", nullable = false)
    private Integer creditsCost = 0;

    /** 已退还的积分，用于幂等：> 0 表示已退过，不再重复退款 */
    @Column(name = "credits_refunded", nullable = false)
    private Integer creditsRefunded = 0;

    /** 任务级失败原因（如「积分不足」「文本提取失败」），供前端直接展示 */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /**
     * 任务级默认音色（预设 voiceId）。为空表示整本沿用克隆参考音频。
     * 支持段落级音色覆盖时，作为未单独指定段落的兜底音色。
     */
    @Column(name = "default_voice_id", length = 100)
    private String defaultVoiceId;

    /**
     * 段落级音色映射（JSON：{段落号: voiceId}），按段/按角色绑定不同音色用。
     * 为空表示全部段落沿用 defaultVoiceId / 克隆参考音频。
     */
    @Column(name = "segment_voices", columnDefinition = "TEXT")
    private String segmentVoices;

    /**
     * 书级自动识别出的语种代码（{@code zh/en/fr/de/es/it/pt/nl/other}），由正文首次解析时
     * {@link com.audiobook.server.util.LanguageDetector#detect(String)} 得到并持久化。
     *
     * <p>用途：合成时据此选引擎——中文/other 走 MOSS，Pocket 支持的 7 门拉丁语（且运营已配
     * 对应音色的）走 Pocket。书级识别一次即稳定复用，避免逐句投票抖动。
     *
     * <p>可空是刻意的：{@code ddl-auto=update} 给历史行加列即 NULL，代码把「未识别」当
     * MOSS 处理，因此升级本字段对存量任务完全无副作用；重跑或续跑时若为 null 会即时补识别。
     */
    @Column(name = "language", length = 16)
    private String language;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Task() {
        this.status = TaskStatus.PENDING.value();
        this.totalSegments = 0;
        this.completedSegments = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Task(Long userId, String bookFilePath, String referenceAudioPath) {
        this.userId = userId;
        this.bookFilePath = bookFilePath;
        this.referenceAudioPath = referenceAudioPath;
        this.status = TaskStatus.PENDING.value();
        this.totalSegments = 0;
        this.completedSegments = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 时间戳统一由 JPA 生命周期回调维护：
     * 各写库路径手工 setUpdatedAt 时，漏一处 updated_at 就与真实修改时间脱节。
     * 构造函数里的初始化保留，仅用于「构造后、落库前」读 DTO 的场景；
     * 落库时以本回调写入的值为准。
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
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

    public String getBookFilePath() {
        return bookFilePath;
    }

    public void setBookFilePath(String bookFilePath) {
        this.bookFilePath = bookFilePath;
    }

    public String getReferenceAudioPath() {
        return referenceAudioPath;
    }

    public void setReferenceAudioPath(String referenceAudioPath) {
        this.referenceAudioPath = referenceAudioPath;
    }

    public String getOriginalBookName() {
        return originalBookName;
    }

    public void setOriginalBookName(String originalBookName) {
        this.originalBookName = originalBookName;
    }

    public Long getStoreBookId() {
        return storeBookId;
    }

    public void setStoreBookId(Long storeBookId) {
        this.storeBookId = storeBookId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /** 枚举类型重载：推荐业务代码使用，编译期拦截非法状态 */
    public void setStatus(TaskStatus status) {
        this.status = status != null ? status.value() : null;
    }

    /** 状态反解为枚举；历史脏数据/未知值返回 null，调用方需容错 */
    public TaskStatus getStatusEnum() {
        return TaskStatus.of(status);
    }

    public Integer getTotalSegments() {
        return totalSegments;
    }

    public void setTotalSegments(Integer totalSegments) {
        this.totalSegments = totalSegments;
    }

    public Integer getCompletedSegments() {
        return completedSegments;
    }

    public void setCompletedSegments(Integer completedSegments) {
        this.completedSegments = completedSegments;
    }

    public Integer getCreditsCost() {
        return creditsCost;
    }

    public void setCreditsCost(Integer creditsCost) {
        this.creditsCost = creditsCost;
    }

    public Integer getCreditsRefunded() {
        return creditsRefunded;
    }

    public void setCreditsRefunded(Integer creditsRefunded) {
        this.creditsRefunded = creditsRefunded;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getDefaultVoiceId() {
        return defaultVoiceId;
    }

    public void setDefaultVoiceId(String defaultVoiceId) {
        this.defaultVoiceId = defaultVoiceId;
    }

    public String getSegmentVoices() {
        return segmentVoices;
    }

    public void setSegmentVoices(String segmentVoices) {
        this.segmentVoices = segmentVoices;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
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
