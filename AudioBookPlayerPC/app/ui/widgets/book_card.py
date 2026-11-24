"""书城书籍卡片控件。

封面（按 cover_color 着色的占位块，取标题首字）、标题、作者、分类角标、下载/合成按钮。
"""
from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QFont
from PySide6.QtWidgets import (
    QFrame, QVBoxLayout, QHBoxLayout, QWidget, QLabel, QPushButton,
    QSizePolicy,
)

from app.models.models import StoreBook


def _readable_text(bg_hex: str) -> str:
    """根据背景色亮度选择黑/白前景文字。"""
    try:
        h = bg_hex.lstrip("#")
        if len(h) == 3:
            h = "".join(ch * 2 for ch in h)
        r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
        luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return "#111111" if luminance > 150 else "#ffffff"
    except Exception:
        return "#ffffff"


_FALLBACK_COVERS = ["#1976d2", "#7b1fa2", "#c2185b", "#388e3c",
                    "#f57c00", "#0097a7", "#512da8", "#d32f2f"]


class BookCard(QFrame):
    """书城书卡片。

    信号:
        clicked(int)          # store_book_id
        download_clicked(int) # store_book_id
    """

    clicked = Signal(int)
    download_clicked = Signal(int)

    def __init__(self, book: StoreBook, parent: QWidget | None = None):
        super().__init__(parent)
        self._book = book
        self.setProperty("card", True)
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedWidth(160)
        self.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Expanding)

        root = QVBoxLayout(self)
        root.setContentsMargins(10, 10, 10, 10)
        root.setSpacing(6)

        # --- 封面占位 ---
        cover_color = book.cover_color or _FALLBACK_COVERS[book.id % len(_FALLBACK_COVERS)]
        self.cover = QLabel()
        self.cover.setFixedSize(136, 180)
        self.cover.setAlignment(Qt.AlignCenter)
        initial = (book.title[:1] or "?")
        cf = QFont()
        cf.setPointSize(40)
        cf.setBold(True)
        self.cover.setFont(cf)
        self.cover.setText(initial)
        fg = _readable_text(cover_color)
        self.cover.setStyleSheet(
            f"background-color: {cover_color}; color: {fg};"
            "border-radius: 8px;")
        root.addWidget(self.cover, 0, Qt.AlignHCenter)

        # --- 标题 ---
        self.title_lbl = QLabel(book.title or "(无名)")
        self.title_lbl.setWordWrap(True)
        self.title_lbl.setMaximumHeight(44)
        self.title_lbl.setAlignment(Qt.AlignLeft | Qt.AlignTop)
        tf = QFont()
        tf.setPointSize(13)
        tf.setBold(True)
        self.title_lbl.setFont(tf)
        root.addWidget(self.title_lbl)

        # --- 作者 ---
        self.author_lbl = QLabel(book.author or "佚名")
        self.author_lbl.setProperty("secondary", True)
        self.author_lbl.setStyleSheet("color: palette(text); font-size: 12px;")
        root.addWidget(self.author_lbl)

        # --- 分类 + 下载量角标 ---
        badge_row = QHBoxLayout()
        badge_row.setSpacing(6)
        self.category_badge = QLabel(book.category or "综合")
        self.category_badge.setStyleSheet(
            "background-color: rgba(25,118,210,0.14); color: #1976d2;"
            "border-radius: 8px; padding: 2px 8px; font-size: 11px;")
        badge_row.addWidget(self.category_badge)
        badge_row.addStretch(1)
        self.download_lbl = QLabel(f"{book.download_count} 次")
        self.download_lbl.setProperty("secondary", True)
        badge_row.addWidget(self.download_lbl)
        root.addLayout(badge_row)

        # --- 操作按钮 ---
        self.action_btn = QPushButton("下载")
        self.action_btn.setProperty("accent", True)
        root.addWidget(self.action_btn)

        # 信号
        self.action_btn.clicked.connect(lambda: self.download_clicked.emit(self._book.id))

    # ------------------------------------------------------------------
    @property
    def book(self) -> StoreBook:
        return self._book

    @property
    def store_book_id(self) -> int:
        return self._book.id

    def set_action_text(self, text: str):
        self.action_btn.setText(text)

    def set_downloading(self, progress_text: str):
        """显示下载进度（复用按钮文本）。"""
        self.action_btn.setText(progress_text)
        self.action_btn.setEnabled(False)

    def set_ready(self):
        self.action_btn.setText("下载")
        self.action_btn.setEnabled(True)

    # ------------------------------------------------------------------
    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self.clicked.emit(self._book.id)
        super().mousePressEvent(event)
