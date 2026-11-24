"""云端音频段的按需下载器（书城公共音频 / 私有云端任务共用一套骨架）。

## 为什么单开一组接口
书城书通常由管理员合成，``store_books.task_id`` 指向的是**管理员的任务**，音频是公共资源，
走 ``/api/store/book/{id}/segments`` + ``/api/store/book/{id}/segments/{sid}/audio``，
只要求登录、不校验任务归属；而 ``/api/tts/**`` 是用户私有任务接口，普通用户拿书城书去调
必然 403。所以书城音频下载与私有任务音频下载各走各的接口，
但「按需下段 / 断点续传 / 并发预取 / 原子落盘」这些机制完全一样 ⇒ 收在一个基类里。

## 设计要点
  - **逐段下载**：按段号升序（= 电子书文本顺序）逐段拉 mp3，下完一段立刻落库，阅读页
    「边下载边播放」直读 ``audio_segments``，不必等整本下完；
  - **断点续传**：已存在且非空的本地文件直接跳过，中断后重进只补缺的段；
  - **原子落盘**：先写 ``<name>.part`` 再 ``os.replace``。⚠️ 直接写目标文件是错的：
    中断会留下「非空但不完整」的 mp3，``is_local()`` 判它有效 ⇒ 这段永远重下不了、
    播放器载入失败 ⇒ 表现为「播到某一段就没声 / 跳段」；
  - **并发预取**：单段的 TTFB 实测 1.5~3 s（服务端每请求开销），串行下载时
    「下一段」比「播完一段」（2~3 s）还慢，播到队尾必然卡住。4 路并发把吞吐抬到约 4 倍；
  - **有序回调**：并发完成后仍按段号升序回调 ``on_segment``，阅读页才能把段连续追加进
    播放队列（乱序追加会让播放顺序错乱）；
  - **落库锚点**：书城书没有本地 task，用 ``task_id = -store_book_id``（负值）作为
    ``audio_segments.task_id`` 命名空间，与真实任务 id（正整数）天然隔离；
    私有任务用本地 ``tasks.id``；
  - **幂等**：``insert_segment`` 的 ``INSERT OR REPLACE`` + 唯一索引 ``(task_id, segment_number)``
    保证重复落库不产生重复行。
"""
from __future__ import annotations

import os
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Callable, Dict, List, Optional, Set

from app.core.api_client import ApiClient
from app.core.config import AppConfig
from app.db.database import LocalDatabase
from app.models.models import Segment

_STATUS_COMPLETED = "completed"

# 预取并发数。实测（书城书 50，段长 2~3 s、约 40 KB/段）：
#   串行 4 段 ≈ 27 s，4 路并发 4 段 ≈ 14 s，8 路 ≈ 1~3 s（冷段）。
# 取 4：既能把「下得比播得快」拉回来，又不会把共享的线上小机器打爆。
PREFETCH_WORKERS = 4


