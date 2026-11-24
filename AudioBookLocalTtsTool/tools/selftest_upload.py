"""上传（SSH 文件模式）自检。

不碰真服务器、不碰业务库：本地起两个临时库
（`audiobook_db_selftest_up_local` / `audiobook_db_selftest_up_server`），
SSH 用假实现（只记录「传了哪些文件、远端有哪些文件」）。

覆盖：

1. 服务器 env 解析：`export ` 前缀、引号、**口令里带 `#`**、JDBC URL 拆解；
2. 原书定位：工作目录里有 `book.<ext>` 就直接用；文件名建议；
3. 正常上传：任务行 + 片段行、名字是 `{tid}/tts_{tid}_{n}.mp3`、`audio_data` 全 NULL；
4. 工作目录缺文件 → 从本地库 BLOB 导出兜底，仍然传齐；
5. 本地合成失败的段：不传文件但**照样写库**（否则段号错位），任务记 partial；
6. 远端文件校验不过 → 抛错、库里不留任务、远端目录与原书都被删掉。
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# Windows 控制台默认 GBK：日志里有中文，统一成 UTF-8
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

import pymysql  # noqa: E402

from app.config import AppConfig, MySqlTarget, SshTarget  # noqa: E402
from app.core import db, uploader  # noqa: E402
from app.core.api_client import ApiError  # noqa: E402
from app.core.ssh_upload import ServerInfo, parse_env_file, parse_jdbc_url  # noqa: E402

PASSWORD = "change-me-local-db"
LOCAL_DB = "audiobook_db_selftest_up_local"
SERVER_DB = "audiobook_db_selftest_up_server"
USERNAME = "tester"

FAILS: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print("  [%s] %s%s" % ("OK" if ok else "FAIL", name, ("　" + detail) if detail else ""))
    if not ok:
        FAILS.append(name)


class FakeSsh:
    """假 SSH：只记录上传了什么，不真的连服务器。"""

    def __init__(self, drop_files: set[str] | None = None) -> None:
        self.files: dict[str, list[str]] = {}      # 远端目录 → 文件名
        self.uploaded_books: list[tuple[str, str]] = []
        self.removed: list[str] = []
        self.put_paths: set[str] = set()           # 单文件传输落地的路径（供 test -s 判定）
        self._drop = drop_files or set()           # 模拟「传丢了几个文件」

    def put_file(self, local_path, remote_path) -> None:
        self.uploaded_books.append((str(local_path), remote_path))
        self.put_paths.add(str(remote_path))

    def run(self, remote_cmd: str, timeout: float = 60.0, check: bool = True) -> str:
        """只支持 `test -s <path>` 这种探测：文件在就 OK，不在就 MISSING。"""
        for path in self.put_paths:
            if path in remote_cmd:
                return "OK\n"
        for names in self.files.values():
            for name in names:
                if name in remote_cmd:
                    return "OK\n"
        return "MISSING\n"

    def mkdir(self, remote_dir: str) -> None:
        self.files.setdefault(remote_dir, [])

    def pipe_tar(self, pairs, remote_dir, on_files=None, total_hint=0) -> int:
        names = []
        count = 0
        for _local, arcname in pairs:
            count += 1
            if arcname not in self._drop:
                names.append(arcname)
            if on_files:
                on_files(count)
        self.files[remote_dir] = names
        return count

    def list_dir(self, remote_dir: str) -> list[str]:
        return list(self.files.get(remote_dir, []))

    def remove_tree(self, remote_path: str) -> None:
        self.removed.append(remote_path)
        self.files.pop(remote_path, None)


# 书城表（只在自检的临时库里建；生产库不建表，结构以 init_database.sql 为准）
DDL_STORE_BOOKS = """
CREATE TABLE IF NOT EXISTS store_books (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    author VARCHAR(100) DEFAULT NULL,
    category VARCHAR(50) DEFAULT NULL,
    description TEXT,
    file_name VARCHAR(500) NOT NULL,
    file_extension VARCHAR(10) DEFAULT NULL,
    file_size BIGINT DEFAULT NULL,
    cover_color VARCHAR(10) DEFAULT NULL,
    cover_image VARCHAR(500) DEFAULT NULL,
    download_count INT DEFAULT 0,
    editor_pick TINYINT(1) DEFAULT 0,
    status VARCHAR(20) DEFAULT 'onshelf',
    task_id BIGINT DEFAULT NULL,
    synthesized_at DATETIME DEFAULT NULL,
    synthesized_by VARCHAR(100) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
"""


class FakeAdminApi:
    """假管理端客户端：只记录「用什么参数调的、调了哪本书」，不发 HTTP。"""

    calls: list[dict] = []
    fail: str = ""

    def __init__(self, base_url: str, secret: str, username: str,
                 token_version: int = 0, log=None, timeout: float = 180.0) -> None:
        FakeAdminApi.calls.append({
            "base_url": base_url,
            "secret_len": len(secret or ""),
            "username": username,
            "token_version": token_version,
        })

    def rebuild_content_snapshot(self, book_id: int) -> dict:
        FakeAdminApi.calls.append({"book_id": book_id})
        if FakeAdminApi.fail:
            raise ApiError(FakeAdminApi.fail)
        return {"success": True, "title": "测试书", "paragraphs": 42, "rows": 60}


def _reset_databases() -> None:
    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root",
                           password=PASSWORD, charset="utf8mb4", autocommit=True)
    with conn.cursor() as cur:
        for name in (LOCAL_DB, SERVER_DB):
            cur.execute(f"DROP DATABASE IF EXISTS `{name}`")
            cur.execute(f"CREATE DATABASE `{name}` CHARACTER SET utf8mb4")
    conn.close()


def _drop_databases() -> None:
    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root",
                           password=PASSWORD, charset="utf8mb4", autocommit=True)
    with conn.cursor() as cur:
        for name in (LOCAL_DB, SERVER_DB):
            cur.execute(f"DROP DATABASE IF EXISTS `{name}`")
    conn.close()


def _target(name: str) -> MySqlTarget:
    return MySqlTarget(host="127.0.0.1", port=3306, database=name, user="root", password=PASSWORD)


def _make_local_task(conn, user_id: int, work_dir: Path, title: str = "测试书") -> int:
    """造一个 4 段的任务：1/3 有文件、2 只有 BLOB、4 是合成失败。"""
    work_dir.mkdir(parents=True, exist_ok=True)
    (work_dir / "book.pdf").write_bytes(b"%PDF-1.4 selftest book")
    task_id = db.create_task(
        conn,
        user_id=user_id,
        book_file_path=f"local/{title}.pdf",
        original_book_name=title,
        total_segments=4,
        status="completed",
    )
    # 段 1：文件在
    (work_dir / "seg_00001.mp3").write_bytes(b"a" * 1000)
    db.insert_segment(conn, task_id=task_id, segment_number=1, text_content="第一段",
                      audio_data=b"a" * 1000, audio_file_path=f"{task_id}/tts_{task_id}_1.mp3",
                      duration_seconds=1.0, status="completed")
    # 段 2：文件被清掉了，只有 BLOB
    db.insert_segment(conn, task_id=task_id, segment_number=2, text_content="第二段",
                      audio_data=b"b" * 2000, audio_file_path=f"{task_id}/tts_{task_id}_2.mp3",
                      duration_seconds=2.0, status="completed")
    # 段 3：文件在
    (work_dir / "seg_00003.mp3").write_bytes(b"c" * 1500)
    db.insert_segment(conn, task_id=task_id, segment_number=3, text_content="第三段",
                      audio_data=b"c" * 1500, audio_file_path=f"{task_id}/tts_{task_id}_3.mp3",
                      duration_seconds=3.0, status="completed")
    # 段 4：本地合成失败，既没文件也没字节
    db.insert_segment(conn, task_id=task_id, segment_number=4, text_content="第四段",
                      audio_data=None, audio_file_path=f"{task_id}/tts_{task_id}_4.mp3",
                      duration_seconds=None, status="failed", error_message="引擎报错")
    conn.commit()
    return task_id


def _server_rows(conn, server_task_id: int) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT segment_number, status, audio_data, audio_file_path "
            "FROM audio_segments WHERE task_id=%s ORDER BY segment_number",
            (server_task_id,),
        )
        return list(cur.fetchall())


def main() -> int:
    print("== 1. env 解析 ==")
    env_text = """
# 注释行
export DB_URL="jdbc:mysql://127.0.0.1:3306/audiobook_db?useSSL=false&serverTimezone=Asia/Shanghai"
DB_USERNAME='root'
DB_PASSWORD="change-me-local-db"
UPLOAD_DIR=/data/audiobook/uploads
"""
    values = parse_env_file(env_text)
    check("DB_URL 去掉 export 与引号", values.get("DB_URL", "").startswith("jdbc:mysql://"))
    check("口令里的 # 没有被当注释截断", values.get("DB_PASSWORD") == "change-me-local-db",
          repr(values.get("DB_PASSWORD")))
    check("单引号也能去", values.get("DB_USERNAME") == "root", repr(values.get("DB_USERNAME")))
    host, port, name = parse_jdbc_url(values["DB_URL"])
    check("JDBC URL 拆解", (host, port, name) == ("127.0.0.1", 3306, "audiobook_db"),
          f"{host}:{port}/{name}")

    print("== 2. 原书定位 ==")
    tmp_root = Path(tempfile.mkdtemp(prefix="selftest_up_"))
    cfg = AppConfig()
    cfg.work_dir = str(tmp_root)
    cfg.local_db = _target(LOCAL_DB)
    cfg.server_db = MySqlTarget(host="", port=3306, database=SERVER_DB, user="root", password="")
    cfg.ssh = SshTarget(host="127.0.0.1", key_path="")
    task_stub = {"id": 7, "original_book_name": "测试书", "book_file_path": "local/测试书.pdf"}
    work7 = uploader.work_dir_for(cfg, 7)
    work7.mkdir(parents=True, exist_ok=True)
    check("工作目录里没有副本时返回 None", uploader.find_book_file(cfg, task_stub) is None)
    (work7 / "book.pdf").write_bytes(b"%PDF")
    found = uploader.find_book_file(cfg, task_stub)
    check("有 book.pdf 时能找到", found is not None and found.name == "book.pdf")
    check("文件名建议优先用库里的原文件名",
          uploader.suggest_book_name(task_stub) == "测试书.pdf",
          uploader.suggest_book_name(task_stub))

    _reset_databases()
    try:
        local_db = db.Database(_target(LOCAL_DB))
        local_db.ensure_schema(with_tool_tables=True)
        server_db = db.Database(_target(SERVER_DB))
        server_db.ensure_schema(with_tool_tables=True)
        # 书城表只在这个临时库里建：生产库的表结构以 init_database.sql 为准，工具不擅自改
        bootstrap = server_db.connect()
        try:
            with bootstrap.cursor() as cur:
                cur.execute(DDL_STORE_BOOKS)
            bootstrap.commit()
        finally:
            bootstrap.close()
        local_conn = local_db.connect()
        server_conn = server_db.connect()
        try:
            local_user = db.ensure_user(local_conn, "local_tts")
            server_user = db.ensure_user(server_conn, USERNAME)
            work1 = uploader.work_dir_for(cfg, 1)
            task_id = _make_local_task(local_conn, local_user, work1)
            task = db.fetch_task(local_conn, task_id)
            info = ServerInfo(db=_target(SERVER_DB), uploads_dir="/fake/uploads")
            logs: list[str] = []

            print("== 3. 正常上传 ==")
            fake = FakeSsh()
            server_task_id = uploader._upload_one(  # noqa: SLF001 - 自检直接打内部函数
                cfg, local_conn, server_conn, fake, info, server_user,
                task_id, task, work1 / "book.pdf", logs.append,
            )
            rows = _server_rows(server_conn, server_task_id)
            check("片段数一致", len(rows) == 4, str(len(rows)))
            check("audio_data 全为 NULL", all(r["audio_data"] is None for r in rows))
            names = sorted(fake.files.get(f"/fake/uploads/{server_task_id}", []))
            check("远端文件名按 tts_{tid}_{n}.mp3", names == sorted(
                [f"tts_{server_task_id}_1.mp3", f"tts_{server_task_id}_2.mp3",
                 f"tts_{server_task_id}_3.mp3"]), str(names))
            check("BLOB 兜底救回了缺文件的那段",
                  any("导出" in m for m in logs), next((m for m in logs if "导出" in m), ""))
            check("原书传到了 uploads 根", fake.uploaded_books and
                  fake.uploaded_books[0][1].startswith("/fake/uploads/file_"),
                  str(fake.uploaded_books))
            with server_conn.cursor() as cur:
                cur.execute("SELECT status, completed_segments, total_segments, "
                            "book_file_path FROM tasks WHERE id=%s", (server_task_id,))
                head = cur.fetchone()
            check("失败段让任务记 partial", head["status"] == "partial", str(head["status"]))
            check("完成段数 = 3", int(head["completed_segments"]) == 3, str(head["completed_segments"]))
            check("book_file_path 指向 file_<uuid>.pdf",
                  str(head["book_file_path"]).startswith("file_") and
                  str(head["book_file_path"]).endswith(".pdf"), str(head["book_file_path"]))
            failed = [r for r in rows if r["status"] == "failed"]
            check("失败段照样写库（保住段号）", len(failed) == 1 and
                  int(failed[0]["segment_number"]) == 4, str([r["segment_number"] for r in failed]))

            print("== 4. 远端校验失败要回滚 ==")
            fake_bad = FakeSsh()
            work2 = uploader.work_dir_for(cfg, 2)
            task2_id = _make_local_task(local_conn, local_user, work2)
            task2 = db.fetch_task(local_conn, task2_id)
            # 通过 list_dir 少报一个来模拟「传丢了」
            orig_list = fake_bad.list_dir

            def short_list(remote_dir: str) -> list[str]:
                names = orig_list(remote_dir)
                return names[1:] if names else names

            fake_bad.list_dir = short_list  # type: ignore[assignment]
            raised = None
            try:
                uploader._upload_one(  # noqa: SLF001
                    cfg, local_conn, server_conn, fake_bad, info, server_user,
                    task2_id, task2, work2 / "book.pdf", logs.append,
                )
            except Exception as exc:  # noqa: BLE001
                raised = str(exc)
            check("校验不过会抛错", raised is not None and "校验失败" in raised, raised or "无异常")
            with server_conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) AS n FROM tasks WHERE original_book_name='测试书'")
                left = int(cur.fetchone()["n"])
            check("回滚后没留下新任务", left == 1, f"共 {left} 条（正常上传那份 1 条）")
            check("回滚删了远端目录", any("/fake/uploads/" in p for p in fake_bad.removed),
                  str(fake_bad.removed[:2]))

            print("== 5. 发布到书城 ==")
            # 服务端配色算法按 Java String.hashCode 复刻，用公开已知向量校验
            check("Java String.hashCode 复刻（\"abc\" = 96354）",
                  uploader.java_string_hash("abc") == 96354,
                  str(uploader.java_string_hash("abc")))
            check("Java String.hashCode 空串为 0", uploader.java_string_hash("") == 0)
            check("发布目标默认走书城", uploader._resolve_target(None, cfg) is True)  # noqa: SLF001
            check("发布目标可切回书架",
                  uploader._resolve_target("shelf", cfg) is False)  # noqa: SLF001
            try:
                uploader._resolve_target("shlef", cfg)  # noqa: SLF001
                check("拼错的发布目标要报错", False, "没报错")
            except Exception as exc:  # noqa: BLE001
                check("拼错的发布目标要报错", "未知的发布目标" in str(exc), str(exc)[:40])

            cfg.store_category = "军事"
            info_store = ServerInfo(db=_target(SERVER_DB), uploads_dir="/fake/uploads",
                                    store_dir="/fake/store")
            fake_store = FakeSsh()
            work5 = uploader.work_dir_for(cfg, 5)
            task5_id = _make_local_task(local_conn, local_user, work5)
            task5 = db.fetch_task(local_conn, task5_id)
            store_task_id, store_book_id = uploader._upload_one_to_store(  # noqa: SLF001
                cfg, local_conn, server_conn, fake_store, info_store, server_user,
                task5_id, task5, work5 / "book.pdf", logs.append, force=False,
            )
            with server_conn.cursor() as cur:
                cur.execute("SELECT user_id, status, total_segments FROM tasks WHERE id=%s",
                            (store_task_id,))
                store_task_row = cur.fetchone()
                cur.execute("SELECT id, title, task_id, status, category, file_name, "
                            "cover_color, synthesized_by FROM store_books ORDER BY id")
                store_rows = list(cur.fetchall())
            check("书城书的任务归管理员账号（不会进普通用户书架）",
                  int(store_task_row["user_id"]) == server_user,
                  f"user_id={store_task_row['user_id']}（owner={server_user}）")
            check("书城只建了一条记录", len(store_rows) == 1, f"共 {len(store_rows)} 条")
            book = store_rows[0]
            check("store_books.task_id 指向该任务",
                  int(book["task_id"]) == store_task_id, str(book["task_id"]))
            check("状态为 onshelf（App 可见）", book["status"] == "onshelf", str(book["status"]))
            check("分类按配置落库", book["category"] == "军事", str(book["category"]))
            check("文件名是 <uuid>_<书名>.pdf",
                  str(book["file_name"]).endswith("_测试书.pdf"), str(book["file_name"]))
            check("封面色与服务端算法一致",
                  book["cover_color"] == uploader.pick_cover_color("测试书"),
                  f"{book['cover_color']} vs {uploader.pick_cover_color('测试书')}")
            check("synthesized_by 标记来源", book["synthesized_by"] == uploader.SYNTHESIZED_BY,
                  str(book["synthesized_by"]))
            store_paths = [p for _l, p in fake_store.uploaded_books if p.startswith("/fake/store/")]
            check("原书落到了书城目录",
                  len(store_paths) == 1 and store_paths[0].startswith("/fake/store/军事/"),
                  str(store_paths))
            check("任务侧原书也传到 uploads（任务仍可独立使用）",
                  any(p.startswith("/fake/uploads/file_") for _l, p in fake_store.uploaded_books),
                  str([p for _l, p in fake_store.uploaded_books]))
            check("书城音频按 tts_{tid}_{n}.mp3 落地",
                  sorted(fake_store.files.get(f"/fake/uploads/{store_task_id}", []))
                  == ["tts_%d_%d.mp3" % (store_task_id, n) for n in (1, 2, 3)],
                  str(fake_store.files.get(f"/fake/uploads/{store_task_id}")))
            check("发布后自检日志出现", any("自检通过" in m for m in logs),
                  next((m for m in logs if "自检" in m), ""))

            print("== 6. 同名书重发：复用同一条记录，不堆重复 ==")
            fake_reuse = FakeSsh()
            work6 = uploader.work_dir_for(cfg, 6)
            task6_id = _make_local_task(local_conn, local_user, work6)
            task6 = db.fetch_task(local_conn, task6_id)
            again_task_id, again_book_id = uploader._upload_one_to_store(  # noqa: SLF001
                cfg, local_conn, server_conn, fake_reuse, info_store, server_user,
                task6_id, task6, work6 / "book.pdf", logs.append, force=True,
            )
            with server_conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) AS n FROM store_books")
                count_books = int(cur.fetchone()["n"])
                cur.execute("SELECT task_id FROM store_books WHERE id=%s", (again_book_id,))
                rebound = cur.fetchone()["task_id"]
            check("同名书复用同一条 store_books", count_books == 1 and again_book_id == store_book_id,
                  f"共 {count_books} 条，id={again_book_id} vs {store_book_id}")
            check("复用后指向新任务", int(rebound) == again_task_id,
                  f"{rebound} vs {again_task_id}")
            old_store_path = store_paths[0]
            check("复用时不生成第二个书城文件（沿用原文件名）",
                  all(p == old_store_path for p in
                      [q for _l, q in fake_reuse.uploaded_books if q.startswith("/fake/store/")]),
                  str([q for _l, q in fake_reuse.uploaded_books if q.startswith("/fake/store/")]))

            print("== 7. 书城发布失败要回滚（不留半成品书） ==")
            fake_fail = FakeSsh()
            # 不真传：直接返回 0 个，让「远端文件校验」失败（模拟传丢了）
            fake_fail.pipe_tar = lambda pairs, remote_dir, on_files=None, total_hint=0: 0  # type: ignore[assignment]
            work7 = uploader.work_dir_for(cfg, 7)
            task7_id = _make_local_task(local_conn, local_user, work7, title="另一本书")
            task7 = db.fetch_task(local_conn, task7_id)
            raised7 = None
            try:
                uploader._upload_one_to_store(  # noqa: SLF001
                    cfg, local_conn, server_conn, fake_fail, info_store, server_user,
                    task7_id, task7, work7 / "book.pdf", logs.append, force=True,
                )
            except Exception as exc:  # noqa: BLE001
                raised7 = str(exc)
            with server_conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) AS n FROM store_books")
                books_after = int(cur.fetchone()["n"])
            check("失败会抛错", raised7 is not None and "校验失败" in raised7, (raised7 or "无异常")[:50])
            check("失败不留新书城记录", books_after == 1, f"共 {books_after} 条")
            check("失败删掉了刚传的书城原书",
                  any("/fake/store/" in p for p in fake_fail.removed), str(fake_fail.removed[:3]))

            print("== 8. 正文快照收尾（发布成功后自动补一次） ==")
            # 组 5/6/7 用的 ServerInfo 没有 jwt_secret ⇒ 老部署必须照常发布、只跳过这一步
            check("没有 JWT_SECRET 时跳过、不报错",
                  any("跳过正文快照" in m for m in logs),
                  next((m for m in logs if "跳过正文快照" in m), "（没出现这条日志）"))

            cfg.store_owner_username = USERNAME
            with server_conn.cursor() as cur:
                cur.execute("UPDATE users SET is_admin=1, token_version=3 WHERE id=%s",
                            (server_user,))
            server_conn.commit()
            info_snap = ServerInfo(db=_target(SERVER_DB), uploads_dir="/fake/uploads",
                                   store_dir="/fake/store", jwt_secret="test-secret")

            original_api = uploader.AdminApi
            uploader.AdminApi = FakeAdminApi
            try:
                FakeAdminApi.calls.clear()
                FakeAdminApi.fail = ""
                snap_logs: list[str] = []
                uploader._rebuild_content_snapshot(  # noqa: SLF001
                    cfg, server_conn, info_snap, store_book_id, snap_logs.append
                )
                init = FakeAdminApi.calls[0] if FakeAdminApi.calls else {}
                check("快照带上了 book_id",
                      any(c.get("book_id") == store_book_id for c in FakeAdminApi.calls),
                      str(FakeAdminApi.calls))
                check("用的是书城归属账号", init.get("username") == USERNAME, str(init.get("username")))
                check("token_version 取库里的真实值（不是写死 0）",
                      init.get("token_version") == 3, str(init.get("token_version")))
                check("默认走 http://<SSH 主机>",
                      init.get("base_url") == f"http://{cfg.ssh.host}", str(init.get("base_url")))
                check("成功日志带段落数",
                      any("正文快照就绪" in m and "42 段" in m for m in snap_logs),
                      str(snap_logs[-1:]))

                cfg.server_api_base = "http://127.0.0.1:8080"
                FakeAdminApi.calls.clear()
                uploader._rebuild_content_snapshot(  # noqa: SLF001
                    cfg, server_conn, info_snap, store_book_id, lambda _m: None
                )
                check("server_api_base 覆盖生效",
                      bool(FakeAdminApi.calls)
                      and FakeAdminApi.calls[0].get("base_url") == "http://127.0.0.1:8080",
                      str(FakeAdminApi.calls[:1]))
                cfg.server_api_base = ""

                # 端点报错 ⇒ 只告警：书已经发布好了，不能因为补快照失败就标红
                FakeAdminApi.calls.clear()
                FakeAdminApi.fail = "HTTP 500：boom"
                fail_logs: list[str] = []
                raised8 = None
                try:
                    uploader._rebuild_content_snapshot(  # noqa: SLF001
                        cfg, server_conn, info_snap, store_book_id, fail_logs.append
                    )
                except Exception as exc:  # noqa: BLE001
                    raised8 = exc
                check("快照失败不抛异常", raised8 is None, repr(raised8))
                check("快照失败有告警", any("正文快照失败" in m for m in fail_logs),
                      str(fail_logs[:1]))
                FakeAdminApi.fail = ""

                # 非管理员 ⇒ 直接跳过（管理员端点必然 403，不必白跑一趟）
                with server_conn.cursor() as cur:
                    cur.execute("UPDATE users SET is_admin=0 WHERE id=%s", (server_user,))
                server_conn.commit()
                FakeAdminApi.calls.clear()
                skip_logs: list[str] = []
                uploader._rebuild_content_snapshot(  # noqa: SLF001
                    cfg, server_conn, info_snap, store_book_id, skip_logs.append
                )
                check("非管理员跳过、不发请求",
                      not FakeAdminApi.calls and any("不是管理员" in m for m in skip_logs),
                      str(skip_logs[:2]))
            finally:
                uploader.AdminApi = original_api
                with server_conn.cursor() as cur:
                    cur.execute("UPDATE users SET is_admin=1 WHERE id=%s", (server_user,))
                server_conn.commit()
        finally:
            local_conn.close()
            server_conn.close()
    finally:
        _drop_databases()

    print()
    if FAILS:
        print("FAILED:", "、".join(FAILS))
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
