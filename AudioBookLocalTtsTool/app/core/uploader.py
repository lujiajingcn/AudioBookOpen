"""发布到书城（主路径）/ 上传到个人书架（兼容路径）。

## 这个工具的角色

本地合成的书是给**有声书后台**用的：成品应当出现在 App 的「书城」，而不是
某个用户自己的书架。服务端这两者是两套数据：

* **书架** —— `tasks.user_id` 指向某个用户，只有该用户看得到；
* **书城** —— `store_books` 一行（原书在 store 目录）+ `store_books.task_id`
  指向一条 tasks（`StoreReadingController` 只认这一条来判断「有没有音频」）。

所以「发布到书城」要做三件事：建一条**归管理员账号**的任务（普通用户书架里
看不到）+ 原书落到书城目录 + 写一条 store_books 指向这条任务。
收尾再补一件事：请服务端**生成正文快照**（见 `_rebuild_content_snapshot`）。

## 传的是什么

  1. **书城原书** → `<store_dir>/{分类}/{uuid_书名}.<ext>`，`store_books.file_name` 记它。
     App 书城下载/阅读正文都读这个文件。
  2. **任务原书** → `<uploads>/file_<uuid>.<ext>`，`tasks.book_file_path` 记它。
     任务侧仍能独立打开/重新合成（书城书和任务是两条可独立使用的链路）。
  3. **音频** → `<uploads>/{serverTaskId}/tts_{serverTaskId}_{段号}.mp3`，段号保持原样
     （客户端按段号对齐文字与声音，段号一变全乱）。
  4. **元数据**：`tasks` + `audio_segments`（`audio_data` 恒 NULL）+ `store_books`。

## 幂等与回滚

* 书城已有**同名**书且它已经有音频 ⇒ 默认跳过（勾「强制重传」才覆盖，且复用
  同一条 store_books 记录与原文件名，不会在书城里堆出两本同名的书）。
* 单个任务失败 ⇒ 回滚数据库事务 + 删掉刚传的音频目录与两份原书，
  绝不在服务器上留一个「有书没声」的半成品（客户端会当成整本来播，后半本全是空气）。
"""

from __future__ import annotations

import re
import shlex
import shutil
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable
from uuid import uuid4

from app.config import AppConfig, MySqlTarget
from app.core import db
from app.core.api_client import AdminApi
from app.core.ssh_upload import (
    ServerInfo,
    SshClient,
    pick_free_port,
    read_server_info,
)

LogFn = Callable[[str], None]
ProgressFn = Callable[[int, int, str], None]

# 每推多少个音频文件打一行日志（3500 个文件逐条报会把日志刷爆）
FILES_PER_LOG = 500

# 与 BookStoreService.COVER_COLORS 完全一致：书城封面底色的唯一来源
COVER_COLORS = (
    "#8D6E63", "#78909C", "#A1887F", "#90A4AE",
    "#7E57C2", "#5C6BC0", "#42A5F5", "#26A69A",
    "#66BB6A", "#9CCC65", "#D4E157", "#FFB300",
    "#FB8C00", "#F4511E", "#E53935", "#D81B60",
)

# 发布到书城时写进 synthesized_by（管理端列表里能看出是哪来的）
SYNTHESIZED_BY = "local-tts-tool"

# 书名里可能要剥掉的扩展名
_EXT_RE = re.compile(r"\.(pdf|txt|md|epub|mobi|azw3?|docx?)$", re.IGNORECASE)


# ---------- 服务端口径的复刻（封面色 / 目录名 / 文件名） ----------


def java_string_hash(text: str) -> int:
    """Java `String.hashCode()` 的等价实现（按 UTF-16 码元累乘 31）。

    必须按 UTF-16 码元而不是 Python 的码点：emoji 之类会占两个码元，
    用码点算出来的 hash 与服务端不同 ⇒ 同一本书两边配色不一致。
    """
    raw = (text or "").encode("utf-16-be")
    value = 0
    for i in range(0, len(raw) - 1, 2):
        value = (31 * value + ((raw[i] << 8) | raw[i + 1])) & 0xFFFFFFFF
    return value


