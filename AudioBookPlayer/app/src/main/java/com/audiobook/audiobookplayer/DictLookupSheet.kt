package com.audiobook.audiobookplayer

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.audiobook.audiobookplayer.model.WordSource
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

/**
 * 查词结果底部弹窗。
 *
 * ## 为什么用 BottomSheetDialog 而不是 AlertDialog
 * 释义可能很长（ECDICT 里`abandon` 这类词有 5+ 条义项、总长数百字），
 * AlertDialog 的内容区高度受限，长文本会被裁掉且无法滚动。
 * BottomSheet 的内容区可占屏更高、且自带拖拽把手，行为符合「临时看一眼就关」的用法。
 *
 * ## 一个 sw 可能返回多个词条
 * 见 [DictionaryManager] 的约定③：去符号后`hood` / `Hood` / `'hood` 归一到同一个键。
 * 因此**每个词条都渲染一块**，而不是只显示第一行 —— 只显示第一条会把用户真正想看的义项藏掉。
 *
 * ## 生命周期约定（与项目其它弹窗一致，见 BookContentActivity.activeDialog）
 * 本弹窗由 Fragment 承载，视图生命周期跟随 Fragment，旋转屏幕不会泄漏 Window。
 */
class DictLookupSheet : BottomSheetDialogFragment() {

