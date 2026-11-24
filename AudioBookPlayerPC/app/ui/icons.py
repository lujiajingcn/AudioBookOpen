"""应用图标资源：加载 assets/icons 下的多尺寸图标并组装成 QIcon。

设计要点：
  - 资源定位与「代码根目录」解耦：源码运行时找 <project>/assets/icons，
    打包成 exe（PyInstaller）后找 sys._MEIPASS/assets/icons。
  - 用多张 PNG 组成一个 QIcon（addFile + QSize），让 Windows 在不同缩放
    比例下都能取到合适分辨率的图，而不是拉伸一张大图。
  - 任何一步失败都返回空 QIcon（isNull() 为 True），调用方只需判空即可，
    绝不因图标缺失让应用起不来。

图标来源：Android 端 AudioBookPlayer 的启动图标素材（mipmap-xxxhdpi 前景层中央
画面卡片，与传统 ic_launcher.png 同源），由 tools/make_icons.py 渲染出各尺寸 PNG
与 app.ico。桌面端与移动端视觉完全一致。
"""
from __future__ import annotations

import os
import sys
from functools import lru_cache
from typing import List, Optional

from PySide6.QtCore import QSize
from PySide6.QtGui import QIcon

# 与 tools/make_icons.py 的 SIZES 保持一致
ICON_SIZES = (16, 24, 32, 48, 64, 128, 256)

# 导航栏/工具栏等 UI 内使用的显示尺寸
BRAND_PX = 28


def _candidate_roots() -> List[str]:
    """返回可能存放 assets/icons 的目录（按优先级）。"""
    roots: List[str] = []

    # 1) PyInstaller 打包后：解包目录
    meipass = getattr(sys, "_MEIPASS", None)
    if meipass:
        roots.append(os.path.join(meipass, "assets"))

    # 2) 源码运行：本文件位于 <project>/app/ui/icons.py ⇒ 上溯两级是项目根
    here = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(os.path.dirname(here))
    roots.append(os.path.join(project_root, "assets"))

    # 3) 以当前工作目录为基准（从别处启动脚本的情况）
    roots.append(os.path.join(os.getcwd(), "assets"))

    return roots


def icons_dir() -> Optional[str]:
    """定位 assets/icons 目录；找不到返回 None。"""
    for root in _candidate_roots():
        d = os.path.join(root, "icons")
        if os.path.isdir(d) and os.path.isfile(os.path.join(d, "app.png")):
            return d
    return None


@lru_cache(maxsize=1)
def app_icon() -> QIcon:
    """返回应用主图标（多尺寸 QIcon）。找不到资源时返回空图标。"""
    d = icons_dir()
    if not d:
        return QIcon()

    icon = QIcon()
    added = False
    for s in ICON_SIZES:
        p = os.path.join(d, f"app_{s}.png")
        if os.path.isfile(p):
            icon.addFile(p, QSize(s, s))
            added = True

    if not added:
        # 退路：至少用 app.png
        p = os.path.join(d, "app.png")
        if os.path.isfile(p):
            icon.addFile(p)
            added = True

    if not added:
        return QIcon()

    # Windows 下再挂 .ico，资源管理器/任务栏优先取它
    ico = os.path.join(d, "app.ico")
    if os.path.isfile(ico):
        icon.addFile(ico)
    return icon


@lru_cache(maxsize=1)
def brand_icon() -> QIcon:
    """应用内嵌的品牌小图标（用于导航栏顶部/工具栏标题旁）。"""
    d = icons_dir()
    if not d:
        return QIcon()
    for name in ("app_32.png", "app_48.png", "app.png"):
        p = os.path.join(d, name)
        if os.path.isfile(p):
            return QIcon(p)
    return QIcon()


def apply_app_identity(app) -> bool:
    """把图标挂到 QApplication 上（影响任务栏、Alt-Tab、任务管理器）。

    返回是否成功应用了图标。Windows 7+ 任务栏按 exe 文件名分组，
    还需要 AppUserModelID 才能保证独立分组，但多数情况下图标已生效。
    """
    icon = app_icon()
    if icon.isNull():
        return False
    app.setWindowIcon(icon)
    return True
