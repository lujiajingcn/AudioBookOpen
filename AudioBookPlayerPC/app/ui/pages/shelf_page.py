"""书架页：本地图书 + 活跃合成任务的合并列表（对标 Android mergeShelf）。"""
from __future__ import annotations

import shutil
from pathlib import Path
from typing import List, Optional, Dict

from PySide6.QtCore import Qt, Signal, QSize
from PySide6.QtGui import QFontMetrics
from PySide6.QtWidgets import (
    QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QListWidget, QListWidgetItem,
    QWidget, QFileDialog, QMessageBox, QSizePolicy, QAbstractItemView,
)

from app.models.models import Book, Task, TaskStatus, BookCategory
from app.core import coverage as _cov

from .base_page import BasePage, TaskRowWidget


_IMPORT_FILTER = "电子书 (*.txt *.md *.epub *.pdf *.mobi);;全部文件 (*.*)"


def _elide_multiline(fm: QFontMetrics, text: str, width: int, max_lines: int) -> str:
    """按字符贪心换行，最多 max_lines 行；仍超出则在最后一行末尾加省略号。"""
    text = text or ""
    if width <= 0 or max_lines <= 0:
        return text
    ell = "…"

    def wrap(t: str) -> List[str]:
        lines: List[str] = []
        cur = ""
        for ch in t:
            if ch == "\n":
                lines.append(cur)
                cur = ""
                continue
            trial = cur + ch
            if cur and fm.horizontalAdvance(trial) > width:
                lines.append(cur)
                cur = ch
            else:
                cur = trial
        lines.append(cur)
        return lines

    lines = wrap(text)
    if len(lines) <= max_lines:
        return "\n".join(lines)
    kept = lines[:max_lines - 1]
    rest = "".join(lines[max_lines - 1:])
    cur = ""
    for ch in rest:
        trial = cur + ch
        if cur and fm.horizontalAdvance(trial + ell) > width:
            break
        cur = trial
    kept.append(cur + ell)
    return "\n".join(kept)

_STATUS_TEXT = {
    TaskStatus.PENDING.value: "待处理",
    TaskStatus.QUEUED.value: "排队中",
    TaskStatus.PROCESSING.value: "合成中",
    TaskStatus.COMPLETED.value: "已完成",
    TaskStatus.PARTIAL.value: "部分完成",
    TaskStatus.FAILED.value: "失败",
    TaskStatus.PAUSED.value: "已暂停",
}


def _status_text(task: Optional[Task]) -> str:
    if task is None:
        return ""
    s = _STATUS_TEXT.get(task.status, task.status)
    if task.status in (TaskStatus.PROCESSING.value, TaskStatus.QUEUED.value, TaskStatus.PARTIAL.value):
        s = f"{s} {task.completed_segments}/{task.total_segments}"
    return s


# ==========================================================================
# 暂停/恢复按钮的可见性与文案（唯一真源）
# ==========================================================================
#
# 对齐安卓端 ``ShelfTaskActions.computeTaskActions`` 的规则表。**为什么要抽成纯函数**：
# 安卓那边是 RecyclerView 的**子 View 复用**路径（任务数不变就不重新 inflate），
# 漏写一个分支不会崩、不会闪、lint 也过，只会让**上一条任务的可见性串到这一条**；
# PC 端虽然每次 refresh 都重建卡片，但同样的规则散落在多个分支里，
# 迟早会在「加一个新状态」时漏改 —— 所以同样收敛成一个纯函数，用单测钉死。
#
# | 情形 | 按钮 | 可点 | 含义 |
# |---|---|---|---|
# | 无任务行 | 恢复 | 否 | 这本书没有合成任务 |
# | 在线任务（非本机） | 恢复 | 否 | 在线合成在服务端跑，本机挂不住它 |
# | 本机 · 合成中 · **当前会话** | 暂停 / 继续 | 是 | 挂起 / 放行本机合成（内存闸门） |
# | 本机 · 合成中 · 不是当前会话 | 恢复 | 否 | 别的书在合成，或进程被杀留下的孤儿行 |
# | 本机 · 已中止(paused) | 恢复 | 是 | 重新发起一轮，**断点续跑**（复用同一行） |
# | 本机 · 部分完成(partial) | 恢复 | 是 | 续跑，只补合成失败的段落 |
# | 本机 · 失败(failed) | 恢复 | 是 | 重新合成 |
#
# ⚠️ **在线任务不给可点的暂停按钮**：在线合成的状态在服务端，本机没有任何
# 能挂起它的开关，给它挂一个按钮点了必然没反应。安卓端同结论
# （ShelfTaskActions 的规则表注释里写了这一点）。
#
# ⚠️ **必须是「当前会话」那一行**：本机合成同一时刻只有一本书在跑，
# 而书架上可能还有别的书的**僵尸「合成中」行**（上次退出留下的）。
# 只看「服务在不在跑」会让那些僵尸行也显示成可点的「暂停」，
# 点下去暂停的是**另一本书** —— 比死按钮更糟。
#
# ⚠️ **本机「合成中」但服务没在跑时不给按钮**：进程被杀留下的孤儿
# 「合成中」行，点下去服务根本没在跑 —— 一个纯死按钮。


