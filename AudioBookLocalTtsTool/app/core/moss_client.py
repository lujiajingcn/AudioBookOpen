"""主进程侧的 MOSS 引擎客户端：拉起桥接进程并与之对话。

## 为什么是「长驻子进程」而不是「每段起一次 CLI」

`infer_onnx.py` 每次运行都要重新加载 ONNX 会话（几百 MB 的模型，几十秒）。
一本书动辄几百段，按段起进程的话，光加载模型就要几小时。
桥接进程只加载一次，之后每段的请求就是一次函数调用。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from app.config import MossSettings

# 桥接脚本与本文件同目录（打包成 exe 时用 sys._MEIPASS 里的副本）
_BRIDGE_SCRIPT = Path(__file__).with_name("moss_bridge.py")


class MossEngineError(RuntimeError):
    """引擎侧错误（加载失败、合成失败等）。"""


@dataclass
class SynthResult:
    """一次合成的结果。"""

    path: str
    sample_rate: int


class MossEngine:
    """MOSS-TTS-Nano 引擎的客户端封装（线程安全：同一时刻只允许一个合成请求）。"""

    def __init__(
        self,
        settings: MossSettings,
        on_log: Callable[[str], None] | None = None,
        bridge_path: str | Path | None = None,
    ) -> None:
        self._settings = settings
        self._on_log = on_log or (lambda _msg: None)
        # 桥接脚本路径：默认用本文件同目录的那份；
        # 留出注入点是为了能拿「假桥接」做协议级自测（不必真的装 MOSS 环境）
        self._bridge_path = Path(bridge_path) if bridge_path else _BRIDGE_SCRIPT
        self._proc: subprocess.Popen | None = None
        self._lock = threading.Lock()
        self._ready = threading.Event()
        self._result: dict | None = None
        self._next_id = 1
        self.voices: list[str] = []
        self._start_error: str | None = None
        # 引擎侧最近几条错误：加载超时时用来提示「到底是哪一步失败」
        self._errors: list[str] = []

    # ---------- 生命周期 ----------

    def start(self, timeout: float = 1800.0) -> None:
        """启动桥接进程并等待引擎就绪。

        超时给到 30 分钟：首次运行引擎会自动下载 ONNX 权重
        （100M 模型 + tokenizer，几百 MB），网络慢时可能要十几分钟。
        """
        project = Path(self._settings.project_path or "").expanduser()
        if not project.is_dir():
            raise MossEngineError(f"MOSS 工程目录不存在：{project}")
        if not self._bridge_path.is_file():
            raise MossEngineError(f"缺少桥接脚本：{self._bridge_path}")

        python_exe = self._settings.python_exe or sys.executable
        cmd = [
            python_exe,
            str(self._bridge_path),
            "--project", str(project),
            "--cpu-threads", str(max(1, int(self._settings.cpu_threads))),
            "--execution-provider", self._settings.execution_provider,
            "--sample-mode", self._settings.sample_mode,
            "--enable-wetext", "1" if self._settings.enable_wetext else "0",
        ]
        if self._settings.model_dir:
            cmd += ["--model-dir", str(Path(self._settings.model_dir).expanduser())]

        env = os.environ.copy()
        # 让子进程能用 UTF-8 输出（Windows 默认 GBK 会让中文日志乱码）
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUNBUFFERED"] = "1"

        creationflags = 0
        if sys.platform == "win32":
            # 不弹黑框（GUI 程序里最忌讳多一个控制台窗口）
            creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)

        self._on_log("启动引擎：" + " ".join(cmd))
        try:
            self._proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
                env=env,
                creationflags=creationflags,
            )
        except OSError as exc:
            raise MossEngineError(f"无法启动引擎进程：{exc}") from exc

        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()

        if not self._ready.wait(timeout):
            self.stop()
            raise MossEngineError(
                "引擎加载超时（模型较大时可能要几分钟）。"
                f"自检信息：{'；'.join(self._recent_errors) if self._recent_errors else '无'}"
            )
        if self._start_error:
            # 加载失败：子进程多半已经自己退出了，这里一并收尾
            self.stop()
            raise MossEngineError(self._start_error)

    @property
    def _recent_errors(self) -> list[str]:
        return self._errors

    def stop(self) -> None:
        """结束桥接进程（先礼后兵：发 quit，再 terminate，最后 kill）。"""
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

    def __enter__(self) -> "MossEngine":
        self.start()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.stop()

    # ---------- 协议收发的内部实现 ----------

    def _read_stdout(self) -> None:
        """读协议通道：ready / done / error。"""
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
                self._on_log("[引擎] " + line)
                continue
            kind = str(message.get("type", ""))
            if kind == "ready":
                self.voices = [str(v) for v in message.get("voices", [])]
                self._on_log(
                    "引擎就绪：内置音色 " + str(len(self.voices)) + " 个"
                    + (("（" + "、".join(self.voices[:6]) + "…）") if self.voices else "")
                )
                self._ready.set()
            elif kind == "error":
                message_text = str(message.get("message", "未知错误"))
                self._errors.append(message_text)
                del self._errors[:-5]
                if message.get("id") is None:
                    # **就绪之前**的 error = 引擎加载失败（工程路径错、解释器不对、
                    # 依赖没装好…）。必须立刻唤醒 start()，否则用户要干等超时才看到错误。
                    self._start_error = message_text
                    self._on_log("[引擎] 加载失败：" + message_text)
                    self._ready.set()
                else:
                    self._result = message
            elif kind == "done":
                self._result = message
            else:
                self._on_log("[引擎] " + line)

    def _read_stderr(self) -> None:
        """读引擎的日志（stderr）。"""
        proc = self._proc
        if proc is None or proc.stderr is None:
            return
        for line in proc.stderr:
            line = line.rstrip()
            if line:
                self._on_log("[引擎] " + line)

    # ---------- 对外接口 ----------

    def synthesize(
        self,
        text: str,
        out_path: str | Path,
        voice: str | None = None,
        prompt_audio_path: str | Path | None = None,
        seed: int | None = None,
        max_new_frames: int | None = None,
        timeout: float = 900.0,
    ) -> SynthResult:
        """合成一段文本，结果写到 out_path（wav）。

        max_new_frames：本次请求的生成帧数预算。**必须由调用方按文本长度算好**
        （与服务端 `moss.tts.frames` 同一公式）—— 引擎会把预算用满，
        给多了短句尾部会拖出一大段无用静音，段间停顿忽长忽短。

        同一时刻只允许一个合成请求（引擎内部是单实例 + KV cache，
        并发调用会互相踩状态）。
        """
        if self._proc is None or self._proc.poll() is not None:
            raise MossEngineError("引擎进程未运行")
        with self._lock:
            self._result = None
            req_id = self._next_id
            self._next_id += 1
            request = {
                "cmd": "synth",
                "id": req_id,
                "text": text,
                "out": str(Path(out_path)),
                "seed": seed,
            }
            if max_new_frames:
                request["max_new_frames"] = int(max_new_frames)
            if voice:
                request["voice"] = str(voice)
            if prompt_audio_path:
                request["prompt_audio_path"] = str(prompt_audio_path)
            try:
                self._proc.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
                self._proc.stdin.flush()
            except (BrokenPipeError, OSError) as exc:
                raise MossEngineError(f"引擎进程已退出：{exc}") from exc

            deadline = threading.Event()
            waited = 0.0
            step = 0.1
            while waited < timeout:
                if self._result is not None:
                    break
                deadline.wait(step)
                waited += step
            if self._result is None:
                raise MossEngineError(f"合成超时（{timeout:.0f} 秒未返回）")
            message = self._result
            self._result = None
            if str(message.get("type")) == "error":
                raise MossEngineError(str(message.get("message", "未知错误")))
            return SynthResult(
                path=str(message.get("path", "")),
                sample_rate=int(message.get("sample_rate", 48000)),
            )
