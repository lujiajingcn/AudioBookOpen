"""PC 端 :mod:`app.core.dictionary` 离线词典测试。

## 为什么需要这个测试

2026-11-06 之前，PC 端把服务端下发的词典件当 **CSV** 解析，
而服务端下发的是 **gzip 的 SQLite 库** ⇒ ``UnicodeDecodeError``、词典恒空、查词恒无结果。
这类缺陷**不会报错、只会「查不到」**，靠人工点很难发现，必须有测试锁住。

## 测什么

1. **sw 归一口径**：必须与建库脚本 ``_dict_build/build_dict.py`` 的 ``strip_word()``
   以及 Android ``DictionaryManager.stripWord`` **逐字符一致** ——
   差一个字符 ⇒ 查词永远不命中。
2. **三条约定**：查 ``sw`` 而非 ``word``；``translation`` 按**字面量** ``\\n`` 切分；
   一个 ``sw`` 可对应多个 ``word``，**全部返回**。
3. **真实词典库端到端**：拿真的 ``dict.db`` 查词，验证命中与多词形。

## 跑法

```
python tests/test_dictionary.py          # unittest，无需 pytest
python -m pytest tests/test_dictionary.py -v
```

真实词典库（``dict.db`` / ``dict.db.gz``）不在工程里。
测试会自动去找这几处（找到就自动跑真实库用例，找不到就跳过）：

- ``../../agent_outputs/_dict_build/dict.db``
- ``../AudioBookLocalTtsTool/...`` 等同源目录
- 环境变量 ``AUDIOBOOK_DICT_DB`` 指定的绝对路径
"""
from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

# 允许直接 ``python tests/test_dictionary.py`` 运行——把工程根加到 sys.path
_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = Path(_HERE).parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from app.core import dictionary  # noqa: E402
from app.core.dictionary import (  # noqa: E402
    DictionaryManager,
    _edge_trim,
    _normalize_definition,
    _parse_translations,
    _strip_word,
)


# ---------- 定位真实词典库（可选，存在才跑真实库用例） ----------

def _find_real_dict_db() -> Path:
    """在常见位置找真实的 dict.db；找不到返回不存在的 Path（用例 skip）。"""
    env = os.environ.get("AUDIOBOOK_DICT_DB")
    if env and Path(env).is_file():
        return Path(env)
    candidates = [
        _ROOT / ".." / ".." / "agent_outputs" / "_dict_build" / "dict.db",
        _ROOT / ".." / "agent_outputs" / "_dict_build" / "dict.db",
        Path(r"<agent-outputs>/_dict_build/dict.db"),
    ]
    for c in candidates:
        try:
            if c.is_file():
                return c.resolve()
        except OSError:
            continue
    return Path("__no_real_dict_db__")


_REAL_DICT_DB = _find_real_dict_db()


def _make_manager(db_path: Path) -> DictionaryManager:
    """造一个指向指定 dict.db 的 DictionaryManager（不碰 config/api）。"""
    dm = DictionaryManager.__new__(DictionaryManager)
    import threading
    dm.config = None
    dm.api = None
    dm._dict_dir = db_path.parent
    dm._db_path = db_path
    dm._part_path = db_path.parent / "dict.db.gz.part"
    dm._info_path = db_path.parent / "dict.info"
    dm._lock = threading.Lock()
    dm._row_count = 0
    return dm


# ============ 归一化口径（跨端一致性合约） ============