def compute_pause_action(
    task: Optional[Task],
    can_pause_local: bool = False,
    is_local_paused: bool = False,
    is_current_session: bool = False,
) -> tuple:
    """算出一个书架行的「暂停/恢复」按钮该长什么样。

    :param task: 该行对应的任务；None 表示这本书没有合成任务。
    :param can_pause_local: 本机合成服务此刻是否真的有会话在跑。
    :param is_local_paused: 本机合成当前是否处于暂停态。
    :param is_current_session: 这一行是不是**当前正在跑的那个会话**。
        本机合成同一时刻只有一本书在跑，别书的僵尸「合成中」行不算。
    :return: (按钮文字, 是否可点, 悬浮提示)
    """
    if task is None:
        return ("恢复", False, "这本书还没有合成任务，没有可暂停或恢复的操作")
    if not task.is_local:
        return ("恢复", False,
                "在线合成在服务器上运行，无法在本机暂停或恢复")
    if task.status == TaskStatus.PROCESSING.value:
        if not can_pause_local:
            return ("恢复", False,
                    "本机合成当前没有在运行（可能是上次退出时留下的记录），无法暂停")
        if not is_current_session:
            return ("恢复", False,
                    "本机合成正在合成另一本书，这一行无法暂停")
        if is_local_paused:
            return ("继续", True, "继续本机合成")
        return ("暂停", True, "暂停本机合成（当前这一段会先跑完）")
    if task.status in (TaskStatus.PAUSED.value, TaskStatus.PARTIAL.value,
                       TaskStatus.FAILED.value):
        return ("恢复", True, "继续合成：从上次中断的地方接着跑，已完成的段落不会重做")
    return ("恢复", False, "当前状态没有可暂停或恢复的操作")


def _ext_to_category(ext: str) -> str:
    ext = (ext or "").lstrip(".").lower()
    mapping = {
        "txt": BookCategory.LOCAL_TXT.value,
        "md": BookCategory.LOCAL_MD.value,
        "epub": BookCategory.LOCAL_EPUB.value,
        "pdf": BookCategory.LOCAL_PDF.value,
        "mobi": BookCategory.LOCAL_MOBI.value,
    }
    return mapping.get(ext, "")


class _ShelfList(QListWidget):
    """支持拖拽排序的书架列表：拖放结束后回写 sort_order。"""

    order_changed = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setViewMode(QListWidget.ListMode)
        self.setResizeMode(QListWidget.Adjust)
        self.setWrapping(True)
        self.setFlow(QListWidget.LeftToRight)
        self.setMovement(QListWidget.Static)
        self.setDragDropMode(QAbstractItemView.InternalMove)
        self.setDefaultDropAction(Qt.MoveAction)
        self.setSelectionMode(QAbstractItemView.SingleSelection)
        self.setWrapping(True)
        self.setSpacing(10)
        self.setUniformItemSizes(False)

    def dropEvent(self, event):
        super().dropEvent(event)
        # 移动完成后触发排序持久化
        self.order_changed.emit()


