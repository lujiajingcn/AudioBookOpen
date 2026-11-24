"""MySQL 存取：本地库与服务器库共用一套实现。

## 为什么表结构必须与 AudioBookServer 一致

工具的产物要能**原样**推到服务器上：本地存的 `tasks` / `audio_segments`
与服务器同名同构，上传时只要「换个 task_id + 改 audio_file_path 前缀」就能直接写进
服务器库，客户端随即能播。因此这里的 DDL 是从
`AudioBookServer/init_database.sql` 抄下来的（字段名、类型、索引、引擎全一致），
只在「表已存在时不动」这一点上做了本地化处理。

## 音频存哪：文件优先，BLOB 兜底

服务端 `TtsDownloadController` 读音频的顺序是：
**先按 `audio_file_path` 找文件 → 文件缺失或为空则回退到 `audio_data` BLOB**。

这条规则让「一键上传」有了不依赖服务器文件系统的活路：
工具把 mp3 字节直接写进 `audio_data`，`audio_file_path` 仍按服务端命名规则填好
（`{taskId}/tts_{taskId}_{n}.mp3`）—— 将来把文件补到服务器 uploads 目录，
路径直接就对上了，不用改库。

## 工具自己的表

`tool_upload_log` 只建在**本地库**（服务器库不碰，服务器的 ddl-auto=validate
虽然允许多表，但那是别人的库，不该由工具塞表进去）。它记的是
「本地 task_id ↔ 服务器 task_id」的映射，用来避免重复上传。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable

import pymysql
from pymysql.cursors import DictCursor

from app.config import MySqlTarget

# 与 AudioBookServer/init_database.sql 一致（仅在「表不存在时」才创建）
DDL_USERS = """
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(100),
    is_admin TINYINT(1) NOT NULL DEFAULT 0,
    banned_until DATETIME DEFAULT NULL,
    credits INT NOT NULL DEFAULT 0,
    token_version INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
"""

DDL_TASKS = """
CREATE TABLE IF NOT EXISTS tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    book_file_path VARCHAR(500) NOT NULL,
    original_book_name VARCHAR(500) DEFAULT NULL,
    reference_audio_path VARCHAR(500) DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    total_segments INT DEFAULT 0,
    completed_segments INT DEFAULT 0,
    credits_cost INT NOT NULL DEFAULT 0,
    credits_refunded INT NOT NULL DEFAULT 0,
    error_message VARCHAR(500) DEFAULT NULL,
    default_voice_id VARCHAR(100) DEFAULT NULL,
    segment_voices TEXT,
    store_book_id BIGINT DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
