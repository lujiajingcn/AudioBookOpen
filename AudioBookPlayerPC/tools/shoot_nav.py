# -*- coding: utf-8 -*-
"""离屏渲染验证：起真实 MainWindow，截「展开 / 折叠 / 隐藏」三态图。

用法：
    set QT_QPA_PLATFORM=offscreen && python tools/shoot_nav.py
输出：
    <工程>/agent_outputs/nav_expanded.png
    <工程>/agent_outputs/nav_collapsed.png
    <工程>/agent_outputs/nav_hidden.png
"""
from __future__ import annotations

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

OUT = os.path.join(ROOT, "agent_outputs")
os.makedirs(OUT, exist_ok=True)

from PySide6.QtCore import QSettings  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from app.core.config import AppConfig  # noqa: E402
from app.main_window import MainWindow  # noqa: E402


def _isolate_settings() -> str:
    """把 QSettings 的落盘位置重定向到临时目录。

    ⚠ 必须隔离：MainWindow 构造时会把「侧栏显隐/折叠态」写进用户真实的
    QSettings（AudioBook/AudioBookPlayerPC）。截图脚本若用真实设置，
    跑一次就会把用户下次启动的侧栏状态改成「隐藏/折叠」。

    ⚠ Windows 上 QSettings 默认走 NativeFormat(注册表)，setPath 对它**无效**；
    必须先 setDefaultFormat(IniFormat)，setPath 才会生效。
    """
    import tempfile
    d = os.path.join(tempfile.gettempdir(), "abpc_shoot_settings")
    os.makedirs(d, exist_ok=True)
    QSettings.setDefaultFormat(QSettings.IniFormat)
    QSettings.setPath(QSettings.IniFormat, QSettings.UserScope, d)
    return d


def main():
    _isolate_settings()
    app = QApplication(sys.argv)
    app.setStyleSheet("")

    cfg = AppConfig()
    cfg.data_dir = os.path.join(os.environ.get("TEMP", "."), "abpc_shot")
    win = MainWindow(cfg)
    win.resize(1080, 740)
    win.show()
    app.processEvents()

    def shot(name):
        app.processEvents()
        path = os.path.join(OUT, name)
        win.grab().save(path)
        print("saved:", path)

    # 1) 展开态
    win._set_nav_visible(True)
    win.nav.set_collapsed(False)
    shot("nav_expanded.png")

    # 2) 折叠态（仅图标）
    win._set_nav_visible(True)
    win.nav.set_collapsed(True)
    shot("nav_collapsed.png")

    # 3) 完全隐藏
    win.nav.set_collapsed(False)
    win._set_nav_visible(False)
    shot("nav_hidden.png")

    print("OK")


if __name__ == "__main__":
    main()
