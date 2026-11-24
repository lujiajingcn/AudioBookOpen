package com.audiobook.audiobookplayer.model

/**
 * 服务器共享参考音频库条目（只读）。
 *
 * 由 GET /api/tts/server-references 返回，所有登录用户可见。
 * 用户可将其下载到手机，加入自己的本地参考音频列表。
 */
data class ServerReferenceAudio(
    val id: Long,
    val name: String,
    /** 服务端落盘文件名，用于推断真实扩展名（下载到本地时要保留） */
    val fileName: String? = null,
    val description: String? = null,
    val fileSize: Long = 0
)
