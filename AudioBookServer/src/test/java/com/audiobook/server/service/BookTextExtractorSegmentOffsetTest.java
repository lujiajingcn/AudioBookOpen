package com.audiobook.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「目录偏移 → 段落下标」映射的回归测试。
 *
 * <p>这是 {@code detail#B5} 缺陷族的最后一块。阅读页端点（{@code extractBookContent}）早已
 * 改用「段落在原文中反查真实下标」，但 TTS 任务目录端点（{@code TtsTaskController#getTaskToc}）
 * 仍在用 {@code text_content.length()} 累加的<b>压缩坐标</b>去比目录的原文真实下标。
 * 两个坐标系不同源：切段时会 trim 掉句子与段落的首尾空白，段文本不是原文的连续子串，
 * 累加值系统性偏小、偏差随段数累积，于是「目录偏移 → 段落」的映射结果<b>段号偏大</b>，
 * 表现就是「点目录跳过头」（{@code ae94e60} 已把这种压缩累加认定为目录跳转的根因）。
 *
 * <p>本测试锁死两条路径共用的那套实现（{@link BookTextExtractor#locateStartOffsets} +
 * {@link BookTextExtractor#paragraphIndexOfOffset}）：
 * <ul>
 *   <li>空白差异 ⇒ 真实下标严格大于压缩坐标（偏差的来源）</li>
 *   <li>同一个目录偏移，用压缩坐标会落到<b>更靠后</b>的段落 ⇒ 直接复现「跳过头」</li>
 *   <li>顺序推进语义：重复句不被前一段抢占；某段未命中不推进游标</li>
 *   <li>真实 EPUB 上：目录映射到的段落必须包含该章标题（业务判据）</li>
 *   <li>两条路径同源：同一批段文本必须算出同一组下标</li>
 * </ul>
 */
class BookTextExtractorSegmentOffsetTest {

    /** 与 {@code text_content.length()} 累加等价的「压缩坐标」，仅用于反证缺陷存在 */
    private static int[] compressedOffsets(List<String> paragraphTexts) {
        int[] offsets = new int[paragraphTexts.size()];
        int cumulative = 0;
        for (int i = 0; i < paragraphTexts.size(); i++) {
            offsets[i] = cumulative;
            cumulative += paragraphTexts.get(i).length();
        }
        return offsets;
    }

    // ---------- 坐标语义 ----------

    @Test
    @DisplayName("段落间空白被切分丢弃 ⇒ 真实下标严格大于压缩坐标")
    void realOffsetsExceedCompressedOnes() {
        String text = "甲甲。\n\n乙乙。\n\n丙丙。";
        List<String> paragraphs = List.of("甲甲。", "乙乙。", "丙丙。");

        int[] real = BookTextExtractor.locateStartOffsets(text, paragraphs);

        // 每段 3 字 + 段间 2 个换行：0 / 5 / 10
        assertThat(real).containsExactly(0, 5, 10);
        // 同样这批段落文本，按长度累加只有 0 / 3 / 6 —— 每段少算 2 个字符
        assertThat(compressedOffsets(paragraphs)).containsExactly(0, 3, 6);
        // 偏差随段数累积，不可能相等
        for (int i = 1; i < real.length; i++) {
            assertThat(real[i]).isGreaterThan(compressedOffsets(paragraphs)[i]);
        }
    }

    @Test
    @DisplayName("同一目录偏移：压缩坐标会跳到更靠后的段落（复现「点目录跳过头」）")
    void compressedOffsetsJumpTooFar() {
        String text = "甲甲。\n\n乙乙。\n\n丙丙。";
        List<String> paragraphs = List.of("甲甲。", "乙乙。", "丙丙。");
        int[] real = BookTextExtractor.locateStartOffsets(text, paragraphs);
        int[] compressed = compressedOffsets(paragraphs);

        // 目录偏移 7 落在原文第 2 段「乙乙。」内部（下标 5..7）
        int charOffset = 7;

        assertThat(BookTextExtractor.paragraphIndexOfOffset(real, charOffset))
                .as("真实坐标：偏移 7 属于第 2 段（下标 1）")
                .isEqualTo(1);
        assertThat(BookTextExtractor.paragraphIndexOfOffset(compressed, charOffset))
                .as("压缩坐标：同一个偏移落到第 3 段（下标 2）—— 段号偏大，即跳过头")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("顺序推进：重复句不被前一段抢占")
    void repeatedParagraphsAdvanceInOrder() {
        String text = "重复句。\n\n重复句。\n\n结尾。";
        List<String> paragraphs = List.of("重复句。", "重复句。", "结尾。");

        // 若每次都从 0 搜，第 2 段会被定位到下标 0（与前一段重合），段号映射随之错位
        assertThat(BookTextExtractor.locateStartOffsets(text, paragraphs)).containsExactly(0, 6, 12);
    }

    @Test
    @DisplayName("某段未命中时不推进游标，后续段落仍能正确定位")
    void unmatchedParagraphKeepsCursor() {
        String text = "甲。乙。";
        List<String> paragraphs = List.of("甲。", "查无此句。", "乙。");

        assertThat(BookTextExtractor.locateStartOffsets(text, paragraphs))
                .as("未命中段落退回游标位置；后一段不受影响，仍落在下标 2")
                .containsExactly(0, 2, 2);
    }

    @Test
    @DisplayName("paragraphIndexOfOffset 边界：早于首段归首段、超出末尾归末段、空数组归 0")
    void paragraphIndexOfOffsetBoundaries() {
        int[] starts = {0, 5, 10};

        assertThat(BookTextExtractor.paragraphIndexOfOffset(starts, -3)).isEqualTo(0);
        assertThat(BookTextExtractor.paragraphIndexOfOffset(starts, 0)).isEqualTo(0);
        assertThat(BookTextExtractor.paragraphIndexOfOffset(starts, 4)).isEqualTo(0);
        assertThat(BookTextExtractor.paragraphIndexOfOffset(starts, 5)).isEqualTo(1);
        assertThat(BookTextExtractor.paragraphIndexOfOffset(starts, 10)).isEqualTo(2);
        assertThat(BookTextExtractor.paragraphIndexOfOffset(starts, 999)).isEqualTo(2);
        assertThat(BookTextExtractor.paragraphIndexOfOffset(new int[0], 5)).isEqualTo(0);
    }

    // ---------- 端到端：与阅读页端点同源 ----------

    @Test
    @DisplayName("EPUB 目录映射到的段落必须包含该章标题（业务判据）")
    void epubTocMapsToParagraphContainingChapterTitle(@TempDir Path dir) throws IOException {
        Path epub = buildEpub(dir);
        BookTextExtractor extractor = new BookTextExtractor();

        BookTextExtractor.BookContent content = extractor.extractBookContent(epub.toFile(), "epub");

        assertThat(content.toc()).extracting(entry -> entry.title)
                .containsExactly("第一章 硅谷", "第二章 一人公司");
        assertThat(content.paragraphs()).hasSizeGreaterThan(2);
        assertThat(content.toc().get(0).charOffset)
                .as("第二章的段落下标必须晚于第一章")
                .isLessThan(content.toc().get(1).charOffset);

        String chapter1 = content.paragraphs().get(content.toc().get(0).charOffset).text();
        String chapter2 = content.paragraphs().get(content.toc().get(1).charOffset).text();
        assertThat(chapter1).as("第一章目录必须落在含该章标题的段落上").contains("第一章 硅谷");
        assertThat(chapter2).as("第二章目录必须落在含该章标题的段落上").contains("第二章 一人公司");
    }

    @Test
    @DisplayName("任务目录端点与阅读页端点同源：同一批段文本必须得到同一组下标")
    void taskTocAndReaderEndpointShareOffsets(@TempDir Path dir) throws IOException {
        Path epub = buildEpub(dir);
        BookTextExtractor extractor = new BookTextExtractor();

        BookTextExtractor.BookContent content = extractor.extractBookContent(epub.toFile(), "epub");
        String fullText = extractor.extractText(epub.toFile(), "epub");

        // 模拟 TtsTaskController#getTaskToc：喂进去的是「实际合成出来的段文本」，
        // 走同一套定位实现，结果必须与 extractBookContent 记录的 charOffset 逐段一致
        List<String> segmentTexts = new ArrayList<>();
        for (BookTextExtractor.Paragraph paragraph : content.paragraphs()) {
            segmentTexts.add(paragraph.text());
        }
        int[] starts = BookTextExtractor.locateStartOffsets(fullText, segmentTexts);
        assertThat(starts).hasSize(content.paragraphs().size());
        for (int i = 0; i < starts.length; i++) {
            assertThat(starts[i])
                    .as("第 %d 段的起始下标两条路径必须一致", i + 1)
                    .isEqualTo(content.paragraphs().get(i).charOffset());
        }

        // 再用同一组下标把「原始目录（原文真实偏移）」映射成段落下标，
        // 必须与 extractBookContent 的 mappedToc 完全一致
        List<BookTextExtractor.TocEntry> rawToc = extractor.extractEpubToc(epub.toFile());
        assertThat(rawToc).hasSize(content.toc().size());
        for (int i = 0; i < rawToc.size(); i++) {
            int index = BookTextExtractor.paragraphIndexOfOffset(starts, rawToc.get(i).charOffset);
            assertThat(index)
                    .as("目录「%s」映射出的段落下标两条路径必须一致", rawToc.get(i).title)
                    .isEqualTo(content.toc().get(i).charOffset);
        }
    }

    // ---------- 测试用 EPUB ----------

    /**
     * 最小 EPUB（EPUB 2 + 带 DOCTYPE 的 NCX），每章正文刻意撑足长度：
     * 文本过短时 {@code TextSegmenter} 会把相邻章节贪心合并成一段，
     * 那样两章目录会映射到同一段落，测不出定位是否正确。
     *
     * <p>章节正文首句直接以章标题开头，于是「目录是否落在正确段落」可以退化成
     * 一个白盒判据：该段文本必须包含章标题。
     */
    private Path buildEpub(Path dir) throws IOException {
        Path epub = dir.resolve("book.epub");
        String filler = "这一段用于把章节长度撑到足以被独立切分，避免与相邻章节合并。";
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(epub))) {
            put(zos, "mimetype", "application/epub+zip");
            put(zos, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0"
                               xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="content.opf"
                                  media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """);
            put(zos, "content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>坐标测试书</dc:title>
                        <dc:identifier id="bookid">urn:uuid:offset-test</dc:identifier>
                      </metadata>
                      <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="c1" href="EPUB/xhtml/chapter1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c2" href="EPUB/xhtml/chapter2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine toc="ncx">
                        <itemref idref="c1"/>
                        <itemref idref="c2"/>
                      </spine>
                    </package>
                    """);
            put(zos, "toc.ncx", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"
                       "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                      <head>
                        <meta name="dtb:uid" content="urn:uuid:offset-test"/>
                        <meta name="dtb:depth" content="1"/>
                      </head>
                      <docTitle><text>坐标测试书</text></docTitle>
                      <navMap>
                        <navPoint id="navPoint-1" playOrder="1">
                          <navLabel><text>第一章 硅谷</text></navLabel>
                          <content src="EPUB/xhtml/chapter1.xhtml"/>
                        </navPoint>
                        <navPoint id="navPoint-2" playOrder="2">
                          <navLabel><text>第二章 一人公司</text></navLabel>
                          <content src="EPUB/xhtml/chapter2.xhtml"/>
                        </navPoint>
                      </navMap>
                    </ncx>
                    """);
            put(zos, "EPUB/xhtml/chapter1.xhtml", chapterHtml("第一章 硅谷", filler));
            put(zos, "EPUB/xhtml/chapter2.xhtml", chapterHtml("第二章 一人公司", filler));
        }
        return epub;
    }

    private String chapterHtml(String title, String filler) {
        StringBuilder sb = new StringBuilder("<html><body><h1>").append(title).append("</h1>");
        for (int i = 0; i < 4; i++) {
            sb.append("<p>").append(filler).append(filler).append("</p>");
        }
        return sb.append("</body></html>").toString();
    }

    private void put(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
