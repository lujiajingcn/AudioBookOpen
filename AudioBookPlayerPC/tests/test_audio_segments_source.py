"""PC 端 ``audio_segments.source`` 显式列行为测试（P1 #5：去掉文件名前缀启发）。

覆盖四件事：
1. 全新 DB 经 ``LocalDatabase._init_tables() → _migrate()`` 后 ``audio_segments.source`` 列存在，
   DEFAULT 'local' 生效。
2. ``insert_segment(seg)`` 把 ``seg.source`` 忠实落库；``get_segments/get_segment`` 读回同值；
   非法值（'' / None / 'foo' / 'SERVER'）在写入侧被规范化为 'local'。
3. ``local_audio_coverage()`` 判源优先用 source 列，不再看文件名前缀——
   文件名 ``tts_`` 前缀但 source='local' 应归入 local 组，反之亦然。
4. 老 DB 无 source 列时的降级路径：``insert_segment`` 应写 7 列不崩溃，
   ``_row_source`` 应回退 'local'，``local_audio_coverage`` 应回退到文件名前缀启发；
   再次跑 ``_migrate()`` 应按文件名回填存量行（tts_ → server，其它 → local）。

运行：``python tests/test_audio_segments_source.py``
"""
from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
import unittest

# 允许直接 ``python tests/test_audio_segments_source.py`` 运行——把工程根加到 sys.path
_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app.core.config import AppConfig  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402
from app.models.models import Segment  # noqa: E402


class _TmpDbMixin(unittest.TestCase):
    """临时目录 + AppConfig(data_dir=tmp) + LocalDatabase(config)，测试结束自动清理。"""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.data_dir = self._tmp.name
        self.audio_dir = os.path.join(self.data_dir, "audio")
        os.makedirs(self.audio_dir, exist_ok=True)
        # AppConfig 只填 data_dir，避开 %LOCALAPPDATA% 真实配置（对齐 MEMORY 里的告警）
        self.config = AppConfig(data_dir=self.data_dir)
        self.db = LocalDatabase(self.config)
        # addCleanup 是 LIFO：这条在 self._tmp.cleanup 之后注册 → 先执行 close()
        # 释放 SQLite 文件句柄，再让临时目录被删除（否则 Windows 下清理报 PermissionError）。
        self.addCleanup(self.db.close)

    def _insert_task(self, book_id: int = 0, book_name: str = "测试书",
                     server_task_id=None, total: int = 3) -> int:
        """直接插一条 tasks 行，返回 task_id。绕过 create_task 的额外校验/依赖。"""
        c = self.db._conn()
        c.execute(
            "INSERT INTO tasks (server_task_id, book_id, book_name, audio_name, status, "
            "total_segments, completed_segments, created_at) VALUES (?,?,?,?,?,?,?,?)",
            (server_task_id, book_id, book_name, "", "completed", total, 0, ""),
        )
        c.commit()
        return int(c.execute("SELECT last_insert_rowid()").fetchone()[0])

    def _insert_book(self, display_name: str, store_book_id=None) -> int:
        c = self.db._conn()
        c.execute(
            "INSERT INTO books (display_name, file_path, store_book_id) VALUES (?,?,?)",
            (display_name, "", store_book_id),
        )
        c.commit()
        return int(c.execute("SELECT last_insert_rowid()").fetchone()[0])


class SourceColumnExistsTest(_TmpDbMixin):

    def test_migrate_adds_source_column(self):
        """新 DB 建表 + _migrate 后，audio_segments 应有 source 列。"""
        self.assertTrue(self.db._has_column("audio_segments", "source"))

    def test_default_value_is_local(self):
        """直接 SQL 插入不带 source 时，DEFAULT 'local' 生效。"""
        task_id = self._insert_task()
        c = self.db._conn()
        c.execute(
            "INSERT INTO audio_segments (task_id, segment_number, status) VALUES (?,?,?)",
            (task_id, 1, "completed"),
        )
        c.commit()
        row = c.execute(
            "SELECT source FROM audio_segments WHERE task_id=? AND segment_number=1",
            (task_id,),
        ).fetchone()
        self.assertEqual("local", row["source"])


