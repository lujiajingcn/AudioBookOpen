"""单词本「跳转到原书」的**真实 GUI** 回归测试（PC 端）。

为什么单开一个文件：``test_word_book_source.py`` 里那一批是**源码静态断言**
（用字符串检索证明 wiring 接上了），成本低但抓不到「真跑起来不работает」的坑。
本文件相反：真建 QWidget、真走 profile_page._on_word_context_menu、
真走 reader_page.open_book(jump=...)。

⚠️ **为什么用假 QMenu 而不是真弹窗**（2026-11-07 实测踩坑，重要）：
    想自动化「用户点了菜单项」有两条路，**两条都不可用**：
      a) ``QAction.trigger()`` —— **不行**：实测 ``QMenu.exec()`` 在这条路径下返回
         **None**（而不是被触发的 action）⇒ 产品代码里
         ``if menu.exec(...) is not act: return`` 这道守卫会把这次点击判成
         「用户没选中」而静默返回 —— 菜单点了跟没点一样。
      b) ``QTest.mouseClick(menu, ...)`` —— 在 offscreen/headless 下**会崩**：
         实测整套测试跑下来会撞 **native access violation (0xC0000005)**，
         且崩不崩取决于前面跑了哪些用例（Qt 全局状态污染），没法稳定复现。
         连「裸 QMenu + 零产品代码」的最小复现都崩 ⇒ 是 headless Qt 的通病。
      c) 直接给 ``QMenu.exec`` 打补丁 —— **也不行**：PySide6 里
         ``QMenu.exec = fake`` 会让后续真调用的 C++ 对象状态错乱，同样崩。
    ⇒ 最终方案：**替换 profile_page 模块里的 QMenu 名字**（模块级注入点），
      换成只实现 ``addAction`` / ``exec`` 的假类：
        - 菜单项**启用** ⇒ exec 返回该 action（= 用户点中了它）
        - 菜单项**禁用** ⇒ exec 返回 None（= 用户点不动、什么都没选中）
      产品代码**一行不改**地全跑：has_source 判定 → setEnabled → exec 返回值守卫
      → _resolve_word_book_target → emit word_jump_requested。
      这样既避开了脆弱的原生模态弹窗，又把真正要验的逻辑全验了。

    另有两条独立铁律（本次一并验证）：
      - 「不带出处」的旧收藏 ⇒ 菜单项禁用 ⇒ 不发信号；
      - 出处能解析成本机 book_id ⇒ 才谈得上跳转。

运行（需要 PySide6）::

    cd AudioBookPlayerPC
    QT_QPA_PLATFORM=offscreen python -m unittest tests.test_word_book_gui -v
"""
from __future__ import annotations

import os
import sys
import tempfile
import time
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

try:
    from PySide6.QtGui import QAction
    from PySide6.QtWidgets import QApplication
    _HAS_PYSIDE6 = True
except Exception:  # pragma: no cover
    _HAS_PYSIDE6 = False


