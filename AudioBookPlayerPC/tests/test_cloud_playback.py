"""云端音频「连播」回归测试：播放队列窗口 + 并发有序下载 + 原子落盘 + 覆盖图锚点。

## 这些测试钉住的线上故障
1. **书架有徽标没有播放按钮** —— 徽标的 bookKey 全靠 ``local_audio_coverage()`` 算出来，
   而书城书的段锚点是 ``task_id = -store_book_id``（tasks 表里没有这一行）。
   旧实现把这类段当「孤儿段」丢弃 ⇒ 本机明明下了 33 段，覆盖图却说「本机没有」。
2. **阅读页不能连播** —— 旧实现把「本机所有已下载段」按段号排序当播放队列，
   段号有缺口时播到缺口就跳到下一段、且新落的段一律 append 到队尾 ⇒ 跳段/乱序/重复。
   现在队列必须**从起播段起连续**（``play_queue``）。
3. 下载中途被中断会留下「非空但不完整」的 mp3，被当成有效音频 ⇒ 该段永远重下不了。
   落盘必须走 ``.part`` + 原子改名。

运行：``python -m unittest tests.test_cloud_playback``
"""
from __future__ import annotations

import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

_HERE = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HERE)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app.core import play_queue  # noqa: E402
from app.core.config import AppConfig  # noqa: E402
from app.core.store_audio import StoreAudioDownloader  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402
from app.models.models import Segment  # noqa: E402


# ======================================================================
# 1. 播放队列窗口（纯函数）
# ======================================================================

class BuildWindowTest(unittest.TestCase):
    """``build_window`` 只取**连续**段，缺口必须停下来。"""

    def test_contiguous_run_from_start(self):
        avail = {n: f"/a/{n}.mp3" for n in range(1, 10)}
        self.assertEqual(play_queue.build_window(avail, 1), list(range(1, 10)))
        self.assertEqual(play_queue.build_window(avail, 5), [5, 6, 7, 8, 9])

    def test_stops_at_gap(self):
        """本机只有 9-26 与 35-49（线上实测分布）：从 9 起播只能拿到 9-26。"""
        avail = {n: f"/a/{n}.mp3" for n in list(range(9, 27)) + list(range(35, 50))}
        self.assertEqual(play_queue.build_window(avail, 9), list(range(9, 27)))

    def test_empty_when_start_missing(self):
        """起点不可得时返回空 —— 调用方据此「去下载这一段」，不许跳过中间内容。"""
        avail = {n: f"/a/{n}.mp3" for n in range(35, 50)}
        self.assertEqual(play_queue.build_window(avail, 27), [])

    def test_limit(self):
        avail = {n: f"/a/{n}.mp3" for n in range(1, 100)}
        self.assertEqual(play_queue.build_window(avail, 10, limit=3), [10, 11, 12])

    def test_invalid_start(self):
        avail = {1: "x"}
        self.assertEqual(play_queue.build_window(avail, 0), [])
        self.assertEqual(play_queue.build_window(avail, -3), [])


class ExtendTest(unittest.TestCase):
    """``extend`` 只能从队尾 +1 往后接连续段（引擎 append 一律排到队尾）。"""

    def test_appends_contiguous_only(self):
        avail = {n: f"/a/{n}.mp3" for n in [1, 2, 3, 5, 6]}
        queued = [1, 2]
        self.assertEqual(play_queue.extend(queued, avail), [3])   # 4 缺 → 停

    def test_nothing_when_gap(self):
        avail = {n: f"/a/{n}.mp3" for n in [1, 2, 7, 8]}
        self.assertEqual(play_queue.extend([1, 2], avail), [])

    def test_empty_queue(self):
        self.assertEqual(play_queue.extend([], {1: "x"}), [])


class IsContiguousTest(unittest.TestCase):

    def test_true_false(self):
        self.assertTrue(play_queue.is_contiguous([1, 2, 3]))
        self.assertTrue(play_queue.is_contiguous([]))
        self.assertTrue(play_queue.is_contiguous([7]))
        self.assertFalse(play_queue.is_contiguous([1, 3]))
        self.assertFalse(play_queue.is_contiguous([3, 2]))


