"""电子书正文提取：把本机的电子书文件变成纯文本。

## 支持的格式与取舍

| 格式 | 做法 | 说明 |
|---|---|---|
| txt / md | 直接读，按编码探测解码 | 绝大多数中文电子书是这两种 |
| epub | zipfile + 内置 HTML 解析器 | 按 OPF spine 顺序取章节，顺序错了正文会乱 |
| pdf | pypdf 逐页取文本 | 页与页之间补换行（否则跨页句子会粘成一段） |
| mobi / azw / azw3 / prc | PDB 头 + PalmDOC LZ77 解压（见 `mobitext.py`） | 与服务端 `BookTextExtractor` 同一套实现，解压出的 HTML 字节与服务端**逐字节相同**；DRM 加密的 AZW3 解不了 |

## 为什么自己写 HTML 解析而不是引入 bs4

epub 内部就是 xhtml，标签结构规整（p / div / br / h1..h6），
用标准库的 html.parser 足够；而 bs4 会给工具本体多加一个依赖，
这个工具的依赖越少越好装（用户拿到手就要能跑起来）。
"""

from __future__ import annotations

import re
import zipfile
from html.parser import HTMLParser
from pathlib import Path

# 块级标签：遇到它们要断行，否则整章会挤成一大段
_BLOCK_TAGS = {
    "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
    "section", "article", "blockquote", "pre", "td",
}

# 这些标签的内容不要（脚本/样式会污染正文）
_SKIP_TAGS = {"script", "style", "head", "title"}

# 编码探测顺序：中文 txt 常见 UTF-8 / UTF-8-BOM / GBK / GB18030
_ENCODINGS = ("utf-8-sig", "utf-8", "gb18030", "big5", "latin-1")


class UnsupportedBookError(Exception):
    """电子书格式无法解析（未知后缀、MOBI 损坏或加密、缺少 pypdf 等）。"""


class _TextHarvester(HTMLParser):
    """把 xhtml 正文抽成纯文本，块级元素之间补换行。"""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs) -> None:
        tag = tag.lower()
        if tag in _SKIP_TAGS:
            self._skip_depth += 1
            return
        if tag in _BLOCK_TAGS:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in _SKIP_TAGS:
            self._skip_depth = max(0, self._skip_depth - 1)
            return
        if tag in _BLOCK_TAGS:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if self._skip_depth == 0:
            self.parts.append(data)

    def text(self) -> str:
        raw = "".join(self.parts)
        # 连续空行压成一行空行：段落之间留一个换行即可，
        # 多留会让后面的切句产出大量空片段
        raw = re.sub(r"[ \t]+\n", "\n", raw)
        raw = re.sub(r"\n{3,}", "\n\n", raw)
        return raw.strip()


def _read_text_file(path: Path) -> str:
    raw = path.read_bytes()
    for enc in _ENCODINGS:
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="ignore")


def _html_to_text(html: str) -> str:
    harvester = _TextHarvester()
    try:
        harvester.feed(html)
        harvester.close()
    except Exception:
        # 个别 xhtml 不合法：已经抽到的部分照样能用
        pass
    return harvester.text()


def _epub_chapter_order(zf: zipfile.ZipFile) -> list[str]:
    """按 OPF spine 顺序返回 xhtml 条目名；解析失败则退化为按文件名排序。"""
    names = set(zf.namelist())
    opf_name = None
    if "META-INF/container.xml" in names:
        try:
            container = zf.read("META-INF/container.xml").decode("utf-8", errors="ignore")
            match = re.search(r"full-path\s*=\s*[\"']([^\"']+)[\"']", container)
            if match:
                opf_name = match.group(1)
        except Exception:
            opf_name = None
    if opf_name is None:
        candidates = [n for n in names if n.lower().endswith(".opf")]
        if not candidates:
            return []
        opf_name = sorted(candidates)[0]

    try:
        opf = zf.read(opf_name).decode("utf-8", errors="ignore")
    except KeyError:
        return []

    base = opf_name.rsplit("/", 1)[0] if "/" in opf_name else ""
    # manifest：id -> href
    manifest: dict[str, str] = {}
    for item in re.findall(r"<item\b[^>]*/?>", opf, flags=re.IGNORECASE):
        item_id = re.search(r"\bid\s*=\s*[\"']([^\"']+)[\"']", item)
        href = re.search(r"\bhref\s*=\s*[\"']([^\"']+)[\"']", item)
        if item_id and href:
            manifest[item_id.group(1)] = href.group(1)

    ordered: list[str] = []
    for ref in re.findall(r"<itemref\b[^>]*/?>", opf, flags=re.IGNORECASE):
        idref = re.search(r"\bidref\s*=\s*[\"']([^\"']+)[\"']", ref)
        if not idref:
            continue
        href = manifest.get(idref.group(1))
        if not href:
            continue
        path = f"{base}/{href}" if base else href
        # epub 里的 href 可能是 URL 编码的，且可能带 ../ 前缀
        path = path.replace("../", "")
        if path in names and path not in ordered:
            ordered.append(path)
    return ordered


