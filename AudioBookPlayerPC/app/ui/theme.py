"""主题管理：亮/暗配色 + 全局 QSS 样式表。

对应 Android 的 themes/colors。提供 ``Theme.apply(app, night_mode)``
一次性给整个 QApplication 打上样式表。"""
from __future__ import annotations

from typing import Dict

from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import QApplication


class Theme:
    """全局主题。集中管理颜色 token、字号和 QSS 样式表字符串。"""

    # ---- 字号（像素，作为点大小近似）----
    FONT_BASE = 14        # 全局基础字号
    FONT_READER = 18      # 阅读页默认字号
    FONT_SMALL = 12
    FONT_LARGE = 20
    FONT_TITLE = 24

    # ------------------------------------------------------------------
    # 调色板
    # ------------------------------------------------------------------
    @staticmethod
    def light_colors() -> Dict[str, str]:
        """亮色模式配色 token。"""
        return {
            "bg": "#f5f6fa",             # 窗口背景
            "surface": "#ffffff",        # 面板 / 侧边栏 / 卡片背景
            "surface_alt": "#eef1f6",    # 次级表面 / 输入框背景
            "hover": "#e9edf3",          # 悬停背景
            "selected": "#dceaf7",       # 选中背景（非高亮）
            "text": "#1f2430",           # 主文本
            "text_secondary": "#6b7280", # 次要文本
            "text_disabled": "#a8b0bd",  # 禁用文本
            "text_inverse": "#ffffff",   # 反色文本（accent 之上）
            "accent": "#1976d2",         # 主题强调色
            "accent_hover": "#1565c0",
            "accent_pressed": "#0d47a1",
            "highlight": "#ffe082",      # 当前播放行高亮背景
            "highlight_text": "#1f2430",
            "border": "#d5dbe5",         # 边框 / 分割线
            "disabled": "#b7bec9",
            "success": "#2e7d32",
            "warning": "#ef6c00",
            "error": "#c62828",
            "scrollbar": "#c3cad6",
            "scrollbar_hover": "#9aa5b4",
            "reader_bg": "#fbfaf5",      # 阅读区背景（纸张色）
            "reader_text": "#2b2b2b",
        }

    @staticmethod
    def dark_colors() -> Dict[str, str]:
        """夜间模式配色 token。"""
        return {
            "bg": "#1a1a2e",             # 深色背景
            "surface": "#16213e",
            "surface_alt": "#0f3460",
            "hover": "#233457",
            "selected": "#27406b",
            "text": "#e0e0e0",           # 浅色文本
            "text_secondary": "#9aa4b8",
            "text_disabled": "#5c6679",
            "text_inverse": "#0d1117",
            "accent": "#4fc3f7",         # 强调色
            "accent_hover": "#81d4fa",
            "accent_pressed": "#29b6f6",
            "highlight": "#3a5a8c",      # 播放行高亮背景
            "highlight_text": "#ffffff",
            "border": "#2c3e5c",
            "disabled": "#4a5468",
            "success": "#66bb6a",
            "warning": "#ffb74d",
            "error": "#ef5350",
            "scrollbar": "#33405c",
            "scrollbar_hover": "#4a5c82",
            "reader_bg": "#1b1b2a",
            "reader_text": "#d8dbe2",
        }

    @classmethod
    def colors(cls, night_mode: bool) -> Dict[str, str]:
        return cls.dark_colors() if night_mode else cls.light_colors()

    # ------------------------------------------------------------------
    # 应用
    # ------------------------------------------------------------------
    @classmethod
    def apply(cls, app: QApplication, night_mode: bool) -> Dict[str, str]:
        """设置全局字体与样式表。返回本次使用的配色（便于读取 token）。"""
        c = cls.colors(night_mode)
        base = QFont("Microsoft YaHei UI", cls.FONT_BASE)
        base.setStyleHint(QFont.SansSerif)
        app.setFont(base)
        qss = cls.build_qss(c)
        # ⚠️ **内容没变就别再 setStyleSheet**（2026-11-07 实测踩坑）：
        #   ``QApplication.setStyleSheet`` 会把**整个 UI 重新 polish 一遍**
        #   （遍历所有现存 widget，逐个重算样式）。MainWindow.__init__ 每次都会
        #   调 Theme.apply ⇒ 反复调用 ⇒ 既慢又脆：若此刻应用里存在正在析构 /
        #   已失效的 widget，polish 会踩到野指针 ⇒ **access violation 段错误**
        #   （实测崩在 ``tests/test_remember_username.py`` 建 MainWindow 时）。
        #   同一份 QSS 重复设置毫无收益，直接跳过即可。
        #   注：Qt 不会规范化 QSS 文本，``app.styleSheet()`` 与原文逐字符相等，
        #   可以直接比较（实测 build_qss 8427 字符，set 后仍 8427 且完全相等）。
        if app.styleSheet() != qss:
            app.setStyleSheet(qss)
        # 记录当前主题，供其它组件读取 token
        _CURRENT.update(c)
        _CURRENT["night_mode"] = "1" if night_mode else "0"
        return c

    @staticmethod
    def current() -> Dict[str, str]:
        """返回当前生效的配色 token（apply 之后有效）。"""
        return dict(_CURRENT)

    @classmethod
    def build_qss(cls, c: Dict[str, str]) -> str:
        """根据配色生成完整样式表字符串。"""
        merged = dict(c)
        merged["base_px"] = cls.FONT_BASE
        merged["small_px"] = cls.FONT_SMALL
        merged["large_px"] = cls.FONT_LARGE
        merged["title_px"] = cls.FONT_TITLE
        merged["reader_px"] = cls.FONT_READER
        return _QSS_TEMPLATE.format(**merged)


