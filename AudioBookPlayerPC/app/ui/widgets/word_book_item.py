"""单词本列表项（PC 端）：三行展示「词 + 音标 / 首条释义 / 出处」。

⚠️ 为什么自己写 ``QStyledItemDelegate``，而不是用 ``QListWidgetItem`` 的富文本：
    实测（2026-11-07，证据见 ``agent_output/pc_wordbook/_probe_html_item2.png``）
    Qt 的 ``QListWidget`` **不解析 HTML** —— ``<b>`` / ``<span style="color:#888888">``
    会被当成普通字符**原样画出来**（截图里满屏尖括号）。
    Qt 的富文本渲染只对 ``QTextDocument`` / ``QTextEdit`` 这类控件生效；
    item view 的默认 delegate 走的是纯文本分支。
    ⇒ 想要「词加粗 + 音标灰色 + 释义单独一行 + 出处灰色」，只能自己画。

    （早期版本 ``profile_page._word_item_html`` 走的就是 HTML 路线，
      真机上一屏是 ``<b>trajectory</b> <span style="color:#888;">...`` 这种尖括号噪音。）

展示口径对齐 Android ``WordBookAdapter`` / Web ``WordBook.vue``：
    - 只显示**首条**释义 —— ECDICT 里 abandon 这类词有 5+ 条义项，
      全展开会把每行撑成好几行高，列表变得难以速览（完整释义在阅读页查词弹窗里看）。
    - 音标为空 ⇒ 整段不画；释义为空 ⇒ 整行不画；出处为空 ⇒ 整行不画，
      **绝不留空白占位**（旧收藏 6 键全 null 是常态）。

数据契约（item 的 data role）见本模块顶部的 ``ROLE_*`` 常量：
    0..3 是原有 word / definition / server_id / sw；
    4..9 是收藏出处 6 键（wire camelCase，与服务端 / 跳转契约逐键对应）；
    10 是音标（新增：delegate 要自己画，文本本身放不下）。
"""
from __future__ import annotations

from PySide6.QtCore import Qt, QSize, QPoint
from PySide6.QtGui import QColor, QFont, QFontMetrics, QPalette, QPainter
from PySide6.QtWidgets import (
    QApplication, QStyle, QStyledItemDelegate, QStyleOptionViewItem,
)


# ---------- item data role（唯一真源，profile_page 与测试都从这里取） ----------
ROLE_WORD = Qt.UserRole + 0
ROLE_DEFINITION = Qt.UserRole + 1
ROLE_SERVER_ID = Qt.UserRole + 2
ROLE_SW = Qt.UserRole + 3
ROLE_BOOK_NAME = Qt.UserRole + 4
ROLE_BOOK_KEY = Qt.UserRole + 5
ROLE_STORE_BOOK_ID = Qt.UserRole + 6
ROLE_SEGMENT_NUMBER = Qt.UserRole + 7
ROLE_LINE_NUMBER = Qt.UserRole + 8
ROLE_SENTENCE_TEXT = Qt.UserRole + 9
ROLE_PHONETIC = Qt.UserRole + 10


def first_translation(definition: str) -> str:
    """取首条释义。

    wire 上 ``translations`` 是 LF(\\n) 分隔的多行字符串，本地 ``definition`` 存的是同一份。
    空串 / 只有空行 ⇒ 返回空串（调用方据此整行不画）。
    """
    if not definition:
        return ""
    for line in definition.split("\n"):
        line = line.strip()
        if line:
            return line
    return ""


def source_label(book_name, segment_number, line_number) -> str:
    """出处副标题「书名 · 第 N 段/行」；无出处（旧收藏 6 键全 null）返回空串。

    与 Android ``WordBookAdapter.buildSourceLabel`` / Web ``WordBook.vue.sourceText`` 同口径：
    段号优先，行号兜底，都没有就只显示书名。
    """
    name = (book_name or "").strip() if isinstance(book_name, str) else ""
    if not name:
        return ""
    try:
        seg = int(segment_number) if segment_number is not None else 0
    except (TypeError, ValueError):
        seg = 0
    try:
        line = int(line_number) if line_number is not None else 0
    except (TypeError, ValueError):
        line = 0
    if seg > 0:
        return f"{name} · 第 {seg} 段"
    if line > 0:
        return f"{name} · 第 {line} 行"
    return name


def word_tooltip(word: str, phonetic: str, definition: str, src: str) -> str:
    """悬停提示：完整释义（多行全给）+ 音标 + 出处。

    列表里只画首条释义（要速览），完整释义放 tooltip —— 与安卓/ Web 的
    「进详情看全部」思路一致，且 PC 端没有独立的查词弹窗可用。
    """
    lines = []
    head = (word or "").strip()
    ph = (phonetic or "").strip()
    if ph:
        head = f"{head} /{ph}/"
    if head:
        lines.append(head)
    for line in (definition or "").split("\n"):
        line = line.strip()
        if line:
            lines.append(line)
    if src:
        lines.append(src)
    return "\n".join(lines)


