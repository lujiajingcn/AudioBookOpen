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
 * 跨端阅读进度（按 <b>book_key</b> 身份存，而非 task_id）。
 *
 * <p><b>为什么不用 task_id</b>：老表 {@link ReadingProgress} 以
 * {@code (user_id, task_id)} 为键，而 {@code task_id} 有两个硬伤：
 * <ul>
 *   <li><b>无音频的书没有 task_id</b>：书城书若从未在服务端合成，
 *       {@code store_books.task_id IS NULL}，进度<b>根本无法保存</b>
 *       （老书级端点直接返回 400「该书尚未合成音频，无阅读进度可保存」）。
 *       而「纯阅读」正是阅读器最基础的场景 —— 用户只是想记住读到哪一行。</li>
 *   <li><b>task_id 不是书的身份</b>：它指向某个<b>合成任务</b>，
 *       而任务可能属于管理员（书城书的音频常是管理员合成的）。
 *       普通用户拿它调私有端点 {@code /api/progress} 会因归属校验 403，
     *       进度永远传不上去；书一旦重新合成、task_id 一换，进度也随之孤立。</li>
 * </ul>
 *
 * <p><b>为什么用 book_key</b>：{@code book_key} 是本工程<b>已经验证过的跨端身份口径</b>
 * —— 有声覆盖图（{@code user_audio_coverage}）就用它，三端逐字符一致
 * （服务端 {@link com.audiobook.server.util.BookKeys}、
 * Android {@code CoverageCodec.bookKey}、PC {@code coverage.book_key}）：
 * <ul>
 *   <li>{@code s:{storeBookId}} —— 书城条目（与是否合成无关）；</li>
 *   <li>{@code t:{serverTaskId}} —— 在线合成任务；</li>
 *   <li>{@code n:{normalizeName(书名)}} —— 本地导入书（文件名/书名归一化）。</li>
 * </ul>
 * 它不依赖「这本书有没有被合成」，也不依赖「任务归谁」，因此
 * <b>同一本书在三端必然算出同一个 key</b>，进度才能真正互通。
 *
 * <p><b>与老表的关系</b>：本表是<b>新增</b>的并行存储，老表 {@code reading_progress}
 * 保持不动（老客户端仍在用它的 {@code /api/progress} 端点）。
 * 两张表语义不同、互不覆盖；老端点的写入会由服务端额外镜像到本表，
 * 让新老客户端看到同一进度。
 *
 * <p><b>位置语义</b>：{@code segment_number} 是 1-based 段号。
 * 对有音频的书它就是音频段号；对无音频的书它是<b>正文段落序号</b>
 * （服务端 {@code /content} 返回的 {@code paragraphs} 下标 +1），
 * 客户端据此定位到正文行。两类书共用一个字段，因为它们的「读到哪里了」
 * 都是「第几个自然段」，只是有音频时恰好还等于音频段号。
 */
@Entity
@Table(name = "user_book_progress",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ubp_user_book", columnNames = {"user_id", "book_key"}),
        indexes = {
                @Index(name = "idx_ubp_user_book", columnList = "user_id, book_key")
        })
public class UserBookProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 跨端书名身份键（含前缀 s:/t:/n:），口径同 {@code user_audio_coverage.book_key} */
    @Column(name = "book_key", nullable = false, length = 255)
    private String bookKey;

    /** 1-based 位置：音频段号（有音频）或正文段落序号（无音频） */
    @Column(name = "segment_number", nullable = false)
    private int segmentNumber;

    /** 段内偏移（毫秒）；纯阅读恒为 0 */
    @Column(name = "offset_ms", nullable = false)
    private int offsetMs;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public UserBookProgress() {
    }

    public UserBookProgress(Long userId, String bookKey, int segmentNumber, int offsetMs) {
        this.userId = userId;
        this.bookKey = bookKey;
        this.segmentNumber = segmentNumber;
        this.offsetMs = offsetMs;
    }

    /** 时间戳统一由 JPA 生命周期回调维护，避免多处手工 setUpdatedAt 脱节。 */
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

    public String getBookKey() {
        return bookKey;
    }

    public void setBookKey(String bookKey) {
        this.bookKey = bookKey;
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
