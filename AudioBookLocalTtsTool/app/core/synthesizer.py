"""合成编排：把「一本电子书 + 一个参考音频」变成「本地 MySQL 里的一个任务」。

## 流程（与服务端同构，只是把云端换成本机）

    读电子书正文 → 按整句切段（与服务端同一口径）
      → 逐段送 MOSS 引擎合成（参考音频克隆 或 内置音色）
      → 转 mp3 → 写本地库 tasks + audio_segments
      → 任务状态置 completed

段号从 1 开始、`(task_id, segment_number)` 唯一 —— 与服务端完全一致，
这样本地产物可以直接推给服务器，不需要任何转换。

## 为什么参考音频要在本地先裁成 ≤8 秒

服务端入库前会把参考音频裁到 `moss.tts.reference-max-seconds`（默认 8s）。
工具不裁的话，本地合成的音色与服务器上同一份参考音频合出来的音色会有差别
（模型对 prompt 长度是敏感的），两边听起来不是一个人。
所以这里按同样的 8 秒上限裁剪。
"""

from __future__ import annotations

import shutil
import subprocess
import tempfile
import time
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from app.config import AppConfig, MossSettings
from app.core import db
from app.core.audio import concat_wavs, convert_to_mp3, find_ffmpeg, wav_duration, write_silent_wav
from app.core.booktext import book_title, extract_text
from app.core.moss_client import MossEngine, MossEngineError
from app.core.segmenter import has_readable_content, sentences, sub_pieces

# 参考音频时长上限（对齐服务端 moss.tts.reference-max-seconds，默认 8 秒）
REFERENCE_MAX_SECONDS = 8.0

# 参考音频最短时长（低于 2 秒克隆不出稳定音色）
REFERENCE_MIN_SECONDS = 2.0

# ---------- 生成帧数（与服务端 moss.tts.frames 一致） ----------
# 服务端公式：帧数 = base + 字符数 × per-char，再夹到 [min, max]
#   base=30、per-char=4.0、min=30、max=375（application.yml）
# 换算依据：375 帧 ≈ 30 秒 ⇒ 12.5 帧/秒；4.0 帧/字符 ≈ 3.1 字/秒，
# 比正常朗读慢 30%+，宁可多给几帧也不把句子截断。
# **上限刻意取 375**：这样任何文本的音频都不会比「一律 375 帧」更短 ——
# 短文本省时间的收益不能以「长文本被截断」为代价。
FRAMES_BASE = 30
FRAMES_PER_CHAR = 4.0
FRAMES_MIN = 30
FRAMES_MAX = 375


def resolve_max_new_frames(text: str) -> int:
    """按文本长度推算本次合成的 max_new_frames（与服务端口径一致）。

    服务端是「按小片（≤45 字）下发」的，工具也按小片下发 ——
    帧数是「一次请求」的预算，长句拆片后每片各要一份。
    """
    length = len(text or "")
    desired = int(round(30 + length * 4.0))
    return max(FRAMES_MIN, min(desired, FRAMES_MAX))


# ---------- 长段预警 / 心跳 / 进度落库 ----------
# 这三个都只为解决同一个问题：**长时间没有输出 = 看起来像卡死**。
# 一本书 3329 段跑 20 多小时，第 1 段却是 5217 字的封面+目录（拆 48 片、17 分钟），
# 期间界面一个字不动、进度条恒 0/3329 —— 用户只能以为它挂了。

# 超过这个字数就算「长段」，开跑前预警（实测 500 字 ≈ 11 片 ≈ 4 分钟）
LONG_SEGMENT_CHARS = 500

# 段内片级心跳的间隔（秒）。顶栏刷新要密（人眼看着"在动"），日志要疏
# （一本书 3329 段，日志每 5 秒一行会刷掉真正有用的信息）。
HEARTBEAT_SECONDS = 5.0
HEARTBEAT_LOG_SECONDS = 30.0

# 多少个片子以上才值得心跳。1 片的段（占绝大多数）根本没"段内"可报，
# 它们的反馈来自段级进度条和每 10 段一行汇总。
HEARTBEAT_MIN_PIECES = 3

# 单小片耗时预估（秒）：只在「还没跑起来、没有实测速度」时用于粗估。
# 依据实测：48 片跑了 17 分钟 ≈ 21 秒/片（16 核 CPU、RTF≈3x），取 20 保守。
SECONDS_PER_PIECE_ESTIMATE = 20.0

