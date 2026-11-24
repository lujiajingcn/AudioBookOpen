# -*- coding: utf-8 -*-
"""PC 端书架「暂停/恢复」能力的回归测试。

对齐安卓端 ``AudioBookPlayer`` 的实现逻辑：

1. ``moss/PauseController.kt`` ⇒ ``app/core/pause.py``
   暂停是**内存闸门**，不是改任务状态：
   - 只在**段边界**生效（当前那一段先跑完）；
   - 用 Condition 真正挂起 worker，不忙等（忙等会把 CPU 占满，
     而暂停的意义正是让机器停下来）；
   - 中止时必须 ``release_all()`` 放行等待者，否则「暂停中点中止」
     会让 worker 永远卡住 —— 比崩溃更难排查。

2. ``TaskRepository.upsertLocalTask`` ⇒ ``db.upsert_local_task``
   本机合成**同书同音色复用未终结的那一行**，而不是每次新建。
   新建的话断点续跑会失效（段落在旧行、新行一段都没有）。

3. ``ShelfTaskActions.computeTaskActions`` ⇒ ``shelf_page.compute_pause_action``
   按钮可见性/文案/可点性的唯一真源：
   - 「暂停/继续」**只对本机合成**成立（在线合成的状态在服务端，本机挂不住它）；
   - 本机合成**服务没在跑**时不给按钮（孤儿行，点下去是纯死按钮）。

运行：``python tests/test_shelf_pause.py``（需 PySide6，可离线跑）。
"""
from __future__ import annotations

import os
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from app.core.config import AppConfig  # noqa: E402
from app.core.pause import PauseController  # noqa: E402
from app.db.database import LocalDatabase  # noqa: E402
from app.models.models import Book, Task, TaskStatus  # noqa: E402
from app.ui.pages.shelf_page import compute_pause_action  # noqa: E402


class PauseControllerTest(unittest.TestCase):
    """闸门本身的语义。"""

    def test_not_paused_passes_through(self):
        pc = PauseController()
        self.assertFalse(pc.is_paused)
        self.assertTrue(pc.await_resume(lambda: False))

    def test_pause_blocks_until_resume(self):
        pc = PauseController()
        pc.pause()
        self.assertTrue(pc.is_paused)

        released = threading.Event()

        def _worker():
            pc.await_resume(lambda: False)
            released.set()

        t = threading.Thread(target=_worker, daemon=True)
        t.start()
        # 挂起期间不应放行
        self.assertFalse(released.wait(0.3))
        pc.resume()
        self.assertTrue(released.wait(3.0), "恢复后 worker 必须被放行")
        self.assertFalse(pc.is_paused)

    def test_cancelled_while_paused_releases_worker(self):
        """暂停中点中止：worker 必须能退出，而不是永远卡在 await_resume。"""
        pc = PauseController()
        pc.pause()
        cancelled = threading.Event()
        released = threading.Event()

        def _worker():
            # 中止标志在 worker 挂起之后才置位
            pc.await_resume(cancelled.is_set)
            released.set()

        t = threading.Thread(target=_worker, daemon=True)
        t.start()
        time.sleep(0.2)
        self.assertFalse(released.is_set())
        cancelled.set()
        pc.release_all()
        self.assertTrue(released.wait(3.0), "中止必须放行卡在暂停里的 worker")

    def test_cancel_flag_alone_does_not_wake_worker(self):
        """⚠️ 只置中止标志**不足以**放行 worker —— 必须配合 release_all()。

        这不是缺陷，而是 Condition 的固有语义：``wait()`` 只在 ``notify_all()``
        时返回，不会因为某个**别的**变量（这里是 ``cancelled`` 标志）变了就醒。
        Android 端同样如此，所以它的 ACTION_CANCEL 分支里
        ``cancelled.set(true)`` 之后**紧接着** ``pauseController.releaseAll()``。

        本测试把这个「必须成对出现」的约定钉死：将来若有人只加标志不
        加 release_all，中止时 worker 会永远卡住 —— 而这种 bug 表现为
        「界面一直挂着、进程还活着」，比崩溃更难排查。
        """
        pc = PauseController()
        pc.pause()
        cancelled = threading.Event()
        released = threading.Event()

        def _worker():
            pc.await_resume(cancelled.is_set)
            released.set()

        t = threading.Thread(target=_worker, daemon=True)
        t.start()
        time.sleep(0.2)
        cancelled.set()
        # 单独置标志：**不会**放行（wait 仍阻塞）
        self.assertFalse(released.wait(0.5),
                         "只置中止标志不该放行 worker（必须配合 release_all）")
        # 配合 release_all 后才放行
        pc.release_all()
        self.assertTrue(released.wait(3.0), "release_all 必须放行等待者")

    def test_pause_is_idempotent(self):
        pc = PauseController()
        pc.pause()
        pc.pause()
        self.assertTrue(pc.is_paused)
        pc.resume()
        pc.resume()
        self.assertFalse(pc.is_paused)


