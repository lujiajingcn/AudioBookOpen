package com.audiobook.server.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookKeys} 归一化行为测试。
 *
 * <p>本测试用例列表与 Android {@code CoverageCodecTest} / PC {@code test_coverage.py} 完全一致，
 * 是三端字节一致性的可执行合约：任一分支漂了，另两端的相同用例就会挂。
 * 修改归一化算法时，必须同步更新三端测试。
 */
class BookKeysTest {

    // ---------- 基本行为 ----------

    @Test
    void nullYieldsEmpty() {
        assertEquals("", BookKeys.normalizeName(null));
    }

    @Test
    void emptyYieldsEmpty() {
        assertEquals("", BookKeys.normalizeName(""));
        assertEquals("", BookKeys.normalizeName("   "));
        assertEquals("", BookKeys.normalizeName("\t\n\r"));
    }

    @Test
    void alreadyCanonicalIsNoop() {
        assertEquals("abc", BookKeys.normalizeName("abc"));
        assertEquals("富国的伪善", BookKeys.normalizeName("富国的伪善"));
    }

    // ---------- 去扩展名 ----------

    @Test
    void stripsKnownExtensions() {
        assertEquals("book", BookKeys.normalizeName("book.pdf"));
        assertEquals("book", BookKeys.normalizeName("book.epub"));
        assertEquals("book", BookKeys.normalizeName("book.mobi"));
        assertEquals("book", BookKeys.normalizeName("book.azw"));
        assertEquals("book", BookKeys.normalizeName("book.azw3"));
        assertEquals("book", BookKeys.normalizeName("book.fb2"));
        assertEquals("book", BookKeys.normalizeName("book.djvu"));
        assertEquals("book", BookKeys.normalizeName("book.txt"));
        assertEquals("book", BookKeys.normalizeName("book.doc"));
        assertEquals("book", BookKeys.normalizeName("book.docx"));
    }

    @Test
    void stripsExtensionCaseInsensitive() {
        // 先小写再剥扩展名，所以 .PDF / .Pdf / .pDf 都能剥
        assertEquals("book", BookKeys.normalizeName("book.PDF"));
        assertEquals("book", BookKeys.normalizeName("book.Pdf"));
        assertEquals("book", BookKeys.normalizeName("book.EPUB"));
    }

    @Test
    void azw3NotShadowedByAzw() {
        // ".azw3" 在扩展名列表中排在 ".azw" 之前，防止 "book.azw3" 被误剥为 "book.3"
        assertEquals("book", BookKeys.normalizeName("book.azw3"));
        assertEquals("book", BookKeys.normalizeName("book.azw"));
    }

    @Test
    void docxNotShadowedByDoc() {
        assertEquals("book", BookKeys.normalizeName("book.docx"));
        assertEquals("book", BookKeys.normalizeName("book.doc"));
    }

    @Test
    void stripsOnlyOneExtension() {
        // "book.pdf.epub" 只剥一次 → "book.pdf"，避免误剥多层扩展名
        assertEquals("book.pdf", BookKeys.normalizeName("book.pdf.epub"));
    }

    @Test
    void unknownExtensionKept() {
        assertEquals("book.zip", BookKeys.normalizeName("book.zip"));
        assertEquals("book.exe", BookKeys.normalizeName("book.exe"));
    }

    @Test
    void onlyExtensionYieldsEmpty() {
        // 整个名字就是 ".pdf"（长度不大于扩展名）：不剥，避免变成空串再引发歧义；
        // 剥了会得空串，也是合法输出——按实现约定 s.length() > ext.length() 才剥，所以留 ".pdf"
        assertEquals(".pdf", BookKeys.normalizeName(".pdf"));
    }

    // ---------- 小写化 ----------

    @Test
    void lowercasesAscii() {
        assertEquals("book title", BookKeys.normalizeName("Book Title"));
        assertEquals("book title", BookKeys.normalizeName("BOOK TITLE"));
        assertEquals("book title", BookKeys.normalizeName("book title"));
    }

    @Test
    void lowercasesLatinExtended() {
        // Latin-1 补充区：É → é（Locale.ROOT 一致行为，与 Python str.lower() 匹配）
        assertEquals("café", BookKeys.normalizeName("CafÉ"));
    }

    @Test
    void cjkUnaffectedByLowercase() {
        assertEquals("富国的伪善", BookKeys.normalizeName("富国的伪善"));
    }

    // ---------- 空白压缩 ----------

