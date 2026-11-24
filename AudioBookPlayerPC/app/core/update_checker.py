"""版本更新检查。

对标 Android 的版本检查，但 PC 端不做「下载 APK 并调起安装」那套 ——
桌面程序更新方式各异（安装包 / 压缩包 / 覆盖 exe），这里只负责：
  - 查服务端最新版本（``GET /api/app/version/latest``）；
  - 判断有无更新 / 是否强更；
  - 需要时把更新包下载到本地（回调进度），交给调用方去安装/提示用户。
"""
from __future__ import annotations

import os
import threading
import time
from pathlib import Path
from typing import Callable, Optional

import requests

from app.core.api_client import ApiClient, ApiException
from app.models.models import AppUpdateInfo

# 与 Android BuildConfig.VERSION_CODE 对齐的默认基线
DEFAULT_VERSION_CODE = 41


class UpdateChecker:
    """版本检查器。"""

    def __init__(self, api: ApiClient):
        self.api = api

    def check(self, current_version: int = DEFAULT_VERSION_CODE) -> AppUpdateInfo:
        """检查更新，返回 AppUpdateInfo（含 has_update / force_update / 下载地址）。

        网络/接口异常时返回一个 has_update=False 的「无更新」结果，而不是抛出 ——
        更新检查是启动时的旁路动作，不该因为查不到版本就阻断主流程。
        """
        try:
            info = self.api.check_update(current_version)
            # 服务端没给 hasUpdate 时，本地按 versionCode 兜底判断
            if not info.has_update and info.version_code > current_version:
                info.has_update = True
            return info
        except ApiException:
            return AppUpdateInfo(version_code=current_version, has_update=False)
        except Exception:
            return AppUpdateInfo(version_code=current_version, has_update=False)

    def check_async(self, on_result: Callable[[AppUpdateInfo], None],
                    current_version: int = DEFAULT_VERSION_CODE) -> threading.Thread:
        """后台线程检查，结果回调（在子线程，UI 侧自行切回主线程）。"""
        def _run() -> None:
            info = self.check(current_version)
            try:
                on_result(info)
            except Exception:
                pass
        t = threading.Thread(target=_run, daemon=True, name="update-check")
        t.start()
        return t

    def download_package(
        self,
        info: AppUpdateInfo,
        save_dir: Optional[Path] = None,
        on_progress: Optional[Callable[[int, int], None]] = None,
    ) -> Path:
        """下载更新包到 save_dir（默认取 config 的更新子目录），返回落地路径。

        无 download_url 时抛错。文件名优先用 URL 末段，退化用版本号。
        """
        if not info.download_url:
            raise RuntimeError("该版本未提供下载地址")

        save_dir = Path(save_dir) if save_dir else self._default_save_dir()
        save_dir.mkdir(parents=True, exist_ok=True)

        name = os.path.basename(info.download_url.split("?")[0].rstrip("/")) or \
            f"audiobook_update_{info.version_code or info.version_name}"
        dest = save_dir / name

        total = int(info.file_size or 0)
        try:
            with requests.get(info.download_url, stream=True, timeout=(15, 600)) as r:
                r.raise_for_status()
                header_total = r.headers.get("Content-Length")
                if not total and header_total:
                    try:
                        total = int(header_total)
                    except ValueError:
                        total = 0
                done = 0
                last_emit = 0.0
                with open(dest, "wb") as f:
                    for chunk in r.iter_content(65536):
                        if not chunk:
                            continue
                        f.write(chunk)
                        done += len(chunk)
                        if on_progress:
                            cur = time.time()
                            if cur - last_emit >= 0.2 or (total and done >= total):
                                last_emit = cur
                                try:
                                    on_progress(done, total)
                                except Exception:
                                    pass
        except requests.RequestException as exc:
            dest.unlink(missing_ok=True)
            raise RuntimeError(f"更新包下载失败：{exc}") from exc

        # MD5 校验（服务端提供时）
        if info.md5:
            if self._md5(dest) != info.md5.lower():
                dest.unlink(missing_ok=True)
                raise RuntimeError("更新包校验失败（MD5 不匹配），已删除")
        return dest

    @staticmethod
    def _md5(path: Path) -> str:
        import hashlib
        h = hashlib.md5()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        return h.hexdigest()

    def _default_save_dir(self) -> Path:
        data_dir = getattr(self.api.config, "resolved_data_dir", None)
        if data_dir is not None:
            return Path(data_dir) / "updates"
        return Path.cwd() / "updates"
