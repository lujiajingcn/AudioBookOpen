"""跨端数据同步层（Phase 1）。

登录成功后，以**服务端为唯一真源**，把云端的
「在线合成任务 / 书城书架 / 收藏 / 阅读进度」拉取合并进本地，
并把本机新增（收藏、进度）补传上云；冲突一律按**时间戳新者胜**。

设计镜像 Android 已验证的 `TaskSyncManager` / `FavoriteRepository`：

  - 任务、书架：**add-no-delete**，按 ``server_task_id`` / ``store_book_id`` 匹配，
    只回填服务端权威列（状态、进度、元信息），绝不覆盖本地改名 / 本地文件路径；
    拉取失败绝不清空本地。
  - 收藏：以服务端全量列表**重建**本地，但带**安全护栏**——
    本机所有「待上传」项必须都已出现在服务端返回列表里才敢重建，
    否则宁可保留本地、不做重建，避免把没同步上去的收藏误删。
  - 进度：逐个 ``book_key`` 双向对账。服务端更新（段号更大 / 时间戳更新）→ 覆盖本地；
    本地更新 → 补传服务端。缺服务端标识的本地书（无对应云端任务）跳过。

本模块不依赖 Qt，纯逻辑 + 复用 ``LocalDatabase`` / ``ApiClient``，便于无头单测。
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Callable, Dict, List, Optional, Tuple

from app.core.api_client import ApiClient, ApiException
from app.core.config import AppConfig
from app.core import coverage
from app.db.database import LocalDatabase, _norm_ts, _is_server_progress_newer, _sw_key


# ---------------------------------------------------------------------------
# 分级错误提示：把 HTTP 状态码 / 网络异常翻译成用户能看懂、能据此行动的一句话。
#
# 为什么要分级：覆盖图 / 收藏 / 生词本这些接口都是 Phase2/Phase3 才加的，
# 老服务端会返 404。如果只把 "Not Found" 甩给用户，用户会以为是自己操作错了；
# 明确告诉用户"服务端版本过旧，需要升级"才能引导正确的行动。
# 同理 401（登录过期）、5xx（服务端故障）、网络不可达（本地问题）也要分开说。
# ---------------------------------------------------------------------------

# 错误类别常量（供 UI 决定要不要重试 / 要不要弹登录框）
ERR_KIND_OK = "ok"
ERR_KIND_NOT_AUTHED = "not_authed"          # 未登录（本地状态，没发请求）
ERR_KIND_AUTH_EXPIRED = "auth_expired"      # 401：登录过期
ERR_KIND_FORBIDDEN = "forbidden"            # 403：无权限
ERR_KIND_SERVER_TOO_OLD = "server_too_old"  # 404：服务端未部署该接口
ERR_KIND_SERVER_ERROR = "server_error"      # 5xx：服务端故障
ERR_KIND_NETWORK = "network"                # 连接失败 / 超时
ERR_KIND_BAD_RESPONSE = "bad_response"      # 200 但结构异常
ERR_KIND_UNKNOWN = "unknown"                # 其它

# P1 #3：覆盖图 ETag 在 sync_state kv 表里的 key。
# 服务端 ETag 已拼入 userId（见 AudioCoverageService.computeEtag），跨账号永不撞，
# 所以本机只需存一个值；切账号后服务端算出的 ETag 自然不同，条件 GET 走 200 全量。
_COVERAGE_ETAG_KEY = "coverage_etag"

# 生词本出处：本地 WordEntry 字段 → wire camelCase（与服务端 WordBookItemRequest 对齐）。
# 上传时**只带有值的键**：本地行出处全 None ⇒ payload 不带出处键 ⇒ 服务端
# WordBookItemRequest.hasSource() 为 false ⇒ 保留云端已有出处，不会被一次同步抹掉。
_WORD_SOURCE_WIRE = (
    ("book_name", "bookName"), ("book_key", "bookKey"),
    ("store_book_id", "storeBookId"), ("segment_number", "segmentNumber"),
    ("line_number", "lineNumber"), ("sentence_text", "sentenceText"),
)


def _word_source_payload(w) -> dict:
    """把一条本地生词行的出处 6 键转成 wire camelCase（只带非 None / 非空串值）。"""
    out = {}
    for attr, key in _WORD_SOURCE_WIRE:
        v = getattr(w, attr, None)
        if v is not None and (not isinstance(v, str) or v.strip()):
            out[key] = v
    return out


# ---- LWW 两类新同步项在 sync_state 里的 key ----
# 现有 9 类是集合型（add-no-delete 并集）；user_settings / book_meta 是**单值覆盖型**，
# 必须按「版本大者胜」合并，靠下面这组影子快照 + 版本水位实现，不能复用任何 _merge_*。
_SS_SHADOW = "user_settings_local_shadow"       # 上次同步结束时本地的样子（判脏用）
_SS_VERSIONS = "user_settings_versions"         # 每项服务端版本号（干净项原样回传）
_BM_SHADOW = "book_meta_local_shadow"           # {bookKey: [sort_order, last_read_at_iso]}
_BM_SERVER = "book_meta_server_values"          # {bookKey: {"sortOrder", "lastReadAt"}}（毫秒）
_BM_VERSIONS = "book_meta_versions"             # {bookKey: version}

# 只同步这 4 项。⛔ category 两端语义不同；⛔ 夜间模式三态 vs bool；⛔ 字号相对倍数 vs 绝对 pt；
# ⛔ MOSS 本机路径、token、仅 WiFi 下载等纯设备配置一律不上云。
_SETTING_KEYS = ("playback_speed", "default_voice_id")
_SETTING_DEFAULTS = {"playback_speed": "1.0", "default_voice_id": ""}


def _json_state(db: LocalDatabase, key: str, default):
    """读 sync_state 里的 JSON；缺失/损坏一律回落到 default，绝不让同步整轮崩掉。"""
    try:
        raw = db.get_sync_state(key)
        if not raw:
            return default
        val = json.loads(raw)
        return val if isinstance(val, type(default)) else default
    except Exception:  # noqa: BLE001
        return default


def _write_state(db: LocalDatabase, key: str, value) -> None:
    db.set_sync_state(key, json.dumps(value, ensure_ascii=False, separators=(",", ":")))


def _iso_to_millis(text: str) -> int:
    """PC 本地 books.last_read_at 是 TEXT ISO 串 → 线上统一口径毫秒 epoch。"""
    s = (text or "").strip()
    if not s:
        return 0
    try:
        return int(datetime.fromisoformat(s).timestamp() * 1000)
    except Exception:  # noqa: BLE001
        return 0


def _millis_to_iso(ms) -> str:
    """毫秒 epoch → PC 本地 TEXT ISO 串（保持与 update_book_last_read 完全相同的格式）。"""
    try:
        v = int(ms or 0)
    except (TypeError, ValueError):
        return ""
    if v <= 0:
        return ""
    try:
        return datetime.fromtimestamp(v / 1000).strftime("%Y-%m-%dT%H:%M:%S")
    except (ValueError, OverflowError, OSError):
        return ""


def _canon_speed(value) -> str:
    """倍速字符串规范化 —— 必须与服务端 UserSettingsService.sanitize 输出逐字一致。

    服务端是 ``String.valueOf(Math.round(speed*100)/100.0)``：1.0→"1.0"、1.5→"1.5"。
    Python 用 ``str(float(round(x,2)))`` 得到同样结果。⛔ 别写成 f"{x:g}"，
    那会把 1.0 变成 "1"，与服务端 "1.0" 不等 ⇒ 每轮同步都误判为「本地改过」。
    """
    try:
        f = max(0.5, min(3.0, float(value)))
    except (TypeError, ValueError):
        f = 1.0
    return str(float(round(f, 2)))


def _as_long(value, default: int = 0) -> int:
    """JSON 数字在 Java 侧可能是 Integer/Long/Double，客户端统一按 Number 取。"""
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _now_version(global_version: int) -> int:
    """本地产生新修改时的版本号：严格大于任何已知服务端版本（含同毫秒与时钟回拨）。"""
    return max(int(time.time() * 1000), int(global_version) + 1)


def classify_api_error(exc: BaseException, feature: str) -> Tuple[str, str]:
    """把异常翻译成 ``(kind, 用户可读消息)``。

    ``feature`` 是功能名（例如 "覆盖图" / "收藏" / "生词本"），用于拼接消息。
    """
    if isinstance(exc, ApiException):
        code = getattr(exc, "status_code", 0) or 0
        raw_msg = getattr(exc, "message", "") or ""
        if code == 401:
            return ERR_KIND_AUTH_EXPIRED, f"{feature}同步失败：登录已过期，请重新登录"
        if code == 403:
            return ERR_KIND_FORBIDDEN, f"{feature}同步失败：无权限访问该接口"
        if code == 404:
            return ERR_KIND_SERVER_TOO_OLD, (
                f"{feature}同步失败：服务端版本过旧，未提供该接口"
                f"（需升级服务端到支持 {feature} 的版本）"
            )
        if 500 <= code <= 599:
            return ERR_KIND_SERVER_ERROR, f"{feature}同步失败：服务端错误({code})，请稍后重试"
        if code == 402:
            return ERR_KIND_UNKNOWN, f"{feature}同步失败：{raw_msg or '积分不足'}"
        return ERR_KIND_UNKNOWN, f"{feature}同步失败({code})：{raw_msg or '未知错误'}"

    # requests 的网络异常类名（不直接 import requests，避免硬依赖；按类名匹配）
    cls_name = type(exc).__name__
    if cls_name in (
        "ConnectionError", "ConnectTimeout", "ReadTimeout", "Timeout",
        "SSLError", "NewConnectionError", "MaxRetryError",
    ):
        return ERR_KIND_NETWORK, f"{feature}同步失败：网络不可达或超时，请检查网络后重试"

    return ERR_KIND_UNKNOWN, f"{feature}同步异常：{exc}"


def _fmt_created_at(raw: object) -> str:
    """把服务端 createdAt（字符串 / LocalDateTime 数组 / None）归一成 'yyyy-MM-dd HH:mm:ss'。

    为什么要归一：Android 与本地 ``ORDER BY created_at DESC`` 都按字典序排；
    ISO 串里的 'T'(0x54) 大于空格(0x20)，混着存会让「有 T 的新任务」排序错乱。
    """
    if raw is None:
        return ""
    if isinstance(raw, (list, tuple)):
        # Jackson 可能把 LocalDateTime 序列化成 [year, month, day, hour, min, sec, ...]
        parts = list(raw) + [0] * (6 - len(raw)) if len(raw) < 6 else list(raw)
        try:
            y, mo, d, h, mi, s = (int(x) for x in parts[:6])
            return f"{y:04d}-{mo:02d}-{d:02d} {h:02d}:{mi:02d}:{s:02d}"
        except Exception:  # noqa: BLE001
            return ""
    return _norm_ts(str(raw))


@dataclass
class SyncResult:
    """一次同步的摘要，供 UI 提示 / 日志。"""
    tasks_added: int = 0
    tasks_updated: int = 0
    shelf_synced: int = 0
    shelf_removed: int = 0            # 本地书城书被服务端移出而对账删除的条数
    # 收藏：P1 #4 起改为「按项对账」（reconcile_favorites_from_server），
    # 下面四项是其原始统计。旧字段 favorites_rebuilt / favorites_guarded 保留为兼容别名
    # （rebuilt = inserted + updated，guarded = pending_missing > 0），避免外部消费者断裂。
    favorites_inserted: int = 0
    favorites_updated: int = 0
    favorites_deleted: int = 0
    favorites_pending_missing: int = 0   # 上报后服务端未回执的待上传项数（保留原样，下次重试）
    favorites_rebuilt: int = 0           # 兼容别名：= inserted + updated
    favorites_pending: int = 0
    favorites_guarded: bool = False      # 兼容别名：True ⇔ pending_missing > 0（不再阻塞重建）
    # 生词本：同上
    words_inserted: int = 0
    words_updated: int = 0
    words_deleted: int = 0
    words_pending_missing: int = 0
    words_rebuilt: int = 0
    words_pending: int = 0
    words_guarded: bool = False
    coverage_pushed: int = 0          # 本机覆盖图上报条数（Phase3）
    coverage_rows: int = 0            # 服务端合并后重建进本地缓存的覆盖图行数（Phase3）
    coverage_not_modified: bool = False  # P1 #3：True=服务端回 304，本地缓存原样保留、未重建
    progress_pulled: int = 0
    progress_pushed: int = 0
    # LWW 单值偏好 / 每书元数据（Phase4）：pulled=从服务端应用到本地的项数，
    # pushed=本次以更高版本上报的脏项数。二者与上面的集合型统计分开记，
    # 便于「设置改了却没同步过去」这类问题一眼看出方向。
    settings_pulled: int = 0
    settings_pushed: int = 0
    book_meta_pulled: int = 0
    book_meta_pushed: int = 0
    errors: List[str] = field(default_factory=list)
    # 分级错误类别（供 UI 决定要不要重试 / 弹登录框 / 提示升级服务端）。
    # 键 = 功能名（"coverage"/"favorites"/"words"/"shelf"/"tasks"/"progress"），
    # 值 = ERR_KIND_* 常量。一次同步里多个功能可能各自失败，故用 dict 而非单值。
    error_kinds: Dict[str, str] = field(default_factory=dict)

    def as_text(self) -> str:
        bits: List[str] = []
        if self.tasks_added or self.tasks_updated:
            bits.append(f"任务 +{self.tasks_added}/~{self.tasks_updated}")
        if self.shelf_synced:
            bits.append(f"书架 {self.shelf_synced}")
        if self.shelf_removed:
            bits.append(f"书架移出 -{self.shelf_removed}")
        if self.favorites_inserted or self.favorites_updated or self.favorites_deleted:
            bits.append(
                f"收藏 +{self.favorites_inserted}/~{self.favorites_updated}"
                f"/-{self.favorites_deleted}")
        elif self.favorites_rebuilt:
            # 兼容旧字段（未走 reconcile 路径的调用方仍可能只填 rebuilt）
            bits.append(f"收藏 {self.favorites_rebuilt}")
        if self.favorites_pending_missing:
            bits.append(f"收藏待补传{self.favorites_pending_missing}项")
        if self.words_inserted or self.words_updated or self.words_deleted:
            bits.append(
                f"生词 +{self.words_inserted}/~{self.words_updated}/-{self.words_deleted}")
        elif self.words_rebuilt:
            bits.append(f"生词 {self.words_rebuilt}")
        if self.words_pending_missing:
            bits.append(f"生词待补传{self.words_pending_missing}项")
        if self.coverage_rows:
            bits.append(f"覆盖 {self.coverage_rows}")
        elif self.coverage_not_modified:
            bits.append("覆盖未变")
        if self.progress_pulled or self.progress_pushed:
            bits.append(f"进度 ↓{self.progress_pulled}/↑{self.progress_pushed}")
        if self.settings_pulled or self.settings_pushed:
            bits.append(f"偏好 ↓{self.settings_pulled}/↑{self.settings_pushed}")
        if self.book_meta_pulled or self.book_meta_pushed:
            bits.append(f"书序 ↓{self.book_meta_pulled}/↑{self.book_meta_pushed}")
        if self.errors:
            bits.append("错误:" + ";".join(self.errors[:2]))
        return " · ".join(bits) if bits else "已同步"


class SyncManager:
    """PC ↔ 服务端的同步编排器（无 Qt 依赖，可在后台线程调用）。"""

    # 任务分页：单页 100（= 服务端 MAX_TASKS_PAGE_SIZE），最多翻 20 页兜底
    _TASK_PAGE_SIZE = 100
    _TASK_MAX_PAGES = 20

    def __init__(self, config: AppConfig, api: ApiClient, db: LocalDatabase):
        self.config = config
        self.api = api
        self.db = db

    # ==================================================================
    # 入口
    # ==================================================================

    def is_available(self) -> bool:
        """仅在「已登录（本地存有 token）」时才允许同步。"""
        try:
            return bool(self.config.is_authenticated and self.config.token)
        except Exception:  # noqa: BLE001
            return False

    def sync_all(self, include_progress: bool = True) -> SyncResult:
        """串起四类同步；每一类独立 try，一类失败不阻断其它类。"""
        res = SyncResult()
        if not self.is_available():
            res.errors.append("未登录/离线")
            res.error_kinds["auth"] = ERR_KIND_NOT_AUTHED
            return res
        # 顺序：先拉任务（进度/收藏/覆盖图的对账都要靠任务映射），再书架、收藏、生词、覆盖图；
        # 之后才是 LWW 两类（book_meta 要等 _pull_shelf 把 books 行建好/删好），最后进度。
        # 功能名 → 用户可读的中文标签（用于拼错误消息）；新增项必须同时登记到这里，
        # 否则失败只会显示英文功能名。
        feature_labels = {
            "tasks": "任务",
            "shelf": "书架",
            "favorites": "收藏",
            "words": "生词本",
            "coverage": "覆盖图",
            "settings": "偏好设置",
            "book_meta": "书架排序",
        }
        for name, fn in (
            ("tasks", lambda: self._merge_tasks(res)),
            ("shelf", lambda: self._pull_shelf(res)),
            ("favorites", lambda: self._sync_favorites(res)),
            ("words", lambda: self._sync_word_book(res)),
            ("coverage", lambda: self._sync_audio_coverage(res)),
            ("settings", lambda: self._sync_user_settings(res)),
            ("book_meta", lambda: self._sync_book_meta(res)),
        ):
            try:
                fn()
            except Exception as e:  # noqa: BLE001
                kind, msg = classify_api_error(e, feature_labels.get(name, name))
                res.errors.append(f"{name}:{msg}")
                res.error_kinds[name] = kind
        if include_progress:
            try:
                self._reconcile_progress(res)
            except Exception as e:  # noqa: BLE001
                kind, msg = classify_api_error(e, "进度")
                res.errors.append(f"progress:{msg}")
                res.error_kinds["progress"] = kind
        return res

    def pull_favorites(self) -> SyncResult:
        """只做收藏这一类同步（供「收藏页 / 阅读页收藏」打开时即时拉取，
        镜像安卓：打开收藏页即 syncWithServer 再读，避免 PC 只在登录时同步导致看不到对端新收藏）。"""
        res = SyncResult()
        if not self.is_available():
            res.error_kinds["auth"] = ERR_KIND_NOT_AUTHED
            return res
        try:
            self._sync_favorites(res)
        except Exception as e:  # noqa: BLE001
            kind, msg = classify_api_error(e, "收藏")
            res.errors.append(f"favorites:{msg}")
            res.error_kinds["favorites"] = kind
        return res

    def pull_word_book(self) -> SyncResult:
        """只做生词本这一类同步（供「单词本页」打开时即时拉取，镜像安卓进单词本页 syncWithServer）。"""
        res = SyncResult()
        if not self.is_available():
            res.error_kinds["auth"] = ERR_KIND_NOT_AUTHED
            return res
        try:
            self._sync_word_book(res)
        except Exception as e:  # noqa: BLE001
            kind, msg = classify_api_error(e, "生词本")
            res.errors.append(f"words:{msg}")
            res.error_kinds["words"] = kind
        return res

    # ==================================================================
    # 1) 在线合成任务：分页拉取 → add-no-delete 合并
    # ==================================================================

    def _merge_tasks(self, res: SyncResult) -> None:
        # 预先记录已存在的 server_task_id，用于区分「新增 / 更新」
        existing_ids = {
            t.server_task_id for t in self.db.get_tasks() if t.server_task_id
        }
        # 用户已在本机书架「移除」的云端任务墓碑：拉取时跳过，避免删掉后又被同步拉回
        try:
            dismissed = self.db.list_dismissed_task_ids()
        except Exception:  # noqa: BLE001
            dismissed = set()
        page = 1
        seen = 0
        while page <= self._TASK_MAX_PAGES:
            data = self.api.get_user_tasks_raw(page=page, size=self._TASK_PAGE_SIZE)
            tasks = data.get("tasks", []) if isinstance(data, dict) else (data or [])
            if not tasks:
                break
            for t in tasks:
                server_task_id = t.get("taskId") or t.get("id")
                if not server_task_id:
                    continue
                sid = int(server_task_id)
                if sid in dismissed:
                    continue
                lid = self.db.upsert_server_task(
                    server_task_id=sid,
                    book_name=t.get("originalBookName", "") or "",
                    status=t.get("status", "") or "",
                    total_segments=int(t.get("totalSegments", 0) or 0),
                    completed_segments=int(t.get("completedSegments", 0) or 0),
                    error_message=t.get("errorMessage", "") or "",
                    created_at=_fmt_created_at(t.get("createdAt")),
                )
                if sid not in existing_ids:
                    if lid:
                        res.tasks_added += 1
                        existing_ids.add(sid)
                elif lid:
                    # lid 非 0 表示确实有状态变化（无变化时 upsert 返回 0）
                    res.tasks_updated += 1
                seen += 1
            total = int((data.get("total", 0) if isinstance(data, dict) else 0) or 0)
            # 收满 total 或本页不满一页 → 结束
            if total and seen >= total:
                break
            if len(tasks) < self._TASK_PAGE_SIZE:
                break
            page += 1

    # ==================================================================
    # 2) 书城书架：拉取 → 按 storeBookId upsert
    # ==================================================================

    def _pull_shelf(self, res: SyncResult) -> None:
        # get_shelf 若网络/鉴权失败会抛 ApiException，被 sync_all 捕获后不进入下面的删除对账，
        # 因此"能正常返回（哪怕是空列表）"即视为服务端书架的真实快照。
        items = self.api.get_shelf()
        server_ids: set = set()
        n = 0
        # 本机已「移出书架」的书城书：跳过 upsert。
        # 为什么：移出书架的服务端 DELETE 是**异步**的（要等一个网络往返），
        # 而上面这份快照可能取自删除之前 ⇒ 直接 upsert 会把刚删的书**复活**
        # ⇒ 用户看到「点了删除没反应」（书闪一下又回来）。
        # 墓碑在用户重新加入该书时清除（见 LocalDatabase.clear_store_book_dismissal）。
        try:
            dismissed = self.db.list_dismissed_store_book_ids()
        except Exception:  # noqa: BLE001
            dismissed = set()
        for it in items or []:
            store_book_id = it.get("storeBookId")
            if not store_book_id:
                continue
            sid = int(store_book_id)
            server_ids.add(sid)
            if sid in dismissed:
                # 已在本机移出：跳过拉回，但仍记入 server_ids，
                # 免得下面的删除对账把它当成本机残留行删掉（那是另一条路径）。
                continue
            lid = self.db.upsert_store_shelf_book(
                store_book_id=sid,
                title=it.get("title", "") or "未命名",
                author=it.get("author", "") or "",
                category=it.get("category", "") or "store",
                has_audio=bool(it.get("hasAudio", False)),
            )
            if lid:
                n += 1
        res.shelf_synced = n

        # 删除对账（跨设备彻底移出）：本地「书城来源」书(store_book_id 非空)若已不在服务端
        # 书架，说明在别的设备上被移出 → 本机也移除该 DB 行（不动磁盘文件，也不碰本地导入书）。
        try:
            removed = 0
            for b in self.db.get_books():
                sbid = getattr(b, "store_book_id", None)
                if sbid and int(sbid) not in server_ids:
                    self.db.remove_book(b.id)
                    removed += 1
            res.shelf_removed = removed
        except Exception as e:  # noqa: BLE001
            res.errors.append(f"shelf-prune:{e}")


    # ==================================================================
    # 3) 收藏：服务端为真源 + 安全护栏重建
    # ==================================================================

    def _sync_favorites(self, res: SyncResult) -> None:
        pending = self.db.pending_upload_favorites()
        # 只把有云端身份(book_name, segment_number)的项当作可上云项；
        # 无身份的（旧数据 / 未带段号）不参与 sync，也不会阻塞对账（它们本就无 server_id）。
        uploadable = [f for f in pending if f.book_name and f.segment_number and f.text]
        res.favorites_pending = len(uploadable)

        server_list: List[dict]
        if uploadable:
            payload = [
                {
                    "bookName": f.book_name,
                    "segmentNumber": f.segment_number,
                    "content": f.text,
                    **({"taskId": f.task_id} if f.task_id else {}),
                }
                for f in uploadable
            ]
            resp = self.api.sync_favorites(payload)
            server_list = self._extract_favorites(resp)
        else:
            resp = self.api.get_favorites_raw()
            server_list = self._extract_favorites(resp)

        # P1 #4：按项对账（取代旧的「全或无 guard + rebuild」）。
        # 服务端已确认的项直接 upsert 回填 server_id；未确认的待上传项保留原样等下次同步；
        # 服务端已删除的行 (server_id 非空但 items 里没有) 本地跟随删除。
        stats = self.db.reconcile_favorites_from_server(server_list)
        res.favorites_inserted = stats.get("inserted", 0)
        res.favorites_updated = stats.get("updated", 0)
        res.favorites_deleted = stats.get("deleted", 0)
        res.favorites_pending_missing = stats.get("pending_missing", 0)
        # 兼容别名：旧字段消费者仍可读到有意义的值
        res.favorites_rebuilt = res.favorites_inserted + res.favorites_updated
        res.favorites_guarded = res.favorites_pending_missing > 0
        if res.favorites_pending_missing:
            # 信息性提示，不阻塞对账（其它项已按项落库；这些行保留原样，下次同步再试）
            res.errors.append(
                f"收藏:{res.favorites_pending_missing}项待上传未回执,保留原样下次重试")

    @staticmethod
    def _extract_favorites(resp: object) -> List[dict]:
        if isinstance(resp, dict):
            items = resp.get("favorites", resp.get("data", []))
        elif isinstance(resp, list):
            items = resp
        else:
            items = []
        out: List[dict] = []
        for f in items or []:
            if not isinstance(f, dict):
                continue
            out.append({
                "id": f.get("id"),
                "bookName": f.get("bookName", "") or "",
                "segmentNumber": f.get("segmentNumber", 0) or 0,
                "content": f.get("content", "") or "",
                "createdAt": f.get("createdAt", "") or "",
                "taskId": f.get("taskId"),
            })
        return out

    # ==================================================================
    # 3b) 生词本：服务端为真源 + 安全护栏重建（镜像收藏）
    # ==================================================================

    def _sync_word_book(self, res: SyncResult) -> None:
        pending = self.db.list_pending_words()
        # 只有具备归一化键(sw)的行才能上云：本机 add_word 已回填 sw，历史遗留行在迁移里补齐。
        uploadable = []
        for w in pending:
            sw = (w.sw or _sw_key(w.word)).strip()
            if sw and (w.word or "").strip():
                uploadable.append((sw, w))
        res.words_pending = len(uploadable)

        server_list: List[dict]
        if uploadable:
            # 出处 6 键必须随上传带出（服务端按「最近一次带出处的上报」刷新出处）。
            # 从本地行读，别丢 —— 否则补传一次会把云端出处当成旧客户端数据而保留旧值。
            payload = [
                {
                    "sw": sw,
                    "word": w.word,
                    **({"phonetic": w.phonetic} if w.phonetic else {}),
                    **({"translations": w.definition} if w.definition else {}),
                    **_word_source_payload(w),
                }
                for sw, w in uploadable
            ]
            resp = self.api.sync_word_book(payload)
            server_list = self._extract_words(resp)
        else:
            resp = self.api.get_word_book_raw()
            server_list = self._extract_words(resp)

        # P1 #4：按项对账（取代旧的「全或无 guard + rebuild」）。
        # 未确认的待上传词保留原样，其它词的 server_id 回填 / 服务端删除镜像照常进行。
        stats = self.db.reconcile_words_from_server(server_list)
        res.words_inserted = stats.get("inserted", 0)
        res.words_updated = stats.get("updated", 0)
        res.words_deleted = stats.get("deleted", 0)
        res.words_pending_missing = stats.get("pending_missing", 0)
        res.words_rebuilt = res.words_inserted + res.words_updated
        res.words_guarded = res.words_pending_missing > 0
        if res.words_pending_missing:
            res.errors.append(
                f"生词:{res.words_pending_missing}项待上传未回执,保留原样下次重试")

    @staticmethod
    def _extract_words(resp: object) -> List[dict]:
        if isinstance(resp, dict):
            items = resp.get("words", resp.get("data", []))
        elif isinstance(resp, list):
            items = resp
        else:
            items = []
        out: List[dict] = []
        for it in items or []:
            if not isinstance(it, dict):
                continue
            out.append({
                "id": it.get("id"),
                "sw": it.get("sw", "") or "",
                "word": it.get("word", "") or "",
                "phonetic": it.get("phonetic", "") or "",
                "translations": it.get("translations", "") or "",
                "createdAt": it.get("createdAt", "") or "",
                # 出处 6 键：全部可空，服务端返回 None 时保持 None（刻意不 `or ""`）。
                # 白名单漏一个键，拉回后本地出处就被 reconcile/rebuild 当成「无出处」丢掉。
                "bookName": it.get("bookName"),
                "bookKey": it.get("bookKey"),
                "storeBookId": it.get("storeBookId"),
                "segmentNumber": it.get("segmentNumber"),
                "lineNumber": it.get("lineNumber"),
                "sentenceText": it.get("sentenceText"),
            })
        return out

    # ==================================================================
    # 3c) 有声覆盖图：本机上报并集 + 服务端合并后重建本地缓存（Phase3，纯元数据）
    # ==================================================================

    def pull_audio_coverage(self) -> SyncResult:
        """只做覆盖图这一类同步（供书架页打开时即时刷新，本机覆盖上云 + 拉回合并全量）。"""
        res = SyncResult()
        if not self.is_available():
            res.error_kinds["auth"] = ERR_KIND_NOT_AUTHED
            return res
        try:
            self._sync_audio_coverage(res)
        except Exception as e:  # noqa: BLE001
            kind, msg = classify_api_error(e, "覆盖图")
            res.errors.append(f"coverage:{msg}")
            res.error_kinds["coverage"] = kind
        return res

    def _sync_audio_coverage(self, res: SyncResult) -> None:
        """把**本机**覆盖图并集上报，再用服务端合并后的全量重建本地 ``audio_coverage`` 缓存。

        覆盖图是「哪些段以何来源合成过」的全局感知层，只增不减、不删本地音频，
        因此不需要收藏/生词那种「待上传项是否都在返回列表」的重建护栏——
        直接以服务端返回的全量镜像本地缓存即可。

        P1 #3 起走**条件 GET**：本机无音频时（新设备 / 只下了电子书）带缓存的 ETag
        发 ``If-None-Match``，服务端未变则回 304，本地缓存原样保留、跳过重建——
        书架页每次打开都触发的这条路径，绝大多数时候省掉一次全量传输 + 一次 rebuild。
        本机有音频时仍走 POST 并集上报（服务端必须知道本机新增的段），
        响应里带的新 ETag 落盘供下次条件 GET 用。
        """
        local = self.db.local_audio_coverage()
        res.coverage_pushed = len(local)
        if local:
            etag, resp = self.api.sync_audio_coverage_with_etag(local)
            if etag:
                self.db.set_sync_state(_COVERAGE_ETAG_KEY, etag)
            server_list = self._extract_coverage(resp)
            res.coverage_rows = self.db.rebuild_audio_coverage_from_server(server_list)
        else:
            cached_etag = self.db.get_sync_state(_COVERAGE_ETAG_KEY)
            status, etag, data = self.api.get_audio_coverage_conditional(cached_etag)
            if status == 304 or data is None:
                # 服务端未变（或老服务端不发 ETag 但回了 304）：本地缓存仍权威，跳过重建
                res.coverage_not_modified = True
                if etag and etag != cached_etag:
                    self.db.set_sync_state(_COVERAGE_ETAG_KEY, etag)
                return
            if etag:
                self.db.set_sync_state(_COVERAGE_ETAG_KEY, etag)
            server_list = self._extract_coverage(data)
            res.coverage_rows = self.db.rebuild_audio_coverage_from_server(server_list)

    @staticmethod
    def _extract_coverage(resp: object) -> List[dict]:
        """解析服务端覆盖图响应（键 = 'items'）为 rebuild 所需的 camelCase 字典列表。"""
        if isinstance(resp, dict):
            items = resp.get("items", resp.get("data", []))
        elif isinstance(resp, list):
            items = resp
        else:
            items = []
        out: List[dict] = []
        for it in items or []:
            if not isinstance(it, dict):
                continue
            bk = it.get("bookKey", "") or ""
            src = it.get("source", "") or ""
            if not bk or not src:
                continue
            out.append({
                "bookKey": bk,
                "source": src,
                "storeBookId": it.get("storeBookId"),
                "serverTaskId": it.get("serverTaskId"),
                "totalSegments": it.get("totalSegments", 0) or 0,
                "doneRanges": it.get("doneRanges", "") or "",
                "doneCount": it.get("doneCount", 0) or 0,
                "updatedAt": it.get("updatedAt", "") or "",
            })
        return out

    # ==================================================================
    # 4) 阅读进度：逐 book_key 双向对账（时间戳新者胜）
    # ==================================================================

    def _reconcile_progress(self, res: SyncResult) -> None:
        rows = self.db.list_all_progress()
        for row in rows:
            self._reconcile_one(row, res)

    def reconcile_book(self, book_key: str) -> SyncResult:
        """对单本书做一次进度双向对账（供阅读页打开时拉取云端最新位置）。"""
        res = SyncResult()
        if not self.is_available() or not book_key:
            return res
        prog = self.db.get_progress(book_key)
        row = {
            "book_key": book_key,
            "segment_number": prog.segment_number,
            "offset_ms": prog.offset_ms,
            "updated_at": prog.updated_at,
            "server_updated_at": prog.server_updated_at,
        }
        try:
            self._reconcile_one(row, res)
        except Exception as e:  # noqa: BLE001
            res.errors.append(f"progress:{e}")
        return res

    def _reconcile_one(self, row: dict, res: SyncResult) -> None:
        book_key = row.get("book_key", "")
        target = self._resolve_server_target(book_key)
        if target is None:
            return  # 本地纯文本书 / 无云端标识：无法与服务端对账
        kind, server_id = target
        local_seg = int(row.get("segment_number", 0) or 0)
        local_off = int(row.get("offset_ms", 0) or 0)
        local_ts = row.get("updated_at", "") or ""
        try:
            if kind == "store":
                srv = self.api.get_store_reading_progress_raw(server_id)
            else:
                srv = self.api.get_progress_raw(server_id)
        except Exception:  # noqa: BLE001
            return
        if not isinstance(srv, dict) or not srv.get("hasProgress"):
            # 服务端无记录：本机若确有进度 → 上传建立
            if local_seg > 0:
                if self._push_progress(kind, server_id, local_seg, local_off):
                    res.progress_pushed += 1
            return
        srv_seg = int(srv.get("segment", 0) or 0)
        srv_off = int(srv.get("offsetMs", 0) or 0)
        srv_ts = srv.get("updatedAt", "") or ""
        if _is_server_progress_newer(local_seg, local_ts, srv_seg, srv_ts):
            # 服务端更新 → 覆盖本地
            if self.db.apply_server_progress(book_key, srv_seg, srv_off, srv_ts):
                res.progress_pulled += 1
        else:
            # 本地更新（段号更大，或段号相等且本地时间戳更新）→ 补传
            # 但仅当本地确有新意（段号/偏移不为 0）才传，避免无谓写
            if local_seg > 0 and (local_seg > srv_seg or
                                  (local_seg == srv_seg and _norm_ts(local_ts) > _norm_ts(srv_ts))):
                if self._push_progress(kind, server_id, local_seg, local_off):
                    res.progress_pushed += 1

    def _push_progress(self, kind: str, server_id: int, seg: int, offset_ms: int) -> bool:
        try:
            if kind == "store":
                self.api.put_store_reading_progress(server_id, seg, offset_ms)
            else:
                self.api.put_progress(server_id, seg, offset_ms)
            return True
        except Exception:  # noqa: BLE001
            return False

    # ---------- 书口径进度（跨端通用） ----------

    def fetch_book_progress(self, server_book_key: str):
        """按跨端 bookKey 拉服务端的阅读进度。

        与 :meth:`reconcile_book` 的区别：后者用**本地**进度主键
        （``local:{bookId}`` / ``store:{storeBookId}``），只能定位到「本地那本书」；
        本方法用**跨端统一**的 bookKey（``s:``/``t:``/``n:``），
        同一本书在 Android/PC/Web 三端算出的是同一个 key ⇒ 进度才能互通。

        :return: 服务端返回的原始 dict（无记录时是 ``{}``）；异常时也是 ``{}``
        """
        if not self.is_available() or not server_book_key:
            return {}
        try:
            srv = self.api.get_book_progress_raw(server_book_key)
        except Exception:  # noqa: BLE001
            return {}
        return srv if isinstance(srv, dict) else {}

    def push_book_progress(self, server_book_key: str, seg: int, offset_ms: int) -> bool:
        """按跨端 bookKey 上报阅读进度。失败返回 False（不抛异常，离线时由本地兜底）。"""
        if not self.is_available() or not server_book_key or seg <= 0:
            return False
        try:
            self.api.put_book_progress(server_book_key, seg, offset_ms)
            return True
        except Exception:  # noqa: BLE001
            return False

    def _resolve_server_target(self, book_key: str) -> Optional[tuple]:
        """把本地进度主键 book_key 映射到服务端进度标识。

        - 'store:{storeBookId}' → ('store', storeBookId)
        - 'local:{bookId}'      → ('task', serverTaskId)（经该书最近任务映射；无任务则 None）
        """
        if not book_key or ":" not in book_key:
            return None
        kind, _, val = book_key.partition(":")
        try:
            iv = int(val)
        except (TypeError, ValueError):
            return None
        if kind == "store":
            return ("store", iv) if iv else None
        if kind == "local":
            task = self._task_for_local_book(iv)
            if task and task.server_task_id:
                return ("task", int(task.server_task_id))
            return None
        return None

    def _task_for_local_book(self, book_id: int):
        try:
            book = self.db.get_book(book_id)
            if not book:
                return None
            tasks = [t for t in self.db.get_tasks() if t.book_name == book.display_name]
            if not tasks:
                return None
            completed = [t for t in tasks
                         if t.status == "completed" and t.server_task_id]
            pick = (completed or tasks)[0]
            return pick
        except Exception:  # noqa: BLE001
            return None

    # ==================================================================
    # Phase4：LWW 单值偏好 + 每书元数据
    #
    # ⛔ 这两类**不是集合型**，绝不能套 _merge_tasks / _sync_audio_coverage 的
    #    add-no-delete 并集语义 —— 用户把倍速从 1.0 改成 1.5，并集会永远保留旧值，
    #    表现为「改了设置同步不过去」。这里靠「影子快照 + 服务端版本水位」判脏：
    #      本地值 == 影子 ⇒ 干净，原样回传服务端版本（服务端判定不覆盖）；
    #      本地值 != 影子 ⇒ 脏，用 max(now, 全局最高版本+1) 去覆盖。
    #    应用远端后立刻把影子更新为「应用后的本地样子」⇒ 天然免疫
    #    「拉取 → 写本地 → 又触发上报」的回写循环，不需要给 config/db 埋任何钩子。
    # ==================================================================

    def _settings_local_values(self) -> Dict[str, str]:
        return {
            "playback_speed": _canon_speed(getattr(self.config, "playback_speed", 1.0)),
            "default_voice_id": (getattr(self.config, "default_voice_id", "") or "").strip(),
        }

    def _sync_user_settings(self, res: SyncResult) -> None:
        """PUT /user-settings：一次往返完成拉 + 推（LWW）。"""
        shadow = _json_state(self.db, _SS_SHADOW, {})
        versions = _json_state(self.db, _SS_VERSIONS, {})
        global_ver = max([0] + [_as_long(v) for v in versions.values()])
        current = self._settings_local_values()

        items: List[Dict] = []
        for key in _SETTING_KEYS:
            value = current[key]
            if key in shadow and shadow.get(key) == value:
                # 干净项：原样回传服务端版本 ⇒ 服务端 shouldOverwrite=false ⇒ 一次库写入都不做
                items.append({"key": key, "value": value,
                              "updatedAt": _as_long(versions.get(key))})
            elif key not in shadow and value == _SETTING_DEFAULTS[key]:
                # ⭐ 首次同步且本地是默认值：回传中性版本 0。
                # 否则「新设备刚装好、用默认值 1.0」会把另一端设的 1.75 冲掉。
                items.append({"key": key, "value": value, "updatedAt": 0})
            else:
                # 本地改过；老用户首次上云且本地已有非默认值也算（不能丢用户设置）
                items.append({"key": key, "value": value,
                              "updatedAt": _now_version(global_ver)})
                res.settings_pushed += 1

        resp = self.api.sync_user_settings(items)
        if not isinstance(resp, dict) or resp.get("success") is False:
            raise ValueError(str((resp or {}).get("message") or "偏好设置同步失败")
                             if isinstance(resp, dict) else "偏好设置同步响应异常")
        server_items = resp.get("items")
        if not isinstance(server_items, list):
            raise ValueError("偏好设置同步响应缺少 items")

        applied = False
        for it in server_items:
            if not isinstance(it, dict):
                continue
            key = it.get("key")
            if key not in current:
                continue
            value = "" if it.get("value") is None else str(it.get("value"))
            versions[key] = _as_long(it.get("updatedAt"))
            if key == "playback_speed":
                try:
                    self.config.playback_speed = float(_canon_speed(value))
                except (TypeError, ValueError):
                    self.config.playback_speed = 1.0
                applied = True
            elif key == "default_voice_id":
                self.config.default_voice_id = value.strip()
                applied = True
            res.settings_pulled += 1

        if applied:
            # 直接赋值 + save，⛔ 不走 settings_changed 信号（那会再触发一轮同步/重绘）
            try:
                self.config.save()
            except Exception:  # noqa: BLE001
                pass
        # 影子必须记「应用远端之后的本地样子」，否则下一轮又判成脏。
        # 游标只在请求成功返回后落盘，失败时不留「假已同步」的水位。
        _write_state(self.db, _SS_SHADOW, self._settings_local_values())
        _write_state(self.db, _SS_VERSIONS, versions)

    def _local_book_meta_snapshot(self) -> Tuple[Dict[str, list], Dict[str, int]]:
        """本地书籍元数据快照 + bookKey→book_id 映射。

        ``books`` 表**没有** ``server_task_id`` 列，要算出 ``t:`` 前缀的 bookKey
        只能借 ``tasks`` 表按书名反查（与 Android ``getShelfBookKeyMap`` 同口径，
        也与 ``reader_page._server_book_key`` 一致）。
        """
        stid_by_name: Dict[str, int] = {}
        try:
            for t in self.db.get_tasks():
                sid = int(getattr(t, "server_task_id", 0) or 0)
                name = getattr(t, "book_name", "") or ""
                if sid > 0 and name and name not in stid_by_name:
                    stid_by_name[name] = sid
        except Exception:  # noqa: BLE001
            pass

        snap: Dict[str, list] = {}
        ids: Dict[str, int] = {}
        for b in self.db.get_books():
            bk = coverage.book_key(getattr(b, "store_book_id", 0) or 0,
                                   stid_by_name.get(b.display_name or ""),
                                   b.display_name or "")
            if not bk or bk in snap:
                continue
            snap[bk] = [int(getattr(b, "sort_order", 0) or 0),
                        str(getattr(b, "last_read_at", "") or "")]
            ids[bk] = int(b.id)
        return snap, ids

    def _sync_book_meta(self, res: SyncResult) -> None:
        """POST /book-meta/sync：一次往返完成拉 + 推（LWW，只同步排序与最近阅读）。"""
        local, key_to_id = self._local_book_meta_snapshot()
        shadow = _json_state(self.db, _BM_SHADOW, {})
        server = _json_state(self.db, _BM_SERVER, {})
        versions = _json_state(self.db, _BM_VERSIONS, {})
        global_ver = max([0] + [_as_long(v) for v in versions.values()])

        items: List[Dict] = []
        for bk, (sort_order, iso) in local.items():
            ms = _iso_to_millis(iso)
            if bk in shadow and list(shadow.get(bk) or []) == [sort_order, iso]:
                # 干净项回传**服务端权威值**（不是本地换算值）——
                # PC 的 ISO 串只精确到秒，直接回传会把服务端毫秒尾数抹掉，造成版本抖动。
                sv = server.get(bk) or {}
                items.append({"bookKey": bk,
                              "sortOrder": _as_long(sv.get("sortOrder"), sort_order),
                              "lastReadAt": _as_long(sv.get("lastReadAt"), ms),
                              "updatedAt": _as_long(versions.get(bk))})
            elif bk not in shadow and sort_order == 0 and ms == 0:
                # 首次同步且本地全默认 ⇒ 中性版本，别让新设备空书架冲掉服务端排序
                items.append({"bookKey": bk, "sortOrder": 0,
                              "lastReadAt": 0, "updatedAt": 0})
            else:
                items.append({"bookKey": bk, "sortOrder": sort_order,
                              "lastReadAt": ms, "updatedAt": _now_version(global_ver)})
                res.book_meta_pushed += 1

        resp = self.api.sync_book_meta(items)
        if not isinstance(resp, dict) or resp.get("success") is False:
            raise ValueError(str((resp or {}).get("message") or "书架排序同步失败")
                             if isinstance(resp, dict) else "书架排序同步响应异常")
        server_items = resp.get("items")
        if not isinstance(server_items, list):
            raise ValueError("书架排序同步响应缺少 items")

        updates: Dict[int, tuple] = {}
        for it in server_items:
            if not isinstance(it, dict):
                continue
            bk = it.get("bookKey")
            if not bk or bk not in key_to_id:
                # 服务端有这本书、本地没有 ⇒ 只忽略。⛔ 绝不 INSERT 书籍行，
                # 书籍真源是书架同步，本表只是元数据叠加层。
                continue
            sort_order = _as_long(it.get("sortOrder"))
            ms = _as_long(it.get("lastReadAt"))
            versions[bk] = _as_long(it.get("updatedAt"))
            server[bk] = {"sortOrder": sort_order, "lastReadAt": ms}
            iso = _millis_to_iso(ms)
            if local.get(bk) != [sort_order, iso]:
                updates[key_to_id[bk]] = (sort_order, iso)
                res.book_meta_pulled += 1

        if updates:
            self.db.apply_book_meta(updates)

        # 重新读一次本地（应用远端之后）再写影子 ⇒ 单位/秒级精度差不会造成永久误判为脏。
        local_after, _ = self._local_book_meta_snapshot()
        _write_state(self.db, _BM_SHADOW, local_after)
        _write_state(self.db, _BM_SERVER, server)
        _write_state(self.db, _BM_VERSIONS, versions)
