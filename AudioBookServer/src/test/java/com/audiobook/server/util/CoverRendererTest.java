package com.audiobook.server.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CoverRenderer} 的回归测试。
 *
 * <p>本类存在的理由：书城的文字封面在生产上**两次坏掉而无人察觉** ——
 * <ol>
 *   <li>服务器只有 DejaVu 字体、没有任何中文字体，书名与作者全被画成 {@code □} 方框；
 *       接口 200、PNG 合法、日志安静，只有人眼看封面才发现。</li>
 *   <li>换行按「每行 26 个字符」而非像素宽度，14 字书名在 28pt 下单行 392px 画进 300px 画布，
 *       首尾两个字被硬裁掉。</li>
 * </ol>
 * 所以这里的断言不是「没报错」，而是「中文真的画得出来」+「每一行真的没越界」。
 */
class CoverRendererTest {

    private static final String TITLE = "中国的官办经济";

    /** 14 字书名：旧实现按字符数换行时，单行 392px 会超出 300px 画布被裁 */
    private static final String LONG_TITLE = "潜规则-中国历史中的真实游戏";

    @Test
    @DisplayName("内置中文字体已随应用打包，且覆盖书名/作者里的汉字与中文标点")
    void bundledFontCoversChinese() {
        assertThat(CoverRenderer.isBundledFontLoaded())
                .as("内置字体资源缺失时中文必然变方框，必须在这里失败")
                .isTrue();

        assertThat(CoverRenderer.firstMissingGlyph(TITLE)).isEqualTo(-1);
        assertThat(CoverRenderer.firstMissingGlyph(LONG_TITLE)).isEqualTo(-1);
        assertThat(CoverRenderer.firstMissingGlyph("吴思")).isEqualTo(-1);
        assertThat(CoverRenderer.firstMissingGlyph("《书名》——“”……·（卷一）")).isEqualTo(-1);
    }

    @Test
    @DisplayName("子集之外的生僻字会报出缺字位置，而不是静默变成方框")
    void reportsMissingGlyphOutsideSubset() {
        // 「龘」不在 GB2312 内 → 子集字体必然覆盖不到；渲染前要能报出位置供日志告警
        assertThat(CoverRenderer.firstMissingGlyph("龘")).isEqualTo(0);
        assertThat(CoverRenderer.firstMissingGlyph("中国龘")).isEqualTo(2);
    }

    @Test
    @DisplayName("渲染出 300x400 PNG，且确实落了墨（存在白字像素）")
    void rendersPngWithInk() throws Exception {
        byte[] png = CoverRenderer.render(TITLE, "吴思", "#9CCC65");

        assertThat(png).isNotEmpty();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(300);
        assertThat(image.getHeight()).isEqualTo(400);
        assertThat(brightPixelsInColumns(image, 0, 300)).as("封面上一个字都没画出来").isGreaterThan(500);
    }

    @Test
    @DisplayName("短书名宁可降一档字号也要排成一行，不出现「中国的官 / 办经济」这种断词")
    void shortTitleStaysOnOneLine() {
        // 7 个字在 40pt 下宽 280px > 252px 可用宽度；折成两行会断在「官|办」中间，读起来别扭
        CoverRenderer.TitleLayout layout = CoverRenderer.layoutTitle(TITLE);

        assertThat(layout.lines()).containsExactly(TITLE);
        assertThat(layout.fontSize()).isGreaterThanOrEqualTo(28f);
        assertThat(CoverRenderer.lineWidth(TITLE, layout.fontSize()))
                .isLessThanOrEqualTo(CoverRenderer.contentWidth());
    }

    @Test
    @DisplayName("书名/作者为空、底色非法时不抛异常，改用兜底值")
    void toleratesBlankInputAndBadColor() throws Exception {
        assertThat(ImageIO.read(new ByteArrayInputStream(CoverRenderer.render(null, null, "#9CCC65"))))
                .isNotNull();
        assertThat(ImageIO.read(new ByteArrayInputStream(CoverRenderer.render("   ", "  ", null))))
                .isNotNull();
        // 底色来自 DB（管理员可改），脏值不该让整个封面接口 500
        assertThat(ImageIO.read(new ByteArrayInputStream(CoverRenderer.render("书", "人", "not-a-color"))))
                .isNotNull();
    }