def pick_cover_color(title: str) -> str:
    """复刻 `BookStoreService.pickCoverColor`：同一书名 → 同一个封面色。"""
    return COVER_COLORS[(java_string_hash(title) & 0x7FFFFFFF) % len(COVER_COLORS)]


def sanitize_category(name: str) -> str:
    """复刻 `AdminService.sanitizeDirName`：只留字母/数字/中文/`_- .`，空则「其他」。"""
    cleaned = re.sub(r"[^a-zA-Z0-9\u4e00-\u9fff_\-\. ]", "", name or "").strip()
    return cleaned or "其他"


def sanitize_file_stem(stem: str) -> str:
    """书名 → 可安全落盘的文件名片段（去路径分隔符与控制字符）。"""
    cleaned = re.sub(r'[/\\:*?"<>|\x00-\x1f]', "_", stem or "").strip(" .")
    return (cleaned or "book")[:120]


def store_title(task: dict, book_path: Path) -> str:
    """书城里的书名：优先用任务里记的原书名（去掉扩展名），没有就用文件名。"""
    raw = str(task.get("original_book_name") or "").strip()
    if not raw:
        raw = book_path.stem
    return _EXT_RE.sub("", raw).strip() or book_path.stem


@dataclass
class UploadOutcome:
    """上传结果汇总。"""

    uploaded: list[tuple[int, int]] = field(default_factory=list)  # (本地 task_id, 服务器 task_id)
    # 发布到书城时额外的映射：(本地 task_id, 服务器 task_id, store_book_id)
    published: list[tuple[int, int, int]] = field(default_factory=list)
    skipped: list[int] = field(default_factory=list)
    failed: list[tuple[int, str]] = field(default_factory=list)

    @property
    def ok_count(self) -> int:
        return len(self.uploaded)

    @property
    def failed_count(self) -> int:
        return len(self.failed)


# ---------- 计划（界面先问「要传哪些」，再决定要不要让用户补原书） ----------


def plan_upload(
    cfg: AppConfig, local_task_ids: list[int] | None = None, force: bool = False
) -> list[dict]:
    """列出这次会传的任务（新的在前）。

    与 `upload_tasks` 的候选规则保持一致：默认排除 `tool_upload_log` 里已有的，
    force=True 时全部纳入。界面先用它算「哪些任务缺原书文件」，好在上传前一次性问清楚。
    """
    conn = db.Database(cfg.local_db).connect()
    try:
        tasks = db.list_tasks(conn, limit=2000)
        already = db.uploaded_task_ids(conn)
    finally:
        conn.close()
    wanted = set(local_task_ids) if local_task_ids else None
    picked = []
    for task in tasks:
        task_id = int(task["id"])
        if wanted is not None and task_id not in wanted:
            continue
        if not force and task_id in already:
            continue
        picked.append(task)
    return picked


def work_dir_for(cfg: AppConfig, task_id: int) -> Path:
    """该任务的中间产物目录（与 synthesizer 的口径一致）。"""
    root = (
        Path(cfg.work_dir).expanduser()
        if cfg.work_dir
        else Path(tempfile.gettempdir()) / "audiobook_local_tts"
    )
    return root / f"task_{task_id}"


def find_book_file(cfg: AppConfig, task: dict) -> Path | None:
    """找任务的原书文件：先看工作目录里合成时留的副本（`book.<ext>`）。

    早期版本合成时没留副本，只能由界面让用户手选（见 `plan_upload` 的用法）。
    """
    work = work_dir_for(cfg, int(task["id"]))
    if work.is_dir():
        for candidate in sorted(work.glob("book.*")):
            if candidate.is_file() and candidate.stat().st_size > 0:
                return candidate
    return None


def suggest_book_name(task: dict) -> str:
    """给文件选择框一个像样的默认文件名。"""
    raw = str(task.get("book_file_path") or "")
    name = Path(raw).name
    if name and name.lower() != "book":
        return name
    return str(task.get("original_book_name") or "")


