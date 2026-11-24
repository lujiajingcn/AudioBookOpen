# -*- coding: utf-8 -*-
"""PC 端阅读页「去掉返回按钮」的行为回归测试。

背景：
    阅读器顶部工具栏原来有一个「← 返回」按钮，点击会存进度 / 停播 /
    切回书架。按需求该按钮已移除，于是「离开阅读器」的唯一路径变成
    **点左侧导航栏**。随之带来两个必须成立的不变量：

  1. 顶部工具栏不再有「返回」按钮；
  2. 点导航栏（含**重复点击当前项**）能把叠在栈顶的阅读器换掉 ——
     NavigationBar.set_current 在索引未变时会直接 return，不发信号，
     若不额外发 item_clicked，用户点当前项会卡在阅读器里出不来；
  3. 阅读器被切走（hideEvent）时必须自己收尾：存进度 / 停表 / 停播，
     否则音频会在别的页面继续响、进度也会丢。

运行：``python tests/test_reader_back_button.py``（需 PySide6，可离线跑）。
"""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 无显示器环境（CI / 远程）也能跑
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import Qt  # noqa: E402
from PySide6.QtWidgets import QApplication, QPushButton  # noqa: E402

from app.ui.pages.reader_page import ReaderPage  # noqa: E402
from app.ui.widgets.navigation import NavigationBar  # noqa: E402


_APP = None


def _app():
    """整个进程共用一个 QApplication（Qt 不允许创建第二个）。"""
    global _APP
    if _APP is None:
        _APP = QApplication.instance() or QApplication(sys.argv[:1])
    return _APP


class _StubAuth:
    """BasePage 会调 auth.on_auth_changed；这里给个最小空实现。"""

    def on_auth_changed(self, _cb):
        return None


class _StubConfig:
    is_authenticated = False
    night_mode = False
    font_size = 18


class ReaderToolbarTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        _app()

    def _make_reader(self):
        page = ReaderPage(_StubConfig(), None, _StubAuth(), None)
        return page

    def test_no_back_button_in_toolbar(self):
        """顶部工具栏不再有「返回」按钮。"""
        page = self._make_reader()
        texts = [b.text().strip() for b in page.findChildren(QPushButton)]
        self.assertNotIn("← 返回", texts)
        for t in texts:
            self.assertNotIn("返回", t, f"阅读页仍残留返回类按钮：{t!r}")
        page.deleteLater()

    def test_toolbar_still_has_other_actions(self):
        """去掉返回按钮不影响其它工具栏动作（目录/搜索/字号/收藏/词典）。"""
        page = self._make_reader()
        texts = {b.text().strip() for b in page.findChildren(QPushButton)}
        for expected in ("目录", "搜索", "字号", "收藏", "词典"):
            self.assertIn(expected, texts)
        page.deleteLater()

    def test_hide_event_stops_session(self):
        """阅读器被切走时收尾：停表 + 停播。

        走真实的 show() → hide()，让 Qt 派发 hideEvent，
        验证「离开阅读器」这条路径确实被接上了。
        """
        page = self._make_reader()

        stopped = {"playback": False}

        class _FakePlayback:
            def stop(self):
                stopped["playback"] = True

        page.playback = _FakePlayback()
        page._pb_timer.start()
        page._tts_timer.start()
        self.assertTrue(page._pb_timer.isActive())
        self.assertTrue(page._tts_timer.isActive())

        page.show()
        page.hide()
        QApplication.processEvents()

        self.assertTrue(stopped["playback"], "离开阅读器时未停播")
        self.assertFalse(page._pb_timer.isActive(), "离开阅读器时未停位置轮询定时器")
        self.assertFalse(page._tts_timer.isActive(), "离开阅读器时未停 TTS 定时器")
        page.deleteLater()

    def test_back_clicked_signal_removed(self):
        """back_clicked 信号与 _on_back 槽已移除（避免留下死代码）。"""
        self.assertFalse(hasattr(ReaderPage, "back_clicked"))
        self.assertFalse(hasattr(ReaderPage, "_on_back"))


class NavigationRepeatClickTest(unittest.TestCase):
    """重复点击当前导航项也必须发信号（阅读器叠在栈顶时的唯一出口）。"""

    @classmethod
    def setUpClass(cls):
        _app()

    def test_repeat_click_emits_item_clicked(self):
        nav = NavigationBar()
        nav.set_items(NavigationBar.DEFAULT_ITEMS)
        nav.set_current(0)

        seen = []
        nav.item_clicked.connect(lambda i: seen.append(i))

        # 点当前项（索引 0）——set_current 会提前 return，但 item_clicked 必须发
        btn = nav._buttons[0]
        btn.click()
        self.assertEqual(seen, [0], "重复点击当前项未发 item_clicked")

        # 点其它项 —— current_changed 与 item_clicked 都应发
        seen.clear()
        nav._buttons[2].click()
        self.assertEqual(seen, [2])

        nav.deleteLater()

    def test_current_changed_not_duplicated_on_repeat_click(self):
        """重复点击当前项不应重复发 current_changed（避免重复切页）。"""
        nav = NavigationBar()
        nav.set_items(NavigationBar.DEFAULT_ITEMS)
        nav.set_current(0)

        changes = []
        nav.current_changed.connect(lambda i: changes.append(i))

        nav._buttons[0].click()
        self.assertEqual(changes, [], "重复点击当前项不应发 current_changed")

        nav._buttons[1].click()
        self.assertEqual(changes, [1])

        nav.deleteLater()


if __name__ == "__main__":
    unittest.main(verbosity=2)
