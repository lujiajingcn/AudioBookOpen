"""主窗口（对标 Android MainActivity）。

承载导航、页面栈、工具栏、状态栏，并创建 / 装配所有服务：
    ApiClient, AuthManager, LocalDatabase, SseClient,
    AudioEngine, OnlineTtsService, LocalTtsService, BookService, DictionaryManager,
    以及各功能页面（书架 / 书城 / 发现 / 我的）。

设计要点：
  - 服务与页面由其它模块提供。这里用「尽力装配」策略：若模块尚未就绪，
    也不会 import 失败，会以占位页 / None 降级，保证工程可独立运行。
  - 游客模式：可浏览书城/发现/阅读，但写操作需登录（合成/发帖/收藏/充值/上传）。
"""
from __future__ import annotations

import inspect
from typing import Callable, Dict, List, Optional

from PySide6.QtCore import Qt, QThread, Signal, QObject, QSettings
from PySide6.QtGui import QAction, QKeySequence, QShortcut
from PySide6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QLabel, QStackedWidget,
    QFrame, QToolButton, QPushButton, QStatusBar, QMenu, QApplication,
    QDialog, QLineEdit, QDialogButtonBox, QMessageBox, QSizePolicy,
)

from app.core.config import AppConfig
from app.core.api_client import ApiClient, ApiException
from app.core.auth import AuthManager
from app.core.sse import SseClient
from app.core.sync import SyncManager
from app.db.database import LocalDatabase
from app.ui.theme import Theme
from app.ui.icons import BRAND_PX, app_icon, brand_icon
from app.ui.widgets.navigation import NavigationBar


APP_TITLE = "有声伴读 - AudioBookPlayerPC"
LAST_PAGE_KEY = "ui/last_page"
# 左侧菜单栏状态持久化（对标 Web 端 localStorage['audiobook.sider.collapsed']）
NAV_VISIBLE_KEY = "ui/nav_visible"      # 1=显示 0=完全隐藏
NAV_COLLAPSED_KEY = "ui/nav_collapsed"  # 1=仅图标（收起）0=展开


# ======================================================================
# 通用后台任务 Worker（在线程中执行 callable，把结果发回主线程）
# ======================================================================
class _TaskWorker(QObject):
    finished = Signal(object)   # 成功结果
    failed = Signal(str)         # 错误信息

    def __init__(self, fn: Callable[[], object]):
        super().__init__()
        self._fn = fn

    def run(self):
        try:
            self.finished.emit(self._fn())
        except ApiException as e:
            self.failed.emit(getattr(e, "message", str(e)))
        except Exception as e:  # noqa: BLE001
            self.failed.emit(str(e))


class _Background(QThread):
    """把 _TaskWorker 放到子线程执行。"""
    def __init__(self, fn: Callable[[], object], parent=None):
        super().__init__(parent)
        self.worker = _TaskWorker(fn)
        self.result = None

    def run(self):
        self.worker.run()


# ======================================================================
# 占位页面（真实页面模块未就绪时使用）
# ======================================================================
class _PlaceholderPage(QFrame):
    def __init__(self, title: str, parent: QWidget | None = None):
        super().__init__(parent)
        self.title = title
        lay = QVBoxLayout(self)
        lay.setAlignment(Qt.AlignCenter)
        lbl = QLabel(title)
        lbl.setProperty("role", "title")
        lbl.setAlignment(Qt.AlignCenter)
        sub = QLabel("（此页面尚未接入具体实现）")
        sub.setProperty("secondary", True)
        sub.setAlignment(Qt.AlignCenter)
        lay.addWidget(lbl)
        lay.addWidget(sub)


