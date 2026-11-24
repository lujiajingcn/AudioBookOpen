"""配置数据与持久化。

## 为什么配置独立成一层

工具的界面要填的东西有五组（引擎、本地库、服务器库、路径、参数），
每组都对应一次「填错就白干」的外部依赖。为了让「填过的」能留下来、
让「换台机器」只要拷一个 json 文件，这里把配置收敛成一个 dataclass，
落盘到用户目录（不放项目目录，避免污染代码仓库、也不怕被 git 提交）。

## 落盘位置

    %APPDATA% / AudioBookLocalTtsTool / config.json

可用环境变量 `AUDIOBOOK_LOCAL_TTS_CONFIG` 覆盖（便于做多套环境：本地/服务器）。
"""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass, field, fields
from pathlib import Path
from typing import Any


def _default_config_path() -> Path:
    override = os.environ.get("AUDIOBOOK_LOCAL_TTS_CONFIG")
    if override:
        return Path(override).expanduser()
    base = os.environ.get("APPDATA") or str(Path.home())
    return Path(base) / "AudioBookLocalTtsTool" / "config.json"


@dataclass
class MySqlTarget:
    """一个 MySQL 连接目标（本地库与服务器库共用同一套字段）。

    默认值对齐 AudioBookServer 的 application-dev.yml：
    `jdbc:mysql://localhost:3306/audiobook_db`，库名 audiobook_db。
    """

    host: str = "127.0.0.1"
    port: int = 3306
    database: str = "audiobook_db"
    user: str = "root"
    password: str = ""

    def describe(self) -> str:
        """给日志看的脱敏描述：绝不打印密码。"""
        return f"{self.user}@{self.host}:{self.port}/{self.database}"


@dataclass
class SshTarget:
    """上传通道：SSH 到服务器（文件走 scp/tar，库走隧道）。

    ## 为什么上传不再直连服务器 MySQL

    生产环境只对外开 22/80：MySQL 绑在 127.0.0.1，公网 3306 是连不通的
    （实测 socket 超时）。硬填公网 IP 的那套配置从来没有真正跑通过。

    真正的上传口径是「文件 + 元数据」：
      1. 原书 → `<remote_uploads>/file_<uuid>.<ext>`，`tasks.book_file_path` 就填这个名字；
      2. 音频 → `<remote_uploads>/{taskId}/tts_{taskId}_{段号}.mp3`；
      3. 库里只写 tasks / audio_segments 元数据，`audio_data` 留空。
    服务端 `TtsDownloadController` 是「先找文件，缺了才回退 BLOB」，所以文件到位就能播。
    """

    host: str = "api.example.com"
    port: int = 22
    user: str = "ubuntu"
    # 私钥路径（留空 = 用 ssh-agent / 默认密钥）；支持 ~ 展开
    key_path: str = "~/.ssh/audiobook_open"
    # 服务器上 uploads 根目录：留空 = 从服务器 env 的 UPLOAD_DIR 读，再不行就用默认值
    remote_uploads: str = ""
    # 服务器上书城目录（发布到书城时原书的落点）：留空 = 读服务器 env 的 STORE_DIR
    remote_store: str = ""
    # 服务器 env 文件：里面有 DB_URL / DB_USERNAME / DB_PASSWORD / UPLOAD_DIR
    server_env: str = "/etc/audiobook/audiobook-server.env"
    # 本地隧道端口：0 = 每次自动挑空闲端口（避免和上次残留的隧道撞端口）
    local_tunnel_port: int = 0

    def describe(self) -> str:
        return f"{self.user}@{self.host}:{self.port}"


@dataclass
class MossSettings:
    """MOSS-TTS-Nano 引擎的接线信息。

    ## 为什么是「工程路径 + 解释器」而不是「一个 exe」

    MOSS-TTS-Nano 官方工程（github.com/OpenMOSS/MOSS-TTS-Nano）的推理入口
    是纯 Python：`onnx_tts_runtime.OnnxTtsRuntime`，依赖 onnxruntime /
    sentencepiece 等一堆包，装在**它自己的环境**里（官方推荐 conda）。
    工具本体的依赖（PySide6、PyMySQL）与它没有交集，硬塞进一个环境只会互相干扰。

    所以这里让用户填两样东西：
      - project_path：MOSS-TTS-Nano 工程目录（里面有 onnx_tts_runtime.py）
      - python_exe：用哪个解释器跑它（留空 = 用运行工具的同一个解释器）
    工具随后用这个解释器拉起 tools/moss_bridge.py，把工程当库加载。
    """

    project_path: str = r"E:\study_audiobook\WorkSpace_AudioBook\MOSS-TTS-Nano"
    python_exe: str = r"C:\Users\audiobook\miniconda3\envs\moss-tts-nano\python.exe"
    # 模型目录：留空 = 工程内默认的 models/（MOSS-TTS-Nano-100M-ONNX + Tokenizer）
    model_dir: str = ""
    cpu_threads: int = 16
    execution_provider: str = "cpu"
    # 采样模式：fixed = 用 ONNX 导出时烘进图里的采样常量（官方 infer_onnx.py 默认）
    sample_mode: str = "fixed"
    # 是否启用 WeTextProcessing 文本正则化（依赖 pynini，很多环境装不上，默认关）
    enable_wetext: bool = False


