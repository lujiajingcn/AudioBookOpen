"""阅读页「连播」真 GUI 回归：模拟线上那本书（本机只有 9–26、35–49）的播放队列。

## 为什么必须真跑页面
旧实现在**纯函数**层面看不出问题：它就是把「本机所有已下载段」按段号排好当队列，
``sorted()`` 永远是对的。错在**语义**——缺口的段不该出现在队列里，新落的段也不该
append 到队尾。只有真建 ``ReaderPage``、真喂 ``_on_cloud_segment_saved_ui`` 才能复现
「播到 26 跳到 35」「27 排在 49 之后」。

## 数据取自线上实测
用户那本《Linux命令行大全_一句话解释》(store_book_id=50) 本机恰好下了 33 段：
9–26（18 段）+ 35–49（15 段），服务端有 119 段。这个缺口分布正是故障现场。

运行::

    cd AudioBookPlayerPC
    python -m unittest tests.test_reader_playback -v
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

from app.core import play_queue  # noqa: E402
from app.core.config import AppConfig  # noqa: E402
from app.core.store_audio import StoreAudioDownloader  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402
from app.models.models import Segment  # noqa: E402

STORE_ID = 50
LOCAL_NUMS = list(range(9, 27)) + list(range(35, 50))   # 33 段，线上实测分布


class _FakeApi:
    """只实现阅读页会用到的那几个方法。"""

    def __init__(self, nums):
        self.nums = list(nums)

    def get_store_book_segments(self, book_id):
        return [Segment(id=20000 + n, segment_number=n, text_content=f"段{n}",
                        status="completed") for n in self.nums]

    def get_store_segment_audio(self, book_id, segment_id, save_path):
        with open(save_path, "wb") as f:
            f.write(b"X" * 256)


class _FakeEngine:
    """替身播放引擎：只记录被要求播什么，不出声（CI 无声卡）。"""

    def __init__(self):
        self.on_segment_start = None
        self.on_segment_end = None
        self.on_all_complete = None
        self.is_playing = False
        self.is_paused = False
        self.is_speaking = False
        self.current_index = 0
        self.current_position_ms = 0
        self.played = []
        self.appended = []
        self.stopped = 0
        self._probe = None

    # 页面调用的 API
    def play_sequence(self, files, start_index=0):
        self.played = list(files)
        self.current_index = start_index
        self.is_playing = True

    def append_files(self, paths):
        self.appended.extend(paths)

    def set_has_more_probe(self, probe):
        self._probe = probe

    def pause(self):
        self.is_paused = True

    def resume(self):
        self.is_paused = False
        self.is_playing = True

    def stop(self):
        self.stopped += 1
        self.is_playing = False
        self.played = []

    def set_speed(self, rate):
        pass


@unittest.skipUnless(_HAS_PYSIDE6, "PySide6 未安装，跳过真实 GUI 用例")
class ReaderContiguousQueueTest(unittest.TestCase):
    """真建 ReaderPage：队列必须从起播段起连续，新段只能接队尾。"""

    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.config = AppConfig(data_dir=self._tmp.name)
        self.db = LocalDatabase(self.config)
        self.addCleanup(self.db.close)
        self.engine = _FakeEngine()
        self.api = _FakeApi(range(1, 120))
        self.store_audio = StoreAudioDownloader(self.config, self.api, self.db)
        self.page = self._make_page()

    def _seed(self, nums) -> None:
        """按需铺「本机已下载」的段（文件 + audio_segments 行）。"""
        for n in nums:
            self._write_audio(n)

    # ---------- 装配 ----------

    def _make_page(self):
        from app.core.api_client import ApiClient
        from app.core.auth import AuthManager
        from app.ui.pages.reader_page import ReaderPage
        api = ApiClient(self.config)
        auth = AuthManager(self.config, api)
        page = ReaderPage(self.config, api, auth, self.db,
                          playback=self.engine, store_audio=self.store_audio)
        # 直接摆好书城身份 + 段元数据（不走网络加载正文）
        page.store_book_id = STORE_ID
        page.book_id = 0
        page.segments = [Segment(id=20000 + n, segment_number=n,
                                 text_content=f"段{n}", status="completed")
                         for n in range(1, 120)]
        # 关掉滑动窗口预取：本用例只验「队列怎么排」，预取会起线程池往临时目录灌文件，
        # 让 TemporaryDirectory 清理时撞「目录不是空的」。预取本身在 test_cloud_playback 里测。
        page._CLOUD_PREFETCH = 0
        return page

    def _write_audio(self, num: int) -> str:
        p = self.config.audio_dir / f"store_{STORE_ID}_{num:05d}.mp3"
        p.write_bytes(b"X" * 512)
        self.db.insert_segment(Segment(
            task_id=-STORE_ID, segment_number=num, text_content=f"段{num}",
            audio_file_path=str(p), status="completed", source="server"))
        return str(p)

    def _drop_audio(self, num: int):
        p = self.config.audio_dir / f"store_{STORE_ID}_{num:05d}.mp3"
        if p.exists():
            p.unlink()

    # ---------- 用例 ----------

    def test_window_is_contiguous_from_start(self):
        """本机 9–26 + 35–49：从 9 起播只能是 9–26，绝不能把 35 接进来。"""
        self._seed(LOCAL_NUMS)
        self.page.current_segment = 9
        self.page._build_audio_map()
        self.assertEqual(self.page._play_segnums, list(range(9, 27)))
        self.assertTrue(play_queue.is_contiguous(self.page._play_segnums))

    def test_start_missing_downloads_instead_of_skipping(self):
        """点 27（本机没有）时队列必须为空 ⇒ 页面会去下载它，而不是跳去 35。"""
        self._seed(LOCAL_NUMS)
        self.page.current_segment = 27
        self.page._build_audio_map()
        self.assertNotIn(27, self.page._audio_by_seg)
        # 未下载的起点 ⇒ 队列里不能出现 35（旧实现正是这样跳段）
        self.assertNotIn(35, self.page._play_segnums)

    def test_new_segment_appends_to_tail_in_order(self):
        """下完 27–34 应依次接在 26 之后，且只 append（不重排、不重复）。

        本用例只铺 9–26（35 以后**没有**），这样「补完缺口 = 队列末尾到 34」，
        能干净地断言「追加只发生一次、一次一段」。
        """
        self._seed(range(9, 27))
        self.page.current_segment = 9
        self.page._build_audio_map()
        self.page._do_play(9)
        self.assertEqual(len(self.engine.played), 18)
        self.assertEqual(self.engine.played[0],
                         str(self.config.audio_dir / "store_50_00009.mp3"))

        for n in range(27, 35):
            self._write_audio(n)
            self.page._on_cloud_segment_saved_ui(STORE_ID, n)

        self.assertEqual(self.page._play_segnums, list(range(9, 35)))
        self.assertTrue(play_queue.is_contiguous(self.page._play_segnums))
        self.assertEqual(len(self.engine.appended), 8)
        # 每次 append 的都是新段，且顺序升序
        self.assertEqual([os.path.basename(p) for p in self.engine.appended],
                         [f"store_50_{n:05d}.mp3" for n in range(27, 35)])

    def test_gap_segment_waits_for_previous(self):
        """先落 30（29 还缺）：不能抢跑接到队尾，否则播放顺序就错成 …26,30,29…"""
        self._seed(range(9, 27))
        self.page.current_segment = 9
        self.page._build_audio_map()
        self.page._do_play(9)

        self._write_audio(30)
        self.page._on_cloud_segment_saved_ui(STORE_ID, 30)
        self.assertEqual(self.page._play_segnums[-1], 26, "缺口前的段被抢跑追加了")

        self._write_audio(27)
        self._write_audio(28)
        self._write_audio(29)
        for n in (27, 28, 29):
            self.page._on_cloud_segment_saved_ui(STORE_ID, n)
        # 27、28、29、30 一起接上（30 早已在 _audio_by_seg 里）
        self.assertEqual(self.page._play_segnums, list(range(9, 31)))
        self.assertTrue(play_queue.is_contiguous(self.page._play_segnums))

    def test_stale_store_key_ignored(self):
        """别的书的段落盘不能污染当前播放队列。"""
        self._seed(range(9, 27))
        self.page.current_segment = 9
        self.page._build_audio_map()
        self.page._do_play(9)
        before = list(self.page._play_segnums)
        self.page._on_cloud_segment_saved_ui(STORE_ID + 1, 27)
        self.assertEqual(self.page._play_segnums, before)

    def test_replay_same_segment_not_duplicated(self):
        """同一段重复通知（并发下载去重失败时）不能把队列撑出重复项。"""
        self._seed(range(9, 27))
        self.page.current_segment = 9
        self.page._build_audio_map()
        self.page._do_play(9)
        self._write_audio(27)
        for _ in range(3):
            self.page._on_cloud_segment_saved_ui(STORE_ID, 27)
        self.assertEqual(self.page._play_segnums.count(27), 1)
        self.assertEqual(self.page._play_segnums, list(range(9, 28)))

    def test_audio_bar_seek_uses_segment_number(self):
        """拖播放条 → 段号必须**原样**当段号用，不能当队列下标（否则整体偏移）。

        旧实现按「队列下标」解释：队列从 9 开始时，拖到第 20 段会落在 9+20=29，
        用户感受到的就是「拖到哪儿都不对」。

        ⚠️ 实际在用的是 ``_FallbackAudioBar``（``widgets/__init__.py`` 没导出 AudioBar，
        所以 ``from app.ui.widgets import AudioBar`` 一直失败）—— 截图里底部就是
        「⏮ ▶ ⏭ + 段号输入框 + 第 N/M 段」，因此这里按占位条的信号（``seek_segment``）验。
        """
        self._seed(range(9, 27))
        self.page.current_segment = 9
        self.page._build_audio_map()
        self.page._do_play(9)
        bar = self.page.audio_bar

        bar.set_position(20, 119)
        self.assertEqual(bar.lbl.text(), "第 20/119 段")
        self.assertEqual(bar.spin.value(), 20)
        bar.seek_segment.emit(20)

        self.assertEqual(self.page.current_segment, 20)
        self.assertEqual(self.page._play_segnums[0], 20)
        self.assertEqual(os.path.basename(self.engine.played[0]),
                         "store_50_00020.mp3")


class _FailingDownloader:
    """段元数据存在、但每次下载都失败（模拟断网 / 服务端 500）。"""

    def __init__(self):
        self.calls = 0

    def segments(self, key):
        return [Segment(id=20000 + n, segment_number=n, text_content=f"段{n}",
                        status="completed") for n in range(1, 200)]

    def has_more_after(self, key, last):
        return True

    def is_local(self, key, n):
        return False

    def local_path(self, key, n):
        from pathlib import Path
        return Path("X:/nonexistent") / f"{n}.mp3"

    def ensure_segment_async(self, key, n, on_segment=None):
        self.calls += 1
        return True

    def prefetch(self, *a, **kw):
        pass


@unittest.skipUnless(_HAS_PYSIDE6, "PySide6 未安装，跳过真实 GUI 用例")
class DownloadFailureGiveUpTest(unittest.TestCase):
    """下载一直失败时必须**停下来说清楚**，不能无限刷请求、无限「正在下载第 N 段」。"""

    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_gives_up_after_max_tries(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        config = AppConfig(data_dir=tmp.name)
        db = LocalDatabase(config)
        self.addCleanup(db.close)
        from app.core.api_client import ApiClient
        from app.core.auth import AuthManager
        from app.ui.pages.reader_page import ReaderPage
        api = ApiClient(config)
        auth = AuthManager(config, api)
        engine = _FakeEngine()
        page = ReaderPage(config, api, auth, db, playback=engine)
        page.store_book_id = STORE_ID
        page.store_audio = _FailingDownloader()
        page._ENSURE_RETRY_SEC = 0.0        # 去掉冷却，快速跑到次数上限
        page._play_segnums = [STORE_ID]     # 队尾任意
        page.current_segment = STORE_ID
        page._want_play = True
        page._pb_timer.start()

        for _ in range(12):
            page._drive_cloud_playback()

        self.assertEqual(page.store_audio.calls, page._ENSURE_MAX_TRIES)
        self.assertFalse(page._want_play, "放弃后应清掉播放意图")
        self.assertIn("下载失败", page.status.text())
        self.assertFalse(page._pb_timer.isActive(), "放弃后应停掉位置轮询")


if __name__ == "__main__":
    unittest.main(verbosity=2)
