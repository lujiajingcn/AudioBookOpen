package com.audiobook.audiobookplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CoverageCodec] bookKey / normalizeName 归一化行为测试。
 *
 * 本测试用例列表与服务端 `BookKeysTest.java` / PC 端 `test_coverage.py` 完全一致，
 * 是三端字节一致性的可执行合约：任一分支漂了，另两端的相同用例就会挂。
 * 修改归一化算法时，必须同步更新三端测试。
 */
class CoverageCodecBookKeyTest {

    // ---------- 基本行为 ----------

    @Test
    fun nullYieldsEmpty() {
        assertEquals("", CoverageCodec.normalizeName(null))
    }

    @Test
    fun emptyYieldsEmpty() {
        assertEquals("", CoverageCodec.normalizeName(""))
        assertEquals("", CoverageCodec.normalizeName("   "))
        assertEquals("", CoverageCodec.normalizeName("\t\n\r"))
    }

    @Test
    fun alreadyCanonicalIsNoop() {
        assertEquals("abc", CoverageCodec.normalizeName("abc"))
        assertEquals("富国的伪善", CoverageCodec.normalizeName("富国的伪善"))
    }

    // ---------- 去扩展名 ----------

    @Test
    fun stripsKnownExtensions() {
        assertEquals("book", CoverageCodec.normalizeName("book.pdf"))
        assertEquals("book", CoverageCodec.normalizeName("book.epub"))
        assertEquals("book", CoverageCodec.normalizeName("book.mobi"))
        assertEquals("book", CoverageCodec.normalizeName("book.azw"))
        assertEquals("book", CoverageCodec.normalizeName("book.azw3"))
        assertEquals("book", CoverageCodec.normalizeName("book.fb2"))
        assertEquals("book", CoverageCodec.normalizeName("book.djvu"))
        assertEquals("book", CoverageCodec.normalizeName("book.txt"))
        assertEquals("book", CoverageCodec.normalizeName("book.doc"))
        assertEquals("book", CoverageCodec.normalizeName("book.docx"))
    }

    @Test
    fun stripsExtensionCaseInsensitive() {
        assertEquals("book", CoverageCodec.normalizeName("book.PDF"))
        assertEquals("book", CoverageCodec.normalizeName("book.Pdf"))
        assertEquals("book", CoverageCodec.normalizeName("book.EPUB"))
    }

    @Test
    fun azw3NotShadowedByAzw() {
        assertEquals("book", CoverageCodec.normalizeName("book.azw3"))
        assertEquals("book", CoverageCodec.normalizeName("book.azw"))
    }

    @Test
    fun docxNotShadowedByDoc() {
        assertEquals("book", CoverageCodec.normalizeName("book.docx"))
        assertEquals("book", CoverageCodec.normalizeName("book.doc"))
    }

    @Test
    fun stripsOnlyOneExtension() {
        assertEquals("book.pdf", CoverageCodec.normalizeName("book.pdf.epub"))
    }

    @Test
    fun unknownExtensionKept() {
        assertEquals("book.zip", CoverageCodec.normalizeName("book.zip"))
        assertEquals("book.exe", CoverageCodec.normalizeName("book.exe"))
    }

    @Test
    fun onlyExtensionYieldsItself() {
        assertEquals(".pdf", CoverageCodec.normalizeName(".pdf"))
    }

    // ---------- 小写化 ----------

    @Test
    fun lowercasesAscii() {
        assertEquals("book title", CoverageCodec.normalizeName("Book Title"))
        assertEquals("book title", CoverageCodec.normalizeName("BOOK TITLE"))
        assertEquals("book title", CoverageCodec.normalizeName("book title"))
    }

    @Test
    fun lowercasesLatinExtended() {
        assertEquals("café", CoverageCodec.normalizeName("CafÉ"))
    }

    @Test
    fun cjkUnaffectedByLowercase() {
        assertEquals("富国的伪善", CoverageCodec.normalizeName("富国的伪善"))
    }

    // ---------- 空白压缩 ----------

    @Test
    fun compressesInternalWhitespaceRuns() {
        assertEquals("a b", CoverageCodec.normalizeName("a  b"))
        assertEquals("a b", CoverageCodec.normalizeName("a\tb"))
        assertEquals("a b", CoverageCodec.normalizeName("a\nb"))
        assertEquals("a b c", CoverageCodec.normalizeName("a   b\t\tc"))
    }

    @Test
    fun trimsUnicodeWhitespace() {
        assertEquals("a b", CoverageCodec.normalizeName("\u00A0a\u00A0b\u00A0"))
        assertEquals("富国", CoverageCodec.normalizeName("\u3000富国\u3000"))
        assertEquals("a b", CoverageCodec.normalizeName("a\u2002b"))
    }

    @Test
    fun trimsLeadingAndTrailingAfterExtensionStrip() {
        assertEquals("book", CoverageCodec.normalizeName("  Book.PDF  "))
    }

