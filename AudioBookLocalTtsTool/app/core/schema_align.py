"""把 MySQL 的表结构对齐到 AudioBookServer 的结构（供界面与命令行共用）。

## 为什么需要

服务端的结构由 `AudioBookServer/init_database.sql` 定义（生产环境
`ddl-auto=validate`，启动时逐列校验，对不上就起不来）。
而**老版本建出来的库**与它已经不一致了，本机就踩过两个坑：

| 差异 | 后果 |
|---|---|
| `audio_segments.audio_data` 是 `tinyblob`（255 字节） | 插一段 mp3 就报 `Data too long for column 'audio_data'`（MySQL 1400） |
| `tasks.reference_audio_path` 是 `NOT NULL` | 「没选参考音频」时插任务报 `Column ... cannot be null`（MySQL 1048） |

`CREATE TABLE IF NOT EXISTS` **不会改已有表**，所以光跑一遍建表脚本没用。

## 做法：不解析 SQL，直接建一个「参考库」再比对

1. 建临时库 `<db>_schema_ref`
2. 用 `init_database.sql` 在参考库里建出**标准结构**（服务器必然是这个结构）
3. 逐表比对目标库与参考库（列 / 索引 / 外键），生成 ALTER
4. 在目标库上执行，最后删掉参考库

好处：目标结构 100% 来自服务端脚本本身，不存在「解析 SQL 解析错了」的问题。

## 踩过的坑（都写进注释了）

* **参考库必须用独立连接建**：`init_database.sql` 里有 `USE audiobook_db;`，
  在同一连接上执行会把默认库切走 → 后面所有 `ALTER TABLE \\`x\\``
  （不带库名）会打在参考库上，表现为「Duplicate column name」，而目标库根本没被改。
  本模块所有语句都**显式带库名**，双保险。
* **MySQL 8 的 information_schema 统计缓存**（`information_schema_stats_expiry`
  默认 86400 秒）：缓存过期时 COLUMNS 会返回过期甚至重复的行。
  连接后要 `SET SESSION information_schema_stats_expiry = 0`。
* **DictCursor 的行是字典**：取值要按列名，`r[0]` 会 KeyError。

## 安全边界

* 只做**不丢数据**的变更：加列、改可空性、放宽类型、补索引/外键
* 类型**收窄**（如 varchar(1000) → varchar(500)）且本地确有超长数据 → **跳过并报告**
* NULL → NOT NULL 且本地存在 NULL → 先回填再改，并报告
* 不删列、不删表、不动数据行
"""

from __future__ import annotations

import re
from pathlib import Path

import pymysql
from pymysql.cursors import DictCursor

from app.config import MySqlTarget

CHARSET = "utf8mb4"
COLLATION = "utf8mb4_unicode_ci"


def connect(host, port, user, password, database=None):
    """建立一个「信息实时」的连接。"""
    conn = pymysql.connect(
        host=host, port=int(port), user=user, password=password,
        database=database, charset="utf8mb4", cursorclass=DictCursor,
        autocommit=True, connect_timeout=15,
    )
    # MySQL 8 的 information_schema 默认缓存表结构统计 24 小时；
    # 缓存过期时 COLUMNS 会返回过期甚至重复的行（表现为「要加的列其实早有了」）。
    try:
        with conn.cursor() as cur:
            cur.execute("SET SESSION information_schema_stats_expiry = 0")
    except pymysql.MySQLError:
        pass  # MySQL 5.7 没有这个变量，本来就是实时的
    return conn


def _split_statements(sql: str) -> list[str]:
    """按分号切语句（跳过注释行）。"""
    cleaned = "\n".join(
        line for line in sql.splitlines()
        if not line.strip().startswith("--")
    )
    return [s.strip() for s in cleaned.split(";") if s.strip()]


def build_reference(conn, ddl_path: Path, ref_db: str) -> None:
    """用 init_database.sql 在临时库里建出标准结构。

    ⚠️ 必须用**独立连接**：脚本里的 `USE audiobook_db;` 会改连接的默认库。
    """
    sql = ddl_path.read_text(encoding="utf-8")
    sql = re.sub(
        r"CREATE DATABASE IF NOT EXISTS\s+\S+",
        "CREATE DATABASE IF NOT EXISTS " + ref_db,
        sql, flags=re.IGNORECASE,
    )
    sql = re.sub(r"^\s*USE\s+\S+\s*;?\s*$", "USE " + ref_db + ";", sql,
                 flags=re.IGNORECASE | re.MULTILINE)
    with conn.cursor() as cur:
        cur.execute("DROP DATABASE IF EXISTS " + ref_db)
        cur.execute(
            "CREATE DATABASE " + ref_db
            + " CHARACTER SET " + CHARSET + " COLLATE " + COLLATION
        )
    with conn.cursor() as cur:
        for statement in _split_statements(sql):
            try:
                cur.execute(statement)
            except pymysql.MySQLError as exc:
                if statement.strip().upper().startswith("INSERT"):
                    continue  # 初始数据无所谓，结构才是关键
                raise RuntimeError(
                    "参考库建表失败：%s\nSQL: %s" % (exc, statement[:200])
                ) from exc


