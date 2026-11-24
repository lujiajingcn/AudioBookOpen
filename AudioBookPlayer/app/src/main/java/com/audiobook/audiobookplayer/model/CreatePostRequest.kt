package com.audiobook.audiobookplayer.model

/**
 * 发布帖子请求体。
 *
 * 必须用具体数据类，不能直接用 Map<String, Any>，
 * 否则 Retrofit 运行时会因为 Kotlin 的 out-variance 把它解析成
 * Map<String, ? extends Object> 而抛出 IllegalArgumentException。
 *
 * @param content 正文（必填，服务端校验非空且 ≤500 字）
 * @param title 标题（可选）
 * @param imageUrls 图片相对路径列表（可选，由客户端先上传拿 URL 再填入）
 * @param refAudioId 嵌入的分享参考音频 id（可选）
 * @param mentions @提及 的用户名列表（可选；不填则服务端从正文自动解析 @用户名）
 */
data class CreatePostRequest(
    val content: String,
    val title: String? = null,
    val imageUrls: List<String>? = null,
    val refAudioId: Long? = null,
    val mentions: List<String>? = null
)
