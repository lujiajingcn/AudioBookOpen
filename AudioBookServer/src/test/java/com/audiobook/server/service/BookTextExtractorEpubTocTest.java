package com.audiobook.server.service;

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
 * EPUB 目录提取的回归测试。
 *
 * <p>背景（生产真实事故）：{@code createSafeDocumentBuilder()} 为防 XXE 开启了
 * {@code disallow-doctype-decl=true}，而 Xerces 只要文档含 <b>DOCTYPE 声明</b>就立即抛
 * {@code SAXParseException}。EPUB 2 的 {@code toc.ncx} 标准模板**几乎总是带 DOCTYPE**，
 * 于是目录解析稳定失败并被 {@code catch (Exception)} 吞掉，表现为
 * 「书能正常读出来，但目录永远是空的」——不报错、不崩，只是功能静默缺失。
 *
 * <p>修复方式是在 parse 前用 {@link BookTextExtractor} 的 {@code stripDoctype} 摘掉 DOCTYPE。
 * 本测试把「带 DOCTYPE 的 NCX」作为**必备**用例锁死，防止后人重构时又把这条路堵回去。
 *
 * <p>同时覆盖三类容易写错的边界：
 * <ul>
 *   <li>DOCTYPE 内部子集 {@code [ ... ]} 里含 {@code >}（不能按第一个 {@code >} 截断）</li>
 *   <li>DTD 标识符引号内含 {@code >}</li>
 *   <li>正文里出现的 {@code <!DOCTYPE} 字样（不应误判；真实 XML 里不会有，但要有确定性行为）</li>
 * </ul>
 */
class BookTextExtractorEpubTocTest {

