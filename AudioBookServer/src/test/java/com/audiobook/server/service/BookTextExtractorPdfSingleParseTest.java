package com.audiobook.server.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF「正文与目录一次解析」的回归测试。
 *
 * <p><b>背景</b>：{@link BookTextExtractor#extractBookContent} 原本先调
 * {@code extractText}（= {@code extractPdf}）取正文、再调 {@code extractToc}
 * （= {@code extractPdfToc}）取目录，而这两个方法各自
 * {@code Loader.loadPDF(file) + stripPdfPages(document)} 走一遍 ——
 * 同一本 PDF 被<b>完整解析两次</b>。PDF 逐页取文是整条链路上最贵的一步
 * （实测 401 页的《无穷的开始》单遍约 5s，两遍就是 10s），
 * 而阅读页必须等这 10s 才有正文返回。
 *
 * <p>本测试锁死两件事：
 * <ul>
 *   <li>{@link BookTextExtractor#extractPdfTextAndToc} 的产物与旧的「两次解析」路径<b>逐字段等价</b>
 *       —— 合并解析不能改变任何输出，只是省掉一次重复劳动；</li>
 *   <li>{@link BookTextExtractor#extractBookContent} 在 PDF 上走合并路径后，
 *       目录条目仍落在正确的段落下标上（点目录不跳过头）。</li>
 * </ul>
 */
class BookTextExtractorPdfSingleParseTest {

    /** 造一本带书签的多页 PDF：正文每页一行，书签指向第 2、4 页。 */
    private static Path buildPdf(Path dir, String name) throws IOException {
        Path pdf = dir.resolve(name);
        try (PDDocument document = new PDDocument()) {
            List<PDPage> pages = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                pages.add(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("CHAPTER " + i + " BEGINS HERE.");
                    cs.endText();
                }
            }
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            outline.addLast(item("CHAPTER TWO BEGINS HERE.", pages.get(1)));
            outline.addLast(item("CHAPTER FOUR BEGINS HERE.", pages.get(3)));
            document.save(pdf.toFile());
        }
        return pdf;
    }

    /** 造一个与真实文件同形的书签：/A = GoTo + 页目标（而非目录项自带的 /Dest） */
    private static PDOutlineItem item(String title, PDPage target) {
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        PDPageXYZDestination dest = new PDPageXYZDestination();
        dest.setPage(target);
        PDActionGoTo action = new PDActionGoTo();
        action.setDestination(dest);
        item.setAction(action);
        return item;
    }

    @Test
    @DisplayName("一次解析的产物与两次解析等价（正文逐字相同、目录条目逐个相同）")
    void singleParseEqualsDoubleParse(@TempDir Path dir) throws Exception {
        Path pdf = buildPdf(dir, "equiv.pdf");
        BookTextExtractor extractor = new BookTextExtractor();

        // 旧路径：正文一次 + 目录一次（两次 loadPDF）
        String oldText = extractor.extractText(pdf.toFile(), "pdf");
        List<BookTextExtractor.TocEntry> oldToc = extractor.extractToc(pdf.toFile(), "pdf");

        // 新路径：一次解析同时产出
        BookTextExtractor.PdfTextAndToc combined = extractor.extractPdfTextAndToc(pdf.toFile());

        assertThat(combined.text()).isEqualTo(oldText);
        assertThat(combined.toc()).hasSameSizeAs(oldToc);
        for (int i = 0; i < oldToc.size(); i++) {
            assertThat(combined.toc().get(i).title).isEqualTo(oldToc.get(i).title);
            assertThat(combined.toc().get(i).charOffset).isEqualTo(oldToc.get(i).charOffset);
            assertThat(combined.toc().get(i).pageNumber).isEqualTo(oldToc.get(i).pageNumber);
        }
    }

    @Test
    @DisplayName("extractBookContent 在 PDF 上：目录落到正确段落下标（合并解析没破坏映射）")
    void bookContentMapsTocToParagraphIndex(@TempDir Path dir) throws Exception {
        Path pdf = buildPdf(dir, "toc.pdf");
        BookTextExtractor extractor = new BookTextExtractor();

        BookTextExtractor.BookContent content = extractor.extractBookContent(pdf.toFile(), "pdf");

        assertThat(content.paragraphs()).isNotEmpty();
        assertThat(content.toc()).isNotEmpty();
        // 目录条目的 charOffset 在 BookContent 路径下语义是「段落下标」：
        // 必须落在实际段落数范围内，且随目录顺序单调不减（点目录不会跳过头）。
        int prev = -1;
        for (BookTextExtractor.TocEntry entry : content.toc()) {
            assertThat(entry.charOffset).isBetween(0, content.paragraphs().size() - 1);
            assertThat(entry.charOffset).isGreaterThanOrEqualTo(prev);
            prev = entry.charOffset;
        }
    }

    @Test
    @DisplayName("extractBookContent 在 PDF 上仍产出 rows（阅读页显示原文行）")
    void bookContentStillProducesRows(@TempDir Path dir) throws Exception {
        Path pdf = buildPdf(dir, "rows.pdf");
        BookTextExtractor extractor = new BookTextExtractor();

        BookTextExtractor.BookContent content = extractor.extractBookContent(pdf.toFile(), "pdf");

        assertThat(content.rows()).isNotNull();
        assertThat(content.rows()).isNotEmpty();
        // rows 与 rowParagraphStart 必须等长（客户端按同一下标配对）
        assertThat(content.rowParagraphStart()).hasSameSizeAs(content.rows());
        assertThat(content.rowParagraphEnd()).hasSameSizeAs(content.rows());
    }
}
