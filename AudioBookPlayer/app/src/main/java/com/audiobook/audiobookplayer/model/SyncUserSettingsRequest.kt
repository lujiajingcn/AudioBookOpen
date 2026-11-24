package com.audiobook.audiobookplayer.model

/**
 * 单值偏好批量同步请求体（一次 PUT 完成「拉 + 推」）。
 *
 * 服务端按 LWW 逐项合并后返回权威全量（同 [UserSettingItemRequest] 结构）：
 * `{success, settings:{...}, items:[...], version}`。
 */
data class SyncUserSettingsRequest(
    val items: List<UserSettingItemRequest>
)
