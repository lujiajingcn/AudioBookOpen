package com.audiobook.audiobookplayer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

import java.util.Base64

/**
 * PDF 降级预览解析器的**纯逻辑**单测（不碰 PDFium 引擎，只测清洗与切行口径）。
 *
 * ## 为什么这些函数必须有测试
 *
 * 服务端 `BookTextExtractor` 有一套自研清洗（点线折叠、页眉页脚剔除）与切行规则。
 * 客户端 [PdfPreviewParser] 是它的移植，两边只要差一个字符，
 * `EbookSegmentMap` 的段定位就会静默失准 —— 而这类失准**不会报错**，
 * 表现为「点读高亮不上」「目录跳错行」，极难定位。
 *
 * 下面每条断言都对应一个真实踩过的语义差异（尤其 Java↔Kotlin 字符串语义、
 * 以及 PDFium 的 CRLF ↔ 服务端 PDFBox 的 LF）。
 *
 * ⚠️ 换引擎（PDFBox-Android 2.0.27 → PDFium 1.0.31）时**后处理逻辑一行未改**，
 * 所以前 10 条断言原样保留 —— 它们是「换引擎没改坏别的东西」的证据。
 * 新增的 3 条覆盖 CRLF 归一，那是本次唯一新增的生产逻辑。
 */
class PdfPreviewParserTest {

    // ---------- stripTrailingJava：Java String.stripTrailing() 的等价实现 ----------

    /**
     * 核心判据：Java 的 `stripTrailing()` 用 `Character.isWhitespace`，
     * **不含** U+00A0(NBSP) / U+2007 / U+202F；
     * 而 Kotlin 的 `Char.isWhitespace()` = `isWhitespace || isSpaceChar`，**会多删这三个**。
     *
     * 若误用 Kotlin 语义，行尾一个 NBSP 就会让该行文本与服务端差一个字符 ⇒ 该行整段定位失败。
     * 这是本文件最重要的一条断言。
     */
    @Test
    fun stripTrailing_keepsNbspUnlikeKotlinIsWhitespace() {
        val nbsp = "正文末尾\u00A0"
        // Kotlin 语义会把它裁掉；Java 语义保留
        assertTrue(
            "Kotlin 的 isWhitespace 会把 NBSP 当空白——这正是不能用它的原因",
            nbsp.trimEnd { it <= ' ' } == nbsp || nbsp.last().isWhitespace()
        )
        assertEquals(
            "Java 语义：U+00A0 不是 Character.isWhitespace，必须保留",
            nbsp,
            PdfPreviewParser.stripTrailingForTest(nbsp)
        )
        assertEquals(
            "Java 语义：U+2007 同样必须保留",
            "行\u2007",
            PdfPreviewParser.stripTrailingForTest("行\u2007")
        )
        assertEquals(
            "Java 语义：U+202F 同样必须保留",
            "行\u202F",
            PdfPreviewParser.stripTrailingForTest("行\u202F")
        )
    }

    @Test
    fun stripTrailing_removesRealWhitespace() {
        // 真正该被裁的：空格、制表、换行、CR
        assertEquals("正文", PdfPreviewParser.stripTrailingForTest("正文   \t\r\n"))
        assertEquals("", PdfPreviewParser.stripTrailingForTest("   "))
        assertEquals("", PdfPreviewParser.stripTrailingForTest(""))
        // 行首空格必须保留（服务端只裁行尾）；行尾空格照常裁掉
        assertEquals("  缩进正文", PdfPreviewParser.stripTrailingForTest("  缩进正文  "))
    }

    // ---------- 切行 ----------

    /**
     * 只裁行尾、保留行首缩进、空行丢弃 —— 与服务端 `splitRowsWithStarts` 同口径。
     */
    @Test
    fun splitRows_matchesServerSemantics() {
        val text = "第一行\n  缩进行  \n\n第三行\t\n"
        assertEquals(
            listOf("第一行", "  缩进行", "第三行"),
            PdfPreviewParser.splitRowsForTest(text)
        )
    }

    /** CRLF 的 '\r' 必须被裁掉，否则行尾会多一个字符导致与服务端不一致 */
    @Test
    fun splitRows_stripsCarriageReturn() {
        assertEquals(
            listOf("行一", "行二"),
            PdfPreviewParser.splitRowsForTest("行一\r\n行二\r\n")
        )
    }

