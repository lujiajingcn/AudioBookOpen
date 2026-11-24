"""PC 端「按项对账」测试（P1 #4：取代全或无 guard + rebuild）。

覆盖 :meth:`LocalDatabase.reconcile_favorites_from_server` 与
:meth:`LocalDatabase.reconcile_words_from_server` 的四条核心不变量：

1. 服务端已知项 upsert 到本地：命中→UPDATE、未命中→INSERT，
   命中且本地原本 server_id IS NULL 时完成回填。
2. 本地已上云行 (server_id 非空) 若不在服务端 items 中 → DELETE（服务端删除镜像）。
3. 本地待上传行 (server_id 为空) 若不在服务端 items 中 → **保留原样**，
   计入 ``pending_missing``，等下次同步重试（旧 guard 想护的东西，现按项处理）。
4. 无云端身份的遗留行（收藏 book_name 为空 / 生词 sw 为空）**完全不动**。

运行：``python tests/test_reconcile.py``
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
from app.models.models import Favorite, WordEntry  # noqa: E402


class _TmpDbMixin(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.config = AppConfig(data_dir=self._tmp.name)
        self.db = LocalDatabase(self.config)
        # addCleanup 是 LIFO：先 close() 释放 SQLite 文件句柄，再删临时目录
        # （否则 Windows 下 TemporaryDirectory.cleanup 报 PermissionError WinError 32）。
        self.addCleanup(self.db.close)


# ======================================================================
# 收藏
# ======================================================================

class FavoritesReconcileTest(_TmpDbMixin):

    def _add_pending(self, book_name: str, seg: int, text: str) -> int:
        """插入一条待上传（server_id 为空）的收藏。"""
        return self.db.add_favorite(Favorite(
            book_id=0, text=text, note="", book_name=book_name,
            segment_number=seg, server_id=None,
        ))

    def _add_synced(self, book_name: str, seg: int, text: str, server_id: int) -> int:
        """插入一条已上云（server_id 非空）的收藏，绕过 add_favorite 的 NULL 硬编码。"""
        c = self.db._conn()
        c.execute(
            "INSERT INTO favorites (book_id, text, note, created_at, book_name, "
            "segment_number, server_id, task_id) VALUES (?,?,?,?,?,?,?,?)",
            (0, text, "", "2026-01-01T00:00:00", book_name, seg, server_id, None))
        c.commit()
        return int(c.execute("SELECT last_insert_rowid()").fetchone()[0])

    def _server_item(self, sid: int, book_name: str, seg: int, content: str,
                     task_id=None) -> dict:
        return {
            "id": sid, "bookName": book_name, "segmentNumber": seg,
            "content": content, "createdAt": "2026-01-01T00:00:00",
            "taskId": task_id,
        }

    def _row(self, fav_id: int):
        return self.db._conn().execute(
            "SELECT * FROM favorites WHERE id=?", (fav_id,)).fetchone()

    def test_insert_new_server_item(self):
        """服务端有、本地无 → INSERT。"""
        stats = self.db.reconcile_favorites_from_server([
            self._server_item(101, "书A", 1, "内容A1"),
        ])
        self.assertEqual({"inserted": 1, "updated": 0, "deleted": 0,
                          "pending_missing": 0}, stats)
        rows = self.db.get_favorites_by_name("书A")
        self.assertEqual(1, len(rows))
        self.assertEqual(101, rows[0].server_id)
        self.assertEqual("内容A1", rows[0].text)

    def test_update_existing_and_backfill_server_id(self):
        """本地待上传 + 服务端已确认 → UPDATE 并回填 server_id（这是最关键的场景）。"""
        local_id = self._add_pending("书A", 5, "本地内容")
        stats = self.db.reconcile_favorites_from_server([
            self._server_item(500, "书A", 5, "服务端内容", task_id=9),
        ])
        self.assertEqual(0, stats["inserted"])
        self.assertEqual(1, stats["updated"])
        self.assertEqual(0, stats["pending_missing"])
        r = self._row(local_id)
        self.assertEqual(500, r["server_id"])
        self.assertEqual("服务端内容", r["text"])
        self.assertEqual(9, r["task_id"])

    def test_delete_synced_row_missing_from_server(self):
        """本地已上云但服务端本次未返回 → DELETE（服务端删/账号切换镜像）。"""
        local_id = self._add_synced("书B", 3, "旧内容", server_id=200)
        stats = self.db.reconcile_favorites_from_server([])   # 服务端空
        self.assertEqual(1, stats["deleted"])
        self.assertIsNone(self._row(local_id))

    def test_pending_missing_kept_untouched(self):
        """本地待上传，服务端未回执 → **保留原样**（P1 #4 核心不变量）。"""
        local_id = self._add_pending("书C", 7, "本机新增还没上云")
        stats = self.db.reconcile_favorites_from_server([])
        self.assertEqual(1, stats["pending_missing"])
        self.assertEqual(0, stats["deleted"])
        r = self._row(local_id)
        self.assertIsNotNone(r)
        self.assertIsNone(r["server_id"])
        self.assertEqual("本机新增还没上云", r["text"])

    def test_partial_confirm_others_still_reconciled(self):
        """混合场景（P1 #4 想解决的核心痛点）：
        3 个待上传 + 2 个已上云；服务端只回执 2 个待上传 + 1 个已上云。
        旧 guard 会因 1 项未确认全部跳过重建；新 reconcile 应：
          - 回执到的 2 待上传 → UPDATE + 回填 server_id
          - 未回执的 1 待上传 → 保留原样，pending_missing=1
          - 回执到的 1 已上云 → UPDATE
          - 未回执的 1 已上云 → DELETE
        """
        p1 = self._add_pending("书D", 1, "p1")
        p2 = self._add_pending("书D", 2, "p2")
        p3 = self._add_pending("书D", 3, "p3")
        s1 = self._add_synced("书D", 10, "s1", server_id=1001)
        s2 = self._add_synced("书D", 20, "s2", server_id=1002)

        stats = self.db.reconcile_favorites_from_server([
            self._server_item(2001, "书D", 1, "p1-server"),
            self._server_item(2002, "书D", 2, "p2-server"),
            # p3 未回执
            self._server_item(1001, "书D", 10, "s1-server"),
            # s2 (server_id=1002) 未回执 → 应删
        ])
        self.assertEqual(0, stats["inserted"])
        self.assertEqual(3, stats["updated"])       # p1/p2/s1
        self.assertEqual(1, stats["deleted"])        # s2
        self.assertEqual(1, stats["pending_missing"])  # p3

        # 具体断言
        self.assertEqual(2001, self._row(p1)["server_id"])
        self.assertEqual(2002, self._row(p2)["server_id"])
        self.assertIsNotNone(self._row(p3))
        self.assertIsNone(self._row(p3)["server_id"])
        self.assertEqual("p3", self._row(p3)["text"])
        self.assertEqual(1001, self._row(s1)["server_id"])
        self.assertIsNone(self._row(s2))

    def test_legacy_row_without_book_name_untouched(self):
        """book_name 为空的极老遗留行 → 不参与对账（不删）。"""
        c = self.db._conn()
        c.execute(
            "INSERT INTO favorites (book_id, text, note, created_at, book_name, "
            "segment_number, server_id, task_id) VALUES (?,?,?,?,?,?,?,?)",
            (7, "老数据", "", "2020-01-01T00:00:00", "", 0, None, None))
        c.commit()
        legacy_id = int(c.execute("SELECT last_insert_rowid()").fetchone()[0])

        stats = self.db.reconcile_favorites_from_server([])
        self.assertEqual(0, stats["deleted"])
        self.assertEqual(0, stats["pending_missing"])
        self.assertIsNotNone(self._row(legacy_id))

    def test_invalid_server_items_skipped(self):
        """服务端 item 缺 bookName / seg=0 / content 空 → 跳过不入 server_map。"""
        stats = self.db.reconcile_favorites_from_server([
            {"id": 1, "bookName": "", "segmentNumber": 5, "content": "x"},
            {"id": 2, "bookName": "书E", "segmentNumber": 0, "content": "x"},
            {"id": 3, "bookName": "书E", "segmentNumber": 1, "content": ""},
            {"id": 4, "bookName": "书E", "segmentNumber": 2, "content": "有效"},
        ])
        self.assertEqual(1, stats["inserted"])
        rows = self.db.get_favorites_by_name("书E")
        self.assertEqual(1, len(rows))
        self.assertEqual(2, rows[0].segment_number)


