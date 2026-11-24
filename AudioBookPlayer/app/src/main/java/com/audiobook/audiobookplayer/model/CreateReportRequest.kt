package com.audiobook.audiobookplayer.model

/**
 * 举报请求体。
 *
 * @param targetType 举报对象类型："POST"（帖子）或 "COMMENT"（评论）
 * @param targetId 被举报对象的 id
 * @param reason 举报理由（必填，≤500 字）
 */
data class CreateReportRequest(
    val targetType: String,
    val targetId: Long,
    val reason: String
)
