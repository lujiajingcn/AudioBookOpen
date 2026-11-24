package com.audiobook.audiobookplayer.model

/**
 * 每书元数据批量同步请求体（全量上报本机所有能算出 bookKey 的书）。
 *
 * 服务端按 bookKey 逐项 LWW 合并后返回权威全量
 * `{success, items:[同结构], total}`。
 */
data class SyncBookMetaRequest(
    val items: List<BookMetaItemRequest>
)
