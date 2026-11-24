"""个人中心页：账号、积分、收藏、参考音频、单词本、设置、检查更新、存储管理。"""
from __future__ import annotations

import shutil
from pathlib import Path
from typing import List

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QScrollArea, QWidget,
    QGroupBox, QLineEdit, QSpinBox, QCheckBox, QListWidget, QListWidgetItem,
    QMessageBox, QFileDialog, QComboBox, QMenu, QHBoxLayout as HBox,
)

from app.models.models import Favorite, WordEntry

from .base_page import BasePage
from ..dialogs.recharge_dialog import RechargeDialog
from ..widgets.word_book_item import (
    ROLE_BOOK_NAME, ROLE_BOOK_KEY, ROLE_STORE_BOOK_ID,
    ROLE_SEGMENT_NUMBER, ROLE_LINE_NUMBER, ROLE_SENTENCE_TEXT,
    ROLE_PHONETIC, ROLE_WORD, ROLE_DEFINITION, ROLE_SERVER_ID, ROLE_SW,
    WordBookItemDelegate, source_label, word_tooltip,
)


# 单词本 item 数据角色：0..3 是原有的 word / definition / server_id / sw；
# 4..9 是收藏出处 6 键（wire camelCase，与服务端 / 跳转契约逐键对应）。
# 右键「跳转到原书」就读这 6 个角色组装 payload —— 用独立角色而非塞一个 dict，
# 是为了与收藏列表 (_load_favorites) 的 setData 风格一致、便于逐键取用。
#
# ⚠️ 真源已挪到 ``app/ui/widgets/word_book_item.py``（绘制器也要读同一批 role，
# 两边各写一份常量迟早会漂）。这里保留同名别名 —— 既有测试按名字从这里 import。
_WB_ROLE_BOOK_NAME = ROLE_BOOK_NAME
_WB_ROLE_BOOK_KEY = ROLE_BOOK_KEY
_WB_ROLE_STORE_BOOK_ID = ROLE_STORE_BOOK_ID
_WB_ROLE_SEGMENT_NUMBER = ROLE_SEGMENT_NUMBER
_WB_ROLE_LINE_NUMBER = ROLE_LINE_NUMBER
_WB_ROLE_SENTENCE_TEXT = ROLE_SENTENCE_TEXT


def _word_source_label(word) -> str:
    """出处副标题「书名 · 第 N 段/行」；无出处（旧收藏 6 键全 null）返回空串。

    与安卓 ``WordBookAdapter.buildSourceLabel`` / Web ``WordBook.vue.sourceText`` 同口径：
    段号优先，行号兜底，都没有就只显示书名。
    """
    return source_label(word.book_name, word.segment_number, word.line_number)


