package com.audiobook.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 每本书的跨端元数据（手动排序 + 最近阅读时间，LWW）。
 *
 * <p>身份沿用 {@code book_key}（{@code s:}/{@code t:}/{@code n:}），
 * 与 {@link UserBookProgress} / {@code UserAudioCoverage} 同一套口径，
 * 三端由 {@link com.audiobook.server.util.BookKeys}、
 * Android {@code CoverageCodec.bookKey}、PC {@code coverage.book_key} 逐字符一致地算出。
 *
 * <p><b>刻意不含 category</b>：两端 books/ebooks 表都有该列但语义不同 ——
 * Android 是用户手工分类，PC 是文件扩展名 / 书城固定值且会被书架同步覆盖。
 * 同步它会把 {@code pdf}/{@code store} 写进用户在手机上设的分类。
 *
 * <p><b>lastReadAt 是毫秒 epoch</b>：Android 本地列本身就是 INTEGER 毫秒；
 * PC 本地列是 TEXT ISO 串（还用于 ORDER BY），由 PC 同步层负责 ISO ↔ 毫秒转换，
 * 服务端与两端都不要在别处再做一次单位换算。
 */
@Entity
@Table(name = "user_book_meta",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ubm_user_book", columnNames = {"user_id", "book_key"}),
        indexes = {
                @Index(name = "idx_ubm_user", columnList = "user_id")
        })
public class UserBookMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "book_key", nullable = false, length = 255)
    private String bookKey;

    /** 书架手动排序，越大越靠前（两端 ORDER BY sort_order DESC 一致） */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 最近阅读时间，毫秒 epoch */
    @Column(name = "last_read_at", nullable = false)
    private long lastReadAt;

    /** LWW 单调版本号（毫秒 epoch，由服务端裁决） */
    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public UserBookMeta() {
    }

    public UserBookMeta(Long userId, String bookKey, int sortOrder, long lastReadAt, long updatedAt) {
        this.userId = userId;
        this.bookKey = bookKey;
        this.sortOrder = sortOrder;
        this.lastReadAt = lastReadAt;
        this.updatedAt = updatedAt;
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

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public long getLastReadAt() {
        return lastReadAt;
    }

    public void setLastReadAt(long lastReadAt) {
        this.lastReadAt = lastReadAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