# ---------- 主流程 ----------


def upload_tasks(
    cfg: AppConfig,
    local_task_ids: list[int] | None = None,
    force: bool = False,
    on_log: LogFn | None = None,
    on_progress: ProgressFn | None = None,
    book_files: dict[int, str] | None = None,
    target: str | None = None,
    meta: dict[int, dict] | None = None,
) -> UploadOutcome:
    """把本地任务发布到书城（默认）或上传到个人书架。

    local_task_ids 为空时处理「全部未处理过」的任务；force=True 忽略上传记录。
    book_files：{本地 task_id: 原书路径} —— 界面在发布前解析/让用户选好，
    因为文件选择框必须在主线程弹，不能在这里（工作线程）临时问。
    target：store（书城，默认）/ shelf（个人书架）。留空取 cfg.publish_target。
    meta：{本地 task_id: {"title": ..., "author": ..., "category": ...}} ——
    书城展示用的元数据覆盖（本地 PDF 文件名往往是 `[书名].作者`，直接上架很难看）。
    """
    log = on_log or (lambda _m: None)
    progress = on_progress or (lambda _d, _t, _c: None)
    outcome = UploadOutcome()
    books = {int(k): v for k, v in (book_files or {}).items()}
    metas = {int(k): dict(v) for k, v in (meta or {}).items()}

    publish_to_store = _resolve_target(target, cfg)
    if not cfg.ssh.host:
        raise RuntimeError("没有配置 SSH 主机：请先在「服务器库」页填服务器地址")

    candidates = plan_upload(cfg, local_task_ids, force)
    if not candidates:
        log("没有需要发布的任务（都已发布过；要重发请勾「强制重传」）")
        return outcome

    ssh = SshClient(cfg.ssh, log=log)
    log(f"SSH 目标：{cfg.ssh.describe()}")
    log("读取服务器参数（env 文件 + uploads / store 目录）…")
    info = read_server_info(ssh, cfg.ssh, cfg.server_db)
    log(f"上传目标：{info.describe()}（来源：{info.source}）")

    owner_username = cfg.store_owner_username if publish_to_store else cfg.server_username
    if publish_to_store and not owner_username:
        raise RuntimeError("发布到书城需要「书城归属账号」（服务器上的管理员用户名）")

    local_conn = db.Database(cfg.local_db).connect()
    try:
        already = db.uploaded_task_ids(local_conn)
        tunnel_port = int(cfg.ssh.local_tunnel_port or 0) or pick_free_port()
        log(f"建立 SSH 隧道：本地 {tunnel_port} → {info.db.host}:{info.db.port}")
        with ssh.tunnel(tunnel_port, info.db.host, info.db.port):
            tunnel_db = MySqlTarget(
                host="127.0.0.1",
                port=tunnel_port,
                database=info.db.database,
                user=info.db.user,
                password=info.db.password,
            )
            server_conn = db.Database(tunnel_db).connect()
            try:
                user_id = _require_user(server_conn, owner_username)
                log(
                    f"发布目标：{'书城' if publish_to_store else '个人书架'}"
                    f"（任务归属 {owner_username}，id={user_id}）"
                )

                total = len(candidates)
                for index, task in enumerate(candidates, start=1):
                    local_task_id = int(task["id"])
                    name = str(task.get("original_book_name") or "")
                    progress(index - 1, total, name)
                    if local_task_id in already and not force:
                        outcome.skipped.append(local_task_id)
                        log(f"跳过任务 {local_task_id}（已发布过）")
                        continue
                    book_path = books.get(local_task_id)
                    try:
                        if not book_path:
                            raise RuntimeError(
                                "缺少原书文件：书城与阅读页都要按原书抽正文，"
                                "只传音频的话书能听不能读。请在发布前指定这本书的源文件。"
                            )
                        title = str((metas.get(local_task_id) or {}).get("title") or "")
                        title = title or store_title(task, Path(book_path))
                        if publish_to_store:
                            # 书城已有同名书且已经有音频 ⇒ 不重复发布（否则书城会出现两本同名书）
                            existing = db.find_store_book_by_title(server_conn, title)
                            if existing and existing.get("task_id") and not force:
                                outcome.skipped.append(local_task_id)
                                log(
                                    f"跳过任务 {local_task_id}：书城已有同名书《{title}》"
                                    f"（store_book #{existing['id']}，已有音频）。"
                                    "确要覆盖请勾「强制重传」。"
                                )
                                progress(index, total, name)
                                continue

                        log(f"[{index}/{total}] 任务 {local_task_id}《{name}》开始发布")
                        if publish_to_store:
                            server_task_id, store_book_id = _upload_one_to_store(
                                cfg, local_conn, server_conn, ssh, info, user_id,
                                local_task_id, task, Path(book_path), log, force,
                                meta=metas.get(local_task_id),
                            )
                            outcome.published.append((local_task_id, server_task_id, store_book_id))
                            log(
                                f"任务 {local_task_id} -> 服务器任务 {server_task_id}"
                                f" -> 书城书籍 #{store_book_id} [OK]"
                            )
                        else:
                            server_task_id = _upload_one(
                                cfg, local_conn, server_conn, ssh, info, user_id,
                                local_task_id, task, Path(book_path), log,
                            )
                            log(f"任务 {local_task_id} -> 服务器任务 {server_task_id} [OK]")
                        outcome.uploaded.append((local_task_id, server_task_id))
                        db.record_upload(
                            local_conn, local_task_id, server_task_id,
                            owner_username, int(task.get("total_segments") or 0),
                        )
                    except Exception as exc:  # 单本失败不影响后面的书
                        outcome.failed.append((local_task_id, str(exc)))
                        log(f"任务 {local_task_id} 发布失败：{exc}")
                    progress(index, total, name)
            finally:
                server_conn.close()
    finally:
        local_conn.close()
    return outcome


