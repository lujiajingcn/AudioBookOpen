# -*- coding: utf-8 -*-
"""侧栏显隐的交互链路验证：模拟真实点击 / 快捷键，检查状态与落盘。

离屏运行：
    set QT_QPA_PLATFORM=offscreen && python tools/check_nav_interaction.py
"""
from __future__ import annotations

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QSettings, Qt  # noqa: E402
from PySide6.QtTest import QTest  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from app.core.config import AppConfig  # noqa: E402
from app.main_window import (  # noqa: E402
    MainWindow, NAV_COLLAPSED_KEY, NAV_VISIBLE_KEY,
)


def _isolate_settings():
    """把 QSettings 落盘位置重定向到临时目录，避免污染用户真实偏好。

    ⚠ 两个坑：
    1) 必须在 new MainWindow 之前调用——构造函数里的 _restore_nav_state()
       会读设置并写回，隔离晚了就读到真实值。
    2) Windows 上 QSettings 默认走 NativeFormat(注册表)，setPath 对它**无效**。
       必须显式把默认格式切成 IniFormat，setPath 才对 IniFormat 生效。
    """
    import tempfile
    d = os.path.join(tempfile.gettempdir(), "abpc_check_settings")
    os.makedirs(d, exist_ok=True)
    QSettings.setDefaultFormat(QSettings.IniFormat)
    QSettings.setPath(QSettings.IniFormat, QSettings.UserScope, d)
    return d


def main():
    _isolate_settings()
    app = QApplication(sys.argv)

    settings = QSettings("AudioBook", "AudioBookPlayerPC_Check")
    settings.clear()

    cfg = AppConfig()
    cfg.data_dir = os.path.join(os.environ.get("TEMP", "."), "abpc_check")
    win = MainWindow(cfg)
    # 用独立 QSettings，避免动用户真实偏好
    win._settings = settings
    # ⚠ 显式复位初始状态：MainWindow 构造期间 _restore_nav_state() 已按
    #   「隔离目录里的残留值」设过一次状态，隔离目录若被其它脚本写过
    #   （nav_collapsed=1 等），断言起点就会漂移。这里强制回到「展开 + 显示」。
    win._set_nav_visible(True)
    win.nav.set_collapsed(False)
    win.show()
    app.processEvents()

    ok = True

    def check(label, cond):
        nonlocal ok
        print(("PASS " if cond else "FAIL ") + label)
        if not cond:
            ok = False

    # --- 1) 初始：展开且可见 ---
    check("初始 _nav_visible=True", win._nav_visible is True)
    check("初始 nav 未隐藏", not win.nav.isHidden())
    check("初始 nav 未折叠", win.nav.collapsed is False)

    # --- 2) 侧栏内部 « 按钮：折叠成图标条 ---
    win.nav._fold_btn.click()
    app.processEvents()
    check("点击 « 后 collapsed=True", win.nav.collapsed is True)
    check("点击 « 后宽度=60", win.nav.width() == 60)
    check("折叠态已落盘", settings.value(NAV_COLLAPSED_KEY) == "1")
    check("折叠态标题已隐藏", win.nav._buttons[0]._title_lbl.isHidden())

    # --- 3) 折叠态下仍能切页 ---
    win.nav._buttons[1].click()
    app.processEvents()
    check("折叠态点击第 2 项 current=1", win.nav.current_index == 1)

    # --- 4) 再点 » 展开 ---
    win.nav._fold_btn.click()
    app.processEvents()
    check("再点 » 后 collapsed=False", win.nav.collapsed is False)
    check("展开后宽度=144", win.nav.width() == 144)

    # --- 5) 顶栏按钮：完全隐藏 ---
    win.nav_toggle_btn.click()
    app.processEvents()
    check("顶栏点击后 nav 隐藏", win.nav.isHidden())
    check("顶栏点击后 _nav_visible=False", win._nav_visible is False)
    check("隐藏已落盘", settings.value(NAV_VISIBLE_KEY) == "0")
    check("顶栏按钮变成 ☰", win.nav_toggle_btn.text() == "☰")
    check("设置菜单项已取消勾选", win.nav_action.isChecked() is False)

    # --- 6) Ctrl+B 恢复 ---
    QTest.keyClick(win, "B", Qt.ControlModifier)
    app.processEvents()
    check("Ctrl+B 后 nav 恢复显示", not win.nav.isHidden())
    check("Ctrl+B 后 _nav_visible=True", win._nav_visible is True)
    check("顶栏按钮恢复 «", win.nav_toggle_btn.text() == "«")

    # --- 7) 设置菜单里的勾选项也能控制 ---
    win.nav_action.setChecked(False)
    app.processEvents()
    check("取消勾选后 nav 隐藏", win.nav.isHidden())
    win.nav_action.setChecked(True)
    app.processEvents()
    check("重新勾选后 nav 显示", not win.nav.isHidden())

    # --- 8) 关闭后重开：状态能从 QSettings 恢复 ---
    settings.setValue(NAV_VISIBLE_KEY, "0")
    settings.setValue(NAV_COLLAPSED_KEY, "1")
    win._restore_nav_state()
    app.processEvents()
    check("重启后恢复为隐藏", win.nav.isHidden())
    check("重启后恢复为折叠", win.nav.collapsed is True)

    settings.clear()
    print("\n=== ALL PASS ===" if ok else "\n=== HAS FAILURE ===")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