# ======================================================================
# 登录对话框
# ======================================================================
class _LoginDialog(QDialog):
    def __init__(self, auth: AuthManager, message: str = "", parent=None,
                 remembered_username: str = ""):
        super().__init__(parent)
        self._auth = auth
        # 记住的用户名（token 过期后仍保留）：预填用，让重登只剩"输密码"
        self._remembered = (remembered_username or "").strip()
        self.setWindowTitle("登录")
        self.setModal(True)
        self.setMinimumWidth(360)

        self._thread: Optional[_Background] = None

        root = QVBoxLayout(self)
        root.setContentsMargins(20, 20, 20, 20)
        root.setSpacing(12)

        tip = QLabel(message or "此操作需要登录后进行。")
        tip.setWordWrap(True)
        tip.setStyleSheet("font-weight: bold;")
        root.addWidget(tip)

        self.user_edit = QLineEdit()
        self.user_edit.setPlaceholderText("用户名")
        # 预填记住的用户名：服务端 JWT TTL 只有 24h ⇒ 每天都要重登一次，
        # 用户名不必每次手打（改用别的账号时直接在框里改即可）。
        if self._remembered:
            self.user_edit.setText(self._remembered)
        root.addWidget(self.user_edit)

        self.pwd_edit = QLineEdit()
        self.pwd_edit.setPlaceholderText("密码")
        self.pwd_edit.setEchoMode(QLineEdit.Password)
        root.addWidget(self.pwd_edit)

        self.status_lbl = QLabel("")
        self.status_lbl.setProperty("secondary", True)
        self.status_lbl.setWordWrap(True)
        root.addWidget(self.status_lbl)

        self.buttons = QDialogButtonBox(
            QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        self.ok_btn = self.buttons.button(QDialogButtonBox.Ok)
        self.ok_btn.setText("登录")
        self.buttons.accepted.connect(self._try_login)
        self.buttons.rejected.connect(self.reject)
        root.addWidget(self.buttons)

        self.pwd_edit.returnPressed.connect(self._try_login)

        # 焦点策略：用户名已预填 ⇒ 直接落在密码框（敲密码回车即可完成重登）；
        # 没有记住的用户名 ⇒ 落在用户名框。
        # 记成属性是为了可测（离屏环境下 focusWidget() 不可靠）。
        self.focus_field = "password" if self._remembered else "username"
        (self.pwd_edit if self._remembered else self.user_edit).setFocus()

    def _try_login(self):
        username = self.user_edit.text().strip()
        password = self.pwd_edit.text()
        if not username or not password:
            self.status_lbl.setText("请输入用户名和密码")
            return
        for w in (self.user_edit, self.pwd_edit, self.ok_btn):
            w.setEnabled(False)
        self.status_lbl.setText("登录中…")

        worker = _Background(lambda: self._auth.login(username, password), self)
        worker.worker.finished.connect(self._on_result)
        worker.worker.failed.connect(self._on_error)
        worker.finished.connect(worker.deleteLater)
        self._thread = worker
        worker.start()

    def _on_result(self, ok: object):
        self._restore_inputs()
        if ok:
            self.accept()
        else:
            self.status_lbl.setText("用户名或密码错误")

    def _on_error(self, msg: str):
        self._restore_inputs()
        self.status_lbl.setText(f"登录失败：{msg}")

    def _restore_inputs(self):
        for w in (self.user_edit, self.pwd_edit, self.ok_btn):
            w.setEnabled(True)


# ======================================================================
# 主窗口
# ======================================================================
class MainWindow(QMainWindow):
    # 登录态变化信号：AuthManager 的回调可能在后台线程触发（login/validate_session
    # 都跑在 _Background worker 里），直接改 QLabel 不安全。用信号把 UI 更新编组回主线程。
    _auth_changed_sig = Signal(bool)
    # 401 信号：on_unauthorized 由 api 在后台线程同步调用，同样需编组回主线程改状态栏。
    _unauthorized_sig = Signal()
    # 402 信号：on_insufficient_credits 也在后台线程被 api 调用，直接弹模态框不安全。
    _insufficient_credits_sig = Signal(object)

    def __init__(self, config: AppConfig):
        super().__init__()
        self.config = config
        self.setWindowTitle(APP_TITLE)
        self.resize(1200, 800)
        self.setMinimumSize(900, 600)

        # 窗口图标（标题栏 / 任务栏按钮）
        _icon = app_icon()
        if not _icon.isNull():
            self.setWindowIcon(_icon)

        # ---------- 核心服务 ----------
        self.api = ApiClient(config)
        self.auth = AuthManager(config, self.api)
        self.db = LocalDatabase(config)
        self.sse = SseClient(config)
        # 跨端同步层（服务端为真源：任务/书架/收藏/进度，时间戳新者胜）
        self.sync = SyncManager(config, self.api, self.db)
        self._sync_running = False
        self._last_sync_ts = 0.0
        # 401 提示的"每会话只弹一次"守卫：一次会话过期会并发触发十来个接口的 401，
        # 没有守卫就会弹出一串一模一样的模态框把用户淹没。
        # 复位点：重新登录成功（_apply_auth_state(True)）或主动登出。
        self._unauthorized_notified = False

        # API 全局回调
        self.api.on_unauthorized = self._on_unauthorized
        self.api.on_insufficient_credits = self._on_insufficient_credits
        # 关窗后必须能摘掉这两个回调 —— 见 _detach_api_callbacks。
        self._api_callbacks_attached = True

        self._settings = QSettings("AudioBook", "AudioBookPlayerPC")

        # 左侧菜单栏是否显示（显式状态位；不用 isVisible() 判断，
        # 因为窗口尚未 show() 时 isVisible() 恒为 False，会导致状态误判）。
        self._nav_visible = True

        # ---------- 应用主题 ----------
        Theme.apply(QApplication.instance(), config.night_mode)

        # ---------- 装配可选服务（其它模块提供，缺失则 None）----------
        self.services: Dict[str, object] = self._build_services()

        # ---------- UI ----------
        # ⚠ _workers 必须早于 _build_ui/_connect_events 初始化：
        #   首屏切换会触发 shelf_shown → _run_sync_async → _run_bg → self._workers.append，
        #   若此时属性还不存在，启动即抛 AttributeError。
        self._workers: List[_Background] = []
        self._build_ui()
        self._build_status_bar()
        self._connect_events()

        # ---------- 启动后台任务 ----------
        self._validate_token_async()
        if self.auth.is_authenticated:
            self._refresh_credits_async()

    # ==================================================================
    # 服务装配
    # ==================================================================
    def _build_services(self) -> Dict[str, object]:
        """尽力装配业务服务。找不到实现模块时返回 None，不影响启动。

        约定的候选模块位置（任一存在即装配）：
            AudioEngine      : app.services.audio_engine / app.core.audio_engine
            OnlineTtsService : app.services.tts_service    / app.core.tts
            LocalTtsService  : app.services.local_tts      / app.core.local_tts
            BookService      : app.services.book_service   / app.core.book_service
            DictionaryManager: app.services.dictionary     / app.core.dictionary
        """
        # 键名必须与页面构造函数期望的 service 关键字参数一致：
        #   playback / online_tts / local_tts / book_service / dictionary。
        specs = {
            "playback": ("AudioEngine", ["app.core.playback"]),
            "online_tts": ("OnlineTtsService", ["app.core.tts_online"]),
            "local_tts": ("LocalTtsService", ["app.core.tts_local"]),
            "book_service": ("BookService", ["app.core.book_service"]),
            "dictionary": ("DictionaryManager", ["app.core.dictionary"]),
            "update_checker": ("UpdateChecker", ["app.core.update_checker"]),
            "store_audio": ("StoreAudioDownloader", ["app.core.store_audio"]),
            # 私有云端任务（/api/tts/**）的按需下载：本机导入的书在云端合成后，
            # 音频不在 store 命名空间下，必须单独走一套接口。
            "task_audio": ("TaskAudioDownloader", ["app.core.task_audio"]),
        }
        deps = {
            "config": self.config, "api": self.api, "auth": self.auth,
            "db": self.db, "sse": self.sse, "main": self,
        }
        out: Dict[str, object] = {}
        for key, (cls_name, modules) in specs.items():
            out[key] = None
            for mod_path in modules:
                try:
                    import importlib
                    mod = importlib.import_module(mod_path)
                    cls = getattr(mod, cls_name, None)
                    if cls is None:
                        continue
                    out[key] = self._instantiate(cls, deps)
                    break
                except Exception:  # noqa: BLE001 —— 服务尚未实现时静默降级
                    continue
        # 跨端同步层：随核心服务一起注入各页面
        out["sync"] = self.sync
        return out

    @staticmethod
    def _instantiate(cls, deps: Dict[str, object]):
        """按构造签名从 deps 中挑选关键字参数实例化；失败则退化尝试。"""
        try:
            params = list(inspect.signature(cls.__init__).parameters.values())[1:]
        except (TypeError, ValueError):
            params = []
        kwargs = {p.name: deps[p.name] for p in params if p.name in deps}
        if kwargs:
            return cls(**kwargs)
        # 位置参数退化：按签名顺序匹配 deps（键名不敏感时优先 config）
        positional = [deps[n] for n in ("config", "api", "auth", "db", "sse")
                      if n in deps][:len(params)]
        if positional and len(positional) == len(params):
            return cls(*positional)
        # 最后兜底：无参构造
        return cls()

    # ==================================================================
    # UI 构建
    # ==================================================================
    def _build_ui(self):
        central = QWidget()
        central.setObjectName("centralRoot")
        # 让 central 依据 QSS 绘制背景（配合 #centralRoot 规则），
        # 否则透明页面会露出底层黑色窗口表面。
        central.setAttribute(Qt.WA_StyledBackground, True)
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        # ---------- 顶部工具栏 ----------
        root.addWidget(self._build_toolbar())

        # ---------- 中部：导航 + 页面栈 ----------
        body = QWidget()
        body_lay = QHBoxLayout(body)
        body_lay.setContentsMargins(0, 0, 0, 0)
        body_lay.setSpacing(0)

        self.nav = NavigationBar()
        self.nav.set_items(NavigationBar.DEFAULT_ITEMS)
        body_lay.addWidget(self.nav)

        self.stack = QStackedWidget()
        self.pages = self._load_pages()
        for page in self.pages:
            self.stack.addWidget(page)
        body_lay.addWidget(self.stack, 1)

        root.addWidget(body, 1)

        # 导航切换
        self.nav.current_changed.connect(self._on_nav_changed)
        # 重复点击当前导航项也要能切页（阅读器/合成页叠在栈顶时是唯一出口）
        self.nav.item_clicked.connect(self._on_nav_item_clicked)
        # 侧栏「仅图标」折叠态变化 → 落盘
        self._wire_nav_state()

        # 页面动作 / 导航信号路由
        self._wire_page_signals()

        # 导航页数量（阅读器 / 合成页在栈中额外占位，不计入导航）
        self._nav_page_count = min(len(self.pages), len(NavigationBar.DEFAULT_ITEMS))

        # 恢复上次页面（仅在导航页范围内）
        try:
            last = int(self._settings.value(LAST_PAGE_KEY, 0))
        except (TypeError, ValueError):
            last = 0
        if not (0 <= last < self._nav_page_count):
            last = 0
        self.nav.set_current(last)
        self._show_page(last)

        # 恢复侧栏的显示/隐藏与折叠态（顶栏按钮与设置菜单已就绪）
        self._restore_nav_state()

    def _build_toolbar(self) -> QFrame:
        bar = QFrame()
        bar.setProperty("topbar", True)
        bar.setFixedHeight(52)
        lay = QHBoxLayout(bar)
        lay.setContentsMargins(16, 6, 12, 6)
        lay.setSpacing(8)

        # 左侧菜单栏显隐开关（放在顶栏最左，与 Web 端 MainLayout 的触发器位置一致）
        self.nav_toggle_btn = QToolButton()
        self.nav_toggle_btn.setText("«")
        self.nav_toggle_btn.setToolTip("隐藏左侧菜单栏 (Ctrl+B)")
        self.nav_toggle_btn.setAutoRaise(True)
        self.nav_toggle_btn.clicked.connect(self._toggle_nav_visible)
        lay.addWidget(self.nav_toggle_btn)

        # 品牌图标（标题左侧）。资源缺失时静默跳过，不留空位。
        brand = brand_icon()
        if not brand.isNull():
            logo = QLabel()
            logo.setPixmap(brand.pixmap(BRAND_PX, BRAND_PX))
            logo.setFixedSize(BRAND_PX, BRAND_PX)
            logo.setToolTip("有声伴读")
            lay.addWidget(logo)

        self.title_lbl = QLabel(APP_TITLE.split(" - ")[0])
        self.title_lbl.setProperty("role", "title")
        lay.addWidget(self.title_lbl)
        lay.addStretch(1)

        # 搜索
        self.search_btn = QToolButton()
        self.search_btn.setText("🔍")
        self.search_btn.setToolTip("搜索")
        self.search_btn.setAutoRaise(True)
        self.search_btn.clicked.connect(self._open_search)
        lay.addWidget(self.search_btn)

        # 更新通知（带角标）
        self.update_btn = QToolButton()
        self.update_btn.setText("⬆")
        self.update_btn.setToolTip("检查更新")
        self.update_btn.setAutoRaise(True)
        self.update_btn.clicked.connect(self._check_update_async)
        lay.addWidget(self.update_btn)

        # 设置
        self.settings_btn = QToolButton()
        self.settings_btn.setText("⚙")
        self.settings_btn.setToolTip("设置")
        self.settings_btn.setAutoRaise(True)
        self.settings_btn.setPopupMode(QToolButton.InstantPopup)
        self.settings_btn.setMenu(self._build_settings_menu())
        lay.addWidget(self.settings_btn)

        return bar

    def _build_settings_menu(self) -> QMenu:
        menu = QMenu(self)

        self.night_action = QAction("夜间模式", self, checkable=True)
        self.night_action.setChecked(self.config.night_mode)
        self.night_action.toggled.connect(self._toggle_night_mode)
        menu.addAction(self.night_action)

        self.nav_action = QAction("显示左侧菜单栏", self, checkable=True)
        self.nav_action.setChecked(True)
        self.nav_action.toggled.connect(self._set_nav_visible)
        menu.addAction(self.nav_action)

        menu.addSeparator()
        login_action = QAction("登录 / 退出", self)
        login_action.triggered.connect(self._toggle_login)
        menu.addAction(login_action)

        menu.addSeparator()
        about_action = QAction("关于", self)
        about_action.triggered.connect(self._show_about)
        menu.addAction(about_action)
        return menu

    def _build_status_bar(self):
        sb = QStatusBar()
        self.setStatusBar(sb)

        self.user_lbl = QLabel("游客")
        self.credits_lbl = QLabel("积分: --")
        self.net_lbl = QLabel("就绪")

        for w in (self.user_lbl, self.credits_lbl):
            w.setProperty("secondary", True)

        sb.addWidget(self.user_lbl)
        sb.addWidget(QLabel("·"))
        sb.addWidget(self.credits_lbl)
        sb.addPermanentWidget(self.net_lbl)

        self._update_user_label()

    # ==================================================================
    # 页面装配
    # ==================================================================
    def _load_pages(self) -> List[QWidget]:
        """加载真实页面；缺失时用占位页。

        页面基类 BasePage 构造签名为：
            (config, api, auth, db, parent=None, **services)
        services 关键字：playback / online_tts / local_tts / book_service / dictionary。
        除 4 个导航页外，额外创建「阅读器」「合成页」（不进导航栏，按需切换）。
        """
        nav_specs = [
            ("app.ui.pages.shelf_page", "ShelfPage", "书架"),
            ("app.ui.pages.store_page", "StorePage", "书城"),
            ("app.ui.pages.discover_page", "DiscoverPage", "发现"),
            ("app.ui.pages.profile_page", "ProfilePage", "我的"),
        ]

        self.shelf_page = self.store_page = \
            self.discover_page = self.profile_page = None
        self.reader_page = self.tts_page = None

        pages: List[QWidget] = []
        for module_path, cls_name, fallback_title in nav_specs:
            widget = self._instantiate_page(module_path, cls_name, fallback_title)
            attr = {
                "ShelfPage": "shelf_page", "StorePage": "store_page",
                "DiscoverPage": "discover_page", "ProfilePage": "profile_page",
            }.get(cls_name)
            if attr:
                setattr(self, attr, widget)
            pages.append(widget)

        # 阅读器 & 合成页：加入页面栈但不进导航栏（栈索引 4 / 5）
        self.reader_page = self._instantiate_page(
            "app.ui.pages.reader_page", "ReaderPage", "阅读")
        self.tts_page = self._instantiate_page(
            "app.ui.pages.tts_page", "TtsPage", "合成")
        pages.append(self.reader_page)
        pages.append(self.tts_page)

        return pages

    def _instantiate_page(self, module_path: str, cls_name: str,
                          fallback_title: str) -> QWidget:
        """构造页面实例并注入依赖；任何异常都回落到占位页。"""
        try:
            import importlib
            mod = importlib.import_module(module_path)
            cls = getattr(mod, cls_name, None)
            if cls is None:
                raise ImportError(f"{module_path} 无 {cls_name}")
            # store_audio 必须在此白名单内：书城书「边下载边播放」与详情页
            # 「下载音频」都依赖页面拿到该服务，漏掉会让 store_audio 恒为 None
            # （表现为点播放提示「未合成」、下载音频提示「服务未就绪」）。
            svc_keys = ("playback", "online_tts", "local_tts",
                        "book_service", "dictionary", "sync", "store_audio",
                        "task_audio")
            svc = {k: v for k, v in self.services.items() if k in svc_keys}
            widget = cls(self.config, self.api, self.auth, self.db,
                         parent=None, **svc)
            self._attach_main_window(widget)
            return widget
        except Exception as e:  # noqa: BLE001 —— 页面未实现时降级
            print(f"[MainWindow] 页面 {cls_name} 初始化失败，使用占位页：{e!r}")
            return _PlaceholderPage(fallback_title)

    def _wire_page_signals(self):
        """把各页面发出的导航 / 动作信号路由到主窗口。"""
        C = self._connect
        # 书架
        C(self.shelf_page, "book_opened", lambda bid: self._open_reader(book_id=bid))
        C(self.shelf_page, "store_book_opened", lambda sid: self._open_reader(store_book_id=sid))
        # 书架「播放」：打开阅读页并**直接起播**（不再要求用户先进阅读页再点底部播放条）
        C(self.shelf_page, "book_play_requested",
          lambda bid: self._open_reader(book_id=bid, autoplay=True))
        C(self.shelf_page, "store_book_play_requested",
          lambda sid: self._open_reader(store_book_id=sid, autoplay=True))
        C(self.shelf_page, "synthesize_requested", self._synthesize_local_book)
        C(self.shelf_page, "shelf_shown", lambda: self._run_sync_async(min_interval=45.0))
        # 书架页「同步」按钮：用户主动要求立刻拉一次云端数据（不等节流窗口）。
        C(self.shelf_page, "manual_sync_requested", lambda: self._run_sync_async(manual=True))
        # 书架页横幅的「重新登录」：登录成功后由 _apply_auth_state(True) 补跑同步，
        # 断掉的书架会自动接上（用户不需要再手动点一次「同步」）。
        C(self.shelf_page, "login_requested",
          lambda: self.require_login("请登录以恢复云端书架同步。"))
        # 书城
        C(self.store_page, "read_requested", lambda sid: self._open_reader(store_book_id=sid))
        C(self.store_page, "synthesize_requested", self._synthesize_store_book)
        # 阅读器（顶部已无「返回」按钮，离开阅读器 = 点左侧导航栏）
        C(self.reader_page, "synthesize_requested", self._synthesize_local_book)
        # 合成页：本机合成的暂停态变了 ⇒ 书架上的「暂停/继续」按钮跟着重绑。
        # 不接的话，用户在合成页点了暂停、退到书架看到的还是「暂停」，
        # 再点一下就成了「暂停两次」（第二次用户其实想继续）。
        C(self.tts_page, "synthesis_pause_changed", self._on_synthesis_pause_changed)
        # 个人中心
        C(self.profile_page, "login_requested",
          lambda: self.require_login("请登录以继续。"))
        C(self.profile_page, "open_book", lambda bid: self._open_reader(book_id=bid))
        C(self.profile_page, "store_book_opened",
          lambda sid: self._open_reader(store_book_id=sid))
        # 单词本「跳转到原书」：书身份已由 profile_page 解析成 (book_id, store_book_id)，
        # 这里只转发，并把出处 6 键作为 jump 传给 reader_page.open_book 做句子定位。
        C(self.profile_page, "word_jump_requested",
          lambda bid, sid, jump: self._open_reader(
              book_id=bid or None, store_book_id=sid or None, jump=jump))
        C(self.profile_page, "settings_changed", self._apply_page_settings)
        # 发现 / 社区
        C(self.discover_page, "login_required",
          lambda: self.require_login("请登录以继续。"))

    @staticmethod
    def _connect(obj, signal_name: str, slot) -> bool:
        sig = getattr(obj, signal_name, None)
        if sig is not None and hasattr(sig, "connect"):
            try:
                sig.connect(slot)
                return True
            except Exception:  # noqa: BLE001
                return False
        return False

    # ---------- 页面路由 ----------
    def _show_stack_page(self, widget: QWidget):
        idx = self.stack.indexOf(widget)
        if idx >= 0:
            self.stack.setCurrentIndex(idx)

    def _open_reader(self, book_id: int = None, store_book_id: int = None,
                     jump: dict = None, autoplay: bool = False):
        if self.reader_page is None:
            return
        self._show_stack_page(self.reader_page)
        fn = getattr(self.reader_page, "open_book", None)
        if callable(fn):
            try:
                # jump：单词本「跳转到原书」的收藏出处（wire 6 键）。普通打开为 None，
                # reader_page.open_book 会照常恢复进度；非空时改为按出处定位句子。
                # autoplay：书架「播放」按钮进入 —— 正文加载完成后直接起播。
                fn(book_id=book_id, store_book_id=store_book_id, jump=jump,
                   autoplay=autoplay)
            except Exception as e:  # noqa: BLE001
                print(f"[MainWindow] 打开阅读器失败：{e!r}")

    def _open_tts(self, book):
        if self.tts_page is None or book is None:
            return
        self._show_stack_page(self.tts_page)
        fn = getattr(self.tts_page, "set_book", None)
        if callable(fn):
            try:
                fn(book)
            except Exception as e:  # noqa: BLE001
                print(f"[MainWindow] 打开合成页失败：{e!r}")

    def _synthesize_local_book(self, book_id: int):
        book = self.db.get_book(book_id) if hasattr(self.db, "get_book") else None
        if book is None:
            return
        if not self.require_login("合成需先登录账号。", lambda: self._open_tts(book)):
            return

    def _synthesize_store_book(self, store_book_id: int, title: str = ""):
        from app.models.models import Book
        book = Book(id=0, display_name=title or "书城书籍",
                    file_path="", store_book_id=store_book_id, has_audio=False)
        if not self.require_login("合成需先登录账号。", lambda: self._open_tts(book)):
            return

    def _on_nav_item_clicked(self, index: int):
        """左侧导航项被点击（含**重复点击当前项**）。

        阅读器/合成页是叠在导航页之上的额外页（栈索引 >= _nav_page_count）。
        ``NavigationBar.set_current`` 在索引未变时会直接返回，不发
        ``current_changed`` —— 于是「点当前高亮的那一项」切不回书架，
        用户会卡在阅读器里出不来（顶部已无「返回」按钮）。这里补切页。
        """
        if index < 0 or index >= getattr(self, "_nav_page_count", len(self.pages)):
            return
        cur = self.stack.currentIndex()
        if cur < getattr(self, "_nav_page_count", len(self.pages)) and cur == index:
            return  # 已经在这个导航页上，无需重复切
        self._show_page(index)

    def _apply_page_settings(self):
        Theme.apply(QApplication.instance(), self.config.night_mode)
        try:
            self.nav.apply_theme()
        except Exception:  # noqa: BLE001
            pass
        lv = getattr(self.reader_page, "line_view", None) if self.reader_page else None
        if lv is not None:
            for name, arg in (("set_night_mode", self.config.night_mode),
                              ("set_font_size", self.config.font_size)):
                f = getattr(lv, name, None)
                if callable(f):
                    try:
                        f(arg)
                    except Exception:  # noqa: BLE001
                        pass

    def _attach_main_window(self, widget: QWidget):
        for attr, value in (("main_window", self), ("mw", self),
                              ("window", self), ("services", self.services)):
            try:
                if hasattr(widget, attr):
                    setattr(widget, attr, value)
            except Exception:  # noqa: BLE001
                pass
        for hook in ("set_main_window", "attach", "bind", "init"):
            fn = getattr(widget, hook, None)
            if callable(fn):
                try:
                    fn(self)
                except TypeError:
                    try:
                        fn()
                    except Exception:  # noqa: BLE001
                        pass
                except Exception:  # noqa: BLE001
                    pass
                break

    # ==================================================================
    # 事件
    # ==================================================================
    def _connect_events(self):
        self.auth.on_auth_changed(self._on_auth_changed)
        # 登录态变化经信号编组回主线程再刷 UI（回调可能来自后台线程）。
        self._auth_changed_sig.connect(self._apply_auth_state)
        self._unauthorized_sig.connect(self._apply_unauthorized)
        self._insufficient_credits_sig.connect(self._apply_insufficient_credits)
        # Ctrl+B 切换左侧菜单栏（与浏览器/IDE 习惯一致）
        self._nav_shortcut = QShortcut(QKeySequence("Ctrl+B"), self)
        self._nav_shortcut.activated.connect(self._toggle_nav_visible)

    def _on_nav_changed(self, index: int):
        self._show_page(index)

    # ==================================================================
    # 左侧菜单栏显隐
    # ==================================================================
    def _toggle_nav_visible(self):
        """顶栏按钮 / Ctrl+B：侧栏「完全显示 ⇄ 完全隐藏」。"""
        self._set_nav_visible(not self._nav_visible)

    def _set_nav_visible(self, visible: bool):
        """显示或隐藏整个侧栏（隐藏时内容区铺满窗口）。"""
        visible = bool(visible)
        self._nav_visible = visible
        self.nav.setVisible(visible)
        self._settings.setValue(NAV_VISIBLE_KEY, "1" if visible else "0")
        self._sync_nav_ui(visible)

    def _on_nav_collapsed_changed(self, collapsed: bool):
        """侧栏内部 « 按钮切换「仅图标」折叠态 → 落盘。"""
        self._settings.setValue(NAV_COLLAPSED_KEY, "1" if collapsed else "0")

    def _wire_nav_state(self):
        """把侧栏的折叠态变化接到落盘逻辑（单独抽出便于复用/测试）。"""
        # 先断后连，保证幂等。PySide6 在「本来就没连上」时会打 RuntimeWarning，
        # 这里静音掉——重复接线本身才是要防的问题。
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            try:
                self.nav.collapsed_changed.disconnect(
                    self._on_nav_collapsed_changed)
            except Exception:  # noqa: BLE001 —— 未连接过 / 无基类初始化时忽略
                pass
        self.nav.collapsed_changed.connect(self._on_nav_collapsed_changed)

    def _sync_nav_ui(self, visible: bool):
        """同步顶栏按钮与设置菜单里的勾选状态。"""
        if getattr(self, "nav_toggle_btn", None) is not None:
            self.nav_toggle_btn.setText("«" if visible else "☰")
            self.nav_toggle_btn.setToolTip(
                "隐藏左侧菜单栏 (Ctrl+B)" if visible
                else "显示左侧菜单栏 (Ctrl+B)")
        act = getattr(self, "nav_action", None)
        if act is not None and act.isChecked() != visible:
            act.blockSignals(True)
            act.setChecked(visible)
            act.blockSignals(False)

    def _restore_nav_state(self):
        """启动时恢复侧栏的显示/隐藏与折叠态。"""
        def _flag(key: str, default: bool) -> bool:
            raw = self._settings.value(key, None)
            if raw is None:
                return default
            if isinstance(raw, bool):
                return raw
            return str(raw).strip().lower() in ("1", "true", "yes", "on")

        visible = _flag(NAV_VISIBLE_KEY, True)
        collapsed = _flag(NAV_COLLAPSED_KEY, False)
        self._nav_visible = visible
        self.nav.setVisible(visible)
        self.nav.set_collapsed(collapsed)
        self._sync_nav_ui(visible)

    def _show_page(self, index: int):
        if 0 <= index < self.stack.count():
            self.stack.setCurrentIndex(index)
            if index < getattr(self, "_nav_page_count", len(self.pages)):
                self._settings.setValue(LAST_PAGE_KEY, index)
            page = self.pages[index]
            on_show = getattr(page, "on_show", None)
            if callable(on_show):
                try:
                    on_show()
                except Exception:  # noqa: BLE001
                    pass

    def _on_auth_changed(self, authenticated: bool, username: Optional[str]):
        # AuthManager 回调：可能来自后台线程（login/validate_session 在 worker 里跑），
        # 只负责把状态经信号编组回主线程，真正的 UI 更新在 _apply_auth_state 做。
        self._auth_changed_sig.emit(bool(authenticated))

    def _apply_auth_state(self, authenticated: bool):
        """主线程：把登录态如实反映到状态栏。

        根因修复——旧代码在此处**从不更新 net_lbl**，于是启动时 token 过期留下的
        "登录已过期"（或"校验登录状态…""同步失败"）在用户**重新登录成功后**仍赖着不走，
        出现"明明已登录、右下角却显示登录已过期"的矛盾（积分都能正常拉到就是证明）。
        这里让状态栏**以登录态为准**：登录成功一律复位为"在线"，登出/失效复位为游客态。
        """
        self._update_user_label()
        if authenticated:
            self.set_network_status("在线")
            self._refresh_credits_async()
            self._run_sync_async()
            # 重新登录成功 ⇒ 本次"会话过期"告警已处理，允许下次过期再提示一次
            self._unauthorized_notified = False
        else:
            self.credits_lbl.setText("积分: --")
            # ⛔ 别一律写"已离线（游客）"：**token 过期**与**从未登录**必须区分开。
            # 前者是故障（会话没了、云端不再同步），后者是正常游客态。
            # 一律写成"游客" ⇒ 用户以为自己是没登录，完全不知道会话已过期
            # —— 这正是本次线上现象的观感（手机端加的书 PC 端永不出现）。
            if getattr(self.auth, "session_expired", False):
                self.set_network_status("登录已过期 · 书架不再同步")
            else:
                self.set_network_status("已离线（游客）")

    def _update_user_label(self):
        if self.auth.is_authenticated:
            self.user_lbl.setText(f"👤 {self.auth.username or '已登录'}")
        else:
            self.user_lbl.setText("游客")

    def _on_unauthorized(self):
        """401：token 过期。api 在后台线程同步回调这里，UI 更新须编组回主线程。"""
        # 先清凭证：若确实带 token，logout 会触发 _auth_changed_sig(False)（先入队）。
        self.auth.handle_unauthorized()
        # 再入队"登录已过期"——排在 _apply_auth_state(False) 之后执行，用更具体的文案覆盖
        # 通用的"已离线（游客）"，同时避免在后台线程直接 setText。
        self._unauthorized_sig.emit()

    def _apply_unauthorized(self):
        """主线程：401 后把状态栏置为"登录已过期"（比"已离线"更具体、更可行动）。

        ⛔ **绝不能只是改一行状态栏就完事**：
        401 会触发 ``AuthManager.handle_unauthorized()`` → ``logout()`` → 删掉 ``auth.json``，
        于是 ``config.token`` 变 None ⇒ ``SyncManager.is_available()`` 返回 False ⇒
        后续所有 ``_run_sync_async`` 都在入口直接 return、**一次请求都不发**。
        表现就是：手机端刚加的书，PC 端书架**永远**不更新，且界面上毫无异样
        （只有状态栏一行小字"登录已过期"，用户极易忽略）—— 本次线上就是这个现象。

        所以这里必须做两件事：
          1. 让状态栏文案**写明"书架不再同步"**，把"为什么不同步"摆到明面上；
          2. 弹一次可操作的提示，登录成功后由 ``_apply_auth_state(True)``
             自动补跑一次同步，把断掉的书架接上。

        提示框里直接给「重新登录」按钮：点一下就弹出**已预填用户名**的登录框
        （只剩输密码一步），省得用户自己再去「我的」里找登录入口。
        """
        self.set_network_status("登录已过期 · 书架不再同步")
        # 状态栏小字极易被忽略 ⇒ 弹一次明确的提示。
        #
        # ⚠️ 守卫必须是「**本次会话过期只提示一次**」，不能用 try/finally 在弹框返回后
        # 立刻复位：一次会话过期会**并发**触发十来个接口的 401，它们的 401 是几乎同时
        # 到达的，弹框返回后立刻复位 ⇒ 会弹出一串一模一样的模态框把用户淹没。
        # 这里改成"标记已提示"，直到用户重新登录（_apply_auth_state(True)）或
        # 主动登出才复位。
        if self._unauthorized_notified:
            return
        self._unauthorized_notified = True
        if self._prompt_relogin() == "relogin":
            # 登录成功 ⇒ _apply_auth_state(True) 自动补跑一次同步（断掉的书架自动接上）
            self.require_login("请重新登录以恢复跨端同步。", lambda: None)

    def _prompt_relogin(self) -> str:
        """弹「登录已过期」提示框，返回用户的选择（``"relogin"`` / ``"later"``）。

        单独抽成一个方法有两个理由：

        1. **弹框与后续动作解耦** —— 弹框只负责问，动作由调用方决定；
        2. **可测**：模态框在离屏环境里会**阻塞**（等用户点击），
           不隔离就没法在自动化测试里覆盖这条链路。测试替换本方法即可，
           不必去 patch ``QMessageBox``（patch 它还得模拟"点了哪个按钮"，
           而 ``button.click()`` 会触发嵌套事件循环 ⇒ 测试挂死）。
        """
        box = QMessageBox(self)
        box.setIcon(QMessageBox.Warning)
        box.setWindowTitle("登录已过期")
        box.setText("登录状态已失效，云端数据（书架 / 收藏 / 阅读进度）将不再同步。\n"
                    "重新登录后会自动补同步一次，把断掉的书架接上。")
        btn_login = box.addButton("重新登录", QMessageBox.AcceptRole)
        box.addButton("稍后再说", QMessageBox.RejectRole)
        box.exec()
        return "relogin" if box.clickedButton() is btn_login else "later"

    def _on_insufficient_credits(self, body: dict):
        """402：余额不足。api 在后台线程回调这里，模态框须编组回主线程弹。"""
        self._insufficient_credits_sig.emit(body if isinstance(body, dict) else {})

    def _apply_insufficient_credits(self, body: object):
        """主线程：弹"积分不足"模态框。"""
        QMessageBox.warning(
            self, "积分不足",
            (body or {}).get("message", "积分不足，请充值后再试。"))

    # ==================================================================
    # 启动后台任务
    # ==================================================================
    def _validate_token_async(self):
        if not self.auth.is_authenticated:
            return
        self.set_network_status("校验登录状态…")
        self._run_bg(lambda: self.auth.validate_session(),
                     on_ok=self._on_validated, on_err=None)

    def _on_validated(self, valid: object):
        self._update_user_label()
        if valid:
            self.set_network_status("在线")
            self._refresh_credits_async()
            self._run_sync_async()
            return
        # ⚠️ 启动校验没过时，要区分「token 过期」与「本来就没登录」：
        #   服务端对过期 token 返回的是 **200 + {"valid": false}**（不是 401），
        #   ⇒ **不会**触发 api 的 401 回调 ⇒ 过期这件事只有 validate_session() 知道
        #   （它把标志记在 auth.session_expired 上）。
        #   不在这里处理的话，启动时 token 过期就是**完全静默**的。
        if getattr(self.auth, "session_expired", False):
            # 复用 401 那条路径：状态栏写明"书架不再同步" + 弹一次可操作的提示
            # （提示框里带「重新登录」按钮，点开就是预填用户名的登录框）。
            self._apply_unauthorized()
            return
        self.set_network_status("已离线（游客）")

    # ==================================================================
    # 跨端同步
    # ==================================================================
    def _run_sync_async(self, manual: bool = False, min_interval: float = 0.0):
        """后台拉取/合并云端数据（任务/书架/收藏/进度）。并发保护：同一时刻只跑一次。

        min_interval>0 时做节流：距上次成功同步不足该秒数则跳过（用于页面切换等自动触发）。
        """
        if self._sync_running:
            if manual:
                self.statusBar().showMessage("同步进行中，请稍候…", 3000)
            return
        if not self.sync.is_available():
            # ⛔ 早先这里直接 return ⇒ 未登录时同步**彻底静默**：
            # 一次请求都不发，用户看到的只是"手机端加的书一直没出现"，
            # 完全无从判断是自己掉登录了。手动同步时必须给出明确说法。
            if manual:
                self.statusBar().showMessage("未登录，无法同步云端数据，请先登录。", 6000)
            return
        import time as _t
        if not manual and min_interval > 0 and (self._last_sync_ts
                                                and _t.time() - self._last_sync_ts < min_interval):
            return
        self._sync_running = True
        if manual:
            self.set_network_status("同步中…")

        def _job():
            return self.sync.sync_all()

        def _ok(result):
            self._sync_running = False
            self._last_sync_ts = _t.time()
            self._on_sync_done(result, manual)

        def _err(msg):
            self._sync_running = False
            if manual:
                self.set_network_status("同步失败")
                self.statusBar().showMessage(f"同步失败：{msg}", 5000)

        self._run_bg(_job, on_ok=_ok, on_err=_err)

    def _on_sync_done(self, result: object, manual: bool = False):
        text = getattr(result, "as_text", None)
        summary = text() if callable(text) else "已同步"
        # 有实际变化才刷新书架，避免无谓重绘闪烁
        changed = (getattr(result, "tasks_added", 0) or getattr(result, "tasks_updated", 0)
                   or getattr(result, "shelf_synced", 0) or getattr(result, "shelf_removed", 0)
                   or getattr(result, "favorites_rebuilt", 0) or getattr(result, "words_rebuilt", 0)
                   or getattr(result, "progress_pulled", 0) or getattr(result, "progress_pushed", 0)
                   or getattr(result, "book_meta_pulled", 0))
        # 说明：book_meta_pulled 表示书架排序/最近阅读被远端覆盖 ⇒ 必须重绘书架；
        # settings_pulled（倍速/默认音色）不进这里，它不改变书架条目，
        # 倍速会在下次打开阅读页时由 AudioBar.set_speed 回灌显示。
        # P2 #6：覆盖图是书架徽章的数据源，但它是「纯感知层」——只影响徽章着色，不影响书籍条目。
        # 只在**手动**同步时把它算作"有变化"以刷新徽章；自动同步（页面切换节流触发）不因覆盖图
        # 重绘书架，避免每次进页闪一下。304（coverage_not_modified）时服务端明确未变，一律跳过。
        coverage_changed = (manual
                            and not getattr(result, "coverage_not_modified", False)
                            and getattr(result, "coverage_rows", 0) > 0)
        if (changed or coverage_changed) and self.shelf_page is not None:
            fn = getattr(self.shelf_page, "refresh", None)
            if callable(fn):
                try:
                    fn()
                except Exception:  # noqa: BLE001
                    pass
        if manual:
            self.set_network_status("在线")
            self.statusBar().showMessage(f"同步完成：{summary}", 6000)
            return

        # 自动同步（切页触发）失败时也要让用户知道。
        # ⛔ 早先这里直接 return：自动同步失败**完全静默** ⇒ 用户看到的是
        # "手机端加的书怎么一直没出现"，却不知道是同步失败了。
        errors = list(getattr(result, "errors", []) or [])
        if errors:
            self.statusBar().showMessage(f"同步失败：{errors[0]}", 8000)

    def _on_synthesis_pause_changed(self):
        """本机合成的暂停态变了 ⇒ 重绑书架（按钮文案/可点性）。

        刻意**不触发跨端同步**：暂停是纯本机状态（服务端毫不知情），
        拉一次同步既慢又没意义 —— 只需要重读本机的会话态重绑按钮。
        """
        if self.shelf_page is None:
            return
        fn = getattr(self.shelf_page, "refresh", None)
        if callable(fn):
            try:
                fn()
            except Exception:  # noqa: BLE001
                pass

    def _refresh_credits_async(self):
        def _job():
            return self.api.get_credits()
        self._run_bg(_job, on_ok=self._on_credits, on_err=None)

    def _on_credits(self, info: object):
        try:
            balance = getattr(info, "balance", None)
            if balance is None and isinstance(info, dict):
                balance = info.get("balance", info.get("credits", 0))
            self.credits_lbl.setText(f"积分: {balance if balance is not None else '--'}")
        except Exception:  # noqa: BLE001
            self.credits_lbl.setText("积分: --")

    def _check_update_async(self):
        def _job():
            return self.api.check_update()
        self._run_bg(_job, on_ok=self._on_update_info,
                     on_err=lambda msg: self.statusBar().showMessage(f"检查更新失败: {msg}", 5000))

    def _on_update_info(self, info: object):
        has = getattr(info, "has_update", False)
        if has:
            self.update_btn.setText("⬆❗")
            name = getattr(info, "version_name", "")
            QMessageBox.information(self, "发现新版本", f"新版本 {name} 可用。")
        else:
            QMessageBox.information(self, "检查更新", "当前已是最新版本。")

    # ==================================================================
    # 工具栏动作
    # ==================================================================
    def _open_search(self):
        if self.pages:
            self.nav.set_current(1)  # 切到书城页
            store = self.pages[1]
            fn = getattr(store, "focus_search", None)
            if callable(fn):
                fn()

    def _toggle_login(self):
        if self.auth.is_authenticated:
            if QMessageBox.question(
                    self, "退出登录", "确定要退出当前账号吗？",
                    QMessageBox.Yes | QMessageBox.No) == QMessageBox.Yes:
                self.auth.logout()
        else:
            self.require_login("请登录以继续。", lambda: None)

    def _toggle_night_mode(self, night: bool):
        self.config.night_mode = night
        try:
            self.config.save()
        except Exception:  # noqa: BLE001
            pass
        Theme.apply(QApplication.instance(), night)
        self.nav.apply_theme()
        # 触发各页面刷新配色
        for page in self.pages:
            fn = getattr(page, "retheme", None)
            if callable(fn):
                try:
                    fn()
                except Exception:  # noqa: BLE001
                    pass

    def _show_about(self):
        QMessageBox.about(
            self, "关于",
            f"{APP_TITLE}\n\n桌面有声书阅读器\n"
            "书架 / 书城 / 在线合成 / 阅读播放 / 社区 / 积分")

    # ==================================================================
    # 游客登录门控
    # ==================================================================
    def require_login(self, message: str = "", callback: Optional[Callable] = None):
        """游客可浏览，但写操作（合成/发帖/收藏/充值/上传）需登录。

        已登录 -> 直接执行 callback。
        未登录 -> 弹登录框，成功后执行 callback。
        返回是否最终处于登录态。
        """
        if self.auth.is_authenticated:
            if callback:
                callback()
            return True
        dlg = _LoginDialog(self.auth, message, self,
                            remembered_username=self.auth.remembered_username)
        if dlg.exec() == QDialog.Accepted:
            if callback:
                callback()
            return True
        return False

    # ==================================================================
    # 通用后台执行
    # ==================================================================
    def _run_bg(self, fn: Callable[[], object],
               on_ok: Optional[Callable[[object], None]] = None,
               on_err: Optional[Callable[[str], None]] = None):
        thread = _Background(fn, self)
        if on_ok:
            thread.worker.finished.connect(on_ok)
        if on_err:
            thread.worker.failed.connect(on_err)

        def _cleanup():
            if thread in self._workers:
                self._workers.remove(thread)
            thread.deleteLater()

        thread.finished.connect(_cleanup)
        self._workers.append(thread)
        thread.start()

    def set_network_status(self, text: str):
        self.net_lbl.setText(text)

    # ==================================================================
    # 关闭
    # ==================================================================
    def _detach_api_callbacks(self):
        """摘掉 ``api`` 上的全局回调（401 / 402）。

        ⚠️ ``api.on_unauthorized`` / ``api.on_insufficient_credits`` 是**普通
        callable**，不是 QObject 信号 ⇒ **不会随 MainWindow 析构自动断开**。
        关窗（或测试里销毁窗口）之后，后台线程若收到 401 仍会回调进来 ⇒
        弹「登录已过期」模态框（窗口都没了）或打到已析构对象上 ⇒ 崩溃。
        实测（2026-11-07）：PC 全量测试里因窗口没销毁，泄漏的 MainWindow 收到
        真实 401 → 弹模态框 → 离屏环境下 abort。
        """
        if not getattr(self, "_api_callbacks_attached", False):
            return
        self._api_callbacks_attached = False
        try:
            if self.api is not None:
                self.api.on_unauthorized = None
                self.api.on_insufficient_credits = None
        except Exception:  # noqa: BLE001
            pass

    def closeEvent(self, event):
        self._detach_api_callbacks()
        try:
            self.sse.disconnect()
        except Exception:  # noqa: BLE001
            pass
        self._settings.setValue(LAST_PAGE_KEY, max(0, self.nav.current_index))
        super().closeEvent(event)
