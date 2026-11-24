"""批量合成（多选电子书）自检。

跑一遍真实链路，但**不碰真引擎、不碰业务库**：

* 用独立的临时库 `audiobook_db_selftest`（跑完删掉），不污染本地业务库；
* 用假的引擎（只写一段静音 wav）替代 MOSS，验证编排逻辑而不是音质。

覆盖四个点：

1. 多本书 → 多个任务，每本书的段号从 1 开始、互不干扰；
2. **整个批次只加载一次模型**（引擎 start 计数 == 1）；
3. 某本书坏了（格式不支持）**不中断整批**：其余书照常合成，坏的单独记原因；
4. 中途「停止」：当前书标记已中止，剩下的书跳过。
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# Windows 控制台默认 GBK：书名里带生僻字时 print 会崩 —— 统一改成 UTF-8 + 替换符兜底。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

from app.config import AppConfig, MySqlTarget  # noqa: E402
from app.core import db  # noqa: E402
from app.core.synthesizer import (  # noqa: E402
    SynthesisRequest,
    synthesize_books,
)

TEST_DB = "audiobook_db_selftest"

FAILS: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print("  [%s] %s%s" % ("OK" if ok else "FAIL", name, ("　" + detail) if detail else ""))
    if not ok:
        FAILS.append(name)


class FakeEngine:
    """假引擎：只写出文件，不做真实合成。"""

    starts = 0
    stops = 0
    synthesized = 0

    def __init__(self, settings, on_log=None) -> None:
        self._on_log = on_log

    def start(self) -> None:
        FakeEngine.starts += 1

    def stop(self) -> None:
        FakeEngine.stops += 1

    def synthesize(self, text, out_path, voice=None, prompt_audio_path=None,
                   seed=0, max_new_frames=0) -> None:
        FakeEngine.synthesized += 1
        path = Path(out_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(22050)
            wav.writeframes(b"\x00\x00" * 2205)  # 0.1 秒静音


def make_book(folder: Path, name: str, sentences: list[str]) -> Path:
    path = folder / name
    path.write_text("\n".join(sentences) + "\n", encoding="utf-8")
    return path


def build_cfg(book_dir: Path) -> AppConfig:
    cfg = AppConfig()
    cfg.local_db = MySqlTarget(
        host="127.0.0.1", port=3306, database=TEST_DB, user="root", password="change-me-local-db"
    )
    cfg.local_username = "local_tts"
    cfg.keep_wav = False
    cfg.work_dir = str(book_dir / "work")
    return cfg


def drop_test_db() -> None:
    conn = db.Database(
        MySqlTarget(host="127.0.0.1", port=3306, database="mysql", user="root",
                    password="change-me-local-db")
    ).connect()
    try:
        with conn.cursor() as cur:
            cur.execute("DROP DATABASE IF EXISTS `%s`" % TEST_DB)
        conn.commit()
    finally:
        conn.close()


def create_test_db() -> None:
    conn = db.Database(
        MySqlTarget(host="127.0.0.1", port=3306, database="mysql", user="root",
                    password="change-me-local-db")
    ).connect()
    try:
        with conn.cursor() as cur:
            cur.execute("DROP DATABASE IF EXISTS `%s`" % TEST_DB)
            cur.execute("CREATE DATABASE `%s` CHARACTER SET utf8mb4" % TEST_DB)
        conn.commit()
    finally:
        conn.close()


def task_rows(cfg: AppConfig) -> list[dict]:
    database = db.Database(cfg.local_db)
    conn = database.connect()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id, original_book_name, status, total_segments, completed_segments "
                        "FROM tasks ORDER BY id")
            return list(cur.fetchall())
    finally:
        conn.close()


def segment_counts(cfg: AppConfig, task_id: int) -> list[tuple[int, str]]:
    database = db.Database(cfg.local_db)
    conn = database.connect()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT segment_number, status FROM audio_segments "
                        "WHERE task_id = %s ORDER BY segment_number", (task_id,))
            return [(int(r["segment_number"]), str(r["status"])) for r in cur.fetchall()]
    finally:
        conn.close()


def main() -> int:
    print("=== 批量合成自检 ===\n")
    tmp = Path(tempfile.mkdtemp(prefix="mobi_batch_"))
    try:
        create_test_db()
        cfg = build_cfg(tmp)

        books = [
            make_book(tmp, "a.txt", ["第一本第一句。", "第一本第二句。"]),
            make_book(tmp, "b.txt", ["第二本第一句。", "第二本第二句。", "第二本第三句。"]),
            make_book(tmp, "c.txt", ["第三本第一句。"]),
        ]
        requests = [SynthesisRequest(book_path=str(p), voice="demo-1") for p in books]

        print("[1] 多本书 → 多个任务")
        FakeEngine.starts = 0
        FakeEngine.stops = 0
        FakeEngine.synthesized = 0
        batch = synthesize_books(cfg, requests, engine_factory=lambda s, l: FakeEngine(s, l))
        rows = task_rows(cfg)
        check("生成 3 个任务", len(rows) == 3, "实际 %d" % len(rows))
        check("书名逐个对应（落库用的是去后缀的文件名）",
              [r["original_book_name"] for r in rows] == ["a", "b", "c"],
              str([r["original_book_name"] for r in rows]))
        check("每个任务状态 completed",
              all(r["status"] == "completed" for r in rows),
              str([r["status"] for r in rows]))
        check("段数正确（2/3/1）",
              [int(r["total_segments"]) for r in rows] == [2, 3, 1],
              str([r["total_segments"] for r in rows]))
        check("每本书的段号都从 1 开始",
              all([n for n, _ in segment_counts(cfg, int(r["id"]))] == list(range(1, int(r["total_segments"]) + 1))
                  for r in rows))
        check("全部段落 completed",
              all(st == "completed" for r in rows for _, st in segment_counts(cfg, int(r["id"]))))

        print("[2] 整个批次只加载一次模型")
        check("引擎 start 调用 1 次", FakeEngine.starts == 1, "实际 %d" % FakeEngine.starts)
        check("引擎 stop 调用 1 次", FakeEngine.stops == 1, "实际 %d" % FakeEngine.stops)
        check("合成调用次数 = 总段数", FakeEngine.synthesized == 6,
              "实际 %d" % FakeEngine.synthesized)

        print("[3] 某本书坏了不中断整批")
        bad = SynthesisRequest(book_path=str(tmp / "bad.xyz"), voice="demo-1")
        FakeEngine.starts = 0
        batch2 = synthesize_books(cfg, [bad, requests[0]], engine_factory=lambda s, l: FakeEngine(s, l))
        check("失败 1 本", len(batch2.failed_books) == 1, str([r.error for r in batch2.failed_books]))
        check("其余书照常合成", len(batch2.succeeded_books) == 1)
        check("失败原因写进结果", bool(batch2.failed_books[0].error))

        print("[4] 中途停止：剩余的书跳过")
        stop = {"flag": False}

        def on_progress(done: int, total: int, current: str) -> None:
            # 第一本书合成完的那一刻按下「停止」
            if done >= total:
                stop["flag"] = True

        batch3 = synthesize_books(cfg, requests, stop_flag=lambda: stop["flag"],
                                  on_progress=on_progress,
                                  engine_factory=lambda s, l: FakeEngine(s, l))
        check("标记为已停止", batch3.stopped)
        check("后面的书被跳过", len(batch3.skipped_books) >= 1,
              str([Path(r.book_path).name for r in batch3.skipped_books]))

        print("[5] 界面上的多选解析")
        try:
            os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
            from PySide6.QtWidgets import QApplication

            from app.ui.main_window import MainWindow

            app = QApplication.instance() or QApplication([])
            win = MainWindow()
            try:
                win.ed_book.setText("; ".join(str(p) for p in books))
                check("多选解析出 3 本", len(win._selected_books()) == 3,
                      str(win._selected_books()))
                win.ed_book.setText("%s;%s" % (books[0], books[0]))
                check("重复路径去重", len(win._selected_books()) == 1)
                win.ed_book.setText("%s;%s" % (books[0], tmp / "nope.txt"))
                check("不存在的路径被剔除", len(win._selected_books()) == 1)
                win.ed_book.setText("")
                check("空输入 = 0 本", win._selected_books() == [])
                check("占位文案提到可多选", "可多选" in win.ed_book.placeholderText())
            finally:
                win.close()
        except ImportError as exc:
            print("  [跳过] 没装 PySide6：%s" % exc)

        return 1 if FAILS else 0
    finally:
        drop_test_db()
        shutil.rmtree(tmp, ignore_errors=True)
        print("\n（临时库 %s 已删除）" % TEST_DB)
        if FAILS:
            print("失败 %d 项：%s" % (len(FAILS), "、".join(FAILS)))
        else:
            print("全部通过。")


if __name__ == "__main__":
    raise SystemExit(main())
