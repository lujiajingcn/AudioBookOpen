"""书城页：分类 / 搜索 / 分页浏览，点击进入详情，支持在线阅读 / 下载 / 合成。"""
from __future__ import annotations

from typing import List, Optional

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QLineEdit, QScrollArea,
    QWidget, QGridLayout, QDialog, QSizePolicy, QMessageBox, QFrame,
)

from app.models.models import StoreBook, TocEntry

from .base_page import BasePage, BookCard as RealBookCard


_PAGE_SIZE = 20


def _parse_books(data) -> tuple[List[StoreBook], int]:
    """把 get_store_books 的响应解析成 (books, total)。响应结构在两端略有差异，尽量兼容。"""
    if isinstance(data, list):
        items, total = data, len(data)
    else:
        d = data or {}
        items = d.get("content") or d.get("items") or d.get("books") or []
        total = d.get("totalElements") or d.get("total") or d.get("totalCount") or len(items)
    books = []
    for b in items:
        if isinstance(b, StoreBook):
            books.append(b)
            continue
        books.append(StoreBook(
            id=b.get("id", 0), title=b.get("title", ""), author=b.get("author", ""),
            category=b.get("category", ""), description=b.get("description", ""),
            file_extension=b.get("fileExtension", ""), file_size=b.get("fileSize", 0),
            cover_color=b.get("coverColor", ""), download_count=b.get("downloadCount", 0),
            task_id=b.get("taskId"), status=b.get("status", "onshelf"),
            tags=list(b.get("tags") or []),
        ))
    return books, int(total or 0)


class _FallbackBookCard(QFrame):
    """真实 BookCard 缺失时的占位卡片。"""

    clicked = Signal(object)  # StoreBook

    def __init__(self, book: StoreBook, parent=None):
        super().__init__(parent)
        self.book = book
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedSize(150, 210)
        self.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Fixed)
        self.setStyleSheet("QFrame{background:#fff;border:1px solid #ddd;border-radius:6px;}")
        lay = QVBoxLayout(self)
        cover = QLabel()
        cover.setFixedSize(120, 150)
        cover.setAlignment(Qt.AlignCenter)
        color = getattr(book, "cover_color", "") or "#cfd8dc"
        cover.setStyleSheet(f"background:{color};color:#fff;border-radius:4px;")
        cover.setText((book.title or "?")[:4])
        lay.addWidget(cover)
        t = QLabel(book.title or "(无标题)")
        t.setWordWrap(True)
        t.setStyleSheet("font-weight:bold;")
        lay.addWidget(t)
        a = QLabel(book.author or "")
        a.setStyleSheet("color:#888;")
        lay.addWidget(a)
        lay.addStretch(1)

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self.clicked.emit(self.book)
        super().mousePressEvent(event)


class StoreDetailDialog(QDialog):
    """书城书详情：标题/作者/简介/目录预览 + 在线阅读 / 下载 / 合成。"""

    read_requested = Signal(int)
    download_requested = Signal(int)
    synthesize_requested = Signal(int)
    audio_download_requested = Signal(int)

    def __init__(self, book: StoreBook, toc: List[TocEntry], parent=None):
        super().__init__(parent)
        self.book = book
        self.setWindowTitle(book.title or "书籍详情")
        self.setMinimumSize(460, 520)

        lay = QVBoxLayout(self)
        title = QLabel(book.title or "(无标题)")
        tf = title.font(); tf.setPointSize(tf.pointSize() + 4); tf.setBold(True); title.setFont(tf)
        title.setWordWrap(True)
        lay.addWidget(title)

        meta = QLabel(f"作者：{book.author or '未知'}    分类：{book.category or '—'}    "
                      f"标签：{'、'.join(book.tags) if book.tags else '—'}    "
                      f"下载：{book.download_count}")
        meta.setStyleSheet("color:#666;")
        meta.setWordWrap(True)
        lay.addWidget(meta)

        desc = QLabel(book.description or "（暂无简介）")
        desc.setWordWrap(True)
        desc.setStyleSheet("margin:8px 0;")
        lay.addWidget(desc)

        lay.addWidget(QLabel("目录预览："))
        self._toc_lbl = QLabel(self._toc_text(toc))
        self._toc_lbl.setWordWrap(True)
        self._toc_lbl.setStyleSheet("background:#f5f5f5;padding:8px;border-radius:4px;color:#444;")
        lay.addWidget(self._toc_lbl, 1)

        btns = QHBoxLayout()
        self.btn_read = QPushButton("在线阅读")
        self.btn_read.clicked.connect(lambda: self._fire(self.read_requested))
        self.btn_download = QPushButton("下载")
        self.btn_download.clicked.connect(lambda: self._fire(self.download_requested))
        self.btn_synth = QPushButton("合成")
        self.btn_synth.clicked.connect(lambda: self._fire(self.synthesize_requested))
        btns.addWidget(self.btn_read)
        btns.addWidget(self.btn_download)
        # 已合成有声的书（task_id 非空）才提供「下载音频」；未合成的书点它只会拿到空列表
        if book.task_id:
            self.btn_audio = QPushButton("下载音频")
            self.btn_audio.clicked.connect(lambda: self._fire(self.audio_download_requested))
            btns.addWidget(self.btn_audio)
        btns.addWidget(self.btn_synth)
        lay.addLayout(btns)

    @staticmethod
    def _toc_text(toc: List[TocEntry]) -> str:
        txt = "\n".join(f"· {e.title}" for e in (toc or [])[:15]) or "（无目录）"
        if toc and len(toc) > 15:
            txt += f"\n…（共 {len(toc)} 章）"
        return txt

    def _replace_toc(self, toc: List[TocEntry]):
        try:
            self._toc_lbl.setText(self._toc_text(toc))
        except Exception:  # noqa: BLE001
            pass

    def _fire(self, sig):
        sig.emit(self.book.id)
        self.accept()


