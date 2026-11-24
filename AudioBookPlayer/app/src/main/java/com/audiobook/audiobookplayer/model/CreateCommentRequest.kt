package com.audiobook.audiobookplayer.model

/**
 * 发表评论 / 回复请求体。
 *
 * @param content 评论正文（必填，≤500 字）
 * @param parentId 被回复评论的 id；为 null 表示对帖子本身的一级评论
 */
data class CreateCommentRequest(
    val content: String,
    val parentId: Long? = null
)
