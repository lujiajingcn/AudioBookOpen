"""PC 端 :mod:`app.core.coverage` 归一化行为测试。

本测试用例列表与服务端 ``BookKeysTest.java`` / Android ``CoverageCodecBookKeyTest.kt`` 完全一致，
是三端字节一致性的可执行合约：任一分支漂了，另两端的相同用例就会挂。
修改归一化算法时，必须同步更新三端测试。

运行：``python -m pytest tests/test_coverage.py -v``（需先安装 pytest）
或不依赖 pytest：``python tests/test_coverage.py``（用 unittest）。
"""
from __future__ import annotations

import os
import sys
import unittest

# 允许直接 ``python tests/test_coverage.py`` 运行——把工程根加到 sys.path
_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app.core import coverage  # noqa: E402


class NormalizeNameTest(unittest.TestCase):

    # ---------- 基本行为 ----------

    def test_none_yields_empty(self):
        self.assertEqual("", coverage.normalize_name(None))

    def test_empty_yields_empty(self):
        self.assertEqual("", coverage.normalize_name(""))
        self.assertEqual("", coverage.normalize_name("   "))
        self.assertEqual("", coverage.normalize_name("\t\n\r"))

    def test_already_canonical_is_noop(self):
        self.assertEqual("abc", coverage.normalize_name("abc"))
        self.assertEqual("富国的伪善", coverage.normalize_name("富国的伪善"))

    # ---------- 去扩展名 ----------

    def test_strips_known_extensions(self):
        for ext in (".pdf", ".epub", ".mobi", ".azw", ".azw3", ".fb2", ".djvu", ".txt", ".doc", ".docx"):
            with self.subTest(ext=ext):
                self.assertEqual("book", coverage.normalize_name("book" + ext))

    def test_strips_extension_case_insensitive(self):
        self.assertEqual("book", coverage.normalize_name("book.PDF"))
        self.assertEqual("book", coverage.normalize_name("book.Pdf"))
        self.assertEqual("book", coverage.normalize_name("book.EPUB"))

    def test_azw3_not_shadowed_by_azw(self):
        self.assertEqual("book", coverage.normalize_name("book.azw3"))
        self.assertEqual("book", coverage.normalize_name("book.azw"))

    def test_docx_not_shadowed_by_doc(self):
        self.assertEqual("book", coverage.normalize_name("book.docx"))
        self.assertEqual("book", coverage.normalize_name("book.doc"))

    def test_strips_only_one_extension(self):
        self.assertEqual("book.pdf", coverage.normalize_name("book.pdf.epub"))

    def test_unknown_extension_kept(self):
        self.assertEqual("book.zip", coverage.normalize_name("book.zip"))
        self.assertEqual("book.exe", coverage.normalize_name("book.exe"))

    def test_only_extension_yields_itself(self):
        # 整个名字就是 ".pdf"（长度不大于扩展名），不剥——与 Java/Kotlin 行为一致
        self.assertEqual(".pdf", coverage.normalize_name(".pdf"))

    # ---------- 小写化 ----------

    def test_lowercases_ascii(self):
        self.assertEqual("book title", coverage.normalize_name("Book Title"))
        self.assertEqual("book title", coverage.normalize_name("BOOK TITLE"))
        self.assertEqual("book title", coverage.normalize_name("book title"))

    def test_lowercases_latin_extended(self):
        self.assertEqual("café", coverage.normalize_name("CafÉ"))

    def test_cjk_unaffected_by_lowercase(self):
        self.assertEqual("富国的伪善", coverage.normalize_name("富国的伪善"))

    # ---------- 空白压缩 ----------

    def test_compresses_internal_whitespace_runs(self):
        self.assertEqual("a b", coverage.normalize_name("a  b"))
        self.assertEqual("a b", coverage.normalize_name("a\tb"))
        self.assertEqual("a b", coverage.normalize_name("a\nb"))
        self.assertEqual("a b c", coverage.normalize_name("a   b\t\tc"))

    def test_trims_unicode_whitespace(self):
        self.assertEqual("a b", coverage.normalize_name("\u00A0a\u00A0b\u00A0"))
        self.assertEqual("富国", coverage.normalize_name("\u3000富国\u3000"))
        self.assertEqual("a b", coverage.normalize_name("a\u2002b"))

    def test_trims_leading_and_trailing_after_extension_strip(self):
        self.assertEqual("book", coverage.normalize_name("  Book.PDF  "))

    # ---------- 综合 ----------

    def test_real_world_example(self):
        raw = "《富国的伪善：自由贸易的迷思与资本主义秘史》【英】张夏准【文字版_PDF电子书_雅书】.pdf"
        expected = "《富国的伪善：自由贸易的迷思与资本主义秘史》【英】张夏准【文字版_pdf电子书_雅书】"
        self.assertEqual(expected, coverage.normalize_name(raw))

    def test_cross_device_merge_case_insensitive(self):
        self.assertEqual(
            coverage.normalize_name("Book Title.pdf"),
            coverage.normalize_name("book title"),
        )

    def test_cross_device_merge_whitespace(self):
        self.assertEqual(
            coverage.normalize_name("a  b.pdf"),
            coverage.normalize_name("A B"),
        )


