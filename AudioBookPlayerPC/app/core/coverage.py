"""有声覆盖图（Phase3 跨端同步）编解码工具 —— 必须与服务端
``com.audiobook.server.util.DoneRanges`` / ``com.audiobook.server.util.BookKeys``
逐字符一致。

覆盖图用「1-based 闭区间压缩串」描述某本书里已经完成合成的段号集合：
逗号分隔、升序、相邻或重叠区间合并；单点写成 ``"41"``，区间写成 ``"41-50"``。
例：``"1-40,41,45-50"``。

三端（Java / Python / Kotlin）对同一个完成段集合，encode 出的规范串必须完全相同，
否则跨端并集 / 去重会漂。规范算法固定为：

    所有区间归一为 (start, end) 整数对 → 按 start 升序（再按 end）→
    相邻/重叠（``next.start <= cur.end + 1``）合并 → 输出 ``start==end ? n : s-e``。

同时提供 bookKey 生成：身份口径 ``s:{storeBookId}`` / ``t:{serverTaskId}`` /
``n:{normalize_name(displayName)}``，三端一致；n: 尾部按 :func:`normalize_name` 归一化
（去扩展名 + Locale 无关小写 + 压缩空白），避免同一本书在不同设备上因文件名大小写、
扩展名带不带、空格数量差异而裂成多行覆盖图。
"""
from __future__ import annotations

from typing import Iterable, List, Sequence, Tuple, Set

# doneRanges 单条上限（与 Java isValid / DTO @Size 对齐）
MAX_DONE_RANGES_LEN = 8192

Range = Tuple[int, int]


def _parse_token(tok: str) -> Range:
    """解析单个 token：``"n"`` → (n, n)；``"a-b"`` → (a, b)。非法抛 ValueError。"""
    if not tok:
        raise ValueError("doneRanges 含空数字")
    # 只允许纯数字；段号 ≥1；不得以 0 开头（避免规范串漂）
    dash = tok.find("-")
    if dash < 0:
        return _parse_num(tok), _parse_num(tok)
    a = _parse_num(tok[:dash])
    b = _parse_num(tok[dash + 1:])
    if a > b:
        raise ValueError(f"doneRanges 区间起点大于终点: {tok}")
    return a, b


def _parse_num(s: str) -> int:
    if not s or not s.isdigit():
        raise ValueError(f"doneRanges 含非数字: {s!r}")
    if len(s) > 1 and s[0] == "0":
        raise ValueError(f"doneRanges 数字不得以 0 开头: {s!r}")
    n = int(s)
    if n < 1:
        raise ValueError(f"doneRanges 段号必须 ≥1: {s!r}")
    return n


def parse(s: str) -> List[Range]:
    """解析规范串为区间列表。空/None → 空列表。非法 token → 抛 ValueError。"""
    out: List[Range] = []
    if s is None:
        return out
    t = s.strip()
    if not t:
        return out
    for token in t.split(","):
        tok = token.strip()
        if not tok:
            continue
        out.append(_parse_token(tok))
    return out


def normalize(raw: Sequence[Range]) -> List[Range]:
    """排序 + 相邻/重叠合并，得到规范区间列表。"""
    lst = sorted(raw, key=lambda r: (r[0], r[1]))
    merged: List[Range] = []
    for r in lst:
        if not merged:
            merged.append((r[0], r[1]))
        else:
            ls, le = merged[-1]
            if r[0] <= le + 1:  # 相邻或重叠都合并
                merged[-1] = (ls, max(le, r[1]))
            else:
                merged.append((r[0], r[1]))
    return merged


def union(*groups: Sequence[Range]) -> List[Range]:
    """并集：合并多组区间（自动排序 + 相邻/重叠合并）。"""
    all_r: List[Range] = []
    for g in groups:
        if g:
            all_r.extend(g)
    return normalize(all_r)


def encode(ranges: Sequence[Range]) -> str:
    """规范串 encode。"""
    parts: List[str] = []
    for s, e in ranges:
        parts.append(str(s) if s == e else f"{s}-{e}")
    return ",".join(parts)


def merge(a: str, b: str) -> str:
    """两串并集 → 规范串。"""
    return encode(union(parse(a), parse(b)))


def count(s: str) -> int:
    """覆盖段数。"""
    n = 0
    for a, b in parse(s):
        n += (b - a + 1)
    return n


def from_numbers(nums: Iterable[int]) -> str:
    """从一组段号（可乱序/重复）生成规范覆盖串。忽略 <1 的非法段号。"""
    ranges = [(int(n), int(n)) for n in nums if n is not None and int(n) >= 1]
    return encode(normalize(ranges))


def expand(s: str) -> Set[int]:
    """覆盖串展开为段号集合（供播放可行性判定）。"""
    out: Set[int] = set()
    for a, b in parse(s):
        out.update(range(a, b + 1))
    return out


def contains(s: str, seg: int) -> bool:
    """覆盖串是否包含某段号。"""
    try:
        n = int(seg)
    except (TypeError, ValueError):
        return False
    for a, b in parse(s):
        if a <= n <= b:
            return True
    return False


def is_valid(s) -> bool:
    """校验（供上报前自检）：None/空串合法；能 parse 且不超长。"""
    if s is None:
        return True
    if len(s) > MAX_DONE_RANGES_LEN:
        return False
    try:
        parse(s)
        return True
    except ValueError:
        return False