class WordBookItemDelegate(QStyledItemDelegate):
    """单词本列表项绘制器：词 + 音标 / 首条释义 / 出处。

    - 词加粗（主色），音标与出处用弱化色，释义用主色；
    - 弱化色从**调色板**推导 ⇒ 自动跟随亮/暗主题（QSS 改的是 widget 的 palette，
      这里取到的就是主题色，不写死十六进制）；
    - 长文本单行省略（``Qt.ElideRight``），不换行 ⇒ 行高稳定、列表好扫。
    """

    MARGIN_X = 8      # 与 QSS ``QListWidget::item { padding: 6px 8px }`` 的 8px 对齐
    MARGIN_Y = 4
    GAP = 2           # 三行之间的行距

    def __init__(self, parent=None):
        super().__init__(parent)

    # ---------- 取数 ----------

    @staticmethod
    def _text(index, role) -> str:
        v = index.data(role)
        return v if isinstance(v, str) else ("" if v is None else str(v))

    def _parts(self, index):
        """把 item 上的 role 解析成绘制需要的四段文本。"""
        word = self._text(index, ROLE_WORD).strip()
        phonetic = self._text(index, ROLE_PHONETIC).strip()
        trans = first_translation(self._text(index, ROLE_DEFINITION))
        src = source_label(index.data(ROLE_BOOK_NAME),
                           index.data(ROLE_SEGMENT_NUMBER),
                           index.data(ROLE_LINE_NUMBER))
        return word, phonetic, trans, src

    # ---------- 颜色 ----------

    @staticmethod
    def _colors(opt: QStyleOptionViewItem):
        """(主文本色, 弱化色)。

        弱化色优先用 ``QPalette.PlaceholderText``；主题没定义它时（QSS 只设了
        ``color``）退化成「主文本色与面板底色的中间值」—— 亮色主题得到灰、
        暗色主题得到浅灰，两边都不至于糊成一片。
        """
        pal = opt.palette
        selected = bool(opt.state & QStyle.State_Selected)
        text = pal.color(QPalette.HighlightedText if selected else QPalette.Text)
        muted = pal.color(QPalette.PlaceholderText)
        if muted != text:
            return text, muted
        base = pal.color(QPalette.Base)
        return text, QColor((text.red() + base.red()) // 2,
                             (text.green() + base.green()) // 2,
                             (text.blue() + base.blue()) // 2)

    # ---------- 字体 ----------

    @staticmethod
    def _fonts(base: QFont):
        word_font = QFont(base)
        word_font.setBold(True)
        body_font = QFont(base)
        small_font = QFont(base)
        pt = small_font.pointSize()
        if pt > 0:
            small_font.setPointSize(max(7, pt - 1))
        return word_font, body_font, small_font

    # ---------- 绘制 ----------

    def paint(self, painter: QPainter, option, index) -> None:
        word, phonetic, trans, src = self._parts(index)
        if not word:
            # 空态提示之类的普通文本项 ⇒ 走默认绘制（默认 delegate 会画 opt.text）
            super().paint(painter, option, index)
            return

        opt = QStyleOptionViewItem(option)
        self.initStyleOption(opt, index)
        opt.text = ""          # 默认分支会把原始文本再画一遍 ⇒ 清空，只留背景/焦点框
        widget = opt.widget
        style = widget.style() if widget is not None else QApplication.style()

        painter.save()
        style.drawControl(QStyle.CE_ItemViewItem, opt, painter, widget)
        painter.restore()

        text_color, muted_color = self._colors(opt)
        word_font, body_font, small_font = self._fonts(opt.font)
        fm_word = QFontMetrics(word_font)
        fm_body = QFontMetrics(body_font)
        fm_small = QFontMetrics(small_font)

        rect = opt.rect
        x = rect.left() + self.MARGIN_X
        avail = max(10, rect.width() - 2 * self.MARGIN_X)
        y = rect.top() + self.MARGIN_Y

        # ---- 第 1 行：词（加粗） + 音标（弱化）----
        painter.setFont(word_font)
        painter.setPen(text_color)
        word_w = fm_word.horizontalAdvance(word)
        painter.drawText(QPoint(x, y + fm_word.ascent()), word)
        if phonetic:
            painter.setFont(body_font)
            painter.setPen(muted_color)
            px = x + word_w + 8
            ph_text = fm_body.elidedText(f"/{phonetic}/", Qt.ElideRight,
                                         max(10, avail - word_w - 8))
            painter.drawText(QPoint(px, y + fm_word.ascent()), ph_text)
        y += fm_word.height() + self.GAP

        # ---- 第 2 行：首条释义 ----
        if trans:
            painter.setFont(body_font)
            painter.setPen(text_color)
            painter.drawText(QPoint(x, y + fm_body.ascent()),
                             fm_body.elidedText(trans, Qt.ElideRight, avail))
            y += fm_body.height() + self.GAP

        # ---- 第 3 行：出处 ----
        if src:
            painter.setFont(small_font)
            painter.setPen(muted_color)
            painter.drawText(QPoint(x, y + fm_small.ascent()),
                             fm_small.elidedText(src, Qt.ElideRight, avail))

    # ---------- 尺寸 ----------

    def sizeHint(self, option, index) -> QSize:
        word, phonetic, trans, src = self._parts(index)
        if not word:
            return super().sizeHint(option, index)

        opt = QStyleOptionViewItem(option)
        self.initStyleOption(opt, index)
        word_font, body_font, small_font = self._fonts(opt.font)
        fm_word = QFontMetrics(word_font)
        fm_body = QFontMetrics(body_font)
        fm_small = QFontMetrics(small_font)

        height = self.MARGIN_Y * 2 + fm_word.height()
        width = fm_word.horizontalAdvance(word)
        if phonetic:
            width = max(width, fm_word.horizontalAdvance(word) + 8
                        + fm_body.horizontalAdvance(f"/{phonetic}/"))
        if trans:
            height += self.GAP + fm_body.height()
            width = max(width, fm_body.horizontalAdvance(trans))
        if src:
            height += self.GAP + fm_small.height()
            width = max(width, fm_small.horizontalAdvance(src))
        return QSize(width + 2 * self.MARGIN_X, height)