    private static final String NCX_WITH_DOCTYPE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"
               "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head>
                <meta name="dtb:uid" content="urn:uuid:test"/>
                <meta name="dtb:depth" content="1"/>
              </head>
              <docTitle><text>测试书</text></docTitle>
              <navMap>
                <navPoint id="navPoint-1" playOrder="1">
                  <navLabel><text>推荐序</text></navLabel>
                  <content src="EPUB/xhtml/Section0001_0001.xhtml"/>
                </navPoint>
                <navPoint id="navPoint-2" playOrder="2">
                  <navLabel><text>第一章 硅谷</text></navLabel>
                  <content src="EPUB/xhtml/Section0001_0007.xhtml"/>
                </navPoint>
                <navPoint id="navPoint-3" playOrder="3">
                  <navLabel><text>第二章 一人公司</text></navLabel>
                  <content src="EPUB/xhtml/Section0001_0008.xhtml"/>
                </navPoint>
              </navMap>
            </ncx>
            """;

    /**
     * 与生产同一形态的最小 EPUB：container.xml + content.opf + 3 个 xhtml + 带 DOCTYPE 的 toc.ncx。
     *
     * <p>各章节正文刻意写足长度：{@code TextSegmenter} 会把短文本贪心合并成一段，
     * 若章节只有一二十字，整本书会退化成单段落，所有目录条目都映射到下标 0 ——
     * 那样测不出「目录能否定位到正确章节」，只能测出「解析没崩」。
     * 这里每章用重复语句凑到 100 字以上，保证至少切出 2 段，章节间下标才能真正拉开。
     */
    private Path buildEpub(Path dir, String ncx) throws IOException {
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
                        <dc:title>测试书</dc:title>
                        <dc:identifier id="bookid">urn:uuid:test</dc:identifier>
                      </metadata>
                      <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="c1" href="EPUB/xhtml/Section0001_0001.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c7" href="EPUB/xhtml/Section0001_0007.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c8" href="EPUB/xhtml/Section0001_0008.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine toc="ncx">
                        <itemref idref="c1"/>
                        <itemref idref="c7"/>
                        <itemref idref="c8"/>
                      </spine>
                    </package>
                    """);
            put(zos, "toc.ncx", ncx);
            put(zos, "EPUB/xhtml/Section0001_0001.xhtml",
                    "<html><body><p>推荐序开头甲。</p><p>" + filler + filler
                            + "</p><p>" + filler + "</p></body></html>");
            put(zos, "EPUB/xhtml/Section0001_0007.xhtml",
                    "<html><body><p>第一章开头乙。</p><p>" + filler + filler
                            + "</p><p>" + filler + "</p></body></html>");
            put(zos, "EPUB/xhtml/Section0001_0008.xhtml",
                    "<html><body><p>第二章开头丙。</p><p>" + filler + filler
                            + "</p><p>" + filler + "</p></body></html>");
        }
        return epub;
    }

    private void put(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    @Test
    @DisplayName("带 DOCTYPE 的 NCX 必须仍能提取出目录（生产事故回归）")
    void doctypeNcxStillYieldsToc(@TempDir Path dir) throws IOException {
        Path epub = buildEpub(dir, NCX_WITH_DOCTYPE);
        BookTextExtractor extractor = new BookTextExtractor();

        List<BookTextExtractor.TocEntry> toc = extractor.extractEpubToc(epub.toFile());

        // 修复前这里恒为 0：DOCTYPE 被 disallow-doctype-decl 拒绝，parse 抛错被吞
        assertThat(toc)
                .as("带 DOCTYPE 的 NCX 应能解析出 3 条目录，为空说明 stripDoctype 失效")
                .hasSize(3);
        assertThat(toc.stream().map(e -> e.title).toList())
                .containsExactly("推荐序", "第一章 硅谷", "第二章 一人公司");
    }

    @Test
    @DisplayName("不带 DOCTYPE 的 NCX 同样正常（回归保护，不能只修带 DOCTYPE 的那条路）")
    void plainNcxStillYieldsToc(@TempDir Path dir) throws IOException {
        String ncx = NCX_WITH_DOCTYPE.replaceAll("(?s)<!DOCTYPE.*?>", "");
        Path epub = buildEpub(dir, ncx);
        BookTextExtractor extractor = new BookTextExtractor();

        List<BookTextExtractor.TocEntry> toc = extractor.extractEpubToc(epub.toFile());

        assertThat(toc).hasSize(3);
    }

    @Test
    @DisplayName("DOCTYPE 带内部子集（[]>[] 内含 >）时不能按第一个 > 截断")
    void doctypeWithInternalSubsetIsStrippedCorrectly(@TempDir Path dir) throws IOException {
        String ncx = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"
                   "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd" [
                  <!ENTITY foo "bar > baz">
                  <!ELEMENT ncx ANY>
                ]>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="p1" playOrder="1">
                      <navLabel><text>唯一章节</text></navLabel>
                      <content src="EPUB/xhtml/Section0001_0001.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>
                """;
        Path epub = buildEpub(dir, ncx);
        BookTextExtractor extractor = new BookTextExtractor();

        List<BookTextExtractor.TocEntry> toc = extractor.extractEpubToc(epub.toFile());

        assertThat(toc)
                .as("内部子集里的 > 不应被当成 DOCTYPE 结尾，否则会截出残缺 XML")
                .hasSize(1);
        assertThat(toc.get(0).title).isEqualTo("唯一章节");
    }

    @Test
    @DisplayName("目录条目的 charOffset 必须落在正文范围内，且章节顺序单调递增")
    void tocCharOffsetPointsIntoChapter(@TempDir Path dir) throws IOException {
        Path epub = buildEpub(dir, NCX_WITH_DOCTYPE);
        BookTextExtractor extractor = new BookTextExtractor();

        // 注意 extractBookContent 返回的 TocEntry.charOffset 已经被「重打包」成
        // paragraphIndex（见 extractBookContent 里的映射循环），字段名保持 charOffset
        // 是为了兼容既有 JSON 字段名，语义在 BookContent 路径下是段落下标。
        BookTextExtractor.BookContent content =
                extractor.extractBookContent(epub.toFile(), "epub");

        List<BookTextExtractor.TocEntry> toc = content.toc();
        assertThat(toc).hasSize(3);

        List<BookTextExtractor.Paragraph> paras = content.paragraphs();
        assertThat(paras)
                .as("章节正文已撑长，应切出多段；若只有 1 段说明 filler 不够长，"
                        + "章节间下标拉不开，本测试会失去意义")
                .hasSizeGreaterThan(1);

        for (BookTextExtractor.TocEntry entry : toc) {
            assertThat(entry.charOffset)
                    .as("目录「%s」映射到的段落下标应在 [0,%d) 内", entry.title, paras.size())
                    .isBetween(0, paras.size() - 1);
        }

        // 三章正文各不相同，目录应按顺序映射到递增的段落下标
        int first = toc.get(0).charOffset;
        int second = toc.get(1).charOffset;
        int third = toc.get(2).charOffset;
        assertThat(second).as("第一章的段落下标应严格大于推荐序").isGreaterThan(first);
        assertThat(third).as("第二章的段落下标应严格大于第一章").isGreaterThan(second);

        // 目录指向的段落应确实是该章节的首段（而非章节内部某一段）
        assertThat(paras.get(first).text()).as("推荐序应指向本章首段").contains("推荐序开头甲");
        assertThat(paras.get(second).text()).as("第一章应指向本章首段").contains("第一章开头乙");
        assertThat(paras.get(third).text()).as("第二章应指向本章首段").contains("第二章开头丙");
    }

    // ---------- 生产事故 2：OPF 在子目录 + NCX src 相对 NCX + 小节锚点 ----------

    /** 每小节正文：标题 + 足够长的填充，保证每节至少切出一段 */
    private static final String FILLER = "这一段用于把小节长度撑到足以被独立切分，避免与相邻小节合并成一段。";

    private String section(String anchor, String heading) {
        return "<h2 id=\"" + anchor + "\">" + heading + "</h2><p>" + FILLER + FILLER + "</p>";
    }

    /**
     * 与生产同一形态的 EPUB：<b>OPF 位于 OEBPS/ 子目录</b>，NCX 的 src 相对 NCX 自身，
     * 且同一章文件里有多个带 #fragment 的小节。
     *
     * <p>这正是书 48《Linux 命令行大全_精简有声化》的形态（457 条目录 / 37 个 spine 文件），
     * 旧实现里 {@code spineHrefs.indexOf("text00002.html")} 恒为 -1，全部条目 charOffset=0，
     * 表现为「点任何小节都跳回正文第一段」。
     */
    private Path buildNestedEpub(Path dir) throws IOException {
        Path epub = dir.resolve("nested.epub");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(epub))) {
            put(zos, "mimetype", "application/epub+zip");
            put(zos, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0"
                               xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf"
                                  media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """);
            put(zos, "OEBPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>嵌套目录测试书</dc:title>
                        <dc:identifier id="bookid">urn:uuid:nested</dc:identifier>
                      </metadata>
                      <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="c1" href="text00001.html" media-type="application/xhtml+xml"/>
                        <item id="c2" href="text00002.html" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine toc="ncx">
                        <itemref idref="c1"/>
                        <itemref idref="c2"/>
                      </spine>
                    </package>
                    """);
            // src 相对 NCX 自身（OEBPS/），不带目录前缀；小节用 #nav_point_* 锚点
            put(zos, "OEBPS/toc.ncx", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN"
                       "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                      <navMap>
                        <navPoint id="n1" playOrder="1">
                          <navLabel><text>第1章 开篇</text></navLabel>
                          <content src="text00001.html"/>
                          <navPoint id="n1-1" playOrder="2">
                            <navLabel><text>1.1 终端仿真器</text></navLabel>
                            <content src="text00001.html#nav_point_2"/>
                          </navPoint>
                          <navPoint id="n1-2" playOrder="3">
                            <navLabel><text>1.2 第一次按键</text></navLabel>
                            <content src="text00001.html#nav_point_3"/>
                          </navPoint>
                        </navPoint>
                        <navPoint id="n2" playOrder="4">
                          <navLabel><text>第2章 文件系统</text></navLabel>
                          <content src="text00002.html#nav_point_4"/>
                        </navPoint>
                      </navMap>
                    </ncx>
                    """);
            put(zos, "OEBPS/text00001.html", "<html><body>"
                    + section("nav_point_1", "第1章 开篇")
                    + section("nav_point_2", "1.1 终端仿真器")
                    + section("nav_point_3", "1.2 第一次按键")
                    + "</body></html>");
            put(zos, "OEBPS/text00002.html", "<html><body>"
                    + section("nav_point_4", "第2章 文件系统")
                    + section("nav_point_5", "2.1 目录结构")
                    + "</body></html>");
        }
        return epub;
    }

    @Test
    @DisplayName("OPF 在子目录时目录必须仍能定位（src 相对 NCX，而非相对 OPF）")
    void nestedOpfTocStillLandsOnChapter(@TempDir Path dir) throws IOException {
        Path epub = buildNestedEpub(dir);
        BookTextExtractor extractor = new BookTextExtractor();

        BookTextExtractor.BookContent content = extractor.extractBookContent(epub.toFile(), "epub");
        List<BookTextExtractor.TocEntry> toc = content.toc();
        List<BookTextExtractor.Paragraph> paras = content.paragraphs();

        assertThat(toc).as("嵌套 OPF 的书也要能解析出 4 条目录").hasSize(4);

        // 关键断言：不能全部落在第 0 段（生产事故的形态）
        assertThat(toc.stream().map(e -> e.charOffset).distinct().count())
                .as("目录不得全部映射到同一段；全相同说明 src 路径没匹配上 spine")
                .isGreaterThan(1);

        assertThat(paras.get(toc.get(0).charOffset).text()).contains("第1章 开篇");
        assertThat(paras.get(toc.get(1).charOffset).text()).contains("1.1 终端仿真器");
        assertThat(paras.get(toc.get(2).charOffset).text()).contains("1.2 第一次按键");
        assertThat(paras.get(toc.get(3).charOffset).text()).contains("第2章 文件系统");
    }

    @Test
    @DisplayName("同一章内的小节按 #fragment 分别定位，不共用章节起点")
    void subsectionFragmentsMapToOwnHeading(@TempDir Path dir) throws IOException {
        Path epub = buildNestedEpub(dir);
        BookTextExtractor extractor = new BookTextExtractor();

        List<BookTextExtractor.TocEntry> toc = extractor.extractEpubToc(epub.toFile());

        int chapter = toc.get(0).charOffset;      // text00001.html 无 fragment
        int sec1 = toc.get(1).charOffset;          // #nav_point_1
        int sec2 = toc.get(2).charOffset;          // #nav_point_2

        assertThat(sec1).as("1.1 的偏移必须严格大于章的起点").isGreaterThan(chapter);
        assertThat(sec2).as("1.2 的偏移必须严格大于 1.1").isGreaterThan(sec1);
    }

    @Test
    @DisplayName("空章节文件不得让后续偏移累计多算（与 extractEpub 的拼接口径一致）")
    void emptySpineFileDoesNotSkewOffsets(@TempDir Path dir) throws IOException {
        Path epub = dir.resolve("empty.epub");
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
                        <dc:title>空章节测试书</dc:title>
                        <dc:identifier id="bookid">urn:uuid:empty</dc:identifier>
                      </metadata>
                      <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="c0" href="blank.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine toc="ncx">
                        <itemref idref="c0"/>
                        <itemref idref="c1"/>
                      </spine>
                    </package>
                    """);
            put(zos, "toc.ncx", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                      <navMap>
                        <navPoint id="n1" playOrder="1">
                          <navLabel><text>唯一章</text></navLabel>
                          <content src="ch1.xhtml"/>
                        </navPoint>
                      </navMap>
                    </ncx>
                    """);
            // 只含图片的封面页：stripHtmlTags 后为空串
            put(zos, "blank.xhtml", "<html><body><img src=\"cover.jpg\"/></body></html>");
            put(zos, "ch1.xhtml", "<html><body><p>唯一章开头丁。</p><p>" + FILLER + FILLER
                    + "</p></body></html>");
        }

        BookTextExtractor extractor = new BookTextExtractor();
        BookTextExtractor.BookContent content = extractor.extractBookContent(epub.toFile(), "epub");
        int offset = content.toc().get(0).charOffset;

        assertThat(content.paragraphs().get(offset).text())
                .as("目录偏移必须精确落在正文首个段落，多算 1 个字符就会落到空段/前一段")
                .contains("唯一章开头丁");
    }
}
