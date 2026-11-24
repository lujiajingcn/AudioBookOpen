package com.audiobook.server.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DictLookupService} 的单测。
 *
 * <p>不起 Spring 容器：直接 new 一个 {@link DictService}（它的构造参数就是词典目录）
 * 传进被测服务，用 SQLite 现场造两个小库。这样测的是**查询口径**本身
 * （sw 归一、释义切分、单字判定），不牵涉 Web 层与部署。
 *
 * <p>覆盖的四个坑（都是 Android 端踩过的，服务端必须一致）：
 * <ol>
 *   <li>按 {@code sw} 而非 {@code word} 查：{@code Running.} 要能命中 {@code running}；</li>
 *   <li>一个 sw 撞多个词形时要**全部返回**（take-off / Take Off / TAKEOFF）；</li>
 *   <li>{@code translation} 是「字面量 \n」分隔，要切成人能读的多行；</li>
 *   <li>中文库只认单字：两个字 / 非汉字一律空。</li>
 * </ol>
 * 另测「只有 dict.db.gz 时首次查询自动解压」——线上词典目录里放的正是 gz。
 */
class DictLookupServiceTest {

    @TempDir
    static Path dictDir;

    private static DictLookupService service;

    @BeforeAll
    static void setUp() throws Exception {
        createEnglishDb(dictDir.resolve("dict.db"));
        createChineseDb(dictDir.resolve("chinese_dict.sqlite"));
        DictService dictService = new DictService(dictDir.toString());
        service = new DictLookupService(dictService);
    }

    // ------------------------------------------------------------------
    // 英汉
    // ------------------------------------------------------------------

    @Test
    @DisplayName("带标点/大写的选区能查到（按 sw 归一，不按 word 精确匹配）")
    void lookupIgnoresPunctuationAndCase() {
        List<DictLookupService.EnglishEntry> hit = service.lookupEnglish("\"Running.\"");
        assertThat(hit).hasSize(1);
        assertThat(hit.get(0).word).isEqualTo("running");
        assertThat(hit.get(0).phonetic).isEqualTo("'rʌniŋ");
    }

    @Test
    @DisplayName("一个 sw 撞多个词形时全部返回，不能只取第一条")
    void lookupReturnsAllHomographs() {
        List<DictLookupService.EnglishEntry> hit = service.lookupEnglish("take-off");
        assertThat(hit).extracting(e -> e.word)
                .containsExactlyInAnyOrder("take-off", "TAKEOFF", "Take Off");
    }

    @Test
    @DisplayName("释义按字面量反斜杠+n 切分（源数据不是真换行）")
    void translationsSplitOnLiteralBackslashN() {
        List<DictLookupService.EnglishEntry> hit = service.lookupEnglish("book");
        assertThat(hit).hasSize(1);
        assertThat(hit.get(0).translations)
                .containsExactly("n. 书", "v. 预订");
        assertThat(hit.get(0).tags).containsExactly("CET4", "GK");
    }

    @Test
    @DisplayName("选中多个词时取最长的那一段（hi hello → hello）")
    void lookupPicksLongestTokenFromMultiWordSelection() {
        List<DictLookupService.EnglishEntry> hit = service.lookupEnglish("hi hello");
        assertThat(hit).hasSize(1);
        assertThat(hit.get(0).word).isEqualTo("hello");
    }

    @Test
    @DisplayName("中文选区不是英文词，返回空（客户端据此提示仅支持查英文）")
    void lookupRejectsChinese() {
        assertThat(DictLookupService.isEnglishWord("汉字")).isFalse();
        assertThat(service.lookupEnglish("这是一个句子")).isEmpty();
        // 纯符号 strip 后为空
        assertThat(service.lookupEnglish("——")).isEmpty();
    }

