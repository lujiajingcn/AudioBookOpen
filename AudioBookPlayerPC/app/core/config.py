"""全局配置管理：服务器地址、本地路径、用户偏好。"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Optional

# 配置持久化路径
_CONFIG_DIR = Path(os.environ.get(
    "AUDIOBOOK_PC_CONFIG",
    str(Path.home() / "AppData" / "Local" / "AudioBookPlayerPC")
    if os.name == "nt" else
    str(Path.home() / ".config" / "AudioBookPlayerPC")
))
_CONFIG_FILE = _CONFIG_DIR / "config.json"

# 默认服务器地址（与 Android BuildConfig.BASE_URL 一致）
DEFAULT_BASE_URL = "http://api.example.com/"


@dataclass
class AppConfig:
    base_url: str = DEFAULT_BASE_URL
    # 本机 TTS 引擎设置（复用 LocalTtsTool 逻辑）
    moss_project_path: str = ""
    moss_python_exe: str = ""
    moss_model_dir: str = ""
    moss_cpu_threads: int = 16
    moss_execution_provider: str = "cpu"
    moss_sample_mode: str = "fixed"
    moss_enable_wetext: bool = False
    # MOSS-TTS-Nano 模块按需下载：
    #   moss_module_base_url 为空时，MossDownloader 会用 base_url 派生 "<base>/moss/pc/"；
    #     将来迁 CDN 只改这一个字符串，不必发新版；
    #   moss_module_dir 为空时，MossModuleManager 用 {resolved_data_dir}/moss_module/。
    moss_module_base_url: str = ""
    moss_module_dir: str = ""
    # 用户偏好
    night_mode: bool = False
    font_size: int = 18
    # 跨端同步：与 Android PlaybackService.KEY_SPEED 同名同值域（0.5~3.0）。
    # ⚠ 之前这个字段全仓只有读取点、没有写入点（audio_bar.speed_changed 未接线），
    #   现由 reader_page._attach_audio_bar 接通；同步水位与影子快照在 sync_state 表。
    playback_speed: float = 1.0
    # 默认音色（纯文本标识）。两端此前都没有持久化：Android 只是 TtsFragment 内存变量，
    # PC 只是单次合成的任务参数。现补成本地偏好并纳入 /api/user-settings 同步。
    default_voice_id: str = ""
    # 记住的用户名（**持久化**，与 token 分开存）。
    # 服务端 JWT TTL 只有 24h ⇒ 每天都要重登一次；token 过期后若把用户名也清掉，
    # 用户就得重新手打用户名。这里单独记住"用户是谁"，登录框直接预填 ⇒ 只剩输密码。
    # ⛔ 这里**绝不能存密码**：密码落盘就等于把凭证交出去了。
    last_username: str = ""
    # 本地数据目录
    data_dir: str = ""
    # 运行时（不持久化）
    _token: Optional[str] = field(default=None, repr=False)
    _username: Optional[str] = field(default=None, repr=False)
    _is_admin: bool = field(default=False, repr=False)

    @property
    def token(self) -> Optional[str]:
        return self._token

    @token.setter
    def token(self, v: str):
        self._token = v

    @property
    def username(self) -> Optional[str]:
        return self._username

    @username.setter
    def username(self, v: str):
        self._username = v

    @property
    def is_authenticated(self) -> bool:
        return self._token is not None

    @property
    def resolved_data_dir(self) -> Path:
        d = Path(self.data_dir) if self.data_dir else _CONFIG_DIR / "data"
        d.mkdir(parents=True, exist_ok=True)
        return d

    @property
    def audio_dir(self) -> Path:
        d = self.resolved_data_dir / "audio"
        d.mkdir(parents=True, exist_ok=True)
        return d

    @property
    def books_dir(self) -> Path:
        d = self.resolved_data_dir / "books"
        d.mkdir(parents=True, exist_ok=True)
        return d

    @property
    def moss_module_dir_path(self) -> Path:
        raw = (self.moss_module_dir or "").strip()
        d = Path(raw).expanduser() if raw else (self.resolved_data_dir / "moss_module")
        d.mkdir(parents=True, exist_ok=True)
        return d

    def api(self, path: str) -> str:
        """拼接完整 API URL。"""
        base = self.base_url.rstrip("/")
        if not path.startswith("/"):
            path = "/" + path
        return base + "/api" + path

    @classmethod
    def load(cls) -> "AppConfig":
        _CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        if _CONFIG_FILE.exists():
            try:
                raw = json.loads(_CONFIG_FILE.read_text("utf-8"))
                return cls(**{k: v for k, v in raw.items() if k in cls.__dataclass_fields__})
            except Exception:
                pass
        return cls()

    def save(self):
        _CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        d = asdict(self)
        # 不保存运行时字段
        d.pop("_token", None)
        d.pop("_username", None)
        d.pop("_is_admin", None)
        _CONFIG_FILE.write_text(json.dumps(d, ensure_ascii=False, indent=2), "utf-8")
