package com.audiobook.audiobookplayer

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * **中文字典**结果卡：展示一个汉字的读音与释义。
 *
 * 由阅读页「长按选中单个字 → 查字典」唤起。数据来自 [ChinDictManager]（随包的
 * `assets/chinese_dict.sqlite`，16142 条常用字）。
 *
 * ## 与英文查词卡 [DictLookupSheet] 分开而不复用
 *
 * 两者视觉结构相似（头 + 分隔线 + 释义列表），但**数据结构不同**：
 * DictLookupSheet 的 `translations` 是 ECDICT 的一行行义项、`exchange` 是词形变化、
 * 还有单词本收藏按钮与考试标签；本卡只有「拼音 + 释义」。
 * 硬塞进同一个 Fragment 会留下一堆 `if (isChinese)` 分支，
 * 而两者永远只会被各自的功能入口调用，不存在混用场景。
 *
 * ## ⚠️ 入参必须走 arguments，不能存普通字段
 *
 * Fragment 在转屏 / 进程被杀后会被**重建**，那时 `newInstance` 不再执行，
 * 普通字段全是默认值（空串 / 0）而 `onDestroyView` 已把引用置空
 * ⇒ 表现为「顶部空白、点按钮只 dismiss，什么都不做且不留痕迹」。
 * 与 DictLookupSheet / TextActionSheet 是同一个坑，此处沿用它们的做法。
 */
class ChinDictSheet : BottomSheetDialogFragment() {

    private val argChar: String get() = requireArguments().getString(ARG_CHAR).orEmpty()

    /** 读音。多音字由 [show] 用「 / 」把多条拼在一起，这里原样展示。 */
    private val argPinyin: String get() = requireArguments().getString(ARG_PINYIN).orEmpty()

    /**
     * 释义条目，**多行**拼成一条字符串传入。
     *
     * 用 `ARG_` 前缀拼接成一个字符串而不是 `Bundle.putStringArrayList`：
     * Bundle 的 ArrayList 在重建时需要 ClassLoader 参与反序列化，
     * 踩坑点比直接拼一个带分隔符的字符串多得多。
     * 分隔符用 `\u0000`（不可能出现在释义正文里的控制符），见 [DEF_JOINER]。
     */
    private val argDefBlock: String get() = requireArguments().getString(ARG_DEFS).orEmpty()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.sheet_chin_dict_lookup, container, false) as LinearLayout
        bind(root)
        return root
    }

    private fun bind(root: LinearLayout) {
        val ch = argChar
        val tvChar = root.findViewById<TextView>(R.id.tvChar)
        val tvPinyin = root.findViewById<TextView>(R.id.tvPinyin)
        val llDefs = root.findViewById<LinearLayout>(R.id.llDefinitions)
        val tvEmpty = root.findViewById<TextView>(R.id.tvEmpty)

        tvChar.text = ch

        // 拼音可能为空（理论上字库每条都有，但别让空串占一行）
        val pinyin = argPinyin.trim()
        if (pinyin.isEmpty()) {
            tvPinyin.visibility = View.GONE
        } else {
            tvPinyin.text = pinyin
            tvPinyin.visibility = View.VISIBLE
        }

        val defs = argDefBlock.split(DEF_JOINER).map { it.trim() }.filter { it.isNotEmpty() }
        // ⚠️ 必须先 removeAllViews：Fragment 重建时 onCreateView 会再跑一次，
        // 不清空就会把上一轮的释义再追加一遍（表现为「点开第二次条目翻倍」）。
        llDefs.removeAllViews()
        if (defs.isEmpty()) {
            tvEmpty.text = "《新华字典》未收录这个字"
            tvEmpty.visibility = View.VISIBLE
            return
        }
        tvEmpty.visibility = View.GONE

        val color = onSurfaceColor()
        val padTop = (6 * resources.displayMetrics.density).toInt()
        for ((i, d) in defs.withIndex()) {
            llDefs.addView(TextView(requireContext()).apply {
                text = d
                textSize = 15f
                setTextColor(color)
                // 首条不加上间距，视觉上与拼音/分隔线连成一组
                if (i > 0) {
                    ViewCompat.setPaddingRelative(this, 0, padTop, 0, 0)
                }
            })
        }
    }

    /** 走主题取正文色；写死颜色会在夜间模式下「深底深字」 */
    private fun onSurfaceColor(): Int {
        val typed = android.util.TypedValue()
        val found = requireContext().theme.resolveAttribute(
            com.google.android.material.R.attr.colorOnSurface, typed, true
        )
        return if (found) typed.data else android.graphics.Color.GRAY
    }

    companion object {
        private const val TAG = "ChinDictSheet"
        private const val ARG_CHAR = "arg_char"
        private const val ARG_PINYIN = "arg_pinyin"
        private const val ARG_DEFS = "arg_defs"

        /**
         * 释义条目的分隔符。
         *
         * 用 U+0000：ASCII 控制符，**不可能出现在汉字释义正文里**。
         * 试过用 `\n` 作分隔 —— 失败：释义正文里本来就有换行（排版换行），
         * 按 `\n` 切会把一条释义劈成好几段。
         */
        private const val DEF_JOINER = "\u0000"

        /**
         * 打开中文释义卡。
         *
         * @param ch  查的那个字（显示用）
         * @param entries [ChinDictManager.lookup] 的结果；为空时弹「未收录」
         * @return true 已显示；false 宿主里已有同类弹窗（避免叠加）
         */
        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            ch: Char,
            entries: List<ChinDictManager.ChinEntry>
        ): Boolean {
            if (fragmentManager.findFragmentByTag(TAG) != null) return false
            // 多音字：每条一个读音，全部展示（用「/」分隔）——
            // 只显示第一条会让「行」这个字只查到 xíng，用户以为字典错了。
            val pinyin = entries.map { it.pinyin }.filter { it.isNotBlank() }.joinToString(" / ")
            // 同一读音下可能有多个条目（库里按 id 分行），全部保留
            val defs = entries.flatMap { it.definitions }
            return try {
                ChinDictSheet().apply {
                    arguments = Bundle().apply {
                        putString(ARG_CHAR, ch.toString())
                        putString(ARG_PINYIN, pinyin)
                        putString(ARG_DEFS, defs.joinToString(DEF_JOINER))
                    }
                }.show(fragmentManager, TAG)
                true
            } catch (e: Exception) {
                // 与 DictLookupSheet / TextActionSheet 一致：FragmentManager 异常状态
                // 下show() 会抛（如已 saveState 后再提交），为这崩掉整页不值得。
                android.util.Log.w(TAG, "显示中文字典卡失败", e)
                false
            }
        }
    }
}