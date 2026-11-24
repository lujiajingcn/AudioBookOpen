package com.audiobook.audiobookplayer.model

/**
 * 服务端下发的新版本信息（GET /api/app/version/latest 的 latest 字段）。
 *
 * 服务端返回的是宽松的 Map<String, Any>（与其它接口一致，未定义专用 DTO），
 * Gson 会把 JSON 数字统一反序列化成 Double，所以这里要做类型宽容解析，
 * 不能假设字段一定是 Int/Long——否则「2.0」这种 Double 直接 as? Int 会得到 null，
 * 版本号被当成 0，客户端每次启动都误报「已是最新」。
 */
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val fileSize: Long,
    val md5: String?,
    val changelog: String?,
    val forceUpdate: Boolean
) {

    /**
     * 展示用版本号，统一为 `v1.2.0` 形式。
     *
     * versionName 由管理员在后台手填，填 `1.2.0` 和 `v1.2.0` 都很常见。
     * UI 一律拼 `"v$versionName"` 的话，后者会显示成 `vv1.2.0`。
     */
    val displayName: String
        get() {
            val trimmed = versionName.trim()
            return if (trimmed.startsWith("v", ignoreCase = true)) {
                "v" + trimmed.drop(1)
            } else {
                "v$trimmed"
            }
        }

    companion object {

        /**
         * 从响应 map 解析；字段缺失或类型不可识别时返回 null（调用方按「无更新」处理）。
         * versionCode 是唯一的比较依据，缺失即视为无效响应。
         */
        fun fromMap(raw: Map<String, Any>?): AppUpdateInfo? {
            if (raw == null) return null
            val code = raw["versionCode"]?.asLong() ?: return null
            if (code <= 0) return null
            return AppUpdateInfo(
                versionCode = code.toInt(),
                versionName = raw["versionName"]?.asString()?.takeIf { it.isNotBlank() }
                    ?: "v$code",
                fileSize = raw["fileSize"]?.asLong() ?: 0L,
                md5 = raw["md5"]?.asString()?.takeIf { it.isNotBlank() },
                changelog = raw["changelog"]?.asString()?.takeIf { it.isNotBlank() },
                forceUpdate = raw["forceUpdate"]?.asBoolean() ?: false
            )
        }

        /** Gson 数字统一为 Double，字符串形态（服务端改动字段类型时）也要能兜住 */
        private fun Any.asLong(): Long? = when (this) {
            is Number -> toLong()
            is String -> trim().toLongOrNull()
            is Boolean -> if (this) 1L else 0L
            else -> null
        }

        private fun Any.asString(): String? = when (this) {
            is String -> this
            is Number -> toLong().toString()
            else -> null
        }

        private fun Any.asBoolean(): Boolean? = when (this) {
            is Boolean -> this
            is Number -> toLong() != 0L
            is String -> equals("true", ignoreCase = true)
            else -> null
        }
    }
}