"""

DDL_AUDIO_SEGMENTS = """
CREATE TABLE IF NOT EXISTS audio_segments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    segment_number INT NOT NULL,
    text_content TEXT NOT NULL,
    audio_data LONGBLOB,
    audio_file_path VARCHAR(500),
    duration_seconds FLOAT,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    error_message VARCHAR(500) DEFAULT NULL,
    voice VARCHAR(100) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_segment_task_number (task_id, segment_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
"""

# 工具自有表（只建在本地库）
DDL_TOOL_UPLOAD_LOG = """
CREATE TABLE IF NOT EXISTS tool_upload_log (
    local_task_id BIGINT NOT NULL PRIMARY KEY,
    server_task_id BIGINT NOT NULL,
    server_username VARCHAR(50) NOT NULL,
    segment_count INT NOT NULL DEFAULT 0,
    uploaded_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
"""


class DatabaseError(RuntimeError):
    """数据库操作失败。"""





# ---------- 老库兼容：按实际列约束补齐空值 ----------

# 值类型 → 该类型在「NOT NULL 且不给值」时的占位值
_EMPTY_BY_TYPE = {
    "int": 0, "bigint": 0, "smallint": 0, "tinyint": 0, "mediumint": 0,
    "decimal": 0, "float": 0.0, "double": 0.0, "bit": b"\x00",
    "date": "1970-01-01", "datetime": "1970-01-01 00:00:00",
    "timestamp": "1970-01-01 00:00:00",
}


def _not_null_columns(conn, table: str) -> dict[str, str]:
    """查该表里「不允许 NULL」的列 → {列名: 数据类型}。

    注意：本模块的连接用 DictCursor，取值要按**列名**（r[0] 会 KeyError）。
    """
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS "
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = %s AND IS_NULLABLE = 'NO'",
            (table,),
        )
        return {
            str(row["COLUMN_NAME"]): str(row["DATA_TYPE"]).lower()
            for row in cur.fetchall()
        }


def _adapt_row(required: dict[str, str], values: dict) -> dict:
    """按「NOT NULL 列清单」补齐空值（纯函数，便于批量场景复用）。"""
    if not required:
        return values
    adapted = dict(values)
    for name, value in values.items():
        if value is None and name in required:
            adapted[name] = _EMPTY_BY_TYPE.get(required[name], "")
    return adapted


def adapt_values(conn, table: str, values: dict) -> dict:
    """把值为 None、但该列是 NOT NULL 的字段补成占位值。

    ## 为什么需要

    新旧两版服务端的建表语句对「可空性」不一致：
    `init_database.sql` 里 `tasks.reference_audio_path` 是 `DEFAULT NULL`，
    而**老版本建出来的库**里它是 `NOT NULL`。工具在「没选参考音频」时
    传 None → MySQL 报 `Column 'reference_audio_path' cannot be null`。

    与其对着一列列打补丁（下次换台机器又是新的错），不如**按实际 schema 自适应**：
    插入前查 information_schema，谁不允许 NULL 就给它一个该类型的空值。
    这样工具对**任何版本**的库都能插进去。
    """
    return _adapt_row(_not_null_columns(conn, table), values)


@dataclass
class Database:
    """一个 MySQL 目标（本地库 / 服务器库）。"""

    target: MySqlTarget

    def connect(self) -> pymysql.connections.Connection:
        """建立连接（不自动 commit，事务边界由调用方控制）。"""
        try:
            return pymysql.connect(
                host=self.target.host,
                port=int(self.target.port),
                user=self.target.user,
                password=self.target.password,
                database=self.target.database,
                charset="utf8mb4",
                cursorclass=DictCursor,
                autocommit=False,
                connect_timeout=10,
                read_timeout=600,
                write_timeout=600,
            )
        except pymysql.MySQLError as exc:
            raise DatabaseError(
                f"连接 MySQL 失败（{self.target.describe()}）：{exc}"
            ) from exc

    def ping(self) -> str:
        """测试连通性，返回服务器版本串。"""
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                cur.execute("SELECT VERSION() AS v")
                row = cur.fetchone()
            return str(row["v"]) if row else "unknown"
        finally:
            conn.close()

    def ensure_schema(self, with_tool_tables: bool = False) -> None:
        """确保表结构存在。

        with_tool_tables=True 时额外建 `tool_upload_log`（只给本地库用）。
        服务器库**不建**表：那是别人的库，结构以服务端 init_database.sql 为准
        （生产是 `ddl-auto: validate`，工具擅自改结构只会把服务端启动搞坏）。
        """
        conn = self.connect()
        try:
            with conn.cursor() as cur:
                for ddl in (DDL_USERS, DDL_TASKS, DDL_AUDIO_SEGMENTS):
                    cur.execute(ddl)
                if with_tool_tables:
                    cur.execute(DDL_TOOL_UPLOAD_LOG)
            conn.commit()
        except pymysql.MySQLError as exc:
            conn.rollback()
            raise DatabaseError(f"初始化表结构失败：{exc}") from exc
        finally:
            conn.close()


# ---------- 用户 ----------


def ensure_user(conn, username: str, password: str = "local-tts-tool") -> int:
    """确保用户存在并返回 user_id。

    本地库可能压根没有 users 表（用户只是拿了个空库来做本地合成），
    这时建一个同名用户即可 —— tasks.user_id 是外键指向 users.id，
    没有用户行插入任务会直接被外键挡下。
    """
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM users WHERE username = %s", (username,))
        row = cur.fetchone()
        if row:
            return int(row["id"])
    values = adapt_values(
        conn, "users",
        {"username": username, "password": password, "is_admin": 0, "credits": 0},
    )
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO users (username, password, is_admin, credits) "
            "VALUES (%(username)s, %(password)s, %(is_admin)s, %(credits)s)",
            values,
        )
        user_id = int(cur.lastrowid)
    conn.commit()
    return user_id


def find_user_id(conn, username: str) -> int | None:
    """按用户名查 user_id（服务器侧上传时用）。"""
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM users WHERE username = %s", (username,))
        row = cur.fetchone()
    return int(row["id"]) if row else None


def fetch_user_auth(conn, username: str) -> dict | None:
    """取签发 token 需要的三样东西：id / token_version / is_admin。

    `token_version` 是服务端「改密即失效旧 token」的依据（`JwtAuthenticationFilter`
    会拿 token 里的版本号与库内当前值比对），所以本地代签时必须用库里的真实版本，
    写死 0 的话，那个账号一旦改过密码就会 401。

    老库可能没有 `token_version` 列（本地库只有半套结构），这时按 0 处理 ——
    服务端对缺失 claim 也按 0 处理，语义一致。
    """
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id, token_version, is_admin FROM users WHERE username = %s",
                (username,),
            )
            row = cur.fetchone()
    except Exception:  # noqa: BLE001 - 列不存在时退化成「只查 id」
        with conn.cursor() as cur:
            cur.execute("SELECT id, is_admin FROM users WHERE username = %s", (username,))
            row = cur.fetchone()
            if row:
                return {
                    "id": int(row["id"]),
                    "token_version": 0,
                    "is_admin": bool(row.get("is_admin")),
                }
        return None
    if not row:
        return None
    return {
        "id": int(row["id"]),
        "token_version": int(row.get("token_version") or 0),
        "is_admin": bool(row.get("is_admin")),
    }


# ---------- 任务 ----------


def create_task(
    conn,
    user_id: int,
    book_file_path: str,
    original_book_name: str,
    reference_audio_path: str | None = None,
    default_voice_id: str | None = None,
    total_segments: int = 0,
    status: str = "processing",
    commit: bool = True,
) -> int:
    """插入一条任务，返回 task_id。

    commit=False 时不提交：上传场景要与「插片段」同事务，避免留下空任务。
    """
    values = adapt_values(
        conn, "tasks",
        {
            "user_id": int(user_id),
            "book_file_path": book_file_path[:500],
            "original_book_name": (original_book_name or "")[:500],
            "reference_audio_path": reference_audio_path or None,
            "status": status,
            "total_segments": int(total_segments),
            "default_voice_id": default_voice_id or None,
        },
    )
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tasks (user_id, book_file_path, original_book_name, "
            "reference_audio_path, status, total_segments, completed_segments, "
            "credits_cost, credits_refunded, default_voice_id) "
            "VALUES (%(user_id)s, %(book_file_path)s, %(original_book_name)s, "
            "%(reference_audio_path)s, %(status)s, %(total_segments)s, 0, 0, 0, "
            "%(default_voice_id)s)",
            values,
        )
        task_id = int(cur.lastrowid)
    if commit:
        conn.commit()
    return task_id


def update_task(
    conn,
    task_id: int,
    status: str | None = None,
    completed_segments: int | None = None,
    total_segments: int | None = None,
    error_message: str | None = None,
    commit: bool = True,
) -> None:
    """更新任务状态与进度。"""
    fields: list[str] = []
    values: list[Any] = []
    if status is not None:
        fields.append("status = %s")
        values.append(status)
    if completed_segments is not None:
        fields.append("completed_segments = %s")
        values.append(int(completed_segments))
    if total_segments is not None:
        fields.append("total_segments = %s")
        values.append(int(total_segments))
    if error_message is not None:
        fields.append("error_message = %s")
        values.append(error_message[:500])
    if not fields:
        return
    values.append(int(task_id))
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE tasks SET " + ", ".join(fields) + " WHERE id = %s",
            tuple(values),
        )
    if commit:
        conn.commit()


# 只有这些状态才算「上次没跑完」。completed 说明上一轮已经跑完了，
# 这时候用户再点「开始合成」，意思是要重新跑（可能换了音色），不该续跑。
RESUMABLE_STATUSES = ("paused", "processing", "partial", "failed")


def find_resumable_task(
    conn,
    book_file_path: str,
    total_segments: int,
    reference_audio_path: str | None = None,
    default_voice_id: str | None = None,
) -> dict | None:
    """找一本上次没跑完的同源任务，用于断点续跑；没有就返回 None。

    匹配条件刻意苛刻 —— 续跑是「接着上次的段号往下写」，前提是两轮的口径完全一致：

    * **路径 + 总段数**：段号 = 句子序号，切分口径变了（改了分段器 / 换了切分长度），
      第 500 段对应的正文就变了，接着写会把 A 内容的段 500 接在 B 内容的段 499 后面。
    * **参考音频 + 音色**：音色变了，前半本一个声音、后半本另一个声音。

    状态只认 RESUMABLE_STATUSES：`completed` 排除掉 —— 那说明已经跑完，
    用户再点开始是想重跑（换音色 / 改参数），不该被"已全部跳过"糊弄过去。

    reference_audio_path 在库里存的是文件名，`<=>` 是 MySQL 的 NULL 安全等号：
    两边都是 NULL（用内置音色）时也算匹配。
    """
    placeholders = ", ".join(["%s"] * len(RESUMABLE_STATUSES))
    sql = (
        "SELECT id, status, total_segments, completed_segments FROM tasks "
        "WHERE book_file_path = %s AND total_segments = %s "
        "AND (reference_audio_path <=> %s) AND (default_voice_id <=> %s) "
        f"AND status IN ({placeholders}) "
        "ORDER BY id DESC LIMIT 1"
    )
    params = (
        book_file_path[:500],
        int(total_segments),
        reference_audio_path or None,
        default_voice_id or None,
        *RESUMABLE_STATUSES,
    )
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.fetchone()


def completed_segment_numbers(conn, task_id: int) -> set[int]:
    """该任务里**已经合成成功**的段号集合（续跑时跳过这些段）。

    只认 status='completed'：上次 failed 的段要继续重试，上次跑到一半
    （进程被强杀，段没来得及落库）的段本来就不在表里。
    """
    with conn.cursor() as cur:
        cur.execute(
            "SELECT segment_number FROM audio_segments "
            "WHERE task_id = %s AND status = 'completed'",
            (int(task_id),),
        )
        return {int(row["segment_number"]) for row in cur.fetchall()}


def fetch_task(conn, task_id: int) -> dict | None:
    with conn.cursor() as cur:
        cur.execute("SELECT * FROM tasks WHERE id = %s", (int(task_id),))
        return cur.fetchone()


def list_tasks(conn, limit: int = 200) -> list[dict]:
    """任务列表（新的在前），供界面展示与「选择要上传的任务」。"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, original_book_name, status, total_segments, completed_segments, "
            "default_voice_id, created_at FROM tasks ORDER BY id DESC LIMIT %s",
            (int(limit),),
        )
        return list(cur.fetchall())


# ---------- 音频片段 ----------


def insert_segment(
    conn,
    task_id: int,
    segment_number: int,
    text_content: str,
    audio_data: bytes | None = None,
    audio_file_path: str | None = None,
    duration_seconds: float | None = None,
    voice: str | None = None,
    status: str = "completed",
    error_message: str | None = None,
    commit: bool = True,
    replace: bool = False,
) -> int:
    """插入一条音频片段，返回 segment_id（**本地库**用）。

    audio_data 存音频字节，audio_file_path 存相对路径
    （`{taskId}/tts_{taskId}_{n}.mp3`，与服务端命名规则一致）。
    本地库里存 BLOB 是为了「中间产物被清掉后还能靠库自包含」——
    上传时会优先用工作目录的 mp3、找不到再从这里的 BLOB 导出临时文件；
    **服务器库不写 BLOB**（见 batch_insert_segments）。

    commit=False 时不提交事务：一次合成要写很多段，统一由调用方控制事务边界。

    replace=True 时改用 ON DUPLICATE KEY UPDATE：**断点续跑**会给上次已存在的段号
    （failed 的段要重试）再写一次，而 `uk_segment_task_number` 会把它挡下来。
    全新任务本来就没有旧行，这个 ON DUPLICATE 不会触发，所以调用方可以无脑打开。
    """
    values = adapt_values(
        conn, "audio_segments",
        {
            "task_id": int(task_id),
            "segment_number": int(segment_number),
            "text_content": text_content,
            "audio_data": audio_data,
            "audio_file_path": audio_file_path or None,
            "duration_seconds": (
                float(duration_seconds) if duration_seconds is not None else None
            ),
            "status": status,
            "voice": voice or None,
            "error_message": (error_message[:500] if error_message else None),
        },
    )
    with conn.cursor() as cur:
        sql = (
            "INSERT INTO audio_segments (task_id, segment_number, text_content, "
            "audio_data, audio_file_path, duration_seconds, status, voice, error_message) "
            "VALUES (%(task_id)s, %(segment_number)s, %(text_content)s, "
            "%(audio_data)s, %(audio_file_path)s, %(duration_seconds)s, %(status)s, "
            "%(voice)s, %(error_message)s)"
        )
        if replace:
            sql += (
                " ON DUPLICATE KEY UPDATE text_content = VALUES(text_content), "
                "audio_data = VALUES(audio_data), audio_file_path = VALUES(audio_file_path), "
                "duration_seconds = VALUES(duration_seconds), status = VALUES(status), "
                "voice = VALUES(voice), error_message = VALUES(error_message)"
            )
        cur.execute(sql, values)
        segment_id = int(cur.lastrowid)
    if commit:
        conn.commit()
    return segment_id


def fetch_segments(conn, task_id: int) -> list[dict]:
    """按段号取出任务的所有片段（含音频字节）。"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, segment_number, text_content, audio_data, audio_file_path, "
            "duration_seconds, status, voice, error_message FROM audio_segments "
            "WHERE task_id = %s ORDER BY segment_number",
            (int(task_id),),
        )
        return list(cur.fetchall())


def fetch_segment_meta(conn, task_id: int) -> list[dict]:
    """按段号取出片段的**元数据**（不含 audio_data）。

    上传时用它：一本 3000 段的书在本地库里躺着近 200MB 的 mp3，
    `SELECT *` 会把它们一次性读进内存（几本书就是几个 GB）。
    音频字节只在上传时「工作目录已清理」的分支里按段单独取。
    """
    with conn.cursor() as cur:
        cur.execute(
            "SELECT segment_number, text_content, audio_file_path, duration_seconds, "
            "status, voice, error_message FROM audio_segments "
            "WHERE task_id = %s ORDER BY segment_number",
            (int(task_id),),
        )
        return list(cur.fetchall())


def fetch_segment_audio(conn, task_id: int, segment_number: int) -> bytes | None:
    """取单段音频字节（工作目录里没有 mp3 时的兜底来源）。"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT audio_data FROM audio_segments WHERE task_id = %s AND segment_number = %s",
            (int(task_id), int(segment_number)),
        )
        row = cur.fetchone()
    if not row:
        return None
    data = row["audio_data"]
    return bytes(data) if data else None