    @Test
    @DisplayName("按像素宽度换行：每行都放得进可用宽度，且不超过 4 行")
    void everyLineFitsWithinContentWidth() {
        List<String> titles = List.of(
                TITLE,
                LONG_TITLE,
                "吴思自选集（第二卷）",
                "《中华人民共和国简史》",
                "The Quick Brown Fox Jumps Over The Lazy Dog",
                "为什么伟大不能被计划：对创造力的重新审视");

        for (String title : titles) {
            CoverRenderer.TitleLayout layout = CoverRenderer.layoutTitle(title);
            assertThat(layout.lines())
                    .as("书名「%s」的行数", title)
                    .hasSizeBetween(1, 4);
            assertThat(String.join("", layout.lines()))
                    .as("分行不能丢字（书名「%s」）", title)
                    .isEqualTo(title);
            for (String line : layout.lines()) {
                assertThat(CoverRenderer.lineWidth(line, layout.fontSize()))
                        .as("书名「%s」的分行「%s」（%spt）超出可用宽度 %d",
                                title, line, layout.fontSize(), CoverRenderer.contentWidth())
                        .isLessThanOrEqualTo(CoverRenderer.contentWidth());
            }
        }
    }

    @Test
    @DisplayName("长书名只换行、不缩到画布外：左右外侧 22px 内一个亮点都没有")
    void longTitleIsNotClippedByCanvas() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                CoverRenderer.render(LONG_TITLE, "吴思", "#5C6BC0")));

        assertThat(image).isNotNull();
        // 边框线是 alpha=90 的白，合成后并不亮（深底 ≈ RGB(149,159,214)），不会被计入
        assertThat(brightPixelsInColumns(image, 0, 22)).as("书名画到了画布左侧外").isZero();
        assertThat(brightPixelsInColumns(image, 278, 300)).as("书名画到了画布右侧外").isZero();
        assertThat(brightPixelsInColumns(image, 22, 278)).as("封面上一个字都没画出来").isGreaterThan(500);
    }

    @Test
    @DisplayName("断点优先落在可断标点之后，不在中文标点之前断行")
    void prefersNaturalBreakPoints() {
        // 「潜规则-」这一行是排版期望：把 '-' 留在行尾，而不是硬按行宽切在「中/国」之间
        assertThat(CoverRenderer.layoutTitle(LONG_TITLE).lines().get(0)).endsWith("-");
        // 顿号/逗号等不该出现在行首
        for (String title : List.of("潜规则-中国历史中的真实游戏", "为什么伟大不能被计划：对创造力的重新审视")) {
            for (String line : CoverRenderer.layoutTitle(title).lines()) {
                assertThat("，。、；：！？）》】」』”’…·%".indexOf(line.charAt(0)))
                        .as("书名「%s」的分行「%s」以中文标点开头", title, line)
                        .isEqualTo(-1);
            }
        }
    }

    @Test
    @DisplayName("超长书名在最小字号下仍放不下时，截到 4 行且末行加省略号")
    void truncatesOverlongTitleWithEllipsis() throws Exception {
        String huge = TITLE.repeat(12);

        CoverRenderer.TitleLayout layout = CoverRenderer.layoutTitle(huge);
        assertThat(layout.lines()).hasSize(4);
        assertThat(layout.lines().get(3)).endsWith("…");

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                CoverRenderer.render(huge, "佚名", "#5C6BC0")));
        assertThat(image).isNotNull();
        assertThat(brightPixelsInColumns(image, 22, 278)).isGreaterThan(500);
        assertThat(brightPixelsInColumns(image, 0, 22)).isZero();
        assertThat(brightPixelsInColumns(image, 278, 300)).isZero();
    }

    @Test
    @DisplayName("超长作者署名缩字号/截断，同样不越界")
    void longAuthorStaysInsideCanvas() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                CoverRenderer.render(TITLE, "一个非常非常非常长的作者署名用来验证不会溢出画布", "#5C6BC0")));

        assertThat(image).isNotNull();
        assertThat(brightPixelsInColumns(image, 0, 22)).isZero();
        assertThat(brightPixelsInColumns(image, 278, 300)).isZero();
    }

    /** 统计 [fromX, toX) 列范围内「足够亮」的像素数（阈值 200×3，可把半透明边框线排除在外） */
    private static long brightPixelsInColumns(BufferedImage image, int fromX, int toX) {
        long count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = fromX; x < toX; x++) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) > 200 && (rgb & 0xFF) > 200) {
                    count++;
                }
            }
        }
        return count;
    }
}
