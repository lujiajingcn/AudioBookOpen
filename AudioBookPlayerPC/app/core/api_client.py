"""HTTP API 客户端（对标 Android RetrofitClient + ApiService + HeavyApiService）。

统一处理：
  - Token 注入 (Authorization: Bearer)
  - 401 → 触发登出回调
  - 402 → 触发余额不足回调
  - 超时分级（轻/重/SSE）
  - 文件上传/下载
"""
from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path
from typing import Optional, Callable, Dict, Any, List
from io import BytesIO

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from app.core.config import AppConfig
from app.models.models import (
    AuthResponse, Task, Segment, Voice, StoreBook, Book,
    Post, Comment, Favorite, CreditInfo, CreditPackage,
    CreditTransaction, ReadingProgress, ReferenceAudio,
    AppUpdateInfo, TocEntry, LineItem
)


class ApiException(Exception):
    def __init__(self, status_code: int, message: str = "", data: Any = None):
        self.status_code = status_code
        self.message = message
        self.data = data
        super().__init__(f"[{status_code}] {message}")


def parse_image_urls(raw: Any) -> List[str]:
    """把服务端的 ``imageUrls`` 字段规整成 ``List[str]``。

    服务端返回的是**数组**（PostService.toItem → ``m.put("imageUrls", ...)``），
    早期 PC 端按单个字符串 ``imageUrl`` 读，永远取不到值。
    这里同时容忍数组 / 单串 / None，并滤掉非字符串项。
    """
    if raw is None:
        return []
    if isinstance(raw, str):
        raw = [raw]
    if not isinstance(raw, (list, tuple)):
        return []
    return [u for u in raw if isinstance(u, str) and u.strip()]


# 兼容旧名（内部曾用下划线前缀，外部模块也引用过）
_parse_image_urls = parse_image_urls