def _resolve_target(target: str | None, cfg: AppConfig) -> bool:
    """把 target 归一成「是否发布到书城」。拼错直接报错，不静默走错目标。"""
    raw = (target or cfg.publish_target or "store").strip().lower()
    if raw in ("store", "书城", "shop"):
        return True
    if raw in ("shelf", "书架", "user"):
        return False
    raise RuntimeError(f"未知的发布目标：{raw!r}（可选 store / shelf）")


def _require_user(server_conn, username: str) -> int:
    """查服务器上的用户 id；查不到时把可选用户名一起报出来（省得靠猜）。"""
    user_id = db.find_user_id(server_conn, username)
    if user_id is not None:
        return user_id
    with server_conn.cursor() as cur:
        cur.execute("SELECT username FROM users ORDER BY id LIMIT 20")
        names = [str(row["username"]) for row in cur.fetchall()]
    raise RuntimeError(
        f"服务器库里没有用户「{username}」。可选：" + ("、".join(names) if names else "（users 表为空）")
    )


def _upload_one(
    cfg: AppConfig,
    local_conn,
    server_conn,
    ssh: SshClient,
    info: ServerInfo,
    user_id: int,
    local_task_id: int,
    task: dict,
    book_path: Path,
    log: LogFn,
) -> int:
    """上传单个任务到「个人书架」，返回服务器侧 task_id。"""
    segments = db.fetch_segment_meta(local_conn, local_task_id)
    if not segments:
        raise RuntimeError("本地任务没有任何音频片段")
    if not book_path.is_file():
        raise RuntimeError(f"原书文件不存在：{book_path}")

    # 1) 原书 → uploads/file_<uuid>.<ext>
    suffix = book_path.suffix.lower() or ".bin"
    book_name = f"file_{uuid4().hex}{suffix}"
    remote_book = f"{info.uploads_dir}/{book_name}"
    size_mb = book_path.stat().st_size / 1024 / 1024
    log(f"  原书：{book_path.name}（{size_mb:.1f} MB）→ {remote_book}")
    ssh.put_file(book_path, remote_book)

    # 2) 任务行：先不提交，拿到 id 用来拼音频路径
    server_task_id = db.create_task(
        server_conn,
        user_id=user_id,
        book_file_path=book_name,
        original_book_name=str(task.get("original_book_name") or ""),
        # 参考音频不上传：声音已经烘进 mp3 里了，服务器不需要再拿它合成
        reference_audio_path=None,
        default_voice_id=task.get("default_voice_id"),
        total_segments=len(segments),
        status="pending",
        commit=False,
    )
    remote_dir = f"{info.uploads_dir}/{server_task_id}"
    try:
        succeeded, total_rows, failed_numbers = _push_audio_and_meta(
            cfg, local_conn, server_conn, ssh, info, server_task_id,
            local_task_id, segments, log,
        )
        server_conn.commit()
        if failed_numbers:
            log(f"  注意：{len(failed_numbers)} 段本地合成失败，已按 failed 上传（客户端会跳过）")
        log(f"  完成：{succeeded}/{total_rows} 段，服务器任务 #{server_task_id}")
        return server_task_id
    except Exception:
        # 回滚：库里的行 + 已经传上去的文件，一个都不留
        server_conn.rollback()
        ssh.remove_tree(remote_dir)
        ssh.remove_tree(remote_book)
        raise


