"""阅读器页：本地图书 / 书城书的正文显示、播放同步、目录、搜索、收藏、词典、进度。

这是最核心的页面。数据来源分两条：
  - 本地书：解析文件正文（txt/md 直接读；epub/pdf 委托 book_service / app.parser），
    若关联了已完成的合成任务，则用任务的音频段做「边读边播」。
  - 城书：api.get_store_book_content 取正文行 + get_store_book_segments 取音频段。

对并行的 widgets / services（LineListView、AudioBar、AudioEngine、Dictionary、BookService）
全部通过安全调用连接；任一缺失时页面仍可读文本、保存进度，并在缺失能力处给出提示。
"""
from __future__ import annotations

import os
import time
from pathlib import Path
from typing import List, Optional

from PySide6.QtCore import Qt, Signal, QPoint, QTimer, QObject
from PySide6.QtGui import QTextCursor, QAction
from PySide6.QtWidgets import (
    QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QWidget, QToolBar,
    QMenu, QMessageBox, QInputDialog, QTextEdit, QDialog, QLineEdit,
    QPlainTextEdit,
)

from app.models.models import (
    Book, LineItem, Segment, TocEntry, Favorite, Task, WordEntry
)
from app.core import play_queue

from .base_page import BasePage
from ..dialogs.toc_dialog import TocDialog
from ..dialogs.search_dialog import SearchDialog
from ..dialogs.font_size_dialog import FontSizeDialog


# 收藏出处：wire camelCase → WordEntry 本地字段名（与服务端 / Android / Web 契约一致）。
# reader_page 内部统一用 wire 形态的 dict 传递：既能直接进 api.add_word_book 的 payload，
# 也能按这张表落到 WordEntry 的本地列，避免两套命名各自漂移。
_WORD_SOURCE_WIRE_TO_FIELD = {
    "bookName": "book_name",
    "bookKey": "book_key",
    "storeBookId": "store_book_id",
    "segmentNumber": "segment_number",
    "lineNumber": "line_number",
    "sentenceText": "sentence_text",
}


class _PlaybackBridge(QObject):
    """把 AudioEngine 后台线程的回调安全地编组回 GUI 线程。

    AudioEngine 是普通 Python 类（非 QObject），它的 on_segment_start /
    on_segment_end / on_all_complete 回调在**播放监控线程**里触发；直接在这些
    回调里操作 Qt 控件是线程不安全的。这里用一个驻留在主线程的 QObject 发信号，
    Qt 的自动连接会在跨线程时改为「队列连接」，把槽函数投递回主线程执行。
    参数均为 AudioEngine 播放列表里的**文件下标**。
    """

    seg_start = Signal(int)
    seg_end = Signal(int)
    all_done = Signal()


class _DictionaryDialog(QDialog):
    """迷你词典查词框：输入单词 → DictionaryManager.lookup → 展示音标+释义。"""

    def __init__(self, dictionary, parent=None):
        super().__init__(parent)
        self._dict = dictionary
        self.setWindowTitle("词典查词")
        self.setMinimumSize(380, 300)
        lay = QVBoxLayout(self)

        row = QHBoxLayout()
        self.edit = QLineEdit()
        self.edit.setPlaceholderText("输入单词后回车查询…")
        self.edit.returnPressed.connect(self._query)
        btn = QPushButton("查询")
        btn.clicked.connect(self._query)
        row.addWidget(self.edit, 1)
        row.addWidget(btn)
        lay.addLayout(row)

        self.out = QPlainTextEdit()
        self.out.setReadOnly(True)
        lay.addWidget(self.out, 1)

        try:
            ready = bool(self._dict is not None and self.call(self._dict, "is_ready"))
        except Exception:  # noqa: BLE001
            ready = False
        if not ready:
            self.out.setPlainText(
                "词典尚未就绪（未安装本地词库）。\n"
                "请在「我的 - 离线词典」下载安装后再查词。")

    @staticmethod
    def call(obj, method, *a, default=None, **kw):
        f = getattr(obj, method, None)
        if callable(f):
            try:
                return f(*a, **kw)
            except Exception:  # noqa: BLE001
                return default
        return default

    def _query(self):
        word = self.edit.text().strip()
        if not word:
            return
        res = self.call(self._dict, "lookup", word, default=None)
        if not res:
            self.out.setPlainText(f"未收录：{word}")
            return
        if isinstance(res, dict):
            w = res.get("word", word)
            ph = res.get("phonetic", "")
            dfn = res.get("definition", "")
        else:
            w = getattr(res, "word", word)
            ph = getattr(res, "phonetic", "")
            dfn = getattr(res, "definition", str(res))
        head = f"{w}"
        if ph:
            head += f"  [{ph}]"
        self.out.setPlainText(head + "\n\n" + (dfn or "（无释义）"))