    // ---------- PDFium 的 CRLF 归一（2026-10-12 换引擎时新增） ----------

    /**
     * ⭐ 本次换引擎引入的新逻辑。**测试反过来纠正了我最初注释里的一个错误说法**，
     * 这里记下真实语义：
     *
     * - **CRLF 不是问题**：`splitRows` 末尾的 `stripTrailingJava()` 已经把行尾的 `\r`
     *   裁掉了（`\r` 属于 Java `Character.isWhitespace`）。所以对 CRLF 而言，
     *   归一是**冗余**的 —— 但它让「行内无隐藏 `\r`」成为不依赖下游的性质，值得留着。
     * - **孤立 CR 才是真问题**：PDFium 正常产出 CRLF，但老式 PDF 生成器会只吐 `\r`。
     *   此时 `splitRows` 只按 `\n` 切，**整段文字变成一行**，中间的 `\r` 也不在行尾
     *   ⇒ 不会被裁 ⇒ 行的粒度与服务端完全不一致。
     */
    @Test
    fun normalizeNewlines_convertsCrlfToLf() {
        assertEquals("行一\n行二", PdfPreviewParser.normalizeNewlinesForTest("行一\r\n行二"))
        // 孤立 CR 也要归一（防御性：某些 PDF 生成器会只吐 CR）
        assertEquals("行一\n行二", PdfPreviewParser.normalizeNewlinesForTest("行一\r行二"))
        // 结尾 CRLF
        assertEquals("行一\n", PdfPreviewParser.normalizeNewlinesForTest("行一\r\n"))
    }

    /** 无 CR 时必须原样返回（不能无条件 replace，否则每次解析都白做一遍全串扫描） */
    @Test
    fun normalizeNewlines_isNoOpWithoutCarriageReturn() {
        val s = "行一\n行二"
        assertEquals(s, PdfPreviewParser.normalizeNewlinesForTest(s))
    }

    /**
     * 端到端口径：PDFium 原始输出 → 与服务端一致的 rows。
     *
     * **本条的判据用孤立 CR**，因为 CRLF 在 `splitRows` 里已被 `stripTrailingJava()`
     * 兜住（那条断言写了但实测两边相同，见上一条的说明）。孤立 CR 才是归一真正的价值：
     * 不归一时 `splitRows` 认不出换行，**整段塌成一行**。
     */
    @Test
    fun rowsFromPdfium_guardsAgainstLoneCarriageReturn() {
        // 三行，但行分隔是孤立 CR
        val lone = "  第一行有缩进  \r第二行\r\r第三行\r"
        assertEquals(
            listOf("  第一行有缩进", "第二行", "第三行"),
            PdfPreviewParser.rowsFromPdfiumForTest(lone)
        )
        // 反面对照：不做归一，整段塌成一行 ⇒ 粒度与服务端不一致
        assertEquals(
            "孤立 CR 不归一，整段会塌成一行",
            listOf("  第一行有缩进  \r第二行\r\r第三行"),
            PdfPreviewParser.splitRowsForTest(lone)
        )
    }

    /**
     * 常见的 CRLF 场景：归一与不归一**结果相同**（`stripTrailingJava` 已兜住行尾 `\r`）。
     *
     * 把这条钉死是为了锁住上面那条注释的事实，而不是让人误以为 CRLF 会丢字符。
     */
    @Test
    fun rowsFromPdfium_crlfIsAlsoHandledByStripTrailing() {
        val raw = "  第一行有缩进  \r\n第二行\r\n\r\n第三行\r\n"
        assertEquals(
            listOf("  第一行有缩进", "第二行", "第三行"),
            PdfPreviewParser.rowsFromPdfiumForTest(raw)
        )
        // 不归一也得到同样结果 —— 因为 \r 在行尾，被 stripTrailingJava 裁掉
        assertEquals(
            PdfPreviewParser.rowsFromPdfiumForTest(raw),
            PdfPreviewParser.splitRowsForTest(raw)
        )
    }

    // ---------- 点线折叠 ----------

