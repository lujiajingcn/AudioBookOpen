package com.audiobook.audiobookplayer.model

/**
 * 单值偏好上报项（与服务端 `UserSettingItemRequest` 对齐，PUT /api/user-settings 的 items[]）。
 *
 * 只同步 2 项：`playback_speed`（Float，序列化成字符串）与 `default_voice_id`（String）。
 * ⛔ 绝不同步夜间模式 / 字号 / MOSS 本机路径 / token / 分类等其它偏好。
 *
 * LWW：服务端逐项比较 [updatedAt]，只有上报值严格大于服务端现存版本才覆盖，
 * 故干净项原样回传服务端版本、脏项用放大后的时间戳覆盖，取值规则见
 * [com.audiobook.audiobookplayer.UserSettingsSyncManager]。
 */
data class UserSettingItemRequest(
    val key: String,
    val value: String,
    /** 毫秒时间戳；干净项 = 上次拿到的服务端版本，脏项 = max(now, 全局最高版本+1) */
    val updatedAt: Long
)
