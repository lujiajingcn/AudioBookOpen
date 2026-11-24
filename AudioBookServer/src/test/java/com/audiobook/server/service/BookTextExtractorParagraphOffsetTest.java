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
 * 段落 {@code charOffset} 必须与原文同坐标的回归测试。
 *
 * <p>背景：段落坐标曾是 {@code charOffset += para.length()} 的**压缩坐标**，而目录坐标
 * （{@code extractEpubToc} / {@code extractPdfToc}）用的是**原文真实长度累加**。
 * {@code TextSegmenter} 在切分与 trim 时会丢掉一批空白（句末标点后的 {@code \s*}、
 * 每句首尾的 trim），于是「段落文本」并不是「原文」的连续子串，累加值必然小于真实下标，
 * 偏差随段落数持续累积 —— 目录映射出的段落下标系统性偏大，表现为「点目录跳过头」
 * （长期红用例 {@code BookTextExtractorEpubTocTest#tocCharOffsetPointsIntoChapter}，
 * 即 {@code detail#B5}）。
 *
 * <p>本测试锁死修复后的两条不变量，并专门覆盖两个**最容易被写错**的空白场景：
 * <ol>
 *   <li><b>段落内部保留的空白</b>：{@code TextSegmenter} 只 trim 首尾，句子内部的空白
 *       会留在段落文本里（英文正文 {@code Hello world.} 是典型）。若实现只在原文侧
 *       跳过空白，就会拿原文的下一个非空白字符去比段落里的空白，直接误判失配、坐标退化。</li>
 *   <li><b>段落以空白开头</b>：切分产物可能带前导空白（{@code TextSegmenter} 的硬切分支
 *       刻意不 trim），{@code locateIgnoringBlank} 必须容忍它。</li>
 * </ol>
 * 两者都会让「旧实现」拿到错误坐标，因此本测试是修复的验收判据而非形式化补测。
 */
class BookTextExtractorParagraphOffsetTest {

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

    private Path writeTxt(Path dir, String content) throws IOException {
        Path f = dir.resolve("book.txt");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    /**
     * 通用不变量：跳过空白后，从 charOffset 起的原文必须以该段文本开头，且坐标严格递增。
     */
    private void assertOffsetsConsistent(String original, List<BookTextExtractor.Paragraph> paras) {
        assertThat(paras).isNotEmpty();
        int prev = -1;
        for (BookTextExtractor.Paragraph p : paras) {
            assertThat(p.charOffset())
                    .as("第 %d 段（%s）的 charOffset 必须严格递增", p.paragraphIndex(), p.text())
                    .isGreaterThan(prev);
            prev = p.charOffset();

            assertThat(p.charOffset())
                    .as("第 %d 段的 charOffset 必须落在原文范围内", p.paragraphIndex())
                    .isBetween(0, original.length() - 1);
            assertThat(original.charAt(p.charOffset()))
                    .as("第 %d 段的 charOffset 应指向该段第一个非空白字符，而不是指向空白",
                            p.paragraphIndex())
                    .isGreaterThan(' ');

            assertThat(stripBlank(original.substring(p.charOffset())))
                    .as("第 %d 段应从 charOffset 起逐字符对应原文（忽略被丢弃的空白）",
                            p.paragraphIndex())
                    .startsWith(stripBlank(p.text()));
        }
    }

    @Test
    @DisplayName("句内保留的空白不得让后续句子坐标退化（英文句子的空格）")
    void internalBlankDoesNotBreakOffsets(@TempDir Path dir) throws IOException {
        // "Hello world." 句内空格会被保留进段落文本；若实现只在原文侧跳空白就会失配，
        // 导致后面所有段落的 charOffset 退化成 searchFrom（此处即 0）。
        String original = "Hello world. This is fine.\n\n甲乙丙丁。 戊己庚辛 壬癸。\n\nA tab\there.";
        Path txt = writeTxt(dir, original);

        List<BookTextExtractor.Paragraph> paras =
                new BookTextExtractor().extractBookContent(txt.toFile(), "txt").paragraphs();

        // 片段 = 完整句子 ⇒ 原文这一小段共 5 句
        assertThat(paras).hasSize(5);
        // 句内空格被保留（切分只 trim 首尾），是触发失配的关键；它必须仍能从 0 正确定位
        assertThat(paras.get(0).text()).isEqualTo("Hello world.");
        assertThat(paras.get(0).charOffset()).isEqualTo(0);

        // 修复前这里恒为 0（失配后退回 searchFrom），会表现为「点目录永远跳到第一段」
        assertThat(paras.get(1).charOffset())
                .as("带内部空白的句子不得让后续句子坐标退化")
                .isEqualTo(original.indexOf("This is fine."));
        assertThat(paras.get(1).text()).isEqualTo("This is fine.");

        // 这一句内部既有空格、后面又紧跟含制表符的句子，是空白处理最容易失配的组合
        assertThat(paras.get(3).text()).isEqualTo("戊己庚辛 壬癸。");
        assertThat(paras.get(3).charOffset()).isEqualTo(original.indexOf("戊己庚辛"));
        assertThat(paras.get(4).charOffset())
                .as("制表符前的定位不得退化")
                .isEqualTo(original.indexOf("A tab"));

        assertOffsetsConsistent(original, paras);
    }

    @Test
    @DisplayName("段落以空白开头时坐标仍须正确（locateStartOffsets 必须容忍前导空白）")
    void paragraphStartingWithBlankKeepsOffsets() {
        // 注意：本用例**不再**经 extractBookContent。片段 = 完整句子后，sentences() 会把句首
        // 空白 trim 掉，「以空白开头的段落」在生产路径上已不可能出现；但 locateStartOffsets
        // 仍是通用工具（TTS 任务端点也在用），它对前导空白的容忍必须继续被锁住 ——
        // 否则将来谁再往它前面接一个不 trim 的切分器（如 TextSegmenter.segment 的硬切分支），
        // 就会静默退化：locateIgnoringBlank 会拿原文里紧随的下一个非空白字符去比段落里的空白。
        String original = "占".repeat(29) + "，" + " " + "乙".repeat(30) + "。";
        List<String> parts = List.of(
                "占".repeat(29) + "，",
                " " + "乙".repeat(29),   // ← 以空白开头：本用例的核心
                "乙");
        int[] starts = BookTextExtractor.locateStartOffsets(original, parts);

        assertThat(starts).hasSize(3);
        assertThat(starts[0]).isEqualTo(0);
        assertThat(starts[1])
                .as("以空白开头的段落，下标必须指向其第一个非空白字符")
                .isEqualTo(30 + 1);
        assertThat(starts[2])
                .as("空白若被当成匹配内容，后续下标会整体左移")
                .isEqualTo(30 + 30);
    }

    @Test
    @DisplayName("多段落累积：偏移不得小于压缩累加值（旧算法的系统性偏差）")
    void offsetsAreRealIndexesNotCompressedAccumulation(@TempDir Path dir) throws IOException {
        // 每句后跟两个换行与空格，旧算法按 para.length() 累加会持续少算这些空白
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append("第").append(i).append("句正文内容。\n\n  ");
        }
        String original = sb.toString();
        Path txt = writeTxt(dir, original);

        List<BookTextExtractor.Paragraph> paras =
                new BookTextExtractor().extractBookContent(txt.toFile(), "txt").paragraphs();

        assertThat(paras).hasSizeGreaterThan(1);
        assertOffsetsConsistent(original, paras);

        // 末段的真实下标必须显著大于「各段长度之和」这一压缩坐标
        int compressed = 0;
        for (int i = 0; i < paras.size() - 1; i++) {
            compressed += paras.get(i).text().length();
        }
        assertThat(paras.get(paras.size() - 1).charOffset())
                .as("末段坐标应反映原文位置，而不是被丢弃空白后的压缩累加值")
                .isGreaterThan(compressed);
    }
}
