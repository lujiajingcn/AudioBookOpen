package com.audiobook.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.audiobook.server.util.TextSegmenter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;

/**
 * 电子书文本提取器 —— 支持 TXT / PDF / EPUB / MOBI / Markdown 五种格式。
 * 每种格式有独立的提取策略，对外暴露统一的 extractText() 方法。
 * Markdown 在服务端剥壳为纯文本（见 {@code convertMarkdown}），与 TXT 路径同口径。
 */
@Component
public class BookTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(BookTextExtractor.class);

    // ---- ZIP 炸弹防护（EPUB 本质是 ZIP）----
    // 上传体积限制只约束「压缩后」大小，恶意构造的高压缩比 ZIP 解压后可达数 GB，
    // 若无条件全部读入 Map<name, byte[]> 会直接打爆异步线程堆。这里对条目数、
    // 单条目解压体积、解压总体积三重设限，超限按损坏/恶意文件处理。
    /** 单个 ZIP 内最多允许的条目数 */
    private static final int MAX_ZIP_ENTRIES = 4096;
    /** 单个条目解压后允许的最大字节数（50MB，远超正常 XHTML/文本条目） */
    private static final long MAX_ZIP_ENTRY_BYTES = 50L * 1024 * 1024;
    /** 整个 ZIP 解压后所有条目合计允许的最大字节数（256MB） */
    private static final long MAX_ZIP_TOTAL_BYTES = 256L * 1024 * 1024;

    /** MOBI 章节标题：{@code <h1>}~{@code <h6>}（DOTALL，允许标题内嵌子标签） */
    private static final Pattern MOBI_HEADING =
            Pattern.compile("(?i)<h[1-6][^>]*>(.*?)</h[1-6]>", Pattern.DOTALL);

    // ---------- Markdown ----------
    /** ATX 标题：{@code #} ~ {@code ######}（作用于 strip 后的整行） */
    private static final Pattern MD_HEADING =
            Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$", Pattern.DOTALL);
    /** 图片 {@code ![alt](url)} → alt */
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\([^)]*\\)");
    /** 链接 {@code [text](url)} → text */
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    /** 参考式链接 {@code [text][ref]} → text */
    private static final Pattern MD_REF_LINK = Pattern.compile("\\[([^\\]]*)\\]\\[[^\\]]*\\]");
    /** 自动链接 {@code <http...>} / {@code <mailto:...>} → 地址 */
    private static final Pattern MD_AUTOLINK = Pattern.compile("<((?:https?://|mailto:)[^>]+)>");
    /** 行内代码 {@code `code`} → code（先于强调处理，避免内部 {@code *} 被当强调） */
    private static final Pattern MD_INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    /** 三级粗体 {@code ***x***} → x */
    private static final Pattern MD_BOLD3 = Pattern.compile("\\*\\*\\*([^*\\n]+?)\\*\\*\\*");
    /** 粗体 {@code **x**} → x */
    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*([^*\\n]+?)\\*\\*");
    /** 斜体（成对 {@code *x*} 才去标记，避免误伤 {@code 2 * 3}） */
    private static final Pattern MD_ITALIC = Pattern.compile("\\*([^*\\n]+?)\\*");
    /** 三级粗体（下划线）{@code ___x___} → x */
    private static final Pattern MD_BOLD_U3 = Pattern.compile("___([^_\\n]+?)___");
    /** 粗体（下划线）{@code __x__} → x */
    private static final Pattern MD_BOLD_U = Pattern.compile("__([^_\\n]+?)__");
    /** 删除线 {@code ~~x~~} → x */
    private static final Pattern MD_STRIKE = Pattern.compile("~~([^~\\n]+?)~~");
    /** 引用块首行 {@code > }（仅行首） */
    private static final Pattern MD_BLOCKQUOTE = Pattern.compile("^>\\s?");
    /** 列表标记 {@code - /* / 1. }（保留前导缩进） */
    private static final Pattern MD_LIST_MARKER = Pattern.compile("^(\\s*)(?:[-*+]|\\d+[.)])\\s+");
    /** 水平线 {@code ---} / {@code ***} / {@code ___} / {@code - - -} 等 */
    private static final Pattern MD_HR =
            Pattern.compile("^(\\s*)([-*_])(\\s*\\2){2,}\\s*$");
    /** 表格分隔行（仅 {@code | : -} 与空白）整行丢弃 */
    private static final Pattern MD_TABLE_SEP = Pattern.compile("^\\s*\\|?[\\s:|-]+\\|?\\s*$");
    /** 残留 HTML 标签 */
    private static final Pattern MD_HTML_TAG = Pattern.compile("<[^>]+>");

    /** 目录条目，包含标题和原文中的字符偏移量 */
    public static class TocEntry {
        public String title;
        public int charOffset;
        public int pageNumber; // PDF 专用，-1 表示无

        public TocEntry() {}

        public TocEntry(String title, int charOffset) {
            this.title = title;
            this.charOffset = charOffset;
            this.pageNumber = -1;
        }

        public TocEntry(String title, int charOffset, int pageNumber) {
            this.title = title;
            this.charOffset = charOffset;
            this.pageNumber = pageNumber;
        }
    }

    // ---------- 对外入口 ----------

    /** 切分后的正文段落（与 TTS 合成使用同一套 TextSegmenter 算法） */
    public record Paragraph(String text, int charOffset, int paragraphIndex) {}

    /**
     * 正文段落的完整提取结果：段落列表 + 目录 + 扩展名。
     *
     * <p>目录条目的 charOffset 已映射为「最近的前一个段落下标」，
     * 前端可直接用它滚动定位，无须再自行按偏移量二分。
     */
    /**
     * 「可直接阅读」的正文提取结果。
     *
     * @param paragraphs 合成片段（**= 完整句子**，长度不限，**与音频段号严格 1:1**）
     * @param toc        目录（{@link TocEntry#charOffset} 在 BookContent 路径下语义是**片段下标**）
     * @param extension  文件扩展名
     * @param rows       原文按行切分的文本（**保留行首缩进**），供阅读页直接显示；
     *                   TXT 为 {@code null}（见 {@link #splitRows}）；老客户端忽略该字段
     * @param rowParagraphStart 与 {@code rows} **等长平行**的段落下标：第 i 行**首个实字**落在第几段。
     *                   单调不减。客户端「点某行播哪段」用它；与 {@code rows} 同为 {@code null}（TXT / 空正文）
     * @param rowParagraphEnd   同上的**末个实字**版本：第 i 行**末个实字**落在第几段。
     *                   一行可以跨段（行尾是下一段的头），故必有 {@code start <= end}。
     *                   客户端算「第 p 段占哪几行」用它 —— 只看 start 会漏掉「本段从上一行中间开始」
     *                   的情况（那一行的 start 是上一段），高亮会缺一块
     */
    public record BookContent(List<Paragraph> paragraphs, List<TocEntry> toc, String extension,
                              List<String> rows, List<Integer> rowParagraphStart,
                              List<Integer> rowParagraphEnd,
                              List<List<int[]>> rowParagraphSegments) {}

    /**
     * {@link #splitRowsWithStarts} 的结果：行文本 + 与它等长的「行首实字下标」「行尾实字下标」。
     *
     * @param rows   行文本（保留行首缩进、已裁行尾）
     * @param starts 第 i 行**首个实字**在原文中的下标
     * @param ends   第 i 行**末个实字**在原文中的下标（闭区间，含）
     */
    record RowSplit(List<String> rows, List<Integer> starts, List<Integer> ends) {}

    /**
     * 提取电子书的「可直接阅读」内容：段落 + 目录。
     *
     * <p>把原先只存在于 {@code BookContentController#extractContent} 里的
     * 「提取正文 → TextSegmenter 切段 → 记录字符偏移 → 提取目录 → 偏移映射到段落下标」
     * 这段编排逻辑上收到此处，供两个入口共用：
     * <ul>
     *   <li>{@code POST /api/tts/extract-content}（上传本地文件后直接阅读）</li>
     *   <li>{@code GET /api/store/book/{id}/content}（书城书籍按 id 直接阅读）</li>
     * </ul>
     * 两处若各自维护一份，段落切分口径（尤其是 TTS 单段字数上限）极易漂移，
     * 表现为「阅读页段落」与「合成音频段落」对不上，用户跟读时文字与声音错位——
     * 这正是 {@link TextSegmenter} 类注释里记录过的那类高风险漂移点。
     *
     * <p>返回结果里有两套粒度，**用途不同、不可互替**：
     * <ul>
     *   <li>{@code paragraphs}：合成片段（**= 完整句子**，可远超 30 字），与音频段号严格 1:1，
     *       供「段号 ↔ 行号」映射；</li>
     *   <li>{@code rows}：原文行（保留行首缩进），供阅读页显示 —— 片段是切给引擎的，不是给人读的。</li>
     * </ul>
     * 两者都由本方法的同一次 {@code extractText} 派生，坐标同源。
     *
     * @param file      电子书文件
     * @param extension 文件扩展名（不含点）
     */
    public BookContent extractBookContent(File file, String extension) throws IOException {
        // 提取正文
        // PDF 走「一次解析同时产出正文与目录」的通道：同一份 PDF 只解析一遍。
        String text;
        List<TocEntry> rawToc = null;
        if (isPdfExtension(extension)) {
            PdfTextAndToc parsed = extractPdfTextAndToc(file);
            text = parsed.text();
            rawToc = parsed.toc();
        } else {
            text = extractText(file, extension);
        }

        // 按与 TTS 一致的算法切分段落，并记录每段在【原文 text 中】的起始下标。
        //
        // 这里必须求真实下标，不能像早前那样 `charOffset += para.length()` 累加：
        // TextSegmenter 在拼接句子时会 trim 掉首尾空白（见其内部注释），所以段落文本
        // 与原文并非逐字符连续的子串 —— 原文「你好。 世界。」会被拼成「你好。世界。」。
        // 累加值必然小于原文下标，且偏差随段落数持续累积；而目录条目用的却是原文真实下标
        // （extractEpub / extractPdf 按原始文本长度累加）。两端不同源 ⇒ 目录映射出的段落
        // 下标系统性偏大，表现为「点目录跳过头」，且越往后越明显。
        //
        // 用 sentences() 而不是 segment()：片段 = **完整句子**、**不限长度**。超过引擎单次
        // 上限的句子由合成侧（VoiceCloningTtsService.doSynthesizeSegment）拆小片再拼回一条音频，
        // 与「阅读页段落」的粒度无关。两处必须调同一个方法，否则阅读页段落与音频段号会错位。
        List<String> paraTexts = TextSegmenter.sentences(text);
        int[] starts = locateStartOffsets(text, paraTexts);
        List<Paragraph> paragraphs = new ArrayList<>(paraTexts.size());
        for (int i = 0; i < paraTexts.size(); i++) {
            paragraphs.add(new Paragraph(paraTexts.get(i), starts[i], i));
        }

        // 原文行（保留行首缩进）：与 paragraphs 是**同一套坐标**下的两种粒度 ——
        // paragraphs 给「段号 ↔ 音频」，rows 给「阅读页显示什么」。客户端拿 rows 显示、
        // 拿 paragraphs 建映射，因此两者必须来自同一次 extractText，否则映射会漂。
        RowSplit rowSplit = splitRowsWithStarts(text, extension);
        List<String> rows = rowSplit == null ? null : rowSplit.rows();
        // 「第 i 行属于第几段」也在服务端算：行首/行尾下标与段落起点（starts）由同一次
        // extractText 派生，这里是**唯一**能精确对齐两者的地方。客户端只拿到行文本，
        // 既不知道行首在原文里的下标、也不知道段落起点 —— 让它自己去猜（忽略空白逐段搜索）
        // 会把同一件事实现第二遍，且猜错了没有任何东西能发现（静默错位）。
        RowParaRange rowRange = rowParagraphRange(rowSplit, starts);

        // 每行内各段字符区间：把「行」再按段切开，客户端子高亮才能从音频真实起点开始
        // （否则整行铺满会把上一句的残字也盖成蓝底）。纯派生：坐标与 rowParagraphRange
        // 完全同源（同一份 rowSplit / starts / rowRange），不存在第二套映射，不会漂。
        // 每个内层数组是 [段落下标, 行内起始字符下标(含), 行内结束字符下标(含)]。
        List<List<int[]>> rowParagraphSegments = null;
        if (rowSplit != null && rowRange != null) {
            final int textLen = text.length();
            final int paraCount = paragraphs.size();
            int[] paraEnds = new int[paraCount];
            for (int p = 0; p < paraCount; p++) {
                // 段落 p 的末实字下标（闭区间）：取「下一段首实字前一位」；最后一段取到全文末。
                // 段间空白/标点被归入本段末尾，视觉上高亮略宽一点，无害。
                paraEnds[p] = (p + 1 < paraCount) ? (starts[p + 1] - 1) : (textLen - 1);
            }
            rowParagraphSegments = new ArrayList<>(rowSplit.rows().size());
            for (int i = 0; i < rowSplit.rows().size(); i++) {
                int rowStartOrig = rowSplit.starts().get(i);
                int rowLen = rowSplit.rows().get(i).length();
                int a = rowRange.start().get(i);
                int b = rowRange.end().get(i);
                List<int[]> segs = new ArrayList<>();
                for (int p = a; p <= b; p++) {
                    int startInRow = Math.max(0, starts[p] - rowStartOrig);
                    int endInRow = Math.min(rowLen - 1, paraEnds[p] - rowStartOrig);
                    if (endInRow >= startInRow) {
                        segs.add(new int[] { p, startInRow, endInRow });
                    }
                }
                rowParagraphSegments.add(segs);
            }
        }

        // 段落已切分完毕且全文不再被任何后续逻辑引用，提前释放引用，
        // 让接下来的目录提取与 JSON 序列化阶段不必同时持有这份可达 50MB 的大字符串，降低堆峰值
        text = null;
        paraTexts = null;

        // 提取目录（仅 PDF/EPUB/MOBI；TXT 无结构信息）
        // PDF 的目录已在 extractPdfTextAndToc 里随正文一并解析出来，直接复用，
        // 不再走 extractToc → 又一次 loadPDF + stripPdfPages（那一遍是纯浪费）。
        List<TocEntry> mappedToc = new ArrayList<>();
        if (rawToc != null) {
            for (TocEntry entry : rawToc) {
                // 将 charOffset 映射到最近的段落下标（与 mapTocToOffsets 同一规则）
                mappedToc.add(new TocEntry(entry.title,
                        paragraphIndexOfOffset(starts, entry.charOffset), entry.pageNumber));
            }
        } else if (extension != null && !extension.equalsIgnoreCase("txt")) {
            for (TocEntry entry : extractToc(file, extension)) {
                // 将 charOffset 映射到最近的段落下标（与 mapTocToOffsets 同一规则）
                mappedToc.add(new TocEntry(entry.title,
                        paragraphIndexOfOffset(starts, entry.charOffset), entry.pageNumber));
            }
        }
        return new BookContent(paragraphs, mappedToc, extension, rows,
                rowRange == null ? null : rowRange.start(),
                rowRange == null ? null : rowRange.end(),
                rowParagraphSegments);
    }

    /**
     * 是否是 PDF（大小写不敏感，{@code extension} 为 null 时按「不是」处理）。
     *
     * <p>PDF 是唯一需要「正文与目录一次解析」的格式：EPUB/MOBI/MD 的正文与目录
     * 各自解析一遍的代价远小于 PDF（EPUB 约 0.3s，PDF 401 页约 5s），
     * 而它们的目录逻辑与正文提取耦合更紧，合并收益不抵改动风险。
     */
    private static boolean isPdfExtension(String extension) {
        return extension != null && extension.equalsIgnoreCase("pdf");
    }

    /**
     * 把原文切成「阅读页要显示的行」：按 {@code \n} 拆行，**只裁行尾、保留行首缩进**，
     * 并丢弃拆出来的纯空白行。
     *
     * <p><b>为什么需要它</b>：{@link TextSegmenter#sentences} 的产物是「完整句子」，
     * 且切分时会把句首空白（段首缩进）与换行结构一起 trim 掉 ——
     * 段首缩进与原文换行结构在那一刻就没了。拿片段当阅读页正文，用户看到的是
     * 一堵没有排版的碎片。客户端对 PDF/MOBI 没有本地解析能力（只能靠服务端），
     * 所以由服务端把**原文行**一并带回去：客户端用 {@code rows} 显示、
     * 用 {@code paragraphs} 建「段号 ↔ 行号」映射，两者互补。
     *
     * <p><b>行首缩进是排版信息，必须留着</b>：只 {@code stripTrailing()}，不 {@code trim()}。
     * 这与客户端 TXT 解析（{@code ParagraphSplitter} 的 lineBreakAsParagraph 模式）同一口径，
     * 否则同一本书在 TXT 路径与 PDF 路径下又是两种排版。
     *
     * <p><b>TXT 不产出（返回 null）</b>：TXT 的阅读页由客户端本地解析，服务端这份没人消费；
     * 而 TXT 恰好是唯一可能顶到上传上限（50MB）的路径，多切一份行列表只会抬高堆峰值
     * 与响应体。PDF/EPUB/MOBI 必须产出，否则阅读页只能退回片段文本。
     *
     * @param text      原文（{@link #extractText} 的返回值）
     * @param extension 文件扩展名（不含点）
     * @return 原文行；TXT 或原文为空时返回 {@code null}
     */
    static List<String> splitRows(String text, String extension) {
        RowSplit split = splitRowsWithStarts(text, extension);
        return split == null ? null : split.rows();
    }

    /**
     * 与 {@link #splitRows} 同一实现，额外带回**每行首/末实字在原文中的下标** ——
     * 这是把「行」与「段」两种粒度对上的唯一依据（见 {@link #rowParagraphRange}）。
     *
     * <p>下标取的是每行**首个/末个「非空白」字符**，判据与 {@link #locateIgnoringBlank}
     * 完全一致（{@code <= ' '}）—— 两套坐标必须用同一个谓词，否则差异本身就会造成错位。
     * 特别地：半角缩进会被跳过（段落侧也被 {@code trim()} 去掉了），而**全角空格 U+3000 不跳过**
     * （它 {@code > ' '}，{@code trim()} 也不去它 ⇒ 它是段落自己的一部分，两侧都从它起算）。
     * 取行首本身则不行：那样半角缩进的行会被判成「属于上一段」，整份映射错位一行。
     *
     * @return {@code rows / starts / ends} 三者等长的结果；TXT 或原文为空时返回 {@code null}
     */
    static RowSplit splitRowsWithStarts(String text, String extension) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        if (extension != null && extension.equalsIgnoreCase("txt")) {
            return null;
        }
        List<String> rows = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        int len = text.length();
        int from = 0;
        while (from <= len) {
            int nl = text.indexOf('\n', from);
            int end = nl < 0 ? len : nl;
            // 只裁行尾：CRLF 的 '\r' 与行尾空格都在这步去掉，行首缩进原样留下
            String row = text.substring(from, end).stripTrailing();
            if (!row.isEmpty()) {
                rows.add(row);
                // 行首缩进跳过后的首个实字：与 locateIgnoringBlank 给段落下标的口径一致
                // （它也是从首个非空白字符起算），否则两边坐标差一个缩进就会整段错位
                int begin = from;
                while (begin < end && text.charAt(begin) <= ' ') {
                    begin++;
                }
                starts.add(begin);
                // stripTrailing 之后行尾必是实字 ⇒ 末个实字就是裁剪后那段的最后一个字符
                ends.add(from + row.length() - 1);
            }
            if (nl < 0) {
                break;
            }
            from = nl + 1;
        }
        return new RowSplit(rows, starts, ends);
    }

    /**
     * 行 → 段的两端下标（与 {@code rows} 等长平行、各自单调不减）。
     *
     * <p>两端**都必须有**，因为一行可以同时含「上一段的尾巴」与「下一段的头」：
     * 只看行首，本段若从上一行中间开始就会被判给上一段，高亮时缺一块；
     * 只看行尾则反过来。客户端据此做三件事：
     * ① 点某行播它开头的那一段（{@code start}）；② 高亮正在播的那一段占的**全部**行
     * （{@code start <= p <= end} 的那些行）；③ 目录给出段落下标后滚到该段的第一行。
     * 三段能力以前都没有 —— 阅读页显示的「段」是切给引擎的句子，不是原文。
     */
    private static RowParaRange rowParagraphRange(RowSplit rowSplit, int[] paragraphStarts) {
        if (rowSplit == null) {
            return null;
        }
        int n = rowSplit.rows().size();
        List<Integer> start = new ArrayList<>(n);
        List<Integer> end = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // 行首/行尾实字落进哪一段就归哪一段：与目录映射同一个 paragraphIndexOfOffset，
            // 保证「目录跳转」与「阅读页高亮」永远落在同一点上
            start.add(paragraphIndexOfOffset(paragraphStarts, rowSplit.starts().get(i)));
            end.add(paragraphIndexOfOffset(paragraphStarts, rowSplit.ends().get(i)));
        }
        return new RowParaRange(start, end);
    }

    /** {@link #rowParagraphRange} 的两端结果；{@code null} 表示没有行（TXT / 空正文） */
    private record RowParaRange(List<Integer> start, List<Integer> end) {}

    /**
     * 在 {@code text} 中从 {@code from} 起**搜索** {@code needle} 的首次出现（忽略两侧空白差异）。
     *
     * <p>与 {@link #locateIgnoringBlank} 的区别在于**是否允许起点试错**：
     * <ul>
     *   <li>{@code locateIgnoringBlank} 假设「起点就在 from」—— 段落坐标是顺序推进的，
     *       段落的起点确实确定，所以一次失配即可判定未命中（更快，也避免误配到后面的重复段落）。</li>
     *   <li>本方法用于按标题搜索（MOBI 目录），起点不确定，必须能退回去从下一个候选起点重试 ——
     *       否则搜「第二章 转折」时，正文首段「第一章的正文…」首字「第」与之相同、第二字就不符，
     *       一次失配直接判未命中，坐标会退化成游标并表现为「目录瞎跳」。</li>
     * </ul>
     *
     * @return {@code {起点下标, 末字符下标 + 1}}；未找到返回 null
     */
    private static int[] searchIgnoringBlank(String text, String needle, int from) {
        if (text == null || needle == null || needle.isEmpty()) {
            return null;
        }
        for (int start = Math.max(from, 0); start < text.length(); start++) {
            if (text.charAt(start) <= ' ') {
                continue; // 起点必须是非空白字符
            }
            int[] span = locateIgnoringBlank(text, needle, start);
            if (span != null) {
                return span;
            }
        }
        return null;
    }

    /**
     * 在 {@code text} 中从 {@code from} 起顺序定位段落 {@code para}，**忽略原文中的空白**。
     *
     * <p>为什么不能直接用 {@code String.indexOf}：{@code TextSegmenter#sentences} 在切分句子
     * 时会 trim 掉首尾空白，段落文本与原文往往不是逐字符连续的子串 ——
     * 原文「你好。 世界。」会被拼成「你好。世界。」，直接查找返回 -1、坐标退化。
     *
     * <p>匹配规则与 segment 的聚合方式对齐：跳过原文中 {@code <= ' '} 的空白字符
     * （与 {@code String.trim()} 去掉的字符集一致），其余字符必须逐个相等。
     * 段落内部保留的空白（trim 只作用于首尾）在原文中同样存在，逐字符相等，不会被误跳过。
     *
     * @return {@code {段落首字符在 text 中的下标, 段落末字符下标 + 1}}；未命中返回 null
     */
    private static int[] locateIgnoringBlank(String text, String para, int from) {
        if (para == null || para.isEmpty() || text == null) {
            return null;
        }
        int j = 0;
        int i = from;
        int start = -1;
        while (j < para.length()) {
            char p = para.charAt(j);
            if (p <= ' ') {
                // 段落侧空白同样要跳过，不能只跳原文侧：
                //  - 句子内部的空白会被保留（TextSegmenter 只 trim 首尾），
                //    英文正文「Hello world.」就是典型；
                //  - 硬切分支刻意不 trim，段落还可能**以空白开头**。
                // 此时原文与段落两边的空白是同源的，各自跳过即可保持对齐。
                j++;
                continue;
            }
            // 在原文中前进到下一个非空白字符（这些空白在段落侧已被切分/trim 丢弃）
            while (i < text.length() && text.charAt(i) <= ' ') {
                i++;
            }
            if (i >= text.length() || text.charAt(i) != p) {
                return null;
            }
            if (start < 0) {
                start = i;
            }
            i++;
            j++;
        }
        // i 是「最后一个匹配字符的下标 + 1」，正好可作为后续段落的搜索起点
        return start >= 0 ? new int[]{start, i} : null;
    }

    // ---------- 段落坐标（**唯一实现**，所有入口都必须走这里）----------

    /**
     * 把一串「段落文本」按顺序定位到原文 {@code text} 中的**真实起始下标**。
     *
     * <p>段落文本通常来自 {@link TextSegmenter#sentences(String)}，但它<b>不是</b>原文的连续子串：
     * 切分时会 trim 掉句子与段落的首尾空白（硬切分支刻意不 trim，见 {@link TextSegmenter}），
     * 所以「按 {@code para.length()} 累加」得到的是一个**压缩坐标** —— 它系统性地小于真实下标，
     * 且偏差随段落数持续累积。凡是需要把某个「原文偏移」换算成段落的地方（目录跳转、
     * 高亮定位、阅读页滚动），都必须用本文方法给出的真实下标，否则越往后偏得越离谱，
     * 表现为「点目录跳过头」。
     *
     * <p>定位策略是**顺序推进**：每段从上段末尾继续找，不回头。这样重复句不会被前一段抢占；
     * 某段未命中时不推进游标（留给后续段落继续尝试），保证一段失配不会把后面全部带偏。
     *
     * @param text           原文（须是 {@code extractText} 的返回值，与目录偏移同一坐标系）
     * @param paragraphTexts 段落文本，顺序与最终段号一致
     * @return 与入参等长的数组，元素为该段在原文中的起始下标
     */
    public static int[] locateStartOffsets(String text, List<String> paragraphTexts) {
        int[] starts = new int[paragraphTexts.size()];
        int searchFrom = 0;
        for (int i = 0; i < paragraphTexts.size(); i++) {
            int[] span = locateIgnoringBlank(text, paragraphTexts.get(i), searchFrom);
            if (span == null) {
                // 未命中（异常输入，理论上不可达）：不推进游标。
                starts[i] = searchFrom;
                continue;
            }
            starts[i] = span[0];
            searchFrom = span[1];
        }
        return starts;
    }

    /**
     * 在**升序**的段落起始下标数组中，找 {@code charOffset} 所属的段落下标（0 起）。
     *
     * <p>规则：最后一个起始下标 ≤ {@code charOffset} 的段落；若比首段还靠前则归首段。
     * 与 {@link #extractBookContent} 的目录映射、以及 TTS 任务目录端点完全一致 ——
     * 三处须同源，否则同一本书的目录在阅读页与播放页会跳到不同段落。
     *
     * @param startOffsets 由 {@link #locateStartOffsets} 产出的升序数组
     * @param charOffset   需要换算的原文偏移
     * @return 段落下标；{@code startOffsets} 为空时返回 0
     */
    public static int paragraphIndexOfOffset(int[] startOffsets, int charOffset) {
        int index = 0;
        for (int i = 0; i < startOffsets.length; i++) {
            if (charOffset >= startOffsets[i]) {
                index = i;
            } else {
                break;
            }
        }
        return index;
    }

    /**
     * 根据文件扩展名自动选择提取策略，返回纯文本内容。
     *
     * @param file       电子书文件
     * @param extension  文件扩展名（不含点），如 "txt", "pdf", "epub", "mobi"
     * @return 提取的纯文本
     */
    public String extractText(File file, String extension) throws IOException {
        return switch (extension.toLowerCase()) {
            case "pdf"  -> extractPdf(file);
            case "epub" -> extractEpub(file);
            case "mobi" -> extractMobi(file);
            case "md"   -> extractMarkdown(file);
            default     -> extractTxt(file);
        };
    }

    /**
     * 根据文件扩展名提取目录。
     *
     * @param file       电子书文件
     * @param extension  文件扩展名（不含点）
     * @return 目录条目列表，按在原文中出现的顺序排列
     */
    public List<TocEntry> extractToc(File file, String extension) throws IOException {
        return switch (extension.toLowerCase()) {
            case "pdf"  -> extractPdfToc(file);
            case "epub" -> extractEpubToc(file);
            case "mobi" -> extractMobiToc(file);
            case "md"   -> extractMarkdownToc(file);
            default     -> List.of(); // TXT 不含结构信息
        };
    }

    // ---------- TXT ----------

    public String extractTxt(File file) throws IOException {
        // 整本电子书一次性读入堆，受上传上限 ≤50MB 约束（文本提取固有行为，非流式）
        byte[] bytes = Files.readAllBytes(file.toPath());
        // 不强制 UTF-8：国内大量 TXT 电子书为 GBK 编码，
        // 直接按 UTF-8 解码会产生整本乱码。探测顺序：BOM → UTF-8 字节模式校验 → GBK 回退
        Charset charset = detectTxtCharset(bytes);
        String text = new String(bytes, charset);
        // 剥离 BOM 字符，防止首段文本前出现不可见的 \uFEFF
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    /**
     * TXT 编码探测：
     * 1. 带 BOM 直接按 BOM 声明的编码；
     * 2. 按 UTF-8 字节模式严格校验（UTF-8 自同步性使误判概率极低），全部合法即按 UTF-8；
     * 3. 出现非法 UTF-8 序列 → 回退 GBK（中文电子书另一主流编码，其字节空间极宽，
     *    正常文本几乎不会解码失败，避免整本读成乱码）。
     */
    private static Charset detectTxtCharset(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8; // UTF-8 BOM
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }

        boolean sawHighByte = false;
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            sawHighByte = true;
            int extra;
            if (b >= 0xC2 && b <= 0xDF) {
                extra = 1;
            } else if (b >= 0xE0 && b <= 0xEF) {
                extra = 2;
            } else if (b >= 0xF0 && b <= 0xF4) {
                extra = 3;
            } else {
                return Charset.forName("GBK"); // 非法 UTF-8 首字节
            }
            if (i + extra >= bytes.length) {
                return Charset.forName("GBK"); // 序列被截断
            }
            for (int k = 1; k <= extra; k++) {
                if ((bytes[i + k] & 0xC0) != 0x80) {
                    return Charset.forName("GBK"); // 后续字节不是 10xxxxxx
                }
            }
            i += extra + 1;
        }
        // 纯 ASCII 或完全符合 UTF-8 结构：按 UTF-8
        return StandardCharsets.UTF_8;
    }

    // ---------- Markdown ----------
    //
    // Plan A：Markdown 在服务端被「剥壳」成纯文本，阅读页与 TTS 合成都用这份纯文本，
    // 与 TXT 路径同一口径（句子切分、行切分、段落映射都复用现有逻辑）。保留换行结构，
    // 阅读页用 strip 后的行显示；标题（# ~ ######）顺带导出为目录。

    public String extractMarkdown(File file) throws IOException {
        return convertMarkdown(file, null);
    }

    public List<TocEntry> extractMarkdownToc(File file) throws IOException {
        List<TocEntry> toc = new ArrayList<>();
        convertMarkdown(file, toc);
        return toc;
    }

    /**
     * Markdown → 纯文本 的单一实现入口。
     *
     * <p>同一份实现既产出正文（{@link #extractMarkdown} 传 {@code null}），
     * 也在同一次遍历里（传 {@code tocOut} 时）收集标题作为目录 ——
     * 这样目录条目的 {@code charOffset} 与正文的纯文本坐标是**同一趟**算出来的，
     * 两者必然同源，目录跳转绝不会偏移到别的段落（见 PDF 那段「不同源」事故注释）。
     *
     * <p>偏移口径：{@code plainOffset} 在每行 append 之前记录，正是该行首个实字在
     * 纯文本里的下标；标题行整行就是一段，所以直接拿它当 charOffset 即可。
     */
    private static String convertMarkdown(File file, List<TocEntry> tocOut) throws IOException {
        String raw = readUtf8WithBom(file);
        // 统一换行：MD 既可能是 \n 也可能是 \r\n（Windows 编辑器常见）
        raw = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder();
        boolean inCode = false;   // 围栏代码块 ``` / ~~~ 状态
        int plainOffset = 0;
        for (String line : raw.split("\n", -1)) {
            String trimmed = line.strip();
            String conv;
            boolean isHeading = false;
            String headingTitle = null;
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                // 围栏代码块的开/闭标记行本身不进正文
                inCode = !inCode;
                conv = "";
            } else if (inCode) {
                conv = line; // 代码块内容原样保留为纯文本（不做任何行内转换）
            } else {
                Matcher hm = MD_HEADING.matcher(trimmed);
                if (hm.matches()) {
                    isHeading = true;
                    // 标题文本再过一遍行内转换，去掉可能的 ** / * 等强调标记
                    headingTitle = convertMdInline(hm.group(2).strip());
                    conv = headingTitle;
                } else {
                    conv = convertMdInline(line);
                }
            }

            int lineStart = plainOffset;
            sb.append(conv).append('\n');
            if (isHeading && headingTitle != null && !headingTitle.isEmpty() && tocOut != null) {
                tocOut.add(new TocEntry(headingTitle, lineStart));
            }
            plainOffset += conv.length() + 1; // +1 为刚刚 append 的 '\n'
        }
        return sb.toString();
    }

    /**
     * 单行 Markdown → 纯文本（行内转换）。不改变换行，保留前导缩进。
     *
     * <p>处理顺序（越「结构」越先处理，越「行内修饰」越后处理）：
     * 图片/链接/参考链接/自动链接 → 引用块与列表标记 → 水平线/表格分隔行整行丢弃 →
     * 行内代码 → 粗体/斜体/删除线（成对才去标记，避免误伤 {@code 2 * 3}、
     * {@code some_var_name}）→ 表格数据行的 {@code |} 换成空格 → 残留 HTML 标签。
     */
    private static String convertMdInline(String line) {
        String s = line;
        s = MD_IMAGE.matcher(s).replaceAll("$1");
        s = MD_LINK.matcher(s).replaceAll("$1");
        s = MD_REF_LINK.matcher(s).replaceAll("$1");
        s = MD_AUTOLINK.matcher(s).replaceAll("$1");
        s = MD_BLOCKQUOTE.matcher(s).replaceAll("");
        s = MD_LIST_MARKER.matcher(s).replaceAll("$1");
        if (MD_HR.matcher(s).matches()) {
            return "";
        }
        if (MD_TABLE_SEP.matcher(s).matches()) {
            return "";
        }
        s = MD_INLINE_CODE.matcher(s).replaceAll("$1");
        s = MD_BOLD3.matcher(s).replaceAll("$1");
        s = MD_BOLD.matcher(s).replaceAll("$1");
        s = MD_ITALIC.matcher(s).replaceAll("$1");
        s = MD_BOLD_U3.matcher(s).replaceAll("$1");
        s = MD_BOLD_U.matcher(s).replaceAll("$1");
        s = MD_STRIKE.matcher(s).replaceAll("$1");
        if (s.indexOf('|') >= 0) {
            s = s.replace('|', ' ');
        }
        s = MD_HTML_TAG.matcher(s).replaceAll("");
        return s;
    }

    /** 以 UTF-8 读取文件并剥离 UTF-8 BOM（MD 恒为 UTF-8，无需 GBK 探测）。 */
    private static String readUtf8WithBom(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        int start = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            start = 3; // 跳过 UTF-8 BOM
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    // ---------- PDF ----------

    /**
     * PDF 的正文与目录<b>一次解析同时产出</b>。
     *
     * <p><b>为什么需要这个方法</b>：{@link #extractBookContent} 原本先调 {@link #extractText}
     * 取正文、再调 {@link #extractToc} 取目录，而这两个方法各自
     * {@code Loader.loadPDF(file) + stripPdfPages(document)} 走一遍 ——
     * 同一本 PDF 被<b>完整解析两次</b>。PDF 的逐页取文是整条链路上最贵的一步
     * （401 页的《无穷的开始》单遍约 5s，两遍就是 10s），
     * 而阅读页必须等这 10s 才有正文返回。
     *
     * <p><b>同源要求</b>：正文与目录的坐标必须来自<b>同一份页文本</b>——
     * 目录的页起点由 {@link #pdfPageStartOffsets} 按页文本长度累加，
     * 页内标题定位又用 {@link #joinPdfPages} 拼出的全文；
     * 两边若各自解析一次，虽然输入相同结果也相同，但一旦清洗逻辑带上状态
     * （如「全书词表」这类跨页结构），两份页文本就会分叉 ⇒ 目录跳错位置。
     * 所以这里让两者<b>共用同一份 pageTexts</b>，从根上杜绝分叉。
     */
    public record PdfTextAndToc(String text, List<TocEntry> toc) {}

    /** PDF 一次解析同时得到正文与目录（目录条目是<b>原文真实下标</b>，未映射到段落下标）。 */
    public PdfTextAndToc extractPdfTextAndToc(File file) throws IOException {
        List<TocEntry> toc;
        String text;
        try (var document = org.apache.pdfbox.Loader.loadPDF(file)) {
            List<String> pageTexts = stripPdfPages(document);
            log.info("PDF 共 {} 页", pageTexts.size());
            text = joinPdfPages(pageTexts);

            if (text.isEmpty()) {
                throw new IOException("PDF 文件未提取到任何文本内容（可能是扫描件或图片型 PDF）");
            }

            // 目录：与 extractPdfToc 同一套逻辑，但复用上面这份 pageTexts / fullText
            int[] pageStartOffset = pdfPageStartOffsets(pageTexts);
            toc = new ArrayList<>();
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            PDOutlineNode outline = catalog.getDocumentOutline();
            if (outline != null && outline.hasChildren()) {
                flattenPdfOutline(outline, toc, pageStartOffset, document, text);
            }
        }
        log.info("PDF 文本提取完成，共 {} 字符；目录 {} 条（正文与目录同源，一次解析）",
                text.length(), toc.size());
        return new PdfTextAndToc(text, toc);
    }

    public String extractPdf(File file) throws IOException {
        String text;
        try (var document = org.apache.pdfbox.Loader.loadPDF(file)) {
            List<String> pageTexts = stripPdfPages(document);
            log.info("PDF 共 {} 页", pageTexts.size());
            text = joinPdfPages(pageTexts);
        }

        if (text.isEmpty()) {
            throw new IOException("PDF 文件未提取到任何文本内容（可能是扫描件或图片型 PDF）");
        }
        log.info("PDF 文本提取完成，共 {} 字符", text.length());
        return text;
    }

    /** 逐页提取文本；下标 i 对应第 i+1 页。{@link #extractPdf} 与 {@link #extractPdfToc} 共用。 */
    private static List<String> stripPdfPages(PDDocument document) throws IOException {
        org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
        int pages = document.getNumberOfPages();
        List<String> pageTexts = new ArrayList<>(pages);
        for (int i = 0; i < pages; i++) {
            stripper.setStartPage(i + 1);
            stripper.setEndPage(i + 1);
            String pageText = stripper.getText(document);
            pageTexts.add(pageText == null ? "" : pageText);
        }
        // 两处清洗都放在这里、就地改写 pageTexts —— 不能挪到 extractPdf 里做：
        // 目录的页起点由 pdfPageStartOffsets(pageTexts) 按「同一份页文本」的长度累加得出
        // （见 pdfPageStartOffsets 注释里记录的「不同源」事故），若只清洗正文不清洗这里，
        // 页起点与正文立刻分叉，目录映射出的段落下标会系统性偏移。
        collapseDotLeaders(pageTexts);
        dropRunningMargins(pageTexts);
        // 第三处清洗：把「视觉行」并回自然段。必须排在 dropRunningMargins **之后** ——
        // 那边靠「位于页面前/后若干行」判断页眉页脚，只有在视觉行粒度上才成立；
        // 先合并成自然段，一页可能只剩两三条「行」，页眉页码就再也认不出来了。
        // 与上两处一样就地改写：pdfPageStartOffsets 必须看到同一份页文本。
        reflowPdfPages(pageTexts);
        return pageTexts;
    }

    // ---------- PDF 版面重排：视觉行 → 自然段 ----------

    /**
     * 单字母连串：{@code S O C I E T I E S} 这种「一个字母 + 一个空格」重复 **4 次及以上**。
     *
     * <p>为什么会出现：PDF 里大字号标题常把每个字单独定位（tracking 拉开），
     * 文本提取器把这些定位间距当成了词间空格。封面上实测得到
     * {@code HOW S O C I E T I E S CHOOSE}，阅读页就是这副样子，TTS 更会逐字母念。
     *
     * <p>门槛取 4 而不是 2：{@code A I}（人工智能）、{@code a b}（变量名）这类
     * 双/三字母组合在正文里是合法写法，合并会毁掉语义；连续 4 个以上单字母
     * 几乎只可能来自「字距被当空格」。
     */
    private static final Pattern PDF_SINGLE_LETTER_RUN =
            Pattern.compile("(?<![A-Za-z])(?:[A-Za-z] ){3,}[A-Za-z](?![A-Za-z])");

    /** 行内连续空白（≥2 个空格/制表符）压成一个 —— 两种引擎的空格宽度算法不同，压缩后才同源 */
    private static final Pattern PDF_INNER_SPACES = Pattern.compile("[ \\t]{2,}");

    /**
     * 单纯由 ASCII 字母组成的串，用来建**全书词表**（判断「合并后的串到底是不是一个词」）。
     *
     * <p>只在**原始**页文本上跑（{@link #pdfCorpus}），不能在
     * {@link #cleanPdfLine} 之后跑：清洗会把 {@code O F E C O N O M I C S} 这种
     * 字距串折成一个假词，喂回词表就成了自证。
     */
    private static final Pattern PDF_WORD = Pattern.compile("[A-Za-z]+");

    /**
     * 不可映射字符：{@code U+FFFE}（非字符）与 {@code U+FFFD}（替换符）。
     *
     * <p>PDFium 在「行尾连字符的 ToUnicode 映射缺失」时会把断行吞掉、把一个
     * U+FFFE 塞在词中间 —— 实测《曼昆经济学原理》得到 {@code gov\ufffeernment}、
     * {@code per\ufffeson}、{@code efficiency} 类碎片。PDFBox 同一处输出的是
     * {@code gov-} + 换行 + {@code ernment}，两边**必须都归到 {@code government}**，
     * 否则客户端预览与服务端权威正文在第一个断词处就分叉。
     */
    private static final char PDF_UNMAPPED = '\uFFFE';
    private static final char PDF_REPLACEMENT = '\uFFFD';

    /** 软连字符：语义就是「此处可断行、连字符不显示」，直接删 */
    private static final char PDF_SOFT_HYPHEN = '\u00AD';

    /**
     * 只由这些字符与空白组成的行 = 装饰/图片占位，整行丢弃。
     *
     * <p>实测封面出版社 logo 的位置被提取成 {@code ■ ■ ■ ■ ■■■■■■}（6 个黑方块），
     * 阅读页显示成一排乱码、TTS 念不出来。注意判据是「**整行**只剩这些」，
     * 正文里夹着一个方块（字体缺字）不会触发 —— 那种情况宁可留着，也不能误删正文。
     */
    private static final String PDF_NOISE_CHARS = "■□▪▫◾◽●○◆◇★☆►◄▲▼\uFFFD\u25A0\u25A1\u25AA\u25AB";

    /** 版心宽度取行长分布的 80 分位（末行、缩进行、图注会把平均值拉低） */
    private static final double PDF_WIDTH_PERCENTILE = 0.80;

    /**
     * 「这一行是段末」的长度阈值（相对版心宽度）。
     *
     * <p>取 0.80 而不是 0.85：实测《Collapse》第 21 页首行 58 字符、版心 69，
     * 0.85 会把 58 判成短行、把一个跨页段落劈成两段；0.80 的阈值是 55.2，正确判为满行。
     * 反向风险（本该断的没断）只是少一个段间距，比多断一段更不可见。
     */
    private static final double PDF_SHORT_LINE_RATIO = 0.80;

    /**
     * 「短行占比」达到这个值的页视为**非流式正文**（版权页、目录、索引、诗歌、表格），
     * 整页不做合并。
     *
     * <p>那类页面每行都是独立条目、没有「满行」概念，硬按行长合并会把整个地址块
     * 并成一大段（实测《Collapse》版权页 48 行会并成 19 段，比不合并更糟）。
     * 实测该书 549 页里短行占比 ≥40% 的只有 12 页，正好是封面/版权/目录/索引。
     */
    private static final double PDF_LIST_PAGE_SHORT_RATIO = 0.40;

    /** 行数太少的页面（封面、扉页）没有版心统计意义，整页原样保留 */
    private static final int PDF_REFLOW_MIN_LINES = 5;

    /** 全大写短行视为标题，不与正文合并 */
    private static final int PDF_HEADING_MAX_LEN = 60;

    /**
     * 把 U+FFFE 还原成「行尾连字符 + 换行」，**必须在切行之前**做。
     *
     * <p>PDFium 的实测行为：遇到「行尾连字符的 ToUnicode 映射缺失」时，它不只丢字符，
     * 还会**把换行一起吞掉** —— `gov-` + `\\n` + `ernment` 变成同一行里的 `gov␞ernment`。
     * 这带来两个后果，只删字符解决不了：
     * <ol>
     *   <li>两个词黏成一个（`g overnment`），要靠断词规则才能正确合并；</li>
     *   <li>**行长失真**：实测某页出现一行 118 字符，而真实版心只有 66 ⇒ 80 分位被抬高，
     *       「短行=段末」判据随之偏移，与服务端（PDFBox 输出两行）的段落边界分叉。</li>
     * </ol>
     *
     * <p>还原成 `-` + 换行后，走的是与服务端**完全相同**的断词合并路径
     * （{@link #isHyphenBreak} → 去掉连字符黏合），两端结果逐字符一致。
     * 前后不是字母时（行尾孤立符号）换成空格，交给 {@link #cleanPdfLine} 后续处理。
     */
    static String expandPdfSoftBreaks(String pageText) {
        if (pageText == null || pageText.indexOf(PDF_UNMAPPED) < 0) {
            return pageText;
        }
        int len = pageText.length();
        StringBuilder sb = new StringBuilder(len + 64);
        for (int i = 0; i < len; i++) {
            char c = pageText.charAt(i);
            if (c == PDF_UNMAPPED) {
                char prev = i > 0 ? pageText.charAt(i - 1) : ' ';
                char next = i + 1 < len ? pageText.charAt(i + 1) : ' ';
                if (Character.isLetter(prev) && Character.isLetter(next)) {
                    sb.append('-').append('\n');
                } else {
                    sb.append(' ');
                }
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 整本逐页重排。就地改写 {@code pageTexts}，与上面两处清洗同源。
     *
     * <p>语料必须**先全本建好再逐页重排**：字距串该不该拆成两个词，
     * 单看一页是判不出来的（见 {@link #collapseLetterSpacedRun}）。
     */
    private static void reflowPdfPages(List<String> pageTexts) {
        PdfCorpus corpus = pdfCorpus(pageTexts);
        for (int i = 0; i < pageTexts.size(); i++) {
            pageTexts.set(i, reflowPdfPageText(pageTexts.get(i), corpus));
        }
    }

    /**
     * 一本书的文本语料：词表 + 相邻词组表。只服务于一个很窄的问题 ——
     * 某个字距串是一个词、还是两个词连排。不做拼写纠正、不参与别的判断。
     */
    record PdfCorpus(Set<String> words, Set<String> adjacentPairs) {
        static final PdfCorpus EMPTY = new PdfCorpus(Set.of(), Set.of());

        boolean isWord(String lowerWord) {
            return words.contains(lowerWord);
        }

        /** 两段是否在书里作为**相邻两个词**出现过（{@code of economics}） */
        boolean areAdjacent(String first, String second) {
            return adjacentPairs.contains(first + ' ' + second);
        }
    }

    /**
     * 建全书语料：词表（全小写、长度 ≥2）+ 相邻词组表。
     *
     * <p>必须在 {@link #cleanPdfLine} **之前**取，否则「字距被当空格」的
     * {@code O F E C O N O M I C S} 已被折成 {@code OFECONOMICS}，
     * 用它自己证明自己该合并，逻辑就空转了。
     *
     * <p><b>为什么单靠词表不够</b>：词表被断词残片污染（行尾 {@code build-} +
     * 下行 {@code ing} 让 {@code ing} 成了「词」），于是几乎任何长单词都能被切成
     * 两个「合法」组合 —— 实测《曼昆》放开切分，{@code Obeying}→{@code Obey ing}、
     * {@code Fleece}→{@code Flee ce}、{@code Outsider}→{@code Out sider} 全被误拆；
     * 《Collapse》的 {@code MONTANA} 被切成 {@code MONT AN}。
     *
     * <p>加上「两半必须相邻出现过」后区分度是干净的：14 个正确切分（{@code of economics}
     * 88 次、{@code an economist} 39 次、{@code common resources} 39 次…）全部命中，
     * 而 {@code obey ing}／{@code flee ce}／{@code mont an}／{@code li ty} 在全书里
     * 一次都没有 —— 因为真实排版下它们从来不是两个相邻的词。
     */
    static PdfCorpus pdfCorpus(List<String> pageTexts) {
        Set<String> words = new HashSet<>(1 << 14);
        Set<String> pairs = new HashSet<>(1 << 15);
        if (pageTexts == null) {
            return PdfCorpus.EMPTY;
        }
        for (String page : pageTexts) {
            if (page == null || page.isEmpty()) {
                continue;
            }
            String previous = null;
            for (String line : expandPdfSoftBreaks(page).split("\n", -1)) {
                previous = null;   // 词组不跨行：跨行的「两半」不是真正的相邻词
                Matcher m = PDF_WORD.matcher(line);
                while (m.find()) {
                    String word = m.group().toLowerCase(Locale.ROOT);
                    if (word.length() < 2) {
                        previous = null;
                        continue;
                    }
                    words.add(word);
                    if (previous != null) {
                        pairs.add(previous + ' ' + word);
                    }
                    previous = word;
                }
            }
        }
        return new PdfCorpus(words, pairs);
    }

    /**
     * 把一页的**视觉行**并回**自然段**：PDF 没有段落概念，文本提取只按坐标换行，
     * 于是一页 60 行就是 60 个「段落」—— 阅读页每行之间都带段间距、TTS 逐行成段。
     *
     * <p>判据只用**行长度**（相对版心），不用坐标：客户端 PDFium 与服务端 PDFBox 的
     * 坐标系、精度都不同，任何坐标判据都会让两端产出不同文本；行长则来自文本本身，
     * 两端天然一致（实测 573 页里 551 页有行尾空格差异，被本方法统一后降到 22 页）。
     *
     * <p>输入口径与 {@link #splitRowsWithStarts} 完全一致：按 {@code \n} 切行、
     * **只裁行尾**、丢弃空行。这一步是「两端同源」的关键 —— 若不先裁行尾，
     * PDFBox 输出 {@code "VIKING "}、PDFium 输出 {@code "VIKING"}，
     * 行长差 1 就会让合并判据在两端口径不同。
     *
     * @param pageText 单页原始文本（已过 {@code collapseDotLeaders} / {@code dropRunningMargins}）
     * @return 重排后的页文本；不需要重排时返回**行级清洗后**的文本（不含空行、无行尾空白）
     */
    static String reflowPdfPageText(String pageText) {
        return reflowPdfPageText(pageText, PdfCorpus.EMPTY);
    }

    /**
     * @param corpus 全书语料（见 {@link #pdfCorpus}）；{@link PdfCorpus#EMPTY} 表示
     *               「没有语料证据」，此时字距串按老规矩直接合并、不尝试插回词间空格
     */
    static String reflowPdfPageText(String pageText, PdfCorpus corpus) {
        if (pageText == null || pageText.isEmpty()) {
            return "";
        }
        // 必须在切行**之前**做：PDFium 会把「行尾连字符 + 换行」吞成一个 U+FFFE 并
        // 把两行黏在同一行里，行长因此失真（实测一行 118 字符，而真实版心是 66）。
        pageText = expandPdfSoftBreaks(pageText);
        List<String> lines = new ArrayList<>();
        for (String raw : pageText.split("\n", -1)) {
            String line = cleanPdfLine(raw, corpus);
            if (!line.isEmpty() && !isPdfNoiseLine(line)) {
                lines.add(line);
            }
        }
        // 断词续行先黏合，再做行长统计。顺序不能反：`gov-`+`ernment` 若留在统计里，
        // 会同时贡献一个 40 字符的「伪短行」和一个 16 字符的「伪短行」，
        // 把短行占比抬到列表页阈值以上 ⇒ 整页拒绝重排，PDF 又退回「每行一段」。
        lines = joinHyphenatedRows(lines);
        if (lines.size() < PDF_REFLOW_MIN_LINES) {
            return String.join("\n", lines);
        }
        int[] lengths = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            lengths[i] = lines.get(i).length();
        }
        java.util.Arrays.sort(lengths);
        int typical = lengths[(int) (lengths.length * PDF_WIDTH_PERCENTILE)];
        if (typical <= 0) {
            return String.join("\n", lines);
        }
        int shortCount = 0;
        for (int len : lengths) {
            if (len < typical * PDF_SHORT_LINE_RATIO) {
                shortCount++;
            }
        }
        if (shortCount >= lines.size() * PDF_LIST_PAGE_SHORT_RATIO) {
            return String.join("\n", lines);
        }
        double shortThreshold = typical * PDF_SHORT_LINE_RATIO;
        List<String> paras = new ArrayList<>();
        StringBuilder cur = new StringBuilder(lines.get(0));
        // ⚠️ 判据必须看「**最后并入的那一行**」的长度，不能看 cur 的总长度：
        // cur 一旦并入两行就必然超过阈值，此后永远不再断开 ⇒ 整页并成一段。
        String last = lines.get(0);
        for (int i = 1; i < lines.size(); i++) {
            String next = lines.get(i);
            boolean join = true;
            if (isPdfHeadingLine(last, typical) || isPdfHeadingLine(next, typical)) {
                join = false;
            } else if (last.length() < shortThreshold) {
                // 上一行明显短于版心 ⇒ 它是段落末行，本行开始新段
                join = false;
            }
            if (join) {
                if (needsSpaceBetween(cur, next)) {
                    cur.append(' ').append(next);
                } else {
                    cur.append(next);
                }
            } else {
                paras.add(cur.toString());
                cur = new StringBuilder(next);
            }
            last = next;
        }
        paras.add(cur.toString());
        return String.join("\n", paras);
    }

    /**
     * 预合并断词续行：行尾 `-`（非 `--`）+ 下一行首小写 ⇒ 去掉连字符直接黏合。
     *
     * <p>必须独立于段落合并、且排在**行长统计之前**（理由见调用处注释）。
     * 客户端 PDFium 会把这类断行吞成 U+FFFE（先由 {@link #expandPdfSoftBreaks}
     * 还原成同样形态），服务端 PDFBox 本来就是两行 —— 两条路到这里汇合，
     * 走同一套规则，结果逐字符一致。
     */
    private static List<String> joinHyphenatedRows(List<String> lines) {
        if (lines.size() < 2) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.size());
        StringBuilder cur = new StringBuilder(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            String next = lines.get(i);
            if (isHyphenBreak(cur, next)) {
                cur.setLength(cur.length() - 1);
                cur.append(next);
            } else {
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(next);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /**
     * 行级清洗：断词连字符、词内空格、行内多空格。**保留行首缩进**（段落排版信息）。
     *
     * <p>行尾空白在此裁掉，与 {@code splitRowsWithStarts} 的 {@code stripTrailing()} 同口径。
     */
    static String cleanPdfLine(String raw) {
        return cleanPdfLine(raw, PdfCorpus.EMPTY);
    }

    static String cleanPdfLine(String raw, PdfCorpus corpus) {
        if (raw == null) {
            return "";
        }
        String line = raw.stripTrailing();
        if (line.isEmpty()) {
            return "";
        }
        if (line.indexOf(PDF_UNMAPPED) >= 0 || line.indexOf(PDF_REPLACEMENT) >= 0) {
            StringBuilder sb = new StringBuilder(line.length());
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == PDF_UNMAPPED || c == PDF_REPLACEMENT) {
                    char prev = i > 0 ? line.charAt(i - 1) : ' ';
                    char next = i + 1 < line.length() ? line.charAt(i + 1) : ' ';
                    // 两侧都是字母 ⇒ 这是被吞掉的「行尾连字符」，黏合而不留空格
                    if (!(Character.isLetter(prev) && Character.isLetter(next))) {
                        sb.append(' ');
                    }
                } else {
                    sb.append(c);
                }
            }
            line = sb.toString();
        }
        if (line.indexOf(PDF_SOFT_HYPHEN) >= 0) {
            line = line.replace(String.valueOf(PDF_SOFT_HYPHEN), "");
        }
        line = PDF_SINGLE_LETTER_RUN.matcher(line)
                .replaceAll(m -> collapseLetterSpacedRun(m.group(), corpus));
        int lead = 0;
        while (lead < line.length() && (line.charAt(lead) == ' ' || line.charAt(lead) == '\t')) {
            lead++;
        }
        if (lead < line.length()) {
            line = line.substring(0, lead) + PDF_INNER_SPACES.matcher(line.substring(lead)).replaceAll(" ");
        }
        return line.stripTrailing();
    }

    /**
     * 折叠一个字距串（{@code O F E C O N O M I C S}）：多数情况是一个词被拉开
     * （{@code S O C I E T I E S}），少数是两个词连排。
     *
     * <p>纯文本判不出是哪种，只能问全书语料：
     * <ol>
     *   <li>合并后的串在全书出现过 ⇒ 就是一个词，直接合并（老行为）；</li>
     *   <li>否则若存在**唯一**一处切分，左右两半都在全书出现过、并且**作为相邻
     *       两个词出现过** ⇒ 两词连排，合并并插回空格。</li>
     * </ol>
     *
     * <p>实测《曼昆》第 1 页章节大标题 {@code T E N  P R I N C I P L E S / O F E C O N O M I C S}：
     * 第二行是 {@code OF} + {@code ECONOMICS}，只有 {@code of|economics} 一种切法成立，
     * 且 {@code of economics} 在书里出现 88 次。不做这一步就会得到 {@code OFECONOMICS}，
     * TTS 念成 "ofeconomics"。
     *
     * <p>「相邻出现过」这一条是必要条件，不能省：只用词表的话，{@code Obeying} 会被切成
     * {@code Obey ing}、{@code MONTANA} 会被切成 {@code MONT AN}（详见 {@link #pdfCorpus}）。
     *
     * <p>没有证据（语料为空）或切法不唯一时，一律按老规矩直接合并 —— 宁可不改也不猜。
     */
    private static String collapseLetterSpacedRun(String run, PdfCorpus corpus) {
        String collapsed = run.replace(" ", "");
        if (corpus == null || corpus.words().isEmpty()) {
            return collapsed;
        }
        String lower = collapsed.toLowerCase(Locale.ROOT);
        if (corpus.isWord(lower)) {
            return collapsed;
        }
        int split = uniqueWordSplit(lower, corpus);
        if (split > 0) {
            return collapsed.substring(0, split) + " " + collapsed.substring(split);
        }
        return collapsed;
    }

    /**
     * 在 {@code word} 里找**唯一**一处切分，使左右两半都在语料里、且相邻出现过。
     *
     * @return 切分位置（左右各至少 2 个字母）；无解或多解返回 -1
     */
    private static int uniqueWordSplit(String word, PdfCorpus corpus) {
        int found = -1;
        for (int i = 2; i <= word.length() - 2; i++) {
            String first = word.substring(0, i);
            String second = word.substring(i);
            if (corpus.isWord(first) && corpus.isWord(second) && corpus.areAdjacent(first, second)) {
                if (found > 0) {
                    return -1;
                }
                found = i;
            }
        }
        return found;
    }

    /** 整行只剩装饰字符（图片/logo 占位）⇒ 丢弃 */
    private static boolean isPdfNoiseLine(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (PDF_NOISE_CHARS.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** 行尾 `-`（非 `--`）且下一行首小写 ⇒ 断词续行，合并时去掉连字符 */
    private static boolean isHyphenBreak(CharSequence cur, String next) {
        int n = cur.length();
        if (n < 2 || cur.charAt(n - 1) != '-') {
            return false;
        }
        if (cur.charAt(n - 2) == '-') {
            return false;
        }
        return !next.isEmpty() && Character.isLowerCase(next.charAt(0));
    }

    /**
     * 全大写短行 = 标题/小标题，不与正文合并。
     *
     * <p>要求「行内至少有 2 个 ASCII 字母且全为大写」：中文行没有 ASCII 字母，
     * 若只按 {@code Character.isLowerCase} 判断，整行中文会被当成「全大写」而误判成标题。
     */
    private static boolean isPdfHeadingLine(CharSequence line, int typical) {
        int len = line.length();
        if (len > PDF_HEADING_MAX_LEN || len >= typical) {
            return false;
        }
        int asciiLetters = 0;
        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (c < 'A' || c > 'Z') {
                if (c >= 'a' && c <= 'z') {
                    return false;
                }
                continue;
            }
            asciiLetters++;
        }
        return asciiLetters >= 2;
    }

    /** 合并两行时是否需要补空格：中日韩字符之间不需要，其余需要 */
    private static boolean needsSpaceBetween(CharSequence cur, String next) {
        if (cur.length() == 0 || next.isEmpty()) {
            return false;
        }
        return !(isCjk(cur.charAt(cur.length() - 1)) || isCjk(next.charAt(0)));
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF);
    }

    // ---------- PDF 文本清洗（页眉/页脚、目录点线）----------

    /**
     * 目录点线（dot leaders）：**连续 4 个及以上**的 ASCII 点。
     *
     * <p>取 4 而不是 3 是为了避开英文省略号 {@code ...}（3 个点是有意义的标点）。
     * 实测《中国的官办经济》的 36 条点线长度全在 **79~159** 之间，与阈值之间有巨大安全裕度。
     */
    private static final Pattern PDF_DOT_LEADER = Pattern.compile("\\.{4,}");

    /** 页码里的数字（归一化用：{@code - 12 -} 与 {@code - 47 -} 归一后同形，才能被认出是同一行） */
    private static final Pattern PDF_DIGIT_RUN = Pattern.compile("\\d+");

    /** 判定页眉/页脚所需的最少页数：页数太少时「在多页上重复」没有统计意义，宁可不删 */
    private static final int PDF_MARGIN_MIN_PAGES = 8;

    /** 判定页眉/页脚：同一模式行至少要出现在这个比例的页面上 */
    private static final double PDF_MARGIN_MIN_PAGE_RATIO = 0.6;

    /** 判定页眉/页脚：只在每页**前/后**这么多非空行内查找（正文里偶尔重复的行不会被误删） */
    private static final int PDF_MARGIN_MAX_LINES = 3;

    /**
     * 折叠目录点线：把连续点串换成一个空格。
     *
     * <p>为什么必须做：点线是**排版装饰**，不是内容。留着它有两个直接后果 ——
     * ① 阅读页上一行字拖着一百多个点；② 若切句规则再把点当句末标点，就会炸出成百上千条
     * 「内容是一个点」的片段（线上事故见 {@code TextSegmenter#SENTENCE_END} 注释）。
     * 两处各修一道，这里负责让文本本身干净，切分规则那道负责不再被任何点串带偏。
     *
     * <p>为什么不删干净而留一个空格：目录行是「（一）序言」+ 点线 + 「页码」的视觉布局，
     * 页码列通常已被 PDFTextStripper 拆成另一行；留一个空格只是保住原行的分隔感，
     * 行尾空格随后会被 {@link #splitRows} 的 {@code stripTrailing()} 去掉。
     */
    private static void collapseDotLeaders(List<String> pageTexts) {
        for (int i = 0; i < pageTexts.size(); i++) {
            String pageText = pageTexts.get(i);
            if (pageText == null || pageText.isEmpty() || pageText.indexOf('.') < 0) {
                continue;
            }
            pageTexts.set(i, PDF_DOT_LEADER.matcher(pageText).replaceAll(" "));
        }
    }

    /**
     * 剔除跨页重复出现的**页眉/页脚**（含页码行）。
     *
     * <p>为什么必须做：PDFTextStripper 只做几何版面还原，会把每页的页眉（书名/作者）与页码
     * 当成正文行一并给出。实测《中国的官办经济》94 页里，书名页眉出现 **98 行**、页码行出现
     * **94 行** —— 于是每页都被插进「中国的官办经济 —— 世界上最怪异的经济 作者：陈经」和
     * 「- 35 -」。合成时这些行会被**逐页念出来**（音频里每页夹一遍书名和页码），
     * 阅读页也会穿插这些与正文无关的行。
     *
     * <p>判定规则（三条同时成立才删，刻意保守）：
     * <ol>
     *   <li>页数 ≥ {@link #PDF_MARGIN_MIN_PAGES}（样本太少不做统计）；</li>
     *   <li>该行（**数字归一化后**，故 {@code - 1 -}/{@code - 2 -} 同形）出现在
     *       ≥ {@link #PDF_MARGIN_MIN_PAGE_RATIO} 比例的页面上；</li>
     *   <li>该行位于所在页的**前/后** {@link #PDF_MARGIN_MAX_LINES} 个非空行之内。</li>
     * </ol>
     * 第 3 条是关键护栏：正文里合法地重复出现的行（如每章末尾的「（待续）」、
     * 表格里的表头）不在页面边缘，不会被误删；反过来，页眉页脚恒定在边缘。
     *
     * <p>与 {@link #collapseDotLeaders} 一样就地改写：{@link #pdfPageStartOffsets} 必须看到
     * 同一份页文本，否则页起点与正文再次分叉。
     */
    private static void dropRunningMargins(List<String> pageTexts) {
        int pages = pageTexts.size();
        if (pages < PDF_MARGIN_MIN_PAGES) {
            return;
        }
        Map<String, Integer> pageHits = new HashMap<>();
        for (String pageText : pageTexts) {
            // 同一页内同名行只记一次，避免「一页里重复两遍」把比例抬上去
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String line : marginCandidateLines(pageText)) {
                String key = normalizeMarginLine(line);
                if (!key.isEmpty() && seen.add(key)) {
                    pageHits.merge(key, 1, Integer::sum);
                }
            }
        }
        int threshold = (int) Math.ceil(pages * PDF_MARGIN_MIN_PAGE_RATIO);
        for (int i = 0; i < pages; i++) {
            pageTexts.set(i, filterMarginLines(pageTexts.get(i), pageHits, threshold));
        }
    }

    /** 取一页里「可能是页眉页脚」的行：前/后 {@link #PDF_MARGIN_MAX_LINES} 个非空行 */
    private static List<String> marginCandidateLines(String pageText) {
        if (pageText == null || pageText.isEmpty()) {
            return List.of();
        }
        List<String> lines = List.of(pageText.split("\n", -1));
        List<String> nonBlank = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) {
                nonBlank.add(line);
            }
        }
        List<String> out = new ArrayList<>(PDF_MARGIN_MAX_LINES * 2);
        for (int k = 0; k < nonBlank.size(); k++) {
            if (k < PDF_MARGIN_MAX_LINES || k >= nonBlank.size() - PDF_MARGIN_MAX_LINES) {
                out.add(nonBlank.get(k));
            }
        }
        return out;
    }

    /**
     * 页眉/页脚的比较键：去掉首尾空白 + 把数字串归一成 {@code #}。
     *
     * <p>归一数字是必需的：页码行的「内容」就是数字本身，{@code - 1 -} 与 {@code - 47 -}
     * 若不归一就是两行互不相干的文本，永远攒不够比例、页码行就删不掉。
     */
    private static String normalizeMarginLine(String line) {
        return PDF_DIGIT_RUN.matcher(line.strip()).replaceAll("#");
    }

    /** 在一页文本里删掉命中页眉/页脚模式的行（其余行原样保留，含空行） */
    private static String filterMarginLines(String pageText, Map<String, Integer> pageHits, int threshold) {
        if (pageText == null || pageText.isEmpty()) {
            return pageText == null ? "" : pageText;
        }
        String[] lines = pageText.split("\n", -1);
        List<Integer> nonBlank = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                nonBlank.add(i);
            }
        }
        boolean[] drop = new boolean[lines.length];
        boolean any = false;
        for (int k = 0; k < nonBlank.size(); k++) {
            if (k >= PDF_MARGIN_MAX_LINES && k < nonBlank.size() - PDF_MARGIN_MAX_LINES) {
                continue;
            }
            int idx = nonBlank.get(k);
            Integer hits = pageHits.get(normalizeMarginLine(lines[idx]));
            if (hits != null && hits >= threshold) {
                drop[idx] = true;
                any = true;
            }
        }
        if (!any) {
            return pageText;
        }
        StringBuilder sb = new StringBuilder(pageText.length());
        for (int i = 0; i < lines.length; i++) {
            if (!drop[i]) {
                sb.append(lines[i]).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 页文本的拼接规则：**非空**页各追加一个 {@code '\n'}，最后对整体 {@code trim()}。
     *
     * <p>刻意抽成与 {@link #pdfPageStartOffsets} 成对的静态方法：目录的页起点必须与正文
     * **同一坐标系**，此前两处各写一份累加，于是必然漂移 —— PDF 目录正是漏掉了这里
     * 每页追加的 {@code '\n'}，页起点比真实下标小、目录映射出的段落下标偏大。
     * 这是与服务端段落坐标（`detail#B5`）同一类缺陷，只是每页固定少 1 个字符。
     */
    static String joinPdfPages(List<String> pageTexts) {
        StringBuilder sb = new StringBuilder();
        for (String pageText : pageTexts) {
            if (pageText != null && !pageText.isEmpty()) {
                sb.append(pageText).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * URL 解码，**遇非法 `%` 转义时按原样返回**，绝不抛 {@link IllegalArgumentException}。
     *
     * <p><b>为什么需要它（v5 / S6）</b>：EPUB 的 {@code href} 是手工拼的，出现非法 `%`
     * （如 {@code "ch1%zz.xhtml"}）并不罕见。{@code java.net.URLDecoder.decode} 对此抛
     * {@code IllegalArgumentException} —— 而它逃出 {@code parseOpfSpine}/{@code parseManifest}
     * 之后，最终由 {@code GlobalExceptionHandler} 兜成 400，客户端拿到的是
     * `URLDecoder: Illegal hex characters in escape (%) pattern - Error at index 5 in: "zz"` 这种
     * **JDK 内部技术文案**，用户看不懂、也无法据此操作。
     *
     * <p><b>为什么不直接抛友好异常</b>：这类 href 多数只是**编码不严谨**，原样保留往往仍能
     * 在 zip 里找到对应条目（文件名的真实字节就是未编码的）。直接拒绝会为一颗坏字符
     * 让整本书解析失败 —— 按原样使用是更宽松、也更正确的降级。
     *
     * <p>与 {@code BookCoverExtractor.decodeUrl} 同口径（那边早有这个保护）；
     * 这里抽成一个方法是为了让三处调用点（manifest / spine / nav）都不再各自裸调 URLDecoder。
     */
    static String safeUrlDecode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.debug("href 含非法 % 转义，按原样使用: {}", value);
            return value;
        }
    }

    /**
     * 每页（0-based）在 {@link #joinPdfPages} 结果中的起始下标。
     *
     * <p>两处细节必须与 {@link #joinPdfPages} 严丝合缝，否则又是「不同源」：
     * <ul>
     *   <li>非空页要算上追加的 {@code '\n'}（空页不占位，与后一页同起点）；</li>
     *   <li>整体 {@code trim()} 会砍掉首个非空白字符之前的前导空白，所有下标要左移同样的量。</li>
     * </ul>
     */
    static int[] pdfPageStartOffsets(List<String> pageTexts) {
        int[] offsets = new int[pageTexts.size()];
        int cumulative = 0;
        int lead = -1; // 首个非空白字符在「尚未 trim」的拼接结果中的位置
        for (int i = 0; i < pageTexts.size(); i++) {
            offsets[i] = cumulative;
            String pageText = pageTexts.get(i);
            if (pageText == null || pageText.isEmpty()) {
                continue;
            }
            if (lead < 0) {
                for (int k = 0; k < pageText.length(); k++) {
                    if (pageText.charAt(k) > ' ') {
                        lead = cumulative + k;
                        break;
                    }
                }
            }
            cumulative += pageText.length() + 1;
        }
        int shift = Math.max(lead, 0);
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = Math.max(0, offsets[i] - shift);
        }
        return offsets;
    }

    /**
     * 提取 PDF 目录书签。
     * 使用 PDFBox 的 DocumentOutline（书签树），将书签标题映射到其在全文中的字符偏移。
     *
     * <p>页起点与正文**共用** {@link #stripPdfPages} + {@link #pdfPageStartOffsets}，
     * 保证两者处于同一坐标系（早期此处自己另写一份累加，漏掉每页追加的 {@code '\n'}，
     * 导致目录映射出的段落下标偏大 —— 同一个 `detail#B5`）。
     *
     * <p>书签只给出**页码**，所以偏移还要在那一页的范围内按标题再定位一次（见
     * {@link #locateHeadingInPage}）：页码粒度只能保证「跳到那一页」，落点是页首，
     * 而页首往往是上一页续下来的句子 —— 映射成段落后会再往前提几行。
     * 页内定位把落点收敛到章节标题本身。
     */
    public List<TocEntry> extractPdfToc(File file) throws IOException {
        List<TocEntry> toc = new ArrayList<>();

        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(file)) {
            List<String> pageTexts = stripPdfPages(document);
            int[] pageStartOffset = pdfPageStartOffsets(pageTexts);
            // 与 extractPdf 同一份拼接（joinPdfPages），否则页内搜索的坐标系又是另一套
            String fullText = joinPdfPages(pageTexts);

            PDDocumentCatalog catalog = document.getDocumentCatalog();
            PDOutlineNode outline = catalog.getDocumentOutline();
            if (outline != null && outline.hasChildren()) {
                flattenPdfOutline(outline, toc, pageStartOffset, document, fullText);
            }
        }

        log.info("PDF 目录提取完成，共 {} 条目", toc.size());
        return toc;
    }

    /**
     * 递归展平 PDF 书签树为 TocEntry 列表。
     * PDFBox 3.x 使用 getAction() 获取跳转目标（getDestination() 已移除）。
     */
    private void flattenPdfOutline(PDOutlineNode node, List<TocEntry> toc,
                                   int[] pageStartOffset, PDDocument document, String fullText)
            throws IOException {
        PDOutlineItem item = node.getFirstChild();
        while (item != null) {
            String title = item.getTitle();
            if (title != null && !title.isBlank()) {
                String cleanTitle = title.trim();
                int pageIndex = resolveOutlineItemPage(item, document);
                int pageStart = (pageIndex >= 0 && pageIndex < pageStartOffset.length)
                        ? pageStartOffset[pageIndex] : 0;
                // 页内再定位：落点从「页首」收敛到「章节标题」。搜不到就退回页首 ——
                // 标题可能是图片、或与书签里的写法不一致，此时页首仍是「同一页」的合理近似。
                int charOffset = pageStart;
                if (pageIndex >= 0 && pageIndex < pageStartOffset.length) {
                    int pageEnd = (pageIndex + 1 < pageStartOffset.length)
                            ? pageStartOffset[pageIndex + 1] : fullText.length();
                    int exact = locateHeadingInPage(fullText, cleanTitle, pageStart, pageEnd);
                    if (exact >= 0) {
                        charOffset = exact;
                    }
                }
                toc.add(new TocEntry(cleanTitle, charOffset, pageIndex + 1));
            }
            if (item.hasChildren()) {
                flattenPdfOutline(item, toc, pageStartOffset, document, fullText);
            }
            item = item.getNextSibling();
        }
    }

    /**
     * 在 {@code [from, to)} 这一段（= 书签指向的那一页）里按标题定位，返回标题首字下标；未命中 -1。
     *
     * <p><b>为什么要限定在页内</b>：书名页前的**印刷目录页**里也有同样的标题，
     * 全书范围内搜必然先命中目录页那一处，落点反而跑到书的最前面（实测第一条书签
     * 「（一）序言」就落成了全书第 0 行）。书签本来就说清了在第几页，只在该页内找，
     * 既快又不可能命中别处的同名文字。
     *
     * <p>用 {@link #locateIgnoringBlank} 而不是 {@code indexOf}：PDFTextStripper 会在
     * 中文之间插空格、标题也可能跨行折行，逐字严格相等会大量漏配。
     */
    private static int locateHeadingInPage(String text, String title, int from, int to) {
        if (text == null || title == null || title.isEmpty()) {
            return -1;
        }
        int limit = Math.min(to, text.length());
        for (int start = Math.max(from, 0); start < limit; start++) {
            if (text.charAt(start) <= ' ') {
                continue; // 起点必须是非空白字符（与 locateIgnoringBlank 同一约定）
            }
            int[] span = locateIgnoringBlank(text, title, start);
            if (span != null && span[0] >= from && span[0] < limit) {
                return span[0];
            }
        }
        return -1;
    }

    /**
     * 解析书签项对应的页码（0-based），失败返回 -1。
     *
     * <p><b>必须同时走两条路</b> —— PDFBox 的两个 getter 各只认一种写法：
     * <ul>
     *   <li>{@code item.getDestination()} —— <b>只读 {@code /Dest} 键</b>。
     *       实测真实 PDF《无穷的开始英文原版》（401 页，9 个书签）全部用
     *       {@code /Dest [ 3 0 R /XYZ … ]} 直数组形式、字典里<b>没有 {@code /A} 键</b>
     *       ⇒ {@code getAction()} 返回 {@code null}，
     *       只认 {@code getAction()} 的旧实现<b>每个条目都返回 -1</b>
     *       ⇒ 所有 {@code charOffset}/{@code pageNumber} 落成 0
     *       ⇒ 服务端返回的目录「所有条目 paragraphIndex 都是 0」
     *       ⇒ 客户端点任何章节都跳到书的最开头；</li>
     *   <li>{@code item.getAction()} —— <b>只读 {@code /A} 键</b>。
     *       由 Word 导出的书签（{@code /A << /S /GoTo /D [...] >>}）只有这一条路，
     *       走 {@code getDestination()} 会读不到（实测恒为 {@code null}）。</li>
     * </ul>
     * 两条都取到就以 {@code /Dest} 优先（更直接），否则回落 {@code /A}。
     * 改用 {@code getDestination()} 后，真实 PDF 的 9 个条目全部解析出正确页码
     * （1 / 2 / 3 / 5 / 6 / 7 / 313 / 315 / 393）。
     *
     * <p><b>只认 {@code PDPageDestination.getPage()}，不要用 {@code getPageNumber()}</b>：
     * {@code getPageNumber()} 恒返回 <b>-1</b>，而 {@code getPage()} 能正确解析出 {@code PDPage}。
     * {@code getPageNumber()} 仅作兜底（部分 PDF 只写页号、没请求页对象）。
     *
     * <p>这里曾用反射依次尝试 {@code getTargetPage(PDDocument)} 与
     * {@code getRetrievePageNumber()} —— <b>这两个方法在 PDFBox 3.0.2 里都不存在</b>，
     * 反射抛出被 {@code log.debug} 吞掉，方法静默返回 -1，于是
     * {@link #flattenPdfOutline} 里每个条目的 {@code charOffset} 都落成 0：
     * 表现为「点目录永远跳到书的最开头」，而日志一片安静。
     * 反射是这次事故的放大器（拼错方法名 = 没有编译期保护 + 静默降级），
     * 因此改为直接调用存在的方法，让编译期看着它。
     */
    private int resolveOutlineItemPage(PDOutlineItem item, PDDocument document) {
        try {
            // 两条路都要走 —— PDFBox 的 getDestination() **只读 /Dest 键**，
            // 读不到 /A GoTo 动作里的目标；而 getAction() **只读 /A 键**，
            // 读不到 /Dest 直数组。任一条单独用都会漏掉一半的书签写法。
            PDPageDestination dest = null;
            if (item.getDestination() instanceof PDPageDestination direct) {
                dest = direct;
            } else if (item.getAction() instanceof PDActionGoTo gotoAction
                    && gotoAction.getDestination() instanceof PDPageDestination viaAction) {
                dest = viaAction;
            }
            if (dest != null) {
                PDPage page = dest.getPage();
                if (page != null) {
                    int index = document.getPages().indexOf(page);
                    if (index >= 0) {
                        return index;
                    }
                }
                // 兜底：部分 PDF 的书签只写了页号、没请求页对象
                int number = dest.getPageNumber();
                if (number > 0) {
                    return number - 1;
                }
            }
        } catch (Exception e) {
            log.debug("解析书签页码失败: {}", e.getMessage());
        }
        return -1;
    }

    // ---------- EPUB ----------

    /**
     * 从 EPUB（ZIP 文件）中读取所有非目录条目。
     *
     * 优先使用 ZipFile（随机访问，读取中央目录），避免 ZipInputStream
     * 对部分工具生成的 EPUB 中 data descriptor 条目的压缩大小校验失败问题。
     * 如 ZipFile 也失败，则回退到 ZipInputStream 并忽略单个条目读取错误。
     */
    Map<String, byte[]> readZipEntries(File file) throws IOException {
        Map<String, byte[]> zipEntries = new HashMap<>();
        long[] totalBytes = {0};
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(file)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    checkEntryCount(zipEntries.size());
                    try (java.io.InputStream is = zf.getInputStream(entry)) {
                        zipEntries.put(entry.getName().toLowerCase(java.util.Locale.ROOT),
                                readEntryCapped(is, entry.getName(), totalBytes));
                    }
                }
            }
        } catch (java.util.zip.ZipException ze) {
            // 极少数情况下 ZipFile 也无法解析，回退到 ZipInputStream 并容忍校验错误
            log.warn("ZipFile 解析失败，回退到 ZipInputStream: {}", ze.getMessage());
            zipEntries.clear();
            totalBytes[0] = 0;
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        checkEntryCount(zipEntries.size());
                        try {
                            zipEntries.put(entry.getName().toLowerCase(java.util.Locale.ROOT),
                                    readEntryCapped(zis, entry.getName(), totalBytes));
                        } catch (java.util.zip.ZipException ignore) {
                            log.warn("跳过无法读取的条目: {}", entry.getName());
                            continue;
                        }
                    }
                    zis.closeEntry();
                }
            }
        }
        return zipEntries;
    }

    private void checkEntryCount(int current) throws IOException {
        if (current >= MAX_ZIP_ENTRIES) {
            throw new IOException("EPUB 内部文件数超过上限（" + MAX_ZIP_ENTRIES + " 个），拒绝解析");
        }
    }

    /**
     * 流式读取单个 ZIP 条目，边读边校验体积上限；超限立即抛错释放缓冲，
     * 绝不先把整个炸弹条目读进堆再判断。
     */
    private byte[] readEntryCapped(java.io.InputStream is, String entryName, long[] totalBytes)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        long entryBytes = 0;
        while ((len = is.read(buffer)) > 0) {
            entryBytes += len;
            if (entryBytes > MAX_ZIP_ENTRY_BYTES) {
                throw new IOException("EPUB 内部文件解压后过大（>" + (MAX_ZIP_ENTRY_BYTES / 1024 / 1024)
                        + "MB），疑似异常压缩包，拒绝解析: " + entryName);
            }
            if (totalBytes[0] + len > MAX_ZIP_TOTAL_BYTES) {
                throw new IOException("EPUB 解压后总内容超过上限（" + (MAX_ZIP_TOTAL_BYTES / 1024 / 1024)
                        + "MB），疑似异常压缩包，拒绝解析");
            }
            totalBytes[0] += len;
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    /**
     * EPUB 本质是一个 ZIP 文件，内部包含：
     *   1. META-INF/container.xml —— 指向 OPF 文件路径
     *   2. OPF 文件 —— manifest 列出所有资源，spine 定义阅读顺序
     *   3. XHTML 内容文件 —— 按 spine 顺序读取并提取纯文本
     *
     * 此方法完全使用 Java 内置库 (java.util.zip + javax.xml.parsers)，不依赖第三方库。
     */
    public String extractEpub(File file) throws IOException {
        // 1. 读取 ZIP 中所有条目
        Map<String, byte[]> zipEntries = readZipEntries(file);

        log.info("EPUB ZIP 包含 {} 个文件", zipEntries.size());

        // 2. 解析 container.xml 找到 OPF 路径
        byte[] containerData = getZipEntry(zipEntries, "META-INF/container.xml");
        if (containerData == null) {
            throw new IOException("EPUB 缺少 META-INF/container.xml");
        }
        String opfPath = parseContainerXml(containerData);
        log.info("EPUB OPF 路径: {}", opfPath);

        // 3. 读取并解析 OPF 文件
        byte[] opfData = getZipEntry(zipEntries, opfPath);
        if (opfData == null) {
            throw new IOException("EPUB 缺少 OPF 文件: " + opfPath);
        }
        // OPF 所在目录（用于解析相对路径）
        String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        List<String> spineHrefs = parseOpfSpine(opfData, opfDir);
        log.info("EPUB spine 包含 {} 个章节", spineHrefs.size());

        // 4. 按 spine 顺序读取 XHTML 并提取文本
        StringBuilder sb = new StringBuilder();
        for (String href : spineHrefs) {
            byte[] xhtmlData = getZipEntry(zipEntries, href);
            if (xhtmlData == null) {
                log.warn("EPUB spine 引用的文件不存在: {}", href);
                continue;
            }
            String htmlContent = new String(xhtmlData, StandardCharsets.UTF_8);
            String plainText = stripHtmlTags(htmlContent);
            if (!plainText.isEmpty()) {
                sb.append(plainText).append('\n');
            }
        }

        String text = sb.toString().trim();
        if (text.isEmpty()) {
            throw new IOException("EPUB 文件未提取到文本内容");
        }
        log.info("EPUB 文本提取完成，共 {} 字符", text.length());
        return text;
    }

    /**
     * 解析 META-INF/container.xml，提取 OPF 文件的 full-path。
     */
    String parseContainerXml(byte[] data) throws IOException {
        try {
            DocumentBuilder builder = createSafeDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(stripDoctype(data)));

            // container.xml 结构：<container><rootfiles><rootfile full-path="OEBPS/content.opf" .../></rootfiles></container>
            NodeList rootFiles = doc.getElementsByTagName("rootfile");
            if (rootFiles.getLength() > 0) {
                String fullPath = ((Element) rootFiles.item(0)).getAttribute("full-path");
                if (fullPath != null && !fullPath.isEmpty()) {
                    return fullPath;
                }
            }
            // 默认值
            return "OEBPS/content.opf";
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("解析 EPUB container.xml 失败", e);
        }
    }

    /**
     * 解析 OPF 文件，按 spine 顺序返回各章节的完整路径列表。
     *
     * @param opfData  OPF 文件内容
     * @param opfDir   OPF 文件所在目录（用于拼接相对路径）
     * @return 按 spine 顺序排列的 ZIP 内文件路径列表
     */
    List<String> parseOpfSpine(byte[] opfData, String opfDir) throws IOException {
        List<String> spineHrefs = new ArrayList<>();
        try {
            DocumentBuilder builder = createSafeDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(stripDoctype(opfData)));

            // 构建 manifest 映射：id → href（完整路径）
            Map<String, String> manifest = new HashMap<>();
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String id = item.getAttribute("id");
                String href = item.getAttribute("href");
                if (id != null && href != null && !id.isEmpty() && !href.isEmpty()) {
                    // URL 解码（部分 EPUB 的 href 含编码字符）
                    href = safeUrlDecode(href);
                    manifest.put(id, normalizeZipPath(opfDir + href));
                }
            }

            // 读取 spine 中的 itemref 顺序
            NodeList itemrefs = doc.getElementsByTagName("itemref");
            for (int i = 0; i < itemrefs.getLength(); i++) {
                Element itemref = (Element) itemrefs.item(i);
                String idref = itemref.getAttribute("idref");
                if (idref != null && manifest.containsKey(idref)) {
                    spineHrefs.add(manifest.get(idref));
                }
            }

            // Fallback: 如果 spine 为空，使用 manifest 中所有 XHTML/HTML 项
            if (spineHrefs.isEmpty()) {
                log.warn("EPUB spine 为空，使用 manifest 中所有 HTML 项作为 fallback");
                for (Map.Entry<String, String> e : manifest.entrySet()) {
                    String href = e.getValue();
                    if (href.endsWith(".xhtml") || href.endsWith(".html") || href.endsWith(".htm")) {
                        spineHrefs.add(href);
                    }
                }
            }

        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("解析 EPUB OPF 文件失败", e);
        }
        return spineHrefs;
    }

    /**
     * 提取 EPUB 目录。
     * 依次尝试 EPUB 2 的 NCX 文件和 EPUB 3 的 nav 元素，
     * 将章节标题映射到其在全文中的字符偏移。
     *
     * @param file  EPUB 文件（ZIP 格式）
     * @return 目录条目列表，按在原文中出现的顺序排列
     */
    public List<TocEntry> extractEpubToc(File file) throws IOException {
        // 读取 ZIP 中所有条目（使用 ZipFile 而非 ZipInputStream，原因同 extractEpub）
        Map<String, byte[]> zipEntries = readZipEntries(file);

        // 解析 container.xml 找到 OPF 路径
        byte[] containerData = getZipEntry(zipEntries, "META-INF/container.xml");
        if (containerData == null) return List.of();
        String opfPath = parseContainerXml(containerData);
        String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

        byte[] opfData = getZipEntry(zipEntries, opfPath);
        if (opfData == null) return List.of();

        // 解析 manifest：id → href（完整路径）
        Map<String, String> manifest = new HashMap<>();
        try {
            DocumentBuilder builder = createSafeDocumentBuilder();
            Document opfDoc = builder.parse(new ByteArrayInputStream(stripDoctype(opfData)));
            NodeList items = opfDoc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String id = item.getAttribute("id");
                String href = item.getAttribute("href");
                if (id != null && href != null && !id.isEmpty() && !href.isEmpty()) {
                    href = safeUrlDecode(href);
                    manifest.put(id, normalizeZipPath(opfDir + href));
                }
            }
        } catch (Exception e) {
            log.warn("解析 OPF manifest 失败: {}", e.getMessage());
        }

        // 获取 spine 顺序
        List<String> spineHrefs = parseOpfSpine(opfData, opfDir);

        // 累计每个 spine 文件对应的字符偏移（按实际读取纯文本后的字符数）。
        // 必须与 extractEpub 的拼接口径逐字对齐：那边**空文本不占位**（if (!plainText.isEmpty())
        // 才 append），这边若照旧无条件 +1，每遇一个空章节（封面图页、分隔页）后续偏移就整体
        // 后移 1 个字符 —— 单章无感，几十章后足以跨过一个段落边界，表现为「目录跳到上一段」。
        int[] spineCharOffsets = new int[spineHrefs.size() + 1];
        int cumulative = 0;
        for (int i = 0; i < spineHrefs.size(); i++) {
            spineCharOffsets[i] = cumulative;
            byte[] xhtmlData = getZipEntry(zipEntries, spineHrefs.get(i));
            if (xhtmlData != null) {
                String plainText = stripHtmlTags(new String(xhtmlData, StandardCharsets.UTF_8));
                if (!plainText.isEmpty()) {
                    cumulative += plainText.length() + 1; // +1 为章节间换行符
                }
            }
        }

        // 方式 1：找 NCX 文件（EPUB 2）
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            String href = e.getValue();
            if (href.endsWith(".ncx")) {
                byte[] ncxData = getZipEntry(zipEntries, href);
                if (ncxData != null) {
                    // NCX 里的 src 是**相对 NCX 文件自身**的路径，与 spine 的 opfDir 基准不同
                    String ncxDir = href.contains("/") ? href.substring(0, href.lastIndexOf('/') + 1) : "";
                    List<TocEntry> toc = parseNcxToc(ncxData, spineHrefs, spineCharOffsets,
                            opfDir, ncxDir, zipEntries);
                    if (!toc.isEmpty()) {
                        log.info("EPUB 从 NCX 文件提取到 {} 条目录", toc.size());
                        return toc;
                    }
                }
            }
        }

        // 方式 2：EPUB 3 nav 元素（遍历 manifest 找 properties="nav"）
        try {
            DocumentBuilder builder = createSafeDocumentBuilder();
            Document opfDoc = builder.parse(new ByteArrayInputStream(stripDoctype(opfData)));
            NodeList allItems = opfDoc.getElementsByTagName("item");
            for (int i = 0; i < allItems.getLength(); i++) {
                Element item = (Element) allItems.item(i);
                String itemHrefAttr = item.getAttribute("href");
                if (itemHrefAttr == null) continue;
                itemHrefAttr = safeUrlDecode(itemHrefAttr);
                String fullPath = normalizeZipPath(opfDir + itemHrefAttr);
                String properties = item.getAttribute("properties");
                if (properties != null && properties.contains("nav")) {
                    byte[] navData = getZipEntry(zipEntries, fullPath);
                    if (navData != null) {
                        // nav 文档里的 href 同样相对 nav 文件自身，不是相对 OPF
                        String navDir = fullPath.contains("/")
                                ? fullPath.substring(0, fullPath.lastIndexOf('/') + 1) : "";
                        List<TocEntry> toc = parseEpubNavToc(navData, spineHrefs, spineCharOffsets,
                                opfDir, navDir, zipEntries);
                        log.info("EPUB 从 nav 元素提取到 {} 条目录", toc.size());
                        return toc;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 EPUB 3 nav 元素失败: {}", e.getMessage());
        }

        return List.of();
    }

    /** 从 NCX 文件解析目录（EPUB 2） */
    private List<TocEntry> parseNcxToc(byte[] ncxData, List<String> spineHrefs,
                                       int[] spineCharOffsets, String opfDir,
                                       String ncxDir, Map<String, byte[]> zipEntries) {
        List<TocEntry> toc = new ArrayList<>();
        try {
            DocumentBuilder builder = createSafeDocumentBuilder();
            // NCX 标准模板带 DOCTYPE，而 createSafeDocumentBuilder 禁止 DOCTYPE，
            // 不摘掉会稳定抛 SAXParseException → 目录恒为空（详见 stripDoctype 注释）
            Document doc = builder.parse(new ByteArrayInputStream(stripDoctype(ncxData)));
            // 从根元素的 navMap 子元素开始遍历
            NodeList navMaps = doc.getElementsByTagName("navMap");
            if (navMaps.getLength() == 0) {
                log.warn("NCX 中未找到 navMap 节点，无法提取 EPUB 目录");
                return toc;
            }
            traverseNcxNavPoint((Element) navMaps.item(0), toc, spineHrefs, spineCharOffsets,
                    opfDir, ncxDir, zipEntries);
        } catch (Exception e) {
            log.warn("解析 NCX 失败: {}", e.getMessage());
        }
        return toc;
    }

    /** 递归遍历 NCX navPoint 节点，将章节标题映射到 charOffset */
    private void traverseNcxNavPoint(Element element, List<TocEntry> toc,
                                     List<String> spineHrefs, int[] spineCharOffsets,
                                     String opfDir, String ncxDir,
                                     Map<String, byte[]> zipEntries) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) continue;
            String tag = child.getTagName();
            if ("navPoint".equals(tag)) {
                // 取 navLabel/text 作为标题
                NodeList labels = child.getElementsByTagName("navLabel");
                NodeList contents = child.getElementsByTagName("content");
                String title = "";
                String src = "";
                if (labels.getLength() > 0) {
                    NodeList texts = ((Element) labels.item(0)).getElementsByTagName("text");
                    if (texts.getLength() > 0 && texts.item(0).getTextContent() != null) {
                        title = texts.item(0).getTextContent().trim();
                    }
                }
                if (contents.getLength() > 0) {
                    src = ((Element) contents.item(0)).getAttribute("src");
                }

                if (!title.isEmpty()) {
                    toc.add(new TocEntry(title, resolveTocCharOffset(src, spineHrefs, spineCharOffsets,
                            opfDir, ncxDir, zipEntries)));
                }

                // 递归处理子 navPoint（只在 navMap/navPoint 下递归，不重复处理当前节点自身的子 navPoint）
                traverseNcxNavPoint(child, toc, spineHrefs, spineCharOffsets,
                        opfDir, ncxDir, zipEntries);
            }
        }
    }

    /**
     * 把目录条目的 {@code src}（如 {@code text00002.html#nav_point_5}）换算成
     * **全文纯文本**里的字符偏移。
     *
     * <p>这里连着修了三个各自独立、后果一样的缺陷（生产真实事故：书 48《Linux 命令行大全》
     * 目录 457 条全部只跳到第 1 段）：
     * <ol>
     *   <li><b>路径基准不同</b>：{@code src} 相对 NCX/nav 文件自身，而 {@code spineHrefs}
     *       是「相对 OPF 目录」拼接后的 ZIP 全路径。OPF 位于子目录时（几乎所有的书都是
     *       {@code OEBPS/content.opf}），{@code spineHrefs.indexOf(src)} 恒为 {@code -1}
     *       → {@code charOffset} 全为 0 → 全部条目映射到第 1 段，点哪都跳回开头。</li>
     *   <li><b>忽略 #fragment</b>：同一章文件里的多个小节（{@code 36.4.2 使用命名管道}）
     *       会共用同一个文件偏移，即「小节点了不动」。改为按锚点在该文件纯文本里的位置精确定位。</li>
     *   <li><b>找不到时不要静默 0</b>：0 是「正文第一段」这个合法位置，一旦解析失败就伪装成
     *       一次成功跳转到开头。改为退回本章起点（有章节信息至少能跳对章），并记 warn 便于排查。</li>
     * </ol>
     *
     * @param src            目录条目原文的 src/href（可含 {@code #fragment}）
     * @param baseDirs       候选基准目录，按优先级排列（NCX/nav 所在目录 → OPF 所在目录）
     * @return 全文纯文本中的字符偏移；无法定位时为 0（与既有兜底一致）
     */
    private int resolveTocCharOffset(String src, List<String> spineHrefs, int[] spineCharOffsets,
                                     String opfDir, String baseDir,
                                     Map<String, byte[]> zipEntries) {
        if (src == null || src.isEmpty()) return 0;
        int hash = src.indexOf('#');
        String srcBase = hash >= 0 ? src.substring(0, hash) : src;
        String fragment = hash >= 0 ? src.substring(hash + 1) : "";

        int idx = resolveSpineIndex(spineHrefs, srcBase, baseDir, opfDir);
        if (idx < 0 || idx >= spineCharOffsets.length) {
            log.warn("EPUB 目录项定位失败，src={} 未匹配到 spine，退回正文起点", src);
            return 0;
        }
        int base = spineCharOffsets[idx];
        if (fragment.isEmpty()) {
            return base;
        }
        byte[] xhtmlData = getZipEntry(zipEntries, spineHrefs.get(idx));
        int inFile = anchorOffsetInFile(xhtmlData, fragment);
        if (inFile < 0) {
            // 锚点缺失（不少 EPUB 的 fragment 指向不存在的 id）：至少跳到本章开头
            return base;
        }
        return base + inFile;
    }

    /**
     * 把目录里的相对路径解析成 spine 下标。
     *
     * <p>候选顺序：各基准目录拼接结果 → 原样 → 后缀唯一匹配。后缀兜底只接受**唯一**命中，
     * 命中多个说明同名文件分处不同目录，此时宁可返回 -1（退回章节/正文起点）也不能赌一个。
     */
    private int resolveSpineIndex(List<String> spineHrefs, String src, String... baseDirs) {
        List<String> candidates = new ArrayList<>();
        for (String base : baseDirs) {
            if (base != null) {
                candidates.add(normalizeZipPath(base + src));
            }
        }
        candidates.add(normalizeZipPath(src));

        for (String candidate : candidates) {
            int idx = spineHrefs.indexOf(candidate);
            if (idx >= 0) return idx;
        }
        // 后缀兜底：src 里可能带 ../ 前缀或与 zip 条目大小写路径表述不同
        for (String candidate : candidates) {
            if (candidate.isEmpty()) continue;
            int found = -1;
            boolean ambiguous = false;
            for (int i = 0; i < spineHrefs.size(); i++) {
                String href = spineHrefs.get(i);
                if (href.equals(candidate) || href.endsWith("/" + candidate)) {
                    if (found >= 0) {
                        ambiguous = true;
                        break;
                    }
                    found = i;
                }
            }
            if (found >= 0 && !ambiguous) return found;
        }
        return -1;
    }

    /**
     * 锚点（{@code id=}/{@code name=}）在该文件**纯文本**里的字符下标；找不到返回 -1。
     *
     * <p>实现用「哨兵法」：把哨兵插到锚点元素开标签的 {@code >} 之后，再整体跑一次
     * {@link #stripHtmlTags}，哨兵在结果中的位置就是精确下标。
     * <b>不能</b>改成「对前缀单独 stripHtmlTags 再取长度」——该方法带 {@code trim()} 与
     * {@code [ \t]+}、{@code \n{3,}} 这类全局归一化，前缀结果与整体结果并不相等
     * （本类反复踩到的「两套坐标」陷阱，见 {@link #extractMobiToc} 注释）。
     */
    int anchorOffsetInFile(byte[] xhtmlData, String fragment) {
        if (xhtmlData == null || fragment == null || fragment.isEmpty()) return -1;
        String html = new String(xhtmlData, StandardCharsets.UTF_8);
        String[][] patterns = {
                {"id=\"" + fragment + "\"", "id='" + fragment + "'"},
                {"name=\"" + fragment + "\"", "name='" + fragment + "'"},
        };
        int attrPos = -1;
        for (String[] group : patterns) {
            for (String p : group) {
                int at = html.indexOf(p);
                if (at >= 0) {
                    attrPos = at;
                    break;
                }
            }
            if (attrPos >= 0) break;
        }
        if (attrPos < 0) return -1;
        int gt = html.indexOf('>', attrPos);
        if (gt < 0) return -1;

        String marked = html.substring(0, gt + 1) + ANCHOR_SENTINEL + html.substring(gt + 1);
        String plain = stripHtmlTags(marked);
        int plainPos = plain.indexOf(ANCHOR_SENTINEL);
        return plainPos < 0 ? -1 : plainPos;
    }

    /** 锚点定位用的哨兵：不会被 stripHtmlTags 的标签/空白归一化吃掉 */
    private static final char ANCHOR_SENTINEL = '\u0001';

    /** 从 EPUB 3 nav 元素解析目录 */
    private List<TocEntry> parseEpubNavToc(byte[] navData, List<String> spineHrefs,
                                           int[] spineCharOffsets, String opfDir,
                                           String navDir, Map<String, byte[]> zipEntries) {
        List<TocEntry> toc = new ArrayList<>();
        try {
            String html = new String(navData, StandardCharsets.UTF_8);

            // 匹配 <nav epub:type="toc">...</nav>
            java.util.regex.Pattern navPattern = java.util.regex.Pattern.compile(
                "(?is)<nav[^>]*epub:type=[\"']toc[\"'][^>]*>(.*?)</nav>", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher navMatcher = navPattern.matcher(html);
            if (!navMatcher.find()) return toc;

            String navContent = navMatcher.group(1);
            // 匹配 <a href="...">标题文本</a>
            java.util.regex.Pattern linkPattern = java.util.regex.Pattern.compile(
                "(?is)<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher linkMatcher = linkPattern.matcher(navContent);

            while (linkMatcher.find()) {
                String href = linkMatcher.group(1);
                String rawText = linkMatcher.group(2);
                // 去除子元素标签得到纯文本
                String title = rawText.replaceAll("(?s)<[^>]+>", "").trim();
                if (title.isEmpty()) continue;

                // 把 href 映射到 spine 中的 charOffset（含 #fragment 精确定位，见 resolveTocCharOffset）
                toc.add(new TocEntry(title, resolveTocCharOffset(href, spineHrefs, spineCharOffsets,
                        opfDir, navDir, zipEntries)));
            }
        } catch (Exception e) {
            log.warn("解析 nav 元素失败: {}", e.getMessage());
        }
        return toc;
    }

    // ---------- MOBI / AZW ----------

    /**
     * MOBI 格式基于 Palm Database (PDB) 容器，文本以 PalmDOC LZ77 压缩存放。
     * 此处实现一个精简但完整的解析器，不依赖任何第三方库。
     */
    public String extractMobi(File file) throws IOException {
        // 整本电子书一次性读入堆，受上传上限 ≤50MB 约束（文本提取固有行为，非流式）
        byte[] raw = Files.readAllBytes(file.toPath());
        // 复用同一套（已加固的）解压逻辑，避免与 extractMobiToc 重复维护两份易碎代码
        byte[] decompressed = decompressMobiText(raw);
        if (decompressed == null || decompressed.length == 0) {
            throw new IOException("MOBI 文件解压失败或提取不到文本内容（文件可能已损坏或非标准格式）");
        }
        String htmlContent = new String(decompressed, StandardCharsets.UTF_8);
        String text = stripHtmlTags(htmlContent);
        if (text.isEmpty()) {
            throw new IOException("MOBI 文件未提取到文本内容");
        }
        log.info("MOBI 文本提取完成，共 {} 字符", text.length());
        return text;
    }

    /**
     * 提取 MOBI 目录。
     *
     * <p>MOBI 正文是 {@code stripHtmlTags(html)} 的结果，所以目录的 charOffset **必须**是
     * 纯文本里的下标。早期这里直接拿 {@code matcher.start()}（**HTML 里的下标**）——
     * 两个坐标系毫无关系（HTML 里每个标签都占字符，纯文本里已被替换/删除），
     * HTML 下标几乎总是大于纯文本总长，映射结果是「点任何目录都跳到最后」。
     *
     * <p>换算方式：先用与 {@link #extractMobi} 完全相同的 {@link #stripHtmlTags} 得到纯文本，
     * 再按标题**顺序**在纯文本里定位（{@link #searchIgnoringBlank}，忽略空白差异）。
     * 不能改成「对 HTML 的各段前缀分别调 stripHtmlTags 再累加长度」：stripHtmlTags 结尾有
     * {@code trim()}，还含 {@code [ \t]+ → " "}、{@code \n{3,} → "\n\n"} 这类**全局**归一化，
     * 分段结果与整体结果并不相等（这正是本类反复踩到的「两套坐标」陷阱）。
     */
    public List<TocEntry> extractMobiToc(File file) throws IOException {
        // 整本电子书一次性读入堆，受上传上限 ≤50MB 约束（目录提取固有行为，非流式）
        byte[] raw = Files.readAllBytes(file.toPath());

        // 解压 MOBI 内容（复用 extractMobi 的解压实现）
        byte[] decompressedText = decompressMobiText(raw);
        if (decompressedText == null || decompressedText.length == 0) {
            return new ArrayList<>();
        }

        String htmlContent = new String(decompressedText, StandardCharsets.UTF_8);
        List<TocEntry> toc = scanMobiHeadings(htmlContent);

        log.info("MOBI 目录提取完成，共 {} 条目", toc.size());
        return toc;
    }

    /**
     * 扫描 MOBI 的 HTML，返回「章节标题 + 该标题在 {@link #stripHtmlTags} 结果中的真实下标」。
     *
     * <p>坐标来源必须是 {@code stripHtmlTags} 的输出（即 {@link #extractMobi} 返回的那份正文），
     * 而且就是**同一个函数调用**、同一份文本，不另算一遍 —— 见 {@link #extractMobiToc} 的说明。
     * 包级可见是为了让单测能直接断言「偏移落在纯文本里且指向标题」。
     */
    List<TocEntry> scanMobiHeadings(String htmlContent) {
        List<TocEntry> toc = new ArrayList<>();
        String plainText = stripHtmlTags(htmlContent);
        Matcher matcher = MOBI_HEADING.matcher(htmlContent);
        int searchFrom = 0;
        while (matcher.find()) {
            // 去除内部子标签（如 <span>、<b>）得到纯文本标题
            String title = matcher.group(1).replaceAll("(?s)<[^>]+>", "").trim();
            if (title.isEmpty()) {
                continue;
            }
            int[] span = searchIgnoringBlank(plainText, title, searchFrom);
            // 未命中（例如标题里含 HTML 实体，纯文本侧已被解码）时不推进游标，
            // 沿用上一处位置 —— 保证下标单调不倒退，目录至少不会乱跳
            int offset = span == null ? searchFrom : span[0];
            if (span != null) {
                searchFrom = span[1];
            }
            toc.add(new TocEntry(title, offset));
        }
        return toc;
    }

    /**
     * 解压 MOBI 文本内容，返回原始 HTML 字节数组。
     * 该方法是 extractMobi 与 extractMobiToc 共用的唯一解压实现，集中处理所有边界与越界风险。
     *
     *   1) 计算第 i 条文本记录大小时，i+1 仍在记录范围内时用 offsets[i+1]-offsets[i]，
     *      否则用文件末尾 raw.length-offsets[i]；直接访问 offsets[i + 1] 会在
     *      i 达到 numRecords-1 时触发 ArrayIndexOutOfBoundsException。
     *   2) 增加整段偏移表、record0、压缩类型、文本记录数等合法性校验，
     *      任何异常均返回 null（由调用方降级为“提取失败”而非抛出未捕获崩溃）。
     */
    private byte[] decompressMobiText(byte[] raw) {
        if (raw == null || raw.length < 78) { // PDB 头（含末尾 2 字节记录数）至少 78 字节
            log.warn("MOBI 文件过小，无法解析");
            return null;
        }
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(raw);
            DataInputStream dis = new DataInputStream(bais);

            // PDB Header：numRecords(UInt16) 是这 78 字节头的**最后 2 字节**，位于偏移 76；
            // 记录信息表从偏移 78 开始。早期实现误把它当成「头 78 字节 + 紧跟 2 字节记录数」，
            // skipBytes(78) 后再读记录数，实际读到的是 record[0].offset 的高 2 字节
            // （真实 MOBI 里 record[0].offset=78=0x0000004E ⇒ 高 2 字节=0x0000），
            // 于是 numRecords 恒为 0、任何标准 MOBI 都解析失败。这里只跳 76 字节，
            // 读完记录数后游标正好落在偏移 78（记录表起点），后续循环无需再改。
            dis.skipBytes(76);
            int numRecords = dis.readUnsignedShort();
            if (numRecords < 2) {
                log.warn("MOBI 记录数过少: {}", numRecords);
                return null;
            }

            long[] offsets = new long[numRecords];
            for (int i = 0; i < numRecords; i++) {
                offsets[i] = Integer.toUnsignedLong(dis.readInt());
                dis.skipBytes(4); // attributes(1) + uniqueID(3)
            }

            // 偏移表合法性校验：必须单调递增且落在文件范围内，否则视为损坏
            for (int i = 0; i < numRecords; i++) {
                if (offsets[i] < 0 || offsets[i] > raw.length) {
                    log.warn("MOBI 记录偏移异常 offsets[{}]={}，文件长度={}", i, offsets[i], raw.length);
                    return null;
                }
                if (i > 0 && offsets[i] < offsets[i - 1]) {
                    log.warn("MOBI 记录偏移非单调 offsets[{}]={} < offsets[{}]={}", i, offsets[i], i - 1, offsets[i - 1]);
                    return null;
                }
            }

            // --- Record 0: PalmDOC header ---
            if (offsets[1] <= offsets[0]) {
                log.warn("MOBI record0 偏移非法");
                return null;
            }
            byte[] record0 = readRecord(raw, offsets[0], (int) ((offsets[1] - offsets[0]) & 0xFFFFFFFFL));
            if (record0.length < 16) {
                log.warn("MOBI PalmDOC 头部过短");
                return null;
            }

            int compressionType = ((record0[0] & 0xFF) << 8) | (record0[1] & 0xFF);
            // 正文总长（textLength，偏移 4~7）与单条记录上限（recordSize，偏移 10~11），
            // 两者都是 big-endian。缺了它们，解压结果会多出尾巴上的杂字节：
            //   * 每条文本记录压缩流的末尾带填充（如 `00 81`），不限长就会多解出几字节，
            //     后面的正文整体错位；
            //   * 最后一条文本记录后面可能还挂着别的数据（目录、封面标记），
            //     不按 textLength 截断就会把杂字节当正文。
            long textLength = ((long) (record0[4] & 0xFF) << 24) | ((record0[5] & 0xFF) << 16)
                    | ((record0[6] & 0xFF) << 8) | (record0[7] & 0xFF);
            int recordCount = ((record0[8] & 0xFF) << 8) | (record0[9] & 0xFF);
            int recordSize = ((record0[10] & 0xFF) << 8) | (record0[11] & 0xFF);
            if (recordSize <= 0) {
                recordSize = 4096; // 标准值；个别文件这个字段写 0
            }

            if (compressionType != 1 && compressionType != 2) {
                log.warn("MOBI 压缩类型无效: {}", compressionType);
                return null;
            }

            // 文本记录数为 recordCount（record 索引 1 .. recordCount），不得超过可用记录数
            if (recordCount > numRecords - 1) {
                log.warn("MOBI 文本记录数 {} 超过可用记录数 {}，截断处理", recordCount, numRecords - 1);
                recordCount = numRecords - 1;
            }
            if (recordCount < 1) {
                log.warn("MOBI 文本记录数为 0");
                return null;
            }

            ByteArrayOutputStream decompressed = new ByteArrayOutputStream();
            for (int i = 1; i <= recordCount; i++) {
                // 安全计算记录大小：i+1 已越界（最后一条记录）时用文件末尾兜底，杜绝越界
                int size;
                if (i + 1 < numRecords) {
                    size = (int) ((offsets[i + 1] - offsets[i]) & 0xFFFFFFFFL);
                } else {
                    size = raw.length - (int) offsets[i];
                }
                if (size <= 0) {
                    log.warn("MOBI 记录 {} 大小异常({})，跳过", i, size);
                    continue;
                }
                byte[] record = readRecord(raw, offsets[i], size);
                if (compressionType == 2) {
                    // 限长：压缩流尾部有填充字节，不限长会多解出几字节、正文整体错位
                    decompressed.write(palmDocDecompress(record, recordSize));
                } else {
                    decompressed.write(record, 0, Math.min(record.length, recordSize));
                }
            }
            byte[] text = decompressed.toByteArray();
            // 按 record0 声明的正文总长截断（声明值不合理时保留原样，宁可多留也不能截短正文）
            if (textLength > 0 && textLength <= text.length) {
                return java.util.Arrays.copyOf(text, (int) textLength);
            }
            return text;
        } catch (Exception e) {
            log.warn("MOBI 解压失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * PalmDOC LZ77 解压。
     *
     * PalmDOC 使用基于字节的 LZ77 变体：
     *   0x00        → 输出字面量 0x00
     *   0x01-0x08   → 从输入流复制 byte 个字面量字节
     *   0x09-0x7F   → 字面量字节，直接输出
     *   0x80-0xBF   → 两字节序列，编码距离和长度
     *   0xC0-0xFF   → 空格(0x20) + (byte XOR 0x80)
     *
     * 回引（0x80-0xBF 分支）直接按索引回读活动缓冲：依赖 ByteArrayOutputStream
     * 每次 out.toByteArray() 全量复制当前输出再回读时，大文档回引上万次会退化为平方级
     * 内存拷贝。直接回读活动缓冲后，重叠回引（distance < copyLen）也自动正确——
     * LZ77 语义要求逐字节引用已扩展输出（如 distance=2 应交替复制最后两字节），
     * 快照式回读只能反复复制快照末字节。
     *
     * @param maxOutput 最多产出多少字节（MOBI 一条文本记录解压后不超过 recordSize）；
     *                   压缩流尾部常有填充字节，不限长会多解出几字节、正文整体错位。
     */
    private byte[] palmDocDecompress(byte[] compressed, int maxOutput) {
        OutputBuffer out = new OutputBuffer(compressed.length * 2);
        int pos = 0;
        int len = compressed.length;

        while (pos < len && out.size() < maxOutput) {
            int b = compressed[pos++] & 0xFF;
            if (b == 0x00) {
                out.write(0x00);
            } else if (b >= 0x01 && b <= 0x08) {
                int copyLen = Math.min(b, len - pos);
                out.write(compressed, pos, copyLen);
                pos += copyLen;
            } else if (b >= 0x09 && b <= 0x7F) {
                out.write(b);
            } else if (b >= 0x80 && b <= 0xBF) {
                if (pos >= len) break;
                int b2 = compressed[pos++] & 0xFF;
                int pair = ((b << 8) | b2);
                // distance = (pair >> 3) & 0x7FF, length = (pair & 0x07) + 3
                int distance = (pair >> 3) & 0x7FF;
                int copyLen = (pair & 0x07) + 3;
                int srcPos = out.size() - distance;
                for (int j = 0; j < copyLen; j++) {
                    if (srcPos < 0) {
                        // 回引越过输出起点：按 PalmDOC 惯例补空格
                        out.write(0x20);
                    } else if (srcPos >= out.size()) {
                        out.write(out.get(out.size() - 1));
                    } else {
                        out.write(out.get(srcPos));
                    }
                    srcPos++;
                }
            } else { // 0xC0-0xFF
                out.write(0x20); // space
                out.write(b ^ 0x80);
            }
        }

        byte[] result = out.toByteArray();
        // 最后一个码字可能写超了（回引一次最多 10 字节），按上限截齐
        if (result.length > maxOutput) {
            return java.util.Arrays.copyOf(result, maxOutput);
        }
        return result;
    }

    /**
     * 可增长字节输出缓冲：供 {@link #palmDocDecompress} 在解压时按索引回读已写入内容
     * （ByteArrayOutputStream 不暴露内部缓冲，每次 toByteArray 都是全量复制）。
     */
    private static final class OutputBuffer {

        private byte[] data;
        private int size;

        OutputBuffer(int initialCapacity) {
            data = new byte[Math.max(16, initialCapacity)];
        }

        int size() {
            return size;
        }

        byte get(int index) {
            return data[index];
        }

        void write(int b) {
            if (size == data.length) {
                grow();
            }
            data[size++] = (byte) b;
        }

        void write(byte[] src, int off, int len) {
            while (size + len > data.length) {
                grow();
            }
            System.arraycopy(src, off, data, size, len);
            size += len;
        }

        private void grow() {
            // 1.5 倍扩容，避免每次 +1 触发频繁复制
            int newCapacity = data.length + (data.length >> 1) + 1;
            data = java.util.Arrays.copyOf(data, newCapacity);
        }

        byte[] toByteArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    // ---------- 辅助方法（包内可见：BookCoverExtractor 复用同一套 ZIP / XML 加固逻辑，
    //            避免为「从书里取封面图」再造一份 ZIP 炸弹与 XXE 防护）----------

    /**
     * 从 ZIP 条目映射中按名称查找条目。
     * readZipEntries 将所有条目名统一转为小写存储（兼容大小写不敏感的文件系统），
     * 因此这里的查找键也统一转为小写后再匹配，避免因 EPUB 内部路径大小写差异导致找不到条目。
     */
    byte[] getZipEntry(Map<String, byte[]> zipEntries, String key) {
        return zipEntries.get(key.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * 创建安全的 DocumentBuilder，禁用外部 DTD 和实体加载（防 XXE）。
     */
    DocumentBuilder createSafeDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setValidating(false);
        factory.setNamespaceAware(false);
        // 关闭 XInclude 与实体引用展开，避免借助 include/实体间接加载外部资源
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            // 主防御：彻底禁止 DOCTYPE 声明（含内部/外部 DTD），从根本上关闭 XXE / 实体扩展攻击面
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            // 纵深防御：即便个别解析器忽略上面的开关，也禁用外部实体与外部 DTD 加载
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
            // 部分 XML 实现可能不支持这些 feature，忽略即可
        }
        return factory.newDocumentBuilder();
    }

    /**
     * 剥掉 XML 声明中的 &lt;!DOCTYPE ...&gt;，其余内容原样返回。
     *
     * <p>为什么需要它：{@link #createSafeDocumentBuilder()} 开启了
     * {@code disallow-doctype-decl=true}（防 XXE 的主防御，必须保留），而 Xerces 的行为是
     * <b>只要文档含 DOCTYPE 声明就立即抛 SAXParseException</b>，与 DTD 是否可访问无关。
     * 而 EPUB 2 的 <b>toc.ncx 几乎总是带 DOCTYPE</b>（标准模板即
     * {@code <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "...">}），
     * 于是目录解析必然失败、被 catch 吞掉后表现为「目录为空」——书读得出来，就是没有目录。
     *
     * <p>因此这里在 parse 之前先摘掉 DOCTYPE。安全性不受影响：DOCTYPE 被移除后，
     * 文档里既没有实体声明也没有外部 DTD 引用，XXE 的载体本身就不存在了；
     * 再加上 factory 上的 load-external-dtd / external-*-entities 三重关闭，
     * 防御强度不降反升（从「靠解析器拒绝」变成「解析器根本没有可加载的东西」）。
     *
     * <p>用字节级扫描而不是正则：DOCTYPE 内部可能含 {@code [ ... ]} 内部子集与引号内的
     * {@code >}，正则容易截错位置；字节扫描可以正确配对引号与方括号。
     *
     * @param data 原始 XML 字节
     * @return 移除 DOCTYPE 后的字节；没有 DOCTYPE 时原样返回
     */
    byte[] stripDoctype(byte[] data) {
        if (data == null || data.length == 0) return data;
        // DOCTYPE 只可能出现在文档开头一小段（XML 声明 + 若干注释/空白之后），
        // 限制扫描窗口既快又能避免把正文里出现的 "<!DOCTYPE" 字符串误判成声明
        int limit = Math.min(data.length, 4096);
        int start = -1;
        for (int i = 0; i + 9 <= limit; i++) {
            if (data[i] == '<' && matchesAscii(data, i, "<!DOCTYPE")) {
                start = i;
                break;
            }
        }
        if (start < 0) return data;

        // 从 DOCTYPE 起逐字符找配对的 '>'，跳过引号内与内部子集 [...] 里的 '>'
        int i = start + 9;
        int bracketDepth = 0;
        char quote = 0;
        for (; i < data.length; i++) {
            char ch = (char) (data[i] & 0xFF);
            if (quote != 0) {
                if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '[') {
                bracketDepth++;
            } else if (ch == ']') {
                if (bracketDepth > 0) bracketDepth--;
            } else if (ch == '>' && bracketDepth == 0) {
                i++; // 含结尾的 '>'
                break;
            }
        }
        if (i > data.length) i = data.length;

        byte[] out = new byte[data.length - (i - start)];
        System.arraycopy(data, 0, out, 0, start);
        System.arraycopy(data, i, out, start, data.length - i);
        return out;
    }

    /** 判断 data 从 offset 起是否与 needle（纯 ASCII）逐字节相等 */
    private boolean matchesAscii(byte[] data, int offset, String needle) {
        if (offset + needle.length() > data.length) return false;
        for (int k = 0; k < needle.length(); k++) {
            if ((data[offset + k] & 0xFF) != (needle.charAt(k) & 0xFF)) return false;
        }
        return true;
    }

    /**
     * 规范化 ZIP 内文件路径，处理 ../ 和 ./（ZIP 条目始终使用正斜杠）。
     */
    String normalizeZipPath(String path) {
        String[] parts = path.split("/");
        List<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
            } else if (!part.equals(".") && !part.isEmpty()) {
                stack.add(part);
            }
        }
        return String.join("/", stack);
    }

    /** 从字节数组中按偏移读取指定长度的记录 */
    private byte[] readRecord(byte[] raw, long offset, int size) {
        int off = (int) offset;
        if (off < 0 || off >= raw.length) {
            log.warn("记录偏移越界: offset={}, rawLength={}", off, raw.length);
            return new byte[0];
        }
        if (size < 0) size = 0;
        if (off + size > raw.length) size = raw.length - off;
        byte[] buf = new byte[size];
        System.arraycopy(raw, off, buf, 0, size);
        return buf;
    }

    /**
     * 去除 HTML 标签，保留纯文本。
     * 同时处理常见的 HTML 实体和空白规范化。
     */
    /**
     * HTML → 纯文本（MOBI 正文提取 + 其目录坐标的唯一来源）。
     *
     * <p>纯函数，无实例状态；包级可见是给单测断言「MOBI 目录偏移确实落在本方法的输出里」用。
     * 注意它含 {@code trim()} 与 {@code [ \t]+}/{@code \n{3,}} 这类**全局**归一化，
     * 因此**不能**拿它对 HTML 的分段前缀分别调用再累加长度来求偏移（结果不相等）。
     */
    String stripHtmlTags(String html) {
        if (html == null || html.isEmpty()) return "";

        // Remove script/style blocks
        String cleaned = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ");

        // Replace block-level tags with newlines
        cleaned = cleaned.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</?p[^>]*>", "\n")
                .replaceAll("(?i)</?div[^>]*>", "\n")
                .replaceAll("(?i)</?(h[1-6]|li|tr|table)[^>]*>", "\n");

        // Remove remaining tags
        cleaned = cleaned.replaceAll("<[^>]+>", " ");

        // Decode common HTML entities
        cleaned = cleaned.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");

        // Decode numeric entities
        cleaned = java.util.regex.Pattern.compile("&#(\\d+);")
                .matcher(cleaned)
                .replaceAll(mr -> String.valueOf((char) Integer.parseInt(mr.group(1))));
        cleaned = java.util.regex.Pattern.compile("&#x([0-9a-fA-F]+);")
                .matcher(cleaned)
                .replaceAll(mr -> String.valueOf((char) Integer.parseInt(mr.group(1), 16)));

        // Normalize whitespace
        cleaned = cleaned.replaceAll("\r\n|\r", "\n");
        cleaned = cleaned.replaceAll("[ \t]+", " ");
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");

        return cleaned.trim();
    }
}