class NormalizeBookKeyTest(unittest.TestCase):

    def test_keeps_s_prefix_as_is(self):
        self.assertEqual("s:42", coverage.normalize_book_key("s:42"))
        self.assertEqual("s:42", coverage.normalize_book_key("  s:42  "))

    def test_keeps_t_prefix_as_is(self):
        self.assertEqual("t:41", coverage.normalize_book_key("t:41"))

    def test_applies_name_rule(self):
        self.assertEqual("n:book", coverage.normalize_book_key("n:Book.PDF"))
        self.assertEqual("n:富国的伪善", coverage.normalize_book_key("n:  富国的伪善  "))

    def test_returns_empty_for_pathological_name_key(self):
        self.assertEqual("", coverage.normalize_book_key("n:"))
        self.assertEqual("", coverage.normalize_book_key("n:   "))

    def test_none_and_empty(self):
        self.assertEqual("", coverage.normalize_book_key(None))
        self.assertEqual("", coverage.normalize_book_key(""))

    def test_unknown_prefix_kept_as_is(self):
        self.assertEqual("x:whatever", coverage.normalize_book_key("x:whatever"))


class BookKeyDispatchTest(unittest.TestCase):

    def test_prefers_store_id(self):
        self.assertEqual("s:42", coverage.book_key(42, 41, "any.pdf"))

    def test_falls_back_to_task_id(self):
        self.assertEqual("t:41", coverage.book_key(None, 41, "any.pdf"))
        self.assertEqual("t:41", coverage.book_key(0, 41, "any.pdf"))

    def test_falls_back_to_normalized_name(self):
        self.assertEqual("n:book", coverage.book_key(None, None, "Book.PDF"))
        self.assertEqual("n:book", coverage.book_key(0, 0, "  book  "))

    def test_empty_when_all_invalid(self):
        self.assertEqual("", coverage.book_key(None, None, None))
        self.assertEqual("", coverage.book_key(0, 0, "  "))

    def test_dot_pdf_only_yields_key(self):
        # ".pdf" 长度不大于 ".pdf"，按实现约定不剥，归一化后是 ".pdf"（非空）
        self.assertEqual("n:.pdf", coverage.book_key(None, None, ".pdf"))


class IsBookWhitespaceTest(unittest.TestCase):

    def test_covers_ascii_and_unicode(self):
        for ch in (" ", "\t", "\n", "\r", "\u00A0", "\u3000", "\u2028"):
            with self.subTest(ch=hex(ord(ch))):
                self.assertTrue(coverage._is_book_ws(ch))

    def test_rejects_non_whitespace(self):
        for ch in ("a", "中", "-", "_"):
            with self.subTest(ch=ch):
                self.assertFalse(coverage._is_book_ws(ch))


class CrossTierContractTest(unittest.TestCase):
    """三端合约锁：把服务端 BookKeysTest / Android CoverageCodecBookKeyTest 的关键 case
    也放这里，任何一端漂了就在此断链。修改归一化算法必须同步更新三处。"""

    CANONICAL_CASES = [
        # (input, expected)
        (None, ""),
        ("", ""),
        ("   ", ""),
        ("abc", "abc"),
        ("富国的伪善", "富国的伪善"),
        ("book.pdf", "book"),
        ("book.epub", "book"),
        ("book.mobi", "book"),
        ("book.azw", "book"),
        ("book.azw3", "book"),
        ("book.fb2", "book"),
        ("book.djvu", "book"),
        ("book.txt", "book"),
        ("book.doc", "book"),
        ("book.docx", "book"),
        ("book.PDF", "book"),
        ("book.Pdf", "book"),
        ("book.pdf.epub", "book.pdf"),
        ("book.zip", "book.zip"),
        (".pdf", ".pdf"),
        ("Book Title", "book title"),
        ("BOOK TITLE", "book title"),
        ("CafÉ", "café"),
        ("a  b", "a b"),
        ("a\tb", "a b"),
        ("a   b\t\tc", "a b c"),
        ("\u00A0a\u00A0b\u00A0", "a b"),
        ("\u3000富国\u3000", "富国"),
        ("a\u2002b", "a b"),
        ("  Book.PDF  ", "book"),
        (
            "《富国的伪善：自由贸易的迷思与资本主义秘史》【英】张夏准【文字版_PDF电子书_雅书】.pdf",
            "《富国的伪善：自由贸易的迷思与资本主义秘史》【英】张夏准【文字版_pdf电子书_雅书】",
        ),
    ]

    def test_canonical_cases(self):
        for raw, expected in self.CANONICAL_CASES:
            with self.subTest(raw=raw):
                self.assertEqual(expected, coverage.normalize_name(raw))


if __name__ == "__main__":
    unittest.main(verbosity=2)
