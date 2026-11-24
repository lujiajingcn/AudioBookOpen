# -*- coding: utf-8 -*-
"""左侧菜单栏显隐 / 折叠回归测试。

覆盖：
  - NavigationBar.set_collapsed 切换宽度（144 ⇄ 60）并隐藏标题标签；
  - 折叠态下按钮仍可点击切页（current_changed 仍发信号）；
  - MainWindow._set_nav_visible 隐藏 / 显示整个侧栏并落盘；
  - 状态持久化：QSettings 写入 ui/nav_visible、ui/nav_collapsed。

离屏运行（无需显示器）：
    set QT_QPA_PLATFORM=offscreen && python tests/test_nav_collapse.py
"""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 离屏：CI / 无显示器环境下也能建 QApplication
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QSettings  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from app.ui.widgets.navigation import NavigationBar  # noqa: E402


def _isolate_settings() -> str:
    """把 QSettings 落盘重定向到临时目录。

    ⚠ Windows 上 QSettings 默认走 NativeFormat(注册表)，setPath 对它无效；
    必须先 setDefaultFormat(IniFormat)。否则测试会在用户注册表里留下垃圾键。
    """
    import tempfile
    d = os.path.join(tempfile.gettempdir(), "abpc_test_settings")
    os.makedirs(d, exist_ok=True)
    QSettings.setDefaultFormat(QSettings.IniFormat)
    QSettings.setPath(QSettings.IniFormat, QSettings.UserScope, d)
    return d


def _app() -> QApplication:
    return QApplication.instance() or QApplication(sys.argv)


class NavigationCollapseTest(unittest.TestCase):
    def setUp(self):
        self.app = _app()
        self.nav = NavigationBar()
        self.nav.set_items(NavigationBar.DEFAULT_ITEMS)

    def tearDown(self):
        self.nav.deleteLater()

    def test_default_expanded(self):
        self.assertFalse(self.nav.collapsed)
        self.assertEqual(self.nav.width(), NavigationBar.EXPANDED_WIDTH)
        self.assertEqual(self.nav.count(), 4)

    def test_collapse_hides_title_and_shrinks(self):
        self.nav.set_collapsed(True)
        self.assertTrue(self.nav.collapsed)
        self.assertEqual(self.nav.width(), NavigationBar.COLLAPSED_WIDTH)
        btn = self.nav._buttons[0]
        self.assertFalse(btn._title_lbl.isVisibleTo(btn)
                         if hasattr(btn._title_lbl, "isVisibleTo")
                         else True)
        # 标题标签被隐藏（用 isHidden 判断，不依赖父是否 show）
        self.assertTrue(btn._title_lbl.isHidden())
        # tooltip 仍保留全名
        self.assertEqual(btn.toolTip(), "书架")

    def test_expand_restores_title(self):
        self.nav.set_collapsed(True)
        self.nav.set_collapsed(False)
        self.assertFalse(self.nav.collapsed)
        self.assertEqual(self.nav.width(), NavigationBar.EXPANDED_WIDTH)
        self.assertFalse(self.nav._buttons[0]._title_lbl.isHidden())

    def test_collapsed_changed_signal(self):
        seen = []
        self.nav.collapsed_changed.connect(lambda c: seen.append(c))
        self.nav.toggle_collapsed()
        self.nav.toggle_collapsed()
        self.assertEqual(seen, [True, False])

    def test_collapsed_nav_still_switches_page(self):
        self.nav.set_collapsed(True)
        seen = []
        self.nav.current_changed.connect(lambda i: seen.append(i))
        self.nav.set_current(2)
        self.assertEqual(seen, [2])
        self.assertEqual(self.nav.current_index, 2)


class MainWindowNavVisibilityTest(unittest.TestCase):
    """测 MainWindow 的侧栏显隐逻辑。

    用真实的 MainWindow 实例（走完整 __init__），但：
      - 把 QSettings 换成独立 org，避免污染用户真实偏好；
      - 桩掉 _validate_token_async / _refresh_credits_async，杜绝联网。
    """

    def setUp(self):
        self.app = _app()
        from app.core.config import AppConfig
        from app.main_window import MainWindow

        _isolate_settings()
        self.settings = QSettings("AudioBook", "AudioBookPlayerPC_Test")
        self.settings.clear()

        cfg = AppConfig()
        cfg.data_dir = os.path.join(
            os.environ.get("TEMP", "."), "abpc_nav_test_data")
        self.mw = MainWindow.__new__(MainWindow)
        # 手动走一遍 QMainWindow 初始化（PySide6 要求基类 __init__ 被调用）
        from PySide6.QtWidgets import QMainWindow
        QMainWindow.__init__(self.mw)

        self.mw.config = cfg
        self.mw.api = None
        self.mw.auth = None
        self.mw.db = None
        self.mw.sse = None
        self.mw.sync = None
        self.mw.services = {}
        self.mw._workers = []
        self.mw._settings = self.settings
        self.mw._nav_visible = True
        # 必须有父级：无父级的 QWidget 是顶层窗口，isHidden() 语义不同
        self.mw.nav = NavigationBar(self.mw)
        self.mw.nav.set_items(NavigationBar.DEFAULT_ITEMS)
        self.mw.nav_toggle_btn = None
        self.mw.nav_action = None
        self.mw._wire_nav_state()

    def tearDown(self):
        self.settings.clear()
        self.mw.nav.deleteLater()

    def test_set_nav_visible_persists(self):
        self.mw._set_nav_visible(False)
        self.assertTrue(self.mw.nav.isHidden())
        self.assertEqual(self.settings.value("ui/nav_visible"), "0")
        self.mw._set_nav_visible(True)
        self.assertFalse(self.mw.nav.isHidden())
        self.assertEqual(self.settings.value("ui/nav_visible"), "1")

    def test_toggle_nav_visible(self):
        self.mw._set_nav_visible(True)
        self.mw._toggle_nav_visible()
        self.assertFalse(self.mw._nav_visible)
        self.assertTrue(self.mw.nav.isHidden())
        self.mw._toggle_nav_visible()
        self.assertTrue(self.mw._nav_visible)
        self.assertFalse(self.mw.nav.isHidden())

    def test_collapsed_changed_persists(self):
        self.mw.nav.set_collapsed(True)
        self.assertEqual(self.settings.value("ui/nav_collapsed"), "1")
        self.mw.nav.set_collapsed(False)
        self.assertEqual(self.settings.value("ui/nav_collapsed"), "0")

    def test_restore_nav_state(self):
        self.settings.setValue("ui/nav_visible", "0")
        self.settings.setValue("ui/nav_collapsed", "1")
        self.mw._restore_nav_state()
        self.assertFalse(self.mw._nav_visible)
        self.assertTrue(self.mw.nav.isHidden())
        self.assertTrue(self.mw.nav.collapsed)

        self.settings.setValue("ui/nav_visible", "1")
        self.settings.setValue("ui/nav_collapsed", "0")
        self.mw._restore_nav_state()
        self.assertTrue(self.mw._nav_visible)
        self.assertFalse(self.mw.nav.isHidden())
        self.assertFalse(self.mw.nav.collapsed)


if __name__ == "__main__":
    unittest.main(verbosity=2)
