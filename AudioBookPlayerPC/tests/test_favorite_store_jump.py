"""PC 端「收藏跳转书城书」测试。

覆盖两处：
1. :meth:`LocalDatabase.add_favorite` 落库 store_book_id，且 :meth:`get_favorites`
   能读回（书城书收藏时 book_id=0，跳转身份只能靠 store_book_id）。
2. :meth:`LocalDatabase.find_store_book_id_by_name` 的兜底反查：
   - 唯一同名书城书 → 返回 store_book_id；
   - 同名多本 / 无命中 → 返回 None（不误跳）。

运行：``python tests/test_favorite_store_jump.py``
"""
from __future__ import annotations

import os
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app.core.config import AppConfig  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402
from app.models.models import Favorite  # noqa: E402


class _TmpDbMixin(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.config = AppConfig(data_dir=self._tmp.name)
        self.db = LocalDatabase(self.config)
        self.addCleanup(self.db.close)


class FavoriteStoreJumpTest(_TmpDbMixin):

    def _insert_book(self, display_name: str, store_book_id: int) -> int:
        c = self.db._conn()
        c.execute(
            "INSERT INTO books (display_name, file_path, store_book_id) VALUES (?,?,?)",
            (display_name, "", store_book_id))
        c.commit()
        return int(c.execute("SELECT last_insert_rowid()").fetchone()[0])

    def test_add_favorite_persists_store_book_id(self):
        lid = self.db.add_favorite(Favorite(
            book_id=0, text="ls 列出目录", note="", book_name="Linux命令行大全",
            segment_number=8, store_book_id=49))
        favs = self.db.get_favorites()
        self.assertEqual(len(favs), 1)
        self.assertEqual(favs[0].store_book_id, 49)
        self.assertEqual(favs[0].book_id, 0)  # 书城书 book_id=0，跳转只能靠 store_book_id
        self.assertEqual(favs[0].id, lid)

    def test_add_favorite_update_keeps_store_book_id(self):
        # 同一 (book_name, segment_number) 再次收藏应合并更新，而非插新行
        self.db.add_favorite(Favorite(
            book_id=0, text="第一版", note="", book_name="Linux命令行大全",
            segment_number=8, store_book_id=49))
        self.db.add_favorite(Favorite(
            book_id=0, text="第二版", note="", book_name="Linux命令行大全",
            segment_number=8, store_book_id=49))
        favs = self.db.get_favorites()
        self.assertEqual(len(favs), 1)
        self.assertEqual(favs[0].text, "第二版")
        self.assertEqual(favs[0].store_book_id, 49)

    def test_find_store_book_id_by_name_unique(self):
        self._insert_book("Linux命令行大全", 49)
        self.assertEqual(self.db.find_store_book_id_by_name("Linux命令行大全"), 49)

    def test_find_store_book_id_by_name_duplicate_returns_none(self):
        self._insert_book("Linux命令行大全", 49)
        self._insert_book("Linux命令行大全", 50)
        self.assertIsNone(self.db.find_store_book_id_by_name("Linux命令行大全"))

    def test_find_store_book_id_by_name_missing_returns_none(self):
        self.assertIsNone(self.db.find_store_book_id_by_name("不存在的书"))

    def test_find_store_book_id_by_name_ignores_local_books(self):
        # store_book_id 为 NULL 的本地书不应被反查命中
        c = self.db._conn()
        c.execute("INSERT INTO books (display_name, file_path) VALUES (?,?)",
                  ("Linux命令行大全", "/tmp/x.epub"))
        c.commit()
        self.assertIsNone(self.db.find_store_book_id_by_name("Linux命令行大全"))


if __name__ == "__main__":
    unittest.main()
