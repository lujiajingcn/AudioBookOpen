# -*- coding: utf-8 -*-
"""PC 端「登录过期 ⇒ 书架永久不同步」的**集成**回归测试（MainWindow 级别）。

背景（2026-11-06 线上故障，见 ``test_shelf_sync_auth.py`` 的详细根因）：
PC 端 JWT 24h 过期 → 服务端 401 → ``logout()`` 删掉 auth.json → 变成游客态
→ ``SyncManager.is_available()`` 为 False → 同步**一次请求都不发** → 手机端新加的书
在 PC 端永远不出现，而界面毫无异样。

本测试直接驱动 MainWindow，覆盖整条链路：
  1. 未登录 ⇒ 书架页横幅可见、提示"不会同步"、「同步」按钮置灰；
  2. 401 ⇒ 状态栏写明"书架不再同步"，且**弹一次**提示（并发 401 不重复弹）；
  3. 401 后 token 被清空（确实退化为游客态）；
  4. 手动同步在未登录时给出明确说法（不是静默无反应）；
  5. 重新登录成功 ⇒ **自动补跑一次同步**，把断掉的书架接上。

离屏运行（无需显示器）：
    set QT_QPA_PLATFORM=offscreen && python tests/test_shelf_sync_integration.py
"""
from __future__ import annotations

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication, QMessageBox  # noqa: E402

from app.core.config import AppConfig  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402


class _FakeSync:
    """SyncManager 替身：记录调用次数，可控制返回。"""

    def __init__(self, available=True):
        self._available = available
        self.calls = 0

    def is_available(self):
        return self._available

    def sync_all(self, include_progress=True):
        self.calls += 1
        return _FakeResult()


class _FakeResult:
    shelf_synced = 3
    shelf_removed = 0
    tasks_added = 0
    tasks_updated = 0
    favorites_rebuilt = 0
    words_rebuilt = 0
    progress_pulled = 0
    progress_pushed = 0
    errors = []

    def as_text(self):
        return "书架 3"


class ShelfSyncIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.app = QApplication.instance() or QApplication([])
        self.tmp = tempfile.mkdtemp()
        self.cfg = AppConfig(data_dir=self.tmp)
        self.db = LocalDatabase(self.cfg)
        # 弹框打桩：记录弹了几次、文案是什么。
        #
        # ⚠️ 401 提示现在用的是「QMessageBox **实例** + addButton + exec」（带「重新登录」
        #    按钮），不再是静态的 ``QMessageBox.warning`` ⇒ 打桩点必须跟着改。
        #    漏改的话，``exec()`` 在离屏下会**阻塞**（模态框等点击）⇒ 测试挂死，
        #    而且症状是「测试卡住」而不是「断言失败」，极难定位。
        self._boxes = []
        self._orig_exec = QMessageBox.exec
        QMessageBox.exec = lambda box, *a, **kw: (
            self._boxes.append((box.windowTitle(), box.text())), QMessageBox.Ok)[1]

        from app.main_window import MainWindow
        self.win = MainWindow(self.cfg)
        self.fake_sync = _FakeSync(available=False)
        self.win.sync = self.fake_sync
        self.win._last_sync_ts = 0.0

    def tearDown(self):
        QMessageBox.exec = self._orig_exec
        try:
            self.db.close()
        except Exception:  # noqa: BLE001
            pass
        try:
            self.win.close()
        except Exception:  # noqa: BLE001
            pass

    def _pump(self, n=15):
        for _ in range(n):
            self.app.processEvents()

    # ---------- 1. 未登录 ⇒ 书架页必须显式提示 ----------

    def test_shelf_banner_visible_when_guest(self):
        self.win.shelf_page.refresh()
        self.assertFalse(self.win.shelf_page.banner.isHidden(),
                         "未登录时书架页横幅必须出现")
        self.assertIn("不会同步", self.win.shelf_page.banner_lbl.text())

    # ---------- 2. 401 ⇒ 明确提示且只弹一次 ----------

    def test_401_warns_once_about_shelf_not_syncing(self):
        """并发 401（一次会话过期会打十来个接口）⇒ 只弹一次提示。"""
        self.cfg.token = "expired-token"
        self.cfg.username = "someone"
        self.win.auth.config.token = "expired-token"
        # 连续触发多次 401
        for _ in range(5):
            self.win._apply_unauthorized()
        self.assertEqual(1, len(self._boxes),
                         f"并发 401 应只弹一次提示，实际弹了 {len(self._boxes)} 次")
        title, text = self._boxes[0]
        self.assertIn("登录已过期", title)
        self.assertIn("不再同步", text,
                      "提示必须点明「不再同步」，否则用户无从判断现象成因")
        self.assertIn("书架", text,
                      "提示必须点明受影响的是书架，否则用户无从判断现象成因")

    def test_401_status_bar_mentions_shelf(self):
        self.win._apply_unauthorized()
        self.assertIn("书架不再同步", self.win.net_lbl.text(),
                      "状态栏必须写明书架不再同步（原来只有「登录已过期」四个字）")

    # ---------- 3. 401 后确实退化为游客态 ----------

    def test_401_clears_credentials(self):
        self.cfg.token = "expired-token"
        self.win.auth.config.token = "expired-token"
        self.win.auth.config.username = "someone"
        self.win._on_unauthorized()
        self._pump()
        self.assertIsNone(self.cfg.token, "401 后 token 必须被清掉（降级为游客）")

    # ---------- 4. 未登录时手动同步要给出说法 ----------

    def test_manual_sync_while_guest_reports_clearly(self):
        """未登录时点「同步」⇒ 必须给出明确提示，而不是静默无反应。"""
        self.win.shelf_page.manual_sync_requested.emit()
        self._pump()
        msg = self.win.statusBar().currentMessage()
        self.assertIn("未登录", msg,
                      f"未登录手动同步应提示未登录，实际状态栏消息：{msg!r}")
        self.assertEqual(0, self.fake_sync.calls,
                         "未登录时不应真的发起同步")

    # ---------- 5. 重新登录 ⇒ 自动补跑同步 ----------

    def test_relogin_triggers_sync_automatically(self):
        """登录成功后必须自动补跑一次同步（把断掉的书架接上）。"""
        self.fake_sync._available = True
        self.cfg.token = "fresh-token"
        self.win.auth.config.token = "fresh-token"
        self.win._apply_auth_state(True)
        self._pump()
        self.assertGreaterEqual(self.fake_sync.calls, 1,
                                "登录成功后必须自动补跑一次同步，否则书架要等下次切页才恢复")

    # ---------- 6. 书架页「同步」按钮已接线到主窗口 ----------

    def test_shelf_sync_button_wired_to_main_window(self):
        """书架页「同步」按钮 ⇒ 主窗口收到 manual_sync_requested ⇒ 发起同步。"""
        self.fake_sync._available = True
        self.cfg.token = "fresh-token"
        self.win.auth.config.token = "fresh-token"
        before = self.fake_sync.calls
        self.win.shelf_page.manual_sync_requested.emit()
        self._pump()
        self.assertEqual(before + 1, self.fake_sync.calls,
                         "书架页「同步」按钮未接线到主窗口的同步流程")


if __name__ == "__main__":
    unittest.main(verbosity=2)