# ---------- bookKey 身份口径（三端必须逐字符一致） ----------

# 已知电子书扩展名。**按长度倒序 + 字母序**——防止 `.azw` 遮蔽 `.azw3`、`.doc` 遮蔽 `.docx`。
# 三端必须使用完全相同的列表和顺序，与服务端 `BookKeys.EXTENSIONS`、
# Android `CoverageCodec.EXTENSIONS` 一致。
_EXTENSIONS: Tuple[str, ...] = (
    ".azw3", ".docx", ".djvu", ".epub", ".mobi",
    ".pdf",  ".azw",  ".doc",  ".fb2",  ".txt",
)

# "书键空白"字符集合。显式列举，避免 Python `str.isspace()` / Java
# `Character.isWhitespace()` / Kotlin `Char.isWhitespace()` 三者定义不一致导致跨端漂。
# 包含：
#   ASCII：0x09 TAB、0x0A LF、0x0B VT、0x0C FF、0x0D CR、0x20 SPACE
#   Unicode：0xA0 NBSP、0x1680 OGHAM SPACE MARK、0x2000-0x200A 排版空格、
#            0x2028 LINE SEP、0x2029 PARA SEP、0x202F NNBSP、0x205F MMSP、0x3000 全角空格
_BOOK_WS_CODEPOINTS: frozenset = frozenset(
    [0x20]
    + list(range(0x09, 0x0E))
    + [0xA0, 0x1680]
    + list(range(0x2000, 0x200B))
    + [0x2028, 0x2029, 0x202F, 0x205F, 0x3000]
)


def _is_book_ws(ch: str) -> bool:
    return ord(ch) in _BOOK_WS_CODEPOINTS


def _trim_book_ws(s: str) -> str:
    a, b = 0, len(s)
    while a < b and _is_book_ws(s[a]):
        a += 1
    while b > a and _is_book_ws(s[b - 1]):
        b -= 1
    return s if (a == 0 and b == len(s)) else s[a:b]


def _compress_ws(s: str) -> str:
    """把连续"书键空白"压成单个 ASCII 空格（0x20），并去两端空白。"""
    out: List[str] = []
    in_ws_run = False
    for ch in s:
        if _is_book_ws(ch):
            in_ws_run = True
        else:
            if in_ws_run and out:
                out.append(" ")
            in_ws_run = False
            out.append(ch)
    return "".join(out)


def normalize_name(name) -> str:
    """归一化书名（不含 ``n:`` 前缀）。**必须与服务端 ``BookKeys.normalizeName``、
    Android ``CoverageCodec.normalizeName`` 逐字符一致**——三端对同一输入必须产出同一字符串，
    否则跨端合并会漂。

    规则（顺序固定，禁止增删步骤）：
      1. 去两端"书键空白"（见 :data:`_BOOK_WS_CODEPOINTS`）；
      2. Unicode 小写化（Python ``str.lower()`` 天然 Locale 无关，与 Java ``toLowerCase(Locale.ROOT)``
         语义一致）；
      3. 若尾部匹配 :data:`_EXTENSIONS` 中某扩展名（且长度严格大于扩展名），剥掉一次；
      4. 压缩内部连续空白为单个 ASCII 空格（0x20），并再次去两端空白。
    """
    if name is None:
        return ""
    s = _trim_book_ws(str(name))
    if not s:
        return ""
    s = s.lower()
    for ext in _EXTENSIONS:
        if len(s) > len(ext) and s.endswith(ext):
            s = s[: -len(ext)]
            break
    return _compress_ws(s)


def normalize_book_key(book_key) -> str:
    """归一化整个 bookKey。仅对 ``n:`` 前缀生效；``s:``/``t:``/未知前缀原样返回（去两端空白后）。
    客户端一般不用它（直接调 :func:`book_key`），但接收服务端回传时可用它做防御性对齐。
    """
    if not book_key:
        return ""
    t = _trim_book_ws(str(book_key))
    if not t:
        return ""
    if t.startswith("n:"):
        tail = normalize_name(t[2:])
        return f"n:{tail}" if tail else ""
    return t


def book_key(store_book_id=None, server_task_id=None, display_name: str = "") -> str:
    """生成覆盖图身份键。优先级：书城 s: > 在线任务 t: > 本地名 n:。

    与 Java/Android 的 bookKey 规则一致：
      - store_book_id 为正整数 → ``s:{store_book_id}``
      - 否则 server_task_id 为正整数 → ``t:{server_task_id}``
      - 否则 :func:`normalize_name` 后非空 → ``n:{normalize_name(display_name)}``
      - 都不满足 → 空串（非法，调用方应跳过）

    n: 分支的归一化（去扩展名/小写/压缩空白）保证同一本书在不同设备上产出相同键，
    避免大小写/扩展名/空格差异导致覆盖图裂行。
    """
    sbid = _pos_int(store_book_id)
    if sbid:
        return f"s:{sbid}"
    stid = _pos_int(server_task_id)
    if stid:
        return f"t:{stid}"
    name = normalize_name(display_name)
    if name:
        return f"n:{name}"
    return ""


def _pos_int(v) -> int:
    try:
        iv = int(v)
    except (TypeError, ValueError):
        return 0
    return iv if iv > 0 else 0
