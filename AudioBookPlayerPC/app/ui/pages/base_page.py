"""页面基类与公共工具。

职责：
  - 提供后台线程执行 + 主线程回调（run_async）的通用封装；
  - 提供对「并行开发的 widgets / services」的柔性引用（try-import + 安全方法调用），
    使得即便对方接口尚未就绪，页面也能被构造、编译、并在缺失能力处优雅降级；
  - 统一样式（夜览/日览）辅助方法。

约定：MainWindow 负责把所有 service 通过构造函数注入到各页面；页面不应自行 new service。
"""
from __future__ import annotations

import threading
from typing import Any, Callable, Optional

from PySide6.QtCore import Qt, QObject, Signal, QPoint
from PySide6.QtGui import QFont
from PySide6.QtWidgets import QWidget, QLabel, QVBoxLayout, QListWidget, QTextEdit

from app.core.config import AppConfig
from app.core.api_client import ApiClient, ApiException
from app.core.auth import AuthManager
from app.db.database import LocalDatabase
from app.models.models import LineItem, Segment


# ============================================================================
# 对并行 widgets / services 的柔性引用
# ----------------------------------------------------------------------------
# 这些模块由其它同事并行编写，可能在页面编写时尚不存在。try-import 失败时
# 回落到本地占位实现（仅供联调前让界面可运行）；真实模块一旦就绪，优先使用真实的。
# ============================================================================

try:  # pragma: no cover - 依赖并行模块
    from app.ui.widgets import LineListView  # type: ignore
except Exception:  # noqa: BLE001
    LineListView = None  # 使用 _FallbackLineListView

try:  # pragma: no cover
    from app.ui.widgets import AudioBar  # type: ignore
except Exception:  # noqa: BLE001
    AudioBar = None

try:  # pragma: no cover
    from app.ui.widgets import TaskRowWidget  # type: ignore
except Exception:  # noqa: BLE001
    TaskRowWidget = None

try:  # pragma: no cover
    from app.ui.widgets import BookCard  # type: ignore
except Exception:  # noqa: BLE001
    BookCard = None

try:  # pragma: no cover
    from app.ui.widgets import NavigationBar  # type: ignore
except Exception:  # noqa: BLE001
    NavigationBar = None


# ---------- 占位实现（真实 widgets 缺失时兜底） ----------