@dataclass
class AppConfig:
    """工具的全部可配置项。"""

    local_db: MySqlTarget = field(default_factory=MySqlTarget)
    # 服务器 MySQL：**一般不用填**。上传时从服务器的 env 文件读真实连接参数，
    # 再经 SSH 隧道连过去。这里只在「env 读不到 / 想连另一套库」时手动覆盖。
    server_db: MySqlTarget = field(default_factory=lambda: MySqlTarget(host="", password=""))
    # 上传通道（SSH）
    ssh: SshTarget = field(default_factory=SshTarget)
    # ---------- 发布目标 ----------
    #
    # 这个工具是**配合有声书后台管理**用的：合成好的书应当出现在「书城」，
    # 而不是挂到某个普通用户的书架里。两者在服务端是两套数据：
    #   * 书架：tasks.user_id = 某个用户 → 只有该用户看得到；
    #   * 书城：store_books 一行 + 原书落在 store 目录，音频靠 store_books.task_id
    #     指到一个 tasks 行（StoreReadingController 只认这一条）。
    # 所以「发布到书城」= 建一条**归管理员账号**的任务（普通用户书架里看不到）
    #   + 建 store_books 行指向它 + 原书放 store 目录。
    publish_target: str = "store"        # store = 发布到书城；shelf = 传到个人书架（旧行为）
    # 书城模式下 tasks 归属的服务器账号：必须是管理员，否则「书城里的书」会跑到某个用户任务列表里
    store_owner_username: str = "audiobook_admin"
    # 书城分类（服务端会按它建目录）；发布后仍可在管理端改
    store_category: str = "其他"
    # 管理端 HTTP 地址：发布收尾要调 `POST /api/admin/books/{id}/content-snapshot`
    # 补一次正文快照（否则客户端第一次打开阅读时，服务端才现解析整本，实测 9.2s）。
    # 留空 = `http://{SSH 主机}`（生产是 nginx :80 反代到 8080）。
    # 调试直连后端时可填 `http://<ip>:8080`。
    server_api_base: str = ""

    # 服务器侧任务归属的用户名（tasks.user_id 是外键，指向 users.id）
    server_username: str = "audiobook"
    # 本地库的任务归属用户名：本地库若已有 users 表就用它，没有就建一个
    local_username: str = "local_tts"

    moss: MossSettings = field(default_factory=MossSettings)

    # ffmpeg 可执行文件：MOSS 产出 WAV，服务端存 MP3，需要转码；
    # 留空则自动在 PATH 里找；找不到就保留 WAV（服务端照样能播，只是文件大几倍）
    ffmpeg_path: str = ""
    # 中间产物（每段的 wav）是否保留
    keep_wav: bool = True
    # 中间产物目录（留空 = 放在系统临时目录下的 audiobook_local_tts 下）
    work_dir: str = ""
    # 服务端的建表脚本（= 服务器表结构的权威定义）：
    # 「对齐结构」按它把本地库改成与服务器完全一致
    server_ddl_path: str = r"E:\study_audiobook\WorkSpace_AudioBook\AudioBook\AudioBook\AudioBookServer\init_database.sql"

    # ---------- 持久化 ----------

    @classmethod
    def load(cls, path: Path | None = None) -> "AppConfig":
        """读配置；文件不存在或损坏时返回默认配置（不让工具因为配置坏掉就打不开）。"""
        cfg_path = path or _default_config_path()
        if not cfg_path.is_file():
            return cls()
        try:
            raw = json.loads(cfg_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return cls()
        return cls.from_dict(raw)

    def save(self, path: Path | None = None) -> Path:
        """写配置；返回实际写入的路径。"""
        cfg_path = path or _default_config_path()
        cfg_path.parent.mkdir(parents=True, exist_ok=True)
        cfg_path.write_text(
            json.dumps(asdict(self), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return cfg_path

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "AppConfig":
        """从字典还原配置。

        逐字段按名字匹配并做类型收敛：配置文件是人手编辑的，
        少一个字段、多一个字段、类型写错都不该让程序起不来。
        """
        cfg = cls()
        for f in fields(cls):
            if f.name not in raw:
                continue
            value = raw[f.name]
            current = getattr(cfg, f.name)
            nested = (MySqlTarget, MossSettings, SshTarget)
            if isinstance(current, nested) and isinstance(value, dict):
                # 按名字逐个字段塞回去：少字段/多字段/类型写错都不该让工具起不来
                for sub in fields(current):
                    if sub.name in value:
                        setattr(current, sub.name, value[sub.name])
            else:
                setattr(cfg, f.name, value)
        return cfg
