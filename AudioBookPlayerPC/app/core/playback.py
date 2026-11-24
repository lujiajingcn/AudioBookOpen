"""音频播放引擎：基于 pygame 的 MP3 播放。

## 为什么是「后台线程 + 逻辑时钟」而不是「纯 SDL 回调」

pygame 的播放状态只在**主线程 / 播放线程**里可见，而本引擎被 GUI（Qt 主线程）
调用。若把「一段播完自动接下一段」的逻辑挂在 SDL 事件上，就要求调用方去 pump
pygame 的事件队列 —— Qt 的事件循环根本不会读 pygame 事件，段与段之间就会卡住。

所以这里用一条独立的守护线程轮询 ``pygame.mixer.music.get_busy()`` 作为「段结束」
的信号，播完一段立刻加载下一段。GUI 侧只管调 play/pause/stop，回调（on_segment_*）
从这条后台线程投递过来。

## 关于「变速播放」

pygame 的流式后端（``pygame.mixer.music``）**没有原生的变速能力**：MP3 解码后按
原始采样率送声卡，没有任何「以 1.5x 播放并保持音高」的接口。真正变速需要把整段
解码成 PCM 再重采样（numpy），代价是内存和 CPU。

因此这里采取务实策略：
  - 请求的倍速记录在 ``self._speed``；
  - 播放**位置**按倍速折算（``current_position_ms``），使阅读页的高亮/进度与
    用户选择的语速保持一致；
  - 若装好了 numpy，且当前段能解码成 PCM，则真的做重采样变速（``_playback_backend``
    为 "sound"）；否则退回流式，音频本身仍是原速，仅时间轴按倍速推进。

这套折中保证了：**没装 pygame/numpy 也能导入本模块、也不会崩**，装上了则尽量还原体验。
"""
from __future__ import annotations

import os
import math
import platform
import subprocess
import sys
import tempfile
import threading
import time
from typing import Callable, List, Optional


class PlaybackError(RuntimeError):
    """播放相关错误（未安装 pygame、文件不存在等）。"""


# 位置轮询间隔（秒）：越小段结束响应越快、CPU 越高。50ms 足够顺滑。
_POLL_INTERVAL = 0.05


def _which(cmd: str) -> Optional[str]:
    """在 PATH 中查找可执行文件（Linux/macOS 用）。找不到返回 None。"""
    from shutil import which  # 局部导入，避免顶层再增加依赖面
    return which(cmd)


def detect_tts_backend() -> Optional[str]:
    """探测当前系统可用的「系统语音合成」后端。

    返回 "windows" / "mac" / "linux" / None：
      - windows：用 PowerShell 调 System.Speech（.NET 内置语音，Win10/11 自带）；
      - mac：用自带的 ``say`` 命令；
      - linux：优先 espeak-ng，退回 espeak，再退回 spd-say（需 speech-dispatcher）。
    没有任何可用后端时返回 None，调用方据此优雅降级（不崩、给出提示）。
    """
    name = platform.system().lower()
    if name.startswith("win") or sys.platform.startswith("win"):
        return "windows"
    if name == "darwin" or sys.platform == "darwin":
        return "mac"
    # Linux / *BSD 等：看装了哪个命令行 TTS
    for c in ("espeak-ng", "espeak", "spd-say"):
        if _which(c):
            return "linux"
    return None


