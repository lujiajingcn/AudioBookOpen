"""单词本「收藏出处」改造的回归测试（PC 端）。

覆盖需求里的四项：
1. ``word_book`` 出处 6 列的 DDL / **幂等迁移**（含 7 列老库补齐）；
2. 出处字段**不被 reconcile / rebuild 覆盖**：服务端该项带出处才刷新，出处全 None
   时保留本地值；同时验证 INSERT 列白名单没漏（否则一同步出处就没了）；
3. 单词本**右键菜单**（Qt 桌面端没有长按，右键 QMenu 是 PC 端等价交互）wiring，
   以及 MainWindow 的信号连接 / reader_page.open_book 的定位接线；
4. ``SegmentMap`` **按文本定位**：行号只是 hint，切分粒度漂移后仍按句子原文命中。

另外顺带覆盖 wire 6 键：``sync.py`` 的 ``_extract_words`` 白名单（拉回解析）、
``_word_source_payload``（上传带出）、``api_client.py`` 的 add/sync payload。

运行：``QT_QPA_PLATFORM=offscreen python -m unittest discover -s tests -p "test_*.py" -v``
"""
from __future__ import annotations

import os
import sys
import tempfile
import types
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

# 本环境可能没装 requests / urllib3（api_client 在 import 期就要它们）。
# 仅为让 sync.py / api_client.py 的纯逻辑函数可被导入做单测，注入最小 stub；
# 这些测试不发起任何网络请求，stub 不影响结果。
try:
    import requests  # noqa: F401
    from requests.adapters import HTTPAdapter  # noqa: F401
    from urllib3.util.retry import Retry  # noqa: F401
except ImportError:  # pragma: no cover —— 仅在缺依赖环境走
    _stub_exc = types.ModuleType("requests.exceptions")
    _stub_exc.RequestException = Exception
    for _name, _attrs in (
        ("urllib3", {}),
        ("urllib3.util", {}),
        ("urllib3.util.retry", {"Retry": object}),
        ("requests", {"Session": object, "exceptions": _stub_exc,
                      "__path__": [], "get": None, "post": None,
                      "put": None, "delete": None}),
        ("requests.exceptions", {"RequestException": Exception}),
        ("requests.adapters", {"HTTPAdapter": object}),
    ):
        if _name not in sys.modules:
            _m = types.ModuleType(_name)
            for _k, _v in _attrs.items():
                setattr(_m, _k, _v)
            sys.modules[_name] = _m

try:
    import PySide6  # noqa: F401
    _HAS_PYSIDE6 = True
except Exception:  # pragma: no cover
    _HAS_PYSIDE6 = False

from app.core.config import AppConfig  # noqa: E402
from app.core.api_client import ApiClient  # noqa: E402
from app.core.sync import SyncManager, _word_source_payload  # noqa: E402
from app.db.database import (LocalDatabase, WORD_SOURCE_COLUMNS,  # noqa: E402
                             _wire_to_word_source, _word_source_has_any)
from app.models.models import WordEntry  # noqa: E402
from app.parser.segment_map import SegmentMap  # noqa: E402


# 一处带完整出处的服务端 words[] 项（wire camelCase，与服务端 toItem 同构）
_WIRE_SOURCE = {
    "bookName": "书A", "bookKey": "n:shua", "storeBookId": None,
    "segmentNumber": 3, "lineNumber": 12,
    "sentenceText": "这是收藏时所在的那一整行原文",
}


def _server_item(sid: int, sw: str, word: str, translations: str = "",
                 source: dict = None) -> dict:
    it = {
        "id": sid, "sw": sw, "word": word, "phonetic": "",
        "translations": translations, "createdAt": "2026-01-01T00:00:00",
    }
    if source:
        it.update(source)
    return it


class _TmpDbMixin(unittest.TestCase):
    """每个用例一个全新的临时库（Windows 下先 close 再删目录，避免文件锁）。"""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.config = AppConfig(data_dir=self._tmp.name)
        self.db = LocalDatabase(self.config)
        self.addCleanup(self.db.close)

    def _row(self, sw: str = "hood"):
        return self.db._conn().execute(
            "SELECT * FROM word_book WHERE sw=?", (sw,)).fetchone()

    def _add_with_source(self) -> int:
        return self.db.add_word(WordEntry(
            word="hood", sw="hood", definition="兜帽",
            book_name="书A", book_key="n:shua",
            segment_number=3, line_number=12,
            sentence_text="这是收藏时所在的那一整行原文"))