def segment_audio_path(task_id: int, segment_number: int, suffix: str = ".mp3") -> str:
    """服务端风格的音频相对路径：`{taskId}/tts_{taskId}_{n}.mp3`。"""
    return f"{int(task_id)}/tts_{int(task_id)}_{int(segment_number)}{suffix}"


# ---------- 书城（store_books） ----------
#
# 发布到书城 = 一行 store_books 指向一条 tasks。客户端判定「这本书有没有音频」
# 只看 store_books.task_id 是否非空（见 StoreReadingController 的类注释），
# 所以这里必须把 task_id 一起写进去，不能只建书、指望音频自己被发现。


def find_store_book_by_title(conn, title: str) -> dict | None:
    """按书名找书城书（发布重传时复用同一条记录，避免书城里出现两本同名书）。"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, title, author, category, file_name, task_id, status "
            "FROM store_books WHERE title = %s ORDER BY id DESC LIMIT 1",
            ((title or "")[:200],),
        )
        return cur.fetchone()


def create_store_book(
    conn,
    *,
    title: str,
    author: str,
    category: str,
    description: str,
    file_name: str,
    file_extension: str,
    file_size: int,
    cover_color: str,
    task_id: int,
    synthesized_by: str,
    commit: bool = False,
) -> int:
    """插入一条书城书籍记录，返回 store_book id。

    `status` 固定 onshelf（发布即可见）；`cover_image` 留空由服务端
    `CoverRenderer` 现画「书名+作者」封面，不依赖 PDF 里有图。
    """
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO store_books (title, author, category, description, file_name, "
            "file_extension, file_size, cover_color, cover_image, download_count, "
            "editor_pick, status, task_id, synthesized_at, synthesized_by) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, NULL, 0, 0, 'onshelf', %s, NOW(), %s)",
            (
                (title or "")[:200], (author or "")[:100], (category or "")[:50],
                description or "", file_name[:500], (file_extension or "")[:10],
                int(file_size), cover_color, int(task_id), synthesized_by[:100],
            ),
        )
        new_id = int(cur.lastrowid)
    if commit:
        conn.commit()
    return new_id


def update_store_book_binding(
    conn,
    store_book_id: int,
    *,
    file_name: str,
    file_size: int,
    task_id: int,
    synthesized_by: str,
    commit: bool = False,
) -> None:
    """重传时把已有的书城书记录改指到新任务/新文件（保留标题作者分类等人工编辑过的字段）。"""
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE store_books SET file_name = %s, file_size = %s, task_id = %s, "
            "status = 'onshelf', synthesized_at = NOW(), synthesized_by = %s WHERE id = %s",
            (file_name[:500], int(file_size), int(task_id), synthesized_by[:100],
             int(store_book_id)),
        )
    if commit:
        conn.commit()


def fetch_store_book(conn, store_book_id: int) -> dict | None:
    """读回一条书城书（发布后自检用：确认 task_id 与文件名真的落库了）。"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, title, task_id, file_name, file_size, status, category "
            "FROM store_books WHERE id = %s",
            (int(store_book_id),),
        )
        return cur.fetchone()