class ApiClient:
    """线程安全的 API 客户端。"""

    # 超时分级
    TIMEOUT_LIGHT = (10, 30)    # (connect, read)
    TIMEOUT_HEAVY = (15, 300)
    TIMEOUT_SSE = (10, 600)

    def __init__(self, config: AppConfig):
        self.config = config
        self._session = requests.Session()
        self._session.headers.update({"User-Agent": "AudioBookPlayerPC/1.0"})
        # 重试
        retry = Retry(total=2, backoff_factor=0.5, status_forcelist=[502, 503, 504])
        adapter = HTTPAdapter(max_retries=retry)
        self._session.mount("http://", adapter)
        self._session.mount("https://", adapter)
        # 回调
        self.on_unauthorized: Optional[Callable] = None
        self.on_insufficient_credits: Optional[Callable] = None
        self._lock = threading.Lock()

    # ---------- 内部 ----------

    def _url(self, path: str) -> str:
        return self.config.api(path)

    def _headers(self, extra: Dict = None) -> Dict[str, str]:
        h = {}
        token = self.config.token
        if token:
            h["Authorization"] = f"Bearer {token}"
        if extra:
            h.update(extra)
        return h

    def _handle_response(self, resp: requests.Response) -> Any:
        """统一解析响应，处理 401/402。"""
        if resp.status_code == 401:
            if self.on_unauthorized:
                self.on_unauthorized()
            raise ApiException(401, "登录已过期")
        if resp.status_code == 402:
            try:
                body = resp.json()
            except Exception:
                body = {}
            if self.on_insufficient_credits:
                self.on_insufficient_credits(body)
            raise ApiException(402, body.get("message", "积分不足"), body)
        if resp.status_code >= 400:
            try:
                body = resp.json()
                msg = body.get("message", resp.text[:200])
            except Exception:
                msg = resp.text[:200]
            raise ApiException(resp.status_code, msg)
        try:
            return resp.json()
        except Exception:
            return resp.text

    def _get(self, path: str, params: Dict = None, timeout=None) -> Any:
        r = self._session.get(self._url(path), params=params,
                              headers=self._headers(), timeout=timeout or self.TIMEOUT_LIGHT)
        return self._handle_response(r)

    def _post(self, path: str, json_data: Any = None, params: Dict = None,
              timeout=None, headers: Dict = None) -> Any:
        h = self._headers(headers)
        r = self._session.post(self._url(path), json=json_data, params=params,
                               headers=h, timeout=timeout or self.TIMEOUT_LIGHT)
        return self._handle_response(r)

    def _post_multipart(self, path: str, files: Dict, data: Dict = None,
                        timeout=None) -> Any:
        h = self._headers()
        h.pop("Content-Type", None)
        r = self._session.post(self._url(path), files=files, data=data,
                               headers=h, timeout=timeout or self.TIMEOUT_HEAVY)
        return self._handle_response(r)

    def _put(self, path: str, json_data: Any = None, timeout=None) -> Any:
        r = self._session.put(self._url(path), json=json_data,
                              headers=self._headers(), timeout=timeout or self.TIMEOUT_LIGHT)
        return self._handle_response(r)

    def _delete(self, path: str, timeout=None) -> Any:
        r = self._session.delete(self._url(path), headers=self._headers(),
                                 timeout=timeout or self.TIMEOUT_LIGHT)
        return self._handle_response(r)

    # ========== 认证 /auth ==========

    def login(self, username: str, password: str) -> AuthResponse:
        data = self._post("/auth/login", {"username": username, "password": password})
        return AuthResponse(
            token=data.get("token", ""),
            is_admin=data.get("isAdmin", False),
            success=data.get("success", True),
            message=data.get("message", "")
        )

    def register(self, username: str, password: str, email: str = "") -> AuthResponse:
        data = self._post("/auth/register", {"username": username, "password": password, "email": email})
        return AuthResponse(
            token=data.get("token", ""),
            is_admin=data.get("isAdmin", False),
            success=data.get("success", True),
        )

    def validate_token(self) -> Dict:
        return self._get("/auth/validate")

    def change_password(self, old_pwd: str, new_pwd: str):
        return self._post("/auth/change-password", {"oldPassword": old_pwd, "newPassword": new_pwd})

    # ========== TTS 任务 /tts ==========

    def get_voices(self) -> List[Voice]:
        data = self._get("/tts/voices")
        items = data if isinstance(data, list) else data.get("voices", [])
        return [Voice(id=str(v.get("id", v.get("demo_id", ""))), display_name=v.get("name", v.get("display", "")))
                for v in items]

    def upload_book_for_tts(self, book_path: str, reference_audio_path: str = "",
                            voice_id: str = "", store_book_id: int = 0) -> int:
        """上传书创建合成任务，返回 taskId。"""
        files = {"bookFile": (os.path.basename(book_path), open(book_path, "rb"))}
        data = {}
        if reference_audio_path:
            files["referenceAudio"] = (os.path.basename(reference_audio_path),
                                        open(reference_audio_path, "rb"))
        if voice_id:
            data["defaultVoiceId"] = voice_id
        if store_book_id:
            data["storeBookId"] = store_book_id
        try:
            result = self._post_multipart("/tts/upload-book", files, data)
            return result.get("taskId", result.get("id", 0))
        finally:
            for f in files.values():
                if hasattr(f[1], 'close'):
                    f[1].close()

    def create_task_from_store(self, store_book_id: int, voice_id: str = "") -> int:
        data = self._post("/tts/from-store", {"storeBookId": store_book_id, "defaultVoiceId": voice_id})
        return data.get("taskId", data.get("id", 0))

    def get_task(self, task_id: int) -> Task:
        d = self._get(f"/tts/task/{task_id}")
        t = d.get("task", d) if isinstance(d, dict) else {}
        return Task(
            id=t.get("taskId", task_id),
            book_name=t.get("originalBookName", ""),
            status=t.get("status", ""),
            total_segments=t.get("totalSegments", 0),
            completed_segments=t.get("completedSegments", 0),
            server_task_id=t.get("taskId", task_id),
            error_message=t.get("errorMessage", "") or "",
            created_at=t.get("createdAt", "") or "",
        )

    def get_user_tasks_raw(self, page: int = None, size: int = None) -> Dict:
        """返回 /tts/tasks 的完整响应体（tasks / total / queuedCount / truncated ...）。

        不传 page/size → 服务端返回最近上限内的一条批量；传则分页（page 从 1 开始）。
        """
        params: Dict[str, Any] = {}
        if page is not None:
            params["page"] = page
        if size is not None:
            params["size"] = size
        data = self._get("/tts/tasks", params or None)
        return data if isinstance(data, dict) else {"tasks": data if isinstance(data, list) else []}

    def get_user_tasks(self) -> List[Task]:
        data = self.get_user_tasks_raw()
        items = data.get("tasks", []) if isinstance(data, dict) else (data or [])
        return [Task(id=t.get("taskId"), server_task_id=t.get("taskId"),
                     book_name=t.get("originalBookName", ""),
                     status=t.get("status", ""), total_segments=t.get("totalSegments", 0),
                     completed_segments=t.get("completedSegments", 0),
                     created_at=t.get("createdAt", "") or "",
                     error_message=t.get("errorMessage", "") or "")
                for t in items]

    def get_task_segments(self, task_id: int) -> List[Segment]:
        data = self._get(f"/tts/task/{task_id}/segments-list")
        items = data if isinstance(data, list) else data.get("segments", [])
        return [Segment(id=s.get("id"), task_id=task_id, segment_number=s.get("segmentNumber", 0),
                        text_content=s.get("textContent", ""), audio_file_path=s.get("audioFilePath", ""),
                        duration_seconds=s.get("durationSeconds", 0), status=s.get("status", ""))
                for s in items]

    def retry_task(self, task_id: int):
        return self._post(f"/tts/task/{task_id}/retry")

    def retry_failed_tasks(self):
        return self._post("/tts/tasks/retry-failed")

    def delete_task(self, task_id: int):
        return self._delete(f"/tts/task/{task_id}")

    def update_task_voices(self, task_id: int, default_voice_id: str, segment_voices: Dict = None):
        body = {"defaultVoiceId": default_voice_id}
        if segment_voices:
            body["segmentVoices"] = json.dumps(segment_voices)
        return self._post(f"/tts/task/{task_id}/voices", body)

    def get_task_toc(self, task_id: int) -> List[TocEntry]:
        data = self._get(f"/tts/task/{task_id}/toc")
        items = data if isinstance(data, list) else data.get("toc", [])
        return [TocEntry(title=t.get("title", ""), level=t.get("level", 0),
                         paragraph_index=t.get("paragraphIndex", 0)) for t in items]

    def download_segment(self, segment_id: int, save_path: str) -> str:
        """下载单段 MP3。"""
        r = self._session.get(self._url(f"/tts/segment/{segment_id}/download"),
                              headers=self._headers(), timeout=self.TIMEOUT_HEAVY, stream=True)
        if r.status_code != 200:
            raise ApiException(r.status_code, "下载失败")
        with open(save_path, "wb") as f:
            for chunk in r.iter_content(65536):
                f.write(chunk)
        return save_path

    def download_task_all(self, task_id: int, save_path: str) -> str:
        """下载整任务 ZIP。"""
        r = self._session.get(self._url(f"/tts/task/{task_id}/download-all"),
                              headers=self._headers(), timeout=self.TIMEOUT_HEAVY, stream=True)
        if r.status_code != 200:
            raise ApiException(r.status_code, "下载失败")
        with open(save_path, "wb") as f:
            for chunk in r.iter_content(65536):
                f.write(chunk)
        return save_path

    # ========== 参考音频 /tts ==========

    def upload_reference(self, file_path: str, name: str = "") -> Dict:
        files = {"file": (os.path.basename(file_path), open(file_path, "rb"))}
        try:
            return self._post_multipart("/tts/references", files, {"name": name or os.path.basename(file_path)})
        finally:
            files["file"][1].close()

    def get_references(self) -> List[Dict]:
        data = self._get("/tts/references")
        return data if isinstance(data, list) else data.get("references", [])

    def delete_reference(self, ref_id: int):
        return self._delete(f"/tts/references/{ref_id}")

    def get_server_references(self) -> List[Dict]:
        data = self._get("/tts/server-references")
        return data if isinstance(data, list) else data.get("references", [])

    def download_server_reference(self, ref_id: int, save_path: str):
        r = self._session.get(self._url(f"/tts/server-references/{ref_id}/audio"),
                              headers=self._headers(), timeout=self.TIMEOUT_HEAVY, stream=True)
        with open(save_path, "wb") as f:
            for chunk in r.iter_content(65536):
                f.write(chunk)

    # ========== 书城 /store ==========

    def get_store_home(self) -> Dict:
        return self._get("/store/home")

    def get_store_books(self, page: int = 0, size: int = 20,
                        category: str = "", keyword: str = "", tag: str = "") -> Dict:
        params = {"page": page, "size": size}
        if category:
            params["category"] = category
        if keyword:
            params["keyword"] = keyword
        # 标签与分类/关键词是叠加关系（服务端按「与」组合过滤），不是互斥切换
        if tag:
            params["tag"] = tag
        return self._get("/store/books", params)

    def get_store_tags(self) -> List[str]:
        """书城标签名列表（标签名 + 在售书籍数，这里只取名字用于筛选栏）。"""
        data = self._get("/store/tags")
        raw = data if isinstance(data, list) else (data or {}).get("tags", [])
        out: List[str] = []
        for item in raw or []:
            if isinstance(item, str):
                name = item
            elif isinstance(item, dict):
                name = item.get("name") or ""
            else:
                name = str(item) if item is not None else ""
            name = name.strip() if isinstance(name, str) else ""
            if name and name not in out:
                out.append(name)
        return out

    def get_store_categories(self) -> List[str]:
        data = self._get("/store/categories")
        raw = data if isinstance(data, list) else (data or {}).get("categories", [])
        out: List[str] = []
        for item in raw or []:
            if isinstance(item, str):
                name = item
            elif isinstance(item, dict):
                name = (item.get("name") or item.get("category")
                        or item.get("label") or item.get("value") or "")
            else:
                name = str(item) if item is not None else ""
            name = name.strip() if isinstance(name, str) else ""
            if name and name not in out:
                out.append(name)
        return out

    def get_store_book_detail(self, book_id: int) -> StoreBook:
        d = self._get(f"/store/book/{book_id}")
        return StoreBook(id=d.get("id", book_id), title=d.get("title", ""),
                         author=d.get("author", ""), category=d.get("category", ""),
                         description=d.get("description", ""),
                         file_extension=d.get("fileExtension", ""),
                         file_size=d.get("fileSize", 0), task_id=d.get("taskId"),
                         tags=list(d.get("tags") or []))

    def get_store_book_content(self, book_id: int) -> Dict:
        """获取书城书正文（段落+目录+原文行）。"""
        return self._get(f"/store/book/{book_id}/content", timeout=self.TIMEOUT_HEAVY)

    def get_store_book_segments(self, book_id: int) -> List[Segment]:
        """本书的音频片段元数据（服务端已按段号升序返回）。

        服务端 ``/store/book/{id}/segments`` 只回 ``id/segmentNumber/textContent/status/voice``
        —— 不含 ``audioFilePath``（服务端路径不外泄）。只有 ``status == "completed"`` 的段
        才有音频，未完成段请求单段接口必然 4xx。audio_file_path 一律留空，由下载器落库时回填。
        """
        data = self._get(f"/store/book/{book_id}/segments")
        items = data if isinstance(data, list) else data.get("segments", [])
        return [Segment(id=s.get("id"), segment_number=s.get("segmentNumber", 0),
                        text_content=s.get("textContent", ""), audio_file_path="",
                        duration_seconds=s.get("durationSeconds", 0),
                        status=s.get("status", ""), voice=s.get("voice", ""))
                for s in items]

    def get_store_segment_audio(self, book_id: int, segment_id: int, save_path: str):
        r = self._session.get(self._url(f"/store/book/{book_id}/segments/{segment_id}/audio"),
                              headers=self._headers(), timeout=self.TIMEOUT_HEAVY, stream=True)
        if r.status_code != 200:
            raise ApiException(r.status_code, "音频下载失败")
        with open(save_path, "wb") as f:
            for chunk in r.iter_content(65536):
                f.write(chunk)

    def download_store_book(self, book_id: int, save_path: str):
        r = self._session.get(self._url(f"/store/book/{book_id}/download"),
                              headers=self._headers(), timeout=self.TIMEOUT_HEAVY, stream=True)
        with open(save_path, "wb") as f:
            for chunk in r.iter_content(65536):
                f.write(chunk)

    def get_store_book_toc(self, book_id: int) -> List[TocEntry]:
        data = self._get(f"/store/book/{book_id}/toc")
        items = data if isinstance(data, list) else data.get("toc", [])
        return [TocEntry(title=t.get("title", ""), level=t.get("level", 0),
                         paragraph_index=t.get("paragraphIndex", 0)) for t in items]

    # ========== 阅读进度 ==========

    def get_store_reading_progress_raw(self, book_id: int) -> Dict:
        d = self._get(f"/store/book/{book_id}/progress")
        return d if isinstance(d, dict) else {}

    def get_store_reading_progress(self, book_id: int) -> ReadingProgress:
        d = self.get_store_reading_progress_raw(book_id)
        if not d.get("hasProgress"):
            return ReadingProgress()
        return ReadingProgress(segment_number=d.get("segment", 0) or 0,
                               offset_ms=d.get("offsetMs", 0) or 0,
                               server_updated_at=d.get("updatedAt", "") or "")

    def put_store_reading_progress(self, book_id: int, segment_number: int, offset_ms: int):
        return self._put(f"/store/book/{book_id}/progress",
                         {"segment": segment_number, "offsetMs": offset_ms})

    def get_progress_raw(self, task_id: int) -> Dict:
        d = self._get("/progress", {"taskId": task_id})
        return d if isinstance(d, dict) else {}

    def get_progress(self, task_id: int) -> ReadingProgress:
        d = self.get_progress_raw(task_id)
        if not d.get("hasProgress"):
            return ReadingProgress()
        return ReadingProgress(segment_number=d.get("segment", 0) or 0,
                               offset_ms=d.get("offsetMs", 0) or 0,
                               server_updated_at=d.get("updatedAt", "") or "")

    def put_progress(self, task_id: int, segment_number: int, offset_ms: int):
        return self._put("/progress", {"taskId": task_id, "segment": segment_number, "offsetMs": offset_ms})

    # ---- 书口径进度（book_key）：跨端通用的那一份 ----

    def get_book_progress_raw(self, book_key: str) -> Dict:
        """拉取某本书的阅读进度（跨端口径）。

        身份用 ``book_key``（``s:{storeBookId}`` / ``t:{serverTaskId}`` / ``n:{书名}``），
        与有声覆盖图同一套口径：三端对同一本书算出同一个 key，进度才能互通。

        为什么不用上面那两个端点：
        - ``/progress?taskId=`` 是**私有任务**口径，服务端按任务归属校验；
          书城书的音频常由管理员合成 ⇒ 普通用户调它必然 403，进度传不上去。
        - ``/store/book/{id}/progress`` 只能覆盖**书城书**，本地导入的书用不了；
          且它原先挂在 ``store_books.task_id`` 上，未合成的书（task_id 为空）直接 400。
        """
        d = self._get("/book-progress", {"bookKey": book_key})
        return d if isinstance(d, dict) else {}

    def get_book_progress(self, book_key: str) -> ReadingProgress:
        d = self.get_book_progress_raw(book_key)
        if not d.get("hasProgress"):
            return ReadingProgress()
        return ReadingProgress(segment_number=d.get("segment", 0) or 0,
                               offset_ms=d.get("offsetMs", 0) or 0,
                               server_updated_at=d.get("updatedAt", "") or "")

    def put_book_progress(self, book_key: str, segment_number: int, offset_ms: int):
        """上报某本书的阅读进度（跨端口径）。

        失败不抛异常（best-effort）：离线/未登录时由本地库兜底。
        """
        try:
            return self._put("/book-progress",
                             {"bookKey": book_key, "segment": segment_number,
                              "offsetMs": offset_ms})
        except Exception:  # noqa: BLE001
            return None

    # ========== 书架 /shelf ==========

    def get_shelf(self) -> List[Dict]:
        data = self._get("/shelf")
        return data if isinstance(data, list) else data.get("items", [])

    def add_to_shelf(self, store_book_id: int):
        return self._post(f"/shelf/{store_book_id}")

    def remove_from_shelf(self, store_book_id: int):
        return self._delete(f"/shelf/{store_book_id}")

    # ========== 收藏 /favorites ==========

    def get_favorites_raw(self) -> Dict:
        data = self._get("/favorites")
        return data if isinstance(data, dict) else {"favorites": data if isinstance(data, list) else []}

    def get_favorites(self) -> List[Favorite]:
        data = self.get_favorites_raw()
        items = data.get("favorites", []) if isinstance(data, dict) else (data or [])
        out: List[Favorite] = []
        for f in items:
            sid = f.get("id")
            out.append(Favorite(id=sid or 0, book_id=0,
                                text=f.get("content", ""), note="",
                                created_at=f.get("createdAt", "") or "",
                                book_name=f.get("bookName", "") or "",
                                segment_number=f.get("segmentNumber", 0) or 0,
                                server_id=sid,
                                task_id=f.get("taskId")))
        return out

    def add_favorite(self, book_name: str, segment_number: int, content: str,
                     task_id: int = None):
        body = {"bookName": book_name, "segmentNumber": segment_number, "content": content}
        if task_id is not None:
            body["taskId"] = task_id
        return self._post("/favorites", body)

    def delete_favorite(self, fav_id: int):
        return self._delete(f"/favorites/{fav_id}")

    def sync_favorites(self, items: List[Dict]) -> Dict:
        """批量合并上报（首次迁移 / 离线补传）。

        items 每项形如 {bookName, segmentNumber, content, taskId?}；服务端按
        (bookName, segmentNumber) 合并去重后返回 {success, favorites:[...], total, imported}。
        """
        return self._post("/favorites/sync", {"favorites": items})

    # ========== 生词本 /word-book（跨端同步，镜像 /favorites） ==========

    def get_word_book_raw(self) -> Dict:
        """拉全量生词本：{success, words:[{id,sw,word,phonetic,translations,createdAt}], total}。"""
        data = self._get("/word-book")
        return data if isinstance(data, dict) else {"words": data if isinstance(data, list) else []}

    def add_word_book(self, sw: str, word: str, phonetic: str = "",
                      translations: str = "", source: Dict = None) -> Dict:
        """单条幂等 upsert（用户点星标即时上云）。已存在同 sw 返回既有、不覆盖快照。

        ``source`` 是收藏出处（wire camelCase 的 6 键：bookName / bookKey / storeBookId /
        segmentNumber / lineNumber / sentenceText），由阅读页在收藏时按「书籍 + 当前句子」
        组装。服务端 ``WordBookItemRequest.hasSource()`` 以 bookName / bookKey 判定是否刷新
        出处：传空 dict / None 表示「不带出处」，服务端会保留该词已有出处，绝不抹掉。
        返回 ``{success, word:{id,...}, message}``，``word.id`` 用于回填本地 server_id。
        """
        body = {"sw": sw, "word": word}
        if phonetic:
            body["phonetic"] = phonetic
        if translations:
            body["translations"] = translations
        if source:
            for k, v in source.items():
                if v is not None:
                    body[k] = v
        return self._post("/word-book", body)

    def sync_word_book(self, items: List[Dict]) -> Dict:
        """批量合并上报（首次迁移 / 离线补传）。

        items 每项形如 {sw, word, phonetic?, translations?} + 可选出处 6 键
        bookName / bookKey / storeBookId / segmentNumber / lineNumber / sentenceText
        （与 POST /api/word-book 同构，服务端按 (userId, sw) 合并去重）。
        ⚠️ 出处键只在有值时带：本地行的出处全为 None 时不带出处键，服务端按旧客户端
        处理并保留云端已有出处。返回 {success, words:[...], total, imported}。
        """
        return self._post("/word-book/sync", {"words": items})

    def delete_word_book(self, word_id: int):
        return self._delete(f"/word-book/{word_id}")

    def delete_word_book_by_sw(self, sw: str):
        """按归一化键删除（客户端没有 server_id 的老数据降级路径）。"""
        return self._delete(f"/word-book/by-sw/{sw}")

    # ========== 有声覆盖图 /audio-coverage（Phase3，仅元数据、不传字节） ==========

    def get_audio_coverage_raw(self) -> Dict:
        """拉全量覆盖图：{success, items:[{bookKey,source,storeBookId?,serverTaskId?,
        totalSegments,doneRanges,doneCount,hasAudio,updatedAt}], total}。"""
        data = self._get("/audio-coverage")
        return data if isinstance(data, dict) else {"items": data if isinstance(data, list) else []}

    def sync_audio_coverage(self, items: List[Dict]) -> Dict:
        """批量并集合并上报（本机覆盖图上云 → 返回合并后全量）。

        items 每项形如 {bookKey, source, doneRanges, totalSegments?, storeBookId?, serverTaskId?}；
        服务端按 (userId, bookKey, source) 对 doneRanges 做**并集**后返回 {success, items:[...], total}。
        """
        return self._post("/audio-coverage/sync", {"items": items})

    # ---- P1 #3：条件 GET / ETag 感知（增量同步，免流量 304） ----

    def get_audio_coverage_conditional(
            self, if_none_match: str = "") -> tuple:
        """条件 GET ``/audio-coverage``，返回 ``(status_code, etag, data)``。

        - ``200``：``data`` = 解析后的 dict（与 :meth:`get_audio_coverage_raw` 同构），
          ``etag`` = 响应 ``ETag`` 头（可能为空串，老服务端不发时）。
        - ``304``：``data`` = ``None``（**调用方应保留本地缓存，不要重建**），
          ``etag`` = 响应 ``ETag``（与请求 ``If-None-Match`` 相同）。
        - 其它状态码：走 :meth:`_handle_response` 的统一错误分支（401/402/4xx/5xx 抛
          :class:`ApiException`），成功路径不会走到这里。

        ``if_none_match`` 为空串时不发 ``If-None-Match`` 头，等价于无条件 GET——
        首次同步 / 客户端未缓存 ETag 时走这条。
        """
        headers = self._headers()
        if if_none_match:
            headers["If-None-Match"] = if_none_match
        r = self._session.get(self._url("/audio-coverage"), headers=headers,
                              timeout=self.TIMEOUT_LIGHT)
        etag = r.headers.get("ETag", "") or ""
        if r.status_code == 304:
            return 304, etag, None
        # 非 304：复用统一错误处理（401/402/4xx/5xx 抛异常），成功则解析 JSON
        data = self._handle_response(r)
        if not isinstance(data, dict):
            data = {"items": data if isinstance(data, list) else []}
        return r.status_code, etag, data

    def sync_audio_coverage_with_etag(self, items: List[Dict]) -> tuple:
        """POST ``/audio-coverage/sync``，返回 ``(etag, data)``。

        与 :meth:`sync_audio_coverage` 同构，额外把响应 ``ETag`` 头透出来，
        供调用方落盘、下次条件 GET 时用作 ``If-None-Match``。
        老服务端不发 ETag 时返回空串，调用方按"无缓存"处理即可。
        """
        r = self._session.post(self._url("/audio-coverage/sync"),
                               json={"items": items},
                               headers=self._headers(),
                               timeout=self.TIMEOUT_LIGHT)
        etag = r.headers.get("ETag", "") or ""
        data = self._handle_response(r)
        if not isinstance(data, dict):
            data = {"items": data if isinstance(data, list) else []}
        return etag, data

    # ========== 社区 /posts ==========

    def get_posts(self, page: int = 0, size: int = 20) -> List[Post]:
        # ⚠ 服务端分页参数名是 **limit**，不是 size（PostController.listPosts）。
        #   写错会被忽略 ⇒ 服务端回落到 MAX_LIMIT=200 条，一页拉回 200 条，
        #   客户端再按 size=20 判定「还有更多」就会一直翻下去。
        data = self._get("/posts", {"page": page, "limit": size})
        items = data if isinstance(data, list) else data.get("posts", data.get("content", []))
        return [Post(id=p.get("id"), title=p.get("title", ""), content=p.get("content", ""),
                     author=p.get("author", p.get("username", "")), created_at=p.get("createdAt", ""),
                     comment_count=p.get("commentCount", 0), favorite_count=p.get("favoriteCount", 0),
                     image_urls=_parse_image_urls(p.get("imageUrls"))) for p in items]

    def get_post_detail(self, post_id: int) -> Dict:
        return self._get(f"/posts/{post_id}")

    def upload_post_image(self, image_path: str) -> str:
        """上传帖子图片，返回服务端的**相对地址**（如 ``/api/posts/image/xxx.png``）。

        ⚠ 必须是独立的一次 multipart 请求打到 ``/api/posts/upload-image``：
        ``POST /api/posts`` 只接受 JSON（``@RequestBody CreatePostRequest``），
        直接把图片 multipart 打过去会被 Spring 以 415 拒绝，
        再被全局兜底 handler 吞成 500「服务器开小差了」——
        客户端与服务端都会看到这条毫无指向性的提示，排查成本极高。
        """
        files = {"file": (os.path.basename(image_path), open(image_path, "rb"))}
        try:
            data = self._post_multipart("/posts/upload-image", files, {})
        finally:
            files["file"][1].close()
        url = data.get("url", "") if isinstance(data, dict) else ""
        if not url:
            raise ApiException(500, "图片上传失败：服务端未返回地址")
        return url

    def create_post(self, title: str, content: str, image_path: str = "") -> Dict:
        """发布帖子。有图时先上传图片拿到相对地址，再随正文一起 JSON 提交。

        两段式（先 upload-image 再 POST /api/posts）与 Android 端完全一致，
        服务端只认这一种形态。
        """
        body: Dict[str, Any] = {"title": title, "content": content}
        if image_path:
            body["imageUrls"] = [self.upload_post_image(image_path)]
        return self._post("/posts", body)

    def create_comment(self, post_id: int, content: str):
        return self._post(f"/posts/{post_id}/comments", {"content": content})

    def toggle_post_favorite(self, post_id: int):
        return self._post(f"/posts/{post_id}/favorite")

    def report_post(self, post_id: int, reason: str):
        return self._post("/posts/reports", {"postId": post_id, "reason": reason})

    def download_image_bytes(self, rel_url: str) -> bytes:
        """下载帖子图片的原始字节。

        服务端返回的是**相对路径**（如 ``/api/posts/image/xxx.png``），
        必须拼上 base_url 才能取到；且 ``/api/posts/image/*`` 对游客也放行，
        但这里统一带上 token，避免将来收紧策略后图片集体失效。
        """
        url = self.config.base_url.rstrip("/") + "/" + rel_url.lstrip("/")
        r = self._session.get(url, headers=self._headers(), timeout=self.TIMEOUT_LIGHT)
        if r.status_code >= 400:
            raise ApiException(r.status_code, f"图片下载失败（HTTP {r.status_code}）")
        return r.content

    # ========== 积分 /credits /payment ==========

    def get_credits(self) -> CreditInfo:
        d = self._get("/credits/me")
        return CreditInfo(balance=d.get("balance", d.get("credits", 0)),
                          free_trials_remaining=d.get("freeTrialsRemaining", 0))

    def get_credit_packages(self) -> List[CreditPackage]:
        data = self._get("/credits/packages")
        items = data if isinstance(data, list) else data.get("packages", [])
        return [CreditPackage(id=p.get("id"), name=p.get("name", ""), credits=p.get("credits", 0),
                              amount=p.get("amount", 0), description=p.get("description", "")) for p in items]

    def get_credit_transactions(self) -> List[CreditTransaction]:
        data = self._get("/credits/transactions")
        items = data if isinstance(data, list) else data.get("transactions", [])
        return [CreditTransaction(id=t.get("id"), type=t.get("type", ""), credits=t.get("credits", 0),
                                  balance_after=t.get("balanceAfter", 0), description=t.get("description", ""),
                                  created_at=t.get("createdAt", "")) for t in items]

    def get_payment_channels(self) -> List[Dict]:
        data = self._get("/payment/channels")
        return data if isinstance(data, list) else data.get("channels", [])

    def create_order(self, package_id: int, channel: str) -> Dict:
        return self._post("/payment/prepay", {"packageId": package_id, "channel": channel})

    def get_order_status(self, order_no: str) -> Dict:
        return self._get(f"/payment/orders/{order_no}")

    # ========== 版本 /app/version ==========

    def check_update(self, current_version_code: int = 41) -> AppUpdateInfo:
        d = self._get("/app/version/latest", {"platform": "android"})
        latest = d.get("latest", d)
        vc = latest.get("versionCode", 0)
        return AppUpdateInfo(
            version_code=vc,
            version_name=latest.get("versionName", ""),
            changelog=latest.get("changelog", ""),
            download_url=d.get("downloadUrl", ""),
            file_size=latest.get("fileSize", 0),
            md5=latest.get("md5", ""),
            force_update=latest.get("forceUpdate", False),
            has_update=d.get("hasUpdate", vc > current_version_code),
        )

    # ========== 词典 /app/dict ==========

    def get_dict_info(self) -> Dict:
        return self._get("/app/dict/info")

    def download_dict(self, save_path: str):
        r = self._session.get(self._url("/app/dict/download"),
                              headers=self._headers(), timeout=self.TIMEOUT_HEAVY, stream=True)
        with open(save_path, "wb") as f:
            for chunk in r.iter_content(65536):
                f.write(chunk)

    # ========== 配置 /config ==========

    def get_config(self) -> Dict:
        return self._get("/config")

    # ========== 跨端单值偏好 /user-settings（LWW，非并集） ==========

    def get_user_settings(self) -> Dict:
        """只读拉全量：{success, settings:{k:v}, items:[{key,value,updatedAt}], version}。"""
        data = self._get("/user-settings")
        return data if isinstance(data, dict) else {"items": [], "version": 0}

    def sync_user_settings(self, items: List[Dict]) -> Dict:
        """一次往返完成「拉 + 推」。

        ``items`` 每项 ``{key, value, updatedAt}``；服务端逐项按版本大者胜（LWW）
        合并后返回**权威全量**（含服务端裁决出的新版本号）。

        ⚠ 与 ``sync_audio_coverage`` 的语义**不同**：覆盖图是集合型 doneRanges 并集，
        这里是单值覆盖。客户端必须回传服务端上次给的版本号，否则每轮同步都会
        把版本号无限抬高；只有本地真的改过时才用更大的版本去覆盖。
        """
        data = self._put("/user-settings", {"items": items})
        return data if isinstance(data, dict) else {"items": [], "version": 0}

    # ========== 每书元数据 /book-meta（LWW，只同步排序与最近阅读） ==========

    def get_book_meta(self) -> Dict:
        """只读拉全量：{success, items:[{bookKey,sortOrder,lastReadAt,updatedAt}], total}。"""
        data = self._get("/book-meta")
        return data if isinstance(data, dict) else {"items": [], "total": 0}

    def sync_book_meta(self, items: List[Dict]) -> Dict:
        """一次往返完成「拉 + 推」，返回服务端合并后的权威全量。

        ``lastReadAt`` 是**毫秒 epoch**（Android 本地列就是这个单位）；
        PC 本地列是 TEXT ISO 串，由 ``core/sync.py`` 负责双向转换，本方法不做单位猜测。
        ⛔ 不含 category：两端该列语义不同（Android 手工分类 / PC 文件类型）。
        """
        data = self._post("/book-meta/sync", {"items": items})
        return data if isinstance(data, dict) else {"items": [], "total": 0}

    # ========== 内容提取 ==========

    def extract_content(self, file_path: str) -> Dict:
        files = {"bookFile": (os.path.basename(file_path), open(file_path, "rb"))}
        try:
            return self._post_multipart("/tts/extract-content", files, timeout=self.TIMEOUT_HEAVY)
        finally:
            files["bookFile"][1].close()
