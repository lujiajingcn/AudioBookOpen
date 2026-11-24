package com.audiobook.server.util;

import java.util.Locale;

/**
 * 覆盖图 bookKey 归一化工具（服务端权威定义）。
 *
 * <p><b>算法必须与客户端（Android {@code CoverageCodec.normalizeName} /
 * PC {@code app/core/coverage.py::normalize_name}）逐字符一致</b>：给定同一显示名，
 * 三端归一化后的字符串必须完全相同，否则同一本书会在服务端裂成多行覆盖图，
 * 徽章/续合/去重都会漂。
 *
 * <p>bookKey 三种前缀：
 * <ul>
 *   <li>{@code s:{storeBookId}} —— 书城条目，纯数字，无需归一。</li>
 *   <li>{@code t:{serverTaskId}} —— 在线合成任务，纯数字，无需归一。</li>
 *   <li>{@code n:{name}} —— 本地导入书，name 为文件名/书名，<b>本工具的归一目标</b>。</li>
 * </ul>
 *
 * <p>{@code n:} 归一规则（固定顺序，禁止增删步骤）：
 * <ol>
 *   <li>去掉两端"书键空白"字符（见 {@link #isBookWhitespace(char)}）。</li>
 *   <li>Unicode 小写化（{@link Locale#ROOT}）——与 Python {@code str.lower()} 语义一致。</li>
 *   <li>若尾部匹配已知电子书扩展名（{@link #EXTENSIONS}），剥掉一次；扩展名列表按长度倒序避免
 *       {@code .doc} 遮蔽 {@code .docx}。</li>
 *   <li>压缩内部连续空白为单个 ASCII 空格（{@code 0x20}），并再次去两端空白。</li>
 * </ol>
 *
 * <p>为什么这样做：跨端"同一本书"最常见的三种裂键就是——文件名大小写不同、扩展名带不带、
 * 空格数量差异（下载/复制路径时被自动 trim）。归一后这些差异被抹平，跨端合并才有意义。
 * 归一化不改语义：不同书不会因为归一化被错误合并（除非用户自己给两本书起了归一后相同的名字，
 * 那属于用户命名冲突，非本工具责任）。
 */
public final class BookKeys {

    /** 覆盖图 bookKey 前缀：书城条目 */
    public static final String PREFIX_STORE = "s:";
    /** 覆盖图 bookKey 前缀：在线合成任务 */
    public static final String PREFIX_TASK = "t:";
    /** 覆盖图 bookKey 前缀：本地书名（唯一需要归一化的分支） */
    public static final String PREFIX_NAME = "n:";

    /**
     * 已知电子书文件扩展名。<b>顺序：按长度倒序 + 字母序</b>——长扩展名在前，防止 {@code .doc} 抢先
     * 匹配到 {@code .docx} 尾巴（{@code endsWith(".doc")} 对 "foo.docx" 为 false 其实不会出问题，
     * 但 {@code endsWith(".azw")} 对 "foo.azw3" 为 false，也不会误剥。保守起见仍按长度倒序）。
     *
     * <p>三端必须使用完全相同的列表和顺序。
     */
    private static final String[] EXTENSIONS = {
        ".azw3", ".docx", ".djvu", ".epub", ".mobi",
        ".pdf",  ".azw",  ".doc",  ".fb2",  ".txt"
    };

    private BookKeys() {
    }

    /**
     * 归一化整个 bookKey。仅对 {@code n:} 前缀生效；{@code s:}/{@code t:}/未知前缀原样返回。
     * 用于服务端接收上报时的防御性归一（老客户端仍发送未归一的 n: 键时也能被正确落库）。
     *
     * @return 归一化后的 bookKey；如果 n: 尾部归一为空，返回 {@code ""}（调用方应丢弃）。
     */
    public static String normalize(String bookKey) {
        if (bookKey == null) {
            return "";
        }
        String t = trimBookWhitespace(bookKey);
        if (t.isEmpty()) {
            return "";
        }
        if (t.startsWith(PREFIX_NAME)) {
            String tail = normalizeName(t.substring(PREFIX_NAME.length()));
            return tail.isEmpty() ? "" : PREFIX_NAME + tail;
        }
        return t;
    }

    /**
     * 归一化书名（不含 {@code n:} 前缀）。三端字节一致。
     *
     * @param name 原始书名/文件名，可为 null
     * @return 归一化后的名字；null/空/仅空白/仅扩展名 → 返回 {@code ""}
     */
    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String s = trimBookWhitespace(name);
        if (s.isEmpty()) {
            return "";
        }
        // Unicode 小写化（Locale.ROOT 保证不受 JVM 默认区域影响，与 Python str.lower() 语义一致）
        s = s.toLowerCase(Locale.ROOT);
        // 剥一次已知扩展名
        for (String ext : EXTENSIONS) {
            if (s.length() > ext.length() && s.endsWith(ext)) {
                s = s.substring(0, s.length() - ext.length());
                break;
            }
        }
        // 压缩内部空白 + 再次去两端
        return compressWhitespace(s);
    }

    /**
     * 判定一个字符是否属于"书键空白"集合。显式列举，避免各语言的 {@code \s} / {@code isspace()} /
     * {@code Character.isWhitespace()} 定义不一致。
     *
     * <p>包含：
     * <ul>
     *   <li>ASCII：0x09 TAB、0x0A LF、0x0B VT、0x0C FF、0x0D CR、0x20 SPACE</li>
     *   <li>Unicode：0xA0 NBSP、0x1680 OGHAM SPACE MARK、0x2000-0x200A 各种排版空格、
     *       0x2028 LINE SEP、0x2029 PARA SEP、0x202F NNBSP、0x205F MMSP、0x3000 全角空格</li>
     * </ul>
     *
     * <p>代理对（&gt; U+FFFF）不会被误判为空白——它们的 code unit 在 0xD800-0xDFFF 范围，不在上表。
     */
    public static boolean isBookWhitespace(char c) {
        return c == 0x20
            || (c >= 0x09 && c <= 0x0D)
            || c == 0xA0
            || c == 0x1680
            || (c >= 0x2000 && c <= 0x200A)
            || c == 0x2028
            || c == 0x2029
            || c == 0x202F
            || c == 0x205F
            || c == 0x3000;
    }

    /** 去两端"书键空白"。 */
    static String trimBookWhitespace(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isBookWhitespace(s.charAt(a))) {
            a++;
        }
        while (b > a && isBookWhitespace(s.charAt(b - 1))) {
            b--;
        }
        return a == 0 && b == s.length() ? s : s.substring(a, b);
    }

    /**
     * 压缩连续"书键空白"为单个 ASCII 空格（0x20），并去掉两端空白。
     * 遍历 UTF-16 code unit，非空白字符（包括代理对的两个 code unit）原样追加。
     */
    static String compressWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean inWsRun = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isBookWhitespace(c)) {
                inWsRun = true;
            } else {
                if (inWsRun && sb.length() > 0) {
                    sb.append(' ');
                }
                inWsRun = false;
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