# ---------- 上传映射（工具自有表，只在本地库） ----------


def record_upload(
    conn,
    local_task_id: int,
    server_task_id: int,
    server_username: str,
    segment_count: int,
) -> None:
    """记录「本地任务已上传到服务器」的映射。"""
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO tool_upload_log (local_task_id, server_task_id, server_username, "
            "segment_count) VALUES (%s, %s, %s, %s) "
            "ON DUPLICATE KEY UPDATE server_task_id = VALUES(server_task_id), "
            "server_username = VALUES(server_username), segment_count = VALUES(segment_count), "
            "uploaded_at = CURRENT_TIMESTAMP",
            (int(local_task_id), int(server_task_id), server_username, int(segment_count)),
        )
    conn.commit()


def fetch_uploads(conn) -> list[dict]:
    """已上传记录（供界面显示「已上传」标记）。"""
    with conn.cursor() as cur:
        cur.execute(
            "SELECT local_task_id, server_task_id, server_username, segment_count, uploaded_at "
            "FROM tool_upload_log ORDER BY uploaded_at DESC"
        )
        return list(cur.fetchall())


def uploaded_task_ids(conn) -> set[int]:
    """已上传过的本地 task_id 集合。"""
    with conn.cursor() as cur:
        cur.execute("SELECT local_task_id FROM tool_upload_log")
        return {int(row["local_task_id"]) for row in cur.fetchall()}


