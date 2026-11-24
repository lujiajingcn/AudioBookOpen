package com.audiobook.server.service;

import com.audiobook.server.util.TextSegmenter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「原文行」（{@code rows}）的回归测试。
 *
 * <p>背景：阅读页此前在 PDF/MOBI 上显示的是 {@code TextSegmenter} 切出的**合成片段**
 * （旧口径 ≤30 字，且切分时 trim 掉了段首空白与换行）—— 一堵没有缩进、没有段落的碎片墙。
 * 客户端对这两种格式没有本地解析能力，只能由服务端把**原文行**一并带回去。
 *
 * <p>本测试锁死三件事：
 * <ul>
 *   <li>{@link BookTextExtractor#splitRows} 的语义：**只裁行尾、保留行首缩进**、丢纯空白行；</li>
 *   <li>TXT 不产出（返回 null）——避免给唯一可能到 50MB 的路径多切一份行列表；</li>
 *   <li>真实 PDF/EPUB 上「{@code rows} 与 {@code paragraphs} 非空白字符完全同源」——
 *       这是客户端 {@code EbookSegmentMap} 能把片段逐一映射回行、进而「点哪句播哪句」的前提。</li>
 * </ul>
 */
class BookTextExtractorRowsTest {

    // ---------- splitRows 的语义 ----------

    @Test
    @DisplayName("保留行首缩进、只裁行尾空白（含 CRLF 的 \\r）")
    void keepsLeadingIndentAndStripsTrailingWhitespace() {
        String text = "  甲甲甲。  \n\t乙乙乙。\r\n丙丙丙。\t\n";

        List<String> rows = BookTextExtractor.splitRows(text, "pdf");

        assertThat(rows).containsExactly("  甲甲甲。", "\t乙乙乙。", "丙丙丙。");
        // 缩进必须还在：这是「保留段首缩进」的全部意义
        assertThat(rows.get(0)).startsWith("  ");
        assertThat(rows.get(0)).doesNotStartWith("    ");
        // 行尾的空格/制表符/'\\r' 必须裁掉，否则渲染出看不见的尾巴
        assertThat(rows).allSatisfy(row -> assertThat(row).isEqualTo(row.stripTrailing()));
        assertThat(rows).allSatisfy(row -> assertThat(row).doesNotContain("\r"));
    }

    @Test
    @DisplayName("纯空白行被丢弃（阅读页不该出现空行）")
    void dropsBlankLines() {
        String text = "甲甲。\n\n   \n\t\n乙乙。";

        List<String> rows = BookTextExtractor.splitRows(text, "pdf");

        assertThat(rows).containsExactly("甲甲。", "乙乙。");
    }

    @Test
    @DisplayName("TXT 与空正文返回 null（不产出 rows）")
    void txtAndEmptyTextProduceNoRows() {
        assertThat(BookTextExtractor.splitRows("  甲甲。  \n乙乙。", "txt")).isNull();
        // 大小写不敏感：扩展名来自上传文件名
        assertThat(BookTextExtractor.splitRows("甲甲。", "TXT")).isNull();
        assertThat(BookTextExtractor.splitRows("", "pdf")).isNull();
        assertThat(BookTextExtractor.splitRows(null, "pdf")).isNull();
    }

    @Test
    @DisplayName("只有换行的正文产出空列表而非 null（调用方按空不写入响应）")
    void whitespaceOnlyTextProducesEmptyList() {
        assertThat(BookTextExtractor.splitRows("\n\n   \n", "pdf")).isEmpty();
    }

    // ---------- 行 → 段 下标表（rowParagraphStart / rowParagraphEnd）----------

    @Test
    @DisplayName("半角缩进要被跳过：与 locateIgnoringBlank 同一个「空白」谓词，否则整份映射错位一行")
    void rowIndexSkipsAsciiIndent() {
        // 换行不是句边界 ⇒ 「乙乙。」是第二段。段落侧的缩进被 trim() 去掉（trim 只去 <= ' '），
        // 原文侧的缩进被 locateIgnoringBlank 跳过 ⇒ 段落起点是「乙」（下标 6）。
        // 行首下标若取「行首那一格空格」（下标 4），这一行就会被判成第一段 —— 错位一行
        String text = "甲甲。\n  乙乙。";
        int[] starts = BookTextExtractor.locateStartOffsets(text, TextSegmenter.sentences(text));

        BookTextExtractor.RowSplit split = BookTextExtractor.splitRowsWithStarts(text, "pdf");

        assertThat(split.rows()).containsExactly("甲甲。", "  乙乙。");
        assertThat(split.starts()).containsExactly(0, 6);
        assertThat(split.ends()).containsExactly(2, 8);
        assertThat(rowRange(text, starts).start()).containsExactly(0, 1);
        assertThat(rowRange(text, starts).end()).containsExactly(0, 1);
    }

    @Test
    @DisplayName("全角缩进是段落自己的一部分（trim 不去它）⇒ 行首下标落在缩进上，同样同源")
    void rowIndexKeepsFullWidthIndent() {
        // 与上一条相反：全角空格 > ' '，trim 与 locateIgnoringBlank 都不动它，
        // 于是「段落起点」就是缩进的第一格、行首实字也在同一格 —— 两侧仍对齐。
        // 这两条一起钉死「缩进该不该跳过」的判据只有一条：<= ' '
        String text = "甲甲。\n\u3000\u3000乙乙。";
        int[] starts = BookTextExtractor.locateStartOffsets(text, TextSegmenter.sentences(text));

        BookTextExtractor.RowSplit split = BookTextExtractor.splitRowsWithStarts(text, "pdf");

        assertThat(TextSegmenter.sentences(text))
                .containsExactly("甲甲。", "\u3000\u3000乙乙。");
        assertThat(split.starts()).containsExactly(0, 4);
        assertThat(split.ends()).containsExactly(2, 8);
        assertThat(rowRange(text, starts).start()).containsExactly(0, 1);
        assertThat(rowRange(text, starts).end()).containsExactly(0, 1);
    }

    @Test
    @DisplayName("一行含多句：该行的 start 归「行首那句」，end 归「行尾那句」")
    void rowIndexMapsBothEndsOfRow() {
        // 一行两句 ⇒ 段 1 不拥有任何以它为行首的行，但它是行尾那一句 ⇒ end 必须指到 1。
        // 只看 start 的客户端会漏掉段 1 的高亮（这正是要两端都带回去的原因）
        String text = "甲甲。乙乙。\n丙丙。\n\n丁丁丁。";
        int[] starts = BookTextExtractor.locateStartOffsets(text, TextSegmenter.sentences(text));

        assertThat(TextSegmenter.sentences(text)).containsExactly("甲甲。", "乙乙。", "丙丙。", "丁丁丁。");
        assertThat(rowRange(text, starts).start())
                .as("行0 从段0 起、行1 是段2（空行被丢弃）、行2 是段3")
                .containsExactly(0, 2, 3);
        assertThat(rowRange(text, starts).end())
                .as("行0 终于段1 ⇒ 段1 仍被行0 覆盖")
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("一句跨多行：这几行的 start/end 全是同一段（客户端据此整段一起高亮）")
    void rowIndexRepeatsForMultiRowParagraph() {
        // 三行都没有句末标点 ⇒ 整段只有 1 句
        String text = "甲乙丙丁\n戊己庚辛\n壬癸子丑。";
        int[] starts = BookTextExtractor.locateStartOffsets(text, TextSegmenter.sentences(text));

        assertThat(TextSegmenter.sentences(text)).hasSize(1);
        assertThat(rowRange(text, starts).start()).containsExactly(0, 0, 0);
        assertThat(rowRange(text, starts).end()).containsExactly(0, 0, 0);
    }

    @Test
    @DisplayName("本段从上一行中间开始、又跨到下一行：上一行与下一行的 end/start 各指到它")
    void rowIndexCoversParagraphStartingMidRow() {
        // 段1「乙乙\n乙。」由 行0 的尾巴「乙乙」+ 行1 的头「乙。」组成 ——
        // 两端都在场时，「段1 占哪几行」才数得全（只看 start 会只剩行1）。
        // 注意句内的换行会从段文本里去掉（换行不是句边界，送进 TTS 会在那里停顿），
        // 但**坐标定位忽略空白**，所以 start/end 不受影响。
        String text = "甲。乙乙\n乙。丙。";
        int[] starts = BookTextExtractor.locateStartOffsets(text, TextSegmenter.sentences(text));

        assertThat(TextSegmenter.sentences(text)).containsExactly("甲。", "乙乙乙。", "丙。");
        assertThat(rowRange(text, starts).start()).containsExactly(0, 1);
        assertThat(rowRange(text, starts).end()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("TXT / 空正文不给下标表（与 rows 同为 null）")
    void txtProducesNoRowIndex() {
        assertThat(BookTextExtractor.splitRowsWithStarts("甲甲。\n乙乙。", "txt")).isNull();
        assertThat(BookTextExtractor.splitRowsWithStarts("", "pdf")).isNull();
        assertThat(BookTextExtractor.splitRowsWithStarts(null, "pdf")).isNull();
    }

    /** 与 {@code BookTextExtractor.extractBookContent} 同一路算法（包内可见，此处直接复用） */
    private static RowRange rowRange(String text, int[] starts) {
        BookTextExtractor.RowSplit split = BookTextExtractor.splitRowsWithStarts(text, "pdf");
        List<Integer> start = new java.util.ArrayList<>();
        List<Integer> end = new java.util.ArrayList<>();
        for (int i = 0; i < split.rows().size(); i++) {
            start.add(BookTextExtractor.paragraphIndexOfOffset(starts, split.starts().get(i)));
            end.add(BookTextExtractor.paragraphIndexOfOffset(starts, split.ends().get(i)));
        }
        return new RowRange(start, end);
    }

    private record RowRange(List<Integer> start, List<Integer> end) {}

    // ---------- 真实 PDF ----------

    @Test
    @DisplayName("真实 PDF：rows 是逐行原文，paragraphs 是完整句子 —— 一句可跨多行")
    void realPdfRowsAreLinesAndParagraphsAreSentences(@TempDir Path dir) throws IOException {
        // 三行都不含句末标点，只有最后一行末尾有一个 '.'：整段文本只有 1 个句子，而原文有 3 行
        // ⇒「一句跨 3 行」。这是「片段 = 完整句子」口径下两种粒度必然不同的最小例子
        // （旧口径是反过来的：30 字硬切让片段比行更碎）。
        String line = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmn";
        String lastLine = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklm.";   // 同为 40 字，末位是句号
        Path pdf = buildPdf(dir, List.of(line, line, lastLine));

        BookTextExtractor.BookContent content =
                new BookTextExtractor().extractBookContent(pdf.toFile(), "pdf");

        // rows：3 行原文（行尾的 '\r' 由 splitRows 的 stripTrailing 处理，
        // 因此本断言与平台无关 —— PDFBox 的行分隔符是 System.lineSeparator()）
        assertThat(content.rows()).hasSize(3);
        assertThat(content.rows()).allSatisfy(row -> assertThat(row).hasSize(40));

        // paragraphs：整段只有一句 ⇒ 比行数少。**不能**再断言它 ≤30 字 ——
        // 片段是完整句子，可以远超引擎单次上限（超长由合成侧拆小片 + 拼接，见 TextSegmenter#subPieces）。
        assertThat(content.paragraphs()).hasSize(1);
        assertThat(content.paragraphs().get(0).text().length())
                .as("这一句横跨了三行原文")
                .isGreaterThan(40);

        // 同源：非空白字符逐字相等（客户端据此把每个片段定位到行）
        assertThat(joinWithoutBlank(content.rows()))
                .isEqualTo(joinWithoutBlank(paragraphsOf(content)));

        // 行 → 段下标表：3 行同一句 ⇒ 两端都是段 0。客户端「点哪行播哪段」全靠它
        assertThat(content.rowParagraphStart()).hasSize(3).containsOnly(0);
        assertThat(content.rowParagraphEnd()).hasSize(3).containsOnly(0);
    }

    @Test
    @DisplayName("真实 PDF：客户端高亮算法（按两端下标二分）为每一段圈出的行，逐字等于该段原文")
    void clientHighlightRangeMatchesParagraphExactly(@TempDir Path dir) throws IOException {
        // 4 行 / 3 句：第 2、3 行都没有句末标点 ⇒ 中间那句横跨两行。这正好覆盖
        // 「一句跨多行」与「行/段边界对齐」两种情形，是客户端高亮算法最直接的验证夹具
        Path pdf = buildPdf(dir, List.of(
                "Alpha beta gamma delta epsilon.",
                "Zeta eta theta iota kappa lambda mu",
                "nu xi omicron pi rho sigma tau.",
                "Upsilon phi chi psi omega."));

        BookTextExtractor.BookContent content =
                new BookTextExtractor().extractBookContent(pdf.toFile(), "pdf");

        assertThat(content.paragraphs()).hasSize(3);
        assertThat(content.rows()).hasSize(4);
        assertThat(content.rowParagraphStart()).as("行0=段0, 行1/2=段1, 行3=段2").containsExactly(0, 1, 1, 2);
        assertThat(content.rowParagraphEnd()).containsExactly(0, 1, 1, 2);

        for (BookTextExtractor.Paragraph para : content.paragraphs()) {
            int[] range = clientRowRange(content.rowParagraphStart(), content.rowParagraphEnd(),
                    para.paragraphIndex());
            assertThat(range[1] - range[0])
                    .as("段 %d 必须圈到至少一行（圈不到 = 高亮会整段不亮）", para.paragraphIndex())
                    .isPositive();
            List<String> hit = content.rows().subList(range[0], range[1]);
            assertThat(joinWithoutBlank(hit))
                    .as("段 %d 圈到的行拼起来应逐字等于该段原文", para.paragraphIndex())
                    .isEqualTo(joinWithoutBlank(List.of(para.text())));
        }
    }

    /**
     * 客户端（{@code Reader.vue}）算「第 p 段占哪几行」的同一条算法：
     * {@code [第一个 end >= p 的行, 第一个 start > p 的行)}。
     * 两端下标各自单调不减，故都能二分；这里在服务端把它复算一遍 ——
     * 客户端的等价实现若与它不一致，本用例先红，不必等到页面上肉眼发现高亮错行。
     */
    private static int[] clientRowRange(List<Integer> start, List<Integer> end, int p) {
        return new int[]{lowerBound(end, p), upperBound(start, p)};
    }

    @Test
    @DisplayName("真实 PDF：一行含多句时，行内段区间把后续句起点标在真实字符位置（而非整行左缘）")
    void rowParagraphSegmentsMarksMidRowSentenceStart(@TempDir Path dir) throws IOException {
        // 一行里连写三句（无换行）：句末标点后仍有内容 ⇒ 这一行同时含段0/1/2，
        // 段1、段2 都从行中间开始。rowParagraphSegments 必须把它们的起点标在真实字符下标，
        // 客户端据此才能让「首行蓝底」从音频真实播放位置起，而不是盖到上一句的残字。
        Path pdf = buildPdf(dir, List.of("Alpha. Beta. Gamma."));

        BookTextExtractor.BookContent content =
                new BookTextExtractor().extractBookContent(pdf.toFile(), "pdf");

        // 一行 ⇒ 一个 row；三句 ⇒ 三个 paragraph；这一行的 start/end 跨 0..2
        assertThat(content.rows()).hasSize(1);
        assertThat(content.paragraphs()).hasSize(3);
        assertThat(content.rowParagraphStart()).containsExactly(0);
        assertThat(content.rowParagraphEnd()).containsExactly(2);

        List<int[]> segs = content.rowParagraphSegments().get(0);
        // 一行三句 ⇒ 三个段区间，按段号顺序
        assertThat(segs).hasSize(3);
        assertThat(segs.get(0)[0]).isZero();
        assertThat(segs.get(0)[1]).isZero();                 // 段0 从行首起
        assertThat(segs.get(1)[0]).isEqualTo(1);
        assertThat(segs.get(1)[1]).as("段1 起点必须 > 0：否则首行蓝底会盖到上一句").isGreaterThan(0);
        assertThat(segs.get(2)[0]).isEqualTo(2);
        assertThat(segs.get(2)[1]).isGreaterThan(segs.get(1)[1]);

        // 区间连续、不重叠、且覆盖整行（无遗漏也无越界）
        int lastEnd = -1;
        for (int[] seg : segs) {
            assertThat(seg[1]).as("段区间必须紧邻上一区间").isEqualTo(lastEnd + 1);
            assertThat(seg[2]).isGreaterThanOrEqualTo(seg[1]);
            lastEnd = seg[2];
        }
        assertThat(lastEnd).isEqualTo(content.rows().get(0).length() - 1);

        // 坐标同源：用段区间切出的每段文字（去空白）应逐字等于该段原文
        String row = content.rows().get(0);
        for (BookTextExtractor.Paragraph para : content.paragraphs()) {
            int[] seg = segs.get(para.paragraphIndex());
            String piece = row.substring(seg[1], seg[2] + 1);
            assertThat(joinWithoutBlank(List.of(piece)))
                    .as("段 %d 的区间切出的文字应等于其原文", para.paragraphIndex())
                    .isEqualTo(joinWithoutBlank(List.of(para.text())));
        }
    }

    private static int lowerBound(List<Integer> sorted, int value) {
        int lo = 0, hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid) < value) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static int upperBound(List<Integer> sorted, int value) {
        int lo = 0, hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid) <= value) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    // ---------- 真实 EPUB ----------

    @Test
    @DisplayName("真实 EPUB：rows 保留段首缩进，且与 paragraphs 非空白字符完全同源")
    void realEpubRowsKeepParagraphIndent(@TempDir Path dir) throws IOException {
        Path epub = buildEpub(dir);

        BookTextExtractor.BookContent content =
                new BookTextExtractor().extractBookContent(epub.toFile(), "epub");

        assertThat(content.rows()).isNotNull().isNotEmpty();
        // EPUB 的段落来自 <p> 标签 ⇒ 每段一行
        assertThat(content.rows()).anySatisfy(row -> assertThat(row).startsWith("　　"));
        assertThat(content.rows()).contains("　　第一章 起风了。");
        // 同源判据
        assertThat(joinWithoutBlank(content.rows()))
                .isEqualTo(joinWithoutBlank(paragraphsOf(content)));
        // 行 → 段下标表的不变式：与 rows 等长、各自单调不减、start<=end、首行必属段 0、不越界。
        // 只断言不变式而不是具体值 —— EPUB 还可能有标题页等额外段落，钉死具体值会假红
        int lastPara = content.paragraphs().size() - 1;
        assertThat(content.rowParagraphStart()).hasSize(content.rows().size());
        assertThat(content.rowParagraphEnd()).hasSize(content.rows().size());
        assertThat(content.rowParagraphStart().get(0)).isZero();
        assertThat(content.rowParagraphStart())
                .isSorted()
                .allSatisfy(idx -> assertThat(idx).isBetween(0, lastPara));
        assertThat(content.rowParagraphEnd())
                .isSorted()
                .allSatisfy(idx -> assertThat(idx).isBetween(0, lastPara));
        for (int i = 0; i < content.rows().size(); i++) {
            assertThat(content.rowParagraphStart().get(i))
                    .as("第 %d 行的 start 不可能大于 end", i)
                    .isLessThanOrEqualTo(content.rowParagraphEnd().get(i));
        }
    }

    // ---------- 夹具 ----------

    private static List<String> paragraphsOf(BookTextExtractor.BookContent content) {
        return content.paragraphs().stream().map(BookTextExtractor.Paragraph::text).toList();
    }

    /** 拼接并去掉所有空白：这是「两端不同源」最灵敏的判据（空白本来就是允许有差异的部分） */
    private static String joinWithoutBlank(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isWhitespace(part.charAt(i))) {
                    sb.append(part.charAt(i));
                }
            }
        }
        return sb.toString();
    }

    private static Path buildPdf(Path dir, List<String> lines) throws IOException {
        Path pdf = dir.resolve("book.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                for (int i = 0; i < lines.size(); i++) {
                    if (i > 0) {
                        cs.newLineAtOffset(0, -20);
                    }
                    cs.showText(lines.get(i));
                }
                cs.endText();
            }
            document.save(pdf.toFile());
        }
        return pdf;
    }

    private static Path buildEpub(Path dir) throws IOException {
        Path epub = dir.resolve("book.epub");
        String opf = """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>测试书</dc:title>
                  </metadata>
                  <manifest>
                    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="c1"/>
                  </spine>
                </package>
                """;
        String container = """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """;
        // 段首用全角空格缩进：stripHtmlTags 会把 [ \t]+ 压成单个半角空格，
        // 全角空格不在其归一化范围内，正好用来断言「缩进没被吃掉」
        String xhtml = "<html><body><p>　　第一章 起风了。</p><p>　　他站在钟楼下，听见了那声闷响。</p></body></html>";
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(epub))) {
            // mimetype 必须是第一个条目且不压缩，否则不是合法 EPUB
            ZipEntry mimetype = new ZipEntry("mimetype");
            zos.putNextEntry(mimetype);
            zos.write("application/epub+zip".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            put(zos, "META-INF/container.xml", container);
            put(zos, "content.opf", opf);
            put(zos, "c1.xhtml", xhtml);
        }
        return epub;
    }

    private static void put(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