def _upload_one_to_store(
    cfg: AppConfig,
    local_conn,
    server_conn,
    ssh: SshClient,
    info: ServerInfo,
    owner_id: int,
    local_task_id: int,
    task: dict,
    book_path: Path,
    log: LogFn,
    force: bool = False,
    meta: dict | None = None,
) -> tuple[int, int]:
    """把单个任务发布到书城，返回 (服务器 task_id, store_book_id)。

    与 `_upload_one` 的差别只有三处：
      1. 任务归属是「书城归属账号」（默认管理员）—— 普通用户书架里看不到；
      2. 原书**多传一份**到书城目录（书城下载/阅读正文读它）；
      3. 多写一条 store_books 指向这条任务（客户端据此判定「有音频」）。
    meta 可覆盖书城展示用的 title / author / category（不传则按本地任务名推）。
    """
    segments = db.fetch_segment_meta(local_conn, local_task_id)
    if not segments:
        raise RuntimeError("本地任务没有任何音频片段")
    if not book_path.is_file():
        raise RuntimeError(f"原书文件不存在：{book_path}")

    overrides = dict(meta or {})
    title = str(overrides.get("title") or "").strip() or store_title(task, book_path)
    author = str(overrides.get("author") or "").strip() or "佚名"
    suffix = book_path.suffix.lower() or ".bin"
    size = book_path.stat().st_size

    # 0) 书城已有同名书？有就复用它的分类/文件名（重传覆盖同一路径，不制造孤儿文件）
    existing = db.find_store_book_by_title(server_conn, title)
    reuse = bool(existing and existing.get("file_name"))
    if reuse:
        category = sanitize_category(str(existing.get("category") or cfg.store_category))
        file_name = str(existing["file_name"])
    else:
        category = sanitize_category(str(overrides.get("category") or cfg.store_category))
        file_name = f"{uuid4().hex}_{sanitize_file_stem(title)}{suffix}"
    remote_store = f"{info.store_dir}/{category}/{file_name}"

    # 1) 书城原书（书城下载 + 阅读正文都读它）
    size_mb = size / 1024 / 1024
    log(f"  书城原书：{book_path.name}（{size_mb:.1f} MB）→ {remote_store}")
    ssh.put_file(book_path, remote_store)
    _require_remote_file(ssh, remote_store, "书城原书")

    # 2) 任务侧原书（任务仍可独立打开 / 重新合成）
    book_name = f"file_{uuid4().hex}{suffix}"
    remote_book = f"{info.uploads_dir}/{book_name}"
    ssh.put_file(book_path, remote_book)

    # 3) 任务行（归管理员账号；不提交，先拿 id 拼音频路径）
    server_task_id = db.create_task(
        server_conn,
        user_id=owner_id,
        book_file_path=book_name,
        original_book_name=str(task.get("original_book_name") or title),
        reference_audio_path=None,
        default_voice_id=task.get("default_voice_id"),
        total_segments=len(segments),
        status="pending",
        commit=False,
    )
    remote_dir = f"{info.uploads_dir}/{server_task_id}"
    try:
        succeeded, total_rows, failed_numbers = _push_audio_and_meta(
            cfg, local_conn, server_conn, ssh, info, server_task_id,
            local_task_id, segments, log,
        )

        # 4) 书城条目：新建，或复用同名书改指到这次的任务
        if reuse:
            store_book_id = int(existing["id"])
            db.update_store_book_binding(
                server_conn, store_book_id,
                file_name=file_name, file_size=size, task_id=server_task_id,
                synthesized_by=SYNTHESIZED_BY, commit=False,
            )
            log(f"  复用书城条目 #{store_book_id}《{title}》（覆盖同名书，未新建）")
        else:
            store_book_id = db.create_store_book(
                server_conn,
                title=title,
                author=author,
                category=category,
                description=f"本地 MOSS-TTS 逐段合成，共 {len(segments)} 段。",
                file_name=file_name,
                file_extension=suffix.lstrip("."),
                file_size=size,
                cover_color=pick_cover_color(title),
                task_id=server_task_id,
                synthesized_by=SYNTHESIZED_BY,
                commit=False,
            )
            log(f"  新建书城条目 #{store_book_id}《{title}》（分类：{category}）")

        server_conn.commit()
    except Exception:
        server_conn.rollback()
        ssh.remove_tree(remote_dir)
        ssh.remove_tree(remote_book)
        # 复用场景覆盖的是书城原有文件，删了会让老条目指向空文件 ⇒ 只在新建时清理
        if not reuse:
            ssh.remove_tree(remote_store)
        raise

    # 5) 提交后自检：库里的 task_id/文件名与磁盘文件都必须对得上（失败只告警，已提交的数据不回滚）
    if failed_numbers:
        log(f"  注意：{len(failed_numbers)} 段本地合成失败，已按 failed 上传（客户端会跳过）")
    _verify_store_book(server_conn, ssh, info, store_book_id, server_task_id, remote_store, log)
    # 6) 正文快照：书由本地合成上架 ⇒ 服务端「合成收尾写快照」那段代码不会执行，
    #    缺快照的书每次打开阅读都要现解析整本（实测国富论 9.2s / 有快照 95ms）。
    _rebuild_content_snapshot(cfg, server_conn, info, store_book_id, log)
    log(f"  完成：{succeeded}/{total_rows} 段 → 服务器任务 #{server_task_id} → 书城 #{store_book_id}")
    return server_task_id, store_book_id


