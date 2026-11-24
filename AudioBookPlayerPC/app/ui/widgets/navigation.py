"""左侧导航栏：竖排 图标+文字 按钮，高亮当前项，切换时发信号。

对标 Android 底部/侧边 NavigationBar 的角色。

支持两种「变窄」形态：
  - 折叠态（仅图标）：宽度 144→56，隐藏文字只留图标，菜单仍可点击切换页面；
    由侧栏顶部的 « 按钮切换。
  - 完全隐藏：由 MainWindow 用 setVisible 控制（顶栏 ☰ 按钮 / Ctrl+B）。
"""
from __future__ import annotations

from typing import List, Tuple

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QFont
from PySide6.QtWidgets import (
    QFrame, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QToolButton,
    QWidget, QSizePolicy,
)

from app.ui.theme import Theme
from app.ui.icons import brand_icon


class _NavButton(QPushButton):
    """单个导航按钮：上图标(emoji/文字) + 下标题，纵向排列。"""

    def __init__(self, icon: str, title: str, parent: QWidget | None = None):
        super().__init__(parent)
        self._title = title
        self.setCheckable(True)
        self.setAutoExclusive(True)
        self.setProperty("navButton", True)
        self.setCursor(Qt.PointingHandCursor)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.setMinimumHeight(70)  # 原 64，放大 10%
        self.setToolTip(title)

        outer = QVBoxLayout(self)
        outer.setContentsMargins(4, 8, 4, 8)
        outer.setSpacing(2)

        row = QHBoxLayout()
        row.setContentsMargins(0, 0, 0, 0)
        row.setSpacing(8)
        self._row = row

        self._icon_lbl = QLabel(icon)
        self._icon_lbl.setAlignment(Qt.AlignCenter)
        icon_font = QFont()
        icon_font.setPointSize(18)
        self._icon_lbl.setFont(icon_font)
        self._icon_lbl.setFixedWidth(24)

        self._title_lbl = QLabel(title)
        self._title_lbl.setAlignment(Qt.AlignLeft | Qt.AlignVCenter)
        title_font = QFont()
        title_font.setPointSize(13)
        self._title_lbl.setFont(title_font)

        row.addWidget(self._icon_lbl)
        row.addWidget(self._title_lbl, 1)
        outer.addLayout(row)

    # 允许外部改文字/图标
    def set_text(self, title: str):
        self._title = title
        self._title_lbl.setText(title)
        self.setToolTip(title)

    def set_icon(self, icon: str):
        self._icon_lbl.setText(icon)

    def set_collapsed(self, collapsed: bool):
        """折叠态：只留图标，标题隐藏（tooltip 仍保留全名）。

        折叠时把整行图标居中，否则标题隐藏后图标会贴左，看起来像没对齐。
        """
        collapsed = bool(collapsed)
        self._title_lbl.setVisible(not collapsed)
        self._row.setAlignment(Qt.AlignCenter if collapsed else Qt.AlignLeft)
        self.setToolTip(self._title)


