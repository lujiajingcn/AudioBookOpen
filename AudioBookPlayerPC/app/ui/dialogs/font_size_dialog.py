"""字号调整弹窗：滑块 + 预览文本，实时发出 size_changed。"""
from __future__ import annotations

from typing import Optional

from PySide6.QtCore import Signal, Qt
from PySide6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QSlider, QLabel, QTextEdit,
    QPushButton, QSizePolicy,
)


class FontSizeDialog(QDialog):
    """字号调节。

    信号:
        size_changed(int) —— 滑块拖动实时发出，供页面即时预览正文。
    """

    size_changed = Signal(int)

    _PREVIEW = "腹有诗书气自华。The quick brown fox jumps over the lazy dog. 0123456789"

    def __init__(self, current: int = 18, night: bool = False,
                 parent: Optional[QDialog] = None):
        super().__init__(parent)
        self._night = night
        self.setWindowTitle("字号设置")
        self.setMinimumWidth(460)

        lay = QVBoxLayout(self)

        head = QHBoxLayout()
        self.min_lbl = QLabel("小")
        self.size_lbl = QLabel(f"{current} pt")
        self.size_lbl.setMinimumWidth(60)
        self.size_lbl.setAlignment(self.size_lbl.alignment())
        head.addWidget(self.min_lbl)
        self.slider = QSlider(Qt.Horizontal)
        self.slider.setMinimum(12)
        self.slider.setMaximum(40)
        self.slider.setValue(max(12, min(40, int(current))))
        self.slider.valueChanged.connect(self._on_value)
        head.addWidget(self.slider, 1)
        head.addWidget(QLabel("大"))
        head.addWidget(self.size_lbl)
        lay.addLayout(head)

        self.preview = QTextEdit(self)
        self.preview.setReadOnly(True)
        self.preview.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.preview.setMaximumHeight(140)
        lay.addWidget(self.preview)

        btns = QHBoxLayout()
        btns.addStretch(1)
        ok = QPushButton("确定")
        ok.clicked.connect(self.accept)
        btns.addWidget(ok)
        lay.addLayout(btns)

        self._render(self.slider.value())

    def current_size(self) -> int:
        return self.slider.value()

    def _on_value(self, v: int):
        self.size_lbl.setText(f"{v} pt")
        self._render(v)
        self.size_changed.emit(v)

    def _render(self, pt: int):
        bg, fg = ("#1e1e1e", "#d4d4d4") if self._night else ("#ffffff", "#222222")
        self.preview.setHtml(
            f'<div style="font-family:\'Microsoft YaHei\';font-size:{pt}pt;'
            f'color:{fg};background:{bg};padding:8px;">{self._PREVIEW}</div>'
        )
