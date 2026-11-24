package com.audiobook.server.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 书城「文字封面」渲染器：纯色底 + 白边线 + 居中书名（自动换行）+ 底部作者，输出 300x400 PNG。
 *
 * <p>书城书籍没有可用封面图时（书里前几页没有图片、管理员也没手工上传），由它兜底出图。
 *
 * <h3>为什么要把字体打进 JAR（而不是用系统字体）</h3>
 * 原先这里用逻辑字体 {@code Font.SERIF}，本机开发时看着正常，但生产服务器上
 * <b>只有 DejaVu 字体、没有任何中文字体</b>（实测 JVM 里 {@code canDisplay('中') == false}），
 * 于是封面上的书名与作者全部渲染成 {@code □} 方框 —— 接口 200、PNG 有效、日志安静，
 * 只有人眼看封面才发现是坏的。字体是运行环境的一部分，靠服务器装字体等于把
 * 「封面是否可读」押在环境上；随应用打包才能保证换机器/重装都不退化。
 *
 * <p>内置字体是 Noto Sans SC（SIL OFL 1.1，见同目录 {@code OFL.txt}）的子集：
 * GB2312 全部汉字 + ASCII + 常用中文标点，约 2.4MB/字重。子集之外的生僻字
 * （如「龘」）仍会缺字，{@link #firstMissingGlyph} 会把缺字位置报出来供日志告警。
 *
 * <h3>换行必须按像素宽度，不能按字符个数</h3>
 * 历史实现用「每行 26 个字符」换行，但汉字是全角、字母数字是半角，26 个字符可能是
 * 260px 也可能是 700px。于是《潜规则-中国历史中的真实游戏》这类 14 字的书名在 28pt 下
 * 单行宽 392px，画到 300px 宽的画布上被硬裁 —— 首字「潜」和末字「戏」直接没了。
 * 现在改为：按
 * {@link FontMetrics#stringWidth} 逐字测量分行，字号从 40pt 往下逐档试，
 * 取「能塞进 {@value #TITLE_MAX_LINES} 行」的最大字号；短书名只要在
 * {@value #SINGLE_LINE_MIN_FONT_SIZE}pt 以上能一行放下就不折行（避免「中国的官 / 办经济」这种断词）；
 * 一行都放不下的超长书名才截断加省略号。
 */
public final class CoverRenderer {

    private static final Logger log = LoggerFactory.getLogger(CoverRenderer.class);

    private static final int WIDTH = 300;
    private static final int HEIGHT = 400;

    /** 封面底色缺省值（与前端 `.cover` 占位背景一致） */
    private static final String DEFAULT_BACKGROUND = "#78909C";

    private static final String REGULAR_RESOURCE = "/fonts/NotoSansSC-Regular-subset.ttf";
    private static final String BOLD_RESOURCE = "/fonts/NotoSansSC-Bold-subset.ttf";

    /** 文字左右各留的内边距（边框线画在 18px 处，文字再往里收一点，避免贴线） */
    private static final int CONTENT_MARGIN_X = 24;

    /** 书名最多行数；超过就换更小字号，最小字号仍超行数才截断 */
    private static final int TITLE_MAX_LINES = 4;

    /** 书名候选字号，从大到小逐档尝试（越短的书名用越大的字号） */
    private static final float[] TITLE_FONT_SIZES = {40f, 36f, 32f, 28f, 25f, 22f, 19f, 16f};

    /**
     * 「宁可降字号也要排成一行」的下限：短书名在 ≥28pt 能一行放下时就不折行。
     * 低于这个字号还放不下的，说明书名确实长，折行比缩成小字更好读。
     */
    private static final float SINGLE_LINE_MIN_FONT_SIZE = 28f;

    /** 书名整块文字的垂直中心（居中于画布偏上位置，给底部作者留空间） */
    private static final int TITLE_CENTER_Y = 180;

    private static final float AUTHOR_FONT_SIZE = 18f;
    private static final float AUTHOR_MIN_FONT_SIZE = 11f;
    /** 作者署名基线 y（一行，不参与自动换行，只缩字号/截断） */
    private static final int AUTHOR_BASELINE_Y = HEIGHT - 60;

    private static final String ELLIPSIS = "…";

    /** 这些字符之后是「自然的断行点」，优先把断点挪到它们后面 */
    private static final String GOOD_BREAK_AFTER = "-—–:：·/、，,；;）) ";

    /** 这些字符不该出现在行首（中文标点排印禁忌），遇到就往前挪一个字 */
    private static final String NO_LINE_START = "，。、；：！？）》】」』”’…·%";

    /** 为找自然断点，断点允许前后挪动的最大字数 */
    private static final int MAX_BREAK_SHIFT = 4;

    /** 字体只加载一次；加载失败也置位，避免每张封面都重读资源并刷日志 */
    private static volatile boolean fontsLoaded;
    private static volatile Font regularFont;
    private static volatile Font boldFont;

    /** 只用来量字宽的一次性画布（不画东西，用完不 dispose） */
    private static volatile Graphics2D metricsScratch;

    private CoverRenderer() {
    }

    /**
     * 书名的排版结果。
     *
     * @param fontSize 选中字号（pt）
     * @param lines    已按像素宽度分好的行（1 ~ {@value #TITLE_MAX_LINES} 行）
     */
    record TitleLayout(float fontSize, List<String> lines) {
    }

    /**
     * 渲染文字封面。
     *
     * @param title         书名（null/空按「未知书名」处理）
     * @param author        作者（空则留白，不画署名）
     * @param coverColorHex 底色，形如 {@code #9CCC65}；null 或非法值时用默认底色
     * @return PNG 字节
     */
    public static byte[] render(String title, String author, String coverColorHex) throws IOException {
        TitleLayout layout = layoutTitle(title);
        String safeAuthor = author != null ? author.trim() : "";

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 背景
            g.setColor(background(coverColorHex));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            // 边框装饰线
            g.setColor(new Color(255, 255, 255, 90));
            g.setStroke(new BasicStroke(2f));
            g.drawRect(18, 18, WIDTH - 36, HEIGHT - 36);

            // 书名
            Font titleFont = face(true, layout.fontSize());
            warnMissingGlyphs(titleFont, String.join("", layout.lines()), "书名");
            g.setFont(titleFont);
            g.setColor(Color.WHITE);
            drawTitle(g, layout);

            // 作者
            if (!safeAuthor.isEmpty()) {
                Font authorFont = authorFontFor(safeAuthor);
                warnMissingGlyphs(authorFont, safeAuthor, "作者");
                g.setFont(authorFont);
                g.setColor(new Color(255, 255, 255, 200));
                drawCentered(g, metricsFor(authorFont),
                        ellipsize(safeAuthor, authorFont, contentWidth()), WIDTH / 2, AUTHOR_BASELINE_Y);
            }
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    /**
     * 返回 {@code text} 中第一个<b>内置字体画不出来</b>的字符下标；全部可画返回 -1。
     *
     * <p>用途有两个：一是渲染时把缺字打成 warn（缺字只会画成方框，不报错、肉眼不易在日志里发现）；
     * 二是给单测一个「字体真的覆盖了中文」的硬断言。
     *
     * @return 缺字下标；{@code text} 为空返回 -1；字体资源整体不可用返回 0
     */
    public static int firstMissingGlyph(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        Font font = face(false, 18f);
        return font.canDisplayUpTo(text);
    }

    /** 内置字体是否真的加载成功（false 表示已退回逻辑字体，中文必然画成方框） */
    public static boolean isBundledFontLoaded() {
        ensureFontsLoaded();
        return regularFont != null;
    }

    /**
     * 计算书名排版：选一个「能塞进 {@value #TITLE_MAX_LINES} 行」的最大字号，并按像素宽度分行。
     *
     * <p>包内可见，供单测直接断言「每行都不超过可用宽度」——这是本类最容易退化、
     * 而肉眼在缩略图上最容易漏看的地方。
     */
    static TitleLayout layoutTitle(String title) {
        String text = normalizeTitle(title);
        int maxWidth = contentWidth();

        // 短书名优先排成一行：40pt 只差一点点时，宁可降一档字号，也不要折成「中国的官 / 办经济」这种断词
        for (float size : TITLE_FONT_SIZES) {
            if (size < SINGLE_LINE_MIN_FONT_SIZE) {
                break;
            }
            if (metricsFor(face(true, size)).stringWidth(text) <= maxWidth) {
                return new TitleLayout(size, List.of(text));
            }
        }

        // 一行实在放不下（或要缩到 28pt 以下才放得下），改多行：取能塞进 TITLE_MAX_LINES 行的最大字号
        for (float size : TITLE_FONT_SIZES) {
            List<String> lines = tryLayout(text, face(true, size), maxWidth);
            if (lines != null) {
                return new TitleLayout(size, lines);
            }
        }

        // 最小字号仍放不下：按最小字号逐行塞满，取前 TITLE_MAX_LINES 行，末行加省略号
        float smallest = TITLE_FONT_SIZES[TITLE_FONT_SIZES.length - 1];
        Font font = face(true, smallest);
        List<String> wrapped = wrapByWidth(font, text, maxWidth);
        List<String> kept = new ArrayList<>(wrapped.subList(0, Math.min(TITLE_MAX_LINES, wrapped.size())));
        if (wrapped.size() > TITLE_MAX_LINES) {
            // 必须先接上省略号再量宽：末行本身往往正好放得下，省略号是「后面还有字被截掉」的唯一标记
            int lastIndex = kept.size() - 1;
            kept.set(lastIndex, ellipsize(kept.get(lastIndex) + ELLIPSIS, font, maxWidth));
        }
        return new TitleLayout(smallest, kept);
    }

    /** 文字可用宽度（画布宽减去左右内边距） */
    static int contentWidth() {
        return WIDTH - CONTENT_MARGIN_X * 2;
    }

    /** 指定字号下、书名样式（粗体）单行文本的像素宽度；包内可见，供单测断言不溢出 */
    static int lineWidth(String text, float fontSize) {
        return metricsFor(face(true, fontSize)).stringWidth(text);
    }

    // ---------- 内部实现 ----------

    private static String normalizeTitle(String title) {
        return title == null || title.isBlank() ? "未知书名" : title.trim();
    }

    /**
     * 尝试用 {@code k = 1..TITLE_MAX_LINES} 行放下 {@code text}。
     *
     * @return 第一个能放下的分行方案；都放不下返回 null（由调用方换更小字号）
     */
    private static List<String> tryLayout(String text, Font font, int maxWidth) {
        FontMetrics fm = metricsFor(font);
        int n = text.length();
        for (int k = 1; k <= Math.min(TITLE_MAX_LINES, n); k++) {
            int[] cuts = refineCuts(text, balancedCuts(n, k), fm, maxWidth);
            List<String> lines = toLines(text, cuts);
            if (fits(lines, fm, maxWidth)) {
                return lines;
            }
        }
        return null;
    }

    /** 把 {@code n} 个字符尽量均匀地切成 {@code k} 段，返回 k+1 个切点（首 0 末 n） */
    private static int[] balancedCuts(int n, int k) {
        int[] cuts = new int[k + 1];
        int base = n / k;
        int remainder = n % k;
        int pos = 0;
        for (int i = 0; i < k; i++) {
            pos += base + (i < remainder ? 1 : 0);
            cuts[i + 1] = pos;
        }
        cuts[k] = n;
        return cuts;
    }

    /**
     * 把均衡切分的断点微调（±{@value #MAX_BREAK_SHIFT} 字）到更自然的位置：
     * 优先「上一行以可断标点结尾」，其次「下一行不以中文标点开头」。挪动后若超宽则放弃。
     */
    private static int[] refineCuts(String text, int[] cuts, FontMetrics fm, int maxWidth) {
        for (int i = 1; i < cuts.length - 1; i++) {
            int original = cuts[i];
            int bestCut = original;
            int bestScore = breakScore(text, original);
            for (int shift = -MAX_BREAK_SHIFT; shift <= MAX_BREAK_SHIFT && bestScore < 2; shift++) {
                if (shift == 0) {
                    continue;
                }
                int candidate = original + shift;
                // 每行至少留 2 个字，避免出现「孤字成行」
                if (candidate <= cuts[i - 1] + 1 || candidate >= cuts[i + 1] - 1) {
                    continue;
                }
                if (fm.stringWidth(text.substring(cuts[i - 1], candidate)) > maxWidth
                        || fm.stringWidth(text.substring(candidate, cuts[i + 1])) > maxWidth) {
                    continue;
                }
                int score = breakScore(text, candidate);
                if (score > bestScore
                        || (score == bestScore && Math.abs(shift) < Math.abs(bestCut - original))) {
                    bestCut = candidate;
                    bestScore = score;
                }
            }
            cuts[i] = bestCut;
        }
        return cuts;
    }

    /** 断点自然度打分：以可断标点结尾 = 2，不以中文标点开头 = 1，否则 0 */
    private static int breakScore(String text, int cut) {
        if (cut <= 0 || cut >= text.length()) {
            return 2;
        }
        if (GOOD_BREAK_AFTER.indexOf(text.charAt(cut - 1)) >= 0) {
            return 2;
        }
        return NO_LINE_START.indexOf(text.charAt(cut)) >= 0 ? 0 : 1;
    }

    private static List<String> toLines(String text, int[] cuts) {
        List<String> lines = new ArrayList<>(cuts.length - 1);
        for (int i = 0; i + 1 < cuts.length; i++) {
            lines.add(text.substring(cuts[i], cuts[i + 1]));
        }
        return lines;
    }

    private static boolean fits(List<String> lines, FontMetrics fm, int maxWidth) {
        for (String line : lines) {
            if (fm.stringWidth(line) > maxWidth) {
                return false;
            }
        }
        return true;
    }

    /** 不管行数、只按像素宽度贪心塞满每一行（超长书名的兜底路径用） */
    private static List<String> wrapByWidth(Font font, String text, int maxWidth) {
        FontMetrics fm = metricsFor(font);
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int fit = start + 1;
            for (int end = start + 1; end <= text.length(); end++) {
                if (fm.stringWidth(text.substring(start, end)) > maxWidth) {
                    break;
                }
                fit = end;
            }
            lines.add(text.substring(start, fit));
            start = fit;
        }
        return lines;
    }

    /** 截断到「内容 + 省略号」放得下为止 */
    private static String ellipsize(String text, Font font, int maxWidth) {
        FontMetrics fm = metricsFor(font);
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        for (int keep = text.length() - 1; keep > 0; keep--) {
            String candidate = text.substring(0, keep) + ELLIPSIS;
            if (fm.stringWidth(candidate) <= maxWidth) {
                return candidate;
            }
        }
        return ELLIPSIS;
    }

    /**
     * 取字面：优先内置字体，取不到则退回逻辑字体（此时中文会变方框，已有 ERROR 日志）
     */
    private static Font face(boolean wantBold, float size) {
        ensureFontsLoaded();
        Font base = wantBold ? boldFont : regularFont;
        if (base == null) {
            base = new Font(Font.SERIF, Font.PLAIN, 12);
        }
        return base.deriveFont(wantBold ? Font.BOLD : Font.PLAIN, size);
    }

    private static void ensureFontsLoaded() {
        if (fontsLoaded) {
            return;
        }
        synchronized (CoverRenderer.class) {
            if (fontsLoaded) {
                return;
            }
            regularFont = loadFont(REGULAR_RESOURCE);
            boldFont = loadFont(BOLD_RESOURCE);
            if (boldFont == null) {
                // 缺粗体不算故障：deriveFont(BOLD) 自身会做合成加粗，只是没真粗体饱满
                boldFont = regularFont;
            }
            if (regularFont == null) {
                log.error("封面内置中文字体缺失（{}），书名/作者将退回逻辑字体并渲染成方框；"
                        + "请确认 fonts/ 资源已打进 JAR", REGULAR_RESOURCE);
            }
            fontsLoaded = true;
        }
    }

    private static Font loadFont(String resource) {
        try (InputStream in = CoverRenderer.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            log.error("封面内置字体加载失败: {} ({})", resource, e.getMessage());
            return null;
        }
    }

    /** 量字宽用的一次性画布：不走真实绘制，只为拿 {@link FontMetrics} */
    private static FontMetrics metricsFor(Font font) {
        Graphics2D g = metricsScratch;
        if (g == null) {
            synchronized (CoverRenderer.class) {
                if (metricsScratch == null) {
                    metricsScratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
                }
                g = metricsScratch;
            }
        }
        return g.getFontMetrics(font);
    }

    private static Color background(String hex) {
        if (hex == null || hex.isBlank()) {
            return Color.decode(DEFAULT_BACKGROUND);
        }
        try {
            return Color.decode(hex.trim());
        } catch (NumberFormatException e) {
            // 底色来自 DB（管理员可手工改），脏值不该让整个封面接口 500
            log.warn("封面底色非法，改用默认色: {}", hex);
            return Color.decode(DEFAULT_BACKGROUND);
        }
    }

    private static void warnMissingGlyphs(Font font, String text, String what) {
        int missing = font.canDisplayUpTo(text);
        if (missing >= 0) {
            log.warn("封面{}存在内置字体无法渲染的字符（第 {} 个字符 '{}'），该字符会显示为方框: {}",
                    what, missing + 1, text.charAt(missing), text);
        }
    }

    /** 作者字号：18pt 起逐档缩到放得下为止（作者是单行，不换行） */
    private static Font authorFontFor(String author) {
        int maxWidth = contentWidth();
        for (float size = AUTHOR_FONT_SIZE; size > AUTHOR_MIN_FONT_SIZE; size -= 1f) {
            if (metricsFor(face(false, size)).stringWidth(author) <= maxWidth) {
                return face(false, size);
            }
        }
        return face(false, AUTHOR_MIN_FONT_SIZE);
    }

    /** 书名整块垂直居中绘制：行数多时自动往上生长，不会压到底部作者 */
    private static void drawTitle(Graphics2D g, TitleLayout layout) {
        FontMetrics fm = metricsFor(face(true, layout.fontSize()));
        int lineHeight = fm.getHeight();
        int lineCount = layout.lines().size();
        int top = TITLE_CENTER_Y - lineCount * lineHeight / 2;
        for (int i = 0; i < lineCount; i++) {
            drawCentered(g, fm, layout.lines().get(i), WIDTH / 2,
                    top + i * lineHeight + fm.getAscent());
        }
    }

    private static void drawCentered(Graphics2D g, FontMetrics fm, String text, int centerX, int y) {
        g.drawString(text, centerX - fm.stringWidth(text) / 2, y);
    }
}
