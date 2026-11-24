"""MOBI / AZW 正文解析（纯标准库，不引第三方依赖）。

## MOBI 是什么

MOBI 是 Kindle 的老格式，外层是 **Palm Database（PDB）** 容器：

    ┌──────────────┬──────────────────────────────────────────┐
    │ PDB 头 78B   │ 记录信息表（每条 8B：offset+attr+uid） │
    ├──────────────┼──────────────────────────────────────────┤
    │ record 0     │ PalmDOC/MOBI 头（压缩类型、文本长度…）  │
    │ record 1..N  │ 正文记录（LZ77 压缩的 HTML）             │
    └──────────────┴──────────────────────────────────────────┘

正文解压后是 **HTML**，再剥标签得到纯文本。

## 为什么自己实现

* 服务端（`BookTextExtractor.decompressMobiText`）就是这套纯实现，
  工具照着做 → **同一本书在两端抽出的正文一致**，段号与目录坐标才对得上。
* `pip install mobi` 会拖进 kindleunpack + pycryptodome 一串依赖；
  这个工具的依赖越少越好装，正文提取这部分自己写更可控。

## 实现要点（都是踩过的坑）

* **PDB 头是 78 字节，记录数在偏移 76**（UInt16，big-endian）：
  早前有人把它当成「78 字节头 + 2 字节记录数」，读到的是 record[0].offset 的高 2 字节
  （真实 MOBI 里 offset=78=0x0000004E ⇒ 高 2 字节=0x0000）→ 记录数恒为 0、任何标准文件都解析失败。
  这里只跳 76 字节，读完记录数游标正好落在记录表起点。
* **MOBI 记录头是 big-endian**（与 PalmDOC 的 little-endian 相反）：
  压缩类型在 record0 的前 2 字节、文本长度在第 4~7 字节、文本记录数在第 8~9 字节、
  单条记录上限（recordSize）在第 10~11 字节，都是 big-endian。
* **PalmDOC LZ77 的回引要从「活动缓冲」逐字节读**：
  距离小于长度时会重叠（distance=2 应交替复制最后两个字节），
  快照式回读会复制错。
* **每条文本记录解压后不超过 recordSize（4096）**：
  压缩流尾部会带填充字节（例如结尾的 `00 81`），
  不限长就会多解出几字节，把后面的正文整体错位。
* **正文总长要按 record0 的 textLength 截断**：
  最后一条文本记录后面可能还挂着别的数据（目录、封面标记等），
  不截断就会把尾巴上的杂字节一起当成正文。
  上面两条一起用，本机 9 本中文 MOBI 实测：解压长度与 textLength **逐字节相等**，
  且整段 UTF-8 解码零错误。
"""

from __future__ import annotations

from pathlib import Path

# PDB 头（含末尾 2 字节记录数）至少 78 字节
PDB_HEADER_SIZE = 78

# MOBI 单条文本记录解压后的上限（record0 里的 recordSize 字段，标准值 4096）
DEFAULT_RECORD_SIZE = 4096


class MobiParseError(Exception):
    """MOBI 解析失败（损坏 / 非标准 / DRM 加密）。"""


def _be_uint16(data: bytes, offset: int) -> int:
    return (data[offset] << 8) | data[offset + 1]


def _be_uint32(data: bytes, offset: int) -> int:
    return (
        (data[offset] << 24) | (data[offset + 1] << 16)
        | (data[offset + 2] << 8) | data[offset + 3]
    )


def palmdoc_decompress(data: bytes, max_output: int | None = None) -> bytes:
    """PalmDOC LZ77 解压。

    码表：
        0x00        → 字面量 0x00
        0x01-0x08   → 从输入流复制 byte 个字面量字节（不是 byte+1）
        0x09-0x7F   → 字面量字节
        0x80-0xBF   → 两字节：distance = (pair >> 3) & 0x7FF，length = (pair & 7) + 3
        0xC0-0xFF   → 空格(0x20) + (byte XOR 0x80)

    max_output：最多产出多少字节（MOBI 一条文本记录解压后不超过 recordSize）。
    """
    out = bytearray()
    pos = 0
    length = len(data)
    while pos < length:
        if max_output is not None and len(out) >= max_output:
            break
        byte = data[pos]
        pos += 1
        if byte == 0x00:
            out.append(0x00)
        elif 0x01 <= byte <= 0x08:
            copy_len = min(byte, length - pos)
            out += data[pos : pos + copy_len]
            pos += copy_len
        elif 0x09 <= byte <= 0x7F:
            out.append(byte)
        elif 0x80 <= byte <= 0xBF:
            if pos >= length:
                break
            second = data[pos]
            pos += 1
            pair = (byte << 8) | second
            distance = (pair >> 3) & 0x7FF
            copy_len = (pair & 0x07) + 3
            src = len(out) - distance
            for _ in range(copy_len):
                if src < 0:
                    out.append(0x20)          # 回引越过起点：按 PalmDOC 惯例补空格
                elif src >= len(out):
                    out.append(out[-1])       # 越过当前末尾：重复最后一个字节
                else:
                    out.append(out[src])      # 逐字节读「活动缓冲」→ 重叠回引也正确
                src += 1
        else:
            out.append(0x20)
            out.append(byte ^ 0x80)
    if max_output is not None and len(out) > max_output:
        del out[max_output:]
    return bytes(out)