class CloudAudioDownloaderBase:
    """云端音频段按需下载器基类。

    子类实现 4 个钩子即可复用全部机制：

        _fetch_segments(key) -> List[Segment]   从服务端取段列表
        _filename(key, num) -> str              本地文件名（决定断点续传的判定）
        _fetch_audio(key, seg, path)            实际下载（把字节写到 path）
        _store_row(key, seg, path)              Segment 落库

    ``key`` 对书城书是 store_book_id，对私有任务是 server_task_id。
    """

    def __init__(self, config: AppConfig, api: ApiClient, db: LocalDatabase):
        self.config = config
        self.api = api
        self.db = db
        self._lock = threading.Lock()
        # 段列表缓存：key -> [Segment]（仅 completed，按段号升序）
        self._segments_cache: Dict[int, List[Segment]] = {}
        # 正在下载的书：key -> 待下载段号集合（播放队尾探针据此判断「还有没有段」）
        self._pending: Dict[int, Set[int]] = {}
        # (key, 段号) -> Event：同一段被多处同时请求时，后到者等前一个下完即可，
        # 不重复下载（否则既浪费带宽，又可能在 os.replace 上互相覆盖）
        self._inflight: Dict[tuple, threading.Event] = {}
        # 正在跑预取窗口的 key：避免播放每推进一段就再起一个线程池
        self._prefetching: Set[int] = set()

    # ---------- 子类钩子 ----------

    def _fetch_segments(self, key: int) -> List[Segment]:
        raise NotImplementedError

    def _filename(self, key: int, segment_number: int) -> str:
        raise NotImplementedError

    def _fetch_audio(self, key: int, seg: Segment, path: str) -> None:
        raise NotImplementedError

    def _store_row(self, key: int, seg: Segment, path: str) -> None:
        raise NotImplementedError

    # ---------- 段列表 ----------

    def segments(self, key: int) -> List[Segment]:
        """本书的已完成（有声）段列表，带缓存；失败/无音频返回空列表。"""
        k = int(key)
        with self._lock:
            cached = self._segments_cache.get(k)
        if cached is not None:
            return cached
        try:
            raw = self._fetch_segments(k)
        except Exception:  # noqa: BLE001 —— 网络/鉴权失败一律降级为空
            return []
        completed = [s for s in raw if s.status == _STATUS_COMPLETED and s.id]
        completed.sort(key=lambda s: s.segment_number)
        with self._lock:
            self._segments_cache[k] = completed
        return completed

    def invalidate(self, key: Optional[int] = None) -> None:
        """丢弃段列表缓存（合成完成 / 书架刷新后调用）。"""
        with self._lock:
            if key is None:
                self._segments_cache.clear()
            else:
                self._segments_cache.pop(int(key), None)

    def has_audio(self, key: int) -> bool:
        """段是否有已合成音频（有任意 completed 段即 True）。"""
        return bool(self.segments(key))

    # ---------- 本地文件 ----------

    def local_path(self, key: int, segment_number: int) -> Path:
        return self.config.audio_dir / self._filename(int(key), int(segment_number))

    @staticmethod
    def _part_path(path: Path) -> Path:
        return path.with_name(path.name + ".part")

    def is_local(self, key: int, segment_number: int) -> bool:
        p = self.local_path(key, segment_number)
        try:
            return p.is_file() and p.stat().st_size > 0
        except OSError:
            return False

    # ---------- 单段下载 ----------

    def download_segment(self, key: int, seg: Segment) -> Optional[str]:
        """下载并落库单段（已存在则直接返回本地路径）。失败返回 None。

        并发安全：同一段被两处同时请求时，后到者等前一个下完再返回结果，
        不会重复下载，也不会看到半截文件。
        """
        k = int(key)
        num = int(seg.segment_number)
        save_path = self.local_path(k, num)
        if self.is_local(k, num):
            self._store_row(k, seg, str(save_path))   # 幂等补落库
            return str(save_path)

        dedup = (k, num)
        with self._lock:
            waiter = self._inflight.get(dedup)
            if waiter is None:
                waiter = threading.Event()
                self._inflight[dedup] = waiter
                owner = True
            else:
                owner = False
        if not owner:
            # 已有别的线程在下一段：等它下完即可（最多等 90 s，避免死等）
            waiter.wait(timeout=90.0)
            return str(save_path) if self.is_local(k, num) else None

        part = self._part_path(save_path)
        try:
            try:
                if part.exists():
                    part.unlink()
            except OSError:
                pass
            self._fetch_audio(k, seg, str(part))
            if not part.is_file() or part.stat().st_size <= 0:
                raise OSError("下载内容为空")
            os.replace(part, save_path)          # 原子替换：不会留下半截文件
            self._store_row(k, seg, str(save_path))
            return str(save_path)
        except Exception:  # noqa: BLE001
            try:
                if part.exists():
                    part.unlink()
            except OSError:
                pass
            return None
        finally:
            with self._lock:
                self._inflight.pop(dedup, None)
            waiter.set()

    # ---------- 整本后台下载 ----------

    def _download_in_pool(self, key: int, seg: Segment) -> Optional[str]:
        """线程池任务：下载单段，并在**本线程**结束时关掉自己的 SQLite 连接。

        ⚠️ 必须有这一步：``LocalDatabase`` 是线程局部连接（``_conn()`` 每线程一条），
        线程池的线程不会被 :meth:`LocalDatabase.close` 的「当前线程」语义回收。
        漏掉的连接在 Windows 上会把 ``audiobook_pc.db`` 一直锁住
        （测试删临时目录报 ``WinError 32``，线上是 WAL 文件越攒越多）。
        """
        try:
            return self.download_segment(key, seg)
        finally:
            try:
                self.db.close()
            except Exception:  # noqa: BLE001
                pass

    def download_all(self, key: int,
                     on_segment: Optional[Callable[[int], None]] = None,
                     on_progress: Optional[Callable[[int, int, int], None]] = None,
                     on_done: Optional[Callable[[bool, str], None]] = None) -> threading.Thread:
        """后台逐段下载整本（段号升序、跳过已下载）。返回工作线程。

        回调在后台线程触发，UI 侧需自行切回主线程（例如经 Qt 信号）。

        ``on_segment`` 只对**本次真正新下载**的段回调：断点续传跳过的段本来就在本机，
        再报一次会让「已下载 N/M 段」的 N 虚高（也会让调用方误以为又落了一段）。
        """
        k = int(key)

        def _work():
            try:
                segs = self.segments(k)
                if not segs:
                    if on_done:
                        on_done(False, "这本书暂无已合成的音频片段")
                    return
                total = len(segs)
                # 开跑前先记下「本机已有哪些段」——并发下 is_local() 会被别的线程改写
                missing = {s.segment_number for s in segs
                           if not self.is_local(k, s.segment_number)}
                saved = 0
                failed = 0
                with self._lock:
                    self._pending[k] = {s.segment_number for s in segs}
                try:
                    # 并发下载、按段号升序回调（顺序对「边下边播」的队列追加是硬要求）
                    with ThreadPoolExecutor(max_workers=PREFETCH_WORKERS) as pool:
                        futures = [pool.submit(self._download_in_pool, k, s) for s in segs]
                        for seg, fut in zip(segs, futures):
                            try:
                                path = fut.result()
                            except Exception:  # noqa: BLE001
                                path = None
                            if path:
                                if seg.segment_number in missing:
                                    saved += 1
                                    if on_segment:
                                        on_segment(seg.segment_number)
                            else:
                                failed += 1
                            if on_progress:
                                on_progress(saved, total, failed)
                finally:
                    with self._lock:
                        self._pending.pop(k, None)
                ok = failed == 0
                msg = f"已下载 {saved}/{total} 段" + ("" if ok else f"，{failed} 段失败")
                if on_done:
                    on_done(ok, msg)
            finally:
                # 后台线程用完即关本线程的 SQLite 连接，避免 per-thread 句柄泄漏
                try:
                    self.db.close()
                except Exception:  # noqa: BLE001
                    pass

        t = threading.Thread(target=_work, daemon=True, name=f"cloud-audio-all-{k}")
        t.start()
        return t

    # ---------- 按需播放 ----------

    def ensure_segment(self, key: int, segment_number: int) -> Optional[str]:
        """确保某段音频已在本地（没有就同步下载）。返回本地路径或 None。"""
        k = int(key)
        seg = next((s for s in self.segments(k) if s.segment_number == segment_number), None)
        if seg is None:
            return None
        return self.download_segment(k, seg)

    def ensure_segment_async(self, key: int, segment_number: int,
                             on_segment: Optional[Callable[[int], None]] = None) -> bool:
        """确保某段在后台下载（已在下载/已在本地则不重复起线程）。

        :return: True 表示调用方需要等回调；False 表示该段已就绪或无需下载
        """
        k = int(key)
        num = int(segment_number)
        if self.is_local(k, num):
            return False
        seg = next((s for s in self.segments(k) if s.segment_number == num), None)
        if seg is None:
            return False
        with self._lock:
            if (k, num) in self._inflight:
                return True          # 已有线程在下一段，等它的回调即可

        def _work():
            try:
                path = self.download_segment(k, seg)
                if path and on_segment:
                    on_segment(num)
            finally:
                try:
                    self.db.close()
                except Exception:  # noqa: BLE001
                    pass

        threading.Thread(target=_work, daemon=True,
                         name=f"cloud-audio-ensure-{k}-{num}").start()
        return True

    def prefetch(self, key: int, from_number: int, count: int,
                 on_segment: Optional[Callable[[int], None]] = None) -> None:
        """异步预取从 from_number 起的 count 段（fire-and-forget，滑动窗口用）。

        每段落库后按**段号升序**回调 ``on_segment(segment_number)``（后台线程），
        供播放侧 append 续播 —— 顺序错了会让播放队列乱序。
        """
        k = int(key)
        if count <= 0 or from_number <= 0:
            return
        with self._lock:
            if k in self._prefetching:
                return          # 已有一个窗口在跑，它会覆盖这一段区间
            self._prefetching.add(k)
        segs = self.segments(k)
        targets = [s for s in segs if s.segment_number >= from_number][:count]
        if not targets:
            with self._lock:
                self._prefetching.discard(k)
            return

        def _work():
            try:
                with ThreadPoolExecutor(max_workers=PREFETCH_WORKERS) as pool:
                    futures = [pool.submit(self._download_in_pool, k, s) for s in targets]
                    for seg, fut in zip(targets, futures):
                        try:
                            path = fut.result()
                        except Exception:  # noqa: BLE001
                            path = None
                        if path and on_segment:
                            on_segment(seg.segment_number)
            finally:
                with self._lock:
                    self._prefetching.discard(k)
                try:
                    self.db.close()
                except Exception:  # noqa: BLE001
                    pass

        threading.Thread(target=_work, daemon=True, name=f"cloud-audio-pre-{k}").start()

    def has_more_after(self, key: int, last_segment_number: int) -> bool:
        """在 last_segment_number 之后是否还有「未下载到本机」的段（播放队尾探针用它）。"""
        k = int(key)
        segs = self.segments(k)
        return any(s.segment_number > last_segment_number and not self.is_local(k, s.segment_number)
                   for s in segs)

    def has_pending(self, key: int) -> bool:
        """是否还有段在下载/待下载（整本后台下载的进度探针用它）。"""
        k = int(key)
        with self._lock:
            pending = self._pending.get(k)
        if not pending:
            return False
        return any(not self.is_local(k, n) for n in pending)


class StoreAudioDownloader(CloudAudioDownloaderBase):
    """书城音频逐段下载器（公共音频，走 /api/store 接口）。"""

    # ---------- 段列表 ----------

    def _task_id(self, store_book_id: int) -> int:
        """书城书音频在 audio_segments 里的 task_id 锚点（负值，隔离真实任务 id）。"""
        return -int(store_book_id)

    def _fetch_segments(self, store_book_id: int) -> List[Segment]:
        return self.api.get_store_book_segments(int(store_book_id))

    def _filename(self, store_book_id: int, segment_number: int) -> str:
        return f"store_{int(store_book_id)}_{int(segment_number):05d}.mp3"

    def _fetch_audio(self, store_book_id: int, seg: Segment, path: str) -> None:
        self.api.get_store_segment_audio(int(store_book_id), seg.id, path)

    def _store_row(self, store_book_id: int, seg: Segment, path: str) -> None:
        self.db.insert_segment(Segment(
            task_id=self._task_id(store_book_id), segment_number=seg.segment_number,
            text_content=seg.text_content, audio_file_path=path,
            duration_seconds=seg.duration_seconds, status="completed",
            voice=seg.voice, source="server"))
