package com.audiobook.audiobookplayer.parser

import android.util.Log

/**
 * 阅读页「原文行 ↔ 音频片段」的位置索引。
 *
 * ## 为什么需要它
 *
 * 阅读页显示的是**电子书原文**（TXT 按句打包成行、EPUB/MOBI 按本地解析出的行、PDF 按服务端原文行），
 * 而音频片段是按服务端的切段规则（单段 ≤30 字）切出来的 —— 两种粒度**边界天然不对齐**：
 * 一个 30 字的片段可能横跨两行原文，一行原文里也可能压着三四个片段。
 * 于是「行号 == 段号」这个隐含假设不成立，按行号直接播放就会串音
 * （点第 3 行播的却是第 3 个碎片，而第 3 行其实从第 7 个碎片才开始）。
 *
 * 本类把「已拆分的句子文本」（`audio_segments.text_content`）逐条定位回整本原文，
 * 得到 `段号 → 原文区间`，再由区间换算出 `段号 ↔ 行号`，供点击播放与高亮使用。
 *
 * ## 定位规则
 *
 * 1. **忽略空白**：比较时丢掉所有空白字符（`\r`、`\n`、空格、全角空格），
 *    因此**一个片段的区间可以跨行** —— 原文里的换行、行首缩进都不构成障碍，
 *    也顺带吸收了「服务端给块级标签补 `\n`、本地不补」这类纯空白差异。
 * 2. **顺序推进**：第 n 段只从第 n-1 段结束之后开始找。原文里重复出现的句子
 *    （叠句、诗歌、重复的章节标题）不会被前一段抢占而错配。
 * 3. **锚点回退**：整段匹配失败 → 取前 [ANCHOR_LENGTH] 个字符做锚点，**仍然只在
 *    游标之后找**；再失败则该段记为「未定位」，**不影响后面的段**
 *    （后面各段仍从原游标继续找，不会因为一段对不上就整体错位）。
 * 4. **区间不重叠**：锚点估算的区间长度按片段文本推算，可能越过下一段的起点，
 *    由 [clampOverlaps] 裁齐。**重叠是「点 A 播 B」的直接成因** ——
 *    [segmentAtOffset] 按段号升序返回首个覆盖者，交叠区会被靠前那段抢走。
 *
 * ## 精度分级
 *
 * 定位成功的段分两类，**不要混为一谈**：
 * - **精确**：整段文本在原文里原样命中，区间就是原文里那几个字。
 * - **估算**（[isEstimated]）：靠前缀锚点定住起点，长度是推算的，可能偏长。
 *
 * [locatedCount] 两者都算，[estimatedCount] 只算估算。判断「这本书的映射
 * 能不能信」要看后者 —— 否则「30 段里 25 段是猜的」会被当成「25 段对上了」。
 *
 * ## 坐标约定
 *
 * 「全文坐标」= 各行文本用单个 `\n` 连接后的下标（与阅读页一行一个 item 的呈现一致）。
 * 行的区间是 `[rowStarts[i], rowEnds[i])`；片段区间是 `[segmentStart(n), segmentEnd(n))`。
 */
