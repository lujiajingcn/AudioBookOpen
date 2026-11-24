package com.audiobook.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF / MOBI 目录坐标的回归测试。
 *
 * <p>与 {@code detail#B5}（EPUB 目录）同一类缺陷：**目录坐标与正文坐标不同源**。
 * 目录条目最终会被 {@code extractBookContent} 换算成段落下标，所以要能定位到正确的段落，
 * 目录偏移就必须落在 {@code extractText} 返回的那份文本里。
 *
 * <ul>
 *   <li><b>PDF</b>：页起点原本只累加 {@code pageText.length()}，漏掉每页追加的 {@code '\n'}，
 *       且正文最后整体 {@code trim()} 过，前导空白也没从下标里扣掉 ⇒ 每页固定偏大。</li>
 *   <li><b>MOBI</b>：原本直接拿 {@code matcher.start()}（**HTML 里的下标**）当偏移 ——
 *       HTML 里每个标签都占字符而纯文本里已被替换/删除，两个坐标系毫无关系，
 *       偏移几乎总是超出纯文本长度 ⇒ 点任何目录都跳到最后。</li>
 * </ul>
 */
class BookTextExtractorFormatOffsetTest {

    /** 与 String.trim() / TextSegmenter 一致的空白判定 */
    private static String stripBlank(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > ' ') {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    // ---------- PDF ----------

    @Test
    @DisplayName("PDF 页起点与正文同源：必须算上每页追加的换行")
    void pdfPageOffsetsMatchJoinedText() {
        List<String> pages = List.of("第一页内容。", "第二页内容。", "第三页内容。");

        String text = BookTextExtractor.joinPdfPages(pages);
        int[] offsets = BookTextExtractor.pdfPageStartOffsets(pages);

        assertThat(text).isEqualTo("第一页内容。\n第二页内容。\n第三页内容。");
        // 每页固定 6 字 + 一个换行 = 7
        assertThat(offsets).containsExactly(0, 7, 14);

        for (int i = 0; i < pages.size(); i++) {
            assertThat(text.substring(offsets[i]))
                    .as("第 %d 页起点应正好落在该页正文上", i + 1)
                    .startsWith(pages.get(i));
        }
    }

    @Test
    @DisplayName("PDF 前导空白被 trim 后，页起点必须同步左移")
    void pdfPageOffsetsAccountForLeadingTrim() {
        List<String> pages = List.of("\n\n第一页内容。", "第二页内容。");

        String text = BookTextExtractor.joinPdfPages(pages);
        int[] offsets = BookTextExtractor.pdfPageStartOffsets(pages);

        assertThat(text).isEqualTo("第一页内容。\n第二页内容。");
        assertThat(offsets[0]).isEqualTo(0);
        // 少了前导 2 个字符：7 = 8(页长+换行) - 2
        assertThat(offsets[1]).isEqualTo(7);
        assertThat(text.substring(offsets[1])).isEqualTo("第二页内容。");
    }

    @Test
    @DisplayName("PDF 空页不占位，与后一页同起点")
    void pdfEmptyPageSharesNextOffset() {
        List<String> pages = List.of("甲页。", "", "乙页。");

        String text = BookTextExtractor.joinPdfPages(pages);
        int[] offsets = BookTextExtractor.pdfPageStartOffsets(pages);

        assertThat(text).isEqualTo("甲页。\n乙页。");
        assertThat(offsets).containsExactly(0, 4, 4);
        assertThat(text.substring(offsets[2])).isEqualTo("乙页。");
    }

    // ---------- MOBI ----------

    @Test
    @DisplayName("MOBI 目录偏移必须落在 stripHtmlTags 的纯文本里（不是 HTML 下标）")
    void mobiTocOffsetsArePlainTextIndexes() {
        String body1 = "第一章的正文内容。".repeat(40);
        String body2 = "第二章的正文内容。".repeat(40);
        String body3 = "第三章的正文内容。".repeat(40);
        String html = "<html><body>"
                + "<h1>第一章 开端</h1><p>" + body1 + "</p>"
                + "<h1><span>第二章</span> 转折</h1><p>" + body2 + "</p>"
                + "<h2>第三章 收束</h2><p>" + body3 + "</p>"
                + "</body></html>";

        BookTextExtractor extractor = new BookTextExtractor();
        String plain = extractor.stripHtmlTags(html);
        List<BookTextExtractor.TocEntry> toc = extractor.scanMobiHeadings(html);

        assertThat(toc).hasSize(3);
        assertThat(toc.stream().map(e -> e.title).toList())
                .containsExactly("第一章 开端", "第二章 转折", "第三章 收束");

        for (BookTextExtractor.TocEntry entry : toc) {
            // 旧实现给的是 HTML 下标，会直接越出纯文本范围
            assertThat(entry.charOffset)
                    .as("目录「%s」的偏移应落在纯文本范围内（长度 %d）", entry.title, plain.length())
                    .isBetween(0, plain.length() - 1);
            assertThat(stripBlank(plain.substring(entry.charOffset)))
                    .as("目录「%s」的偏移应指向该标题", entry.title)
                    .startsWith(stripBlank(entry.title));
        }

        // 顺序必须单调递增（章节顺序不能乱）
        assertThat(toc.get(1).charOffset).isGreaterThan(toc.get(0).charOffset);
        assertThat(toc.get(2).charOffset).isGreaterThan(toc.get(1).charOffset);

        // 与旧实现的方向对照：纯文本下标恒小于同一位置的 HTML 下标（标签被替换掉了）
        assertThat(toc.get(2).charOffset).isLessThan(html.indexOf("<h2"));
    }
}