class ShelfRowWidget(QWidget):
    """单条书架项：封面占位 + 标题 + 作者 + 任务状态 + 操作按钮。"""

    open_clicked = Signal(int)          # book_id（本地书）
    store_open_clicked = Signal(int)    # store_book_id（云端/书城书，无本地文件）
    synthesize_clicked = Signal(int)    # book_id
    delete_clicked = Signal(int)        # book_id
    pause_clicked = Signal(int)         # book_id (暂停/继续本机合成)
    play_clicked = Signal(int)          # book_id（播放：直接起播，不要求先"阅读"）

    def __init__(self, book: Book, task: Optional[Task], parent=None,
                 coverage=None, can_pause_local: bool = False,
                 is_local_paused: bool = False, is_current_session: bool = False,
                 playable: bool = False, play_tip: str = ""):
        super().__init__(parent)
        self.book = book
        self.task = task

        # ---- 先创建按钮，用其真实 sizeHint 宽度决定卡片宽度（刚好容纳一行按钮） ----
        has_file = bool(book.file_path)
        store_id = getattr(book, "store_book_id", 0) or 0
        # 播放：**入口必须是这里**。旧卡片只有「阅读/合成/恢复/删除」，
        # 而徽标又写着「☁ 云端有声 · 可下载播放」—— 用户找不到任何可以点的「播放」，
        # 只能先进阅读页再点底部播放条，等于把主操作藏起来了（本次故障即此）。
        self.btn_play = QPushButton("播放")
        self.btn_play.setEnabled(bool(playable))
        self.btn_play.setToolTip(play_tip or ("从上次读到的位置开始播放" if playable
                                              else "这本书还没有可播放的音频"))
        self.btn_play.clicked.connect(lambda: self.play_clicked.emit(self.book.id))
        # 打开方式：书城/云端书（store_book_id > 0）一律走 store 正文接口，本机文件只作离线兜底；
        # 否则「已合成音频但本机未下载」的书从书架点开会走本地路径、丢 store_book_id，
        # 播放时拿不到音频段元数据 → 只会提示「未合成」，无法边下载边播放。
        self.btn_open = QPushButton("阅读")
        self.btn_open.setEnabled(has_file or store_id > 0)
        if store_id > 0:
            self.btn_open.clicked.connect(
                lambda: self.store_open_clicked.emit(store_id))
        else:
            self.btn_open.clicked.connect(lambda: self.open_clicked.emit(self.book.id))
        self.btn_tts = QPushButton("合成")
        self.btn_tts.clicked.connect(lambda: self.synthesize_clicked.emit(self.book.id))
        # 暂停/恢复按钮：文案与可点性由 compute_pause_action 统一判定。
        # ⚠️ 不可点时**保留按钮但置灰 + 挂悬浮提示**：整行只有「合成/阅读/删除」时
        # 直接少一个按钮会让卡片宽度在刷新时跳动（Qt 会重排整列），
        # 而置灰 + 提示能让用户知道「为什么这里不能操作」。
        label, enabled, tip = compute_pause_action(
            task, can_pause_local=can_pause_local, is_local_paused=is_local_paused,
            is_current_session=is_current_session)
        self.btn_pause = QPushButton(label)
        self.btn_pause.setEnabled(enabled)
        self.btn_pause.setToolTip(tip)
        self.btn_pause.clicked.connect(lambda: self.pause_clicked.emit(self.book.id))
        self.btn_del = QPushButton("删除")
        self.btn_del.clicked.connect(lambda: self.delete_clicked.emit(self.book.id))
        # 紧凑内边距；删除按钮保留红色文字
        for b in (self.btn_play, self.btn_open, self.btn_tts, self.btn_pause, self.btn_del):
            b.setStyleSheet("QPushButton{padding:5px 6px;}")
            b.ensurePolished()
        self.btn_del.setStyleSheet(
            "QPushButton{padding:5px 6px;color:#c0392b;}"
            "QPushButton:disabled{color:#a8b0bd;}")
        self.btn_del.ensurePolished()

        BTN_GAP = 2          # 按钮间距（已缩小60%）
        MARGIN = 8           # 卡片左右内边距
        SIDE_PAD = 8         # 按钮组左右对称留白：放大右边界容纳"删除"，并保证左右间隙相等
        _btns = (self.btn_play, self.btn_open, self.btn_tts, self.btn_pause, self.btn_del)
        btn_row_w = sum(max(b.sizeHint().width(), b.minimumSizeHint().width()) for b in _btns) \
            + BTN_GAP * (len(_btns) - 1)
        card_w = btn_row_w + 2 * (MARGIN + SIDE_PAD)
        self.setFixedWidth(card_w)
        # 信息列可用宽 = 卡片宽 - 左右margin - 封面64 - body间距8
        info_w = max(card_w - 2 * MARGIN - 64 - 8, 24)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(MARGIN, MARGIN, MARGIN, MARGIN)
        lay.setSpacing(6)

        body = QHBoxLayout()
        body.setSpacing(8)
        # 封面占位
        cover = QLabel()
        cover.setFixedSize(64, 84)
        cover.setAlignment(Qt.AlignCenter)
        cover.setStyleSheet("background:#dcdcdc;border:1px solid #bbb;border-radius:4px;color:#666;")
        cover.setText((book.display_name or "?")[:2])
        body.addWidget(cover)

        info = QVBoxLayout()
        # 书名：按信息列宽贪心换行，最多两行，仍放不下则末行加省略号
        title = QLabel()
        f = title.font(); f.setBold(True); title.setFont(f)
        title.setWordWrap(False)
        full_name = book.display_name or "(未命名)"
        disp_title = _elide_multiline(QFontMetrics(title.font()), full_name, info_w, 2)
        title.setText(disp_title)
        # 仅当书名被省略时，鼠标悬停浮显完整书名（移开自动消失）
        if disp_title.endswith("…"):
            title.setToolTip(full_name)
        info.addWidget(title)
        # 作者：单行，超出用省略号
        author = QLabel()
        author.setStyleSheet("color:#888;")
        a_text = book.author or ""
        if a_text:
            author.setText(QFontMetrics(author.font()).elidedText(
                a_text, Qt.ElideRight, info_w))
        info.addWidget(author)
        status = task and (_status_text(task)) or ("有声" if book.has_audio else "未合成")
        st = QLabel(status)
        st.setStyleSheet("color:#2c6e49;")
        st.setWordWrap(True)
        info.addWidget(st)
        # Phase3 跨端有声覆盖图徽标（诚实标注来源，绝不谎报本机可播）
        if coverage:
            cov_text, cov_color = coverage
            cv = QLabel(cov_text)
            cv.setStyleSheet(f"color:{cov_color};")
            cv.setWordWrap(True)
            info.addWidget(cv)
        body.addLayout(info, 1)
        lay.addLayout(body)

        # 按钮行：左右各加等比 stretch 居中，保证左间隙(阅读↔左边界)==右间隙(删除↔右边界)
        btns = QHBoxLayout()
        btns.setSpacing(BTN_GAP)
        btns.addStretch(1)
        for b in _btns:
            btns.addWidget(b)
        btns.addStretch(1)
        lay.addLayout(btns)

        # 保证卡片有足够高度，避免按钮行上移遮挡封面
        self.setMinimumHeight(150)


