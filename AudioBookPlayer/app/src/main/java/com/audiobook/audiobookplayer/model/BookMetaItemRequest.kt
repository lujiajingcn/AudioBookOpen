package com.audiobook.audiobookplayer.model

/**
 * 每书元数据上报项（与服务端 `BookMetaItemRequest` 对齐，POST /api/book-meta/sync 的 items[]）。
 *
 * 身份是跨端 bookKey（`s:`/`t:`/`n:`，见 [com.audiobook.audiobookplayer.CoverageCodec]）。
 * 一本书一个 item，**只**带 [sortOrder] 与 [lastReadAt] 两个同步字段，单个 [updatedAt] 做 LWW。
 *
 * ⛔ 绝不同步 `category`（Android 手工分类 / PC 扩展名或书城固定值，同步会互相污染），
 * ⛔ 也不 INSERT/DELETE 书籍行 —— 服务端响应按 bookKey 匹配本机行后只 UPDATE 这两列。
 */
data class BookMetaItemRequest(
    val bookKey: String,
    val sortOrder: Int,
    /** 最近阅读时间（epoch 毫秒） */
    val lastReadAt: Long,
    val updatedAt: Long
)
