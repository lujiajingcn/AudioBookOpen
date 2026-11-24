"""阅读页文本显示控件。

以列表形式逐行展示 ``LineItem``，支持：
  - 可配置字号
  - 高亮当前播放段（背景色变化）
  - 平滑滚动到指定行
  - 点击某行某字符 -> "从此处播放" 信号
  - 文本选中（收藏 / 查词）
"""
from __future__ import annotations

from typing import Dict, List, Optional

from PySide6.QtCore import Qt, Signal, QPropertyAnimation, QSize, QEasingCurve
from PySide6.QtGui import QColor, QFont, QTextLayout
from PySide6.QtWidgets import (
    QListWidget, QListWidgetItem, QWidget, QAbstractItemView,
)

from app.models.models import LineItem
from app.ui.theme import Theme

# 角色：保存行索引 / 段号
_ROLE_LINE_INDEX = Qt.UserRole + 1
_ROLE_SEGMENT = Qt.UserRole + 2


class LineListView(QListWidget):
    """逐行文本列表。

    信号:
        line_clicked(int, int)  # (行索引, 字符偏移)
        selection_changed_text(str)  # 选中的文本（用于收藏/查词）
    """

    line_clicked = Signal(int, int)
    selection_changed_text = Signal(str)

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.setObjectName("LineListView")
        self.setProperty("reader", True)
        self.setWordWrap(True)
        self.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.setMouseTracking(True)
        self.setUniformItemSizes(False)
        self.setFrameShape(QListWidget.NoFrame)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.setVerticalScrollMode(QListWidget.ScrollPerPixel)

        self._font_size = Theme.FONT_READER
        self._highlight_color = QColor(Theme.current().get("highlight", "#ffe082"))
        self._highlighted: List[int] = []
        self._scroll_anim: Optional[QPropertyAnimation] = None

        self._apply_font()
        self.itemSelectionChanged.connect(self._emit_selection)

    # ------------------------------------------------------------------
    # 字号
    # ------------------------------------------------------------------
    @property
    def current_font_size(self) -> int:
        return self._font_size

    def set_font_size(self, size: int):
        size = max(10, min(72, int(size)))
        self._font_size = size
        self._apply_font()

    def _apply_font(self):
        f = QFont("Microsoft YaHei UI", self._font_size)
        self.setFont(f)
        # 同步已存在条目的字体（触发重新计算 sizeHint）
        for i in range(self.count()):
            it = self.item(i)
            if it is not None:
                it.setFont(f)
                # 强制更新行高
                self._resize_item(it)

    # ------------------------------------------------------------------
    # 载入文本行
    # ------------------------------------------------------------------
    def set_lines(self, lines: List[LineItem], keep_scroll: bool = False):
        """用给定的行数据重建列表。"""
        vbar = self.verticalScrollBar()
        saved = vbar.value() if keep_scroll else 0

        self.setUpdatesEnabled(False)
        self.clear()
        self._highlighted.clear()
        f = QFont("Microsoft YaHei UI", self._font_size)
        for li in lines:
            it = QListWidgetItem(li.text)
            it.setFont(f)
            it.setData(_ROLE_LINE_INDEX, li.index)
            it.setData(_ROLE_SEGMENT,
                       li.segment_number if li.segment_number is not None else -1)
            # 保留可选中标志以便文本选择
            it.setFlags(it.flags() | Qt.ItemIsSelectable | Qt.ItemIsEnabled)
            self.addItem(it)
            self._resize_item(it)
        self.setUpdatesEnabled(True)

        vbar.setValue(saved)

    def _resize_item(self, it: QListWidgetItem):
        """依据换行后的文本高度调整行高。"""
        fm_width = self.viewport().width()
        if fm_width <= 0:
            fm_width = 800
        layout = QTextLayout(it.text(), it.font())
        layout.beginLayout()
        height = 0
        line_count = 0
        while True:
            line = layout.createLine()
            if not line.isValid():
                break
            line.setLineWidth(fm_width - 24)
            height += line.height() + 6
            line_count += 1
        layout.endLayout()
        it.setSizeHint(QSize(it.sizeHint().width(), max(24, height + 12)))

    def resizeEvent(self, event):
        super().resizeEvent(event)
        # 视口宽度变化时重新计算行高
        self.setUpdatesEnabled(False)
        for i in range(self.count()):
            it = self.item(i)
            if it is not None:
                self._resize_item(it)
        self.setUpdatesEnabled(True)

    # ------------------------------------------------------------------
    # 高亮当前播放段
    # ------------------------------------------------------------------
    def set_highlight_color(self, color: str):
        self._highlight_color = QColor(color)

    def highlight_segment(self, segment_number: int,
                          spans: Optional[Dict[int, tuple]] = None):
        """高亮属于 ``segment_number`` 的所有行。

        spans: 可选 {行index: (起始偏移, 结束偏移)}，用于后续逐字高亮扩展；
               当前实现按整行背景高亮。
        """
        # 清除旧高亮
        for idx in self._highlighted:
            if 0 <= idx < self.count():
                self.item(idx).setBackground(QColor(0, 0, 0, 0))
        self._highlighted.clear()

        spans = spans or {}
        new_idx: List[int] = []
        for i in range(self.count()):
            it = self.item(i)
            seg = it.data(_ROLE_SEGMENT)
            li = it.data(_ROLE_LINE_INDEX)
            matched = (seg == segment_number) or (li in spans)
            if matched:
                it.setBackground(self._highlight_color)
                new_idx.append(i)
        self._highlighted = new_idx
        if new_idx:
            self.scroll_to_line(new_idx[0])

    def clear_highlight(self):
        for idx in self._highlighted:
            if 0 <= idx < self.count():
                self.item(idx).setBackground(QColor(0, 0, 0, 0))
        self._highlighted.clear()

    # ------------------------------------------------------------------
    # 滚动
    # ------------------------------------------------------------------
    def scroll_to_line(self, index: int, smooth: bool = True):
        """滚动到第 index 行（列表内 row 与 line index 一致时使用）。"""
        if index < 0 or index >= self.count():
            return
        it = self.item(index)
        rect = self.visualItemRect(it)
        target = self.verticalScrollBar().value() + rect.top() - 20
        target = max(0, min(target, self.verticalScrollBar().maximum()))
        if not smooth:
            self.verticalScrollBar().setValue(int(target))
            return
        # 平滑动画
        if self._scroll_anim is not None:
            self._scroll_anim.stop()
        anim = QPropertyAnimation(self.verticalScrollBar(), b"value", self)
        anim.setDuration(220)
        anim.setStartValue(self.verticalScrollBar().value())
        anim.setEndValue(int(target))
        anim.setEasingCurve(QEasingCurve.OutCubic)
        self._scroll_anim = anim
        anim.start()

    def line_index_at_scroll(self, scroll_value: int) -> int:
        """给定垂直滚动值，返回**视口顶部可见**的行号（0 起）；没有可见行返回 -1。

        <p>用途：纯阅读（无音频）时，用户「读到哪」由视口首行决定 ——
        播放进度只在有音频时才有，纯阅读的书必须靠它记位置。
        用 item 几何**二分**找（而不是 ``itemAt``，后者点在空白处会返回 None）。
        """
        n = self.count()
        if n == 0:
            return -1
        value = max(0, int(scroll_value))
        # 二分：找最大的 i 使 item(i) 的 top <= value（该 item 即视口顶部所在行）
        lo, hi = 0, n - 1
        ans = 0
        while lo <= hi:
            mid = (lo + hi) // 2
            it = self.item(mid)
            if it is None:
                break
            if self.visualItemRect(it).top() <= value:
                ans = mid
                lo = mid + 1
            else:
                hi = mid - 1
        return ans

    def scroll_to_segment(self, segment_number: int):
        for i in range(self.count()):
            if self.item(i).data(_ROLE_SEGMENT) == segment_number:
                self.scroll_to_line(i)
                return

    # ------------------------------------------------------------------
    # 选中
    # ------------------------------------------------------------------
    def selected_text(self) -> str:
        texts = [it.text() for it in self.selectedItems()]
        return "\n".join(texts)

    def _emit_selection(self):
        t = self.selected_text()
        if t.strip():
            self.selection_changed_text.emit(t)

    # ------------------------------------------------------------------
    # 点击 -> 字符偏移
    # ------------------------------------------------------------------
    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            item = self.itemAt(event.pos())
            if item is not None:
                row = self.row(item)
                line_index = item.data(_ROLE_LINE_INDEX)
                offset = self._char_offset_at(item, event.pos())
                self.line_clicked.emit(
                    int(line_index) if line_index is not None else row, offset)
        super().mousePressEvent(event)

    def _char_offset_at(self, item: QListWidgetItem, pos) -> int:
        """把点击坐标近似换算为文本中的字符偏移。"""
        text = item.text()
        if not text:
            return 0
        rect = self.visualItemRect(item)
        # 文本起始位置（相对控件）
        local_x = pos.x() - rect.left() - 8  # 8px 内边距
        if local_x <= 0:
            return 0
        metrics = self.fontMetrics()
        # 逐字符累加宽度（简单近似；word wrap 时忽略换行位置）
        accumulated = 0
        for i, ch in enumerate(text):
            w = metrics.horizontalAdvance(ch)
            if accumulated + w > local_x:
                return i
            accumulated += w
        return len(text)