# ======================================================================
# 1) DDL + 幂等迁移
# ======================================================================

class WordBookMigrationTest(_TmpDbMixin):

    def _cols(self):
        return [r[1] for r in self.db._conn().execute(
            "PRAGMA table_info(word_book)").fetchall()]

    def test_ddl_has_all_six_source_columns(self):
        cols = self._cols()
        for c in WORD_SOURCE_COLUMNS:
            self.assertIn(c, cols)

    def test_migrate_is_idempotent(self):
        before = set(self._cols())
        for _ in range(3):
            self.db._migrate()   # 模拟每次开库都跑一遍，不得报错 / 重复加列
        self.assertEqual(before, set(self._cols()))
        # 重新开一个连接（新 LocalDatabase）后仍是同样的 6 列，各恰好一次
        self.db.close()
        db2 = LocalDatabase(self.config)
        self.addCleanup(db2.close)
        cols2 = [r[1] for r in db2._conn().execute(
            "PRAGMA table_info(word_book)").fetchall()]
        for c in WORD_SOURCE_COLUMNS:
            self.assertEqual(1, cols2.count(c))

    def test_migrate_adds_columns_to_legacy_7_column_db(self):
        """老库只有 7 列 → _migrate 幂等补齐 6 列，且存量行出处为 NULL。"""
        import sqlite3
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "audiobook_pc.db")
            conn = sqlite3.connect(path)
            conn.execute("""CREATE TABLE word_book (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL, phonetic TEXT DEFAULT '',
                definition TEXT DEFAULT '', created_at TEXT DEFAULT '',
                sw TEXT DEFAULT '', server_id INTEGER)""")
            conn.execute(
                "INSERT INTO word_book (word, sw) VALUES ('legacy', 'legacy')")
            conn.commit()
            conn.close()
            db = LocalDatabase(AppConfig(data_dir=d))
            try:
                cols = [r[1] for r in db._conn().execute(
                    "PRAGMA table_info(word_book)").fetchall()]
                for c in WORD_SOURCE_COLUMNS:
                    self.assertIn(c, cols)
                # 老数据没有出处 ⇒ 全 NULL，「跳转到原书」据此禁用，绝不崩
                r = db._conn().execute(
                    "SELECT * FROM word_book WHERE sw='legacy'").fetchone()
                for c in WORD_SOURCE_COLUMNS:
                    self.assertIsNone(r[c])
            finally:
                db.close()


# ======================================================================
# 2) 出处不被 reconcile / rebuild 覆盖 + add_word 刷新语义
# ======================================================================

