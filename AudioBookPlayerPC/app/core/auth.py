"""认证管理（对标 Android AuthManager）：登录/登出/token 管理/游客模式。"""
from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Optional, Callable

from app.core.config import AppConfig
from app.core.api_client import ApiClient, ApiException


class AuthManager:
    """管理登录态、token 持久化、游客模式。"""

    def __init__(self, config: AppConfig, api: ApiClient):
        self.config = config
        self.api = api
        self._lock = threading.Lock()
        self._token_file = config.resolved_data_dir / "auth.json"
        self._callbacks: list[Callable] = []  # 登录状态变化回调
        # 本次会话里 token 是否因过期而失效（启动校验 / 401 两种来源）。
        # 与"用户主动登出"区分开：主动登出不该被当成"过期"去弹告警。
        self.session_expired = False
        self._restore()

    # ---------- 属性 ----------

    @property
    def is_authenticated(self) -> bool:
        return self.config.token is not None

    @property
    def username(self) -> Optional[str]:
        return self.config.username

    @property
    def remembered_username(self) -> str:
        """**记住的用户名**（token 失效 / 主动登出后仍然保留）—— 登录框预填用。

        为什么单独记：服务端 JWT TTL 只有 24h，每天必然要重登一次。
        token 过期时必须清掉（安全），但"用户是谁"不是凭证，留着能让重登只剩"输密码"。

        ⛔ 这里**不含密码**：密码不落盘。
        """
        return (self.config.last_username or "").strip()

    # ---------- 操作 ----------

    def login(self, username: str, password: str) -> bool:
        try:
            resp = self.api.login(username, password)
            if resp.success and resp.token:
                self.config.token = resp.token
                self.config.username = username
                self.config._is_admin = resp.is_admin
                self._remember_username(username)
                self._persist()
                self._notify()
                return True
            return False
        except ApiException:
            return False

    def register(self, username: str, password: str, email: str = "") -> bool:
        try:
            resp = self.api.register(username, password, email)
            if resp.success and resp.token:
                self.config.token = resp.token
                self.config.username = username
                self._remember_username(username)
                self._persist()
                self._notify()
                return True
            return False
        except ApiException:
            return False

    def logout(self):
        """登出：清 token 与当前用户名。

        ⛔ **不动 ``config.last_username``**：那不是凭证，是"上次是谁登录的"。
        token 过期 / 主动登出后，登录框据此预填用户名 ⇒ 重登只需输密码。
        （真要"换人用"时，用户在登录框里直接改用户名即可，改完会覆盖记住的值。）
        """
        self.config.token = None
        self.config.username = None
        self.config._is_admin = False
        self._token_file.unlink(missing_ok=True)
        self._notify()

    def _remember_username(self, username: str):
        """记住用户名并落盘（供下次登录框预填）。

        只在**值确实变了**时才写盘 —— 否则每次登录都无谓地重写 config.json。
        """
        name = (username or "").strip()
        if not name or name == (self.config.last_username or "").strip():
            return
        self.config.last_username = name
        try:
            self.config.save()
        except Exception:  # noqa: BLE001 —— 记不住用户名不该让登录失败
            pass

    def validate_session(self) -> bool:
        """启动时校验 token 是否仍有效。

        ⚠️ 服务端对「token 已过期」返回的是 **200 + {"valid": false}**（不是 401），
        所以这里**不会**触发 api 的 401 回调 ⇒ 主界面收不到任何"过期"信号。
        若不在这里记一笔，启动时 token 过期的表现是**完全静默**：
        界面只显示"已离线（游客）"，用户根本不知道自己是掉登录了，
        只觉得"手机端加的书 PC 端怎么不更新"。

        ⇒ 这里把「本来有 token、校验却没过」记进 [session_expired]，
        由主界面据此弹一次可操作的提示。
        """
        if not self.is_authenticated:
            return False
        try:
            data = self.api.validate_token()
            if data.get("valid") or data.get("success"):
                return True
            self.session_expired = True
            self.logout()
            return False
        except ApiException as e:
            if e.status_code == 401:
                self.session_expired = True
                self.logout()
            return False

    def handle_unauthorized(self):
        """401 回调：仅确实带 token 时才清凭证。"""
        if self.is_authenticated:
            self.logout()

    # ---------- 事件 ----------

    def on_auth_changed(self, callback: Callable):
        self._callbacks.append(callback)

    def _notify(self):
        for cb in self._callbacks:
            try:
                cb(self.is_authenticated, self.username)
            except Exception:
                pass

    # ---------- 持久化 ----------

    def _persist(self):
        if not self.config.token:
            self._token_file.unlink(missing_ok=True)
            return
        data = {"token": self.config.token, "username": self.config.username,
                "is_admin": self.config._is_admin}
        self._token_file.write_text(json.dumps(data), "utf-8")

    def _restore(self):
        if not self._token_file.exists():
            return
        try:
            data = json.loads(self._token_file.read_text("utf-8"))
            self.config.token = data.get("token")
            self.config.username = data.get("username")
            self.config._is_admin = data.get("is_admin", False)
        except Exception:
            pass