class AudioEngine:
    """线程安全的音频播放引擎。

    支持单文件播放与「连续段」播放，段结束/全部结束通过回调通知；带睡眠定时。
    所有公开方法都可以从任意线程调用（内部用一把可重入锁保护状态）。
    """

    def __init__(self) -> None:
        self._lock = threading.RLock()

        # ---- 播放列表状态 ----
        self._files: List[str] = []
        self._index: int = 0
        self._current_path: Optional[str] = None

        # ---- 传输状态 ----
        self._playing: bool = False       # 已装载一段并处于播放/暂停中
        self._paused: bool = False
        self._stop_requested: bool = False

        # ---- 时间与位置 ----
        self._volume: float = 1.0
        self._speed: float = 1.0          # 用户请求的倍速
        self._applied_speed: float = 1.0  # 实际作用于时间轴的倍速
        self._base_pos_ms: int = 0        # 本段开始（或 seek 落点）对应的原始毫秒
        self._paused_at_ms: int = 0
        self._started_mono: float = 0.0   # perf_counter() 起点
        self._segment_duration_ms: int = 0

        # ---- 变速重采样后的 Sound（numpy 路径）----
        self._sound = None                # pygame.mixer.Sound
        self._channel = None              # 播放返回的 Channel
        self._backend: str = "none"       # "music" | "sound" | "none"

        # ---- 后台线程 ----
        self._monitor: Optional[threading.Thread] = None
        self._gen: int = 0                # 世代号：seek/stop/切段都 ++，用于让旧循环退出

        # ---- 边下载边播放：动态追加 + 队尾等待探针 ----
        # has_more_probe() 返回 True 表示「还有更多段在后台下载」，监控线程播到队尾时不立即
        # 结束、而是等待 append_files() 追加后继续接播（书城书「点开即听」依赖它）。
        self._has_more_probe: Optional[Callable[[], bool]] = None

        # ---- 睡眠定时 ----
        self._sleep_timer: Optional[threading.Timer] = None

        # ---- 系统 TTS（临时朗读）----
        self._tts_backend: Optional[str] = detect_tts_backend()
        self._tts_proc = None               # 正在朗读的子进程
        self._tts_tmp: Optional[str] = None # 承载文本的临时文件（用完删）
        self._tts_speaking: bool = False

        # ---- 回调（从后台线程调用）----
        self.on_segment_start: Optional[Callable[[int], None]] = None
        self.on_segment_end: Optional[Callable[[int], None]] = None
        self.on_all_complete: Optional[Callable[[], None]] = None

        self._mixer_ready: bool = False

    # ==================================================================
    # pygame 懒加载：没装 pygame 也能 import，只有真正播放才报错
    # ==================================================================

    def _require_pygame(self):
        try:
            import pygame  # type: ignore
        except ImportError as exc:  # pragma: no cover - 取决于运行环境
            raise PlaybackError("未安装 pygame，无法播放音频。请 pip install pygame") from exc
        return pygame

    def _ensure_mixer(self) -> None:
        if self._mixer_ready:
            return
        pygame = self._require_pygame()
        try:
            # 44.1kHz / 16bit / 立体声 / 512 缓冲区：兼顾 MP3 解码兼容与延迟
            pygame.mixer.pre_init(44100, -16, 2, 512)
            pygame.mixer.init()
        except Exception as exc:  # pygame.mixer.init 失败常见于无声卡
            raise PlaybackError(f"初始化音频设备失败：{exc}") from exc
        self._mixer_ready = True

    # ==================================================================
    # 对外属性
    # ==================================================================

    @property
    def is_playing(self) -> bool:
        """正在播放（未暂停、已装载段）。"""
        with self._lock:
            if not self._playing or self._paused:
                return False
            return self._track_active()

    @property
    def is_paused(self) -> bool:
        with self._lock:
            return self._playing and self._paused

    @property
    def current_index(self) -> int:
        with self._lock:
            return self._index

    @property
    def current_position_ms(self) -> int:
        """当前段内已播放位置（按请求倍速折算，单位毫秒）。"""
        with self._lock:
            if not self._playing:
                return 0
            if self._paused:
                pos = self._paused_at_ms
            else:
                elapsed = (time.perf_counter() - self._started_mono) * 1000.0
                pos = self._base_pos_ms + elapsed * self._applied_speed
            if self._segment_duration_ms > 0:
                pos = min(pos, self._segment_duration_ms)
            return int(max(0, pos))

    @property
    def speed(self) -> float:
        with self._lock:
            return self._speed

    @property
    def volume(self) -> float:
        with self._lock:
            return self._volume

    # ==================================================================
    # 传输控制
    # ==================================================================

    def play_file(self, path: str) -> None:
        """播放单个 MP3（等价于只含一个文件的序列）。"""
        if not path or not os.path.isfile(path):
            raise PlaybackError(f"音频文件不存在：{path}")
        self._ensure_mixer()
        with self._lock:
            self._stop_locked(reset_callbacks=False)
            self._files = [path]
            self._index = 0
            self._start_segment_locked(0, start_ms=0, fire_callback=True)
            self._start_monitor_locked()

    def play_sequence(self, files: List[str], start_index: int = 0) -> None:
        """连续播放一串 MP3；每段播完自动接下一段，全部播完触发 on_all_complete。"""
        if not files:
            raise PlaybackError("播放列表为空")
        self._ensure_mixer()
        start_index = max(0, min(start_index, len(files) - 1))
        with self._lock:
            self._stop_locked(reset_callbacks=False)
            self._files = list(files)
            self._index = start_index
            self._start_segment_locked(start_index, start_ms=0, fire_callback=True)
            self._start_monitor_locked()

    def append_files(self, paths: List[str]) -> None:
        """把更多文件追加到当前播放列表末尾（用于「边下载边播放」）。

        监控线程会在每段播完后重新读列表长度，新追加的段会被无缝接上；配合
        ``set_has_more_probe``，播到旧队尾时若有段在下载，会等待追加而不是结束。
        """
        if not paths:
            return
        with self._lock:
            self._files.extend(list(paths))

    def set_has_more_probe(self, probe: Optional[Callable[[], bool]]) -> None:
        """设置「是否还有更多段在下载」探针；传 None 关闭（恢复「播完即结束」）。"""
        with self._lock:
            self._has_more_probe = probe

    def pause(self) -> None:
        with self._lock:
            if not self._playing or self._paused:
                return
            self._paused_at_ms = self._compute_position_ms()
            self._track_pause()
            self._paused = True

    def resume(self) -> None:
        with self._lock:
            if not self._playing or not self._paused:
                return
            self._track_unpause()
            # 重置单调时钟基准：从暂停点继续推进
            self._base_pos_ms = self._paused_at_ms
            self._started_mono = time.perf_counter()
            self._paused = False

    def stop(self) -> None:
        with self._lock:
            self._stop_locked(reset_callbacks=False)

    def set_speed(self, rate: float) -> None:
        """设置播放倍速（0.5 ~ 3.0）。

        位置时间轴始终按此倍速折算；若装好 numpy 且当前段可解码 PCM，则当前段会以
        真实变速重新起播（保持音高不变的重采样不做，这里做的是「变速变音高」的轻量实现，
        与系统播放器「快进不失真」不同，但对听书场景足够）。numpy 不可用时保持原速，
        仅时间轴按倍速推进。
        """
        rate = max(0.25, min(4.0, float(rate)))
        with self._lock:
            old = self._speed
            self._speed = rate
            if not self._playing:
                # 记下，下一段起播时生效
                return
            if self._backend == "sound" and abs(old - rate) > 1e-3:
                # 真实变速：用新倍速重新起播当前段（保持听感与时间轴一致）
                self._start_segment_locked(
                    self._index, start_ms=self._compute_position_ms(), fire_callback=False)
            elif self._backend == "music":
                # 流式无法真变速：重新锚定逻辑时间轴，使位置按新倍速推进
                pos = self._compute_position_ms()
                self._base_pos_ms = pos
                self._started_mono = time.perf_counter()
                self._applied_speed = rate

    def set_volume(self, vol: float) -> None:
        vol = max(0.0, min(1.0, float(vol)))
        with self._lock:
            self._volume = vol
            self._apply_volume_locked()

    def seek(self, position_ms: int) -> None:
        """在当前段内定位（毫秒）。"""
        position_ms = max(0, int(position_ms))
        with self._lock:
            if not self._playing or not self._current_path:
                return
            self._start_segment_locked(
                self._index, start_ms=position_ms, fire_callback=False)

    # ==================================================================
    # 睡眠定时
    # ==================================================================

    def sleep_timer(self, minutes: int) -> None:
        """N 分钟后自动停止播放。传 <=0 取消已设定时。"""
        with self._lock:
            if self._sleep_timer is not None:
                self._sleep_timer.cancel()
                self._sleep_timer = None
            if minutes is None or minutes <= 0:
                return
            timer = threading.Timer(minutes * 60.0, self._on_sleep_timer_fired)
            timer.daemon = True
            self._sleep_timer = timer
            timer.start()

    def _on_sleep_timer_fired(self) -> None:
        with self._lock:
            self._sleep_timer = None
            self._stop_locked(reset_callbacks=False)

    # ==================================================================
    # 系统 TTS：朗读任意临时文本（"朗读选中" / 无音频书的"从此处朗读"）
    # ==================================================================

    @property
    def is_speaking(self) -> bool:
        """系统 TTS 是否正在朗读（底层子进程仍存活）。

        顺带回收已结束的进程与临时文件，使调用方（GUI 轮询）读到的是最新状态。
        """
        with self._lock:
            if not self._tts_speaking or self._tts_proc is None:
                return False
            if self._tts_proc.poll() is not None:
                self._tts_speaking = False
                self._tts_proc = None
                self._cleanup_tts_tmp_locked()
                return False
            return True

    def speak_temp(self, text: str, rate: Optional[float] = None) -> bool:
        """用系统语音引擎朗读一段临时文本。非阻塞，可被 stop()/stop_speak() 打断。

        返回 True 表示已成功起播（本机存在可用后端）；False 表示当前系统没有
        可用的语音引擎，调用方应据此提示用户。文本写入临时 UTF-8 文件再交给子
        进程读取，规避命令行转义与超长限制；Windows 下优先选择中文(zh)音色。

        rate：可选倍速（约 0.5~3.0），按各后端语义折算；None 表示用默认语速。
        """
        text = (text or "").strip()
        if not text:
            return False
        backend = self._tts_backend
        if not backend:
            return False
        with self._lock:
            self._stop_locked(reset_callbacks=False)  # 打断正在播放的音频与上一次朗读
            tmp = None
            try:
                fd, tmp = tempfile.mkstemp(prefix="audiobook_tts_", suffix=".txt")
                with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
                    f.write(text)
                argv, extra_env = self._build_tts_command(backend, tmp, rate)
                if not argv:
                    self._unlink_tmp(tmp)
                    return False
                env = dict(os.environ)
                env["PYTHONUTF8"] = "1"
                if extra_env:
                    env.update(extra_env)
                kwargs = {
                    "env": env,
                    "stdin": subprocess.DEVNULL,
                    "stdout": subprocess.DEVNULL,
                    "stderr": subprocess.DEVNULL,
                }
                if sys.platform.startswith("win"):
                    # 不弹出黑色控制台窗口
                    kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0)
                proc = subprocess.Popen(argv, **kwargs)
                self._tts_proc = proc
                self._tts_tmp = tmp
                self._tts_speaking = True
                return True
            except Exception:  # noqa: BLE001
                if tmp:
                    self._unlink_tmp(tmp)
                return False

    def speak_texts(self, texts: List[str], rate: Optional[float] = None) -> bool:
        """按顺序朗读多段文本（合并为一次连续朗读，段间以换行分隔）。"""
        joined = "\n".join(t.strip() for t in (texts or []) if t and t.strip())
        return self.speak_temp(joined, rate=rate)

    def stop_speak(self) -> None:
        """立即停止当前系统朗读（终止子进程并清理临时文件）。"""
        with self._lock:
            self.stop_speak_locked()

    def stop_speak_locked(self) -> None:
        """终止朗读子进程并清理临时文件。调用方须已持锁。"""
        proc = self._tts_proc
        self._tts_proc = None
        self._tts_speaking = False
        if proc is not None and proc.poll() is None:
            try:
                proc.terminate()
                try:
                    proc.wait(timeout=0.5)
                except Exception:
                    proc.kill()
            except Exception:
                pass
        self._cleanup_tts_tmp_locked()

    def _cleanup_tts_tmp_locked(self) -> None:
        if self._tts_tmp:
            self._unlink_tmp(self._tts_tmp)
            self._tts_tmp = None

    @staticmethod
    def _unlink_tmp(path: Optional[str]) -> None:
        if not path:
            return
        try:
            os.unlink(path)  # 仅删除本引擎自建的临时朗读文件，非用户数据
        except Exception:
            pass

    @staticmethod
    def _map_windows_rate(rate: Optional[float]) -> int:
        """把倍速（0.5~3.0）按指数映射到 System.Speech 的 Rate(-10~10，0=正常)。"""
        if rate is None:
            return 0
        try:
            r = max(0.5, min(3.0, float(rate)))
            steps = round(10 * math.log2(r))
            return int(max(-10, min(10, steps)))
        except Exception:
            return 0

    def _build_tts_command(self, backend: str, tmp_path: str,
                           rate: Optional[float]):
        """按后端构造 (argv, extra_env)。argv 为空表示无法构造。"""
        if backend == "windows":
            script = (
                "Add-Type -AssemblyName System.Speech; "
                "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                "$zh = $s.GetInstalledVoices() | Where-Object { "
                "$_.Enabled -and $_.VoiceInfo.Culture.Name -like 'zh*' } | "
                "Select-Object -First 1; "
                "if ($zh) { $s.SelectVoice($zh.VoiceInfo.Name) }; "
                "$r = $env:AB_TTS_RATE; if ($r) { try { $s.Rate = [int]$r } catch {} }; "
                "$t = Get-Content -LiteralPath $env:AB_TTS_FILE -Encoding UTF8 -Raw; "
                "if ($t) { $s.Speak($t) }; "
                "$s.Dispose();"
            )
            exe = _which("powershell") or _which("powershell.exe")
            if not exe:
                windir = os.environ.get("WINDIR") or r"C:\Windows"
                cand = os.path.join(
                    windir, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
                exe = cand if os.path.exists(cand) else "powershell"
            extra = {"AB_TTS_FILE": tmp_path}
            if rate is not None:
                wr = self._map_windows_rate(rate)
                if wr:
                    extra["AB_TTS_RATE"] = str(wr)
            return [exe, "-NoProfile", "-NonInteractive", "-Command", script], extra

        if backend == "mac":
            argv = ["say"]
            if rate is not None:
                base = 180
                argv += ["--rate", str(int(base * max(0.5, min(3.0, float(rate)))))]
            argv += ["-f", tmp_path]
            return argv, None

        if backend == "linux":
            for c in ("espeak-ng", "espeak"):
                if _which(c):
                    argv = [c]
                    if rate is not None:
                        base = 175
                        argv += ["-s", str(int(base * max(0.5, min(3.0, float(rate)))))]
                    argv += ["-f", tmp_path]
                    return argv, None
            if _which("spd-say"):
                try:
                    with open(tmp_path, encoding="utf-8") as f:
                        txt = f.read()
                except Exception:
                    return None, None
                return ["spd-say", "-t", "0", txt], None

        return None, None

    # ==================================================================
    # 内部：段装载 / 后端抽象
    # ==================================================================

    def _start_segment_locked(self, index: int, start_ms: int, fire_callback: bool) -> None:
        """装载并起播 index 处文件，从 start_ms 开始。调用方须已持有锁。"""
        files = self._files
        if index < 0 or index >= len(files):
            return
        path = files[index]
        self._gen += 1
        self._current_path = path
        self._index = index
        self._base_pos_ms = max(0, start_ms)
        self._paused_at_ms = self._base_pos_ms
        self._segment_duration_ms = 0
        self._playing = True
        self._paused = False
        self._stop_requested = False

        if not os.path.isfile(path):
            # 文件缺失：不崩，标记 0 长度交给监控线程跳到下一段
            self._backend = "none"
            self._applied_speed = 1.0
            self._sound = None
            self._channel = None
            self._started_mono = time.perf_counter()
            if fire_callback and self.on_segment_start:
                self._safe_call(self.on_segment_start, index)
            return

        # 优先尝试 numpy 变速（sound 后端）；失败退回 music 流式
        loaded = False
        if abs(self._speed - 1.0) > 1e-3:
            loaded = self._load_sound_backend(path, start_ms)
        if not loaded:
            loaded = self._load_music_backend(path, start_ms)

        self._started_mono = time.perf_counter()
        self._apply_volume_locked()
        if fire_callback and self.on_segment_start:
            self._safe_call(self.on_segment_start, index)

    def _load_music_backend(self, path: str, start_ms: int) -> bool:
        pygame = self._require_pygame()
        try:
            pygame.mixer.music.load(path)
        except Exception:
            self._backend = "none"
            return False
        self._sound = None
        self._channel = None
        try:
            # start 单位：pygame 用「秒」。MP3 的 seek 支持依赖 SDL_mixer，失败则从头播。
            #
            # ⚠️ 这里踩过两个坑（2026-11-07）：
            #   1) 关键字叫 ``loops`` 不是 ``loopy``。写错时每次起播都抛 TypeError，
            #      被外层 ``except TypeError`` 吞掉后走 ``play()`` —— 于是
            #      **带 start 的定位永远不生效，每段都从头播**（表现为「点了进度没反应」）。
            #   2) ``music.play()`` 返回 None，不是 bool。旧代码 ``if not ok:`` 恒为真，
            #      即使 start 成功也会立刻重播一遍并清零时间轴。
            if start_ms > 0:
                try:
                    pygame.mixer.music.play(loops=0, start=start_ms / 1000.0)
                except Exception:
                    # 老版本签名 / SDL_mixer 不支持 MP3 seek：从头播，并把时间轴对齐到 0
                    pygame.mixer.music.play(loops=0)
                    self._base_pos_ms = 0
            else:
                pygame.mixer.music.play(loops=0)
        except TypeError:
            # 极老版本：play() 不接受任何关键字
            pygame.mixer.music.play()
            self._base_pos_ms = 0
        except Exception:
            self._backend = "none"
            return False
        self._backend = "music"
        self._applied_speed = 1.0
        # 时长（近似）：读不了就不限制位置
        self._segment_duration_ms = self._estimate_duration_ms(path)
        return True

    def _load_sound_backend(self, path: str, start_ms: int) -> bool:
        """用 numpy 重采样实现真实变速；任何环节失败都返回 False（退回 music）。"""
        try:
            import numpy as np  # type: ignore
            import pygame  # type: ignore
            from pygame import sndarray  # type: ignore
        except Exception:
            return False

        try:
            base = pygame.mixer.Sound(path)
        except Exception:
            return False
        try:
            original = sndarray.array(base)  # shape (n, channels) 或 (n,)
        except Exception:
            return False

        total_frames = len(original)
        if total_frames == 0:
            return False
        rate = self._speed if abs(self._speed - 1.0) > 1e-3 else 1.0

        # 起始帧（原始时间轴 → 数组下标）
        sr = float(pygame.mixer.get_init()[2]) if pygame.mixer.get_init() else 44100.0
        start_frame = int(start_ms / 1000.0 * sr)
        start_frame = max(0, min(start_frame, total_frames - 1))
        seg = original[start_frame:]
        self._segment_duration_ms = int(total_frames / sr * 1000)

        if abs(rate - 1.0) > 1e-3:
            # 线性重采样到「更快」：输出长度 = 输入 / rate
            n_out = max(1, int(len(seg) / rate))
            idx = np.linspace(0, len(seg) - 1, n_out)
            if seg.ndim == 1:
                resampled = np.interp(idx, np.arange(len(seg)), seg)
            else:
                cols = [np.interp(idx, np.arange(len(seg)), seg[:, c])
                        for c in range(seg.shape[1])]
                resampled = np.stack(cols, axis=1)
        else:
            resampled = seg

        try:
            resampled = resampled.astype(original.dtype)
            sound = sndarray.make_sound(resampled)
        except Exception:
            # make_sound 对采样格式很挑剔，失败就退回 music
            return False

        try:
            chan = sound.play()
        except Exception:
            return False
        if chan is None:
            return False

        # 记录：play() 返回正在播放该 sound 的 Channel（seek 靠上面的数组切片实现）
        self._sound = sound
        self._channel = chan
        self._backend = "sound"
        self._applied_speed = rate
        return True

    def _estimate_duration_ms(self, path: str) -> int:
        """尽力估计音频时长（有 numpy 时用文件字节数粗估，读不出来返回 0）。"""
        try:
            # 粗略：MP3 64kbps 时 8 字节/毫秒。仅作位置夹逼用，不追求精确。
            size = os.path.getsize(path)
            return int(size / 8.0)
        except Exception:
            return 0

    # ---- 后端多态的原子操作 ----

    def _track_active(self) -> bool:
        if self._backend == "music":
            import pygame  # type: ignore
            return pygame.mixer.music.get_busy()
        if self._backend == "sound":
            return self._channel is not None and self._channel.get_busy()
        # backend none：文件缺失也当作「已结束」，交给监控线程处理
        return False

    def _track_pause(self) -> None:
        if self._backend == "music":
            import pygame  # type: ignore
            pygame.mixer.music.pause()
        elif self._backend == "sound":
            if self._channel is not None:
                self._channel.pause()

    def _track_unpause(self) -> None:
        if self._backend == "music":
            import pygame  # type: ignore
            pygame.mixer.music.unpause()
        elif self._backend == "sound":
            if self._channel is not None:
                self._channel.unpause()

    def _track_stop(self) -> None:
        try:
            import pygame  # type: ignore
            pygame.mixer.music.stop()
        except Exception:
            pass
        try:
            if self._sound is not None:
                self._sound.stop()
            if self._channel is not None:
                self._channel.stop()
        except Exception:
            pass

    def _apply_volume_locked(self) -> None:
        try:
            if self._backend == "music":
                import pygame  # type: ignore
                pygame.mixer.music.set_volume(self._volume)
            elif self._backend == "sound" and self._sound is not None:
                self._sound.set_volume(self._volume)
        except Exception:
            pass

    def _compute_position_ms(self) -> int:
        """无锁版本的位置计算（调用方已持锁）。"""
        if not self._playing:
            return 0
        if self._paused:
            return self._paused_at_ms
        elapsed = (time.perf_counter() - self._started_mono) * 1000.0
        pos = self._base_pos_ms + elapsed * self._applied_speed
        if self._segment_duration_ms > 0:
            pos = min(pos, self._segment_duration_ms)
        return int(max(0, pos))

    # ==================================================================
    # 后台监控线程
    # ==================================================================

    def _start_monitor_locked(self) -> None:
        if self._monitor is not None and self._monitor.is_alive():
            return
        gen = self._gen
        self._monitor = threading.Thread(
            target=self._monitor_loop, args=(gen,), daemon=True)
        self._monitor.start()

    def _monitor_loop(self, gen: int) -> None:
        """轮询当前段是否播完；播完则回调并推进到下一段。

        用世代号 self._gen 判断自己是否还「当值」：一旦 seek/stop/新起一段令 _gen
        变化，旧循环立刻退出，避免两条线程同时推进播放列表。
        """
        while True:
            with self._lock:
                if self._stop_requested or gen != self._gen:
                    return
                playing = self._playing
                paused = self._paused
            if not playing:
                return
            if paused:
                time.sleep(_POLL_INTERVAL)
                continue
            if self._track_active():
                time.sleep(_POLL_INTERVAL)
                continue

            # 当前段播完（或文件缺失）
            with self._lock:
                if self._stop_requested or gen != self._gen:
                    return
                self._track_stop()
                self._playing = False
                end_index = self._index
                on_end = self.on_segment_end
            if on_end:
                self._safe_call(on_end, end_index)

            # 推进到下一段：实时读长度（播放期间可能有 append_files 追加）。
            # 追到队尾且探针报告「还有段在下载」时，锁外等待追加而不是立刻结束。
            while True:
                with self._lock:
                    if self._stop_requested or gen != self._gen:
                        return
                    next_index = self._index + 1
                    if next_index < len(self._files):
                        self._start_segment_locked(next_index, start_ms=0, fire_callback=True)
                        gen = self._gen  # 跟上新一轮世代，继续本线程
                        break
                    probe = self._has_more_probe
                    on_all = self.on_all_complete
                if probe is not None:
                    try:
                        has_more = bool(probe())
                    except Exception:  # noqa: BLE001 —— 探针异常视为「没有更多」
                        has_more = False
                    if has_more:
                        time.sleep(_POLL_INTERVAL)
                        continue
                # 确认结束：清空播放列表并触发 on_all_complete
                with self._lock:
                    if self._stop_requested or gen != self._gen:
                        return
                    self._files = []
                    self._current_path = None
                    self._backend = "none"
                if on_all:
                    self._safe_call(on_all)
                return

    def _stop_locked(self, reset_callbacks: bool) -> None:
        """停止并复位状态。调用方须已持锁。"""
        self._stop_requested = True
        self._gen += 1  # 让监控线程退出
        self._track_stop()
        self._playing = False
        self._paused = False
        self._files = []
        self._current_path = None
        self._index = 0
        self._base_pos_ms = 0
        self._paused_at_ms = 0
        self._segment_duration_ms = 0
        self._backend = "none"
        self._sound = None
        self._channel = None
        self.stop_speak_locked()
        if reset_callbacks:
            self.on_segment_start = None
            self.on_segment_end = None
            self.on_all_complete = None

    # ==================================================================
    # 工具
    # ==================================================================

    @staticmethod
    def _safe_call(cb: Callable, *args) -> None:
        """调用回调并吞掉其中异常，避免一个坏回调拖垮后台线程。"""
        try:
            cb(*args)
        except Exception:
            pass

    def release(self) -> None:
        """彻底释放混音器（退出程序时调用）。"""
        with self._lock:
            self._stop_locked(reset_callbacks=True)
            if self._sleep_timer is not None:
                self._sleep_timer.cancel()
                self._sleep_timer = None
        if self._mixer_ready:
            try:
                import pygame  # type: ignore
                pygame.mixer.quit()
            except Exception:
                pass
            self._mixer_ready = False
