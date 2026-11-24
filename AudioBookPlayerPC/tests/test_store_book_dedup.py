"""书城书去重回归测试（修复「同一书城书多次下载 → 书架重复出现」）。

覆盖四件事：
1. ``LocalDatabase._migrate()`` 对历史重复行去重：同一 store_book_id 只保留
   「有本地文件优先 → 最近阅读优先 → id 最小」的一行，其余删除。
2. ``books(store_book_id)`` 部分唯一索引：重复 store_book_id 的第二次插入抛 IntegrityError。
3. ``update_store_book_file()``：占位行（无本地文件）回填 file_path，不新建行。
4. ``get_book_by_store_id()``：按 store_book_id 精确取到唯一行。

运行：``python tests/test_store_book_dedup.py``
"""
from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app.core.config import AppConfig  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402
from app.models.models import Book  # noqa: E402


class _TmpDbMixin(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.data_dir = self._tmp.name
        self.config = AppConfig(data_dir=self.data_dir)
        self.db = LocalDatabase(self.config)
        self.addCleanup(self.db.close)

    def _insert_book_raw(self, display_name: str, file_path: str = "",
                         store_book_id=None, last_read_at: str = "") -> int:
        c = self.db._conn()
        c.execute(
            "INSERT INTO books (display_name, file_path, store_book_id, last_read_at) "
            "VALUES (?,?,?,?)",
            (display_name, file_path, store_book_id, last_read_at),
        )
        c.commit()
        return int(c.execute("SELECT last_insert_rowid()").fetchone()[0])


class StoreBookDedupTest(_TmpDbMixin):

    def test_migrate_dedups_historical_duplicates(self):
        """模拟旧库：无唯一索引 + 同一 store_book_id 多行 → _migrate 去重保留有文件的一行。"""
        c = self.db._conn()
        c.execute("DROP INDEX IF EXISTS idx_books_store_book_id")
        c.commit()

        sid = 42
        self._insert_book_raw("Linux命令行大全", "", sid)                    # 占位行
        self._insert_book_raw("Linux命令行大全", "/books/Linux.txt", sid)   # 已下载行
        self._insert_book_raw("Linux命令行大全", "", sid)                    # 又一条占位

        self.db._migrate()

        rows = c.execute(
            "SELECT id, file_path FROM books WHERE store_book_id=?", (sid,)).fetchall()
        self.assertEqual(len(rows), 1, "去重后同一 store_book_id 应只剩一行")
        self.assertEqual(rows[0]["file_path"], "/books/Linux.txt",
                         "应保留有本地文件的那一行")

    def test_unique_index_blocks_duplicate_insert(self):
        """部分唯一索引：同一 store_book_id 第二次插入应抛 IntegrityError。"""
        self._insert_book_raw("书A", "", 7)
        with self.assertRaises(sqlite3.IntegrityError):
            self._insert_book_raw("书A", "", 7)

    def test_update_store_book_file_fills_placeholder(self):
        """占位行回填文件信息，不新建行；未命中返回 False。"""
        sid = 9
        self._insert_book_raw("书B", "", sid)
        before = len(self.db.get_books())

        ok = self.db.update_store_book_file(sid, "/books/B.epub", "epub", True, "作者")
        self.assertTrue(ok)
        after = len(self.db.get_books())
        self.assertEqual(before, after, "回填不应新增行")

        b = self.db.get_book_by_store_id(sid)
        self.assertIsNotNone(b)
        self.assertEqual(b.file_path, "/books/B.epub")
        self.assertEqual(b.file_ext, "epub")
        self.assertTrue(b.has_audio)
        self.assertEqual(b.author, "作者")
        self.assertEqual(b.display_name, "书B", "不应改动 display_name")

        # 未命中返回 False
        self.assertFalse(self.db.update_store_book_file(12345, "/x", "txt", False))

    def test_get_book_by_store_id(self):
        sid = 5
        self._insert_book_raw("书C", "/books/C.txt", sid)
        b = self.db.get_book_by_store_id(sid)
        self.assertIsNotNone(b)
        self.assertEqual(b.store_book_id, sid)
        self.assertIsNone(self.db.get_book_by_store_id(0))
        self.assertIsNone(self.db.get_book_by_store_id(999))


if __name__ == "__main__":
    unittest.main(verbosity=2)
