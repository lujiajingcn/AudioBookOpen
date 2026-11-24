package com.audiobook.server.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BookCoverExtractor} 的回归测试。
 *
 * <p>本类存在的理由：封面提取是「猜」出来的 —— 书里哪张图才是封面，没有标准答案。
 * 猜错的代价不是报错而是**静默给出难看的结果**（把 24x24 的项目符号、纯白扫描页当封面），
 * 所以这里的用例锁的是「选择口径」：只看前 3 页、只要够大的图、跳过近似纯色、多候选取最大、
 * EPUB 优先用 OPF 声明的封面。同时锁「任何畸形输入都不抛异常」——它跑在上传主链路上，
 * 抛出去就等于上传失败，而封面本来只是附加信息。
 */
class BookCoverExtractorTest {

    private final BookCoverExtractor extractor = new BookCoverExtractor(new BookTextExtractor());

    @TempDir
    Path tmp;

    // ---------- PDF ----------

    @Test
    @DisplayName("PDF 第 1 页有图 → 用该图作封面，输出 JPEG 且带原图尺寸特征")
    void pdfUsesImageFromFirstPage() throws Exception {
        File pdf = pdf(Map.of(0, contentImage(600, 800)), 4);

        BookCoverExtractor.CoverImage cover = extractor.extract(pdf, "pdf").orElseThrow();

        BufferedImage out = decode(cover);
        // 600x800 未超上限，不做缩放：证明拿到的是书里那张图，而不是新画的文字封面
        assertThat(out.getWidth()).isEqualTo(600);
        assertThat(out.getHeight()).isEqualTo(800);
    }

    @Test
    @DisplayName("PDF 前 3 页都没有图（纯文字）→ 返回空，交给书名+作者封面")
    void pdfWithoutImageReturnsEmpty() throws Exception {
        assertThat(extractor.extract(pdf(Map.of(), 6), "pdf")).isEmpty();
    }

    @Test
    @DisplayName("PDF 只有第 4 页才有图 → 不取（只分析前 3 页）")
    void pdfIgnoresImageBeyondThirdPage() throws Exception {
        assertThat(extractor.extract(pdf(Map.of(3, contentImage(600, 800)), 8), "pdf")).isEmpty();
    }

    @Test
    @DisplayName("PDF 里的装饰小图（60x60）被忽略 → 返回空")
    void pdfIgnoresTinyDecoration() throws Exception {
        assertThat(extractor.extract(pdf(Map.of(0, contentImage(60, 60)), 3), "pdf")).isEmpty();
    }

    @Test
    @DisplayName("PDF 里第 1 页是纯白图、第 2 页才是真图 → 跳过纯白，取真图")
    void pdfSkipsBlankImageAndTakesLaterOne() throws Exception {
        File pdf = pdf(new LinkedHashMap<>(Map.of(0, blankImage(600, 800), 1, contentImage(400, 600))), 3);

        BufferedImage out = decode(extractor.extract(pdf, "pdf").orElseThrow());

        assertThat(out.getWidth()).isEqualTo(400);
        assertThat(out.getHeight()).isEqualTo(600);
    }

    @Test
    @DisplayName("超大图（1200x1600）被缩到最长边 800，避免几 MB 原图落盘")
    void pdfScalesDownHugeImage() throws Exception {
        BufferedImage out = decode(extractor.extract(pdf(Map.of(0, contentImage(1200, 1600)), 2), "pdf")
                .orElseThrow());

        assertThat(Math.max(out.getWidth(), out.getHeight())).isEqualTo(800);
        // 宽高比必须保持
        assertThat(out.getWidth() * 1600).isEqualTo(out.getHeight() * 1200);
    }

    // ---------- EPUB ----------

    @Test
    @DisplayName("EPUB：OPF 声明的封面优先（即使章节里还有更大的插画）")
    void epubPrefersDeclaredCover() throws Exception {
        byte[] declared = jpeg(contentImage(300, 450));
        byte[] bigger = jpeg(contentImage(700, 900));
        Map<String, byte[]> entries = epubEntries(
                "<meta name=\"cover\" content=\"cover-img\"/>",
                xhtmlWith("<img src=\"images/photo.jpg\"/>"));
        entries.put("OEBPS/images/cover.jpg", declared);
        entries.put("OEBPS/images/photo.jpg", bigger);

        BufferedImage out = decode(extractor.extract(epub(entries), "epub").orElseThrow());

        assertThat(out.getWidth()).isEqualTo(300);
        assertThat(out.getHeight()).isEqualTo(450);
    }

    @Test
    @DisplayName("EPUB：没有 cover 声明时取前 3 个章节里的 <img>")
    void epubFallsBackToSpineImage() throws Exception {
        Map<String, byte[]> entries = epubEntries("",
                xhtmlWith("<p>封面</p><img src=\"images/photo.jpg\" alt=\"\"/>"));
        entries.put("OEBPS/images/photo.jpg", jpeg(contentImage(400, 600)));

        BufferedImage out = decode(extractor.extract(epub(entries), "epub").orElseThrow());

        assertThat(out.getWidth()).isEqualTo(400);
        assertThat(out.getHeight()).isEqualTo(600);
    }

