"""上传通道：SSH 到服务器（文件走 scp/tar，数据库走本地隧道）。

## 为什么上传要改成 SSH

服务端的音频存储口径是「**文件优先、BLOB 兜底**」
（`TtsDownloadController.downloadSegment`：先找 `uploads/{taskId}/tts_{taskId}_{n}.mp3`，
文件缺失且非空才回读 `audio_data`）。而生产环境：

* MySQL 绑在 `127.0.0.1:3306`，公网只开 22/80 ⇒ 直连 3306 是**连接超时**；
* `audio_segments.audio_data` 是 **tinyblob（255 字节上限）**，且服务端恒写 NULL。

所以「把 mp3 塞进库」这条路根本走不通（旧实现第一步的容量检查就会抛错）。
正确做法是：

  1. 原书 → `<uploads>/file_<uuid>.<ext>`，`tasks.book_file_path` 填这个名字；
  2. 音频 → `<uploads>/{taskId}/tts_{taskId}_{段号}.mp3`；
  3. 元数据经 SSH 隧道写进 `127.0.0.1:3306`，`audio_data` 留空。

## 依赖

Windows 10/11 自带 OpenSSH 客户端（`ssh.exe` / `scp.exe`）。本模块只用「一次性命令 +
一条 `-L` 隧道」，不用 ControlMaster（Windows 版 OpenSSH 不支持），所以连接开销只有几次握手。
"""

from __future__ import annotations

import os
import shlex
import shutil
import socket
import subprocess
import tarfile
import time
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Iterator

from app.config import MySqlTarget, SshTarget

LogFn = Callable[[str], None]

# Windows 自带 OpenSSH 的安装位置（PATH 里没有时兜底）
_WIN_OPENSSH = r"C:\Windows\System32\OpenSSH"
# 默认 uploads 根目录（服务器 env 里没有 UPLOAD_DIR 时用）
DEFAULT_UPLOADS_DIR = "/data/audiobook/uploads"
# 默认书城目录（服务器 env 里没有 STORE_DIR 时用；与 application.yml 的 app.store.dir 对应）
DEFAULT_STORE_DIR = "/data/audiobook/store_books"


class SshError(RuntimeError):
    """SSH / 传输失败。"""


# ---------- 小工具 ----------


def _find_tool(name: str) -> str:
    found = shutil.which(name)
    if found:
        return found
    candidate = Path(_WIN_OPENSSH) / f"{name}.exe"
    if candidate.is_file():
        return str(candidate)
    raise SshError(
        f"找不到 {name} 命令：需要 OpenSSH 客户端。"
        "Windows 10/11 自带（设置 → 应用 → 可选功能 → OpenSSH 客户端），确认它在 PATH 里。"
    )


def pick_free_port() -> int:
    """挑一个当前空闲的本地端口（隧道用）。

    固定 13306 的问题：上次工具被强杀时隧道进程可能还没退出，端口被占 ⇒
    这次 `-L` 直接失败。让系统分配，永远不会撞。
    """
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _wait_port(port: int, timeout: float = 15.0) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        with socket.socket() as sock:
            sock.settimeout(0.5)
            try:
                sock.connect(("127.0.0.1", port))
                return True
            except OSError:
                time.sleep(0.15)
    return False


def parse_env_file(text: str) -> dict[str, str]:
    """解析 `KEY=value` / `KEY="value"` 形式的 env 文件。

    只做「够用」的解析：忽略空行与 `#` 注释，去掉 `export ` 前缀和成对引号。
    服务器上的 env 是 `DB_URL="jdbc:mysql://..."` 这种，值里可能带 `#`（密码），
    所以**不能**按 `#` 截断 —— 只有整行以 `#` 开头才算注释。
    """
    values: dict[str, str] = {}
    for raw in (text or "").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].strip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        if key:
            values[key] = value
    return values


def parse_jdbc_url(url: str) -> tuple[str, int, str]:
    """把 `jdbc:mysql://host:port/db?params` 拆成 (host, port, db)。"""
    raw = (url or "").strip()
    if "://" not in raw:
        raise SshError(f"服务器 env 里的 DB_URL 不是 JDBC 格式：{raw!r}")
    rest = raw.split("://", 1)[1]
    host_port, _, tail = rest.partition("/")
    database = tail.split("?", 1)[0].strip()
    host, _, port_text = host_port.partition(":")
    try:
        port = int(port_text) if port_text else 3306
    except ValueError:
        port = 3306
    return (host or "127.0.0.1"), port, (database or "audiobook_db")


# ---------- 服务器信息 ----------


@dataclass
class ServerInfo:
    """从服务器读到的上传所需参数。"""

    db: MySqlTarget
    uploads_dir: str
    # 书城目录（发布到书城时原书的落点）。老调用方不传时按默认值兜底，保持向后兼容。
    store_dir: str = DEFAULT_STORE_DIR
    source: str = "env"          # env / 手动覆盖
    # 服务器 env 里的 JWT_SECRET：发布收尾要本地签一个短时管理员 token，
    # 调 `/api/admin/books/{id}/content-snapshot` 补正文快照（见 api_client）。
    # 空 = env 里没有这个键（老部署），此时跳过快照，不影响发布本身。
    jwt_secret: str = ""

    def describe(self) -> str:
        return (f"{self.db.describe()} · uploads={self.uploads_dir} · "
                f"store={self.store_dir}")


