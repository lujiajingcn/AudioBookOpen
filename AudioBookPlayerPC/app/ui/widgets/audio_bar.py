"""阅读页底部播放控制条。

包含：上一段 / 播放暂停 / 下一段 / 进度滑块 / 时间标签 / 倍速 / 音量 / 睡眠定时。
"""
from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QFrame, QHBoxLayout, QVBoxLayout, QWidget, QPushButton, QLabel,
    QSlider, QComboBox, QToolButton, QSizePolicy,
)

SPEEDS = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0]


def _fmt_time(ms: int) -> str:
    ms = max(0, int(ms))
    total_s = ms // 1000
    m, s = divmod(total_s, 60)
    return f"{m:02d}:{s:02d}"


class AudioBar(QFrame):
    """播放控制条。

    信号:
        play_clicked()
        pause_clicked()
        seek_to(int)              # 毫秒
        next_segment()
        prev_segment()
        speed_changed(float)
        volume_changed(int)       # 0-100
        sleep_clicked()
    """

    play_clicked = Signal()
    pause_clicked = Signal()
    seek_to = Signal(int)
    next_segment = Signal()
    prev_segment = Signal()
    speed_changed = Signal(float)
    volume_changed = Signal(int)
    sleep_clicked = Signal()

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.setProperty("audiobar", True)
        self.setObjectName("AudioBar")
        self.setMinimumHeight(64)

        self._playing = False
        self._total_ms = 0
        self._position_ms = 0
        self._seeking = False  # 用户拖动进度条中
        # 进度条单位：True = 「第 N 段 / 共 M 段」（听书队列），False = 毫秒（单文件）
        self._pos_in_segments = False

        outer = QHBoxLayout(self)
        outer.setContentsMargins(14, 8, 14, 8)
        outer.setSpacing(10)

        # --- 左侧控制按钮 ---
        self.prev_btn = QToolButton()
        self.prev_btn.setText("⏮")
        self.prev_btn.setToolTip("上一段")
        self.prev_btn.setAutoRaise(True)

        self.play_btn = QPushButton("▶")
        self.play_btn.setProperty("accent", True)
        self.play_btn.setFixedSize(44, 40)
        self.play_btn.setToolTip("播放 / 暂停")

        self.next_btn = QToolButton()
        self.next_btn.setText("⏭")
        self.next_btn.setToolTip("下一段")
        self.next_btn.setAutoRaise(True)

        for w in (self.prev_btn, self.play_btn, self.next_btn):
            outer.addWidget(w)

        # --- 时间 + 进度 ---
        self.pos_label = QLabel("00:00")
        # 68px：段模式要放得下「第 1298 段」这种 6~7 个字的文案（44px 会被省略号截断）
        self.pos_label.setFixedWidth(68)
        self.pos_label.setAlignment(Qt.AlignRight | Qt.AlignVCenter)

        self.slider = QSlider(Qt.Horizontal)
        self.slider.setRange(0, 0)
        self.slider.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.slider.setAccessibleName("播放进度")

        self.dur_label = QLabel("00:00")
        self.dur_label.setFixedWidth(68)
        self.dur_label.setAlignment(Qt.AlignLeft | Qt.AlignVCenter)

        outer.addWidget(self.pos_label)
        outer.addWidget(self.slider, 1)
        outer.addWidget(self.dur_label)

        # --- 倍速 ---
        speed_lbl = QLabel("倍速")
        speed_lbl.setProperty("secondary", True)
        self.speed_combo = QComboBox()
        for v in SPEEDS:
            self.speed_combo.addItem(f"{v:g}x", v)
        self.set_speed(1.0)
        outer.addWidget(speed_lbl)
        outer.addWidget(self.speed_combo)

        # --- 音量 ---
        vol_lbl = QLabel("🔊")
        self.volume_slider = QSlider(Qt.Horizontal)
        self.volume_slider.setRange(0, 100)
        self.volume_slider.setValue(80)
        self.volume_slider.setFixedWidth(90)
        outer.addWidget(vol_lbl)
        outer.addWidget(self.volume_slider)

        # --- 睡眠定时 ---
        self.sleep_btn = QToolButton()
        self.sleep_btn.setText("🌙")
        self.sleep_btn.setToolTip("睡眠定时")
        self.sleep_btn.setAutoRaise(True)
        self.sleep_btn.setCheckable(True)
        outer.addWidget(self.sleep_btn)

        # ---- 信号连接 ----
        self.play_btn.clicked.connect(self._on_play_toggle)
        self.prev_btn.clicked.connect(self.prev_segment.emit)
        self.next_btn.clicked.connect(self.next_segment.emit)
        self.slider.sliderPressed.connect(self._on_slider_pressed)
        self.slider.sliderMoved.connect(self._on_slider_moved)
        self.slider.sliderReleased.connect(self._on_slider_released)
        self.speed_combo.currentIndexChanged.connect(self._on_speed_changed)
        self.volume_slider.valueChanged.connect(self.volume_changed.emit)
        self.sleep_btn.clicked.connect(self.sleep_clicked.emit)

    # ------------------------------------------------------------------
    # 内部槽
    # ------------------------------------------------------------------
    def _on_play_toggle(self):
        # 先按当前状态发出对应信号，再翻转
        if self._playing:
            self.pause_clicked.emit()
        else:
            self.play_clicked.emit()

    def _on_slider_pressed(self):
        self._seeking = True

    def _on_slider_moved(self, value: int):
        # 拖动中的即时反馈要跟单位一致：段模式显示「第 N 段」，毫秒模式显示时间
        self.pos_label.setText(f"第 {int(value)} 段" if self._pos_in_segments
                               else _fmt_time(value))

    def _on_slider_released(self):
        self._seeking = False
        self.seek_to.emit(int(self.slider.value()))

    def _on_speed_changed(self, _idx: int):
        val = self.speed_combo.currentData()
        if val is not None:
            self.speed_changed.emit(float(val))

    # ------------------------------------------------------------------
    # 对外 API
    # ------------------------------------------------------------------
    def set_playing(self, playing: bool):
        """由播放器状态同步按钮显示。注意：本方法不发射信号。"""
        self._playing = playing
        self.play_btn.setText("⏸" if playing else "▶")
        self.play_btn.setToolTip("暂停" if playing else "播放")

    def set_progress(self, position_ms: int, total_ms: int = None):
        """更新播放进度（毫秒单位，单文件场景）。拖动中不覆盖滑块。"""
        self._pos_in_segments = False
        self._position_ms = int(position_ms)
        if total_ms is not None:
            self._total_ms = max(0, int(total_ms))
            self.slider.blockSignals(True)
            self.slider.setRange(0, self._total_ms)
            self.slider.blockSignals(False)
            self.dur_label.setText(_fmt_time(self._total_ms))
        if not self._seeking:
            self.slider.blockSignals(True)
            self.slider.setValue(self._position_ms)
            self.slider.blockSignals(False)
            self.pos_label.setText(_fmt_time(self._position_ms))

    def set_position(self, current: int, total: int):
        """按**段**显示进度：「第 N 段 / 共 M 段」。

        为什么不能复用 :meth:`set_progress`：那里的单位是毫秒，而阅读页的播放单位
        是「一段一句话/一小节」。用毫秒语义硬套，标签会显示成 ``01:59`` 这种假时间
        （119 段 = 1 分 59 秒），用户根本对不上正文位置。

        配套约定：段模式下 ``seek_to`` 发出的是**段号**（不是毫秒），
        阅读页据此 ``_play_from(段号)``。
        """
        self._pos_in_segments = True
        cur = int(current or 0)
        tot = int(total or 0)
        self.slider.blockSignals(True)
        if tot <= 0:
            # 还没加载音频：别显示「第 0 段 / 共 1 段」这种假数据
            self.slider.setRange(0, 0)
            self.slider.setValue(0)
            self.slider.blockSignals(False)
            self.pos_label.setText("--")
            self.dur_label.setText("--")
            return
        self.slider.setRange(0, tot)
        if not self._seeking:
            self.slider.setValue(max(0, min(cur, tot)))
        self.slider.blockSignals(False)
        self.pos_label.setText(f"第 {max(0, cur)} 段")
        self.dur_label.setText(f"共 {tot} 段")

    def set_speed(self, speed: float):
        """程序化设置倍速（不触发 speed_changed）。"""
        for i in range(self.speed_combo.count()):
            if abs(float(self.speed_combo.itemData(i)) - speed) < 1e-3:
                self.speed_combo.blockSignals(True)
                self.speed_combo.setCurrentIndex(i)
                self.speed_combo.blockSignals(False)
                return

    def set_volume(self, value: int):
        self.volume_slider.blockSignals(True)
        self.volume_slider.setValue(max(0, min(100, int(value))))
        self.volume_slider.blockSignals(False)

    def set_sleep_active(self, active: bool):
        self.sleep_btn.setChecked(bool(active))

    def reset(self):
        """切换书籍时复位。"""
        self._playing = False
        self.play_btn.setText("▶")
        self.set_progress(0, 0)
