package com.audiobook.audiobookplayer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 走「服务端解析缓存」做本机合成时的切分与定位口径。
 *
 * 背景：客户端的 PDF 解析器（PDFium，见 [PdfPreviewParser]）只产出**降级预览**，
 * 不是权威；权威是服务端 pdfbox 3.0.2。所以本机合成的正文**只能**来自
 * `ebook_content_cache` —— 那是服务端 pdfbox 抽出的**行**
 * （见 `MossLocalSynthesizer.resolveParagraphs`）。
 * ⚠️ 预览绝不能写进这个缓存，否则同名书会出现两种段号 ⇒ 点读串音。
 *
 * ## 这条链路真正的风险不是「切得对不对」，是「切完还能不能定位回原文」
 *
 * 合成入库的 `text_content` 是**段落**，而阅读页显示的是**行**，两者靠
 * [EbookSegmentMap] 的「归一化后全文 indexOf」对齐。所以：
 * - 段落必须是原文的子串（否则定位失败 ⇒ 点读/高亮串音）；
 * - 段落顺序必须与原文一致（否则 `locate` 的游标单调前进会失效）。
 *
 * 缓存里存的行是 PDF 版面抽取的结果，特征与 EPUB 不同：**一「行」常常是半句话**，
 * 句号可能在下一行。这类输入下切分仍必须保持顺序与子串性质。
 */
class PdfCachedRowsSynthesisTest {

    /** 把缓存里的行按 `MossLocalSynthesizer.resolveParagraphs` 的方式切成段落。
     *
     * ⚠️ 这里**必须调生产函数本身**（`ParagraphSplitter.splitRows`），不能在用例里
     * 自己重写一遍 `feed(it + "\n")` —— 那样测的是测试自己的副本，
     * 生产代码改了切分粒度测试照样绿，等于没有鉴别力。
     */
    private fun paragraphsFromCachedRows(rows: List<String>): List<String> =
        ParagraphSplitter.splitRows(rows)

    /**
     * 主判据：缓存行切出来的每一个段，都必须能在原文（缓存行的拼接）里定位到，
     * 且定位顺序严格递增。
     *
     * 这条不过 ⇒ 合成入库的音频与阅读页文本错位 ⇒ 点读串音。
     */
    @Test
    fun `缓存行切出的段全部能定位回原文且顺序递增`() {
        // 真实 PDF 版面抽取的典型形态：句号落在行尾、下一行接续下一句、行首有缩进
        val rows = listOf(
            "第一章  官办经济的起源",
            "",
            "在讨论官办经济之前，必须先厘清一个前提：",
            "所谓「官办」，指的是由国家直接出资经营的那一类经济活动。",
            "它既不同于官督商办，也不同于官商合办。",
        )

        val paragraphs = paragraphsFromCachedRows(rows).filter { it.isNotBlank() }

        // ⚠️ 先钉住 fixture 前提，别让「段数不对」变成一句空泛的断言。
        // ParagraphSplitter 是**按 100 字打包句子**（阅读显示粒度，不是 TTS 的 30 字）：
        // 短句会先攒进同一个段，超过上限才另起一段。这几条加起来约 80 字 ⇒ 切出 1 段。
        // 记成「切出 N 段」会把「段数」和「切分粒度」这两件事混在一起 ——
        // 将来有人调 MAX_PARAGRAPH_LENGTH 时，这条断言会给出误导性的失败理由。
        assertEquals(
            "fixture 前提：本例正文约 80 字，应被 100 字上限合并为 1 段",
            1,
            paragraphs.size
        )

        // 模拟合成入库：段号从 1 连续递增，text_content 就是段文本本身
        val segments = paragraphs.mapIndexed { i, text -> (i + 1) to text }
        val map = EbookSegmentMap.build(rows, segments)!!

        assertEquals(
            "每一段都应定位成功",
            paragraphs.size,
            map.locatedCount
        )
        // 顺序递增：locate 用单调游标，段号乱序或倒挂会让后面的段定位失败
        var prev = -1
        for ((number, text) in segments) {
            val start = map.segmentStart(number)
            assertTrue("段 $number 起点 $start 应大于上一段终点 $prev", start > prev)
            assertEquals(
                "段 $number 定位到的原文（去空白）应等于段落文本（去空白）",
                text.filterNot { it.isWhitespace() },
                rows.joinToString("\n")
                    .substring(map.segmentStart(number), map.segmentEnd(number))
                    .filterNot { it.isWhitespace() }
            )
            prev = map.segmentEnd(number) - 1
        }
    }

    /**
     * 逐行喂 + 每行补 `\n`，与「整本一次喂」必须**逐字节一致**。
     *
     * 这是流式切分的通用不变式（EpubParser 逐章喂时靠它保证结果稳定）；
     * 缓存路径是另一种喂法（逐行），若两者不一致，段边界就会依赖喂入方式，
     * 续跑时同一本书两次合成的段数可能不同 ⇒ 「部分完成」的段号对不上。
     */
    @Test
    fun `逐行喂与整本一次喂结果一致`() {
        val rows = listOf(
            "他打开箱子，里面是一叠发黄的手稿。",
            "第一页写着日期，笔迹很旧；",
            "后面的字迹越来越潦草。",
        )
        val perLine = paragraphsFromCachedRows(rows)

        val whole = ParagraphSplitter()
        whole.feed(rows.joinToString("\n") + "\n")
        val wholeResult = whole.finish()

        assertEquals(perLine, wholeResult)
    }

    /**
     * 空白的行不得产出空段落。
     *
     * PDF 抽取里空行很常见（页间分隔），若产出空段，合成会拿空文本去推理 ——
     * 要么白烧一次模型推理（几十秒到几分钟），要么直接失败。
     */
    @Test
    fun `空白行不产出空段落`() {
        val rows = listOf(
            "",
            "   ",
            "\t",
            "这一行有内容。",
            "",
        )
        val paragraphs = paragraphsFromCachedRows(rows)
        assertTrue(paragraphs.none { it.isBlank() })
        assertTrue(paragraphs.any { it.contains("这一行有内容") })
    }

    /**
     * 段数不能爆炸。
     *
     * 合成是逐段推理的，一段几十秒到几分钟。切得太碎会让「一本 31 段的书」变成
     * 几百段、几小时才跑完，用户等不起；切得太粗会让单段文本超长而触发截断。
     * 这个断言只钉住「不越界」，具体粒度由 ParagraphSplitter 自己的测试负责。
     */
    @Test
    fun `段数与行数同量级不会爆炸`() {
        val rows = (1..20).map { "这是第 $it 段测试文本，长度适中便于检查切分行为。" }
        val paragraphs = paragraphsFromCachedRows(rows)
        assertTrue(
            "20 行切成 ${paragraphs.size} 段，明显爆炸",
            paragraphs.size in 1..40
        )
    }
}