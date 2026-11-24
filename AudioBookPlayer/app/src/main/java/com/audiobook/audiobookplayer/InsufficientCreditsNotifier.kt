package com.audiobook.audiobookplayer

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 余额不足（HTTP 402）全局引导。
 *
 * 402 处理不能做成「RetrofitClient 上的单例回调、由单个页面注册」——那样只有注册过的
 * 页面（如书架页）有感知，合成页/参考音频页/充值页触发 402 时用户完全无感知（静默失败）。
 *
 * 这里由 OkHttp 拦截器在收到 402 时直接调用 [notify]，由本对象在【当前前台 Activity】
 * 上弹「去充值」引导，覆盖全部页面，不依赖任何单页注册。当前前台 Activity 由
 * [AudioBookApplication] 通过 ActivityLifecycleCallbacks 持续刷新。
 */
object InsufficientCreditsNotifier {
    private const val TAG = "InsufficientCredits"

    private val handler = Handler(Looper.getMainLooper())

    /** 当前前台（已 resumed）Activity；由 AudioBookApplication 维护 */
    @Volatile
    var currentActivity: Activity? = null
        private set

    fun setCurrentActivity(activity: Activity?) {
        currentActivity = activity
    }

    /** 由网络拦截器在收到 402 时调用，统一切到主线程弹窗 */
    fun notify(message: String) {
        handler.post {
            val act = currentActivity
            if (act == null || act.isFinishing || act.isDestroyed) {
                Log.w(TAG, "无可用前台 Activity，跳过余额不足提示: $message")
                return@post
            }
            showDialog(act, message)
        }
    }

    private fun showDialog(activity: Activity, message: String) {
        val text = message.ifEmpty { "余额不足，请先充值" }
        // 已在充值页则不再引导跳转，仅告知原因
        if (activity is RechargeActivity) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("余额不足")
                .setMessage(text)
                .setPositiveButton("我知道了", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("余额不足")
            .setMessage(text)
            .setPositiveButton("去充值") { _, _ ->
                if (activity.isFinishing || activity.isDestroyed) return@setPositiveButton
                activity.startActivity(Intent(activity, RechargeActivity::class.java))
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
