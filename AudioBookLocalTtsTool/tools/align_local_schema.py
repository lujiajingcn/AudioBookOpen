"""命令行入口：把本地 MySQL 的表结构对齐到 AudioBookServer 的结构。

真正的实现见 `app/core/schema_align.py`（界面上的「对齐结构」按钮也用它）。

```bash
python tools/align_local_schema.py --host 127.0.0.1 --port 3306 \\
    --user root --password xxx --database audiobook_db \\
    --ddl "E:\\path\\to\\AudioBookServer\\init_database.sql"

python tools/align_local_schema.py ... --dry-run    # 只看差异
python tools/align_local_schema.py ... --verify     # 只校验是否已一致
```
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import MySqlTarget  # noqa: E402
from app.core import schema_align  # noqa: E402


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="把本地 MySQL 表结构对齐到 AudioBookServer 的结构"
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="")
    parser.add_argument("--database", default="audiobook_db")
    parser.add_argument(
        "--ddl", required=True,
        help="AudioBookServer/init_database.sql 的路径（目标结构的权威定义）",
    )
    parser.add_argument("--dry-run", action="store_true", help="只看差异，不执行")
    parser.add_argument("--verify", action="store_true", help="只校验是否已对齐")
    args = parser.parse_args(argv)

    target = MySqlTarget(
        host=args.host, port=args.port, database=args.database,
        user=args.user, password=args.password,
    )
    if args.verify:
        problems = schema_align.verify(target, args.ddl)
        for item in problems:
            print("  ! " + item)
        print("\n校验结果：%s" % ("全部一致" if not problems else "%d 处不一致" % len(problems)))
        return 0 if not problems else 1

    result = schema_align.align(target, args.ddl, dry_run=args.dry_run, log=print)
    if args.dry_run:
        print("\n--dry-run：将执行 %d 条语句" % result["statements"])
        return 0
    print("\n完成：成功 %d 条，失败 %d 条" % (
        result["statements"] - result["failed"], result["failed"]))
    return 1 if result["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