def _push_audio_and_meta(
    cfg: AppConfig,
    local_conn,
    server_conn,
    ssh: SshClient,
    info: ServerInfo,
    server_task_id: int,
    local_task_id: int,
    segments: list[dict],
    log: LogFn,
) -> tuple[int, int, set[int]]:
    """传音频 + 写片段/任务元数据（两条路径共用，事务由调用方提交）。

    返回 (成功段数, 总段数, 本地合成失败的段号集合)。
    """
    remote_dir = f"{info.uploads_dir}/{server_task_id}"
    pairs, expected, temp_dir, failed_numbers = _collect_audio_files(
        cfg, local_conn, local_task_id, segments, server_task_id, log
    )
    try:
        if not pairs:
            raise RuntimeError("本地没有任何可用音频（全是合成失败的段）")
        log(f"  上传 {len(pairs)} 个音频文件 → {remote_dir}")

        def _tick(count: int) -> None:
            if count % FILES_PER_LOG == 0:
                log(f"    已推送 {count}/{len(pairs)} 个音频文件")

        ssh.pipe_tar(pairs, remote_dir, on_files=_tick, total_hint=len(pairs))

        # 校验：数量与名字都要对得上（少一个文件客户端就少一段声音）
        got = set(ssh.list_dir(remote_dir))
        missing = expected - got
        if missing:
            sample = "、".join(sorted(missing)[:3])
            raise RuntimeError(
                f"远端文件校验失败：应有 {len(expected)} 个，实到 {len(got)} 个，"
                f"缺 {len(missing)} 个（如 {sample}）"
            )

        # 元数据（audio_data 恒 NULL，只记路径）
        rows = []
        succeeded = 0
        for segment in segments:
            number = int(segment["segment_number"])
            suffix_seg = _segment_suffix(segment)
            ok = number not in failed_numbers
            succeeded += 1 if ok else 0
            rows.append((
                server_task_id,
                number,
                str(segment.get("text_content") or ""),
                db.segment_audio_path(server_task_id, number, suffix_seg),
                segment.get("duration_seconds"),
                "completed" if ok else "failed",
                segment.get("voice"),
                segment.get("error_message"),
            ))
        db.batch_insert_segments(server_conn, rows, commit=False)
        db.update_task(
            server_conn,
            server_task_id,
            status="completed" if not failed_numbers else "partial",
            completed_segments=succeeded,
            total_segments=len(rows),
            commit=False,
        )
        return succeeded, len(rows), failed_numbers
    finally:
        if temp_dir is not None:
            shutil.rmtree(temp_dir, ignore_errors=True)


