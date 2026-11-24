package com.audiobook.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * 词典**查询**服务（Web 阅读页长按「翻译」「查字典」用）。
 *
 * <h2>为什么服务端也要查一次</h2>
 * Android 端把 {@code dict.db} 下载到本机，用 {@code SQLiteDatabase} 直接查；浏览器没有
 * 可用的 SQLite（sql.js 要额外拉 1.5MB wasm + 55MB 库），所以在 Web 上只能由服务端代查。
 * 两端口径必须一致 —— 同一份 {@code dict.db}、同一个 {@code sw} 归一规则、
 * 同一套「释义按字面量 {@code \n} 切分」约定，否则同一本书在两端查出来不一样。
 *
 * <h2>两个库、两种写法</h2>
 * <table border="1">
 *   <tr><th></th><th>英汉（ECDICT）</th><th>中文单字（新华字典）</th></tr>
 *   <tr><td>文件</td><td>{@code dict.db}（55MB，由 {@code dict.db.gz} 首次查询时解压）</td>
 *       <td>{@code chinese_dict.sqlite}（4MB，随包资源，需单独上传到词典目录）</td></tr>
 *   <tr><td>表</td><td>{@code dict(sw, word, phonetic, translation, exchange, tag, collins, oxford)}</td>
 *       <td>{@code word(id, word, pinyin, explanation)}</td></tr>
 *   <tr><td>查询键</td><td>{@code sw}（去非字母数字 + 小写）</td><td>{@code word}（单字精确）</td></tr>
 * </table>
 *
 * <h2>三个不能想当然的地方</h2>
 * <ol>
 *   <li><b>必须按 {@code sw} 查，不能按 {@code word} 查</b>：用户长按到的是书里的形态
 *       （{@code Running.} / {@code take-off} / {@code "book"}），{@code sw} 是去符号小写后的键。
 *       附带后果：一个 {@code sw} 可能对应多行（{@code take-off}/{@code Take Off}/{@code TAKEOFF}），
 *       全部返回，不要只取第一行。</li>
 *   <li><b>{@code translation} 里的换行是字面量反斜杠+n 两个字符</b>，不是真换行
 *       （全库 768739 行中含真换行的为 0）。按真换行切会把整段释义挤成一行。
 *       这里在服务端就切好成数组下发，客户端不必知道这条约定。</li>
 *   <li><b>中文库只有单字</b>：{@code word = ?} 是精确匹配，多字必然查空。是否显示「查字典」
 *       由客户端按「选区恰好一个汉字」判定，服务端再做一次校验兜底。</li>
 * </ol>
 *
 * <h2>首次查询解压 55MB 的代价</h2>
 * 线上词典目录里只有分发件 {@code dict.db.gz}（23MB）。裸库不存在时，在**第一查询**时解压
 * （实测 1~2 秒），解压到 {@code dict.db.tmp} 再原子改名 —— 并发首查时不能出现
 * 「一个线程正在写、另一个线程已经打开」的半成品库。失败不缓存失败态：60 秒冷却后允许重试，
 * 免得补传文件后还必须重启服务。
 */
@Service
public class DictLookupService {

    private static final Logger log = LoggerFactory.getLogger(DictLookupService.class);

    /** 裸库（解压后）文件名 */
    private static final String ENGLISH_DB = "dict.db";
    /** 分发件（gzip）文件名，与 DictService 的分发口径同一个文件 */
    private static final String ENGLISH_GZ = "dict.db.gz";
    /** 中文单字库文件名（取自 AudioBookPlayer 的 assets/chinese_dict.sqlite） */
    private static final String CHINESE_DB = "chinese_dict.sqlite";

    /** 单次查询最多返回多少条词条（{@code sw} 撞车的极端情况） */
    private static final int MAX_ENTRIES = 20;
    /** 查询词长度上限：正常单词不超过 30，超长一律视为脏输入，别让它进 SQL */
    private static final int MAX_QUERY_LEN = 64;
    /** 结果缓存条数上限；超了整体清空（词典查询的重复率极高，简单策略足够） */
    private static final int CACHE_LIMIT = 2048;
    /** 解压失败后的冷却时间（毫秒）：期间不再反复尝试，过后允许重试 */
    private static final long PREPARE_RETRY_COOLDOWN_MS = 60_000L;

    private final DictService dictService;

    /** sw → 词条列表（含「查无此词」的空列表，避免同一个不存在的词反复打库） */
    private final Map<String, List<EnglishEntry>> englishCache = new ConcurrentHashMap<>();
    /** 汉字 → 释义（同上） */
    private final Map<String, List<ChineseEntry>> chineseCache = new ConcurrentHashMap<>();

