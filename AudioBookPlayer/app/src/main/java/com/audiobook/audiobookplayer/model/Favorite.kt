package com.audiobook.audiobookplayer.model

/**
 * 收藏的数据模型，对应 SQLite 中的 favorites 表。
 *
 * @param id           本地主键
 * @param bookName     所属书籍名称（对应 BookContentActivity 的 bookTableName/displayName）
 * @param lineNumber   被收藏内容所在段落的行号（对应 LineItem.lineNumber，上云时映射为服务端 segmentNumber）。
 *                     注意：行号是**切分粒度的函数**（行切分规则一变，同一本书的行数就变），
 *                     所以跳转定位以 [content]（原文片段）为准，
 *                     lineNumber 只作兜底与「多命中时选哪一个」的偏置（见 EbookSegmentMap.locateRowByText）。
 * @param content      被收藏的文本内容
 * @param createdAt    收藏时间（由数据库 CURRENT_TIMESTAMP 自动生成，可能为 null）
 * @param serverId     服务端收藏 id；null 表示尚未上云，下次同步会自动补传
 */
data class Favorite(
    val id: Long = 0L,
    val bookName: String,
    val lineNumber: Int,
    val content: String,
    val createdAt: String? = null,
    val serverId: Long? = null
)
