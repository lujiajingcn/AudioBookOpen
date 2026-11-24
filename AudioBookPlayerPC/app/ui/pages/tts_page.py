"""TTS 合成页：为选定书籍配置并发起在线 / 本机合成，展示分段进度。

在线合成：优先 OnlineTtsService.create_task；缺失时回落 ApiClient 上传，并用 SseClient
实时监听进度（SSE 回调来自后台线程，经 Qt 信号编组回主线程）。
本机合成：在后台线程调用 LocalTtsService.synthesize_book，连接其进度/日志信号。
"""
from __future__ import annotations

import os
import threading
from pathlib import Path
from typing import List, Optional

from PySide6.QtCore import Qt, Signal, QObject
from PySide6.QtWidgets import (
    QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QComboBox, QRadioButton,
    QButtonGroup, QProgressBar, QPlainTextEdit, QWidget, QFileDialog,
    QMessageBox, QGroupBox, QLineEdit, QDialog,
)

from app.core.sse import SseClient
from app.core.moss_module import MossModuleCancelled
from app.models.models import Book, Voice, ReferenceAudio

from .base_page import BasePage


class _SseBridge(QObject):
    progress = Signal(int, int, str)
    complete = Signal(int)
    error = Signal(str)


class _LocalBridge(QObject):
    """把 LocalTtsService 后台线程的进度/日志/结果编组回主线程。"""
    progress = Signal(int, int, str)   # completed, total, current_text
    log = Signal(str)
    finished = Signal(object)          # SynthesisResult
    errored = Signal(str)


class _ModuleBridge(QObject):
    """把 MOSS 模块下载后台线程的 进度/阶段/结果 编组回主线程（信号跨线程安全）。"""
    progress = Signal(int, int, int)   # downloaded_bytes, total_bytes, bytes_per_second
    stage = Signal(str)                # 阶段文案：开始下载 / 校验 / 解压 / 安装 …
    finished = Signal(object)          # ensure_module 返回的 info dict
    cancelled = Signal(str)            # 用户取消（保留断点）
    errored = Signal(str)


