"""在线（云端）TTS 服务：把「上传书 → 云端合成 → 下载成品」这条链路编排好。

## 进度监控为什么要 SSE + 轮询双通道

``SseClient`` 走 ``/api/tts/stream/{taskId}``，服务端合成一段就推一次，延迟低；但
SSE 连接不稳（代理断连、服务端重启、token 过期），一旦断了界面上进度就永远停住。
Android 端正是为此配了 ``TaskPollingManager`` 兜底。这里同理：SSE 负责「快」，
轮询 ``GET /api/tts/task/{id}`` 负责「准」，任一通道报告终态即完成（用一把锁去重，
保证 on_complete 只触发一次）。
"""
from __future__ import annotations

import threading
import time
from pathlib import Path
from typing import Callable, Dict, List, Optional

from app.core.api_client import ApiClient, ApiException
from app.core.config import AppConfig
from app.core.sse import SseClient
from app.db.database import LocalDatabase
from app.models.models import Segment, Task

# 轮询兜底间隔（秒）：SSE 正常时它也顺带校准，SSE 断了它就是唯一来源
_POLL_INTERVAL = 8.0
# 最长监控时长（秒）：一本书云端合成可能很久，给足余量，靠终态/stop 提前结束
_MAX_MONITOR_SECONDS = 24 * 3600.0


class TtsOnlineError(RuntimeError):
    """云端合成编排中的错误。"""


