package com.audiobook.audiobookplayer.model

/**
 * 帖子评论（含嵌套回复）。
 *
 * @param parentId 被回复评论的 id；为 null 表示对帖子本身的一级评论
 * @param parentUsername 被回复者的用户名（冗余存储，便于展示「回复 @xxx」）
 */
data class Comment(
    val id: Long = 0,
    val postId: Long = 0,
    val username: String = "",
    val content: String = "",
    val parentId: Long? = null,
    val parentUsername: String? = null,
    val createdAt: String? = null
)