def batch_insert_segments(conn, rows: Iterable[tuple], commit: bool = True) -> None:
    """批量插入片段元数据（上传时用，省掉逐条 commit 的开销）。

    rows 的每个元素是：
        (task_id, segment_number, text_content, audio_file_path,
         duration_seconds, status, voice, error_message)

    **`audio_data` 固定写 NULL**：服务端音频只认文件
    （`uploads/{taskId}/tts_{taskId}_{n}.mp3`），BLOB 那条列在服务端恒为空，
    写进去既撑大表又不会被读到。commit=False 时与「插任务」同事务。
    """
    payload = list(rows)
    if not payload:
        return
    sql = (
        "INSERT INTO audio_segments (task_id, segment_number, text_content, audio_data, "
        "audio_file_path, duration_seconds, status, voice, error_message) "
        "VALUES (%(task_id)s, %(segment_number)s, %(text_content)s, NULL, "
        "%(audio_file_path)s, %(duration_seconds)s, %(status)s, %(voice)s, "
        "%(error_message)s)"
    )
    # 老库里有些列是 NOT NULL（比如 reference_audio_path）→ 按实际 schema 补空值
    required = _not_null_columns(conn, "audio_segments")
    adapted = [
        _adapt_row(required, {
            "task_id": r[0], "segment_number": r[1], "text_content": r[2],
            "audio_file_path": r[3], "duration_seconds": r[4],
            "status": r[5], "voice": r[6], "error_message": r[7],
        })
        for r in payload
    ]
    with conn.cursor() as cur:
        cur.executemany(sql, adapted)
    if commit:
        conn.commit()