# 进度落库间隔（段）：让 tasks.completed_segments 中途就能看出跑到哪，
# 而不是只在收尾时写一次（那样崩溃后库里恒为 0）。
PROGRESS_COMMIT_INTERVAL = 10


def format_duration(seconds: float) -> str:
    """把秒数格式化成中文时长（用于日志里的"约 xx"与 ETA）。"""
    seconds = max(0.0, float(seconds))
    if seconds >= 3600:
        return f"{seconds / 3600:.1f} 小时"
    if seconds >= 60:
        return f"{seconds / 60:.0f} 分钟"
    return f"{seconds:.0f} 秒"


@dataclass
class SynthesisRequest:
    """一次合成任务的输入（一本书）。"""

    book_path: str
    reference_audio_path: str | None = None
    voice: str | None = None
    book_name: str | None = None


@dataclass
class SynthesisOutcome:
    """一次合成任务的结果。"""

    task_id: int = 0
    total_segments: int = 0
    succeeded: int = 0
    failed: int = 0
    # 断点续跑时跳过的「上次已完成」段数（含在 total_segments 里）
    skipped: int = 0
    # 本次是否接着上次的任务跑（False = 新建任务）
    resumed: bool = False
    elapsed_seconds: float = 0.0
    message: str = ""
    stopped: bool = False
    errors: list[str] = field(default_factory=list)


@dataclass
class BookResult:
    """批量合成里**一本书**的结果。"""

    book_path: str
    outcome: SynthesisOutcome | None = None
    error: str = ""
    skipped: bool = False

    @property
    def ok(self) -> bool:
        return self.outcome is not None and not self.outcome.stopped and not self.error


@dataclass
class BatchOutcome:
    """批量合成（一批书）的汇总结果。"""

    results: list[BookResult] = field(default_factory=list)
    elapsed_seconds: float = 0.0
    stopped: bool = False

    @property
    def succeeded_books(self) -> list[BookResult]:
        return [r for r in self.results if r.ok]

    @property
    def failed_books(self) -> list[BookResult]:
        return [r for r in self.results if r.error]

    @property
    def skipped_books(self) -> list[BookResult]:
        return [r for r in self.results if r.skipped]

    @property
    def task_ids(self) -> list[int]:
        return [r.outcome.task_id for r in self.results if r.outcome and r.outcome.task_id]

    @property
    def total_segments(self) -> int:
        return sum(r.outcome.total_segments for r in self.results if r.outcome)

    @property
    def succeeded_segments(self) -> int:
        return sum(r.outcome.succeeded for r in self.results if r.outcome)

    @property
    def skipped_segments(self) -> int:
        """续跑时跳过的「上次已完成」段数（已含在 total_segments 里）。"""
        return sum(r.outcome.skipped for r in self.results if r.outcome)

    @property
    def done_segments(self) -> int:
        """累计已完成段数 = 本次合成 + 上次已完成的。"""
        return self.succeeded_segments + self.skipped_segments

    @property
    def failed_segments(self) -> int:
        return sum(r.outcome.failed for r in self.results if r.outcome)


LogFn = Callable[[str], None]
ProgressFn = Callable[[int, int, str], None]
BookFn = Callable[[int, int, str], None]
# 段内心跳：一段被拆成几十片时要让人看见"还在动"，参数是给界面显示的一行短文本
HeartbeatFn = Callable[[str], None]


class EngineSession:
    """引擎会话：批量合成时整个批次**共用一个**模型实例。

    为什么要有它：MOSS 引擎每次 ``start()`` 都要把模型加载进内存（几十秒），
    一批 5 本书若每本都重新加载，光加载就白等好几分钟。
    这里把「创建 + 启动 + 停止」收拢到会话里，一批书只加载一次。

    启动失败时**记住**失败原因并原样抛出：后面几本书不该再去反复尝试加载
    （每本书的任务都会被标成 failed 并写明原因）。
    """

    def __init__(
        self,
        cfg: AppConfig,
        log: LogFn,
        engine_factory: Callable[[MossSettings, LogFn], MossEngine] | None = None,
    ) -> None:
        self._cfg = cfg
        self._log = log
        self._factory = engine_factory
        self._engine: MossEngine | None = None
        self._failure: Exception | None = None

    def get(self) -> MossEngine:
        if self._failure is not None:
            raise self._failure
        if self._engine is None:
            factory = self._factory or (lambda settings, log: MossEngine(settings, on_log=log))
            engine = factory(self._cfg.moss, self._log)
            try:
                self._log("加载 MOSS 引擎（首次加载模型可能要几十秒）…")
                engine.start()
            except Exception as exc:
                self._failure = exc
                raise
            self._engine = engine
        return self._engine

    def close(self) -> None:
        if self._engine is not None:
            try:
                self._engine.stop()
            except Exception:  # 停止失败不影响已产出的结果
                pass
            self._engine = None