class ShelfPage(BasePage):
    """书架页。

    信号:
        book_opened(int book_id)
        store_book_opened(int store_book_id)
        book_play_requested(int book_id)            —— 书架直接起播（本地书）
        store_book_play_requested(int store_book_id) —— 书架直接起播（书城书）
        synthesize_requested(int book_id)
        shelf_shown()             —— 主窗口切到本页时触发一次（节流的）跨端同步
        manual_sync_requested()   —— 用户点「同步」按钮，要求立即同步一次
        login_requested()         —— 用户在横幅上点「重新登录」
    """

    book_opened = Signal(int)
    store_book_opened = Signal(int)
    book_play_requested = Signal(int)
    store_book_play_requested = Signal(int)
    synthesize_requested = Signal(int)
    shelf_shown = Signal()
    manual_sync_requested = Signal()
    login_requested = Signal()

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.setWindowTitle("书架")

        root = QVBoxLayout(self)
        root.setContentsMargins(12, 12, 12, 12)

        head = QHBoxLayout()
        title = QLabel("我的书架")
        tf = title.font(); tf.setPointSize(tf.pointSize() + 4); tf.setBold(True)
        title.setFont(tf)
        head.addWidget(title)
        head.addStretch(1)
        # 「同步」按钮：让用户能主动拉一次云端书架。
        # 为什么需要：同步是**自动**触发的（切页时节流 45s），用户新加了一本书
        # 却看不到时没有任何办法自己重试 ⇒ 只能干等或重启应用。
        self.btn_sync = QPushButton("同步")
        self.btn_sync.setToolTip("立即从云端拉取书架 / 收藏 / 进度")
        self.btn_sync.clicked.connect(self.manual_sync_requested.emit)
        head.addWidget(self.btn_sync)
        self.btn_import = QPushButton("导入书籍")
        self.btn_import.clicked.connect(self._import_book)
        head.addWidget(self.btn_import)
        root.addLayout(head)

        # ⛔ 未登录横幅：**云端书架是登录态专属能力**。
        # 未登录时 `SyncManager.is_available()` 为 False ⇒ 同步一次都不发，
        # 界面却和登录时长得一模一样 ⇒ 用户只能看到"手机端加的书没出现"，
        # 根本想不到是自己掉登录了。这条横幅把因果直接摆出来。
        self.banner = QWidget()
        bl = QHBoxLayout(self.banner)
        bl.setContentsMargins(0, 0, 0, 0)
        self.banner_lbl = QLabel()
        self.banner_lbl.setWordWrap(True)
        self.banner_lbl.setStyleSheet("color:#b7791f;")
        bl.addWidget(self.banner_lbl, 1)
        self.btn_relogin = QPushButton("重新登录")
        self.btn_relogin.clicked.connect(self.login_requested.emit)
        bl.addWidget(self.btn_relogin, 0, Qt.AlignRight)
        self.banner.setVisible(False)
        root.addWidget(self.banner)

        self.list = _ShelfList(self)
        self.list.order_changed.connect(self._persist_order)
        root.addWidget(self.list, 1)

        self.hint = QLabel("提示：拖拽卡片可调整顺序")
        self.hint.setStyleSheet("color:#999;")
        root.addWidget(self.hint)

        # book_id -> (row_widget) 便于回调
        self._rows: Dict[int, ShelfRowWidget] = {}
        # 本机确实持有音频的 bookKey -> 已下载段数（refresh 时重算）
        # ⚠ 从 set 改成 dict：光知道「有没有」不够 —— 只有 33/119 段时若照旧说
        #   「可直接播放」，用户播到第 34 段就没声了，比不显示更糟。
        self._local_cov: Dict[str, int] = {}

    # ---------- 数据合并（mergeShelf） ----------

    def _merged_entries(self) -> List[tuple]:
        """返回 (Book, latest Task|None) 列表，任务按 book_name 关联到书名。"""
        books = self.db.get_books()
        tasks = self.db.get_tasks()
        tasks_by_name: Dict[str, List[Task]] = {}
        for t in tasks:
            tasks_by_name.setdefault(t.book_name, []).append(t)

        entries: List[tuple] = []
        matched = set()
        for b in books:
            matched.add(b.display_name)
            ts = tasks_by_name.get(b.display_name) or []
            entries.append((b, ts[0] if ts else None))

        # 仅有任务、无电子书记录：补虚拟条目（book_id=0）
        for name, ts in tasks_by_name.items():
            if name in matched:
                continue
            vb = Book(id=0, display_name=name, file_path="",
                      has_audio=any(t.is_terminal and t.status == TaskStatus.COMPLETED.value for t in ts))
            entries.append((vb, ts[0]))
        return entries

    def refresh(self):
        """重建列表。showEvent 中亦会调用。"""
        self.list.clear()
        self._rows.clear()
        self._update_banner()
        # 重算本机持有音频的 bookKey（→ 已下载段数），覆盖图徽章据此判定「本机可播」
        try:
            self._local_cov = {c["bookKey"]: int(c.get("doneCount") or 0)
                               for c in self.db.local_audio_coverage()}
        except Exception:  # noqa: BLE001
            self._local_cov = {}
        entries = self._merged_entries()
        if not entries:
            it = QListWidgetItem("书架空空如也，点击右上角『导入书籍』添加电子书")
            self.list.addItem(it)
            return
        for book, task in entries:
            row = self._make_row(book, task)
            item = QListWidgetItem(self.list)
            item.setSizeHint(QSize(row.minimumWidth(), max(row.sizeHint().height(), 160)))
            self.list.addItem(item)
            self.list.setItemWidget(item, row)
            if book.id:
                self._rows[book.id] = row

    # ---------- 同步状态横幅 ----------

    def _update_banner(self):
        """按登录态显示/隐藏「云端不同步」提示条。

        为什么必须有这条横幅：
        **云端书架只在登录态才有**。未登录时 ``SyncManager.is_available()`` 为 False，
        ``_run_sync_async`` 在入口就 return —— 一次网络请求都不发。
        于是手机端新加的书在 PC 端"永远不出现"，而界面和登录时**长得一模一样**，
        用户无从判断是自己掉登录了还是服务端有问题（本次线上故障即是如此）。

        这里把因果直接写出来，并给出「重新登录」按钮。
        """
        authed = bool(self.config.is_authenticated)
        self.banner.setVisible(not authed)
        if not authed:
            self.banner_lbl.setText(
                "当前未登录：云端书架（手机端添加的书）不会同步到此。"
                "请重新登录以恢复跨端同步。")
        # 未登录时「同步」按钮也没意义（同步在未登录时直接跳过）⇒ 置灰
        self.btn_sync.setEnabled(authed)

    def auth_changed(self, is_auth: bool, username):
        """登录态变化 ⇒ 横幅与「同步」按钮可用性要跟着变。"""
        try:
            self._update_banner()
        except Exception:  # noqa: BLE001
            pass

    def _make_row(self, book: Book, task: Optional[Task]) -> QWidget:
        if TaskRowWidget is not None and task is not None:
            # 优先复用真实 TaskRowWidget 展示任务部分（若其接口匹配）
            try:
                pass  # 组合控件较复杂，此处统一用 ShelfRowWidget 呈现，保留扩展点
            except Exception:  # noqa: BLE001
                pass
        badge, playable, play_tip = self._coverage_info(book, task)
        # 「虚拟条目」（只有 tasks 行、没有 books 行）没有可打开的正文身份：
        # 播放会以 book_id=0 去打开发阅读页，_load_local(0) 直接报「本地书籍不存在」。
        # 覆盖图是按书名/任务算的，可能判成「可播」，所以这里统一兜一道闸。
        if (int(getattr(book, "id", 0) or 0) <= 0
                and int(getattr(book, "store_book_id", 0) or 0) <= 0):
            playable = False
            play_tip = "这条记录只有云端合成任务、没有原书：请先导入电子书再播放"
        # 本机合成的会话态由**服务**持有（唯一事实来源）：合成页与书架页读同一份。
        can_pause = bool(self.call(self.local_tts, "is_synthesis_busy", default=False))
        is_paused = bool(self.call(self.local_tts, "is_synthesis_paused", default=False))
        cur_task_id = int(self.call(self.local_tts, "current_synthesis_task_id",
                                    default=0) or 0)
        # 「当前会话」= 服务正在合成的**那一行**。
        # 本机合成同一时刻只有一本书在跑，书架上别的书的僵尸「合成中」行
        # 不该也显示成可点的「暂停」—— 点下去暂停的是另一本书。
        is_current = bool(task is not None and cur_task_id and task.id == cur_task_id)
        row = ShelfRowWidget(book, task, self, coverage=badge,
                              can_pause_local=can_pause,
                              is_local_paused=is_paused,
                              is_current_session=is_current,
                              playable=playable, play_tip=play_tip)
        row.open_clicked.connect(self.book_opened.emit)
        row.store_open_clicked.connect(self.store_book_opened.emit)
        row.play_clicked.connect(lambda _bid, r=row: self._on_play_row(r))
        row.synthesize_clicked.connect(self.synthesize_requested.emit)
        row.delete_clicked.connect(lambda _bid, r=row: self._delete_book(r))
        row.pause_clicked.connect(self._toggle_pause)
        return row

    def _on_play_row(self, row):
        """书架「播放」：书城书走 store 身份（正文/音频段都在服务端），否则按本地书。"""
        book = getattr(row, "book", None)
        if book is None:
            return
        store_id = int(getattr(book, "store_book_id", 0) or 0)
        if store_id > 0:
            self.store_book_play_requested.emit(store_id)
        else:
            self.book_play_requested.emit(int(book.id))

    def _coverage_info(self, book: Book, task: Optional[Task]):
        """Phase3 跨端有声覆盖图徽章 + 「能不能播」。

        身份键 bookKey 与上报口径一致（s:/t:/n:）。四档诚实标注：
          - 本机全有 → 可直接播放；
          - 本机只有一部分（如 33/119）→ 标出已下段数，其余边下载边播；
          - 只有 server 源覆盖 → 边下载边播（走按需下载）；
          - 只有别的设备的 local 源 → 只提示，**不宣称本机可播**。
        :return: (badge 或 None, 是否可播, 按钮提示)
        """
        playable_from_task = bool(
            task is not None and int(getattr(task, "server_task_id", 0) or 0) > 0
            and task.status == TaskStatus.COMPLETED.value)
        store_id = int(getattr(book, "store_book_id", 0) or 0)
        try:
            bk = _cov.book_key(
                store_id,
                (getattr(task, "server_task_id", 0) or 0) if task else 0,
                (book.display_name or (task.book_name if task else "")),
            )
            rows = self.db.get_audio_coverage_for_book(bk) if bk else []
            local_n = int(self._local_cov.get(bk, 0) or 0) if bk else 0
            if bk and (rows or local_n):
                def _row_of(source):
                    for r in rows:
                        if r.get("source") == source and int(r.get("done_count", 0) or 0) > 0:
                            return r
                    return None

                srv = _row_of("server")
                loc = _row_of("local")
                srv_done = int(srv.get("done_count", 0) or 0) if srv else 0
                srv_total = int(srv.get("total_segments", 0) or 0) if srv else 0

                # 1) 本机确有音频段
                if local_n > 0:
                    # 「全有」：服务端知道总数就比总数，否则比服务端已完成数
                    full = local_n >= (srv_total or srv_done or local_n)
                    if full:
                        return (("✔ 本机有声 · 可直接播放", "#2c6e49"),
                                True, "直接播放本机已下载的音频")
                    frac = (f"（{local_n}/{srv_total}）" if srv_total
                            else f"（已下 {local_n} 段）")
                    return ((f"◐ 本机部分有声{frac} · 边下边播", "#2b6cb0"),
                            True, "本机只有部分音频，其余边下载边播放")
                # 2) 服务端有（任意设备在线合成的段，可经现有下载/流式接口播放）
                if srv:
                    frac = (f"（{srv_done}/{srv_total}）" if srv_total
                            else (f"（{srv_done} 段）" if srv_done else ""))
                    return ((f"☁ 云端有声 · 可下载播放{frac}", "#2b6cb0"),
                            True, "云端有音频：点播放边下载边播放")
                # 3) 仅别的设备的本地合成过 → 提示，不谎报本机可播
                if loc:
                    tot = int(loc.get("total_segments", 0) or 0)
                    dc = int(loc.get("done_count", 0) or 0)
                    frac = f"（{dc}/{tot}）" if tot > 0 else (f"（{dc} 段）" if dc > 0 else "")
                    return ((f"◍ 另一设备已合成 · 本机待合成{frac}", "#b7791f"),
                            False, "音频在另一台设备上，本机服务端没有字节，需要重新合成")
        except Exception:  # noqa: BLE001
            pass
        # 覆盖图还没同步过来（未登录/没同步过）时的兜底判定
        if store_id > 0 and book.has_audio:
            return (None, True, "云端有音频：点播放边下载边播放")
        if playable_from_task:
            return (None, True, "云端有音频：点播放边下载边播放")
        return (None, False, "还没有可播放的音频，先点「合成」")

    # ---------- 操作 ----------

    def _import_book(self):
        path, _ = QFileDialog.getOpenFileName(self, "选择电子书", "", _IMPORT_FILTER)
        if not path:
            return
        src = Path(path)
        ext = src.suffix.lower()
        dst_dir = self.config.books_dir
        dst = dst_dir / src.name
        try:
            if src.resolve() != dst.resolve():
                shutil.copy2(str(src), str(dst))
        except Exception as e:  # noqa: BLE001
            QMessageBox.warning(self, "导入失败", f"复制文件出错：{e}")
            return
        book = Book(display_name=src.stem, file_path=str(dst),
                    file_ext=ext.lstrip("."), category=_ext_to_category(ext),
                    sort_order=len(self.db.get_books()) + 1, has_audio=False)
        try:
            new_id = self.db.add_book(book)
            book.id = new_id
        except Exception as e:  # noqa: BLE001
            QMessageBox.warning(self, "导入失败", f"写入数据库出错：{e}")
            return
        # 注意：书籍已经通过 db.add_book 落库；不再调用 book_service.import_book
        # （其签名为 import_book(file_path)，传 Book 对象会 TypeError 被静默吞掉；
        #  若改传 file_path 反而会重复解析+拷贝+再插一条 Book，造成书架重复项。）
        self.refresh()
        QMessageBox.information(self, "导入成功", f"已添加《{book.display_name}》")

    def _purge_book_and_tasks(self, book: Book, task: Optional[Task]):
        """删掉一本书在书架上的**全部**痕迹：books 行 + 挂在它上面的 tasks 行。

        **为什么必须连 tasks 一起删**：书架列表是 books ∪ 虚拟条目的合并
        （见 :meth:`_merged_entries`）——任务按 `book_name` 关联到书，
        凡是「只有任务、没有书行」的任务都会**补成一条虚拟条目**显示出来。
        若只删 books 行，剩下的 task 就自动变成虚拟条目，
        **书名照旧显示在书架上** ⇒ 用户点了删除却「没反应」。

        ⛔ 只删**本书名**对应的任务：不同名的任务属于别的书，不能连带删除。
        ⛔ 云端任务额外落墓碑（dismissal），否则下次同步会把它拉回来。

        :return: 被删掉的任务条数
        """
        name = book.display_name
        removed = 0
        for t in self.db.get_tasks():
            if t.book_name != name:
                continue
            self.db.delete_task(t.id)
            if t.server_task_id:
                # 墓碑必须带书名：tasks 行已被删掉，之后只能靠书名找回它
                self.db.dismiss_task(t.server_task_id, name)
            removed += 1
        self.db.remove_book(book.id)
        return removed

    def _delete_book(self, row):
        """从书架删除。兼容三种条目：本地书(有 id)、书城/云端书、仅任务无书(虚拟 id=0)。

        旧实现只把 book_id 传进来，对「仅任务、无本地书」的虚拟条目会退化成 `#{0}`
        且 `remove_book(0)` 什么也没删——这里改为拿整行(含书名与任务)处理。

        ⛔ 三条分支都**必须把该书的 tasks 行一并清掉**，否则任务会退化成
        虚拟条目继续显示在书架上 ⇒ 用户看到「删除没反应」。详见
        :meth:`_purge_book_and_tasks`。
        """
        book = getattr(row, "book", None)
        task = getattr(row, "task", None)
        if book is None:
            return
        name = (book.display_name or (task.book_name if task else "") or "").strip() \
            or f"#{book.id}"

        if book.id:
            store_id = int(getattr(book, "store_book_id", 0) or 0)
            if store_id > 0:
                # 书城来源的书：移出书架是"跨设备"语义——本机删 DB 行(不动磁盘)并调服务端
                # DELETE /shelf/{id}，使其它设备下次同步也把它移除。
                if QMessageBox.question(
                        self, "移出书架",
                        f"确定将《{name}》移出书架？\n所有设备都将不再显示该书"
                        f"（不会删除已下载的磁盘文件）。") != QMessageBox.Yes:
                    return

                def _remove_remote():
                    try:
                        return self.api.remove_from_shelf(store_id)
                    except Exception as e:  # noqa: BLE001
                        return {"error": str(e)}

                # 先落墓碑再删本地：墓碑必须在 DELETE **发出之前**写好，
                # 否则并发的同步可能用「删除前的快照」把这本书 upsert 回来
                # ⇒ 用户看到「点了删除没反应」（书闪一下又回来）。
                try:
                    self.db.dismiss_store_book(store_id)
                except Exception:  # noqa: BLE001
                    pass

                # 先删本地即时消失；服务端 best-effort，失败则提示（下次同步可能拉回）
                self._purge_book_and_tasks(book, task)
                self.refresh()

                def _after(res_obj):
                    if isinstance(res_obj, dict) and res_obj.get("error"):
                        # 云端移出失败：撤掉墓碑，否则这本书在本机永远回不来
                        # （用户可能已在别处重新加入）。
                        try:
                            self.db.clear_store_book_dismissal(store_id)
                        except Exception:  # noqa: BLE001
                            pass
                        QMessageBox.warning(
                            self, "移出未同步",
                            f"《{name}》已从本机移除，但云端移出失败：{res_obj['error']}\n"
                            f"下次同步可能被重新拉回，请检查网络后重试。")

                self.run_async(_remove_remote, on_result=_after)
                return

            # 纯本地导入的书：只从书架移除记录，不动磁盘文件
            if QMessageBox.question(
                    self, "删除确认",
                    f"确定从书架删除《{name}》？（不会删除磁盘文件）") != QMessageBox.Yes:
                return
            self._purge_book_and_tasks(book, task)
            self.refresh()
            return

        # 虚拟条目：仅合成任务、无本地书行
        if task is not None:
            is_cloud = bool(task.server_task_id)
            # 云端任务：非破坏式「从本机书架移除」——只删本地任务行并落下墓碑(dismissal)，
            # 让后续同步不再把它拉回；绝不删云端音频/任务，其它设备与云端成品音频不受影响。
            tip = (f"确定从本机书架移除《{name}》？\n该条目是云端合成任务，"
                   f"仅从本机书架隐藏（不影响云端音频，其它设备仍可访问）。") if is_cloud else \
                  (f"确定从书架删除《{name}》？\n该条目是本机合成任务，将移除任务记录"
                   f"（不会删除已下载音频文件）。")
            if QMessageBox.question(self, "删除确认", tip) != QMessageBox.Yes:
                return
            # 删除本地任务行（连同其 audio_segments），云端任务额外写墓碑表阻止同步再拉回
            self.db.delete_task(task.id)
            if is_cloud:
                # 墓碑带上书名：本地 tasks 行已删，之后靠它才能在「重新加入」时清掉
                self.db.dismiss_task(task.server_task_id, task.book_name or name)
            self.refresh()
            return

        # 兜底：既无本地书 id 也无任务，无可删对象
        QMessageBox.information(self, "删除", f"《{name}》没有可删除的本地记录。")

    def _toggle_pause(self, book_id: int):
        """暂停 / 继续 / 续跑本机合成。

        ⛔ **只对本机合成**成立：在线合成的状态在服务端，本机没有任何能挂起它的
        开关，给它挂一个按钮点了必然没反应（对齐安卓 ShelfTaskActions 的规则表）。

        这里有**两种不同的语义**，必须分开：
          1. 合成**进行中** ⇒ 内存闸门式暂停/继续：挂起合成线程、任务状态不变
             （只写 progress_text=已暂停），点「继续」原线程接着跑。
          2. 合成**已结束**（paused/partial/failed）⇒ 续跑：重新发起一轮，
             复用同一行任务，已完成的段落不重做（对齐安卓 continueLocalSynthesis）。
        """
        entries = {b.id: t for b, t in self._merged_entries()}
        task = entries.get(book_id)
        if task is None:
            return
        if not task.is_local:
            # 按钮本该是灰的（compute_pause_action 已判），走到这里说明状态变了。
            self.log("在线合成无法在本机暂停或恢复")
            return

        if task.status == TaskStatus.PROCESSING.value:
            # ---- 情形 1：内存闸门式暂停 / 继续 ----
            # 动作按**服务的真实状态**决定，而不是按按钮上的文案 ——
            # 文案与真实状态之间可能隔着一次写库/回传的延迟，按文案走会连点两次
            # 变成「暂停了两次」（第二次用户其实想继续）。（对齐安卓 BookListFragment）
            paused = bool(self.call(self.local_tts, "is_synthesis_paused", default=False))
            if paused:
                ok = bool(self.call(self.local_tts, "resume_synthesis", default=False))
                self.log("已恢复合成" if ok else "当前没有进行中的本机合成")
            else:
                ok = bool(self.call(self.local_tts, "pause_synthesis", default=False))
                self.log("将在当前段合成结束后暂停" if ok
                         else "当前没有进行中的本机合成")
            self.refresh()
            return

        # ---- 情形 2：续跑（重新发起一轮，断点续跑） ----
        # 交给合成页去跑：它会复用同一行任务并跳过已完成的段。
        self.synthesize_requested.emit(book_id)

    def _persist_order(self):
        """拖拽后按从上到下、从左到右的顺序回写 sort_order（仅本地书）。"""
        order = 0
        for i in range(self.list.count()):
            item = self.list.item(i)
            w = self.list.itemWidget(item)
            if isinstance(w, ShelfRowWidget) and w.book.id:
                order += 1
                self.db.update_book_sort(w.book.id, order)

    # ---------- 生命周期 ----------

    def on_show(self):
        """主窗口切换到书架时回调：触发一次（节流的）跨端同步。"""
        self.shelf_shown.emit()

    def showEvent(self, event):
        super().showEvent(event)
        self.refresh()
