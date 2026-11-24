"""管理端 HTTP 调用：本地签发管理员 token 调 `/api/admin/**`。

## 为什么需要这一层

发布到书城的收尾要给服务端补一次**正文快照**（`POST /api/admin/books/{id}/content-snapshot`）。
快照本来只在「服务端合成」结束时写入；本地工具是直接把音频与 `store_books` 塞进库里，
走不到那段代码，于是这类书每次打开阅读都要服务端实时解析整本
（实测《国富论》9.2s，有快照的书 95ms）。补一次就归零。

## 为什么自己签 token 而不是存管理员口令

管理员端点要求 `ROLE_ADMIN`，而这个工具**从来不走账号口令登录**：它已经通过 SSH 隧道
直连生产库、直接写 `tasks / audio_segments / store_books`，权限本来就高于任何 HTTP 接口。
既然服务器 env 里就有 `JWT_SECRET`，在这里按 `JwtUtil.generateToken` 的口径签一个
**短时（10 分钟）**token 是最小改动：

    key    = SHA-512(secret)              （与 JwtUtil.getSigningKey 完全一致）
    alg    = HS512
    claims = sub / isAdmin / tokenVersion

服务端 `JwtAuthenticationFilter` 拿到 token 后仍会按库里的 `token_version` 和封禁状态复核，
所以签出来的权限**不会超过**那个账号本身 —— 不是绕过鉴权，是复用同一把签名钥匙。

## token 去哪了

只出现在 HTTP 请求头里：不落盘、不进日志、不进命令行参数。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.request
from typing import Any, Callable

LogFn = Callable[[str], None]

# token 有效期：一次发布收尾只需要几秒，10 分钟足够了；
# 越短越安全（万一内存被 dump，能用的窗口也小）
DEFAULT_TOKEN_TTL = 600

# 重建快照要服务端解析整本 PDF：4 MB 的《国富论》实测 9.2s，给足余量
DEFAULT_TIMEOUT = 180.0


class ApiError(RuntimeError):
    """HTTP 调用失败（连不上 / 非 2xx / 响应不是 JSON）。"""


def _b64url(raw: bytes) -> str:
    """JWT 用的 base64url（无 padding）。"""
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _compact_json(value: dict) -> bytes:
    """JWT 段内的 JSON 必须是紧凑格式（带空格服务端也能解析，但没必要多传字节）。"""
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def sign_admin_token(
    secret: str,
    username: str,
    token_version: int = 0,
    ttl_seconds: int = DEFAULT_TOKEN_TTL,
) -> str:
    """签发一个管理员 JWT（与 `JwtUtil.generateToken` 同构）。

    纯标准库实现（hmac + hashlib），不引入 PyJWT —— 工具只为这一个调用加依赖不值当。
    """
    if not secret:
        raise ApiError("没有 JWT_SECRET，无法签发管理员 token")
    if not username:
        raise ApiError("没有管理员用户名，无法签发 token")

    now = int(time.time())
    header = {"alg": "HS512"}
    payload = {
        "sub": username,
        "isAdmin": True,
        "tokenVersion": int(token_version or 0),
        "iat": now,
        "exp": now + int(ttl_seconds),
    }
    signing_input = (
        f"{_b64url(_compact_json(header))}.{_b64url(_compact_json(payload))}"
    )
    key = hashlib.sha512(secret.encode("utf-8")).digest()
    signature = hmac.new(key, signing_input.encode("ascii"), hashlib.sha512).digest()
    return f"{signing_input}.{_b64url(signature)}"


class AdminApi:
    """一个管理端 API 客户端（固定 token，一次发布复用）。

    只实现发布收尾需要的那一个端点：多写一个方法就多一份「和服务端签名对不上」的风险。
    """

    def __init__(
        self,
        base_url: str,
        secret: str,
        username: str,
        token_version: int = 0,
        log: LogFn | None = None,
        timeout: float = DEFAULT_TIMEOUT,
    ) -> None:
        self.base_url = (base_url or "").rstrip("/")
        self.timeout = float(timeout)
        self._log = log or (lambda _m: None)
        self._token = sign_admin_token(secret, username, token_version)

    def _post(self, path: str, timeout: float | None = None) -> dict[str, Any]:
        """POST 一个端点，返回解析后的 JSON；非 2xx 抛 ApiError（带上服务端的 message）。"""
        if not self.base_url:
            raise ApiError("没有管理端地址（server_api_base 为空，且 SSH 主机也没填）")
        url = f"{self.base_url}{path}"
        request = urllib.request.Request(
            url,
            data=b"{}",                      # 端点无请求体，但 POST 要带上长度，省得被拒
            method="POST",
            headers={
                "Authorization": f"Bearer {self._token}",
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout or self.timeout) as resp:
                body = resp.read().decode("utf-8", "replace")
                status = resp.status
        except urllib.error.HTTPError as exc:
            # 4xx/5xx 的响应体里有 {success,message}，比异常字符串有用得多
            detail = ""
            try:
                detail = exc.read().decode("utf-8", "replace")
            except Exception:  # noqa: BLE001 - 读不出体就只报状态码
                pass
            raise ApiError(f"HTTP {exc.code}：{_short(detail) or exc.reason}") from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"连不上 {url}：{exc.reason}") from exc
        except TimeoutError as exc:
            raise ApiError(f"{url} 超时（{timeout or self.timeout:.0f}s）") from exc

        try:
            data = json.loads(body)
        except json.JSONDecodeError as exc:
            raise ApiError(f"HTTP {status} 但响应不是 JSON：{_short(body)}") from exc
        if not isinstance(data, dict):
            raise ApiError(f"HTTP {status} 响应结构异常：{_short(body)}")
        if data.get("success") is False:
            raise ApiError(str(data.get("message") or "服务端返回失败"))
        return data

    def rebuild_content_snapshot(self, store_book_id: int) -> dict[str, Any]:
        """让服务端重建这本书的正文快照（幂等：重复调用只是覆盖同一行）。

        返回 `{title, paragraphs, rows, fileSize}`。
        """
        return self._post(f"/api/admin/books/{int(store_book_id)}/content-snapshot")


def _short(text: str, limit: int = 200) -> str:
    """异常信息只留一段：HTTP 错误体可能是整页 HTML。"""
    flat = " ".join((text or "").split())
    return flat[:limit]
