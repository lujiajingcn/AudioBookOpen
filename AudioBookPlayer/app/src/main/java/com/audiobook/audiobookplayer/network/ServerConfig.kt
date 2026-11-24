package com.audiobook.audiobookplayer.network

import com.audiobook.audiobookplayer.BuildConfig

/**
 * 服务器地址配置。
 *
 * 场景 A（单一官方服务器 / 上架发布）：地址完全由构建期注入的 BuildConfig.BASE_URL 决定，
 * 不再提供应用内自定义入口（登录页的服务器填写框、历史下拉、折叠高级选项均已移除）。
 * debug / release 各自的默认地址见 app/build.gradle.kts 的 buildConfigField("BASE_URL", ...)。
 */
object ServerConfig {

    /** 当前服务器地址（即构建期注入的 BuildConfig.BASE_URL），保证以 / 结尾 */
    val baseUrl: String
        get() = normalize(BuildConfig.BASE_URL)

    private fun normalize(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}