class StripWordTest(unittest.TestCase):
    """``_strip_word`` 必须与建库脚本 / Android 逐字符一致。"""

    def test_strips_non_alnum_and_lowercases(self):
        self.assertEqual("hello", _strip_word("hello"))
        self.assertEqual("hello", _strip_word("Hello"))
        self.assertEqual("hello", _strip_word("HELLO"))

    def test_strips_inner_punctuation(self):
        # 连字符、撇号、空格、点全部剔除
        self.assertEqual("takeoff", _strip_word("take-off"))
        self.assertEqual("dont", _strip_word("don't"))
        self.assertEqual("helloworld", _strip_word("hello world"))
        self.assertEqual("running", _strip_word("Running."))

    def test_empty_inputs(self):
        self.assertEqual("", _strip_word(""))
        self.assertEqual("", _strip_word(None))
        self.assertEqual("", _strip_word("---"))
        self.assertEqual("", _strip_word("!!!"))

    def test_keeps_digits(self):
        self.assertEqual("covid19", _strip_word("COVID19"))
        self.assertEqual("covid19", _strip_word("COVID-19"))

    def test_matches_build_script_semantics(self):
        """与建库脚本 ``strip_word`` 逐字符一致（同一段算法，两处独立实现）。"""
        def build_script_strip_word(s):
            return "".join(ch for ch in s if ch.isalnum()).lower()

        for probe in ["hello", "take-off", "don't", "Running.", "COVID-19",
                      "Hood", "'hood", "-hood", "take off", "a", "", "  "]:
            self.assertEqual(build_script_strip_word(probe), _strip_word(probe),
                             f"sw 口径不一致: {probe!r}")

    def test_matches_db_sw_key(self):
        """与 ``app.db.database._sw_key`` 一致（单词本去重键 = 同一个 sw）。"""
        from app.db.database import _sw_key
        for probe in ["hello", "take-off", "don't", "Running.", "Hood"]:
            self.assertEqual(_sw_key(probe), _strip_word(probe), f"sw 不一致: {probe!r}")


class EdgeTrimTest(unittest.TestCase):

    def test_strips_edges_only(self):
        self.assertEqual("hello", _edge_trim("hello"))
        self.assertEqual("hello", _edge_trim("hello,"))
        self.assertEqual("hello", _edge_trim('"hello"'))
        self.assertEqual("hello", _edge_trim("  hello.  "))

    def test_keeps_inner_symbols(self):
        # 中间的连字符/撇号必须留着 —— take-off 与 takeoff 是两个词条
        self.assertEqual("take-off", _edge_trim("take-off"))
        self.assertEqual("don't", _edge_trim("don't"))

    def test_empty(self):
        self.assertEqual("", _edge_trim(""))
        self.assertEqual("", _edge_trim("---"))


class ParseTranslationsTest(unittest.TestCase):
    """约定 ②：``translation`` 里的换行是**字面量** ``\\n``（两字符）。"""

    def test_splits_on_literal_backslash_n(self):
        self.assertEqual(["a", "b"], _parse_translations("a\\nb"))

    def test_keeps_real_newline_as_part_of_item(self):
        # 真换行不是分隔符（库里 0 行含真换行），但也不该崩
        self.assertEqual(["a\nb"], _parse_translations("a\nb"))

    def test_drops_empty_items(self):
        self.assertEqual(["a"], _parse_translations("\\na\\n\\n"))

    def test_empty_input(self):
        self.assertEqual([], _parse_translations(None))
        self.assertEqual([], _parse_translations(""))

    def test_normalize_definition_converts_literal_n(self):
        self.assertEqual("a\nb", _normalize_definition("a\\nb"))
        self.assertEqual("", _normalize_definition(None))


# ============ 真实词典库端到端 ============

