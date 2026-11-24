"""断点续跑 / 段内心跳 / 进度落库 自检。

跑真实链路，但不碰真引擎、不碰业务库：
用独立临时库 `audiobook_db_selftest_resume`（跑完删掉）+ 假引擎（只写 0.1 秒静音 wav）。

覆盖：

1. 中途「停止」→ 任务 paused、completed_segments 是真实进度；
2. 再点「开始合成」→ **复用同一个 task_id**、只合成剩下的段（已完成的段不重调引擎）；
3. 取消「断点续跑」→ 新建任务、从第 1 段重跑；
4. 任务停在 processing（进程被强杀，没走到收尾）也能被续跑接上；
5. 长段（≥500 字）开跑前有预警；
6. 段内每 HEARTBEAT_SECONDS 有一行心跳（测试里把间隔调成 0，让每片都报）；
7. 停止在**小片之间**也生效（不必等整段跑完），该段不落库；
8. 每 10 段中途落库（不必等收尾）。
"""

from __future__ import annotations

import sys
import tempfile
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# Windows 控制台默认 GBK：日志里有中文和箭头，统一成 UTF-8
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

from app.config import AppConfig, MySqlTarget  # noqa: E402
from app.core import db, synthesizer  # noqa: E402
from app.core.synthesizer import SynthesisRequest, synthesize_book  # noqa: E402

TEST_DB = "audiobook_db_selftest_resume"
PASSWORD = "change-me-local-db"

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
    on_call = None  # 每次 synthesize 后的钩子（用于中途置停止标志）

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
        if FakeEngine.on_call:
            FakeEngine.on_call(FakeEngine.synthesized)


def reset_engine() -> None:
    FakeEngine.starts = 0
    FakeEngine.stops = 0
    FakeEngine.synthesized = 0
    FakeEngine.on_call = None


# ---------- 测试库 ----------


def _mysql_conn(database: str):
    return db.Database(
        MySqlTarget(host="127.0.0.1", port=3306, database=database,
                    user="root", password=PASSWORD)
    ).connect()


def create_test_db() -> None:
    conn = _mysql_conn("mysql")
    try:
        with conn.cursor() as cur:
            cur.execute("DROP DATABASE IF EXISTS `%s`" % TEST_DB)
            cur.execute("CREATE DATABASE `%s` CHARACTER SET utf8mb4" % TEST_DB)
        conn.commit()
    finally:
        conn.close()


def drop_test_db() -> None:
    conn = _mysql_conn("mysql")
    try:
        with conn.cursor() as cur:
            cur.execute("DROP DATABASE IF EXISTS `%s`" % TEST_DB)
        conn.commit()
    finally:
        conn.close()


def build_cfg(work_root: Path) -> AppConfig:
    cfg = AppConfig()
    cfg.local_db = MySqlTarget(
        host="127.0.0.1", port=3306, database=TEST_DB, user="root", password=PASSWORD
    )
    cfg.local_username = "local_tts"
    cfg.keep_wav = False
    cfg.work_dir = str(work_root)
    return cfg


def make_book(folder: Path, name: str, sentences: list[str]) -> Path:
    path = folder / name
    path.write_text("\n".join(sentences) + "\n", encoding="utf-8")
    return path


def tasks(cfg: AppConfig) -> list[dict]:
    conn = db.Database(cfg.local_db).connect()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id, status, total_segments, completed_segments "
                "FROM tasks ORDER BY id"
            )
            return list(cur.fetchall())
    finally:
        conn.close()


def segments(cfg: AppConfig, task_id: int) -> list[tuple[int, str]]:
    conn = db.Database(cfg.local_db).connect()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT segment_number, status FROM audio_segments "
                "WHERE task_id = %s ORDER BY segment_number",
                (task_id,),
            )
            return [(int(r["segment_number"]), str(r["status"])) for r in cur.fetchall()]
    finally:
        conn.close()


def set_status(cfg: AppConfig, task_id: int, status: str) -> None:
    conn = db.Database(cfg.local_db).connect()
    try:
        db.update_task(conn, task_id, status=status)
    finally:
        conn.close()


