# -*- coding: utf-8 -*-
"""PC 端「记住用户名」的回归测试。

背景：服务端 JWT TTL 只有 **24h**（``jwt.expiration=86400000``）⇒ 每天都要重登一次。
PC 端原先的三处缺口：

1. ``AuthManager.logout()`` 把 ``config.username`` 也清掉了 ⇒ 用户得重新手打用户名；
2. 401 提示只写"请在「我的 - 登录 / 注册」重新登录" ⇒ 用户得自己去找入口；
3. **启动时** token 过期最糟：服务端对过期 token 返回 **200 + {"valid": false}**
   （不是 401）⇒ 不触发 401 回调 ⇒ 界面只显示"已离线（游客）"⇒ **完全静默**，
   用户根本不知道自己掉登录了。

本测试覆盖：
  1. 登录成功 ⇒ 记住用户名并落盘 config.json；
  2. 登出 / 401 ⇒ token 清掉但用户名**保留**；
  3. 启动校验发现过期 ⇒ 标记 ``session_expired``（与"主动登出"区分开）；
  4. 登录框预填用户名 + 焦点落在密码框（敲密码回车即可完成重登）；
  5. ``require_login`` 把记住的用户名传给登录框；
  6. 启动即过期 ⇒ 状态栏写"登录已过期 · 书架不再同步"，而不是"游客"。

离屏运行（无需显示器）：
    set QT_QPA_PLATFORM=offscreen && python tests/test_remember_username.py
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# ⚠️ 必须在 import app.core.config **之前**设置：config 模块在 import 时就把
#    _CONFIG_DIR / _CONFIG_FILE 按环境变量算成模块级常量了。
#    不隔离 ⇒ AuthManager._remember_username() 里的 config.save() 会写进用户
#    **真实**的 config.json（测试污染真实配置）。
_CFG_DIR = tempfile.mkdtemp(prefix="abpc_remember_")
os.environ["AUDIOBOOK_PC_CONFIG"] = _CFG_DIR
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication, QDialog, QMessageBox  # noqa: E402

from app.core.auth import AuthManager  # noqa: E402
from app.core.config import AppConfig  # noqa: E402


class _FakeAuthResponse:
    def __init__(self, token="", is_admin=False, success=True, message=""):
        self.token = token
        self.is_admin = is_admin
        self.success = success
        self.message = message


class _FakeApi:
    """ApiClient 替身：只提供 AuthManager 用得到的接口。"""

    def __init__(self, valid=True):
        self._valid = valid
        self.login_calls = []

    def login(self, username, password):
        self.login_calls.append((username, password))
        return _FakeAuthResponse(token="tok-" + username)

    def register(self, username, password, email=""):
        return _FakeAuthResponse(token="tok-" + username)

    def validate_token(self):
        return {"valid": self._valid, "success": self._valid}


class RememberUsernameTest(unittest.TestCase):
    def setUp(self):
        self.app = QApplication.instance() or QApplication([])
        self.tmp = tempfile.mkdtemp()
        self.cfg = AppConfig(data_dir=self.tmp)
        self.api = _FakeApi()
        self.auth = AuthManager(self.cfg, self.api)

    # ---------- 1. 登录成功 ⇒ 记住并落盘 ----------

    def test_login_remembers_username(self):
        self.assertTrue(self.auth.login("alice", "pw"))
        self.assertEqual("alice", self.cfg.last_username)
        self.assertEqual("alice", self.auth.remembered_username)

    def test_remembered_username_persisted_to_disk(self):
        """必须真的写进 config.json —— 否则下次启动就预填不了。"""
        self.auth.login("alice", "pw")
        saved = json.loads((Path(_CFG_DIR) / "config.json").read_text("utf-8"))
        self.assertEqual("alice", saved.get("last_username"),
                         "记住的用户名必须落盘 config.json")

    def test_username_not_re_written_when_unchanged(self):
        """同名重复登录不该反复重写 config.json。"""
        self.auth.login("alice", "pw")
        mtime = (Path(_CFG_DIR) / "config.json").stat().st_mtime_ns
        self.auth.logout()
        self.auth.login("alice", "pw")
        self.assertEqual(mtime, (Path(_CFG_DIR) / "config.json").stat().st_mtime_ns)

    # ---------- 2. 登出 / 401 ⇒ 清 token 但留住用户名 ----------

    def test_logout_keeps_username_clears_token(self):
        self.auth.login("alice", "pw")
        self.auth.logout()
        self.assertIsNone(self.cfg.token, "登出必须清掉 token")
        self.assertEqual("alice", self.auth.remembered_username,
                         "登出后必须记住用户名，否则每天都要重打一遍")

    def test_401_keeps_username(self):
        """401 会走 logout() ⇒ 用户名同样要留下。"""
        self.auth.login("alice", "pw")
        self.auth.handle_unauthorized()
        self.assertIsNone(self.cfg.token)
        self.assertEqual("alice", self.auth.remembered_username)

    # ---------- 3. 过期 vs 主动登出，必须区分 ----------

    def test_validate_session_marks_expired(self):
        """服务端对过期 token 返回 200 + valid:false（不是 401）⇒ 只有这里知道。"""
        self.cfg.token = "tok"
        self.api._valid = False
        self.assertFalse(self.auth.validate_session())
        self.assertTrue(self.auth.session_expired,
                        "启动校验发现过期必须记下来，否则界面完全静默")
        self.assertIsNone(self.cfg.token, "过期后必须清掉 token")

    def test_validate_session_ok_not_expired(self):
        self.cfg.token = "tok"
        self.api._valid = True
        self.assertTrue(self.auth.validate_session())
        self.assertFalse(self.auth.session_expired)

    def test_manual_logout_is_not_marked_expired(self):
        """主动登出不是"过期" ⇒ 不该弹过期告警。"""
        self.auth.login("alice", "pw")
        self.auth.logout()
        self.assertFalse(self.auth.session_expired,
                         "主动登出不该被当成 token 过期")

    # ---------- 4. 登录框预填 ----------

    def test_login_dialog_prefills_username(self):
        from app.main_window import _LoginDialog
        dlg = _LoginDialog(self.auth, "", None, remembered_username="alice")
        try:
            self.assertEqual("alice", dlg.user_edit.text(),
                             "登录框必须预填记住的用户名")
            self.assertEqual("password", dlg.focus_field,
                             "用户名已填好 ⇒ 焦点应落在密码框（敲密码回车即完成重登）")
        finally:
            dlg.deleteLater()

    def test_login_dialog_without_remembered_username(self):
        from app.main_window import _LoginDialog
        dlg = _LoginDialog(self.auth, "", None, remembered_username="")
        try:
            self.assertEqual("", dlg.user_edit.text())
            self.assertEqual("username", dlg.focus_field)
        finally:
            dlg.deleteLater()

    def test_require_login_passes_remembered_username(self):
        """主窗口打开登录框时必须把记住的用户名传下去。"""
        import app.main_window as mw
        captured = {}

        class _FakeDialog:
            def __init__(self, auth, message="", parent=None, remembered_username=""):
                captured["username"] = remembered_username

            def exec(self):
                return QDialog.Rejected

        orig = mw._LoginDialog
        mw._LoginDialog = _FakeDialog
        try:
            self.cfg.last_username = "alice"
            mw.MainWindow.require_login  # 确认属性存在
            from app.main_window import MainWindow
            win = MainWindow(self.cfg)
            try:
                win.require_login("请登录以继续。")
            finally:
                win.close()
        finally:
            mw._LoginDialog = orig
        self.assertEqual("alice", captured.get("username"),
                         "登录框必须拿到记住的用户名，否则预填形同虚设")


class StartupExpiredHintTest(unittest.TestCase):
    """启动时 token 就已过期 ⇒ 必须显式提示，不能只显示"游客"。"""

    def setUp(self):
        self.app = QApplication.instance() or QApplication([])
        self.tmp = tempfile.mkdtemp()
        self.cfg = AppConfig(data_dir=self.tmp)
        # 弹框打桩：exec 在离屏下会**阻塞**（模态框等点击）⇒ 必须打桩。
        # ⛔ 这里**不能**用 `button.click()` 来模拟"点了重新登录"——
        #    QMessageBox 的按钮 click 会触发嵌套事件循环 ⇒ 测试直接挂死（SIGTERM）。
        #    要测"点重新登录"那条链路，改为替换 ``_prompt_relogin``（见下方用例）。
        self._boxes = []
        self._buttons = []
        self._orig_exec = QMessageBox.exec

        def _fake_exec(box, *a, **kw):
            self._boxes.append((box.windowTitle(), box.text()))
            self._buttons.append([b.text() for b in box.buttons()])
            return QMessageBox.Ok

        QMessageBox.exec = _fake_exec
        from app.main_window import MainWindow
        self.win = MainWindow(self.cfg)

    def tearDown(self):
        # ⚠️ 顺序很重要：**先销毁窗口（close 会触发 closeEvent → 断开
        #    api.on_unauthorized 回调），再恢复 QMessageBox.exec**。
        #    只 close() 不销毁的话，窗口仍挂在 QApplication 顶层 widget 列表里，
        #    它的 api client 之后收到真实 401 还会回调进来 ⇒ 弹「登录已过期」
        #    模态框 ⇒ 离屏环境下 abort（实测崩在 main_window._prompt_relogin，
        #    faulthandler 栈：main_window.py:892 _prompt_relogin）。
        try:
            self.win.close()
        except Exception:  # noqa: BLE001
            pass
        try:
            import shiboken6
            shiboken6.delete(self.win)
        except Exception:  # noqa: BLE001
            pass
        QMessageBox.exec = self._orig_exec

    def test_startup_expired_status_bar_not_guest(self):
        self.win.auth.session_expired = True
        self.win._on_validated(False)
        text = self.win.net_lbl.text()
        self.assertIn("登录已过期", text,
                      f"启动即过期必须写明「登录已过期」，实际状态栏：{text!r}")
        self.assertNotIn("游客", text,
                         "不能写成「已离线（游客）」—— 用户会以为从没登录过")

    def test_plain_guest_status_bar_still_guest(self):
        """从没登录过 ⇒ 正常游客态，不该被误报成过期。"""
        self.win.auth.session_expired = False
        self.win._on_validated(False)
        self.assertIn("游客", self.win.net_lbl.text())

    def test_expired_prompt_offers_relogin_button(self):
        """过期提示必须给「重新登录」按钮，否则用户得自己去找入口。"""
        self.win._apply_unauthorized()
        self.assertTrue(self._boxes, "过期必须弹提示")
        title, text = self._boxes[0]
        self.assertIn("登录已过期", title)
        self.assertIn("书架", text, "提示要点明受影响的是书架")
        flat = [t for group in self._buttons for t in group]
        self.assertTrue(any("重新登录" in t for t in flat),
                        f"提示框必须有「重新登录」按钮，实际按钮：{flat}")

    def test_relogin_button_opens_prefilled_login_dialog(self):
        """点「重新登录」⇒ 应打开**已预填用户名**的登录框（重登只剩输密码）。"""
        import app.main_window as mw
        captured = {}

        class _FakeDialog:
            def __init__(self, auth, message="", parent=None, remembered_username=""):
                captured["username"] = remembered_username

            def exec(self):
                return QDialog.Rejected

        self.cfg.last_username = "alice"
        # 模拟用户在提示框里点了「重新登录」
        self.win._prompt_relogin = lambda: "relogin"
        orig = mw._LoginDialog
        mw._LoginDialog = _FakeDialog
        try:
            self.win._apply_unauthorized()
        finally:
            mw._LoginDialog = orig
        self.assertEqual("alice", captured.get("username"),
                         "点「重新登录」弹出的登录框必须预填记住的用户名")

    def test_declining_relogin_does_not_open_login(self):
        """选「稍后再说」⇒ 不该弹登录框（用户明确推迟了）。"""
        import app.main_window as mw
        opened = []

        class _FakeDialog:
            def __init__(self, *a, **kw):
                opened.append(True)

            def exec(self):
                return QDialog.Rejected

        self.win._prompt_relogin = lambda: "later"
        orig = mw._LoginDialog
        mw._LoginDialog = _FakeDialog
        try:
            self.win._apply_unauthorized()
        finally:
            mw._LoginDialog = orig
        self.assertFalse(opened, "选「稍后再说」时不该弹登录框")


if __name__ == "__main__":
    unittest.main(verbosity=2)
