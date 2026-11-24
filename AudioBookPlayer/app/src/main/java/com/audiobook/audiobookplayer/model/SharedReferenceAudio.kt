package com.audiobook.audiobookplayer.model

/**
 * 「发现 - 参考音频」排行榜条目（其他用户分享出来的参考音频）。
 *
 * 由 GET /api/tts/shared-references 返回，服务端已按 useCount 倒序排好，
 * 客户端只按返回顺序展示，不自己再排一遍。
 *
 * @param id       服务端 user_reference_audio 表的 id（**不是**管理员共享库的 id）
 * @param rank     名次，从 1 开始，由服务端下发
 * @param ownerName 分享者昵称
 * @param useCount 使用量：被其他用户存进自己库的次数
 * @param mine     是否是本人分享的。本人的条目不能给自己加使用量，也不显示「使用」按钮
 * @param owned    本地库是否已持有该分享条目（由客户端根据本地库比对后写入，
 *                 不来自服务端）。决定「使用」按钮显示成「已使用」并置灰
 */
data class SharedReferenceAudio(
    val id: Long,
    val name: String,
    val rank: Int = 0,
    val fileName: String? = null,
    val description: String? = null,
    val fileSize: Long = 0,
    val useCount: Long = 0,
    val ownerName: String = "",
    val mine: Boolean = false,
    val owned: Boolean = false
)
