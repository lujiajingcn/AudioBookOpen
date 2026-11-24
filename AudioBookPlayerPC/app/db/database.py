"""本地 SQLite 数据库：书架、任务、音频段、阅读进度、收藏、单词本。

对标 Android DatabaseHelper / BookStorage。"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from pathlib import Path
from typing import List, Optional, Dict

from app.core.config import AppConfig
from app.core import coverage
from app.models.models import Book, Task, Segment, ReadingProgress, Favorite, WordEntry


def _sw_key(text: str) -> str:
    """单词本归一化键：去非字母数字 + 转小写。

    与 Android `DictionaryManager.stripWord`、服务端建库脚本 `strip_word`、以及本端
    ``app.core.dictionary._strip_word`` 逐字符一致 —— 三者对同一个词算出同一个 sw，
    跨端才能把 `hood` / `Hood` / `'hood` 视为同一个词，收藏不会插出多条。
    """
    return "".join(ch.lower() for ch in (text or "") if ch.isalnum())


# ---------------------------------------------------------------------------
# 生词本「收藏出处」6 键：本地列名，顺序固定（SQL 拼接与参数元组共用）。
# 与服务端 WordBookService.toItem / WordBookItemRequest 逐字段对齐，三端同口径。
# ---------------------------------------------------------------------------
WORD_SOURCE_COLUMNS = (
    "book_name", "book_key", "store_book_id",
    "segment_number", "line_number", "sentence_text",
)
# INSERT 列名 / 占位符 / UPDATE SET 片段：本文件所有 word_book 写入口共用，
# 免得哪天新增列时漏改一条 SQL ——「列白名单漏一列，一同步出处就没了」。
_WORD_SOURCE_COLS = ",".join(WORD_SOURCE_COLUMNS)
_WORD_SOURCE_QMARKS = ",".join("?" for _ in WORD_SOURCE_COLUMNS)
_WORD_SOURCE_SET = ",".join(f"{c}=?" for c in WORD_SOURCE_COLUMNS)


def _clean_word_source(book_name=None, book_key=None, store_book_id=None,
                       segment_number=None, line_number=None, sentence_text=None) -> dict:
    """把 6 个出处值规整为「可空」：空串 / 0 / 非法数字 / None 一律归一为 None。

    这是所有出处写入口（本地收藏 / 服务端拉回 / 迁移读回）共用的唯一规整点 ——
    DB 里「没有出处」永远是 NULL，:func:`_word_source_has_any` 才能准确判定，
    不会把空串 / 0 误当成有效出处。
    """
    def s(v):
        return str(v) if v is not None and str(v).strip() != "" else None

    def i(v):
        if v is None or v == "":
            return None
        try:
            n = int(v)
        except (TypeError, ValueError):
            return None
        return n if n > 0 else None  # 段号/行号 1-based，0 无意义

    return {
        "book_name": s(book_name), "book_key": s(book_key),
        "store_book_id": i(store_book_id), "segment_number": i(segment_number),
        "line_number": i(line_number), "sentence_text": s(sentence_text),
    }


def _word_source_has_any(src: dict) -> bool:
    """该项是否「带出处」——与服务端 ``WordBookItemRequest.hasSource()`` 同口径：
    bookName 或 bookKey 非空即算；其余 4 键只是补齐，不单独构成出处。

    只有判为带出处才整体覆盖本地；出处全 None（旧客户端 / 存量数据）时保留本地值，
    避免一次同步把跳转信息抹掉。
    """
    return bool(src.get("book_name") or src.get("book_key"))


def _word_source_tuple(src: dict) -> tuple:
    """出处 dict → 按 :data:`WORD_SOURCE_COLUMNS` 顺序的 SQL 参数元组。"""
    return tuple(src.get(c) for c in WORD_SOURCE_COLUMNS)


def _wire_to_word_source(it: dict) -> dict:
    """服务端 words[] 项（camelCase）→ 本地出处 dict（列名 + 可空规整）。"""
    return _clean_word_source(
        book_name=it.get("bookName"), book_key=it.get("bookKey"),
        store_book_id=it.get("storeBookId"), segment_number=it.get("segmentNumber"),
        line_number=it.get("lineNumber"), sentence_text=it.get("sentenceText"))


def _row_word_source(r) -> dict:
    """sqlite3.Row → 本地出处 dict（兼容未迁移老库缺列 → None），同样走可空规整。"""
    keys = r.keys() if hasattr(r, "keys") else WORD_SOURCE_COLUMNS
    raw = {c: (r[c] if c in keys else None) for c in WORD_SOURCE_COLUMNS}
    return _clean_word_source(**raw)


def _entry_word_source(entry) -> dict:
    """WordEntry → 本地出处 dict（可空规整）。"""
    raw = {c: getattr(entry, c, None) for c in WORD_SOURCE_COLUMNS}
    return _clean_word_source(**raw)


def _norm_ts(ts: str) -> str:
    """把 'yyyy-MM-ddTHH:mm:ss(.sss)' / 'yyyy-MM-dd HH:mm:ss' 归一为可字典序比较的秒级串。"""
    if not ts:
        return ""
    cleaned = str(ts).replace("T", " ").strip()
    return cleaned[:19]


def _is_server_progress_newer(local_seg: int, local_ts: str,
                              server_seg: int, server_ts: str) -> bool:
    """阅读进度「时间戳新者胜」判定：服务端是否应覆盖本地。

    段号更大者胜（读得更远）；段号相等时比时间戳（服务端 >= 本地即认为服务端更新，
    因为两端读同一段时以最后写入为准）。任一侧缺时间戳时的兜底：
    有段号优势直接判新；段号相等且本地无时间戳、服务端有时间戳 → 服务端胜。
    """
    local_seg = local_seg or 0
    server_seg = server_seg or 0
    if server_seg > local_seg:
        return True
    if server_seg < local_seg:
        return False
    lts, sts = _norm_ts(local_ts), _norm_ts(server_ts)
    if not sts:
        return False
    if not lts:
        return True
    return sts >= lts


class LocalDatabase:
    """线程安全的本地数据库。"""

    def __init__(self, config: AppConfig):
        self._db_path = config.resolved_data_dir / "audiobook_pc.db"
        self._local = threading.local()
        self._init_tables()

    def _conn(self) -> sqlite3.Connection:
        if not hasattr(self._local, "conn") or self._local.conn is None:
            self._local.conn = sqlite3.connect(str(self._db_path), check_same_thread=False)
            self._local.conn.row_factory = sqlite3.Row
            self._local.conn.execute("PRAGMA journal_mode=WAL")
        return self._local.conn

    def close(self):
        """关闭**当前线程**的 SQLite 连接，释放文件句柄。

        主要用途：测试在 TemporaryDirectory 清理前解锁 ``audiobook_pc.db``（Windows 下
        连接不关会锁住文件导致 PermissionError）；也便于应用优雅退出。
        WAL 模式下 close 会顺带把 WAL 落盘。conn 置 None 后 _conn() 会按需重连。
        """
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            try:
                conn.close()
            except sqlite3.Error:
                pass
            self._local.conn = None

    def _init_tables(self):
        c = self._conn()
        c.executescript("""
        CREATE TABLE IF NOT EXISTS books (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            display_name TEXT NOT NULL,
            file_path TEXT NOT NULL,
            file_ext TEXT DEFAULT '',
            has_audio INTEGER DEFAULT 0,
            last_read_at TEXT DEFAULT '',
            category TEXT DEFAULT '',
            sort_order INTEGER DEFAULT 0,
            store_book_id INTEGER,
            author TEXT DEFAULT '',
            description TEXT DEFAULT '',
            cover_url TEXT DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS tasks (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            server_task_id INTEGER,
            book_name TEXT DEFAULT '',
            audio_name TEXT DEFAULT '',
            status TEXT DEFAULT 'pending',
            total_segments INTEGER DEFAULT 0,
            completed_segments INTEGER DEFAULT 0,
            created_at TEXT DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS audio_segments (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id INTEGER NOT NULL,
            segment_number INTEGER NOT NULL,
            text_content TEXT DEFAULT '',
            audio_file_path TEXT DEFAULT '',
            duration_seconds REAL DEFAULT 0,
            status TEXT DEFAULT 'pending',
            voice TEXT DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS reading_progress (
            book_key TEXT PRIMARY KEY,
            segment_number INTEGER DEFAULT 0,
            offset_ms INTEGER DEFAULT 0,
            updated_at TEXT DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS favorites (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            book_id INTEGER DEFAULT 0,
            text TEXT DEFAULT '',
            note TEXT DEFAULT '',
            created_at TEXT DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS word_book (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            word TEXT NOT NULL,
            phonetic TEXT DEFAULT '',
            definition TEXT DEFAULT '',
            created_at TEXT DEFAULT '',
            sw TEXT DEFAULT '',
            server_id INTEGER,
            -- 收藏出处（三端单词本「跳转到原书」）：6 列全部可空。
            -- 老数据 / 旧客户端 / 服务端返回 None 时保持 NULL，UI 据此禁用跳转。
            book_name TEXT,
            book_key TEXT,
            store_book_id INTEGER,
            segment_number INTEGER,
            line_number INTEGER,
            sentence_text TEXT
        );
        CREATE TABLE IF NOT EXISTS audio_coverage (
            book_key TEXT NOT NULL,
            source TEXT NOT NULL,
            store_book_id INTEGER,
            server_task_id INTEGER,
            total_segments INTEGER DEFAULT 0,
            done_ranges TEXT DEFAULT '',
            done_count INTEGER DEFAULT 0,
            updated_at TEXT DEFAULT '',
            PRIMARY KEY (book_key, source)
        );
        """)
        c.commit()
        # 跨端同步需要的增量列（老库补齐；幂等，每次开库都跑一遍）
        self._migrate()

    def _has_column(self, table: str, column: str) -> bool:
        rows = self._conn().execute(f"PRAGMA table_info({table})").fetchall()
        return any(r[1] == column for r in rows)

    def _add_column(self, table: str, column: str, ddl: str):
        if not self._has_column(table, column):
            try:
                c = self._conn()
                c.execute(f"ALTER TABLE {table} ADD COLUMN {column} {ddl}")
                c.commit()
            except sqlite3.OperationalError:
                # 并发/重复补列时列可能已存在，忽略即可
                pass

    def _migrate(self):
        """为既有库补齐跨端同步所需的列（收藏上云身份 + 任务恢复字段 + 书籍云端键）。

        身份口径与服务端 /api/favorites 对齐：收藏以 (book_name, segment_number) 为唯一身份，
        server_id 记录服务端主键（为空=待上传）；task_id 供「去听」深链。
        tasks 补 error_message / progress_text / book_id，供服务端任务恢复与失败原因展示。
        books 补 store_book_id 已存在于建表，这里再补 cloud_added 标记本机从未有文件的云端书。
        """
        c = self._conn()
        # favorites：云端身份与主键
        self._add_column("favorites", "book_name", "TEXT DEFAULT ''")
        self._add_column("favorites", "segment_number", "INTEGER DEFAULT 0")
        self._add_column("favorites", "server_id", "INTEGER")
        self._add_column("favorites", "task_id", "INTEGER")
        self._add_column("favorites", "store_book_id", "INTEGER")
        # 收藏上云去重索引（本机唯一身份=书名+段号+内容）
        if self._has_column("favorites", "book_name"):
            try:
                c.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_fav_book_seg "
                    "ON favorites (book_name, segment_number)")
                c.commit()
            except sqlite3.OperationalError:
                pass
        # tasks：失败原因 / 阶段文案 / 关联本地书籍行
        self._add_column("tasks", "error_message", "TEXT DEFAULT ''")
        self._add_column("tasks", "progress_text", "TEXT DEFAULT ''")
        self._add_column("tasks", "book_id", "INTEGER DEFAULT 0")
        # task_dismissals：记下墓碑属于哪本书。
        # 为什么：移除书架时本地 tasks 行会被删掉，之后就查不到
        # 「这个 server_task_id 属于哪本书」⇒ 用户重新加入该书时清不掉墓碑。
        self._add_column("task_dismissals", "book_name", "TEXT DEFAULT ''")
        # 进度：记录服务端 updatedAt 以便时间戳新者胜比较
        self._add_column("reading_progress", "server_updated_at", "TEXT DEFAULT ''")
        # 单词本：加云端身份列（sw=归一化键唯一身份，server_id=服务端主键），并把唯一键
        # 从旧的 word 迁到 sw（对齐 Android/服务端以 sw 去重）。老库只有 word 列，需回填 sw。
        self._add_column("word_book", "sw", "TEXT DEFAULT ''")
        self._add_column("word_book", "server_id", "INTEGER")
        # 单词本收藏出处：老库补齐 6 列。全部可空（不带 DEFAULT，存量行自然是 NULL），
        # 与 DDL 同名同型；_add_column 幂等，重复开库 / 并发补列都不会报错。
        # ⚠️ 这 6 列是「跳转到原书」的唯一数据来源，reconcile / rebuild 的列白名单
        #    必须同步带上，否则一次服务端同步就会把出处抹成 NULL（跳转按钮随即失效）。
        self._add_column("word_book", "book_name", "TEXT")
        self._add_column("word_book", "book_key", "TEXT")
        self._add_column("word_book", "store_book_id", "INTEGER")
        self._add_column("word_book", "segment_number", "INTEGER")
        self._add_column("word_book", "line_number", "INTEGER")
        self._add_column("word_book", "sentence_text", "TEXT")
        try:
            rows = c.execute(
                "SELECT id, word FROM word_book WHERE sw IS NULL OR sw=''").fetchall()
            for r in rows:
                c.execute("UPDATE word_book SET sw=? WHERE id=?",
                          (_sw_key(r["word"]), r["id"]))
            c.commit()
            # 同一 sw 只留一条：优先已上云(server_id 非空)，其次 created_at 最新，再次 id 最大
            dupes = c.execute(
                "SELECT sw FROM word_book WHERE sw<>'' GROUP BY sw HAVING COUNT(*)>1"
            ).fetchall()
            for d in dupes:
                keep = c.execute(
                    "SELECT id FROM word_book WHERE sw=? "
                    "ORDER BY (CASE WHEN server_id IS NOT NULL THEN 0 ELSE 1 END), "
                    "created_at DESC, id DESC LIMIT 1", (d["sw"],)).fetchone()
                if keep:
                    c.execute("DELETE FROM word_book WHERE sw=? AND id<>?",
                              (d["sw"], keep["id"]))
            c.commit()
            c.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_word_book_sw "
                "ON word_book (sw) WHERE sw <> ''")
            c.commit()
        except sqlite3.OperationalError:
            pass
        # 书架「本地移除」墓碑：云端任务被本机从书架删除后记录其 server_task_id，
        # 同步时据此跳过重新拉回（非破坏式——不删云端任务/音频，只是本机不再显示）。
        c.execute("""CREATE TABLE IF NOT EXISTS task_dismissals (
            server_task_id INTEGER PRIMARY KEY,
            dismissed_at TEXT DEFAULT '',
            book_name TEXT DEFAULT ''
        )""")
        c.commit()
        # 书架「本机已移出」墓碑：书城书被本机从书架移除后记录其 store_book_id，
        # 同步时据此跳过重新拉回。
        # 为什么需要：删除的服务端 DELETE 是**异步**的（要等一个网络往返），
        # 而同步的 get_shelf 快照可能正好取自删除之前 ⇒ upsert 会把刚删的书
        # **复活** ⇒ 用户看到「点了删除没反应」（书闪一下又回来）。
        # 与 task_dismissals 同构：非破坏式——不删云端数据，只是本机不再显示。
        c.execute("""CREATE TABLE IF NOT EXISTS shelf_dismissals (
            store_book_id INTEGER PRIMARY KEY,
            dismissed_at TEXT DEFAULT ''
        )""")
        c.commit()
        # 同步状态 kv（P1 #3：覆盖图 ETag 缓存等）。
        # 一行一个 key，value 是字符串（ETag / 时间戳 / 小 JSON 都能塞）。
        # 与业务表分离：清掉它只影响"下次同步要不要走 304"，绝不丢用户数据。
        c.execute("""CREATE TABLE IF NOT EXISTS sync_state (
            key TEXT PRIMARY KEY,
            value TEXT DEFAULT '',
            updated_at TEXT DEFAULT ''
        )""")
        c.commit()
        # audio_segments：显式 source 列（对齐 Android v12 迁移）。
        # 老库用文件名前缀启发式判源（tts_ → server，local_ → local），但文件名可能被用户重命名，
        # 导致判源错误。显式列更可靠：下载时写 'server'，本机合成时写 'local'。
        # 迁移策略：**仅在本次新建列时**回填存量行；列一旦存在，新行由 insert_segment 显式写
        # source，后续迁移不得再覆盖（否则会冲掉用户/下载写入的权威值）。
        # 回填判源用 os.path.basename 前缀启发式——与读取路径(见 local_audio_coverage)一致，
        # 且跨平台。不用 SQL LIKE：'%/tts_%' 在 Windows 反斜杠路径下匹配不到，是历史 bug 根因。
        # 另一坑：ALTER ADD COLUMN ... DEFAULT 'local' 会把存量行**立即**填成 'local'（非 NULL），
        # 所以不能用 `WHERE source IS NULL OR source=''` 判断"待回填"（永远不命中）——
        # 正确做法是用"列是否刚被新建"作为一次性回填的触发条件。
        _had_source_col = self._has_column("audio_segments", "source")
        self._add_column("audio_segments", "source", "TEXT DEFAULT 'local'")
        if not _had_source_col:
            try:
                rows = c.execute("SELECT id, audio_file_path FROM audio_segments").fetchall()
                updates = []
                for r in rows:
                    fn = os.path.basename((r["audio_file_path"] or "")).lower()
                    src = "server" if fn.startswith("tts_") else "local"
                    updates.append((src, r["id"]))
                if updates:
                    c.executemany("UPDATE audio_segments SET source=? WHERE id=?", updates)
                    c.commit()
            except sqlite3.Error:
                pass
        # 书城书去重：历史 bug 曾让同一 store_book_id 被重复下载插成多行（书架出现重复项）。
        # 保留「有本地文件优先 → 最近阅读优先 → id 最小」的那一行，其余删除；
        # 再建部分唯一索引，从根上杜绝再次插入重复的书城书。
        try:
            dupes = c.execute(
                "SELECT store_book_id FROM books "
                "WHERE store_book_id IS NOT NULL AND store_book_id > 0 "
                "GROUP BY store_book_id HAVING COUNT(*) > 1").fetchall()
            for d in dupes:
                keep = c.execute(
                    "SELECT id FROM books WHERE store_book_id=? "
                    "ORDER BY (CASE WHEN file_path IS NOT NULL AND file_path <> '' "
                    "THEN 0 ELSE 1 END), last_read_at DESC, id ASC LIMIT 1",
                    (d["store_book_id"],)).fetchone()
                if keep:
                    c.execute("DELETE FROM books WHERE store_book_id=? AND id<>?",
                              (d["store_book_id"], keep["id"]))
            c.commit()
            c.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_books_store_book_id "
                "ON books (store_book_id) "
                "WHERE store_book_id IS NOT NULL AND store_book_id > 0")
            c.commit()
        except sqlite3.OperationalError:
            pass
        # audio_segments 唯一键 (task_id, segment_number)：让 insert_segment 的
        # `INSERT OR REPLACE` 真正按「同一任务的同段号」去重（书城音频下载幂等、断点续传
        # 都依赖它）。先收敛历史重复行（有文件优先、id 最小优先），再建唯一索引。
        try:
            dupes = c.execute(
                "SELECT task_id, segment_number FROM audio_segments "
                "GROUP BY task_id, segment_number HAVING COUNT(*) > 1").fetchall()
            for d in dupes:
                keep = c.execute(
                    "SELECT id FROM audio_segments WHERE task_id=? AND segment_number=? "
                    "ORDER BY (CASE WHEN audio_file_path IS NOT NULL AND audio_file_path <> '' "
                    "THEN 0 ELSE 1 END), id ASC LIMIT 1",
                    (d["task_id"], d["segment_number"])).fetchone()
                if keep:
                    c.execute(
                        "DELETE FROM audio_segments WHERE task_id=? AND segment_number=? AND id<>?",
                        (d["task_id"], d["segment_number"], keep["id"]))
            c.commit()
            c.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_audio_segments_task_seg "
                "ON audio_segments (task_id, segment_number)")
            c.commit()
        except sqlite3.OperationalError:
            pass

    # ---------- Books ----------

    def add_book(self, book: Book) -> int:
        c = self._conn()
        cur = c.execute(
            "INSERT INTO books (display_name,file_path,file_ext,has_audio,category,sort_order,store_book_id,author) VALUES (?,?,?,?,?,?,?,?)",
            (book.display_name, book.file_path, book.file_ext, int(book.has_audio),
             book.category, book.sort_order, book.store_book_id, book.author))
        c.commit()
        return cur.lastrowid

    def get_books(self) -> List[Book]:
        rows = self._conn().execute("SELECT * FROM books ORDER BY sort_order DESC, last_read_at DESC").fetchall()
        return [self._row_to_book(r) for r in rows]

    def get_book(self, book_id: int) -> Optional[Book]:
        r = self._conn().execute("SELECT * FROM books WHERE id=?", (book_id,)).fetchone()
        return self._row_to_book(r) if r else None

    def remove_book(self, book_id: int):
        c = self._conn()
        c.execute("DELETE FROM books WHERE id=?", (book_id,))
        c.commit()

    def update_book_sort(self, book_id: int, sort_order: int):
        c = self._conn()
        c.execute("UPDATE books SET sort_order=? WHERE id=?", (sort_order, book_id))
        c.commit()

    def update_book_last_read(self, book_id: int):
        c = self._conn()
        c.execute("UPDATE books SET last_read_at=? WHERE id=?",
                  (time.strftime("%Y-%m-%dT%H:%M:%S"), book_id))
        c.commit()

    def apply_book_meta(self, updates: Dict[int, tuple]) -> int:
        """按 book_id 批量回灌**跨端书籍元数据**（静默，不发任何 UI 信号）。

        ``updates`` 形如 ``{book_id: (sort_order:int, last_read_at_iso:str)}``。
        ``last_read_at`` 仍写本地的 TEXT ISO 串格式 —— 该列还被
        ``ORDER BY sort_order DESC, last_read_at DESC`` 与去重逻辑使用，
        单位转换只在 ``core/sync.py`` 的同步边界做，本地格式一律不动。

        ⛔ 只 UPDATE 这两列：**不** INSERT/DELETE 书籍行（书籍真源是书架同步）、
        **不** 碰 ``category``（PC 该列是文件类型/书城固定值，与 Android 手工分类
        语义不同，跨端同步会互相污染）。
        ⛔ 调用方必须已经把远端 ``bookKey`` 换算成本地 ``book_id``；本方法不认 bookKey。

        :return: 实际更新的行数
        """
        if not updates:
            return 0
        c = self._conn()
        with c:
            c.executemany(
                "UPDATE books SET sort_order=?, last_read_at=? WHERE id=?",
                [(int(sort_order), str(last_read_at or ""), int(book_id))
                 for book_id, (sort_order, last_read_at) in updates.items()])
        return len(updates)

    def update_book_has_audio(self, book_id: int, has_audio: bool):
        c = self._conn()
        c.execute("UPDATE books SET has_audio=? WHERE id=?", (int(has_audio), book_id))
        c.commit()

    def _row_to_book(self, r) -> Book:
        return Book(id=r["id"], display_name=r["display_name"], file_path=r["file_path"],
                    file_ext=r["file_ext"], has_audio=bool(r["has_audio"]),
                    last_read_at=r["last_read_at"], category=r["category"],
                    sort_order=r["sort_order"], store_book_id=r["store_book_id"],
                    author=r["author"] or "")

    def get_book_by_store_id(self, store_book_id: int) -> Optional[Book]:
        if not store_book_id:
            return None
        r = self._conn().execute("SELECT * FROM books WHERE store_book_id=?",
                                 (store_book_id,)).fetchone()
        return self._row_to_book(r) if r else None

    def find_store_book_id_by_name(self, book_name: str) -> Optional[int]:
        """按书名反查书城身份（收藏跳转兜底：服务端收藏不存 store_book_id）。

        仅在命中**唯一**一条 display_name 完全相等的书城书（store_book_id>0）时返回，
        避免同名书误跳。返回 None 表示不确定。
        """
        if not book_name:
            return None
        rows = self._conn().execute(
            "SELECT store_book_id FROM books "
            "WHERE store_book_id IS NOT NULL AND store_book_id > 0 AND display_name=?",
            (book_name,)).fetchall()
        if len(rows) == 1 and rows[0]["store_book_id"]:
            return int(rows[0]["store_book_id"])
        return None

    def upsert_store_shelf_book(self, store_book_id: int, title: str, author: str = "",
                                category: str = "", has_audio: bool = False) -> int:
        """云端书架项 → 本地 books 表（跨端书架共享写入口，身份=store_book_id）。

        已存在：只刷新 author/category/has_audio 这类**服务端权威**元信息，
        不动 display_name/file_path（用户可能已改过名 / 已下载文件到本地）。
        不存在：建一条占位行（category='store'、file_path 空、排在最前），
        阅读时走 store 正文接口按 store_book_id 加载，无需本地文件。
        返回本地行 id（0=失败）。

        ⚠️ **category 必须两分支同源**：
        早先 INSERT 分支硬编码 ``'store'`` 而把入参丢弃，UPDATE 分支却写入
        服务端的分类（如 '军事'/'编程'/'其他'）。于是同一本书**首次**同步落库
        category='store'、**再次**同步后变成服务端分类 ⇒ 本地分类字段在两次
        同步之间静默漂移，按 category 做的筛选/展示会前后不一致。
        现在两分支统一写入**传入的** category（入参为空才回落 'store'）。
        """
        if not store_book_id:
            return 0
        # 两分支同源：优先用调用方给的（服务端权威分类），为空才回落 'store'
        cat = category or "store"
        c = self._conn()
        existing = c.execute("SELECT id FROM books WHERE store_book_id=?",
                             (store_book_id,)).fetchone()
        if existing:
            c.execute("UPDATE books SET author=?, category=?, has_audio=? WHERE id=?",
                      (author or "", cat, int(bool(has_audio)), existing["id"]))
            c.commit()
            return existing["id"]
        top = c.execute("SELECT COALESCE(MAX(sort_order),0)+1 FROM books").fetchone()[0]
        cur = c.execute(
            "INSERT INTO books (display_name,file_path,file_ext,has_audio,category,sort_order,"
            "store_book_id,author) VALUES (?,?,?,?,?,?,?,?)",
            (title or "未命名", "", "", int(bool(has_audio)), cat, top,
             store_book_id, author or ""))
        c.commit()
        return cur.lastrowid

    def update_store_book_file(self, store_book_id: int, file_path: str, file_ext: str,
                               has_audio: bool, author: str = "") -> bool:
        """书城书占位行（无本地文件）下载后回填文件信息，**不新建行**（避免书架重复项）。

        身份=store_book_id。命中已存在行则更新 file_path/file_ext/has_audio/author，
        不动 display_name/category（用户可能已改过名）；未命中返回 False（调用方需自行插入）。
        """
        if not store_book_id:
            return False
        c = self._conn()
        cur = c.execute(
            "UPDATE books SET file_path=?, file_ext=?, has_audio=?, author=? "
            "WHERE store_book_id=?",
            (file_path or "", file_ext or "", int(bool(has_audio)), author or "",
             store_book_id))
        c.commit()
        return cur.rowcount > 0

    # ---------- Tasks ----------

    def upsert_task(self, task: Task) -> int:
        c = self._conn()
        existing = c.execute("SELECT id FROM tasks WHERE server_task_id=?", (task.server_task_id,)).fetchone()
        if existing:
            c.execute("UPDATE tasks SET status=?,completed_segments=?,total_segments=? WHERE id=?",
                      (task.status, task.completed_segments, task.total_segments, existing["id"]))
            return existing["id"]
        cur = c.execute(
            "INSERT INTO tasks (server_task_id,book_name,audio_name,status,total_segments,completed_segments,created_at) VALUES (?,?,?,?,?,?,?)",
            (task.server_task_id, task.book_name, task.audio_name, task.status,
             task.total_segments, task.completed_segments, time.strftime("%Y-%m-%dT%H:%M:%S")))
        c.commit()
        return cur.lastrowid

    def get_tasks(self) -> List[Task]:
        rows = self._conn().execute("SELECT * FROM tasks ORDER BY created_at DESC").fetchall()
        out: List[Task] = []
        for r in rows:
            keys = r.keys()
            out.append(Task(id=r["id"], server_task_id=r["server_task_id"], book_name=r["book_name"],
                            audio_name=r["audio_name"], status=r["status"],
                            total_segments=r["total_segments"], completed_segments=r["completed_segments"],
                            created_at=r["created_at"],
                            error_message=(r["error_message"] if "error_message" in keys else "") or "",
                            progress_text=(r["progress_text"] if "progress_text" in keys else "") or ""))
        return out

    def upsert_server_task(self, server_task_id: int, book_name: str, status: str,
                           total_segments: int, completed_segments: int,
                           error_message: str = "", created_at: str = "") -> int:
        """服务端任务 → 本地 tasks 表的合并写（跨端恢复唯一写入口）。

        按 server_task_id 匹配：已存在只补状态相关列（status/completed/total/error_message），
        **不动** book_name/audio_name（本地可能改过名、指定过音色）；
        不存在则插一行占位任务，audio_name 记「云端任务」，task id 用 -server_task_id 占位（唯一且不与本地 rowid 冲突）。
        返回本地行 id（0=未变/跳过）。
        """
        if not server_task_id:
            return 0
        c = self._conn()
        existing = c.execute("SELECT id,status,total_segments,completed_segments,error_message "
                             "FROM tasks WHERE server_task_id=?", (server_task_id,)).fetchone()
        if existing:
            if (existing["status"] == status and existing["total_segments"] == total_segments
                    and existing["completed_segments"] == completed_segments
                    and (existing["error_message"] or "") == (error_message or "")):
                return 0  # 无变化不写，避免每次同步触发刷新闪烁
            c.execute("UPDATE tasks SET status=?,completed_segments=?,total_segments=?,error_message=? "
                      "WHERE server_task_id=?",
                      (status, completed_segments, total_segments, error_message or "", server_task_id))
            c.commit()
            return existing["id"]
        cur = c.execute(
            "INSERT INTO tasks (server_task_id,book_name,audio_name,status,total_segments,"
            "completed_segments,created_at,error_message) VALUES (?,?,?,?,?,?,?,?)",
            (server_task_id, book_name or "未命名书籍", "云端任务", status,
             total_segments, completed_segments, created_at or "", error_message or ""))
        c.commit()
        return cur.lastrowid

    def update_task_status(self, task_id: int, status: str, completed: int = None):
        c = self._conn()
        if completed is not None:
            c.execute("UPDATE tasks SET status=?,completed_segments=? WHERE id=?", (status, completed, task_id))
        else:
            c.execute("UPDATE tasks SET status=? WHERE id=?", (status, task_id))
        c.commit()

    def update_task_progress_text(self, task_id: int, text: str):
        """写任务行的阶段文案（progress_text）。

        用途：本机合成**暂停**时写「已暂停」，恢复时写空串。
        ⚠️ 刻意**不碰 status**：暂停是**内存闸门**（合成线程还活着、挂在
        await_resume 上），任务仍在进行中 —— 若把 status 也改成 paused，
        就会与「被中止后落 paused」的那条路径混成同一个状态，
        两者的续跑语义完全不同（详见 LocalTtsService.synthesize_book 收尾处注释）。
        对齐安卓：LocalSynthesisService 暂停时也只写 progress_text。
        """
        c = self._conn()
        c.execute("UPDATE tasks SET progress_text=? WHERE id=?", (text or "", task_id))
        c.commit()

    def upsert_local_task(self, book_name: str, audio_name: str) -> int:
        """开始一轮本机合成时登记任务行：**同一本书复用已有的行，不新建**。

        【为什么不能无条件 INSERT —— 断点续跑会失效】
        本机合成没有 server_task_id（它不是服务端产生的），而 ``upsert_task``
        是按 ``server_task_id`` 查旧行的；传 NULL 时 SQL 的 ``= NULL`` **永不命中**
        ⇒ 每开跑一次就多一行任务行。
        后果不只是显示乱：合成进度写的是**新建的那一行**，而断点续跑靠的是
        「同一 task_id 下已 completed 且音频文件还在」的段 ⇒ 段落在旧行上，
        新行一段都没有 ⇒ 每次「停止 → 重新开始」都**整本重合成**，
        已合成好的几千段全部白跑。对齐安卓 ``TaskRepository.upsertLocalTask``。

        【复用条件】``book_name`` + ``audio_name`` 相同（即同一本书的同一种合成方式）
        且状态属于「未终结」：processing / partial / failed / paused。
        ``completed`` 不复用 —— 那是已完成的历史记录。

        复用时**保留原有的 completed_segments**：那是已落盘的段数，
        重置成 0 会让用户「已完成 27 段」瞬间变「0 段」，看着像倒退。

        :return: 实际使用的 task_id（复用时是旧行的 id）。
        """
        c = self._conn()
        row = c.execute(
            "SELECT id, completed_segments FROM tasks "
            "WHERE book_name=? AND audio_name=? AND status IN (?,?,?,?) "
            "ORDER BY id DESC LIMIT 1",
            (book_name, audio_name, "processing", "partial", "failed", "paused"),
        ).fetchone()
        if row:
            reused_id = row["id"]
            kept_done = row["completed_segments"]
            # 状态回到 processing，进度数字**保持** kept_done 不动。
            c.execute(
                "UPDATE tasks SET status=?, completed_segments=?, server_task_id=NULL "
                "WHERE id=?",
                ("processing", kept_done, reused_id),
            )
            c.commit()
            return reused_id
        cur = c.execute(
            "INSERT INTO tasks (server_task_id,book_name,audio_name,status,total_segments,"
            "completed_segments,created_at) VALUES (?,?,?,?,?,?,?)",
            (None, book_name, audio_name, "processing", 0, 0,
             time.strftime("%Y-%m-%dT%H:%M:%S")),
        )
        c.commit()
        return cur.lastrowid

    def get_task(self, task_id: int) -> Optional[Task]:
        r = self._conn().execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not r:
            return None
        keys = r.keys()
        return Task(id=r["id"], server_task_id=r["server_task_id"], book_name=r["book_name"],
                    audio_name=r["audio_name"], status=r["status"],
                    total_segments=r["total_segments"], completed_segments=r["completed_segments"],
                    created_at=r["created_at"],
                    error_message=(r["error_message"] if "error_message" in keys else "") or "",
                    progress_text=(r["progress_text"] if "progress_text" in keys else "") or "")

    def delete_task(self, task_id: int):
        """删除本地任务行及其段记录（书架上「仅任务、无本地书」的虚拟项的删除落点）。

        仅清本地；云端任务的服务端删除由调用方 best-effort 处理。
        """
        if not task_id:
            return
        c = self._conn()
        c.execute("DELETE FROM audio_segments WHERE task_id=?", (task_id,))
        c.execute("DELETE FROM tasks WHERE id=?", (task_id,))
        c.commit()

    def dismiss_task(self, server_task_id: int, book_name: str = ""):
        """标记某云端任务「本机已从书架移除」，同步时不再拉回（不删云端数据）。

        :param book_name: 任务所属的书名。**必须记下来** ——
            移除书架时本地 tasks 行会被一并删掉，之后就再也查不到
            「这个 server_task_id 属于哪本书」⇒ 用户重新加入该书时
            无法清掉它的任务墓碑 ⇒ 书回来了却显示「未合成」。
        """
        if not server_task_id:
            return
        c = self._conn()
        c.execute("INSERT OR REPLACE INTO task_dismissals "
                  "(server_task_id, dismissed_at, book_name) VALUES (?,?,?)",
                  (int(server_task_id), time.strftime("%Y-%m-%dT%H:%M:%S"),
                   book_name or ""))
        c.commit()

    def is_task_dismissed(self, server_task_id: int) -> bool:
        if not server_task_id:
            return False
        r = self._conn().execute(
            "SELECT 1 FROM task_dismissals WHERE server_task_id=?", (int(server_task_id),)).fetchone()
        return r is not None

    def list_dismissed_task_ids(self) -> set:
        rows = self._conn().execute("SELECT server_task_id FROM task_dismissals").fetchall()
        return {r["server_task_id"] for r in rows}

    def clear_task_dismissals_for_book(self, book_name: str):
        """清掉某本书名下所有云端任务的「本机已移出」墓碑。

        用户**重新加入**这本书时调用：墓碑只是「本机暂时不要它」的标记，
        留着会让这本书的合成任务永远拉不回来（书回来了但显示「未合成」）。

        ⛔ **不能靠 ``tasks`` 表反查 server_task_id**：移除书架时本地 tasks 行
        已随书一起删掉 ⇒ 反查必然落空 ⇒ 墓碑永远清不掉。
        改用墓碑表自己记录的 ``book_name``（见 :meth:`dismiss_task`）。
        """
        if not book_name:
            return
        c = self._conn()
        # 墓碑表里记了书名 ⇒ 直接按书名清（老库没记书名的条目无法清，
        # 但那种条目是旧版本留下的，且它们对应的 tasks 行还在，仍走下面的兜底）。
        c.execute("DELETE FROM task_dismissals WHERE book_name=?", (book_name,))
        # 兜底：tasks 行还在的老墓碑（book_name 为空）按 tasks 表反查清掉。
        ids = [r["server_task_id"] for r in c.execute(
            "SELECT server_task_id FROM tasks WHERE book_name=? AND server_task_id IS NOT NULL",
            (book_name,)).fetchall()]
        for sid in ids:
            c.execute("DELETE FROM task_dismissals WHERE server_task_id=?", (sid,))
        c.commit()

    # ---------- 书架「本机已移出」墓碑（书城书） ----------

    #: 墓碑有效期（秒）。过期即视为失效并自动清理。
    #: 取值依据：移出书架时服务端 DELETE 走异步（一个网络往返），
    #: 而并发同步的 get_shelf 快照可能取自删除之前 ⇒ 需要一个**短窗口**
    #: 让同步跳过这本书。窗口过后服务端早已删除，同步自然不会再拉回来。
    #: ⛔ 不能永久保留：否则用户在别的设备重新加入这本书后，本机永远看不到它。
    SHELF_DISMISSAL_TTL_SECONDS = 120

    def dismiss_store_book(self, store_book_id: int):
        """标记某书城书「本机已从书架移出」，同步时不再拉回（不删云端数据）。

        背景：移出书架的服务端 DELETE 是**异步**的，而同步的 get_shelf 快照
        可能取自删除之前 ⇒ upsert 会把刚删的书复活。没有墓碑就会出现
        「点了删除，书闪一下又回来」。
        """
        if not store_book_id:
            return
        c = self._conn()
        c.execute("INSERT OR REPLACE INTO shelf_dismissals (store_book_id, dismissed_at) VALUES (?,?)",
                  (int(store_book_id), time.strftime("%Y-%m-%dT%H:%M:%S")))
        c.commit()

    def is_store_book_dismissed(self, store_book_id: int) -> bool:
        if not store_book_id:
            return False
        r = self._conn().execute(
            "SELECT 1 FROM shelf_dismissals WHERE store_book_id=?", (int(store_book_id),)).fetchone()
        return r is not None

    def list_dismissed_store_book_ids(self) -> set:
        """当前**有效**的墓碑 id 集合（过期的不算，并顺手清掉）。

        过期墓碑必须失效：它只是用来覆盖「删除的异步窗口」，
        长期有效会让用户在别处重新加入的书在本机永远消失。
        """
        cutoff = time.time() - self.SHELF_DISMISSAL_TTL_SECONDS
        out = set()
        expired = []
        for row in self._conn().execute(
                "SELECT store_book_id, dismissed_at FROM shelf_dismissals").fetchall():
            sid = row["store_book_id"]
            try:
                ts = time.mktime(time.strptime(row["dismissed_at"], "%Y-%m-%dT%H:%M:%S"))
            except Exception:  # noqa: BLE001 —— 时间戳坏了就当作过期
                ts = 0
            if ts >= cutoff:
                out.add(sid)
            else:
                expired.append(sid)
        if expired:
            c = self._conn()
            for sid in expired:
                c.execute("DELETE FROM shelf_dismissals WHERE store_book_id=?", (sid,))
            c.commit()
        return out

    def clear_store_book_dismissal(self, store_book_id: int):
        """清掉墓碑——用户**重新加入**该书时调用，否则它永远不会被同步拉回来。"""
        if not store_book_id:
            return
        c = self._conn()
        c.execute("DELETE FROM shelf_dismissals WHERE store_book_id=?", (int(store_book_id),))
        c.commit()

    # ---------- Segments ----------

    def insert_segment(self, seg: Segment):
        c = self._conn()
        # source 列由 _migrate 通过 _add_column 添加；老 DB 未迁移时该列不存在，
        # 退化写 7 列（与迁移前行为一致，避免崩溃）。
        src = getattr(seg, "source", "local") or "local"
        if src not in ("local", "server"):
            src = "local"
        if self._has_column("audio_segments", "source"):
            c.execute("""INSERT OR REPLACE INTO audio_segments
                         (task_id,segment_number,text_content,audio_file_path,duration_seconds,status,voice,source)
                         VALUES (?,?,?,?,?,?,?,?)""",
                      (seg.task_id, seg.segment_number, seg.text_content,
                       seg.audio_file_path, seg.duration_seconds, seg.status, seg.voice, src))
        else:
            c.execute("""INSERT OR REPLACE INTO audio_segments
                         (task_id,segment_number,text_content,audio_file_path,duration_seconds,status,voice)
                         VALUES (?,?,?,?,?,?,?)""",
                      (seg.task_id, seg.segment_number, seg.text_content,
                       seg.audio_file_path, seg.duration_seconds, seg.status, seg.voice))
        c.commit()

    @staticmethod
    def _row_source(r) -> str:
        """从 sqlite3.Row 里安全读出 source，未迁移的老 DB / NULL / 空串 → 'local'。"""
        try:
            keys = r.keys()
        except Exception:  # noqa: BLE001
            return "local"
        if "source" not in keys:
            return "local"
        v = r["source"]
        return v if v in ("local", "server") else "local"

    def get_segments(self, task_id: int) -> List[Segment]:
        rows = self._conn().execute(
            "SELECT * FROM audio_segments WHERE task_id=? ORDER BY segment_number", (task_id,)).fetchall()
        return [Segment(id=r["id"], task_id=r["task_id"], segment_number=r["segment_number"],
                        text_content=r["text_content"], audio_file_path=r["audio_file_path"],
                        duration_seconds=r["duration_seconds"], status=r["status"], voice=r["voice"],
                        source=self._row_source(r))
                for r in rows]

    def get_segment(self, task_id: int, segment_number: int) -> Optional[Segment]:
        r = self._conn().execute(
            "SELECT * FROM audio_segments WHERE task_id=? AND segment_number=?",
            (task_id, segment_number)).fetchone()
        if not r:
            return None
        return Segment(id=r["id"], task_id=r["task_id"], segment_number=r["segment_number"],
                       text_content=r["text_content"], audio_file_path=r["audio_file_path"],
                       duration_seconds=r["duration_seconds"], status=r["status"], voice=r["voice"],
                       source=self._row_source(r))

    # ---------- Progress ----------

    def save_progress(self, book_key: str, seg_num: int, offset_ms: int):
        c = self._conn()
        # 保留既有 server_updated_at（若有），只更新本机进度与本地时间戳
        prev = c.execute("SELECT server_updated_at FROM reading_progress WHERE book_key=?",
                         (book_key,)).fetchone()
        sua = prev["server_updated_at"] if prev and "server_updated_at" in prev.keys() else ""
        c.execute("""INSERT OR REPLACE INTO reading_progress
                     (book_key,segment_number,offset_ms,updated_at,server_updated_at)
                     VALUES (?,?,?,?,?)""",
                  (book_key, seg_num, offset_ms, time.strftime("%Y-%m-%dT%H:%M:%S"), sua or ""))
        c.commit()

    def get_progress(self, book_key: str) -> ReadingProgress:
        r = self._conn().execute("SELECT * FROM reading_progress WHERE book_key=?", (book_key,)).fetchone()
        if not r:
            return ReadingProgress()
        keys = r.keys()
        return ReadingProgress(segment_number=r["segment_number"], offset_ms=r["offset_ms"],
                               updated_at=(r["updated_at"] if "updated_at" in keys else "") or "",
                               server_updated_at=(r["server_updated_at"] if "server_updated_at" in keys else "") or "")

    def apply_server_progress(self, book_key: str, seg_num: int, offset_ms: int,
                              server_updated_at: str = "") -> bool:
        """服务端进度覆盖本地，仅在服务端更新时（段号更大 / 时间戳更新）才应用（时间戳新者胜）。"""
        c = self._conn()
        r = c.execute("SELECT segment_number, updated_at FROM reading_progress WHERE book_key=?",
                      (book_key,)).fetchone()
        if r:
            local_seg = r["segment_number"] or 0
            local_ts = (r["updated_at"] if "updated_at" in r.keys() else "") or ""
            server_newer = _is_server_progress_newer(local_seg, local_ts, seg_num, server_updated_at)
            if not server_newer:
                return False
        c.execute("""INSERT OR REPLACE INTO reading_progress
                     (book_key,segment_number,offset_ms,updated_at,server_updated_at)
                     VALUES (?,?,?,?,?)""",
                  (book_key, seg_num, offset_ms, time.strftime("%Y-%m-%dT%H:%M:%S"),
                   server_updated_at or ""))
        c.commit()
        return True

    def list_all_progress(self) -> List[dict]:
        rows = self._conn().execute("SELECT * FROM reading_progress").fetchall()
        out: List[dict] = []
        for r in rows:
            keys = r.keys()
            out.append({
                "book_key": r["book_key"],
                "segment_number": r["segment_number"] or 0,
                "offset_ms": r["offset_ms"] or 0,
                "updated_at": (r["updated_at"] if "updated_at" in keys else "") or "",
                "server_updated_at": (r["server_updated_at"] if "server_updated_at" in keys else "") or "",
            })
        return out

    # ---------- Favorites ----------

    def _row_to_favorite(self, r) -> Favorite:
        keys = r.keys()
        return Favorite(
            id=r["id"], book_id=r["book_id"] if "book_id" in keys else 0,
            text=r["text"], note=r["note"] if "note" in keys else "",
            created_at=r["created_at"],
            book_name=(r["book_name"] if "book_name" in keys else "") or "",
            segment_number=(r["segment_number"] if "segment_number" in keys else 0) or 0,
            server_id=(r["server_id"] if "server_id" in keys else None),
            task_id=(r["task_id"] if "task_id" in keys else None),
            store_book_id=(r["store_book_id"] if "store_book_id" in keys else None),
        )

    def add_favorite(self, fav: Favorite) -> int:
        """新增/更新一条收藏。身份=(book_name, segment_number)，与该唯一索引冲突时合并。

        text 作为正文镜像（旧字段），note 本地批注保留；server_id 为空表示待上云，
        已有行若已带 server_id 则沿用（避免本机重复收藏把云端主键清掉）。
        """
        c = self._conn()
        now = time.strftime("%Y-%m-%dT%H:%M:%S")
        existing = None
        if fav.book_name and fav.segment_number:
            existing = c.execute(
                "SELECT id, server_id FROM favorites WHERE book_name=? AND segment_number=?",
                (fav.book_name, fav.segment_number)).fetchone()
        if existing:
            server_id = existing["server_id"] if "server_id" in existing.keys() else None
            c.execute(
                "UPDATE favorites SET book_id=?, text=?, note=?, task_id=?, store_book_id=? "
                "WHERE id=?",
                (fav.book_id, fav.text, fav.note, fav.task_id, fav.store_book_id, existing["id"]))
            c.commit()
            return existing["id"]
        cur = c.execute(
            "INSERT INTO favorites (book_id,text,note,created_at,book_name,segment_number,"
            "server_id,task_id,store_book_id) VALUES (?,?,?,?,?,?,?,?,?)",
            (fav.book_id, fav.text, fav.note, now, fav.book_name or "", fav.segment_number or 0,
             None, fav.task_id, fav.store_book_id))
        c.commit()
        return cur.lastrowid

    def list_favorites(self) -> List[Favorite]:
        rows = self._conn().execute("SELECT * FROM favorites ORDER BY created_at DESC").fetchall()
        return [self._row_to_favorite(r) for r in rows]

    def pending_upload_favorites(self) -> List[Favorite]:
        """本机新增、尚未上云（server_id 为空）的收藏（首次迁移 / 离线补传用）。"""
        rows = self._conn().execute(
            "SELECT * FROM favorites WHERE server_id IS NULL ORDER BY created_at DESC").fetchall()
        return [self._row_to_favorite(r) for r in rows]

    def get_favorites(self, book_id: int = 0) -> List[Favorite]:
        c = self._conn()
        if book_id:
            rows = c.execute("SELECT * FROM favorites WHERE book_id=? ORDER BY created_at DESC",
                             (book_id,)).fetchall()
        else:
            rows = c.execute("SELECT * FROM favorites ORDER BY created_at DESC").fetchall()
        return [self._row_to_favorite(r) for r in rows]

    def get_favorites_by_name(self, book_name: str) -> List[Favorite]:
        """按书名取收藏（跨端身份口径：书名，与 book_id 无关）。"""
        if not book_name:
            return self.get_favorites(0)
        rows = self._conn().execute(
            "SELECT * FROM favorites WHERE book_name=? ORDER BY created_at DESC",
            (book_name,)).fetchall()
        return [self._row_to_favorite(r) for r in rows]

    def set_favorite_server_id(self, local_id: int, server_id: int, task_id: int = None):
        c = self._conn()
        if task_id is not None:
            c.execute("UPDATE favorites SET server_id=?, task_id=? WHERE id=?",
                      (server_id, task_id, local_id))
        else:
            c.execute("UPDATE favorites SET server_id=? WHERE id=?", (server_id, local_id))
        c.commit()

    def delete_favorite(self, fav_id: int):
        c = self._conn()
        c.execute("DELETE FROM favorites WHERE id=?", (fav_id,))
        c.commit()

    def delete_favorite_by_server_id(self, server_id: int) -> bool:
        c = self._conn()
        cur = c.execute("DELETE FROM favorites WHERE server_id=?", (server_id,))
        c.commit()
        return cur.rowcount > 0

    def rebuild_favorites_from_server(self, items: List[dict]) -> int:
        """用服务端全量列表重建本地收藏表（单一真源，镜像 Android FavoriteRepository）。

        事务内：清空本地 favorites → 按服务端每一项插入（server_id=服务端 id）。
        调用方须先做「待上传项是否都在返回列表」的安全护栏，确认无误再调用本方法。
        item 字段：id / bookName / segmentNumber / content / createdAt / taskId。
        返回写入条数。
        """
        c = self._conn()
        try:
            # 只清「云端可管理」的行：已带 server_id，或有云端身份(book_name+段号)因而必然经过上传/护栏。
            # 保留本机遗留、无云端身份(book_name 为空)的老收藏——它们传不上服务端，若一并删就是丢数据。
            c.execute("DELETE FROM favorites "
                      "WHERE server_id IS NOT NULL "
                      "OR (book_name IS NOT NULL AND book_name <> '')")
            n = 0
            for it in items or []:
                book_name = it.get("bookName") or ""
                seg = it.get("segmentNumber") or 0
                content = it.get("content") or ""
                if not book_name or not seg or not content:
                    continue
                c.execute(
                    "INSERT INTO favorites (book_id,text,note,created_at,book_name,segment_number,"
                    "server_id,task_id) VALUES (?,?,?,?,?,?,?,?)",
                    (0, content, "", it.get("createdAt") or "", book_name, seg,
                     it.get("id"), it.get("taskId")))
                n += 1
            c.commit()
            return n
        except Exception:
            try:
                c.rollback()
            except Exception:  # noqa: BLE001
                pass
            raise

    def reconcile_favorites_from_server(self, items: List[dict]) -> Dict[str, int]:
        """按项对账（P1 #4：取代 rebuild + 全或无 guard）。

        与 :meth:`rebuild_favorites_from_server` 的区别：不再"先清空再插入"，而是逐行判定，
        因而**单个待上传项未被服务端确认时不会阻塞其它项的回填 / 服务端删除的镜像**。

        身份 = (book_name, segment_number)。算法：

        1. 遍历服务端 items：按身份 upsert 到本地
           - 命中本地行 → UPDATE 内容/created_at/server_id/task_id（服务端权威）；
             若本地原本是待上传（server_id IS NULL），此步同时完成 server_id 回填
           - 未命中 → INSERT 新行（book_id=0，note=''）
        2. 遍历本地"云端可管理"行（book_name 非空）中未被 items 覆盖到的：
           - server_id IS NOT NULL → DELETE（服务端已删/账号切换/下架，本地跟随）
           - server_id IS NULL → **保留原样**（未确认的待上传项，下次同步再试；这就是
             旧 guard 想护住的东西，现在按项处理而不是全或无）
        3. 本地 book_name 为空的遗留行**完全不动**（无云端身份，不参与同步）

        返回：``{"inserted": n, "updated": n, "deleted": n, "pending_missing": n}``
        —— 供 sync 层记录 / UI 展示，不作为阻塞条件。
        """
        c = self._conn()
        stats = {"inserted": 0, "updated": 0, "deleted": 0, "pending_missing": 0}
        try:
            # 1) 服务端 items 索引 + upsert
            server_map: Dict[tuple, dict] = {}
            for it in items or []:
                bn = (it.get("bookName") or "").strip()
                seg = int(it.get("segmentNumber") or 0)
                content = it.get("content") or ""
                if not bn or not seg or not content:
                    continue
                server_map[(bn, seg)] = it

            for (bn, seg), it in server_map.items():
                existing = c.execute(
                    "SELECT id FROM favorites WHERE book_name=? AND segment_number=?",
                    (bn, seg)).fetchone()
                if existing:
                    c.execute(
                        "UPDATE favorites SET text=?, created_at=?, server_id=?, task_id=? "
                        "WHERE id=?",
                        (it.get("content") or "", it.get("createdAt") or "",
                         it.get("id"), it.get("taskId"), existing["id"]))
                    stats["updated"] += 1
                else:
                    c.execute(
                        "INSERT INTO favorites (book_id,text,note,created_at,book_name,"
                        "segment_number,server_id,task_id) VALUES (?,?,?,?,?,?,?,?)",
                        (0, it.get("content") or "", "", it.get("createdAt") or "",
                         bn, seg, it.get("id"), it.get("taskId")))
                    stats["inserted"] += 1

            # 2) 本地"云端可管理"行中未被服务端覆盖的：已上云 → 删；待上传 → 保留
            local_rows = c.execute(
                "SELECT id, book_name, segment_number, server_id FROM favorites "
                "WHERE book_name IS NOT NULL AND book_name <> ''").fetchall()
            for r in local_rows:
                key = ((r["book_name"] or "").strip(), int(r["segment_number"] or 0))
                if key in server_map:
                    continue
                if r["server_id"] is not None:
                    c.execute("DELETE FROM favorites WHERE id=?", (r["id"],))
                    stats["deleted"] += 1
                else:
                    stats["pending_missing"] += 1

            c.commit()
            return stats
        except Exception:
            try:
                c.rollback()
            except Exception:  # noqa: BLE001
                pass
            raise

    # ---------- Word Book ----------

    def add_word(self, entry: WordEntry) -> int:
        """新增/更新一条生词收藏。唯一身份 = sw（归一化键，对齐 Android/服务端）。

        已存在同 sw：更新音标/释义快照，并按「最近一次带出处的收藏」刷新出处 6 列，
        但**保留 server_id**（本机重复收藏不该把云端主键清空，否则会被同步当成"待上传"
        重复上云）。本次不带出处（旧路径 / 无上下文）时不动出处列，避免把已有跳转信息
        抹成 NULL。不存在则插入、server_id 置空待同步补传。
        老库仍带 word 列唯一约束，插入撞约束时降级为按 sw 更新（同样刷新出处）。
        """
        c = self._conn()
        sw = entry.sw or _sw_key(entry.word)
        entry.sw = sw
        now = time.strftime("%Y-%m-%dT%H:%M:%S")
        src = _entry_word_source(entry)
        has_src = _word_source_has_any(src)
        src_vals = _word_source_tuple(src)
        existing = None
        if sw:
            existing = c.execute(
                "SELECT id FROM word_book WHERE sw=?", (sw,)).fetchone()
        if existing:
            if has_src:
                c.execute(
                    "UPDATE word_book SET word=?, phonetic=?, definition=?, "
                    + _WORD_SOURCE_SET + " WHERE id=?",
                    (entry.word, entry.phonetic, entry.definition,
                     *src_vals, existing["id"]))
            else:
                c.execute("UPDATE word_book SET word=?, phonetic=?, definition=? WHERE id=?",
                          (entry.word, entry.phonetic, entry.definition, existing["id"]))
            c.commit()
            return existing["id"]
        try:
            cur = c.execute(
                "INSERT INTO word_book (word,phonetic,definition,created_at,sw,server_id,"
                + _WORD_SOURCE_COLS + ") "
                "VALUES (?,?,?,?,?,?," + _WORD_SOURCE_QMARKS + ")",
                (entry.word, entry.phonetic, entry.definition, now, sw, None, *src_vals))
            c.commit()
            return cur.lastrowid
        except sqlite3.IntegrityError:
            # 老库 word 列唯一约束：同词已存在则改走按 sw/word 更新（同样按带出处才覆盖）
            if has_src:
                c.execute(
                    "UPDATE word_book SET phonetic=?, definition=?, sw=?, "
                    + _WORD_SOURCE_SET + " WHERE word=? OR (sw<>'' AND sw=?)",
                    (entry.phonetic, entry.definition, sw, *src_vals, entry.word, sw))
            else:
                c.execute(
                    "UPDATE word_book SET phonetic=?, definition=?, sw=? "
                    "WHERE word=? OR (sw<>'' AND sw=?)",
                    (entry.phonetic, entry.definition, sw, entry.word, sw))
            c.commit()
            r = c.execute("SELECT id FROM word_book WHERE word=? OR sw=? LIMIT 1",
                          (entry.word, sw)).fetchone()
            return r["id"] if r else 0

    def get_words(self) -> List[WordEntry]:
        # SELECT * 覆盖出处 6 列（DDL / 迁移保证列存在）；_row_to_word 是唯一的行→模型
        # 解析点，出处白名单在那里统一扩，UI 的 _load_words 与同步的 list_pending_words
        # 都自动拿到完整出处，不会漏列。
        rows = self._conn().execute(
            "SELECT * FROM word_book ORDER BY created_at DESC, id DESC").fetchall()
        return [self._row_to_word(r) for r in rows]

    def _row_to_word(self, r) -> WordEntry:
        keys = r.keys()
        return WordEntry(
            word=r["word"], phonetic=r["phonetic"], definition=r["definition"],
            created_at=r["created_at"] or "",
            sw=(r["sw"] if "sw" in keys else "") or "",
            server_id=(r["server_id"] if "server_id" in keys else None),
            # 老库未迁移时 6 列不存在 → _row_word_source 逐键回落 None，不抛异常。
            **_row_word_source(r))

    def is_word_saved(self, word_or_sw: str) -> bool:
        """查该词（按 sw 归一）是否已在单词本，供阅读页星标初始状态判定。"""
        if not word_or_sw:
            return False
        sw = _sw_key(word_or_sw)
        c = self._conn()
        r = c.execute(
            "SELECT 1 FROM word_book WHERE sw=? OR word=? LIMIT 1",
            (sw, word_or_sw)).fetchone()
        return r is not None

    def delete_word(self, word: str):
        c = self._conn()
        sw = _sw_key(word)
        c.execute("DELETE FROM word_book WHERE word=? OR (sw<>'' AND sw=?)", (word, sw))
        c.commit()

    def delete_word_by_sw(self, sw: str) -> bool:
        c = self._conn()
        cur = c.execute("DELETE FROM word_book WHERE sw=?", (sw,))
        c.commit()
        return cur.rowcount > 0

    def set_word_server_id(self, local_id: int, server_id: int):
        c = self._conn()
        c.execute("UPDATE word_book SET server_id=? WHERE id=?", (server_id, local_id))
        c.commit()

    def list_pending_words(self) -> List[WordEntry]:
        """本机新增、尚未上云（server_id 为空）的生词（首次迁移 / 离线补传用）。

        返回的 WordEntry 自带出处 6 键（_row_to_word 已扩列）。core/sync.py 的
        _sync_word_book 上传时必须把它们一起塞进 payload —— 服务端按「最近一次
        带出处的上报」刷新出处，不带出处会被当成旧客户端而保留云端旧值。
        """
        rows = self._conn().execute(
            "SELECT * FROM word_book WHERE server_id IS NULL "
            "ORDER BY created_at DESC, id DESC").fetchall()
        return [self._row_to_word(r) for r in rows]

    def rebuild_words_from_server(self, items: List[dict]) -> int:
        """用服务端全量列表重建本地单词本（单一真源，镜像 rebuild_favorites_from_server）。

        调用方须先做「待上传项是否都在返回列表」的安全护栏再调本方法。
        item 字段：id / sw / word / phonetic / translations（换行用 LF）/ createdAt。
        PC 的 definition 本就是 LF 归一的整段释义，直接落回 definition；wire 的 translations
        也按 LF 传，无需像 Android 那样在字面量 \\n 与 LF 之间来回转换。
        删除只清「云端可管理」的行（有 server_id 或有 sw 身份），保留极老无 sw 的遗留行。

        item 的出处 6 键：bookName / bookKey / storeBookId / segmentNumber / lineNumber /
        sentenceText。出处策略（与 Android / 服务端对齐）：服务端该项「带出处」才用其
        6 键整体覆盖；出处全 None（旧客户端 / 存量数据）时**保留本地值**。因本方法先
        DELETE 再 INSERT，删前已按 sw 快照本地出处，插入时按上述策略回填 —— 否则一次
        全量重建就会把刚收藏的出处抹成 NULL，「跳转到原书」随即失效。
        返回写入条数。
        """
        c = self._conn()
        try:
            # 重建会清空「云端可管理」行；先按 sw 快照本地出处，供「服务端不带出处」的项
            # 回填（dict 即使全 None 也 truthy，可安全 .get），避免同步抹掉跳转信息。
            local_src: Dict[str, dict] = {}
            try:
                for r in c.execute(
                        "SELECT sw, " + _WORD_SOURCE_COLS + " FROM word_book").fetchall():
                    sw = (r["sw"] or "").strip()
                    if sw:
                        local_src[sw] = _row_word_source(r)
            except sqlite3.OperationalError:
                local_src = {}  # 极老库尚未补出处列：无快照可留，退化为留空
            c.execute(
                "DELETE FROM word_book "
                "WHERE server_id IS NOT NULL OR (sw IS NOT NULL AND sw <> '')")
            n = 0
            for it in items or []:
                sw = (it.get("sw") or "").strip()
                word = (it.get("word") or "").strip()
                if not sw or not word:
                    continue
                src = _wire_to_word_source(it)
                if not _word_source_has_any(src):
                    src = local_src.get(sw) or src  # 服务端不带出处 ⇒ 沿用本地快照
                c.execute(
                    "INSERT INTO word_book (word,phonetic,definition,created_at,sw,server_id,"
                    + _WORD_SOURCE_COLS + ") "
                    "VALUES (?,?,?,?,?,?," + _WORD_SOURCE_QMARKS + ")",
                    (word, it.get("phonetic") or "", it.get("translations") or "",
                     it.get("createdAt") or "", sw, it.get("id"),
                     *_word_source_tuple(src)))
                n += 1
            c.commit()
            return n
        except Exception:
            try:
                c.rollback()
            except Exception:  # noqa: BLE001
                pass
            raise

    def reconcile_words_from_server(self, items: List[dict]) -> Dict[str, int]:
        """按项对账（P1 #4：取代 rebuild + 全或无 guard，镜像 reconcile_favorites_from_server）。

        身份 = ``sw``（归一化键，对齐 Android/服务端）。算法：

        1. 遍历服务端 items：按 sw upsert 到本地
           - 命中本地行 → UPDATE word/phonetic/definition/created_at/server_id（服务端权威）；
             若本地原本是待上传（server_id IS NULL），此步同时完成 server_id 回填
           - 未命中 → INSERT 新行
        2. 遍历本地 sw 非空的行中未被 items 覆盖到的：
           - server_id IS NOT NULL → DELETE（服务端删词/账号切换，本地跟随）
           - server_id IS NULL → **保留原样**（未确认待上传，下次同步再试）
        3. 本地 sw 为空的极老遗留行**完全不动**（无云端身份，不参与同步）

        item 字段：id / sw / word / phonetic / translations / createdAt + 出处 6 键
        bookName / bookKey / storeBookId / segmentNumber / lineNumber / sentenceText。
        PC 的 definition 存的就是 LF 归一的整段释义，wire 的 translations 直接落回。

        出处策略（与 Android / 服务端对齐，也是「列白名单别漏」的意义所在）：
        服务端该项带出处才整体覆盖本地 6 列；出处全 None 时保留本地值，避免同步抹掉。
        INSERT 分支（本地无该 sw）没有本地值可保留，直接落服务端 6 键。

        返回：``{"inserted": n, "updated": n, "deleted": n, "pending_missing": n}``。
        """
        c = self._conn()
        stats = {"inserted": 0, "updated": 0, "deleted": 0, "pending_missing": 0}
        try:
            server_map: Dict[str, dict] = {}
            for it in items or []:
                sw = (it.get("sw") or "").strip()
                word = (it.get("word") or "").strip()
                if not sw or not word:
                    continue
                server_map[sw] = it

            for sw, it in server_map.items():
                src = _wire_to_word_source(it)
                has_src = _word_source_has_any(src)
                src_vals = _word_source_tuple(src)
                existing = c.execute(
                    "SELECT id FROM word_book WHERE sw=?", (sw,)).fetchone()
                if existing:
                    # 服务端该项「带出处」才整体覆盖出处 6 列；出处全 None（旧客户端 /
                    # 存量数据）时**只更新快照，不动出处** —— 否则一次同步就会把本地
                    # 刚收藏的跳转信息抹成 NULL。与服务端「保留原值」语义对称。
                    if has_src:
                        c.execute(
                            "UPDATE word_book SET word=?, phonetic=?, definition=?, "
                            "created_at=?, server_id=?, " + _WORD_SOURCE_SET + " WHERE id=?",
                            ((it.get("word") or "").strip(), it.get("phonetic") or "",
                             it.get("translations") or "", it.get("createdAt") or "",
                             it.get("id"), *src_vals, existing["id"]))
                    else:
                        c.execute(
                            "UPDATE word_book SET word=?, phonetic=?, definition=?, "
                            "created_at=?, server_id=? WHERE id=?",
                            ((it.get("word") or "").strip(), it.get("phonetic") or "",
                             it.get("translations") or "", it.get("createdAt") or "",
                             it.get("id"), existing["id"]))
                    stats["updated"] += 1
                else:
                    # 本地原本无此 sw，没有本地出处可保留 ⇒ 直接写服务端 6 键（None 即 NULL）
                    c.execute(
                        "INSERT INTO word_book (word,phonetic,definition,created_at,sw,server_id,"
                        + _WORD_SOURCE_COLS + ") "
                        "VALUES (?,?,?,?,?,?," + _WORD_SOURCE_QMARKS + ")",
                        ((it.get("word") or "").strip(), it.get("phonetic") or "",
                         it.get("translations") or "", it.get("createdAt") or "",
                         sw, it.get("id"), *src_vals))
                    stats["inserted"] += 1

            local_rows = c.execute(
                "SELECT id, sw, server_id FROM word_book "
                "WHERE sw IS NOT NULL AND sw <> ''").fetchall()
            for r in local_rows:
                sw = (r["sw"] or "").strip()
                if sw in server_map:
                    continue
                if r["server_id"] is not None:
                    c.execute("DELETE FROM word_book WHERE id=?", (r["id"],))
                    stats["deleted"] += 1
                else:
                    stats["pending_missing"] += 1

            c.commit()
            return stats
        except Exception:
            try:
                c.rollback()
            except Exception:  # noqa: BLE001
                pass
            raise

    # ---------- Sync State（P1 #3：覆盖图 ETag 等同步元数据 kv） ----------

    def get_sync_state(self, key: str) -> str:
        """读 ``sync_state`` 表里某个 key 的 value；不存在返回空串。"""
        if not key:
            return ""
        try:
            r = self._conn().execute(
                "SELECT value FROM sync_state WHERE key=?", (key,)).fetchone()
        except sqlite3.Error:
            return ""
        return (r["value"] if r and r["value"] is not None else "")

    def set_sync_state(self, key: str, value: str) -> None:
        """写 ``sync_state``（upsert）。value 为 None 时落空串。"""
        if not key:
            return
        c = self._conn()
        try:
            c.execute(
                "INSERT OR REPLACE INTO sync_state (key, value, updated_at) VALUES (?,?,?)",
                (key, value or "", time.strftime("%Y-%m-%dT%H:%M:%S")))
            c.commit()
        except sqlite3.Error:
            # 同步元数据写失败不该炸主流程：下次同步顶多多走一次 200 全量
            try:
                c.rollback()
            except Exception:  # noqa: BLE001
                pass

    def clear_sync_state(self, key: str = None) -> None:
        """清 ``sync_state``：传 key 删单条，传 None 清全表（登出 / 切账号时用）。"""
        c = self._conn()
        try:
            if key is None:
                c.execute("DELETE FROM sync_state")
            else:
                c.execute("DELETE FROM sync_state WHERE key=?", (key,))
            c.commit()
        except sqlite3.Error:
            try:
                c.rollback()
            except Exception:  # noqa: BLE001
                pass

    # ---------- Audio Coverage（Phase3 有声元数据跨端同步） ----------

    def local_audio_coverage(self) -> List[Dict]:
        """聚合**本机**每本书的有声覆盖图（仅元数据，不含字节），供 POST /api/audio-coverage/sync。

        数据源：``audio_segments``（本机持有/下载过的完成段）+ ``tasks`` + ``books``。
        - 身份键 bookKey：s:{store_book_id} > t:{server_task_id} > n:{normalize_name(book_name)}
          （与服务端 / Android 逐字符一致，用 ``coverage.book_key``）。
        - 判源：优先用 ``audio_segments.source`` 显式列（v12 迁移后，下载时写 'server'，
          本机合成时写 'local'）；老库无此列时回退到文件名前缀启发式（``tts_`` → server，
          ``local_`` 或其它 → local）。显式列更可靠，避免用户重命名文件后判源错误。
        - 同一 (bookKey, source) 的段号做并集压成规范串（只反映本机持有，不代表可播）。

        返回 [{bookKey, source, storeBookId?, serverTaskId?, totalSegments, doneRanges, doneCount}]，
        空表 / 无有效段 → []。纯读，不改任何本地数据。
        """
        c = self._conn()
        # 任务元信息：本地 task_id → (store_book_id, server_task_id, book_name, total_segments)
        task_meta: Dict[int, Dict] = {}
        try:
            rows = c.execute(
                "SELECT t.id AS task_id, t.server_task_id AS server_task_id, "
                "t.book_name AS book_name, t.total_segments AS total_segments, "
                "b.store_book_id AS store_book_id "
                "FROM tasks t LEFT JOIN books b ON b.id = t.book_id").fetchall()
            for r in rows:
                task_meta[r["task_id"]] = {
                    "server_task_id": r["server_task_id"],
                    "book_name": r["book_name"] or "",
                    "total_segments": int(r["total_segments"] or 0),
                    "store_book_id": r["store_book_id"],
                }
        except sqlite3.Error:
            return []
        # 书名 → store_book_id 兜底（任务的 book_id 未连上 books 时，按书名找书城身份）
        name_to_store: Dict[str, int] = {}
        # store_book_id → 书名：书城书的音频段用 task_id = -store_book_id 作锚点
        # （见 StoreAudioDownloader），tasks 表里**没有**这一行 —— 旧实现把它当
        # 「孤儿段」直接跳过 ⇒ 明明本机下了 33 段，书架却显示「云端有声」，
        # 上传给服务端的覆盖图也丢掉「本机持有」这一事实。
        store_names: Dict[int, str] = {}
        try:
            for r in c.execute(
                    "SELECT display_name, store_book_id FROM books "
                    "WHERE store_book_id IS NOT NULL AND store_book_id > 0").fetchall():
                nm = (r["display_name"] or "").strip()
                sbid = int(r["store_book_id"] or 0)
                if nm:
                    name_to_store[nm] = sbid
                if sbid:
                    store_names[sbid] = nm
        except sqlite3.Error:
            pass

        # 分组：(bookKey, source) → {nums:set, store_book_id, server_task_id, total_segments}
        groups: Dict[tuple, Dict] = {}
        try:
            # 优先用显式 source 列（v12 迁移后）；老库无此列时回退到文件名前缀启发式
            has_source_col = self._has_column("audio_segments", "source")
            if has_source_col:
                segs = c.execute(
                    "SELECT task_id, segment_number, audio_file_path, source FROM audio_segments "
                    "WHERE status='completed' AND audio_file_path <> ''").fetchall()
            else:
                segs = c.execute(
                    "SELECT task_id, segment_number, audio_file_path FROM audio_segments "
                    "WHERE status='completed' AND audio_file_path <> ''").fetchall()
        except sqlite3.Error:
            return []

        for s in segs:
            meta = task_meta.get(s["task_id"])
            if not meta:
                # 书城锚点：task_id = -store_book_id（没有对应的 tasks 行）
                try:
                    anchor = -int(s["task_id"])
                except (TypeError, ValueError):
                    anchor = 0
                if anchor <= 0:
                    continue  # 真正的孤儿段（任务已删）：无法定身份，跳过
                meta = {"server_task_id": None,
                        "book_name": store_names.get(anchor, ""),
                        "total_segments": 0,
                        "store_book_id": anchor}
            sbid = meta.get("store_book_id") or name_to_store.get(
                (meta["book_name"] or "").strip(), 0)
            bk = coverage.book_key(sbid, meta.get("server_task_id"), meta["book_name"])
            if not bk:
                continue  # 无身份键（无书城/任务 id 且书名为空）：跳过
            # 判源：优先用显式 source 列；老库回退到文件名前缀启发式（tts_ → server，其它 → local）
            if has_source_col and s["source"]:
                src = s["source"] if s["source"] in ("server", "local") else "local"
            else:
                fn = os.path.basename((s["audio_file_path"] or "")).lower()
                src = "server" if fn.startswith("tts_") else "local"
            gkey = (bk, src)
            g = groups.get(gkey)
            if g is None:
                g = {"nums": set(), "store_book_id": sbid or 0,
                     "server_task_id": meta.get("server_task_id") or 0,
                     "total_segments": meta.get("total_segments") or 0}
                groups[gkey] = g
            try:
                g["nums"].add(int(s["segment_number"]))
            except (TypeError, ValueError):
                continue
            # total_segments / 身份 id 取非空最大值（多任务同书时以更权威者优先）
            if (meta.get("total_segments") or 0) > g["total_segments"]:
                g["total_segments"] = int(meta.get("total_segments") or 0)
            if not g["store_book_id"] and sbid:
                g["store_book_id"] = int(sbid)
            if not g["server_task_id"] and meta.get("server_task_id"):
                g["server_task_id"] = int(meta["server_task_id"])

        out: List[Dict] = []
        for (bk, src), g in groups.items():
            if not g["nums"]:
                continue
            ranges = coverage.from_numbers(g["nums"])
            if not ranges:
                continue
            item: Dict = {
                "bookKey": bk,
                "source": src,
                "totalSegments": int(g["total_segments"] or 0),
                "doneRanges": ranges,
                "doneCount": coverage.count(ranges),
            }
            if g["store_book_id"]:
                item["storeBookId"] = int(g["store_book_id"])
            if g["server_task_id"]:
                item["serverTaskId"] = int(g["server_task_id"])
            out.append(item)
        out.sort(key=lambda d: (d["bookKey"], d["source"]))
        return out

    def rebuild_audio_coverage_from_server(self, items: List[Dict]) -> int:
        """用服务端并集合并后的全量覆盖图**重建**本地缓存表（供 UI 读，纯元数据）。

        覆盖图是感知层、不删本地音频，故无需收藏式护栏——直接镜像服务端真源即可。
        item 字段（服务端 camelCase）：bookKey / source / storeBookId? / serverTaskId? /
        totalSegments / doneRanges / doneCount。返回写入条数。
        """
        c = self._conn()
        try:
            c.execute("DELETE FROM audio_coverage")
            n = 0
            for it in items or []:
                if not isinstance(it, dict):
                    continue
                bk = (it.get("bookKey") or "").strip()
                src = (it.get("source") or "").strip()
                if not bk or not src:
                    continue
                ranges = it.get("doneRanges") or ""
                c.execute(
                    "INSERT OR REPLACE INTO audio_coverage "
                    "(book_key,source,store_book_id,server_task_id,total_segments,"
                    "done_ranges,done_count,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    (bk, src, it.get("storeBookId"), it.get("serverTaskId"),
                     int(it.get("totalSegments") or 0), ranges,
                     int(it.get("doneCount") or coverage.count(ranges)),
                     it.get("updatedAt") or ""))
                n += 1
            c.commit()
            return n
        except Exception:
            try:
                c.rollback()
            except Exception:  # noqa: BLE001
                pass
            raise

    def get_audio_coverage(self) -> List[Dict]:
        """全部覆盖图缓存（供书架批量判定）。"""
        rows = self._conn().execute(
            "SELECT * FROM audio_coverage ORDER BY book_key, source").fetchall()
        return [self._row_to_coverage(r) for r in rows]

    def get_audio_coverage_for_book(self, book_key: str) -> List[Dict]:
        """某本书（按 bookKey）的覆盖图，含 server/local 两源。"""
        if not book_key:
            return []
        rows = self._conn().execute(
            "SELECT * FROM audio_coverage WHERE book_key=? ORDER BY source",
            (book_key,)).fetchall()
        return [self._row_to_coverage(r) for r in rows]

    def _row_to_coverage(self, r) -> Dict:
        ranges = r["done_ranges"] or ""
        keys = r.keys()
        return {
            "book_key": r["book_key"],
            "source": r["source"],
            "store_book_id": (r["store_book_id"] if "store_book_id" in keys else None),
            "server_task_id": (r["server_task_id"] if "server_task_id" in keys else None),
            "total_segments": int(r["total_segments"] or 0),
            "done_ranges": ranges,
            "done_count": int(r["done_count"] or 0) or coverage.count(ranges),
            "updated_at": (r["updated_at"] if "updated_at" in keys else "") or "",
        }

