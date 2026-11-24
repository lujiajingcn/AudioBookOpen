"""发现 / 社区页：帖子列表、发帖、帖子详情与评论、收藏。

游客可浏览；发帖 / 评论 / 收藏需登录。
"""
from __future__ import annotations

import threading
from typing import List, Optional

from PySide6.QtCore import Qt, Signal, QObject
from PySide6.QtWidgets import (
    QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QLineEdit, QTextEdit,
    QScrollArea, QWidget, QFrame, QDialog, QMessageBox, QFileDialog,
    QSizePolicy, QPlainTextEdit,
)

from app.core.api_client import parse_image_urls
from app.models.models import Post, Comment

from .base_page import BasePage


_PAGE_SIZE = 20


class _DetailBridge(QObject):
    """把后台线程取回的评论列表编组回主线程。"""
    comments = Signal(list)


class _DetailImagesBridge(QObject):
    """把后台线程取回的图片地址列表编组回主线程。"""
    loaded = Signal(list)


class _ImageBridge(QObject):
    """把后台线程下载到的图片字节编组回主线程。

    ⚠ 必须是 QObject + Signal，而不是在后台线程里直接调 widget 方法：
    QPixmap 只能在 GUI 线程创建，跨线程直接 setPixmap 会崩。
    """
    loaded = Signal(str, bytes)   # 相对地址, 图片字节


class PostImageStrip(QWidget):
    """帖子图片条：后台下载图片，主线程显示缩略图（最多 3 张，与 Android 端一致）。

    下载失败不显示任何东西（帖子其余内容照常可读）——
    图片是锦上添花，不该因为一张图 404 就让整条帖子渲染失败。
    """

    MAX_IMAGES = 3
    THUMB_PX = 96

    def __init__(self, api, rel_urls: List[str], parent=None):
        super().__init__(parent)
        self._api = api
        self._bridge = _ImageBridge()
        self._bridge.loaded.connect(self._on_loaded)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 4, 0, 0)
        lay.setSpacing(6)
        urls = [u for u in (rel_urls or []) if u][:self.MAX_IMAGES]
        for rel in urls:
            lbl = QLabel()
            lbl.setFixedSize(self.THUMB_PX, self.THUMB_PX)
            lbl.setAlignment(Qt.AlignCenter)
            lbl.setStyleSheet("background:#f2f2f2;border:1px solid #ddd;border-radius:4px;color:#999;")
            lbl.setText("…")
            # 记住这个 label 对应哪张图：回填时按 rel 匹配，而不是按下标猜
            lbl._ab_rel = rel
            lay.addWidget(lbl)
        lay.addStretch(1)
        self.setVisible(bool(urls))
        for rel in urls:
            self._download(rel)

    def _download(self, rel: str):
        def _work():
            try:
                data = self._api.download_image_bytes(rel)
            except Exception:  # noqa: BLE001 —— 图片失败静默降级
                return
            try:
                self._bridge.loaded.emit(rel, data)
            except RuntimeError:
                # 控件已销毁时信号发射失败属正常（窗口被关掉了）
                pass
        threading.Thread(target=_work, daemon=True).start()

    def _on_loaded(self, rel: str, data: bytes):
        from PySide6.QtGui import QPixmap
        pm = QPixmap()
        if not pm.loadFromData(data):
            return
        for i in range(self.layout().count()):
            item = self.layout().itemAt(i)
            w = item.widget()
            if w is None or not isinstance(w, QLabel):
                continue
            if getattr(w, "_ab_rel", None) == rel:
                w.setPixmap(pm.scaled(self.THUMB_PX, self.THUMB_PX,
                                      Qt.KeepAspectRatio, Qt.SmoothTransformation))
                w.setText("")
                return