class _FallbackLineListView(QTextEdit):
    """占位正文视图：只读富文本，支持 set_lines / highlight_segment / scroll / 字号。

    真实 LineListView 由 widgets 同事提供，接口子集一致（见下）。
    """

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        self.setReadOnly(True)
        self._lines: list[LineItem] = []
        self._font_pt = 18
        self._night = False
        self._active_segment: Optional[int] = None
        self.setLineWrapMode(QTextEdit.LineWrapMode.WidgetWidth)
        self._render()

    # -- 契约方法（真实 widget 应实现同名方法） --
    def set_lines(self, lines: list[LineItem]) -> None:
        self._lines = list(lines)
        self._render()

    def set_font_size(self, pt: int) -> None:
        self._font_pt = max(10, int(pt))
        self._render()

    def set_night_mode(self, on: bool) -> None:
        self._night = bool(on)
        self._render()

    def highlight_segment(self, segment_number: Optional[int]) -> None:
        self._active_segment = segment_number
        self._render()

    def scroll_to_line(self, line_index: int) -> None:
        for i, ln in enumerate(self._lines):
            if ln.index == line_index or i == line_index:
                cursor = self.document().find(ln.text[:12] or " ")
                if not cursor.isNull():
                    self.setTextCursor(cursor)
                break

    # -- 内部 --
    def _render(self) -> None:
        self.setHtml(self._html())
        f = QFont("Microsoft YaHei", self._font_pt)
        self.setFont(f)

    def _html(self) -> str:
        bg, fg = ("#1e1e1e", "#d4d4d4") if self._night else ("#fbf7ee", "#2b2b2b")
        hl = "#3a3d00" if self._night else "#fff3b0"
        out = [f'<div style="font-family:\'Microsoft YaHei\';color:{fg};">']
        for ln in self._lines:
            active = ln.segment_number is not None and ln.segment_number == self._active_segment
            style = (f'padding:2px 8px;background:{hl};border-radius:4px;' if active
                     else 'padding:2px 8px;')
            text = (ln.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\n", "<br>")) or "&nbsp;"
            seg = ln.segment_number if ln.segment_number is not None else 0
            out.append(f'<p data-seg="{seg}" style="{style}">{text}</p>')
        out.append("</div>")
        return "".join(out)


class _FallbackAudioBar(QWidget):
    """占位播放条：播放/暂停、上/下一段、段号显示。"""

    play_pause_clicked = Signal(bool)   # True=请求播放
    prev_clicked = Signal()
    next_clicked = Signal()
    seek_segment = Signal(int)          # 段号（1-based）

    def __init__(self, parent: Optional[QWidget] = None):
        super().__init__(parent)
        from PySide6.QtWidgets import QHBoxLayout, QPushButton, QSpinBox
        lay = QHBoxLayout(self)
        self.btn_prev = QPushButton("⏮")
        self.btn_play = QPushButton("▶")
        self.btn_next = QPushButton("⏭")
        self.spin = QSpinBox()
        self.spin.setMinimum(1)
        self.lbl = QLabel("未加载音频")
        for w in (self.btn_prev, self.btn_play, self.btn_next, self.spin, self.lbl):
            lay.addWidget(w)
        self.btn_play.clicked.connect(lambda: self.play_pause_clicked.emit(True))
        self.btn_prev.clicked.connect(self.prev_clicked.emit)
        self.btn_next.clicked.connect(self.next_clicked.emit)
        self.spin.editingFinished.connect(lambda: self.seek_segment.emit(self.spin.value()))

    def set_playing(self, playing: bool) -> None:
        self.btn_play.setText("⏸" if playing else "▶")

    def set_position(self, current: int, total: int) -> None:
        total = int(total or 0)
        if total <= 0:
            # 还没加载音频：别显示「第 0/0 段」
            self.spin.setMaximum(1)
            self.lbl.setText("未加载音频")
            return
        self.spin.setMaximum(max(1, total))
        if current:
            self.spin.setValue(current)
        self.lbl.setText(f"第 {current}/{total} 段")


# ============================================================================
# 通用异步执行：后台线程 -> 主线程回调（通过 Qt 信号跨线程编组）
# ============================================================================

class _AsyncBridge(QObject):
    result = Signal(object)
    error = Signal(str)


class BasePage(QWidget):
    """所有页面的基类：注入 config/api/auth/db，提供异步与安全调用工具。"""

    def __init__(self, config: AppConfig, api: ApiClient,
                 auth: AuthManager, db: LocalDatabase,
                 parent: Optional[QWidget] = None, **services: Any):
        super().__init__(parent)
        self.config = config
        self.api = api
        self.auth = auth
        self.db = db
        # services: playback / online_tts / local_tts / book_service / dictionary 等，
        # 由 MainWindow 注入，可能为 None（并行开发）。
        self.playback = services.get("playback")
        self.online_tts = services.get("online_tts")
        self.local_tts = services.get("local_tts")
        self.book_service = services.get("book_service")
        self.dictionary = services.get("dictionary")
        self.sync = services.get("sync")
        self.store_audio = services.get("store_audio")
        # 私有云端任务的音频按需下载器（本机导入的书在云端合成后走 /api/tts/**）
        self.task_audio = services.get("task_audio")
        self.extra_services = services
        # 防止 QThread/bridge 被 GC
        self._bridges: set[_AsyncBridge] = set()
        # 认证变化联动（子类可覆盖 auth_changed）
        try:
            self.auth.on_auth_changed(self._on_auth_changed)
        except Exception:  # noqa: BLE001
            pass

    # ---------- 认证联动 ----------

    def _on_auth_changed(self, is_auth: bool, username: Optional[str]):
        try:
            self.auth_changed(is_auth, username)
        except Exception:  # noqa: BLE001
            pass

    def auth_changed(self, is_auth: bool, username: Optional[str]):
        """子类覆盖：登录状态变化时刷新 UI。"""

    # ---------- 异步 ----------

    @staticmethod
    def _emit_bridge(bridge: _AsyncBridge, signal, payload: Any) -> None:
        """往 bridge 发信号；**页面已被析构时静默丢弃**（不抛、不崩进程）。

        页面析构 ⇒ bridge 跟着被 C++ 析构 ⇒ 此时再 emit 会抛
        ``RuntimeError: Internal C++ object already deleted``。
        这个 RuntimeError 必须在这里吞掉：它发生在**后台线程**里，
        抛出只会打印一串 traceback（噪音），但如果让它冒到
        ``_run`` 的 ``except Exception`` 里再 emit 一次 error 信号，
        就会变成「对已析构对象二次 emit」⇒ 有可能把进程带崩。
        """
        try:
            signal.emit(payload)
        except RuntimeError:
            pass

    def run_async(self, fn: Callable[[], Any],
                  on_result: Optional[Callable[[Any], None]] = None,
                  on_error: Optional[Callable[[str], None]] = None) -> threading.Thread:
        """在后台线程执行 fn()，完成后在主线程回调 on_result/on_error。

        ⚠️ **bridge 必须挂 parent=self**（2026-11-07 实测踩坑，重要）：
            没有 parent 时 bridge 只被 Python 侧的 ``self._bridges`` 集合保活，
            页面对象被 GC 析构后 **后台线程仍然会 emit** ⇒ 回调（lambda 里
            捕获了已析构的页面）打到野指针上 ⇒ **access violation 段错误**。
            实测崩在 ``tests/test_word_book_gui.py`` 的 ``_pump`` 抽水时
            （faulthandler 栈：``test_word_book_gui.py:119 _pump``）。
            真实应用里同理：**关掉页面 / 退出程序时**，在途的后台线程回调
            会访问已销毁的页面 ⇒ 潜在崩溃。
            parent=self ⇒ 页面析构时 bridge 及其信号连接一起销毁，
            Qt 自动断开；线程再 emit 只会被上面 ``_emit_bridge`` 吞掉。
        """
        bridge = _AsyncBridge(self)
        self._bridges.add(bridge)

        def _cleanup(*_a):
            self._bridges.discard(bridge)

        if on_result:
            bridge.result.connect(on_result)
            bridge.result.connect(_cleanup)
        else:
            bridge.result.connect(_cleanup)
        if on_error:
            bridge.error.connect(on_error)
            bridge.error.connect(_cleanup)
        else:
            bridge.error.connect(_cleanup)

        def _run():
            try:
                payload = fn()
            except ApiException as e:
                self._emit_bridge(bridge, bridge.error,
                                  getattr(e, "message", None) or str(e))
                return
            except Exception as e:  # noqa: BLE001
                self._emit_bridge(bridge, bridge.error, str(e))
                return
            self._emit_bridge(bridge, bridge.result, payload)

        t = threading.Thread(target=_run, daemon=True)
        t.start()
        return t

    # ---------- 柔性调用（真实 service/widget 方法名不确定时） ----------

    @staticmethod
    def call(obj: Any, method: str, *args, default: Any = None, **kwargs) -> Any:
        """若 obj 存在可调用 method 则调用，否则返回 default，绝不抛异常。"""
        f = getattr(obj, method, None)
        if callable(f):
            try:
                return f(*args, **kwargs)
            except Exception:  # noqa: BLE001
                return default
        return default

    @staticmethod
    def try_connect(obj: Any, signal_name: str, handler: Callable) -> bool:
        """若 obj 上有名为 signal_name 的信号则连接，返回是否连接成功。"""
        sig = getattr(obj, signal_name, None)
        if sig is not None and hasattr(sig, "connect"):
            try:
                sig.connect(handler)
                return True
            except Exception:  # noqa: BLE001
                return False
        return False

    # ---------- 工具 ----------

    def make_line_view(self, parent: Optional[QWidget] = None) -> QWidget:
        """构造正文视图：优先真实 LineListView，缺失则用占位实现。"""
        if LineListView is not None:
            try:
                return LineListView(parent)
            except Exception:  # noqa: BLE001
                pass
        return _FallbackLineListView(parent)

    def make_audio_bar(self, parent: Optional[QWidget] = None) -> QWidget:
        if AudioBar is not None:
            try:
                return AudioBar(parent)
            except Exception:  # noqa: BLE001
                pass
        return _FallbackAudioBar(parent)

    def require_login(self) -> bool:
        """未登录时提示并返回 False。真正的登录跳转交给 MainWindow（通过信号）。"""
        return bool(self.config.is_authenticated)

    def log(self, msg: str) -> None:
        print(f"[{self.__class__.__name__}] {msg}")
