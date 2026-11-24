"""主窗口：配置、选书、合成、上传。

## 线程模型

合成与上传都是长任务（几百段音频、每段十几秒），放在主线程会把界面冻住。
所以：

  - 合成：`QThread` + `SynthWorker`（worker 是 QObject，通过信号回报日志/进度）
  - 上传：同样模式（`UploadWorker`）

worker 里**不碰任何界面控件**，只 emit 信号；界面在主线程里更新 —— Qt 的规矩，
跨线程直接改控件会偶发崩溃。

## 界面分组

    左侧：配置（MOSS 引擎 / 本地 MySQL / 服务器 MySQL / 其他）
    右侧：合成输入 + 进度 + 日志 + 本地任务列表
"""

from __future__ import annotations

import os
import threading
from pathlib import Path

from PySide6.QtCore import QObject, QThread, QTimer, Qt, Signal, Slot
from PySide6.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QCheckBox,
    QComboBox,
    QFileDialog,
    QFormLayout,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QProgressBar,
    QPushButton,
    QSpinBox,
    QSplitter,
    QTableWidget,
    QTableWidgetItem,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from app.config import AppConfig, MySqlTarget, SshTarget
from app.core import db
from app.core.synthesizer import (
    BatchOutcome,
    SynthesisOutcome,
    SynthesisRequest,
    synthesize_books,
)
from app.core.uploader import (
    UploadOutcome,
    find_book_file,
    plan_upload,
    suggest_book_name,
    upload_tasks,
)

BOOK_FILTER = "电子书 (*.txt *.md *.epub *.pdf *.mobi *.azw *.azw3);;所有文件 (*)"
AUDIO_FILTER = "音频 (*.wav *.mp3 *.flac *.m4a *.aac *.ogg);;所有文件 (*)"


class SynthWorker(QObject):
    """合成任务（在子线程里跑）。一次可合成多本书。"""

    log = Signal(str)
    progress = Signal(int, int, str)
    # 段内心跳：一段拆成几十片时，每几秒报一行，只用来刷新顶栏文字
    heartbeat = Signal(str)
    # 当前第几本 / 共几本 / 书名（批量合成时用）
    book = Signal(int, int, str)
    done = Signal(object)
    failed = Signal(str)

    def __init__(
        self,
        cfg: AppConfig,
        requests: list[SynthesisRequest],
        stop_flag,
        resume: bool = True,
    ) -> None:
        super().__init__()
        self._cfg = cfg
        self._requests = list(requests)
        self._stop_flag = stop_flag
        self._resume = resume

    @Slot()
    def run(self) -> None:
        try:
            outcome = synthesize_books(
                self._cfg,
                self._requests,
                on_log=self.log.emit,
                on_progress=lambda d, t, c: self.progress.emit(d, t, c),
                on_book=lambda i, n, name: self.book.emit(i, n, name),
                on_heartbeat=self.heartbeat.emit,
                stop_flag=self._stop_flag,
                resume=self._resume,
            )
            self.done.emit(outcome)
        except Exception as exc:  # 任何异常都要报到界面上，不能静默死掉
            self.failed.emit(str(exc))


class UploadWorker(QObject):
    """上传任务（在子线程里跑）。"""

    log = Signal(str)
    progress = Signal(int, int, str)
    done = Signal(object)
    failed = Signal(str)

    def __init__(
        self,
        cfg: AppConfig,
        task_ids: list[int] | None,
        force: bool,
        book_files: dict[int, str] | None = None,
    ) -> None:
        super().__init__()
        self._cfg = cfg
        self._task_ids = task_ids
        self._force = force
        self._book_files = dict(book_files or {})

    @Slot()
    def run(self) -> None:
        try:
            outcome = upload_tasks(
                self._cfg,
                local_task_ids=self._task_ids,
                force=self._force,
                on_log=self.log.emit,
                on_progress=lambda d, t, c: self.progress.emit(d, t, c),
                book_files=self._book_files,
            )
            self.done.emit(outcome)
        except Exception as exc:
            self.failed.emit(str(exc))