@unittest.skipUnless(_HAS_PYSIDE6, "PySide6 未安装，跳过真实 GUI 用例")
class WordBookGuiJumpTest(unittest.TestCase):
    """真 GUI：单词本右键 → 跳转到原书。"""

    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        # ⚠️ ignore_cleanup_errors：reader 的后台加载线程会**自己**建一条 thread-local
        #   SQLite 连接（LocalDatabase._conn 用 threading.local），db.close() 只关当前
        #   线程那条 ⇒ Windows 下临时目录清理时文件仍被锁 ⇒ PermissionError。
        #   那是资源释放的小瑕疵，不是本用例要验的行为，故清理阶段容错。
        self._tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(self._tmp.cleanup)

        from app.core.api_client import ApiClient
        from app.core.auth import AuthManager
        from app.core.config import AppConfig
        from app.db.database import LocalDatabase
        from app.models.models import Book, WordEntry

        self.config = AppConfig(data_dir=self._tmp.name)
        self.api = ApiClient(self.config)
        self.auth = AuthManager(self.config, self.api)
        self.db = LocalDatabase(self.config)
        self.addCleanup(self.db.close)

        from app.ui.pages.profile_page import ProfilePage
        from app.ui.pages.reader_page import ReaderPage

        self.profile = ProfilePage(self.config, self.api, self.auth, self.db)
        self.reader = ReaderPage(self.config, self.api, self.auth, self.db)
        # ⚠️ **必须显式销毁页面 widget**（2026-11-07 实测踩坑）：
        #   ReaderPage 带着后台加载线程 + 250ms 轮询定时器。若只是让 Python GC
        #   回收，回收时机不可控 ⇒ 页面已析构而线程/定时器还在跑 ⇒ 之后任何
        #   Qt 操作（例如 ``Theme.apply`` 里的 ``setStyleSheet`` 会 polish 全部
        #   顶层 widget）都可能碰到野指针 ⇒ **access violation 段错误**。
        #   实测崩在 ``test_word_book_gui.py:119 _pump`` 抽水时。
        from tests.qt_cleanup import register_destroy
        register_destroy(self, self.reader, self.profile)

        # 收藏夹里的两本：一本带出处、一本不带（旧数据）
        self.db.add_word(WordEntry(
            word="hood", sw="hood", definition="兜帽",
            book_name="测试之书", book_key="n:testbook",
            segment_number=2, line_number=3,
            sentence_text="THE HOOD WAS PULLED OVER HIS FACE."))
        self.db.add_word(WordEntry(word="legacy", sw="legacy", definition="旧收藏"))

        # 收藏必须来自本机书架里的书，才谈得上「跳转到原书」⇒ 先把书入库
        book_dir = os.path.join(self._tmp.name, "books")
        os.makedirs(book_dir, exist_ok=True)
        self.txt_path = os.path.join(book_dir, "测试之书.txt")
        with open(self.txt_path, "w", encoding="utf-8") as f:
            f.write("CHAPTER ONE\n"
                    "IT WAS A COLD MORNING IN THE VALLEY.\n"
                    "THE HOOD WAS PULLED OVER HIS FACE.\n"
                    "HE SAID NOTHING ELSE THAT DAY.\n")
        self.book_id = self.db.add_book(Book(
            display_name="测试之书", file_path=self.txt_path,
            file_ext="txt", has_audio=False, category="txt", sort_order=0))

    # ---------- 工具 ----------

    def _pump(self, seconds=1.2):
        """抽水：让 Qt 事件循环转一会儿（异步加载 / 菜单 / 定时器都靠它）。"""
        end = time.time() + seconds
        while time.time() < end:
            self.app.processEvents()
            time.sleep(0.01)

    def _find_item(self, needle):
        for i in range(self.profile.word_list.count()):
            it = self.profile.word_list.item(i)
            if needle in it.text():
                return it
        return None

    def _click_menu_item(self):
        """把 profile_page 的 QMenu 换成假类，模拟「用户点了第一个菜单项」。

        返回一个记录菜单项状态（文本 / 是否启用）的 dict：
        - 菜单项**启用** ⇒ exec 返回该 action（= 用户点中了它）
        - 菜单项**禁用** ⇒ exec 返回 None（= 用户点不动、没选中任何项）

        之所以用假 QMenu 而不是真鼠标点击：见模块 docstring ——
        offscreen 下真弹窗会撞 native access violation，且不可稳定复现。
        """
        from app.ui.pages import profile_page as pp

        seen = {"text": "", "enabled": None}

        class _FakeMenu:
            """只实现产品代码用到的接口：addAction / exec。"""

            def __init__(self, parent=None):
                self._actions = []
                self.parent = parent

            def addAction(self, text):
                a = QAction(text)
                self._actions.append(a)
                return a

            def exec(self, *args, **kwargs):
                if not self._actions:
                    return None
                a = self._actions[0]
                seen["text"] = a.text()
                seen["enabled"] = a.isEnabled()
                # 禁用的项点不动 ⇒ 等价于「用户什么都没选中」
                return a if a.isEnabled() else None

        self._orig_menu = pp.QMenu
        pp.QMenu = _FakeMenu
        self.addCleanup(self._restore_menu)
        return seen

    def _restore_menu(self):
        from app.ui.pages import profile_page as pp
        pp.QMenu = self._orig_menu

    # ---------- 用例 ----------

    def test_word_list_items_carry_six_source_roles(self):
        """单词本 item 上确实挂着 6 个出处角色（旧条目全 None）。"""
        from app.ui.pages.profile_page import (
            _WB_ROLE_BOOK_NAME, _WB_ROLE_BOOK_KEY, _WB_ROLE_STORE_BOOK_ID,
            _WB_ROLE_SEGMENT_NUMBER, _WB_ROLE_LINE_NUMBER, _WB_ROLE_SENTENCE_TEXT,
        )
        self.profile._load_words()
        self.assertEqual(2, self.profile.word_list.count())

        hood = self._find_item("hood")
        legacy = self._find_item("legacy")
        self.assertIsNotNone(hood)
        self.assertIsNotNone(legacy)

        self.assertEqual("测试之书", hood.data(_WB_ROLE_BOOK_NAME))
        self.assertEqual("n:testbook", hood.data(_WB_ROLE_BOOK_KEY))
        self.assertIsNone(hood.data(_WB_ROLE_STORE_BOOK_ID))
        self.assertEqual(2, hood.data(_WB_ROLE_SEGMENT_NUMBER))
        self.assertEqual(3, hood.data(_WB_ROLE_LINE_NUMBER))
        self.assertEqual("THE HOOD WAS PULLED OVER HIS FACE.",
                         hood.data(_WB_ROLE_SENTENCE_TEXT))

        for role in (_WB_ROLE_BOOK_NAME, _WB_ROLE_BOOK_KEY, _WB_ROLE_STORE_BOOK_ID,
                     _WB_ROLE_SEGMENT_NUMBER, _WB_ROLE_LINE_NUMBER,
                     _WB_ROLE_SENTENCE_TEXT):
            self.assertIsNone(legacy.data(role))

    def test_right_click_with_source_emits_jump_signal(self):
        """带出处的词右键 → 真的点菜单 → word_jump_requested 带出正确的书身份与出处。"""
        jumps = []
        self.profile.word_jump_requested.connect(
            lambda bid, sid, j: jumps.append((bid, sid, j)))

        self.profile._load_words()
        hood = self._find_item("hood")
        self.assertIsNotNone(hood)

        seen = self._click_menu_item()
        rect = self.profile.word_list.visualItemRect(hood)
        self.profile._on_word_context_menu(rect.center())
        self._pump()

        self.assertEqual("跳转到原书", seen["text"])
        self.assertTrue(seen["enabled"])
        self.assertEqual(1, len(jumps), "右键点菜单后必须发出跳转信号")
        bid, sid, jump = jumps[0]
        self.assertEqual(self.book_id, bid)
        self.assertEqual(0, sid)
        self.assertEqual("测试之书", jump.get("bookName"))
        self.assertEqual("n:testbook", jump.get("bookKey"))
        self.assertEqual(3, jump.get("lineNumber"))
        self.assertEqual("THE HOOD WAS PULLED OVER HIS FACE.",
                         jump.get("sentenceText"))

    def test_right_click_without_source_disables_menu_and_emits_nothing(self):
        """旧收藏（无出处）右键 → 菜单项禁用、提示「无收藏出处」、不发信号。"""
        jumps = []
        self.profile.word_jump_requested.connect(
            lambda bid, sid, j: jumps.append((bid, sid, j)))

        self.profile._load_words()
        legacy = self._find_item("legacy")
        self.assertIsNotNone(legacy)

        seen = self._click_menu_item()
        rect = self.profile.word_list.visualItemRect(legacy)
        self.profile._on_word_context_menu(rect.center())
        self._pump()

        self.assertIn("无收藏出处", seen["text"])
        self.assertFalse(seen["enabled"])
        self.assertEqual(0, len(jumps))

    def test_reader_locates_favorite_line_by_text(self):
        """jump 传到 reader_page.open_book ⇒ 定位到收藏的那一行（按文本，不靠行号）。"""
        jump = {"bookName": "测试之书", "bookKey": "n:testbook",
                "segmentNumber": 2, "lineNumber": 3,
                "sentenceText": "THE HOOD WAS PULLED OVER HIS FACE."}
        self.reader.open_book(book_id=self.book_id, store_book_id=None, jump=jump)
        self._pump(2.5)
        self.assertGreater(len(self.reader.lines), 0, "阅读器应加载出正文")

        from app.parser.segment_map import SegmentMap
        texts = [ln.text for ln in self.reader.lines]
        sm = SegmentMap.for_text_lookup(texts)
        row = sm.locate_row_by_text(jump["sentenceText"], jump["lineNumber"])
        self.assertEqual(2, row, "应命中收藏那一行（0-based 下标 2）")
        self.assertIn("HOOD", texts[row].upper())

    def test_resolve_target_maps_book_name_to_local_book(self):
        """出处里的书名能解析成本机 book_id（解析不到就跳不过去）。"""
        bid, sid = self.profile._resolve_word_book_target(
            {"bookName": "测试之书", "bookKey": "n:testbook", "storeBookId": None})
        self.assertEqual(self.book_id, bid)
        self.assertEqual(0, sid)

    def test_resolve_target_unknown_book_returns_zero(self):
        """本机没有这本书 ⇒ 返回 (0,0)，由调用方提示「找不到这本书」，不静默。"""
        bid, sid = self.profile._resolve_word_book_target(
            {"bookName": "根本不存在的书", "bookKey": "n:nope", "storeBookId": None})
        self.assertEqual(0, bid)
        self.assertEqual(0, sid)


if __name__ == "__main__":
    unittest.main(verbosity=2)
