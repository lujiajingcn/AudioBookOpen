package com.audiobook.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF「视觉行 → 自然段」重排的回归测试（{@link BookTextExtractor#reflowPdfPageText}）。
 *
 * <p>背景（P0-3）：PDF 没有段落概念，文本提取只按坐标换行，一页 60 行就是 60 个「段落」。
 * 阅读页每行之间都带段间距、看起来像一堆碎句；客户端拿这份 rows 当正文显示与点读定位。
 *
 * <p>本测试锁死四类判据，任何一条被改坏都会让重排要么退化（不合并）要么过度（并成一坨）：
 * <ul>
 *   <li><b>满行续行</b>：行长接近版心的行，段落未结束，必须与下一行合并；</li>
 *   <li><b>短行断段</b>：明显短于版心的行是段落**末行**，它与下一行之间必须断开；</li>
 *   <li><b>非流式页保护</b>：版权页/目录/索引这类「每行都是独立条目」的页整页不动；</li>
 *   <li><b>字符级修复</b>：字距造成的词内空格、缺映射产生的断词字符、图片占位方块。</li>
 * </ul>
 *
 * <p>⚠️ 与客户端 {@code PdfPreviewParserTest} 的对应用例**必须同口径**：
 * 客户端预览文本与服务端权威正文只要在一个字符上分叉，点读映射就会静默失准。
 */
class BookTextExtractorPdfReflowTest {

    private static String rep(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    /** 版心 40 字符的英文页：a/b 满行 → 与短行 c 同属一段；d/e 满行 → 与短行 f 同属一段 */
    private static final String EN_PAGE = String.join("\n",
            rep('a', 40), rep('b', 40), rep('c', 8),
            rep('d', 40), rep('e', 40), rep('f', 8));

    @Test
    @DisplayName("满行续行合并，短行（段末行）与下一行断开")
    void mergesFullLinesAndBreaksAfterShortLine() {
        String out = BookTextExtractor.reflowPdfPageText(EN_PAGE);

        assertThat(out.split("\n")).containsExactly(
                rep('a', 40) + " " + rep('b', 40) + " " + rep('c', 8),
                rep('d', 40) + " " + rep('e', 40) + " " + rep('f', 8));
    }

    @Test
    @DisplayName("版权页式「长短交错、短行占多数」整页不合并")
    void leavesListLikePageUntouched() {
        String page = String.join("\n",
                "Penguin Group (USA) Inc., 375 Hudson Street, New York, New York 10014, U.S.A.",
                "New York, New York 10014, U.S.A.",
                "Penguin Group (Canada), 10 Alcorn Avenue, Toronto, Ontario, Canada M4V 3B2",
                "Penguin Books Ltd, 80 Strand, London WC2R ORL, England",
                "(a division of Penguin Books Ltd)",
                "Penguin Ireland, 25 St. Stephen's Green, Dublin 2, Ireland");

        String out = BookTextExtractor.reflowPdfPageText(page);

        // 这种页面每行都是独立条目，合并只会把整个地址块并成一坨
        assertThat(out.split("\n")).hasSize(6);
    }

    @Test
    @DisplayName("行数太少的页面（封面/扉页）原样保留")
    void keepsVeryShortPageAsIs() {
        String page = "COLLAPSE\nHOW SOCIETIES CHOOSE\nTO FAIL OR SUCCEED";

        assertThat(BookTextExtractor.reflowPdfPageText(page).split("\n")).hasSize(3);
    }

    @Test
    @DisplayName("字距造成的词内空格：S O C I E T I E S → SOCIETIES")
    void joinsSingleLetterRuns() {
        String page = String.join("\n",
                "HOW S O C I E T I E S CHOOSE",
                "TO FAIL OR S U C C E E D",
                "JARED DIAMOND WROTE THIS",
                "VIKING PENGUIN GROUP USA",
                "COPYRIGHT TWO THOUSAND FIVE",
                "ALL RIGHTS RESERVED HERE");

        String out = BookTextExtractor.reflowPdfPageText(page);

        assertThat(out).contains("SOCIETIES").contains("SUCCEED");
        assertThat(out).doesNotContain("S O C I E T I E S");
    }

    @Test
    @DisplayName("双字母组合 A I 不被误合并（门槛是连续 4 个单字母）")
    void keepsShortTwoLetterRuns() {
        String page = String.join("\n",
                "an A I system is not the same as a single letter run",
                "and this line is also long enough to look like body text",
                "another long line follows right after this one as well",
                "and a fourth long line is here to reach the line minimum",
                "short tail.");

        String out = BookTextExtractor.reflowPdfPageText(page);

        assertThat(out).contains("A I system");
    }

    @Test
    @DisplayName("整行只有装饰方块（出版社 logo 占位）的行被丢弃")
    void dropsNoiseOnlyLine() {
        String page = String.join("\n",
                "COLLAPSE",
                "HOW SOCIETIES CHOOSE",
                "■ ■ ■ ■ ■■■■■■",
                "JARED",
                "DIAMOND",
                "VIKING");

        String out = BookTextExtractor.reflowPdfPageText(page);

        assertThat(out).doesNotContain("■");
        assertThat(out).contains("COLLAPSE").contains("VIKING");
    }

    @Test
    @DisplayName("正文里夹一个缺字方块的行不会被整行删掉")
    void keepsLineWithStrayGlyph() {
        String page = String.join("\n",
                rep('a', 40),
                "the lazy dog and the ■ cat ran away from the big dog today",
                rep('b', 40),
                rep('c', 40),
                rep('d', 40),
                "tail.");

        String out = BookTextExtractor.reflowPdfPageText(page);

        assertThat(out).contains("■ cat");
    }

    @Test
    @DisplayName("缺映射字符在词中间：gov\\uFFFEernment → government")
    void gluesUnmappedBreakCharacterInsideWord() {
        String page = String.join("\n",
                "the excess of government spending over gov\uFFFEernment revenue,",
                "and the rest of the sentence continues here for good measure,",
                "which makes this line long enough to be a full line in the page,",
                "and here is the final short line.");

        String out = BookTextExtractor.reflowPdfPageText(page);

        assertThat(out).contains("government revenue");
        assertThat(out).doesNotContain("\uFFFE");
    }

    /**
     * 本测试锁死「两端同源」这条命脉。
     *
     * <p>同一个断词在同一份 PDF 上，两个引擎给出的**形态不同**：
     * <ul>
     *   <li>PDFBox（服务端）：{@code "...of Na-"} 换行 {@code "ture, don't..."} —— 两行，行尾连字符；</li>
     *   <li>PDFium（客户端）：{@code "...of Na␞ture, don't..."} —— **一行**，中间一个 U+FFFE，
     *       连换行都被吞掉了。</li>
     * </ul>
     * 若不处理，PDFium 那侧的行长是 118 而 PDFBox 是 52+66，80 分位跟着变、
     * 「短行=段末」判据随之偏移，于是同一页在两端的**段落边界不同**（实测 573 页里 3 页）。
     *
     * <p>现在两条路都先还原成「行尾连字符 + 换行」，再由 {@code joinHyphenatedRows}
     * 走同一套黏合规则 —— 断言它们产出**完全相同**的文本。
     */
    @Test
    @DisplayName("PDFium 形态（U+FFFE 黏行）与 PDFBox 形态（行尾连字符）必须产出同一结果")
    void pdfiumAndPdfboxShapesProduceSameResult() {
        String pdfiumShape = String.join("\n",
                "Only those evil modern First World inhabitants are ignorant of Na\uFFFEture,"
                        + " don't respect the environment, and destroy it.",
                "In fact, both extreme sides in this controversy—-the racists and",
                "the humanitarians, who think that native peoples can do no wrong",
                "and that we should all go back to living in caves, are just wrong.",
                "tail.");
        String pdfboxShape = String.join("\n",
                "Only those evil modern First World inhabitants are ignorant of Na-",
                "ture, don't respect the environment, and destroy it.",
                "In fact, both extreme sides in this controversy—-the racists and",
                "the humanitarians, who think that native peoples can do no wrong",
                "and that we should all go back to living in caves, are just wrong.",
                "tail.");

        String fromFium = BookTextExtractor.reflowPdfPageText(pdfiumShape);
        String fromBox = BookTextExtractor.reflowPdfPageText(pdfboxShape);

        assertThat(fromFium).isEqualTo(fromBox);
        assertThat(fromFium).contains("of Nature, don't respect");
        assertThat(fromFium).doesNotContain("\uFFFE");
    }

    @Test
    @DisplayName("缺映射字符不在词中时换成空格，不把两个词黏成一个")
    void replacesUnmappedCharacterOutsideWordWithSpace() {        String page = String.join("\n",
                "first column text ends right here at the row end\uFFFE",
                "second line starts with capital letters here and goes on",
                "third line is also a fairly full line of text on this page",
                "fourth line is also a fairly full line of text on this page",
                "final line.");

        String out = BookTextExtractor.reflowPdfPageText(page);

        assertThat(out).doesNotContain("\uFFFE");
        assertThat(out).contains("row end second line");
    }

    @Test
    @DisplayName("行尾连字符断词：exam- + ple → example")
    void dehyphenatesAcrossLineBreak() {
        String page = String.join("\n",
                "this is a very long sentence that ends with an exam-",
                "ple of hyphenation which must be glued back together",
                "and another full line follows right after the previous",
                "one and this line is here so the page has enough lines",
                "then a short tail.");

        String out = BookTextExtractor.reflowPdfPageText(page);

        assertThat(out).contains("example of hyphenation");
        assertThat(out).doesNotContain("exam-");
    }

    @Test
    @DisplayName("`--` 不是断词连字符，不能被吞掉")
    void keepsDoubleHyphen() {
        String page = String.join("\n",
                "the value is shown as a range of numbers and a--",
                "b notation which should not be glued together here",
                "another full line that is long enough to be merged",
                "and this fifth line makes the page eligible for reflow",
                "tail.");

        String out = BookTextExtractor.reflowPdfPageText(page);

        assertThat(out).contains("a--");
    }

    @Test
    @DisplayName("全大写短行是标题，前后都断开")
    void keepsHeadingLineStandalone() {
        String page = String.join("\n",
                rep('t', 40),
                "ABSOLUTE ADVANTAGE",
                rep('o', 40),
                rep('a', 40),
                "tail.");

        String out = BookTextExtractor.reflowPdfPageText(page);

        assertThat(out.split("\n")).contains("ABSOLUTE ADVANTAGE");
    }

    @Test
    @DisplayName("中文段落合并时不插空格")
    void doesNotInsertSpaceBetweenCjk() {
        String page = String.join("\n",
                "这是一行接近满行的中文正文用来测试版心宽度判断是否准确的样例",
                "这是紧接上一行的续行同样接近满行所以必须与上一行合并成同段",
                "这一行比较短。",
                "这是另一段的第一行同样是一行接近满行的中文正文内容样例文字",
                "这是它的续行同样是一行接近满行的中文正文内容用来占位填充字",
                "结尾。");

        String out = BookTextExtractor.reflowPdfPageText(page);

        assertThat(out).contains("判断是否准确的样例这是紧接上一行的续行");
        assertThat(out).doesNotContain("样例 这是");
    }

    @Test
    @DisplayName("保留段首缩进，续行合并进上一行后不再带缩进")
    void keepsParagraphIndent() {
        String page = String.join("\n",
                "   第一章绪论这是段首缩进的正文第一行内容接近满行占位填充",
                "这是续行同样接近满行需要与上一行合并成同一段落内容文字",
                "这一行比较短。",
                "   第二章这是另一段同样带缩进的第一行正文内容接近满行填",
                "这是它的续行同样接近满行需要合并到同一段落里面去的内容",
                "结尾。");

        String out = BookTextExtractor.reflowPdfPageText(page);

        String[] lines = out.split("\n");
        // 两段：每段的首行保留 4 个半角缩进，续行（含段末短行）已被并入，不再单独成行
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("   第一章").contains("这一行比较短。");
        assertThat(lines[1]).startsWith("   第二章").contains("结尾。");
    }

    @Test
    @DisplayName("空页与 null 返回空串，不抛异常")
    void handlesEmptyPage() {
        assertThat(BookTextExtractor.reflowPdfPageText(null)).isEmpty();
        assertThat(BookTextExtractor.reflowPdfPageText("")).isEmpty();
        assertThat(BookTextExtractor.reflowPdfPageText("\n \n\t\n")).isEmpty();
    }

    @Test
    @DisplayName("重排不丢可见字符（只动空白与断词连字符）")
    void neverLosesVisibleCharacters() {
        String page = String.join("\n",
                "A next consideration in my five-point framework is climate",
                "change, a term that today we tend to associate with global",
                "caused by humans. In fact, climate may become hotter or",
                "wetter or drier, or more or less variable between months",
                "tail.");

        String out = BookTextExtractor.reflowPdfPageText(page);

        assertThat(visibleChars(out)).isEqualTo(visibleChars(page));
    }

    // ---------- 字距串（letterspaced run）：一个词还是两个词 ----------

    /** 把几句话当作「全书」，建语料 */
    private static BookTextExtractor.PdfCorpus corpusOf(String... pages) {
        return BookTextExtractor.pdfCorpus(java.util.Arrays.asList(pages));
    }

    @Test
    @DisplayName("字距串是两个词连排、书里出现过该词组 ⇒ 插回空格（OFECONOMICS → OF ECONOMICS）")
    void insertsSpaceWhenPhraseIsAttested() {
        var corpus = corpusOf("the study of economics has many facets");

        assertThat(BookTextExtractor.cleanPdfLine("O F E C O N O M I C S", corpus))
                .isEqualTo("OF ECONOMICS");
    }

    @Test
    @DisplayName("书里没有该词组 ⇒ 不做切分，与修改前一致")
    void keepsRunMergedWhenPhraseIsMissing() {
        // 有 of、也有 study，但没有 "of economics"
        var corpus = corpusOf("the study of plants has many facets");

        assertThat(BookTextExtractor.cleanPdfLine("O F E C O N O M I C S", corpus))
                .isEqualTo("OFECONOMICS");
    }

    @Test
    @DisplayName("两半单看都是「词」但从未相邻出现过 ⇒ 不切分（Obeying 不被切成 Obey ing）")
    void doesNotSplitWhenHalvesNeverAdjacent() {
        // 语料里刻意同时有 obey 与断词残片 ing，但没有 "obey ing" 这个相邻组合
        var corpus = corpusOf("people obey the rules", "build-\ning is happening");

        assertThat(BookTextExtractor.cleanPdfLine("O b e y i n g", corpus))
                .isEqualTo("Obeying");
    }

    @Test
    @DisplayName("合并后的串在书里出现过 ⇒ 直接合并，不尝试切分（SOCIETIES）")
    void collapsesWhenWholeWordIsAttested() {
        var corpus = corpusOf("hunting societies collapse over time");

        assertThat(BookTextExtractor.cleanPdfLine("S O C I E T I E S", corpus))
                .isEqualTo("SOCIETIES");
    }

    @Test
    @DisplayName("词组不跨行：of 在行尾、economics 在下一行 ⇒ 不算相邻")
    void doesNotCountPhraseAcrossLineBreak() {
        var corpus = corpusOf("the study of\neconomics has many facets");

        assertThat(BookTextExtractor.cleanPdfLine("O F E C O N O M I C S", corpus))
                .isEqualTo("OFECONOMICS");
    }

    @Test
    @DisplayName("没有全书语料时保持老行为：直接合并，不插空格")
    void withoutCorpusKeepsOldBehaviour() {
        assertThat(BookTextExtractor.cleanPdfLine("O F E C O N O M I C S"))
                .isEqualTo("OFECONOMICS");
    }

    /** 只保留非空白字符，用于「重排不丢字」的等价比较 */
    private static String visibleChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
