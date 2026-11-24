"""合成任务状态行控件。

显示书名、状态图标、进度条（已完成/总段数）、暂停/继续、重试按钮。
"""
from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QFrame, QHBoxLayout, QVBoxLayout, QWidget, QLabel, QProgressBar, QPushButton,
)

from app.models.models import Task, TaskStatus

# 状态 -> (图标, 文案, 颜色token)
_STATUS_META = {
    TaskStatus.PENDING.value: ("⏳", "等待中", "text_secondary"),
    TaskStatus.QUEUED.value: ("📥", "排队中", "text_secondary"),
    TaskStatus.PROCESSING.value: ("🎙", "合成中", "accent"),
    TaskStatus.COMPLETED.value: ("✅", "已完成", "success"),
    TaskStatus.PARTIAL.value: ("⚠", "部分完成", "warning"),
    TaskStatus.FAILED.value: ("❌", "失败", "error"),
    TaskStatus.PAUSED.value: ("⏸", "已暂停", "warning"),
}


class TaskRowWidget(QFrame):
    """任务状态行。

    信号（均携带 task_id）:
        pause_clicked(int)
        resume_clicked(int)
        retry_clicked(int)
        clicked(int)
    """

    pause_clicked = Signal(int)
    resume_clicked = Signal(int)
    retry_clicked = Signal(int)
    clicked = Signal(int)

    def __init__(self, task: Task, parent: QWidget | None = None):
        super().__init__(parent)
        self._task = task
        self.setProperty("card", True)
        self.setMinimumHeight(72)
        self.setCursor(Qt.PointingHandCursor)

        root = QHBoxLayout(self)
        root.setContentsMargins(14, 10, 14, 10)
        root.setSpacing(12)

        # 状态图标
        self.icon_lbl = QLabel()
        self.icon_lbl.setFixedWidth(30)
        self.icon_lbl.setAlignment(Qt.AlignCenter)
        f = self.icon_lbl.font()
        f.setPointSize(18)
        self.icon_lbl.setFont(f)
        root.addWidget(self.icon_lbl)

        # 中部：书名 + 状态 + 进度
        mid = QVBoxLayout()
        mid.setSpacing(4)

        title_row = QHBoxLayout()
        title_row.setSpacing(8)
        self.name_lbl = QLabel(task.book_name or "(未知书籍)")
        self.name_lbl.setProperty("role", "title")
        self.status_lbl = QLabel()
        self.status_lbl.setProperty("secondary", True)
        self.status_lbl.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        title_row.addWidget(self.name_lbl, 1)
        title_row.addWidget(self.status_lbl)
        mid.addLayout(title_row)

        prog_row = QHBoxLayout()
        prog_row.setSpacing(8)
        self.progress = QProgressBar()
        self.progress.setProperty("role", "segment")
        self.progress.setTextVisible(False)
        self.progress.setRange(0, max(1, task.total_segments))
        self.progress.setValue(task.completed_segments)
        self.progress_label = QLabel()
        self.progress_label.setProperty("secondary", True)
        self.progress_label.setFixedWidth(90)
        self.progress_label.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        prog_row.addWidget(self.progress, 1)
        prog_row.addWidget(self.progress_label)
        mid.addLayout(prog_row)

        root.addLayout(mid, 1)

        # 右侧操作按钮
        btn_col = QVBoxLayout()
        btn_col.setSpacing(4)
        self.toggle_btn = QPushButton("暂停")
        self.retry_btn = QPushButton("重试")
        self.retry_btn.setVisible(False)
        btn_col.addWidget(self.toggle_btn)
        btn_col.addWidget(self.retry_btn)
        root.addLayout(btn_col)

        # 信号
        self.toggle_btn.clicked.connect(self._on_toggle)
        self.retry_btn.clicked.connect(lambda: self.retry_clicked.emit(self._task.id))

        self._refresh()

    # ------------------------------------------------------------------
    @property
    def task_id(self) -> int:
        return self._task.id

    def update_task(self, task: Task):
        self._task = task
        self._refresh()

    def set_progress(self, completed: int, total: int, status: str = None):
        self._task.completed_segments = completed
        self._task.total_segments = total
        if status:
            self._task.status = status
        self._refresh()

    # ------------------------------------------------------------------
    def _on_toggle(self):
        if self._task.status == TaskStatus.PAUSED.value:
            self.resume_clicked.emit(self._task.id)
        else:
            self.pause_clicked.emit(self._task.id)

    def _refresh(self):
        t = self._task
        icon, text, color_token = _STATUS_META.get(
            t.status, ("❔", t.status or "未知", "text_secondary"))
        self.icon_lbl.setText(icon)
        self.status_lbl.setText(text)

        # 状态着色
        c = Theme_current_colors()
        color = c.get(color_token, c.get("text", "#333"))
        self.status_lbl.setStyleSheet(f"color: {color};")

        total = max(1, t.total_segments)
        self.progress.setRange(0, total)
        self.progress.setValue(t.completed_segments)
        self.progress_label.setText(t.progress_text or f"{t.completed_segments}/{total}")

        # 按钮可见性
        terminal = t.status in (TaskStatus.COMPLETED.value,)
        failed = t.status == TaskStatus.FAILED.value
        self.retry_btn.setVisible(failed)
        if failed:
            self.toggle_btn.setVisible(False)
        elif terminal:
            self.toggle_btn.setVisible(False)
        elif t.status == TaskStatus.PAUSED.value:
            self.toggle_btn.setVisible(True)
            self.toggle_btn.setText("继续")
        else:
            self.toggle_btn.setVisible(True)
            self.toggle_btn.setText("暂停")


def Theme_current_colors():
    """安全读取当前主题配色（即便 theme 尚未 apply）。"""
    try:
        from app.ui.theme import Theme
        return Theme.current()
    except Exception:
        return {
            "accent": "#1976d2", "success": "#2e7d32", "warning": "#ef6c00",
            "error": "#c62828", "text": "#1f2430", "text_secondary": "#6b7280",
        }