    /** 已就绪的裸库；null 表示还没解压/不存在 */
    private volatile File englishDb;
    /** 上次解压失败的时刻（0 = 从未失败），用于冷却判断 */
    private volatile long prepareFailedAt;

    public DictLookupService(DictService dictService) {
        this.dictService = dictService;
    }

    // ------------------------------------------------------------------
    // 对外查询
    // ------------------------------------------------------------------

    /**
     * 查英汉词条。
     *
     * @param raw 用户选中的原文（可能带标点、大小写、"take-off" 这类连接符，也可能是多个词）
     * @return 命中列表，**永不为 null**；查无此词返回空列表
     */
    public List<EnglishEntry> lookupEnglish(String raw) {
        String word = extractQueryWord(raw);
        if (word == null) return Collections.emptyList();
        String sw = stripWord(word);
        if (sw.isEmpty()) return Collections.emptyList();

        List<EnglishEntry> hit = englishCache.get(sw);
        if (hit != null) return hit;

        File db = englishDb();
        if (db == null) return Collections.emptyList();

        List<EnglishEntry> out = queryEnglish(db, sw);
        put(englishCache, sw, out);
        return out;
    }

    /**
     * 查中文单字。
     *
     * @param raw 选区文本；**恰好一个汉字**才查（多字/非汉字一律返回空）
     */
    public List<ChineseEntry> lookupChinese(String raw) {
        String ch = singleCjkChar(raw);
        if (ch == null) return Collections.emptyList();

        List<ChineseEntry> hit = chineseCache.get(ch);
        if (hit != null) return hit;

        File db = chineseDb();
        if (db == null) return Collections.emptyList();

        List<ChineseEntry> out = queryChinese(db, ch);
        put(chineseCache, ch, out);
        return out;
    }

    /** 英汉词典是否可用（裸库在，或能解压出来） */
    public boolean englishAvailable() {
        return englishDb() != null;
    }

    /** 中文单字库是否可用（文件已上传） */
    public boolean chineseAvailable() {
        return chineseDb() != null;
    }

    // ------------------------------------------------------------------
    // 库文件定位 / 首次解压
    // ------------------------------------------------------------------

    private File englishDb() {
        File ready = englishDb;
        if (ready != null && ready.isFile()) return ready;

        long failedAt = prepareFailedAt;
        if (failedAt > 0 && System.currentTimeMillis() - failedAt < PREPARE_RETRY_COOLDOWN_MS) {
            return null;
        }

        synchronized (this) {
            if (englishDb != null && englishDb.isFile()) return englishDb;
            File dir = dictService.resolveDictDir();
            if (dir == null) return null;

            File db = new File(dir, ENGLISH_DB);
            if (db.isFile()) {
                englishDb = db;
                return db;
            }
            File gz = new File(dir, ENGLISH_GZ);
            if (!gz.isFile()) {
                // 可预期状态：词典还没部署。只 warn 一次（进去就不再查，靠 englishDb 判断）
                if (prepareFailedAt == 0) {
                    log.warn("离线词典未部署，查询接口返回空：缺少 {} 或 {}",
                            db.getAbsolutePath(), gz.getAbsolutePath());
                    prepareFailedAt = System.currentTimeMillis();
                }
                return null;
            }
            File unpacked = gunzip(gz, db);
            if (unpacked == null) {
                prepareFailedAt = System.currentTimeMillis();
                return null;
            }
            prepareFailedAt = 0;
            englishDb = unpacked;
            return unpacked;
        }
    }