def _rebuild_content_snapshot(
    cfg: AppConfig,
    server_conn,
    info: ServerInfo,
    store_book_id: int,
    log: LogFn,
) -> None:
    """请服务端把这本书的正文快照补上（`book_content_snapshots`）。

    ## 为什么发布流程要带这一步

    快照只在服务端自己合成收尾时写入。本地工具是「库里直写」，合成这一步没发生过，
    所以本地发布的书**天生没有快照** —— 客户端第一次打开阅读、服务端只能现场解析整本。
    实测同一本书：有快照 95ms，没快照 9225ms，还要多等一次全文回传。

    ## 出问题不拦发布

    快照缺失只是一次「慢」，书本身是完整的（音频、原书、store_books 都已提交）。
    所以这里失败只告警：让它把一次成功的发布标记成失败，代价太大。
    """
    if not info.jwt_secret:
        log("  ⚠ 跳过正文快照：服务器 env 里读不到 JWT_SECRET"
            "（不影响发布，但客户端首次打开要等实时解析整本）")
        return

    username = (cfg.store_owner_username or "").strip()
    auth = db.fetch_user_auth(server_conn, username) if username else None
    if not auth:
        log(f"  ⚠ 跳过正文快照：服务器上查不到账号「{username}」")
        return
    if not auth["is_admin"]:
        # 管理员端点要求 ROLE_ADMIN，非管理员签出来的 token 只会收到 403
        log(f"  ⚠ 跳过正文快照：账号「{username}」不是管理员（管理员端点会 403）")
        return

    base_url = (cfg.server_api_base or f"http://{cfg.ssh.host}").strip()
    started = time.time()
    log(f"  生成正文快照（服务端解析整本，几秒到几十秒）… {base_url}")
    try:
        api = AdminApi(
            base_url=base_url,
            secret=info.jwt_secret,
            username=username,
            token_version=int(auth["token_version"]),
            log=log,
        )
        data = api.rebuild_content_snapshot(store_book_id)
    except Exception as exc:  # noqa: BLE001 - 快照失败不该让已完成的发布变红
        log(f"  ⚠ 正文快照失败：{exc}")
        log("    书已发布，但客户端首次打开会退化为实时解析整本；"
            f"可稍后手动重试：POST {base_url}/api/admin/books/{store_book_id}/content-snapshot")
        return

    elapsed = (time.time() - started) * 1000
    log(
        f"  正文快照就绪：《{data.get('title') or ''}》"
        f"{data.get('paragraphs')} 段 / {data.get('rows')} 行，耗时 {elapsed:.0f}ms"
    )


def _require_remote_file(ssh: SshClient, remote_path: str, what: str) -> None:
    """确认远端文件真的落地了（scp 成功但路径写错，只会在客户端第一次打开时才炸）。"""
    out = ssh.run(
        f"test -s {shlex.quote(remote_path)} && echo OK || echo MISSING",
        timeout=60, check=False,
    )
    if "OK" not in out:
        raise RuntimeError(f"{what}没有落到服务器：{remote_path}")