@unittest.skipUnless(_REAL_DICT_DB.is_file(), f"未找到真实词典库（{_REAL_DICT_DB}），跳过")
class RealDictDbTest(unittest.TestCase):
    """拿真的 dict.db 查词，锁住「查得到」这件事本身。"""

    @classmethod
    def setUpClass(cls):
        cls.dm = _make_manager(_REAL_DICT_DB)

    def test_basic_word_hits(self):
        for w in ["hello", "running", "abandon", "take-off", "don't"]:
            hit = self.dm.lookup(w)
            self.assertIsNotNone(hit, f"查不到真实词: {w!r}")
            self.assertTrue(hit["word"], "命中项缺 word")
            self.assertTrue(hit["definition"], f"命中项缺释义: {w!r}")

    def test_sw_normalization_makes_variants_hit(self):
        """约定 ①：查 sw 而非 word ⇒ 大小写/连字符/撇号变体都命中。"""
        base = self.dm.lookup("hello")
        self.assertIsNotNone(base)
        for variant in ["hello", "Hello", "HELLO", "hello."]:
            self.assertIsNotNone(self.dm.lookup(variant), f"变体未命中: {variant!r}")

    def test_multiple_words_under_same_sw_all_returned(self):
        """约定 ③：一个 sw 可对应多个 word，``lookup_all`` 必须全部返回。"""
        entries = self.dm.lookup_all("take-off")
        self.assertGreaterEqual(len(entries), 3,
                                f"take-off 的 sw=takeoff 应命中多条，实际 {len(entries)}")
        words = {e["word"] for e in entries}
        self.assertIn("take-off", words)
        self.assertIn("takeoff", words)

    def test_lookup_prefers_exact_case_match(self):
        """``lookup`` 优先返回与查询**完全同形**（大小写敏感）的词条。"""
        hit = self.dm.lookup("hood")
        self.assertIsNotNone(hit)
        self.assertEqual("hood", hit["word"])

    def test_translations_are_split_list(self):
        """约定 ②：命中项的 translations 必须是切好的列表，不是整坨。"""
        hit = self.dm.lookup("abandon")
        self.assertIsInstance(hit["translations"], list)
        self.assertGreaterEqual(len(hit["translations"]), 2)
        for item in hit["translations"]:
            self.assertNotIn("\\n", item, "translations 里仍有字面量 \\n")

    def test_miss_returns_none(self):
        self.assertIsNone(self.dm.lookup("zzzznotawordqqq"))
        self.assertIsNone(self.dm.lookup(""))
        self.assertIsNone(self.dm.lookup("   "))
        self.assertIsNone(self.dm.lookup("中文"))
        self.assertEqual([], self.dm.lookup_all("zzzznotawordqqq"))

    def test_is_ready_and_row_count(self):
        self.assertTrue(self.dm.is_ready())
        self.assertGreater(self.dm.row_count(), 700000)

    def test_lookup_uses_index_not_full_scan(self):
        """查询计划必须走 PRIMARY KEY —— 全表扫描在 76 万行上会让查词卡住。"""
        conn = sqlite3.connect(f"file:{_REAL_DICT_DB}?mode=ro", uri=True)
        try:
            plan = str(conn.execute(
                "EXPLAIN QUERY PLAN SELECT word FROM dict WHERE sw=?", ("hello",)
            ).fetchall())
        finally:
            conn.close()
        self.assertTrue("USING PRIMARY KEY" in plan or "USING INDEX" in plan,
                        f"查询未走索引: {plan}")


class RealDictDbMultiSwTest(unittest.TestCase):
    """用真实库统计一遍，确认「多词形」不是个例而是常态。"""

    @unittest.skipUnless(_REAL_DICT_DB.is_file(), "未找到真实词典库，跳过")
    def test_many_sw_have_multiple_words(self):
        conn = sqlite3.connect(f"file:{_REAL_DICT_DB}?mode=ro", uri=True)
        try:
            n = conn.execute(
                "SELECT COUNT(*) FROM (SELECT sw FROM dict GROUP BY sw HAVING COUNT(*) > 1)"
            ).fetchone()[0]
        finally:
            conn.close()
        # 建库脚本注释里记的是 23482 个键；这里只断言「确实存在大量多词形键」
        self.assertGreater(n, 1000, f"多词形键异常少: {n}")


# ============ 安装/卸载流程（用自造的小库，不依赖网络） ============