    /**
     * 连续 4 个及以上 ASCII 点折成一个空格；3 个点（英文省略号）不动。
     * 阈值取 4 是为了避开有意义的省略号，与服务端一致。
     */
    @Test
    fun collapseDotLeaders_foldsOnlyFourOrMore() {
        val pages = mutableListOf(
            "第一章 绪论........12",
            "他说...然后走了",
            "目录...."
        )
        PdfPreviewParser.collapseDotLeadersForTest(pages)
        assertEquals("第一章 绪论 12", pages[0])
        assertEquals("3 个点是英文省略号，是有意义的标点，不能折", "他说...然后走了", pages[1])
        assertEquals("目录 ", pages[2])
    }

    /** 页码列通常已被 PDFTextStripper 拆到另一行；点线折成空格保住原行分隔感，行尾空格随后被裁 */
    @Test
    fun collapseDotLeaders_foldsToSingleSpace() {
        val pages = mutableListOf("标题" + ".".repeat(80))
        PdfPreviewParser.collapseDotLeadersForTest(pages)
        assertEquals("标题 ", pages[0])
    }

    // ---------- 页眉页脚剔除 ----------

    /**
     * 页数不足 8 页时**不做统计**：样本太少时「在多页上重复」没有统计意义，
     * 宁可不删（错删正文比多留页眉更糟）。
     */
    @Test
    fun dropRunningMargins_skipsWhenTooFewPages() {
        val pages = (1..5).map { i -> "书名A\n正文$i" }.toMutableList()
        PdfPreviewParser.dropRunningMarginsForTest(pages)
        assertTrue("页数 <8 时不应删除任何行", pages[0].contains("书名A"))
    }

    /**
     * 达标页数时，出现在 ≥60% 页面的边缘行被删。
     *
     * 样本必须**足够长**：护栏是「只在前/后 3 个非空行内查找」，
     * 短页里几乎每个行都是边缘行，正文反而会被正确地当成页眉删掉 ——
     * 那不是 bug，是规则本身。真实 PDF 一页几十行，正文落在中部。
     * 10 行时边缘是下标 0~2 与 7~9，下标 3~6 属中部。
     */
    @Test
    fun dropRunningMargins_removesRepeatedEdgeLine() {
        val pages = (1..10).map { i ->
            (0..9).joinToString("\n") { k ->
                when (k) {
                    0 -> "书名A"
                    9 -> "- $i -"
                    else -> "第${k}段$i"
                }
            }
        }.toMutableList()
        PdfPreviewParser.dropRunningMarginsForTest(pages)
        assertTrue("重复的书名应被剔除", !pages[0].contains("书名A"))
        assertTrue("页面中部的正文必须保留", pages[0].contains("第3段1"))
        // 页码行数字不同，但归一化后同为 "- # -" ⇒ 也应被删
        assertTrue("页码行应被剔除", !pages[0].contains("- 1 -"))
    }

    /**
     * 正文里合法重复的行（不在页面边缘）**不能**被删。
     * 这是第 3 条护栏的意义：每章末尾的「（待续）」在页面中部，不该被误删。
     *
     * 「中间重复行」放在 7 行文本的第 3 位 —— 前 3 个非空行是[书名A/第一段/中间重复行]，
     * 所以它**确实**在边缘区。要真正测到中部，必须把它推到第 4 位之后。
     */
    @Test
    fun dropRunningMargins_keepsRepeatedMiddleLine() {
        val pages = (1..10).map { i ->
            (0..9).joinToString("\n") { k ->
                when (k) {
                    0 -> "书名A"
                    5 -> "中间重复行"          // 落在中部（下标 3~6）
                    9 -> "- $i -"
                    else -> "第${k}段$i"
                }
            }
        }.toMutableList()
        PdfPreviewParser.dropRunningMarginsForTest(pages)
        assertTrue("页面中部的重复行必须保留", pages[0].contains("中间重复行"))
        assertTrue("书名仍应被剔除", !pages[0].contains("书名A"))
    }

    /** 低于 60% 阈值的不删：宁可多留页眉，不可错删正文 */
    @Test
    fun dropRunningMargins_keepsLineBelowThreshold() {
        // 10 页里只有 4 页首行是「偶尔出现的行」= 40% < 60%。
        // 放在首行（下标 0）才能验证「命中率不够就不删」——若放在中部，
        // 即使命中率达100% 也不会删，测的就不是阈值这条规则了。
        val pages = (1..10).map { i ->
            (0..9).joinToString("\n") { k ->
                when {
                    k == 0 && i <= 4 -> "偶尔出现的行"
                    k == 0 -> "书名A"
                    else -> "第${k}段$i"
                }
            }
        }.toMutableList()
        PdfPreviewParser.dropRunningMarginsForTest(pages)
        assertTrue("40% < 60% 阈值，不应删除", pages[0].contains("偶尔出现的行"))
        // 反面对照：同一批数据里 100% 出现的书名A 必须被删
        assertTrue("100% 出现的书名应被剔除", !pages[5].contains("书名A"))
    }