class InsertSegmentSourceTest(_TmpDbMixin):

    def test_source_server_persisted(self):
        task_id = self._insert_task()
        self.db.insert_segment(Segment(
            task_id=task_id, segment_number=1, text_content="a",
            audio_file_path=os.path.join(self.audio_dir, "tts_1_00001.mp3"),
            duration_seconds=1.0, status="completed", voice="v", source="server",
        ))
        got = self.db.get_segment(task_id, 1)
        self.assertIsNotNone(got)
        self.assertEqual("server", got.source)

    def test_source_local_persisted(self):
        task_id = self._insert_task()
        self.db.insert_segment(Segment(
            task_id=task_id, segment_number=2, text_content="b",
            audio_file_path=os.path.join(self.audio_dir, "local_1_00002.mp3"),
            duration_seconds=1.0, status="completed", voice="v", source="local",
        ))
        got = self.db.get_segment(task_id, 2)
        self.assertIsNotNone(got)
        self.assertEqual("local", got.source)

    def test_default_source_is_local(self):
        """Segment 不显式传 source → 落 'local'（与 dataclass 默认一致）。"""
        task_id = self._insert_task()
        self.db.insert_segment(Segment(
            task_id=task_id, segment_number=3, text_content="c",
            audio_file_path=os.path.join(self.audio_dir, "x.mp3"),
            status="completed",
        ))
        got = self.db.get_segment(task_id, 3)
        self.assertEqual("local", got.source)

    def test_invalid_source_normalized_to_local(self):
        """写入侧对空串/None/未知字符串一律规范化为 'local'（防脏数据）。"""
        task_id = self._insert_task()
        for i, bad in enumerate(["", None, "foo", "SERVER", " server "], start=1):
            with self.subTest(bad=bad):
                self.db.insert_segment(Segment(
                    task_id=task_id, segment_number=i, text_content="x",
                    audio_file_path=os.path.join(self.audio_dir, f"x{i}.mp3"),
                    status="completed", source=bad,
                ))
                got = self.db.get_segment(task_id, i)
                self.assertEqual("local", got.source)

    def test_get_segments_reads_source_back(self):
        task_id = self._insert_task()
        self.db.insert_segment(Segment(task_id=task_id, segment_number=1,
                                       audio_file_path=os.path.join(self.audio_dir, "a.mp3"),
                                       status="completed", source="server"))
        self.db.insert_segment(Segment(task_id=task_id, segment_number=2,
                                       audio_file_path=os.path.join(self.audio_dir, "b.mp3"),
                                       status="completed", source="local"))
        segs = self.db.get_segments(task_id)
        self.assertEqual(2, len(segs))
        self.assertEqual("server", segs[0].source)
        self.assertEqual("local", segs[1].source)


class LocalAudioCoverageUsesSourceColumnTest(_TmpDbMixin):
    """覆盖图判源：source 列存在且非空时**优先**用它，忽略文件名前缀。"""

    def test_filename_prefix_ignored_when_source_column_set(self):
        """文件名以 ``tts_`` 开头但 source='local'（异常/迁移未回填）→ 判为 local。
        反之文件名 ``local_`` 开头但 source='server' → 判为 server。
        这一条正是 P1 #5 想去掉的启发式依赖。"""
        book_id = self._insert_book("书名A")
        task_id = self._insert_task(book_id=book_id, book_name="书名A", total=2)
        self.db.insert_segment(Segment(
            task_id=task_id, segment_number=1, text_content="x",
            audio_file_path=os.path.join(self.audio_dir, "tts_999_00001.mp3"),
            status="completed", source="local",   # 显式标 local，尽管文件名 tts_
        ))
        self.db.insert_segment(Segment(
            task_id=task_id, segment_number=2, text_content="y",
            audio_file_path=os.path.join(self.audio_dir, "local_999_00002.mp3"),
            status="completed", source="server",  # 显式标 server，尽管文件名 local_
        ))
        cov = self.db.local_audio_coverage()
        # 期望两个独立分组：一个 (bookKey, 'local')，一个 (bookKey, 'server')
        by_src = {(row["bookKey"], row["source"]): row for row in cov}
        # bookKey = n:书名a（无 store_book_id / server_task_id，走书名分支，归一化小写）
        self.assertIn(("n:书名a", "local"), by_src)
        self.assertIn(("n:书名a", "server"), by_src)
        self.assertEqual("1", by_src[("n:书名a", "local")]["doneRanges"])
        self.assertEqual("2", by_src[("n:书名a", "server")]["doneRanges"])