def _verify_store_book(
    server_conn,
    ssh: SshClient,
    info: ServerInfo,
    store_book_id: int,
    server_task_id: int,
    remote_store: str,
    log: LogFn,
) -> None:
    """发布后自检：库里指向对不对、文件在不在。只告警不抛（数据已提交）。"""
    row = db.fetch_store_book(server_conn, store_book_id)
    if not row:
        log(f"  ⚠ 自检失败：store_books #{store_book_id} 读不回来")
        return
    if int(row.get("task_id") or 0) != int(server_task_id):
        log(f"  ⚠ 自检失败：store_books.task_id={row.get('task_id')}，应为 {server_task_id}")
    if str(row.get("status")) != "onshelf":
        log(f"  ⚠ 自检异常：书城状态是 {row.get('status')}，不是 onshelf（App 里看不到）")
    out = ssh.run(f"test -s {shlex.quote(remote_store)} && echo OK || echo MISSING",
                  timeout=60, check=False)
    if "OK" not in out:
        log(f"  ⚠ 自检失败：书城原书不在服务器上：{remote_store}")
        return
    log(f"  自检通过：store_books #{store_book_id}（task_id={row.get('task_id')}，"
        f"分类={row.get('category')}，文件已就位）")


def _segment_suffix(segment: dict) -> str:
    """按本地记录的后缀决定远端文件名。

    本地库是只保留 WAV 的场景（找不到 ffmpeg）也存在，服务端不看后缀，
    但文件名必须与库里 `audio_file_path` 一致（客户端按库里的路径去取）。
    """
    raw = str(segment.get("audio_file_path") or "")
    return ".wav" if raw.lower().endswith(".wav") else ".mp3"


def _collect_audio_files(
    cfg: AppConfig,
    local_conn,
    local_task_id: int,
    segments: list[dict],
    server_task_id: int,
    log: LogFn,
) -> tuple[list[tuple[Path, str]], set[str], Path | None, set[int]]:
    """凑齐要上传的 (本地文件, 远端文件名) 列表。

    优先用工作目录里的 `seg_00042.mp3`（合成时就在那儿）；文件被清理过的话，
    从本地库的 BLOB 导出到临时目录再传 —— 本地库是「自包含」的最后一道保险。
    本地既没有文件也没有 BLOB（= 那段本来就合成失败）→ 记进 failed_numbers，
    不上传文件但**照样写库**：少一个段号，后面所有段的声音都会整体错位。
    """
    work = work_dir_for(cfg, local_task_id)
    pairs: list[tuple[Path, str]] = []
    expected: set[str] = set()
    failed_numbers: set[int] = set()
    need_blob: list[tuple[dict, str]] = []

    for segment in segments:
        number = int(segment["segment_number"])
        suffix = _segment_suffix(segment)
        arcname = f"tts_{server_task_id}_{number}{suffix}"
        local_file = work / f"seg_{number:05d}{suffix}"
        if local_file.is_file() and local_file.stat().st_size > 0:
            pairs.append((local_file, arcname))
            expected.add(arcname)
        else:
            need_blob.append((segment, arcname))

    temp_dir: Path | None = None
    if need_blob:
        recovered = 0
        temp_dir = Path(tempfile.mkdtemp(prefix=f"tts_upload_{local_task_id}_"))
        for segment, arcname in need_blob:
            number = int(segment["segment_number"])
            data = db.fetch_segment_audio(local_conn, local_task_id, number)
            if not data:
                failed_numbers.add(number)
                continue
            path = temp_dir / arcname
            path.write_bytes(data)
            pairs.append((path, arcname))
            expected.add(arcname)
            recovered += 1
        if recovered:
            log(f"  工作目录缺文件，已从本地库导出 {recovered} 段音频作为兜底")
    return pairs, expected, temp_dir, failed_numbers
