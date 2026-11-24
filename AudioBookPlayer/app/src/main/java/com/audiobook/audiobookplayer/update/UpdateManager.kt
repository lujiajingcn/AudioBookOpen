package com.audiobook.audiobookplayer.update

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.audiobook.audiobookplayer.R
import com.audiobook.audiobookplayer.model.AppUpdateInfo
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 客户端版本更新：检查 → 下载 → 校验 → 安装。
 *
 * 三个容易踩空、且都会让「更新功能看起来是好的、实际永远更新不了」的点：
 *
 * 1. 比较依据必须是 versionCode 而不是 versionName。versionName 是给人看的字符串，
 *    "1.10" 与 "1.9" 按字典序比较会得出 1.10 < 1.9 的错误结论。
 * 2. 请求不能带鉴权假设：本组接口在服务端 permitAll（启动页检查时可能未登录），
 *    因此这里也不传 Authorization 头。
 * 3. 下载必须 @Streaming 边下边写盘：APK 几十 MB，整包读进堆在低内存机型必 OOM。
 *    下载完成后再按 size + MD5 双重校验，避免装上一个被截断的坏包。
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val PLATFORM = "android"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** APK 存放目录名（位于 filesDir 下，由 FileProvider 暴露给系统安装器） */
    private const val UPDATE_DIR = "update"

    private const val PREF_NAME = "app_update"
    private const val KEY_IGNORED_VERSION = "ignored_version_code"

    private const val CHANNEL_ID = "app_update"
    private const val NOTIFY_ID_PROGRESS = 2001

    /** 下载进度回调节流：过于频繁地切主线程会把 UI 线程刷爆 */
    private const val PROGRESS_MIN_INTERVAL_MS = 200L

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val p = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs = p
            return p
        }
    }

    // ---------- 版本查询 ----------

    /** 当前 APK 的 versionCode（与服务端 version_code 同一套编号） */
    fun currentVersionCode(context: Context): Long = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        PackageInfoCompat.getLongVersionCode(info)
    } catch (e: Exception) {
        Log.w(TAG, "读取当前版本号失败: ${e.message}")
        0L
    }

    /** 当前展示用版本号（versionName） */
    fun currentVersionName(context: Context): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "未知"
    } catch (e: Exception) {
        Log.w(TAG, "读取当前版本名失败: ${e.message}")
        "未知"
    }

    /**
     * 检查更新。
     *
     * @return 有新版本时返回 [AppUpdateInfo]；无更新、接口失败、响应无法解析时返回 null。
     *         失败一律静默——启动检查绝不能因为服务器抖动把用户挡在应用外。
     */
    suspend fun checkForUpdate(context: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.apiService.getLatestVersion(
                platform = PLATFORM,
                versionCode = currentVersionCode(context).toInt()
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "检查更新失败: HTTP ${response.code()}")
                return@withContext null
            }
            val body = response.body() ?: return@withContext null
            if (body["success"] as? Boolean != true) return@withContext null
            if (body["hasUpdate"] as? Boolean != true) {
                Log.d(TAG, "已是最新版本")
                return@withContext null
            }
            @Suppress("UNCHECKED_CAST")
            val latest = body["latest"] as? Map<String, Any>
            val info = AppUpdateInfo.fromMap(latest)
            if (info == null) {
                Log.w(TAG, "版本信息解析失败: $latest")
                return@withContext null
            }
            if (info.versionCode <= currentVersionCode(context)) {
                // 服务端判定与本地比较不一致时以本地为准（防止回滚版本被当成更新反复弹窗）
                Log.d(TAG, "服务端版本不高于本地，忽略: ${info.versionCode}")
                return@withContext null
            }
            info
        } catch (e: CancellationException) {
            // 协程取消不是「检查失败」：调用方（如启动页离开时取消）需要能正常取消，
            // 被这里吞掉会变成「检查失败、无更新」，掩盖真正的取消语义
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "检查更新异常: ${e.message}")
            null
        }
    }

    // ---------- 「本次忽略」 ----------

    /** 已被用户忽略、本次启动周期内不再提示的版本号 */
    fun isIgnored(context: Context, versionCode: Int): Boolean =
        prefs(context).getInt(KEY_IGNORED_VERSION, 0) == versionCode

    /** 记录用户忽略的版本（只记一个：新版本发布后旧的忽略自动失效） */
    fun ignoreVersion(context: Context, versionCode: Int) {
        prefs(context).edit().putInt(KEY_IGNORED_VERSION, versionCode).apply()
    }

    // ---------- 下载 ----------

    /**
     * 下载 APK 到本地并校验。
     *
     * @param onProgress 进度回调（已切主线程）：百分比 / 已下载字节 / 总字节
     * @return 校验通过的 APK 文件；失败返回 null（失败原因已打日志）
     *
     * 已存在 MD5 匹配的同名文件时直接复用，避免用户取消安装后再点更新还要重下一次。
     */
    suspend fun downloadApk(
        context: Context,
        info: AppUpdateInfo,
        onProgress: suspend (percent: Int, downloaded: Long, total: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, UPDATE_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "无法创建更新目录: ${dir.absolutePath}")
            return@withContext null
        }
        val target = File(dir, "audiobook-${info.displayName}.apk")

        // 复用已下载且校验通过的文件
        if (target.exists() && verify(target, info)) {
            Log.d(TAG, "复用已下载的 APK: ${target.absolutePath}")
            return@withContext target
        }
        // 清掉旧包（含同名但校验失败的半截文件），避免磁盘被历史版本占满
        dir.listFiles()?.forEach { f ->
            if (f.extension == "apk" && !f.delete()) Log.w(TAG, "清理旧 APK 失败: ${f.name}")
        }

        try {
            // 带上版本号：服务端据此返回该版本的文件，避免与 /latest 的元信息错位
            val response = RetrofitClient.heavyApi.downloadApk(
                platform = PLATFORM,
                versionCode = info.versionCode
            )
            if (!response.isSuccessful) {
                Log.e(TAG, "下载 APK 失败: HTTP ${response.code()}")
                return@withContext null
            }
            val body = response.body() ?: run {
                Log.e(TAG, "下载 APK 失败: 空响应体")
                return@withContext null
            }

            val total = info.fileSize.takeIf { it > 0 }
                ?: body.contentLength().takeIf { it > 0 }
                ?: 0L

            val digest = MessageDigest.getInstance("MD5")
            var downloaded = 0L
            var lastNotify = 0L

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= PROGRESS_MIN_INTERVAL_MS || downloaded == total) {
                            lastNotify = now
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                            withContext(Dispatchers.Main) {
                                onProgress(percent.coerceIn(0, 100), downloaded, total)
                            }
                            notifyProgress(context, percent.coerceIn(0, 100), total)
                        }
                    }
                }
            }

            if (!verify(target, info)) {
                Log.e(TAG, "APK 校验失败（size=$downloaded/$total），已丢弃")
                target.delete()
                cancelProgressNotification(context)
                return@withContext null
            }

            Log.d(TAG, "APK 下载完成: ${target.absolutePath} ($downloaded bytes)")
            notifyDownloaded(context, target)
            target
        } catch (e: CancellationException) {
            // 用户取消下载：清理半截文件与通知，但不走「下载失败」分支
            target.delete()
            cancelProgressNotification(context)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "下载 APK 异常: ${e.message}", e)
            target.delete()
            cancelProgressNotification(context)
            null
        }
    }

    /** 大小与 MD5 双重校验（服务端未下发 MD5 时只校验大小） */
    private fun verify(file: File, info: AppUpdateInfo): Boolean {
        if (!file.exists()) return false
        if (info.fileSize > 0 && file.length() != info.fileSize) return false
        val expected = info.md5
        if (!expected.isNullOrBlank()) {
            val actual = file.md5()
            if (actual == null || !actual.equals(expected, ignoreCase = true)) return false
        }
        return true
    }

    private fun File.md5(): String? = try {
        val digest = MessageDigest.getInstance("MD5")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.w(TAG, "计算 MD5 失败: ${e.message}")
        null
    }

    // ---------- 安装 ----------

    /**
     * 安装 APK。
     *
     * Android 8.0（API 26）起，安装「未知来源」应用需要 REQUEST_INSTALL_PACKAGES 权限：
     * Manifest 声明只是入场券，运行时还要用户给本 App 单独授权（canRequestPackageInstalls）。
     * 未授权时直接 startActivity 会被系统静默拒绝（用户只看到「什么都没发生」），
     * 因此这里先判定，未授权则跳系统授权页并提示用户回来重试。
     *
     * 文件一律通过 FileProvider 暴露：Android 7.0 起，把 file:// URI 抛给其它 App
     * 会触发 FileUriExposedException 直接崩溃。
     *
     * @return true = 已发起安装或已跳转授权页；false = 失败（如没有可处理安装的应用）
     */
    fun installApk(context: Context, file: File): Boolean {
        if (!file.exists()) {
            Log.e(TAG, "APK 文件不存在: ${file.absolutePath}")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            Log.w(TAG, "未授予「安装未知应用」权限，跳转系统授权页")
            openInstallPermissionSettings(context)
            return true
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                Log.e(TAG, "没有可处理 APK 安装的应用")
                return false
            }
            // 授予所有可能处理该 URI 的包读权限（部分 ROM 的 PackageInstaller 包名非固定）
            val resInfoList = context.packageManager.queryIntentActivities(intent, 0)
            for (resolveInfo in resInfoList) {
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "发起安装失败: ${e.message}", e)
            false
        }
    }

    /** 是否已被授予「安装未知应用」权限（Android 8.0 以下恒为 true） */
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    private fun openInstallPermissionSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "跳转安装授权页失败: ${e.message}")
        }
    }

    // ---------- 通知 ----------

    /**
     * 下载进度与完成通知。
     *
     * 用户点「立即更新」后很可能切到别的应用，页内进度条看不见；完成后留一条
     * 常驻通知，点一下直接调起安装界面——否则用户只能回到 App 再点一次更新。
     * 未授予通知权限（Android 13+）时静默跳过，不影响下载与安装。
     */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "新版本 APK 的下载进度" }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyProgress(context: Context, percent: Int, total: Long) {
        // 内联权限判定：areNotificationsEnabled() 会在运行时正确反映 Android 13+ 的
        // POST_NOTIFICATIONS 授权状态，并尊重用户在系统设置中关闭应用通知。
        // lint 的 MissingPermission 检测器未识别该 API，这里用 @SuppressLint 显式标注
        // 我们已经做了运行时判定，避免其误报阻断构建。
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        try {
            ensureChannel(context)
            val text = if (total > 0) {
                "已下载 $percent%（${formatMB(total)}）"
            } else {
                "正在下载 $percent%"
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                // stat_sys_download* 本身是白色 alpha 蒙版，原生能显示；
                // 但它是 framework 私有资源、不同 OEM 表现不一致，换成本工程单色 vector 更稳。
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("正在下载新版本")
                .setContentText(text)
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(NOTIFY_ID_PROGRESS, notification)
        } catch (e: Exception) {
            Log.w(TAG, "更新进度通知失败: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyDownloaded(context: Context, file: File) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        try {
            ensureChannel(context)
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
            val pending = android.app.PendingIntent.getActivity(context, 0, installIntent, flags)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download_done)
                .setContentTitle("新版本已下载完成")
                .setContentText("点击安装")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFY_ID_PROGRESS, notification)
        } catch (e: Exception) {
            Log.w(TAG, "下载完成通知失败: ${e.message}")
        }
    }

    private fun cancelProgressNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFY_ID_PROGRESS)
        } catch (e: Exception) {
            Log.w(TAG, "取消更新通知失败: ${e.message}")
        }
    }

    fun formatMB(bytes: Long): String {
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        if (bytes >= 1024) return String.format("%.0f KB", bytes / 1024.0)
        return "$bytes B"
    }
}