    // 入参一律走 arguments（见 onCreateView 的 requireArguments），
    // 不要加「argXxx」普通字段：那些字段既不会被赋值也不参与重建，
    // 曾让 formatExchange 里的自指项过滤恒不生效（拿 argWord 比对，它永远是 ""）。

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext())
        dialog.setOnShowListener {
            // 展开到多高：按内容自适应，但不超过屏幕 70%。
            // 不设这一句时 BottomSheet 默认停在半展开态，长释义需要用户再拖一次才看全。
            val sheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(sheet)
            val target = when {
                sheet.height > 0 -> sheet.height
                else -> (resources.displayMetrics.heightPixels * 0.6f).toInt()
            }
            behavior.peekHeight = target.coerceAtMost(
                (resources.displayMetrics.heightPixels * 0.7f).toInt()
            )
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.sheet_dict_lookup, container, false)
        bind(view)
        return view
    }

    private fun bind(root: View) {
        val word = requireArguments().getString(ARG_WORD).orEmpty()
        val phonetic = requireArguments().getString(ARG_PHONETIC).orEmpty()
        val translations = requireArguments().getStringArrayList(ARG_TRANSLATIONS) ?: ArrayList()
        val tags = requireArguments().getStringArrayList(ARG_TAGS) ?: ArrayList()
        val exchange = requireArguments().getString(ARG_EXCHANGE).orEmpty()
        val footer = requireArguments().getString(ARG_FOOTER).orEmpty()
        // 收藏出处（阅读页查词时传入；null = 旧链路/服务端无出处，收藏时 6 列落 NULL）。
        // getSerializable(String) 自 API 33 起标 deprecated，但 Bundle 里只是进程内传递的
        // WordSource，兼容写法足够，用新重载反而要按 compileSdk 分支。
        @Suppress("DEPRECATION")
        val source = requireArguments().getSerializable(ARG_SOURCE) as? WordSource

        val tvWord = root.findViewById<TextView>(R.id.tvWord)
        val tvPhonetic = root.findViewById<TextView>(R.id.tvPhonetic)
        val tvTags = root.findViewById<TextView>(R.id.tvTags)
        val ll = root.findViewById<LinearLayout>(R.id.llTranslations)
        val tvExchange = root.findViewById<TextView>(R.id.tvExchange)
        val tvEmpty = root.findViewById<TextView>(R.id.tvEmpty)
        val tvFooter = root.findViewById<TextView>(R.id.tvFooter)

        tvWord.text = word
        tvPhonetic.text = phonetic
        tvPhonetic.visibility = if (phonetic.isBlank()) View.GONE else View.VISIBLE

        // 标签：考试级别 + 柯林斯星级（见 formatTags）
        val tagText = formatTags(tags)
        tvTags.text = tagText
        tvTags.visibility = if (tagText.isBlank()) View.GONE else View.VISIBLE

        // 释义：逐条一个 TextView，行间距用 margin 而不是 setSpacingAfter
        // （前者不受 targetSdk 的字体缩放影响，也不会在无障碍大字模式下被压没）
        ll.removeAllViews()
        translations.forEachIndexed { index, line ->
            val tv = TextView(requireContext()).apply {
                text = line
                textSize = 15f
                // ⚠️ 必须用 ?attr/colorOnSurface 而不是写死颜色（见 detail#E6 的 UI 陷阱）：
                // 写死颜色在夜间模式下会是「深底深字」，整块释义直接看不见。
                setTextColor(onSurfaceColor())
                if (index > 0) {
                    // 上边距用像素算好再给 4 参重载：setPaddingRelative 还有
                    // 「相对方向」的 3 参重载，这里必须四个都传，否则只设了 top
                    ViewCompat.setPaddingRelative(
                        this, 0, (6 * resources.displayMetrics.density).toInt(), 0, 0
                    )
                }
            }
            ll.addView(tv)
        }

        // 释义为空：词条存在但 ECDICT 没给中文释义
        tvEmpty.visibility = if (translations.isEmpty()) View.VISIBLE else View.GONE
        if (translations.isEmpty()) {
            tvEmpty.text = "该词条暂无中文释义"
        }

        // 词形变化：只在能解析出中文标签时显示（exchange 是给机器看的原始串）
        val forms = formatExchange(exchange, word)
        tvExchange.text = forms
        tvExchange.visibility = if (forms.isBlank()) View.GONE else View.VISIBLE

        tvFooter.text = footer
        tvFooter.visibility = if (footer.isBlank()) View.GONE else View.VISIBLE

        // 收藏按钮（单词本）：见 bindFavoriteButton
        bindFavoriteButton(
            root.findViewById(R.id.btnFavoriteWord),
            word = word,
            phonetic = phonetic,
            translations = translations,
            source = source
        )

        // 朗读按钮：见 bindSpeakButton
        bindSpeakButton(root.findViewById(R.id.btnSpeakWord), word)
    }

    /**
     * 绑定「朗读这个单词」按钮。
     *
     * ## 为什么弹窗里还要一个按钮（点读不是已经念了吗）
     * 点读在弹窗弹出的同时会自动朗读一次（`BookContentActivity.lookupAndShow` →
     * `speakWordOrExplain`），但那次是「顺手就念了」：周围吵、走神、引擎起播慢，
     * 用户经常没听清，而弹窗出来之后**没有任何重放入口** —— 只能关掉弹窗再点一次单词。
     * 这个按钮把那句话变成可重复的动作，与自动朗读并存。
     *
     * ## 复用 [TextToSpeaker] 而不是自己 new 一个 TextToSpeech
     * 它已经是进程级单例：`Locale.US`（念英文词，不用设备默认 locale）、
     * 单词语速 0.92x、`QUEUE_FLUSH`（连点时后者赢）。另建一份会与它抢引擎、
     * 越点越慢，而且语言/语速会悄悄漂移。
     *
     * ## 引擎不可用要明说，不能静默
     * 设备可能压根没装 TTS 引擎（精简 ROM、不带 Google TTS 的模拟器）。
     * 此时 [TextToSpeaker.speak] 返回 false，必须提示原因 ——
     * 否则用户点了没声音，只会以为这个按钮坏了。释义本身不依赖引擎，照常可看。
     *
     * ## 朗读用「原词」而不是 stripWord 后的词
     * `stripWord` 是**去重键**（hood / Hood / 'hood 归一），不是发音文本；
     * 拿它去念会把 `'hood` 念成 `hood`、把带连字符的复合词念断。发音要用展示给用户的那个词。
     */
    private fun bindSpeakButton(btn: ImageButton, word: String) {
        if (word.isBlank()) {
            // 理论上查得到词就不会为空；真为空则藏起按钮，不给一个点了没反应的死控件
            btn.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE
        btn.setOnClickListener {
            if (!TextToSpeaker.speak(requireContext(), word)) {
                Toast.makeText(
                    requireContext(),
                    "本机未安装语音引擎，无法朗读（释义仍可查看）",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 绑定「收藏到单词本」按钮：两态（已收藏/未收藏）+ 点击切换。
     *
     * ## 两态怎么来的
     * 打开弹窗时查一次 [WordBookRepository.isSaved] 决定初始图标；点击时在
     * 「加/ 删」之间切换并同步换图标 + Toast。图标是两份drawable（灰星/ 琥珀星），
     * 不靠 tint（tint 会被控件 app:tint 覆盖，见 drawable/ic_word_book.xml 注释）。
     *
     * ## 去重键用 stripWord 而不是原词
     * 同一词典 sw 可能对应多个展示词（hood / Hood / 'hood），用原词去重会插出多条；
     * 用 [DictionaryManager.stripWord] 与词典查询、与 word_book 唯一索引同口径。
     *
     * @param phonetic音标，可能空串；translations 释义列表（存快照，卸载词典后仍可读）
     * @param source 收藏出处（书名 + 当前句子位置）。透传给 [WordBookRepository.add]，
     *                收藏时一并落库；null = 无出处（旧收藏语义，单词本里该词将不能跳转）。
     */
    private fun bindFavoriteButton(
        btn: ImageButton,
        word: String,
        phonetic: String,
        translations: List<String>,
        source: WordSource? = null
    ) {
        val sw = DictionaryManager.stripWord(word)
        if (sw.isEmpty()) {
            // 理论上查得到词就不会为空；真为空则藏起按钮，不给一个点了没反应的死控件
            btn.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE

        // 当前收藏态（决定初始图标）。查询是IO + suspend，这里异步做，先按「未收藏」画。
        var saved = false
        fun paint() {
            btn.setImageResource(
                if (saved) R.drawable.ic_word_book_filled else R.drawable.ic_word_book
            )
            btn.contentDescription = if (saved) "取消收藏该单词" else "收藏该单词到单词本"
        }
        paint()

        lifecycleScope.launch {
            saved = WordBookRepository.isSaved(sw)
            paint()
        }

        btn.setOnClickListener {
            lifecycleScope.launch {
                // 用仓储的实际返回值决定新状态：写失败（磁盘满/表锁）时保持原状，
                // 而不是盲目翻转成「已收藏」—— 那样图标与库里真实状态就不一致了。
                saved = if (saved) {
                    !WordBookRepository.remove(sw)
                } else {
                    // 存词条快照：phonetic + translations，卸载词典后收藏仍可读；
                    // 同时把出处 6 字段透传落库（重复收藏 = 刷新为最近一次出处）。
                    WordBookRepository.add(
                        sw, word, phonetic, translations,
                        bookName = source?.bookName,
                        bookKey = source?.bookKey,
                        storeBookId = source?.storeBookId,
                        segmentNumber = source?.segmentNumber,
                        lineNumber = source?.lineNumber,
                        sentenceText = source?.sentenceText
                    )
                }
                paint()
                Toast.makeText(
                    requireContext(),
                    if (saved) "已收藏到单词本" else "已取消收藏",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 取主题的「正文主色」。
     *
     * ⚠️ 不用写死颜色（detail#E6的 UI 陷阱）：夜间模式下写死颜色会变成
     * 「深底深字」，整块释义直接看不见。兜底值取浅灰而不是纯黑，
     * 因为它在浅色主题上也只是偏灰，不会刺眼。
     */
    private fun onSurfaceColor(): Int {
        val ta = requireContext().theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary)
        )
        return try {
            ta.getColor(0, 0xFF333333.toInt())
        } finally {
            ta.recycle()
        }
    }

    /**
     * 标签文本：`中考 · 高考 · CET4 · 柯林斯 ★★★`
     *
     * 标签顺序由 [DictionaryManager.parseTags] 按学习阶段排好（zky → gk → zk → cet4 → cet6 → ky → …），
     * 这里只做展示，最多取 4 个，避免一行挤七八个标签把单词区撑开。
     */
    private fun formatTags(tags: List<String>): String {
        if (tags.isEmpty()) return ""
        val parts = tags.take(4).map { it.uppercase() }
        return parts.joinToString(" · ")
    }

    /**
     * 词形变化文本：`过去式 ran，现在分词 running，第三人称单数 runs，复数 runs`
     *
     * 三个要点：
     *  1. **同一形态可能对应多个标签**（`runs` 既是第三人称单数也是复数），
     *     所以走 [DictionaryManager.exchangeLabelsAll] 取全部，不是取第一个；
     *  2. 跳过「变化后与当前词相同」的自指项（`d:run` 对 run 自身是「过去分词 run」，
     *     展示出来等于重复当前词，没有信息量）；
     *  3. 同一形态的多个标签要**去重后合并成一项**，否则会出现
     *     「第三人称单数 runs，复数 runs」这种重复行 —— 这里用一个 map 按形态归并。
     */
    private fun formatExchange(exchange: String, word: String): String {
        if (exchange.isBlank()) return ""
        // 先收集 形态 -> 标签集合，去掉与当前词同形的自指项
        val byForm = LinkedHashMap<String, MutableList<String>>()
        for (part in exchange.split("/")) {
            val idx = part.indexOf(':')
            if (idx <= 0) continue
            val form = part.substring(idx + 1)
            // word 由形参传入（曾读一个从未赋值的字段 ⇒ 恒为 "" ⇒ 这行过滤从不生效）
            if (form.equals(word, ignoreCase = true)) continue
            val labels = DictionaryManager.exchangeLabelsAll(exchange, form)
            if (labels.isEmpty()) continue
            byForm.getOrPut(form) { ArrayList() }.addAll(labels)
        }
        if (byForm.isEmpty()) return ""
        val text = byForm.entries.joinToString("，") { (form, labels) ->
            // labels 已按 exchange 顺序去重（同一 key 只会命中一次）
            labels.distinct().joinToString("/") + " $form"
        }
        return "词形变化：$text"
    }

    companion object {
        private const val TAG = "DictLookupSheet"
        private const val ARG_WORD = "word"
        private const val ARG_PHONETIC = "phonetic"
        private const val ARG_TRANSLATIONS = "translations"
        private const val ARG_TAGS = "tags"
        private const val ARG_EXCHANGE = "exchange"
        private const val ARG_FOOTER = "footer"
        /** 收藏出处（[WordSource]，Serializable，经 arguments 进程内传递） */
        private const val ARG_SOURCE = "word_source"

        /**
         * 展示查词结果。
         *
         * @param entries 命中的词条（可多个，见类注释）
         * @param footer  底部说明（通常是词典来源与许可）
         * @param source  收藏出处。阅读页查词时由 [com.audiobook.audiobookplayer.BookContentActivity]
         *                用 [WordSource.build] 组装（书名 + 句子原文 + 行号 + bookKey），
         *                经 arguments 传进弹窗，收藏按钮点击时落库；不传 = 无出处。
         */
        fun show(
            fragment: androidx.fragment.app.FragmentActivity,
            word: String,
            entries: List<DictionaryManager.DictEntry>,
            footer: String,
            source: WordSource? = null
        ) {
            if (fragment.isFinishing || fragment.isDestroyed) return
            // 先摘掉已展示的同名弹窗：连点两次「翻译」会叠两个 sheet
            val existing = fragment.supportFragmentManager
                .findFragmentByTag(TAG) as? DictLookupSheet
            existing?.dismissAllowingStateLoss()

            val sheet = DictLookupSheet()
            val args = Bundle().apply {
                putString(ARG_WORD, word)
                putString(ARG_PHONETIC, entries.firstOrNull()?.phonetic.orEmpty())
                putStringArrayList(
                    ARG_TRANSLATIONS,
                    ArrayList(entries.flatMap { it.translations })
                )
                putStringArrayList(ARG_TAGS, ArrayList(entries.firstOrNull()?.tags ?: emptyList()))
                putString(ARG_EXCHANGE, entries.firstOrNull()?.exchange.orEmpty())
                putString(ARG_FOOTER, footer)
                putSerializable(ARG_SOURCE, source)
            }
            sheet.arguments = args
            try {
                sheet.show(fragment.supportFragmentManager, TAG)
            } catch (e: Exception) {
                // FragmentManager 处于异常状态（如 Activity 正在销毁）时 show 会抛
                // 查词是个「顺手」功能，为此崩掉整页不值得
                Log.w(TAG, "展示释义弹窗失败", e)
            }
        }
    }
}