    @Test
    @DisplayName("查无此词返回空列表而不是 null")
    void lookupUnknownWord() {
        assertThat(service.lookupEnglish("zzzznotaword")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 中文
    // ------------------------------------------------------------------

    @Test
    @DisplayName("单个汉字能查到，释义按空行分条")
    void lookupChineseSingleChar() {
        List<DictLookupService.ChineseEntry> hit = service.lookupChinese("汉");
        assertThat(hit).hasSize(1);
        assertThat(hit.get(0).pinyin).isEqualTo("hàn");
        assertThat(hit.get(0).definitions).containsExactly("朝代名，汉朝。", "汉族。");
    }

    @Test
    @DisplayName("带前后标点的单字也能认（长按手柄容易越界）")
    void lookupChineseWithSurroundingPunctuation() {
        assertThat(DictLookupService.singleCjkChar("「汉」")).isEqualTo("汉");
        assertThat(service.lookupChinese("「汉」")).hasSize(1);
    }

    @Test
    @DisplayName("两个字/非汉字都不是「单字」，返回空")
    void lookupChineseRejectsMultiChar() {
        assertThat(DictLookupService.singleCjkChar("汉字")).isNull();
        assertThat(DictLookupService.singleCjkChar("hello")).isNull();
        assertThat(service.lookupChinese("汉字")).isEmpty();
        assertThat(service.lookupChinese("hello")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 首次解压
    // ------------------------------------------------------------------

    @Test
    @DisplayName("词典目录只有 dict.db.gz 时，首次查询自动解压出裸库")
    void unzipsGzipOnFirstLookup() throws Exception {
        Path dir = Files.createTempDirectory("dict-gz");
        try {
            // 先造一个库，再压成 gz 并删掉裸库 —— 与线上部署形态一致
            Path db = dir.resolve("dict.db");
            createEnglishDb(db);
            gzip(db, dir.resolve("dict.db.gz"));
            Files.delete(db);

            DictLookupService svc = new DictLookupService(new DictService(dir.toString()));
            List<DictLookupService.EnglishEntry> hit = svc.lookupEnglish("running");

            assertThat(hit).hasSize(1);
            assertThat(hit.get(0).word).isEqualTo("running");
            assertThat(Files.exists(dir.resolve("dict.db"))).as("解压后裸库应落盘").isTrue();
            // 半成品不能留在目录里（否则下次会有人拿它开库）
            assertThat(Files.exists(dir.resolve("dict.db.tmp"))).isFalse();
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    // ------------------------------------------------------------------
    // 造库
    // ------------------------------------------------------------------

    private static void createEnglishDb(Path path) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE dict(" +
                    "sw TEXT NOT NULL, word TEXT NOT NULL, phonetic TEXT, translation TEXT, " +
                    "exchange TEXT, tag TEXT, collins INTEGER, oxford INTEGER, " +
                    "PRIMARY KEY(sw, word)) WITHOUT ROWID");
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath())) {
            insertDict(c, "running", "running", "'rʌniŋ", "n. 跑步\\nv. 运行", "p:ran/i:running", "cet4 cet6", 3, 1);
            insertDict(c, "takeoff", "take-off", "'teɪkɒf", "n. 起飞", "", "", 2, 0);
            insertDict(c, "takeoff", "TAKEOFF", "'teɪkɒf", "n. 起飞（大写）", "", "", 0, 0);
            insertDict(c, "takeoff", "Take Off", "'teɪkɒf", "n. 起飞（分词）", "", "", 0, 0);
            insertDict(c, "book", "book", "bʊk", "n. 书\\nv. 预订", "", "cet4 gk", 5, 1);
            insertDict(c, "hello", "hello", "hə'ləʊ", "int. 喂", "", "", 4, 1);
        }
    }

    private static void insertDict(Connection c, String sw, String word, String phonetic,
                                   String translation, String exchange, String tag,
                                   int collins, int oxford) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO dict(sw, word, phonetic, translation, exchange, tag, collins, oxford) "
                        + "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, sw);
            ps.setString(2, word);
            ps.setString(3, phonetic);
            ps.setString(4, translation);
            ps.setString(5, exchange);
            ps.setString(6, tag);
            ps.setInt(7, collins);
            ps.setInt(8, oxford);
            ps.executeUpdate();
        }
    }

    private static void createChineseDb(Path path) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE word (id INTEGER PRIMARY KEY, word TEXT NOT NULL, " +
                    "pinyin TEXT NOT NULL, explanation TEXT NOT NULL)");
            st.executeUpdate("CREATE INDEX idx_word_word ON word(word)");
            st.executeUpdate("INSERT INTO word(word, pinyin, explanation) VALUES(" +
                    "'汉', 'hàn', '朝代名，汉朝。\n\n汉族。')");
        }
    }

    private static void gzip(Path src, Path dst) throws IOException {
        byte[] data = Files.readAllBytes(src);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(dst))) {
            out.write(data);
        }
    }
}