    @Test
    @DisplayName("EPUB：章节里的图太小 → 返回空")
    void epubIgnoresTinyImage() throws Exception {
        Map<String, byte[]> entries = epubEntries("",
                xhtmlWith("<img src=\"images/thumb.jpg\"/>"));
        entries.put("OEBPS/images/thumb.jpg", jpeg(contentImage(80, 100)));

        assertThat(extractor.extract(epub(entries), "epub")).isEmpty();
    }

    // ---------- 边界与容错 ----------

    @Test
    @DisplayName("TXT 不做图片提取（本体不可能有图）")
    void txtReturnsEmpty() throws Exception {
        File txt = tmp.resolve("a.txt").toFile();
        Files.writeString(txt.toPath(), "纯文本内容", StandardCharsets.UTF_8);

        assertThat(extractor.extract(txt, "txt")).isEmpty();
    }

    @Test
    @DisplayName("畸形/损坏文件不抛异常，只返回空（封面提取不能拖垮上传）")
    void corruptFilesDoNotThrow() throws Exception {
        File badPdf = tmp.resolve("bad.pdf").toFile();
        Files.writeString(badPdf.toPath(), "this is not a pdf at all", StandardCharsets.UTF_8);
        File badEpub = tmp.resolve("bad.epub").toFile();
        Files.writeString(badEpub.toPath(), "this is not a zip at all", StandardCharsets.UTF_8);

        assertThat(extractor.extract(badPdf, "pdf")).isEmpty();
        assertThat(extractor.extract(badEpub, "epub")).isEmpty();
        assertThat(extractor.extract(tmp.resolve("missing.pdf").toFile(), "pdf")).isEmpty();
        assertThat(extractor.extract(null, "pdf")).isEmpty();
    }

    // ---------- 造测试数据 ----------

    /** 造一张「有内容」的图：渐变底 + 两个色块，保证不会被判成近似纯色 */
    private static BufferedImage contentImage(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new java.awt.Color(0x2E, 0x4A, 0x7A),
                0, h, new java.awt.Color(0xE8, 0xC1, 0x6A)));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.fillRect(w / 4, h / 4, Math.max(1, w / 2), Math.max(1, h / 6));
        g.setColor(Color.BLACK);
        g.fillRect(w / 3, h / 2, Math.max(1, w / 3), Math.max(1, h / 8));
        g.dispose();
        return image;
    }

    private static BufferedImage blankImage(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }

    private static BufferedImage decode(BookCoverExtractor.CoverImage cover) throws IOException {
        assertThat(cover.extension()).isEqualTo("jpg");
        // JPEG 头：证明输出是重新编码的图片，而不是把原图字节直接透传（PNG 头是 89 50）
        assertThat(cover.data()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(cover.data()[1] & 0xFF).isEqualTo(0xD8);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(cover.data()));
        assertThat(image).as("产出的字节必须是可解码的图片").isNotNull();
        return image;
    }

    /** 造 PDF：imagesByPage 里的页码放图，其余页只有一行文字 */
    private File pdf(Map<Integer, BufferedImage> imagesByPage, int totalPages) throws IOException {
        File file = tmp.resolve("book-" + System.nanoTime() + ".pdf").toFile();
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < totalPages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    BufferedImage source = imagesByPage.get(i);
                    if (source != null) {
                        PDImageXObject image = LosslessFactory.createFromImage(document, source);
                        cs.drawImage(image, 40, 120, 280, 360);
                    } else {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                        cs.newLineAtOffset(60, 700);
                        cs.showText("plain text page " + (i + 1));
                        cs.endText();
                    }
                }
            }
            document.save(file);
        }
        return file;
    }

    private File epub(Map<String, byte[]> entries) throws IOException {
        File file = tmp.resolve("book-" + System.nanoTime() + ".epub").toFile();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return file;
    }

    private static Map<String, byte[]> epubEntries(String extraMetadata, String firstDocBody) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("mimetype", "application/epub+zip".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/container.xml", ("<?xml version=\"1.0\"?>\n"
                + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\""
                + " media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("OEBPS/content.opf", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"id\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>测试书</dc:title>"
                + extraMetadata + "</metadata>"
                + "<manifest>"
                + "<item id=\"ch1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"cover-img\" href=\"images/cover.jpg\" media-type=\"image/jpeg\"/>"
                + "</manifest>"
                + "<spine><itemref idref=\"ch1\"/></spine></package>")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("OEBPS/ch1.xhtml", xhtmlWith(firstDocBody).getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static String xhtmlWith(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" + body + "</body></html>";
    }
}
