#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""AudioBookPlayerPC —— 桌面有声书阅读器入口。

对标 Android AudioBookPlayer 全部功能：
  书架 / 书城 / 在线阅读 / 在线+本机TTS合成 / 播放 / 社区 / 积分 / 词典 / 游客模式

用法：
    python run.py
    # 或双击 start.bat
"""
import sys
import os

# 保证工程根在 sys.path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from PySide6.QtWidgets import QApplication
from PySide6.QtCore import Qt, QTranslator, QLibraryInfo

from app.main_window import MainWindow
from app.core.config import AppConfig
from app.ui.icons import apply_app_identity


def _install_zh_translator(app: QApplication) -> None:
    """加载 Qt 自带中文 .qm，把内建控件文本本地化。

    解决"提示内容是中文、按钮却是英文(OK/Cancel/Yes/No/Close)"的割裂：
    QMessageBox 标准按钮、QFileDialog 打开/保存/取消、QDialogButtonBox 的确定/取消
    等都来自 Qt 自身的 tr() 字符串，只有装了 qtbase_zh_CN.qm 才会变中文。
    必须在创建任何窗口/对话框之前安装，才对后续新建的控件生效。
    """
    # 候选翻译目录：优先 PySide6 包内的 translations/（源码运行），再退到 QLibraryInfo
    candidates = []
    try:
        import PySide6
        candidates.append(os.path.join(os.path.dirname(PySide6.__file__), "translations"))
    except Exception:  # noqa: BLE001
        pass
    try:
        candidates.append(QLibraryInfo.path(QLibraryInfo.LibraryPath.TranslationsPath))
    except Exception:  # noqa: BLE001
        pass

    translators = []
    for base in candidates:
        if not base or not os.path.isdir(base):
            continue
        got_any = False
        # qtbase_* 覆盖 QWidget/QMessageBox/QFileDialog 等；qt_* 覆盖 QtWinExtras 等杂项
        for cat in ("qtbase_zh_CN", "qt_zh_CN"):
            path = os.path.join(base, cat + ".qm")
            if not os.path.isfile(path):
                continue
            tr = QTranslator(app)
            if tr.load(path):
                app.installTranslator(tr)
                translators.append(tr)  # 持强引用，防止被 GC 回收导致翻译失效
                got_any = True
        if got_any:
            break
    # 挂到 app 上保命；找不到 .qm 也不报错，退化为英文按钮但不影响功能
    app._zh_translators = translators


def main():
    # 高 DPI 适配
    QApplication.setHighDpiScaleFactorRoundingPolicy(
        Qt.HighDpiScaleFactorRoundingPolicy.PassThrough)

    app = QApplication(sys.argv)
    app.setApplicationName("AudioBookPlayerPC")
    app.setApplicationDisplayName("有声伴读")
    app.setOrganizationName("AudioBook")

    # 应用图标：影响窗口标题栏、任务栏按钮、Alt-Tab 切换条。
    # Windows 需要显式 AppUserModelID，否则任务栏按 exe 名分组会串图标。
    try:
        import ctypes
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(
            "AudioBook.AudioBookPlayerPC")
    except Exception:  # noqa: BLE001 —— 非 Windows 或权限不足时忽略
        pass
    apply_app_identity(app)

    # 装中文翻译——必须在建窗前，令所有标准按钮/系统对话框显示中文
    _install_zh_translator(app)

    cfg = AppConfig.load()
    window = MainWindow(cfg)
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
