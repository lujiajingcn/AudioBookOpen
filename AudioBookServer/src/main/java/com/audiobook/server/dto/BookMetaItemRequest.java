package com.audiobook.server.dto;

/**
 * 单本书的跨端元数据（POST /api/book-meta/sync 的 items 元素，也是响应元素）。
 *
 * <p>{@code lastReadAt} 是<b>毫秒 epoch</b>：Android 本地列本来就是这个单位；
 * PC 本地列是 TEXT ISO 串，由 PC 同步层转换后再上报，服务端不做单位猜测。
 *
 * <p>刻意不含 category —— 两端该列语义不同（Android 用户手工分类 / PC 文件类型），
 * 同步会互相污染，详见 {@code db/user_settings.sql} 顶部注释。
 */
public class BookMetaItemRequest {

    private String bookKey;
    private Integer sortOrder;
    private Long lastReadAt;
    private Long updatedAt;

    public BookMetaItemRequest() {
    }

    public BookMetaItemRequest(String bookKey, Integer sortOrder, Long lastReadAt, Long updatedAt) {
        this.bookKey = bookKey;
        this.sortOrder = sortOrder;
        this.lastReadAt = lastReadAt;
        this.updatedAt = updatedAt;
    }

    public String getBookKey() {
        return bookKey;
    }

    public void setBookKey(String bookKey) {
        this.bookKey = bookKey;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Long getLastReadAt() {
        return lastReadAt;
    }

    public void setLastReadAt(Long lastReadAt) {
        this.lastReadAt = lastReadAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
