"""书内搜索弹窗：输入关键词，列出命中行，点击跳转到该处。"""
from __future__ import annotations

from typing import List, Optional

from PySide6.QtCore import Signal, Qt
from PySide6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QLineEdit, QListWidget, QListWidgetItem,
    QLabel, QPushButton,
)

from app.models.models import LineItem


class SearchDialog(QDialog):
    """在给定正文行中搜索关键词。

    信号:
        jumped(line_index, segment_number) —— 点击结果跳转。
    """

    jumped = Signal(int, int)

    _MAX_RESULTS = 500

    def __init__(self, lines: List[LineItem], parent: Optional[QDialog] = None):
        super().__init__(parent)
        self._lines = list(lines or [])
        self.setWindowTitle("书内搜索")
        self.setMinimumSize(420, 520)

        lay = QVBoxLayout(self)
        top = QHBoxLayout()
        self.edit = QLineEdit(self)
        self.edit.setPlaceholderText("输入关键词，回车搜索…")
        self.edit.textChanged.connect(self._do_search)
        self.edit.returnPressed.connect(self._do_search)
        top.addWidget(self.edit)
        lay.addLayout(top)

        self.count_label = QLabel("请输入关键词")
        self.count_label.setStyleSheet("color:#888;")
        lay.addWidget(self.count_label)

        self.list = QListWidget(self)
        self.list.itemActivated.connect(self._on_pick)
        self.list.itemClicked.connect(self._on_pick)
        lay.addWidget(self.list, 1)

        btns = QHBoxLayout()
        btns.addStretch(1)
        close = QPushButton("关闭")
        close.clicked.connect(self.reject)
        btns.addWidget(close)
        lay.addLayout(btns)

    def _do_search(self):
        kw = self.edit.text().strip().lower()
        self.list.clear()
        if not kw:
            self.count_label.setText("请输入关键词")
            return
        hits = 0
        for ln in self._lines:
            if kw in (ln.text or "").lower():
                seg = ln.segment_number or 0
                it = QListWidgetItem(f"[{ln.index}] {ln.text}")
                it.setData(Qt.UserRole, ln.index)
                it.setData(Qt.UserRole + 1, seg)
                self.list.addItem(it)
                hits += 1
                if hits >= self._MAX_RESULTS:
                    break
        self.count_label.setText(f"命中 {hits} 条" + ("（已达上限）" if hits >= self._MAX_RESULTS else ""))

    def _on_pick(self, item: QListWidgetItem):
        idx = int(item.data(Qt.UserRole) or 0)
        seg = int(item.data(Qt.UserRole + 1) or 0)
        self.jumped.emit(idx, seg)
        self.accept()