    // ---------- 综合 ----------

    @Test
    fun realWorldExample() {
        val raw = "《富国的伪善：自由贸易的迷思与资本主义秘史》【英】张夏准【文字版_PDF电子书_雅书】.pdf"
        val expected = "《富国的伪善：自由贸易的迷思与资本主义秘史》【英】张夏准【文字版_pdf电子书_雅书】"
        assertEquals(expected, CoverageCodec.normalizeName(raw))
    }

    @Test
    fun crossDeviceMergeCaseInsensitive() {
        assertEquals(
            CoverageCodec.normalizeName("Book Title.pdf"),
            CoverageCodec.normalizeName("book title")
        )
    }

    @Test
    fun crossDeviceMergeWhitespace() {
        assertEquals(
            CoverageCodec.normalizeName("a  b.pdf"),
            CoverageCodec.normalizeName("A B")
        )
    }

    // ---------- normalizeBookKey 前缀分派 ----------

    @Test
    fun normalizeKeepsSPrefixAsIs() {
        assertEquals("s:42", CoverageCodec.normalizeBookKey("s:42"))
        assertEquals("s:42", CoverageCodec.normalizeBookKey("  s:42  "))
    }

    @Test
    fun normalizeKeepsTPrefixAsIs() {
        assertEquals("t:41", CoverageCodec.normalizeBookKey("t:41"))
    }

    @Test
    fun normalizeAppliesNameRule() {
        assertEquals("n:book", CoverageCodec.normalizeBookKey("n:Book.PDF"))
        assertEquals("n:富国的伪善", CoverageCodec.normalizeBookKey("n:  富国的伪善  "))
    }

    @Test
    fun normalizeReturnsEmptyForPathologicalNameKey() {
        assertEquals("", CoverageCodec.normalizeBookKey("n:"))
        assertEquals("", CoverageCodec.normalizeBookKey("n:   "))
    }

    @Test
    fun normalizeNullSafe() {
        assertEquals("", CoverageCodec.normalizeBookKey(null))
    }

    @Test
    fun normalizeUnknownPrefixKeptAsIs() {
        assertEquals("x:whatever", CoverageCodec.normalizeBookKey("x:whatever"))
    }

    // ---------- bookKey 分派 ----------

    @Test
    fun bookKeyPrefersStoreId() {
        assertEquals("s:42", CoverageCodec.bookKey(42L, 41L, "any.pdf"))
    }

    @Test
    fun bookKeyFallsBackToTaskId() {
        assertEquals("t:41", CoverageCodec.bookKey(null, 41L, "any.pdf"))
        assertEquals("t:41", CoverageCodec.bookKey(0L, 41L, "any.pdf"))
    }

    @Test
    fun bookKeyFallsBackToNormalizedName() {
        assertEquals("n:book", CoverageCodec.bookKey(null, null, "Book.PDF"))
        assertEquals("n:book", CoverageCodec.bookKey(0L, 0L, "  book  "))
    }

    @Test
    fun bookKeyEmptyWhenAllInvalid() {
        assertEquals("", CoverageCodec.bookKey(null, null, null))
        assertEquals("", CoverageCodec.bookKey(0L, 0L, "  "))
        // ".pdf" 不是无效输入：长度不大于扩展名，归一化后仍是 ".pdf"，
        // 会产出 "n:.pdf"，由 bookKeyFromDotPdfOnlyYieldsKey 单独断言。
    }

    @Test
    fun bookKeyFromDotPdfOnlyYieldsKey() {
        // ".pdf" 长度不大于 ".pdf"，按实现约定不剥，归一化后是 ".pdf"（非空），所以 bookKey 应产出 "n:.pdf"
        assertEquals("n:.pdf", CoverageCodec.bookKey(null, null, ".pdf"))
    }

    // ---------- 空白判定 ----------

    @Test
    fun isBookWhitespaceCoversAsciiAndUnicode() {
        assertTrue(CoverageCodec.isBookWhitespace(' '))
        assertTrue(CoverageCodec.isBookWhitespace('\t'))
        assertTrue(CoverageCodec.isBookWhitespace('\n'))
        assertTrue(CoverageCodec.isBookWhitespace('\r'))
        assertTrue(CoverageCodec.isBookWhitespace('\u00A0'))
        assertTrue(CoverageCodec.isBookWhitespace('\u3000'))
        assertTrue(CoverageCodec.isBookWhitespace('\u2028'))
    }

    @Test
    fun isBookWhitespaceRejectsNonWhitespace() {
        assertFalse(CoverageCodec.isBookWhitespace('a'))
        assertFalse(CoverageCodec.isBookWhitespace('中'))
        assertFalse(CoverageCodec.isBookWhitespace('-'))
        assertFalse(CoverageCodec.isBookWhitespace('_'))
    }
}