# ---------- 结构比对 ----------

_EMPTY_BY_TYPE = {
    "int": 0, "bigint": 0, "smallint": 0, "tinyint": 0, "mediumint": 0,
    "decimal": 0, "float": 0.0, "double": 0.0, "bit": b"\x00",
    "date": "1970-01-01", "datetime": "1970-01-01 00:00:00",
    "timestamp": "1970-01-01 00:00:00",
}


def _is_narrowing(current: str, target: str) -> bool:
    """目标类型是否比当前类型更窄（会截断数据 → 危险）。"""
    cur_len = _varchar_len(current)
    tgt_len = _varchar_len(target)
    if cur_len and tgt_len:
        return tgt_len < cur_len
    if current.startswith("text") and not target.startswith("text"):
        return True
    if current.startswith("bigint") and target in ("int", "smallint", "tinyint"):
        return True
    return False


def _varchar_len(type_str: str) -> int | None:
    match = re.search(r"varchar\((\d+)\)", type_str.lower())
    return int(match.group(1)) if match else None


def _column_sql(column: dict, after: str | None = None) -> str:
    """把 information_schema 的列信息拼成列定义（用于 ADD/MODIFY）。"""
    parts = ["`" + column["COLUMN_NAME"] + "`", column["COLUMN_TYPE"]]
    if column.get("CHARACTER_SET_NAME"):
        parts.append("CHARACTER SET " + column["CHARACTER_SET_NAME"])
        if column.get("COLLATION_NAME"):
            parts.append("COLLATE " + column["COLLATION_NAME"])
    if column["IS_NULLABLE"] == "NO":
        parts.append("NOT NULL")
    else:
        parts.append("DEFAULT NULL")
    default = column.get("COLUMN_DEFAULT")
    if default is not None and column["IS_NULLABLE"] == "NO":
        text = str(default)
        if text.upper().startswith("CURRENT_TIMESTAMP"):
            parts.append("DEFAULT " + text)
        elif column["CHARACTER_SET_NAME"]:
            parts.append("DEFAULT '" + text.replace("'", "''") + "'")
        else:
            parts.append("DEFAULT " + text)
    if after:
        parts.append("AFTER `" + after + "`")
    return " ".join(parts)


def fetch_columns(conn, database: str, table: str) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, "
            "CHARACTER_SET_NAME, COLLATION_NAME, ORDINAL_POSITION, EXTRA "
            "FROM information_schema.COLUMNS "
            "WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s ORDER BY ORDINAL_POSITION",
            (database, table),
        )
        rows = list(cur.fetchall())
    # 去重：统计信息异常时同一列可能返回多行（否则会生成重复的 ADD COLUMN）
    seen: set[str] = set()
    unique: list[dict] = []
    for row in rows:
        name = str(row["COLUMN_NAME"])
        if name in seen:
            continue
        seen.add(name)
        unique.append(row)
    return unique


def fetch_indexes(conn, database: str, table: str) -> dict[str, dict]:
    result: dict[str, dict] = {}
    with conn.cursor() as cur:
        cur.execute(
            "SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME "
            "FROM information_schema.STATISTICS "
            "WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s "
            "ORDER BY INDEX_NAME, SEQ_IN_INDEX",
            (database, table),
        )
        for row in cur.fetchall():
            entry = result.setdefault(
                row["INDEX_NAME"],
                {"unique": not int(row["NON_UNIQUE"]), "columns": []},
            )
            entry["columns"].append(row["COLUMN_NAME"])
    return result


def fetch_foreign_keys(conn, database: str, table: str) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT k.CONSTRAINT_NAME, k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, "
            "k.REFERENCED_COLUMN_NAME, r.UPDATE_RULE, r.DELETE_RULE "
            "FROM information_schema.KEY_COLUMN_USAGE k "
            "JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
            "  ON r.CONSTRAINT_NAME = k.CONSTRAINT_NAME "
            " AND r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA "
            "WHERE k.TABLE_SCHEMA = %s AND k.TABLE_NAME = %s "
            "  AND k.REFERENCED_TABLE_NAME IS NOT NULL",
            (database, table),
        )
        seen: set[tuple] = set()
        unique: list[dict] = []
        for row in cur.fetchall():
            key = (row["CONSTRAINT_NAME"], row["COLUMN_NAME"])
            if key in seen:
                continue
            seen.add(key)
            unique.append(row)
        return unique