class PostRow(QFrame):
    """帖子列表项（点击进详情）。"""

    clicked = Signal(object)  # Post

    def __init__(self, post: Post, api=None, parent=None):
        super().__init__(parent)
        self.post = post
        self.setCursor(Qt.PointingHandCursor)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Minimum)
        self.setStyleSheet("QFrame{background:#fff;border:1px solid #e0e0e0;border-radius:6px;}"
                           "QFrame:hover{background:#fafcff;border-color:#b8d4ff;}")
        lay = QVBoxLayout(self)
        lay.setContentsMargins(12, 10, 12, 10)
        t = QLabel(post.title or "(无标题)")
        tf = t.font(); tf.setBold(True); t.setFont(tf); t.setWordWrap(True)
        lay.addWidget(t)
        meta = QLabel(f"{post.author or '匿名'}  ·  {post.created_at or ''}")
        meta.setStyleSheet("color:#888;")
        lay.addWidget(meta)
        preview = (post.content or "")[:120] + ("…" if len(post.content or "") > 120 else "")
        c = QLabel(preview)
        c.setWordWrap(True)
        c.setStyleSheet("color:#444;")
        lay.addWidget(c)
        # 图片：有图才建；api 缺失时跳过（不让渲染问题挡住正文）
        if api is not None and post.image_urls:
            lay.addWidget(PostImageStrip(api, post.image_urls))
        foot = QLabel(f"💬 {post.comment_count}    ⭐ {post.favorite_count}")
        foot.setStyleSheet("color:#2c6e49;")
        lay.addWidget(foot)

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self.clicked.emit(self.post)
        super().mousePressEvent(event)


class CreatePostDialog(QDialog):
    posted = Signal()

    def __init__(self, api, parent=None):
        super().__init__(parent)
        self.api = api
        self._image = ""
        self.setWindowTitle("发布帖子")
        self.setMinimumSize(460, 420)
        lay = QVBoxLayout(self)
        self.title_edit = QLineEdit(); self.title_edit.setPlaceholderText("标题")
        lay.addWidget(self.title_edit)
        self.content_edit = QPlainTextEdit(); self.content_edit.setPlaceholderText("正文内容…")
        lay.addWidget(self.content_edit, 1)
        row = QHBoxLayout()
        self.btn_img = QPushButton("添加图片")
        self.btn_img.clicked.connect(self._pick_image)
        self.img_lbl = QLabel("未选择图片")
        self.img_lbl.setStyleSheet("color:#888;")
        row.addWidget(self.btn_img); row.addWidget(self.img_lbl, 1)
        lay.addLayout(row)
        btns = QHBoxLayout()
        btns.addStretch(1)
        cancel = QPushButton("取消"); cancel.clicked.connect(self.reject)
        self.submit = QPushButton("发布"); self.submit.clicked.connect(self._submit)
        btns.addWidget(cancel); btns.addWidget(self.submit)
        lay.addLayout(btns)

    def _pick_image(self):
        path, _ = QFileDialog.getOpenFileName(self, "选择图片", "", "图片 (*.png *.jpg *.jpeg *.webp)")
        if path:
            self._image = path
            self.img_lbl.setText(path.split("/")[-1])

    def _submit(self):
        title = self.title_edit.text().strip()
        content = self.content_edit.toPlainText().strip()
        if not title or not content:
            QMessageBox.information(self, "提示", "标题和正文不能为空。")
            return
        self.submit.setEnabled(False)
        try:
            self.api.create_post(title, content, self._image)
        except Exception as e:  # noqa: BLE001
            self.submit.setEnabled(True)
            QMessageBox.warning(self, "发布失败", str(e))
            return
        self.posted.emit()
        self.accept()


