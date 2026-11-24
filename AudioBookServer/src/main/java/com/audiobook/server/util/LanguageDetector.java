package com.audiobook.server.util;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级文本语种识别（零第三方依赖）。用于「自动识别书籍语言 → 决定走 MOSS 还是 Pocket 引擎」。
 *
 * <p><b>为什么不引第三方模型库（lingua 等）。</b>本项目长期在离线/受限环境构建，往 Maven 本地仓库
 * 拉一个几十 MB 的语言识别模型既拖慢构建、又给运行时添内存与体积负担；而这里真正要回答的问题很窄：
 * 一本书到底是<b>中文</b>，还是 Pocket 支持的那门<b>拉丁语系外语</b>（英/法/德/西/意/葡/荷）。
 * 判中文靠 CJK 字符占比，一判一个准；在几门拉丁语之间消歧，用「高频虚词（停用词）命中数 +
 * 变音符号特征」在整本书的样本上投票已经足够可靠——书级样本动辄上万词，停用词分布差异被放大，
 * 误判率远低于单句。故自建一个几十行、可单测、行为透明的启发式判定，不为一个窄场景引入整个模型栈。
 *
 * <p><b>识别粒度：书级，而非逐句。</b>单句太短，停用词少，西/意/葡彼此难分；而一本书的语言是
 * 稳定属性，应在正文提取时对「整本样本」识别一次。{@link #detect(String)} 内部已对超长文本取样，
 * 调用方直接把拼接好的书级样本传进来即可。
 *
 * <p><b>兜底原则：宁可回 {@code other} 也不硬猜。</b>{@code other} 在上层会被路由到 MOSS
 * （MOSS 自带英/日/韩/法/德等多语音色），因此日/韩/俄/阿等 Pocket 不支持的语种，或样本太短/太混杂
 * 判不准时，一律 {@code other}→MOSS，绝不会把请求发给没有对应音色的 Pocket 节点。
 */
public final class LanguageDetector {

    private LanguageDetector() {
    }

    /** 语种代码：中文 + Pocket 支持的 7 门拉丁语系；识别不出时返回 {@link #OTHER}。 */
    public static final String ZH = "zh";
    public static final String OTHER = "other";

    /** Pocket 可路由的拉丁语集合（= 停用词表的键集）：{@link #isPocketRoutable} 与上层判据共用。 */
    private static final Set<String> POCKET_LANGUAGES =
            Set.of("en", "fr", "de", "es", "it", "pt", "nl");

    /** 书级样本取样上限：够铺开支用词投票，又避免把整本几十万字读进正则引擎逐字符扫。 */
    private static final int MAX_SAMPLE_CHARS = 24_000;

    /** 判中文：CJK 表意文字最少个数（太低说明多半不是中文正文）。 */
    private static final int MIN_CJK_FOR_ZH = 24;
    /** 判中文：CJK 相对拉丁字母的占比门槛。 */
    private static final double CJK_RATIO_FOR_ZH = 0.5;

    /**
     * 停用词命中最少次数：低于此值说明样本不足以在几门拉丁语间区分，回 {@code other}。
     * 书级样本通常命中成百上千，这个门槛只用来挡「只言片语」。
     */
    private static final int MIN_STOPWORD_HITS = 5;
    /** 冠军与亚军的最小分差：小于此值视为西/意/葡等近亲语言互搏，不敢认 → {@code other}。 */
    private static final int MIN_LEAD = 2;

    /** 拉丁词切分：连续的 Unicode 字母（含带音标拉丁字母），撇号等非字母自然成为分隔。 */
    private static final Pattern WORD = Pattern.compile("[\\p{L}]+");

    /**
     * 各语言的停用词表（小写、含重音形式）。刻意选**跨语言歧义低**的高频虚词为主，
     * 允许少量重叠（如 {@code de/la/que}），靠累计命中数在整本书上拉开差距。
     */
    private static final Map<String, Set<String>> STOPWORDS = buildStopwords();

    /**
     * 强区分度的变音/特殊字符 → 归属语言。命中一次加 {@link #DIACRITIC_WEIGHT} 分。
     * 只收录「几乎唯一指向某门语言」的符号（ß→德、ñ/¿/¡→西、ã/õ→葡、œ→法），
     * 普通 é/à/ü 不加——它们在多门语言里都出现，加了只会让近亲语言比分更胶着。
     */
    private static final Map<Character, String> DIACRITIC_HINTS = buildDiacriticHints();
    private static final int DIACRITIC_WEIGHT = 3;

    /**
     * 识别文本语种。
     *
     * @param text 书级样本（可超长，内部取样）；null/空 → {@link #OTHER}
     * @return {@code zh} / {@code en,fr,de,es,it,pt,nl} 之一；判不准时 {@link #OTHER}
     */
    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return OTHER;
        }
        String sample = text.length() > MAX_SAMPLE_CHARS ? text.substring(0, MAX_SAMPLE_CHARS) : text;
        // NFC 归一：把「基字母 + 组合重音」合成单码点，保证与停用词表里的预组合形式（é）对得上
        sample = Normalizer.normalize(sample, Normalizer.Form.NFC);

        long cjk = 0;
        long kana = 0;
        long latin = 0;
        for (int i = 0; i < sample.length(); i++) {
            char c = sample.charAt(i);
            if (isCjkIdeograph(c)) {
                cjk++;
            } else if (isKana(c)) {
                kana++;
            } else if (isLatinLetter(c)) {
                latin++;
            }
        }

        // 中文优先判定。刻意排除「假名不少」的情况：日文里也混大量汉字，但假名的存在说明它更可能是
        // 日语，而 Pocket 不支持日语 → 落到 other → 交给 MOSS（其 demo-13 正是日文音色），不误发 Pocket。
        long cjkPlusLatin = cjk + latin;
        if (cjk >= MIN_CJK_FOR_ZH && cjkPlusLatin > 0
                && (double) cjk / cjkPlusLatin >= CJK_RATIO_FOR_ZH
                && kana < cjk * 0.1) {
            return ZH;
        }

        // 几乎没有 CJK、又确有拉丁正文时，才在 7 门拉丁语间投票。
        if (latin < 20) {
            return OTHER;
        }
        return voteLatinLanguage(sample);
    }

    /** 停用词命中 + 变音符号加权，取最高分语言；分差不显著或样本太薄时回 {@link #OTHER}。 */
    private static String voteLatinLanguage(String sample) {
        Map<String, Integer> scores = new HashMap<>();
        for (String lang : POCKET_LANGUAGES) {
            scores.put(lang, 0);
        }

        Matcher m = WORD.matcher(sample.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String word = m.group();
            for (Map.Entry<String, Set<String>> e : STOPWORDS.entrySet()) {
                if (e.getValue().contains(word)) {
                    scores.merge(e.getKey(), 1, Integer::sum);
                }
            }
        }

        for (int i = 0; i < sample.length(); i++) {
            String lang = DIACRITIC_HINTS.get(sample.charAt(i));
            if (lang != null) {
                scores.merge(lang, DIACRITIC_WEIGHT, Integer::sum);
            }
        }

        String best = OTHER;
        int bestScore = 0;
        int secondScore = 0;
        for (Map.Entry<String, Integer> e : scores.entrySet()) {
            int s = e.getValue();
            if (s > bestScore) {
                secondScore = bestScore;
                bestScore = s;
                best = e.getKey();
            } else if (s > secondScore) {
                secondScore = s;
            }
        }

        if (bestScore < MIN_STOPWORD_HITS) {
            return OTHER;
        }
        if (bestScore - secondScore < MIN_LEAD) {
            return OTHER;
        }
        return best;
    }

    // ===== 字符分类（手写区间判断，避免逐字符查 UnicodeBlock 的开销）=====

    private static boolean isCjkIdeograph(char c) {
        // CJK 统一表意文字基本区 + 扩展 A + 兼容表意文字，覆盖常见中日韩汉字
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static boolean isKana(char c) {
        // 平假名 0x3040-0x309F、片假名 0x30A0-0x30FF
        return c >= 0x3040 && c <= 0x30FF;
    }

    private static boolean isLatinLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= 0x00C0 && c <= 0x024F); // Latin-1 补充 + Latin 扩展 A（含各语重音字母）
    }

    // ===== 停用词表 =====

    private static Map<String, Set<String>> buildStopwords() {
        Map<String, Set<String>> map = new HashMap<>();
        map.put("en", Set.of("the", "and", "of", "to", "in", "is", "you", "that", "it", "for",
                "on", "with", "as", "was", "are", "but", "this", "they", "have", "from", "one",
                "then", "all", "not", "out", "at", "be", "by", "or", "an", "his", "her", "our",
                "would", "could", "there", "their", "what", "about", "which"));
        map.put("fr", Set.of("le", "de", "des", "et", "la", "les", "dans", "une", "que", "qui",
                "pour", "pas", "sur", "au", "aux", "est", "mais", "comme", "aussi", "cette",
                "son", "ses", "je", "tu", "il", "elle", "nous", "vous", "ne", "plus", "bien",
                "tout", "avec", "ces", "dont", "tous", "être"));
        map.put("de", Set.of("der", "die", "und", "das", "zu", "nicht", "von", "mit", "ist",
                "für", "auch", "auf", "den", "dem", "ein", "eine", "wir", "sie", "er", "es",
                "aus", "noch", "sehr", "beim", "zum", "als", "aber", "oder", "wenn", "sich",
                "wird", "haben", "kann", "um", "bei", "wieder"));
        map.put("es", Set.of("el", "la", "que", "de", "y", "en", "un", "con", "no", "por",
                "para", "como", "más", "pero", "sus", "le", "ya", "este", "sí", "porque",
                "esta", "cuando", "muy", "sin", "sobre", "entre", "está", "todo", "nos", "mí"));
        map.put("it", Set.of("il", "che", "di", "e", "la", "è", "non", "con", "per", "una",
                "del", "delle", "gli", "questo", "anche", "come", "più", "ma", "sua",
                "loro", "se", "quando", "sono", "hai", "stato", "dalla", "nella", "tutto",
                "cosa", "così", "della"));
        map.put("pt", Set.of("de", "a", "o", "que", "e", "do", "da", "em", "um", "para",
                "com", "não", "uma", "os", "no", "se", "as", "dos", "tudo", "está", "pelo",
                "pela", "já", "entre", "quando", "muito", "sem", "sobre", "mais", "como",
                "também", "por", "você", "nós"));
        map.put("nl", Set.of("de", "het", "een", "en", "van", "is", "in", "te", "dat", "niet",
                "op", "aan", "met", "voor", "zijn", "er", "maar", "ook", "als", "uit", "bij",
                "zich", "zonder", "nog", "over", "naar", "deze", "dit", "was", "heeft", "door"));
        return map;
    }

    private static Map<Character, String> buildDiacriticHints() {
        Map<Character, String> m = new HashMap<>();
        m.put('ß', "de"); // eszett 几乎为德语独有
        m.put('ñ', "es");
        m.put('¿', "es");
        m.put('¡', "es");
        m.put('ã', "pt");
        m.put('õ', "pt");
        m.put('œ', "fr");
        return m;
    }

    /** 供调用方（合成服务）判断某语言是否属于 Pocket 可路由集合（英/法/德/西/意/葡/荷）。 */
    public static boolean isPocketRoutable(String lang) {
        return lang != null && POCKET_LANGUAGES.contains(lang);
    }
}
