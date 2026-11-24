"""书城音频下载器（StoreAudioDownloader）回归测试。

覆盖：
1. ``segments()`` 只保留 completed 段、按段号升序；
2. ``download_all()`` 只下载 completed 段、跳过已存在文件（断点续传）、落库到
   ``task_id = -store_book_id``、source='server'；
3. ``ensure_segment()`` 按需下载单段并落库；
4. ``has_more_after()`` 正确判断「某段号之后还有未下载段」。

运行：``python tests/test_store_audio.py``
"""
from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app.core.config import AppConfig  # noqa: E402
from app.core.store_audio import StoreAudioDownloader  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402
from app.models.models import Segment  # noqa: E402


def _seg(sid: int, num: int, status: str = "completed", text: str = "") -> dict:
    return {"id": sid, "number": num, "text": text or f"段{num}", "status": status}


class _FakeApi:
    """模拟 ApiClient 的书城段接口。"""

    def __init__(self, segments):
        self._segments = segments
        self.downloaded = []  # (book_id, segment_id)

    def get_store_book_segments(self, book_id):
        return [Segment(id=s["id"], segment_number=s["number"],
                        text_content=s["text"], status=s["status"])
                for s in self._segments]

    def get_store_segment_audio(self, book_id, segment_id, save_path):
        self.downloaded.append((book_id, segment_id))
        Path(save_path).write_bytes(b"FAKE_MP3")


class _TmpDbMixin(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.data_dir = self._tmp.name
        self.config = AppConfig(data_dir=self.data_dir)
        self.db = LocalDatabase(self.config)
        self.addCleanup(self.db.close)


class StoreAudioDownloaderTest(_TmpDbMixin):

    def _make_dl(self, segments):
        self.api = _FakeApi(segments)
        return StoreAudioDownloader(self.config, self.api, self.db)

    def test_segments_filters_completed_and_sorted(self):
        dl = self._make_dl([
            _seg(3, 3), _seg(1, 1), _seg(2, 2, status="failed"), _seg(4, 4),
        ])
        segs = dl.segments(7)
        self.assertEqual([s.segment_number for s in segs], [1, 3, 4])
        self.assertEqual(segs[0].id, 1)

    def test_download_all_downloads_completed_and_skips_existing(self):
        sid = 42
        # 段 2 已有本地文件 → 应跳过
        pre_path = self.config.audio_dir / f"store_{sid}_00002.mp3"
        pre_path.write_bytes(b"EXISTING")
        dl = self._make_dl([
            _seg(1, 1), _seg(2, 2), _seg(3, 3, status="failed"), _seg(4, 4),
        ])

        saved = []
        done = []
        t = dl.download_all(sid, on_segment=saved.append, on_done=lambda ok, m: done.append((ok, m)))
        t.join(timeout=10)

        # 只下载 completed 且本地缺失的段（段 1、4），段 2 跳过、段 3 failed 不下载
        self.assertEqual(sorted(seg_id for _, seg_id in self.api.downloaded), [1, 4])
        self.assertEqual(sorted(saved), [1, 4])
        self.assertTrue(done and done[-1][0], f"应成功，实际 {done}")

        # 落库锚点：task_id = -store_book_id，source='server'
        segs = self.db.get_segments(-sid)
        self.assertEqual(sorted(s.segment_number for s in segs), [1, 2, 4])
        for s in segs:
            self.assertEqual(s.task_id, -sid)
            self.assertEqual(s.source, "server")
            self.assertEqual(s.status, "completed")
        # 段 2 保留原文件内容（未被重新下载覆盖）
        self.assertEqual(pre_path.read_bytes(), b"EXISTING")

    def test_ensure_segment_downloads_and_upserts(self):
        sid = 9
        dl = self._make_dl([_seg(11, 5)])
        path = dl.ensure_segment(sid, 5)
        self.assertIsNotNone(path)
        self.assertTrue(Path(path).is_file())
        seg = self.db.get_segment(-sid, 5)
        self.assertIsNotNone(seg)
        self.assertEqual(seg.audio_file_path, str(path))
        # 再次 ensure 命中本地，不再重复下载
        dl.ensure_segment(sid, 5)
        self.assertEqual(len(self.api.downloaded), 1)

    def test_has_more_after(self):
        sid = 5
        dl = self._make_dl([_seg(1, 1), _seg(2, 2), _seg(3, 3)])
        # 段 1 已下载，段 2/3 未下载
        (self.config.audio_dir / f"store_{sid}_00001.mp3").write_bytes(b"X")
        self.assertTrue(dl.has_more_after(sid, 1))   # 后面还有 2、3
        self.assertFalse(dl.has_more_after(sid, 3))  # 3 之后没了
        # 全下载后 has_more_after(0) 应为 False
        t = dl.download_all(sid)
        t.join(timeout=10)
        self.assertFalse(dl.has_more_after(sid, 0))


if __name__ == "__main__":
    unittest.main(verbosity=2)
