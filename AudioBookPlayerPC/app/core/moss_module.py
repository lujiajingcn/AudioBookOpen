"""MOSS-TTS-Nano 模块「按需下载」：Manifest / ModuleManager / Downloader 三件套。

对齐 Android 端 ``AudioBookPlayer/app/src/main/java/.../moss/MossManifest.kt``
``MossModuleManager.kt`` ``MossDownloader.kt``：

  - **同一份 manifest schema**：``version`` / ``archive.file,size,sha256`` /
    ``totals.downloadBytes,installedBytes`` / ``files[{name,size,sha256}]``；
  - **同一份下载协议**：HTTP ``Range: bytes=<已下字节>-`` + ``.part`` 断点、
    下完立刻校验**整包 sha256**通过才 rename 提交，解压到 staging 后**逐文件 sha256**
    校验，任何一条不符就整体回滚 —— 不做「部分可用」这种含糊状态；
  - **同一份基址覆盖机制**：客户端优先读 ``AppConfig.moss_module_base_url``，
    未配置时从 ``base_url`` 派生 ``<base>/moss/pc/``。将来迁 CDN 只改字符串。

PC 与 Android 的差异：Android 只需要一堆扁平的 ONNX + .data 权重文件；PC 的
``MossEngine`` 会拉起一个 ``python moss_bridge.py --project <MOSS工程目录>`` 子进程，
需要的是**完整可运行的仓库**（含 ``*.py`` + ``models/`` 权重）。所以 PC 侧的
archive 应打包为「GitHub 源码头 + 权重」的 zip，扁平/嵌套目录两种布局都支持：
解压后如果发现 staging 只有一个一级子目录（GitHub ``MOSS-TTS-Nano-main/`` 常见），
自动 promote 一层，让 ``browser_poc_manifest.json`` 之类落在模块根上。

跨平台纯标准库：``urllib.request`` + ``hashlib`` + ``zipfile`` + ``threading``，
无新增依赖。所有下载/校验/解压都在调用方起的后台线程里跑，UI 用信号桥接回主线程。
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import threading
import time
import urllib.error
import urllib.request
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, Iterable, List, Optional


# 与 Android MossDownloader 对齐的常量
BUFFER = 1 << 16           # 64 KiB 下载/解压缓冲
SHA_BUF = 1 << 20          # 1 MiB sha256 分块
CONNECT_TIMEOUT = 30.0
READ_TIMEOUT = 60.0
PROGRESS_INTERVAL = 0.5    # 秒；每 500ms 上报一次进度（同 Android）
USER_AGENT = "AudioBookPlayerPC/moss-module"

# 与 Android MossModuleManager.REQUIRED_FILES 一致：这是**引擎启动前**必须就位的
# 核心文件。刻意写死而不是完全依赖 manifest —— manifest 自己也可能损坏/缺失，
# 必须能在「没读到 manifest」时判出「模块没装」。manifest 里的 size 若存在，
# 会覆盖这里的兜底尺寸校验。
REQUIRED_FILES: List[str] = [
    "browser_poc_manifest.json",
    "tts_browser_onnx_meta.json",
    "codec_browser_onnx_meta.json",
    "tokenizer.model",
    "moss_tts_prefill.onnx",
    "moss_tts_decode_step.onnx",
    "moss_tts_local_fixed_sampled_frame.onnx",
    "moss_tts_global_shared.data",
    "moss_tts_local_shared.data",
    "moss_audio_tokenizer_decode_full.onnx",
    "moss_audio_tokenizer_decode_shared.data",
]

# encode 是**可选能力**（用户想拿参考音频克隆音色时才需要），不参与 is_installed 判定，
# 与 Android 侧「不要把 encode 列为必需，否则 1.0.0 老用户升级后被判没装全」同理。
OPTIONAL_ENCODE_FILES: List[str] = [
    "moss_audio_tokenizer_encode.onnx",
    "moss_audio_tokenizer_encode.data",
]


# ============================================================================
# 异常
# ============================================================================

class MossModuleError(RuntimeError):
    """模块下载/校验/安装的通用错误。上层 UI 直接展示 str(e)。"""


class MossModuleCancelled(MossModuleError):
    """用户主动取消：保留 .part 断点、不视为失败。"""


# ============================================================================
# Manifest
# ============================================================================

@dataclass
class ManifestFile:
    name: str
    size: int
    sha256: str


@dataclass
class MossManifest:
    version: str
    archive_file: str
    archive_size: int
    archive_sha256: str
    download_bytes: int
    installed_bytes: int
    files: List[ManifestFile] = field(default_factory=list)

    @property
    def file_sha256(self) -> Dict[str, str]:
        return {f.name: f.sha256 for f in self.files if f.sha256}

    @classmethod
    def from_dict(cls, obj: dict) -> "MossManifest":
        # 与 Android MossManifest.fromJson 完全一致的字段取舍
        arch = obj.get("archive") or {}
        if not arch:
            raise MossModuleError("清单缺少 archive 段")
        totals = obj.get("totals") or {}
        files: List[ManifestFile] = []
        for f in (obj.get("files") or []):
            sha = (f.get("sha256") or "").strip()
            if not sha:
                continue   # 没有 sha256 的条目不纳入校验集（同 Android 的取舍）
            files.append(ManifestFile(
                name=str(f.get("name", "")).replace("\\", "/"),
                size=int(f.get("size", 0) or 0),
                sha256=sha.lower(),
            ))
        try:
            return cls(
                version=str(obj["version"]),
                archive_file=str(arch["file"]),
                archive_size=int(arch["size"]),
                archive_sha256=str(arch["sha256"]).lower(),
                download_bytes=int(totals.get("downloadBytes") or arch["size"]),
                installed_bytes=int(totals.get("installedBytes") or 0),
                files=files,
            )
        except KeyError as e:
            raise MossModuleError(f"清单字段不全：{e}") from e


# ============================================================================
# ModuleManager
# ============================================================================

class MossModuleManager:
    """模块本地状态：安装位置、轻校验、逐文件 sha256 校验、卸载。

    目录布局：

      <config.moss_module_dir_path>           # 也称 root
        ├── <promote 后的 MOSS 项目文件>         # 或直接在 root 下，或唯一一级子目录里
        ├── browser_poc_manifest.json
        ├── moss_tts_*.onnx / *.data
        └── _staging/                          # 下载解压的暂存（安装完成后清除）
      <root 的父目录>/moss_download/           # .part / .zip 落这里，卸载一并清
    """

    STAGING_NAME = "_staging"
    DOWNLOAD_DIR_NAME = "moss_download"

    def __init__(self, config):
        self.config = config

    # ---- 路径 ----

    def root_dir(self) -> Path:
        return self.config.moss_module_dir_path   # 保证存在

    def download_dir(self) -> Path:
        p = self.root_dir().parent / self.DOWNLOAD_DIR_NAME
        p.mkdir(parents=True, exist_ok=True)
        return p

    def module_dir(self) -> Path:
        """真正含 REQUIRED_FILES 的目录：root 自己，或 root 下唯一一级子目录。"""
        root = self.root_dir()
        if (root / "browser_poc_manifest.json").is_file():
            return root
        try:
            subs = [p for p in root.iterdir() if p.is_dir() and p.name != self.STAGING_NAME]
        except OSError:
            return root
        for sub in subs:
            if (sub / "browser_poc_manifest.json").is_file():
                return sub
        return root

    # ---- 校验 ----

    @staticmethod
    def sha256(path: Path) -> str:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            while True:
                b = f.read(SHA_BUF)
                if not b:
                    break
                h.update(b)
        return h.hexdigest()

    def is_installed(self, manifest_files: Iterable[ManifestFile] = ()) -> bool:
        """轻校验：REQUIRED_FILES 齐全 + 大小与 manifest（若有）一致。

        有 manifest 时以 manifest 里的 size 为准，硬编码 EXPECTED_SIZES 只兜底；
        与 Android 侧「服务端能独立升级、不逼客户端发版」的设计前提保持一致。
        """
        md = self.module_dir()
        if not md.is_dir():
            return False
        sizes = {f.name: f.size for f in manifest_files if f.size > 0}
        for name in REQUIRED_FILES:
            f = md / name
            if not f.is_file():
                return False
            expected = sizes.get(name) or 0
            if expected > 0 and f.stat().st_size != expected:
                return False
        return True

    def missing_files(self) -> List[str]:
        md = self.module_dir()
        return [n for n in REQUIRED_FILES if not (md / n).is_file()]

    def has_voice_clone_model(self) -> bool:
        md = self.module_dir()
        return all((md / n).is_file() for n in OPTIONAL_ENCODE_FILES)

    def verify_all(self, expected: Dict[str, str], base_dir: Optional[Path] = None) -> List[str]:
        """全量 sha256 校验（慢，数百 MB 需几十秒）。返回不匹配的文件名列表。"""
        target = base_dir or self.module_dir()
        bad: List[str] = []
        for name, want in expected.items():
            f = target / name
            if not f.is_file():
                bad.append(name)
                continue
            try:
                actual = self.sha256(f)
            except OSError:
                bad.append(name)
                continue
            if actual.lower() != want.lower():
                bad.append(name)
        return bad

    def installed_bytes(self) -> int:
        md = self.module_dir()
        total = 0
        for p in md.rglob("*"):
            if p.is_file():
                try:
                    total += p.stat().st_size
                except OSError:
                    pass
        return total

    def uninstall(self) -> None:
        """清空模块目录 + 下载缓存（供用户主动卸载/重装用）。"""
        shutil.rmtree(self.root_dir(), ignore_errors=True)
        shutil.rmtree(self.download_dir(), ignore_errors=True)

    def builtin_voices(self) -> List[str]:
        """读模块自带的 ``browser_poc_manifest.json`` 里的 ``builtin_voices``。

        与 Android 侧一致：过滤掉 ``prompt_audio_codes`` 为空的条目，那些会被
        运行时丢弃；模块未装/清单坏了直接返回空列表，让 UI 兜底。
        """
        f = self.module_dir() / "browser_poc_manifest.json"
        if not f.is_file():
            return []
        try:
            obj = json.loads(f.read_text("utf-8"))
            arr = obj.get("builtin_voices") or []
            out: List[str] = []
            for entry in arr:
                name = (entry.get("voice") or "").strip()
                codes = entry.get("prompt_audio_codes")
                if name and isinstance(codes, list) and len(codes) > 0:
                    out.append(name)
            return out
        except Exception:
            return []


# ============================================================================
# Downloader
# ============================================================================

class MossDownloader:
    """HTTP Range 续传下载 → 整包 sha256 校验 → 解压 → 逐文件校验 → 提交。

    与 Android 一样是**进程级单例语义**：同一时刻只允许一轮下载，第二个调用者
    直接被 tryLock 拒；这样避免两个线程用同一个 .part 各自 seek 到不同 offset
    把内容搅乱、最后哈希失败反掉所有续传进度。
    """

    def __init__(self, config, manager: Optional[MossModuleManager] = None):
        self.config = config
        self.mgr = manager or MossModuleManager(config)
        self._lock = threading.Lock()
        self._cancel = threading.Event()
        self._pause = threading.Event()
        # 进度状态（供 UI 快照查询；不追求线程安全，粗粒度即可）
        self.stage: str = ""
        self.downloaded: int = 0
        self.total: int = 0
        self.bytes_per_second: int = 0
        self.busy: bool = False

    # ---- URL ----

    def base_url(self) -> str:
        u = (self.config.moss_module_base_url or "").strip()
        if not u:
            root = (self.config.base_url or "").rstrip("/")
            u = f"{root}/moss/pc/"
        if not u.endswith("/"):
            u += "/"
        return u

    def manifest_url(self) -> str:
        return self.base_url() + "manifest.json"

    def archive_url(self, manifest: MossManifest) -> str:
        return self.base_url() + manifest.archive_file.lstrip("/")

    # ---- 取消 / 暂停 ----

    def cancel(self) -> None:
        """取消：让正在阻塞的 read() 尽快回，保留 .part。"""
        self._cancel.set()
        self._pause.clear()

    def pause(self) -> None:
        """暂停：只置标志；下一次 read() 前的检查会 block 到 resume/cancel。"""
        self._pause.set()

    def resume(self) -> None:
        self._pause.clear()

    def is_paused(self) -> bool:
        return self._pause.is_set() and not self._cancel.is_set()

    # ---- Manifest ----

    def fetch_manifest(self) -> MossManifest:
        req = urllib.request.Request(
            self.manifest_url(),
            headers={"Accept": "application/json",
                     "Cache-Control": "no-cache",
                     "User-Agent": USER_AGENT},
            method="GET",
        )
        try:
            with urllib.request.urlopen(req, timeout=CONNECT_TIMEOUT) as resp:
                ct = (resp.headers.get("Content-Type") or "").lower()
                body = resp.read().decode("utf-8", errors="replace")
        except urllib.error.URLError as e:
            raise MossModuleError(f"清单请求失败：{e}") from e
        # nginx 对未知 location 常 fallback 到前端 index.html：200 + text/html。
        # 不加这道判断的话，App 会把 HTML 当 JSON 解析，报出的错与真实原因（后端配置）
        # 毫不相干，运维排查两眼一抹黑。
        if "json" not in ct:
            raise MossModuleError(
                f"清单返回的不是 JSON（Content-Type={ct or '未知'}，{len(body)} 字节）。"
                "服务端可能没配 PC 模块的 location —— 未知路径会被 fallback 到前端页面。"
            )
        try:
            obj = json.loads(body)
        except Exception as e:
            raise MossModuleError(f"清单不是合法 JSON（{len(body)} 字节）：{e}") from e
        return MossManifest.from_dict(obj)

    # ---- 断点 ----

    def _archive_file(self, manifest: MossManifest) -> Path:
        # 只取 basename：manifest 若被入侵给出 ../xxx，也会归一到下载目录内，
        # 不会被续传清理/复用逻辑误删目录外的东西。
        return self.mgr.download_dir() / Path(manifest.archive_file).name

    def _part_file(self, manifest: MossManifest) -> Path:
        return self.mgr.download_dir() / (Path(manifest.archive_file).name + ".part")

    def partial_bytes(self, manifest: MossManifest) -> int:
        p = self._part_file(manifest)
        try:
            return p.stat().st_size if p.is_file() else 0
        except OSError:
            return 0

    def existing_archive(self, manifest: MossManifest) -> Optional[Path]:
        a = self._archive_file(manifest)
        try:
            if a.is_file() and a.stat().st_size == manifest.archive_size:
                return a
        except OSError:
            pass
        return None

    def clear_downloads(self) -> None:
        shutil.rmtree(self.mgr.download_dir(), ignore_errors=True)

    # ---- 空间 ----

    def required_peak_bytes(self, manifest: MossManifest) -> int:
        # 峰值 = 压缩包 + 解压结果 + 32MB 余量（对齐 Android）
        return int(manifest.download_bytes + manifest.installed_bytes + (32 << 20))

    def available_bytes(self) -> int:
        try:
            usage = shutil.disk_usage(str(self.mgr.root_dir()))
            return int(usage.free)
        except OSError:
            return 1 << 62  # 读不到就别拦着用户，让下载过程自己失败

    def has_enough_space(self, manifest: MossManifest) -> bool:
        # 关键：可用空间要**加上已下载的 .part**（续传时它已经占掉一份空间），
        # 与 required_peak_bytes 判定同口径，避免 UI 放行、执行时又拦下。
        return self.available_bytes() + self.partial_bytes(manifest) >= self.required_peak_bytes(manifest)

    # ---- 下载 ----

    def download(self, manifest: MossManifest,
                 on_progress: Optional[Callable[[int, int, int], None]] = None,
                 on_stage: Optional[Callable[[str], None]] = None) -> Path:
        """Range 续传下载。返回**校验通过的** zip 路径。必须在后台线程调用。"""
        if not self._lock.acquire(blocking=False):
            raise MossModuleError("已有下载任务在进行，请稍候")
        self._cancel.clear()
        self._pause.clear()
        self.busy = True
        try:
            return self._download_locked(manifest, on_progress, on_stage)
        finally:
            self.busy = False
            self._lock.release()

    def _download_locked(self, manifest, on_progress, on_stage) -> Path:
        target = self._archive_file(manifest)
        if target.is_file() and target.stat().st_size == manifest.archive_size:
            self._emit_stage(on_stage, "已存在完整安装包，跳过下载")
            return target

        part = self._part_file(manifest)
        already = 0
        if part.is_file():
            try:
                already = part.stat().st_size
            except OSError:
                already = 0
            if already >= manifest.archive_size:
                # 比预期还大：本地文件坏了，重来
                try:
                    part.unlink()
                except OSError:
                    pass
                already = 0

        headers = {"User-Agent": USER_AGENT}
        if already > 0:
            headers["Range"] = f"bytes={already}-"
            self._emit_stage(on_stage, f"继续下载（已完成 {already // (1024*1024)} MB）")
        else:
            self._emit_stage(on_stage, "开始下载")
        self.downloaded = already
        self.total = manifest.archive_size

        req = urllib.request.Request(self.archive_url(manifest), headers=headers, method="GET")
        try:
            resp = urllib.request.urlopen(req, timeout=READ_TIMEOUT)
        except urllib.error.URLError as e:
            raise MossModuleError(f"下载失败：{e}") from e

        try:
            status = getattr(resp, "status", None) or resp.getcode()
            if status not in (200, 206):
                raise MossModuleError(f"下载失败：HTTP {status}")
            resuming = (status == 206)
            # 服务端不支持 Range → 200 全量响应；此时必须从头写，否则「旧前半 + 新后半」
            if resuming:
                mode = "ab"
                start_at = already
            else:
                mode = "wb"
                start_at = 0
                try:
                    if part.exists():
                        part.unlink()
                except OSError:
                    pass

            clen_hdr = resp.headers.get("Content-Length")
            try:
                clen = int(clen_hdr) if clen_hdr else -1
            except ValueError:
                clen = -1
            total = (start_at + clen) if clen > 0 else manifest.archive_size
            self.total = total

            downloaded = start_at
            last_report = time.time()
            last_bytes = start_at

            with open(part, mode) as f:
                while True:
                    if self._cancel.is_set():
                        raise MossModuleCancelled("下载已取消（保留断点，可继续）")
                    # 暂停：block 到 resume/cancel
                    while self._pause.is_set() and not self._cancel.is_set():
                        time.sleep(0.15)
                    if self._cancel.is_set():
                        raise MossModuleCancelled("下载已取消（保留断点，可继续）")
                    try:
                        chunk = resp.read(BUFFER)
                    except (urllib.error.URLError, OSError) as e:
                        if self._cancel.is_set():
                            raise MossModuleCancelled("下载已取消（保留断点）") from e
                        raise MossModuleError(f"读取中断（已保留断点）：{e}") from e
                    if not chunk:
                        break
                    f.write(chunk)
                    f.flush()
                    downloaded += len(chunk)
                    now = time.time()
                    if now - last_report >= PROGRESS_INTERVAL:
                        dt = max(now - last_report, 1e-3)
                        speed = int((downloaded - last_bytes) / dt)
                        self.downloaded = downloaded
                        self.bytes_per_second = speed
                        if on_progress:
                            on_progress(downloaded, total, speed)
                        last_report = now
                        last_bytes = downloaded

            if on_progress:
                on_progress(downloaded, total, 0)
            self.downloaded = downloaded

            if downloaded != manifest.archive_size:
                raise MossModuleError(
                    f"下载未完成：{downloaded}/{manifest.archive_size}（已保留断点，可重试续传）")
        finally:
            try:
                resp.close()
            except Exception:
                pass

        # ---- 整包 sha256 校验，过了才 rename 提交 ----
        self._emit_stage(on_stage, "校验安装包完整性")
        self.stage = "校验安装包完整性"
        try:
            actual = MossModuleManager.sha256(part)
        except OSError as e:
            raise MossModuleError(f"无法读取安装包做校验：{e}") from e
        if actual.lower() != manifest.archive_sha256.lower():
            try:
                part.unlink()
            except OSError:
                pass
            raise MossModuleError(
                f"安装包校验失败（期望 {manifest.archive_sha256[:16]}… "
                f"实得 {actual[:16]}…），已删除，请重新下载")
        try:
            if target.exists():
                target.unlink()
        except OSError:
            pass
        try:
            os.replace(str(part), str(target))
        except OSError as e:
            raise MossModuleError(f"无法提交安装包：{e}") from e
        return target

    # ---- 解压 + 安装 ----

    def install(self, archive: Path, manifest: MossManifest,
                on_stage: Optional[Callable[[str], None]] = None) -> None:
        """staging 解压 → 逐文件 sha256 校验 → 整体替换到 root。

        不直接往 root 写：中途失败会留下「看着装了、其实残缺」的目录，
        而轻校验只查文件大小，查不出「大小对、内容错」的情况。
        """
        root = self.mgr.root_dir()
        staging = root / self.mgr.STAGING_NAME
        if staging.exists():
            shutil.rmtree(staging, ignore_errors=True)
        staging.mkdir(parents=True, exist_ok=True)
        try:
            self._emit_stage(on_stage, "解压安装包")
            self._unzip(archive, staging)

            # GitHub 源码 zip 常见单个顶层子目录 MOSS-TTS-Nano-main/；promote 一层，
            # 让 REQUIRED_FILES 直接落在模块根上，与 Android 扁平包同构。
            self._promote_single_top_dir(staging)

            self._emit_stage(on_stage, "校验解压结果")
            bad = self.mgr.verify_all(manifest.file_sha256, base_dir=staging)
            if bad:
                raise MossModuleError(
                    "解压结果校验失败：" + "、".join(bad[:6]) + ("…" if len(bad) > 6 else ""))

            self._emit_stage(on_stage, "安装")
            # 清掉 root 里的旧内容（保留 staging）
            for p in list(root.iterdir()):
                if p.name == self.mgr.STAGING_NAME:
                    continue
                if p.is_dir():
                    shutil.rmtree(p, ignore_errors=True)
                else:
                    try:
                        p.unlink()
                    except OSError:
                        pass
            # 提交：staging 内容整体挪到 root
            for p in list(staging.iterdir()):
                dst = root / p.name
                try:
                    os.replace(str(p), str(dst))
                except OSError:
                    shutil.move(str(p), str(dst))
            shutil.rmtree(staging, ignore_errors=True)

            # 自检：与 Android 一致 —— 装完必须轻校验过，才算真装好
            if not self.mgr.is_installed(manifest.files):
                raise MossModuleError(
                    f"安装后自检未通过，缺少：{self.mgr.missing_files()}")

            # 安装包用完即删（几百 MB 没必要留着；需要时还能重下）
            try:
                archive.unlink()
            except OSError:
                pass
        except Exception:
            shutil.rmtree(staging, ignore_errors=True)
            raise

    @staticmethod
    def _unzip(archive: Path, target: Path) -> None:
        with zipfile.ZipFile(str(archive), "r") as zf:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                rel = MossDownloader._safe_relpath(info.filename)
                if not rel:
                    continue
                out = target / rel
                out.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as src, open(out, "wb") as dst:
                    shutil.copyfileobj(src, dst, BUFFER)

    @staticmethod
    def _safe_relpath(name: str) -> str:
        """拒绝 ``..`` / 绝对路径 / Windows 盘符 —— 防 zip slip。"""
        parts: List[str] = []
        for seg in Path(name.replace("\\", "/")).parts:
            if seg in ("..", ".", "/", "\\"):
                return ""
            if len(seg) >= 2 and seg[1] == ":":
                return ""
            parts.append(seg)
        if not parts:
            return ""
        return "/".join(parts)

    @staticmethod
    def _promote_single_top_dir(staging: Path) -> None:
        entries = [p for p in staging.iterdir()]
        if len(entries) == 1 and entries[0].is_dir():
            inner = entries[0]
            for p in list(inner.iterdir()):
                try:
                    os.replace(str(p), str(staging / p.name))
                except OSError:
                    shutil.move(str(p), str(staging / p.name))
            try:
                inner.rmdir()
            except OSError:
                pass

    @staticmethod
    def _emit_stage(cb: Optional[Callable[[str], None]], text: str) -> None:
        if cb:
            try:
                cb(text)
            except Exception:
                pass


# ============================================================================
# 高层：确保模块可用（同步；必须在后台线程调用）
# ============================================================================

def ensure_module_installed(config,
                            on_progress: Optional[Callable[[int, int, int], None]] = None,
                            on_stage: Optional[Callable[[str], None]] = None,
                            allow_download: bool = True,
                            downloader: Optional["MossDownloader"] = None,
                            ) -> Dict[str, object]:
    """探测 → 必要时下载 → 安装 → 返回可直接填给 ``AppConfig.moss_*`` 的路径。

    返回 dict::

        {
          "installed": True,
          "project_path": str,   # 填给 config.moss_project_path
          "model_dir": str,      # 若模块自带 models/，填给 config.moss_model_dir；否则空
          "version": str,
          "download_bytes": int,
          "installed_bytes": int,
          "manifest": MossManifest,
        }

    未装且 ``allow_download=False`` 时抛 ``MossModuleError``（供 UI 先弹确认框）。

    ``downloader`` 可传入调用方自建的 ``MossDownloader``，让 UI 能持有同一个实例做
    暂停/取消（pause/resume/cancel 都在下载器的事件标志上）；不传则内部新建。
    """
    mgr = MossModuleManager(config)
    dl = downloader or MossDownloader(config, mgr)
    manifest = dl.fetch_manifest()

    if not mgr.is_installed(manifest.files):
        if not allow_download:
            raise MossModuleError("MOSS-TTS-Nano 模块尚未安装")
        if not dl.has_enough_space(manifest):
            avail = dl.available_bytes() / (1024 * 1024)
            need = dl.required_peak_bytes(manifest) / (1024 * 1024)
            raise MossModuleError(
                f"磁盘空间不足：需要约 {need:.0f} MB，实际可用 {avail:.0f} MB")
        archive = dl.download(manifest, on_progress=on_progress, on_stage=on_stage)
        dl.install(archive, manifest, on_stage=on_stage)

    md = mgr.module_dir()
    models = (md / "models") if (md / "models").is_dir() else None
    return {
        "installed": True,
        "project_path": str(md),
        "model_dir": str(models) if models else "",
        "version": manifest.version,
        "download_bytes": manifest.download_bytes,
        "installed_bytes": manifest.installed_bytes,
        "manifest": manifest,
    }