class WordBookSourcePersistenceTest(_TmpDbMixin):

    def test_add_and_get_roundtrip(self):
        self._add_with_source()
        w = self.db.get_words()[0]
        self.assertEqual("书A", w.book_name)
        self.assertEqual("n:shua", w.book_key)
        self.assertIsNone(w.store_book_id)
        self.assertEqual(3, w.segment_number)
        self.assertEqual(12, w.line_number)
        self.assertEqual("这是收藏时所在的那一整行原文", w.sentence_text)
        self.assertIsNone(w.server_id)

    def test_reconcile_no_source_keeps_local_source(self):
        """服务端该项出处全 None ⇒ 只更新快照/server_id，出处保留本地值。"""
        self._add_with_source()
        stats = self.db.reconcile_words_from_server([
            _server_item(9, "hood", "hood", translations="服务端释义")])
        self.assertEqual(1, stats["updated"])
        r = self._row()
        self.assertEqual("书A", r["book_name"])
        self.assertEqual("n:shua", r["book_key"])
        self.assertEqual("这是收藏时所在的那一整行原文", r["sentence_text"])
        self.assertEqual(9, r["server_id"])
        self.assertEqual("服务端释义", r["definition"])

    def test_reconcile_with_source_refreshes_all_six(self):
        """服务端带出处 ⇒ 整体刷新 6 列（验证 UPDATE 列白名单没漏）。"""
        self._add_with_source()
        self.db.reconcile_words_from_server([
            _server_item(9, "hood", "hood", source={
                "bookName": "书B", "bookKey": "s:77", "storeBookId": 77,
                "segmentNumber": 8, "lineNumber": 20,
                "sentenceText": "另一本书的另一句"})])
        r = self._row()
        self.assertEqual("书B", r["book_name"])
        self.assertEqual("s:77", r["book_key"])
        self.assertEqual(77, r["store_book_id"])
        self.assertEqual(8, r["segment_number"])
        self.assertEqual(20, r["line_number"])
        self.assertEqual("另一本书的另一句", r["sentence_text"])

    def test_reconcile_insert_keeps_source_columns(self):
        """本地无该 sw 的 INSERT 分支也必须带 6 列白名单。"""
        stats = self.db.reconcile_words_from_server([
            _server_item(9, "new", "new", source=_WIRE_SOURCE)])
        self.assertEqual(1, stats["inserted"])
        r = self._row("new")
        self.assertEqual("书A", r["book_name"])
        self.assertEqual("n:shua", r["book_key"])
        self.assertEqual(3, r["segment_number"])
        self.assertEqual(12, r["line_number"])
        self.assertEqual("这是收藏时所在的那一整行原文", r["sentence_text"])

    def test_rebuild_no_source_keeps_local_source(self):
        """rebuild 先 DELETE 再 INSERT，删前的本地出处快照必须回填。"""
        self._add_with_source()
        n = self.db.rebuild_words_from_server([
            _server_item(9, "hood", "hood", translations="服务端释义")])
        self.assertEqual(1, n)
        r = self._row()
        self.assertEqual("书A", r["book_name"])
        self.assertEqual("n:shua", r["book_key"])
        self.assertEqual("这是收藏时所在的那一整行原文", r["sentence_text"])

    def test_rebuild_with_source_inserts_all_six(self):
        """rebuild 全新项带出处 ⇒ 6 列都写入（验证 INSERT 列白名单没漏）。"""
        n = self.db.rebuild_words_from_server([
            _server_item(9, "new", "new", source={
                "bookName": "书B", "bookKey": "s:77", "storeBookId": 77,
                "segmentNumber": 8, "lineNumber": 20,
                "sentenceText": "另一本书的另一句"})])
        self.assertEqual(1, n)
        r = self._row("new")
        self.assertEqual("书B", r["book_name"])
        self.assertEqual("s:77", r["book_key"])
        self.assertEqual(77, r["store_book_id"])
        self.assertEqual(8, r["segment_number"])
        self.assertEqual(20, r["line_number"])
        self.assertEqual("另一本书的另一句", r["sentence_text"])

    def test_repeat_favorite_refreshes_source_keeps_server_id(self):
        """重复收藏按「最近一次带出处」刷新出处，且绝不动 server_id。"""
        self._add_with_source()
        self.db.set_word_server_id(self._row()["id"], 55)
        self.db.add_word(WordEntry(
            word="hood", sw="hood", definition="新释义",
            book_name="书C", book_key="n:shuc",
            segment_number=5, line_number=30, sentence_text="新句子"))
        r = self._row()
        self.assertEqual("书C", r["book_name"])
        self.assertEqual("新句子", r["sentence_text"])
        self.assertEqual(55, r["server_id"])

    def test_repeat_favorite_without_source_keeps_existing_source(self):
        """旧路径 / 无上下文的重复收藏不带出处 ⇒ 保留已有出处，不抹成 NULL。"""
        self._add_with_source()
        self.db.add_word(WordEntry(word="hood", sw="hood", definition="无出处重查"))
        r = self._row()
        self.assertEqual("书A", r["book_name"])
        self.assertEqual("这是收藏时所在的那一整行原文", r["sentence_text"])

    def test_source_helpers_normalize_nulls(self):
        """空串 / 0 / 非法数字都归一为 None，判定口径与服务端 hasSource() 一致。"""
        clean = _wire_to_word_source({
            "bookName": "", "bookKey": "   ", "storeBookId": 0,
            "segmentNumber": "x", "lineNumber": None, "sentenceText": ""})
        self.assertTrue(all(v is None for v in clean.values()))
        self.assertFalse(_word_source_has_any(clean))
        self.assertTrue(_word_source_has_any(
            _wire_to_word_source({"bookName": "书A"})))
        self.assertTrue(_word_source_has_any(
            _wire_to_word_source({"bookKey": "n:shua"})))


# ======================================================================
# 3) wire 6 键：拉回解析 / 上传带出 / api_client payload
# ======================================================================