def _is_epub_resource(name: str) -> bool:
    """是不是 EPUB 里的「非正文」文件（图片/样式/字体/元数据…）。"""
    lower = name.lower()
    if lower.startswith("meta-inf/"):
        return True
    return lower.endswith((
        ".opf", ".ncx", ".css", ".js", ".json", ".png", ".jpg", ".jpeg", ".gif",
        ".svg", ".webp", ".ttf", ".otf", ".woff", ".woff2", ".mp3", ".mp4", ".m4a",
    ))


def _natural_key(name: str) -> tuple:
    """自然排序：文件名里的数字按数值比（chapter2 < chapter10）。

    字典序会把 chapter10 排到 chapter2 前面 —— 章节顺序直接决定正文顺序，
    排错了整本书就是乱的。
    """
    parts = re.split(r"(\d+)", name)
    return tuple(int(p) if p.isdigit() else p.lower() for p in parts)


def _extract_epub(path: Path) -> str:
    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
        order = _epub_chapter_order(zf)
        # ⚠️ **不要按后缀过滤章节**：有些书把章节存成 `.xml`
        # （实测《改革历程.epub》正文是 OEBPS/chapter1.xml … chapter20.xml），
        # 只认 .xhtml/.html/.htm 就会一章都取不到 ⇒ 整本提取为空。
        # 服务端是按 OPF spine **原样**读取全部分章的，这里保持同口径。
        entries = [n for n in order if not _is_epub_resource(n)]
        if not entries:
            # 没有 spine（解析失败）：退回「按文件名排序取所有疑似正文」
            entries = sorted(
                (n for n in names if not _is_epub_resource(n)),
                key=_natural_key,
            )
        chunks: list[str] = []
        for name in entries:
            try:
                html = zf.read(name).decode("utf-8", errors="ignore")
            except KeyError:
                continue
            text = _html_to_text(html)
            if text:
                chunks.append(text)
    return "\n\n".join(chunks)


def _extract_pdf(path: Path) -> str:
    try:
        from pypdf import PdfReader
    except ImportError as exc:  # 依赖缺失时给出可执行的提示
        raise UnsupportedBookError(
            "解析 PDF 需要 pypdf，请先执行：pip install pypdf"
        ) from exc
    reader = PdfReader(str(path))
    pages: list[str] = []
    for page in reader.pages:
        try:
            pages.append(page.extract_text() or "")
        except Exception:
            pages.append("")
    # 页与页之间补换行：否则跨页的句子会被粘成一段
    return "\n\n".join(pages).strip()


def _extract_mobi(path: Path) -> str:
    """MOBI / AZW：解压出 HTML，再走与 EPUB 同一套 HTML 清洗。

    解析实现见 `mobitext.py`（纯标准库，口径与服务端
    `BookTextExtractor.decompressMobiText` 一致）。
    """
    from app.core.mobitext import MobiParseError, extract_mobi_html

    try:
        html = extract_mobi_html(path)
    except MobiParseError as exc:
        raise UnsupportedBookError("MOBI 解析失败：" + str(exc)) from exc
    return _html_to_text(html)


def extract_text(path: str | Path) -> str:
    """提取电子书正文（纯文本）。

    失败时抛异常（由调用方转成界面上的提示），不吞异常 ——
    「这本书读不出来」必须让用户看见，否则会表现成「合成结果是空的」。
    """
    book_path = Path(path)
    if not book_path.is_file():
        raise FileNotFoundError(f"电子书不存在：{book_path}")

    suffix = book_path.suffix.lower().lstrip(".")
    if suffix in ("txt", "md", "markdown", "text"):
        return _read_text_file(book_path)
    if suffix == "epub":
        return _extract_epub(book_path)
    if suffix == "pdf":
        return _extract_pdf(book_path)
    if suffix in ("mobi", "azw", "azw3", "prc"):
        return _extract_mobi(book_path)
    # 未知后缀：按纯文本试一次，能读出来就用
    try:
        return _read_text_file(book_path)
    except Exception as exc:
        raise UnsupportedBookError(f"不支持的电子书格式：{suffix or '（无后缀）'}") from exc


def book_title(path: str | Path) -> str:
    """书名：取文件名（去后缀）。落库时用 original_book_name。"""
    return Path(path).stem
