# -*- coding: utf-8 -*-
"""PC 端书架「删除」无效的回归测试。

背景（两个独立缺陷，都会表现为「点了删除没反应」）：

**缺陷 1：只删 books 行，tasks 行残留 ⇒ 任务退化成虚拟条目**
    书架列表是 books ∪ 虚拟条目 的合并（``ShelfPage._merged_entries``）：
    凡是「只有任务、没有书行」的任务都会**补成一条虚拟条目**显示出来。
    而删除的 A/B 两条分支只执行 ``db.remove_book(book.id)``，
    挂在该书上的 tasks 行**原封不动** ⇒ 删掉 books 行后，那些任务
    立刻变成虚拟条目，**书名照旧显示在书架上**。
    ⛔ 判定「删掉了没」不能只看 ``page._rows`` —— 虚拟条目（book.id == 0）
    在 refresh() 里**不入 _rows**（只有 ``if book.id:`` 才入），
    但**确实显示在列表里**。只看 _rows 会误判成「已删除」。

**缺陷 2：服务端 DELETE 是异步的 ⇒ 并发同步把刚删的书复活**
    移出书架时服务端 DELETE 走 ``run_async``（要等一个网络往返），
    而同步的 ``get_shelf`` 快照可能取自删除之前 ⇒ ``upsert_store_shelf_book``
    把刚删的书**重新插回本地** ⇒ 用户看到「书闪一下又回来」。
    修复：删除时先落「本机已移出」墓碑（``shelf_dismissals``），
    同步跳过有墓碑的书；用户重新加入时清墓碑。

运行：``python tests/test_shelf_delete.py``（需 PySide6，可离线跑）。
"""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication, QMessageBox  # noqa: E402

from app.core.config import AppConfig  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402
from app.models.models import Book, Task, TaskStatus  # noqa: E402
from app.ui.pages.shelf_page import ShelfPage  # noqa: E402


def _shelf_titles(page) -> list:
    """书架上**实际显示**的标题（见模块 docstring：不能只看 _rows）。"""
    out = []
    for i in range(page.list.count()):
        item = page.list.item(i)
        w = page.list.itemWidget(item)
        out.append(w.book.display_name if w is not None else item.text())
    return out