def decompress_mobi_text(raw: bytes) -> bytes:
    """把 MOBI 的正文记录解压成 HTML 字节数组。失败抛 MobiParseError。"""
    if raw is None or len(raw) < PDB_HEADER_SIZE:
        raise MobiParseError("MOBI 文件过小，无法解析")

    # 只跳 76 字节 → 记录数(UInt16) → 游标正好落在 78（记录表起点）
    num_records = _be_uint16(raw, 76)
    if num_records < 2:
        raise MobiParseError("MOBI 记录数过少：%d" % num_records)

    offsets: list[int] = []
    pos = PDB_HEADER_SIZE
    for _ in range(num_records):
        if pos + 8 > len(raw):
            raise MobiParseError("MOBI 记录信息表被截断")
        offsets.append(_be_uint32(raw, pos))
        pos += 8  # offset(4) + attributes(1) + uniqueID(3)

    # 偏移表合法性：必须落在文件内且单调递增
    for index, offset in enumerate(offsets):
        if offset < 0 or offset > len(raw):
            raise MobiParseError("MOBI 记录偏移异常：offsets[%d]=%d" % (index, offset))
        if index > 0 and offset < offsets[index - 1]:
            raise MobiParseError("MOBI 记录偏移非单调：offsets[%d]" % index)

    if offsets[1] <= offsets[0]:
        raise MobiParseError("MOBI record0 偏移非法")

    record0 = raw[offsets[0] : offsets[1]]
    if len(record0) < 16:
        raise MobiParseError("MOBI PalmDOC 头部过短")

    compression = _be_uint16(record0, 0)
    text_length = _be_uint32(record0, 4)      # 正文总长（字节）
    record_count = _be_uint16(record0, 8)
    record_size = _be_uint16(record0, 10)      # 单条记录解压后的上限

    if compression not in (1, 2):
        # 0 = 无压缩（少见）；其他值（含加密标志）不支持
        raise MobiParseError(
            "MOBI 压缩类型无效：%d（若是 DRM 加密的 AZW3，请先解密）" % compression
        )

    if record_count > num_records - 1:
        record_count = num_records - 1
    if record_count < 1:
        raise MobiParseError("MOBI 文本记录数为 0")
    if record_size <= 0:
        record_size = DEFAULT_RECORD_SIZE

    chunks: list[bytes] = []
    for index in range(1, record_count + 1):
        if index + 1 < num_records:
            size = offsets[index + 1] - offsets[index]
        else:
            size = len(raw) - offsets[index]
        if size <= 0:
            continue
        record = raw[offsets[index] : offsets[index] + size]
        if compression == 2:
            # 限长：压缩流尾部有填充字节，不限长会多解出几字节、正文整体错位
            chunks.append(palmdoc_decompress(record, max_output=record_size))
        else:
            chunks.append(record[:record_size])
    if not chunks:
        raise MobiParseError("MOBI 没有可用的文本记录")

    text = b"".join(chunks)
    # 按 record0 声明的正文总长截断：最后一条文本记录后面可能还挂着别的数据
    if 0 < text_length <= len(text):
        text = text[:text_length]
    return text


def decode_mobi_bytes(data: bytes) -> str:
    """解码正文：优先 UTF-8（服务端同口径），失败退 cp1252 / latin-1。

    服务端是 `new String(bytes, UTF_8)`（非法字节会变成 U+FFFD 替换符）；
    这里多一层回退：真实 MOBI 里偶有非 UTF-8 的老文件，
    退到 cp1252/latin-1 至少不出现成片的 U+FFFD。
    """
    for encoding in ("utf-8", "cp1252", "latin-1"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def extract_mobi_html(path: str | Path) -> str:
    """读取 MOBI 文件并返回解压后的 HTML 文本。"""
    raw = Path(path).read_bytes()
    decompressed = decompress_mobi_text(raw)
    if not decompressed:
        raise MobiParseError("MOBI 解压后没有内容")
    return decode_mobi_bytes(decompressed)
