"""目录弹窗：展示 TocEntry 列表，点击跳转。"""
from __future__ import annotations

from typing import List, Optional

from PySide6.QtCore import Signal, Qt
from PySide6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QPushButton, QLabel, QListWidget,
    QListWidgetItem,
)

from app.models.models import TocEntry


class TocDialog(QDialog):
    """Table of contents 弹窗。

    信号:
        entry_selected(paragraph_index, segment_number) —— 点击某章跳转。
        segment_number 约定 = paragraph_index + 1（有音频时），无音频为 0。
    """

    entry_selected = Signal(int, int)  # (paragraph_index, segment_number)

    def __init__(self, toc: List[TocEntry], title: str = "目录",
                 parent: Optional[QDialog] = None):
        super().__init__(parent)
        self.toc = list(toc or [])
        self.setWindowTitle(title)
        self.setMinimumSize(360, 480)
        self.setModal(True)

        lay = QVBoxLayout(self)
        head = QLabel(f"共 {len(self.toc)} 个章节")
        head.setStyleSheet("color:#888;")
        lay.addWidget(head)

        self.list = QListWidget(self)
        self.list.setAlternatingRowColors(True)
        self.list.itemActivated.connect(self._on_activate)
        self.list.itemClicked.connect(self._on_activate)
        lay.addWidget(self.list, 1)

        for entry in self.toc:
            indent = "　" * max(0, int(getattr(entry, "level", 0) or 0))
            pidx = int(getattr(entry, "paragraph_index", 0) or 0)
            it = QListWidgetItem(f"{indent}{getattr(entry, 'title', '')}")
            it.setData(Qt.UserRole, pidx)
            self.list.addItem(it)

        btns = QHBoxLayout()
        btns.addStretch(1)
        close = QPushButton("关闭")
        close.clicked.connect(self.reject)
        btns.addWidget(close)
        lay.addLayout(btns)

    def _on_activate(self, item: QListWidgetItem):
        pidx = int(item.data(Qt.UserRole) or 0)
        self.entry_selected.emit(pidx, pidx + 1)
        self.accept()
