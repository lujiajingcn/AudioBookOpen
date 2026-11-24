"""阅读页「切书」回归测试。

**缺陷背景**：阅读器页是**单实例复用**的（MainWindow 只创建一个 ReaderPage 放进
QStackedWidget），切书时走 ``open_book()``。原实现有两个问题：

1. **不清理上一次的正文**：``open_book()`` 只改 ``book_id``/``store_book_id`` 并启动
   异步加载，视图上仍留着上一本书的正文。加载是异步的（书城 PDF 实测 5~10s），
   这期间用户看到的就是「上一本书的内容」—— 像是点开了别的书，
   而真正的目标内容要等几秒后才出现。
2. **异步结果无身份校验**：``_apply_loaded`` 无条件把结果贴上去。
   「先点 A 再点 B」时 A 可能后到，A 的正文会把 B 的正文盖掉。

本测试覆盖：
- 切书立即清空正文与书籍身份（不再残留上一本书）；
- 旧请求的结果被丢弃，不会覆盖新书；
- 加载失败时旧请求的失败信息不污染新书的状态栏。

运行：``python tests/test_reader_book_switch.py``
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

# 全局只建一个 QApplication（Qt 不允许重复创建）
_APP = QApplication.instance() or QApplication([])


class _StubApi:
    """记录书城正文请求的假 ApiClient。"""

    def __init__(self):
        self.content_calls = []

    def get_store_book_content(self, store_book_id):
        self.content_calls.append(store_book_id)
        return {"title": f"书{store_book_id}", "paragraphs": [
            {"text": f"第{store_book_id}本书的第一段。", "paragraphIndex": 0},
        ]}

    def get_store_book_segments(self, store_book_id):
        return []

    def get_store_book_toc(self, store_book_id):
        return []


class ReaderBookSwitchTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.config = AppConfig(data_dir=self._tmp.name)
        self.db = LocalDatabase(self.config)
        # 先关库、再删临时目录：反序时 Windows 会因文件仍被占用而删不掉
        # （sqlite 连接未关 → PermissionError）。
        self.addCleanup(self.db.close)
        self.api = _StubApi()
        self.reader = ReaderPage(self.config, self.api, None, self.db)
        self.addCleanup(self.reader.deleteLater)
        # 把异步层换成「手动触发」：真实实现会开线程，测试就无法确定性地
        # 控制「A 的结果在 B 之后才回来」这个时序 —— 而时序正是本测试的核心。
        self._pending = []
        self.reader.run_async = self._fake_run_async

    def _fake_run_async(self, fn, on_result=None, on_error=None):
        """记录本次异步请求，等测试显式调用 flush 时才回调。"""
        self._pending.append((fn, on_result, on_error))

    def _flush(self, index=0, error=None):
        """触发第 index 个挂起的异步请求的回调。"""
        fn, on_result, on_error = self._pending[index]
        if error is not None:
            on_error(error)
        else:
            on_result(fn())

    # ---------- 工具 ----------

    def _loaded_result(self, book_id: int, text: str) -> dict:
        """构造一份「某本书已加载」的结果。"""
        return dict(book=None, task=None, store_title=f"书{book_id}",
                    lines=[LineItem(index=0, text=text, segment_number=None)],
                    segments=[], toc=[], key=f"store:{book_id}")

    # ---------- 用例 ----------

    def test_open_book_clears_previous_content(self):
        """切书必须立刻清空上一本文本 —— 这是「显示的还是上一本书」的根因。"""
        reader = self.reader
        # 先假装 A 已经加载完
        reader.open_book(store_book_id=49)
        self._flush(0)
        self.assertEqual(len(reader.lines), 1)
        self.assertEqual(reader.lines[0].text, "第49本书的第一段。")

        # 切到 B
        reader.open_book(store_book_id=45)

        # 立即（不等异步结果）就应该是空的
        self.assertEqual(reader.lines, [])
        self.assertIsNone(reader.book)
        self.assertIsNone(reader.task)
        self.assertEqual(reader._book_key, "")
        # 视图里不能残留 A 的正文：只剩一行「加载中」提示
        view_lines = reader.line_view._lines
        self.assertEqual(len(view_lines), 1)
        self.assertIn("加载", view_lines[0].text)

    def test_stale_result_is_dropped(self):
        """旧请求的结果不能覆盖新书（先点 A 再点 B，A 后到）。"""
        reader = self.reader
        reader.open_book(store_book_id=49)   # 请求 A
        reader.open_book(store_book_id=45)   # 请求 B（后发起）
        self.assertEqual(len(self._pending), 2)

        # B 先到
        self._flush(1)
        self.assertEqual(reader._store_title, "书45")

        # A 后到 —— 必须被丢弃，不能把 B 的正文盖成 A 的
        self._flush(0)
        self.assertEqual(reader._store_title, "书45")
        self.assertEqual(reader.lines[0].text, "第45本书的第一段。")

    def test_current_result_is_applied(self):
        """当前请求的结果正常生效。"""
        reader = self.reader
        reader.open_book(store_book_id=45)

        self._flush(0)

        self.assertEqual(len(reader.lines), 1)
        self.assertEqual(reader.lines[0].text, "第45本书的第一段。")
        self.assertEqual(reader._store_title, "书45")

    def test_stale_error_does_not_pollute_status(self):
        """旧请求的失败信息不该盖掉新书的状态。"""
        reader = self.reader
        reader.open_book(store_book_id=49)
        reader.open_book(store_book_id=45)

        reader._on_load_error("旧书炸了", reader._load_seq - 1)
        self.assertNotIn("旧书炸了", reader.status.text())

        reader._on_load_error("新书炸了", reader._load_seq)
        self.assertIn("新书炸了", reader.status.text())

    def test_switch_book_bumps_seq(self):
        """每次 open_book 都要让序号自增（请求序号是丢弃旧结果的依据）。"""
        reader = self.reader
        before = reader._load_seq
        reader.open_book(store_book_id=45)
        self.assertEqual(reader._load_seq, before + 1)
        reader.open_book(store_book_id=46)
        self.assertEqual(reader._load_seq, before + 2)

    def test_first_request_seq_is_guarded(self):
        """第一个请求的序号是 1 —— 守卫不能因为「序号为 0/假值」而失效。

        回归点：早先用 ``if seq and ...`` 判空，序号 0 会被 falsy 短路，
        于是「旧结果」被当成「当前结果」贴上去。
        """
        reader = self.reader
        reader.open_book(store_book_id=49)
        self.assertEqual(reader._load_seq, 1)

        # 伪造一份「序号为 0 的旧结果」：必须被丢弃
        reader._apply_loaded(self._loaded_result(99, "过期正文"), 0)
        self.assertEqual(reader.lines, [])


if __name__ == "__main__":
    unittest.main()
