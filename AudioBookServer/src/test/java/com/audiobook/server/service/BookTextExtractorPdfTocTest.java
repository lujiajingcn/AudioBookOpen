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
 * PDF 书签（outline）→ 页码 → 原文偏移 的回归测试。
 *
 * <p>背景：{@code resolveOutlineItemPage} 早期用**反射**依次尝试
 * {@code getTargetPage(PDDocument)} 与 {@code getRetrievePageNumber()} —— 这两个方法在
 * PDFBox 3.0.2 里**都不存在**，反射异常被 {@code log.debug} 吞掉后方法静默返回 -1，
 * 于是每个书签的 {@code charOffset} 都落成 0：用户点任何一条目录都跳回书的开头，
 * 而服务端日志一片安静（真实 94 页 PDF 实测：26 条目录全部 pageNumber=0）。
 *
 * <p>正确读法是 {@code PDPageDestination.getPage()}：
 * 这类由 Word 导出的书签（{@code /A << /S /GoTo /D [ 3 0 R /XYZ … ] >>}）只在解析时
 * 填了 {@code PDPage} 对象，{@code getPageNumber()} 实测恒为 -1。
 *
 * <p>本测试锁死：书签能解析出**正确且递增**的页码，落在**对应页**的原文位置上。
 */
class BookTextExtractorPdfTocTest {

    @Test
    @DisplayName("书签经 /A GoTo + PDPageXYZDestination 解析出正确页码与页起点偏移")
    void resolvesOutlinePageAndOffset(@TempDir Path dir) throws IOException {
        Path pdf = buildOutlinePdf(dir);

        BookTextExtractor extractor = new BookTextExtractor();
        List<BookTextExtractor.TocEntry> toc = extractor.extractPdfToc(pdf.toFile());

        assertThat(toc).hasSize(2);
        // 页码必须是真实页号（修复前恒为 0）——「（一）序言」在第 2 页、「（二）正文」在第 4 页
        assertThat(toc).extracting(t -> t.pageNumber).containsExactly(2, 4);
        // 偏移必须递增且互不相同（修复前全是 0）
        assertThat(toc.get(0).charOffset)
                .as("书签偏移必须真的落在各自页上，不能都塌到 0")
                .isLessThan(toc.get(1).charOffset)
                .isPositive();

        // 端到端：偏移必须正好落在该页**标题**上（页内按标题定位），不是页首
        String text = extractor.extractText(pdf.toFile(), "pdf");
        assertThat(text.substring(toc.get(0).charOffset).stripLeading()).startsWith("BRAVO");
        assertThat(text.substring(toc.get(1).charOffset).stripLeading()).startsWith("DELTA");

        // 目录条目最终要能映射成段落下标（阅读页/播放器用它跳转）
        List<String> paras = com.audiobook.server.util.TextSegmenter.sentences(text);
        int[] starts = BookTextExtractor.locateStartOffsets(text, paras);
        assertThat(BookTextExtractor.paragraphIndexOfOffset(starts, toc.get(0).charOffset))
                .isLessThan(BookTextExtractor.paragraphIndexOfOffset(starts, toc.get(1).charOffset));
    }

    @Test
    @DisplayName("页内按标题定位：落点收敛到标题本身，而不是所在页的页首")
    void headingSearchNarrowsLandingToTitle(@TempDir Path dir) throws IOException {
        // 书签指向第 3 页；该页内容为「前页续下来的句子 + 标题 + 正文」，
        // 页首偏移落在一个句子的中间 —— 页内定位必须把它收敛到标题那一段
        Path pdf = buildHeadingPdf(dir);

        BookTextExtractor extractor = new BookTextExtractor();
        List<BookTextExtractor.TocEntry> toc = extractor.extractPdfToc(pdf.toFile());

        assertThat(toc).hasSize(1);
        assertThat(toc.get(0).pageNumber).isEqualTo(3);

        String text = extractor.extractText(pdf.toFile(), "pdf");
        // 落点必须是标题的首字。页首偏移会比它早 —— 页首是上一页续下来的句子的尾巴，
        // 按页首落点会「提前几行」，映射成段落后还会更早
        int pageStart = text.indexOf("CONTINUED FROM PREVIOUS PAGE");
        assertThat(pageStart).as("夹具第 3 页首行应在原文里找得到").isPositive();
        assertThat(toc.get(0).charOffset)
                .as("页内按标题定位必须比页首更靠后")
                .isGreaterThan(pageStart);
        assertThat(text.substring(toc.get(0).charOffset).stripLeading())
                .startsWith("CHAPTER TWO BEGINS HERE.");
    }