class EbookSegmentMap private constructor(
    private val rowStarts: IntArray,
    private val rowEnds: IntArray,
    private val segStarts: IntArray,
    private val segEnds: IntArray,
    private val segRows: IntArray,
    private val segEstimated: BooleanArray
) {

    /** 阅读页行数 */
    val rowCount: Int get() = rowStarts.size

    /** 最大段号（段号从 1 起） */
    val segmentCount: Int get() = segStarts.size - 1

    /** 成功定位到原文的片段数（诊断用：定位失败过多说明两端文本不同源） */
    val locatedCount: Int get() = (1..segmentCount).count { segStarts[it] >= 0 }

    /**
     * 成功定位、但**区间是估算出来的**段数（整段文本对不上，靠前缀锚点定的位）。
     *
     * 与 [locatedCount] 分开统计，因为两者精度不同：精确命中的段区间就是原文里
     * 那几个字，锚点估算的区间长度是**按片段文本长度推算**的（片段与原文有出入时
     * 会偏长/偏短）。上层要判断「这本书的映射能不能信」时必须看这个数 ——
     * 只看 locatedCount 会把「30 段里 25 段是猜的」当成「30 段里 25 段对上了」。
     */
    val estimatedCount: Int get() = (1..segmentCount).count { segEstimated[it] }

    /** 该段的区间是否由锚点估算而来（未定位的段返回 false） */
    fun isEstimated(segmentNumber: Int): Boolean =
        segmentNumber in 1..segmentCount && segEstimated[segmentNumber]

    // ---------------- 行 ↔ 全文坐标 ----------------

    /** 行下标 → 该行首字符的全文坐标；越界返回 -1 */
    fun offsetOfRow(row: Int): Int = rowStarts.getOrElse(row) { -1 }

    /** 行下标 → 该行文本长度；越界返回 0 */
    fun rowLength(row: Int): Int =
        if (row in rowStarts.indices) rowEnds[row] - rowStarts[row] else 0

    // ---------------- 段 ↔ 全文坐标 ----------------

    fun isLocated(segmentNumber: Int): Boolean = segmentStart(segmentNumber) >= 0

    /** 段号 → 该段在原文中的起始下标；未定位/越界返回 -1 */
    fun segmentStart(segmentNumber: Int): Int = segStarts.getOrElse(segmentNumber) { -1 }

    /** 段号 → 该段在原文中的结束下标（不含）；未定位/越界返回 -1 */
    fun segmentEnd(segmentNumber: Int): Int = segEnds.getOrElse(segmentNumber) { -1 }

    /**
     * 段号 → 该段**起点**所在的行下标；未定位/越界返回 -1。
     *
     * ⚠️ 这只是段覆盖的**第一行**，不等于「用户觉得正在读的那一行」：
     * 服务端切段时换行不是段边界（≤30 字一段），而阅读页是**一行一段**，
     * 于是段会横跨两行，且跨行段的起点常常只是**上一行行尾的一个标点**
     * （`。`、`”`）—— 用它做整行底色就会把底色画到相邻那一行。
     *
     * **滚动定位**（目录跳转、断点续播、收藏跳转）用这个：
     * 把目标行滚到屏幕里就够了，段首行比「重心行」更靠前，可见范围更稳。
     * **高亮**要用 [rowsOfSegmentNumber]（段覆盖的每一行）。
     */
    fun rowOfSegmentNumber(segmentNumber: Int): Int = segRows.getOrElse(segmentNumber) { -1 }

    /**
     * 段号 → 该段**覆盖到的所有行**（连续、升序，含首尾）；未定位/越界返回 null。
     *
     * 首行 = [segmentStart] 所在行，末行 = [segmentEnd] - 1 所在行。
     * 两者都在**原文坐标**里，而 `segmentEnd - 1` 必定是段内**非空白**字符的原文下标
     * （区间由 [Normalized.originalIndex] 还原而来），因此它一定落在某一行内部，
     * 不会落到行间连接符上 —— 首末行都不会偏。
     *
     * 段不跨行时返回单元素区间（`n..n`），此时与 [rowOfSegmentNumber] 一致。
     */
    fun rowsOfSegmentNumber(segmentNumber: Int): IntRange? {
        val start = segmentStart(segmentNumber)
        val end = segmentEnd(segmentNumber)
        if (start < 0 || end <= start) return null
        val first = rowIndexOfOffset(rowStarts, rowEnds, start)
        val last = rowIndexOfOffset(rowStarts, rowEnds, end - 1)
        if (first < 0 || last < first) return null
        return first..last
    }

    /**
     * 段号 → 该段在**它覆盖的每一行**内的字符区间（行下标 to 左闭右开区间，按行号升序）。
     * 未定位返回空 Map。供高亮按行分片使用：整行底色给 [rowsOfSegmentNumber] 的每一行，
     * 行内句底色给这里的对应区间。
     *
     * 段完全覆盖不到的行走不进结果（例如段跨行时中间夹了一个**全空白行**，
     * 该行区间为空 —— 空行本来也没有内容要涂）。
     */
    fun spansInRowsOfSegment(segmentNumber: Int): Map<Int, IntRange> {
        val rows = rowsOfSegmentNumber(segmentNumber) ?: return emptyMap()
        val spans = LinkedHashMap<Int, IntRange>(rows.count())
        for (row in rows) {
            spanInRow(row, segmentNumber)?.let { spans[row] = it }
        }
        return spans
    }

    // ---------------- 点击位置 → 段号 ----------------

    /**
     * 全文坐标 → 覆盖该位置的段号；落在段间空白或未定位处返回 -1。
     * 这就是「点击文本行，判断这属于哪个拆分后的句子」的判据。
     */
    fun segmentAtOffset(offset: Int): Int {
        for (n in 1..segmentCount) {
            val s = segStarts[n]
            if (s >= 0 && s <= offset && offset < segEnds[n]) return n
        }
        return -1
    }

    /**
     * 全文坐标 → 该位置起（含）最近的一段，用于点击落在段间空白（换行、行尾缩进）
     * 或两端文本不同源导致该处没定位到段时，仍然能播「接下来该听到的那段」。
     * 所有段都在该位置之前结束则返回 -1。
     */
    fun segmentAtOrAfterOffset(offset: Int): Int {
        for (n in 1..segmentCount) {
            val s = segStarts[n]
            if (s >= 0 && segEnds[n] > offset) return n
        }
        return -1
    }

    /** 行下标 → 该行（按行首位置）应播放的段号；该行之后没有音频则返回 -1 */
    fun segmentOfRow(row: Int): Int {
        val offset = offsetOfRow(row)
        if (offset < 0) return -1
        val covering = segmentAtOffset(offset)
        if (covering > 0) return covering
        return segmentAtOrAfterOffset(offset)
    }

    /**
     * 段号在指定行内的字符区间（左闭右开：`[first, last + 1)`），用于行内高亮。
     * 该段与这一行无交集时返回 null（例如它整段都落在别的行里）。
     */
    fun spanInRow(row: Int, segmentNumber: Int): IntRange? {
        val start = segmentStart(segmentNumber)
        val end = segmentEnd(segmentNumber)
        if (start < 0 || row !in rowStarts.indices) return null
        val from = maxOf(start, rowStarts[row]) - rowStarts[row]
        val to = minOf(end, rowEnds[row]) - rowStarts[row]
        if (to <= from) return null
        return from until to
    }

    companion object {
        private const val TAG = "EbookSegmentMap"

        /** 锚点长度：整段匹配失败时用来「找到大概位置」的前缀长度 */
        private const val ANCHOR_LENGTH = 8

        /** 锚点下限：短于这个长度不做锚点匹配（太容易命中无关位置，反而把后续段带偏） */
        private const val ANCHOR_MIN = 4

        /** 行连接符：全文坐标里行与行之间恰好占一个字符 */
        private const val ROW_SEPARATOR = "\n"

        /**
         * 建立映射。
         *
         * @param rows     阅读页逐行文本（顺序即显示顺序）
         * @param segments 音频片段：段号 to 文本（须按段号升序）
         * @return 任一入参为空时返回 null（没有可映射的东西，调用方保持旧行为）
         */
        fun build(rows: List<String>, segments: List<Pair<Int, String>>): EbookSegmentMap? {
            if (rows.isEmpty() || segments.isEmpty()) return null
            val starts = IntArray(rows.size)
            val ends = IntArray(rows.size)
            var cursor = 0
            rows.forEachIndexed { i, text ->
                starts[i] = cursor
                ends[i] = cursor + text.length
                cursor = ends[i] + ROW_SEPARATOR.length
            }

            val maxSegment = segments.maxOf { it.first }
            val segStarts = IntArray(maxSegment + 1) { -1 }
            val segEnds = IntArray(maxSegment + 1) { -1 }
            val segEstimated = BooleanArray(maxSegment + 1)
            locate(rows.joinToString(ROW_SEPARATOR), segments, segStarts, segEnds, segEstimated)
            clampOverlaps(segStarts, segEnds, segEstimated, maxSegment)

            val segRows = IntArray(maxSegment + 1) { -1 }
            for (n in 1..maxSegment) {
                if (segStarts[n] >= 0) segRows[n] = rowIndexOfOffset(starts, ends, segStarts[n])
            }

            val map = EbookSegmentMap(starts, ends, segStarts, segEnds, segRows, segEstimated)
            Log.d(
                TAG,
                "位置映射建立完成：${map.locatedCount}/${map.segmentCount} 段定位成功" +
                    "（其中 ${map.estimatedCount} 段为锚点估算），${map.rowCount} 行"
            )
            if (map.locatedCount < map.segmentCount) {
                // 定位失败说明本地原文与服务端切段所用文本不同源（多为编码/解析差异），
                // 这些段会退回「按行号播放」，值得在日志里留痕
                val missing = (1..maxSegment).filter { segStarts[it] < 0 }.take(10)
                Log.w(TAG, "以下段号未能定位到原文：$missing${if (maxSegment > 10) " …" else ""}")
            }
            return map
        }

        /**
         * 把估算区间裁到「下一段的起点」，消除重叠。
         *
         * 锚点回退只用前 [ANCHOR_LENGTH] 个字符确定**起点**，游标也只推进这么多；
         * 而区间长度是按 `游标 + 整段文本长度` 推算的。片段文本与原文有出入时
         * （编码差异、PDF 跨版本丢字），这个估算区间会**越过下一段的起点**，
         * 于是两段的区间交叠。
         *
         * ⚠️ 重叠会直接变成「点 A 播 B」：[segmentAtOffset] 是**按段号升序返回
         * 首个覆盖者**的，交叠区里靠前那段会抢走本该属于后面那段的位置。
         * 所以这里按段号升序把每段的终点压到下一已定位段的起点 ——宁可让估算段
         * 少覆盖几个字（那样只是高亮短了一点），也不能让它盖到别人的地盘上。
         *
         * 只裁不删：裁完若区间变空（说明下一段起点与它重合），才降级为未定位。
         */
        private fun clampOverlaps(
            segStarts: IntArray,
            segEnds: IntArray,
            segEstimated: BooleanArray,
            maxSegment: Int
        ) {
            for (n in 1..maxSegment) {
                if (segStarts[n] < 0) continue
                val next = (n + 1..maxSegment).firstOrNull { segStarts[it] >= 0 } ?: continue
                if (segEnds[n] > segStarts[next]) {
                    segEnds[n] = segStarts[next]
                    // 估算区间被裁到与下一段起点重合 = 这一段实际覆盖不到任何字符，
                    // 留着它只会让 segmentAtOffset 拿到一个空区间，按未定位处理。
                    if (segEnds[n] <= segStarts[n]) {
                        segStarts[n] = -1
                        segEnds[n] = -1
                        segEstimated[n] = false
                    }
                }
            }
        }

        /**
         * 逐段顺序定位，结果写入 [segStarts] / [segEnds]（下标即段号，-1 表示未定位），
         * 靠锚点定位的段在 [segEstimated] 里标记。
         * 游标只在**匹配成功**时前移 —— 一段失败不该把后面各段一起带偏。
         */
        private fun locate(
            fullText: String,
            segments: List<Pair<Int, String>>,
            segStarts: IntArray,
            segEnds: IntArray,
            segEstimated: BooleanArray
        ) {
            val norm = normalize(fullText)
            var cursor = 0
            for ((number, text) in segments) {
                if (number <= 0 || number >= segStarts.size) continue
                val query = normalize(text)
                if (query.length == 0) continue

                val hit = norm.indexOf(query.text, cursor)
                if (hit >= 0) {
                    segStarts[number] = norm.originalIndex(hit)
                    segEnds[number] = norm.originalIndex(hit + query.length - 1) + 1
                    cursor = hit + query.length
                    continue
                }

                // 回退：锚点（**只在游标之后找**）。整段对不上时用前若干字定住起点，
                // 区间长度按片段长度估算（末尾越界由 clampOverlaps 裁掉）。
                //
                // ⚠️ 这里曾经还有一层「全范围再找一次」的兜底，已删除 —— 它
                // **可证明永远有害**：indexOf(anchor, cursor) 已经覆盖了 >= cursor
                // 的全部位置，它返回 -1 之后 indexOf(anchor) 只可能命中 < cursor，
                // 也就是把这一段安到**已经走过**的位置上，产出必然逆序于前段的区间。
                // 后果是段 N 播完时高亮/滚动跳回前一段所在的位置 —— 而用户明明
                // 已经听到第 N 段了。「兜底」看起来是在救场，实际是制造错位。
                if (query.length >= ANCHOR_MIN) {
                    val anchor = query.text.substring(0, minOf(ANCHOR_LENGTH, query.length))
                    val at = norm.indexOf(anchor, cursor)
                    if (at >= 0) {
                        val tail = minOf(norm.length - 1, at + query.length - 1)
                        segStarts[number] = norm.originalIndex(at)
                        segEnds[number] = norm.originalIndex(tail) + 1
                        segEstimated[number] = true
                        cursor = at + anchor.length
                        continue
                    }
                }
                // 彻底找不到：保持未定位，且**不动游标**
            }
        }

        /**
         * 去掉所有空白后的文本，外加「归一化下标 → 原文下标」的映射。
         * 之所以丢掉空白而不是折叠成单个空格：原文与服务端的空白本来就不一致
         * （服务端给块级标签补 `\n`、TXT 里的 `\r\n` 原样保留），
         * 折叠规则稍有出入就会让整段匹配失败；而中文语料里空白不承载语义，丢掉最稳。
         */
        private class Normalized(val text: String, private val toOriginal: IntArray) {
            val length: Int get() = text.length

            fun indexOf(query: String, from: Int): Int {
                if (from > text.length) return -1
                return text.indexOf(query, from)
            }

            fun indexOf(query: String): Int = text.indexOf(query)

            fun originalIndex(normalizedIndex: Int): Int =
                toOriginal[normalizedIndex.coerceIn(0, toOriginal.size - 1)]
        }

        private fun normalize(src: String): Normalized {
            val sb = StringBuilder(src.length)
            val map = IntArray(src.length)
            for (i in src.indices) {
                if (!src[i].isWhitespace()) {
                    map[sb.length] = i
                    sb.append(src[i])
                }
            }
            return Normalized(sb.toString(), map.copyOf(sb.length))
        }

        /**
         * 在一组行文本里按**内容**定位行下标（收藏跳转用）。
         *
         * **为什么不能按行号定位**：收藏表存的是「当时那一行的行号」，而行号是**切分粒度的函数**：
         * 同一本书改一次行切分规则（例如 TXT 从「按句打包 ≤100 字」改成「一行即一段」），
         * 行数就从 5 变成 14，旧收藏的行号指向的已是另一段文字 —— 点进去跳到错位置。
         * 文字内容不会随切分规则变化，所以定位必须以内容为准。
         *
         * 规则与段号定位同源（同一套 [normalize]，忽略所有空白后再比对）：
         * 先整段包含，失败再退 [ANCHOR_LENGTH] 字锚点。命中多行时取「与
         * [lineNumberHint] 最接近」的那行 —— 原文里重复出现的句子（叠句、重复的章节标题、
         * 同书内重复的段落）不会被随意挑中；行号虽会漂移，但「大概在书的什么位置」
         * 这个信息仍然有效，正好用来在多命中里做偏置。
         *
         * @param rows           行文本（顺序即显示顺序）
         * @param needle         收藏时选中的文本（可能只是某行的一部分）
         * @param lineNumberHint 收藏时存的行号（**从 1 起**）；≤0 表示无提示
         * @return 行下标；找不到返回 -1
         */
        fun locateRowByText(rows: List<String>, needle: String, lineNumberHint: Int = -1): Int {
            if (rows.isEmpty() || needle.isBlank()) return -1
            val query = normalize(needle)
            if (query.length == 0) return -1

            var candidates = rows.indices.filter { i ->
                // 归一化后的行不会比原行更长 ⇒ 原行比查询短就不可能包含它（先做便宜的剪枝）
                rows[i].length >= query.length && normalize(rows[i]).text.contains(query.text)
            }
            if (candidates.isEmpty() && query.length >= ANCHOR_MIN) {
                val anchor = query.text.substring(0, minOf(ANCHOR_LENGTH, query.length))
                candidates = rows.indices.filter { i ->
                    rows[i].length >= anchor.length && normalize(rows[i]).text.contains(anchor)
                }
            }
            if (candidates.isEmpty()) return -1
            if (candidates.size == 1 || lineNumberHint <= 0) return candidates.first()
            val preferred = lineNumberHint - 1
            return candidates.minByOrNull { kotlin.math.abs(it - preferred) } ?: candidates.first()
        }

        private fun rowIndexOfOffset(starts: IntArray, ends: IntArray, offset: Int): Int {
            if (starts.isEmpty()) return -1
            var lo = 0
            var hi = starts.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                if (starts[mid] <= offset) {
                    ans = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            if (ans < 0) return 0
            // 落在行尾那个连接符上时归到下一行（该字符不属于任何一行的正文）
            if (offset > ends[ans] && ans + 1 < starts.size) return ans + 1
            return ans
        }
    }
}