# ---------- SSH 客户端 ----------


class SshClient:
    """一个服务器目标的 SSH 封装（命令、传文件、隧道）。"""

    def __init__(self, target: SshTarget, log: LogFn | None = None) -> None:
        self.target = target
        self._log = log or (lambda _m: None)
        self.ssh = _find_tool("ssh")
        self.scp = _find_tool("scp")

    # ---- 基础 ----

    @property
    def address(self) -> str:
        return f"{self.target.user}@{self.target.host}"

    def _opts(self) -> list[str]:
        """所有子命令共用的选项。

        `BatchMode=yes`：绝不弹密码/口令提示 —— 后台线程里弹不出来，
        卡住不返回比直接报错更难查。私钥有口令时会直接失败并给出明确原因。
        """
        opts = [
            "-o", "BatchMode=yes",
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=15",
            "-o", "ServerAliveInterval=15",
        ]
        key = os.path.expanduser(self.target.key_path or "")
        if key:
            if not Path(key).is_file():
                raise SshError(f"私钥不存在：{key}")
            opts += ["-i", key]
        return opts

    def _sshrun(self, remote_cmd: str) -> list[str]:
        return [self.ssh, *self._opts(), "-p", str(self.target.port), self.address, remote_cmd]

    def run(self, remote_cmd: str, timeout: float = 60.0, check: bool = True) -> str:
        """执行一条远程命令，返回 stdout。"""
        proc = subprocess.run(
            self._sshrun(remote_cmd),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        if check and proc.returncode != 0:
            detail = (proc.stderr or proc.stdout or "").strip()
            raise SshError(f"远程命令失败（退出码 {proc.returncode}）：{remote_cmd}\n{detail}")
        return proc.stdout or ""

    def check(self) -> str:
        """连通性自检，返回服务器 uname（失败抛 SshError）。"""
        out = self.run("echo SSH_OK; uname -sr", timeout=30).strip()
        return out.replace("\n", " ")

    # ---- 文件 ----

    def mkdir(self, remote_dir: str) -> None:
        self.run(f"mkdir -p {shlex.quote(remote_dir)}", timeout=60)

    def list_dir(self, remote_dir: str) -> list[str]:
        """列目录里的文件名（不递归）。

        目录不存在时返回空列表：`ls` 报错不算异常 —— 调用方靠「数量对不上」判断，
        比在这里抛错更好定位。
        """
        out = self.run(
            f"ls -1 {shlex.quote(remote_dir)} 2>/dev/null || true", timeout=120, check=False
        )
        return [line.strip() for line in out.splitlines() if line.strip()]

    def remove_tree(self, remote_path: str) -> None:
        """尽力删除（回滚用，失败只记日志不抛）。"""
        try:
            self.run(f"rm -rf -- {shlex.quote(remote_path)}", timeout=120, check=False)
        except Exception:
            pass

    def put_file(self, local_path: Path | str, remote_path: str) -> None:
        """scp 单个文件（原书用：一般几 MB，不流式）。"""
        local = Path(local_path)
        if not local.is_file():
            raise SshError(f"本地文件不存在：{local}")
        argv = [
            self.scp, "-P", str(self.target.port), *self._opts(),
            str(local), f"{self.address}:{remote_path}",
        ]
        proc = subprocess.run(
            argv, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600
        )
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout or "").strip()
            raise SshError(f"scp 失败（{local.name} → {remote_path}）：{detail}")

    def pipe_tar(
        self,
        pairs: Iterable[tuple[Path, str]],
        remote_dir: str,
        on_files: Callable[[int], None] | None = None,
        total_hint: int = 0,
    ) -> int:
        """把一批 (本地文件, 远端文件名) 用 tar 流一次性推到远端目录。

        ## 为什么用 tar 流 + Python 侧改名

        * 逐文件 scp：3500 个文件 = 3500 次握手，慢得没边；
        * 本地 `tar --transform`：Windows 自带的 `tar.exe` 是 **bsdtar**，没有这个选项
          （Git Bash 里的 GNU tar 才有，但工具进程未必用得到它）。

        所以在 Python 里用 `tarfile` 以流模式（`w|`）写进 ssh 的 stdin，
        `arcname` 直接给最终文件名 —— 既不落临时包，也不依赖本地 tar 的实现。
        远端只用 `tar -xf -`，GNU/bsdtar 都支持。
        """
        self.mkdir(remote_dir)
        argv = self._sshrun(f"tar -xf - -C {shlex.quote(remote_dir)}")
        proc = subprocess.Popen(
            argv, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE
        )
        pushed = 0
        try:
            assert proc.stdin is not None
            with tarfile.open(fileobj=proc.stdin, mode="w|") as tar:
                for local_file, arcname in pairs:
                    tar.add(str(local_file), arcname=arcname)
                    pushed += 1
                    if on_files is not None:
                        on_files(pushed)
            proc.stdin.close()
        except Exception:
            proc.kill()
            raise
        err = (proc.stderr.read() or b"").decode("utf-8", "replace") if proc.stderr else ""
        code = proc.wait()
        if code != 0:
            raise SshError(f"tar 上传失败（退出码 {code}）：{err.strip()[:500]}")
        self._log(f"    已推送 {pushed}/{total_hint or pushed} 个音频文件")
        return pushed

    # ---- 隧道 ----

    @contextmanager
    def tunnel(
        self, local_port: int, remote_host: str = "127.0.0.1", remote_port: int = 3306
    ) -> Iterator[int]:
        """起一条 `ssh -L` 隧道，退出时一定关掉。

        MySQL 只监听服务器的回环地址，所以「本地端口 → 服务器 127.0.0.1:3306」。
        退出用 `terminate()` 而不是等它自己结束：`-N` 永远不会自己退出。
        """
        argv = [
            self.ssh, *self._opts(), "-N",
            "-L", f"{local_port}:{remote_host}:{remote_port}",
            "-p", str(self.target.port), self.address,
        ]
        proc = subprocess.Popen(
            argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE
        )
        try:
            if not _wait_port(local_port):
                detail = ""
                if proc.poll() is not None and proc.stderr is not None:
                    detail = (proc.stderr.read() or b"").decode("utf-8", "replace").strip()
                raise SshError(
                    f"SSH 隧道建立失败（本地 {local_port} → {remote_host}:{remote_port}）"
                    + (f"：{detail}" if detail else "：15 秒内端口未就绪")
                )
            yield local_port
        finally:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()


