package com.audiobook.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Markdown（.md）电子书提取的回归测试。
 *
 * <p>Plan A：Markdown 在服务端「剥壳」成纯文本，与 TXT 路径同口径参与句子切分 / 行切分 /
 * 段落映射。本测试钉死：
 * <ul>
 *   <li>{@link BookTextExtractor#extractMarkdown} 把 {@code # / ** / * / ` / 链接 / 图片 / 引用 /
 *        列表 / 代码块 / 表格 / 水平线 / HTML 标签} 等语法去掉，但保留换行与正文；</li>
 *   <li>成对才去强调标记，避免误伤 {@code 2 * 3} 与 {@code some_var_name}；</li>
 *   <li>{@link BookTextExtractor#extractMarkdownToc} 把标题导出为目录，且 charOffset 与正文纯文本
 *        同源（落在该标题行首）；</li>
 *   <li>{@link BookTextExtractor#extractBookContent} 对 md 产出 rows（非 TXT）并映射目录。</li>
 * </ul>
 */
class BookTextExtractorMarkdownTest {

    private static final String SAMPLE = String.join("\n",
            "# 标题一",
            "",
            "这是**粗体**和*斜体*以及 `代码` 示例。",
            "",
            "## 小标题",
            "",
            "- 项目一",
            "- 项目二 带 *强调*",
            "",
            "> 引用文字",
            "",
            "普通 [链接](http://x.com) 与 ![图](a.png)。",
            "",
            "### 1.1 末节",
            "",
            "```",
            "code **keep** *keep*",
            "```",
            "",
            "| A | B |",
            "|---|---|",
            "| 1 | 2 |",
            "",
            "数学 2 * 3 = 6 和 some_var_name 保留。",
            "",
            "---",
            "",
            "结尾。"
    );

    private String extract(String md) throws IOException {
        return new BookTextExtractor().extractMarkdown(writeMd(md).toFile());
    }

    private Path writeMd(String md) throws IOException {
        Path f = Files.createTempFile("md", ".md");
        Files.writeString(f, md, StandardCharsets.UTF_8);
        return f;
    }

    // ---------- 剥壳：语法去除 ----------

    @Test
    @DisplayName("标题 / 粗体 / 斜体 / 行内代码 被去掉标记，保留正文")
    void stripsHeadingsAndEmphasis() throws IOException {
        String plain = extract(SAMPLE);

        assertThat(plain).contains("标题一").doesNotContain("# 标题一");
        assertThat(plain).contains("粗体").doesNotContain("**粗体**");
        assertThat(plain).contains("斜体").doesNotContain("*斜体*");
        assertThat(plain).contains("代码").doesNotContain("`代码`");
        // 行内代码的反引号彻底消失
        assertThat(plain).doesNotContain("`");
    }

    @Test
    @DisplayName("链接 → 文字、图片 → 替代文字，url 不出现在正文")
    void stripsLinksAndImages() throws IOException {
        String plain = extract(SAMPLE);

        assertThat(plain).contains("链接").doesNotContain("http://x.com");
        assertThat(plain).contains("图").doesNotContain("a.png");
        assertThat(plain).contains("普通 链接 与 图。");
    }

    @Test
    @DisplayName("引用块与列表标记被去掉，保留内容")
    void stripsBlockquoteAndListMarkers() throws IOException {
        String plain = extract(SAMPLE);

        assertThat(plain).contains("引用文字").doesNotContain("> 引用文字");
        assertThat(plain).contains("项目一").doesNotContain("- 项目一");
        assertThat(plain).contains("项目二 带 强调");
    }

    @Test
    @DisplayName("围栏代码块：标记行丢弃，块内正文原样保留（不被行内转换）")
    void preservesCodeFenceContent() throws IOException {
        String plain = extract(SAMPLE);

        assertThat(plain).contains("code **keep** *keep*");
        assertThat(plain).doesNotContain("```");
    }

    @Test
    @DisplayName("表格数据行保留单元格、分隔行丢弃")
    void stripsTableSeparatorKeepsCells() throws IOException {
        String plain = extract(SAMPLE);

        assertThat(plain).contains("A").contains("B").contains("1").contains("2");
        assertThat(plain).doesNotContain("|---");
    }

    @Test
    @DisplayName("水平线被丢弃")
    void stripsHorizontalRule() throws IOException {
        String plain = extract(SAMPLE);
        assertThat(plain).doesNotContain("---");
    }

    @Test
    @DisplayName("成对才去强调：2 * 3 与 some_var_name 不被破坏（单下划线保留）")
    void preservesMathAndSnakeCase() throws IOException {
        String plain = extract(SAMPLE);

        assertThat(plain).contains("数学 2 * 3 = 6 和 some_var_name 保留。");
        // 单下划线（some_var_name）不属于 __ 强调，必须原样保留
        assertThat(plain).contains("some_var_name");
    }

    @Test
    @DisplayName("HTML 标签被剥离")
    void stripsHtmlTags() throws IOException {
        String plain = extract("正文 <b>加粗</b> 与 <br> 换行。");
        assertThat(plain)
                .contains("正文").contains("加粗").contains("换行")
                .doesNotContain("<b>").doesNotContain("<br>");
    }

    @Test
    @DisplayName("CRLF 被统一为 LF，BOM 被剥离")
    void normalizesLineEndingsAndBom() throws IOException {
        String md = "\uFEFF# 标题\r\n\r\n正文。\r\n";
        Path f = Files.createTempFile("md", ".md");
        // 手写带 BOM + CRLF 的字节
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] rest = md.substring(1).getBytes(StandardCharsets.UTF_8);
        try (java.io.OutputStream os = Files.newOutputStream(f)) {
            os.write(bom);
            os.write(rest);
        }
        String plain = new BookTextExtractor().extractMarkdown(f.toFile());
        assertThat(plain).startsWith("标题").doesNotContain("\r");
    }

    // ---------- 目录 ----------

    @Test
    @DisplayName("标题导出为目录，charOffset 落在该标题行首（与正文同源）")
    void tocOffsetsLandOnHeading() throws IOException {
        List<BookTextExtractor.TocEntry> toc =
                new BookTextExtractor().extractMarkdownToc(writeMd(SAMPLE).toFile());
        String plain = extract(SAMPLE);

        assertThat(toc).extracting("title").containsExactly("标题一", "小标题", "1.1 末节");
        for (BookTextExtractor.TocEntry e : toc) {
            assertThat(plain.substring(e.charOffset, e.charOffset + e.title.length()))
                    .as("目录条目 %s 的 charOffset 必须指向标题行首", e.title)
                    .isEqualTo(e.title);
        }
    }

    @Test
    @DisplayName("空标题行（如单独的 #）不计入目录")
    void emptyHeadingNotInToc() throws IOException {
        List<BookTextExtractor.TocEntry> toc =
                new BookTextExtractor().extractMarkdownToc(writeMd("# \n\n## 真标题\n").toFile());
        assertThat(toc).extracting("title").containsExactly("真标题");
    }

    // ---------- extractBookContent 端到端 ----------

    @Test
    @DisplayName("extractBookContent(md)：产出 rows（非 TXT）并映射目录")
    void bookContentProducesRowsAndToc(@TempDir Path dir) throws IOException {
        Path md = dir.resolve("book.md");
        Files.writeString(md, SAMPLE, StandardCharsets.UTF_8);

        BookTextExtractor.BookContent content =
                new BookTextExtractor().extractBookContent(md.toFile(), "md");

        // MD 不是 TXT ⇒ 必须产出 rows 供阅读页显示
        assertThat(content.rows()).isNotNull().isNotEmpty();
        // paragraphs 来自同一份纯文本，非空
        assertThat(content.paragraphs()).isNotEmpty();
        // 目录被映射为段落下标（BookContent 路径下 TocEntry.charOffset 即段落下标），且都合法
        assertThat(content.toc()).hasSize(3);
        int last = content.paragraphs().size() - 1;
        assertThat(content.toc())
                .allSatisfy(t -> assertThat(t.charOffset).isBetween(0, last));
        // 标题行本身应成为某个段落（能被目录跳到）
        assertThat(content.paragraphs())
                .extracting(BookTextExtractor.Paragraph::text)
                .anyMatch(t -> t.contains("标题一"));
    }

    @Test
    @DisplayName("大小写不敏感：扩展名 MD / Md 同样走 Markdown 提取")
    void extensionCaseInsensitive() throws IOException {
        Path md = Files.createTempFile("book", ".MD");
        Files.writeString(md, "# 大写扩展名\n\n正文。", StandardCharsets.UTF_8);
        BookTextExtractor.BookContent content =
                new BookTextExtractor().extractBookContent(md.toFile(), "MD");
        assertThat(content.paragraphs()).extracting(BookTextExtractor.Paragraph::text)
                .anyMatch(t -> t.contains("大写扩展名"));
    }
}
