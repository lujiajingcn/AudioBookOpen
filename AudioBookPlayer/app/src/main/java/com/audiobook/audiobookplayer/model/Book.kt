package com.audiobook.audiobookplayer.model

data class Book(
    val id: Int = 0,
    val tableName: String,
    val displayName: String,
    val filePath: String = "",
    val hasAudio: Boolean = false,
    /** 最近一次打开阅读的时间戳（epoch millis），0 表示从未打开；用于书架「最近阅读」排序 */
    val lastReadAt: Long = 0L,
    /** 分类标签（如「本地 / 书城 / URL / 未分类」或用户自定义）；空串视为「未分类」 */
    val category: String = "",
    /**
     * 书架顺序（**值越大越靠前**）。
     *
     * 由「长按拖放调整书籍顺序」写入；新导入的书取 MAX+1 排在最前，
     * 存量记录为 0（相同）时按导入时间兜底，排序口径见 BookStorage.SHELF_ORDER_BY。
     */
    val sortOrder: Int = 0,
    /**
     * 书城书籍 id（ebooks.store_book_id）。`> 0` 表示这本书来自书城，可走
     * `GET api/store/book/{id}/content` 在线取正文；`-1`（默认）= 手工导入或旧数据未回填。
     * 书架跨端同步据此判定「服务端在架的书本机是否已有」以及「本机书城占位条目是否该清理」。
     */
    val storeBookId: Long = -1L
)