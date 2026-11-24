"""存量修复：把**已经落库**的旧段落里的换行清掉，并重新合成这些段的音频。

## 为什么需要这个脚本

切句规则改成「去掉句内换行」之后，**新合成**的书不再有问题；
但**已经合成过的旧数据**里，段的 `text_content` 还带着 PDF 的版面换行，
音频也在换行处停了一下 —— 用户听旧书时依然会觉得「这里怎么顿了一下」。

例：本地库 task 3（《潜规则》PDF）的前 84 段里有 67 段带换行，
其中一段是：

    当记者后啃过《汉\n书》和《后汉书》 ，完全被乱七八糟的人名和事件淹没了……

只改数据库里的文本**不能**消掉音频里的停顿 —— 停顿在音频字节里。
所以这个脚本做两件事：

1. 用与服务端/工具完全相同的规则（`clean_inner_newlines`）清掉段文本里的换行；
2. 用**同样的音色、同样的 seed、同样的拆片与帧数预算**重新合成这几段，
   把新的音频字节覆盖回原行（段号、行 id 都不变 ⇒ 客户端的「段号 ↔ 行号」映射不受影响）。

## 安全边界

* **先备份**：动过的行（含旧音频字节，base64）写到
  `%APPDATA%/AudioBookLocalTtsTool/backup/task_<id>_<时间戳>.json`，随时可回滚；
* **只动带换行的段**：其它段一行都不碰；
* **段号不变**：不重排、不删除、不新增段；
* `--dry-run` 只报告不改库；`--no-audio` 只清文本（不重合成，停顿仍在）；
* 默认只改**本地库**；要不要推到服务器由你自己决定（工具里的「上传选中」）。

## 跑法

```bash
# 先看看会动哪些段
python tools/repair_legacy_newlines.py --task 3 --dry-run

# 正式修（重合成 + 覆盖音频）
python tools/repair_legacy_newlines.py --task 3

# 参考音频不在 MOSS 工程里时手动指定
python tools/repair_legacy_newlines.py --task 3 --reference "D:\\voice\\zh_6.wav"

# 只清文本、不重合成（快，但音频里的停顿还在）
python tools/repair_legacy_newlines.py --task 3 --no-audio
```
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

from app.config import AppConfig  # noqa: E402
from app.core import db  # noqa: E402
from app.core.audio import (  # noqa: E402
    concat_wavs,
    convert_to_mp3,
    find_ffmpeg,
    wav_duration,
    write_silent_wav,
)
from app.core.segmenter import clean_inner_newlines, has_readable_content, sub_pieces  # noqa: E402
from app.core.synthesizer import EngineSession, prepare_reference, resolve_max_new_frames  # noqa: E402


# ---------------------------------------------------------------- 工具

def backup_dir() -> Path:
    base = os.environ.get("APPDATA") or str(Path.home())
    path = Path(base) / "AudioBookLocalTtsTool" / "backup"
    path.mkdir(parents=True, exist_ok=True)
    return path


def find_reference(cfg: AppConfig, name: str | None) -> str | None:
    """按文件名在 MOSS 工程的 assets/audio 里找参考音频。"""
    if not name:
        return None
    direct = Path(name)
    if direct.is_file():
        return str(direct)
    project = Path(cfg.moss.project_path or "")
    for folder in (project / "assets" / "audio", project / "assets"):
        if not folder.is_dir():
            continue
        for candidate in folder.glob("*.wav"):
            if candidate.name.lower() == name.lower():
                return str(candidate)
    return None


def fetch_dirty_segments(conn, task_ids: list[int] | None) -> list[dict]:
    """取出 text_content 里带换行的段（带音频字节，用于备份）。"""
    sql = (
        "SELECT id, task_id, segment_number, text_content, audio_data, "
        "audio_file_path, duration_seconds, status, voice "
        "FROM audio_segments WHERE text_content LIKE '%%\\n%%' "
        "OR text_content LIKE '%%\\r%%'"
    )
    params: tuple = ()
    if task_ids:
        sql += " AND task_id IN (" + ",".join(["%s"] * len(task_ids)) + ")"
        params = tuple(task_ids)
    sql += " ORDER BY task_id, segment_number"
    with conn.cursor() as cur:
        cur.execute(sql, params)
        # 注意：Database 用的是 DictCursor，fetchall() 直接给 list[dict]
        return [dict(row) for row in cur.fetchall()]


def write_backup(rows: list[dict], task_label: str) -> Path:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    path = backup_dir() / f"task_{task_label}_{stamp}.json"
    payload = {
        "saved_at": stamp,
        "rows": [
            {
                **{k: v for k, v in row.items() if k != "audio_data"},
                "audio_data_b64": (
                    base64.b64encode(row["audio_data"]).decode("ascii")
                    if row.get("audio_data")
                    else None
                ),
            }
            for row in rows
        ],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


# ---------------------------------------------------------------- 主流程

def main() -> int:
    parser = argparse.ArgumentParser(description="修复存量段落里的换行（并重合成音频）")
    parser.add_argument("--task", default="all",
                        help="任务 id（逗号分隔）；all = 所有含换行的段")
    parser.add_argument("--reference", default=None, help="参考音频路径（默认按任务记录的文件名找）")
    parser.add_argument("--voice", default=None, help="内置音色 id（不给就用参考音频克隆）")
    parser.add_argument("--limit", type=int, default=0, help="最多处理多少段（0 = 不限）")
    parser.add_argument("--dry-run", action="store_true", help="只报告，不改库")
    parser.add_argument("--no-audio", action="store_true", help="只清文本，不重合成（停顿仍在音频里）")
    args = parser.parse_args()

    task_ids: list[int] | None = None
    if args.task.strip().lower() != "all":
        task_ids = [int(x) for x in args.task.replace("，", ",").split(",") if x.strip()]

    cfg = AppConfig.load()
    database = db.Database(cfg.local_db)
    conn = database.connect()
    try:
        rows = fetch_dirty_segments(conn, task_ids)
        if not rows:
            print("没有带换行的段 —— 库里已经是干净的。")
            return 0
        print("带换行的段共 %d 条：" % len(rows))
        for row in rows[:10]:
            preview = row["text_content"].replace("\n", "\\n").replace("\r", "\\r")
            print("  task=%s 段号=%s  %s" % (row["task_id"], row["segment_number"], preview[:60]))
        if len(rows) > 10:
            print("  …… 其余 %d 条省略" % (len(rows) - 10))

        if args.dry_run:
            print("（--dry-run：没有改动任何数据）")
            return 0

        backup = write_backup(rows, args.task.replace(",", "_"))
        print("已备份到：%s" % backup)

        # ---------- 文本清理 ----------
        cleaned = []
        for row in rows:
            new_text = clean_inner_newlines(row["text_content"])
            if new_text != row["text_content"]:
                cleaned.append((row, new_text))
        print("文本需要清理：%d 条" % len(cleaned))

        if args.no_audio:
            with conn.cursor() as cur:
                for row, new_text in cleaned:
                    cur.execute(
                        "UPDATE audio_segments SET text_content = %%s, updated_at = NOW() "
                        "WHERE id = %%s", (new_text, row["id"]))
            conn.commit()
            print("已更新 %d 条文本（--no-audio：音频未重合成，停顿仍在）" % len(cleaned))
            return 0

        # ---------- 重合成 ----------
        with conn.cursor() as cur:
            cur.execute("SELECT reference_audio_path, default_voice_id FROM tasks "
                        "WHERE id = %s", (rows[0]["task_id"],))
            task_row = cur.fetchone()
        reference = args.reference or find_reference(cfg, task_row["reference_audio_path"] if task_row else None)
        voice = args.voice or (task_row["default_voice_id"] if task_row else None)
        print("参考音频：%s" % (reference or "（无 → 用内置音色 %s）" % voice))
        if not reference and not voice:
            print("找不到参考音频也没有内置音色 —— 无法合成。请用 --reference 指定。")
            return 2

        work_dir = Path(cfg.work_dir or (Path(os.environ.get("TEMP", ".")) /
                                         "audiobook_local_tts" / "repair"))
        work_dir.mkdir(parents=True, exist_ok=True)
        reference_wav = prepare_reference(cfg, reference, work_dir) if reference else None

        ffmpeg = find_ffmpeg(cfg.ffmpeg_path)
        if not ffmpeg:
            print("未找到 ffmpeg：音频保留 WAV 格式（服务端照样能播）")

        session = EngineSession(cfg, lambda m: print("  [引擎] %s" % m))
        engine = session.get()
        print("引擎已就绪，开始重合成……")
        started = time.time()
        done = 0
        failed = 0
        try:
            for row, new_text in cleaned:
                index = int(row["segment_number"])
                wav_path = work_dir / f"repair_{row['id']:06d}.wav"
                if not has_readable_content(new_text):
                    write_silent_wav(wav_path)
                else:
                    pieces = [p for p in sub_pieces(new_text) if has_readable_content(p)]
                    if not pieces:
                        write_silent_wav(wav_path)
                    else:
                        piece_paths = []
                        for piece_index, piece in enumerate(pieces):
                            piece_wav = work_dir / f"repair_{row['id']:06d}_{piece_index:02d}.wav"
                            engine.synthesize(
                                text=piece,
                                out_path=piece_wav,
                                voice=voice,
                                prompt_audio_path=reference_wav,
                                seed=1234 + index * 100 + piece_index,
                                max_new_frames=resolve_max_new_frames(piece),
                            )
                            if Path(piece_wav).is_file():
                                piece_paths.append(Path(piece_wav))
                        if not piece_paths:
                            print("  段号 %s 合成失败：引擎没产出音频" % index)
                            failed += 1
                            continue
                        if len(piece_paths) == 1:
                            piece_paths[0].replace(wav_path)
                        else:
                            concat_wavs(piece_paths, wav_path)

                suffix = ".mp3"
                if ffmpeg:
                    mp3_path = work_dir / f"repair_{row['id']:06d}.mp3"
                    if not convert_to_mp3(wav_path, mp3_path, ffmpeg):
                        raise RuntimeError("ffmpeg 转 mp3 失败")
                    audio_bytes = mp3_path.read_bytes()
                else:
                    suffix = ".wav"
                    audio_bytes = wav_path.read_bytes()

                with conn.cursor() as cur:
                    cur.execute(
                        "UPDATE audio_segments SET text_content = %s, audio_data = %s, "
                        "audio_file_path = %s, duration_seconds = %s, status = 'completed', "
                        "error_message = NULL, updated_at = NOW() WHERE id = %s",
                        (new_text, audio_bytes,
                         db.segment_audio_path(int(row["task_id"]), index, suffix),
                         wav_duration(wav_path), row["id"]),
                    )
                conn.commit()
                done += 1
                print("  段号 %s 已重合成（%d/%d，耗时 %.1f 秒）"
                      % (index, done, len(cleaned), time.time() - started))
        finally:
            session.close()

        print("完成：重合成 %d 段，失败 %d 段，总耗时 %.1f 秒" % (done, failed, time.time() - started))
        print("备份文件：%s（需要回滚时照着它还原）" % backup)
        return 0 if failed == 0 else 1
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
