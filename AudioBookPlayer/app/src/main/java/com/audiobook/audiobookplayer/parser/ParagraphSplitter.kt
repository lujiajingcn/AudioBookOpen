package com.audiobook.audiobookplayer.parser

/**
 * 增量（流式）分句器。
 *
 * 「按句切分 + 每 ≤100 字打包成一段」的状态化增量版本。设计目标：
 *
 * 1. **流式、不驻留整本**：逐章节 `feed()`，全程只保存「当前正在打包的段落」+
 *    「还没遇到句末标点的半句」，不再用 StringBuilder 拼出整本书纯文本。
 *    大书（几十万字）整本驻留堆内存，低端机直接 OOM。
 *
 * 2. **切分点确定性**：`feed` 多次（逐章喂）与 `feed` 一次（整本喂）的切分结果
 *    **逐字节一致**。靠 `pending` 缓冲跨 chunk / 跨章节的"残句"——上一章没以句末
 *    标点结尾的尾巴，会带到下一章开头继续拼，不会在章节交界被硬切成两段。
 *
 * 注意：**本类在客户端不承担「与音频片段对齐」的职责**，也不要把它当成服务端
 * TextSegmenter 的等价物 —— 两者有两处刻意的差异：
 * - 每段上限不同：这里 [MAX_PARAGRAPH_LENGTH] = 100（阅读显示粒度），服务端是 30
 *   （TTS 单次合成的成本护栏）；
 * - 超长句的硬切策略不同：服务端会优先在 [，、；：,;] 等次级停顿处落刀以免劈开词，
 *   这里直接按 maxLength 硬切。
 *
 * 因此本地段落与服务端音频片段**下标并不对应**，二者的映射由 [EbookSegmentMap]
 * 按文本内容（忽略空白）定位完成，不依赖段落边界一致。
 *
 * ## 两种模式（[lineBreakAsParagraph]）
 *
 * 默认（false）是「把换行当普通空白」的老行为：整本当作一个连续文本流，只按句
 * 打包。缺点是把原文的**段落结构**抹平了 —— 手工排版过的 TXT（每行一段、段首留
 * 缩进）会被合并成几大块，且每块的开头都被 `trim()` 裁掉缩进，读起来像一整段。
 *
 * 传 true 则**把原文换行当作段落边界**，并且**保留行首空白**（只裁行尾）：
 * 原文一行就是一个段落候选，段首的缩进原样带到阅读页；行内超过 [maxLength] 时才
 * 继续按句打包，缩进只贴在该行的第一段上。
 *
 * **只有 TXT 路径用它**，原因见 [lineBreakAsParagraph] 的注释（EPUB 的换行来自
 * HTML 源码缩进，是噪音而非排版）。
 *
 * @property maxLength       单段最大字符数（阅读显示粒度，与服务端的 30 刻意不同）
 * @property sentenceEnd     句末标点（匹配后即视为一句结束，其后空白一并吞掉）
 * @property lineBreakAsParagraph 是否把换行当段落边界并保留行首缩进（TXT 专用，默认关）
 */
