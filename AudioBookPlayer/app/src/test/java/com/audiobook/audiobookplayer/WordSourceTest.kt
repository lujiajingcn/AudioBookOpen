package com.audiobook.audiobookplayer

import com.audiobook.audiobookplayer.model.WordBookEntry
import com.audiobook.audiobookplayer.model.WordSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WordSource] 出处组装 / 跳转可用性判定的纯函数测试。
 *
 * 这些是「收藏时记录书籍 + 句子位置」「单词本长按跳转到原书」两条链路里
 * 不依赖 Android 运行时的判定，锁住三件事：
 *  1. bookKey 口径与覆盖图一致（s: > t: > n:，本地书 id 落 null）；
 *  2. 句子原文截断、空串归一，避免把整段正文/空出处上云；
 *  3. **旧收藏（出处全 null）一律禁用跳转** —— 绝不崩、绝不白屏。
 */
class WordSourceTest {

    // ---------- build：bookKey 三端口径 ----------

    @Test
    fun storeBookWinsWithSPrefix() {
        val s = WordSource.build(
            bookName = "any.pdf",
            storeBookId = 42L,
            serverTaskId = 41L,
            lineNumber = 12,
            sentenceText = "hello world"
        )
        assertEquals("s:42", s.bookKey)
        assertEquals(42L, s.storeBookId)
        assertEquals("any.pdf", s.bookName)
        assertEquals(12, s.lineNumber)
        assertEquals("hello world", s.sentenceText)
    }

    @Test
    fun taskBookUsesTPrefix() {
        val s = WordSource.build(
            bookName = "any.pdf",
            storeBookId = -1L,
            serverTaskId = 88L,
            lineNumber = null,
            sentenceText = "hello"
        )
        assertEquals("t:88", s.bookKey)
        // -1 = 本地/未指定，按 wire 约定「本地书为 null」
        assertNull(s.storeBookId)
    }

    @Test
    fun localBookFallsBackToNormalizedName() {
        val s = WordSource.build(
            bookName = "Book.PDF",
            storeBookId = -1L,
            serverTaskId = -1L,
            lineNumber = 3,
            sentenceText = "hello"
        )
        // 归一化去扩展名 + 小写，与 CoverageCodec.bookKey 完全一致
        assertEquals("n:book", s.bookKey)
        assertNull(s.storeBookId)
    }

    @Test
    fun nonPositiveIdsIgnored() {
        val s = WordSource.build(
            bookName = "My Book",
            storeBookId = 0L,
            serverTaskId = 0L,
            lineNumber = 0,
            sentenceText = "hello"
        )
        assertEquals("n:my book", s.bookKey)
        assertNull(s.storeBookId)
        // 行号/段号只收 1-based 正整数；0 视为未知
        assertNull(s.lineNumber)
        assertNull(s.segmentNumber)
    }

    // ---------- build：字段清洗 ----------

    @Test
    fun sentenceTextTruncatedToLimit() {
        val long = "x".repeat(WordSource.MAX_SENTENCE_LEN + 500)
        val s = WordSource.build(
            bookName = "B", storeBookId = null, serverTaskId = null,
            lineNumber = 1, sentenceText = long
        )
        assertEquals(WordSource.MAX_SENTENCE_LEN, s.sentenceText!!.length)
    }

    @Test
    fun blankFieldsBecomeNull() {
        val s = WordSource.build(
            bookName = "   ",
            storeBookId = null,
            serverTaskId = null,
            lineNumber = null,
            sentenceText = "   "
        )
        assertNull(s.bookName)
        assertNull(s.bookKey)
        assertNull(s.sentenceText)
        assertFalse(WordSource.hasSource(s))
    }

    @Test
    fun segmentNumberDefaultsToNull() {
        // 阅读页落点只有行号 + 行原文，段号不强算，留 null
        val s = WordSource.build(
            bookName = "B", storeBookId = null, serverTaskId = null,
            lineNumber = 5, sentenceText = "hello"
        )
        assertNull(s.segmentNumber)
    }

    // ---------- hasSource：与服务端 WordBookItemRequest.hasSource() 同口径 ----------

    @Test
    fun hasSourceBookNameOnly() {
        assertTrue(WordSource.hasSource(WordSource(bookName = "Book")))
    }

    @Test
    fun hasSourceBookKeyOnly() {
        assertTrue(WordSource.hasSource(WordSource(bookKey = "s:42")))
    }

    @Test
    fun hasSourceFalseForNullOrEmpty() {
        assertFalse(WordSource.hasSource(null))
        assertFalse(WordSource.hasSource(WordSource()))
        assertFalse(WordSource.hasSource(WordSource(bookName = "  ", bookKey = "")))
    }

    // ---------- canJumpToBook：旧收藏 / null 出处必须禁用跳转 ----------

    @Test
    fun nullSourceDisablesJump() {
        assertFalse(WordSource.canJumpToBook(null))
    }

    @Test
    fun legacyEntryWithNoSourceDisablesJump() {
        // v13 之前 / 服务端返回 null 的旧收藏：6 列全 null
        val legacy = WordSource()
        assertFalse(WordSource.canJumpToBook(legacy))
        assertFalse(
            WordSource.canJumpToBook(legacy.bookName, legacy.sentenceText, legacy.lineNumber)
        )
    }

    @Test
    fun bookNameAloneCannotJump() {
        // 只有书名不知道定位到哪一句（SEGMENT/LINE/TEXT 都没有）
        assertFalse(WordSource.canJumpToBook(WordSource(bookName = "Book")))
        assertFalse(WordSource.canJumpToBook("Book", null, null))
        assertFalse(WordSource.canJumpToBook("Book", "", 0))
    }

    @Test
    fun anchorWithoutBookNameCannotJump() {
        // 只有锚点句却不知道打开哪本书
        assertFalse(WordSource.canJumpToBook(WordSource(sentenceText = "hello")))
        assertFalse(WordSource.canJumpToBook(null, "hello", 7))
    }

    @Test
    fun sentenceTextIsFirstClassAnchor() {
        val s = WordSource(bookName = "Book", sentenceText = "the quick brown fox")
        assertTrue(WordSource.canJumpToBook(s))
        assertTrue(WordSource.canJumpToBook("Book", "the quick brown fox", null))
    }

    @Test
    fun lineNumberAloneIsEnoughAsFallback() {
        // 行号虽只是 hint，但没有句子原文时仍可兜底定位（前提是书名在）
        val s = WordSource(bookName = "Book", lineNumber = 23)
        assertTrue(WordSource.canJumpToBook(s))
        assertTrue(WordSource.canJumpToBook("Book", null, 23))
    }

    @Test
    fun blankBookNameDisablesJump() {
        assertFalse(WordSource.canJumpToBook(WordSource(bookName = "  ", sentenceText = "hello")))
        assertFalse(WordSource.canJumpToBook("", "hello", null))
    }

    // ---------- WordBookEntry 默认值：保证既有构造点不炸 ----------

    @Test
    fun entryLegacyConstructorStillCompilesWithNullSource() {
        val e = WordBookEntry(sw = "hood", word = "Hood")
        assertNull(e.bookName)
        assertNull(e.bookKey)
        assertNull(e.storeBookId)
        assertNull(e.segmentNumber)
        assertNull(e.lineNumber)
        assertNull(e.sentenceText)
        // 旧条目落到 Fragment 里，长按会被禁用跳转并提示重新收藏
        assertFalse(WordSource.canJumpToBook(e.bookName, e.sentenceText, e.lineNumber))
    }
}