def list_tables(conn, database: str) -> list[str]:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT TABLE_NAME FROM information_schema.TABLES "
            "WHERE TABLE_SCHEMA = %s AND TABLE_TYPE = 'BASE TABLE'",
            (database,),
        )
        return [str(r["TABLE_NAME"]) for r in cur.fetchall()]


# ---------- 主流程 ----------


def plan_table(conn, database: str, table: str, ref_database: str,
               warnings: list[str]) -> list[str]:
    """生成把目标库某张表对齐到参考结构所需的 SQL 列表。

    所有语句都**显式带库名**（`库`.`表`），不依赖连接的默认库。
    """
    statements: list[str] = []
    qualified = "%s.`%s`" % (database, table)
    local_by_name = {c["COLUMN_NAME"]: c for c in fetch_columns(conn, database, table)}
    ref_cols = fetch_columns(conn, ref_database, table)

    previous = None
    for ref in ref_cols:
        name = ref["COLUMN_NAME"]
        local = local_by_name.get(name)
        if local is None:
            statements.append(
                "ALTER TABLE %s ADD COLUMN %s" % (qualified, _column_sql(ref, previous))
            )
            previous = name
            continue

        needs_change = (
            local["COLUMN_TYPE"].lower() != ref["COLUMN_TYPE"].lower()
            or local["IS_NULLABLE"] != ref["IS_NULLABLE"]
            or (local.get("COLUMN_DEFAULT") or "") != (ref.get("COLUMN_DEFAULT") or "")
            or (local.get("CHARACTER_SET_NAME") or "") != (ref.get("CHARACTER_SET_NAME") or "")
        )
        if needs_change:
            if _is_narrowing(local["COLUMN_TYPE"].lower(), ref["COLUMN_TYPE"].lower()) \
                    and _has_overlong(conn, database, table, name, ref):
                warnings.append(
                    "%s.%s：本地是 %s，目标 %s（收窄），且存在超长数据 → 跳过"
                    % (table, name, local["COLUMN_TYPE"], ref["COLUMN_TYPE"])
                )
                previous = name
                continue
            if ref["IS_NULLABLE"] == "NO" and local["IS_NULLABLE"] == "YES" \
                    and _has_nulls(conn, database, table, name):
                placeholder = "0" if ref["COLUMN_TYPE"].lower() not in (
                    "varchar", "text", "longtext", "mediumtext", "char"
                ) else "''"
                statements.append(
                    "UPDATE %s SET `%s` = %s WHERE `%s` IS NULL"
                    % (qualified, name, placeholder, name)
                )
                warnings.append(
                    "%s.%s：本地存在 NULL 值，先回填 %s 再改为 NOT NULL"
                    % (table, name, placeholder)
                )
            statements.append(
                "ALTER TABLE %s MODIFY COLUMN %s" % (qualified, _column_sql(ref))
            )
        previous = name

    # ---- 索引 ----
    local_idx = fetch_indexes(conn, database, table)
    for name, info in fetch_indexes(conn, ref_database, table).items():
        if name == "PRIMARY":
            if "PRIMARY" not in local_idx:
                statements.append(
                    "ALTER TABLE %s ADD PRIMARY KEY (%s)"
                    % (qualified, ", ".join("`%s`" % c for c in info["columns"]))
                )
            continue
        if name in local_idx:
            continue
        kind = "UNIQUE KEY" if info["unique"] else "INDEX"
        statements.append(
            "ALTER TABLE %s ADD %s `%s` (%s)"
            % (qualified, kind, name, ", ".join("`%s`" % c for c in info["columns"]))
        )

    # ---- 外键 ----
    local_fks = {
        (fk["CONSTRAINT_NAME"], fk["COLUMN_NAME"])
        for fk in fetch_foreign_keys(conn, database, table)
    }
    for fk in fetch_foreign_keys(conn, ref_database, table):
        key = (fk["CONSTRAINT_NAME"], fk["COLUMN_NAME"])
        if key in local_fks:
            continue
        statements.append(
            "ALTER TABLE %s ADD CONSTRAINT `%s` FOREIGN KEY (`%s`) "
            "REFERENCES `%s` (`%s`) ON UPDATE %s ON DELETE %s"
            % (qualified, fk["CONSTRAINT_NAME"], fk["COLUMN_NAME"],
               fk["REFERENCED_TABLE_NAME"], fk["REFERENCED_COLUMN_NAME"],
               fk["UPDATE_RULE"], fk["DELETE_RULE"])
        )
    return statements