# ======================================================================
# 生词本
# ======================================================================

class WordsReconcileTest(_TmpDbMixin):

    def _add_pending(self, word: str, sw: str, definition: str = "") -> int:
        return self.db.add_word(WordEntry(
            word=word, phonetic="", definition=definition, sw=sw, server_id=None))

    def _add_synced(self, word: str, sw: str, server_id: int) -> int:
        c = self.db._conn()
        c.execute(
            "INSERT INTO word_book (word, phonetic, definition, created_at, sw, server_id) "
            "VALUES (?,?,?,?,?,?)",
            (word, "", "", "2026-01-01T00:00:00", sw, server_id))
        c.commit()
        return int(c.execute("SELECT last_insert_rowid()").fetchone()[0])

    def _server_item(self, sid: int, sw: str, word: str,
                     translations: str = "", phonetic: str = "") -> dict:
        return {
            "id": sid, "sw": sw, "word": word, "phonetic": phonetic,
            "translations": translations, "createdAt": "2026-01-01T00:00:00",
        }

    def _row_by_sw(self, sw: str):
        return self.db._conn().execute(
            "SELECT * FROM word_book WHERE sw=?", (sw,)).fetchone()

    def test_insert_new_word(self):
        stats = self.db.reconcile_words_from_server([
            self._server_item(300, "apple", "apple", translations="苹果"),
        ])
        self.assertEqual({"inserted": 1, "updated": 0, "deleted": 0,
                          "pending_missing": 0}, stats)
        r = self._row_by_sw("apple")
        self.assertEqual(300, r["server_id"])
        self.assertEqual("苹果", r["definition"])

    def test_backfill_server_id_for_pending(self):
        self._add_pending("banana", "banana", definition="本地释义")
        stats = self.db.reconcile_words_from_server([
            self._server_item(301, "banana", "banana", translations="香蕉"),
        ])
        self.assertEqual(1, stats["updated"])
        r = self._row_by_sw("banana")
        self.assertEqual(301, r["server_id"])
        self.assertEqual("香蕉", r["definition"])   # 服务端权威覆盖本地

    def test_delete_synced_word_missing_from_server(self):
        self._add_synced("cherry", "cherry", server_id=400)
        stats = self.db.reconcile_words_from_server([])
        self.assertEqual(1, stats["deleted"])
        self.assertIsNone(self._row_by_sw("cherry"))

    def test_pending_missing_kept(self):
        self._add_pending("durian", "durian", definition="榴莲")
        stats = self.db.reconcile_words_from_server([])
        self.assertEqual(1, stats["pending_missing"])
        self.assertEqual(0, stats["deleted"])
        r = self._row_by_sw("durian")
        self.assertIsNotNone(r)
        self.assertIsNone(r["server_id"])
        self.assertEqual("榴莲", r["definition"])

    def test_partial_confirm_others_still_reconciled(self):
        """混合场景，与收藏对称。"""
        self._add_pending("elephant", "elephant", definition="大象")
        self._add_pending("tiger", "tiger", definition="老虎")
        self._add_pending("zebra", "zebra", definition="斑马")   # 未回执
        self._add_synced("lion", "lion", server_id=501)
        self._add_synced("wolf", "wolf", server_id=502)          # 未回执 → 删

        stats = self.db.reconcile_words_from_server([
            self._server_item(601, "elephant", "elephant", translations="象"),
            self._server_item(602, "tiger", "tiger", translations="虎"),
            self._server_item(501, "lion", "lion", translations="狮"),
        ])
        self.assertEqual(0, stats["inserted"])
        self.assertEqual(3, stats["updated"])
        self.assertEqual(1, stats["deleted"])          # wolf
        self.assertEqual(1, stats["pending_missing"])  # zebra

        self.assertEqual(601, self._row_by_sw("elephant")["server_id"])
        self.assertEqual(602, self._row_by_sw("tiger")["server_id"])
        self.assertIsNone(self._row_by_sw("zebra")["server_id"])
        self.assertEqual("斑马", self._row_by_sw("zebra")["definition"])
        self.assertEqual(501, self._row_by_sw("lion")["server_id"])
        self.assertIsNone(self._row_by_sw("wolf"))

    def test_legacy_row_without_sw_untouched(self):
        """sw 为空的极老遗留行 → 不参与对账。"""
        c = self.db._conn()
        c.execute(
            "INSERT INTO word_book (word, phonetic, definition, created_at, sw, server_id) "
            "VALUES (?,?,?,?,?,?)",
            ("legacy_word", "", "", "2020-01-01T00:00:00", "", None))
        c.commit()
        stats = self.db.reconcile_words_from_server([])
        self.assertEqual(0, stats["deleted"])
        self.assertEqual(0, stats["pending_missing"])
        row = c.execute(
            "SELECT * FROM word_book WHERE word='legacy_word'").fetchone()
        self.assertIsNotNone(row)

    def test_invalid_server_items_skipped(self):
        stats = self.db.reconcile_words_from_server([
            {"id": 1, "sw": "", "word": "x"},
            {"id": 2, "sw": "a", "word": ""},
            {"id": 3, "sw": "valid", "word": "valid"},
        ])
        self.assertEqual(1, stats["inserted"])
        self.assertIsNotNone(self._row_by_sw("valid"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
