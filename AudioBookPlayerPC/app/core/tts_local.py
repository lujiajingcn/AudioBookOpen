"""本机 TTS：MOSS-TTS-Nano 引擎桥接 + 整本合成编排。

引擎侧与音频侧的口径全部对齐 ``AudioBookLocalTtsTool``
（``app/core/moss_client.py`` / ``synthesizer.py`` / ``audio.py``）：

  - 引擎是「长驻子进程 + JSON 行协议」（ready/done/error），模型只加载一次；
  - 段号从 1 开始、与服务端 ``audio_segments`` 同口径；
  - 超长句拆 ≤45 字小片分别合成再拼回，帧数预算用同一公式
    ``clamp(30 + 字数*4.0, 30, 375)``；
  - 无可朗读内容的段用静音占位（保住「每段都有音频」的契约）；
  - 产物统一转 mp3（无 ffmpeg 时退回 wav）。

这里把工具的实现「下沉」进播放器本体，使 PC 端无需依赖外部工具目录也能本机合成。
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import threading
import time
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, List, Optional

from app.core.book_service import get_book_sentences, get_segmenter
from app.core.config import AppConfig
from app.core.moss_module import (
    MossModuleManager, MossDownloader, MossModuleError, MossModuleCancelled,
    ensure_module_installed,
)
from app.core.pause import PauseController
from app.db.database import LocalDatabase
from app.models.models import Segment


class MossEngineError(RuntimeError):
    """引擎侧错误（加载失败、合成失败等）。"""


# ==========================================================================
# 音频工具（纯标准库 + ffmpeg 子进程）
# ==========================================================================

SILENT_SAMPLE_RATE = 22050
SILENT_MILLIS = 300


def find_ffmpeg() -> Optional[str]:
    return shutil.which("ffmpeg")


def wav_duration(path: str | Path) -> float:
    try:
        with wave.open(str(path), "rb") as wav:
            frames = wav.getnframes()
            rate = wav.getframerate() or 1
            return frames / float(rate)
    except Exception:
        return 0.0


def write_silent_wav(path: str | Path) -> Path:
    """整段无可朗读内容时的静音占位（规格与服务端 WavConcat.silentWav 一致）。"""
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    frames = SILENT_SAMPLE_RATE * SILENT_MILLIS // 1000
    with wave.open(str(target), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SILENT_SAMPLE_RATE)
        wav.writeframes(b"\x00\x00" * frames)
    return target


def concat_wavs(wav_paths: List[Path], out_path: Path) -> Path:
    """把多个 wav 首尾相接（超长句「拆小片分别合成再拼回」的落点）。"""
    target = Path(out_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    usable = [Path(p) for p in wav_paths if Path(p).is_file()]
    if not usable:
        raise ValueError("没有可拼接的音频片段")
    with wave.open(str(usable[0]), "rb") as first:
        params = first.getparams()
        frames = [first.readframes(first.getnframes())]
    for path in usable[1:]:
        try:
            with wave.open(str(path), "rb") as wav:
                # 只比格式参数（声道/位宽/采样率），不比 nframes（每片本就不同）
                if wav.getparams()[:3] != params[:3]:
                    continue
                frames.append(wav.readframes(wav.getnframes()))
        except Exception:
            continue
    with wave.open(str(target), "wb") as out:
        out.setnchannels(params.nchannels)
        out.setsampwidth(params.sampwidth)
        out.setframerate(params.framerate)
        out.writeframes(b"".join(frames))
    return target


def convert_to_mp3(wav_path: Path, mp3_path: Path, ffmpeg: Optional[str],
                   bitrate: str = "64k") -> bool:
    if not ffmpeg:
        return False
    mp3_path.parent.mkdir(parents=True, exist_ok=True)
    cmd = [ffmpeg, "-y", "-loglevel", "error", "-i", str(wav_path),
           "-codec:a", "libmp3lame", "-b:a", bitrate, str(mp3_path)]
    try:
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0 and mp3_path.is_file() and mp3_path.stat().st_size > 0


def _trim_wav(src: Path, dst: Path, max_seconds: float) -> None:
    with wave.open(str(src), "rb") as wav:
        params = wav.getparams()
        rate = params.framerate or 48000
        keep = min(params.nframes, int(max_seconds * rate))
        wav.setpos(0)
        frames = wav.readframes(keep)
        with wave.open(str(dst), "wb") as out:
            out.setnchannels(params.nchannels)
            out.setsampwidth(params.sampwidth)
            out.setframerate(rate)
            out.writeframes(frames)


REFERENCE_MAX_SECONDS = 8.0
REFERENCE_MIN_SECONDS = 2.0


# ==========================================================================
# 帧数预算（与服务端 moss.tts.frames 同公式）
# ==========================================================================

FRAMES_BASE = 30
FRAMES_PER_CHAR = 4.0
FRAMES_MIN = 30
FRAMES_MAX = 375


def resolve_max_new_frames(text: str) -> int:
    """本次合成请求的生成帧数预算 = clamp(30 + 字数*4.0, 30, 375)。"""
    length = len(text or "")
    desired = int(round(FRAMES_BASE + length * FRAMES_PER_CHAR))
    return max(FRAMES_MIN, min(desired, FRAMES_MAX))


# 心跳间隔（秒）：长段拆成几十片时，「有字在动」是「没卡死」的唯一证据
HEARTBEAT_SECONDS = 5.0


# ==========================================================================
# MOSS 引擎客户端（桥接子进程）
# ==========================================================================

class SynthResult:
    def __init__(self, path: str, sample_rate: int):
        self.path = path
        self.sample_rate = sample_rate


def _find_bridge_script(explicit: Optional[str | Path] = None) -> Path:
    """定位 moss_bridge.py：显式路径 → 本目录随包副本 → 兄弟工程 LocalTtsTool。"""
    candidates: List[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    candidates.append(Path(__file__).with_name("moss_bridge.py"))
    # 兄弟工程（开发期）：逐级向上找包含 AudioBookLocalTtsTool 的父目录
    here = Path(__file__).resolve()
    for ancestor in here.parents:
        candidates.append(
            ancestor / "AudioBookLocalTtsTool" / "app" / "core" / "moss_bridge.py")
    for c in candidates:
        try:
            if c.is_file():
                return c
        except OSError:
            continue
    raise MossEngineError(
        "找不到 MOSS 桥接脚本 moss_bridge.py。请把它放到 app/core/ 下，"
        "或在配置里指定 LocalTtsTool 的 moss_bridge.py 路径。")


class MossEngine:
    """MOSS-TTS-Nano 引擎客户端：拉起桥接子进程并按 JSON 行协议对话。

    线程安全：同一时刻只允许一个合成请求（引擎内部单实例 + KV cache）。
    """

    def __init__(self) -> None:
        self._proc: Optional[subprocess.Popen] = None
        self._lock = threading.Lock()
        self._ready = threading.Event()
        self._result: Optional[dict] = None
        self._next_id = 1
        self._start_error: Optional[str] = None
        self._errors: List[str] = []
        self.bridge_path: Optional[Path] = None
        self.voices: List[str] = []

    # ---- 生命周期 ----

    def start(self, python_exe: str = "", project_path: str = "",
              model_dir: str = "", cpu_threads: int = 16,
              provider: str = "cpu", sample_mode: str = "fixed",
              enable_wetext: bool = False, bridge_path: Optional[str | Path] = None,
              timeout: float = 1800.0,
              on_log: Optional[Callable[[str], None]] = None) -> None:
        """启动桥接进程并等待引擎就绪。"""
        log = on_log or (lambda _m: None)
        raw_project = (project_path or "").strip()
        if not raw_project:
            raise MossEngineError("未配置 MOSS 工程目录（moss_project_path 为空）")
        project = Path(raw_project).expanduser()
        if not project.is_dir():
            raise MossEngineError(f"MOSS 工程目录不存在：{project}")

        self.bridge_path = _find_bridge_script(bridge_path)
        exe = python_exe or sys.executable
        if python_exe and not (Path(python_exe).exists() or shutil.which(python_exe)):
            raise MossEngineError(f"Python 解释器不可用：{python_exe}")

        cmd = [
            exe, str(self.bridge_path),
            "--project", str(project),
            "--cpu-threads", str(max(1, int(cpu_threads))),
            "--execution-provider", provider,
            "--sample-mode", sample_mode,
            "--enable-wetext", "1" if enable_wetext else "0",
        ]
        if model_dir:
            cmd += ["--model-dir", str(Path(model_dir).expanduser())]

        env = os.environ.copy()
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUNBUFFERED"] = "1"
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if sys.platform == "win32" else 0

        log("启动引擎：" + " ".join(cmd))
        try:
            self._proc = subprocess.Popen(
                cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, encoding="utf-8", errors="replace", bufsize=1,
                env=env, creationflags=creationflags)
        except OSError as exc:
            raise MossEngineError(f"无法启动引擎进程：{exc}") from exc

        self._ready.clear()
        self._start_error = None
        threading.Thread(target=self._read_stdout, args=(log,), daemon=True).start()
        threading.Thread(target=self._read_stderr, args=(log,), daemon=True).start()

        if not self._ready.wait(timeout):
            self.stop()
            hint = "；".join(self._errors[-3:]) if self._errors else "无"
            raise MossEngineError(f"引擎加载超时（首次加载模型可能要几分钟）。自检：{hint}")
        if self._start_error:
            self.stop()
            raise MossEngineError(self._start_error)

    def stop(self) -> None:
        """结束桥接进程（先 quit、再 terminate、最后 kill）。"""
        proc = self._proc
        self._proc = None
        if proc is None:
            return
        try:
            if proc.stdin and not proc.stdin.closed:
                proc.stdin.write(json.dumps({"cmd": "quit"}) + "\n")
                proc.stdin.flush()
        except Exception:
            pass
        try:
            proc.wait(timeout=5)
        except Exception:
            try:
                proc.terminate()
                proc.wait(timeout=5)
            except Exception:
                try:
                    proc.kill()
                except Exception:
                    pass
        self._ready.clear()

    # ---- 协议收发 ----

    def _read_stdout(self, log: Callable[[str], None]) -> None:
        proc = self._proc
        if proc is None or proc.stdout is None:
            return
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                log("[引擎] " + line)
                continue
            kind = str(message.get("type", ""))
            if kind == "ready":
                self.voices = [str(v) for v in message.get("voices", [])]
                log(f"引擎就绪：内置音色 {len(self.voices)} 个"
                    + (f"（{'、'.join(self.voices[:6])}…）" if self.voices else ""))
                self._ready.set()
            elif kind == "error":
                msg = str(message.get("message", "未知错误"))
                self._errors.append(msg)
                del self._errors[:-5]
                if message.get("id") is None:
                    self._start_error = msg
                    log("[引擎] 加载失败：" + msg)
                    self._ready.set()
                else:
                    self._result = message
            elif kind == "done":
                self._result = message
            else:
                log("[引擎] " + line)

    def _read_stderr(self, log: Callable[[str], None]) -> None:
        proc = self._proc
        if proc is None or proc.stderr is None:
            return
        for line in proc.stderr:
            line = line.rstrip()
            if line:
                log("[引擎] " + line)

    # ---- 合成 ----

    def synthesize(self, text: str, out_path: str | Path, voice: Optional[str] = None,
                   prompt_audio_path: Optional[str | Path] = None, seed: Optional[int] = None,
                   max_new_frames: Optional[int] = None, timeout: float = 900.0) -> SynthResult:
        """合成一段文本，结果写入 out_path（wav）。"""
        if self._proc is None or self._proc.poll() is not None:
            raise MossEngineError("引擎进程未运行")
        with self._lock:
            self._result = None
            req_id = self._next_id
            self._next_id += 1
            request = {"cmd": "synth", "id": req_id, "text": text,
                       "out": str(Path(out_path)), "seed": seed}
            if max_new_frames:
                request["max_new_frames"] = int(max_new_frames)
            if voice:
                request["voice"] = str(voice)
            if prompt_audio_path:
                request["prompt_audio_path"] = str(prompt_audio_path)
            try:
                assert self._proc.stdin is not None
                self._proc.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
                self._proc.stdin.flush()
            except (BrokenPipeError, OSError) as exc:
                raise MossEngineError(f"引擎进程已退出：{exc}") from exc

            sleeper = threading.Event()
            waited = 0.0
            step = 0.1
            while waited < timeout:
                if self._result is not None:
                    break
                sleeper.wait(step)
                waited += step
            if self._result is None:
                raise MossEngineError(f"合成超时（{timeout:.0f} 秒未返回）")
            message = self._result
            self._result = None
            if str(message.get("type")) == "error":
                raise MossEngineError(str(message.get("message", "未知错误")))
            return SynthResult(path=str(message.get("path", "")),
                               sample_rate=int(message.get("sample_rate", 48000)))

    @property
    def available(self) -> bool:
        return self._proc is not None and self._proc.poll() is None and self._ready.is_set()


# ==========================================================================
# 合成结果
# ==========================================================================

@dataclass
class SynthesisResult:
    """一次整本本机合成的结果。"""
    task_id: int = 0
    total_segments: int = 0
    succeeded: int = 0
    failed: int = 0
    skipped: int = 0            # 断点续跑时跳过的「上次已完成」段数
    stopped: bool = False
    elapsed_seconds: float = 0.0
    audio_dir: str = ""
    message: str = ""
    errors: List[str] = field(default_factory=list)


# ==========================================================================
# LocalTtsService
# ==========================================================================

# 暂停时写进任务行的阶段文案。
#
# 单点定义：服务写库用它、书架读它判断「这一行是不是暂停住了」，
# 两边各写一份字符串早晚会漂移（写的是「已暂停」、读的是「暂停中」，
# 于是暂停后书架上的状态列永远停在「合成中」）。
# 与安卓端 LocalSynthesisService.PAUSED_PROGRESS_TEXT 同口径。
PAUSED_PROGRESS_TEXT = "已暂停"


class LocalTtsService:
    """本机 TTS 服务：编排「抽取 → 切句 → 逐段合成 → 拼接 → 转码 → 落库」。"""

    def __init__(self, config: AppConfig, db: LocalDatabase):
        self.config = config
        self.db = db
        self.engine = MossEngine()
        self.module_mgr = MossModuleManager(config)
        self._downloader = MossDownloader(config, self.module_mgr)
        self._engine_log_cb: Optional[Callable[[str], None]] = None
        # ---------- 本机合成会话（进程级） ----------
        # 暂停/恢复闸门由**服务**持有而不是 UI：暂停状态要在「合成页」与「书架页」
        # 两处都能正确显示与切换，而书架页点暂停时合成页可能根本没打开。
        # 让服务做唯一事实来源，两边都读它。（对齐安卓 LocalSynthesisService.pauseController）
        self._pause = PauseController()
        # 会话标志：描述「本机合成正在进行」这一进程级状态，UI 也要读同一份。
        # 刻意不放在 UI 层：两个页面各自维护一份必然漂移。
        self._session_lock = threading.Lock()
        self._session_active = False
        # 当前会话的本机 task_id（书架要靠它把「已暂停」写到正确的那一行）
        self._session_task_id = 0

    # ---- 可用性 ----

    def is_module_installed(self) -> bool:
        """本地是否已就位 MOSS-TTS-Nano 模块（不联网，只看 REQUIRED_FILES 是否齐）。"""
        try:
            return self.module_mgr.is_installed()
        except Exception:  # noqa: BLE001
            return False

    def _mount_installed_module(self) -> bool:
        """若模块已装在默认目录、但 config.moss_project_path 未指向它，自动挂载。

        手动配置过工程目录（指向合法目录）的用户不受影响；只有在 config 为空
        或指向不存在的目录时，才用已下载的模块回填 project_path / model_dir。
        """
        raw = (self.config.moss_project_path or "").strip()
        if raw and Path(raw).expanduser().is_dir():
            return True   # 已有合法工程目录，尊重用户配置
        if not self.is_module_installed():
            return False
        md = self.module_mgr.module_dir()
        self.config.moss_project_path = str(md)
        models = md / "models"
        if models.is_dir():
            self.config.moss_model_dir = str(models)
        return True

    def is_engine_available(self) -> bool:
        """是否具备启动本机引擎的条件（工程目录 + 桥接脚本）。

        工程目录既认用户手动配置的 ``moss_project_path``，也认「按需下载」落到
        默认模块目录的 MOSS-TTS-Nano —— 后者先自动挂载再判定，保证下载完模块后
        本机合成能直接开工，而不必让用户再去设置里填路径。
        """
        if not self._mount_installed_module():
            return False
        try:
            _find_bridge_script(None)
            return True
        except MossEngineError:
            return False

    def ensure_module(self,
                      on_progress: Optional[Callable[[int, int, int], None]] = None,
                      on_stage: Optional[Callable[[str], None]] = None,
                      allow_download: bool = True) -> dict:
        """确保 MOSS-TTS-Nano 模块可用；未装则从服务器下载并安装（同步，走后台线程）。

        成功后把工程目录 / 模型目录回填进 config，使随后的 ``_start_engine`` 直接可用。
        复用 ``ensure_module_installed`` 的断点续传 + 整包/逐文件 sha256 校验流程。
        抛 ``MossModuleError``（含取消 ``MossModuleCancelled``）交由上层 UI 呈现。

        下载器是本服务持有的单例，UI 可通过 ``pause_module`` / ``resume_module`` /
        ``cancel_module`` 精确操作**正在进行的这一次**下载（暂停只置标志、取消保留断点）。
        """
        info = ensure_module_installed(
            self.config, on_progress=on_progress, on_stage=on_stage,
            allow_download=allow_download, downloader=self._downloader)
        # 回填，让引擎与后续合成直接读到正确路径
        self.config.moss_project_path = info.get("project_path", "") or self.config.moss_project_path
        self.config.moss_model_dir = info.get("model_dir", "") or self.config.moss_model_dir
        return info

    # ---- 模块下载控制（供下载弹窗的 暂停/继续/取消 按钮调用） ----

    def pause_module(self) -> None:
        self._downloader.pause()

    def resume_module(self) -> None:
        self._downloader.resume()

    def cancel_module(self) -> None:
        self._downloader.cancel()

    def module_download_paused(self) -> bool:
        return self._downloader.is_paused()

    def module_status(self) -> dict:
        """给 UI 展示模块状态：是否已装、缺哪些必需文件、是否有克隆模型、内置音色。"""
        return {
            "installed": self.is_module_installed(),
            "missing": self.module_mgr.missing_files(),
            "has_clone_model": self.module_mgr.has_voice_clone_model(),
            "builtin_voices": self.module_mgr.builtin_voices(),
            "module_dir": str(self.module_mgr.module_dir()),
            "project_path": (self.config.moss_project_path or "").strip(),
        }

    # ---- 本机合成的暂停 / 继续（进程级，对齐安卓 LocalSynthesisService） ----

    def is_synthesis_busy(self) -> bool:
        """本机合成会话此刻是否真的在跑。

        ⚠️ 判重必须用它、**不能**用「有没有任务行是 processing」：
        进程被杀留下的孤儿「合成中」行上若也挂上暂停按钮，点下去服务根本没在跑
        —— 一个纯死按钮。（对齐安卓的 ``LocalSynthesisService.isBusy``）
        """
        with self._session_lock:
            return self._session_active

    def is_synthesis_paused(self) -> bool:
        """本机合成当前是否处于暂停态。"""
        return self._pause.is_paused

    def current_synthesis_task_id(self) -> int:
        """当前会话的本机 task_id（0 = 没有会话）。"""
        with self._session_lock:
            return self._session_task_id

    def pause_synthesis(self) -> bool:
        """暂停本机合成。返回是否真的暂停了（没有会话在跑时返回 False）。

        暂停**只在段边界生效**：当前正在合成的那一段会先跑完，之后的段不再开始。
        """
        with self._session_lock:
            if not self._session_active:
                return False
            self._pause.pause()
            task_id = self._session_task_id
        # 书架那条任务也要显示「已暂停」：用户可能是在合成页点的暂停，
        # 然后退到书架看状态 —— 那里若还写「合成中 3/16」，他会以为没暂停成功。
        if task_id:
            try:
                self.db.update_task_progress_text(task_id, PAUSED_PROGRESS_TEXT)
            except Exception:  # noqa: BLE001
                pass
        return True

    def resume_synthesis(self) -> bool:
        """恢复本机合成。返回是否真的恢复了（没有会话在跑时返回 False）。"""
        with self._session_lock:
            if not self._session_active:
                return False
            task_id = self._session_task_id
        self._pause.resume()
        if task_id:
            try:
                self.db.update_task_progress_text(task_id, "")
            except Exception:  # noqa: BLE001
                pass
        return True

    def release_synthesis_pause(self) -> None:
        """中止时调用：放行卡在暂停里的 worker。

        ⚠️ 关键：暂停中点「停止」时必须先调它，否则 worker 永远等在 await_resume 上，
        合成既不结束也不释放内存 —— 比崩溃更难排查（进程还活着，界面一直挂着）。
        """
        self._pause.release_all()

    def builtin_voices(self) -> List[str]:
        """本机可用音色：优先引擎已就绪时上报的 voices，否则读模块清单里的内置音色。

        引擎尚未启动（合成还没真正开始）时也能列出色表，靠的是下载好的
        ``browser_poc_manifest.json`` —— 与 Android ``MossModuleManager.builtinVoices``
        同口径，不必为「列出下拉项」而付出加载几百 MB 模型的代价。
        """
        if self.engine.voices:
            return list(self.engine.voices)
        return self.module_mgr.builtin_voices()

    def list_voices(self) -> List[str]:
        return self.builtin_voices()

    def _start_engine(self, on_log: Callable[[str], None]) -> None:
        if self.engine.available:
            return
        cfg = self.config
        # 兜底挂载：万一调用方（UI）没先走 ensure_module/is_engine_available，
        # 这里也尝试用已下载的默认模块回填工程目录，避免「装了却仍报未配置」。
        if not ((cfg.moss_project_path or "").strip()
                and Path(cfg.moss_project_path).expanduser().is_dir()):
            self._mount_installed_module()
        self.engine.start(
            python_exe=cfg.moss_python_exe,
            project_path=cfg.moss_project_path,
            model_dir=cfg.moss_model_dir,
            cpu_threads=cfg.moss_cpu_threads,
            provider=cfg.moss_execution_provider,
            sample_mode=cfg.moss_sample_mode,
            enable_wetext=cfg.moss_enable_wetext,
            on_log=on_log,
        )

    # ---- 参考音频 ----

    def _prepare_reference(self, ref_path: Optional[str], work_dir: Path,
                           log: Callable[[str], None]) -> Optional[str]:
        if not ref_path:
            return None
        src = Path(ref_path).expanduser()
        if not src.is_file():
            log(f"⚠ 参考音频不存在，忽略：{src}")
            return None
        ffmpeg = find_ffmpeg()
        wav_path = src
        if src.suffix.lower() != ".wav":
            if not ffmpeg:
                log("⚠ 参考音频非 wav 且无 ffmpeg，忽略克隆参考（改用内置音色）")
                return None
            wav_path = work_dir / f"reference_{src.stem}.wav"
            cmd = [ffmpeg, "-y", "-loglevel", "error", "-i", str(src),
                   "-ar", "48000", "-ac", "2", "-c:a", "pcm_s16le", str(wav_path)]
            r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if r.returncode != 0 or not wav_path.is_file():
                log("⚠ 参考音频转码失败，改用内置音色")
                return None
        dur = wav_duration(wav_path)
        if dur <= 0:
            log("⚠ 参考音频无法读取时长，改用内置音色")
            return None
        if dur > REFERENCE_MAX_SECONDS:
            trimmed = work_dir / "reference_trimmed.wav"
            _trim_wav(Path(wav_path), trimmed, REFERENCE_MAX_SECONDS)
            return str(trimmed)
        if dur < REFERENCE_MIN_SECONDS:
            log(f"⚠ 参考音频太短（{dur:.1f}s），改用内置音色")
            return None
        return str(wav_path)

    # ---- 主流程 ----

    def synthesize_book(
        self,
        book_path: str,
        voice: Optional[str] = None,
        ref_audio_path: Optional[str] = None,
        task_id: int = 0,
        on_progress: Optional[Callable[[int, int, str], None]] = None,
        on_log: Optional[Callable[[str], None]] = None,
        stop_flag: Optional[Callable[[], bool]] = None,
    ) -> SynthesisResult:
        """把一本电子书在本机合成成音频，逐段落库到 task_id 名下。

        - on_progress(completed, total, current_text)：段级进度
        - on_log(msg)：日志（长段心跳每 5s 也从这里冒）
        - stop_flag() -> True：中断（在段间与片间都检查）
        - 断点续跑：task_id 下已 completed 且文件存在的段自动跳过

        task_id 应为调用方先在本地 tasks 表建好的记录 id（audio_name 建议形如
        「本机合成 · 书名」以便 Task.is_local 判定）。
        """
        log = on_log or (lambda _m: None)
        progress = on_progress or (lambda _d, _t, _c: None)
        should_stop = stop_flag or (lambda: False)

        result = SynthesisResult(task_id=task_id)
        started = time.time()

        _sentences, sub_pieces, has_readable = get_segmenter()

        # 1) 正文抽取 + 切句（段号 = 句子序号，与服务端口径一致）
        log(f"读取电子书：{book_path}")
        try:
            segments = get_book_sentences(book_path)
        except Exception as exc:
            result.message = str(exc)
            log(f"正文抽取失败：{exc}")
            if task_id:
                self.db.update_task_status(task_id, "failed")
            return result

        result.total_segments = len(segments)
        silent_count = sum(1 for s in segments if not has_readable(s))
        log(f"切出 {len(segments)} 句"
            + (f"（{silent_count} 句无可朗读内容，用静音占位）" if silent_count else ""))
        if not segments:
            result.message = "没有可朗读的句子"
            if task_id:
                self.db.update_task_status(task_id, "failed")
            return result

        audio_dir = self.config.audio_dir
        result.audio_dir = str(audio_dir)
        work_dir = self.config.resolved_data_dir / "tts_work" / f"task_{task_id}"
        work_dir.mkdir(parents=True, exist_ok=True)

        ffmpeg = find_ffmpeg()
        if not ffmpeg:
            log("未找到 ffmpeg：音频将保留 WAV（能播，只是更大）")

        # 2) 参考音频
        try:
            reference_wav = self._prepare_reference(ref_audio_path, work_dir, log)
        except Exception as exc:
            log(f"⚠ 参考音频处理失败，改用内置音色：{exc}")
            reference_wav = None

        # 3) 启动引擎
        try:
            log("加载 MOSS 引擎（首次加载模型可能要几十秒）…")
            self._start_engine(log)
        except MossEngineError as exc:
            result.message = f"引擎加载失败：{exc}"
            log(result.message)
            if task_id:
                self.db.update_task_status(task_id, "failed")
            return result

        last_beat = 0.0
        if task_id:
            self.db.update_task_status(task_id, "processing")

        # ---------- 会话登记（进程级） ----------
        # 必须在**真正开始合成**之后才登记：加载模型那几十秒里用户点「暂停」，
        # 没有会话在跑 ⇒ 明确拒绝（返回 False），而不是挂上一个永远等不到
        # 闸门的暂停状态。
        with self._session_lock:
            self._session_active = True
            self._session_task_id = int(task_id or 0)

        try:
            for index, sentence in enumerate(segments, start=1):
                if should_stop():
                    result.stopped = True
                    break

                # ---------- 暂停闸门（段边界） ----------
                # 必须放在**取段号之前**：若放在之后，暂停期间仍会把游标推到末尾，
                # 恢复时发现「没有剩余段了」，表现为**暂停一下就整本结束**。
                #
                # 暂停期间 worker 真正挂起（Condition.wait），不空转、不烧 CPU。
                if not self._pause.await_resume(should_stop):
                    # 放行但已中止 ⇒ 走中止路径
                    result.stopped = True
                    break

                # 断点续跑：上次已完成且音频还在 → 跳过，段号照样占位
                existing = self.db.get_segment(task_id, index) if task_id else None
                if (existing and existing.status == "completed"
                        and existing.audio_file_path
                        and Path(existing.audio_file_path).is_file()):
                    result.skipped += 1
                    progress(index, len(segments), sentence)
                    continue

                progress(index - 1, len(segments), sentence)
                wav_path = work_dir / f"seg_{index:05d}.wav"
                seg_started = time.time()

                if not has_readable(sentence):
                    write_silent_wav(wav_path)
                else:
                    pieces = [p for p in sub_pieces(sentence) if has_readable(p)]
                    if not pieces:
                        write_silent_wav(wav_path)
                    else:
                        piece_paths: List[Path] = []
                        piece_failed: Optional[str] = None
                        stopped_mid = False
                        for pi, piece in enumerate(pieces):
                            if should_stop():
                                stopped_mid = True
                                break
                            piece_wav = work_dir / f"seg_{index:05d}_{pi:02d}.wav"
                            try:
                                self.engine.synthesize(
                                    text=piece, out_path=piece_wav, voice=voice,
                                    prompt_audio_path=reference_wav,
                                    seed=1234 + index * 100 + pi,
                                    max_new_frames=resolve_max_new_frames(piece))
                            except MossEngineError as exc:
                                piece_failed = str(exc)
                                break
                            if Path(piece_wav).is_file():
                                piece_paths.append(Path(piece_wav))
                            # 长段片级心跳（≥3 片才报，避免单片段刷屏）
                            if len(pieces) >= 3 and pi + 1 < len(pieces):
                                now = time.time()
                                if now - last_beat >= HEARTBEAT_SECONDS:
                                    last_beat = now
                                    log(f"段 {index}/{len(segments)} · "
                                        f"片 {pi + 1}/{len(pieces)} · "
                                        f"本段已 {now - seg_started:.0f}s")
                        if stopped_mid:
                            result.stopped = True
                            log(f"已在第 {index} 段中间中断（该段未落库，续跑会重做）")
                            break
                        if piece_failed or not piece_paths:
                            reason = piece_failed or "合成未产出音频"
                            result.failed += 1
                            result.errors.append(f"第 {index} 段：{reason}")
                            log(f"第 {index} 段合成失败：{reason}")
                            if task_id:
                                self.db.insert_segment(Segment(
                                    task_id=task_id, segment_number=index,
                                    text_content=sentence, audio_file_path="",
                                    status="failed", voice=voice or "",
                                    source="local"))
                            progress(index, len(segments), sentence)
                            continue
                        if len(piece_paths) == 1:
                            piece_paths[0].replace(wav_path)
                        else:
                            concat_wavs(piece_paths, wav_path)

                # 4) 转 mp3（或保留 wav）
                suffix = ".wav"
                final_path = audio_dir / f"local_{task_id}_{index:05d}.wav"
                mp3_path = audio_dir / f"local_{task_id}_{index:05d}.mp3"
                if ffmpeg and convert_to_mp3(wav_path, mp3_path, ffmpeg):
                    suffix = ".mp3"
                    final_path = mp3_path
                    wav_path.unlink(missing_ok=True)
                else:
                    shutil.copyfile(wav_path, final_path)

                if task_id:
                    self.db.insert_segment(Segment(
                        task_id=task_id, segment_number=index, text_content=sentence,
                        audio_file_path=str(final_path),
                        duration_seconds=wav_duration(wav_path),
                        status="completed", voice=voice or "",
                        source="local"))
                result.succeeded += 1
                progress(index, len(segments), sentence)

                # 进度落库（每 10 段一次，崩溃后也能看出跑到哪）
                if task_id and (index % 10 == 0 or index == len(segments)):
                    self.db.update_task_status(
                        task_id, "processing", result.succeeded + result.skipped)
        finally:
            self.engine.stop()
            shutil.rmtree(work_dir, ignore_errors=True)
            # 会话标志必须在这里清：否则本机合成虽然结束了，
            # 下一次「开始合成」会被自己判成「已有任务在跑」而永远启动不了。
            with self._session_lock:
                self._session_active = False
                self._session_task_id = 0
            # 复位暂停状态：结束后若还留着 True，用户再次开始合成时
            # 按钮会显示「继续」，而实际上根本没在暂停 —— 状态分叉。
            self._pause.release_all()
            # 阶段文案清空：任务行上不该留着上一轮的「已暂停」。
            if task_id:
                try:
                    self.db.update_task_progress_text(task_id, "")
                except Exception:  # noqa: BLE001
                    pass

        # 5) 收尾
        total_done = result.succeeded + result.skipped
        if task_id:
            if result.stopped:
                # ⚠️ 这里的 `paused` 是「**被中止**、可续跑」，与上面那个
                # **内存闸门式的暂停**（pause_synthesis）是两件不同的事：
                #   · 本行的 paused = 合成线程**已经退出**了，任务落到终态，
                #     要续跑得重新发起一轮（复用本行，走断点续跑）；
                #   · 内存暂停 = 合成线程**还活着**、挂在 await_resume 上，
                #     任务状态仍是 processing，只靠 progress_text 显示「已暂停」。
                # 两者共用同一个 status 值会导致「暂停中」的按钮行为自相矛盾，
                # 所以内存暂停**绝不写 status**（对齐安卓：pause 只改 progress_text）。
                self.db.update_task_status(task_id, "paused", total_done)
            else:
                self.db.update_task_status(
                    task_id, "completed" if result.failed == 0 else "partial", total_done)

        result.elapsed_seconds = time.time() - started
        result.message = (f"成功 {result.succeeded} 段，失败 {result.failed} 段"
                          + (f"，跳过 {result.skipped} 段" if result.skipped else "")
                          + ("（已中断）" if result.stopped else ""))
        log("合成结束：" + result.message)
        return result