def _work_root(cfg: AppConfig) -> Path:
    """中间产物根目录。"""
    if cfg.work_dir:
        root = Path(cfg.work_dir).expanduser()
    else:
        root = Path(tempfile.gettempdir()) / "audiobook_local_tts"
    root.mkdir(parents=True, exist_ok=True)
    return root


def _trim_wav(src: Path, dst: Path, max_seconds: float) -> None:
    """把 wav 裁到 max_seconds（只裁，不改采样率/声道）。"""
    with wave.open(str(src), "rb") as wav:
        params = wav.getparams()
        rate = params.framerate or 48000
        total = params.nframes
        keep = min(total, int(max_seconds * rate))
        wav.setpos(0)
        frames = wav.readframes(keep)
        with wave.open(str(dst), "wb") as out:
            out.setnchannels(params.nchannels)
            out.setsampwidth(params.sampwidth)
            out.setframerate(rate)
            out.writeframes(frames)


def prepare_reference(
    cfg: AppConfig, reference_path: str | None, work_dir: Path
) -> str | None:
    """把用户选的参考音频整理成引擎能用的 wav（≤8 秒、48kHz 单声道以上均可）。

    返回 wav 路径；不需要参考音频时返回 None。
    """
    if not reference_path:
        return None
    src = Path(reference_path).expanduser()
    if not src.is_file():
        raise FileNotFoundError(f"参考音频不存在：{src}")

    suffix = src.suffix.lower()
    if suffix == ".wav":
        wav_path = src
    else:
        ffmpeg = find_ffmpeg(cfg.ffmpeg_path)
        if not ffmpeg:
            raise RuntimeError(
                f"参考音频是 {suffix} 格式，需要 ffmpeg 转成 wav；"
                "请在「其他设置」里填写 ffmpeg 路径，或直接选一个 wav 文件"
            )
        wav_path = work_dir / ("reference_" + src.stem + ".wav")
        cmd = [
            ffmpeg, "-y", "-loglevel", "error",
            "-i", str(src), "-ar", "48000", "-ac", "2",
            "-c:a", "pcm_s16le", str(wav_path),
        ]
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode != 0 or not wav_path.is_file():
            raise RuntimeError(
                "参考音频转码失败："
                + result.stderr.decode("utf-8", "ignore")[:200]
            )

    duration = wav_duration(wav_path)
    if duration <= 0:
        # 读不出时长 = 这不是有效的 WAV（后缀骗人 / 文件损坏）
        raise RuntimeError(
            f"无法读取参考音频（{src.name} 不是有效的 WAV）："
            "请换一个 wav 文件，或在「其他设置」里填 ffmpeg 路径以支持 mp3 等格式"
        )
    if duration > REFERENCE_MAX_SECONDS:
        trimmed = work_dir / "reference_trimmed.wav"
        _trim_wav(Path(wav_path), trimmed, REFERENCE_MAX_SECONDS)
        return str(trimmed)
    if duration < REFERENCE_MIN_SECONDS:
        raise RuntimeError(
            f"参考音频太短（{duration:.1f} 秒）：至少需要 {REFERENCE_MIN_SECONDS:.0f} 秒"
        )
    return str(wav_path)