class TtsPage(BasePage):
    """TTS 合成页。

    信号:
        task_started(int task_id)
        task_finished(int task_id, bool success)
    """

    task_started = Signal(int)
    task_finished = Signal(int, bool)
    # 本机合成的暂停态变化（暂停/继续/开始/结束）。
    # 书架上的「暂停/继续」按钮要跟着变 —— 用户在合成页点了暂停，
    # 退到书架看到的按钮若还是「暂停」，再点一下就成了「暂停两次」。
    # （对齐安卓 BookListFragment 订阅 LocalSynthesisService.paused 后重绑）
    synthesis_pause_changed = Signal()

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._book: Optional[Book] = None
        self._voices: List[Voice] = []
        self._refs: List[ReferenceAudio] = []
        self._server_task_id: int = 0
        self._sse: Optional[SseClient] = None
        self._bridge: Optional[_SseBridge] = None
        # 本机合成状态
        self._local_task_id: int = 0
        self._local_bridge: Optional[_LocalBridge] = None
        self._local_stop: Optional[threading.Event] = None
        # 模块下载状态
        self._module_bridge: Optional[_ModuleBridge] = None
        self._module_dialog: Optional[QDialog] = None
        self._pending_after_module = None   # 模块就绪后要接着做的事

        self._build_ui()

    # ==================================================================
    # UI
    # ==================================================================

    def _build_ui(self):
        root = QVBoxLayout(self)
        root.setContentsMargins(16, 16, 16, 16)
        root.setSpacing(10)

        title = QLabel("音频合成")
        tf = title.font(); tf.setPointSize(tf.pointSize() + 4); tf.setBold(True); title.setFont(tf)
        root.addWidget(title)

        self.book_lbl = QLabel("未选择书籍")
        self.book_lbl.setStyleSheet("color:#444;")
        root.addWidget(self.book_lbl)

        # 合成方式
        mode_box = QGroupBox("合成方式")
        mode_lay = QHBoxLayout(mode_box)
        self.rb_online = QRadioButton("在线合成（服务器）")
        self.rb_local = QRadioButton("本机合成（MOSS 引擎）")
        self.rb_online.setChecked(True)
        self.mode_group = QButtonGroup(self)
        self.mode_group.addButton(self.rb_online)
        self.mode_group.addButton(self.rb_local)
        self.rb_online.toggled.connect(self._on_mode_changed)
        mode_lay.addWidget(self.rb_online)
        mode_lay.addWidget(self.rb_local)
        mode_lay.addStretch(1)
        root.addWidget(mode_box)

        # 音色
        voice_box = QGroupBox("音色")
        vlay = QHBoxLayout(voice_box)
        vlay.addWidget(QLabel("音色："))
        self.voice_combo = QComboBox()
        vlay.addWidget(self.voice_combo, 1)
        self.btn_refresh_voices = QPushButton("刷新")
        self.btn_refresh_voices.clicked.connect(self._load_voices)
        vlay.addWidget(self.btn_refresh_voices)
        root.addWidget(voice_box)

        # 参考音频
        ref_box = QGroupBox("参考音频")
        rlay = QHBoxLayout(ref_box)
        rlay.addWidget(QLabel("来源："))
        self.ref_combo = QComboBox()
        self.ref_combo.addItem("（不使用参考音频）", None)
        rlay.addWidget(self.ref_combo, 1)
        self.btn_upload_ref = QPushButton("上传新参考音频")
        self.btn_upload_ref.clicked.connect(self._upload_reference)
        rlay.addWidget(self.btn_upload_ref)
        root.addWidget(ref_box)

        # 进度
        self.progress = QProgressBar()
        self.progress.setRange(0, 100)
        self.progress.setValue(0)
        root.addWidget(self.progress)
        self.seg_lbl = QLabel("段数：0 / 0")
        root.addWidget(self.seg_lbl)

        # 日志
        root.addWidget(QLabel("合成日志："))
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setMaximumBlockCount(2000)
        root.addWidget(self.log, 1)

        # 控制
        ctl = QHBoxLayout()
        self.btn_start = QPushButton("开始合成")
        self.btn_start.clicked.connect(self._start)
        # 暂停/继续：只对本机合成有意义（在线合成的状态在服务端，本机挂不住它）。
        # 与安卓 TtsFragment 的 btnPauseSynthesis 同位置、同语义。
        self.btn_pause = QPushButton("暂停合成")
        self.btn_pause.clicked.connect(self._toggle_pause)
        self.btn_pause.setEnabled(False)
        self.btn_stop = QPushButton("停止")
        self.btn_stop.clicked.connect(self._stop)
        self.btn_stop.setEnabled(False)
        ctl.addWidget(self.btn_start)
        ctl.addWidget(self.btn_pause)
        ctl.addWidget(self.btn_stop)
        ctl.addStretch(1)
        root.addLayout(ctl)

    # ==================================================================
    # 选书 / 模式
    # ==================================================================

    def set_book(self, book: Book):
        """由 MainWindow 在进入本页时调用。"""
        self._book = book
        name = book.display_name if book else "未选择书籍"
        extra = f"（书城书 #{book.store_book_id}）" if (book and book.store_book_id) else ""
        self.book_lbl.setText(f"当前书籍：{name}{extra}")
        self._log(f"已选择《{name}》")
        self._load_voices()
        self._load_references()

    def _on_mode_changed(self, _checked: bool):
        self._load_voices()

    # ==================================================================
    # 音色 / 参考音频
    # ==================================================================

    def _load_voices(self):
        self.voice_combo.clear()
        if self.rb_local.isChecked():
            names = self.call(self.local_tts, "builtin_voices", default=None) or \
                    self.call(self.local_tts, "list_voices", default=None)
            if isinstance(names, (list, tuple)):
                for n in names:
                    disp = n if isinstance(n, str) else getattr(n, "display_name", str(n))
                    self.voice_combo.addItem(disp, disp)
            if self.voice_combo.count() == 0:
                for d in ("Junhao", "Xiaoxiao", "default"):
                    self.voice_combo.addItem(d + "（内置）", d)
            self.voice_combo.setEnabled(True)
            self.ref_combo.setEnabled(True)
        else:
            def _fetch():
                return self.api.get_voices()
            self.run_async(_fetch, on_result=self._on_voices,
                           on_error=lambda m: self._log(f"音色加载错误：{m}"))

    def _on_voices(self, voices):
        self._voices = list(voices or [])
        self.voice_combo.clear()
        self.voice_combo.addItem("（服务器默认音色）", "")
        for v in self._voices:
            self.voice_combo.addItem(f"{v.display_name} ({v.id})", v.id)
        self.voice_combo.setEnabled(True)

    def _load_references(self):
        self.ref_combo.clear()
        self.ref_combo.addItem("（不使用参考音频）", None)
        if not self.config.is_authenticated:
            return

        def _fetch():
            refs = self.api.get_references()
            out = []
            for r in refs:
                if isinstance(r, dict):
                    out.append((r.get("id"), r.get("name") or r.get("fileName") or "参考音频"))
            return out
        self.run_async(_fetch, on_result=self._on_references,
                       on_error=lambda m: self._log(f"加载参考音频失败：{m}"))

    def _on_references(self, refs):
        for rid, name in (refs or []):
            self.ref_combo.addItem(name, rid)

    def _upload_reference(self):
        if not self.config.is_authenticated:
            QMessageBox.information(self, "需要登录", "上传参考音频需先登录。")
            return
        path, _ = QFileDialog.getOpenFileName(self, "选择参考音频", "", "音频 (*.wav *.mp3 *.m4a *.flac)")
        if not path:
            return
        self._log(f"上传参考音频：{path}")

        def _work():
            return self.api.upload_reference(path)
        self.run_async(_work,
                       on_result=lambda d: (self._log("参考音频上传成功"), self._load_references()),
                       on_error=lambda m: self._log(f"上传失败：{m}"))

    # ==================================================================
    # 开始 / 停止
    # ==================================================================

    def _start(self):
        if self._book is None:
            QMessageBox.information(self, "未选书", "请先在书架中选择要合成的书籍。")
            return
        if not self.config.is_authenticated:
            QMessageBox.information(self, "需要登录", "发起合成需先登录账号。")
            return
        self.btn_start.setEnabled(False)
        self.btn_stop.setEnabled(True)
        self.progress.setRange(0, 0)  # busy 直到收到第一段进度
        self._log("开始合成…")
        if self.rb_online.isChecked():
            self._start_online()
        else:
            self._start_local()

    # ---------- 在线 ----------

    def _start_online(self):
        book = self._book
        voice_id = self.voice_combo.currentData() or ""
        ref_id = self.ref_combo.currentData()
        ref_path = self._ref_path_for(ref_id)
        store_book_id = book.store_book_id or 0

        def _create():
            # 本地书：优先 OnlineTtsService.create_task（签名为 book_path:str，
            # 会顺带在本地 tasks 表登记一条，令书架显示「合成中」）。
            # 旧代码误把 Book 对象传进去 → Path(Book) 抛 TypeError 被 self.call 吞掉。
            if book.file_path and os.path.isfile(book.file_path):
                tid = self.call(self.online_tts, "create_task",
                                book.file_path, voice_id, "")
                if tid:
                    return int(tid)
                return self.api.upload_book_for_tts(book.file_path, "", voice_id)
            # 书城书：走按 store_book_id 建任务
            if store_book_id:
                return self.api.create_task_from_store(store_book_id, voice_id)
            raise RuntimeError("书籍缺少可用文件，无法上传合成")

        self.run_async(_create, on_result=self._after_online_created,
                       on_error=self._on_start_error)

    def _after_online_created(self, task_id: int):
        self.progress.setRange(0, 100)
        self.progress.setValue(0)
        if not task_id:
            self._on_start_error("服务器未返回任务号")
            return
        self._server_task_id = int(task_id)
        self._log(f"在线任务已创建：#{task_id}")
        self.task_started.emit(self._server_task_id)
        self._monitor_sse(self._server_task_id)

    def _ref_path_for(self, ref_id):
        if ref_id is None:
            return ""
        # 参考音频通常已在服务器；本机文件路径未知，留空由服务器按 id 处理
        return ""

    def _monitor_sse(self, task_id: int):
        self._sse = SseClient(self.config)
        bridge = _SseBridge()
        self._bridge = bridge
        bridge.progress.connect(self._on_progress)
        bridge.complete.connect(self._on_complete)
        bridge.error.connect(self._on_sse_error)
        self._sse.connect(
            task_id,
            on_progress=lambda c, t, s: bridge.progress.emit(c, t, s),
            on_complete=lambda tid: bridge.complete.emit(tid),
            on_error=lambda m: bridge.error.emit(m),
        )

    # ---------- 本机 ----------

    def _start_local(self):
        """本机合成入口：先过「模块是否就位」这道门，缺模块则走按需下载，就绪后再真正开合成。

        拆成「门 + 真身」两段，是因为下载是一次可能持续数分钟、需要独立进度弹窗与
        暂停/取消的异步过程；把它挡在真正合成之前，用户就不会遇到「点了开始却卡在
        引擎加载、还以为是本机坏了」。
        """
        book = self._book
        if self.local_tts is None:
            self._on_start_error("本机合成引擎（MOSS）未就绪")
            return
        if not (book and book.file_path and os.path.isfile(book.file_path)):
            self._on_start_error("本机合成需要本地电子书文件，请先把书下载到书架再合成")
            return

        # 模块没下载过 → 弹确认 + 下载；已下载 → 直接开合成。
        # 只看「模块是否装好」，不看 is_engine_available：后者还要求桥接脚本/解释器就位，
        # 那是本机环境配置问题，下载解决不了，交给 _run_local_synthesis 里更准确的报错。
        if self.call(self.local_tts, "is_module_installed", default=False):
            self._run_local_synthesis()
            return

        mb, size = self._describe_module_download()
        if not self._prompt_module_download(mb):
            self._reset_controls()
            self.progress.setRange(0, 100)
            self.progress.setValue(0)
            self._log("已取消：未下载本机合成模块")
            return
        self._download_module(then=self._run_local_synthesis)

    def _prompt_module_download(self, mb: str) -> bool:
        """下载前确认框：展示体积 + **可选的保存目录**，确认后把目录写进 config 并持久化。

        为什么目录要可选：模块动辄 700 多 MB，默认落在系统盘 AppData 下很容易把 C 盘
        塞满；让用户挑一个有空间的盘，是几百 MB 级下载该有的基本自由度。选完即写入
        ``config.moss_module_dir`` 并 save()，下次本机合成沿用同一目录、命中已装模块
        就不再重复下载。
        """
        dlg = QDialog(self)
        dlg.setWindowTitle("下载本机合成模块 · 选择保存目录")
        dlg.setModal(True)
        dlg.setAttribute(Qt.WA_DeleteOnClose, True)
        dlg.setMinimumWidth(520)
        lay = QVBoxLayout(dlg)
        lay.setSpacing(10)

        info = QLabel(
            "首次使用本机合成需要下载 MOSS-TTS-Nano 模块。\n"
            f"下载体积：{mb}　·　支持断点续传，可随时暂停或取消。")
        info.setWordWrap(True)
        lay.addWidget(info)

        dir_row = QHBoxLayout()
        dir_row.addWidget(QLabel("保存目录："))
        edit = QLineEdit()
        edit.setText(str(self.config.moss_module_dir_path))
        edit.setToolTip("模块文件将下载并解压到该目录（含 models/ 子目录）")
        dir_row.addWidget(edit, 1)
        btn_browse = QPushButton("浏览…")
        dir_row.addWidget(btn_browse)
        lay.addLayout(dir_row)

        def _browse():
            start = edit.text().strip() or str(self.config.moss_module_dir_path)
            chosen = QFileDialog.getExistingDirectory(
                dlg, "选择 MOSS-TTS-Nano 模块保存目录", start)
            if chosen:
                edit.setText(chosen)
        btn_browse.clicked.connect(_browse)

        hint = QLabel("提示：建议选空间充足的磁盘；更换目录后需重新下载该目录下的模块。")
        hint.setStyleSheet("color:#888;")
        hint.setWordWrap(True)
        lay.addWidget(hint)

        btn_row = QHBoxLayout()
        btn_row.addStretch(1)
        btn_ok = QPushButton("开始下载")
        btn_no = QPushButton("取消")
        btn_row.addWidget(btn_ok)
        btn_row.addWidget(btn_no)
        lay.addLayout(btn_row)

        accepted = {"v": False}

        def _ok():
            raw = edit.text().strip()
            if not raw:
                QMessageBox.information(dlg, "目录不能为空", "请选择或填写模块保存目录。")
                return
            try:
                p = Path(raw).expanduser()
                p.mkdir(parents=True, exist_ok=True)
                # 探一下可写：新建一个临时文件再删，避免选了只读/无权限目录后下载才失败
                probe = p / ".audiobook_write_probe"
                probe.write_text("ok", "utf-8")
                probe.unlink()
            except Exception as e:  # noqa: BLE001
                QMessageBox.warning(dlg, "目录不可用", f"该目录无法创建或写入：\n{e}")
                return
            self.config.moss_module_dir = str(p)
            try:
                self.config.save()
            except Exception:  # noqa: BLE001
                pass   # 存不下不拦下载，本次进程内仍生效
            accepted["v"] = True
            dlg.accept()

        btn_ok.clicked.connect(_ok)
        btn_no.clicked.connect(dlg.reject)
        dlg.exec()
        return accepted["v"]


    def _describe_module_download(self):
        """尽力从服务端拿真实体积（不阻塞太久），拿不到就退回兜底文案。"""
        mb = "约 725 MB"
        try:
            info = self.call(self.local_tts, "module_status", default={}) or {}
        except Exception:  # noqa: BLE001
            info = {}
        _ = info
        # 体积在下载弹窗里会用服务端 manifest 真值刷新，这里只给个先行的粗略文案。
        return mb, 0

    def _download_module(self, then):
        """弹一个带进度/阶段/暂停/取消的模态下载框，后台跑 ensure_module。"""
        dlg = QDialog(self)
        dlg.setWindowTitle("下载本机合成模块（MOSS-TTS-Nano）")
        dlg.setModal(True)
        dlg.setMinimumWidth(440)
        lay = QVBoxLayout(dlg)

        tip = QLabel("正在从服务器获取模块清单…")
        tip.setWordWrap(True)
        lay.addWidget(tip)

        bar = QProgressBar()
        bar.setRange(0, 100)
        bar.setValue(0)
        lay.addWidget(bar)

        pct_lbl = QLabel("0%  ·  准备中")
        lay.addWidget(pct_lbl)

        btn_row = QHBoxLayout()
        btn_pause = QPushButton("暂停")
        btn_pause.setEnabled(False)   # 拿到下载态后才允许暂停
        btn_cancel = QPushButton("取消")
        btn_row.addWidget(btn_pause)
        btn_row.addStretch(1)
        btn_row.addWidget(btn_cancel)
        lay.addLayout(btn_row)

        self._module_dialog = dlg
        self._pending_after_module = then

        bridge = _ModuleBridge()
        self._module_bridge = bridge
        bridge.stage.connect(lambda s: (tip.setText(s), self._log(f"模块：{s}")))
        bridge.progress.connect(lambda d, t, sp: self._on_module_progress(d, t, sp, bar, pct_lbl, btn_pause))
        bridge.finished.connect(lambda info: self._on_module_done(dlg, info))
        bridge.cancelled.connect(lambda m: self._on_module_cancelled(dlg, m))
        bridge.errored.connect(lambda m: self._on_module_failed(dlg, m))

        paused_state = {"v": False}

        def _toggle_pause():
            if paused_state["v"]:
                self.call(self.local_tts, "resume_module")
                paused_state["v"] = False
                btn_pause.setText("暂停")
                tip.setText("继续下载…")
            else:
                self.call(self.local_tts, "pause_module")
                paused_state["v"] = True
                btn_pause.setText("继续")
                tip.setText("已暂停（断点已保留）")

        btn_pause.clicked.connect(_toggle_pause)

        def _cancel():
            self.call(self.local_tts, "cancel_module")
            btn_cancel.setEnabled(False)
            btn_pause.setEnabled(False)
            tip.setText("正在取消…（保留断点，可稍后继续）")

        btn_cancel.clicked.connect(_cancel)

        # 用户用窗口 X 关闭 = 取消
        dlg.finished.connect(lambda _r: self.call(self.local_tts, "cancel_module"))

        def _worker():
            try:
                info = self.local_tts.ensure_module(
                    on_progress=lambda d, t, sp: bridge.progress.emit(int(d), int(t), int(sp)),
                    on_stage=lambda s: bridge.stage.emit(str(s)),
                    allow_download=True,
                )
                bridge.finished.emit(info)
            except MossModuleCancelled as e:
                bridge.cancelled.emit(str(e))
            except Exception as e:  # noqa: BLE001
                bridge.errored.emit(str(e))

        threading.Thread(target=_worker, daemon=True, name="moss-module-dl").start()
        dlg.open()   # 非阻塞模态：保持事件循环，让信号能刷新进度

    def _on_module_progress(self, downloaded, total, speed, bar, pct_lbl, btn_pause):
        btn_pause.setEnabled(True)
        if total > 0:
            bar.setRange(0, 100)
            pct = min(100, int(downloaded * 100 / total))
            bar.setValue(pct)
            mb_done = downloaded / (1024 * 1024)
            mb_total = total / (1024 * 1024)
            spd = speed / (1024 * 1024)
            pct_lbl.setText(f"{pct}%  ·  {mb_done:.1f}/{mb_total:.1f} MB  ·  {spd:.1f} MB/s")
        else:
            bar.setRange(0, 0)
            mb_done = downloaded / (1024 * 1024)
            pct_lbl.setText(f"已下载 {mb_done:.1f} MB")

    def _on_module_done(self, dlg, info):
        mb = ""
        try:
            db = int((info or {}).get("download_bytes", 0) or 0)
            if db:
                mb = f"（约 {db / (1024 * 1024):.0f} MB）"
        except Exception:  # noqa: BLE001
            pass
        self._log(f"本机合成模块已就绪{mb}")
        if dlg.isVisible():
            dlg.close()
        self._module_dialog = None
        then = self._pending_after_module
        self._pending_after_module = None
        # 刷新一次音色下拉（模块自带内置音色）
        self._load_voices()
        if callable(then):
            then()

    def _on_module_cancelled(self, dlg, msg):
        self._log(f"模块下载已取消：{msg}")
        if dlg.isVisible():
            dlg.close()
        self._module_dialog = None
        self._pending_after_module = None
        self._reset_controls()
        self.progress.setRange(0, 100)
        self.progress.setValue(0)

    def _on_module_failed(self, dlg, msg):
        self._log(f"模块下载失败：{msg}")
        if dlg.isVisible():
            dlg.close()
        self._module_dialog = None
        self._pending_after_module = None
        QMessageBox.warning(self, "模块下载失败", str(msg))
        self._reset_controls()
        self.progress.setRange(0, 100)
        self.progress.setValue(0)

    def _run_local_synthesis(self):
        book = self._book
        voice = self.voice_combo.currentData() or ""
        if self.local_tts is None:
            self._on_start_error("本机合成引擎（MOSS）未就绪")
            return
        if not (book and book.file_path and os.path.isfile(book.file_path)):
            self._on_start_error("本机合成需要本地电子书文件，请先把书下载到书架再合成")
            return

        # 重入忙碌态（下载阶段把控件复位过）
        self.btn_start.setEnabled(False)
        self.btn_stop.setEnabled(True)
        self.progress.setRange(0, 0)

        # 先在本地 tasks 表建一条记录，synthesize_book 需要 task_id 才能逐段落库，
        # 让阅读页之后能按段号取到音频（旧代码没传 task_id → 段不落库 → 合成完仍「无音频」）。
        #
        # ⚠️ 用 upsert_local_task 而不是 upsert_task：**同书同音色复用未终结的那一行**，
        # 而不是每次都新建。新建的话断点续跑会失效 ——
        # synthesize_book 的续跑判据是「同一 task_id 下已 completed 且音频还在」的段，
        # 段落在旧行、新行一段都没有 ⇒ 每次「停止 → 重新开始」都整本重合成，
        # 已合成好的几千段全部白跑。（对齐安卓 TaskRepository.upsertLocalTask）
        audio_name = f"本机合成 · {book.display_name}"
        task_id = 0
        try:
            task_id = int(self.db.upsert_local_task(book.display_name, audio_name) or 0)
        except Exception as e:  # noqa: BLE001
            self._local_task_id = 0
            self._log(f"创建本机任务记录失败（将不入库音频段）：{e}")
        else:
            self._local_task_id = task_id

        self._local_stop = threading.Event()
        bridge = _LocalBridge()
        self._local_bridge = bridge
        bridge.progress.connect(self._on_local_progress)
        bridge.log.connect(self._log)
        bridge.finished.connect(self._on_local_result)
        bridge.errored.connect(self._on_start_error)

        stop_ev = self._local_stop
        task_id = self._local_task_id
        book_path = book.file_path

        def _run():
            try:
                res = self.local_tts.synthesize_book(
                    book_path,
                    voice=(voice or None),
                    ref_audio_path=None,
                    task_id=task_id,
                    on_progress=lambda c, t, cur: bridge.progress.emit(int(c), int(t), str(cur or "")),
                    on_log=lambda m: bridge.log.emit(str(m)),
                    stop_flag=lambda: stop_ev.is_set(),
                )
                bridge.finished.emit(res)
            except Exception as e:  # noqa: BLE001
                bridge.errored.emit(str(e))
        threading.Thread(target=_run, daemon=True, name="local-tts").start()
        self._log("本机合成已启动…")
        # 暂停按钮此刻还不可点：会话要到 synthesize_book 真正进入段循环才登记
        # （加载模型那几十秒里没有可挂起的会话）。进度回调里会把它点亮。

    def _on_local_progress(self, completed: int, total: int, _current: str = ""):
        self.progress.setRange(0, 100)
        pct = int(completed * 100 / total) if total else 0
        self.progress.setValue(min(100, pct))
        if total:
            self.seg_lbl.setText(f"段数：{completed} / {total}  ·  本机合成中")
        # 会话此时才真正登记（synthesize_book 进入段循环后）⇒ 暂停按钮在这里点亮。
        # 之前它一直是灰的：加载模型那几十秒里确实没有可挂起的会话。
        self._sync_pause_button()

    def _on_local_result(self, result):
        msg = getattr(result, "message", None) or "本机合成完成"
        self._log(msg)
        stopped = bool(getattr(result, "stopped", False))
        ok = (not stopped) and int(getattr(result, "failed", 0) or 0) == 0 \
            and int(getattr(result, "succeeded", 0) or 0) > 0
        if ok and self._book and self._book.id:
            try:
                self.db.update_book_has_audio(self._book.id, True)
            except Exception:  # noqa: BLE001
                pass
        if not stopped:
            self.progress.setRange(0, 100)
            self.progress.setValue(100 if ok else 0)
        self.task_finished.emit(int(self._local_task_id or 0), ok)
        self._reset_controls()
        # 会话已结束 ⇒ 书架上的「暂停/继续」按钮该收起/变灰了。
        self.synthesis_pause_changed.emit()

    # ---------- 兼容旧命名（保留，避免外部引用断裂） ----------

    def _on_progress2(self, value):
        try:
            v = int(value)
        except Exception:  # noqa: BLE001
            return
        self.progress.setRange(0, 100)
        self.progress.setValue(min(100, v))

    # ---------- SSE 回调（主线程） ----------

    def _on_progress(self, completed: int, total: int, status: str):
        self.progress.setRange(0, 100)
        pct = int(completed * 100 / total) if total else 0
        self.progress.setValue(pct)
        self.seg_lbl.setText(f"段数：{completed} / {total}  ·  状态：{status}")
        if total:
            self._local_total = total
        self._log(f"进度 {completed}/{total}（{status}）")

    def _on_complete(self, task_id: int):
        self.progress.setRange(0, 100)
        self.progress.setValue(100)
        self._log(f"合成完成（任务 #{task_id}）")
        # 标记本地书有音频
        if self._book and self._book.id:
            try:
                self.db.update_book_has_audio(self._book.id, True)
            except Exception:  # noqa: BLE001
                pass
        # 尽力把成品音频段下载到本地并入库，让阅读页之后能「边读边播」。
        # 失败不影响「合成完成」这一结果，只是稍后播放仍需重新下载。
        if self.online_tts is not None and task_id:
            def _dl():
                try:
                    return list(self.online_tts.download_completed_segments(int(task_id)) or [])
                except Exception as e:  # noqa: BLE001
                    self._log(f"下载成品音频段失败（可稍后在书架重试）：{e}")
                    return []
            self.run_async(_dl, on_result=lambda paths: self._log(
                f"已下载 {len(paths or [])} 段音频到本地"))
        self.task_finished.emit(int(task_id or 0), True)
        self._reset_controls()

    def _on_sse_error(self, msg: str):
        self._log(f"SSE：{msg}")

    def _on_start_error(self, msg: str):
        self._log(f"启动失败：{msg}")
        QMessageBox.warning(self, "合成失败", str(msg))
        self._reset_controls()

    def _toggle_pause(self):
        """暂停 / 继续本机合成。

        ⚠️ 暂停**只在段边界生效**：当前正在合成的那一段会先跑完，
        之后的段不再开始（一次推理跑在 native 里，不可中断）。

        动作按**服务的真实状态**决定，而不是按按钮上的文案 ——
        文案与真实状态之间可能隔着一次写库/回传的延迟，
        按文案走会连点两次变成「暂停了两次」（第二次用户其实想继续）。
        （对齐安卓 BookListFragment.toggleLocalSynthesisPause）
        """
        paused = bool(self.call(self.local_tts, "is_synthesis_paused", default=False))
        if paused:
            ok = bool(self.call(self.local_tts, "resume_synthesis", default=False))
            self._log("已恢复合成" if ok else "当前没有进行中的本机合成")
        else:
            ok = bool(self.call(self.local_tts, "pause_synthesis", default=False))
            self._log("将在当前段合成结束后暂停" if ok
                      else "当前没有进行中的本机合成")
        self._sync_pause_button()
        self.synthesis_pause_changed.emit()

    def _sync_pause_button(self):
        """按服务的真实状态刷新暂停按钮的文案与可用性。"""
        busy = bool(self.call(self.local_tts, "is_synthesis_busy", default=False))
        paused = bool(self.call(self.local_tts, "is_synthesis_paused", default=False))
        self.btn_pause.setEnabled(busy)
        self.btn_pause.setText("继续合成" if paused else "暂停合成")

    def _stop(self):
        self._log("停止合成…")
        # ⚠️ 本机：**必须先放行暂停闸门**，否则「暂停中点停止」会让合成线程
        # 永远卡在 await_resume 上 —— 合成既不结束也不释放内存，
        # 比崩溃更难排查（进程还活着，界面一直挂着）。
        #
        # 【为什么只置中断标志不够】闸门用的是 Condition.wait()，
        # 它**只会在 notify_all() 时返回**，不会因为「中断标志变了」就醒。
        # 所以「置标志」与「放行闸门」必须成对出现，缺一不可。
        # （对齐安卓 LocalSynthesisService 的 ACTION_CANCEL 分支：
        #   cancelled.set(true) 之后紧接着 pauseController.releaseAll()）
        self.call(self.local_tts, "release_synthesis_pause")
        # 本机：置中断标志（synthesize_book 在段间/片间检查 stop_flag 后优雅停止）
        if self._local_stop is not None:
            try:
                self._local_stop.set()
            except Exception:  # noqa: BLE001
                pass
        # 在线：断开 SSE + 停掉本地轮询监控
        if self._sse is not None:
            try:
                self._sse.disconnect()
            except Exception:  # noqa: BLE001
                pass
        self.call(self.online_tts, "stop_all_monitors")
        self.task_finished.emit(int(self._server_task_id or self._local_task_id or 0), False)
        self._reset_controls()

    def _reset_controls(self):
        self.progress.setRange(0, 100)
        self.btn_start.setEnabled(True)
        self.btn_stop.setEnabled(False)
        # 暂停按钮跟着会话状态走：合成结束/停止后不该再能点。
        self._sync_pause_button()

    # ==================================================================
    # 工具
    # ==================================================================

    def _log(self, msg: str):
        # 允许在后台线程安全调用
        try:
            self.log.appendPlainText(msg)
        except Exception:  # noqa: BLE001
            pass

    def showEvent(self, event):
        super().showEvent(event)
        if self._voices:
            pass
