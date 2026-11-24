package com.audiobook.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 生词本单条请求体（POST /api/word-book 或 /sync 内嵌列表项）。
 *
 * <p>身份 = {@code sw}（客户端已 {@code stripWord} 归一化）；服务端不重算 sw，
 * 也不按 {@code word} 反推 —— 保持与 Android v8 迁移的 sw 唯一键语义一致，
 * 也让 ECDICT 词典命中口径只有一处实现（在客户端）。
 *
 * <p>{@code phonetic} / {@code translations} 都存"词条快照"而非外键，
 * 卸载离线词典后仍可读出；{@code translations} 用 LF（{@code \u000A}）分隔多义项。
 *
 * <p>出处六字段（{@code bookName} 起）全部可空：旧客户端不带出处时服务端
 * **保留**已有出处，避免一次同步把跳转信息抹掉；带出处时按「最近一次」刷新。
 */
public class WordBookItemRequest {

    @NotBlank(message = "sw 不能为空")
    @Size(max = 128, message = "sw 归一化键过长")
    private String sw;

    @NotBlank(message = "word 不能为空")
    @Size(max = 255, message = "单词过长")
    private String word;

    @Size(max = 255, message = "音标过长")
    private String phonetic;

    /**
     * 释义列表字符串，LF（真换行符）分隔。可空 —— 老客户端可能只上报 word/sw。
     * 单条上限 20000 字与收藏对齐，防单条过大。
     */
    @Size(max = 20000, message = "释义内容过长")
    private String translations;

    // ------------------------------------------------------------------
    // 收藏出处（三端单词本「跳转到原书」）
    // ------------------------------------------------------------------

    @Size(max = 255, message = "书名过长")
    private String bookName;

    @Size(max = 255, message = "bookKey 过长")
    private String bookKey;

    /** 书城书 id；本地书传 null */
    private Long storeBookId;

    /** 段号（1-based）；不传表示未知 */
    private Integer segmentNumber;

    /** 行号 hint（定位兜底，行号会随切分粒度漂移） */
    private Integer lineNumber;

    /** 收藏时所在句子/段落原文；定位第一判据，客户端应自行截断 */
    @Size(max = 20000, message = "句子原文过长")
    private String sentenceText;

    /**
     * 本次上报是否携带出处。旧客户端只发 sw/word/phonetic/translations，
     * 此时返回 false —— WordBookService 据此跳过出处刷新，保护已有数据。
     */
    public boolean hasSource() {
        return (bookName != null && !bookName.trim().isEmpty())
                || (bookKey != null && !bookKey.trim().isEmpty());
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