class ParagraphSplitter(
    private val maxLength: Int = MAX_PARAGRAPH_LENGTH,
    private val sentenceEnd: Regex = SENTENCE_BOUNDARY,
    /**
     * 换行是否算「段落边界」，且行首空白是否保留。
     *
     * **TXT 传 true、EPUB 保持 false**，不是遗漏而是刻意的：
     * - TXT 的换行与行首空格是**作者排版**（本例原文段首 8 个半角空格、续行 4 个），
     *   保留它才能让阅读页与原文排版一致；
     * - EPUB 的换行是 [stripHtmlTags] 把块级标签（`<p>`、`<br>`）转出来的，
     *   行首那点空白往往是 **XHTML 源码的缩进**，属于噪音 —— 打开这个开关只会让
     *   每一段随机多出一个空格。
     *
     * 两种格式共用本类是为了不出现第二套切分口径；行为差异靠这个参数表达。
     */
    private val lineBreakAsParagraph: Boolean = false
) {
    /** 已完成的段落 */
    private val out = mutableListOf<String>()

    /** 当前正在打包的段落（不含句末以后的空白） */
    private val paragraph = StringBuilder()

    /** 还没遇到句末标点的半句：跨 feed / 跨章节拼接，保证切分点确定 */
    private val pending = StringBuilder()

    /** 换行模式下：本行的行首空白还没摘取（遇到换行、或 finish 后重置为 true） */
    private var needExtractLead = true

    /**
     * 换行模式下：已经摘出来、等待贴到「本行第一段」前面的行首空白。
     * null = 还没摘；"" = 摘到的是空（本行无缩进）或已经贴出过。
     */
    private var leadToAttach: String? = null

    /** 喂入一段文本（可以是整本，也可以是某个章节；多次喂入的切分结果与一次喂入完全一致）。 */
    fun feed(chunk: String) {
        if (!lineBreakAsParagraph) {
            feedLegacy(chunk)
            return
        }
        pending.append(chunk)
        drainLines(carryOver = true)
    }

    /** 收尾：把残留在缓冲里的不足 maxLength 的尾巴也吐出来。 */
    fun finish(): List<String> {
        if (lineBreakAsParagraph) {
            drainLines(carryOver = false)
            return out.toList()
        }
        if (pending.isNotEmpty()) {
            val tail = pending.toString().trim()
            pending.setLength(0)
            if (tail.isNotEmpty()) pack(tail)
        }
        if (paragraph.isNotEmpty()) {
            emit(paragraph.toString().trim())
            paragraph.setLength(0)
        }
        return out.toList()
    }

    // ---------------- 换行模式（TXT） ----------------

    /**
     * 把 [pending] 里能确定的部分切成「原文行」并产出。
     *
     * @param carryOver true = 后面还会有 `feed`（未收尾），此时**不能**把「还没等到换行
     *                  的尾巴」当成一行交付，也不能在两块之间把行首缩进判死；
     *                  false = 输入已结束（[finish]），剩余内容就是最后一行。
     */
    private fun drainLines(carryOver: Boolean) {
        while (pending.isNotEmpty() || !carryOver) {
            // ① 行首：把原文的缩进摘出来单独保存 —— 它不能被接下来的按句打包 trim 掉
            if (needExtractLead) {
                val n = leadingIndentEnd(pending)
                // buffer 里全是空白（缩进可能只喂到一半，例如块边界恰好切在 8 个空格中间）
                // ⇒ 等下一块，否则会把「半个缩进」当成完整的，另外半截落到下一行行首
                if (carryOver && n == pending.length) return
                leadToAttach = pending.substring(0, n)
                pending.delete(0, n)
                needExtractLead = false
            }

            // ② 换行 = 行边界。**先切出这一行的文本、再删换行符、最后才交给打包**：
            //    不能反过来（先打包再按旧下标删 pending）—— 打包会消费 pending，
            //    之前算出来的换行下标就失效了，删的会是后面的内容。
            val lb = indexOfLineBreak(pending, atEnd = !carryOver)
            if (lb != null) {
                val line = pending.substring(0, lb.first)
                pending.delete(0, lb.second)
                consumeLine(line)
                flushParagraph()
                needExtractLead = true
                leadToAttach = null
                continue
            }

            // ③ 还没遇到换行：先把完整的句子消费掉。这一步同时保证「无换行的巨行」不会把
            //    pending 撑爆 —— 与旧实现一样，pending 里只留「还没等到句末标点的半句」。
            consumeCompleteSentences()
            if (carryOver) return

            // ④ 收尾：剩下的就是最后一行（可能连句末标点都没有，如原文末尾的「和物。」）
            val tail = pending.toString()
            pending.setLength(0)
            if (tail.isNotBlank()) consumeLine(tail)
            flushParagraph()
            return
        }
    }

    /**
     * 按句打包一段**已经切好的完整文本**（一行，或换行之前的全部内容）。
     *
     * 与 [consumeCompleteSentences] 的分工：本方法不读也不改 [pending]，因此调用方
     * 可以先用 [indexOfLineBreak] 把行切出来再交进来，不存在「下标被中途消费掉」的问题。
     */
    private fun consumeLine(text: String) {
        var rest = text
        while (rest.isNotEmpty()) {
            val end = findSentenceEnd(rest, stopAtLineBreak = true)
            val piece = if (end < 0) rest else rest.substring(0, end)
            rest = if (end < 0) "" else rest.substring(end)
            val sentence = piece.trim()
            if (sentence.isNotEmpty()) pack(sentence)
        }
    }

    /**
     * 按句消费 [pending] 开头已完整的内容，残缺的半句留在 [pending] 等下次 `feed`。
     * 调用方须保证 [pending] 内**没有换行**（否则换行会被当成句末空白吞掉）。
     */
    private fun consumeCompleteSentences() {
        while (pending.isNotEmpty()) {
            val end = findSentenceEnd(pending, stopAtLineBreak = true)
            if (end < 0) break
            val sentence = pending.substring(0, end).trim()
            pending.delete(0, end)
            if (sentence.isNotEmpty()) pack(sentence)
        }
    }

    /** 当前正在打包的段落落盘（行结束时调用）。 */
    private fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            emit(paragraph.toString().trim())
            paragraph.setLength(0)
        }
    }

    /**
     * 行首缩进的长度。**不把换行算作缩进** —— 否则连续空行的 `\n` 会被当成缩进吞掉，
     * 空行分隔作用消失。全角空格（U+3000）与半角空格一视同仁。
     */
    private fun leadingIndentEnd(sb: CharSequence): Int {
        var i = 0
        while (i < sb.length && sb[i] != '\n' && sb[i] != '\r' && sb[i].isWhitespace()) i++
        return i
    }

    /**
     * 第一个换行的区间（start 不含、end 不含，即要删掉 `[start, end)`）。
     * 没有换行返回 null。
     *
     * `\r` 落在 buffer 末尾且输入未结束（`atEnd = false`）时返回 null：此时无法判断
     * 它后面是否还跟着 `\n`（CRLF 被切在两块之间），必须等下一块 —— 否则会把 `\n`
     * 留给下一行，行首从此多一个换行符，分块与整本喂入的结果就不一致了。
     */
    private fun indexOfLineBreak(sb: CharSequence, atEnd: Boolean): Pair<Int, Int>? {
        for (i in sb.indices) {
            when (sb[i]) {
                '\n' -> return i to i + 1
                '\r' -> {
                    if (i + 1 < sb.length && sb[i + 1] == '\n') return i to i + 2
                    if (i == sb.length - 1 && !atEnd) return null
                    return i to i + 1
                }
            }
        }
        return null
    }

    // ---------------- 默认模式（EPUB 等） ----------------

    /** 老行为：换行当普通空白，整本按句打包。 */
    private fun feedLegacy(chunk: String) {
        pending.append(chunk)
        // 反复从 pending 开头抠出"以句末标点 + 后续空白结尾"的完整句子
        while (pending.isNotEmpty()) {
            val end = findSentenceEnd(pending)
            if (end < 0) break // 还没遇到句末标点，等后续 chunk
            val sentence = pending.substring(0, end).trim()
            pending.delete(0, end)
            if (sentence.isNotEmpty()) pack(sentence)
        }
    }

    /** 把一句塞进当前段落，超长则强制截断成多段。 */
    private fun pack(sentence: String) {
        // 当前段落加上新句子超长，先保存
        if (paragraph.length + sentence.length > maxLength && paragraph.isNotEmpty()) {
            emit(paragraph.toString().trim())
            paragraph.setLength(0)
        }
        // 单个句子超长，强制截断
        if (sentence.length > maxLength) {
            if (paragraph.isNotEmpty()) {
                emit(paragraph.toString().trim())
                paragraph.setLength(0)
            }
            var remaining = sentence
            while (remaining.length > maxLength) {
                emit(remaining.substring(0, maxLength))
                remaining = remaining.substring(maxLength)
            }
            paragraph.append(remaining)
        } else {
            paragraph.append(sentence)
        }
    }

    /**
     * 返回 pending 中第一个句子结束的位置（含其后空白，exclusive）；没有句末标点返回 -1。
     *
     * @param stopAtLineBreak 吞「句末标点之后的空白」时，遇到换行就停下。
     *        换行模式下必须为 true —— 否则「他说。\n他没说。」里的换行会被当作句末
     *        空白一起吞掉，原文的行结构就在这一步丢掉了（正是本次要修的问题）。
     */
    private fun findSentenceEnd(s: CharSequence, stopAtLineBreak: Boolean = false): Int {
        val m = sentenceEnd.find(s) ?: return -1
        var end = m.range.last + 1
        while (end < s.length && s[end].isWhitespace()) {
            if (stopAtLineBreak && (s[end] == '\n' || s[end] == '\r')) break
            end++ // 吃掉句末以后的空白，与 split 的 \s* 一致
        }
        return end
    }

    /**
     * 落一个段落。换行模式下，本行的行首缩进会贴在这一行**第一段**的前面（只贴一次）。
     * 默认模式下 [leadToAttach] 恒为 null，等价于直接 `out.add`。
     */
    private fun emit(text: String) {
        val lead = leadToAttach
        leadToAttach = null
        out.add(if (lead.isNullOrEmpty()) text else lead + text)
    }

    companion object {
        /** 与服务端一致：中文句号/叹号/问号 + 英文 . ! ? */
        val SENTENCE_BOUNDARY: Regex = Regex("[。！？.!?]")

        /**
         * 单段最大字符数。**不要照抄服务端的 30**：服务端取 30 是为了压 TTS 单次合成的
         * 最坏耗时与内存（段数是成本的直接乘数），而客户端的段落只用于阅读显示 ——
         * 切到 30 会让正文每 30 字就换行，阅读体验明显变差。
         */
        const val MAX_PARAGRAPH_LENGTH: Int = 100

        /**
         * 把「一批行」切成段落，供**已经有行的调用方**使用。
         *
         * 与解析器的区别：解析器负责从文件里**造**出行（读 txt / 解 zip / 查解析缓存），
         * 本函数只负责行 → 段。抽出它是因为「切分口径」必须只有一处实现 ——
         * 测试若在用例里自己重写一遍 `feed(it + "\n")`，那只测到了测试自己的副本，
         * 生产代码改了粒度测试照样绿。
         *
         * 用**默认模式**（`lineBreakAsParagraph = false`）：这里的行来自 EPUB 的
         * `stripTags` 或 PDF 的版面抽取，换行都不是作者排版，行首缩进属于噪音。
         *
         * 逐行喂而不是先 `joinToString`：与 [EpubParser] 逐章喂同一形态，
         * 且不用为大书拼出第二份整本字符串。
         */
        fun splitRows(rows: List<String>): List<String> {
            val splitter = ParagraphSplitter()
            rows.forEach { splitter.feed(it + "\n") }
            return splitter.finish()
        }
    }
}