    // ---------- 版面重排：视觉行 → 自然段（P0-3）----------
    //
    // ⚠️ 本节与 Java 侧 BookTextExtractorPdfReflowTest **逐条对应**。
    // 两份实现（Kotlin 预览 / Java 权威）必须同口径：只要一处阈值不同，
    // 预览文本与服务端覆盖后的文本就会在段数上分叉，点读映射随之静默失准。

    private fun rep(c: Char, n: Int) = c.toString().repeat(n)

    /** 版心 40 字符的英文页：a/b 满行 → 与短行 c 同属一段；d/e 满行 → 与短行 f 同属一段 */
    private val enPage = listOf(
        rep('a', 40), rep('b', 40), rep('c', 8),
        rep('d', 40), rep('e', 40), rep('f', 8)
    ).joinToString("\n")

    @Test
    fun reflow_mergesFullLinesAndBreaksAfterShortLine() {
        val out = PdfPreviewParser.reflowPageTextForTest(enPage)

        assertEquals(
            "满行续行合并；短行是段末行，与下一行断开",
            listOf(
                rep('a', 40) + " " + rep('b', 40) + " " + rep('c', 8),
                rep('d', 40) + " " + rep('e', 40) + " " + rep('f', 8)
            ),
            out.split("\n")
        )
    }

    @Test
    fun reflow_leavesListLikePageUntouched() {
        val page = listOf(
            "Penguin Group (USA) Inc., 375 Hudson Street, New York, New York 10014, U.S.A.",
            "New York, New York 10014, U.S.A.",
            "Penguin Group (Canada), 10 Alcorn Avenue, Toronto, Ontario, Canada M4V 3B2",
            "Penguin Books Ltd, 80 Strand, London WC2R ORL, England",
            "(a division of Penguin Books Ltd)",
            "Penguin Ireland, 25 St. Stephen's Green, Dublin 2, Ireland"
        ).joinToString("\n")

        assertEquals(
            "版权页式「短行占多数」的页面整页不合并",
            6,
            PdfPreviewParser.reflowPageTextForTest(page).split("\n").size
        )
    }

    @Test
    fun reflow_keepsVeryShortPageAsIs() {
        val page = "COLLAPSE\nHOW SOCIETIES CHOOSE\nTO FAIL OR SUCCEED"
        assertEquals(3, PdfPreviewParser.reflowPageTextForTest(page).split("\n").size)
    }

    @Test
    fun reflow_joinsSingleLetterRuns() {
        val page = listOf(
            "HOW S O C I E T I E S CHOOSE",
            "TO FAIL OR S U C C E E D",
            "JARED DIAMOND WROTE THIS",
            "VIKING PENGUIN GROUP USA",
            "COPYRIGHT TWO THOUSAND FIVE",
            "ALL RIGHTS RESERVED HERE"
        ).joinToString("\n")

        val out = PdfPreviewParser.reflowPageTextForTest(page)
        assertTrue("字距被当空格时必须黏合", out.contains("SOCIETIES") && out.contains("SUCCEED"))
        assertTrue("原文的单字母串不应残留", !out.contains("S O C I E T I E S"))
    }

    @Test
    fun reflow_keepsShortTwoLetterRuns() {
        val page = listOf(
            "an A I system is not the same as a single letter run",
            "and this line is also long enough to look like body text",
            "another long line follows right after this one as well",
            "and a fourth long line is here to reach the line minimum",
            "short tail."
        ).joinToString("\n")

        assertTrue(
            "门槛是连续 4 个单字母，A I 这种两字母组合不能被合并",
            PdfPreviewParser.reflowPageTextForTest(page).contains("A I system")
        )
    }

