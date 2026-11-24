package com.audiobook.audiobookplayer.moss

import com.audiobook.audiobookplayer.BuildConfig
import org.json.JSONObject

/**
 * 服务端模块清单（`/moss/manifest.json`）的客户端模型。
 *
 * 与 `.tmp_moss/pack_module.py` 产出的 JSON 一一对应。字段刻意只取 App 真正需要的，
 * 不做全量映射 —— 清单将来加字段（比如多语言说明）不应该逼着客户端跟着发版。
 *
 * ## 为什么清单里必须有「逐文件 sha256」
 * 只校验整包 md5 是不够的。包下载对了，**解压到手机文件系统的过程仍可能出错**
 * （空间不足写到一半、用户中途清理、文件系统异常）。一旦某个 .data 文件少了几 KB，
 * ONNX Runtime 报出来的是一句看不懂的 native 错误，而不是"文件损坏"。
 * 逐文件哈希才能精确定位到"到底哪个文件坏了"。
 */
data class MossManifest(
    val version: String,
    val archiveFile: String,
    val archiveSize: Long,
    val archiveSha256: String,
    /** 下载体积（压缩包） */
    val downloadBytes: Long,
    /** 安装后占用（解压后） */
    val installedBytes: Long,
    val files: List<FileEntry>
) {
    data class FileEntry(val name: String, val size: Long, val sha256: String)

    val fileSha256: Map<String, String> get() = files.associate { it.name to it.sha256 }

    companion object {
        fun fromJson(json: JSONObject): MossManifest {
            val archive = json.getJSONObject("archive")
            val totals = json.optJSONObject("totals")
            val files = ArrayList<FileEntry>()
            json.optJSONArray("files")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val sha = f.optString("sha256", "")
                    // 没有 sha256 的条目直接不纳入校验集：宁可少校验，
                    // 也不能拿空串去比对（那会让所有文件都"校验失败"）
                    if (sha.isNotEmpty()) {
                        files.add(FileEntry(f.getString("name"), f.optLong("size", 0L), sha))
                    }
                }
            }
            return MossManifest(
                version = json.getString("version"),
                archiveFile = archive.getString("file"),
                archiveSize = archive.getLong("size"),
                archiveSha256 = archive.getString("sha256"),
                downloadBytes = totals?.optLong("downloadBytes", archive.getLong("size"))
                    ?: archive.getLong("size"),
                installedBytes = totals?.optLong("installedBytes", 0L) ?: 0L,
                files = files
            )
        }
    }
}

/**
 * 模块托管地址。
 *
 * 默认值来自构建配置（build.gradle.kts 的 MOSS_MODULE_BASE），但**允许运行时覆盖**，
 * 存在 SharedPreferences 里。为什么需要这层覆盖：
 *   · 725 MB 的包将来很可能迁到 CDN 或对象存储，地址一变就要发一次 App 版本才能跟上，
 *     而老用户不升级就永远下载不到 —— 一个纯服务端的变更不该有这种约束；
 *   · 排查线上下载问题时，能当场把地址指到备用源/直连源，不必等发版。
 *
 * 覆盖入口只放在 debug 包里（见 MossModuleActivity 的长按标题），
 * 避免 release 用户误改成一个无效地址后完全没法恢复。
 */
object MossModuleSource {

    private const val PREFS = "moss_prefs"
    private const val KEY_BASE = "module_base_url"

    /** 构建期的默认地址 */
    val defaultBaseUrl: String get() = BuildConfig.MOSS_MODULE_BASE

    /** 当前生效的地址（已做结尾斜杠归一化） */
    fun baseUrl(context: android.content.Context): String {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_BASE, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return normalize(saved ?: defaultBaseUrl)
    }

    /** 是否正在使用运行时覆盖（UI 需要提示，否则"为什么下的不是官方源"没人说得清） */
    fun isOverridden(context: android.content.Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_BASE, null)
            ?.trim()
            ?.isNotEmpty() == true

    /** 设置覆盖地址；传 null / 空串表示恢复默认 */
    fun setBaseUrl(context: android.content.Context, url: String?) {
        val editor = context.applicationContext
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
        if (url.isNullOrBlank()) editor.remove(KEY_BASE) else editor.putString(KEY_BASE, url.trim())
        editor.apply()
    }

    /**
     * 归一化：必须以 "/" 结尾。
     * 下载逻辑是字符串拼接（baseUrl + "manifest.json"），少了斜杠会拼成
     * `http://host/mossmanifest.json` —— 这种地址会 404，而报错信息完全看不出原因。
     */
    private fun normalize(url: String): String = if (url.endsWith("/")) url else "$url/"

    fun manifestUrl(context: android.content.Context): String = baseUrl(context) + "manifest.json"

    fun archiveUrl(context: android.content.Context, manifest: MossManifest): String =
        baseUrl(context) + manifest.archiveFile
}