class ShelfDeleteTest(unittest.TestCase):
    def setUp(self):
        self.app = QApplication.instance() or QApplication([])
        import tempfile
        self.tmp = tempfile.mkdtemp()
        self.cfg = AppConfig(data_dir=self.tmp)
        self.db = LocalDatabase(self.cfg)

        class _FakeApi:
            def __init__(self):
                self.removed = []

            def remove_from_shelf(self, store_book_id):
                self.removed.append(int(store_book_id))
                return {"removed": True, "success": True}

        self.api = _FakeApi()
        self.page = ShelfPage(self.cfg, self.api, None, self.db)

        # 一律「确认」
        self._orig_q = QMessageBox.question
        QMessageBox.question = staticmethod(
            lambda p, t, x, *a, **kw: QMessageBox.Yes)

    def tearDown(self):
        QMessageBox.question = self._orig_q
        try:
            self.db.close()
        except Exception:  # noqa: BLE001
            pass

    def _pump(self, n: int = 12):
        for _ in range(n):
            self.app.processEvents()

    def _add_book(self, **kwargs):
        self.db.add_book(Book(id=0, **kwargs))
        return self.db.get_books()[-1]

    def _add_task(self, book_name, server_task_id=None):
        """建一条任务行。

        ⛔ ``server_task_id`` 必须区分：本机合成任务用 None
        （见 tts_page.py 的 Task(...) 构造），云端任务用真实 id。
        upsert_task 是按 server_task_id 做 upsert 的 ⇒ 传同一个 id 会**互相覆盖**。
        """
        self.db.upsert_task(Task(
            id=0, book_name=book_name,
            status=TaskStatus.COMPLETED.value,
            server_task_id=server_task_id,
            total_segments=1, completed_segments=1))

    # ---------- 缺陷 1 ----------

    def test_store_book_with_task_actually_disappears(self):
        """书城书 + 有任务：删除后标题必须从书架消失。"""
        b = self._add_book(display_name="书城书甲", file_path="", file_ext="pdf",
                            has_audio=False, store_book_id=45)
        self._add_task(b.display_name, server_task_id=99)
        self.page.refresh()
        self._pump()
        self.assertIn("书城书甲", _shelf_titles(self.page))

        self.page._delete_book(next(iter(self.page._rows.values())))
        self._pump()
        self.page.refresh()
        self._pump()

        self.assertNotIn("书城书甲", _shelf_titles(self.page),
                         "书名仍在书架上 ⇒ 任务退化成虚拟条目了")
        self.assertEqual([], self.db.get_books())
        self.assertEqual([], self.db.get_tasks())

    def test_local_book_with_task_actually_disappears(self):
        """纯本地书 + 有任务：同样不能退化成虚拟条目。"""
        b = self._add_book(display_name="本地书乙", file_path="/tmp/b.txt",
                            file_ext="txt", has_audio=False)
        self._add_task(b.display_name)
        self.page.refresh()
        self._pump()

        self.page._delete_book(next(iter(self.page._rows.values())))
        self._pump()
        self.page.refresh()
        self._pump()

        self.assertNotIn("本地书乙", _shelf_titles(self.page))
        self.assertEqual([], self.db.get_tasks())

    def test_book_without_task_still_works(self):
        """无任务的普通书：删除照旧（回归护栏）。"""
        self._add_book(display_name="本地书丙", file_path="/tmp/c.txt",
                        file_ext="txt", has_audio=False)
        self.page.refresh()
        self._pump()

        self.page._delete_book(next(iter(self.page._rows.values())))
        self._pump()
        self.page.refresh()
        self._pump()

        self.assertNotIn("本地书丙", _shelf_titles(self.page))
        self.assertEqual([], self.db.get_books())

    def test_other_book_task_not_deleted(self):
        """只删**同名**的任务：别的书的任务不能被连带删除。"""
        b = self._add_book(display_name="书城书丁", file_path="", file_ext="pdf",
                            has_audio=False, store_book_id=46)
        self._add_task(b.display_name)
        self._add_task("别的书的任务", server_task_id=1234)
        self.page.refresh()
        self._pump()

        self.page._delete_book(next(iter(self.page._rows.values())))
        self._pump()

        names = [t.book_name for t in self.db.get_tasks()]
        self.assertEqual(["别的书的任务"], names)

    # ---------- 缺陷 2 ----------

    def test_deletion_leaves_tombstone(self):
        """移出书城书要落墓碑，防止并发同步把它复活。"""
        self._add_book(display_name="书城书戊", file_path="", file_ext="pdf",
                        has_audio=False, store_book_id=47)
        self.page.refresh()
        self._pump()

        self.page._delete_book(next(iter(self.page._rows.values())))
        self._pump()

        self.assertTrue(self.db.is_store_book_dismissed(47),
                        "缺墓碑 ⇒ 同步会把刚删的书 upsert 回来")
        self.assertEqual([47], self.api.removed)

    def test_sync_skips_dismissed_book(self):
        """同步拉取时必须跳过有墓碑的书。"""
        self.db.dismiss_store_book(48)
        # 造一条假的「服务端书架」返回
        self.api.get_shelf = lambda: [{
            "storeBookId": 48, "title": "书城书己",
            "author": "", "category": "store", "hasAudio": False,
        }]
        from app.core.sync import SyncManager
        sync = SyncManager(self.cfg, self.api, self.db)
        sync._pull_shelf(type("R", (), {"errors": [], "shelf_synced": 0,
                                         "shelf_removed": 0})())

        self.assertEqual([], self.db.get_books(),
                         "有墓碑的书被同步拉回来了")

    def test_sync_still_pulls_non_dismissed_book(self):
        """没有墓碑的书照常被同步拉进来（别把同步整体搞坏）。"""
        self.api.get_shelf = lambda: [{
            "storeBookId": 50, "title": "书城书庚",
            "author": "", "category": "store", "hasAudio": False,
        }]
        from app.core.sync import SyncManager
        sync = SyncManager(self.cfg, self.api, self.db)
        sync._pull_shelf(type("R", (), {"errors": [], "shelf_synced": 0,
                                         "shelf_removed": 0})())

        books = self.db.get_books()
        self.assertEqual(1, len(books))
        self.assertEqual(50, books[0].store_book_id)

    def test_expired_tombstone_does_not_block_forever(self):
        """过期墓碑必须失效：否则用户在别处重新加入的书，本机永远看不到。"""
        import time
        self.db.dismiss_store_book(52)
        self.assertIn(52, self.db.list_dismissed_store_book_ids())

        # 把墓碑时间戳改到很久以前（模拟过期）
        self.db._conn().execute(
            "UPDATE shelf_dismissals SET dismissed_at='2000-01-01T00:00:00' WHERE store_book_id=52")
        self.db._conn().commit()

        self.assertNotIn(52, self.db.list_dismissed_store_book_ids(),
                         "过期墓碑仍然生效 ⇒ 书永远回不来")
        # 且过期墓碑应被顺手清掉
        self.assertFalse(self.db.is_store_book_dismissed(52))

    def test_task_dismissal_cleared_after_task_row_deleted(self):
        """**回归**：移除书架时 tasks 行已被删掉，任务墓碑必须仍能被清掉。

        墓碑原先靠 ``tasks`` 表反查 server_task_id —— 但删除时 tasks 行
        已经删了 ⇒ 反查落空 ⇒ 墓碑永远清不掉 ⇒ 用户重新加入该书后，
        书回来了却显示「未合成」（合成任务拉不回来）。
        修复：墓碑表自带 ``book_name``，清理时直接按书名查墓碑表。
        """
        self._add_task("书城书丙", server_task_id=910)
        # 模拟删除：先删 tasks 行，再落带书名的墓碑
        for t in self.db.get_tasks():
            if t.book_name == "书城书丙":
                self.db.delete_task(t.id)
                self.db.dismiss_task(t.server_task_id, "书城书丙")
        self.assertTrue(self.db.is_task_dismissed(910))
        self.assertEqual([t for t in self.db.get_tasks() if t.book_name == "书城书丙"], [],
                         "前置条件：tasks 行已被删除")

        self.db.clear_task_dismissals_for_book("书城书丙")
        self.assertFalse(self.db.is_task_dismissed(910),
                         "tasks 行已删时任务墓碑清不掉 ⇒ 重新加入后书显示「未合成」")

    def test_task_dismissal_cleared_for_book(self):
        """重新加入书时要清掉它的任务墓碑，否则书回来了却显示「未合成」。"""
        self._add_task("书城书辛", server_task_id=777)
        self.db.dismiss_task(777)
        self.assertTrue(self.db.is_task_dismissed(777))

        self.db.clear_task_dismissals_for_book("书城书辛")
        self.assertFalse(self.db.is_task_dismissed(777),
                         "任务墓碑没清 ⇒ 重新加入后合成任务拉不回来")

    def test_task_dismissal_only_affects_named_book(self):
        """清墓碑只影响同名书的任务。"""
        self._add_task("书甲", server_task_id=801)
        self._add_task("书乙", server_task_id=802)
        self.db.dismiss_task(801)
        self.db.dismiss_task(802)

        self.db.clear_task_dismissals_for_book("书甲")
        self.assertFalse(self.db.is_task_dismissed(801))
        self.assertTrue(self.db.is_task_dismissed(802),
                        "别的书的墓碑被误清了")

    def test_tombstone_cleared_when_readded(self):
        """用户重新加入该书 ⇒ 墓碑要清掉，否则它永远回不来。"""
        self.db.dismiss_store_book(51)
        self.assertTrue(self.db.is_store_book_dismissed(51))
        self.db.clear_store_book_dismissal(51)
        self.assertFalse(self.db.is_store_book_dismissed(51))


if __name__ == "__main__":
    unittest.main(verbosity=2)
