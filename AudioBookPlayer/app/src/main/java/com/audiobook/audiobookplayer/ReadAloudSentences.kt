package com.audiobook.audiobookplayer

/**
 * 句级高亮：把一行原文切成若干**句子区间**。
 *
 * ## 为什么需要它
 * 阅读页的朗读单位原本是**行**（P0-3 重排之后一行就是一个自然段），
 * 所以旧实现只能整行高亮 —— 一段 90 字，屏幕上同时亮着 4~5 行，
 * 用户看不出"现在念到哪儿"（2026-11-02 UI 走查 P2-10）。
 *
 * ## 为什么不是「按引擎回报的字符区间切」
 * 最初的实现用系统 TTS 的 `onRangeStart` 把发音区间归到句子上。该回调 API 26+
 * 才有，且**要靠引擎主动实现** —— 真机（K30 Pro，`com.xiaomi.mibrain.speech`）
 * 实测朗读 20 多秒、几十条 utterance，`onRangeStart` **一次都没回调**，
 * 高亮因此始终停在整行。所以朗读侧改为**逐句入队**（一条 utterance = 一句），
 * 由 `onStart` 直接确定"正在读哪句"；本类只负责把行切成句子。
 *
 * ## 判句规则（刻意只做「句末标点」一种）
 * 句末：`。！？；…` 与半角 `.!?;`；句末标点后面紧跟的
 *  · 右引号 / 右括号（`” ’ ） 》 」 』 】 " '`）—— `他说：“走吧。”` 的收尾引号
 *    必须属于本句，否则高亮边界会落在引号左侧，看着像"少亮一个字符"；
 *  · 或更多句末标点（`？！`、`……`）—— 连写时是一个收束，不该切成两句。
 * 这两类一并并入本句。
 *
 * 不做「按字数硬切」：中文没有空格，硬切会切在词中间，
 * 高亮边界一旦落在词里，比整段高亮更让人困惑。
 */
internal object ReadAloudSentences {

    /** 句末标点（中英文各一套；`…` 常连写，按单字符判即可） */
    private const val TERMINATORS = "。！？；…!?;"

    /** 句末标点后被并入本句的收尾符号 */
    private const val TRAILING = "”’）)》」』】\"'"

    /**
     * 把 [text] 切成句子，返回**闭区间**列表（相对 [text] 的下标），按出现顺序。
     *
     * 空文本 / 全空白返回空列表。首尾空白不并入句子 —— 句子区间直接拿来当高亮范围，
     * 带着行首缩进点亮会看起来"多亮了两格"。
     * 没有任何句末标点时整段就是一句（返回单个区间）。
     */
    internal fun sentenceRanges(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>(4)
        var start = 0
        // 行首空白不计入第一句
        while (start < text.length && text[start].isWhitespace()) start++
        var i = start
        while (i < text.length) {
            if (isTerminatorAt(text, i)) {
                var end = i + 1
                // 句末标点之后的收尾符号与连写标点都并入本句
                while (end < text.length &&
                    (TRAILING.indexOf(text[end]) >= 0 || isTerminatorAt(text, end))
                ) {
                    end++
                }
                if (end > start) out.add(start..(end - 1))
                // 下一句从第一个非空白字符起：英文句号后面那个空格不该算进下一句
                start = end
                while (start < text.length && text[start].isWhitespace()) start++
                i = start
                continue
            }
            i++
        }
        if (start < text.length) {
            // 末尾可能带行尾空白，收掉（高亮不需要把空格点亮）
            var end = text.length - 1
            while (end > start && text[end].isWhitespace()) end--
            out.add(start..end)
        }
        return out
    }

    /**
     * 该位置是不是句末标点。
     *
     * 英文句点单独判：`3.14`、`Mr. Smith` 里的点不是句末 ——
     * 只有后面紧跟空白 / 文本结束 / 收尾符号时才算。
     * 中文句号不存在这层歧义，其余标点一律按句末处理。
     */
    private fun isTerminatorAt(text: String, index: Int): Boolean {
        val c = text[index]
        if (c == '.') {
            val next = text.getOrNull(index + 1)
            return next == null || next.isWhitespace() || TRAILING.indexOf(next) >= 0
        }
        return TERMINATORS.indexOf(c) >= 0
    }
}