class NavigationBar(QFrame):
    """竖向导航栏容器。

    信号:
        current_changed(int): 当前选中项索引发生变化时发出。
        item_clicked(int): 任一导航项被点击都发出（**含重复点击当前项**）。
        collapsed_changed(bool): 折叠态（仅图标）切换时发出。
    """

    current_changed = Signal(int)
    item_clicked = Signal(int)
    collapsed_changed = Signal(bool)

    # 默认四项：书架 / 书城 / 发现 / 我的
    DEFAULT_ITEMS: List[Tuple[str, str]] = [
        ("📚", "书架"),
        ("🏬", "书城"),
        ("🧭", "发现"),
        ("👤", "我的"),
    ]

    EXPANDED_WIDTH = 144      # 展开态：图标 + 文字
    COLLAPSED_WIDTH = 60      # 收起态：仅图标

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.setProperty("nav", True)
        self.setObjectName("NavigationBar")
        self.setFixedWidth(self.EXPANDED_WIDTH)
        self._buttons: List[_NavButton] = []
        self._current = -1
        self._collapsed = False

        self._layout = QVBoxLayout(self)
        self._layout.setContentsMargins(8, 12, 8, 12)
        self._layout.setSpacing(4)

        # 顶部一行：折叠按钮（« 收起为图标 / » 展开）
        self._top_row = QWidget()
        top_lay = QHBoxLayout(self._top_row)
        top_lay.setContentsMargins(0, 0, 0, 0)
        top_lay.addStretch(1)
        self._fold_btn = QToolButton()
        self._fold_btn.setText("«")
        self._fold_btn.setToolTip("收起侧栏（只显示图标）")
        self._fold_btn.setProperty("navToggle", True)
        self._fold_btn.setCursor(Qt.PointingHandCursor)
        self._fold_btn.setFixedSize(26, 26)
        self._fold_btn.setAutoRaise(True)
        self._fold_btn.clicked.connect(self.toggle_collapsed)
        top_lay.addWidget(self._fold_btn, 0, Qt.AlignTop)
        self._layout.addWidget(self._top_row)

        # 品牌图标（顶部居中）。资源缺失时静默跳过，不留空位。
        _brand = brand_icon()
        if not _brand.isNull():
            logo = QLabel()
            logo.setPixmap(_brand.pixmap(36, 36))
            logo.setFixedSize(36, 36)
            logo.setAlignment(Qt.AlignCenter)
            logo.setToolTip("有声伴读")
            self._layout.addWidget(logo, 0, Qt.AlignHCenter)

        # 导航项插在 logo 之后、stretch 之前（add_item 用 count()-1 定位）
        self._layout.addStretch(1)

        # 主题化样式（跟随当前亮/暗配色）
        self.apply_theme()

    def apply_theme(self):
        """根据当前主题刷新导航栏样式。主题切换后应调用。"""
        self.setStyleSheet(_nav_qss(Theme.current()))

    # ------------------------------------------------------------------
    # 折叠 / 展开
    # ------------------------------------------------------------------
    @property
    def collapsed(self) -> bool:
        """当前是否为「仅图标」折叠态。"""
        return self._collapsed

    def set_collapsed(self, collapsed: bool):
        """切换折叠态。

        折叠 = 只显示图标、隐藏文字（宽度 144→56），菜单仍可点击切换页面；
        完全隐藏侧栏由 MainWindow 用 setVisible 控制，不在本组件内。
        """
        collapsed = bool(collapsed)
        if collapsed == self._collapsed:
            return
        self._collapsed = collapsed
        self.setFixedWidth(self.COLLAPSED_WIDTH if collapsed
                           else self.EXPANDED_WIDTH)
        # 收起时把左右留白收紧，图标条不至于挤在一条窄缝里
        self._layout.setContentsMargins(4 if collapsed else 8, 12,
                                        4 if collapsed else 8, 12)
        for b in self._buttons:
            b.set_collapsed(collapsed)
        self._fold_btn.setText("»" if collapsed else "«")
        self._fold_btn.setToolTip("展开侧栏" if collapsed else "收起侧栏（只显示图标）")
        self.collapsed_changed.emit(collapsed)

    def toggle_collapsed(self):
        """折叠态 ⇄ 展开态。"""
        self.set_collapsed(not self._collapsed)

    # ------------------------------------------------------------------
    def add_item(self, title: str, icon: str = "") -> int:
        btn = _NavButton(icon or "•", title)
        idx = len(self._buttons)
        btn.clicked.connect(lambda _checked=False, i=idx: self._on_item_clicked(i))
        self._buttons.append(btn)
        # 插入到 stretch 之前
        self._layout.insertWidget(self._layout.count() - 1, btn)
        return idx

    def _on_item_clicked(self, index: int):
        """导航项被点击。

        ``set_current`` 在索引未变时会**直接返回**（只补一次选中态），
        于是「重复点击当前项」不会发 ``current_changed``。阅读器/合成页
        是叠在导航页之上的额外页，用户只能靠点左侧导航离开，因此这里
        额外发 ``item_clicked``，让上层能识别「点当前项也要切页」。
        """
        self.set_current(index)
        self.item_clicked.emit(index)

    def set_items(self, items: List[Tuple[str, str]]):
        """重建导航项。items: [(icon, title), ...]。"""
        for b in self._buttons:
            self._layout.removeWidget(b)
            b.deleteLater()
        self._buttons.clear()
        self._current = -1
        for icon, title in items:
            self.add_item(title, icon)
        if self._buttons:
            self.set_current(0)

    # ------------------------------------------------------------------
    @property
    def current_index(self) -> int:
        return self._current

    def set_current(self, index: int):
        """程序化切换当前项。发出 current_changed（若索引变化）。"""
        if index < 0 or index >= len(self._buttons):
            return
        if index == self._current:
            # 仍确保视觉选中
            self._buttons[index].setChecked(True)
            return
        self._current = index
        for i, b in enumerate(self._buttons):
            b.setChecked(i == index)
        self.current_changed.emit(index)

    def count(self) -> int:
        return len(self._buttons)

    def set_enabled_at(self, index: int, enabled: bool):
        if 0 <= index < len(self._buttons):
            self._buttons[index].setEnabled(enabled)


# ----------------------------------------------------------------------
# 导航栏局部样式（依据主题配色动态生成）
# ----------------------------------------------------------------------
def _nav_qss(c: dict) -> str:
    css = """
#NavigationBar {
    background-color: @surface@;
    border-right: 1px solid @border@;
}
QPushButton[navButton="true"] {
    background-color: @surface_alt@;
    border: 1px solid @border@;
    border-radius: 8px;
    color: @text@;
    padding: 4px 8px;
    text-align: left;
}
QPushButton[navButton="true"]:hover {
    background-color: @hover@;
    border-color: @accent@;
}
QPushButton[navButton="true"]:checked {
    background-color: @accent@;
    color: @text_inverse@;
    border-color: @accent_pressed@;
}
QPushButton[navButton="true"]:checked:hover {
    background-color: @accent_hover@;
}
QPushButton[navButton="true"]:disabled {
    color: @text_disabled@;
}
QToolButton[navToggle="true"] {
    background-color: transparent;
    border: none;
    border-radius: 6px;
    color: @text_secondary@;
    font-size: 15px;
    padding: 0;
}
QToolButton[navToggle="true"]:hover {
    background-color: @hover@;
    color: @text@;
}
QToolButton[navToggle="true"]:pressed {
    background-color: @selected@;
}
"""
    for key in ("surface", "surface_alt", "border", "text", "hover", "selected",
                "accent", "accent_hover", "accent_pressed", "text_inverse",
                "text_disabled", "text_secondary"):
        css = css.replace("@%s@" % key, c.get(key, "#888888"))
    return css