    @Test
    fun reflow_dropsNoiseOnlyLine() {
        val page = listOf(
            "COLLAPSE",
            "HOW SOCIETIES CHOOSE",
            "■ ■ ■ ■ ■■■■■■",
            "JARED",
            "DIAMOND",
            "VIKING"
        ).joinToString("\n")

        val out = PdfPreviewParser.reflowPageTextForTest(page)
        assertTrue("整行只有装饰方块（logo 占位）必须丢弃", !out.contains("■"))
        assertTrue(out.contains("COLLAPSE") && out.contains("VIKING"))
    }

    @Test
    fun reflow_keepsLineWithStrayGlyph() {
        val page = listOf(
            rep('a', 40),
            "the lazy dog and the ■ cat ran away from the big dog today",
            rep('b', 40), rep('c', 40), rep('d', 40), "tail."
        ).joinToString("\n")

        assertTrue(
            "正文里夹一个缺字方块的行不能被整行删掉",
            PdfPreviewParser.reflowPageTextForTest(page).contains("■ cat")
        )
    }

    @Test
    fun reflow_gluesUnmappedBreakCharacterInsideWord() {        val page = listOf(
            "the excess of government spending over gov\uFFFEernment revenue,",
            "and the rest of the sentence continues here for good measure,",
            "which makes this line long enough to be a full line in the page,",
            "and here is the final short line."
        ).joinToString("\n")

        val out = PdfPreviewParser.reflowPageTextForTest(page)
        assertTrue("U+FFFE 在词中=被吞掉的行尾连字符，必须黏合成 government", out.contains("government revenue"))
        assertTrue(!out.contains("\uFFFE"))
    }

    /**
     * 本测试锁死「两端同源」这条命脉（与 Java 侧 `pdfiumAndPdfboxShapesProduceSameResult` 对应）。
     *
     * 同一个断词在同一份 PDF 上，两个引擎给出的**形态不同**：
     * - PDFBox（服务端）：`"...of Na-"` 换行 `"ture, don't..."` —— 两行，行尾连字符；
     * - PDFium（客户端）：`"...of Na␞ture, don't..."` —— **一行**，中间一个 U+FFFE，连换行都被吞掉。
     *
     * 若不处理，PDFium 那侧行长 118 而 PDFBox 是 52+66，80 分位跟着变、「短行=段末」
     * 判据随之偏移，同一页在两端的**段落边界不同**（实测 573 页里 3 页）。
     */
    @Test
    fun reflow_pdfiumAndPdfboxShapesProduceSameResult() {
        val pdfiumShape = listOf(
            "Only those evil modern First World inhabitants are ignorant of Na\uFFFEture," +
                " don't respect the environment, and destroy it.",
            "In fact, both extreme sides in this controversy—-the racists and",
            "the humanitarians, who think that native peoples can do no wrong",
            "and that we should all go back to living in caves, are just wrong.",
            "tail."
        ).joinToString("\n")
        val pdfboxShape = listOf(
            "Only those evil modern First World inhabitants are ignorant of Na-",
            "ture, don't respect the environment, and destroy it.",
            "In fact, both extreme sides in this controversy—-the racists and",
            "the humanitarians, who think that native peoples can do no wrong",
            "and that we should all go back to living in caves, are just wrong.",
            "tail."
        ).joinToString("\n")

        val fromFium = PdfPreviewParser.reflowPageTextForTest(pdfiumShape)
        val fromBox = PdfPreviewParser.reflowPageTextForTest(pdfboxShape)

        assertEquals("两种引擎形态必须归一到同一份文本", fromBox, fromFium)
        assertTrue(fromFium.contains("of Nature, don't respect"))
        assertTrue(!fromFium.contains("\uFFFE"))
    }

    @Test
    fun reflow_replacesUnmappedCharacterOutsideWordWithSpace() {
        val page = listOf(
            "first column text ends right here at the row end\uFFFE",
            "second line starts with capital letters here and goes on",
            "third line is also a fairly full line of text on this page",
            "fourth line is also a fairly full line of text on this page",
            "final line."
        ).joinToString("\n")

        val out = PdfPreviewParser.reflowPageTextForTest(page)
        assertTrue(!out.contains("\uFFFE"))
        assertTrue("不在词中时换成空格，两个词不能被黏成一个", out.contains("row end second line"))
    }