    @Test
    @DisplayName("端到端：Dest 直数组书签经 extractBookContent 映射到正确段落下标")
    void endToEndMapsDirectDestTocToParagraphIndex(@TempDir Path dir) throws IOException {
        // ⛔ 这条锁死的是**线上事故的完整链路**：
        //   服务端 `extractBookContent` → 目录 charOffset 映射成段落下标 →
        //   API 返回 toc[].paragraphIndex → 客户端点目录跳转。
        // 事故时这一步全塌成 0：客户端点任何章节都跳到书的最开头。
        Path pdf = buildDirectDestOutlinePdf(dir);

        BookTextExtractor extractor = new BookTextExtractor();
        BookTextExtractor.BookContent content = extractor.extractBookContent(pdf.toFile(), "pdf");

        List<BookTextExtractor.TocEntry> toc = content.toc();
        assertThat(toc).hasSize(2);
        // 段落下标必须**递增且互不相同**（全 0 = 事故症状）
        assertThat(toc.get(0).charOffset)
                .as("目录条目映射出的段落下标不能都塌成 0")
                .isLessThan(toc.get(1).charOffset)
                .isPositive();
        // 页码也要正确
        assertThat(toc).extracting(t -> t.pageNumber).containsExactly(2, 4);
        // 落点必须落在该章标题所在的段上
        String text = content.paragraphs().get(toc.get(0).charOffset).text();
        assertThat(text).contains("BRAVO");
        String text2 = content.paragraphs().get(toc.get(1).charOffset).text();
        assertThat(text2).contains("DELTA");
    }

    @Test
    @DisplayName("书签用 Dest 直数组（无 A 动作）时也能解析出正确页码")
    void resolvesOutlineWithDirectDestArray(@TempDir Path dir) throws IOException {
        // ⛔ 真实 PDF《无穷的开始英文原版》（401 页，9 个书签）**全部**用
        //    Dest 直数组形式，字典里**没有 A 键** ⇒ item.getAction() 返回 null
        // ⇒ 旧实现（只认 getAction）每个条目都返回 -1 ⇒ 所有 charOffset/pageNumber 落成 0
        // ⇒ 服务端返回的目录「所有条目 paragraphIndex 都是 0」
        // ⇒ 客户端点任何章节都跳到书的最开头。
        Path pdf = buildDirectDestOutlinePdf(dir);

        BookTextExtractor extractor = new BookTextExtractor();
        List<BookTextExtractor.TocEntry> toc = extractor.extractPdfToc(pdf.toFile());

        assertThat(toc).hasSize(2);
        // 页码必须解析出来（修复前恒为 0）
        assertThat(toc).extracting(t -> t.pageNumber).containsExactly(2, 4);
        // 偏移必须递增（修复前全是 0）
        assertThat(toc.get(0).charOffset)
                .as("Dest 直数组形式的书签偏移必须真的落在各自页上")
                .isLessThan(toc.get(1).charOffset)
                .isPositive();

        // 端到端：偏移必须落在该页标题上
        String text = extractor.extractText(pdf.toFile(), "pdf");
        assertThat(text.substring(toc.get(0).charOffset).stripLeading()).startsWith("BRAVO");
        assertThat(text.substring(toc.get(1).charOffset).stripLeading()).startsWith("DELTA");
    }

