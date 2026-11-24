package com.audiobook.audiobookplayer

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「离线词典」管理弹窗：安装 / 卸载 ECDICT。
 *
 * ## 为什么要单独一个弹窗而不是直接下载
 * 词典是 **23.1MB 的下载**（gzip 前55.3MB），必须让用户明确知道在发生什么：
 * 显示进度、可取消、失败能重试。点一下就静默下载 23MB 是不负责任的。
 *
 * ## 状态来源
 * 全部读 [DictionaryManager.state]（StateFlow），不在本类里另存一份状态 ——
 * 两份状态必然漂移（下载完了弹窗还显示「下载中」这类）。
 *
 * ## 卸载
 * 已安装时按钮变成「卸载词典」并二次确认：卸掉后阅读页的「翻译」会退化为
 * 「未安装词典」提示，用户可能已经习惯了查词，不该一次误触就丢掉55MB 的东西。
 *
 * ## 生命周期
 * 本类是**进程单例**（[instance]），不是每次 show 都new 一个：
 * `DictionaryManager` 是 object 单例、状态是全局 StateFlow，
 * 每开一次弹窗就 new 一个实例的话，会各自往 Activity scope 上挂一个
 * **永不取消**的收集器 ⇒ 开N 次就有 N 个收集器 + N 套 View 引用被长期持有。
 */
class DictManageDialog {

    companion object {
        private const val TAG = "DictManageDialog"

        private var instance: DictManageDialog? = null

        /**
         * 展示管理弹窗（单例，复用同一个实例）。
         *
         * @param dismissOnInstalled 安装成功后**自动关闭本弹窗**。
         *   阅读页点读链路传 true：装完马上要弹的是单词释义卡，
         *   这个只负责显示「已安装 · 版本 · 词条数」的管理窗若还留在屏幕上，
         *   就会和释义卡叠在一起（用户看到的就是一个多余的"安装完成"弹窗）。
         *   「我的 - 离线词典」入口保持默认 false —— 用户是主动来管理词典的，
         *   装完还留在原地告诉他结果才有意义。
         * @param onClosed 用户关闭弹窗时的回调（页面据此刷新状态文案）
         */
        fun show(
            context: Context,
            lifecycleOwner: androidx.lifecycle.LifecycleOwner,
            // ⚠️ 新参数一律带默认值，且**必须排在 onClosed 之前**：调用方（ProfileFragment）
            // 用「尾随 lambda 传 onClosed」的写法，而尾随 lambda 只绑定**最后一个**参数。
            // 把新参数放到 onClosed 后面会让那个 lambda 静默错位到 onInstalled 上。
            autoStart: Boolean = false,
            dismissOnInstalled: Boolean = false,
            onInstalled: (() -> Unit)? = null,
            onClosed: () -> Unit = {}
        ) {
            // 防重入：连点「离线词典」会叠两个弹窗、两套收集器
            if (instance?.dialog?.isShowing == true) return
            val sheet = instance ?: DictManageDialog().also { instance = it }
            sheet.onInstalled = onInstalled
            sheet.dismissOnInstalled = dismissOnInstalled
            val dialog = sheet.build(context)
            sheet.observe(lifecycleOwner)
            // 弹窗一关就取消收集器：render() 会往已dismiss 的 View 上写状态，
            // 收集器留着纯属浪费（并且延长 View 生命周期）
            val observeJob = sheet.observeJob
            dialog.setOnDismissListener {
                observeJob?.cancel()
                sheet.dialog = null
                // 必须清空：回调捕获了调用方的 Activity/协程作用域，
                // 本类是**进程单例**，不清会一直拖住直到下次 show 被覆盖。
                sheet.onInstalled = null
                sheet.dismissOnInstalled = false
                onClosed()
            }
            dialog.show()
            // autoStart：调用方（阅读页点读）已经问过用户「是否下载」并拿到同意，
            // 这里直接开始下载，不必让用户在弹窗里再点一次「下载词典」。
            // 进度/失败重试仍由本弹窗渲染，所以用户看得到发生了什么。
            if (autoStart) sheet.onPrimaryClick(context)
        }
    }