# ======================================================================
# 2. 覆盖图锚点：书城书 task_id = -store_book_id
# ======================================================================

class _TmpDbMixin(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.data_dir = self._tmp.name
        self.config = AppConfig(data_dir=self.data_dir)
        self.db = LocalDatabase(self.config)
        # LIFO：先 close 再删目录（否则 Windows 报 WinError 32）
        self.addCleanup(self.db.close)

    def _insert_book(self, display_name: str, store_book_id=None) -> int:
        c = self.db._conn()
        c.execute(
            "INSERT INTO books (display_name, file_path, store_book_id) VALUES (?,?,?)",
            (display_name, "", store_book_id))
        c.commit()
        return int(c.execute("SELECT last_insert_rowid()").fetchone()[0])


class StoreAnchorCoverageTest(_TmpDbMixin):
    """书城锚点（负 task_id）必须计入本机覆盖，否则书架会说「本机没有音频」。"""

    def test_negative_task_id_counted_as_store_book(self):
        self._insert_book("Linux命令行大全_一句话解释", store_book_id=50)
        # 模拟 StoreAudioDownloader 落库：task_id = -50
        for n in (1, 2, 3, 5):
            p = self.config.audio_dir / f"store_50_{n:05d}.mp3"
            p.write_bytes(b"MP3")
            self.db.insert_segment(Segment(
                task_id=-50, segment_number=n, text_content=f"段{n}",
                audio_file_path=str(p), status="completed", source="server"))

        rows = self.db.local_audio_coverage()
        by_key = {(r["bookKey"], r["source"]): r for r in rows}
        self.assertIn(("s:50", "server"), by_key,
                      f"负 task_id 的书城段未被识别为 s:50，实际 {list(by_key)}")
        row = by_key[("s:50", "server")]
        self.assertEqual(row["storeBookId"], 50)
        self.assertEqual(row["doneCount"], 4)
        self.assertEqual(row["doneRanges"], "1-3,5")

    def test_true_orphan_still_skipped(self):
        """正 task_id 但没有对应 tasks 行 = 真孤儿（任务已删）→ 仍应跳过。"""
        self._insert_book("某书", store_book_id=0)
        p = self.config.audio_dir / "local_x_00001.mp3"
        p.write_bytes(b"MP3")
        self.db.insert_segment(Segment(
            task_id=999, segment_number=1, text_content="x",
            audio_file_path=str(p), status="completed", source="local"))
        self.assertEqual(self.db.local_audio_coverage(), [])


# ======================================================================
# 3. 并发有序 + 原子落盘
# ======================================================================

def _seg(sid: int, num: int) -> Segment:
    return Segment(id=sid, segment_number=num, text_content=f"段{num}",
                   status="completed")


class _SlowApi:
    """模拟真实网络：段号越大越早返回（故意制造乱序完成）。"""

    def __init__(self, nums, delay=None, fail=(), truncate=()):
        self.nums = list(nums)
        self.delay = delay or {}
        self.fail = set(fail)
        self.truncate = set(truncate)
        self.calls = []
        self._lock = threading.Lock()

    def get_store_book_segments(self, book_id):
        return [_seg(1000 + n, n) for n in self.nums]

    def get_store_segment_audio(self, book_id, segment_id, save_path):
        num = segment_id - 1000
        with self._lock:
            self.calls.append(num)
        if self.delay:
            time.sleep(self.delay.get(num, 0))
        if num in self.fail:
            raise OSError("网络中断")
        data = b"X" * 1024
        if num in self.truncate:
            # 写到一半就被打断：留下**非空但不完整**的文件
            Path(save_path).write_bytes(data[:10])
            raise OSError("连接被重置")
        Path(save_path).write_bytes(data)


class ConcurrentOrderedDownloadTest(_TmpDbMixin):
    """并发下载仍要**按段号升序**回调 —— 乱序 append 会让播放顺序错乱。"""

    def test_callbacks_are_ascending_despite_random_completion(self):
        nums = list(range(1, 13))
        # 段号小的延迟大 → 完成顺序与段号顺序相反
        delay = {n: (13 - n) * 0.01 for n in nums}
        api = _SlowApi(nums, delay=delay)
        dl = StoreAudioDownloader(self.config, api, self.db)

        got = []
        done = []
        t = dl.download_all(7, on_segment=got.append,
                            on_done=lambda ok, m: done.append((ok, m)))
        t.join(timeout=30)

        self.assertEqual(got, nums, f"回调必须升序，实际 {got}")
        self.assertEqual(len(api.calls), len(nums))
        self.assertTrue(done and done[-1][0], f"应成功，实际 {done}")
        self.assertEqual(sorted(s.segment_number for s in self.db.get_segments(-7)), nums)


class AtomicWriteTest(_TmpDbMixin):
    """失败的下载不许留下「能骗过 is_local 的半截文件」。"""

    def test_partial_download_leaves_no_valid_file(self):
        api = _SlowApi([1, 2, 3], fail={2})
        dl = StoreAudioDownloader(self.config, api, self.db)
        t = dl.download_all(11)
        t.join(timeout=20)

        self.assertTrue(dl.is_local(11, 1))
        self.assertFalse(dl.is_local(11, 2), "段2 下载失败却留下有效文件")
        self.assertTrue(dl.is_local(11, 3))
        # .part 残片必须被清掉，不能堆在音频目录里
        parts = list(self.config.audio_dir.glob("*.part"))
        self.assertEqual(parts, [], f"残留 .part：{parts}")
        # 失败段不得落库
        self.assertEqual(sorted(s.segment_number for s in self.db.get_segments(-11)), [1, 3])

    def test_truncated_then_retry_succeeds(self):
        """一次「写了半截就断」不算成功；重试必须能真正补上。"""
        api = _SlowApi([1], truncate={1})
        dl = StoreAudioDownloader(self.config, api, self.db)
        self.assertIsNone(dl.ensure_segment(13, 1))
        self.assertFalse(dl.is_local(13, 1))

        api.truncate.clear()          # 网络恢复
        path = dl.ensure_segment(13, 1)
        self.assertIsNotNone(path)
        self.assertTrue(Path(path).is_file())
        self.assertEqual(Path(path).read_bytes(), b"X" * 1024)

    def test_skips_existing_file_without_redownload(self):
        sid = 17
        p = self.config.audio_dir / f"store_{sid}_00001.mp3"
        p.write_bytes(b"OLD")
        api = _SlowApi([1, 2])
        dl = StoreAudioDownloader(self.config, api, self.db)
        t = dl.download_all(sid)
        t.join(timeout=20)
        self.assertNotIn(1, api.calls)          # 已有 → 不重复下
        self.assertEqual(p.read_bytes(), b"OLD")
        self.assertIn(2, api.calls)
        # 已有文件仍要幂等补落库（否则本机有字节、库里没记录）
        self.assertEqual(sorted(s.segment_number for s in self.db.get_segments(-sid)), [1, 2])


class ProbeTest(_TmpDbMixin):
    """队尾探针 / 按需下载的判定。"""

    def test_has_more_after(self):
        api = _SlowApi([1, 2, 3])
        dl = StoreAudioDownloader(self.config, api, self.db)
        (self.config.audio_dir / "store_5_00001.mp3").write_bytes(b"X")
        self.assertTrue(dl.has_more_after(5, 1))
        self.assertFalse(dl.has_more_after(5, 3))

    def test_ensure_segment_async_dedup(self):
        """同一段并发请求只下 1 次（避免两线程同时 os.replace 互相覆盖）。"""
        api = _SlowApi([1], delay={1: 0.15})
        dl = StoreAudioDownloader(self.config, api, self.db)
        fired = []
        started = []
        for _ in range(5):
            started.append(dl.ensure_segment_async(21, 1, on_segment=fired.append))
        # 至少有一个线程负责下载；其余复用 inflight
        self.assertTrue(any(started))
        for _ in range(60):
            if dl.is_local(21, 1):
                break
            time.sleep(0.05)
        self.assertTrue(dl.is_local(21, 1))
        self.assertEqual(api.calls, [1], f"重复下载：{api.calls}")
        del fired


if __name__ == "__main__":
    unittest.main(verbosity=2)
