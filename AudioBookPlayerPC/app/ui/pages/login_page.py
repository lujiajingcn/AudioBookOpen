"""登录 / 注册对话框。

模式切换：登录 <-> 注册（注册多一个邮箱字段）。
网络调用（AuthManager.login/register）在后台线程执行，结果经 Qt 信号编组回主线程。
成功后由 AuthManager 内部更新 config 并触发回调；对话框 emit login_succeeded 后关闭。
"""
from __future__ import annotations

import threading
from typing import Optional

from PySide6.QtCore import Qt, Signal, QObject
from PySide6.QtWidgets import (
    QDialog, QVBoxLayout, QFormLayout, QLineEdit, QLabel, QPushButton,
    QWidget, QComboBox, QHBoxLayout,
)
from PySide6.QtWidgets import QSizePolicy

from app.core.auth import AuthManager


class _Bridge(QObject):
    done = Signal(bool, str)  # (success, message)


class LoginDialog(QDialog):
    """登录 / 注册。

    信号:
        login_succeeded(str username) —— 登录或注册成功。
    """

    login_succeeded = Signal(str)

    def __init__(self, auth: AuthManager, parent: Optional[QWidget] = None,
                 mode: str = "login", prefill_username: str = ""):
        super().__init__(parent)
        self.auth = auth
        self._mode = "register" if mode == "register" else "login"
        self._busy = False
        self.setWindowTitle("登录 / 注册")
        self.setModal(True)
        self.setMinimumWidth(360)
        self._b = _Bridge()
        self._b.done.connect(self._on_done)

        lay = QVBoxLayout(self)
        self.title_lbl = QLabel()
        self.title_lbl.setAlignment(Qt.AlignCenter)
        f = self.title_lbl.font()
        f.setPointSize(f.pointSize() + 3)
        f.setBold(True)
        self.title_lbl.setFont(f)
        lay.addWidget(self.title_lbl)

        self.form = QFormLayout()
        self.user_edit = QLineEdit(prefill_username)
        self.user_edit.setPlaceholderText("用户名")
        self.pwd_edit = QLineEdit()
        self.pwd_edit.setEchoMode(QLineEdit.Password)
        self.pwd_edit.setPlaceholderText("密码")
        self.email_edit = QLineEdit()
        self.email_edit.setPlaceholderText("邮箱（选填）")
        self.form.addRow("用户名", self.user_edit)
        self.form.addRow("密码", self.pwd_edit)
        self.form.addRow("邮箱", self.email_edit)
        lay.addLayout(self.form)

        self.status = QLabel("")
        self.status.setStyleSheet("color:#c0392b;")
        lay.addWidget(self.status)

        btns = QVBoxLayout()
        self.primary_btn = QPushButton()
        self.primary_btn.clicked.connect(self._submit)
        self.primary_btn.setDefault(True)
        btns.addWidget(self.primary_btn)

        row = QHBoxLayout()
        self.switch_btn = QPushButton()
        self.switch_btn.clicked.connect(self._toggle_mode)
        self.guest_btn = QPushButton("以游客身份进入")
        self.guest_btn.clicked.connect(self.reject)
        row.addWidget(self.switch_btn)
        row.addWidget(self.guest_btn)
        btns.addLayout(row)
        lay.addLayout(btns)

        self._apply_mode()

    # ---------- 模式 ----------

    def _toggle_mode(self):
        self._mode = "register" if self._mode == "login" else "login"
        self._apply_mode()

    def _apply_mode(self):
        reg = self._mode == "register"
        self.title_lbl.setText("注册账号" if reg else "登录账号")
        self.primary_btn.setText("注册并登录" if reg else "登录")
        self.switch_btn.setText("已有账号？去登录" if reg else "没有账号？去注册")
        # 控制邮箱行（字段 + 标签）的可见性
        for i in range(self.form.rowCount()):
            lbl = self.form.itemAt(i, QFormLayout.LabelRole)
            fld = self.form.itemAt(i, QFormLayout.FieldRole)
            if fld and fld.widget() is self.email_edit:
                if lbl and lbl.widget():
                    lbl.widget().setVisible(reg)
                self.email_edit.setVisible(reg)
                break

    # ---------- 提交 ----------

    def _submit(self):
        if self._busy:
            return
        username = self.user_edit.text().strip()
        password = self.pwd_edit.text()
        email = self.email_edit.text().strip()
        if not username or not password:
            self.status.setText("用户名和密码不能为空")
            return
        self._busy = True
        self.primary_btn.setEnabled(False)
        self.status.setText("处理中…")
        self.status.setStyleSheet("color:#666;")
        b = self._b
        mode = self._mode

        def _run():
            try:
                if mode == "register":
                    ok = self.auth.register(username, password, email)
                    msg = "注册成功" if ok else "注册失败，请检查输入或稍后重试"
                else:
                    ok = self.auth.login(username, password)
                    msg = "登录成功" if ok else "用户名或密码错误"
            except Exception as e:  # noqa: BLE001
                ok, msg = False, str(e)
            b.done.emit(ok, msg)

        threading.Thread(target=_run, daemon=True).start()

    def _on_done(self, ok: bool, msg: str):
        self._busy = False
        self.primary_btn.setEnabled(True)
        if ok:
            self.status.setStyleSheet("color:#27ae60;")
            self.status.setText(msg)
            self.login_succeeded.emit(self.user_edit.text().strip())
            self.accept()
        else:
            self.status.setStyleSheet("color:#c0392b;")
            self.status.setText(msg)

    # 回车即提交
    def keyPressEvent(self, event):
        if event.key() in (Qt.Key_Return, Qt.Key_Enter):
            focused = self.focusWidget()
            if focused in (self.user_edit, self.pwd_edit, self.email_edit):
                self._submit()
                return
        super().keyPressEvent(event)