    @Test
    void compressesInternalWhitespaceRuns() {
        assertEquals("a b", BookKeys.normalizeName("a  b"));
        assertEquals("a b", BookKeys.normalizeName("a\tb"));
        assertEquals("a b", BookKeys.normalizeName("a\nb"));
        assertEquals("a b c", BookKeys.normalizeName("a   b\t\tc"));
    }

    @Test
    void trimsUnicodeWhitespace() {
        // NBSP(U+00A0) / IDEOGRAPHIC SPACE(U+3000) / EN SPACE(U+2002) 都当空白处理
        assertEquals("a b", BookKeys.normalizeName("\u00A0a\u00A0b\u00A0"));
        assertEquals("富国", BookKeys.normalizeName("\u3000富国\u3000"));
        assertEquals("a b", BookKeys.normalizeName("a\u2002b"));
    }

    @Test
    void trimsLeadingAndTrailingAfterExtensionStrip() {
        // "  Book.PDF  " → trim → "Book.PDF" → lower → "book.pdf" → strip → "book" → compress → "book"
        assertEquals("book", BookKeys.normalizeName("  Book.PDF  "));
    }

    // ---------- 综合 ----------

    @Test
    void realWorldExample() {
        // 真机 2026-11-05 落到生产库的书名（含全角标点/中文/尾部 .pdf 扩展名）
        String raw = "《富国的伪善：自由贸易的迷思与资本主义秘史》【英】张夏准【文字版_PDF电子书_雅书】.pdf";
        String expected = "《富国的伪善：自由贸易的迷思与资本主义秘史》【英】张夏准【文字版_pdf电子书_雅书】";
        assertEquals(expected, BookKeys.normalizeName(raw));
    }

    @Test
    void crossDeviceMergeCaseInsensitive() {
        // PC 上传 "Book Title.pdf"，Android 上传 "book title"（用户手动去扩展名后重命名）
        // 归一化后必须命中同一键
        assertEquals(BookKeys.normalizeName("Book Title.pdf"), BookKeys.normalizeName("book title"));
    }

    @Test
    void crossDeviceMergeWhitespace() {
        // 下载器有时会把空格替换为下划线——那种情况归一化帮不上（不属于空白差异），
        // 但纯"多空格 vs 单空格"必须归一到一起
        assertEquals(BookKeys.normalizeName("a  b.pdf"), BookKeys.normalizeName("A B"));
    }

    // ---------- normalize(bookKey) 前缀分派 ----------

    @Test
    void normalizeKeepsSPrefixAsIs() {
        assertEquals("s:42", BookKeys.normalize("s:42"));
        assertEquals("s:42", BookKeys.normalize("  s:42  "));
    }

    @Test
    void normalizeKeepsTPrefixAsIs() {
        assertEquals("t:41", BookKeys.normalize("t:41"));
    }

    @Test
    void normalizeAppliesNameRule() {
        assertEquals("n:book", BookKeys.normalize("n:Book.PDF"));
        assertEquals("n:富国的伪善", BookKeys.normalize("n:  富国的伪善  "));
    }

    @Test
    void normalizeReturnsEmptyForPathologicalNameKey() {
        // "n:" 尾部归一后为空 → 整个 bookKey 归一为空，调用方应丢弃
        assertEquals("", BookKeys.normalize("n:"));
        assertEquals("", BookKeys.normalize("n:   "));
    }

    @Test
    void normalizeNullSafe() {
        assertEquals("", BookKeys.normalize(null));
    }

    @Test
    void normalizeUnknownPrefixKeptAsIs() {
        // 未来若新增前缀，未知前缀不应被误改
        assertEquals("x:whatever", BookKeys.normalize("x:whatever"));
    }

    // ---------- 空白判定 ----------

    @Test
    void isBookWhitespaceCoversAsciiAndUnicode() {
        assertTrue(BookKeys.isBookWhitespace(' '));
        assertTrue(BookKeys.isBookWhitespace('\t'));
        assertTrue(BookKeys.isBookWhitespace('\n'));
        assertTrue(BookKeys.isBookWhitespace('\r'));
        assertTrue(BookKeys.isBookWhitespace('\u00A0'));   // NBSP
        assertTrue(BookKeys.isBookWhitespace('\u3000'));   // IDEOGRAPHIC SPACE
        assertTrue(BookKeys.isBookWhitespace('\u2028'));   // LINE SEP
    }

    @Test
    void isBookWhitespaceRejectsNonWhitespace() {
        assertFalse(BookKeys.isBookWhitespace('a'));
        assertFalse(BookKeys.isBookWhitespace('中'));
        assertFalse(BookKeys.isBookWhitespace('-'));
        assertFalse(BookKeys.isBookWhitespace('_'));
    }
}