# apply() 之后由 Theme 维护的全局 token 字典，供组件读取当前颜色
_CURRENT: Dict[str, str] = Theme.light_colors()


def token(name: str, fallback: str = "") -> str:
    """便捷读取当前主题配色 token。"""
    return _CURRENT.get(name, fallback)


def is_night_mode() -> bool:
    return _CURRENT.get("night_mode", "0") == "1"


# ----------------------------------------------------------------------
# QSS 模板
#   使用 {token} 占位符，由 build_qss 通过配色字典填充。
# ----------------------------------------------------------------------
_QSS_TEMPLATE = """
/* ============ 主窗口 / 通用容器 ============ */
QMainWindow {{
    background-color: {bg};
}}

/* 主窗口中央容器：绘制主题背景（配合 WA_StyledBackground），
   避免透明页面透出底层黑色窗口。ID 选择器优先级高于通用 QWidget 规则。 */
#centralRoot {{
    background-color: {bg};
}}

QWidget {{
    background-color: transparent;
    color: {text};
    font-size: {base_px}px;
}}

QDialog {{
    background-color: {surface};
}}

/* ============ 标签 ============ */
QLabel {{
    background-color: transparent;
    color: {text};
    padding: 1px;
}}
QLabel[secondary="true"] {{
    color: {text_secondary};
}}
QLabel[role="title"] {{
    font-size: {large_px}px;
    font-weight: bold;
    color: {text};
}}

/* ============ 按钮 ============ */
QPushButton {{
    background-color: {surface_alt};
    color: {text};
    border: 1px solid {border};
    border-radius: 6px;
    padding: 7px 14px;
    min-height: 20px;
}}
QPushButton:hover {{
    background-color: {hover};
    border-color: {accent};
}}
QPushButton:pressed {{
    background-color: {selected};
}}
QPushButton:disabled {{
    color: {text_disabled};
    background-color: {surface_alt};
    border-color: {border};
}}
QPushButton:checked {{
    background-color: {accent};
    color: {text_inverse};
    border-color: {accent_pressed};
}}
QPushButton[accent="true"] {{
    background-color: {accent};
    color: {text_inverse};
    border: none;
    font-weight: bold;
}}
QPushButton[accent="true"]:hover {{
    background-color: {accent_hover};
}}
QPushButton[accent="true"]:pressed {{
    background-color: {accent_pressed};
}}
QPushButton[danger="true"] {{
    background-color: {error};
    color: #ffffff;
    border: none;
}}
QPushButton[flat="true"] {{
    background-color: transparent;
    border: none;
}}
QPushButton[flat="true"]:hover {{
    background-color: {hover};
}}

/* ============ 列表 / 列表项 ============ */
QListWidget, QListView {{
    background-color: {surface};
    color: {text};
    border: 1px solid {border};
    border-radius: 8px;
    padding: 4px;
    outline: none;
}}
QListWidget::item, QListView::item {{
    background-color: transparent;
    color: {text};
    border: none;
    border-radius: 6px;
    padding: 6px 8px;
    margin: 1px 2px;
}}
QListWidget::item:hover, QListView::item:hover {{
    background-color: {hover};
}}
QListWidget::item:selected, QListView::item:selected {{
    background-color: {selected};
    color: {text};
}}
QListWidget::item:selected:active {{
    background-color: {accent};
    color: {text_inverse};
}}

/* ============ 滚动条 ============ */
QScrollBar:vertical {{
    background: transparent;
    width: 12px;
    margin: 2px;
}}
QScrollBar::handle:vertical {{
    background: {scrollbar};
    border-radius: 5px;
    min-height: 30px;
}}
QScrollBar::handle:vertical:hover {{
    background: {scrollbar_hover};
}}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{
    height: 0px;
}}
QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical {{
    background: transparent;
}}
QScrollBar:horizontal {{
    background: transparent;
    height: 12px;
    margin: 2px;
}}
QScrollBar::handle:horizontal {{
    background: {scrollbar};
    border-radius: 5px;
    min-width: 30px;
}}
QScrollBar::handle:horizontal:hover {{
    background: {scrollbar_hover};
}}
QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal {{
    width: 0px;
}}
QScrollBar::add-page:horizontal, QScrollBar::sub-page:horizontal {{
    background: transparent;
}}

/* ============ 进度条 ============ */
QProgressBar {{
    background-color: {surface_alt};
    border: none;
    border-radius: 6px;
    height: 12px;
    text-align: center;
    color: {text};
}}
QProgressBar::chunk {{
    background-color: {accent};
    border-radius: 6px;
}}
QProgressBar[role="segment"] {{
    height: 8px;
}}
QProgressBar[role="segment"]::chunk {{
    background-color: {success};
}}

/* ============ 滑动条（音量 / 播放进度）============ */
QSlider::groove:horizontal {{
    background: {surface_alt};
    height: 6px;
    border-radius: 3px;
}}
QSlider::sub-page:horizontal {{
    background: {accent};
    height: 6px;
    border-radius: 3px;
}}
QSlider::handle:horizontal {{
    background: {accent};
    width: 14px;
    margin: -5px 0;
    border-radius: 7px;
}}
QSlider::handle:horizontal:hover {{
    background: {accent_hover};
}}

/* ============ 标签页 ============ */
QTabWidget::pane {{
    border: 1px solid {border};
    border-radius: 8px;
    background: {surface};
    top: -1px;
}}
QTabBar::tab {{
    background: {surface_alt};
    color: {text_secondary};
    border: 1px solid {border};
    border-bottom: none;
    border-top-left-radius: 6px;
    border-top-right-radius: 6px;
    padding: 8px 18px;
    margin-right: 2px;
}}
QTabBar::tab:selected {{
    background: {surface};
    color: {accent};
    font-weight: bold;
}}
QTabBar::tab:hover:!selected {{
    background: {hover};
}}

/* ============ 菜单 ============ */
QMenu {{
    background-color: {surface};
    border: 1px solid {border};
    border-radius: 8px;
    padding: 6px;
}}
QMenu::item {{
    padding: 7px 28px 7px 20px;
    border-radius: 5px;
    color: {text};
}}
QMenu::item:selected {{
    background-color: {accent};
    color: {text_inverse};
}}
QMenu::item:disabled {{
    color: {text_disabled};
}}
QMenu::separator {{
    height: 1px;
    background: {border};
    margin: 6px 10px;
}}

/* ============ 下拉框 ============ */
QComboBox {{
    background-color: {surface_alt};
    color: {text};
    border: 1px solid {border};
    border-radius: 6px;
    padding: 6px 10px;
    min-height: 22px;
}}
QComboBox:hover {{
    border-color: {accent};
}}
QComboBox::drop-down {{
    border: none;
    width: 22px;
}}
QComboBox QAbstractItemView {{
    background-color: {surface};
    color: {text};
    border: 1px solid {border};
    border-radius: 6px;
    selection-background-color: {accent};
    selection-color: {text_inverse};
    outline: none;
}}

/* ============ 数字输入 ============ */
QSpinBox, QDoubleSpinBox {{
    background-color: {surface_alt};
    color: {text};
    border: 1px solid {border};
    border-radius: 6px;
    padding: 5px 8px;
    min-height: 22px;
}}
QSpinBox:hover, QDoubleSpinBox:hover {{
    border-color: {accent};
}}
QSpinBox::up-button, QDoubleSpinBox::up-button {{
    subcontrol-origin: border;
    subcontrol-position: top right;
    width: 18px;
    border-left: 1px solid {border};
    background: {surface};
}}
QSpinBox::down-button, QDoubleSpinBox::down-button {{
    subcontrol-origin: border;
    subcontrol-position: bottom right;
    width: 18px;
    border-left: 1px solid {border};
    background: {surface};
}}

/* ============ 复选框 ============ */
QCheckBox {{
    color: {text};
    spacing: 8px;
    background: transparent;
}}
QCheckBox::indicator {{
    width: 18px;
    height: 18px;
    border: 1px solid {border};
    border-radius: 4px;
    background: {surface_alt};
}}
QCheckBox::indicator:hover {{
    border-color: {accent};
}}
QCheckBox::indicator:checked {{
    background: {accent};
    border-color: {accent};
    image: none;
}}

/* ============ 输入框 ============ */
QLineEdit, QTextEdit, QPlainTextEdit {{
    background-color: {surface_alt};
    color: {text};
    border: 1px solid {border};
    border-radius: 6px;
    padding: 7px 10px;
    selection-background-color: {accent};
    selection-color: {text_inverse};
}}
QLineEdit:focus, QTextEdit:focus, QPlainTextEdit:focus {{
    border: 1px solid {accent};
}}

/* ============ 分割器 ============ */
QSplitter::handle {{
    background-color: {border};
}}
QSplitter::handle:horizontal {{
    width: 3px;
}}
QSplitter::handle:vertical {{
    height: 3px;
}}
QSplitter::handle:hover {{
    background-color: {accent};
}}

/* ============ 框架 / 分组 ============ */
QFrame[frameShape="4"], QFrame[frameShape="5"] {{
    color: {border};
}}
QFrame[card="true"] {{
    background-color: {surface};
    border: 1px solid {border};
    border-radius: 10px;
}}
QFrame[nav="true"] {{
    background-color: {surface};
    border-right: 1px solid {border};
}}
QFrame[topbar="true"] {{
    background-color: {surface};
    border-bottom: 1px solid {border};
}}
QFrame[audiobar="true"] {{
    background-color: {surface};
    border-top: 1px solid {border};
}}
QGroupBox {{
    color: {accent};
    border: 1px solid {border};
    border-radius: 8px;
    margin-top: 12px;
    padding-top: 8px;
    font-weight: bold;
}}
QGroupBox::title {{
    subcontrol-origin: margin;
    left: 12px;
    padding: 0 4px;
}}

/* ============ 状态栏 / 工具栏 / 提示 ============ */
QStatusBar {{
    background-color: {surface};
    color: {text_secondary};
}}
QStatusBar::item {{
    border: none;
}}
QToolBar {{
    background-color: {surface};
    border-bottom: 1px solid {border};
    spacing: 6px;
}}
QToolTip {{
    background-color: {text};
    color: {surface};
    border: none;
    padding: 6px 8px;
    border-radius: 5px;
}}
"""

# 需要注入 QSS 的字号变量已在 Theme.build_qss 内部合并。