def synthesize_book(
    cfg: AppConfig,
    request: SynthesisRequest,
    on_log: LogFn | None = None,
    on_progress: ProgressFn | None = None,
    stop_flag: Callable[[], bool] | None = None,
    engine_factory: Callable[[MossSettings, LogFn], MossEngine] | None = None,
    session: EngineSession | None = None,
    on_heartbeat: HeartbeatFn | None = None,
    resume: bool = True,
) -> SynthesisOutcome:
    """合成整本书并写入本地 MySQL。

    on_log：日志回调（给界面显示）
    on_progress：进度回调 (已完成段数, 总段数, 当前段文本)
    stop_flag：返回 True 时中断（界面上的「停止」按钮）
    engine_factory：引擎工厂（默认用 MossEngine；留出注入点便于自测）
    session：外部给的引擎会话（批量合成时共用一个模型实例）。
             不给就自己起一个、用完即停（单本合成的原有行为）。
    on_heartbeat：段内心跳（长段拆成几十片时，每 HEARTBEAT_SECONDS 报一行）
    resume：True 时先找上次没跑完的同源任务接着写（见 db.find_resumable_task）；
            False 时一律新建任务、从第 1 段重跑。
    """
    log = on_log or (lambda _m: None)
    progress = on_progress or (lambda _d, _t, _c: None)
    heartbeat = on_heartbeat or (lambda _m: None)
    should_stop = stop_flag or (lambda: False)
    outcome = SynthesisOutcome()
    started = time.time()

    # ---------- 1. 正文与分段 ----------
    log("读取电子书：" + request.book_path)
    text = extract_text(request.book_path)
    if not text.strip():
        raise RuntimeError("电子书正文为空（可能是扫描版 PDF 或加密文件）")
    # ⚠️ **一个句子都不能丢**：服务端的段号 = 句子序号（AudioSegment(taskId, i+1, 句)），
    # 客户端按段号对齐「正文段落 ↔ 音频段」。这里若把「无可朗读内容」的句子
    # 过滤掉，后面的段号会整体前移 → 客户端点第 N 段听到的是第 N+1 段的声音。
    # 服务端的做法是：整段不可朗读时给一段**静音占位**，段号照样占着。
    segments = sentences(text)
    silent_count = sum(1 for s in segments if not has_readable_content(s))
    log(
        f"正文 {len(text)} 字，切出 {len(segments)} 句"
        + (f"（其中 {silent_count} 句无可朗读内容，将用静音占位）" if silent_count else "")
    )
    if not segments:
        raise RuntimeError("没有可朗读的句子")
    outcome.total_segments = len(segments)

    # ---------- 2. 落库准备 ----------
    database = db.Database(cfg.local_db)
    database.ensure_schema(with_tool_tables=True)
    conn = database.connect()
    try:
        user_id = db.ensure_user(conn, cfg.local_username)
        title = request.book_name or book_title(request.book_path)
        book_file_key = f"local/{Path(request.book_path).name}"
        reference_name = (
            Path(request.reference_audio_path).name
            if request.reference_audio_path else None
        )

        # ---- 断点续跑：先看有没有上次没跑完的同源任务 ----
        # 判据在 db.find_resumable_task 里（路径 + 段数 + 参考音频 + 音色全一致）。
        # 命中的话**复用原 task_id**，已完成的段直接跳过 —— 任务号不变，
        # 界面上看到的就是"同一个任务继续跑"，上传时也不会变成两个半本书。
        done_numbers: set[int] = set()
        resumed_task: dict | None = None
        if resume:
            resumed_task = db.find_resumable_task(
                conn,
                book_file_key,
                len(segments),
                reference_audio_path=reference_name,
                default_voice_id=request.voice,
            )
        if resumed_task:
            task_id = int(resumed_task["id"])
            outcome.task_id = task_id
            outcome.resumed = True
            done_numbers = db.completed_segment_numbers(conn, task_id)
            # 状态拉回 processing：上次可能是 paused（用户点停）或 processing
            # （进程被强杀，压根没走到收尾）；error_message 清掉上次的中止原因。
            # completed_segments 立刻按实际段数写一次：老版本只在收尾写，
            # 被强杀的任务在库里恒为 0，任务表会显示「0/3329」而其实已有 40 段。
            db.update_task(
                conn, task_id, status="processing",
                completed_segments=len(done_numbers),
                error_message="",
            )
            log(
                f"发现未完成的任务 #{task_id}（上次状态 {resumed_task['status']}）："
                f"接着往下合成，已有 {len(done_numbers)}/{len(segments)} 段"
            )
            if len(done_numbers) >= len(segments):
                log("该任务所有段都已合成完成，只需刷新状态（不需要再调引擎）")
        else:
            task_id = db.create_task(
                conn,
                user_id=user_id,
                book_file_path=book_file_key,
                original_book_name=title,
                reference_audio_path=reference_name,
                default_voice_id=request.voice,
                total_segments=len(segments),
                status="processing",
            )
            outcome.task_id = task_id
            log(f"已在本地库创建任务 #{task_id}（用户 {cfg.local_username}）")

        work_dir = _work_root(cfg) / f"task_{task_id}"
        work_dir.mkdir(parents=True, exist_ok=True)

        # 把源书留一份在工作目录里：上传时要用它作为服务器上的 `book_file_path`
        # （App 打开这本书读正文全靠它）。只存文件路径是不够的 ——
        # 用户的原始文件可能被移走/改名，等到上传时再去问就晚了、也容易选错。
        # 电子书一般几 MB，占的空间远小于音频。
        book_copy = work_dir / f"book{Path(request.book_path).suffix.lower()}"
        try:
            if not book_copy.is_file() or book_copy.stat().st_size == 0:
                shutil.copyfile(request.book_path, book_copy)
        except OSError as exc:
            # 复制失败不该让整本合成失败：上传时再让用户手选原书即可
            log(f"⚠ 原书副本保存失败（不影响合成，上传时需手动指定原书）：{exc}")

        # ---------- 3. 长段预警 ----------
        # 「跑了很多分钟但界面一动不动」最常见的根因就是某一段特别长：
        # PDF 的封面+目录（5217 字）里全是点线和页码、没有句号，切句器切不开，
        # 整段被当成"一句话"→ 拆 48 片 → 17 分钟。开跑前先把它说清楚，
        # 用户就不会把这 17 分钟当成卡死。
        long_segments = [
            (n, len(s), len(sub_pieces(s)))
            for n, s in enumerate(segments, start=1)
            if n not in done_numbers and len(s) >= LONG_SEGMENT_CHARS
        ]
        if long_segments:
            long_segments.sort(key=lambda item: -item[1])
            log(
                f"⚠ 有 {len(long_segments)} 个超长段（≥{LONG_SEGMENT_CHARS} 字），"
                "单段会拆成很多小片、耗时以分钟计："
            )
            for n, chars, piece_count in long_segments[:5]:
                log(
                    f"    第 {n} 段 {chars} 字 → {piece_count} 片，"
                    f"约 {format_duration(piece_count * SECONDS_PER_PIECE_ESTIMATE)}"
                )
            if len(long_segments) > 5:
                log(f"    ……另有 {len(long_segments) - 5} 个超长段")
            log(
                f"    合成期间顶栏每 {HEARTBEAT_SECONDS:.0f} 秒刷新一次心跳"
                f"（形如「段 1/3329 · 片 12/48」），日志每 "
                f"{HEARTBEAT_LOG_SECONDS:.0f} 秒一行 —— 不是卡死"
            )

        # ---------- 4. 参考音频 ----------
        reference_wav = prepare_reference(cfg, request.reference_audio_path, work_dir)
        if reference_wav:
            log("参考音频已就绪：" + reference_wav)
        else:
            log("未选参考音频 → 使用内置音色：" + str(request.voice))

        ffmpeg = find_ffmpeg(cfg.ffmpeg_path)
        if not ffmpeg:
            log("未找到 ffmpeg：音频将保留为 WAV 格式（服务端照样能播，只是文件更大）")

        # ---------- 5. 引擎 ----------
        # session 为空 = 单本合成：自己起、自己停；
        # 有 session = 批量合成：整个批次共用一个模型实例（省掉重复加载）。
        own_session = session is None
        if own_session:
            session = EngineSession(cfg, log, engine_factory)
        # 上次已经跑到最后一段（所有段 completed，但进程在写收尾状态前被杀了）
        # → 没有任何段要合成，就别白等几十秒去加载模型了。
        nothing_to_do = len(done_numbers) >= len(segments)
        if nothing_to_do:
            log("本次没有需要新合成的段，跳过引擎加载")
        try:
            engine = None if nothing_to_do else session.get()
        except Exception as exc:
            # 引擎没起来 → 任务不能留在 processing（那会让它永远显示「合成中」）。
            # 续跑场景下已完成的段数要保住，不能清零。
            db.update_task(
                conn, task_id, status="failed",
                completed_segments=len(done_numbers),
                error_message=f"引擎加载失败：{exc}"[:500],
            )
            raise
        try:
            # 段内心跳的上一次时间戳。跨段共享：不重置的话每段开头都会白打一行。
            last_heartbeat = 0.0
            last_log_beat = 0.0
            resumed_skipped = 0        # 本次跳过的「上次已完成」段数
            synthesized_now = 0        # 本次真正处理的段数（估速只算这些，跳过的段是上次花的钱）
            speed_started = time.time()

            for index, sentence in enumerate(segments, start=1):
                if should_stop():
                    outcome.stopped = True
                    break

                # 断点续跑：上次已合成成功的段直接跳过，但段号照样占位。
                # 进度也照常推进 —— 否则进度条会停在断点之前，看着像没动。
                if index in done_numbers:
                    resumed_skipped += 1
                    outcome.skipped = resumed_skipped
                    progress(index, len(segments), sentence)
                    if resumed_skipped % 50 == 0 or resumed_skipped == len(done_numbers):
                        log(f"跳过已完成段 {resumed_skipped}/{len(done_numbers)}")
                    continue

                progress(index - 1, len(segments), sentence)
                wav_path = work_dir / f"seg_{index:05d}.wav"
                segment_started = time.time()

                # 整段没有可朗读内容（如一整行装饰线）→ 静音占位，不调引擎。
                # 与服务端 WavConcat.silentWav() 同思路：保住「每段都有音频」的契约。
                if not has_readable_content(sentence):
                    write_silent_wav(wav_path)
                else:
                    # 超长句拆小片（≤45 字），逐片合成后拼回一条 ——
                    # 与服务端 TextSegmenter.subPieces + WavConcat 的口径一致：
                    # 切分点相同、每片的帧数预算相同，出来的停顿才和服务器一样。
                    pieces = [p for p in sub_pieces(sentence) if has_readable_content(p)]
                    if not pieces:
                        write_silent_wav(wav_path)
                    else:
                        piece_paths: list[Path] = []
                        piece_failed: str | None = None
                        stopped_mid = False
                        for piece_index, piece in enumerate(pieces):
                            # 停止按钮在**片间**也要响应：一段拆 48 片时若只在段间检查，
                            # 点了「停止」还要等十几分钟才生效。
                            if should_stop():
                                stopped_mid = True
                                break
                            piece_wav = work_dir / f"seg_{index:05d}_{piece_index:02d}.wav"
                            try:
                                engine.synthesize(
                                    text=piece,
                                    out_path=piece_wav,
                                    voice=request.voice,
                                    prompt_audio_path=reference_wav,
                                    seed=1234 + index * 100 + piece_index,
                                    max_new_frames=resolve_max_new_frames(piece),
                                )
                            except MossEngineError as exc:
                                piece_failed = str(exc)
                                break
                            produced = Path(piece_wav)
                            if produced.is_file():
                                piece_paths.append(produced)
                            # 片级心跳：长段期间"有字在动"就是"没卡死"的唯一证据。
                            # 只对**拆了 ≥3 片**的段报 —— 单片的段没有"段内"可言，
                            # 每段都报的话 3329 段就是 3329 行噪音，反而淹掉有效信息。
                            if (len(pieces) >= HEARTBEAT_MIN_PIECES
                                    and piece_index + 1 < len(pieces)):
                                now = time.time()
                                if now - last_heartbeat >= HEARTBEAT_SECONDS:
                                    last_heartbeat = now
                                    beat = (
                                        f"段 {index}/{len(segments)} · "
                                        f"片 {piece_index + 1}/{len(pieces)} · "
                                        f"本段已 {format_duration(now - segment_started)}"
                                    )
                                    heartbeat(beat)  # 顶栏：每 5 秒
                                    if now - last_log_beat >= HEARTBEAT_LOG_SECONDS:
                                        last_log_beat = now
                                        log("    " + beat)  # 日志：每 30 秒（留痕够算速度）
                        if stopped_mid:
                            # 这一段不落库：缺片 = 半截音频，比没有更糟（客户端播到一半断掉）。
                            # 段号不占位，下次续跑整段重做。
                            outcome.stopped = True
                            log(f"已在第 {index} 段中间中断（该段未落库，续跑时会重做这一段）")
                            break
                        if piece_failed is not None or not piece_paths:
                            # 失败的段**照样落库**（status=failed）：
                            # 不落库就会缺一个段号，后面的段整体前移 → 客户端声音错位。
                            reason = piece_failed or "合成未产出音频"
                            outcome.failed += 1
                            outcome.errors.append(f"第 {index} 段：{reason}")
                            log(f"第 {index} 段合成失败：{reason}")
                            db.insert_segment(
                                conn,
                                task_id=task_id,
                                segment_number=index,
                                text_content=sentence,
                                audio_data=None,
                                audio_file_path=db.segment_audio_path(task_id, index, ".mp3"),
                                duration_seconds=None,
                                voice=request.voice,
                                status="failed",
                                error_message=reason[:500],
                                # 续跑时这个段号可能已有上次的 failed 行（唯一键）
                                replace=True,
                            )
                            progress(index, len(segments), sentence)
                            # 失败段也算"处理过"，否则落库节奏会被它错开一格
                            synthesized_now += 1
                            if synthesized_now % PROGRESS_COMMIT_INTERVAL == 0:
                                db.update_task(
                                    conn, task_id,
                                    completed_segments=resumed_skipped + outcome.succeeded,
                                )
                            continue
                        if len(pieces) >= HEARTBEAT_MIN_PIECES:
                            log(
                                f"第 {index} 段完成：{len(pieces)} 片，"
                                f"用时 {format_duration(time.time() - segment_started)}"
                            )
                        if len(piece_paths) == 1:
                            piece_paths[0].replace(wav_path)
                        else:
                            concat_wavs(piece_paths, wav_path)

                mp3_path = work_dir / f"seg_{index:05d}.mp3"
                suffix = ".mp3"
                audio_bytes: bytes
                if ffmpeg and convert_to_mp3(wav_path, mp3_path, ffmpeg):
                    audio_bytes = mp3_path.read_bytes()
                else:
                    suffix = ".wav"
                    audio_bytes = wav_path.read_bytes()

                db.insert_segment(
                    conn,
                    task_id=task_id,
                    segment_number=index,
                    text_content=sentence,
                    audio_data=audio_bytes,
                    audio_file_path=db.segment_audio_path(task_id, index, suffix),
                    # 时长从 **wav** 读：转码后的 mp3 用 wave 模块读不出来，
                    # 拿 mp3 去求时长会恒等于 0（服务端自己不写这个字段）
                    duration_seconds=wav_duration(wav_path),
                    voice=request.voice,
                    status="completed",
                    # 续跑时这个段号可能已有上次的 failed 行（唯一键挡着），走 UPDATE
                    replace=True,
                )
                outcome.succeeded += 1
                synthesized_now += 1
                progress(index, len(segments), sentence)

                # 进度落库：每 PROGRESS_COMMIT_INTERVAL 段（以及最后一段）写一次。
                # 只在收尾写的话，中途崩溃/断电/强杀之后 tasks.completed_segments
                # 恒为 0 —— 既看不出停在哪，也判断不出值不值得续跑。
                remaining = len(segments) - index
                if synthesized_now % PROGRESS_COMMIT_INTERVAL == 0 or remaining == 0:
                    db.update_task(
                        conn, task_id,
                        completed_segments=resumed_skipped + outcome.succeeded,
                    )
                    if remaining == 0:
                        log(f"已合成 {index}/{len(segments)} 段（全部完成）")
                    else:
                        per_segment = (time.time() - speed_started) / max(1, synthesized_now)
                        log(
                            f"已合成 {index}/{len(segments)} 段"
                            f"（本次 {synthesized_now} 段，平均 {per_segment:.0f} 秒/段），"
                            f"剩余约 {format_duration(per_segment * remaining)}"
                        )
        finally:
            if own_session:
                session.close()

        # ---------- 7. 收尾 ----------
        # completed_segments 的口径是「这个任务累计有多少段合成成功」。
        # 续跑时必须把上次已完成的一起加回去，否则任务表会显示成"只剩 5 段"。
        total_done = resumed_skipped + outcome.succeeded
        if outcome.stopped:
            db.update_task(
                conn, task_id,
                status="paused",
                completed_segments=total_done,
                error_message="用户中止（再点「开始合成」可断点续跑）",
            )
            log(
                f"已中止：{total_done}/{len(segments)} 段已完成"
                + (f"（其中 {outcome.succeeded} 段是本次新合成的）" if resumed_skipped else "")
                + "；再点「开始合成」会接着跑，不会重做已完成的段"
            )
        else:
            status = "completed" if outcome.failed == 0 else "partial"
            db.update_task(
                conn, task_id,
                status=status,
                completed_segments=total_done,
                error_message=("；".join(outcome.errors[:3]) or None),
            )
            log(
                f"合成结束：成功 {outcome.succeeded} 段，失败 {outcome.failed} 段"
                + (
                    f"（另有 {resumed_skipped} 段是上次已完成的，本次未重做）"
                    if resumed_skipped else ""
                )
            )

        if not cfg.keep_wav:
            shutil.rmtree(work_dir, ignore_errors=True)
        else:
            log("中间音频目录：" + str(work_dir))
    finally:
        conn.close()

    outcome.elapsed_seconds = time.time() - started
    return outcome


