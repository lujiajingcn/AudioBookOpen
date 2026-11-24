"""PC 单词本**展示**的回归测试（2026-11-07 用户报「我的收藏内容不正确」）。

背景（原始 bug）：
    ``ProfilePage._load_words`` 只拼了 ``f"{w.word}  {w.phonetic}"``，
    释义（明明存在 ``UserRole+1`` 里）和出处 6 键全被丢掉 ⇒ 单词本看起来
    只有一串音标，用户完全看不出收藏的是哪个词、哪本书。

⚠️ 这里还钉死一个**曾经踩过的坑**（写这批测试的直接原因）：
    修复第一版走了「QListWidgetItem 富文本(HTML)」路线，
    但实测 **QListWidget 不解析 HTML** —— ``<b>`` / ``<span style="color:...">``
    会被当成普通字符原样画出来（证据：agent_output/pc_wordbook/_probe_html_item2.png）。
    ⇒ 现在改用 ``WordBookItemDelegate`` 自己画三行，
      本文件用「item 文本里绝不允许出现尖括号」把这条坑钉死。

跑法::

    cd AudioBookPlayerPC
    QT_QPA_PLATFORM=offscreen python -m unittest tests.test_word_book_display -v
"""
from __future__ import annotations

import os
import sys
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

try:
    from PySide6.QtWidgets import QApplication
    _HAS_PYSIDE6 = True
except Exception:  # pragma: no cover
    _HAS_PYSIDE6 = False


# ======================================================================
# 纯函数层（不需要 GUI）
# ======================================================================

@unittest.skipUnless(_HAS_PYSIDE6, "PySide6 未安装")
class WordBookTextHelperTest(unittest.TestCase):
    """first_translation / source_label / word_tooltip 的口径。"""

    def test_first_translation_takes_first_nonempty_line(self):
        from app.ui.widgets.word_book_item import first_translation
        self.assertEqual("n. 轨迹", first_translation("n. 轨迹\nn. 弹道"))
        self.assertEqual("n. 轨迹", first_translation("\n\n  n. 轨迹  \n其余"))
        self.assertEqual("", first_translation(""))
        self.assertEqual("", first_translation("\n \n"))

    def test_source_label_segment_wins_over_line(self):
        from app.ui.widgets.word_book_item import source_label
        # 段号优先
        self.assertEqual("书 · 第 3 段", source_label("书", 3, 7))
        # 只有行号 → 行
        self.assertEqual("书 · 第 7 行", source_label("书", None, 7))
        # 都没有 → 只显示书名
        self.assertEqual("书", source_label("书", 0, 0))
        # 没书名 → 空串（旧收藏 6 键全 null）
        self.assertEqual("", source_label(None, 3, 7))
        self.assertEqual("", source_label("   ", 3, 7))

    def test_tooltip_contains_full_definition_and_source(self):
        from app.ui.widgets.word_book_item import word_tooltip
        tip = word_tooltip("trajectory", "trəˈdʒɛktri",
                            "n. 轨迹\nn. 弹道", "Linux命令行大全 · 第 37 段")
        self.assertIn("trajectory", tip)
        self.assertIn("/trəˈdʒɛktri/", tip)
        self.assertIn("n. 轨迹", tip)
        self.assertIn("n. 弹道", tip)          # 完整释义都在 tooltip 里
        self.assertIn("Linux命令行大全 · 第 37 段", tip)


# ======================================================================
# 真 GUI 层
# ======================================================================