    @Test
    fun reflow_dehyphenatesAcrossLineBreak() {
        val page = listOf(
            "this is a very long sentence that ends with an exam-",
            "ple of hyphenation which must be glued back together",
            "and another full line follows right after the previous",
            "one and this line is here so the page has enough lines",
            "then a short tail."
        ).joinToString("\n")

        val out = PdfPreviewParser.reflowPageTextForTest(page)
        assertTrue("行尾连字符断词必须黏合", out.contains("example of hyphenation"))
        assertTrue(!out.contains("exam-"))
    }

    @Test
    fun reflow_keepsDoubleHyphen() {
        val page = listOf(
            "the value is shown as a range of numbers and a--",
            "b notation which should not be glued together here",
            "another full line that is long enough to be merged",
            "and this fifth line makes the page eligible for reflow",
            "tail."
        ).joinToString("\n")

        assertTrue(
            "`--` 不是断词连字符，不能被吞掉",
            PdfPreviewParser.reflowPageTextForTest(page).contains("a--")
        )
    }

    @Test
    fun reflow_keepsHeadingLineStandalone() {
        val page = listOf(
            rep('t', 40),
            "ABSOLUTE ADVANTAGE",
            rep('o', 40), rep('a', 40), "tail."
        ).joinToString("\n")

        assertTrue(
            "全大写短行是标题，前后都断开",
            PdfPreviewParser.reflowPageTextForTest(page).split("\n").contains("ABSOLUTE ADVANTAGE")
        )
    }

    @Test
    fun reflow_doesNotInsertSpaceBetweenCjk() {
        val page = listOf(
            "这是一行接近满行的中文正文用来测试版心宽度判断是否准确的样例",
            "这是紧接上一行的续行同样接近满行所以必须与上一行合并成同段",
            "这一行比较短。",
            "这是另一段的第一行同样是一行接近满行的中文正文内容样例文字",
            "这是它的续行同样是一行接近满行的中文正文内容用来占位填充字",
            "结尾。"
        ).joinToString("\n")

        val out = PdfPreviewParser.reflowPageTextForTest(page)
        assertTrue("中文行合并不能插空格", out.contains("判断是否准确的样例这是紧接上一行的续行"))
        assertTrue(!out.contains("样例 这是"))
    }

    @Test
    fun reflow_keepsParagraphIndent() {
        val page = listOf(
            "   第一章绪论这是段首缩进的正文第一行内容接近满行占位填充",
            "这是续行同样接近满行需要与上一行合并成同一段落内容文字",
            "这一行比较短。",
            "   第二章这是另一段同样带缩进的第一行正文内容接近满行填",
            "这是它的续行同样接近满行需要合并到同一段落里面去的内容",
            "结尾。"
        ).joinToString("\n")

        val lines = PdfPreviewParser.reflowPageTextForTest(page).split("\n")
        assertEquals("应合并成两段", 2, lines.size)
        assertTrue("段首缩进是排版信息，必须保留", lines[0].startsWith("   第一章"))
        assertTrue(lines[0].contains("这一行比较短。"))
        assertTrue(lines[1].startsWith("   第二章"))
    }

    @Test
    fun reflow_handlesEmptyPage() {
        assertEquals("", PdfPreviewParser.reflowPageTextForTest(""))
        assertEquals("", PdfPreviewParser.reflowPageTextForTest("\n \n\t\n"))
    }

    @Test
    fun reflow_neverLosesVisibleCharacters() {
        val page = listOf(
            "A next consideration in my five-point framework is climate",
            "change, a term that today we tend to associate with global",
            "caused by humans. In fact, climate may become hotter or",
            "wetter or drier, or more or less variable between months",
            "tail."
        ).joinToString("\n")

        fun visible(s: String) = s.filter { !Character.isWhitespace(it) }
        assertEquals(
            "重排只允许增删空白与断词连字符，可见字符一个都不能丢",
            visible(page),
            visible(PdfPreviewParser.reflowPageTextForTest(page))
        )
    }

    // ---------- 字距串：一个词还是两个词 ----------

    @Test
    fun reflow_insertsSpaceWhenCorpusHasThePhrase() {
        val line = PdfPreviewParser.cleanPdfLineWithCorpusForTest(
            "O F E C O N O M I C S",
            listOf("the study of economics has many facets")
        )
        assertEquals("OF ECONOMICS", line)
    }