class MainWindow(QMainWindow):
    """工具主窗口。"""

    # 「刷新音色」在后台线程里跑，结果用信号回主线程
    _voices_loaded = Signal(list)
    _voices_failed = Signal(str)
    # 「对齐结构」同样在后台线程里跑
    _align_done = Signal(str)
    _align_failed = Signal(str)
    # 「测试上传通道」结果回主线程
    _probe_done = Signal(str)
    _probe_failed = Signal(str)

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("AudioBookLocalTtsTool —— 本地有声书合成与上传")
        self.resize(1280, 820)

        self.cfg = AppConfig.load()
        self._stop_flag = False
        self._thread: QThread | None = None
        self._worker: QObject | None = None
        self._voice_thread: threading.Thread | None = None
        self._align_thread: threading.Thread | None = None
        self._engine_voices: list[str] = []
        # 批量合成时给状态栏加「第 2/3 本 · 」前缀（单本时为空）
        self._book_prefix = ""
        # 上次选原书文件的目录（上传老任务时要人工补文件，记住上次位置省得反复找）
        self._last_book_dir = ""

        root = QWidget()
        self.setCentralWidget(root)
        outer = QVBoxLayout(root)
        outer.setContentsMargins(10, 10, 10, 10)

        splitter = QSplitter(Qt.Orientation.Horizontal)
        outer.addWidget(splitter)
        splitter.addWidget(self._build_left())
        splitter.addWidget(self._build_right())
        splitter.setStretchFactor(0, 5)
        splitter.setStretchFactor(1, 6)

        self._apply_config_to_ui()
        self._append_log("就绪。先在左侧填好 MOSS 工程路径与两个 MySQL 连接，再选书开始合成。")

        # 音色加载线程的结果回主线程（后台线程不能直接碰界面）
        self._voices_loaded.connect(self._on_voices_loaded)
        self._voices_failed.connect(self._on_voices_failed)
        self._align_done.connect(self._on_align_done)
        self._align_failed.connect(self._on_align_failed)
        self._probe_done.connect(self._on_probe_done)
        self._probe_failed.connect(self._on_probe_failed)

        # 启动就把「本地任务」表填上。原先只在合成完成 / 上传完成 / 手动点
        # 「刷新任务」时才查库，于是**重启后表格恒为空**：上一次已经合成好的书
        # 明明在库里，界面上却找不到，也就没法选中它上传。
        # 用 singleShot(0) 而不是在 __init__ 里直接查：先让窗口画出来，
        # 本地 MySQL 偶发慢或没起时不会拖住窗口显示。
        QTimer.singleShot(0, self._reload_tasks)

    # ---------- 界面搭建 ----------

    def _build_left(self) -> QWidget:
        tabs = QTabWidget()

        # ---- MOSS 引擎 ----
        moss_box = QGroupBox("MOSS-TTS-Nano 引擎")
        moss_form = QFormLayout(moss_box)
        self.ed_project = QLineEdit()
        self.ed_project.setPlaceholderText("MOSS-TTS-Nano 工程目录（含 onnx_tts_runtime.py）")
        moss_form.addRow("工程路径", self._with_browse(self.ed_project, self._browse_dir))
        self.ed_python = QLineEdit()
        self.ed_python.setPlaceholderText("留空 = 用运行本工具的解释器（推荐填 MOSS 环境的 python）")
        moss_form.addRow("Python 解释器", self._with_browse(self.ed_python, self._browse_file))
        self.ed_model_dir = QLineEdit()
        self.ed_model_dir.setPlaceholderText("留空 = 工程默认的 models/ 目录（首次会自动下载）")
        moss_form.addRow("模型目录", self._with_browse(self.ed_model_dir, self._browse_dir))
        self.sp_threads = QSpinBox()
        self.sp_threads.setRange(1, 64)
        self.sp_threads.setValue(4)
        moss_form.addRow("CPU 线程", self.sp_threads)
        self.cb_provider = QComboBox()
        self.cb_provider.addItems(["cpu", "cuda"])
        moss_form.addRow("执行提供方", self.cb_provider)
        self.cb_sample_mode = QComboBox()
        self.cb_sample_mode.addItems(["fixed", "full", "greedy"])
        moss_form.addRow("采样模式", self.cb_sample_mode)
        self.chk_wetext = QCheckBox("启用 WeTextProcessing 文本正则化（需要 pynini，默认关）")
        moss_form.addRow("", self.chk_wetext)
        tabs.addTab(moss_box, "MOSS 引擎")

        # ---- 本地 MySQL ----
        local_box = QGroupBox("本地 MySQL（合成结果落这里）")
        local_form = QFormLayout(local_box)
        self.ed_local_host = QLineEdit()
        local_form.addRow("IP / 主机", self.ed_local_host)
        self.sp_local_port = QSpinBox()
        self.sp_local_port.setRange(1, 65535)
        local_form.addRow("端口", self.sp_local_port)
        self.ed_local_db = QLineEdit()
        local_form.addRow("数据库", self.ed_local_db)
        self.ed_local_user = QLineEdit()
        local_form.addRow("用户名", self.ed_local_user)
        self.ed_local_pw = QLineEdit()
        self.ed_local_pw.setEchoMode(QLineEdit.Password)
        local_form.addRow("密码", self.ed_local_pw)
        row = QHBoxLayout()
        self.btn_local_test = QPushButton("测试连接")
        self.btn_local_test.clicked.connect(self._test_local_db)
        self.btn_local_init = QPushButton("初始化表结构")
        self.btn_local_init.clicked.connect(self._init_local_schema)
        self.btn_local_align = QPushButton("对齐服务器结构")
        self.btn_local_align.clicked.connect(self._align_local_schema)
        row.addWidget(self.btn_local_test)
        row.addWidget(self.btn_local_init)
        row.addWidget(self.btn_local_align)
        local_form.addRow("", self._wrap(row))
        self.ed_ddl = QLineEdit()
        self.ed_ddl.setPlaceholderText("AudioBookServer/init_database.sql（服务器结构的权威定义）")
        local_form.addRow("建表脚本", self._with_browse(self.ed_ddl, self._browse_file))
        tabs.addTab(local_box, "本地库")

        # ---- 服务器（SSH 上传通道）----
        server_box = QGroupBox("服务器（发布走 SSH：文件 + 元数据）")
        server_form = QFormLayout(server_box)
        self.cb_publish_target = QComboBox()
        self.cb_publish_target.addItem("书城（管理员视角，App 书城里可见）", "store")
        self.cb_publish_target.addItem("个人书架（挂在某个用户名下）", "shelf")
        self.cb_publish_target.setToolTip(
            "书城：建一条归管理员的合成任务 + 书城书籍记录，普通用户书架里不会出现；\n"
            "个人书架：只建任务，书只在那个账号的「我的书籍」里。"
        )
        server_form.addRow("发布到", self.cb_publish_target)
        self.ed_ssh_host = QLineEdit()
        self.ed_ssh_host.setPlaceholderText("服务器 IP / 域名")
        server_form.addRow("SSH 主机", self.ed_ssh_host)
        self.sp_ssh_port = QSpinBox()
        self.sp_ssh_port.setRange(1, 65535)
        server_form.addRow("SSH 端口", self.sp_ssh_port)
        self.ed_ssh_user = QLineEdit()
        server_form.addRow("SSH 用户", self.ed_ssh_user)
        self.ed_ssh_key = QLineEdit()
        self.ed_ssh_key.setPlaceholderText("私钥路径（~/.ssh/xxx），留空 = 用 ssh-agent")
        server_form.addRow("私钥", self._with_browse(self.ed_ssh_key, self._browse_file))
        self.ed_remote_uploads = QLineEdit()
        self.ed_remote_uploads.setPlaceholderText("留空 = 读服务器 env 的 UPLOAD_DIR（/data/audiobook/uploads）")
        server_form.addRow("uploads 目录", self.ed_remote_uploads)
        self.ed_remote_store = QLineEdit()
        self.ed_remote_store.setPlaceholderText("留空 = 读服务器 env 的 STORE_DIR（/data/audiobook/store_books）")
        server_form.addRow("书城目录", self.ed_remote_store)
        self.ed_server_env = QLineEdit()
        self.ed_server_env.setPlaceholderText("含 DB_URL / DB_USERNAME / DB_PASSWORD / UPLOAD_DIR")
        server_form.addRow("服务器 env", self.ed_server_env)
        self.sp_tunnel_port = QSpinBox()
        self.sp_tunnel_port.setRange(0, 65535)
        self.sp_tunnel_port.setToolTip("0 = 每次自动挑空闲端口，避免和上次残留的隧道撞车")
        server_form.addRow("隧道端口", self.sp_tunnel_port)
        self.ed_srv_pw = QLineEdit()
        self.ed_srv_pw.setEchoMode(QLineEdit.Password)
        self.ed_srv_pw.setPlaceholderText("一般留空：默认从服务器 env 读")
        server_form.addRow("库口令覆盖", self.ed_srv_pw)
        self.ed_srv_username = QLineEdit()
        self.ed_srv_username.setPlaceholderText("个人书架模式：任务挂在这个用户名下（tasks.user_id 外键）")
        server_form.addRow("书架用户名", self.ed_srv_username)
        self.ed_store_owner = QLineEdit()
        self.ed_store_owner.setPlaceholderText("书城模式：任务归这个管理员账号（audiobook_admin）")
        server_form.addRow("书城归属账号", self.ed_store_owner)
        self.ed_store_category = QLineEdit()
        self.ed_store_category.setPlaceholderText("书城分类（服务端会按它建目录），发布后可在管理端改")
        server_form.addRow("书城分类", self.ed_store_category)
        self.ed_api_base = QLineEdit()
        self.ed_api_base.setPlaceholderText("留空 = http://SSH 主机（nginx :80 反代 /api）")
        self.ed_api_base.setToolTip(
            "发布收尾会调一次管理端点补「正文快照」（POST /api/admin/books/{id}/content-snapshot）：\n"
            "没有快照的书，客户端第一次打开阅读时服务端才现解析整本（实测 9.2s，有快照 95ms）。\n"
            "地址留空就用 SSH 主机；直连后端调试可填 http://<ip>:8080。"
        )
        server_form.addRow("管理端地址", self.ed_api_base)
        self.ed_local_username = QLineEdit()
        self.ed_local_username.setPlaceholderText("本地库里的任务归属用户名")
        server_form.addRow("本地用户名", self.ed_local_username)
        row2 = QHBoxLayout()
        self.btn_srv_test = QPushButton("测试上传通道")
        self.btn_srv_test.clicked.connect(self._test_upload_channel)
        row2.addWidget(self.btn_srv_test)
        server_form.addRow("", self._wrap(row2))
        tabs.addTab(server_box, "服务器")

        # ---- 其他 ----
        other_box = QGroupBox("其他")
        other_form = QFormLayout(other_box)
        self.ed_ffmpeg = QLineEdit()
        other_form.addRow("ffmpeg", self._with_browse(self.ed_ffmpeg, self._browse_file))
        self.ed_workdir = QLineEdit()
        other_form.addRow("中间产物目录", self._with_browse(self.ed_workdir, self._browse_dir))
        self.chk_keep_wav = QCheckBox("保留每段的中间音频（调试用）")
        self.chk_keep_wav.setChecked(True)
        other_form.addRow("", self.chk_keep_wav)
        tabs.addTab(other_box, "其他")

        return tabs

    def _build_right(self) -> QWidget:
        panel = QWidget()
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(0, 0, 0, 0)

        # ---- 合成输入 ----
        input_box = QGroupBox("合成输入")
        form = QFormLayout(input_box)
        self.ed_book = QLineEdit()
        self.ed_book.setPlaceholderText(
            "选择本机的电子书（txt / md / epub / pdf / mobi，可多选；多本用 ; 分隔）"
        )
        form.addRow("电子书", self._with_browse(self.ed_book, self._browse_book))
        self.ed_reference = QLineEdit()
        self.ed_reference.setPlaceholderText("可选：选择参考音频（wav/mp3…），用于克隆自己的声音")
        form.addRow("参考音频", self._with_browse(self.ed_reference, self._browse_audio))
        self.cb_voice = QComboBox()
        self.cb_voice.addItem("（使用参考音频克隆）", "")
        form.addRow("内置音色", self.cb_voice)
        btn_row = QHBoxLayout()
        self.btn_start = QPushButton("开始合成")
        self.btn_start.clicked.connect(self._start_synthesis)
        self.btn_stop = QPushButton("停止")
        self.btn_stop.setEnabled(False)
        self.btn_stop.clicked.connect(self._stop_synthesis)
        self.btn_refresh_voices = QPushButton("刷新音色")
        self.btn_refresh_voices.clicked.connect(self._load_voices)
        # 断点续跑默认开：一本书要跑十几小时，中途停下（或进程被杀）后
        # 再点开始，默认就该接着跑而不是从第 1 段重来。
        self.chk_resume = QCheckBox("断点续跑")
        self.chk_resume.setChecked(True)
        self.chk_resume.setToolTip(
            "同一本书上次没跑完时，接着上次的段号往下合成，已完成的段不重做。\n"
            "取消勾选 = 一律新建任务、从第 1 段重跑。"
        )
        btn_row.addWidget(self.btn_start)
        btn_row.addWidget(self.btn_stop)
        btn_row.addWidget(self.btn_refresh_voices)
        btn_row.addWidget(self.chk_resume)
        form.addRow("", self._wrap(btn_row))
        layout.addWidget(input_box)

        # ---- 进度 ----
        self.progress = QProgressBar()
        self.progress.setRange(0, 100)
        self.progress.setValue(0)
        layout.addWidget(self.progress)
        self.lb_status = QLabel("空闲")
        layout.addWidget(self.lb_status)

        # ---- 本地任务 ----
        task_box = QGroupBox("本地任务（可一键发布到书城）")
        task_layout = QVBoxLayout(task_box)
        self.table = QTableWidget(0, 6)
        self.table.setHorizontalHeaderLabels(
            ["任务 ID", "书名", "状态", "段数", "音色", "已发布"]
        )
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        task_layout.addWidget(self.table)
        task_row = QHBoxLayout()
        self.btn_reload_tasks = QPushButton("刷新任务")
        self.btn_reload_tasks.clicked.connect(self._reload_tasks)
        self.btn_upload = QPushButton("一键发布（全部未发布）")
        self.btn_upload.clicked.connect(lambda: self._do_upload(None, False))
        self.btn_upload_selected = QPushButton("发布选中任务")
        self.btn_upload_selected.clicked.connect(self._upload_selected)
        self.chk_force = QCheckBox("强制重发（忽略已发布记录）")
        task_row.addWidget(self.btn_reload_tasks)
        task_row.addWidget(self.btn_upload)
        task_row.addWidget(self.btn_upload_selected)
        task_row.addWidget(self.chk_force)
        task_layout.addLayout(task_row)
        layout.addWidget(task_box, stretch=3)

        # ---- 日志 ----
        log_box = QGroupBox("日志")
        log_layout = QVBoxLayout(log_box)
        self.log_view = QPlainTextEdit()
        self.log_view.setReadOnly(True)
        self.log_view.setMaximumBlockCount(5000)
        log_layout.addWidget(self.log_view)
        layout.addWidget(log_box, stretch=2)

        return panel

    # ---------- 小工具 ----------

    def _with_browse(self, edit: QLineEdit, handler) -> QWidget:
        """输入框 + 「浏览…」按钮。"""
        widget = QWidget()
        row = QHBoxLayout(widget)
        row.setContentsMargins(0, 0, 0, 0)
        row.addWidget(edit, stretch=1)
        button = QPushButton("浏览…")
        button.setFixedWidth(80)
        button.clicked.connect(lambda: handler(edit))
        row.addWidget(button)
        return widget

    @staticmethod
    def _wrap(layout) -> QWidget:
        widget = QWidget()
        layout.setContentsMargins(0, 0, 0, 0)
        widget.setLayout(layout)
        return widget

    def _browse_dir(self, edit: QLineEdit) -> None:
        path = QFileDialog.getExistingDirectory(self, "选择目录", edit.text() or str(Path.home()))
        if path:
            edit.setText(path)

    def _browse_file(self, edit: QLineEdit) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "选择文件", edit.text() or str(Path.home()))
        if path:
            edit.setText(path)

    def _browse_book(self, edit: QLineEdit) -> None:
        """选择电子书（**支持多选**）。

        多选的结果替换当前选择；路径之间用 ``;`` 分隔写进输入框
        （输入框仍可手工编辑 —— 想只保留其中几本时直接改就行）。
        """
        paths, _ = QFileDialog.getOpenFileNames(
            self, "选择电子书（可多选）", str(Path.home()), BOOK_FILTER
        )
        if not paths:
            return
        edit.setText(";".join(paths))
        self._append_log("已选择 %d 本书：%s" % (len(paths), "、".join(Path(p).name for p in paths)))

    def _selected_books(self) -> list[str]:
        """从输入框解析出选中的书（去重、忽略不存在的路径）。"""
        raw = self.ed_book.text().strip()
        if not raw:
            return []
        result: list[str] = []
        seen: set[str] = set()
        for part in raw.replace("\n", ";").replace("；", ";").split(";"):
            path = part.strip().strip('"')
            if not path:
                continue
            key = os.path.normcase(os.path.abspath(path))
            if key in seen:
                continue
            seen.add(key)
            if Path(path).is_file():
                result.append(path)
            else:
                self._append_log("跳过不存在的文件：" + path)
        return result

    def _browse_audio(self, edit: QLineEdit) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "选择参考音频", str(Path.home()), AUDIO_FILTER)
        if path:
            edit.setText(path)

    def _append_log(self, message: str) -> None:
        self.log_view.appendPlainText(message)

    # ---------- 配置读写 ----------

    def _apply_config_to_ui(self) -> None:
        cfg = self.cfg
        self.ed_project.setText(cfg.moss.project_path)
        self.ed_python.setText(cfg.moss.python_exe)
        self.ed_model_dir.setText(cfg.moss.model_dir)
        self.sp_threads.setValue(int(cfg.moss.cpu_threads))
        self.cb_provider.setCurrentText(cfg.moss.execution_provider)
        self.cb_sample_mode.setCurrentText(cfg.moss.sample_mode)
        self.chk_wetext.setChecked(bool(cfg.moss.enable_wetext))

        self.ed_local_host.setText(cfg.local_db.host)
        self.sp_local_port.setValue(int(cfg.local_db.port))
        self.ed_local_db.setText(cfg.local_db.database)
        self.ed_local_user.setText(cfg.local_db.user)
        self.ed_local_pw.setText(cfg.local_db.password)

        self.ed_ssh_host.setText(cfg.ssh.host)
        self.sp_ssh_port.setValue(int(cfg.ssh.port))
        self.ed_ssh_user.setText(cfg.ssh.user)
        self.ed_ssh_key.setText(cfg.ssh.key_path)
        self.ed_remote_uploads.setText(cfg.ssh.remote_uploads)
        self.ed_remote_store.setText(cfg.ssh.remote_store)
        self.ed_server_env.setText(cfg.ssh.server_env)
        self.sp_tunnel_port.setValue(int(cfg.ssh.local_tunnel_port))
        self.ed_srv_pw.setText(cfg.server_db.password)
        self.ed_srv_username.setText(cfg.server_username)
        self.ed_store_owner.setText(cfg.store_owner_username)
        self.ed_store_category.setText(cfg.store_category)
        self.ed_api_base.setText(cfg.server_api_base)
        index = self.cb_publish_target.findData(cfg.publish_target or "store")
        self.cb_publish_target.setCurrentIndex(index if index >= 0 else 0)
        self.ed_local_username.setText(cfg.local_username)

        self.ed_ffmpeg.setText(cfg.ffmpeg_path)
        self.ed_workdir.setText(cfg.work_dir)
        self.chk_keep_wav.setChecked(bool(cfg.keep_wav))
        self.ed_ddl.setText(cfg.server_ddl_path)

    def _collect_config(self, save: bool = True) -> AppConfig:
        """从界面收集配置。

        save=False 时不写盘、不打日志：供「刷新任务」这类只想拿当前连接参数、
        不该产生副作用的场景使用（否则每刷一次表格就多两行日志）。
        """
        cfg = self.cfg
        cfg.moss.project_path = self.ed_project.text().strip()
        cfg.moss.python_exe = self.ed_python.text().strip()
        cfg.moss.model_dir = self.ed_model_dir.text().strip()
        cfg.moss.cpu_threads = int(self.sp_threads.value())
        cfg.moss.execution_provider = self.cb_provider.currentText()
        cfg.moss.sample_mode = self.cb_sample_mode.currentText()
        cfg.moss.enable_wetext = bool(self.chk_wetext.isChecked())

        cfg.local_db = MySqlTarget(
            host=self.ed_local_host.text().strip(),
            port=int(self.sp_local_port.value()),
            database=self.ed_local_db.text().strip(),
            user=self.ed_local_user.text().strip(),
            password=self.ed_local_pw.text(),
        )
        cfg.ssh = SshTarget(
            host=self.ed_ssh_host.text().strip(),
            port=int(self.sp_ssh_port.value()),
            user=self.ed_ssh_user.text().strip(),
            key_path=self.ed_ssh_key.text().strip(),
            remote_uploads=self.ed_remote_uploads.text().strip(),
            remote_store=self.ed_remote_store.text().strip(),
            server_env=self.ed_server_env.text().strip(),
            local_tunnel_port=int(self.sp_tunnel_port.value()),
        )
        # 服务器库连接参数一般来自服务器的 env 文件；这里只保留「口令覆盖」。
        # 主机/库名/用户名仍取配置里的值（界面上不再改），留空即全部走 env。
        cfg.server_db.password = self.ed_srv_pw.text()
        cfg.server_username = self.ed_srv_username.text().strip()
        cfg.publish_target = str(self.cb_publish_target.currentData() or "store")
        cfg.store_owner_username = self.ed_store_owner.text().strip() or "audiobook_admin"
        cfg.store_category = self.ed_store_category.text().strip() or "其他"
        cfg.server_api_base = self.ed_api_base.text().strip()
        cfg.local_username = self.ed_local_username.text().strip()
        cfg.ffmpeg_path = self.ed_ffmpeg.text().strip()
        cfg.work_dir = self.ed_workdir.text().strip()
        cfg.keep_wav = bool(self.chk_keep_wav.isChecked())
        cfg.server_ddl_path = self.ed_ddl.text().strip() or cfg.server_ddl_path
        if save:
            try:
                path = cfg.save()
                self._append_log("配置已保存：" + str(path))
            except OSError as exc:
                self._append_log("配置保存失败：" + str(exc))
        return cfg

    # ---------- 各个动作 ----------

    def _test_local_db(self) -> None:
        self._collect_config()
        try:
            version = db.Database(self.cfg.local_db).ping()
            self._append_log("本地 MySQL 连接成功：" + self.cfg.local_db.describe() + "（" + version + "）")
        except Exception as exc:
            self._append_log("本地 MySQL 连接失败：" + str(exc))

    def _init_local_schema(self) -> None:
        self._collect_config()
        try:
            db.Database(self.cfg.local_db).ensure_schema(with_tool_tables=True)
            self._append_log("本地库表结构已就绪（users / tasks / audio_segments / tool_upload_log）")
        except Exception as exc:
            self._append_log("初始化表结构失败：" + str(exc))

    def _align_local_schema(self) -> None:
        """把本地库的结构对齐到服务器（init_database.sql 定义的结构）。

        走后台线程：建参考库 + 比对 + 改表都要花点时间，别把界面冻住。
        """
        from app.core import schema_align

        cfg = self._collect_config()
        ddl = self.ed_ddl.text().strip() or cfg.server_ddl_path
        if not Path(ddl).is_file():
            self._append_log("找不到建表脚本：" + ddl)
            return
        self._append_log("开始对齐本地库结构（目标：" + ddl + "）")
        self.btn_local_align.setEnabled(False)

        def work() -> None:
            try:
                result = schema_align.align(cfg.local_db, ddl, log=self._append_log)
                problems = schema_align.verify(cfg.local_db, ddl)
                if problems:
                    for item in problems:
                        self._append_log("  ! 仍不一致：" + item)
                    self._align_done.emit("对齐完成，但仍有差异：" + str(len(problems)) + " 处")
                else:
                    self._align_done.emit(
                        "对齐完成：%d 条语句，失败 %d 条；校验结果：与服务器完全一致"
                        % (result["statements"], result["failed"])
                    )
            except Exception as exc:
                self._align_failed.emit(str(exc))

        self._align_thread = threading.Thread(target=work, daemon=True)
        self._align_thread.start()

    @Slot(str)
    def _on_align_done(self, message: str) -> None:
        self.btn_local_align.setEnabled(True)
        self._append_log(message)

    @Slot(str)
    def _on_align_failed(self, message: str) -> None:
        self.btn_local_align.setEnabled(True)
        self._append_log("对齐失败：" + message)
        QMessageBox.warning(self, "对齐失败", message)

    def _test_upload_channel(self) -> None:
        """只读探测整条上传链路：SSH → 读 env → 起隧道 → 连库。

        放后台线程：SSH 握手 + 隧道 + 连库要好几秒，主线程会卡。
        任何一步失败都把原始报错打出来 —— 这三段（认证 / env / 库权限）最容易混成一团。
        """
        from app.core import db as db_module
        from app.core.ssh_upload import SshClient, pick_free_port, read_server_info

        cfg = self._collect_config()
        if not cfg.ssh.host:
            self._append_log("请先填 SSH 主机")
            return
        self.btn_srv_test.setEnabled(False)
        self._append_log("测试上传通道：SSH → 读服务器 env → 隧道 → 数据库 …")

        def work() -> None:
            try:
                ssh = SshClient(cfg.ssh, log=self._append_log)
                uname = ssh.check()
                self._append_log(f"  SSH 连通：{uname}")
                info = read_server_info(ssh, cfg.ssh, cfg.server_db)
                self._append_log(f"  服务器参数（{info.source}）：{info.describe()}")
                port = int(cfg.ssh.local_tunnel_port or 0) or pick_free_port()
                with ssh.tunnel(port, info.db.host, info.db.port):
                    target = MySqlTarget(
                        host="127.0.0.1",
                        port=port,
                        database=info.db.database,
                        user=info.db.user,
                        password=info.db.password,
                    )
                    version = db_module.Database(target).ping()
                self._probe_done.emit(
                    f"上传通道可用：{uname} · 库 {info.db.database}（{version}）"
                    f" · uploads={info.uploads_dir} · store={info.store_dir}"
                )
            except Exception as exc:  # noqa: BLE001
                self._probe_failed.emit(str(exc))

        threading.Thread(target=work, daemon=True).start()

    @Slot(str)
    def _on_probe_done(self, message: str) -> None:
        self.btn_srv_test.setEnabled(True)
        self.lb_status.setText(message)
        self._append_log(message)

    @Slot(str)
    def _on_probe_failed(self, message: str) -> None:
        self.btn_srv_test.setEnabled(True)
        self.lb_status.setText("上传通道不可用")
        self._append_log("上传通道探测失败：" + message)
        QMessageBox.warning(self, "上传通道不可用", message)

    def _load_voices(self) -> None:
        """加载引擎内置音色清单（顺带验证工程路径与解释器配得对不对）。

        放后台线程：加载模型要几十秒到几分钟，放在主线程会把界面冻住。
        """
        from app.core.moss_client import MossEngine

        cfg = self._collect_config()
        if not cfg.moss.project_path:
            self._append_log("请先填写 MOSS 工程路径")
            return
        self._append_log("正在加载引擎（模型较大时可能要几十秒）…")
        self.btn_refresh_voices.setEnabled(False)
        self.lb_status.setText("加载引擎音色…")

        def work() -> None:
            engine = MossEngine(cfg.moss, on_log=self._append_log)
            try:
                engine.start()
                voices = list(engine.voices)
            except Exception as exc:
                self._append_log("引擎加载失败：" + str(exc))
                self._voices_failed.emit(str(exc))
                return
            finally:
                engine.stop()
            self._voices_loaded.emit(voices)

        self._voice_thread = threading.Thread(target=work, daemon=True)
        self._voice_thread.start()

    @Slot(list)
    def _on_voices_loaded(self, voices: list) -> None:
        self.btn_refresh_voices.setEnabled(True)
        self.lb_status.setText("空闲")
        self._engine_voices = list(voices)
        current = self.cb_voice.currentData()
        self.cb_voice.clear()
        self.cb_voice.addItem("（使用参考音频克隆）", "")
        for name in voices:
            self.cb_voice.addItem(str(name), str(name))
        index = self.cb_voice.findData(current)
        if index >= 0:
            self.cb_voice.setCurrentIndex(index)
        self._append_log("内置音色 " + str(len(voices)) + " 个已载入")

    @Slot(str)
    def _on_voices_failed(self, message: str) -> None:
        self.btn_refresh_voices.setEnabled(True)
        self.lb_status.setText("引擎加载失败")
        QMessageBox.warning(self, "引擎加载失败", message)

    def _start_synthesis(self) -> None:
        books = self._selected_books()
        if not books:
            QMessageBox.warning(self, "缺少电子书", "请先选择本机的电子书文件（可多选）")
            return
        cfg = self._collect_config()
        reference = self.ed_reference.text().strip() or None
        voice = self.cb_voice.currentData() or None
        if not reference and not voice:
            QMessageBox.warning(
                self, "缺少音色",
                "要么选择一个参考音频（克隆自己的声音），要么选择一个内置音色",
            )
            return

        self._stop_flag = False
        resume = self.chk_resume.isChecked()
        requests = [
            SynthesisRequest(
                book_path=book,
                reference_audio_path=reference,
                voice=voice,
            )
            for book in books
        ]
        self.btn_start.setEnabled(False)
        self.btn_stop.setEnabled(True)
        self.progress.setValue(0)
        self.lb_status.setText(f"合成中…（共 {len(requests)} 本）")
        if resume:
            self._append_log(
                "断点续跑已开启：同一本书若上次没跑完，会接着上次的段号往下合成"
                "（已完成的段不重做）；想强制从头跑就取消勾选「断点续跑」"
            )

        self._thread = QThread(self)
        self._worker = SynthWorker(cfg, requests, lambda: self._stop_flag, resume=resume)
        self._worker.moveToThread(self._thread)
        self._thread.started.connect(self._worker.run)
        self._worker.log.connect(self._append_log)
        self._worker.progress.connect(self._on_progress)
        self._worker.heartbeat.connect(self._on_heartbeat)
        self._worker.book.connect(self._on_book)
        self._worker.done.connect(self._on_synth_done)
        self._worker.failed.connect(self._on_synth_failed)
        self._worker.done.connect(self._thread.quit)
        self._worker.failed.connect(self._thread.quit)
        self._thread.finished.connect(self._worker.deleteLater)
        self._thread.start()

    def _stop_synthesis(self) -> None:
        self._stop_flag = True
        self._append_log("已请求停止（当前小片合成完后生效，一般几秒内）…")

    @Slot(int, int, str)
    def _on_book(self, index: int, total: int, name: str) -> None:
        """切到下一本书：进度条归零，状态栏显示「第几本 / 共几本」。"""
        self.progress.setValue(0)
        # 段内的进度/心跳会覆盖状态栏文字，前缀留着才看得出是第几本
        self._book_prefix = f"第 {index}/{total} 本 · " if total > 1 else ""
        self.lb_status.setText(self._book_prefix + name)

    @Slot(int, int, str)
    def _on_progress(self, done: int, total: int, current: str) -> None:
        if total > 0:
            self.progress.setValue(int(done * 100 / total))
        preview = current if len(current) <= 28 else current[:28] + "…"
        self.lb_status.setText(f"{self._book_prefix}{done}/{total}  {preview}")

    @Slot(str)
    def _on_heartbeat(self, message: str) -> None:
        """段内心跳：一段拆几十片时让顶栏持续有变化（进度条不跟着动）。

        进度条的刻度是「段」，段内没有可推进的完整刻度，所以这里只刷新文字。
        没有它的话，第 1 段那 17 分钟里界面是完全静止的 —— 和卡死一模一样。
        """
        self.lb_status.setText(f"{self._book_prefix}合成中 · {message}")

    @Slot(object)
    def _on_synth_done(self, outcome: object) -> None:
        """合成结束（单本 = BatchOutcome 里一本书，批量 = 多本）。"""
        self.btn_start.setEnabled(True)
        self.btn_stop.setEnabled(False)
        self._book_prefix = ""   # 收尾文案说整批，别再挂着「第 3/3 本 · 」
        if isinstance(outcome, BatchOutcome):
            ok = len(outcome.succeeded_books)
            failed = len(outcome.failed_books)
            skipped = len(outcome.skipped_books)
            text = (
                f"合成完成：{ok} 本成功"
                + (f"，{failed} 本失败" if failed else "")
                + (f"，{skipped} 本跳过" if skipped else "")
                + f"，共 {outcome.done_segments}/{outcome.total_segments} 段"
                + (
                    f"（其中 {outcome.skipped_segments} 段是上次已完成的）"
                    if outcome.skipped_segments else ""
                )
                + f"，用时 {outcome.elapsed_seconds / 60:.1f} 分钟"
            )
            for result in outcome.failed_books:
                self._append_log(f"  失败：{Path(result.book_path).name} —— {result.error}")
            if outcome.task_ids:
                self._append_log("生成的任务号：" + "、".join(str(i) for i in outcome.task_ids))
        else:
            single = outcome  # type: ignore[assignment]
            text = (
                f"任务 #{single.task_id} 完成：成功 {single.succeeded + single.skipped}"
                f"/{single.total_segments} 段"
                + (
                    f"（本次新合成 {single.succeeded} 段，续跑跳过 {single.skipped} 段）"
                    if single.skipped else ""
                )
                + f"，用时 {single.elapsed_seconds / 60:.1f} 分钟"
                + ("（已中止）" if single.stopped else "")
            )
        if not outcome.stopped:
            self.progress.setValue(100)
        self.lb_status.setText(text)
        self._append_log(self.lb_status.text())
        self._reload_tasks()
        if isinstance(outcome, BatchOutcome) and outcome.task_ids:
            # 批量合成完：把这批任务在表格里选中，方便接着「上传选中」
            self._select_task_rows(set(outcome.task_ids))

    def _select_task_rows(self, task_ids: set[int]) -> None:
        """在任务表里选中指定的任务（批量合成后方便直接上传）。"""
        self.table.clearSelection()
        for row in range(self.table.rowCount()):
            item = self.table.item(row, 0)
            if not item:
                continue
            try:
                task_id = int(item.text())
            except ValueError:
                continue
            if task_id in task_ids:
                self.table.selectRow(row)

    @Slot(str)
    def _on_synth_failed(self, message: str) -> None:
        self.btn_start.setEnabled(True)
        self.btn_stop.setEnabled(False)
        self._book_prefix = ""
        self.lb_status.setText("合成失败")
        self._append_log("合成失败：" + message)
        QMessageBox.critical(self, "合成失败", message)

    # ---------- 任务列表与上传 ----------

    def _reload_tasks(self) -> None:
        """刷新本地任务列表（并标出哪些已经发布过）。"""
        self._collect_config(save=False)
        try:
            database = db.Database(self.cfg.local_db)
            database.ensure_schema(with_tool_tables=True)
            conn = database.connect()
        except Exception as exc:
            self._append_log("读取本地任务失败：" + str(exc))
            return
        try:
            tasks = db.list_tasks(conn)
            uploaded = db.uploaded_task_ids(conn)
        except Exception as exc:
            self._append_log("读取本地任务失败：" + str(exc))
            return
        finally:
            conn.close()

        self.table.setRowCount(len(tasks))
        for row, task in enumerate(tasks):
            task_id = int(task["id"])
            values = [
                str(task_id),
                str(task.get("original_book_name") or ""),
                str(task.get("status") or ""),
                f"{int(task.get('completed_segments') or 0)}/{int(task.get('total_segments') or 0)}",
                str(task.get("default_voice_id") or "参考音频克隆"),
                "是" if task_id in uploaded else "否",
            ]
            for column, text in enumerate(values):
                self.table.setItem(row, column, QTableWidgetItem(text))
        self.table.resizeColumnsToContents()
        self._append_log(f"本地任务 {len(tasks)} 条")

    def _upload_selected(self) -> None:
        rows = {item.row() for item in self.table.selectedItems()}
        if not rows:
            QMessageBox.information(self, "未选择任务", "请先在表格里选中要发布的任务")
            return
        ids = []
        for row in sorted(rows):
            item = self.table.item(row, 0)
            if item:
                try:
                    ids.append(int(item.text()))
                except ValueError:
                    continue
        self._do_upload(ids, bool(self.chk_force.isChecked()))

    def _incomplete_tasks(self, cfg: AppConfig, task_ids: list[int] | None) -> list[str]:
        """列出范围内「还没合成完」的任务（paused / processing / failed / pending）。

        断点续跑上线后 paused 任务会变得常见：用户停了之后顺手点「一键上传」，
        推上去的就是半本书 —— 服务端不看本地任务状态，客户端会当成整本来播
        （后半本全是空气）。所以上传前必须显式确认一次。
        """
        try:
            conn = db.Database(cfg.local_db).connect()
        except Exception:  # noqa: BLE001
            return []  # 连不上本地库交给上传流程自己报错，不在这里拦
        try:
            rows = db.list_tasks(conn, limit=1000)
        except Exception:  # noqa: BLE001
            return []
        finally:
            conn.close()

        wanted = set(task_ids) if task_ids else None
        pending: list[str] = []
        for task in rows:
            task_id = int(task["id"])
            if wanted is not None and task_id not in wanted:
                continue
            if str(task.get("status") or "") in ("completed", "partial"):
                continue
            pending.append(
                f"#{task_id} {task.get('original_book_name') or ''}"
                f"（{task.get('status')}，"
                f"{int(task.get('completed_segments') or 0)}/"
                f"{int(task.get('total_segments') or 0)} 段）"
            )
        return pending

    def _do_upload(self, task_ids: list[int] | None, force: bool) -> None:
        cfg = self._collect_config()
        if not cfg.ssh.host:
            QMessageBox.warning(self, "缺少服务器配置", "请先在「服务器」页填 SSH 主机与私钥")
            return
        planned = plan_upload(cfg, task_ids, force)
        if not planned:
            QMessageBox.information(
                self, "没有要发布的任务",
                "这些任务都已发布过。要重发请先勾选「强制重发（忽略已发布记录）」。",
            )
            return
        pending = self._incomplete_tasks(cfg, task_ids)
        if pending:
            answer = QMessageBox.question(
                self,
                "有任务还没合成完",
                "以下任务还没跑完，现在上传只会推已有的段（服务端会当成整本书）：\n\n"
                + "\n".join(pending[:8])
                + (f"\n……另有 {len(pending) - 8} 个" if len(pending) > 8 else "")
                + "\n\n建议先「开始合成」把它续跑完。仍要继续上传吗？",
                QMessageBox.Yes | QMessageBox.No,
                QMessageBox.No,
            )
            if answer != QMessageBox.Yes:
                self._append_log("已取消上传：先把这些任务续跑完再传")
                return
        # 原书文件必须在**主线程**问（文件选择框不能在工作线程里弹），
        # 所以先一次性解析好，再交给后台的上传线程。
        book_files = self._resolve_book_files(cfg, planned)
        if book_files is None:
            self._append_log("已取消上传：没有选定原书文件")
            return
        self.btn_upload.setEnabled(False)
        self.btn_upload_selected.setEnabled(False)
        self.lb_status.setText(f"发布中…（{len(planned)} 个任务）")
        self._thread = QThread(self)
        self._worker = UploadWorker(cfg, task_ids, force, book_files=book_files)
        self._worker.moveToThread(self._thread)
        self._thread.started.connect(self._worker.run)
        self._worker.log.connect(self._append_log)
        self._worker.progress.connect(self._on_progress)
        self._worker.done.connect(self._on_upload_done)
        self._worker.failed.connect(self._on_upload_failed)
        self._worker.done.connect(self._thread.quit)
        self._worker.failed.connect(self._thread.quit)
        self._thread.finished.connect(self._worker.deleteLater)
        self._thread.start()

    def _resolve_book_files(self, cfg: AppConfig, tasks: list[dict]) -> dict[int, str] | None:
        """给每个待传任务配一个原书文件路径；用户取消则返回 None。

        服务器只存音频是不够的：App 打开书要按 `book_file_path` 去 uploads 找原书
        抽正文，缺了就是「能听不能读」。所以这里必须先落到一个真实文件。

        优先用合成时留在工作目录的副本（新版合成都留 `book.<ext>`）；
        老任务没有副本，就让用户选一次（按书名预填目录，选完记住）。
        """
        files: dict[int, str] = {}
        for task in tasks:
            task_id = int(task["id"])
            found = find_book_file(cfg, task)
            if found is not None:
                files[task_id] = str(found)
                continue
            title = str(task.get("original_book_name") or f"任务 {task_id}")
            start_dir = self._last_book_dir or str(Path.home())
            hint = str(Path(start_dir) / suggest_book_name(task))
            picked, _ = QFileDialog.getOpenFileName(
                self,
                f"选择原书文件：{title}（App 读正文要用它）",
                hint if Path(hint).exists() else start_dir,
                BOOK_FILTER,
            )
            if not picked:
                return None
            files[task_id] = picked
            self._last_book_dir = str(Path(picked).parent)
            self._append_log(f"任务 {task_id} 原书：{Path(picked).name}")
        return files

    @Slot(object)
    def _on_upload_done(self, outcome: UploadOutcome) -> None:
        self.btn_upload.setEnabled(True)
        self.btn_upload_selected.setEnabled(True)
        self.lb_status.setText(
            f"发布完成：成功 {outcome.ok_count} 个任务，跳过 {len(outcome.skipped)} 个，"
            f"失败 {outcome.failed_count} 个"
        )
        self._append_log(self.lb_status.text())
        for local_id, server_task_id, store_book_id in outcome.published:
            self._append_log(
                f"  任务 {local_id} → 服务器任务 {server_task_id} → 书城书籍 #{store_book_id}"
            )
        for local_id, message in outcome.failed:
            self._append_log(f"  任务 {local_id} 失败原因：{message}")
        self._reload_tasks()

    @Slot(str)
    def _on_upload_failed(self, message: str) -> None:
        self.btn_upload.setEnabled(True)
        self.btn_upload_selected.setEnabled(True)
        self.lb_status.setText("发布失败")
        self._append_log("发布失败：" + message)
        QMessageBox.critical(self, "发布失败", message)

    # ---------- 退出 ----------

    def closeEvent(self, event) -> None:
        if self._thread is not None and self._thread.isRunning():
            self._stop_flag = True
            self._thread.quit()
            self._thread.wait(3000)
        super().closeEvent(event)
