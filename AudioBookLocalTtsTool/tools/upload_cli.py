"""命令行发布：不开界面，直接把本地合成的书发布到书城（或传到个人书架）。

## 为什么要有它

界面里点「一键发布」在后台线程跑，长任务（上千段、88MB）一关窗口就断；
而且自动化场景（脚本、定时任务、远程跑）根本点不到按钮。这个入口复用
`app.core.uploader.upload_tasks` 的同一条链路，只把「选原书文件」这一步
从文件对话框换成命令行参数。

## 用法

    # 发布到书城（默认）：指定本地任务 + 原书
    python tools/upload_cli.py --task 2 --book "E:/本地合成的电子书/论持久战全文.pdf"

    # 发布到书城并指定分类（默认取配置里的 store_category）
    python tools/upload_cli.py --task 2 --book ... --category 军事

    # 传到个人书架（旧行为：书只出现在那个账号的「我的书籍」里）
    python tools/upload_cli.py --task 2 --book ... --target shelf --user audiobook

    # 先干跑，看会发哪些、缺不缺原书
    python tools/upload_cli.py --task 2 --book ... --dry-run

日志一律同时写 stdout 与 `--log` 指定的文件（默认 tools/upload_cli.log），
因为这台机器上长时间命令的输出常被截断，事后只能靠日志文件复盘。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# 允许 `python tools/upload_cli.py` 直接跑（把工程根加进 sys.path）
ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.config import AppConfig  # noqa: E402
from app.core.uploader import find_book_file, plan_upload, upload_tasks  # noqa: E402


def _parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="把本地合成的电子书发布到书城（或上传到个人书架）"
    )
    p.add_argument("--task", type=int, action="append", default=[],
                   help="本地任务 id，可重复；不给则处理全部未发布过的任务")
    p.add_argument("--book", action="append", default=[],
                   help="原书文件路径，可重复；按出现顺序配给 --task（数量一致时一一对应）")
    p.add_argument("--target", choices=("store", "shelf"), default="",
                   help="store = 发布到书城（默认，取配置）；shelf = 个人书架")
    p.add_argument("--category", default="", help="书城分类（仅 store 模式，默认取配置）")
    p.add_argument("--title", default="",
                   help="书城书名（不填按本地任务名推；本地 PDF 文件名常常是 `[书名].作者`）")
    p.add_argument("--author", default="", help="书城作者（不填记「佚名」，之后可在管理端改）")
    p.add_argument("--user", default="",
                   help="归属账号：shelf 模式=任务归属用户名；store 模式=书城归属管理员（默认 audiobook_admin）")
    p.add_argument("--force", action="store_true", help="忽略已发布记录，强制重发")
    p.add_argument("--api-base", default="",
                   help="管理端地址（发布收尾补正文快照用；默认 http://<SSH 主机>）")
    p.add_argument("--dry-run", action="store_true", help="只列计划与缺件，不真的发布")
    p.add_argument("--log", default=str(Path(__file__).with_name("upload_cli.log")),
                   help="日志文件（默认 tools/upload_cli.log）")
    return p


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    cfg = AppConfig.load()

    log_path = Path(args.log).expanduser()
    log_path.parent.mkdir(parents=True, exist_ok=True)
    fh = log_path.open("a", encoding="utf-8")

    def log(message: str) -> None:
        print(message, flush=True)
        fh.write(message + "\n")
        fh.flush()

    task_ids = [int(t) for t in args.task] or None
    books_in = [str(Path(b).expanduser()) for b in args.book]

    # 命令行覆盖配置（不改盘上的配置，只影响这一次）
    target = args.target or cfg.publish_target or "store"
    if args.category:
        cfg.store_category = args.category
    if args.user:
        if target == "shelf":
            cfg.server_username = args.user
        else:
            cfg.store_owner_username = args.user
    if args.api_base:
        cfg.server_api_base = args.api_base
    where = "书城" if target == "store" else "个人书架"
    owner = cfg.store_owner_username if target == "store" else cfg.server_username

    planned = plan_upload(cfg, task_ids, args.force)
    if not planned:
        log("没有需要发布的任务（都已发布过；要重发请加 --force）")
        fh.close()
        return 0

    log(f"发布目标：{where}（任务归属 {owner}）")
    if target == "store":
        log(f"书城分类：{cfg.store_category}")
        log(f"管理端地址：{cfg.server_api_base or ('http://' + cfg.ssh.host)}"
            "（发布收尾会补一次正文快照）")
        if args.title:
            log(f"书名覆盖：{args.title}（作者 {args.author or '佚名'}）")
    log(f"本次计划发布 {len(planned)} 个任务：")
    book_files: dict[int, str] = {}
    missing: list[int] = []
    for index, task in enumerate(planned):
        task_id = int(task["id"])
        manual = books_in[index] if index < len(books_in) else None
        found = Path(manual) if manual else find_book_file(cfg, task)
        exists = bool(found) and Path(found).is_file()
        log(
            f"  - 本地任务 {task_id}《{task.get('original_book_name')}》"
            f" {task.get('completed_segments')}/{task.get('total_segments')} 段"
            f" · 原书 {found.name if found else '(缺)'}{'' if exists else ' ❌ 不存在'}"
        )
        if exists:
            book_files[task_id] = str(found)
        else:
            missing.append(task_id)

    if missing:
        log(f"缺原书文件的任务：{missing}（发布前必须指定 --book，否则书能听不能读）")
        fh.close()
        return 2
    if args.dry_run:
        log("dry-run：未执行发布")
        fh.close()
        return 0

    # 注意：日志句柄必须留到「汇总打印」之后再关。曾经写成
    # `try: upload ... finally: fh.close()`，结果发布成功却在写汇总行时抛
    # `ValueError: I/O operation on closed file`，退出码变成非 0，
    # 让已经在服务器上完成的上传看起来像失败了。
    # 元数据覆盖：--title/--author 只在「单本」时有确定语义，多本时会让人困惑，直接拒绝
    meta: dict[int, dict] = {}
    if args.title or args.author:
        if len(planned) != 1:
            log(f"--title/--author 只能配合单个任务使用（当前 {len(planned)} 个）")
            fh.close()
            return 2
        meta[int(planned[0]["id"])] = {"title": args.title, "author": args.author}

    outcome = upload_tasks(
        cfg,
        local_task_ids=task_ids,
        force=args.force,
        on_log=log,
        book_files=book_files,
        target=target,
        meta=meta,
    )

    log(
        f"结束：成功 {outcome.ok_count} 个，跳过 {len(outcome.skipped)} 个，"
        f"失败 {outcome.failed_count} 个"
    )
    for local_id, server_id, store_id in outcome.published:
        log(f"  本地 {local_id} → 服务器任务 {server_id} → 书城书籍 #{store_id}")
    for local_id, server_id in outcome.uploaded:
        if not any(p[0] == local_id for p in outcome.published):
            log(f"  本地 {local_id} → 服务器任务 {server_id}（个人书架）")
    for local_id, message in outcome.failed:
        log(f"  本地 {local_id} 失败：{message}")
    fh.close()
    return 0 if not outcome.failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