class StorePage(BasePage):
    """书城页。

    信号（由 MainWindow 负责路由到阅读器 / TTS 等）:
        read_requested(int store_book_id)
        download_requested(int store_book_id, title)
        synthesize_requested(int store_book_id, title)
    """

    read_requested = Signal(int)
    download_requested = Signal(int, str)
    synthesize_requested = Signal(int, str)
    download_audio_requested = Signal(int)
    # 书城音频下载进度桥（后台线程 emit → 主线程槽，跨线程自动排队）
    audio_dl_progress = Signal(int, int, int)
    audio_dl_done = Signal(bool, str)

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._page = 0
        self._total = 0
        self._category = ""
        self._keyword = ""
        # 当前选中的标签（空串 = 不按标签筛选）；与分类/关键词叠加，不是互斥切换
        self._tag = ""
        self._books: List[StoreBook] = []

        root = QVBoxLayout(self)
        root.setContentsMargins(12, 12, 12, 12)

        head = QHBoxLayout()
        tf = QLabel("书城")
        _f = tf.font(); _f.setPointSize(_f.pointSize() + 4); _f.setBold(True); tf.setFont(_f)
        head.addWidget(tf)
        head.addStretch(1)
        self.search = QLineEdit()
        self.search.setPlaceholderText("搜索书名 / 作者…")
        self.search.returnPressed.connect(self._on_search)
        head.addWidget(self.search, 1)
        btn_search = QPushButton("搜索")
        btn_search.clicked.connect(self._on_search)
        head.addWidget(btn_search)
        root.addLayout(head)

        self.cat_bar = QHBoxLayout()
        self.cat_buttons: List[QPushButton] = []
        root.addLayout(self.cat_bar)

        # 标签栏：分类之下的第二维筛选（分类单选归属 / 标签多选描述，筛选时按「与」组合）。
        # 没有标签时这一行不放任何控件，布局高度自然为 0，不留空白。
        self.tag_bar = QHBoxLayout()
        self.tag_buttons: List[QPushButton] = []
        root.addLayout(self.tag_bar)

        self.scroll = QScrollArea(self)
        self.scroll.setWidgetResizable(True)
        self.grid_host = QWidget()
        self.grid = QGridLayout(self.grid_host)
        self.grid.setSpacing(12)
        self.grid.setAlignment(Qt.AlignTop | Qt.AlignLeft)
        self.scroll.setWidget(self.grid_host)
        root.addWidget(self.scroll, 1)

        self.info = QLabel("")
        self.info.setStyleSheet("color:#888;")
        root.addWidget(self.info)

        nav = QHBoxLayout()
        self.btn_prev = QPushButton("上一页")
        self.btn_prev.clicked.connect(lambda: self._goto(self._page - 1))
        self.btn_next = QPushButton("下一页")
        self.btn_next.clicked.connect(lambda: self._goto(self._page + 1))
        self.page_lbl = QLabel("第 1 页")
        nav.addWidget(self.btn_prev)
        nav.addStretch(1)
        nav.addWidget(self.page_lbl)
        nav.addStretch(1)
        nav.addWidget(self.btn_next)
        root.addLayout(nav)

        self._build_categories()
        self._build_tags()
        self.refresh()

    # ---------- 分类 ----------

    def _build_categories(self):
        # “全部”先占位，分类异步拉取（不阻塞 UI 线程）
        self._add_category_button("全部", "", checked=True)
        self.cat_bar.addStretch(1)
        self.run_async(self._safe_categories, on_result=self._on_categories)

    def _safe_categories(self):
        try:
            return list(self.api.get_store_categories() or [])
        except Exception:  # noqa: BLE001
            return []

    def _on_categories(self, cats):
        # 移除末尾 stretch，插入分类按钮后再补回
        while self.cat_bar.count() and self.cat_bar.itemAt(self.cat_bar.count() - 1).widget() is None:
            self.cat_bar.takeAt(self.cat_bar.count() - 1)
        for c in (cats or []):
            self._add_category_button(c, c, checked=False)
        self.cat_bar.addStretch(1)

    def _add_category_button(self, label: str, cat: str, checked: bool):
        b = QPushButton(label)
        b.setCheckable(True)
        b.setChecked(checked)
        b.clicked.connect(lambda _=False, val=cat: self._select_category(val))
        self.cat_bar.addWidget(b)
        self.cat_buttons.append(b)

    def _select_category(self, cat: str):
        self._category = cat
        for b in self.cat_buttons:
            b.setChecked(b.text() == (cat or "全部"))
        self._goto(0)

    # ---------- 标签 ----------

    def _build_tags(self):
        # 与分类一样异步拉取，不阻塞 UI 线程；拉不到就整行留空（服务端旧版本无此端点）
        self.run_async(self._safe_tags, on_result=self._on_tags)

    def _safe_tags(self):
        try:
            return list(self.api.get_store_tags() or [])
        except Exception:  # noqa: BLE001
            return []

    def _on_tags(self, tags):
        # 清空重建（拉动标签栏时数量/顺序可能变化，逐个复用反而更容易错位）
        while self.tag_bar.count():
            item = self.tag_bar.takeAt(0)
            w = item.widget()
            if w is not None:
                w.deleteLater()
        self.tag_buttons.clear()
        if not tags:
            return
        for name in tags[:20]:
            b = QPushButton(name)
            b.setCheckable(True)
            b.setChecked(name == self._tag)
            # 字号比分类按钮小一号：这一行是「描述性筛选」，视觉权重低于分类
            b.setStyleSheet("QPushButton{font-size:11px;padding:2px 8px;}")
            b.clicked.connect(lambda _=False, val=name: self._select_tag(val))
            self.tag_bar.addWidget(b)
            self.tag_buttons.append(b)
        self.tag_bar.addStretch(1)

    def _select_tag(self, tag: str):
        # 再点一次已选中的标签 = 取消标签筛选（与 Android / Web 的 toggle 语义一致）
        self._tag = "" if tag == self._tag else tag
        for b in self.tag_buttons:
            b.setChecked(b.text() == self._tag)
        self._goto(0)

    # ---------- 加载 ----------

    def refresh(self):
        self._load_page(self._page)

    def _on_search(self):
        self._keyword = self.search.text().strip()
        self._goto(0)

    def _goto(self, page: int):
        page = max(0, page)
        self._load_page(page)

    def _load_page(self, page: int):
        self.info.setText("加载中…")
        b = self.run_async(
            lambda: self.api.get_store_books(page=page, size=_PAGE_SIZE,
                                             category=self._category, keyword=self._keyword,
                                             tag=self._tag),
            on_result=lambda data, p=page: self._on_books(data, p),
            on_error=lambda m: self.info.setText(f"加载失败：{m}"),
        )

    def _on_books(self, data, page: int):
        self._page = page
        books, total = _parse_books(data)
        self._books = books
        self._total = total
        self._render_cards()
        pages = max(1, (total + _PAGE_SIZE - 1) // _PAGE_SIZE) if total else 1
        self.page_lbl.setText(f"第 {page + 1} / {pages} 页")
        self.info.setText(f"共 {total} 本")
        self.btn_prev.setEnabled(page > 0)
        self.btn_next.setEnabled(page + 1 < pages)

    def _render_cards(self):
        # 清空网格
        while self.grid.count():
            it = self.grid.takeAt(0)
            w = it.widget()
            if w is not None:
                w.setParent(None)
                w.deleteLater()
        if not self._books:
            self.grid.addWidget(QLabel("没有找到书籍"), 0, 0)
            return
        cols = 4
        for idx, book in enumerate(self._books):
            card = self._make_card(book)
            self.grid.addWidget(card, idx // cols, idx % cols)
        for c in range(cols):
            self.grid.setColumnStretch(c, 1)

    def _make_card(self, book: StoreBook) -> QWidget:
        card = None
        if RealBookCard is not None:
            try:
                card = RealBookCard(book, parent=self.grid_host)
            except TypeError:
                try:
                    card = RealBookCard(parent=self.grid_host)
                    self.call(card, "set_book", book)
                except Exception:  # noqa: BLE001
                    card = None
            except Exception:  # noqa: BLE001
                card = None
        if card is None:
            card = _FallbackBookCard(book, self.grid_host)
        # 连接点击（优先真实 BookCard 的 clicked/bookClicked，缺失回落到详情）
        connected = (self.try_connect(card, "clicked", lambda *_a, bk=book: self._open_detail(bk)) or
                     self.try_connect(card, "bookClicked", lambda *_a, bk=book: self._open_detail(bk)) or
                     self.try_connect(card, "itemClicked", lambda *_a, bk=book: self._open_detail(bk)))
        if not connected:
            # 占位卡片自带 clicked
            self.try_connect(card, "clicked", self._open_detail)
        return card

    # ---------- 详情 ----------

    def _open_detail(self, book: StoreBook):
        dlg = StoreDetailDialog(book, [], self)
        dlg.read_requested.connect(lambda bid, bk=book: self._on_read(bk))
        dlg.download_requested.connect(lambda bid, bk=book: self._on_download(bk))
        dlg.synthesize_requested.connect(lambda bid, bk=book: self._on_synthesize(bk))
        dlg.audio_download_requested.connect(lambda bid, bk=book: self._on_download_audio(bk))
        # 异步加载目录预览
        self.run_async(lambda: self._safe_toc(book.id),
                       on_result=lambda toc: dlg._replace_toc(toc) if hasattr(dlg, "_replace_toc") else None)
        dlg.exec()

    def _safe_toc(self, store_book_id: int) -> List[TocEntry]:
        try:
            return self.api.get_store_book_toc(store_book_id)
        except Exception:  # noqa: BLE001
            return []

    # ---------- 动作 ----------

    def _on_read(self, book: StoreBook):
        # 游客可在线阅读
        self.read_requested.emit(book.id)

    def _on_download(self, book: StoreBook):
        if not self.require_login():
            QMessageBox.information(self, "需要登录", "下载需先登录账号。")
            return
        self.info.setText(f"正在下载《{book.title}》…")
        ext = (book.file_extension or "txt").lstrip(".")

        def _work():
            import os
            from app.models.models import Book as LocalBook, BookCategory
            # 幂等：同一书城书（store_book_id）本机只保留一条记录，重复下载不再插出重复项。
            existing = self.db.get_book_by_store_id(book.id)
            already_local = existing is not None and bool((existing.file_path or "").strip())
            if already_local:
                # 已在书架（本机已有文件）→ 不再重复下载，仅 best-effort 确保云端书架在册。
                shelf_err = ""
                try:
                    self.api.add_to_shelf(book.id)
                except Exception as e:  # noqa: BLE001
                    shelf_err = str(e)
                # 用户重新加入 ⇒ 清掉「本机已移出」墓碑，否则同步会一直跳过它。
                # 任务墓碑也要清：否则这本书的合成任务拉不回来（书回来了却显示「未合成」）。
                try:
                    self.db.clear_store_book_dismissal(book.id)
                    self.db.clear_task_dismissals_for_book(book.title)
                except Exception:
                    pass
                return existing.id, (existing.file_path or ""), shelf_err, True

            safe_title = "".join(c for c in book.title if c not in '\\/:*?"<>|') or f"store_{book.id}"
            save_path = str(self.config.books_dir / f"{safe_title}.{ext}")
            # 已存在则加 id 防覆盖
            if os.path.exists(save_path):
                save_path = str(self.config.books_dir / f"{safe_title}_{book.id}.{ext}")
            self.api.download_store_book(book.id, save_path)

            if existing is not None:
                # 占位行（云端同步下来的、无本地文件）→ 下载后回填文件信息，不新建行
                self.db.update_store_book_file(book.id, save_path, ext,
                                               bool(book.task_id), book.author)
                new_id = existing.id
            else:
                b = LocalBook(display_name=book.title, file_path=save_path, file_ext=ext,
                              category=BookCategory.STORE.value, store_book_id=book.id,
                              author=book.author, description=book.description,
                              has_audio=bool(book.task_id))
                new_id = self.db.add_book(b)

            # 写云端书架（跨端共享）。失败不再静默：返回错误信息，让 UI 如实提示，
            # 否则会出现"本机已下载但其它设备/下次同步看不到"的假成功。
            shelf_err = ""
            try:
                self.api.add_to_shelf(book.id)
            except Exception as e:  # noqa: BLE001
                shelf_err = str(e)
            # 用户重新加入 ⇒ 清掉「本机已移出」墓碑，否则同步会一直跳过它。
            # 任务墓碑也要清：否则这本书的合成任务拉不回来（书回来了却显示「未合成」）。
            try:
                self.db.clear_store_book_dismissal(book.id)
                self.db.clear_task_dismissals_for_book(book.title)
            except Exception:
                pass
            return new_id, save_path, shelf_err, False

        def _on_done(r):
            _nid, _path, shelf_err, already = r
            if already:
                self.info.setText(f"《{book.title}》已在书架")
                return
            if shelf_err:
                self.info.setText(f"《{book.title}》已下载，但云端书架同步失败：{shelf_err}")
                QMessageBox.warning(
                    self, "已下载（未同步云端）",
                    f"《{book.title}》已保存到本机书架，但写入云端书架失败：\n{shelf_err}\n"
                    f"该书暂时只在本机可见，其它设备不会同步到。请检查网络后重试加入书架。")
            else:
                self.info.setText(f"《{book.title}》已下载到书架（已同步云端）")

        self.run_async(_work,
                       on_result=_on_done,
                       on_error=lambda m: (self.info.setText(f"下载失败：{m}"),
                                           QMessageBox.warning(self, "下载失败", m)))


    def _on_download_audio(self, book: StoreBook):
        """下载书城书的合成音频（逐段后台下载，落库到本机，阅读页随后即可播放）。"""
        if not self.require_login():
            QMessageBox.information(self, "需要登录", "下载音频需先登录账号。")
            return
        dl = self.store_audio
        if dl is None:
            QMessageBox.warning(self, "下载音频", "书城音频下载服务未就绪，暂时无法下载。")
            return
        self.info.setText(f"正在下载《{book.title}》的合成音频…")

        # 断开上次可能残留的连接，防止重复触发
        for sig in (self.audio_dl_progress, self.audio_dl_done):
            try:
                sig.disconnect()
            except Exception:  # noqa: BLE001
                pass
        self.audio_dl_progress.connect(
            lambda saved, total, failed: self.info.setText(
                f"《{book.title}》音频下载中：{saved}/{total} 段"
                + (f"（{failed} 段失败）" if failed else "")))
        self.audio_dl_done.connect(
            lambda ok, msg: self._on_audio_dl_done(book.title, ok, msg))

        # 后台线程回调里 emit 信号，跨线程自动排队到主线程
        dl.download_all(book.id,
                        on_progress=lambda s, t, f: self.audio_dl_progress.emit(s, t, f),
                        on_done=lambda ok, msg: self.audio_dl_done.emit(ok, msg))

    def _on_audio_dl_done(self, title: str, ok: bool, msg: str):
        self.info.setText(f"《{title}》音频{msg}")
        if ok:
            QMessageBox.information(self, "下载音频", f"《{title}》{msg}")
        else:
            QMessageBox.warning(self, "下载音频", f"《{title}》{msg}")

    def _on_synthesize(self, book: StoreBook):
        if not self.require_login():
            QMessageBox.information(self, "需要登录", "合成需先登录账号。")
            return
        self.synthesize_requested.emit(book.id, book.title)

    def showEvent(self, event):
        super().showEvent(event)