def run_book(cfg: AppConfig, book: Path, logs: list[str], stop_after: int = 0,
             resume: bool = True) -> tuple[object, list[str]]:
    """跑一本；stop_after>0 表示引擎被调用这么多次后置停止标志。"""
    reset_engine()
    logs.clear()
    stop_box = {"stop": False}
    if stop_after:
        FakeEngine.on_call = lambda n: stop_box.__setitem__("stop", n >= stop_after)

    # 心跳间隔改成 0：假引擎每片只要几毫秒，真实间隔下根本等不到心跳
    original_hb = synthesizer.HEARTBEAT_SECONDS
    synthesizer.HEARTBEAT_SECONDS = 0.0
    try:
        outcome = synthesize_book(
            cfg,
            SynthesisRequest(book_path=str(book), voice="demo-1"),
            on_log=logs.append,
            engine_factory=lambda s, l: FakeEngine(s, l),
            stop_flag=lambda: stop_box["stop"],
            resume=resume,
        )
    finally:
        synthesizer.HEARTBEAT_SECONDS = original_hb
        FakeEngine.on_call = None
    return outcome, logs


def main() -> int:
    print("=== 断点续跑 / 心跳 / 进度落库 自检 ===\n")
    tmp = Path(tempfile.mkdtemp(prefix="tts_resume_"))
    try:
        create_test_db()
        cfg = build_cfg(tmp / "work")
        cfg.work_dir = str(tmp / "work")
        Path(cfg.work_dir).mkdir(parents=True, exist_ok=True)

        short_book = make_book(tmp, "short.txt", [f"第{i}句话说测试。" for i in range(1, 9)])
        long_text = "目录" + "点线" * 350  # 700 字、无句号 → 必然是一段超长句
        long_book = make_book(tmp, "long.txt", [long_text, "第二句话。", "第三句话。"])

        # ---------- 1. 中途停止 → paused + 真实进度 ----------
        print("[1] 中途停止：任务标 paused、completed_segments 是真实进度")
        logs: list[str] = []
        outcome, logs = run_book(cfg, short_book, logs, stop_after=4)
        rows = tasks(cfg)
        t1 = int(rows[0]["id"])
        check("只有 1 个任务", len(rows) == 1, "实际 %d" % len(rows))
        check("状态 paused", rows[0]["status"] == "paused", str(rows[0]["status"]))
        check("completed_segments=4", int(rows[0]["completed_segments"]) == 4,
              str(rows[0]["completed_segments"]))
        check("落库 4 段且全 completed", segments(cfg, t1) == [(i, "completed") for i in range(1, 5)],
              str(segments(cfg, t1)))
        check("日志说明可续跑", any("断点续跑" in m or "接着跑" in m for m in logs))

        # ---------- 2. 续跑：同一个 task_id、只合成剩下的 ----------
        print("\n[2] 续跑：复用同一 task_id，只合成剩下的 4 段")
        outcome, logs = run_book(cfg, short_book, logs)
        rows = tasks(cfg)
        check("任务数仍是 1（没新建）", len(rows) == 1, "实际 %d" % len(rows))
        check("task_id 不变", int(rows[0]["id"]) == t1, "%d vs %d" % (int(rows[0]["id"]), t1))
        check("引擎只合成 4 次（跳过已完成的 4 段）", FakeEngine.synthesized == 4,
              "实际 %d" % FakeEngine.synthesized)
        check("outcome.skipped=4", outcome.skipped == 4, str(outcome.skipped))
        check("状态 completed", rows[0]["status"] == "completed", str(rows[0]["status"]))
        check("completed_segments=8", int(rows[0]["completed_segments"]) == 8,
              str(rows[0]["completed_segments"]))
        check("8 段齐全", len(segments(cfg, t1)) == 8, str(len(segments(cfg, t1))))
        check("日志提示已有段数", any("已有 4/8 段" in m for m in logs), "")

        # ---------- 3. 关闭续跑 → 新建任务 ----------
        print("\n[3] 取消「断点续跑」：新建任务、从第 1 段重跑")
        outcome, logs = run_book(cfg, short_book, logs, resume=False)
        rows = tasks(cfg)
        check("任务数变成 2", len(rows) == 2, "实际 %d" % len(rows))
        check("新任务 completed_segments=8", int(rows[1]["completed_segments"]) == 8,
              str(rows[1]["completed_segments"]))
        check("引擎合成 8 次（全部重跑）", FakeEngine.synthesized == 8,
              "实际 %d" % FakeEngine.synthesized)
        check("outcome.skipped=0", outcome.skipped == 0, str(outcome.skipped))

        # ---------- 4. processing（被强杀）也能接上 ----------
        print("\n[4] 任务停在 processing（进程被强杀）：续跑能接上")
        outcome, _ = run_book(cfg, short_book, logs, stop_after=3, resume=False)
        t3 = int(tasks(cfg)[-1]["id"])
        set_status(cfg, t3, "processing")   # 假装上次是硬中断，没写收尾状态
        outcome, logs = run_book(cfg, short_book, logs)
        rows = tasks(cfg)
        check("没有新建任务", int(rows[-1]["id"]) == t3, "task_id %d" % t3)
        check("跳过 3 段、只合成 5 段", FakeEngine.synthesized == 5,
              "实际 %d" % FakeEngine.synthesized)
        check("最终状态 completed", rows[-1]["status"] == "completed", str(rows[-1]["status"]))

        # ---------- 5. 长段预警 + 段内心跳 ----------
        print("\n[5] 长段预警 + 段内心跳")
        outcome, logs = run_book(cfg, long_book, logs)
        check("有超长段预警", any("超长段" in m for m in logs))
        check("预警里写了片数与预估", any("片，约" in m for m in logs),
              next((m for m in logs if "片，约" in m), ""))
        check("有段内心跳（段 x/y · 片 i/n · 本段已 …）",
              any("本段已" in m and "片 " in m for m in logs),
              next((m for m in logs if "本段已" in m), ""))
        check("有「第 1 段完成：N 片」", any("段完成：" in m for m in logs),
              next((m for m in logs if "段完成：" in m), ""))
        check("长书也跑完了", outcome.failed == 0 and outcome.stopped is False,
              f"failed={outcome.failed}")

        # ---------- 6. 小片之间可停止 ----------
        print("\n[6] 停止在「小片之间」生效：该段不落库")
        outcome, logs = run_book(cfg, long_book, logs, stop_after=3, resume=False)
        tid = int(tasks(cfg)[-1]["id"])
        check("中途停了", outcome.stopped is True)
        check("第 1 段没落库（缺片=半截音频，不能留）",
              segments(cfg, tid) == [], str(segments(cfg, tid)))
        check("日志说明该段会重做", any("中间中断" in m for m in logs),
              next((m for m in logs if "中间中断" in m), ""))
        check("引擎只被调了 3 次", FakeEngine.synthesized == 3, "实际 %d" % FakeEngine.synthesized)

        # ---------- 7. 进度中途落库 ----------
        print("\n[7] 每 10 段中途落库（不必等收尾）")
        many = make_book(tmp, "many.txt", [f"第{i}句。" for i in range(1, 31)])
        seen: list[int] = []
        reset_engine()
        stop_box = {"stop": False}

        def peek(done: int, total: int, current: str) -> None:
            # 在「第 12 段已合成、但还没到第 20 段」时查库：
            # 若只在收尾写，这里会读到 0
            if done == 12 and not seen:
                rows = tasks(cfg)
                seen.append(int(rows[-1]["completed_segments"]))

        original_hb = synthesizer.HEARTBEAT_SECONDS
        synthesizer.HEARTBEAT_SECONDS = 1e9  # 这个用例不需要心跳
        try:
            synthesize_book(
                cfg,
                SynthesisRequest(book_path=str(many), voice="demo-1"),
                on_log=lambda _m: None,
                on_progress=peek,
                engine_factory=lambda s, l: FakeEngine(s, l),
                stop_flag=lambda: stop_box["stop"],
            )
        finally:
            synthesizer.HEARTBEAT_SECONDS = original_hb

        check("跑到第 12 段时库里已有 ≥10 段", bool(seen) and seen[0] >= 10,
              "读到 %s" % (seen or ["未取到"]))
        final = tasks(cfg)[-1]
        check("30 段全部完成", int(final["completed_segments"]) == 30 and
              final["status"] == "completed",
              "%s / %s" % (final["completed_segments"], final["status"]))

        print()
        if FAILS:
            print("FAILED (%d): %s" % (len(FAILS), "、".join(FAILS)))
            return 1
        print("ALL PASS")
        return 0
    finally:
        try:
            drop_test_db()
        except Exception as exc:  # noqa: BLE001
            print("（清理测试库失败：%s）" % exc)
        import shutil

        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