    /**
     * 把分发件解压成裸库（幂等）。
     *
     * <p>先写 {@code .tmp} 再 rename：rename 在同一文件系统内是原子的，
     * 并发首查的另一个线程要么看到「没有 db」、要么看到「完整 db」，
     * 绝不会打开一个写到一半的 SQLite 文件（那种情况 SQLite 会直接报
     * {@code file is not a database}，且很难从日志看出是并发导致的）。
     */
    private File gunzip(File gz, File target) {
        File tmp = new File(target.getParentFile(), ENGLISH_DB + ".tmp");
        long t0 = System.currentTimeMillis();
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(gz), 1 << 16));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            in.transferTo(out);
        } catch (IOException e) {
            log.error("解压离线词典失败：{}", gz.getAbsolutePath(), e);
            deleteQuietly(tmp);
            return null;
        }
        if (!tmp.renameTo(target)) {
            // 可能是另一个线程/进程刚好写好了：以磁盘上的成品为准
            if (!target.isFile()) {
                log.error("离线词典改名失败：{} -> {}", tmp, target);
                deleteQuietly(tmp);
                return null;
            }
            deleteQuietly(tmp);
        }
        log.info("离线词典裸库已就绪：{}（{} MB，解压耗时 {} ms）",
                target.getAbsolutePath(), target.length() / (1024 * 1024),
                System.currentTimeMillis() - t0);
        return target;
    }

    private File chineseDb() {
        File dir = dictService.resolveDictDir();
        if (dir == null) return null;
        File f = new File(dir, CHINESE_DB);
        return f.isFile() ? f : null;
    }

    private static void deleteQuietly(File f) {
        try {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Exception ignored) {
            // 删除失败不影响主流程，下次解压会覆盖
        }
    }

    // ------------------------------------------------------------------
    // SQL
    // ------------------------------------------------------------------

    private List<EnglishEntry> queryEnglish(File db, String sw) {
        String sql = "SELECT word, phonetic, translation, exchange, tag, collins, oxford "
                + "FROM dict WHERE sw = ? ORDER BY word LIMIT " + MAX_ENTRIES;
        List<EnglishEntry> out = new ArrayList<>();
        try (Connection c = openReadOnly(db);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sw);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EnglishEntry(
                            rs.getString(1),
                            nullToEmpty(rs.getString(2)),
                            splitTranslations(rs.getString(3)),
                            nullToEmpty(rs.getString(4)),
                            splitTags(rs.getString(5)),
                            rs.getInt(6),
                            rs.getInt(7)));
                }
            }
        } catch (SQLException e) {
            // 与 Android 端一致：查词失败绝不能让阅读页崩掉，返回空由客户端提示「未收录」
            log.warn("查询英汉词典失败：sw={}", sw, e);
            return Collections.emptyList();
        }
        return out;
    }

    private List<ChineseEntry> queryChinese(File db, String ch) {
        String sql = "SELECT pinyin, explanation FROM word WHERE word = ? ORDER BY pinyin ASC";
        List<ChineseEntry> out = new ArrayList<>();
        try (Connection c = openReadOnly(db);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ChineseEntry(
                            nullToEmpty(rs.getString(1)),
                            splitDefinitions(rs.getString(2))));
                }
            }
        } catch (SQLException e) {
            log.warn("查询中文单字库失败：{}", ch, e);
            return Collections.emptyList();
        }
        return out;
    }

    /** 只读打开：词典是静态资源，绝不能被服务端进程写坏（也避免生成 -journal 文件） */
    private Connection openReadOnly(File db) throws SQLException {
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setReadOnly(true);
        cfg.setBusyTimeout(3000);
        return DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath(), cfg.toProperties());
    }

    // ------------------------------------------------------------------
    // 文本处理（与 Android 端逐字符一致，改动必须两端同步）
    // ------------------------------------------------------------------

    /**
     * strip-word：去掉所有非字母数字字符并转小写。
     *
     * <p>必须与建库脚本 {@code build_dict.py} 的 {@code strip_word()} 以及
     * Android 的 {@code DictionaryManager.stripWord} 完全一致 ——
     * 差一个字符就会「客户端能查到、服务端查不到」这种最难查的错位。
     */
    public static String stripWord(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /**
     * 从选区文本里提取要查的词（port of Android {@code DictionaryManager.extractQueryWord}）。
     *
     * <p>用户长按选中的**从来不是**一个干净的单词，实际形态是：
     * <ul>
     *   <li>尾部带标点：{@code word,} / {@code "book"} / {@code Running.}</li>
     *   <li>长按划过多个词：{@code hi hello}</li>
     *   <li>带连字符或撇号：{@code take-off} / {@code don't}（这两个必须原样保留，
     *       它们是词典里的真实词形，靠 {@link #stripWord} 归一去命中）</li>
     * </ul>
     *
     * <p>处理顺序：① 含空白 ⇒ 按空白切分，取 strip 后**最长**的一段（{@code hi hello} → {@code hello}）；
     * ② 否则剥掉**首尾**非字母数字字符（{@code hello,} → {@code hello}）；
     * ③ 含中文 / strip 后为空 ⇒ 返回 null（客户端据此提示「仅支持查英文」）。
     *
     * <p>只剥首尾、不动中间：{@code take-off} 的连字符必须留着，否则词形就变了
     * （{@code takeoff} 与 {@code take-off} 在库里是两个词条）。
     *
     * @return 用于查询的原文（保持大小写，便于回显）；null 表示不是可查的英文词
     */
    public static String extractQueryWord(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_QUERY_LEN) return null;
        // ⚠️ 判「含空白」与「按空白切分」必须用同一个字符集。Java 的 \s 不含
        //   U+00A0 / U+3000，而 NBSP 在 EPUB 里很常见（hello\u00A0world）：
        //   只用 \s 判会走「无空白」分支返回整串 → stripWord 剔掉 NBSP 变成
        //   "helloworld" → 必然查不到。WS 显式列出这些空格。
        if (WS.matcher(trimmed).find()) {
            String best = null;
            for (String part : WS.split(trimmed)) {
                String s = stripEdge(part);
                if (s != null && (best == null || s.length() > best.length())) best = s;
            }
            return best;
        }
        return stripEdge(trimmed);
    }

    /** 「空白」= Unicode 空白 + NBSP + 全角空格 + 各类细空格（与 Android 端同一字符集） */
    private static final java.util.regex.Pattern WS =
            java.util.regex.Pattern.compile("[\\s\\u00A0\\u3000\\u2000-\\u200A\\u202F\\u205F]+");

    /** 剥掉首尾非字母数字字符；不是英文词时返回 null */
    private static String stripEdge(String s) {
        if (!isEnglishWord(s)) return null;
        int start = 0;
        int end = s.length();
        while (start < end && !Character.isLetterOrDigit(s.charAt(start))) start++;
        while (end > start && !Character.isLetterOrDigit(s.charAt(end - 1))) end--;
        return start >= end ? null : s.substring(start, end);
    }

    /**
     * 判断是否是「可查的英文词」：含 CJK/假名/谚文 ⇒ 不是；stripWord 后为空 ⇒ 不是。
     *
     * <p>与客户端「翻译」菜单项的显隐判定同口径 —— 两处分叉就会出现
     * 「菜单项在、点了却查不到」这种最差的形态。
     */
    public static boolean isEnglishWord(String text) {
        if (text == null || text.isBlank()) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= 0x2E80 && ch <= 0x9FFF)
                    || (ch >= 0xAC00 && ch <= 0xD7AF)
                    || (ch >= 0xF900 && ch <= 0xFAFF)) {
                return false;
            }
        }
        return !stripWord(text).isEmpty();
    }

    /**
     * 从选区文本里取出**唯一的一个汉字**；不是恰好一个汉字时返回 null。
     *
     * <p>选区可能带前后空白或标点（长按手柄微微越界），所以先剥掉非汉字再判长度。
     * 「0 个」与「2 个及以上」都返回 null —— 查字典只对单字有意义。
     */
    public static String singleCjkChar(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        int found = -1;
        int count = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (isCjk(ch)) {
                count++;
                if (found < 0) found = i;
            }
        }
        if (count != 1) return null;
        return String.valueOf(t.charAt(found));
    }

    /** 与 {@code ChinDictManager.isCjk} 同口径：基本区 + 扩展 A */
    private static boolean isCjk(char ch) {
        return (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF);
    }

    /** ECDICT 的释义是「字面量 \n」分隔的多行文本 */
    private static List<String> splitTranslations(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\\\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** ECDICT 的 tag 是空格分隔的词表标签（zk/gk/cet4/cet6/ky/toefl/ielts/gre） */
    private static List<String> splitTags(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String t : raw.trim().split("\\s+")) {
            if (!t.isEmpty()) out.add(t.toUpperCase());
        }
        return out;
    }

    /** 中文释义按**空行**分条（源数据如此），单换行是排版换行 */
    private static List<String> splitDefinitions(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String block : raw.split("\n\n")) {
            String t = block.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 缓存写入：超过上限整体清空（简单、无锁竞争、内存可控） */
    private <T> void put(Map<String, List<T>> cache, String key, List<T> value) {
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(key, value);
    }

    // ------------------------------------------------------------------
    // 返回结构
    // ------------------------------------------------------------------

    /** 英汉词条。字段 public final 便于 Jackson 直接序列化（与项目内其它 DTO 同风格） */
    public static class EnglishEntry {
        public final String word;
        public final String phonetic;
        public final List<String> translations;
        public final String exchange;
        public final List<String> tags;
        public final int collins;
        public final int oxford;

        public EnglishEntry(String word, String phonetic, List<String> translations,
                            String exchange, List<String> tags, int collins, int oxford) {
            this.word = word;
            this.phonetic = phonetic;
            this.translations = translations;
            this.exchange = exchange;
            this.tags = tags;
            this.collins = collins;
            this.oxford = oxford;
        }
    }

    /** 中文单字词条：一个读音一条（多音字会返回多条） */
    public static class ChineseEntry {
        public final String pinyin;
        public final List<String> definitions;

        public ChineseEntry(String pinyin, List<String> definitions) {
            this.pinyin = pinyin;
            this.definitions = definitions;
        }
    }
}
