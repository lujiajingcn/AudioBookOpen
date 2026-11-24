package com.audiobook.audiobookplayer.update

import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.audiobook.audiobookplayer.R
import com.audiobook.audiobookplayer.model.AppUpdateInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * 更新提示与下载安装的界面层。
 *
 * 下载刻意跑在**全局 scope** 而不是 Activity 的 lifecycleScope：
 * 用户在下载过程中切到别的应用、Activity 被回收时，用 lifecycleScope 会让下载
 * 随之中断，回来又要从头下一次几十 MB 的包。下载完成会留一条系统通知，
 * 用户点通知即可安装（见 [UpdateManager.notifyDownloaded]）。
 */
object UpdateDialog {

    /**
     * 下载刻意跑在**全局 scope** 而不是 Activity 的 lifecycleScope（见类注释）。
     * 全局 scope 持有 Activity 引用若不加防护会泄漏整个 Activity；
     * 因此协程内部一律通过 [WeakReference] 取 Activity，取不到（已被回收）就放弃。
     * 同时挂一个 [CoroutineExceptionHandler]：下载链路里任何未捕获异常都会进这里
     * （否则全局 scope 的未捕获异常直接让进程崩溃，弱网下尤其致命）。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            Log.e("UpdateDialog", "下载更新过程中发生未捕获异常", e)
        }
    )

    /**
     * 弹出更新提示。
     *
     * @param onSkip      非强制更新时用户选择「稍后再说」（启动页据此继续进入应用）
     * @param onForceExit 强制更新时用户选择「退出应用」
     * @param onGiveUp    「放弃本次更新、回到主界面」的统一出口：下载失败 / 初始弹窗被取消 /
     *   非强制安装失败时调用。调用方决定语义——启动页传入 goMain()（进入现有安装版本），
     *   手动检查更新（已在应用内）传入空实现只做提示。
     */
    fun show(
        activity: AppCompatActivity,
        info: AppUpdateInfo,
        onSkip: () -> Unit = {},
        onForceExit: () -> Unit = {},
        onGiveUp: () -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val message = buildString {
            append("新版本：${info.displayName}")
            if (info.fileSize > 0) {
                append("\n安装包大小：${UpdateManager.formatMB(info.fileSize)}")
            }
            if (!info.changelog.isNullOrBlank()) {
                append("\n\n更新内容：\n${info.changelog}")
            }
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (info.forceUpdate) "需要更新后才能继续使用" else "发现新版本")
            .setMessage(message)
            .setCancelable(!info.forceUpdate)
            .setPositiveButton("立即更新", null)
            .apply {
                if (info.forceUpdate) {
                    setNegativeButton("退出应用") { _, _ -> onForceExit() }
                } else {
                    setNegativeButton("稍后再说") { _, _ ->
                        // 记下被忽略的版本号：同一次启动内不再反复弹（新版本发布后自动失效）
                        UpdateManager.ignoreVersion(activity, info.versionCode)
                        onSkip()
                    }
                }
            }
            .create()

        dialog.setOnShowListener {
            // 手动接管 positive 的点击：默认实现会在点击后立刻 dismiss，
            // 而我们要先换成下载进度对话框
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                startDownload(activity, info, onForceExit, onGiveUp)
            }
        }
        // 非强制更新：用户点对话框外部或按返回键取消时，不能把人留在启动页——
        // 必须兜底进入现有应用（onGiveUp = 启动页的 goMain）。强制更新不可取消，无需此监听。
        if (!info.forceUpdate) {
            dialog.setOnCancelListener { onGiveUp() }
        }
        dialog.show()
    }

    /**
     * 手动检查更新（「我的 - 检查更新」入口）。
     *
     * 与启动页的区别：不受「本次忽略」影响（用户主动点的，必须给结果），
     * 且没有更新时明确提示「已是最新版本」——不做任何反馈会让用户以为按钮坏了。
     */
    fun checkManually(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val info = UpdateManager.checkForUpdate(activity)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (info == null) {
                Toast.makeText(activity, "已是最新版本", Toast.LENGTH_SHORT).show()
            } else {
                show(activity, info, onForceExit = { activity.finishAffinity() })
            }
        }
    }

    /**
     * 下载期间进度框不可取消（点屏幕、返回键均不响应），避免误触打断下载；
     * 任何意外关闭一律走 [onGiveUp] 兜底，绝不把用户留在启动页。
     *
     * @param onForceExit 强制更新时下载失败要能回到「重试 / 退出」的岔路口。
     *   否则进度框一关，用户停在启动页上进不去也退不出，只能杀进程——
     *   这是强制更新最容易被忽略的死局（下载失败恰恰只在弱网时发生，而弱网用户最多）。
     */
    private fun startDownload(
        activity: AppCompatActivity,
        info: AppUpdateInfo,
        onForceExit: () -> Unit = {},
        onGiveUp: () -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        // 下载协程跑在全局 scope，这里先把 Activity 收进弱引用，
        // 协程内部一律弱引用取用，避免 Activity 被全局 scope 泄漏
        val activityRef = WeakReference(activity)

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val tvProgress = view.findViewById<TextView>(R.id.tvProgress)

        var job: Job? = null
        // 下载期间进度框一律不可取消：点屏幕（含对话框外部）/ 返回键都不响应。
        //
        // 原先非强制更新用 setCancelable(!info.forceUpdate)，用户随手点一下对话框外
        // 就会 dismiss → setOnDismissListener 取消 job → 下载被中断、半截包被删；
        // 而进度框没有 onCancel 兜底，onGiveUp 从不被调用 ⇒ 启动页既不跳转也不
        // finish，用户看到的就是「进度条消失，永远停在启动页」。
        //
        // 刻意不提供「取消」按钮：本类的设计就是下载跑全局 scope、切后台继续，
        // 完成后发一条通知点一下即可安装（见类注释）。用户想离开按 HOME 即可，
        // 不需要在进度框上留一个会误触打断下载的入口。
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("正在下载 ${info.displayName}")
            .setView(view)
            .setCancelable(false)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                // setCancelable(false) 已拦 BACK；再显式吃掉返回/ESC，
                // 避免部分 ROM 的窗口策略仍把按键透传给 Activity。
                setOnKeyListener { _, keyCode, _ ->
                    keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
                }
            }

        // downloadFinished 区分「下载完成后自动收起」与「意外关闭」：
        // 前者绝不能取消 job（会连安装一起取消）；后者必须兜底 onGiveUp，
        // 否则任何系统级关闭都会把用户重新按在启动页。
        var downloadFinished = false
        dialog.setOnDismissListener {
            if (!downloadFinished) {
                job?.cancel()
                val act = activityRef.get()
                if (act != null && !act.isFinishing && !act.isDestroyed) onGiveUp()
            }
        }

        // 必须先 show 再启动下载：命中「复用已下载且校验通过的 APK」时 downloadApk
        // 会立即返回，若此时进度框还没 show，协程里的 `if (dialog.isShowing) dismiss()`
        // 不成立，随后这里再 show 出的就是一个永远不会消失的进度框。
        dialog.show()

        job = scope.launch {
            // 下载协程跑在全局 scope，绝不能直接持有 Activity（会被泄漏到应用进程结束）。
            // 统一用弱引用取，取不到说明 Activity 已回收，下载意义不大，直接放弃。
            val act = activityRef.get() ?: return@launch
            val file = UpdateManager.downloadApk(act.applicationContext, info) { percent, downloaded, total ->
                progressBar.progress = percent
                tvProgress.text = if (total > 0) {
                    "$percent%（${UpdateManager.formatMB(downloaded)} / ${UpdateManager.formatMB(total)}）"
                } else {
                    "$percent%（${UpdateManager.formatMB(downloaded)}）"
                }
            }
            // 先置位再 dismiss：否则下面这行 dismiss 会触发上面的监听，把【当前正在
            // 运行的 job】取消掉。取消是协作式的，今天 installApk / canInstallPackages
            // 都是非挂起函数所以还能走到安装；但只要中间多出一个挂起点
            // （withContext / delay / 任何 suspend 调用），安装就会被静默取消，
            // 用户看到的是「下载完了却什么都没发生」，且没有任何日志
            downloadFinished = true
            if (dialog.isShowing) dialog.dismiss()
            if (act.isFinishing || act.isDestroyed) return@launch

            if (file == null) {
                Toast.makeText(act, "下载失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
                // 下载失败（弱网最常见）一律放弃本次更新、进入现有安装版本：
                // 启动页据此 goMain，不把人按在启动动画页；手动检查更新时 onGiveUp 为空实现，只提示。
                // 不再对强制更新单独「重弹提示框」——那会让弱网用户在重试死循环里进退两难。
                onGiveUp()
                return@launch
            }
            if (!UpdateManager.installApk(act, file)) {
                Toast.makeText(act, "无法打开安装程序，请稍后重试", Toast.LENGTH_SHORT).show()
                if (info.forceUpdate && !act.isFinishing && !act.isDestroyed) {
                    // 启动页上没有任何可点的入口，不重新弹框用户就卡死了
                    show(act, info, onForceExit = onForceExit, onGiveUp = onGiveUp)
                } else {
                    onGiveUp()
                }
                return@launch
            }
            if (!UpdateManager.canInstallPackages(act)) {
                // 首次安装未知来源应用会先跳授权页，这里给用户明确指引，
                // 否则用户看到的是「什么都没发生」
                Toast.makeText(
                    act,
                    "请先允许「安装未知应用」，然后回到应用重新点击更新",
                    Toast.LENGTH_LONG
                ).show()
                if (info.forceUpdate && !act.isFinishing && !act.isDestroyed) {
                    // 启动页上没有任何可点的入口，不重新弹框用户就卡死了
                    show(act, info, onForceExit = onForceExit, onGiveUp = onGiveUp)
                }
            }
        }
    }
}