    // ---------- 夹具 ----------

    /**
     * 4 页 PDF，书签用 **Dest 直数组**（字典里只有 Dest 键，没有 A 键）。
     *
     * <p>这是真实世界大量 PDF 的写法（LaTeX/Word/Calibre 导出都可能这样），
     * 与 {@link #buildOutlinePdf} 的「动作式（/A GoTo）」形式相对。
     */
    private static Path buildDirectDestOutlinePdf(Path dir) throws IOException {
        Path pdf = dir.resolve("outline_dest.pdf");
        String[] markers = {
                "ALPHA " + "a".repeat(30) + ".",
                "BRAVO " + "b".repeat(30) + ".",
                "CHARLIE " + "c".repeat(30) + ".",
                "DELTA " + "d".repeat(30) + ".",
        };
        try (PDDocument document = new PDDocument()) {
            List<PDPage> pages = new ArrayList<>();
            for (String marker : markers) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                pages.add(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(marker);
                    cs.endText();
                }
            }
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            outline.addLast(itemWithDirectDest("（一）序言", pages.get(1)));
            outline.addLast(itemWithDirectDest("（二）正文", pages.get(3)));
            document.save(pdf.toFile());
        }
        return pdf;
    }

    /**
     * 造一个**只有 Dest、没有 A 动作**的书签项。
     *
     * <p>{@code PDOutlineItem.setDestination(...)} 写入的就是 Dest 键
     * —— 与 {@link #item(String, PDPage)}（写 A 键 + GoTo 动作）形成对照。
     */
    private static PDOutlineItem itemWithDirectDest(String title, PDPage target) {
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        PDPageXYZDestination dest = new PDPageXYZDestination();
        dest.setPage(target);
        item.setDestination(dest);
        // 明确不设 action：模拟「字典里没有 A 键」的真实 PDF
        return item;
    }

    /** 3 页：第 3 页首行是上一页续下来的句子，第 2 行才是标题 */
    private static Path buildHeadingPdf(Path dir) throws IOException {
        Path pdf = dir.resolve("heading.pdf");
        try (PDDocument document = new PDDocument()) {
            addPage(document, "PAGE ONE " + "x".repeat(30) + ".");
            addPage(document, "PAGE TWO " + "y".repeat(30) + ".");
            PDPage third = addPage(document, "CONTINUED FROM PREVIOUS PAGE",
                    "CHAPTER TWO BEGINS HERE.",
                    "Body text of chapter two follows here.");
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            outline.addLast(item("CHAPTER TWO BEGINS HERE.", third));
            document.save(pdf.toFile());
        }
        return pdf;
    }

    /** 只取「第 3 页」的文本无需真拆页：本夹具只需一个非空列表来算页起点下界 */
    private static PDPage addPage(PDDocument document, String... lines) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(72, 700);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    cs.newLineAtOffset(0, -20);
                }
                cs.showText(lines[i]);
            }
            cs.endText();
        }
        return page;
    }

    /** 4 页 PDF：每页首行是唯一标记，书签指向第 2、4 页 */
    private static Path buildOutlinePdf(Path dir) throws IOException {
        Path pdf = dir.resolve("outline.pdf");
        String[] markers = {
                "ALPHA " + "a".repeat(30) + ".",
                "BRAVO " + "b".repeat(30) + ".",
                "CHARLIE " + "c".repeat(30) + ".",
                "DELTA " + "d".repeat(30) + ".",
        };
        try (PDDocument document = new PDDocument()) {
            List<PDPage> pages = new ArrayList<>();
            for (String marker : markers) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                pages.add(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(marker);
                    cs.endText();
                }
            }
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            outline.addLast(item("（一）序言", pages.get(1)));
            outline.addLast(item("（二）正文", pages.get(3)));
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
}