    @Test
    fun reflow_keepsRunMergedWhenCorpusLacksThePhrase() {
        val line = PdfPreviewParser.cleanPdfLineWithCorpusForTest(
            "O F E C O N O M I C S",
            listOf("the study of plants has many facets")
        )
        assertEquals("OFECONOMICS", line)
    }

    @Test
    fun reflow_doesNotSplitWhenHalvesNeverAdjacent() {
        // 语料里同时有 obey 与断词残片 ing，但没有 "obey ing" 相邻 ⇒ 不许切成 Obey ing
        val line = PdfPreviewParser.cleanPdfLineWithCorpusForTest(
            "O b e y i n g",
            listOf("people obey the rules", "build-\ning is happening")
        )
        assertEquals("Obeying", line)
    }

    @Test
    fun reflow_keepsWholeWordMergedWhenAttested() {
        val line = PdfPreviewParser.cleanPdfLineWithCorpusForTest(
            "S O C I E T I E S",
            listOf("hunting societies collapse over time")
        )
        assertEquals("SOCIETIES", line)
    }

    @Test
    fun reflow_withoutCorpusKeepsOldBehaviour() {
        assertEquals("OFECONOMICS", PdfPreviewParser.cleanPdfLineForTest("O F E C O N O M I C S"))
    }

    /**
     * 与服务端实现的金标一致性（最重要的一条）。
     *
     * `pdf_reflow_vectors.tsv` 是「真实 PDF 页文本 → 服务端 `BookTextExtractor.reflowPdfPageText`
     * 的实际输出」的固化快照，取自两本真实书（两栏英文书《Collapse》573 页与教材
     * 《曼昆经济学原理》790 页，后者含大量 U+FFFE 断词）。客户端预览必须与之**逐字符相同**：
     * 只要在一处段落边界上分叉，预览段号与服务端权威段号就会错位，点读静默失准。
     *
     * 失败时一次性列出所有差异（而不是第一条就退出），否则修一处跑一轮，效率极低。
     * 重新生成金标：跑 `deploy` 侧 ReflowDump（见文件头说明），不要手改本文件。
     */
    @Test
    fun reflow_matchesServerGoldenVectors() {
        val stream = javaClass.classLoader?.getResourceAsStream("pdf_reflow_vectors.tsv")
        if (stream == null) {
            fail("缺少金标向量 pdf_reflow_vectors.tsv（app/src/test/resources/）")
            return
        }
        val decode = Base64.getDecoder()
        // 名字形如 "mankiw_101"，按书分组 —— 字距串的切分依赖**全书语料**，
        // 逐页独立算会与生产路径不一致，所以必须整本一起喂进去。
        val byBook = LinkedHashMap<String, MutableList<Triple<Int, String, String>>>()
        stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split("\t")
            if (parts.size != 3) return@forEachLine
            val lastUnderscore = parts[0].lastIndexOf('_')
            if (lastUnderscore <= 0) return@forEachLine
            val book = parts[0].substring(0, lastUnderscore)
            val page = parts[0].substring(lastUnderscore + 1).toIntOrNull() ?: return@forEachLine
            val raw = String(decode.decode(parts[1]), Charsets.UTF_8)
            val expected = String(decode.decode(parts[2]), Charsets.UTF_8)
            byBook.getOrPut(book) { ArrayList() }.add(Triple(page, raw, expected))
        }

        val failures = ArrayList<String>()
        var count = 0
        for ((_, items) in byBook) {
            items.sortBy { it.first }
            val actualPages = PdfPreviewParser.reflowPagesForTest(items.map { it.second })
            items.forEachIndexed { index, (page, _, expected) ->
                val actual = actualPages[index]
                count++
                if (actual != expected) {
                    var k = 0
                    while (k < minOf(actual.length, expected.length) && actual[k] == expected[k]) k++
                    val from = maxOf(0, k - 60)
                    failures.add(
                        "第 $page 页在第 $k 个字符分叉（期望长 ${expected.length}，实际长 ${actual.length}）\n" +
                            "  期望…${expected.substring(from, minOf(expected.length, k + 60))}\n" +
                            "  实际…${actual.substring(from, minOf(actual.length, k + 60))}"
                    )
                }
            }
        }
        assertTrue("金标向量应至少 20 条，实际 $count", count >= 20)
        assertTrue("与服务端重排结果不一致：\n" + failures.joinToString("\n\n"), failures.isEmpty())
    }
}
