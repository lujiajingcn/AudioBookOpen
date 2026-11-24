package com.audiobook.audiobookplayer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 合成任务终态系统通知。
 *
 * 任务在后台合成期间用户通常会离开 App，页内进度看不到——任务完成/失败时发一条
 * 系统通知，用户不用守着页面。全局轮询（TaskPollingManager）与 TtsFragment 自带
 * 轮询都可能在终态迁移时调用这里，用 per-task 去重保证同一终态只提醒一次
 * （failed→completed 这种后续迁移仍会再次提醒）。
 */
object TaskNotificationHelper {

    private const val TAG = "TaskNotificationHelper"
    private const val CHANNEL_ID = "tts_task_terminal"
    private const val CHANNEL_NAME = "语音合成任务"

    /** taskId -> 上次已通知的终态，用于去重 */
    private val lastNotified = ConcurrentHashMap<Long, String>()
    private val nextNotifyId = AtomicInteger(1000)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "语音合成任务的完成与失败提醒"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 任务进入终态时提醒。未授权通知权限（Android 13+ 需运行时授权，引导在合成页发起）
     * 或非终态时静默跳过，绝不影响合成主流程。
     */
    fun notifyTerminal(context: Context, taskId: Long, bookName: String, status: String) {
        if (status != "completed" && status != "partial" && status != "failed") return
        // 去重：同一任务同一终态只提醒一次；不同终态迁移（如 failed → 重试后 completed）允许再次提醒
        if (lastNotified.put(taskId, status) == status) return

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "未授权通知权限，跳过任务通知: taskId=$taskId")
            return
        }

        try {
            ensureChannel(context)
            val title = when (status) {
                "completed" -> "语音合成完成"
                "partial" -> "语音合成部分完成"
                else -> "语音合成失败"
            }
            val text = when (status) {
                "failed" -> "《$bookName》合成失败，点击查看失败原因"
                // partial 不能沿用「合成完成」：它还有若干段没成功，
                // 同一条通知的标题已经写着「部分完成」，正文再说完工会自相矛盾，
                // 用户点「下载音频」还会因为缺段落直接失败。
                "partial" -> "《$bookName》还有段落未合成成功，点击查看并补齐"
                else -> "《$bookName》合成完成，点击查看或下载音频"
            }
            // 文案写「点击查看」就一定要有 setContentIntent，否则点通知没反应。
            // 补一个打开主界面的 PendingIntent（FLAG_IMMUTABLE 满足 Android 12+ 要求）。
            val contentIntent = PendingIntent.getActivity(
                context,
                taskId.toInt(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                // 同 PlaybackService——framework 的 ic_dialog_info 是彩色 PNG，
                // API 21+ 按 alpha 蒙版渲染会变成白块，改用本工程单色 vector。
                .setSmallIcon(R.drawable.ic_stat_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context).notify(nextNotifyId.getAndIncrement(), notification)
            Log.d(TAG, "已发送任务终态通知: taskId=$taskId, status=$status")
        } catch (e: Exception) {
            // 通知渠道被禁用/权限在发送前被撤销等场景，只记日志不影响业务
            Log.w(TAG, "发送任务通知失败: ${e.message}")
        }
    }

    /** 任务被删除时清理去重记录（任务 ID 可能被复用） */
    fun forgetTask(taskId: Long) {
        lastNotified.remove(taskId)
    }
}