class LocalTaskReuseTest(unittest.TestCase):
    """tasks 表：本机任务行复用（断点续跑的前提）。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.db = LocalDatabase(AppConfig(data_dir=self.tmp))

    def tearDown(self):
        try:
            self.db.close()
        except Exception:  # noqa: BLE001
            pass

    def test_same_book_reuses_row(self):
        """同书同音色的第二轮合成必须复用第一行，而不是新建。"""
        audio = "本机合成 · 测试书"
        first = self.db.upsert_local_task("测试书", audio)
        self.assertGreater(first, 0)
        self.db.update_task_status(first, "paused", 27)

        second = self.db.upsert_local_task("测试书", audio)
        self.assertEqual(second, first, "必须复用同一行")

        rows = self.db.get_tasks()
        self.assertEqual(len(rows), 1, f"不该多出任务行，实际 {len(rows)} 行")

    def test_reuse_preserves_completed_segments(self):
        """复用时保留已落盘段数：重置成 0 会让用户「已完成 27 段」瞬间变「0 段」。"""
        audio = "本机合成 · 测试书"
        tid = self.db.upsert_local_task("测试书", audio)
        self.db.update_task_status(tid, "paused", 27)

        again = self.db.upsert_local_task("测试书", audio)
        t = self.db.get_task(again)
        self.assertEqual(t.status, "processing")
        self.assertEqual(t.completed_segments, 27, "已落盘段数不能被清零")

    def test_completed_task_not_reused(self):
        """completed 是已完成的历史记录，不该被复用（续跑按钮也不会出现在它上面）。"""
        audio = "本机合成 · 测试书"
        tid = self.db.upsert_local_task("测试书", audio)
        self.db.update_task_status(tid, "completed", 31)

        other = self.db.upsert_local_task("测试书", audio)
        self.assertNotEqual(other, tid, "completed 的行不该被复用")

    def test_different_voice_not_reused(self):
        """不同音色 = 不同合成方式，不该复用同一行。"""
        a = self.db.upsert_local_task("测试书", "本机合成 · Junhao")
        b = self.db.upsert_local_task("测试书", "本机合成 · Xiaoxiao")
        self.assertNotEqual(a, b)

    def test_local_task_has_no_server_task_id(self):
        """本机任务没有服务端身份：写上假 id 会让同步去轮询一个不存在的任务。"""
        tid = self.db.upsert_local_task("测试书", "本机合成 · 测试书")
        t = self.db.get_task(tid)
        self.assertIsNone(t.server_task_id)

    def test_progress_text_write_and_clear(self):
        """暂停只写 progress_text，**不改 status**（暂停时线程还活着）。"""
        tid = self.db.upsert_local_task("测试书", "本机合成 · 测试书")
        self.db.update_task_progress_text(tid, "已暂停")
        t = self.db.get_task(tid)
        self.assertEqual(t.progress_text, "已暂停")
        self.assertEqual(t.status, "processing", "暂停不得改任务状态")

        self.db.update_task_progress_text(tid, "")
        self.assertEqual(self.db.get_task(tid).progress_text, "")


class TaskIsTerminalTest(unittest.TestCase):
    """终态口径必须与服务端 TaskStatus.isTerminal 一致（含 partial）。"""

    def test_terminal_states(self):
        for st in ("completed", "partial", "failed"):
            self.assertTrue(Task(status=st).is_terminal, f"{st} 应算终态")
        for st in ("pending", "queued", "processing", "paused"):
            self.assertFalse(Task(status=st).is_terminal, f"{st} 不该算终态")


class ComputePauseActionTest(unittest.TestCase):
    """按钮规则表（对齐安卓 ShelfTaskActions）。"""

    @staticmethod
    def _local(status: str) -> Task:
        return Task(status=status, audio_name="本机合成 · 测试书")

    @staticmethod
    def _online(status: str) -> Task:
        return Task(status=status, audio_name="云端合成 · 测试书")

    def test_no_task_not_clickable(self):
        label, enabled, tip = compute_pause_action(None)
        self.assertEqual(label, "恢复")
        self.assertFalse(enabled)
        self.assertTrue(tip)

    def test_online_task_not_clickable(self):
        """在线合成在服务端跑，本机挂不住它 ⇒ 不给可点的按钮。"""
        for st in ("processing", "queued", "partial", "failed"):
            label, enabled, _ = compute_pause_action(self._online(st))
            self.assertFalse(enabled, f"在线 {st} 不该给可点按钮")
            self.assertEqual(label, "恢复")

    def test_local_processing_with_busy_service_shows_pause(self):
        label, enabled, _ = compute_pause_action(
            self._local("processing"), can_pause_local=True, is_current_session=True)
        self.assertEqual(label, "暂停")
        self.assertTrue(enabled)

    def test_local_paused_shows_continue(self):
        label, enabled, _ = compute_pause_action(
            self._local("processing"), can_pause_local=True, is_local_paused=True,
            is_current_session=True)
        self.assertEqual(label, "继续")
        self.assertTrue(enabled)

    def test_orphan_processing_not_clickable(self):
        """进程被杀留下的孤儿「合成中」行：服务没在跑 ⇒ 纯死按钮，不给。"""
        label, enabled, tip = compute_pause_action(
            self._local("processing"), can_pause_local=False)
        self.assertFalse(enabled)
        self.assertEqual(label, "恢复")
        self.assertTrue(tip)

    def test_other_book_processing_not_clickable(self):
        """别书的僵尸「合成中」行：服务在跑，但跑的是**另一本** ⇒ 不能点。

        点下去暂停的会是另一本书 —— 比死按钮更糟。
        """
        label, enabled, tip = compute_pause_action(
            self._local("processing"), can_pause_local=True,
            is_current_session=False)
        self.assertFalse(enabled)
        self.assertEqual(label, "恢复")
        self.assertTrue(tip)

    def test_finished_local_tasks_allow_resume(self):
        """已中止/部分完成/失败的本机任务 ⇒ 「恢复」可点（续跑，断点续跑）。"""
        for st in ("paused", "partial", "failed"):
            label, enabled, _ = compute_pause_action(self._local(st))
            self.assertEqual(label, "恢复", st)
            self.assertTrue(enabled, st)

    def test_pending_local_not_clickable(self):
        label, enabled, _ = compute_pause_action(self._local("pending"))
        self.assertFalse(enabled)
        self.assertEqual(label, "恢复")


if __name__ == "__main__":
    unittest.main(verbosity=2)
