"""PC 端「跨端阅读位置同步」测试。

**缺陷背景**：三端的阅读位置原先各说各话，同一本书在不同端根本认不出来：
- PC 本地进度主键是 ``local:{bookId}`` / ``store:{storeBookId}``（本机内部编号），
  换台设备就失效；
- 服务端进度以 ``task_id`` 为键 → **无音频的书**（``task_id IS NULL``）压根存不了
  （书级端点直接 400「该书尚未合成音频，无阅读进度可保存」）；
- 书城书的音频常由管理员合成 ⇒ 普通用户调私有任务端点 ``/api/progress`` 必然 403。

修复：进度身份改为**跨端统一的 bookKey**（``s:``/``t:``/``n:``），
与有声覆盖图同一套口径，三端逐字符一致。

本测试覆盖：
1. ``_server_book_key()`` 对各类书算出正确的 bookKey（书城 / 有任务 / 本地名）；
2. 保存进度时**同时**上报 bookKey 口径（跨端）与老端点（兼容老客户端）；
3. 恢复进度时按 bookKey 拉取服务端进度并跳转；
4. 纯阅读（无音频）时，滚动也能记录位置（原先只有播放才更新进度）。

运行：``python tests/test_reader_progress_sync.py``
"""
from __future__ import annotations

import os
import sys
import tempfile
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from PySide6.QtWidgets import QApplication  # noqa: E402

from app.core.config import AppConfig  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402
from app.models.models import LineItem  # noqa: E402
from app.ui.pages.reader_page import ReaderPage  # noqa: E402

_APP = QApplication.instance() or QApplication([])


class _StubApi:
    """记录进度上报的假 ApiClient。"""

    def __init__(self):
        self.book_progress_puts = []   # (bookKey, segment, offsetMs)
        self.book_progress_gets = []    # bookKey
        self.store_puts = []            # (storeBookId, segment, offsetMs)
        self.task_puts = []             # (taskId, segment, offsetMs)
        self.server_progress = {}       # bookKey -> 服务端返回的 dict

    def get_book_progress_raw(self, book_key):
        self.book_progress_gets.append(book_key)
        return self.server_progress.get(book_key, {})

    def put_book_progress(self, book_key, segment, offset_ms):
        self.book_progress_puts.append((book_key, segment, offset_ms))
        return {"success": True}

    def put_store_reading_progress(self, book_id, segment, offset_ms):
        self.store_puts.append((book_id, segment, offset_ms))
        return {"success": True}

    def put_progress(self, task_id, segment, offset_ms):
        self.task_puts.append((task_id, segment, offset_ms))
        return {"success": True}


def _pump(events: int = 12) -> None:
    """让事件循环转起来：后台线程的结果是靠信号回到主线程的。

    不转事件循环就断言 ⇒ 回调还没执行，断言必然失败（这是异步测试的常见坑）。
    """
    app = QApplication.instance()
    for _ in range(events):
        app.processEvents()


class _StubSync:
    """假同步层：记录被调用的 bookKey，并按预设返回服务端进度。"""

    def __init__(self):
        self.fetched = []
        self.pushed = []
        self.server_progress = {}

    def is_available(self):
        return True

    def fetch_book_progress(self, server_book_key):
        self.fetched.append(server_book_key)
        return self.server_progress.get(server_book_key, {})

    def push_book_progress(self, server_book_key, seg, offset_ms):
        self.pushed.append((server_book_key, seg, offset_ms))
        return True

    def reconcile_book(self, book_key):
        from app.core.sync import SyncResult
        return SyncResult()


class ReaderProgressSyncTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.config = AppConfig(data_dir=self._tmp.name)
        self.db = LocalDatabase(self.config)
        self.addCleanup(self.db.close)
        self.api = _StubApi()
        self.sync = _StubSync()
        self.reader = ReaderPage(self.config, self.api, None, self.db)
        self.reader.sync = self.sync
        self.addCleanup(self.reader.deleteLater)
        # 已登录（否则 _save_progress 不会上报）
        self.config.token = "test-token"

    # ---------- bookKey 计算 ----------

    def test_store_book_key_uses_store_prefix(self):
        """书城书 ⇒ s:{storeBookId}（三端一致的身份）。"""
        self.reader.store_book_id = 45
        self.reader._store_title = "无穷的开始"
        self.assertEqual(self.reader._server_book_key(), "s:45")

    def test_local_book_with_task_uses_task_prefix(self):
        """本地书但有服务端任务 ⇒ t:{serverTaskId}。"""
        from app.models.models import Task
        self.reader.book = type("B", (), {"store_book_id": 0, "display_name": "某本书"})()
        self.reader.task = Task(book_name="某本书", server_task_id=42)
        self.assertEqual(self.reader._server_book_key(), "t:42")

    def test_local_book_without_task_uses_name_prefix(self):
        """纯本地书（无任务）⇒ n:{归一化书名}。"""
        from app.models.models import Task
        self.reader.book = type("B", (), {"store_book_id": 0, "display_name": "My Book.PDF"})()
        self.reader.task = Task(book_name="My Book.PDF")
        self.assertEqual(self.reader._server_book_key(), "n:my book")

    # ---------- 上报 ----------

    def test_save_progress_reports_by_book_key(self):
        """保存进度 ⇒ 上报 bookKey 口径（跨端通用）+ 老端点（兼容老客户端）。"""
        self.reader.store_book_id = 45
        self.reader._store_title = "无穷的开始"
        self.reader._book_key = "store:45"
        self.reader.current_segment = 12
        self.reader._offset_ms = 500

        self.reader._save_progress()

        # bookKey 口径
        self.assertEqual(self.api.book_progress_puts, [("s:45", 12, 500)])
        # 老端点也照发（老客户端靠它读）
        self.assertEqual(self.api.store_puts, [(45, 12, 500)])

    def test_save_progress_for_local_book_uses_name_key(self):
        """纯本地书（无任务、无音频）⇒ 也能上报（n: 口径）。"""
        from app.models.models import Task
        self.reader.book = type("B", (), {"store_book_id": 0, "display_name": "Local Book"})()
        self.reader.task = Task(book_name="Local Book")
        self.reader._book_key = "local:1"
        self.reader.current_segment = 3

        self.reader._save_progress()

        self.assertEqual(self.api.book_progress_puts, [("n:local book", 3, 0)])

    # ---------- 恢复 ----------

    def test_restore_progress_pulls_by_book_key(self):
        """恢复进度 ⇒ 按 bookKey 拉服务端进度。"""
        self.reader.store_book_id = 45
        self.reader._store_title = "无穷的开始"
        self.reader._book_key = "store:45"

        self.reader._restore_progress()
        _pump()

        self.assertIn("s:45", self.sync.fetched)

    def test_server_progress_wins_when_newer(self):
        """服务端进度更新 ⇒ 应用它（跳到云端位置）。"""
        self.reader.store_book_id = 45
        self.reader._store_title = "无穷的开始"
        self.reader._book_key = "store:45"
        self.reader.lines = [LineItem(index=i, text=f"行{i}", segment_number=i + 1)
                             for i in range(20)]
        self.reader._seg_first_line = {i + 1: i for i in range(20)}
        # 服务端位置更靠后 ⇒ 应覆盖本地（本地无进度）
        self.sync.server_progress["s:45"] = {
            "hasProgress": True, "segment": 7, "offsetMs": 0,
            "updatedAt": "2099-01-01T00:00:00",
        }

        self.reader._restore_progress()
        _pump()

        self.assertEqual(self.reader.current_segment, 7)

    def test_local_progress_wins_when_newer(self):
        """本地进度更靠后 ⇒ 不被服务端旧位置覆盖。"""
        self.reader.store_book_id = 45
        self.reader._store_title = "无穷的开始"
        self.reader._book_key = "store:45"
        self.reader.lines = [LineItem(index=i, text=f"行{i}", segment_number=i + 1)
                             for i in range(20)]
        self.reader._seg_first_line = {i + 1: i for i in range(20)}
        self.reader.db.save_progress("store:45", 15, 0)
        # 服务端位置更靠前 ⇒ 应保留本地的 15
        self.sync.server_progress["s:45"] = {
            "hasProgress": True, "segment": 3, "offsetMs": 0,
            "updatedAt": "2000-01-01T00:00:00",
        }

        self.reader._restore_progress()
        _pump()

        self.assertEqual(self.reader.current_segment, 15)

    # ---------- 纯阅读（无音频）也要记位置 ----------

    def test_scroll_records_position_without_audio(self):
        """纯阅读（无音频）⇒ 滚动停稳后也要记进度（原先只有播放才更新）。"""
        self.reader.store_book_id = 45
        self.reader._store_title = "无穷的开始"
        self.reader._book_key = "store:45"
        # 无音频：段落序号来自服务端 rowParagraphStart（行→段落下标）
        self.reader.lines = [LineItem(index=i, text=f"行{i}", segment_number=i + 1)
                             for i in range(20)]
        self.reader._seg_first_line = {i + 1: i for i in range(20)}
        self.reader.current_segment = 0     # 还没播过 ⇒ 位置应由滚动决定

        # 模拟「滚动到第 5 行并停稳」
        self.reader._scroll_line_index = 5
        self.reader._on_scroll_settled()

        # 位置应记成第 6 段（行 5 → segment_number 6）
        self.assertEqual(self.reader.current_segment, 6)
        self.assertEqual(self.api.book_progress_puts, [("s:45", 6, 0)])

    def test_scroll_does_not_override_playing_position(self):
        """正在播放 ⇒ 滚动不覆盖播放位置（播放段更准）。"""
        self.reader.store_book_id = 45
        self.reader._store_title = "无穷的开始"
        self.reader._book_key = "store:45"
        self.reader.lines = [LineItem(index=i, text=f"行{i}", segment_number=i + 1)
                             for i in range(20)]
        self.reader.current_segment = 9      # 正在播第 9 段

        self.reader._scroll_line_index = 5
        self.reader._on_scroll_settled()

        self.assertEqual(self.reader.current_segment, 9)


if __name__ == "__main__":
    unittest.main()
