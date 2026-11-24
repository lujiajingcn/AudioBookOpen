"""私有云端合成任务（``/api/tts/**``）的音频按需下载器。

## 为什么还需要它
书城音频走 ``/api/store``（公共资源，不校验任务归属），而**本机导入的书**在云端
合成后，音频挂在用户自己的任务下，只能走 ``/api/tts/task/{id}/segments-list`` +
``/api/tts/segment/{sid}/download``。两者机制相同（逐段、断点续传、并发预取），
所以共用 :class:`CloudAudioDownloaderBase`。

## 落库锚点
``audio_segments.task_id`` 用**本地** ``tasks.id``（与 ``OnlineTtsService.
download_completed_segments`` 完全一致），文件名也用 ``tts_{serverTaskId}_{段号:05d}.mp3``
—— 这样「整本下载完成」与「边下边播临时拉的一段」共用一个文件与一行记录，
不会出现两份字节、两行记录。

## 与书架徽标的对应
书架上「☁ 云端有声 · 可下载播放」的 t: 身份键（``t:{serverTaskId}``）就靠它兑现；
没有它时点播放只会弹「本书还没有合成音频」，而云端明明有 119 段。
"""
from __future__ import annotations

from typing import List

from app.core.store_audio import CloudAudioDownloaderBase
from app.db.database import LocalDatabase
from app.models.models import Segment, Task


class TaskAudioDownloader(CloudAudioDownloaderBase):
    """私有云端任务的音频按需下载器（key = server_task_id）。"""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        # server_task_id → 本地 tasks.id 缓存：落库是逐段调用的，
        # 每段都全表扫一遍 get_tasks() 会把播放期间的写库拖慢
        self._local_ids: dict = {}

    # ---------- 本地任务行 ----------

    def _local_task_id(self, server_task_id: int) -> int:
        """server_task_id → 本地 tasks.id；本地还没有该任务时补一行。

        补行沿用 ``OnlineTtsService.download_completed_segments`` 的既有做法
        （那里也是先 ``get_task_status`` 再 ``upsert_task``）：``audio_segments``
        必须有 task_id 锚点，否则阅读页 ``get_segments(task.id)`` 读不到这些段。
        """
        tid = int(server_task_id)
        cached = self._local_ids.get(tid)
        if cached:
            return int(cached)
        for t in self.db.get_tasks():
            if int(t.server_task_id or 0) == tid:
                self._local_ids[tid] = int(t.id or 0)
                return int(t.id or 0)
        try:
            remote = self.api.get_task(tid)
        except Exception:  # noqa: BLE001
            return 0
        try:
            lid = int(self.db.upsert_task(Task(
                id=0, book_name=remote.book_name or "",
                audio_name=remote.audio_name or f"云端合成 · {remote.book_name or tid}",
                status=remote.status or "completed",
                total_segments=remote.total_segments, completed_segments=remote.completed_segments,
                server_task_id=tid)) or 0)
        except Exception:  # noqa: BLE001
            return 0
        if lid:
            self._local_ids[tid] = lid
        return lid

    # ---------- 钩子实现 ----------

    def _fetch_segments(self, server_task_id: int) -> List[Segment]:
        return self.api.get_task_segments(int(server_task_id))

    def _filename(self, server_task_id: int, segment_number: int) -> str:
        # ⚠ 必须与 OnlineTtsService.download_completed_segments 的命名逐字符一致：
        #   否则同一段会下两份，断点续传也失效。
        return f"tts_{int(server_task_id)}_{int(segment_number):05d}.mp3"

    def _fetch_audio(self, server_task_id: int, seg: Segment, path: str) -> None:
        self.api.download_segment(seg.id, path)

    def _store_row(self, server_task_id: int, seg: Segment, path: str) -> None:
        local_id = self._local_task_id(server_task_id)
        if not local_id:
            return
        self.db.insert_segment(Segment(
            task_id=local_id, segment_number=seg.segment_number,
            text_content=seg.text_content, audio_file_path=path,
            duration_seconds=seg.duration_seconds, status="completed",
            voice=seg.voice, source="server"))
