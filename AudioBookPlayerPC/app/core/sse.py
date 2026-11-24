"""SSE 客户端（对标 Android TaskProgressListener + SseClient）。

订阅 /api/tts/stream/{taskId} 实时接收合成进度。"""
from __future__ import annotations

import json
import threading
import time
from typing import Optional, Callable

import requests

from app.core.config import AppConfig


class SseClient:
    """后台线程 SSE 连接，收到 progress/complete/error 事件时回调。"""

    def __init__(self, config: AppConfig):
        self.config = config
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._task_id: Optional[int] = None

    def connect(self, task_id: int,
                on_progress: Callable[[int, int, str], None],  # (completed, total, status)
                on_complete: Callable[[int], None],             # (task_id)
                on_error: Callable[[str], None]):               # (message)
        """启动 SSE 连接（非阻塞）。"""
        self.disconnect()
        self._task_id = task_id
        self._stop_event.clear()

        def _run():
            url = self.config.api(f"/tts/stream/{task_id}")
            headers = {"Accept": "text/event-stream"}
            token = self.config.token
            if token:
                headers["Authorization"] = f"Bearer {token}"
            try:
                resp = requests.get(url, headers=headers, stream=True, timeout=(10, 600))
                if resp.status_code != 200:
                    on_error(f"SSE 连接失败: {resp.status_code}")
                    return
                event_type = ""
                data_buf = ""
                for line in resp.iter_lines(decode_unicode=True):
                    if self._stop_event.is_set():
                        break
                    if line is None:
                        continue
                    line = line.strip()
                    if line.startswith("event:"):
                        event_type = line[6:].strip()
                    elif line.startswith("data:"):
                        data_buf = line[5:].strip()
                    elif line == "" and data_buf:
                        # 空行 = 事件结束
                        self._dispatch(event_type, data_buf, on_progress, on_complete, on_error)
                        event_type = ""
                        data_buf = ""
            except requests.exceptions.ConnectionError:
                if not self._stop_event.is_set():
                    on_error("SSE 连接断开")
            except Exception as e:
                if not self._stop_event.is_set():
                    on_error(str(e))

        self._thread = threading.Thread(target=_run, daemon=True)
        self._thread.start()

    def _dispatch(self, event_type: str, data_str: str, on_progress, on_complete, on_error):
        try:
            data = json.loads(data_str)
        except json.JSONDecodeError:
            return
        if event_type == "progress" or "completedSegments" in data:
            completed = data.get("completedSegments", 0)
            total = data.get("totalSegments", 0)
            status = data.get("status", "processing")
            on_progress(completed, total, status)
        if event_type == "complete" or status == "completed":
            on_complete(self._task_id)
        if event_type == "error":
            on_error(data.get("message", "合成出错"))

    def disconnect(self):
        self._stop_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=5)
        self._thread = None