# ---------- 读服务器参数 ----------


def read_server_info(ssh: SshClient, target: SshTarget, fallback_db: MySqlTarget) -> ServerInfo:
    """读服务器 env，解析出数据库连接与 uploads 目录。

    env 文件通常只有 root 可读，所以先直接 `cat`，失败再 `sudo -n cat`
    （服务器上的部署用户有免密 sudo；没有的话会走到「手动覆盖」分支）。

    手动覆盖规则：`fallback_db.password` 非空 ⇒ 认为用户要连另一套库，
    主机/端口/库名优先用 fallback_db，其余缺的再从 env 补。
    """
    env_path = target.server_env or ""
    text = ""
    if env_path:
        text = ssh.run(
            f"cat {shlex.quote(env_path)} 2>/dev/null || sudo -n cat {shlex.quote(env_path)} 2>/dev/null",
            timeout=30,
            check=False,
        )
    values = parse_env_file(text)

    db_host, db_port, db_name = "127.0.0.1", 3306, "audiobook_db"
    if values.get("DB_URL"):
        db_host, db_port, db_name = parse_jdbc_url(values["DB_URL"])
    if values.get("DB_HOST"):
        db_host = values["DB_HOST"]
    if values.get("DB_PORT"):
        try:
            db_port = int(values["DB_PORT"])
        except ValueError:
            pass
    if values.get("DB_NAME"):
        db_name = values["DB_NAME"]

    db = MySqlTarget(
        host=db_host,
        port=db_port,
        database=db_name,
        user=values.get("DB_USERNAME") or "root",
        password=values.get("DB_PASSWORD") or "",
    )

    source = "env"
    if fallback_db.password:
        db = MySqlTarget(
            host=fallback_db.host or db.host,
            port=int(fallback_db.port or db.port),
            database=fallback_db.database or db.database,
            user=fallback_db.user or db.user,
            password=fallback_db.password,
        )
        source = "手动覆盖"
    elif not db.password:
        raise SshError(
            "读不到服务器数据库口令：请确认 "
            f"{env_path or '服务器 env 文件'} 存在且含 DB_PASSWORD，"
            "或在「服务器库」里手动填写数据库口令。"
        )

    uploads = target.remote_uploads or values.get("UPLOAD_DIR") or DEFAULT_UPLOADS_DIR
    store = target.remote_store or values.get("STORE_DIR") or DEFAULT_STORE_DIR
    return ServerInfo(
        db=db,
        uploads_dir=uploads.rstrip("/"),
        store_dir=store.rstrip("/"),
        source=source,
        jwt_secret=clean_secret(values.get("JWT_SECRET") or ""),
    )


def clean_secret(raw: str) -> str:
    """清掉 env 值里的引号与换行。

    env 是按 systemd 口径写的（`JWT_SECRET="xxx"`），而各种手改/复制粘贴会带进
    CR、行尾空格、多余引号 —— 这些字符进了签名密钥只会得到一个「签名不匹配」的 401，
    且完全看不出原因。JWT_SECRET 本身就是无空白的随机串，去掉它们永远是安全的。
    """
    text = (raw or "").strip()
    for _ in range(2):  # 处理 `"'xxx'"` 这类双层引号
        if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'":
            text = text[1:-1]
    return "".join(ch for ch in text if ch not in "\"'\r\n\t ")