class WordBookWireTest(unittest.TestCase):

    def test_extract_words_keeps_six_source_keys(self):
        items = SyncManager._extract_words({"words": [
            _server_item(9, "hood", "hood", source=_WIRE_SOURCE)]})
        self.assertEqual(1, len(items))
        it = items[0]
        for k in ("bookName", "bookKey", "storeBookId", "segmentNumber",
                  "lineNumber", "sentenceText"):
            self.assertIn(k, it)   # 白名单漏一个键，拉回后出处就被丢掉
        self.assertEqual("书A", it["bookName"])
        self.assertEqual("n:shua", it["bookKey"])
        self.assertEqual(3, it["segmentNumber"])
        self.assertEqual(12, it["lineNumber"])
        self.assertEqual("这是收藏时所在的那一整行原文", it["sentenceText"])

    def test_extract_words_none_source_stays_none(self):
        it = SyncManager._extract_words(
            {"words": [{"id": 1, "sw": "a", "word": "a"}]})[0]
        for k in ("bookName", "bookKey", "storeBookId", "segmentNumber",
                  "lineNumber", "sentenceText"):
            self.assertIsNone(it[k])

    def test_word_source_payload_from_local_row(self):
        w = WordEntry(word="hood", sw="hood", book_name="书A",
                      book_key="n:shua", segment_number=3, line_number=12,
                      sentence_text="那一行原文")
        p = _word_source_payload(w)
        self.assertEqual("书A", p["bookName"])
        self.assertEqual("n:shua", p["bookKey"])
        self.assertEqual(3, p["segmentNumber"])
        self.assertEqual(12, p["lineNumber"])
        self.assertEqual("那一行原文", p["sentenceText"])
        # 本地书 storeBookId 为 None ⇒ 不带该键，服务端按旧客户端保留云端旧值
        self.assertNotIn("storeBookId", p)

    def test_word_source_payload_empty_when_no_source(self):
        self.assertEqual({}, _word_source_payload(WordEntry(word="a", sw="a")))

    def test_add_word_book_payload_carries_source(self):
        api = object.__new__(ApiClient)
        seen = {}

        def _post(path, body):
            seen["path"] = path
            seen["body"] = body
            return {"word": {"id": 42}}

        api._post = _post
        api.add_word_book("hood", "hood", phonetic="h", translations="兜帽",
                          source={"bookName": "书A", "bookKey": "n:shua",
                                  "segmentNumber": 3, "sentenceText": "那一行",
                                  "storeBookId": None})
        b = seen["body"]
        self.assertEqual("/word-book", seen["path"])
        self.assertEqual("书A", b["bookName"])
        self.assertEqual("n:shua", b["bookKey"])
        self.assertEqual(3, b["segmentNumber"])
        self.assertEqual("那一行", b["sentenceText"])
        self.assertNotIn("storeBookId", b)   # None 键被跳过

    def test_sync_word_book_passes_source_through(self):
        api = object.__new__(ApiClient)
        seen = {}
        api._post = lambda path, body: seen.setdefault("body", body) or {}
        items = [{"sw": "hood", "word": "hood", "bookName": "书A"}]
        api.sync_word_book(items)
        self.assertEqual(items, seen["body"]["words"])


# ======================================================================
# 4) 按文本定位（行号漂移仍命中）
# ======================================================================

class SegmentTextLocateTest(unittest.TestCase):

    def test_locate_by_text_ignores_line_number_drift(self):
        """收藏时行号是切分粒度的函数；前插两行后 hint 完全错，仍按原文命中。"""
        needle = "The hood was pulled over his face."
        rows = ["第一章 开篇", "这是完全无关的一段文字", needle,
                "另一段无关内容", "结尾"]
        sm = SegmentMap.for_text_lookup(rows)
        self.assertEqual(2, sm.locate_row_by_text(needle, 3))  # 1-based hint=3

        # 切分粒度变了：目标句真实下标从 2 漂到 4，而收藏时记的 hint 仍是 3
        drifted = ["新增的标题", "新增的空行"] + rows
        sm2 = SegmentMap.for_text_lookup(drifted)
        self.assertEqual(4, sm2.locate_row_by_text(needle, 3))
        # 就算 hint 被换成完全错的 1，按文本也照样命中
        self.assertEqual(4, sm2.locate_row_by_text(needle, 1))

    def test_locate_by_text_without_audio_segments(self):
        """for_text_lookup 不需要 segments：纯阅读（无音频）的书也能定位。"""
        sm = SegmentMap.for_text_lookup(["甲", "目标句子原文", "乙"])
        self.assertEqual(1, sm.locate_row_by_text("目标句子原文", -1))
        self.assertEqual(-1, sm.locate_row_by_text("根本不存在的句子", -1))

    def test_locate_normalizes_whitespace(self):
        sm = SegmentMap.for_text_lookup(["The  hood\t was here."])
        self.assertEqual(0, sm.locate_row_by_text("Thehoodwashere.", 1))


