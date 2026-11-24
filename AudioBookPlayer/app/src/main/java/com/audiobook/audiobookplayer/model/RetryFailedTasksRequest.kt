package com.audiobook.audiobookplayer.model

/**
 * 批量重试失败任务请求体。
 *
 * 与 [CreatePostRequest] 同理：Map<String, Any> 在 @Body 位置会被 Retrofit
 * 解析成带通配符的类型，必须换成具体数据类。
 */
data class RetryFailedTasksRequest(
    val taskIds: List<Long>
)