class ProfilePage(BasePage):
    """个人中心。

    信号:
        login_requested()        —— 请求打开登录对话框（由 MainWindow 处理）
        open_book(int book_id)   —— 从收藏跳转阅读
        store_book_opened(int)   —— 从收藏跳转书城书
        word_jump_requested(int, int, dict) —— 单词本「跳转到原书」：
            (book_id, store_book_id, 出处 6 键)。书身份在本页解析，定位信息单独走 dict。
        settings_changed()       —— 夜览/字号等偏好变更，主窗口可刷新外观
    """

    login_requested = Signal()
    open_book = Signal(int)
    store_book_opened = Signal(int)   # 收藏跳转书城书（store_book_id）
    # 单词本「跳转到原书」：拆成 (book_id, store_book_id, jump)。
    # 为什么不用单 Signal(dict)：书身份换算要查本页的 db（反查同名书城书 / 本地书架），
    # 把脏活留在本页，MainWindow 的连接就只是一行转发；jump 保持 wire 6 键原样，
    # 交给 reader_page.open_book 做按文本的句子定位。
    word_jump_requested = Signal(int, int, dict)
    settings_changed = Signal()

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._refs: List = []
        self._build_ui()

    # ==================================================================

    def _build_ui(self):
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        scroll = QScrollArea(self)
        scroll.setWidgetResizable(True)
        host = QWidget()
        self.v = QVBoxLayout(host)
        self.v.setContentsMargins(16, 16, 16, 16)
        self.v.setSpacing(12)
        scroll.setWidget(host)
        root.addWidget(scroll)

        # ---- 账号 ----
        acc = QGroupBox("账号")
        al = QHBoxLayout(acc)
        self.account_lbl = QLabel("游客")
        al.addWidget(self.account_lbl, 1)
        self.btn_login = QPushButton("登录 / 注册")
        self.btn_login.clicked.connect(self._on_login)
        self.btn_logout = QPushButton("退出登录")
        self.btn_logout.clicked.connect(self._on_logout)
        al.addWidget(self.btn_login)
        al.addWidget(self.btn_logout)
        self.v.addWidget(acc)

        # ---- 积分 ----
        cr = QGroupBox("积分")
        cl = QHBoxLayout(cr)
        self.credit_lbl = QLabel("余额：—    免费额度：—")
        cl.addWidget(self.credit_lbl, 1)
        btn_recharge = QPushButton("充值")
        btn_recharge.clicked.connect(self._open_recharge)
        cl.addWidget(btn_recharge)
        self.v.addWidget(cr)

        # ---- 我的收藏 ----
        fav_box = QGroupBox("我的收藏")
        fl = QVBoxLayout(fav_box)
        self.fav_list = QListWidget()
        # 给列表兜一个最小高度：这三个 QGroupBox 在同一个 QVBoxLayout 里均分空间，
        # 不设下限的话单词本（每行 46~66px）会被压到只剩一两行半，看不出内容。
        self.fav_list.setMinimumHeight(88)
        self.fav_list.itemDoubleClicked.connect(self._open_favorite)
        fl.addWidget(self.fav_list)
        fbtns = HBox()
        fbtns.addStretch(1)
        btn_fav_del = QPushButton("删除所选")
        btn_fav_del.clicked.connect(self._delete_favorite)
        fbtns.addWidget(btn_fav_del)
        fl.addLayout(fbtns)
        self.v.addWidget(fav_box)

        # ---- 参考音频 ----
        ref_box = QGroupBox("我的参考音频")
        rl = QVBoxLayout(ref_box)
        self.ref_list = QListWidget()
        self.ref_list.setMinimumHeight(88)
        rl.addWidget(self.ref_list)
        rbtns = HBox()
        btn_ref_up = QPushButton("上传")
        btn_ref_up.clicked.connect(self._upload_reference)
        btn_ref_del = QPushButton("删除所选")
        btn_ref_del.clicked.connect(self._delete_reference)
        rbtns.addStretch(1); rbtns.addWidget(btn_ref_up); rbtns.addWidget(btn_ref_del)
        rl.addLayout(rbtns)
        self.v.addWidget(ref_box)

        # ---- 单词本 ----
        wb_box = QGroupBox("单词本")
        wl = QVBoxLayout(wb_box)
        self.word_list = QListWidget()
        self.word_list.itemDoubleClicked.connect(self._show_word)
        # Qt 桌面端没有「长按」手势：右键菜单（QMenu + CustomContextMenu）是 PC 端
        # 与 Android/Web「长按单词」等价的交互，用来弹「跳转到原书」。
        self.word_list.setContextMenuPolicy(Qt.CustomContextMenu)
        self.word_list.customContextMenuRequested.connect(self._on_word_context_menu)
        # 列表项自己画（词+音标 / 首条释义 / 出处 三行）——QListWidget 不解析 HTML，
        # 详见 app/ui/widgets/word_book_item.py 的模块说明。
        # 关掉 wordWrap：行高完全交给 delegate 的 sizeHint，避免 Qt 自己按文本再算一遍。
        self.word_list.setWordWrap(False)
        self.word_list.setItemDelegate(WordBookItemDelegate(self.word_list))
        # 单词本每行 46~66px（三行结构）⇒ 至少留 4 行的空间，否则只剩一两行半。
        self.word_list.setMinimumHeight(196)
        wl.addWidget(self.word_list)
        wbtns = HBox()
        wbtns.addStretch(1)
        btn_word_del = QPushButton("删除所选")
        btn_word_del.clicked.connect(self._delete_word)
        wbtns.addWidget(btn_word_del)
        wl.addLayout(wbtns)
        self.v.addWidget(wb_box)

        # ---- 离线词典 ----
        dict_box = QGroupBox("离线词典")
        dl = QVBoxLayout(dict_box)
        self.dict_lbl = QLabel("词典状态：检测中…")
        dl.addWidget(self.dict_lbl)
        dbtns = HBox()
        self.btn_dict_download = QPushButton("下载 / 更新词典")
        self.btn_dict_download.clicked.connect(self._download_dict)
        dbtns.addWidget(self.btn_dict_download)
        self.btn_dict_uninstall = QPushButton("卸载词典")
        self.btn_dict_uninstall.clicked.connect(self._uninstall_dict)
        dbtns.addWidget(self.btn_dict_uninstall)
        dbtns.addStretch(1)
        dl.addLayout(dbtns)
        self.v.addWidget(dict_box)

        # ---- 设置 ----
        st = QGroupBox("设置")
        sl = QVBoxLayout(st)

        row1 = HBox()
        self.chk_night = QCheckBox("夜间模式")
        self.chk_night.toggled.connect(self._on_night_toggle)
        row1.addWidget(self.chk_night)
        row1.addSpacing(24)
        row1.addWidget(QLabel("默认字号："))
        self.spin_font = QSpinBox()
        self.spin_font.setRange(12, 40)
        self.spin_font.valueChanged.connect(self._on_font_change)
        row1.addWidget(self.spin_font)
        row1.addStretch(1)
        sl.addLayout(row1)

        # MOSS 引擎
        sl.addWidget(QLabel("本机合成引擎（MOSS）："))
        self.edit_project = QLineEdit(); self.edit_project.setPlaceholderText("项目路径 moss_project_path")
        self.edit_python = QLineEdit(); self.edit_python.setPlaceholderText("python 可执行文件 moss_python_exe")
        self.edit_model = QLineEdit(); self.edit_model.setPlaceholderText("模型目录 moss_model_dir")
        for lbl, w in (("项目路径", self.edit_project), ("Python", self.edit_python), ("模型目录", self.edit_model)):
            r = HBox(); r.addWidget(QLabel(lbl)); r.addWidget(w, 1); sl.addLayout(r)
        row2 = HBox()
        row2.addWidget(QLabel("线程数"))
        self.spin_threads = QSpinBox(); self.spin_threads.setRange(1, 128)
        row2.addWidget(self.spin_threads)
        row2.addSpacing(16)
        row2.addWidget(QLabel("推理后端"))
        self.combo_provider = QComboBox(); self.combo_provider.addItems(["cpu", "cuda", "dml", "coreml"])
        row2.addWidget(self.combo_provider)
        row2.addStretch(1)
        sl.addLayout(row2)

        btn_save = QPushButton("保存设置")
        btn_save.clicked.connect(self._save_settings)
        sl.addWidget(btn_save)
        self.v.addWidget(st)

        # ---- 检查更新 / 存储 ----
        bottom = QGroupBox("关于与存储")
        bl = QVBoxLayout(bottom)
        brow = HBox()
        btn_update = QPushButton("检查更新")
        btn_update.clicked.connect(self._check_update)
        brow.addWidget(btn_update)
        brow.addStretch(1)
        bl.addLayout(brow)
        self.disk_lbl = QLabel("存储占用：计算中…")
        bl.addWidget(self.disk_lbl)
        btn_clear = QPushButton("清理音频缓存")
        btn_clear.clicked.connect(self._clear_audio_cache)
        bl.addWidget(btn_clear)
        self.v.addWidget(bottom)

        self.v.addStretch(1)

    # ==================================================================
    # 刷新
    # ==================================================================

    def refresh(self):
        self._apply_login_state()
        self._load_credits()
        self._sync_and_load_favorites()
        self._load_references()
        self._sync_and_load_words()
        self._load_settings_fields()
        self._calc_storage()
        self._refresh_dict_state()

    def auth_changed(self, is_auth: bool, username):
        self._apply_login_state()
        self.refresh()

    def _apply_login_state(self):
        authed = self.config.is_authenticated
        self.account_lbl.setText(f"已登录：{self.config.username or ''}" if authed else "游客（未登录）")
        self.btn_login.setVisible(not authed)
        self.btn_logout.setVisible(authed)

    # ---------- 账号 ----------

    def _on_login(self):
        self.login_requested.emit()

    def _on_logout(self):
        self.auth.logout()
        self._apply_login_state()

    # ---------- 积分 ----------

    def _load_credits(self):
        if not self.config.is_authenticated:
            self.credit_lbl.setText("余额：—（登录后可查看）")
            return
        def _f():
            return self.api.get_credits()
        self.run_async(_f,
                       on_result=lambda c: self.credit_lbl.setText(
                           f"余额：{getattr(c, 'balance', 0)}    免费额度：{getattr(c, 'free_trials_remaining', 0)}"),
                       on_error=lambda m: self.credit_lbl.setText(f"余额读取失败：{m}"))

    def _open_recharge(self):
        if not self.config.is_authenticated:
            QMessageBox.information(self, "需要登录", "充值需先登录账号。")
            self.login_requested.emit()
            return
        dlg = RechargeDialog(self.api, self)
        dlg.credits_changed.connect(self._load_credits)
        dlg.exec()

    # ---------- 收藏 ----------

    def _sync_and_load_favorites(self):
        """先即时渲染本地，再后台拉一次服务端收藏并刷新（跨端互通：安卓新增也能马上看到）。"""
        self._load_favorites()
        if self.sync is not None and self.config.is_authenticated:
            def _pull():
                try:
                    return self.sync.pull_favorites()
                except Exception:  # noqa: BLE001
                    return None
            self.run_async(_pull, on_result=lambda _r: self._load_favorites())

    def _load_favorites(self):
        self.fav_list.clear()
        for f in self.db.get_favorites():
            preview = (f.text[:30] + "…") if len(f.text) > 30 else f.text
            it = QListWidgetItem(f"{preview}  —— {f.created_at}")
            it.setData(Qt.UserRole, f.id)
            it.setData(Qt.UserRole + 1, f.book_id)
            it.setData(Qt.UserRole + 2, f.server_id or 0)
            it.setData(Qt.UserRole + 3, f.store_book_id or 0)
            it.setData(Qt.UserRole + 4, f.book_name or "")
            self.fav_list.addItem(it)
        if self.fav_list.count() == 0:
            self.fav_list.addItem(QListWidgetItem("（暂无收藏，可在阅读器中选中文字右键收藏）"))

    def _open_favorite(self, item: QListWidgetItem):
        book_id = int(item.data(Qt.UserRole + 1) or 0)
        store_book_id = int(item.data(Qt.UserRole + 3) or 0)
        # 书城书：优先用收藏时记下的 store_book_id；跨端同步回来的收藏没有该字段，
        # 再按书名反查本机书架兜底（仅命中唯一同名书城书时跳转）。
        if not store_book_id:
            fname = item.data(Qt.UserRole + 4) or ""
            if fname:
                try:
                    store_book_id = self.db.find_store_book_id_by_name(str(fname)) or 0
                except Exception:  # noqa: BLE001
                    store_book_id = 0
        if store_book_id:
            self.store_book_opened.emit(store_book_id)
            return
        if book_id:
            self.open_book.emit(book_id)

    def _delete_favorite(self):
        item = self.fav_list.currentItem()
        if not item:
            return
        fid = int(item.data(Qt.UserRole) or 0)
        sid = int(item.data(Qt.UserRole + 2) or 0)
        if sid:
            # 有云端身份：先删服务端（best-effort），再按 server_id 删本地，
            # 否则下次同步会以「服务端为真源」把它拉回来。
            def _do():
                try:
                    self.api.delete_favorite(sid)
                except Exception:  # noqa: BLE001
                    pass
                try:
                    self.db.delete_favorite_by_server_id(sid)
                except Exception:  # noqa: BLE001
                    pass
            self.run_async(_do, on_result=lambda *_: self._load_favorites())
        elif fid:
            try:
                self.db.delete_favorite(fid)
            except Exception as e:  # noqa: BLE001
                QMessageBox.warning(self, "失败", str(e))
            self._load_favorites()

    # ---------- 参考音频 ----------

    def _load_references(self):
        self.ref_list.clear()
        if not self.config.is_authenticated:
            self.ref_list.addItem(QListWidgetItem("（登录后可管理参考音频）"))
            return
        def _f():
            return self.api.get_references()
        self.run_async(_f, on_result=self._on_references,
                       on_error=lambda m: self.ref_list.addItem(QListWidgetItem(f"加载失败：{m}")))

    def _on_references(self, refs):
        self.ref_list.clear()
        refs = refs or []
        self._refs = refs
        if not refs:
            self.ref_list.addItem(QListWidgetItem("（还没有参考音频）"))
            return
        for r in refs:
            rid = r.get("id") if isinstance(r, dict) else getattr(r, "id", None)
            name = r.get("name") if isinstance(r, dict) else getattr(r, "name", "音频")
            it = QListWidgetItem(str(name))
            it.setData(Qt.UserRole, rid)
            self.ref_list.addItem(it)

    def _upload_reference(self):
        if not self.config.is_authenticated:
            QMessageBox.information(self, "需要登录", "上传需先登录。")
            return
        path, _ = QFileDialog.getOpenFileName(self, "选择参考音频", "", "音频 (*.wav *.mp3 *.m4a *.flac)")
        if not path:
            return
        self.run_async(lambda: self.api.upload_reference(path),
                       on_result=lambda d: self._load_references(),
                       on_error=lambda m: QMessageBox.warning(self, "上传失败", m))

    def _delete_reference(self):
        item = self.ref_list.currentItem()
        if not item:
            return
        rid = int(item.data(Qt.UserRole) or 0)
        if not rid:
            return
        def _f():
            return self.api.delete_reference(rid)
        self.run_async(_f, on_result=lambda *_a: self._load_references(),
                       on_error=lambda m: QMessageBox.warning(self, "删除失败", m))

    # ---------- 单词本 ----------

    def _sync_and_load_words(self):
        """先即时渲染本地，再后台拉一次服务端生词本并刷新（跨端互通：安卓收藏的词也能看到）。"""
        self._load_words()
        if self.sync is not None and self.config.is_authenticated:
            def _pull():
                try:
                    return self.sync.pull_word_book()
                except Exception:  # noqa: BLE001
                    return None
            self.run_async(_pull, on_result=lambda _r: self._load_words())

    def _load_words(self):
        self.word_list.clear()
        for w in self.db.get_words():
            # 显示内容对齐安卓/ Web：词 + 音标 + 首条释义 + 出处。
            # 之前只拼了「词 + 音标」，释义（明明存在 UserRole+1 里）和出处全被丢掉，
            # 单词本看起来像只有一串音标，用户无法判断收藏的是哪个词 / 哪本书。
            # 渲染交给 WordBookItemDelegate（自己画三行）；item 的 text 只留单词本身，
            # 便于复制/朗读/测试按单词找行。
            it = QListWidgetItem(w.word or "")
            it.setData(ROLE_WORD, w.word)
            it.setData(ROLE_DEFINITION, w.definition)
            it.setData(ROLE_SERVER_ID, w.server_id or 0)
            it.setData(ROLE_SW, w.sw or "")
            it.setData(ROLE_PHONETIC, w.phonetic or "")
            # 出处 6 键：旧数据 / 服务端返回 None 时为 None，右键菜单据此禁用跳转。
            it.setData(ROLE_BOOK_NAME, w.book_name)
            it.setData(ROLE_BOOK_KEY, w.book_key)
            it.setData(ROLE_STORE_BOOK_ID, w.store_book_id)
            it.setData(ROLE_SEGMENT_NUMBER, w.segment_number)
            it.setData(ROLE_LINE_NUMBER, w.line_number)
            it.setData(ROLE_SENTENCE_TEXT, w.sentence_text)
            # 完整释义（多行）+ 出处放 tooltip：列表里只画首条释义（要速览）。
            it.setToolTip(word_tooltip(w.word, w.phonetic, w.definition,
                                       _word_source_label(w)))
            self.word_list.addItem(it)
        if self.word_list.count() == 0:
            self.word_list.addItem(QListWidgetItem("（单词本为空，阅读器查词后点「☆ 加入单词本」收藏）"))

    @staticmethod
    def _word_jump_payload(item: QListWidgetItem) -> dict:
        """从 item 的 6 个出处角色还原 wire 6 键 dict（None 保持 None）。"""
        return {
            "bookName": item.data(_WB_ROLE_BOOK_NAME) or None,
            "bookKey": item.data(_WB_ROLE_BOOK_KEY) or None,
            "storeBookId": item.data(_WB_ROLE_STORE_BOOK_ID) or None,
            "segmentNumber": item.data(_WB_ROLE_SEGMENT_NUMBER) or None,
            "lineNumber": item.data(_WB_ROLE_LINE_NUMBER) or None,
            "sentenceText": item.data(_WB_ROLE_SENTENCE_TEXT) or None,
        }

    def _on_word_context_menu(self, pos):
        """单词本右键菜单（PC 端「长按」等价交互）→「跳转到原书」。"""
        item = self.word_list.itemAt(pos)
        if item is None:
            return
        jump = self._word_jump_payload(item)
        # 与服务端 WordBookItemRequest.hasSource() 同口径：bookName / bookKey 至少一个
        # 非空才算「有出处」。旧数据 / 服务端全 None ⇒ 禁用菜单项并提示，绝不崩。
        has_source = bool(jump.get("bookName") or jump.get("bookKey"))
        menu = QMenu(self)
        act = menu.addAction("跳转到原书" if has_source else "跳转到原书（该词无收藏出处）")
        act.setEnabled(has_source)
        if menu.exec(self.word_list.mapToGlobal(pos)) is not act or not has_source:
            return
        book_id, store_book_id = self._resolve_word_book_target(jump)
        if not book_id and not store_book_id:
            QMessageBox.information(self, "跳转失败", "本机书架里找不到这本书，无法跳转。")
            return
        self.word_jump_requested.emit(book_id, store_book_id, jump)

    def _resolve_word_book_target(self, jump: dict):
        """把收藏出处换算成本机可打开的书身份 ``(book_id, store_book_id)``。

        照搬 _open_favorite 口径：书城书优先用 storeBookId（或 bookKey 的 s: 前缀），
        再按 bookName 反查唯一同名书城书；本地书按 bookName 在本机书架找唯一同名行。
        都找不到返回 (0, 0)，由调用方提示 —— 不静默无反应。
        """
        sid = jump.get("storeBookId")
        bk = str(jump.get("bookKey") or "")
        if not sid and bk.startswith("s:"):
            try:
                sid = int(bk[2:])
            except (TypeError, ValueError):
                sid = None
        name = jump.get("bookName") or ""
        if not sid and name:
            try:
                sid = self.db.find_store_book_id_by_name(str(name)) or 0
            except Exception:  # noqa: BLE001
                sid = 0
        if sid:
            return 0, int(sid)
        # 本地书：按书名在本机书架找唯一同名行（与 _open_favorite 的 book_id 分支同族）
        if name:
            try:
                matches = [b for b in self.db.get_books() if b.display_name == name]
                if len(matches) == 1:
                    return int(matches[0].id), 0
            except Exception:  # noqa: BLE001
                pass
        return 0, 0

    def _show_word(self, item: QListWidgetItem):
        word = item.data(ROLE_WORD) or ""
        defn = item.data(ROLE_DEFINITION) or "（无释义）"
        QMessageBox.information(self, f"单词：{word}", defn)

    def _delete_word(self):
        item = self.word_list.currentItem()
        if not item:
            return
        word = item.data(ROLE_WORD) or ""
        sid = int(item.data(ROLE_SERVER_ID) or 0)
        sw = item.data(ROLE_SW) or ""
        if not word:
            return
        if sid or sw:
            # 有云端身份（server_id 或 sw）：先删服务端(best-effort)再删本地，
            # 否则下次同步会以「服务端为真源」把它拉回来。
            def _do():
                try:
                    if sid:
                        self.api.delete_word_book(sid)
                    elif sw:
                        self.api.delete_word_book_by_sw(sw)
                except Exception:  # noqa: BLE001
                    pass
                try:
                    self.db.delete_word(word)
                except Exception:  # noqa: BLE001
                    pass
            self.run_async(_do, on_result=lambda *_: self._load_words())
        else:
            self.db.delete_word(word)
            self._load_words()

    # ---------- 设置 ----------

    def _load_settings_fields(self):
        self.chk_night.setChecked(self.config.night_mode)
        self.spin_font.setValue(self.config.font_size)
        self.edit_project.setText(self.config.moss_project_path or "")
        self.edit_python.setText(self.config.moss_python_exe or "")
        self.edit_model.setText(self.config.moss_model_dir or "")
        self.spin_threads.setValue(self.config.moss_cpu_threads)
        idx = self.combo_provider.findText(self.config.moss_execution_provider or "cpu")
        self.combo_provider.setCurrentIndex(max(0, idx))

    def _on_night_toggle(self, on: bool):
        self.config.night_mode = on
        self.config.save()
        self.settings_changed.emit()

    def _on_font_change(self, pt: int):
        self.config.font_size = pt
        self.config.save()
        self.settings_changed.emit()

    def _save_settings(self):
        self.config.moss_project_path = self.edit_project.text().strip()
        self.config.moss_python_exe = self.edit_python.text().strip()
        self.config.moss_model_dir = self.edit_model.text().strip()
        self.config.moss_cpu_threads = int(self.spin_threads.value())
        self.config.moss_execution_provider = self.combo_provider.currentText()
        try:
            self.config.save()
            QMessageBox.information(self, "已保存", "设置已保存。")
            self.settings_changed.emit()
        except Exception as e:  # noqa: BLE001
            QMessageBox.warning(self, "保存失败", str(e))

    # ---------- 检查更新 ----------

    def _check_update(self):
        def _f():
            return self.api.check_update(41)
        self.run_async(_f, on_result=self._on_update,
                       on_error=lambda m: QMessageBox.warning(self, "检查更新失败", m))

    def _on_update(self, info):
        if not info:
            QMessageBox.information(self, "检查更新", "无法获取版本信息。")
            return
        if getattr(info, "has_update", False):
            msg = (f"发现新版本 {info.version_name}\n\n更新内容：\n{info.changelog or '（无）'}\n\n"
                   f"下载地址：{info.download_url or '（无）'}")
            QMessageBox.information(self, "检查更新", msg)
        else:
            QMessageBox.information(self, "检查更新", "已是最新版本。")

    # ---------- 存储 ----------

    def _calc_storage(self):
        def _f():
            total = 0
            for d in (self.config.audio_dir, self.config.resolved_data_dir):
                try:
                    for p in Path(d).rglob("*"):
                        if p.is_file():
                            total += p.stat().st_size
                except Exception:  # noqa: BLE001
                    continue
            return total
        self.run_async(_f, on_result=lambda b: self.disk_lbl.setText(
            f"存储占用：约 {b / (1024 * 1024):.1f} MB"))

    def _clear_audio_cache(self):
        if QMessageBox.question(self, "清理缓存",
                                 f"将删除音频缓存目录内容：\n{self.config.audio_dir}\n\n确定继续？") \
                != QMessageBox.Yes:
            return
        try:
            d = self.config.audio_dir
            for p in Path(d).glob("*"):
                if p.is_file():
                    p.unlink()
                elif p.is_dir():
                    shutil.rmtree(p, ignore_errors=True)
        except Exception as e:  # noqa: BLE001
            QMessageBox.warning(self, "清理失败", str(e))
            return
        self._calc_storage()

    # ==================================================================
    # 离线词典
    # ==================================================================

    def _refresh_dict_state(self):
        """刷新词典状态标签与按钮可用性。

        ⚠️ ``row_count()`` 在没有安装信息时会走 ``SELECT COUNT(*)``（76 万行全表扫描），
        所以这里放到后台线程跑，不能在主线程调 —— 否则「我的」页每次打开都会卡一下。
        """
        dm = self.dictionary
        if dm is None:
            self.dict_lbl.setText("词典状态：词典服务未就绪")
            self.btn_dict_download.setEnabled(False)
            self.btn_dict_uninstall.setEnabled(False)
            return

        try:
            installed = bool(self.call(dm, "is_ready", default=False))
        except Exception:  # noqa: BLE001
            installed = False

        if not installed:
            self.dict_lbl.setText("词典状态：未安装（阅读页查词不可用）")
            self.btn_dict_download.setEnabled(True)
            self.btn_dict_uninstall.setEnabled(False)
            return

        self.btn_dict_download.setEnabled(True)
        self.btn_dict_uninstall.setEnabled(True)

        def _count():
            return int(self.call(dm, "row_count", default=0))

        def _done(n: int):
            size_mb = 0.0
            try:
                # ⚠️ 不能用 ``self.call(dm, "db_path")``：``call`` 内部是
                #   ``getattr(obj, method)`` + 若是 callable 才调用 —— 而 ``db_path``
                #   是 **property**，getattr 拿到的是 property 对象本身，**不是路径字符串**。
                #   对 property 直接取值即可。
                p = dm.db_path
                if p is not None and Path(p).is_file():
                    size_mb = Path(p).stat().st_size / (1024 * 1024)
            except Exception:  # noqa: BLE001
                pass
            self.dict_lbl.setText(f"词典状态：已安装（{n} 词条，约 {size_mb:.0f} MB）")

        self.run_async(_count, on_result=_done)

    def _download_dict(self):
        """下载并安装离线词典（后台线程）。

        ⚠️ 24MB 的下载 + MD5 + 解压绝不能放主线程 —— 会把「我的」页冻住。
        """
        dm = self.dictionary
        if dm is None:
            QMessageBox.information(self, "词典", "词典服务未就绪。")
            return

        self.btn_dict_download.setEnabled(False)
        self.dict_lbl.setText("词典状态：下载中…（首次约 24MB，解压后约 58MB）")

        def _work():
            return dm.download_dict()

        def _ok(_path):
            self._refresh_dict_state()
            QMessageBox.information(self, "词典", "词典安装完成，现在可以在阅读页查词了。")

        def _err(msg: str):
            self._refresh_dict_state()
            QMessageBox.warning(self, "词典安装失败", msg or "未知错误")

        self.run_async(_work, on_result=_ok, on_error=_err)

    def _uninstall_dict(self):
        dm = self.dictionary
        if dm is None:
            return
        if QMessageBox.question(self, "卸载词典",
                                 "将删除本地词典库（约 58MB）。\n\n确定继续？") \
                != QMessageBox.Yes:
            return
        try:
            ok = bool(self.call(dm, "uninstall", default=False))
        except Exception as e:  # noqa: BLE001
            QMessageBox.warning(self, "卸载失败", str(e))
            return
        if not ok:
            QMessageBox.warning(self, "卸载失败", "词典文件未能完全删除（可能正被占用）。")
        self._refresh_dict_state()

    # ==================================================================

    def showEvent(self, event):
        super().showEvent(event)
        self.refresh()