class PostDetailDialog(QDialog):
    """帖子详情：正文 + 评论列表 + 发表评论 + 收藏。"""

    def __init__(self, api, auth, post: Post, parent=None):
        super().__init__(parent)
        self.api = api
        self.auth = auth
        self.post = post
        self._faved = False
        self.setWindowTitle(post.title or "帖子详情")
        self.setMinimumSize(520, 600)

        lay = QVBoxLayout(self)
        head = QLabel(post.title or "(无标题)")
        hf = head.font(); hf.setBold(True); hf.setPointSize(hf.pointSize() + 3); head.setFont(hf)
        head.setWordWrap(True)
        lay.addWidget(head)
        meta = QLabel(f"{post.author or '匿名'}  ·  {post.created_at or ''}")
        meta.setStyleSheet("color:#888;")
        lay.addWidget(meta)
        body = QLabel(post.content or "")
        body.setWordWrap(True)
        body.setTextInteractionFlags(Qt.TextSelectableByMouse)
        lay.addWidget(body)
        # 图片：列表点进来时 Post 已带 imageUrls；没有则等详情接口回来再补
        if post.image_urls:
            lay.addWidget(PostImageStrip(api, post.image_urls))

        self.fav_btn = QPushButton(f"⭐ 收藏 ({post.favorite_count})")
        self.fav_btn.clicked.connect(self._toggle_fav)
        lay.addWidget(self.fav_btn)

        lay.addWidget(QLabel("评论："))
        self.comments_host = QVBoxLayout()
        lay.addLayout(self.comments_host, 1)

        crow = QHBoxLayout()
        self.comment_edit = QLineEdit(); self.comment_edit.setPlaceholderText("写下你的评论…")
        send = QPushButton("发表"); send.clicked.connect(self._send_comment)
        crow.addWidget(self.comment_edit, 1); crow.addWidget(send)
        lay.addLayout(crow)

        self._load_comments()
        self._load_detail_images()

    def _require(self) -> bool:
        if not self.auth.is_authenticated:
            QMessageBox.information(self, "需要登录", "请先登录再参与互动。")
            return False
        return True

    def _load_comments(self):
        def _fetch():
            d = self.api.get_post_detail(self.post.id)
            items = []
            if isinstance(d, dict):
                items = d.get("comments") or d.get("content") or []
            out = []
            for c in items if isinstance(items, list) else []:
                if isinstance(c, dict):
                    out.append(Comment(id=c.get("id"), post_id=self.post.id,
                                       content=c.get("content", ""),
                                       author=c.get("author", c.get("username", "")),
                                       created_at=c.get("createdAt", "")))
            return out
        # 复用父级窗口的异步？详情是独立 QDialog，这里用简单线程 + 信号
        self._bridge = _DetailBridge()
        self._bridge.comments.connect(self._on_comments)
        threading.Thread(target=lambda: self._bridge.comments.emit(_fetch()), daemon=True).start()

    def _load_detail_images(self):
        """从详情接口补齐图片。

        列表项里的 Post 可能来自旧缓存（没有 image_urls），
        详情接口返回的 ``post`` 字段才是权威来源 —— 补齐后才发现有图才插控件。
        """
        if self.post.image_urls:
            return   # 已有，不重复插

        def _work():
            try:
                d = self.api.get_post_detail(self.post.id)
            except Exception:  # noqa: BLE001
                return
            p = d.get("post") if isinstance(d, dict) else None
            urls = parse_image_urls(p.get("imageUrls")) if isinstance(p, dict) else []
            if urls:
                try:
                    self._img_bridge.loaded.emit(urls)
                except RuntimeError:
                    pass

        self._img_bridge = _DetailImagesBridge()
        self._img_bridge.loaded.connect(self._on_detail_images)
        threading.Thread(target=_work, daemon=True).start()

    def _on_detail_images(self, urls: List[str]):
        strip = PostImageStrip(self.api, urls)
        # 插在正文之后、收藏按钮之前：布局索引随插入而变，用 insertWidget 定位
        self.layout().insertWidget(self.layout().indexOf(self.fav_btn), strip)

    def _on_comments(self, comments):
        for c in comments:
            row = QFrame()
            rl = QHBoxLayout(row)
            rl.setContentsMargins(6, 4, 6, 4)
            who = QLabel(c.author or "匿名"); who.setStyleSheet("color:#666;font-weight:bold;")
            txt = QLabel(c.content); txt.setWordWrap(True)
            rl.addWidget(who); rl.addWidget(txt, 1)
            self.comments_host.addWidget(row)

    def _send_comment(self):
        if not self._require():
            return
        content = self.comment_edit.text().strip()
        if not content:
            return
        try:
            self.api.create_comment(self.post.id, content)
        except Exception as e:  # noqa: BLE001
            QMessageBox.warning(self, "失败", str(e)); return
        self.comment_edit.clear()
        self._on_comments([Comment(content=content, author=self.auth.username or "我")])

    def _toggle_fav(self):
        if not self._require():
            return
        try:
            self.api.toggle_post_favorite(self.post.id)
        except Exception as e:  # noqa: BLE001
            QMessageBox.warning(self, "失败", str(e)); return
        self._faved = not self._faved
        delta = 1 if self._faved else -1
        self.fav_btn.setText(f"{'★' if self._faved else '⭐'} 收藏 ({max(0, self.post.favorite_count + delta)})")