class LegacyDbFallbackTest(_TmpDbMixin):
    """老 DB 无 source 列时的降级路径。手工把列干掉模拟「未迁移」状态。"""

    def _drop_source_column(self):
        """sqlite 3.35+ 支持 DROP COLUMN；老版本需重建表。这里直接用 DROP。"""
        c = self.db._conn()
        try:
            c.execute("ALTER TABLE audio_segments DROP COLUMN source")
            c.commit()
        except sqlite3.OperationalError as e:
            self.skipTest(f"当前 SQLite 不支持 DROP COLUMN：{e}")

    def test_insert_segment_falls_back_to_seven_columns(self):
        """无 source 列时 insert_segment 应写 7 列不抛异常。"""
        self._drop_source_column()
        self.assertFalse(self.db._has_column("audio_segments", "source"))
        task_id = self._insert_task()
        # 就算 Segment 里带了 source='server'，也应安静地退化写入
        self.db.insert_segment(Segment(
            task_id=task_id, segment_number=1, text_content="x",
            audio_file_path=os.path.join(self.audio_dir, "a.mp3"),
            status="completed", source="server",
        ))
        got = self.db.get_segment(task_id, 1)
        self.assertIsNotNone(got)
        # 读回默认 'local'（列不存在，_row_source 兜底）
        self.assertEqual("local", got.source)

    def test_local_audio_coverage_uses_filename_heuristic(self):
        """无 source 列时，覆盖图判源回退到文件名前缀（tts_ → server）。"""
        self._drop_source_column()
        book_id = self._insert_book("书名B")
        task_id = self._insert_task(book_id=book_id, book_name="书名B", total=2)
        c = self.db._conn()
        # 手工插入，绕过 insert_segment（因为它也会因列不存在而退化）
        c.execute(
            "INSERT INTO audio_segments (task_id, segment_number, text_content, "
            "audio_file_path, duration_seconds, status, voice) VALUES (?,?,?,?,?,?,?)",
            (task_id, 1, "x", os.path.join(self.audio_dir, "tts_999_00001.mp3"),
             1.0, "completed", ""),
        )
        c.execute(
            "INSERT INTO audio_segments (task_id, segment_number, text_content, "
            "audio_file_path, duration_seconds, status, voice) VALUES (?,?,?,?,?,?,?)",
            (task_id, 2, "y", os.path.join(self.audio_dir, "local_999_00002.mp3"),
             1.0, "completed", ""),
        )
        c.commit()
        cov = self.db.local_audio_coverage()
        by_src = {(row["bookKey"], row["source"]): row for row in cov}
        self.assertIn(("n:书名b", "server"), by_src)
        self.assertIn(("n:书名b", "local"), by_src)


class MigrationBackfillTest(_TmpDbMixin):
    """_migrate 里的回填 SQL 应把 tts_ 前缀的老行标为 'server'，其它标 'local'。"""

    def test_backfill_by_filename_prefix(self):
        # 先在**没有** source 列的情形下塞三行——手工 DROP 后 INSERT
        c = self.db._conn()
        try:
            c.execute("ALTER TABLE audio_segments DROP COLUMN source")
            c.commit()
        except sqlite3.OperationalError as e:
            self.skipTest(f"当前 SQLite 不支持 DROP COLUMN：{e}")
        task_id = self._insert_task()
        c.execute(
            "INSERT INTO audio_segments (task_id, segment_number, audio_file_path, status) "
            "VALUES (?,?,?,?)",
            (task_id, 1, os.path.join(self.audio_dir, "tts_1_00001.mp3"), "completed"),
        )
        c.execute(
            "INSERT INTO audio_segments (task_id, segment_number, audio_file_path, status) "
            "VALUES (?,?,?,?)",
            (task_id, 2, os.path.join(self.audio_dir, "local_1_00002.mp3"), "completed"),
        )
        c.execute(
            "INSERT INTO audio_segments (task_id, segment_number, audio_file_path, status) "
            "VALUES (?,?,?,?)",
            (task_id, 3, os.path.join(self.audio_dir, "weird_1_00003.mp3"), "completed"),
        )
        c.commit()

        # 再跑一次 _migrate：应加列 + 按文件名回填
        self.db._migrate()
        rows = {r["segment_number"]: r["source"] for r in c.execute(
            "SELECT segment_number, source FROM audio_segments WHERE task_id=?", (task_id,))}
        self.assertEqual("server", rows[1])
        self.assertEqual("local", rows[2])
        self.assertEqual("local", rows[3])   # 未知前缀归 local，与旧启发一致


if __name__ == "__main__":
    unittest.main(verbosity=2)
