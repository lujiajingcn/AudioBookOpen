package com.audiobook.audiobookplayer.moss

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.audiobook.audiobookplayer.BookContentActivity

/**
 * 把本机合成的终态结论呈现给用户。
 *
 * 抽成独立文件是因为**两个页面都要用**：合成页（[com.audiobook.audiobookplayer.TtsFragment]）
 * 与书架页（[com.audiobook.audiobookplayer.BookListFragment]）。合成可以从书架的
 * 「继续合成」直接发起，那时合成页根本没被创建 —— 只在合成页里做提示，
 * 等于对这条最常见的路径完全静默。
 *
 * ## 为什么只有「中止 / 完成」用 Toast
 * 系统 Toast 的正文是**单行**的，超宽直接省略号截断。真机实测过一版把
 * 「已中止：已完成 11/3242 段，已合成的段保留，可在书架点『继续合成』补齐」
 * 整句塞进 Toast，屏幕上只显示到「可在书架点『继续…』」—— 最关键的
 * 「去哪补」正好被截掉了，等于没说。
 *
 * 所以规则是：**文案可能长的一律用对话框，能压到一行内的才用 Toast**。
 * 失败/部分完成的正文是服务端给的原始原因（长度不可控），必须走对话框；
 * 中止/全部完成只有段数，短且确定。
 */
fun showSynthesisOutcome(context: Context, outcome: LocalSynthesisService.Outcome) {
    when (outcome.level) {
        LocalSynthesisService.Outcome.Level.FAILED ->
            showOutcomeDialog(context, outcome, "《${outcome.bookName}》本机合成失败")

        LocalSynthesisService.Outcome.Level.PARTIAL ->
            showOutcomeDialog(context, outcome, "《${outcome.bookName}》部分完成")

        // 中止：只报段数。「已合成的段保留」这件事在书架那行（部分完成 +
        // 「继续合成剩余 N 段」按钮）上本来就看得见，写进 Toast 只会把句子撑长。
        LocalSynthesisService.Outcome.Level.CANCELLED,
        LocalSynthesisService.Outcome.Level.DONE ->
            Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
    }
}

private fun showOutcomeDialog(
    context: Context,
    outcome: LocalSynthesisService.Outcome,
    title: String
) {
    // 服务给的文案形如「合成失败：<原因>」，标题已经说清结论，
    // 正文只留原因，否则对话框里「失败」会出现两遍。
    val detail = outcome.message
        .removePrefix("合成失败：")
        .removePrefix("部分完成：")
        .ifBlank { outcome.message }

    // 宿主已经不可用时退回 Toast：在已 finish 的 Activity 上 show 对话框
    // 会抛 BadTokenException（真机上表现为「合成结束那一刻直接闪退」）。
    // isDestroyed 需要 API 17，本工程 minSdk 24，无需再判 SDK_INT。
    val activity = context as? Activity
    if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
        Toast.makeText(context, outcome.message.take(40), Toast.LENGTH_LONG).show()
        return
    }

    val builder = AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(detail)
        .setPositiveButton("知道了", null)

    // 「去阅读页」只在这种原因下给：客户端没有该格式的解析器（PDF），
    // 必须由服务端先把文本提取进缓存。用户唯一的出路就是把书在阅读页打开一次，
    // 没有按钮时这句话只能靠他自己理解「阅读页」在哪、哪本书。
    if (outcome.bookPath.isNotBlank() &&
        outcome.level == LocalSynthesisService.Outcome.Level.FAILED &&
        detail.contains("阅读页")
    ) {
        builder.setNeutralButton("去阅读页") { _, _ ->
            // 文件可能已被用户删除，启动失败不该再抛给用户第二次
            runCatching {
                context.startActivity(
                    Intent(context, BookContentActivity::class.java).apply {
                        putExtra("BOOK_TABLE_NAME", outcome.bookName)
                        putExtra("FILE_PATH", outcome.bookPath)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }
    builder.show()
}