class InstallUninstallTest(unittest.TestCase):
    """验证安装信息读写、旧 CSV 清理、卸载清理。"""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dicttest_"))
        self.dm = _make_manager(self.tmp / "dict.db")

    def tearDown(self):
        import shutil
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _make_db(self):
        conn = sqlite3.connect(str(self.tmp / "dict.db"))
        conn.execute(
            "CREATE TABLE dict(sw TEXT NOT NULL, word TEXT NOT NULL, phonetic TEXT, "
            "translation TEXT, exchange TEXT, tag TEXT, collins INTEGER, oxford INTEGER, "
            "PRIMARY KEY(sw, word)) WITHOUT ROWID")
        conn.execute("INSERT INTO dict VALUES(?,?,?,?,?,?,?,?)",
                     ("hello", "hello", "hə'ləʊ", "int. 喂\\nn. 你好", "", "zk", 3, 1))
        conn.execute("INSERT INTO dict VALUES(?,?,?,?,?,?,?,?)",
                     ("hello", "Hello", "", "int. 喂（问候）", "", "", 0, 0))
        conn.commit()
        conn.close()

    def test_info_roundtrip(self):
        self.dm._write_info({"version": "1.0.28", "rows": "2"})
        info = self.dm._read_info()
        self.assertEqual("1.0.28", info["version"])
        self.assertEqual("2", info["rows"])

    def test_read_info_missing_file(self):
        self.assertEqual({}, self.dm._read_info())

    def test_legacy_csv_removed(self):
        """旧 CSV 方案留下的 ecdict.csv 必须被清掉（它其实是 SQLite 库）。"""
        legacy = self.tmp / "ecdict.csv"
        legacy.write_bytes(b"SQLite format 3\x00" + b"\x00" * 100)
        self.dm._cleanup_legacy_csv()
        self.assertFalse(legacy.exists())

    def test_uninstall_removes_all_artifacts(self):
        self._make_db()
        (self.tmp / "dict.info").write_text("rows=2\n", encoding="utf-8")
        (self.tmp / "dict.db.gz.part").write_bytes(b"partial")
        self.assertTrue(self.dm.is_ready())
        self.assertTrue(self.dm.uninstall())
        self.assertFalse(self.dm.is_ready())
        self.assertFalse((self.tmp / "dict.db").exists())
        self.assertFalse((self.tmp / "dict.info").exists())
        self.assertFalse((self.tmp / "dict.db.gz.part").exists())

    def test_lookup_on_missing_db_returns_none(self):
        """库不在时不能抛异常 —— 阅读页查词必须安全返回 None。"""
        self.assertIsNone(self.dm.lookup("hello"))
        self.assertEqual([], self.dm.lookup_all("hello"))
        self.assertFalse(self.dm.is_ready())

    def test_lookup_on_corrupt_db_returns_none(self):
        """库损坏时不能抛异常（读取时 sqlite3 会报 DatabaseError）。"""
        (self.tmp / "dict.db").write_bytes(b"not a sqlite database at all")
        self.assertIsNone(self.dm.lookup("hello"))
        self.assertEqual([], self.dm.lookup_all("hello"))

    def test_row_count_reads_info_first(self):
        """词条数优先读安装信息 —— COUNT(*) 是 76 万行全表扫描，不能每次都跑。"""
        self._make_db()
        (self.tmp / "dict.info").write_text("rows=768739\n", encoding="utf-8")
        self.assertEqual(768739, self.dm.row_count())

    def test_row_count_falls_back_to_count(self):
        """没有安装信息时才回落到 SELECT COUNT(*)。"""
        self._make_db()
        self.assertEqual(2, self.dm.row_count())

    def test_lookup_all_returns_all_spellings(self):
        self._make_db()
        entries = self.dm.lookup_all("hello")
        self.assertEqual(2, len(entries))
        self.assertEqual({"hello", "Hello"}, {e["word"] for e in entries})
        for e in entries:
            self.assertIsInstance(e["translations"], list)
            self.assertIsInstance(e["oxford"], bool)
            self.assertIsInstance(e["collins"], int)

    def test_lookup_prefers_exact_word(self):
        """``lookup`` 必须返回**大小写敏感**的完全同形词条。

        ⚠️ 库里 ``hello`` 与 ``Hello`` 是两条独立词条，而 ``ORDER BY word`` 是 ASCII 序
        （大写在小写前）⇒ ``Hello`` 排在前面。若用 ``.lower()`` 判「同形」，
        ``lookup("hello")`` 会取到 ``Hello``。
        """
        self._make_db()
        self.assertEqual("hello", self.dm.lookup("hello")["word"])
        self.assertEqual("Hello", self.dm.lookup("Hello")["word"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
