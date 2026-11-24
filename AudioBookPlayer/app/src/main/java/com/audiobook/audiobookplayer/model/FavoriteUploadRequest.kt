package com.audiobook.audiobookplayer.model

/**
 * 听书收藏上传请求体（新增 / 批量同步共用，与服务端 FavoriteItemRequest 对齐）。
 *
 * 服务端唯一身份 = bookName + segmentNumber；taskId 仅作「去听」深链辅助，可空。
 */
data class FavoriteUploadRequest(
    val bookName: String,
    val segmentNumber: Int,
    val content: String,
    val taskId: Long? = null
)

/** 批量同步请求体：一次性上报本地已有收藏，服务端合并去重后返回全量列表 */
data class SyncFavoritesRequest(
    val favorites: List<FavoriteUploadRequest>
)