def synthesize_books(
    cfg: AppConfig,
    requests: list[SynthesisRequest],
    on_log: LogFn | None = None,
    on_progress: ProgressFn | None = None,
    on_book: BookFn | None = None,
    stop_flag: Callable[[], bool] | None = None,
    engine_factory: Callable[[MossSettings, LogFn], MossEngine] | None = None,
    on_heartbeat: HeartbeatFn | None = None,
    resume: bool = True,
) -> BatchOutcome:
    """依次合成多本书（每本书一个任务），返回汇总结果。

    * 整个批次**共用一个引擎实例**（模型只加载一次）；
    * 单本失败（格式不支持、正文为空……）**不中断整批**：记下原因，继续下一本；
    * 中途「停止」：当前这本标记为已中止，剩下的书标记为「已跳过」；
    * resume=True 时每本书各自找自己的未完成任务续跑（批次里第 3 本上次没跑完，
      就只续第 3 本，另外两本该新建还是新建）。
    """
    log = on_log or (lambda _m: None)
    progress = on_progress or (lambda _d, _t, _c: None)
    book_hook = on_book or (lambda _i, _n, _name: None)
    heartbeat = on_heartbeat or (lambda _m: None)
    should_stop = stop_flag or (lambda: False)

    batch = BatchOutcome()
    started = time.time()
    total = len(requests)
    if total == 0:
        return batch

    log(f"共 {total} 本书待合成，依次进行（每本一个任务）")
    session = EngineSession(cfg, log, engine_factory)
    try:
        for index, request in enumerate(requests, start=1):
            name = request.book_name or Path(request.book_path).name
            if should_stop():
                batch.stopped = True
                batch.results.append(BookResult(book_path=request.book_path, skipped=True))
                log(f"[{index}/{total}] 已停止，跳过：{name}")
                continue
            book_hook(index, total, name)
            log(f"[{index}/{total}] 开始合成：{name}")
            try:
                outcome = synthesize_book(
                    cfg,
                    request,
                    on_log=log,
                    on_progress=progress,
                    stop_flag=should_stop,
                    session=session,
                    on_heartbeat=heartbeat,
                    resume=resume,
                )
            except Exception as exc:  # 单本失败不影响后面的书
                log(f"[{index}/{total}] 合成失败：{name} —— {exc}")
                batch.results.append(
                    BookResult(book_path=request.book_path, error=str(exc))
                )
                continue
            batch.results.append(
                BookResult(book_path=request.book_path, outcome=outcome)
            )
            if outcome.stopped:
                batch.stopped = True
                log(f"[{index}/{total}] 已中止：{name}")
    finally:
        session.close()

    batch.elapsed_seconds = time.time() - started
    ok = len(batch.succeeded_books)
    log(
        f"批量合成结束：成功 {ok} 本"
        + (f"，失败 {len(batch.failed_books)} 本" if batch.failed_books else "")
        + (f"，跳过 {len(batch.skipped_books)} 本" if batch.skipped_books else "")
        + f"，共合成 {batch.succeeded_segments}/{batch.total_segments} 段"
        + f"，用时 {batch.elapsed_seconds / 60:.1f} 分钟"
    )
    return batch