class OnlineTtsService:
    """云端合成服务。"""

    def __init__(self, config: AppConfig, api: ApiClient, db: LocalDatabase, sse: SseClient):
        self.config = config
        self.api = api
        self.db = db
        self.sse = sse
        self._monitors: Dict[int, threading.Event] = {}   # task_id -> stop event
        self._lock = threading.Lock()

    # ---------- 建任务 ----------

    def create_task(self, book_path: str, voice_id: str = "",
                    ref_audio_path: str = "") -> int:
        """上传电子书、创建云端合成任务。

        返回**服务端** taskId（后续进度/下载都用它）。同时在本地 tasks 表登记一条
        （server_task_id 对齐），让书架能显示「合成中」。
        """
        src = Path(book_path)
        if not src.is_file():
            raise TtsOnlineError(f"电子书不存在：{book_path}")

        try:
            task_id = self.api.upload_book_for_tts(
                book_path=str(src),
                reference_audio_path=ref_audio_path or "",
                voice_id=voice_id or "",
            )
        except ApiException as exc:
            raise TtsOnlineError(f"上传失败：{exc.message}") from exc

        if not task_id:
            raise TtsOnlineError("服务端未返回 taskId")

        # 本地登记
        task = Task(id=0, book_name=src.stem,
                    audio_name=f"云端合成 · {src.stem}",
                    status="queued", server_task_id=task_id)
        self.db.upsert_task(task)
        return int(task_id)

    # ---------- 进度监控 ----------

    def start_progress_monitor(
        self,
        task_id: int,
        on_update: Callable[[Task], None],
        on_complete: Callable[[int], None],
        on_error: Callable[[str], None],
    ) -> Callable[[], None]:
        """订阅某任务的合成进度。

        同时挂 SSE（实时）与轮询（兜底）。任一通道报告终态即触发 ``on_complete``
        并停止监控。返回一个「停止监控」的回调，用完（如离开页面）务必调用。

        回调都在后台线程触发，UI 侧需自行切回主线程。
        """
        stop = threading.Event()
        with self._lock:
            self._monitors[task_id] = stop

        state = {"done": False}
        guard = threading.Lock()

        def _load_local_task() -> Optional[Task]:
            for t in self.db.get_tasks():
                if t.server_task_id == task_id:
                    return t
            return None

        def _finalize_complete() -> None:
            with guard:
                if state["done"]:
                    return
                state["done"] = True
            stop.set()
            try:
                self.sse.disconnect()
            except Exception:
                pass
            task = _load_local_task()
            if task is not None:
                self.db.update_task_status(task.id, "completed", task.total_segments or None)
            _safe(on_complete, task_id)

        def _finalize_error(msg: str) -> None:
            with guard:
                if state["done"]:
                    return
                state["done"] = True
            stop.set()
            try:
                self.sse.disconnect()
            except Exception:
                pass
            _safe(on_error, msg)

        def _emit_update(completed: int, total: int, status: str) -> None:
            task = _load_local_task()
            if task is None:
                task = Task(book_name="", server_task_id=task_id)
            task.completed_segments = completed
            task.total_segments = total
            task.status = status
            if task.id:
                self.db.update_task_status(task.id, status, completed)
            _safe(on_update, task)

        # ---- SSE ----
        def sse_progress(completed: int, total: int, status: str) -> None:
            _emit_update(completed, total, status)
            if status == "completed":
                _finalize_complete()
            elif status == "failed":
                _finalize_error("云端合成失败")

        def sse_complete(_tid: int) -> None:
            _finalize_complete()

        def sse_error(_msg: str) -> None:
            # SSE 断了不直接判失败：轮询还在兜底。只在轮询也拿不到时才升级。
            pass

        try:
            self.sse.connect(task_id, sse_progress, sse_complete, sse_error)
        except Exception:
            pass  # 连不上也没关系，全靠轮询

        # ---- 轮询兜底线程 ----
        def _poll_loop() -> None:
            deadline = time.time() + _MAX_MONITOR_SECONDS
            while not stop.is_set() and time.time() < deadline:
                # 等一个间隔（可被 stop 提前唤醒）
                if stop.wait(_POLL_INTERVAL):
                    return
                try:
                    t = self.api.get_task(task_id)
                except ApiException as exc:
                    if exc.status_code in (401, 402, 404):
                        _finalize_error(exc.message)
                        return
                    continue
                except Exception:
                    continue
                _emit_update(t.completed_segments, t.total_segments, t.status)
                if t.status == "completed":
                    _finalize_complete()
                    return
                if t.status == "failed":
                    _finalize_error("云端合成失败")
                    return
            if not stop.is_set():
                _finalize_error("监控超时")

        threading.Thread(target=_poll_loop, daemon=True,
                         name=f"tts-poll-{task_id}").start()

        def _stop() -> None:
            stop.set()
            try:
                self.sse.disconnect()
            except Exception:
                pass
            with self._lock:
                self._monitors.pop(task_id, None)

        return _stop

    def stop_all_monitors(self) -> None:
        with self._lock:
            monitors = list(self._monitors.items())
        for _, stop in monitors:
            stop.set()

    # ---------- 任务状态 / 操作 ----------

    def get_task_status(self, task_id: int) -> Task:
        """查询服务端任务状态，并回写本地。"""
        task = self.api.get_task(task_id)
        task.audio_name = task.audio_name or f"云端合成 · {task.book_name}"
        self.db.upsert_task(task)
        return task

    def retry_task(self, task_id: int) -> None:
        try:
            self.api.retry_task(task_id)
        except ApiException as exc:
            raise TtsOnlineError(f"重试失败：{exc.message}") from exc

    def retry_failed(self) -> None:
        try:
            self.api.retry_failed_tasks()
        except ApiException as exc:
            raise TtsOnlineError(f"批量重试失败：{exc.message}") from exc

    def delete_task(self, task_id: int, delete_local_audio: bool = True) -> None:
        """删除服务端任务；可选一并清理本地已下载的该任务音频。"""
        try:
            self.api.delete_task(task_id)
        except ApiException as exc:
            raise TtsOnlineError(f"删除失败：{exc.message}") from exc
        if delete_local_audio:
            self._delete_local_task_audio(task_id)

    def _delete_local_task_audio(self, server_task_id: int) -> None:
        local_task = next((t for t in self.db.get_tasks()
                           if t.server_task_id == server_task_id), None)
        if local_task is None:
            return
        for seg in self.db.get_segments(local_task.id):
            if seg.audio_file_path:
                try:
                    p = Path(seg.audio_file_path)
                    if p.is_file():
                        p.unlink()
                except OSError:
                    pass

    # ---------- 下载成品 ----------

    def download_completed_segments(self, task_id: int,
                                    on_progress: Optional[Callable[[int, int], None]] = None
                                    ) -> List[str]:
        """把某任务所有「已完成」的段 MP3 下到本地 audio_dir，返回本地路径列表（按段号）。

        已存在且大小 > 0 的文件跳过（支持断点续下）。每段入库（audio_segments），
        供阅读页按段号对齐「文字 ↔ 声音」。
        """
        task = self.get_task_status(task_id)  # 顺带 upsert 本地 task，保证有本地 id
        local_task = next((t for t in self.db.get_tasks()
                           if t.server_task_id == task_id), None)
        if local_task is None:
            local_task = task  # 兜底：upsert 已建

        segments = self.api.get_task_segments(task_id)
        completed = [s for s in segments if s.status == "completed" and s.id]
        completed.sort(key=lambda s: s.segment_number)

        paths: List[str] = []
        total = len(completed)
        for i, seg in enumerate(completed, start=1):
            local_name = f"tts_{task_id}_{seg.segment_number:05d}.mp3"
            save_path = self.config.audio_dir / local_name
            if not (save_path.is_file() and save_path.stat().st_size > 0):
                try:
                    self.api.download_segment(seg.id, str(save_path))
                except ApiException as exc:
                    # 单段下载失败不中断整批：记日志性质地跳过
                    if on_progress:
                        _safe(on_progress, i, total)
                    continue
            # 落库（本地段号 = 服务端段号，口径一致）
            # 显式 source='server'：本段字节由服务端下发，其它端可经同一接口下载，
            # 覆盖图判源不再依赖文件名前缀启发。
            self.db.insert_segment(Segment(
                task_id=local_task.id, segment_number=seg.segment_number,
                text_content=seg.text_content, audio_file_path=str(save_path),
                duration_seconds=seg.duration_seconds, status="completed", voice=seg.voice,
                source="server"))
            paths.append(str(save_path))
            if on_progress:
                _safe(on_progress, i, total)

        if total > 0 and len(paths) == total:
            self.db.update_task_status(local_task.id, "completed", total)
        return paths

    def download_segment(self, segment_id: int, save_path: str) -> str:
        """下载单段 MP3 到 save_path，返回该路径。"""
        return self.api.download_segment(segment_id, save_path)


def _safe(cb: Callable, *args) -> None:
    try:
        cb(*args)
    except Exception:
        pass
