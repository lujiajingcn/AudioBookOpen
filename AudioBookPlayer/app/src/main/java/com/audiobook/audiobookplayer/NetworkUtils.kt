package com.audiobook.audiobookplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络相关工具 + 轻量全局设置（SharedPreferences 持久化）。
 */
object NetworkUtils {

    /** 当前是否通过 WiFi 联网（仅当能拿到活跃网络且具备 TRANSPORT_WIFI 能力时返回 true） */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

/**
 * 应用级开关设置（首次启动引导标记、仅 WiFi 下载、夜间模式、字体大小等）。
 * 独立存于 "app_settings" 文件，与登录态/服务端地址等分开放置。
 *
 * 夜间模式与阅读页正文字号都存这里。夜间模式值直接复用 AppCompatDelegate 的
 * 模式常量（-1=跟随系统, 1=日间, 2=夜间），正文字号是阅读页电子书正文的缩放比例（0.8~1.6）。
 */
object AppPrefs {

    private const val NAME = "app_settings"
    private const val KEY_ONLY_WIFI = "only_wifi_download"
    private const val KEY_ONBOARDING_DONE = "onboarding_done_v1"
    private const val KEY_NIGHT_MODE = "night_mode"   // AppCompatDelegate 模式常量：-1/1/2
    /**
     * 阅读页正文字号缩放比例，默认 1.0f。
     *
     * ⚠️ 键名保持 "font_scale" 这个**历史字符串**，别"顺手改成" reader_text_scale：
     * 改名 ⇒ 已存的用户偏好读不出来，正文字号被静默重置回 100%。
     * 语义变了但键不变，是刻意的 —— 存量值直接沿用，只是作用范围从全局收窄到正文。
     */
    private const val KEY_FONT_SCALE = "font_scale"
    /** 阅读页切栏把手的纵向位置比例（占可用高度），默认 0.5 = 居中 */
    private const val KEY_CHROME_HANDLE_RATIO = "chrome_handle_ratio"
    /** 书城音频未完成的后台下载任务（JSON 数组），见 [AppPrefs.getStoreAudioJobs] */
    private const val KEY_STORE_AUDIO_JOBS = "store_audio_jobs"
    /** 阅读页「点读」模式开关，默认关（见 [isPointReadEnabled]） */
    private const val KEY_POINT_READ = "point_read_enabled"
    /**
     * 阅读页「回到播放处」的一次性引导是否已弹过（见 [isBackToPlayHintShown]）。
     *
     * 这个按钮**只在「朗读中 + 用户手动翻走了」时才出现**，而 2026-10-29 之后它只剩
     * 一枚准星图标（文字降级为长按提示）—— 图标本身猜不出含义，所以第一次见到它时
     * 弹一次 Toast 说明。存进 SharedPreferences 而不是内存变量：内存标记每次冷启动
     * 都会重置，用户每次开 App 都被同一条 Toast 打扰一遍，那就不是「一次性引导」了。
     */
    private const val KEY_BACK_TO_PLAY_HINT = "back_to_play_hint_shown_v1"
    /**
     * 默认预设音色 id（跨端同步项之一，见 UserSettingsSyncManager）。
     * 空串 = 未选择 / 回退到参考音频克隆。⚠ 只存 voiceId，不存音色显示名。
     */
    private const val KEY_DEFAULT_VOICE_ID = "default_voice_id"

    fun isOnlyWifiDownload(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONLY_WIFI, false)

    fun setOnlyWifiDownload(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONLY_WIFI, value).apply()
    }

    /** 新手引导是否已展示过（只弹一次） */
    fun isOnboardingDone(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    /** 夜间模式偏好（AppCompatDelegate 模式常量：-1 跟随系统 / 1 日间 / 2 夜间） */
    fun getNightMode(context: Context): Int =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, -1)