    private var progressBar: ProgressBar? = null
    private var textStatus: TextView? = null
    private var textSize: TextView? = null
    private var btnPrimary: Button? = null
    private var btnUninstall: Button? = null
    private var dialog: AlertDialog? = null

    /** 状态收集器的Job，dismiss 时取消（见 show 里的注释） */
    private var observeJob: kotlinx.coroutines.Job? = null

    /** 当前弹窗是否正在展示下载（用于避免重复点按发起并发下载） */
    private var downloading = false

    /**
     * 词典**安装成功**后的回调（阅读页点读用它接续查词：装完直接把刚才点的那个词弹出来）。
     *
     * 与 onClosed 的区别很关键：onClosed 是「弹窗一关」就触发（此时往往还在下载），
     * 在它里面查词必然查不到 —— 必须等 onDone。
     *
     * 持有即持有 Activity，所以随 dismiss 置 null（见 show 里的 setOnDismissListener）。
     */
    private var onInstalled: (() -> Unit)? = null

    /**
     * 安装成功后是否自动关闭本弹窗（见 [show] 的 `dismissOnInstalled`）。
     * 随 [onInstalled] 一起在 dismiss 时清空：它同样捕获着调用方的上下文。
     */
    private var dismissOnInstalled: Boolean = false

    fun build(context: Context): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_dict_manage, null, false)

        progressBar = view.findViewById<ProgressBar>(R.id.pbDictProgress)
        textStatus = view.findViewById<TextView>(R.id.tvDictStatus)
        textSize = view.findViewById<TextView>(R.id.tvDictSize)
        btnPrimary = view.findViewById<Button>(R.id.btnDictPrimary)
        btnUninstall = view.findViewById<Button>(R.id.btnDictUninstall)

        btnPrimary?.setOnClickListener { onPrimaryClick(context) }
        btnUninstall?.setOnClickListener { confirmUninstall(context) }

        val dlg = MaterialAlertDialogBuilder(context)
            .setTitle("离线词典")
            .setView(view)
            // 取消按钮始终可用：下载中点「取消」只关闭弹窗，不中断后台下载
            // （DictionaryManager 跑在 lifecycleScope 上，关窗后仍会跑完并更新状态），
            // 用户下次进来能看到最终结果。
            .setNegativeButton("关闭", null)
            .create()
        dialog = dlg
        return dlg
    }

    private fun observe(owner: androidx.lifecycle.LifecycleOwner) {
        val scope = (owner as? androidx.appcompat.app.AppCompatActivity)?.lifecycleScope
            ?: return
        observeJob = scope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DictionaryManager.state.collectLatest { state ->
                    render(state)
                }
            }
        }
    }

    /** 按状态刷新界面。所有文案都在这里收口，避免多处各写一份。 */
    private fun render(state: DictionaryManager.DictState) {
        val bar = progressBar
        val status = textStatus
        val size = textSize
        val primary = btnPrimary
        val uninstall = btnUninstall

        // 默认：进度条隐藏、主按钮可点、卸载按钮隐藏
        bar?.isVisible = false
        primary?.isEnabled = true
        uninstall?.isVisible = false

        when (state) {
            is DictionaryManager.DictState.NotInstalled -> {
                status?.text = "未安装。阅读页长按英文单词点「翻译」即可查词。"
                size?.text = "词典约 23 MB（安装后解压为 55 MB），MIT 许可，可随时卸载"
                primary?.text = "下载词典"
            }

            is DictionaryManager.DictState.Installed -> {
                status?.text = "已安装 · ${state.version} · ${state.rows} 词条"
                size?.text = "占用 ${formatMB(state.sizeBytes)}"
                primary?.text = "重新下载"
                uninstall?.isVisible = true
                uninstall?.isEnabled = true
            }

            is DictionaryManager.DictState.Downloading -> {
                status?.text = "正在下载…"
                bar?.isVisible = true
                setProgress(bar, state.percent)
                size?.text = progressText(state.downloaded, state.total)
                // 下载中禁用主按钮：DictionaryManager 没有做「同事务去重」，
                // 连点会发起两个下载互相覆盖 .part，最终 MD5 必然失败
                primary?.isEnabled = false
                primary?.text = "下载中…"
            }

            is DictionaryManager.DictState.Installing -> {
                status?.text = "下载完成，正在解压…"
                bar?.isVisible = true
                setProgress(bar, state.percent)
                size?.text = "已解压 ${formatMB(state.downloaded)}"
                primary?.isEnabled = false
                primary?.text = "处理中…"
            }

            is DictionaryManager.DictState.Error -> {
                status?.text = "失败：${state.message}"
                size?.text = "可点下方按钮重试"
                primary?.text = "重试下载"
            }
        }
    }

    /** percent < 0 表示总长未知（服务端没给 Content-Length），此时用不确定态动画 */
    private fun setProgress(bar: ProgressBar?, percent: Int) {
        if (bar == null) return
        if (percent < 0) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.progress = percent
        }
    }

    private fun progressText(done: Long, total: Long): String = if (total > 0) {
        "${formatMB(done)} / ${formatMB(total)}"
    } else {
        "已下载 ${formatMB(done)}"
    }

    private fun onPrimaryClick(context: Context) {
        if (downloading) return
        downloading = true
        val scope = context as? androidx.appcompat.app.AppCompatActivity
        if (scope == null) {
            downloading = false
            return
        }
        // ⭐ 不用 viewLifecycleScope：Dialog 不是 LifecycleOwner，
        // 而 DictionaryManager 的下载挂在调用方 Activity 上——用户在下载期间
        // 切走页面时下载继续，回来时状态已是最终态。
        scope.lifecycleScope.launch {
            try {
                DictionaryManager.downloadAndInstall { rows ->
                    Log.i(TAG, "词典安装完成: rows=$rows")
                    // ⚠️ onDone 是**普通（非 suspend）**回调，此刻运行在
                    // downloadAndInstall 的 IO 线程里。而 onInstalled 要弹窗/查词，
                    // 既不能直接碰 UI，也不能在这里写 withContext
                    //（那需要 suspend 上下文，会编译报
                    //  「Suspension functions can be called only within coroutine body」）。
                    // 正解：用 Activity 的 Main 作用域另起一个协程。
                    scope.lifecycleScope.launch {
                        // ⚠️ dismiss() 会触发 setOnDismissListener，那里会把 onInstalled
                        // 置空。所以**先把回调取出来**，再关自己。
                        val after = onInstalled
                        if (dismissOnInstalled) {
                            // 先关自己、再弹释义卡：反过来的话释义卡会叠在这个
                            // 管理窗上面，屏幕上同时留着两个弹窗 —— 那正是用户
                            // 说的"多余的安装完成弹窗"。
                            dialog?.dismiss()
                        }
                        after?.invoke()
                    }
                }
            } finally {
                downloading = false
            }
        }
    }

    private fun confirmUninstall(context: Context) {
        val activity = context as? androidx.appcompat.app.AppCompatActivity
        MaterialAlertDialogBuilder(context)
            .setTitle("卸载离线词典？")
            .setMessage("卸载后阅读页的「翻译」会提示先下载词典，需要时可重新下载。")
            .setPositiveButton("卸载") { _, _ ->
                if (activity == null) return@setPositiveButton
                // ⚠️ uninstall() 要关 SQLite 连接 + 删 dict.db(55MB) + .part(23MB)，
                //   放主线程会掉帧（同文件 ProfileFragment.clearAudioForBook
                //   就是走 Dispatchers.IO，两处保持一致）。
                activity.lifecycleScope.launch {
                    val removed = withContext(Dispatchers.IO) {
                        DictionaryManager.uninstall()
                    }
                    Log.i(TAG, "卸载词典: removed=$removed")
                    render(DictionaryManager.state.value)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatMB(bytes: Long): String =
        if (bytes <= 0L) "0 MB"
        // 显式给 Locale：默认 locale 下阿拉伯语等会输出非 ASCII 数字，
        // 与同屏其它位置的数字对不齐（lint DefaultLocale）
        else String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
}