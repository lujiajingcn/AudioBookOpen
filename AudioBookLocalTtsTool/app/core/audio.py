"""音频工具：WAV → MP3 转换与时长读取。

## 为什么要转码

MOSS 引擎产出的是 48 kHz 立体声 WAV；服务端 `audio_segments.audio_file_path`
里的既有音频全是 mp3（`tts_{taskId}_{n}.mp3`），客户端也是按 mp3 播的。
所以本地合成完统一转成 mp3，命名与路径规则也照服务端来
（见 `db.segment_audio_path`），这样「本地产物」与「服务器产物」长得一模一样。

## ffmpeg 找不到怎么办

不阻塞：保留 wav 并按 .wav 落库。服务端下载接口是按**文件路径**取音频、
Content-Type 固定 audio/mpeg，wav 字节照样能播（客户端按内容解码）。
只是文件大几倍、上传慢一些。工具会在日志里说清楚，不会静默降级。
"""

from __future__ import annotations

import shutil
import subprocess
import wave
from pathlib import Path


def find_ffmpeg(configured: str = "") -> str | None:
    """定位 ffmpeg：先看用户填的路径，再看 PATH。"""
    if configured:
        path = Path(configured).expanduser()
        if path.is_file():
            return str(path)
        found = shutil.which(configured)
        if found:
            return found
    return shutil.which("ffmpeg")


def wav_duration(path: str | Path) -> float:
    """读取 wav 时长（秒）。读不出来返回 0.0。"""
    try:
        with wave.open(str(path), "rb") as wav:
            frames = wav.getnframes()
            rate = wav.getframerate() or 1
            return frames / float(rate)
    except Exception:
        return 0.0


def convert_to_mp3(
    wav_path: str | Path,
    mp3_path: str | Path,
    ffmpeg: str | None,
    bitrate: str = "64k",
) -> bool:
    """把 wav 转成 mp3。成功返回 True；ffmpeg 不可用或失败返回 False。"""
    if not ffmpeg:
        return False
    wav = Path(wav_path)
    mp3 = Path(mp3_path)
    mp3.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        ffmpeg, "-y", "-loglevel", "error",
        "-i", str(wav),
        "-codec:a", "libmp3lame",
        "-b:a", bitrate,
        str(mp3),
    ]
    try:
        result = subprocess.run(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    if result.returncode != 0 or not mp3.is_file():
        return False
    return mp3.stat().st_size > 0


# 静音占位音频的规格：与服务端 WavConcat.silentWav() 完全一致
# （22050 Hz / 单声道 / 16 bit / 300 毫秒）。
# 两端规格一致，客户端切段播放时不会因为采样率突变而卡顿。
SILENT_SAMPLE_RATE = 22050
SILENT_MILLIS = 300


def write_silent_wav(path: str | Path) -> Path:
    """生成一段静音 WAV（16 bit PCM / 单声道），用作「整段都没有可朗读内容」的占位。

    为什么需要它：段文本若整段都是排版装饰线（下划线、点线）或空白，
    过滤掉无可朗读内容的小片后就没有任何东西可送引擎了（空文本必然合成失败）。
    服务端的做法是给一段静音占位，**保住「每段都有音频」的契约** ——
    少了它，客户端点第 N 段就是一片空白。
    """
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    frames = SILENT_SAMPLE_RATE * SILENT_MILLIS // 1000
    with wave.open(str(target), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SILENT_SAMPLE_RATE)
        wav.writeframes(b"\x00\x00" * frames)
    return target


def concat_wavs(wav_paths: list[str | Path], out_path: str | Path) -> Path:
    """把多个 wav 首尾相接成一条音频（超长句「拆小片分别合成再拼回」的落点）。

    与服务端 `WavConcat.concat` 同一思路：服务端把 >45 字的句子拆成小片、
    每片单独合成，最后拼回一条音频。工具照做，两边的切分点与停顿才一致。

    参数不一致（采样率/声道/位宽不同）时无法直接拼接 —— 这种情况按第一片的参数
    重采样是不安全的，宁可原样拼接第一片并把其余片丢掉（宁可少内容，也不要
    产出速度错乱的音频）。
    """
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
                # 只比「格式参数」（声道/位宽/采样率）—— **不能比 nframes**：
                # 每片长度本来就不同，比了会把后面所有片都判成「格式不同」而丢掉，
                # 结果只剩第一片（曾真实发生过：87 字拆 3 片，拼接后只剩 8.6s / 18.7s）。
                if wav.getparams()[:3] != params[:3]:
                    continue
                frames.append(wav.readframes(wav.getnframes()))
        except Exception:
            continue
    with wave.open(str(target), "wb") as out:
        # 只写格式参数：nframes 由 writeframes 自动累加
        out.setnchannels(params.nchannels)
        out.setsampwidth(params.sampwidth)
        out.setframerate(params.framerate)
        out.writeframes(b"".join(frames))
    return target
