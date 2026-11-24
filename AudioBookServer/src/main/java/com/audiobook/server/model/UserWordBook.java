package com.audiobook.server.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 用户生词本词条（跨端同步）。
 *
 * <p>身份口径 = {@code (user_id, sw)}：{@code sw} 是「strip-word」归一化后的键
 * （去非字母数字 + 小写，与客户端 {@code DictionaryManager.stripWord} 一致），
 * 不是原形 {@code word}。用 {@code sw} 而非 {@code word} 作唯一键有两个原因：
 * <ol>
 *   <li>Android 端早就是 {@code sw UNIQUE}（v8 迁移），PC 端要跨端合并必须对齐；</li>
 *   <li>同一词的不同大小写/屈折形式（Apple / apple / apples）在词典命中里
 *       会归到同一条 {@code sw}，避免云端出现「三条几乎一样的收藏」。</li>
 * </ol>
 *
 * <p>{@code translations} 存**换行符（LF, {@code \u000A}）分隔**的义项列表，与
 * Android 本地（字面量 {@code \n} 两字符）在 sync 边界互转。这个字段是词条
 * **快照**而非外键 —— 卸载 55 MB 离线词典后仍能读出，不依赖 ECDICT 数据库。
 *
 * <p>{@code created_at} 只写不改（{@code updatable=false}）；{@code updatedAt}
 * 预留（本期不做词条编辑，将来加"改义项"再启用）。
 *
 * <p>收藏出处（{@code bookName}/{@code bookKey}/{@code storeBookId}/
 * {@code segmentNumber}/{@code lineNumber}/{@code sentenceText}）记录「在哪本书的
 * 哪一句收藏了这个词」，供三端单词本「跳转到原书」。它们**有意不进唯一键**
 * （与 {@link TextFavorite} 的 {@code taskId} 同理）：同一个词在不同书里查到仍合并成一条，
 * 出处取「最近一次带出处的上报」——见 {@code WordBookService.add}。全部可空：
 * 老数据与旧客户端没有出处，客户端应禁用跳转按钮而不是报错。
 */
@Entity
@Table(name = "user_word_books", uniqueConstraints = {
    @UniqueConstraint(name = "uk_word_user_sw",
        columnNames = {"user_id", "sw"})
}, indexes = {
    @Index(name = "idx_word_user_created", columnList = "user_id, created_at")
})
public class UserWordBook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 归一化键（stripWord(word) = 去非字母数字 + 小写），跨端去重身份 */
    @Column(name = "sw", nullable = false, length = 128)
    private String sw;

    /** 原始词形（用户点击的那一次拼写，保留大小写；后续查词典按 sw 匹配） */
    @Column(name = "word", nullable = false, length = 255)
    private String word;

    /** 音标（快照）；可空 */
    @Column(name = "phonetic", length = 255)
    private String phonetic;

    /**
     * 释义列表，LF（真换行符 {@code \u000A}）分隔的字符串。
     * 用 TEXT 是因为长词条 + 多词性 + 例句可达数千字符，VARCHAR 顶不住；
     * 不建 JSON 列是为了保持与 {@link TextFavorite#content} 相同的"客户端约定分隔符"
     * 语义 —— 客户端自己负责 encode/decode，服务端只当字符串搬运。
     */
    @Column(name = "translations", columnDefinition = "TEXT")
    private String translations;

    /** 词条首次收藏时间（@PrePersist 自动填）；updatable=false 防误改 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 预留：将来支持"改释义后重传"时刷新；本期不写入 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ------------------------------------------------------------------
    // 收藏出处（2026-11-07）：三端单词本「跳转到原书」用
    // ------------------------------------------------------------------

    /** 书名 / 展示名；Android 用它查书架并作为 BOOK_TABLE_NAME 打开阅读页 */
    @Column(name = "book_name", length = 255)
    private String bookName;

    /** 跨端统一书键（BookKeys 口径：s:{storeBookId} / t:{taskId} / n:{归一化书名}） */
    @Column(name = "book_key", length = 255)
    private String bookKey;

    /** 书城书 id；Web / PC 用 /reader/:id 或 store_book_opened 深链，本地书为 null */
    @Column(name = "store_book_id")
    private Long storeBookId;

    /** 段号（1-based，与 TextFavorite.segmentNumber / UserBookProgress 口径一致）；Web 定位主判据 */
    @Column(name = "segment_number")
    private Integer segmentNumber;

    /** 行号 hint；Android / PC 按文本定位失败时的兜底。行号会随切分粒度漂移，不能当主键 */
    @Column(name = "line_number")
    private Integer lineNumber;

    /** 收藏时所在的句子/段落原文；跨端定位的第一判据（内容不随切分规则变化） */
    @Column(name = "sentence_text", columnDefinition = "TEXT")
    private String sentenceText;

    public UserWordBook() {
    }

    public UserWordBook(Long userId, String sw, String word, String phonetic, String translations) {
        this.userId = userId;
        // sw 是身份键，客户端必须已经 stripWord 归一化过；服务端不重算 —— 因为
        // stripWord 语义要跟 ECDICT 词典的键归一化严格一致，一处实现两处用（客户端
        // 查词典 & 客户端上报），搬到服务端重复实现容易漂。这里只做兜底 non-blank。
        if (sw == null || sw.isEmpty()) {
            throw new IllegalArgumentException("sw 不能为空");
        }
        if (word == null || word.isEmpty()) {
            throw new IllegalArgumentException("word 不能为空");
        }
        this.sw = sw;
        this.word = word;
        this.phonetic = phonetic;
        this.translations = translations;
    }

    /**
     * 覆盖收藏出处。语义 = 「记最近一次」：调用方（{@code WordBookService.add}）只在
     * 本次上报确实带了出处时才调它；旧客户端不带出处的上报不会走到这里，
     * 因此不会把已有出处清成 null。
     *
     * <p>音标/释义快照**不在这里改** —— 收藏仍是「那一刻的快照」，
     * 出处只是可刷新的定位元数据。
     */
    public void applySource(String bookName, String bookKey, Long storeBookId,
                            Integer segmentNumber, Integer lineNumber, String sentenceText) {
        this.bookName = bookName;
        this.bookKey = bookKey;
        this.storeBookId = storeBookId;
        this.segmentNumber = segmentNumber;
        this.lineNumber = lineNumber;
        this.sentenceText = sentenceText;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSw() {
        return sw;
    }

    public void setSw(String sw) {
        this.sw = sw;
    }

    public String getWord() {
        return word;
    }

    public void setWord(String word) {
        this.word = word;
    }

    public String getPhonetic() {
        return phonetic;
    }

    public void setPhonetic(String phonetic) {
        this.phonetic = phonetic;
    }

    public String getTranslations() {
        return translations;
    }

    public void setTranslations(String translations) {
        this.translations = translations;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getBookName() {
        return bookName;
    }

    public void setBookName(String bookName) {
        this.bookName = bookName;
    }

    public String getBookKey() {
        return bookKey;
    }

    public void setBookKey(String bookKey) {
        this.bookKey = bookKey;
    }

    public Long getStoreBookId() {
        return storeBookId;
    }

    public void setStoreBookId(Long storeBookId) {
        this.storeBookId = storeBookId;
    }

    public Integer getSegmentNumber() {
        return segmentNumber;
    }

    public void setSegmentNumber(Integer segmentNumber) {
        this.segmentNumber = segmentNumber;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }

    public String getSentenceText() {
        return sentenceText;
    }

    public void setSentenceText(String sentenceText) {
        this.sentenceText = sentenceText;
    }
}