    fun setNightMode(context: Context, mode: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_NIGHT_MODE, mode).apply()
    }

    /**
     * 阅读页**电子书正文**的字号缩放比例，默认 1.0，范围约束在 UI 层做（0.8~1.6）。
     *
     * ⚠️ 这里只被 [LineAdapter] 用来量正文 TextView 的 sp 字号，
     * **不再**参与 `Configuration.fontScale`（那是全局缩放，会把标题栏、
     * 底部导航、列表、弹窗全部一起放大 —— 与「只调正文」的需求相反）。
     */
    fun getReaderTextScale(context: Context): Float =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_FONT_SCALE, 1.0f)

    fun setReaderTextScale(context: Context, scale: Float) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_FONT_SCALE, scale).apply()
    }

    /**
     * 阅读页切栏把手的纵向位置（占可用高度的比例，0f~1f），默认 0.5（竖直居中）。
     *
     * ## 为什么存「比例」而不是「像素 y」
     *
     * 把手能停的范围取决于当前屏高（正文区上下边界），而屏高会因
     * 「顶部栏/播放栏显隐、横屏、状态栏高度」变化。存像素 y 的话，换个设备或
     * 转屏后这个值就落在范围外 ⇒ 把手要么贴边要么看不见，还得再加一套
     * 「越界就夹回去」的兼容逻辑。存比例后，越界夹取只依赖当次的实际范围，
     * 持久化值永远是有效的相对位置。
     *
     * 夹取（clamp）本身在 [BookContentActivity.applyHandlePosition] 里做，
     * 这里只负责存取，不做业务判断 —— 保持偏好层无 UI 知识。
     */
    fun getChromeHandleRatio(context: Context): Float =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_CHROME_HANDLE_RATIO, 0.5f)

    fun setChromeHandleRatio(context: Context, ratio: Float) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_CHROME_HANDLE_RATIO, ratio).apply()
    }

    /**
     * 阅读页「点读」模式是否开启（点英文单词 → 弹释义 + 朗读）。
     *
     * ## 为什么持久化而不是每次进入都默认关
     * 点读会**改变单击正文的语义**（开启时单击不再播句子，见 BookContentActivity），
     * 属于会打断既有使用习惯的模式。如果每次进页面都要重新点开，
     * 用户就得记得「今天这个页面要先开点读」，反而不如不做这个功能；
     * 持久化后它是一次「我决定在这个 App 里就这么用」的设置。
     *
     * 默认 **false**：新装用户第一眼看到的是原有行为，不会一进来就发现
     * 「点句子不播了」而不知道多了一个开关。
     */
    fun isPointReadEnabled(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_POINT_READ, false)

    fun setPointReadEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_POINT_READ, enabled).apply()
    }

    /**
     * 「回到播放处」的一次性引导是否已经弹过（默认 false ⇒ 还没弹过）。
     *
     * 语义就是「这条 Toast 整个 App 生命周期只出现一次」，不按朗读会话、不按书重置：
     * 它是**认图标**的教学提示，认一次就够了；按会话重置只会变成每本书都来一遍的噪声。
     * 换图标（也就是重新教学）时才需要改 [KEY_BACK_TO_PLAY_HINT] 的版本后缀。
     */
    fun isBackToPlayHintShown(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACK_TO_PLAY_HINT, false)

    fun setBackToPlayHintShown(context: Context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BACK_TO_PLAY_HINT, true).apply()
    }

    /**
     * 书城合成音频的**未完成下载任务**（进程被杀后据此续传）。
     *
     * 只存任务标识、不存进度：进度 = 「这本书本地已有多少段」，是一个**可由
     * `audio_segments` 直接查出来的量**。另存一份反而要处理两处不一致
     * （存的是 37、库里其实是 35），不如让数据库当唯一真源。
     *
     * 用 JSON 存而不是拼分隔符：书名是用户可见文本，含 `|`、换行都可能，
     * 拼串必然在某个书名上炸掉。
     */
    fun getStoreAudioJobs(context: Context): List<StoreAudioDownloadManager.JobSpec> {
        val raw = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_STORE_AUDIO_JOBS, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val bookId = o.optLong("bookId", -1L)
                val fileName = o.optString("fileName")
                if (bookId <= 0 || fileName.isEmpty()) return@mapNotNull null
                StoreAudioDownloadManager.JobSpec(
                    bookId = bookId,
                    title = o.optString("title"),
                    fileName = fileName,
                    fileExtension = o.optString("fileExtension").ifEmpty { null },
                    bookName = o.optString("bookName").ifEmpty { null }
                )
            }
        } catch (e: Exception) {
            // 存坏了就当没有未完成任务：宁可不续传，也不能让启动路径因解析异常崩掉
            emptyList()
        }
    }

    fun setStoreAudioJobs(context: Context, jobs: List<StoreAudioDownloadManager.JobSpec>) {
        val arr = org.json.JSONArray()
        jobs.forEach { job ->
            arr.put(
                org.json.JSONObject().apply {
                    put("bookId", job.bookId)
                    put("title", job.title)
                    put("fileName", job.fileName)
                    put("fileExtension", job.fileExtension ?: "")
                    put("bookName", job.bookName ?: "")
                }
            )
        }
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_STORE_AUDIO_JOBS, arr.toString()).apply()
    }

    /**
     * 默认预设音色 id（跨端同步项）。空串表示「未选择 / 用参考音频克隆」。
     * 由 TtsFragment 选择/重置时落盘，UserSettingsSyncManager 读写并与服务端同步。
     */
    fun getDefaultVoiceId(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_VOICE_ID, "") ?: ""

    fun setDefaultVoiceId(context: Context, voiceId: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEFAULT_VOICE_ID, voiceId).apply()
    }
}
