"""正文快照收尾自检：本地签 token 的格式 + 真服务器上的调用闭环。

## 覆盖

离线（默认）：

1. `sign_admin_token` 的结构与签名 —— 三段、header.alg=HS512、HMAC-SHA512 自验、
   claims（sub / isAdmin / tokenVersion / exp）；
2. `clean_secret` 把 env 值里的引号、CR/LF、空格清掉（签名密钥混进这些只会 401）；
3. `fetch_user_auth` 的两条路径：有 `token_version` 列取真实值；
   老库没这一列时降级成 0 且不抛。

在线（`--live`，需要 SSH 可达 + 服务器在跑）：

4. 真读服务器 env 拿 `JWT_SECRET`，挑一本已绑任务的书，调
   `POST /api/admin/books/{id}/content-snapshot` ⇒ `success=true` 且段数 > 0；
5. **反向**：把密钥改一个字符再签 ⇒ 必须 401。
   这条是为了证明调用真的走鉴权，而不是端点没设防。

用法：

    python tools/selftest_snapshot.py          # 只跑离线
    python tools/selftest_snapshot.py --live   # 连真服务器
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# Windows 控制台默认 GBK：日志里有中文，统一成 UTF-8
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

import pymysql  # noqa: E402

from app.config import AppConfig, MySqlTarget  # noqa: E402
from app.core import db  # noqa: E402
from app.core.api_client import AdminApi, ApiError, sign_admin_token  # noqa: E402
from app.core.ssh_upload import (  # noqa: E402
    SshClient,
    clean_secret,
    pick_free_port,
    read_server_info,
)

PASSWORD = "change-me-local-db"
TEMP_DB = "audiobook_db_selftest_snap"

FAILS: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print("  [%s] %s%s" % ("OK" if ok else "FAIL", name, ("　" + detail) if detail else ""))
    if not ok:
        FAILS.append(name)


# ---------- 工具 ----------


def _b64url_decode(text: str) -> bytes:
    padding = "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(text + padding)


def _admin_conn():
    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root",
                           password=PASSWORD, charset="utf8mb4", autocommit=True)
    return conn


def _reset_temp_db(with_token_version: bool) -> None:
    conn = _admin_conn()
    with conn.cursor() as cur:
        cur.execute(f"DROP DATABASE IF EXISTS `{TEMP_DB}`")
        cur.execute(f"CREATE DATABASE `{TEMP_DB}` CHARACTER SET utf8mb4")
        cur.execute(f"USE `{TEMP_DB}`")
        extra = ", token_version INT NOT NULL DEFAULT 0" if with_token_version else ""
        cur.execute(
            "CREATE TABLE users ("
            "id INT AUTO_INCREMENT PRIMARY KEY,"
            "username VARCHAR(50) NOT NULL,"
            "password VARCHAR(255) NOT NULL,"
            "is_admin TINYINT(1) DEFAULT 0,"
            f"credits INT DEFAULT 0{extra}"
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        )
        cur.execute(
            "INSERT INTO users (username, password, is_admin, credits" 
            + (", token_version" if with_token_version else "")
            + ") VALUES ('boss', 'x', 1, 0" + (", 7" if with_token_version else "") + ")"
        )
    conn.close()


def _drop_temp_db() -> None:
    conn = _admin_conn()
    with conn.cursor() as cur:
        cur.execute(f"DROP DATABASE IF EXISTS `{TEMP_DB}`")
    conn.close()


def _conn_to_temp_db():
    return db.Database(MySqlTarget(host="127.0.0.1", port=3306, database=TEMP_DB,
                                   user="root", password=PASSWORD)).connect()


# ---------- 离线 ----------


def test_token_shape() -> None:
    secret = "unit-test-secret-change-me"
    token = sign_admin_token(secret, "boss", token_version=7, ttl_seconds=600)
    parts = token.split(".")
    check("token 是三段式", len(parts) == 3, f"实际 {len(parts)} 段")

    header = _b64url_decode(parts[0]).decode()
    check("header.alg = HS512", '"alg":"HS512"' in header.replace(" ", ""), header)

    # 签名自验：用同一套密钥重算，必须逐字节相等
    key = hashlib.sha512(secret.encode("utf-8")).digest()
    expect = hmac.new(key, f"{parts[0]}.{parts[1]}".encode("ascii"), hashlib.sha512).digest()
    check("签名可按 SHA-512(secret)+HS512 复现", _b64url_decode(parts[2]) == expect)

    import json as _json
    payload = _json.loads(_b64url_decode(parts[1]).decode())
    check("claims.sub", payload.get("sub") == "boss", str(payload.get("sub")))
    check("claims.isAdmin", payload.get("isAdmin") is True, str(payload.get("isAdmin")))
    check("claims.tokenVersion", payload.get("tokenVersion") == 7, str(payload.get("tokenVersion")))
    check("claims.exp - iat = ttl",
          int(payload.get("exp", 0)) - int(payload.get("iat", 0)) == 600,
          str(int(payload.get("exp", 0)) - int(payload.get("iat", 0))))

    # 换一个字符签名必须不同（防「密钥没进签名」的低级错误）
    other = sign_admin_token(secret + "x", "boss", token_version=7, ttl_seconds=600)
    check("换密钥后签名不同", other.split(".")[2] != parts[2])

    for bad in ("", None):
        try:
            sign_admin_token(bad or "", "boss")
            check("空密钥应报错", False)
        except ApiError:
            check("空密钥应报错", True)


def test_clean_secret() -> None:
    cases = [
        ('"abc123"', "abc123"),
        ("'abc123'", "abc123"),
        ('"abc123"\r\n', "abc123"),
        ("  abc123  ", "abc123"),
        ("abc\r\n123", "abc123"),
    ]
    for raw, expect in cases:
        got = clean_secret(raw)
        check(f"clean_secret({raw!r})", got == expect, f"得到 {got!r}")


def test_fetch_user_auth() -> None:
    _reset_temp_db(with_token_version=True)
    conn = _conn_to_temp_db()
    try:
        auth = db.fetch_user_auth(conn, "boss")
        check("读到 token_version", bool(auth) and auth["token_version"] == 7, str(auth))
        check("读到 is_admin", bool(auth) and auth["is_admin"] is True, str(auth))
        check("查不到的用户返回 None", db.fetch_user_auth(conn, "nobody") is None)
    finally:
        conn.close()

    # 老库（没有 token_version 列）⇒ 降级为 0，绝不抛
    _reset_temp_db(with_token_version=False)
    conn = _conn_to_temp_db()
    try:
        auth = db.fetch_user_auth(conn, "boss")
        check("老库降级：token_version = 0", bool(auth) and auth["token_version"] == 0, str(auth))
        check("老库降级：仍能读到 is_admin", bool(auth) and auth["is_admin"] is True, str(auth))
    finally:
        conn.close()
    _drop_temp_db()


# ---------- 在线 ----------


def test_live() -> None:
    cfg = AppConfig.load()
    if not cfg.ssh.host:
        check("在线自检需要配置 SSH 主机", False, "当前配置为空")
        return

    ssh = SshClient(cfg.ssh, log=lambda _m: None)
    info = read_server_info(ssh, cfg.ssh, cfg.server_db)
    check("服务器 env 里有 JWT_SECRET", bool(info.jwt_secret), f"长度 {len(info.jwt_secret)}")
    if not info.jwt_secret:
        return

    port = pick_free_port()
    with ssh.tunnel(port, info.db.host, info.db.port):
        conn = db.Database(MySqlTarget(
            host="127.0.0.1", port=port, database=info.db.database,
            user=info.db.user, password=info.db.password,
        )).connect()
        try:
            auth = db.fetch_user_auth(conn, cfg.store_owner_username)
            check(f"服务器上有管理员「{cfg.store_owner_username}」",
                  bool(auth) and auth["is_admin"], str(auth))
            if not auth:
                return
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT id, title FROM store_books WHERE task_id IS NOT NULL "
                    "AND status = 'onshelf' ORDER BY id DESC LIMIT 1"
                )
                row = cur.fetchone()
        finally:
            conn.close()

    if not row:
        check("书城里有已绑任务的书", False, "store_books 里没有 task_id 非空的书")
        return
    book_id = int(row["id"])
    print(f"  用《{row['title']}》(#{book_id}) 验证")

    base_url = (cfg.server_api_base or f"http://{cfg.ssh.host}").strip()
    api = AdminApi(base_url, info.jwt_secret, cfg.store_owner_username,
                   int(auth["token_version"]))
    data = api.rebuild_content_snapshot(book_id)
    check("重建快照返回 success", data.get("success") is True, str(data)[:120])
    check("快照段落数 > 0", int(data.get("paragraphs") or 0) > 0, f"paragraphs={data.get('paragraphs')}")
    check("快照有行数据", int(data.get("rows") or 0) > 0, f"rows={data.get('rows')}")

    # 反向：错误密钥必须 401 —— 证明端点真在验签，不是随便谁都能调
    bad = AdminApi(base_url, info.jwt_secret + "x", cfg.store_owner_username,
                   int(auth["token_version"]))
    try:
        bad.rebuild_content_snapshot(book_id)
        check("错误密钥应被拒（401）", False, "居然成功了")
    except ApiError as exc:
        check("错误密钥应被拒（401）", "401" in str(exc), str(exc)[:80])


def main() -> int:
    print("== 离线：签发 token ==")
    test_token_shape()
    print("== 离线：清洗 env 密钥 ==")
    test_clean_secret()
    print("== 离线：读用户鉴权信息 ==")
    try:
        test_fetch_user_auth()
    except Exception as exc:  # noqa: BLE001
        check("本地 MySQL 可用", False, f"{exc}")

    if "--live" in sys.argv:
        print("== 在线：真服务器调用闭环 ==")
        try:
            test_live()
        except Exception as exc:  # noqa: BLE001
            check("在线自检未抛异常", False, f"{type(exc).__name__}: {exc}")

    print()
    if FAILS:
        print(f"FAILED（{len(FAILS)} 项）：" + "、".join(FAILS))
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