# ======================================================================
# 5) 右键菜单（PC 端「长按」等价交互）+ 跳转接线
#
# 本环境可能没装 PySide6，无法构造真实 QWidget；因此用源码静态断言验证
# wiring（右键策略 / 菜单 / 无出处禁用 / 新信号 / 定位接线都确实接上了），
# 另附一个 PySide6 可用时才跑的真实 GUI 用例。
# ======================================================================

class WordBookContextMenuTest(unittest.TestCase):

    @staticmethod
    def _source(rel_path: str) -> str:
        with open(os.path.join(_ROOT, *rel_path.split("/")), encoding="utf-8") as f:
            return f.read()

    def test_profile_context_menu_wiring_present(self):
        s = self._source("app/ui/pages/profile_page.py")
        self.assertIn("setContextMenuPolicy(Qt.CustomContextMenu)", s)
        self.assertIn(
            "customContextMenuRequested.connect(self._on_word_context_menu)", s)
        self.assertIn("QMenu(self)", s)
        self.assertIn("跳转到原书", s)
        self.assertIn("act.setEnabled(has_source)", s)   # 无出处时禁用
        self.assertIn("无收藏出处", s)                    # 并给出提示
        self.assertIn("word_jump_requested = Signal(int, int, dict)", s)
        # _load_words 必须把 6 键都塞进 item 角色
        for c in ("book_name", "book_key", "store_book_id",
                  "segment_number", "line_number", "sentence_text"):
            self.assertIn(c, s)

    def test_main_window_connects_jump_signal(self):
        s = self._source("app/main_window.py")
        self.assertIn('"word_jump_requested"', s)
        self.assertIn("jump=jump", s)
        self.assertIn("jump: dict = None", s)

    def test_reader_open_book_accepts_jump_and_locate(self):
        s = self._source("app/ui/pages/reader_page.py")
        self.assertIn("jump: dict = None", s)
        self.assertIn("locate_row_by_text", s)
        self.assertIn("self._skip_progress_restore", s)   # 跳过进度恢复抢占
        self.assertIn("self._pending_jump", s)

    @unittest.skipUnless(_HAS_PYSIDE6, "PySide6 未安装，跳过真实 GUI 右键策略用例")
    def test_qt_custom_context_menu_policy(self):
        from PySide6.QtCore import Qt
        from PySide6.QtWidgets import QApplication, QListWidget
        app = QApplication.instance() or QApplication([])
        try:
            lw = QListWidget()
            lw.setContextMenuPolicy(Qt.CustomContextMenu)
            self.assertEqual(Qt.CustomContextMenu, lw.contextMenuPolicy())
        finally:
            app.quit()


# ======================================================================
# 6) 端到端闭环：上传带出处 → 服务端回显 → 拉回写回本地 → 再遇无出处快照仍不丢
#    （对应契约里最强调的三点：务必带出、必须解析、列白名单别漏）
# ======================================================================

class WordBookEndToEndSourceTest(_TmpDbMixin):

    def test_source_survives_upload_pull_reconcile(self):
        # ① 本机收藏（带出处）落库，server_id 为空 = 待上传
        self._add_with_source()
        pending = self.db.list_pending_words()
        self.assertEqual(1, len(pending))
        # ② 同步层从本地行组装 upload payload，出处 6 键务必带出
        src = _word_source_payload(pending[0])
        self.assertTrue(_word_source_has_any(_wire_to_word_source(src)))
        upload = {"sw": pending[0].sw, "word": pending[0].word,
                  "translations": pending[0].definition, **src}
        # ③ 服务端回显（带 id + 原样出处）→ sync._extract_words 白名单解析
        echoed = dict(upload, id=88, createdAt="2026-01-01T00:00:00")
        items = SyncManager._extract_words({"words": [echoed]})
        # ④ 拉回写回本地：server_id 回填且出处保持
        stats = self.db.reconcile_words_from_server(items)
        self.assertEqual(1, stats["updated"])
        r = self._row()
        self.assertEqual(88, r["server_id"])
        self.assertEqual("书A", r["book_name"])
        self.assertEqual("这是收藏时所在的那一整行原文", r["sentence_text"])
        # ⑤ 之后再遇到「不带出处」的服务端快照（旧客户端 / 存量数据）→ 出处仍不丢
        self.db.reconcile_words_from_server([
            _server_item(88, "hood", "hood", translations="新版释义")])
        r2 = self._row()
        self.assertEqual("书A", r2["book_name"])
        self.assertEqual("这是收藏时所在的那一整行原文", r2["sentence_text"])
        self.assertEqual("新版释义", r2["definition"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
