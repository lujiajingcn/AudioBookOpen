"""命令行入口。"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .pipeline import Config, Organizer, Result


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="organize",
        description="把计算机类 EPUB 改写成「有声化精简版」：删代码与图表，补口语说明。",
    )
    p.add_argument("inputs", nargs="+", help="EPUB 文件，或包含 EPUB 的目录")
    p.add_argument("-o", "--outdir", default="output", help="输出目录（默认 ./output）")
    p.add_argument(
        "--mode", choices=("rewrite", "strip", "audit"), default="rewrite",
        help="rewrite=删并补说明（默认）；strip=只删不补；audit=只出报告不改文件",
    )
    p.add_argument(
        "--format", default="epub,txt",
        help="输出格式，逗号分隔，可选 epub/txt（默认 epub,txt）",
    )
    p.add_argument("--merge-threshold", type=int, default=2,
                   help="连续同类示例保留几处后开始合并（默认 2，0=不合并）")
    p.add_argument("--no-transition", action="store_true", help="不加章末「下一章……」")
    p.add_argument("--no-front-note", action="store_true", help="不加卷首的版本说明")
    p.add_argument("--json-report", default="", help="把统计写到指定 json 文件")
    p.add_argument("-q", "--quiet", action="store_true", help="只输出汇总，不逐章打印")
    return p


def collect_inputs(paths: list[str]) -> list[Path]:
    out: list[Path] = []
    for raw in paths:
        p = Path(raw)
        if p.is_dir():
            out.extend(sorted(x for x in p.rglob("*.epub") if x.is_file()))
        elif p.is_file():
            out.append(p)
        else:
            print(f"[跳过] 找不到：{raw}", file=sys.stderr)
    return out


def print_table(rows: list[dict], headers: list[str]) -> None:
    if not rows:
        return
    widths = {h: _display_width(h) for h in headers}
    for r in rows:
        for h in headers:
            widths[h] = max(widths[h], _display_width(str(r.get(h, ""))))
    line = "+" + "+".join("-" * (widths[h] + 2) for h in headers) + "+"
    print(line)
    print("| " + " | ".join(_pad(h, widths[h]) for h in headers) + " |")
    print(line)
    for r in rows:
        print("| " + " | ".join(_pad(str(r.get(h, "")), widths[h]) for h in headers) + " |")
    print(line)


def _display_width(s: str) -> int:
    return sum(2 if ord(c) > 0x2E80 else 1 for c in s)


def _pad(s: str, width: int) -> str:
    gap = width - _display_width(s)
    return s + " " * max(0, gap)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    inputs = collect_inputs(args.inputs)
    if not inputs:
        print("没有可处理的 EPUB。", file=sys.stderr)
        return 2

    formats = tuple(f.strip().lower() for f in args.format.split(",") if f.strip())
    unknown = [f for f in formats if f not in ("epub", "txt")]
    if unknown:
        print(f"不支持的输出格式：{', '.join(unknown)}", file=sys.stderr)
        return 2

    cfg = Config(
        mode=args.mode,
        formats=formats,
        merge_threshold=args.merge_threshold,
        chapter_transition=not args.no_transition,
        front_matter_note=not args.no_front_note,
        verbose=not args.quiet,
    )

    outdir = Path(args.outdir)
    all_reports: list[dict] = []
    failed = 0

    for path in inputs:
        print(f"\n▶ 处理 {path.name}")
        try:
            result = Organizer(cfg).run(path, outdir)
        except Exception as exc:  # noqa: BLE001 - 单本失败不该中断整批
            failed += 1
            print(f"  ✗ 失败：{type(exc).__name__}: {exc}", file=sys.stderr)
            continue

        if not args.quiet:
            print_table([p.as_row() for p in result.pages],
                        ["章节", "原字数", "保留字数", "压缩率", "代码块", "表格", "插图", "丢弃段"])

        rep = result.report
        all_reports.append(rep)
        print(f"  原 {rep['chars_in']} 字 → 保留 {rep['chars_out']} 字 "
              f"（{rep['compress_ratio'] * 100:.0f}%）")
        print(f"  代码块 {rep['code_blocks']} 处 / {rep['code_chars']} 字，"
              f"表格 {rep['tables']} 处，插图 {rep['images']} 处，"
              f"丢弃整页 {rep['pages_dropped']}/{rep['pages']}")
        if result.out_epub:
            print(f"  → EPUB: {result.out_epub}  ({_size(result.out_epub)})")
        if result.out_txt:
            print(f"  → TXT : {result.out_txt}  ({_size(result.out_txt)})")

    if args.json_report:
        Path(args.json_report).write_text(
            json.dumps(all_reports, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n统计已写入 {args.json_report}")

    if len(all_reports) > 1:
        print("\n=== 汇总 ===")
        print_table(
            [
                {
                    "文件": r["input"],
                    "原字数": r["chars_in"],
                    "保留字数": r["chars_out"],
                    "压缩率": f"{r['compress_ratio'] * 100:.0f}%",
                    "代码块": r["code_blocks"],
                    "表格": r["tables"],
                }
                for r in all_reports
            ],
            ["文件", "原字数", "保留字数", "压缩率", "代码块", "表格"],
        )

    return 1 if failed else 0


def _size(p: Path) -> str:
    n = p.stat().st_size
    for unit in ("B", "KB", "MB"):
        if n < 1024:
            return f"{n:.0f}{unit}"
        n /= 1024
    return f"{n:.1f}GB"
