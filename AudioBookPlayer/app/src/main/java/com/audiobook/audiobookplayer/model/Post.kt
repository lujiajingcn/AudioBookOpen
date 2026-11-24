package com.audiobook.audiobookplayer.model

/**
 * 发现页帖子（支持标题 / 图片 / 嵌入参考音频 / @提及）。
 *
 * @param createdAt 服务端已格式化好的 "yyyy-MM-dd HH:mm:ss"
 * @param imageUrls 帖子图片的相对路径列表（如 ["/api/posts/image/xxx.jpg"]），
 *                  拼上 [com.audiobook.audiobookplayer.network.RetrofitClient.BASE_URL] 即为可访问地址
 * @param refAudioId 嵌入的分享参考音频 id（来自排行榜），为 null 表示未嵌入
 * @param mentions 正文中解析出的 @用户名 列表（用于高亮与提醒）
 */
data class Post(
    val id: Long = 0,
    val username: String = "",
    val title: String? = null,
    val content: String = "",
    val imageUrls: List<String> = emptyList(),
    val refAudioId: Long? = null,
    val mentions: List<String> = emptyList(),
    val commentCount: Int = 0,
    val favoriteCount: Int = 0,
    /** 点赞数。服务端从 post_likes 现算，不是冗余列（见 PostLike 类注释） */
    val likeCount: Int = 0,
    /** 当前登录用户是否已点赞；游客恒 false */
    val liked: Boolean = false,
    val createdAt: String? = null,
    val isFavorited: Boolean = false
)

/** 这条帖子是不是当前登录用户自己发的。
 *
 * 判据是**用户名**而不是 user id：服务端 posts 表存的是发帖时的 username 快照
 * （没有 user_id 列），服务端 deleteOwnPost 也按 username 比对 —— 两边必须同口径，
 * 否则会出现「客户端给了删除按钮，服务端却 403」这种自相矛盾的界面。
 */
val Post.isMine: Boolean
    get() {
        val me = com.audiobook.audiobookplayer.network.AuthManager.username
        return !me.isNullOrBlank() && me == username
    }
