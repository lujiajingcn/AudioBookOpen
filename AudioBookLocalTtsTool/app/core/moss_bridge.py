"""MOSS-TTS-Nano 引擎桥接脚本（**在 MOSS 工程自己的解释器下运行**）。

## 为什么要有这个桥

MOSS-TTS-Nano 官方工程（github.com/OpenMOSS/MOSS-TTS-Nano）的推理入口是纯 Python：
`onnx_tts_runtime.OnnxTtsRuntime`（`infer_onnx.py` 就是这么用的）。
它依赖 onnxruntime / sentencepiece 等一堆包，官方推荐装在独立的 conda 环境里。
工具本体只需要 PySide6 + PyMySQL —— 两边依赖没有交集，硬塞进一个环境只会互相干扰。

所以拆成两个进程：

    工具主进程 (PySide6 环境)  --JSON 行协议-->  桥接进程 (MOSS 工程环境)
                                        回传 wav 路径

模型只加载一次（加载几十秒），之后逐段合成都在同一个进程里跑 ——
比「每段起一次 `infer_onnx.py`」快一个数量级。

## 协议

stdout 上每行一个 JSON（**不含任何其它输出**）：

    {"type": "ready", "voices": ["Junhao", ...]}      引擎就绪
    {"type": "done",  "id": 7, "path": "...wav"}       某段合成完成
    {"type": "error", "id": 7, "message": "..."}       某段失败

stdin 上每行一个 JSON 命令：

    {"cmd": "synth", "id": 7, "text": "要念的句子", "voice": "Junhao",
     "prompt_audio_path": "C:/.../ref.wav", "out": "C:/.../seg_7.wav", "seed": 1234}
    {"cmd": "quit"}

## stdout 为什么要「独占」

第三方库随时可能 print 点什么（进度、警告）。协议通道一旦被污染，
主进程就解析不出 JSON。所以这里把 Python 的 `sys.stdout` 指向 stderr，
协议数据用 `os.write(1, ...)` 直接写到文件描述符 1 —— 物理上不可能被 print 干扰。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import traceback

# 协议通道：dup 出来的原始 stdout（Python 的 sys.stdout 会被挪到 stderr）
_PROTOCOL_OUT = os.fdopen(os.dup(1), "w", encoding="utf-8", buffering=1)
sys.stdout = sys.stderr  # 第三方库的 print 一律走 stderr，不污染协议


def emit(payload: dict) -> None:
    """往协议通道写一行 JSON。"""
    _PROTOCOL_OUT.write(json.dumps(payload, ensure_ascii=False) + "\n")
    _PROTOCOL_OUT.flush()


def estimate_max_frames(text: str, base: int = 375) -> int:
    """估算本次合成的最大帧数（**仅在调用方没给时兜底**）。

    公式与服务端 `moss.tts.frames` 一致：帧数 = base(30) + 字符数 × 4.0，
    再夹到 [30, 375]。12.5 帧/秒 ⇒ 每字约 4 帧。

    ⚠️ 这里**不能**用「一律 375 帧」：引擎会把预算用满，短句尾部会拖出
    一大段无用静音，段间停顿忽长忽短（服务端为此专门做了一轮收紧）。
    """
    need = int(round(30 + len(text or "") * 4.0))
    return max(30, min(375, need))


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="MOSS-TTS-Nano 引擎桥接")
    parser.add_argument("--project", required=True, help="MOSS-TTS-Nano 工程目录")
    parser.add_argument("--model-dir", default=None,
                        help="ONNX 模型目录；留空则用工程默认（首次会自动下载）")
    parser.add_argument("--cpu-threads", type=int, default=4)
    parser.add_argument("--execution-provider", default="cpu", choices=("cpu", "cuda"))
    parser.add_argument("--sample-mode", default="fixed", choices=("greedy", "fixed", "full"))
    parser.add_argument("--enable-wetext", type=int, default=0)
    parser.add_argument("--max-new-frames", type=int, default=375)
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)

    project = os.path.abspath(args.project)
    if not os.path.isdir(project):
        emit({"type": "error", "message": f"MOSS 工程目录不存在：{project}"})
        return 2
    # 工程内的相对路径（models/ 等）都以工程根为基准
    sys.path.insert(0, project)
    os.chdir(project)

    try:
        from onnx_tts_runtime import OnnxTtsRuntime
    except Exception as exc:  # 依赖没装好是最常见的失败原因
        emit({
            "type": "error",
            "message": "加载 onnx_tts_runtime 失败：" + str(exc)
            + "；请确认已用 MOSS 工程的解释器启动，并在该环境里执行 pip install -r requirements.txt",
        })
        return 3

    try:
        runtime = OnnxTtsRuntime(
            model_dir=args.model_dir,
            thread_count=max(1, int(args.cpu_threads)),
            max_new_frames=int(args.max_new_frames),
            do_sample=True,
            sample_mode=args.sample_mode,
            execution_provider=args.execution_provider,
        )
    except Exception as exc:
        emit({"type": "error", "message": "加载模型失败：" + str(exc)})
        return 4

    voices: list[str] = []
    try:
        for item in runtime.list_builtin_voices():
            if isinstance(item, dict):
                name = str(item.get("voice", "")).strip()
            else:
                name = str(item).strip()
            if name:
                voices.append(name)
    except Exception:
        voices = []

    emit({
        "type": "ready",
        "voices": voices,
        "model_dir": str(getattr(runtime, "model_dir", "")),
        "execution_provider": str(getattr(runtime, "execution_provider", "")),
    })

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            continue
        cmd = str(request.get("cmd", ""))
        if cmd == "quit":
            break
        if cmd != "synth":
            continue

        req_id = request.get("id")
        text = str(request.get("text", ""))
        voice = request.get("voice")
        prompt_path = request.get("prompt_audio_path") or None
        out_path = str(request.get("out", ""))
        seed = request.get("seed")
        try:
            result = runtime.synthesize(
                text=text,
                voice=str(voice or "Junhao"),
                prompt_audio_path=prompt_path,
                output_audio_path=out_path,
                sample_mode=args.sample_mode,
                do_sample=True,
                streaming=True,
                max_new_frames=int(request.get("max_new_frames")
                                   or estimate_max_frames(text, args.max_new_frames)),
                voice_clone_max_text_tokens=75,
                enable_wetext=bool(args.enable_wetext),
                enable_normalize_tts_text=True,
                seed=int(seed) if seed is not None else None,
            )
            # 官方 infer_onnx.py 返回的是 dict，但不同版本可能返回带属性的对象：
            # 两种都兼容，免得版本一升级就报 AttributeError
            def _pick(name: str, default=None):
                if isinstance(result, dict):
                    return result.get(name, default)
                return getattr(result, name, default)

            emit({
                "type": "done",
                "id": req_id,
                "path": str(_pick("audio_path", out_path) or out_path),
                "sample_rate": int(_pick("sample_rate", 48000) or 48000),
            })

        except Exception as exc:
            emit({
                "type": "error",
                "id": req_id,
                "message": str(exc),
                "trace": traceback.format_exc(limit=3),
            })
    return 0


if __name__ == "__main__":
    sys.exit(main())