class DiscoverPage(BasePage):
    """发现 / 社区页。

    信号:
        login_required() —— 需要登录时通知 MainWindow 弹出登录。
    """

    login_required = Signal()

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._page = 0
        self._posts: List[Post] = []
        self._loading = False
        self._has_more = True

        root = QVBoxLayout(self)
        root.setContentsMargins(12, 12, 12, 12)

        head = QHBoxLayout()
        tf = QLabel("发现")
        _f = tf.font(); _f.setPointSize(_f.pointSize() + 4); _f.setBold(True); tf.setFont(_f)
        head.addWidget(tf)
        head.addStretch(1)
        self.btn_create = QPushButton("发帖")
        self.btn_create.clicked.connect(self._create_post)
        head.addWidget(self.btn_create)
        root.addLayout(head)

        self.scroll = QScrollArea(self)
        self.scroll.setWidgetResizable(True)
        self.host = QWidget()
        self.vbox = QVBoxLayout(self.host)
        self.vbox.setSpacing(8)
        self.vbox.addStretch(1)
        self.scroll.setWidget(self.host)
        root.addWidget(self.scroll, 1)
        # 滚到底自动加载更多
        sb = self.scroll.verticalScrollBar()
        sb.valueChanged.connect(self._on_scroll)
        sb.rangeChanged.connect(lambda *_a: self._maybe_load_more())

        self.btn_more = QPushButton("加载更多")
        self.btn_more.clicked.connect(lambda: self._load_page(self._page, append=True))
        root.addWidget(self.btn_more)

        self.refresh()

    # ---------- 加载 ----------

    def refresh(self):
        self._page = 0
        self._has_more = True
        while self.vbox.count() > 1:
            it = self.vbox.takeAt(0)
            w = it.widget()
            if w:
                w.setParent(None); w.deleteLater()
        self._load_page(0, append=False)

    def _load_page(self, page: int, append: bool):
        if self._loading:
            return
        self._loading = True
        self.btn_more.setEnabled(False)
        self.btn_more.setText("加载中…")

        def _fetch():
            return self.api.get_posts(page=page, size=_PAGE_SIZE)
        self.run_async(_fetch,
                       on_result=lambda posts: self._on_posts(posts, page, append),
                       on_error=lambda m: self._on_posts_error(m))

    def _on_posts(self, posts, page: int, append: bool):
        self._loading = False
        posts = list(posts or [])
        if append:
            self._posts.extend(posts)
        else:
            self._posts = posts
            self._clear_rows()
        for p in posts:
            row = PostRow(p, self.api, self.host)
            row.clicked.connect(self._open_detail)
            self.vbox.insertWidget(self.vbox.count() - 1, row)
        self._page = page + 1
        self._has_more = len(posts) >= _PAGE_SIZE
        self.btn_more.setEnabled(self._has_more)
        self.btn_more.setText("加载更多" if self._has_more else "没有更多了")

    def _on_posts_error(self, msg: str):
        self._loading = False
        self.btn_more.setEnabled(True)
        self.btn_more.setText("重试加载")

    def _clear_rows(self):
        i = 0
        while i < self.vbox.count():
            it = self.vbox.itemAt(i)
            w = it.widget()
            if isinstance(w, PostRow):
                self.vbox.takeAt(i)
                w.setParent(None); w.deleteLater()
            else:
                i += 1

    def _on_scroll(self, value: int):
        sb = self.scroll.verticalScrollBar()
        if sb.maximum() - value <= 40:
            self._maybe_load_more()

    def _maybe_load_more(self):
        if self._has_more and not self._loading and self._posts:
            # 仅当已经滚动接近底部时由 _on_scroll 触发；rangeChanged 不自动触发以避免循环
            pass

    # ---------- 交互 ----------

    def _create_post(self):
        if not self.config.is_authenticated:
            QMessageBox.information(self, "需要登录", "发帖需先登录账号。")
            self.login_required.emit()
            return
        dlg = CreatePostDialog(self.api, self)
        dlg.posted.connect(self.refresh)
        dlg.exec()

    def _open_detail(self, post: Post):
        dlg = PostDetailDialog(self.api, self.auth, post, self)
        dlg.exec()