def _has_overlong(conn, database: str, table: str, name: str, ref: dict) -> bool:
    limit = _varchar_len(ref["COLUMN_TYPE"].lower())
    if not limit:
        return False
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) c FROM `%s`.`%s` WHERE CHAR_LENGTH(`%s`) > %%s"
            % (database, table, name), (limit,),
        )
        row = cur.fetchone()
    return bool(row and int(row["c"]) > 0)


def _has_nulls(conn, database: str, table: str, name: str) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) c FROM `%s`.`%s` WHERE `%s` IS NULL"
            % (database, table, name)
        )
        row = cur.fetchone()
    return bool(row and int(row["c"]) > 0)


def align(target: MySqlTarget, ddl_path: str | Path,
          dry_run: bool = False, log=None) -> dict:
    """把 target 指向的库对齐到 init_database.sql 的结构。

    返回 {"statements": n, "failed": n, "warnings": [...]}。
    """
    say = log or (lambda _m: None)
    ddl = Path(ddl_path)
    if not ddl.is_file():
        raise FileNotFoundError("找不到建表脚本：" + str(ddl))
    ref_db = target.database + "_schema_ref"

    conn = connect(target.host, target.port, target.user, target.password, target.database)
    try:
        say("建参考库 " + ref_db)
        # 参考库用**独立连接**建：init_database.sql 里的 USE 会改连接的默认库
        ref_conn = connect(target.host, target.port, target.user, target.password)
        try:
            build_reference(ref_conn, ddl, ref_db)
        finally:
            ref_conn.close()

        ref_tables = set(list_tables(conn, ref_db))
        local_tables = set(list_tables(conn, target.database))
        warnings: list[str] = []
        statements: list[str] = []

        for table in sorted(ref_tables - local_tables):
            with conn.cursor() as cur:
                cur.execute("SHOW CREATE TABLE `%s`.`%s`" % (ref_db, table))
                row = cur.fetchone()
            create = row["Create Table"].replace(
                # 注意末尾那个反引号：它是**表名**的开引号
                "CREATE TABLE `", "CREATE TABLE IF NOT EXISTS `%s`.`" % target.database, 1
            )
            statements.append(create)
            say("  + 新建表 " + table)

        for table in sorted(ref_tables & local_tables):
            part = plan_table(conn, target.database, table, ref_db, warnings)
            if part:
                say("  ~ 对齐表 " + table + "（%d 条）" % len(part))
                statements.extend(part)

        for item in warnings:
            say("  ! " + item)

        if dry_run:
            for statement in statements:
                say("  " + statement)
            return {"statements": len(statements), "failed": 0, "warnings": warnings}

        failed = 0
        for statement in statements:
            try:
                with conn.cursor() as cur:
                    cur.execute(statement)
            except pymysql.MySQLError as exc:
                failed += 1
                say("  ! 失败：" + str(exc)[:160])
                say("    " + statement[:200])
        return {"statements": len(statements), "failed": failed, "warnings": warnings}
    finally:
        try:
            with conn.cursor() as cur:
                cur.execute("DROP DATABASE IF EXISTS " + ref_db)
        except Exception:
            pass
        conn.close()


def verify(target: MySqlTarget, ddl_path: str | Path) -> list[str]:
    """校验目标库与参考结构是否一致，返回不一致项列表（空 = 一致）。"""
    ddl = Path(ddl_path)
    ref_db = target.database + "_schema_ref"
    conn = connect(target.host, target.port, target.user, target.password, target.database)
    try:
        ref_conn = connect(target.host, target.port, target.user, target.password)
        try:
            build_reference(ref_conn, ddl, ref_db)
        finally:
            ref_conn.close()
        problems: list[str] = []
        for table in sorted(set(list_tables(conn, ref_db))):
            local_cols = {c["COLUMN_NAME"]: c for c in fetch_columns(conn, target.database, table)}
            for ref in fetch_columns(conn, ref_db, table):
                name = ref["COLUMN_NAME"]
                local = local_cols.get(name)
                if local is None:
                    problems.append("%s 缺列 %s" % (table, name))
                elif local["COLUMN_TYPE"].lower() != ref["COLUMN_TYPE"].lower() \
                        or local["IS_NULLABLE"] != ref["IS_NULLABLE"]:
                    problems.append(
                        "%s.%s 本地 %s/%s vs 服务器 %s/%s"
                        % (table, name, local["COLUMN_TYPE"], local["IS_NULLABLE"],
                           ref["COLUMN_TYPE"], ref["IS_NULLABLE"])
                    )
        return problems
    finally:
        try:
            with conn.cursor() as cur:
                cur.execute("DROP DATABASE IF EXISTS " + ref_db)
        except Exception:
            pass
        conn.close()