@unittest.skipUnless(_HAS_PYSIDE6, "PySide6 未安装，跳过真实 GUI 用例")
class WordBookDisplayGuiTest(unittest.TestCase):
    """单词本列表：内容、角色契约、行高、tooltip。"""

    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(self._tmp.cleanup)

        from app.core.api_client import ApiClient
        from app.core.auth import AuthManager
        from app.core.config import AppConfig
        from app.db.database import LocalDatabase
        from app.models.models import WordEntry
        from app.ui.pages.profile_page import ProfilePage

        self.config = AppConfig(data_dir=self._tmp.name)
        self.api = ApiClient(self.config)
        self.auth = AuthManager(self.config, self.api)
        self.db = LocalDatabase(self.config)
        self.addCleanup(self.db.close)

        self.db.add_word(WordEntry(
            word="trajectory", sw="trajectory", phonetic="trəˈdʒɛktri",
            definition="n. 轨迹；轨道\nn. 弹道",
            book_name="Linux命令行大全", book_key="s:12",
            segment_number=37, line_number=4,
            sentence_text="The trajectory of the shell was calculated."))
        self.db.add_word(WordEntry(
            word="legacy", sw="legacy", phonetic="ˈlɛɡəsi", definition="n. 遗产"))

        self.profile = ProfilePage(self.config, self.api, self.auth, self.db)
        self.profile._load_words()
        # ⚠️ 显式销毁页面 widget：让 Python GC 回收的话时机不可控，
        #   页面可能已析构而 Qt 侧还持有引用 ⇒ 后续 Qt 操作（setStyleSheet
        #   会 polish 全部顶层 widget）碰到野指针 ⇒ access violation。
        #   同款修复见 tests/test_word_book_gui.py。
        from tests.qt_cleanup import register_destroy
        register_destroy(self, self.profile)

    # ---------- 工具 ----------

    # ---------- 工具 ----------

    def _item(self, word):
        for i in range(self.profile.word_list.count()):
            it = self.profile.word_list.item(i)
            if it.data(0) == word:
                return it
        return None

    def _index_of(self, item):
        for i in range(self.profile.word_list.count()):
            if self.profile.word_list.item(i) is item:
                return i
        return -1

    # ---------- 用例 ----------

    def test_delegate_is_installed(self):
        """列表项必须由 WordBookItemDelegate 画（默认 delegate 画不出三行结构）。"""
        from app.ui.widgets.word_book_item import WordBookItemDelegate
        self.assertIsInstance(self.profile.word_list.itemDelegate(),
                              WordBookItemDelegate)

    def test_item_text_is_plain_word_without_html(self):
        """⚠️ 防回归：item 文本只能是单词本身，**绝不能**出现 HTML 尖括号。

        QListWidget 不解析 HTML —— 早期版本把 ``<b>word</b><br>...`` 塞进
        item 文本，结果屏幕上满屏尖括号。这条断言就是那次事故的守门员。
        """
        self.assertEqual(2, self.profile.word_list.count())
        for i in range(self.profile.word_list.count()):
            text = self.profile.word_list.item(i).text()
            self.assertNotIn("<", text)
            self.assertNotIn(">", text)
            self.assertNotIn("&", text)
            self.assertIn(text, ("trajectory", "legacy"))

    def test_roles_carry_word_phonetic_definition(self):
        """词 / 音标 / 释义都在 role 上 —— 绘制器只认 role。"""
        from app.ui.widgets.word_book_item import (
            ROLE_WORD, ROLE_PHONETIC, ROLE_DEFINITION, ROLE_SW,
        )
        it = self._item("trajectory")
        self.assertIsNotNone(it)
        self.assertEqual("trajectory", it.data(ROLE_WORD))
        self.assertEqual("trəˈdʒɛktri", it.data(ROLE_PHONETIC))
        self.assertEqual("n. 轨迹；轨道\nn. 弹道", it.data(ROLE_DEFINITION))
        self.assertEqual("trajectory", it.data(ROLE_SW))

    def test_delegate_splits_into_three_lines(self):
        """delegate 能把 role 解析成「词 / 音标 / 首条释义 / 出处」四段。"""
        from app.ui.widgets.word_book_item import WordBookItemDelegate
        dlg = self.profile.word_list.itemDelegate()
        idx = self.profile.word_list.model().index(
            self._index_of(self._item("trajectory")))
        word, phonetic, trans, src = dlg._parts(idx)
        self.assertEqual("trajectory", word)
        self.assertEqual("trəˈdʒɛktri", phonetic)
        self.assertEqual("n. 轨迹；轨道", trans)      # 只取首条
        self.assertEqual("Linux命令行大全 · 第 37 段", src)

        idx2 = self.profile.word_list.model().index(
            self._index_of(self._item("legacy")))
        word2, phonetic2, trans2, src2 = dlg._parts(idx2)
        self.assertEqual("legacy", word2)
        self.assertEqual("ˈlɛɡəsi", phonetic2)
        self.assertEqual("n. 遗产", trans2)
        self.assertEqual("", src2)                  # 旧收藏没有出处 ⇒ 空串（不画该行）

    def test_row_with_source_is_taller_than_row_without(self):
        """有出处的条目多画一行 ⇒ 行高更高（旧实现里出处根本没显示）。"""
        with_src = self.profile.word_list.visualItemRect(self._item("trajectory"))
        without = self.profile.word_list.visualItemRect(self._item("legacy"))
        self.assertGreater(with_src.height(), without.height())

    def test_tooltip_has_full_definition_and_source(self):
        tip = self._item("trajectory").toolTip()
        self.assertIn("n. 轨迹；轨道", tip)
        self.assertIn("n. 弹道", tip)                       # 第二条释义也在
        self.assertIn("Linux命令行大全 · 第 37 段", tip)     # 出处也在

    def test_empty_state_hint_is_plain_text(self):
        """空单词本 ⇒ 一行中文提示，且同样不含尖括号。"""
        self.db.delete_word("trajectory")
        self.db.delete_word("legacy")
        self.profile._load_words()
        self.assertEqual(1, self.profile.word_list.count())
        text = self.profile.word_list.item(0).text()
        self.assertIn("单词本为空", text)
        self.assertNotIn("<", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