class ReaderPage(BasePage):
    """阅读器。

    离开方式：点左侧导航栏任意一项（顶部工具栏**没有**「返回」按钮）。
    离开时的收尾（存进度 / 停表 / 停播 / 解探针）由 hideEvent 兜底。

    信号:
        synthesize_requested(int book_id)  —— 无音频时请求去合成
    """

    synthesize_requested = Signal(int)
    # 云端音频：后台线程落段后跨线程投递（key = store_book_id 或 server_task_id, segment_number）
    _cloud_seg_saved = Signal(int, int)

    # 云端音频滑动窗口预取深度。**为什么不能只预取 5 段**：
    # 实测单段音频只有 2~3 s，而单段下载（含 1.5~3 s 的请求 TTFB）要 2~7 s；
    # 窗口太浅 ⇒ 播到队尾时下一段还没下完，监控线程只能空等 ⇒「不能连播」。
    # 24 段 ≈ 1 MB、按 4 路并发约几秒拉完，足够覆盖播放消耗。
    _CLOUD_PREFETCH = 24

    # 同一段下载失败后的重试冷却（秒）。太短会把服务端打成筛子，太长会让「卡一下」
    # 变成「卡很久」；5 s 与实测单段 TTFB(1.5~3 s) 同量级。
    _ENSURE_RETRY_SEC = 5.0
    # 同一段最多重试次数（含首次）：约 4×5s=20s 后放弃并如实报错，
    # 而不是让界面永远停在「正在下载第 N 段…」。
    _ENSURE_MAX_TRIES = 4

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)

        # ---- 状态 ----
        self.book: Optional[Book] = None
        self.book_id: int = 0
        self.store_book_id: int = 0
        self._store_title: str = ""       # 书城书真实标题（收藏身份 bookName / 标题显示用）
        self.task: Optional[Task] = None
        self.lines: List[LineItem] = []
        self.segments: List[Segment] = []
        self.toc: List[TocEntry] = []
        self.current_segment: int = 0
        self._offset_ms: int = 0
        self._book_key: str = ""
        self._seg_first_line: dict = {}       # segment_number -> first line index
        self._line_index_to_seg: dict = {}    # line index -> segment_number
        self._audio_by_seg: dict = {}         # segment_number -> local audio path
        self._play_files: List[str] = []      # 有序：**从起播段起连续**的本地文件路径
        self._play_segnums: List[int] = []    # 有序：与 _play_files 一一对应的段号
        self._playback_attached = False
        self._cloud_prepare_active = False    # 云端音频首次准备（首段下载）是否进行中
        self._cloud_prepare_seg = 0           # 正在准备的那一段（避免重复点击重排）
        self._want_play = False               # 用户意图：正在播/缓冲中（区别于引擎瞬时状态）
        self._autoplay = False                # 本次打开是否要自动起播（书架「播放」进入）
        self._buffering_seg = 0               # 正在等下载的那一段（0 = 不在缓冲）
        # 「下一段」下载重试节流：(段号, 上次发起时刻, 已尝试次数)。轮询 250ms 一次，
        # 下载失败后若不节流会每 250ms 起一个线程重下同一段 ⇒ 线程风暴 + 服务端被刷。
        self._ensure_try = (0, 0.0, 0)
        self._status_seg = 0                 # 状态栏已显示的段号（避免每 500ms 重绘）
        # 打开请求的序号：每次 open_book 自增。异步加载回来时用它判断
        # 「这份结果是不是当前这本书的」—— 慢的旧请求晚回来时必须丢弃，
        # 否则会出现「先点 A 再点 B，A 的正文把 B 的正文盖掉」。
        self._load_seq = 0
        # 单词本「跳转到原书」：本次加载完成后要应用的收藏出处（wire 6 键），
        # 以及「本次为跳转打开」标志（用于跳过 _restore_progress 的进度恢复抢占）。
        self._pending_jump = None
        self._skip_progress_restore = False
        # 纯阅读（无音频）时的「读到哪一行」：由滚动条可见首行推导。
        # 有音频时位置由播放段决定，这条只用来在没有音频时兜底。
        self._scroll_line_index: int = -1
        self._scroll_save_timer: Optional[QTimer] = None

        # ---- 播放回调桥 + 位置轮询定时器 ----
        self._pb_bridge = _PlaybackBridge(self)
        self._pb_timer = QTimer(self)
        self._pb_timer.setInterval(250)

        # ---- 系统朗读（TTS）完成轮询 ----
        self._tts_timer = QTimer(self)
        self._tts_timer.setInterval(400)
        self._tts_timer.timeout.connect(self._on_tts_tick)
        self._tts_restored_status: str = ""

        self._build_ui()
        self._wire_playback_bridge()

    def _wire_playback_bridge(self):
        b = self._pb_bridge
        b.seg_start.connect(self._on_pb_file_index)
        b.seg_end.connect(self._on_pb_file_index_end)
        b.all_done.connect(self._on_pb_all_done)
        self._pb_timer.timeout.connect(self._poll_position)
        self._cloud_seg_saved.connect(self._on_cloud_segment_saved_ui)

    # ==================================================================
    # UI
    # ==================================================================

    def _build_ui(self):
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        # ---- 顶部工具栏 ----
        # ⚠ 这里原来有一个「← 返回」按钮，已按需求移除。
        # 离开阅读器改为「点左侧导航栏任意一项」（MainWindow 负责切页），
        # 因此阅读器被隐藏时必须自己收尾（存进度 / 停表 / 停播）——见 hideEvent。
        bar = QToolBar(self)
        bar.setMovable(False)

        self._title = QLabel("阅读器")
        self._title.setStyleSheet("margin-left:12px;font-weight:bold;")
        bar.addWidget(self._title)
        bar.addSeparator()

        def _mkbtn(text, slot):
            b = QPushButton(text)
            b.setFlat(True)
            b.clicked.connect(slot)
            bar.addWidget(b)
            return b

        _mkbtn("目录", self._open_toc)
        _mkbtn("搜索", self._open_search)
        _mkbtn("字号", self._open_font_size)
        _mkbtn("收藏", self._open_favorites)
        _mkbtn("词典", self._open_dictionary)

        root.addWidget(bar)

        # ---- 正文 ----
        self.line_view = self.make_line_view(self)
        root.addWidget(self.line_view, 1)
        self._install_view_hooks()

        # ---- 底部播放条 ----
        self.audio_bar = self.make_audio_bar(self)
        self._attach_audio_bar()
        root.addWidget(self.audio_bar)

        self.status = QLabel("")
        self.status.setStyleSheet("color:#888;padding:2px 8px;")
        root.addWidget(self.status)

    def _install_view_hooks(self):
        """给正文视图安装事件过滤器：双击查词、右键「从此处朗读/收藏」等。"""
        vp = getattr(self.line_view, "viewport", None)
        target = vp() if callable(vp) else self.line_view
        try:
            if target is not None:
                target.setMouseTracking(True)
                target.installEventFilter(self)
        except Exception:  # noqa: BLE001
            pass
        self._install_scroll_tracking()

    def _install_scroll_tracking(self):
        """监听正文视图的垂直滚动，记录「读到哪一行」。

        **为什么必须有它**：进度原先只在**播放**时更新（``_on_pb_segment``），
        于是「没有音频的书」—— 也就是纯阅读 —— 永远记不住位置：
        用户读到哪里、换个终端打开，都只能从头开始。
        纯阅读时位置由**视口首行**决定：有音频时仍以播放段为准（更准确）。
        """
        self._scroll_save_timer = QTimer(self)
        self._scroll_save_timer.setSingleShot(True)
        self._scroll_save_timer.setInterval(800)
        self._scroll_save_timer.timeout.connect(self._on_scroll_settled)
        try:
            bar = self.line_view.verticalScrollBar()
            bar.valueChanged.connect(self._on_scrolled)
        except Exception:  # noqa: BLE001
            self._scroll_save_timer = None

    def _on_scrolled(self, _value: int):
        """滚动中：只更新「当前可见首行」，落盘延后（滚轮会连发几十个事件）。"""
        idx = self._first_visible_line()
        if idx is not None and idx != self._scroll_line_index:
            self._scroll_line_index = idx
            if self._scroll_save_timer is not None:
                self._scroll_save_timer.start()

    def _on_scroll_settled(self):
        """滚动停稳：把可见首行换算成段号并记进度（纯阅读时这就是唯一的位置来源）。"""
        # 用滚动事件里记下的行号，而不是重新问视图：
        # 平滑滚动还在动画时，视图几何与最终落点可能不一致；已记的行号更贴近用户看到的。
        idx = self._scroll_line_index
        if idx is None or idx < 0:
            idx = self._first_visible_line()
        if idx is None or idx < 0:
            return
        self._scroll_line_index = idx
        # 有音频在播时不覆盖播放位置（播放段更准）
        if self.current_segment:
            return
        seg = self._segment_of_line(idx)
        if seg:
            self.current_segment = seg
            self._save_progress()

    def _first_visible_line(self) -> Optional[int]:
        """视口顶部可见的行号（0 起）；取不到返回 None。

        两种正文控件都要支持：
        - 真实的 QListWidget（``app/ui/widgets/line_list.py``）：按 item 几何二分；
        - 占位的 QTextEdit（``base_page._FallbackLineListView``）：按 block 编号
          （它把每行渲染成一个 ``<p>``，block 编号即行号）。
        """
        lw = self.line_view
        # ① 真实控件：按 item 几何找视口顶部所在行
        try:
            vbar = lw.verticalScrollBar()
            idx = self.call(lw, "line_index_at_scroll", vbar.value(), default=None)
            if isinstance(idx, int) and idx >= 0:
                return idx
        except Exception:  # noqa: BLE001
            pass
        # ② 占位控件（QTextEdit）：视口左上角所在的 block 即首行
        if isinstance(lw, QTextEdit):
            try:
                doc = lw.document()
                block = lw.cursorForPosition(QPoint(0, 0)).blockNumber()
                if 0 <= block < doc.blockCount():
                    return block
            except Exception:  # noqa: BLE001
                pass
        return None

    def _segment_of_line(self, line_index: int) -> int:
        """行号 → 段号（1-based）；这一行不属于任何段时返回 0。

        书城书用服务端给的 ``rowParagraphStart`` 映射（行 → 段落下标），
        本地书用本机切段结果，两边都落在 ``LineItem.segment_number`` 上。
        """
        if not self.lines:
            return 0
        if 0 <= line_index < len(self.lines):
            seg = self.lines[line_index].segment_number
            return int(seg) if seg else 0
        return 0

    def eventFilter(self, obj, event):
        from PySide6.QtCore import QEvent
        if obj in (getattr(self.line_view, "viewport", lambda: None)(), self.line_view):
            et = event.type()
            if et == QEvent.MouseButtonDblClick and event.button() == Qt.LeftButton:
                self._on_double_click(event.pos())
                return True
            if et == QEvent.ContextMenu:
                self._on_context_menu(event.pos(), event.globalPos())
                return True
        return super().eventFilter(obj, event)

    # ==================================================================
    # 打开书籍
    # ==================================================================

    def open_book(self, book_id: int = None, store_book_id: int = None,
                  jump: dict = None, autoplay: bool = False):
        """打开一本书。

        ``autoplay``：书架「播放」按钮进来时置 True —— 正文加载完成后直接起播，
        不必让用户再点一次底部播放条（书架上写着「可下载播放」却没有播放入口，
        正是本次故障的来源）。

        **必须先清空上一次的正文**：阅读器页是**单实例复用**的（MainWindow 只创建一个
        ReaderPage 放进 QStackedWidget），切书时旧正文仍留在视图上。
        加载是异步的（书城 PDF 实测要 5~10s），这期间用户看到的就是「上一本书的正文」，
        像是点开了别的书 —— 而真正的目标内容要等几秒后才出现。

        ``jump``：单词本「跳转到原书」带来的收藏出处（wire 6 键）。非空表示本次是
        「跳转打开」—— _apply_loaded 完成后按出处定位句子，并跳过进度恢复的抢占。
        """
        self._load_seq += 1
        seq = self._load_seq
        self._clear_content()
        self._autoplay = bool(autoplay)
        # _load_seq 守卫：异步加载乱序完成时旧请求会被 _apply_loaded 整包丢弃，
        # 它末尾的跳转应用也就不会执行 —— 所以把 pending_jump 与「本次打开」绑定即可，
        # 不必再单独存一份 seq。标志用于让 _restore_progress 不把定位抢回上次阅读位置。
        self._pending_jump = jump or None
        self._skip_progress_restore = bool(jump)

        if store_book_id:
            self.store_book_id = store_book_id
            self.book_id = 0
            self._show_loading("正在加载书城内容…")
            self.run_async(lambda: self._fetch_store(store_book_id),
                           on_result=lambda r: self._apply_loaded(r, seq),
                           on_error=lambda m: self._on_load_error(m, seq))
        elif book_id:
            self.book_id = book_id
            self.store_book_id = 0
            self._store_title = ""
            self._show_loading("正在加载本地书籍…")
            self.run_async(lambda: self._load_local(book_id),
                           on_result=lambda r: self._apply_loaded(r, seq),
                           on_error=lambda m: self._on_load_error(m, seq))

    def _show_loading(self, message: str):
        """显示加载态：标题栏 + 状态栏 + 正文区的占位提示。

        正文区留一行提示而不是空白 —— 空白看起来像「这本书没有内容」，
        而提示能明确告诉用户「正在加载，等一下」。
        """
        self.status.setText(message)
        self._title.setText("正在加载…")
        self.call(self.line_view, "set_lines",
                  [LineItem(index=0, text=message, segment_number=None)])

    def _clear_content(self):
        """清空当前正文与书籍身份，让「加载中」期间不残留上一本书的内容。"""
        self.book = None
        self.task = None
        self.lines = []
        self.segments = []
        self.toc = []
        self.current_segment = 0
        self._offset_ms = 0
        self._book_key = ""
        self._seg_first_line.clear()
        self._line_index_to_seg.clear()
        self._audio_by_seg.clear()
        self._play_files = []
        self._play_segnums = []
        self._want_play = False
        self._buffering_seg = 0
        self._ensure_try = (0, 0.0, 0)
        self._cloud_prepare_active = False
        self._cloud_prepare_seg = 0
        self._pb_timer.stop()
        # 滚动位置也要复位：否则新书刚打开就被上一本的滚动位置「纠正」回去
        self._scroll_line_index = -1
        self.call(self.line_view, "set_lines", [])
        self.call(self.line_view, "set_font_size", self.config.font_size)
        self.call(self.line_view, "set_night_mode", self.config.night_mode)

    def _on_load_error(self, message: str, seq: int):
        """加载失败：只处理「当前这本书」的错误，旧请求的失败不该覆盖新书的状态。"""
        if seq != self._load_seq:
            return
        self.status.setText(f"加载失败：{message}")

    # ---------- 本地 ----------

    def _load_local(self, book_id: int):
        book = self.db.get_book(book_id)
        if not book:
            raise RuntimeError(f"本地书籍不存在: {book_id}")
        task = self._find_task_for(book)

        segments = self.db.get_segments(task.id) if task else []
        # 云端任务：本机可能只下了几段（或一段都没下），但服务端有完整段表。
        # 旧实现只读本机 audio_segments ⇒ 「一段都没下」时正文按原始行切、段号全空，
        # 徽标写着「☁ 云端有声 · 可下载播放」，阅读页却连音频入口都没有，
        # 点播放直接弹「本书还没有合成音频」。
        # 这里用服务端段表做骨架（文本/段号），本机已下载的路径回填进去，
        # 播放时缺的段再按需下载（TaskAudioDownloader）。
        if task and int(getattr(task, "server_task_id", 0) or 0) > 0:
            try:
                remote = self.api.get_task_segments(int(task.server_task_id))
            except Exception:  # noqa: BLE001
                remote = []
            if remote:
                local_by_num = {s.segment_number: s for s in segments if s.audio_file_path}
                merged: List[Segment] = []
                for rs in sorted(remote, key=lambda s: s.segment_number):
                    ls = local_by_num.get(rs.segment_number)
                    if ls:
                        rs.audio_file_path = ls.audio_file_path
                        rs.duration_seconds = rs.duration_seconds or ls.duration_seconds
                    merged.append(rs)
                segments = merged
        text = self._parse_local_text(book)
        if not text and segments:
            # 解析不出正文时，退回用段文本
            text = "\n".join(s.text_content for s in segments)
        lines = self._build_lines(text, segments)
        toc = self._local_toc(book, task)
        # book / task 随结果一起返回，由 _apply_loaded 在主线程写入 self：
        # 本方法跑在后台线程，若在这里直接写 self.book，旧请求晚返回时
        # 会把「当前书」的身份改掉（标题显示成别的书）。
        return dict(book=book, task=task, lines=lines, segments=segments, toc=toc,
                    key=f"local:{book_id}")

    def _build_lines(self, text: str, segments: List[Segment]) -> List[LineItem]:
        """本地书正文：有音频段时用段文本（保证段号对齐、高亮/从此处读可用）；
        否则按行切分纯文本，段号留空。"""
        lines: List[LineItem] = []
        if segments:
            segs = sorted(segments, key=lambda s: s.segment_number)
            for i, s in enumerate(segs):
                lines.append(LineItem(index=i, text=s.text_content or "",
                                      segment_number=s.segment_number))
            return lines
        raw_lines = [ln.rstrip() for ln in (text or "").splitlines()]
        idx = 0
        for ln in raw_lines:
            lines.append(LineItem(index=idx, text=ln, segment_number=None))
            idx += 1
        return lines

    def _find_task_for(self, book: Book) -> Optional[Task]:
        tasks = [t for t in self.db.get_tasks() if t.book_name == book.display_name]
        if not tasks:
            return None
        completed = [t for t in tasks if t.status == "completed"]
        return (completed or tasks)[0]

    def _parse_local_text(self, book: Book) -> str:
        """统一走 book_service.extract_book_text 抽取正文：
        优先 app.parser.booktext（txt/md/epub/pdf/mobi），缺失时退回内置实现。
        （旧实现依赖不存在的 book_service.get_book_text 与空的 app.parser 顶层导出，
        导致 epub/pdf 等解析出空正文、阅读页一片空白。）"""
        path = book.file_path
        if not path or not os.path.exists(path):
            return ""
        try:
            from app.core.book_service import extract_book_text
            return extract_book_text(path) or ""
        except Exception:  # noqa: BLE001
            return ""

    def _local_toc(self, book: Book, task: Optional[Task]) -> List[TocEntry]:
        if task and task.server_task_id:
            try:
                return self.api.get_task_toc(task.server_task_id)
            except Exception:  # noqa: BLE001
                pass
        toc = self.call(self.book_service, "get_toc", book, default=None)
        return toc if isinstance(toc, list) else []

    # ---------- 书城 ----------

    def _resolve_store_title(self, store_book_id: int, content) -> str:
        """按 正文→本地书架→书籍详情 顺序解析书城书的真实标题。"""
        if isinstance(content, dict):
            t = content.get("title") or content.get("bookName") or content.get("name") or ""
            if t:
                return str(t).strip()
        try:
            sb = self.db.get_book_by_store_id(store_book_id)
            if sb and sb.display_name:
                return sb.display_name
        except Exception:  # noqa: BLE001
            pass
        try:
            detail = self.api.get_store_book_detail(store_book_id)
            t = getattr(detail, "title", "") or ""
            if t:
                return str(t).strip()
        except Exception:  # noqa: BLE001
            pass
        return ""

    def _fetch_store(self, store_book_id: int):
        # 正文优先走服务端（段落与合成音频严格对齐，是「边下载边播放」的锚点）；
        # 服务端不可用（离线/下架/解析失败）时退回本机已下载文件，保证至少能读正文。
        try:
            content = self.api.get_store_book_content(store_book_id)
        except Exception:  # noqa: BLE001
            content = None
        if not isinstance(content, dict):
            content = self._store_content_fallback(store_book_id)
        # 解析真实书名（收藏身份 bookName / 标题显示）：正文→本地书架→书籍详情，逐级兜底
        # 存在局部变量里、随结果返回，由 _apply_loaded 在主线程写入 self._store_title：
        # 本方法跑在后台线程，直接写 self 会让旧请求晚返回时改掉当前书的标题。
        store_title = self._resolve_store_title(store_book_id, content)
        segments: List[Segment] = []
        try:
            segments = self.api.get_store_book_segments(store_book_id)
        except Exception:  # noqa: BLE001
            segments = []
        # 回填本机已下载的书城音频段（audio_segments 里 task_id = -store_book_id），
        # 让 _build_audio_map 能命中本地文件，已下过的段无需重新下载即可播放。
        try:
            local = self.db.get_segments(-store_book_id)
            local_by_num = {s.segment_number: s for s in local if s.audio_file_path}
            for s in segments:
                ls = local_by_num.get(s.segment_number)
                if ls:
                    s.audio_file_path = ls.audio_file_path
                    s.duration_seconds = ls.duration_seconds
        except Exception:  # noqa: BLE001
            pass
        try:
            toc = self.api.get_store_book_toc(store_book_id)
        except Exception:  # noqa: BLE001
            toc = content.get("toc", []) if isinstance(content, dict) else []
        lines = self._store_lines(content, segments)
        return dict(book=None, task=None, store_title=store_title,
                    lines=lines, segments=segments, toc=toc,
                    key=f"store:{store_book_id}")

    def _store_content_fallback(self, store_book_id: int) -> dict:
        """书城正文接口不可用时的离线兜底：用本机已下载文件拼出最小正文结构。

        段落号仅按行号近似（无音频时不影响纯阅读；离线本也拉不到音频段）。
        """
        try:
            sb = self.db.get_book_by_store_id(store_book_id)
            if sb and sb.file_path and os.path.exists(sb.file_path):
                from app.core.book_service import extract_book_text
                text = extract_book_text(sb.file_path) or ""
                paras = [{"text": ln, "paragraphIndex": i}
                         for i, ln in enumerate(text.splitlines()) if ln.strip()]
                return {"title": sb.display_name, "paragraphs": paras, "source": "local"}
        except Exception:  # noqa: BLE001
            pass
        return {}

    def _store_lines(self, content, segments: List[Segment]) -> List[LineItem]:
        """优先渲染原文行 rows（用 rowParagraphStart 映射段号），退回 paragraphs。"""
        c = content if isinstance(content, dict) else {}
        rows = c.get("rows")
        starts = c.get("rowParagraphStart") or c.get("rowParagraphs")
        paras = c.get("paragraphs") or []
        lines: List[LineItem] = []
        if rows:
            for i, r in enumerate(rows):
                pidx = None
                if starts and i < len(starts) and starts[i] is not None:
                    pidx = int(starts[i])
                lines.append(LineItem(index=i, text=r,
                                      segment_number=(pidx + 1) if pidx is not None else None))
            return lines
        if paras:
            for i, p in enumerate(paras):
                if isinstance(p, dict):
                    text = p.get("text", "")
                    pidx = p.get("paragraphIndex", i)
                else:
                    text = str(p)
                    pidx = i
                lines.append(LineItem(index=i, text=text, segment_number=int(pidx) + 1))
            return lines
        # 退回：段文本
        for i, s in enumerate(segments):
            lines.append(LineItem(index=i, text=s.text_content, segment_number=s.segment_number))
        return lines

    # ---------- 应用加载结果 ----------

    def _apply_loaded(self, result: dict, seq: Optional[int] = None):
        """应用加载结果。

        seq 是本次 open_book 的请求序号：异步加载是乱序完成的，
        「先点 A 再点 B」时 A 可能后到。若不校验，A 的正文会盖掉 B 的正文
        （用户看到的是点开了 A，不是 B）。旧结果直接丢弃。

        哨兵值用 None 而不是 0：请求序号从 1 开始（open_book 先自增再取），
        若拿 0 当「不校验」，第一个请求的序号恰好是 0 时守卫会被 falsy 短路。
        """
        if seq is not None and seq != self._load_seq:
            return
        # 书籍身份在这里（主线程）写入：加载函数跑在后台线程，
        # 由它们直接写 self.book / self.task / self._store_title 会有竞态。
        self.book = result.get("book")
        self.task = result.get("task")
        if "store_title" in result:
            self._store_title = result.get("store_title") or ""
        self.lines = result.get("lines", [])
        self.segments = result.get("segments", [])
        self.toc = result.get("toc", [])
        self._book_key = result.get("key", "")
        self._rebuild_line_maps()
        self._build_audio_map()

        # 展示
        self.call(self.line_view, "set_lines", self.lines)
        self.call(self.line_view, "set_font_size", self.config.font_size)
        self.call(self.line_view, "set_night_mode", self.config.night_mode)

        title = self.book.display_name if self.book else (self._store_title or "书城书籍")
        self._title.setText(title)
        self.setWindowTitle(title)
        if self.book:
            self.db.update_book_last_read(self.book.id)

        self._attach_playback()

        # 恢复进度：跳转打开时**必须跳过** —— _restore_progress 会本地 + 两次跨端
        # 共三次 _highlight_and_scroll，把刚按出处定位好的句子抢回上次阅读位置。
        if not self._skip_progress_restore:
            self._restore_progress()

        seg_count = len(self.segments)
        done = sum(1 for s in self.segments if s.status == "completed")
        self.status.setText(
            f"共 {len(self.lines)} 行 · {done}/{seg_count} 段有声"
            if seg_count else f"共 {len(self.lines)} 行 · 无音频"
        )
        if not self.segments:
            self.call(self.audio_bar, "set_position", 0, 0)

        # 书架「播放」进入：正文/进度就绪后直接起播，不必让用户再点一次底部播放条。
        # 用 singleShot(0) 把起播排到本次事件之后，避免在加载回调里同步阻塞做网络准备工作。
        if self._autoplay:
            self._autoplay = False
            QTimer.singleShot(0, self._on_play)

        # 单词本跳转定位放在最后（进度恢复 / 状态更新之后），保证不被任何后续高亮覆盖。
        if self._pending_jump:
            jump = self._pending_jump
            self._pending_jump = None
            self._skip_progress_restore = False
            self._apply_word_jump(jump)

    def _apply_word_jump(self, jump: dict):
        """按收藏出处定位到句子（单词本「跳转到原书」的落点）。

        优先级：sentenceText 按内容定位（**第一判据**，行号会随切分粒度漂移）→
        lineNumber 行号 hint → segmentNumber 段号兜底 → 都没有则补做进度恢复。
        只做视觉定位，不改 current_segment / 不触发 _save_progress：跳一本书看一眼
        不该把用户的阅读进度覆盖成这个句子。
        """
        if not isinstance(jump, dict) or not self.lines:
            self._restore_progress()
            return

        def _pos_int(v):
            try:
                n = int(v or 0)
            except (TypeError, ValueError):
                return 0
            return n if n > 0 else 0

        text = (jump.get("sentenceText") or "").strip()
        line_hint = _pos_int(jump.get("lineNumber"))
        seg = _pos_int(jump.get("segmentNumber"))
        row = -1
        if text:
            # SegmentMap 的按文本定位（注释写明「收藏跳转用」但一直没接线，这里接上）。
            # for_text_lookup 不需要音频段，纯阅读的书也能用同一套规则。
            from app.parser.segment_map import SegmentMap
            sm = SegmentMap.for_text_lookup([ln.text for ln in self.lines])
            row = sm.locate_row_by_text(text, line_hint)
        if row < 0 and line_hint:
            # 行号只是 hint：文本没命中才用，夹紧到有效范围（1-based → 0-based 下标）。
            row = min(max(line_hint - 1, 0), len(self.lines) - 1)
        if row >= 0:
            self.call(self.line_view, "scroll_to_line", row)
            line_seg = self._line_index_to_seg.get(row)
            if line_seg:
                self.call(self.line_view, "highlight_segment", line_seg)
            return
        if seg:
            # 段号是 1-based 语义位置，比行号稳；内部会 scroll_to_line + highlight_segment。
            self._highlight_and_scroll(seg)
            return
        # 出处里只有书名、没有任何定位依据：照常恢复进度，别停在开头干瞪眼。
        self._restore_progress()

    def _rebuild_line_maps(self):
        self._seg_first_line.clear()
        self._line_index_to_seg.clear()
        for ln in self.lines:
            if ln.segment_number is not None:
                self._line_index_to_seg[ln.index] = ln.segment_number
                self._seg_first_line.setdefault(ln.segment_number, ln.index)

    def _build_audio_map(self):
        """重建「段号 → 本机音频文件」映射，并初始化播放窗口。

        ⚠ 必须直读 ``audio_segments``（本机已落盘的段），不能只看加载时的内存快照：
        快照里 ``audio_file_path`` 永远是加载那一刻的值，刚下载的段会被漏掉 ⇒
        误报「本书暂无合成音频」。
        """
        local: dict = {}
        try:
            if self.store_book_id:
                rows = self.db.get_segments(-self.store_book_id)
            elif self.task is not None and self.task.id:
                rows = self.db.get_segments(self.task.id)
            else:
                rows = []
            local = {int(s.segment_number): s.audio_file_path
                     for s in rows if s.audio_file_path}
        except Exception:  # noqa: BLE001
            local = {}
        self._audio_by_seg = {}
        # 快照里带路径的段（书城/任务接口直接给了路径）作为兜底
        for s in self.segments:
            p = s.audio_file_path or ""
            if p and os.path.exists(p):
                local.setdefault(int(s.segment_number), p)
        for num, p in local.items():
            if p and os.path.exists(p):
                self._audio_by_seg[int(num)] = str(p)
        self._build_play_window(self.current_segment)

    # ---- 播放窗口：从起播段起**连续**的一段 ----

    def _build_play_window(self, start: int):
        """把播放队列重置为「从 start 起连续可得」的一段（缺口留给按需下载）。

        ⚠ **不许退回「最近的可得段」**：本机只有 9–26 / 35–49 时，用户点第 27 段，
        旧行为会悄悄从 35 起播 —— 正文高亮在 27、声音却是 35 之后的内容，
        用户唯一的感受就是「跳段、串台」。起点不可得时这里返回空队列，
        由 :meth:`_prepare_cloud_streaming` 去下载这一段，下完再起播。
        """
        self._play_files = []
        self._play_segnums = []
        seg = int(start or 0)
        if not seg:
            return
        for n in play_queue.build_window(self._audio_by_seg, seg):
            self._play_files.append(self._audio_by_seg[n])
            self._play_segnums.append(n)

    def _extend_play_queue(self) -> int:
        """把「已下载且紧接队尾」的段追加进播放队列，返回追加条数。

        ⚠ 只能追加**连续**段：引擎的 ``append_files`` 一律排在队尾，
        缺口没补上就追加后面的段，播放顺序会错乱（旧实现正是如此）。
        """
        if not self._play_segnums:
            return 0
        new = play_queue.extend(self._play_segnums, self._audio_by_seg)
        if not new:
            return 0
        files = [self._audio_by_seg[n] for n in new]
        self._play_segnums.extend(new)
        self._play_files.extend(files)
        if self.playback is not None:
            try:
                self.playback.append_files(files)
            except Exception:  # noqa: BLE001
                pass
        return len(new)

    # ---- 云端音频（书城书 / 私有云端任务） ----

    def _cloud_pair(self):
        """当前书的云端音频下载器 → (downloader, key)；没有则 (None, 0)。

        书城书走 ``/api/store/**``（公共音频），本机导入的书在云端合成后走
        ``/api/tts/**``（私有任务）。两者的 key 分别是 store_book_id / server_task_id。
        """
        if self.store_book_id and self.store_audio is not None:
            return self.store_audio, int(self.store_book_id)
        task = self.task
        stid = int(getattr(task, "server_task_id", 0) or 0) if task is not None else 0
        if stid and self.task_audio is not None:
            return self.task_audio, stid
        return None, 0

    # ---- 段号 <-> 播放列表下标 ----

    def _index_for_seg(self, seg: int) -> int:
        try:
            return self._play_segnums.index(seg)
        except ValueError:
            return 0

    def _seg_for_index(self, idx: int) -> Optional[int]:
        if 0 <= idx < len(self._play_segnums):
            return self._play_segnums[idx]
        return None

    def _ordinal_of(self, seg: int) -> int:
        """段号对应的 1-based 序位（用于「第 x/N 段」显示）；找不到返回 0。"""
        try:
            return self._play_segnums.index(seg) + 1
        except ValueError:
            return 0

    # ==================================================================
    # 进度
    # ==================================================================

    def _server_book_key(self) -> str:
        """当前这本书的**跨端统一身份键**（s:/t:/n:）。

        与有声覆盖图（:func:`app.core.coverage.book_key`）同一套口径，三端逐字符一致 ⇒
        同一本书在 Android/PC/Web 算出同一个 key，进度才能互通。

        本地进度表用的主键是 ``local:{bookId}`` / ``store:{storeBookId}``（本机内部编号），
        它只在本机有效 —— 换台设备就找不到对应的书，因此**不能**拿去和服务端对账。
        """
        try:
            from app.core import coverage as _cov
            sbid = 0
            stid = 0
            name = ""
            if self.store_book_id:
                sbid = int(self.store_book_id)
                name = self._store_title or ""
            elif self.book is not None:
                sbid = int(getattr(self.book, "store_book_id", 0) or 0)
                name = self.book.display_name or ""
            if self.task is not None:
                stid = int(getattr(self.task, "server_task_id", 0) or 0)
            if not name and self.task is not None:
                name = self.task.book_name or ""
            return _cov.book_key(sbid, stid, name)
        except Exception:  # noqa: BLE001
            return ""

    def _restore_progress(self):
        try:
            prog = self.db.get_progress(self._book_key)
        except Exception:  # noqa: BLE001
            prog = None
        if prog and prog.segment_number:
            self.current_segment = prog.segment_number
            self._offset_ms = prog.offset_ms
            self._highlight_and_scroll(prog.segment_number)
        # 跨端：后台拉取云端最新进度（时间戳新者胜）；服务端更新则跳到云端位置。
        # 本地更新时 reconcile_book 会自动补传，无需在此额外处理。
        if self.sync and self._book_key and self.config.is_authenticated:
            bk = self._book_key

            def _pull():
                try:
                    return self.sync.reconcile_book(bk)
                except Exception:  # noqa: BLE001
                    return None

            def _ok(res):
                if res is None or not getattr(res, "progress_pulled", 0):
                    return
                if self._book_key != bk:   # 期间已切书，忽略
                    return
                try:
                    np = self.db.get_progress(bk)
                except Exception:  # noqa: BLE001
                    return
                if np.segment_number and np.segment_number != self.current_segment:
                    self.current_segment = np.segment_number
                    self._offset_ms = np.offset_ms
                    self._highlight_and_scroll(np.segment_number)

            self.run_async(_pull, on_result=_ok)
        # 跨端（书口径）：按 bookKey 拉服务端进度。
        # 与上面的 reconcile_book 是**两条不同的路**：
        #   reconcile_book 用本机主键（local:/store:）+ 任务口径端点，只能认出「自己的任务」；
        #   这里用跨端 bookKey + /api/book-progress，能认出**别的终端**推进到哪。
        # 两者取「更新的那个」应用（时间戳新者胜）。
        if self.sync and self.config.is_authenticated:
            sbk = self._server_book_key()
            if sbk:
                local_key = self._book_key

                def _pull2():
                    return self.sync.fetch_book_progress(sbk)

                def _ok2(srv):
                    if not isinstance(srv, dict) or not srv.get("hasProgress"):
                        return
                    if self._book_key != local_key:   # 期间已切书，忽略
                        return
                    try:
                        srv_seg = int(srv.get("segment", 0) or 0)
                        srv_off = int(srv.get("offsetMs", 0) or 0)
                        srv_ts = srv.get("updatedAt", "") or ""
                    except Exception:  # noqa: BLE001
                        return
                    if not srv_seg:
                        return
                    # 服务端更新才覆盖本地（与 reconcile_book 同一条「时间戳新者胜」规则）
                    try:
                        local = self.db.get_progress(local_key)
                    except Exception:  # noqa: BLE001
                        local = None
                    local_seg = local.segment_number if local else 0
                    local_ts = (local.updated_at if local else "") or ""
                    from app.db.database import _is_server_progress_newer
                    if not _is_server_progress_newer(local_seg, local_ts, srv_seg, srv_ts):
                        return
                    self.current_segment = srv_seg
                    self._offset_ms = srv_off
                    self._highlight_and_scroll(srv_seg)

                self.run_async(_pull2, on_result=_ok2)

    def _save_progress(self):
        if not self._book_key or not self.current_segment:
            return
        try:
            self.db.save_progress(self._book_key, self.current_segment, self._offset_ms)
        except Exception:  # noqa: BLE001
            pass
        if not self.config.is_authenticated:
            return
        seg, off = self.current_segment, self._offset_ms
        # 上报 ①：书口径（跨端通用）。
        # 身份用 bookKey 而非本机 id：同一本书在三端算出的 key 相同，
        # 别的终端才能认出来。**无音频的书也在这里上报** —— 纯阅读的位置同样要跨端。
        sbk = self._server_book_key()
        if sbk:
            def _put_key():
                try:
                    self.api.put_book_progress(sbk, seg, off)
                except Exception:  # noqa: BLE001
                    pass
            self.run_async(_put_key)
        # 上报 ②：老端点（任务口径）。保留是为了让**已在用的老客户端**
        # 也能读到本机推进的位置（服务端会把它镜像到 book_key 表）。
        if self.store_book_id:
            def _put():
                try:
                    self.api.put_store_reading_progress(self.store_book_id, seg, off)
                except Exception:  # noqa: BLE001
                    pass
            self.run_async(_put)
        elif self.task and self.task.server_task_id:
            tid = int(self.task.server_task_id)

            def _put_task():
                try:
                    self.api.put_progress(tid, seg, off)
                except Exception:  # noqa: BLE001
                    pass
            self.run_async(_put_task)

    # ==================================================================
    # 高亮 / 自动滚动
    # ==================================================================

    def _highlight_and_scroll(self, segment_number: int):
        self.call(self.line_view, "highlight_segment", segment_number)
        line_idx = self._seg_first_line.get(segment_number)
        if line_idx is not None:
            self.call(self.line_view, "scroll_to_line", line_idx)

    # ==================================================================
    # 播放集成
    # ==================================================================

    def _attach_audio_bar(self):
        """连接底部播放条。真实/占位 AudioBar 的信号名不同，逐一 try_connect。

        占位 _FallbackAudioBar 发 play_pause_clicked/prev_clicked/next_clicked/seek_segment；
        真实 AudioBar 发 play_clicked/pause_clicked/prev_segment/next_segment/seek_to(毫秒)。
        全部映射到本页的起播/暂停/切段逻辑。
        """
        ab = self.audio_bar
        # 播放/暂停切换（占位）
        self.try_connect(ab, "play_pause_clicked", self._on_play_pause)
        # 真实 widget 的分离信号
        self.try_connect(ab, "play_clicked", lambda *_a: self._on_play())
        self.try_connect(ab, "playClicked", lambda *_a: self._on_play())
        self.try_connect(ab, "pause_clicked", lambda *_a: self._do_pause())
        self.try_connect(ab, "pauseClicked", lambda *_a: self._do_pause())
        # 上/下一段
        self.try_connect(ab, "prev_clicked", self._on_prev)
        self.try_connect(ab, "prevClicked", self._on_prev)
        self.try_connect(ab, "prev_segment", self._on_prev)
        self.try_connect(ab, "next_clicked", self._on_next)
        self.try_connect(ab, "nextClicked", self._on_next)
        self.try_connect(ab, "next_segment", self._on_next)
        # 跳转：**两种播放条的载荷都是「段号」**。
        # 占位条是段号输入框（1-based 段号）；真实 AudioBar 走 set_position 的段模式
        # （见 audio_bar.set_position 注释），滑块值同样是段号。
        # ⚠ 历史坑：这里原先映射到 _on_seek_ordinal（把值当**队列下标**），
        # 而队列是从「起播段」开始的窗口 —— 用户拖到第 60 段会落到 69，
        # 起播点越靠后偏得越多，表现为「拖进度条乱跳」。
        self.try_connect(ab, "seek_segment", self._on_seek_segment)
        self.try_connect(ab, "seekSegment", self._on_seek_segment)
        self.try_connect(ab, "seek_to", self._on_seek_segment)
        # 倍速：此前 ``AudioBar.speed_changed`` 全仓**没有接收者** —— 用户在下拉里改倍速
        # 既不改变播放速率，也不写回 config.playback_speed（该字段永远停在 1.0）。
        # 现在接通；跨端同步层靠「config 现值 vs 影子快照」判脏，这里只需落盘，
        # 不必（也不该）显式发同步请求。
        self.try_connect(ab, "speed_changed", self._on_speed_changed)
        self.try_connect(ab, "speedChanged", self._on_speed_changed)
        try:
            setter = getattr(ab, "set_speed", None)
            if callable(setter):
                # set_speed 内部 blockSignals，程序化回灌不会触发上报
                setter(float(getattr(self.config, "playback_speed", 1.0) or 1.0))
        except Exception:  # noqa: BLE001
            pass

    def _on_speed_changed(self, speed):
        """用户主动改倍速：落盘 + 立即应用到播放引擎（同步由 SyncManager 下轮自动带上去）。"""
        try:
            rate = max(0.5, min(3.0, float(speed)))
        except (TypeError, ValueError):
            return
        self.config.playback_speed = rate
        try:
            self.config.save()
        except Exception:  # noqa: BLE001
            pass
        pb = self.playback
        setter = getattr(pb, "set_speed", None) if pb is not None else None
        if callable(setter):
            try:
                setter(rate)
            except Exception:  # noqa: BLE001
                pass

    def _attach_playback(self):
        """把 AudioEngine 的后台回调接到主线程信号桥上（只需一次）。"""
        if self._playback_attached or self.playback is None:
            return
        pb = self.playback
        try:
            pb.on_segment_start = self._pb_bridge.seg_start.emit
            pb.on_segment_end = self._pb_bridge.seg_end.emit
            pb.on_all_complete = self._pb_bridge.all_done.emit
        except Exception:  # noqa: BLE001
            pass
        self._playback_attached = True

    # ---- 传输控制 ----

    def _on_play_pause(self, *_a):
        """播放条主按钮：朗读中→停止朗读；播放中→暂停；暂停中→继续；停止→起播。"""
        pb = self.playback
        if pb is not None and bool(getattr(pb, "is_speaking", False)):
            self._stop_system_say()
            return
        if pb is None:
            if not self._play_files:
                self._offer_synthesize()
                return
            QMessageBox.information(self, "播放引擎未就绪",
                                    "本机/在线播放引擎尚未初始化，暂时无法播放音频。")
            return
        if getattr(pb, "is_playing", False):
            self._do_pause()
        elif getattr(pb, "is_paused", False):
            self._do_resume()
        else:
            self._on_play()

    def _on_play(self):
        """播放：从当前段起播；本机没有这一段就从云端按需下载。"""
        seg = int(self.current_segment or 0)
        if not seg:
            seg = min(self._audio_by_seg) if self._audio_by_seg else 0
        if not seg:
            dl, key = self._cloud_pair()
            segs = dl.segments(key) if dl is not None else []
            if segs:
                seg = int(segs[0].segment_number)
        if not seg:
            self._offer_synthesize()
            return
        self._do_play(seg)

    def _do_play(self, seg: int):
        """从 seg 段起播。本机没有这一段时先下载（云端音频「边下边播」）。"""
        seg = int(seg or 0)
        if not seg:
            return
        if self.playback is None:
            QMessageBox.information(self, "播放引擎未就绪",
                                    "本机/在线播放引擎尚未初始化，暂时无法播放音频。")
            return
        if seg not in self._audio_by_seg:
            dl, _key = self._cloud_pair()
            if dl is not None:
                self._prepare_cloud_streaming(seg)
            else:
                self._offer_synthesize()
            return
        # 队列必须是「从 seg 起连续」的一段：含缺口的队列会跳段/乱序（见 play_queue 模块注释）
        self._build_play_window(seg)
        if not self._play_files:
            return
        self._tts_timer.stop()
        self._tts_restored_status = ""
        self._attach_playback()
        # 云端音频：先武装队尾探针（追到队尾时等待追加），本地有完整音频则解除探针
        if self._cloud_pair()[0] is not None:
            self._arm_cloud_probe()
        else:
            self._disarm_cloud_probe()
        try:
            self.playback.play_sequence(self._play_files, 0)
        except Exception as e:  # noqa: BLE001 —— 含 PlaybackError(无 pygame/无声卡)
            QMessageBox.warning(self, "播放失败", str(e) or "音频设备不可用")
            return
        self.current_segment = seg
        self._want_play = True
        self._buffering_seg = 0
        self._ensure_try = (0, 0.0, 0)
        self._highlight_and_scroll(self.current_segment)
        self.call(self.audio_bar, "set_playing", True)
        self._set_status_playing(self.current_segment, True)
        self._pb_timer.start()
        self._prefetch_cloud_ahead()

    # ---- 云端音频「边下载边播放」 ----

    def _prepare_cloud_streaming(self, seg: int):
        """本机没有这一段：后台下载它，就绪后起播并预取后续段。"""
        dl, key = self._cloud_pair()
        if dl is None:
            self._offer_synthesize()
            return
        if self._cloud_prepare_active:
            return
        segs = dl.segments(key)
        if not segs:
            # 真没合成音频 → 走「去合成」提示
            self._offer_synthesize()
            return
        nums = [int(s.segment_number) for s in segs]
        start = int(seg or 0)
        if start not in nums:
            start = next((n for n in nums if n >= start), nums[0])
        self._cloud_prepare_active = True
        self._cloud_prepare_seg = start
        self.current_segment = start
        self._want_play = True
        self._set_status_buffering(start, first=True)
        # 下载是异步的：期间用户可能已经换书。回来时必须确认「还是这本书这次加载」，
        # 否则 A 书的音频会在 B 书的页面上起播（声音和正文完全对不上）。
        seq = self._load_seq
        book_key = self._book_key

        def _work():
            return dl.ensure_segment(key, start)

        def _done(path):
            self._cloud_prepare_active = False
            if seq != self._load_seq or book_key != self._book_key:
                return
            if not path:
                self._want_play = False
                self._buffering_seg = 0
                self.status.setText("音频下载失败，请稍后重试")
                return
            # 首段已落库：重建映射（含本机已有段）后起播
            self._audio_by_seg[start] = str(path)
            self._build_audio_map()
            self._do_play(start)

        def _err(m):
            self._cloud_prepare_active = False
            if seq != self._load_seq or book_key != self._book_key:
                return
            self._want_play = False
            self._buffering_seg = 0
            self.status.setText(f"音频下载失败：{m}")

        self.run_async(_work, on_result=_done, on_error=_err)

    def _arm_cloud_probe(self):
        """设置播放引擎队尾探针：追到队尾时若后面还有未下载段则等待追加。"""
        if self.playback is None:
            return
        dl, key = self._cloud_pair()
        if dl is None:
            self._disarm_cloud_probe()
            return

        def _probe():
            if not self._play_segnums:
                return False
            return dl.has_more_after(key, self._play_segnums[-1])

        try:
            self.playback.set_has_more_probe(_probe)
        except Exception:  # noqa: BLE001
            pass

    def _disarm_cloud_probe(self):
        if self.playback is not None:
            try:
                self.playback.set_has_more_probe(None)
            except Exception:  # noqa: BLE001
                pass

    def _prefetch_cloud_ahead(self):
        """从队尾往后预取一批段（fire-and-forget，落一段追加一段）。"""
        dl, key = self._cloud_pair()
        if dl is None or not self._play_segnums:
            return
        try:
            dl.prefetch(key, self._play_segnums[-1] + 1, self._CLOUD_PREFETCH,
                        on_segment=lambda n: self._cloud_seg_saved.emit(key, n))
        except Exception:  # noqa: BLE001
            pass

    def _drive_cloud_playback(self):
        """播放推进/缓冲时（250ms）：补齐连续段 + 确保「下一段」真的在下载。

        为什么必须有它：引擎追到队尾只会调 probe 空等，而 probe 只回答
        「后面还有没有段」——**不会去下**。没有这个驱动时，只要预取窗口没跑到
        下一段（下载失败、窗口太浅、刚点播放），声音就永久停在那一段，
        界面也毫无提示（旧实现正是这个症状）。
        """
        dl, key = self._cloud_pair()
        if dl is None:
            return
        self._extend_play_queue()
        if not self._play_segnums:
            return
        last = self._play_segnums[-1]
        nxt = last + 1
        if nxt in self._audio_by_seg:
            return
        if not dl.has_more_after(key, last):
            # 后面真没有了：把「缓冲中」收掉，等引擎自然收尾
            if self._buffering_seg:
                self._buffering_seg = 0
                self._set_status_playing(self.current_segment, True)
            return
        if not self._buffering_seg:
            self._set_status_buffering(nxt)
        # 节流 + 尝试次数上限：下载失败时不要每 250ms 起一个新线程，
        # 也不要无限「正在下载第 N 段…」让用户干等（重试 4 次≈20s 后如实报错）。
        now = time.monotonic()
        last_seg, last_at, tries = self._ensure_try
        if last_seg == nxt:
            if (now - last_at) < self._ENSURE_RETRY_SEC:
                return
            if tries >= self._ENSURE_MAX_TRIES:
                self._want_play = False
                self._buffering_seg = 0
                self.status.setText(f"第 {nxt} 段下载失败，请检查网络后重新点播放")
                self._pb_timer.stop()
                return
            tries += 1
        else:
            tries = 1
        self._ensure_try = (nxt, now, tries)
        dl.ensure_segment_async(
            key, nxt, on_segment=lambda n: self._cloud_seg_saved.emit(key, n))

    def _on_cloud_segment_saved_ui(self, key: int, segment_number: int):
        """云端音频新段落库后（主线程）：更新映射 + 按连续顺序追加到播放引擎。"""
        dl, cur = self._cloud_pair()
        if dl is None or int(key) != int(cur):
            return
        path = dl.local_path(cur, int(segment_number))
        if not path.is_file():
            return
        self._audio_by_seg[int(segment_number)] = str(path)
        self._ensure_try = (0, 0.0, 0)   # 有段落盘 ⇒ 解除重试冷却与次数累计
        self._extend_play_queue()
        if self._buffering_seg and self._buffering_seg in self._audio_by_seg:
            self._buffering_seg = 0
            self._set_status_playing(self.current_segment, True)

    def _do_pause(self):
        self.call(self.playback, "pause")
        self.call(self.audio_bar, "set_playing", False)
        # 暂停时「播放意图」保留：恢复后要继续驱动云端补段
        self._want_play = bool(self._want_play)

    def _do_resume(self):
        self.call(self.playback, "resume")
        self.call(self.audio_bar, "set_playing", True)
        self._want_play = True
        if self._play_files:
            self._pb_timer.start()

    def _on_pause(self):
        self._do_pause()

    def _on_prev(self):
        """上一段：按段号 -1，与播放队列无关（队列只保证连续，不保证起点）。"""
        seg = int(self.current_segment or 0)
        if seg <= 1:
            return
        self._play_from(seg - 1)

    def _on_next(self):
        seg = int(self.current_segment or 0)
        if seg <= 0:
            return
        last = max(self._audio_by_seg) if self._audio_by_seg else seg
        if self.segments:
            last = max(last, max(int(s.segment_number) for s in self.segments))
        if seg >= last:
            return
        self._play_from(seg + 1)

    def _on_seek_segment(self, seg: int):
        self._play_from(int(seg))

    def _play_from(self, seg: int):
        seg = int(seg)
        self.current_segment = seg
        self._offset_ms = 0
        self._highlight_and_scroll(seg)
        self._save_progress()
        self._do_play(seg)

    # ---- playback 回调（信号桥，主线程）----

    def _on_pb_file_index(self, file_index: int):
        seg = self._seg_for_index(int(file_index))
        if seg is None:
            return
        self._on_pb_segment(seg)

    def _on_pb_file_index_end(self, file_index: int):
        # AudioEngine 播完一段会自动接下一段并再发 seg_start，这里无需手动推进；
        # 仅在播放条上做进度显示的小更新。
        pass

    def _on_pb_segment(self, seg: int):
        self.current_segment = int(seg)
        self._highlight_and_scroll(int(seg))
        total = max(len(self.segments), int(seg)) or 1
        self.call(self.audio_bar, "set_position", int(seg), total)
        self._save_progress()
        # 每播到一段：把已就绪的连续段接上，并继续往后预取
        self._extend_play_queue()
        self._prefetch_cloud_ahead()

    def _poll_position(self):
        pb = self.playback
        if pb is None:
            self._pb_timer.stop()
            return
        playing = bool(getattr(pb, "is_playing", False))
        paused = bool(getattr(pb, "is_paused", False))
        if not playing and not paused:
            # 云端音频可能正卡在「等下一段下载完」：引擎是停的，但**不能就此收手**，
            # 否则没有任何东西再去补队列 / 触发下载（旧实现的表现就是永久静音）。
            if self._want_play:
                self._drive_cloud_playback()
                if self._cloud_pair()[0] is not None:
                    return          # 保持轮询，等下一段落盘
            self._pb_timer.stop()
            return
        if playing:
            seg = self._seg_for_index(int(getattr(pb, "current_index", 0)))
            if seg is not None:
                self.current_segment = seg
                self._set_status_playing(seg, True)
            self._offset_ms = int(getattr(pb, "current_position_ms", 0))
            total = max(len(self.segments), seg or 0) or 1
            if seg is not None:
                self.call(self.audio_bar, "set_position", seg, total)

    def _on_pb_all_done(self):
        self._pb_timer.stop()
        self._want_play = False
        self._buffering_seg = 0
        self.call(self.audio_bar, "set_playing", False)
        if self.current_segment:
            self.status.setText(f"播放完毕 · 第 {self.current_segment} 段")

    def _on_pb_position(self, seg: int, offset_ms: int):
        self.current_segment = int(seg)
        self._offset_ms = int(offset_ms)

    def _on_pb_state(self, playing: bool):
        self.call(self.audio_bar, "set_playing", bool(playing))
        # 起播/暂停时刷新状态栏：否则书城书「边下载边播放」全程停在「正在准备音频…」
        self._set_status_playing(getattr(self, "current_segment", 0), bool(playing))

    def _set_status_playing(self, seg: int, playing: bool = True):
        """状态栏显示「播放中/已暂停 · 第 N 段」（段号变化时才重绘）。"""
        try:
            seg = int(seg or 0)
        except (TypeError, ValueError):
            return
        if not seg:
            return
        if playing and seg == self._status_seg:
            return
        self._status_seg = seg
        self.status.setText(f"{'播放中' if playing else '已暂停'} · 第 {seg} 段")

    def _set_status_buffering(self, seg: int, first: bool = False):
        """状态栏显示「正在下载第 N 段…」。

        ⚠️ 这条提示是必需的：云端音频按需下载时，下载一段可能要 2~7 s，
        期间播放引擎是静默的。没有提示，用户只会看到「点了播放没反应/播两句就停」，
        以为是播放器坏了（本次故障的第一印象）。
        """
        self._buffering_seg = int(seg or 0)
        tail = "（首次播放需先下载音频，请稍候）" if first else ""
        self.status.setText(f"正在下载第 {seg} 段…{tail}")

    def _on_pb_finished(self, *_a):
        self._pb_timer.stop()
        self.call(self.audio_bar, "set_playing", False)

    def _offer_synthesize(self):
        if self.book and self.book.id:
            if QMessageBox.question(self, "无音频", "本书还没有合成音频，是否去合成？") == QMessageBox.Yes:
                self.synthesize_requested.emit(self.book.id)
        else:
            QMessageBox.information(self, "无音频", "本书暂无合成音频，可在书城详情或书架中发起合成。")

    # ==================================================================
    # 双击查词 / 右键菜单
    # ==================================================================

    def _on_double_click(self, pos):
        word = self._word_at(pos)
        if word:
            # 双击路径原本连 seg 都没算：这里补齐行/段上下文，收藏才带得出出处。
            self._lookup(word, line_idx=self._line_index_at(pos),
                         seg=self._segment_at(pos))

    def _on_context_menu(self, pos, global_pos: QPoint):
        menu = QMenu(self)
        text = self._selected_text() or self._word_at(pos)
        seg = self._segment_at(pos)
        line_idx = self._line_index_at(pos)

        if text:
            act_lookup = menu.addAction("查词 / 翻译")
            act_fav = menu.addAction("收藏")
            act_read = menu.addAction("朗读选中")
        else:
            act_lookup = act_fav = act_read = None
        # 从此处朗读：有合成音频段→播放原声；无音频但有正文行→系统 TTS 朗读
        can_play_here = bool(seg)
        can_tts_here = isinstance(line_idx, int) and line_idx >= 0 and bool(self.lines)
        act_from_here = menu.addAction("从此处朗读") if (can_play_here or can_tts_here) else None
        menu.addSeparator()
        act_toc = menu.addAction("目录")
        act_font = menu.addAction("字号")

        chosen = menu.exec(global_pos)
        if chosen is None:
            return
        if chosen is act_lookup:
            # 右键菜单已算出 seg / line_idx，必须透传给 _lookup —— 旧代码在这里全丢了，
            # 导致从右键查词收藏的词没有出处，「跳转到原书」永远不可用。
            self._lookup(text, line_idx=line_idx, seg=seg)
        elif chosen is act_fav:
            self._add_favorite(text, seg)
        elif chosen is act_read:
            self._system_say(text)
        elif act_from_here and chosen is act_from_here:
            if can_play_here:
                self._play_from(seg)
            else:
                self._read_aloud_from_line(line_idx)
        elif chosen is act_toc:
            self._open_toc()
        elif chosen is act_font:
            self._open_font_size()

    def _word_at(self, pos) -> str:
        lw = self.line_view
        for name in ("word_at", "wordUnderCursorAt", "get_word_at"):
            v = self.call(lw, name, pos, default=None)
            if isinstance(v, str) and v.strip():
                return v.strip()
        if isinstance(lw, QTextEdit):
            cur = lw.cursorForPosition(pos)
            cur.select(QTextCursor.WordUnderCursor)
            return cur.selectedText().strip()
        return ""

    def _selected_text(self) -> str:
        lw = self.line_view
        for name in ("selected_text", "selection_text", "get_selection"):
            v = self.call(lw, name, default=None)
            if isinstance(v, str) and v.strip():
                return v.strip()
        if isinstance(lw, QTextEdit):
            return lw.textCursor().selectedText().strip()
        return ""

    def _segment_at(self, pos) -> int:
        lw = self.line_view
        v = self.call(lw, "segment_at", pos, default=None)
        if isinstance(v, int) and v > 0:
            return v
        idx = self._line_index_at(pos)
        if idx is not None and idx in self._line_index_to_seg:
            return self._line_index_to_seg[idx]
        return 0

    # ==================================================================
    # 词典 / 收藏 / 朗读
    # ==================================================================

    def _lookup(self, word: str, *, line_idx=None, seg=None):
        """查词 + 明示收藏。line_idx / seg 是本次查词所在的正文行/段上下文，
        用于组装收藏出处（书籍 + 当前句子）；不传时出处为空，跳转按钮会禁用。"""
        word = (word or "").strip()
        if not word:
            return
        res = self.call(self.dictionary, "lookup", word, default=None)
        if res is None:
            # ⚠️ 必须区分「词典没装」与「词典里没收这个词」——
            #   两者都返回 None，但前者是用户该去装的，后者是正常结果。
            #   一律报「词典服务未就绪」会让用户以为坏了，而查生僻词本是常事。
            installed = bool(self.call(self.dictionary, "is_ready", default=False))
            if installed:
                QMessageBox.information(self, "词典", f"词典未收录该词：{word}")
            else:
                QMessageBox.information(
                    self, "词典",
                    f"未安装离线词典，无法查词：{word}\n\n请在「我的 - 离线词典」下载安装。")
            return
        # 组装收藏出处并写进 WordEntry：收藏时一并落库 + 上云，单词本才能「跳转到原书」。
        source = self._word_source_context(line_idx, seg)
        entry = self._to_word_entry(word, res, source)
        # 明示收藏：查词不再自动写单词本，改由用户点星标按钮决定存/删（对齐安卓 DictLookupSheet）。
        saved = False
        try:
            saved = bool(self.db.is_word_saved(entry.word))
        except Exception:  # noqa: BLE001
            saved = False
        text = f"<b>{entry.word}</b>"
        if entry.phonetic:
            text += f"  <span style='color:#888'>{entry.phonetic}</span>"
        text += f"<hr>{entry.definition or '（无释义）'}"
        box = QMessageBox(self)
        box.setWindowTitle("查词")
        box.setTextFormat(Qt.RichText)
        box.setText(text)
        toggle = box.addButton(
            "★ 移出单词本" if saved else "☆ 加入单词本", QMessageBox.ActionRole)
        box.addButton(QMessageBox.Close)
        box.exec()
        if box.clickedButton() is toggle:
            try:
                if saved:
                    self.db.delete_word(entry.word)
                else:
                    self._save_word_with_source(entry, source)
            except Exception:  # noqa: BLE001
                pass


    @staticmethod
    def _to_word_entry(word: str, res, source: dict = None) -> WordEntry:
        if isinstance(res, WordEntry):
            entry = res
        elif isinstance(res, dict):
            entry = WordEntry(word=word, phonetic=res.get("phonetic", ""),
                              definition=res.get("definition", res.get("translation", "")))
        else:
            # 让 dictionary 生成对象
            entry = WordEntry(word=word)
            entry.phonetic = getattr(res, "phonetic", "") or ""
            entry.definition = getattr(res, "definition", "") or str(res)
        # wire 出处 6 键 → WordEntry 本地字段（只覆盖有值的键，其余保持 None）。
        if source:
            for wire_key, field in _WORD_SOURCE_WIRE_TO_FIELD.items():
                v = source.get(wire_key)
                if v is not None:
                    setattr(entry, field, v)
        return entry

    def _word_source_context(self, line_idx=None, seg=None) -> dict:
        """组装「收藏出处」wire 6 键（全部可空，只放有值的键）。

        定位第一判据是 sentenceText（整行原文，不随切分粒度漂移）；lineNumber 只是 hint，
        PC 按 1-based 存（与 SegmentMap.locate_row_by_text 的 hint 约定一致；跨端仅作
        兜底，差一行不影响文本定位）。bookKey 用 _server_book_key() 的 s:/t:/n: 跨端
        口径，**不能**用本机 _book_key（local:/store: 只在本机有效，换台设备认不出）。
        全无出处时返回空 dict —— 服务端 hasSource() 据此判为旧客户端并保留云端旧值。
        """
        src: dict = {}
        book_name = self._favorite_book_name()
        if book_name:
            src["bookName"] = str(book_name)[:255]   # 服务端 bookName 上限 255
        bk = self._server_book_key()
        if bk:
            src["bookKey"] = str(bk)[:255]           # 服务端 bookKey 上限 255
        sbid = int(self.store_book_id or 0)
        if sbid > 0:
            src["storeBookId"] = sbid                # 本地书为 None，不带该键
        try:
            seg_n = int(seg or 0)
        except (TypeError, ValueError):
            seg_n = 0
        if seg_n > 0:
            src["segmentNumber"] = seg_n            # 段号 1-based
        if isinstance(line_idx, int) and line_idx >= 0:
            src["lineNumber"] = int(line_idx) + 1   # 0-based 行下标 → 1-based hint
        if isinstance(line_idx, int) and 0 <= line_idx < len(self.lines):
            sentence = (self.lines[line_idx].text or "").strip()
            if sentence:
                src["sentenceText"] = sentence[:20000]   # 服务端上限 20000，客户端自行截断
        return src

    def _save_word_with_source(self, entry: WordEntry, source: dict):
        """收藏落本地 + 即时上云（参考 _add_favorite 的范式）。

        本地先入库，网络失败不阻塞（单词本里立刻能看到）；上云成功后回填 server_id，
        避免下次同步把它当成「待上传」重复上报。出处 6 键随 payload 一起发 —— 服务端按
        「最近一次带出处的上报」刷新出处；若网络失败，出处仍留在本地，由同步层补传。
        """
        lid = self.db.add_word(entry)
        if not self.config.is_authenticated:
            return
        from app.db.database import _sw_key
        sw = entry.sw or _sw_key(entry.word)

        def _sync():
            try:
                resp = self.api.add_word_book(
                    sw=sw, word=entry.word, phonetic=entry.phonetic or "",
                    translations=entry.definition or "", source=source)
                # 服务端 POST /word-book 返回 {success, word:{id,...}, message}
                w = resp.get("word") if isinstance(resp, dict) else None
                sid = w.get("id") if isinstance(w, dict) else None
                if sid and lid:
                    self.db.set_word_server_id(lid, int(sid))
            except Exception:  # noqa: BLE001
                pass  # 离线 / 老服务端：本地已入库，出处下次同步补传

        self.run_async(_sync)

    def _open_dictionary(self):
        if self.dictionary is None:
            QMessageBox.information(self, "词典", "词典服务未就绪。")
            return
        dlg = _DictionaryDialog(self.dictionary, self)
        dlg.exec()

    def _favorite_book_name(self) -> str:
        """收藏的跨端身份书名（与 Android / 服务端 bookName 对齐）。"""
        if self.book and self.book.display_name:
            return self.book.display_name
        if self.store_book_id:
            if self._store_title:
                return self._store_title
            try:
                sb = self.db.get_book_by_store_id(self.store_book_id)
                if sb and sb.display_name:
                    return sb.display_name
            except Exception:  # noqa: BLE001
                pass
            t = self._title.text() if self._title else ""
            if t and t != "书城书籍":
                return t
        return ""

    def _add_favorite(self, text: str, segment=None):
        text = (text or "").strip()
        if not text:
            return
        seg = segment or self.current_segment or 0
        try:
            seg = int(seg)
        except (TypeError, ValueError):
            seg = 0
        book_name = self._favorite_book_name()
        # 收藏身份 = (bookName, segmentNumber)，二者缺一都无法跨端识别，服务端也要求段号>=1
        if seg < 1 or not book_name:
            QMessageBox.warning(self, "收藏失败", "无法确定书名或段落位置，请在正文中重新选择。")
            return
        task_id = None
        if self.task and self.task.server_task_id:
            task_id = int(self.task.server_task_id)
        fav = Favorite(book_id=self.book_id or 0, text=text, note="",
                       book_name=book_name, segment_number=seg, task_id=task_id,
                       store_book_id=self.store_book_id or None)
        try:
            lid = self.db.add_favorite(fav)
        except Exception as e:  # noqa: BLE001
            QMessageBox.warning(self, "收藏失败", str(e))
            return
        if self.config.is_authenticated:
            def _sync():
                try:
                    resp = self.api.add_favorite(book_name, seg, text, task_id)
                    sid = None
                    if isinstance(resp, dict):
                        f = resp.get("favorite")
                        if isinstance(f, dict):
                            sid = f.get("id")
                    if sid and lid:
                        self.db.set_favorite_server_id(lid, int(sid), task_id)
                except Exception:  # noqa: BLE001
                    pass
            self.run_async(_sync)
        self.status.setText("已收藏")

    def _open_favorites(self):
        # 登录后：后台拉一次服务端收藏（跨端互通），回来再展示；未登录直接展示本地。
        if self.sync is not None and self.config.is_authenticated:
            def _pull():
                try:
                    self.sync.pull_favorites()
                except Exception:  # noqa: BLE001
                    pass
            self.run_async(_pull, on_result=lambda _r: self._show_favorites_dialog())
        else:
            self._show_favorites_dialog()

    def _show_favorites_dialog(self):
        name = self._favorite_book_name()
        favs = self.db.get_favorites_by_name(name) if name else self.db.get_favorites(0)
        if not favs:
            QMessageBox.information(self, "收藏", "本书还没有收藏内容。选中文字后可右键『收藏』。")
            return
        lines = [f"· {f.text}" for f in favs[:50]]
        QMessageBox.information(self, f"收藏（{len(favs)}）", "\n".join(lines))

    def _system_say(self, text: str):
        """系统 TTS 真声朗读：把一段临时文本交给播放引擎朗读。"""
        text = (text or "").strip()
        if not text:
            return
        pb = self.playback
        if pb is None or not hasattr(pb, "speak_temp"):
            QMessageBox.information(self, "朗读", "当前没有可用的朗读引擎。")
            return
        # ⛔ 原来优先读一个根本不存在的 ``config.speed`` 幽灵属性；
        #   ``playback_speed`` 现在由 AudioBar 接线写入并跨端同步，直接用它。
        rate = getattr(self.config, "playback_speed", 1.0) or 1.0
        try:
            started = bool(pb.speak_temp(text, rate=rate))
        except Exception:  # noqa: BLE001
            started = False
        if not started:
            QMessageBox.information(
                self, "朗读", "本机未检测到可用的系统语音引擎（朗读功能需要系统自带 TTS）。")
            return
        self._tts_restored_status = self.status.text()
        self.status.setText("正在朗读…（点击播放/停止按钮可结束）")
        self.call(self.audio_bar, "set_playing", True)
        self._tts_timer.start()

    def _on_tts_tick(self):
        """轮询系统朗读是否结束：结束后停表并还原状态栏。"""
        pb = self.playback
        speaking = bool(getattr(pb, "is_speaking", False)) if pb is not None else False
        if not speaking:
            self._tts_timer.stop()
            self.status.setText(self._tts_restored_status)
            self._tts_restored_status = ""
            if pb is None or not getattr(pb, "is_playing", False):
                self.call(self.audio_bar, "set_playing", False)

    def _stop_system_say(self):
        """结束当前系统朗读并复位 UI（供播放/停止按钮调用）。"""
        self._tts_timer.stop()
        self.call(self.playback, "stop_speak")
        self.status.setText(self._tts_restored_status)
        self._tts_restored_status = ""
        self.call(self.audio_bar, "set_playing", False)

    def _line_index_at(self, pos):
        """解析点击处对应的正文行号（与 _segment_at 的行号解析口径一致）。"""
        lw = self.line_view
        idx = None
        for name in ("line_index_at", "index_at", "lineAt"):
            cand = self.call(lw, name, pos, default=None)
            if isinstance(cand, int):
                idx = cand
                break
        if idx is None and isinstance(lw, QTextEdit):
            cur = lw.cursorForPosition(pos)
            idx = cur.blockNumber()
        return idx

    def _read_aloud_from_line(self, line_index: int):
        """无合成音频时，用系统 TTS 从指定行开始朗读正文（连续朗读该处往后的若干行）。"""
        if line_index is None or not self.lines:
            return
        chunk: List[str] = []
        for ln in self.lines[max(0, int(line_index)): max(0, int(line_index)) + 200]:
            t = (ln.text or "").strip()
            if t:
                chunk.append(t)
        if not chunk:
            QMessageBox.information(self, "朗读", "此处没有可朗读的文字。")
            return
        self.call(self.line_view, "scroll_to_line", int(line_index))
        self._system_say("\n".join(chunk))

    # ==================================================================
    # 工具栏动作
    # ==================================================================

    def _open_toc(self):
        if not self.toc:
            QMessageBox.information(self, "目录", "本书没有可用目录。")
            return
        dlg = TocDialog(self.toc, self._title.text(), self)
        dlg.entry_selected.connect(self._jump_to_paragraph)
        dlg.exec()

    def _jump_to_paragraph(self, paragraph_index: int, segment_number: int):
        # 段落 -> 首个 line 行号跳转
        idx = self._seg_first_line.get(segment_number)
        if idx is None:
            idx = self._seg_first_line.get(paragraph_index + 1)
        if idx is not None:
            self.call(self.line_view, "scroll_to_line", idx)
        if segment_number:
            self.call(self.line_view, "highlight_segment", segment_number)

    def _open_search(self):
        dlg = SearchDialog(self.lines, self)
        dlg.jumped.connect(lambda idx, seg: self._on_search_jump(idx, seg))
        dlg.exec()

    def _on_search_jump(self, line_index: int, segment_number: int):
        self.call(self.line_view, "scroll_to_line", line_index)
        if segment_number:
            self.current_segment = segment_number
            self.call(self.line_view, "highlight_segment", segment_number)

    def _open_font_size(self):
        dlg = FontSizeDialog(self.config.font_size, self.config.night_mode, self)
        dlg.size_changed.connect(self._apply_font_size)
        if dlg.exec():
            self.config.font_size = dlg.current_size()
            self.config.save()

    def _apply_font_size(self, pt: int):
        self.call(self.line_view, "set_font_size", pt)

    def _stop_session(self):
        """结束本次阅读会话：存进度、停表、停播、解探针。

        顶部工具栏没有「返回」按钮了，阅读器是被「切走」的（点左侧导航栏），
        所以收尾统一放在这里，由 hideEvent / closeEvent 调用。
        """
        self._save_progress()
        self._pb_timer.stop()
        self._tts_timer.stop()
        self._want_play = False
        self._buffering_seg = 0
        self._ensure_try = (0, 0.0, 0)
        self._disarm_cloud_probe()
        self.call(self.playback, "stop")

    # ==================================================================
    # 生命周期
    # ==================================================================

    def hideEvent(self, event):
        """阅读器被切走（点左侧导航栏换页）时收尾。

        没有「返回」按钮后，唯一的离开路径就是切页；若不在这里停播/存进度，
        音频会在别的页面继续响、进度也会丢。
        """
        self._stop_session()
        super().hideEvent(event)

    def closeEvent(self, event):
        self._stop_session()
        super().closeEvent(event)
