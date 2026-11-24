"""统一电子书正文提取入口：把本机电子书文件变成「段落列表 + 目录」。

## 支持的格式

| 后缀 | 做法 |
|---|---|
| ``.txt`` | 按编码探测（utf-8 → gbk → latin-1）读取，按空行切成自然段 |
| ``.md`` / ``.markdown`` | 先剥 Markdown 语法成纯文本，ATX 标题（# ~ ######）顺带导出为目录 |
| ``.epub`` | ``ebooklib.epub.read_epub``，按 OPF **spine 顺序**取章节，剥 HTML 标签 |
| ``.pdf`` | ``pypdf.PdfReader`` 逐页取文本，剥离页眉/页脚/页码， outline 作目录 |
| ``.mobi`` / ``.azw`` / ``.azw3`` / ``.prc`` | PDB 头 + **PalmDOC LZ77 解压**出 HTML，再剥标签 |

## 与服务端口径的关系

正文**提取**（本模块）与**切句**（``segmenter``）是两件事：本模块只负责把文件读成
「自然段 + 目录」，**不切句**。段号由上层 ``local_parser`` 用 ``segmenter.sentences``
在服务端同口径下算出，因此这里只需保证「抽出的文字内容与文本顺序正确」即可，
段落粒度只影响目录定位精度。

MOBI 解压算法与本地工具 ``AudioBookLocalTtsTool/app/core/mobitext.py``
及服务端 ``BookTextExtractor.decompressMobiText`` **逐字节一致**，
保证同一本书在客户端/服务端抽出的正文相同，段号与目录坐标才对得上。
"""
from __future__ import annotations

import re
import zipfile
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path
from typing import Dict, List, Optional, Tuple
from urllib.parse import unquote

from app.models.models import TocEntry


class UnsupportedBookError(Exception):
    """电子书格式无法解析（未知后缀、MOBI 损坏/加密、缺少 pypdf 等）。"""


# ===========================================================================
# 通用：文本读取与段落切分
# ===========================================================================

# 编码探测顺序（任务约定）：utf-8 → gbk → latin-1
_ENCODINGS: Tuple[str, ...] = ("utf-8", "gbk", "latin-1")


def read_text_with_encoding_fallback(path: str) -> str:
    """读文本文件，按 ``utf-8 → gbk → latin-1`` 依次尝试解码。

    国内大量 TXT 电子书是 GBK，直接按 UTF-8 解码会整本乱码；latin-1 兜底保证
    「至少能读出来不抛异常」（它把每个字节都映射成一个字符）。剥掉首部可能残留的 BOM。
    """
    raw = Path(path).read_bytes()
    for enc in _ENCODINGS:
        try:
            text = raw.decode(enc)
            if text.startswith("\ufeff"):
                text = text.lstrip("\ufeff")
            return text
        except UnicodeDecodeError:
            continue
    # 理论上到不了这里（latin-1 永不失败），保险再兜一次
    return raw.decode("utf-8", errors="replace")


def _split_paragraphs(text: str) -> List[str]:
    """把连续文本切成自然段：**按空行分段**，丢弃纯空白段。

    刻意不按「单个换行」分段：中文 TXT 常有版面折行（一句被拆成两行），按换行切会把
    句子劈断，破坏 ``segmenter.sentences`` 的整句切分。空行才是可靠的段落边界。
    """
    if not text:
        return []
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    blocks = re.split(r"\n[ \t\u3000]*\n", text)
    paragraphs: List[str] = []
    for block in blocks:
        block = block.strip("\n").strip()
        if block:
            paragraphs.append(block)
    return paragraphs


def _norm_ws(text: str) -> str:
    """去掉所有空白，用于目录标题与段落的模糊匹配（与服务端忽略空白同思路）。"""
    return "".join(ch for ch in text if not ch.isspace())


def _locate_paragraph(paragraphs: List[str], title: str, hint: int = 0) -> int:
    """在 ``paragraphs`` 里按内容定位标题所在的段下标；找不到返回 ``hint`` 处或 0。

    规则与段号定位同源（忽略所有空白后比对）：先整段包含，失败再退前缀锚点。
    ``hint`` 是「大概在该书什么位置」的偏置（多命中时取最近者）。
    """
    query = _norm_ws(title)
    if not paragraphs or not query:
        return 0
    candidates: List[int] = []
    for i, para in enumerate(paragraphs):
        if len(para) >= len(query) and query in _norm_ws(para):
            candidates.append(i)
    if not candidates:
        anchor = query[:8]
        if len(anchor) >= 4:
            for i, para in enumerate(paragraphs):
                if anchor in _norm_ws(para):
                    candidates.append(i)
    if not candidates:
        return max(0, min(hint, len(paragraphs) - 1))
    if len(candidates) == 1:
        return candidates[0]
    return min(candidates, key=lambda idx: abs(idx - hint))


# ===========================================================================
# HTML → 纯文本（EPUB / MOBI 共用）
# ===========================================================================

# 块级标签：遇到它们要断行，否则整章会挤成一大段
_BLOCK_TAGS = {
    "p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
    "section", "article", "blockquote", "pre", "td",
}
# 这些标签的内容不要（脚本/样式会污染正文）
_SKIP_TAGS = {"script", "style", "head", "title"}


class _TextHarvester(HTMLParser):
    """把 xhtml 正文抽成纯文本，块级元素之间补换行。"""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: List[str] = []
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
        raw = re.sub(r"[ \t]+\n", "\n", raw)   # 行尾空白
        raw = re.sub(r"\n{3,}", "\n\n", raw)   # 连续空行压成一段边界
        return raw.strip()


def _html_to_text(html: str) -> str:
    harvester = _TextHarvester()
    try:
        harvester.feed(html)
        harvester.close()
    except Exception:
        # 个别 xhtml 不合法：已抽到的部分照样能用
        pass
    return harvester.text()


_HEADING_RE = re.compile(r"(?is)<h([1-6])[^>]*>(.*?)</h\1>")
_TAG_RE = re.compile(r"(?is)<[^>]+>")


def _html_headings(html: str) -> List[Tuple[int, str]]:
    """从 HTML 里抓出 ``(级别, 标题纯文本)``，供 MOBI 生成目录。"""
    out: List[Tuple[int, str]] = []
    for m in _HEADING_RE.finditer(html):
        level = int(m.group(1))
        title = _TAG_RE.sub("", m.group(2))
        title = re.sub(r"\s+", " ", title).strip()
        if title:
            out.append((level, title))
    return out


# ===========================================================================
# Markdown
# ===========================================================================

_MD_HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
_MD_IMAGE = re.compile(r"!\[([^\]]*)\]\([^)]*\)")
_MD_LINK = re.compile(r"\[([^\]]*)\]\([^)]*\)")
_MD_REF_LINK = re.compile(r"\[([^\]]*)\]\[[^\]]*\]")
_MD_AUTOLINK = re.compile(r"<((?:https?://|mailto:)[^>]+)>")
_MD_INLINE_CODE = re.compile(r"`([^`\n]+)`")
_MD_BOLD3 = re.compile(r"\*\*\*([^*\n]+?)\*\*\*")
_MD_BOLD = re.compile(r"\*\*([^*\n]+?)\*\*")
_MD_ITALIC = re.compile(r"\*([^*\n]+?)\*")
_MD_BOLD_U3 = re.compile(r"___([^_\n]+?)___")
_MD_BOLD_U = re.compile(r"__([^_\n]+?)__")
_MD_STRIKE = re.compile(r"~~([^~\n]+?)~~")
_MD_BLOCKQUOTE = re.compile(r"^>\s?")
_MD_LIST_MARKER = re.compile(r"^(\s*)(?:[-*+]|\d+[.)])\s+")
_MD_HR = re.compile(r"^(\s*)([-*_])(\s*\2){2,}\s*$")
_MD_TABLE_SEP = re.compile(r"^\s*\|?[\s:|-]+\|?\s*$")
_MD_HTML_TAG = re.compile(r"<[^>]+>")


def _strip_md_inline(line: str) -> str:
    """单行 Markdown → 纯文本（行内转换），不改换行、保留前导缩进。

    处理顺序与服务端 ``convertMdInline`` 一致：图片/链接/自动链接 → 引用/列表标记
    → 水平线/表格分隔整行丢弃 → 行内代码 → 粗体/斜体/删除线 → 表格 ``|`` → 残留 HTML 标签。
    """
    s = line
    s = _MD_IMAGE.sub(r"\1", s)
    s = _MD_LINK.sub(r"\1", s)
    s = _MD_REF_LINK.sub(r"\1", s)
    s = _MD_AUTOLINK.sub(r"\1", s)
    s = _MD_BLOCKQUOTE.sub("", s)
    s = _MD_LIST_MARKER.sub(r"\1", s)
    if _MD_HR.match(s) or _MD_TABLE_SEP.match(s):
        return ""
    s = _MD_INLINE_CODE.sub(r"\1", s)
    s = _MD_BOLD3.sub(r"\1", s)
    s = _MD_BOLD.sub(r"\1", s)
    s = _MD_ITALIC.sub(r"\1", s)
    s = _MD_BOLD_U3.sub(r"\1", s)
    s = _MD_BOLD_U.sub(r"\1", s)
    s = _MD_STRIKE.sub(r"\1", s)
    if "|" in s:
        s = s.replace("|", " ")
    s = _MD_HTML_TAG.sub("", s)
    return s


def strip_markdown(text: str) -> str:
    """移除 Markdown 语法，返回纯文本（保留换行结构，围栏代码块内容原样保留）。"""
    if not text:
        return ""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    out_lines: List[str] = []
    in_code = False
    for line in text.split("\n"):
        trimmed = line.strip()
        if trimmed.startswith("```") or trimmed.startswith("~~~"):
            in_code = not in_code
            out_lines.append("")
            continue
        if in_code:
            out_lines.append(line)
            continue
        hm = _MD_HEADING.match(trimmed)
        if hm:
            out_lines.append(_strip_md_inline(hm.group(2).strip()))
        else:
            out_lines.append(_strip_md_inline(line))
    return "\n".join(out_lines)


def _extract_markdown(path: str) -> Tuple[List[str], List[TocEntry]]:
    raw = read_text_with_encoding_fallback(path)
    # 先在原文里按出现顺序抓标题（级别 + 文本），再剥壳
    headings: List[Tuple[int, str]] = []
    in_code = False
    for line in raw.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        t = line.strip()
        if t.startswith("```") or t.startswith("~~~"):
            in_code = not in_code
            continue
        if in_code:
            continue
        hm = _MD_HEADING.match(t)
        if hm:
            title = _strip_md_inline(hm.group(2).strip())
            if title:
                headings.append((len(hm.group(1)), title))
    paragraphs = _split_paragraphs(strip_markdown(raw))
    toc = _build_toc_from_headings(paragraphs, headings)
    return paragraphs, toc


def _build_toc_from_headings(
    paragraphs: List[str], headings: List[Tuple[int, str]]
) -> List[TocEntry]:
    """把 ``(级别, 标题)`` 列表转成 ``TocEntry``，标题定位到其所在段下标。"""
    toc: List[TocEntry] = []
    last_idx = 0
    for level, title in headings:
        idx = _locate_paragraph(paragraphs, title, hint=last_idx)
        last_idx = idx
        toc.append(TocEntry(title=title, level=level, paragraph_index=idx))
    return toc


# ===========================================================================
# TXT
# ===========================================================================

def _extract_txt(path: str) -> Tuple[List[str], List[TocEntry]]:
    raw = read_text_with_encoding_fallback(path)
    return _split_paragraphs(raw), []


# ===========================================================================
# EPUB
# ===========================================================================

def _epub_href_to_id(book, href: str) -> Optional[str]:
    """把 EPUB 目录里的 href（可能带 #fragment / URL 编码）映射到 spine item id。"""
    if not href:
        return None
    target = href.split("#", 1)[0]
    if not target:
        return None
    # 常见 URL 转义
    for esc, ch in (("%20", " "), ("%23", "#"), ("%25", "%")):
        target = target.replace(esc, ch)
    for item_id, _ in book.spine:
        item = book.get_item_with_id(item_id)
        if item is None:
            continue
        if item.get_name() == target or item.get_href() == target:
            return item_id
    return None


def _flatten_epub_toc(raw_toc, level: int = 0) -> List[Tuple[str, int, Optional[str]]]:
    """把 ebooklib 的 toc（Link 或 (Link, [children]) 树）拍平成 ``(title, level, id)``。"""
    flat: List[Tuple[str, int, Optional[str]]] = []
    for entry in raw_toc or []:
        link, children = entry, []
        if isinstance(entry, tuple):
            link = entry[0]
            children = entry[1] if len(entry) > 1 else []
        title = getattr(link, "title", "") or ""
        href = getattr(link, "href", "") or ""
        item_id = href.split("#", 1)[0] or None
        if title:
            flat.append((title.strip(), level, item_id))
        flat.extend(_flatten_epub_toc(children, level + 1))
    return flat


# ---------- 纯标准库 EPUB 兜底解析（无需 ebooklib） ----------

def _epub_localname(tag: str) -> str:
    """去掉 XML 命名空间，取标签本名。"""
    return tag.split("}", 1)[1] if "}" in tag else tag


def _epub_opf_dir(opf_path: str) -> str:
    d = opf_path.rsplit("/", 1)[0]
    return d + "/" if d else ""


def _epub_join_href(opf_dir: str, href: str) -> str:
    """把 OPF/nav 里的相对 href 归一为 zip 内路径（去 fragment、URL 解码、折叠 ./ ../）。"""
    href = unquote((href or "").split("#", 1)[0]).replace("\\", "/")
    if not href:
        return ""
    if href.startswith("/"):
        href = href.lstrip("/")
    else:
        href = opf_dir + href
    parts: List[str] = []
    for seg in href.split("/"):
        if seg in ("", "."):
            continue
        if seg == "..":
            if parts:
                parts.pop()
            continue
        parts.append(seg)
    return "/".join(parts)


def _epub_norm_key(href: str) -> str:
    """用于匹配的路径键（小写、去 fragment/query、去首尾斜杠）。"""
    h = unquote((href or "").split("#", 1)[0]).split("?", 1)[0].replace("\\", "/")
    return h.strip("/").lower()


def _epub_nav_html_pairs(html: str) -> List[Tuple[str, int, str]]:
    """从 EPUB3 的 nav 文档抽取 ``(标题, 层级, href)``（按 <ol>/<ul> 嵌套深度）。"""
    pairs: List[Tuple[str, int, str]] = []
    try:
        root = ET.fromstring(html)
    except Exception:
        root = None
    if root is not None:
        def walk(node, depth):
            for child in list(node):
                ln = _epub_localname(child.tag)
                if ln in ("ol", "ul"):
                    walk(child, depth + 1)
                elif ln == "li":
                    a_href = ""
                    a_text = ""
                    for sub in child.iter():
                        if _epub_localname(sub.tag) == "a":
                            a_href = sub.get("href") or ""
                            a_text = "".join(t or "" for t in sub.itertext()).strip()
                            break
                    if a_href and a_text:
                        pairs.append((a_text, depth, a_href))
                    for sub in child:
                        if _epub_localname(sub.tag) in ("ol", "ul"):
                            walk(sub, depth + 1)
                else:
                    walk(child, depth)
        walk(root, 0)
    if not pairs:  # 极端情况：正则兜底
        for m in re.finditer(r'(?is)<a[^>]*href=["\']([^"\']+)["\'][^>]*>(.*?)</a>', html):
            href = m.group(1)
            title = re.sub(r"(?is)<[^>]+>", "", m.group(2))
            title = re.sub(r"\s+", " ", title).strip()
            if title:
                pairs.append((title, 0, href))
    return pairs


def _extract_epub_stdlib(path: str) -> Tuple[List[str], List[TocEntry]]:
    """不依赖任何第三方库的 EPUB 提取：zip → container.xml → OPF → spine 正文 + NCX/nav 目录。"""
    try:
        zf = zipfile.ZipFile(path)
    except Exception as exc:
        raise UnsupportedBookError(f"EPUB 无法作为 ZIP 打开：{exc}") from exc

    try:
        names = zf.namelist()
        lower_map = {n.lower(): n for n in names}

        # 1) container.xml → OPF 路径
        opf_path = None
        container = lower_map.get("meta-inf/container.xml")
        if container:
            try:
                croot = ET.fromstring(zf.read(container))
                for el in croot.iter():
                    if _epub_localname(el.tag) == "rootfile":
                        opf_path = el.get("full-path")
                        break
            except Exception:
                opf_path = None
        if not opf_path or opf_path not in names:
            cand = [n for n in names if n.lower().endswith(".opf")]
            if not cand:
                raise UnsupportedBookError("EPUB 缺少 OPF 清单文件")
            opf_path = cand[0]

        opf_dir = _epub_opf_dir(opf_path)

        def read_by_href(href: str) -> Optional[bytes]:
            full = _epub_join_href(opf_dir, href)
            if full in names:
                return zf.read(full)
            key = _epub_norm_key(full)
            for n in names:
                if _epub_norm_key(n) == key:
                    return zf.read(n)
            base = key.rsplit("/", 1)[-1]
            for n in names:
                nk = _epub_norm_key(n)
                if nk.endswith("/" + base) or nk == base:
                    return zf.read(n)
            return None

        # 2) OPF：manifest + spine
        try:
            oroot = ET.fromstring(zf.read(opf_path))
        except Exception as exc:
            raise UnsupportedBookError(f"EPUB OPF 解析失败：{exc}") from exc

        manifest: Dict[str, Tuple[str, str]] = {}  # id -> (href, media_type)
        ncx_href = ""
        nav_href = ""
        for el in oroot.iter():
            if _epub_localname(el.tag) == "item":
                mid = el.get("id") or ""
                href = el.get("href") or ""
                mt = (el.get("media-type") or "").lower()
                props = (el.get("properties") or "").lower()
                if mid:
                    manifest[mid] = (href, mt)
                if "ncx" in mt:
                    ncx_href = href
                if "nav" in props:
                    nav_href = href

        spine_ids: List[str] = []
        for el in oroot.iter():
            if _epub_localname(el.tag) == "itemref":
                if (el.get("linear") or "yes").lower() == "no":
                    continue
                rid = el.get("idref") or ""
                if rid in manifest:
                    spine_ids.append(rid)

        # 3) 按 spine 顺序抽正文，并记录每个 item 的起始段下标
        paragraphs: List[str] = []
        item_first_para: Dict[str, int] = {}
        cursor = 0
        for rid in spine_ids:
            href, mt = manifest[rid]
            if mt and ("image" in mt or "css" in mt or "font" in mt or
                       mt.startswith("video") or mt.startswith("audio")):
                item_first_para[rid] = cursor
                continue
            data = read_by_href(href)
            if data is None:
                item_first_para[rid] = cursor
                continue
            text = _html_to_text(data.decode("utf-8", errors="ignore"))
            ps = _split_paragraphs(text)
            item_first_para[rid] = cursor
            paragraphs.extend(ps)
            cursor += len(ps)

        if not paragraphs:
            # 没有 spine 命中时，退而读所有 xhtml/html 成员（保证「至少不空白」）
            for n in names:
                if n.lower().endswith((".xhtml", ".html", ".htm")):
                    text = _html_to_text(zf.read(n).decode("utf-8", errors="ignore"))
                    paragraphs.extend(_split_paragraphs(text))

        # 4) 目录：NCX（EPUB2）优先，退回 nav（EPUB3）
        toc_pairs: List[Tuple[str, int, str]] = []  # (title, level, href)
        if ncx_href:
            ncx_bytes = read_by_href(ncx_href)
            if ncx_bytes:
                try:
                    ncx_dir = _epub_opf_dir(_epub_join_href(opf_dir, ncx_href))
                    nroot = ET.fromstring(ncx_bytes.decode("utf-8", errors="ignore"))

                    def walk_navpoint(np, level):
                        content_src = ""
                        label = ""
                        for c in np:
                            ln = _epub_localname(c.tag)
                            if ln == "content":
                                content_src = c.get("src") or ""
                            elif ln == "navLabel":
                                for t in c.iter():
                                    if _epub_localname(t.tag) == "text":
                                        label = (t.text or "").strip()
                        if content_src and label:
                            toc_pairs.append((label, level,
                                              _epub_join_href(ncx_dir, content_src)))
                        for c in np:
                            if _epub_localname(c.tag) == "navPoint":
                                walk_navpoint(c, level + 1)

                    for el in nroot.iter():
                        if _epub_localname(el.tag) == "navMap":
                            for c in el:
                                if _epub_localname(c.tag) == "navPoint":
                                    walk_navpoint(c, 0)
                            break
                except Exception:
                    pass
        if not toc_pairs and nav_href:
            nav_bytes = read_by_href(nav_href)
            if nav_bytes:
                nav_dir = _epub_opf_dir(_epub_join_href(opf_dir, nav_href))
                raw = _epub_nav_html_pairs(nav_bytes.decode("utf-8", errors="ignore"))
                toc_pairs = [(t, lv, _epub_join_href(nav_dir, h)) for (t, lv, h) in raw]

        # spine item 的路径键 → 起始段下标
        key_to_first: Dict[str, int] = {}
        for rid in spine_ids:
            href, _mt = manifest[rid]
            key_to_first[_epub_norm_key(_epub_join_href(opf_dir, href))] = item_first_para.get(rid, 0)

        toc: List[TocEntry] = []
        last_idx = 0
        for title, level, href in toc_pairs:
            k = _epub_norm_key(href)
            if k in key_to_first:
                idx = key_to_first[k]
            else:
                base = k.rsplit("/", 1)[-1]
                hit = next((v for kk, v in key_to_first.items()
                            if kk.endswith("/" + base) or kk == base), None)
                idx = hit if hit is not None else _locate_paragraph(paragraphs, title, hint=last_idx)
            last_idx = idx
            toc.append(TocEntry(title=title, level=level, paragraph_index=idx))

        if not paragraphs:
            raise UnsupportedBookError("EPUB 未提取到任何正文")
        return paragraphs, toc
    finally:
        zf.close()


def _extract_epub(path: str) -> Tuple[List[str], List[TocEntry]]:
    """EPUB 正文/目录提取。

    优先用 ebooklib（若已安装）；未安装或解析失败时退回内置的纯标准库实现
    （zipfile + XML 解析 OPF/spine/NCX/nav），避免因可选依赖缺失导致整本空白。
    """
    try:
        from ebooklib import epub  # noqa: F401
    except ImportError:
        return _extract_epub_stdlib(path)

    try:
        book = epub.read_epub(path)
    except Exception:
        # ebooklib 存在但该文件解析失败（不规范 OPF 等）：同样退回标准库兜底
        return _extract_epub_stdlib(path)

    paragraphs: List[str] = []
    seen_items: set[str] = set()
    # 按 spine 顺序取章节；顺序错了正文会乱
    for item_id, _ in book.spine:
        if item_id in seen_items:
            continue
        seen_items.add(item_id)
        item = book.get_item_with_id(item_id)
        if item is None:
            continue
        try:
            content = item.get_content()
        except Exception:
            continue
        if not content:
            continue
        html = content.decode("utf-8", errors="ignore")
        text = _html_to_text(html)
        for para in _split_paragraphs(text):
            paragraphs.append(para)

    # 目录：把 toc 拍平，用 href→item 的段落起点 + 标题内容定位双重手段确定段下标
    toc: List[TocEntry] = []
    # 预计算每个 spine item 的起始段下标（按 href 归属）
    item_first_para: Dict[str, int] = {}
    cursor = 0
    for item_id, _ in book.spine:
        item = book.get_item_with_id(item_id)
        if item is None:
            continue
        try:
            content = item.get_content()
        except Exception:
            content = b""
        n_paras = len(_split_paragraphs(_html_to_text((content or b"").decode("utf-8", "ignore"))))
        item_first_para[item_id] = cursor
        cursor += n_paras

    last_idx = 0
    for title, level, href_id in _flatten_epub_toc(book.toc):
        idx: int
        resolved = _epub_href_to_id(book, href_id or "") if href_id else None
        if resolved is not None and resolved in item_first_para:
            idx = item_first_para[resolved]
        else:
            idx = _locate_paragraph(paragraphs, title, hint=last_idx)
        last_idx = idx
        toc.append(TocEntry(title=title, level=level, paragraph_index=idx))
    return paragraphs, toc


# ===========================================================================
# PDF
# ===========================================================================

_PAGE_NUMBER_RE = re.compile(
    r"^\s*[-–—\s]*(\d{1,5}|[IVXLCivxlc]{1,9}|\d{1,3}\s*/\s*\d{1,5})[-–—\s]*\s*$"
)


def _line_signature(line: str) -> str:
    """行「签名」：去掉数字与空白后小写 —— 页码/章节号变动不影响，用于跨页比对。"""
    return re.sub(r"\s+", "", re.sub(r"[0-9０-９]+", "", line)).lower()


def _strip_running_headers_footers(pages: List[str]) -> List[str]:
    """剥离各页重复出现的页眉/页脚与独立页码行。

    思路：对每页取「首行」「末行」的签名（去数字后）跨页统计；某签名在足够多页
    （≥ 35% 且 ≥ 2 页）出现 ⇒ 认定为running页眉/页脚，从各页顶/底删掉。另外任何
    位于页顶/页底、且整行只是页码的行也一并删。只动首/末行，正文中间的相似行不误删。
    """
    n = len(pages)
    if n < 2:
        return pages

    page_lines: List[List[str]] = [p.replace("\r\n", "\n").replace("\r", "\n").split("\n") for p in pages]

    threshold = max(2, int(n * 0.35))
    sig_counts: Dict[str, int] = {}
    for lines in page_lines:
        edges = []
        if lines:
            edges.append(lines[0])
        if len(lines) > 1:
            edges.append(lines[-1])
        for line in edges:
            sig = _line_signature(line)
            if sig:  # 空签名（纯数字/空白行）不参与重复统计，交给页码规则处理
                sig_counts[sig] = sig_counts.get(sig, 0) + 1
    running = {sig for sig, cnt in sig_counts.items() if cnt >= threshold}

    cleaned: List[str] = []
    for lines in page_lines:
        kept = list(lines)
        # 删首部连续的页眉行 / 页码行
        while kept:
            first = kept[0]
            if _PAGE_NUMBER_RE.match(first) or (_line_signature(first) in running and first.strip()):
                kept.pop(0)
            else:
                break
        # 删尾部连续的页脚行 / 页码行
        while kept:
            last = kept[-1]
            if _PAGE_NUMBER_RE.match(last) or (_line_signature(last) in running and last.strip()):
                kept.pop()
            else:
                break
        cleaned.append("\n".join(kept))
    return cleaned


def _flatten_pdf_outline(outline, reader, depth: int = 0) -> List[Tuple[int, str, int]]:
    """把 pypdf 的 outline 拍平成 ``(depth, title, page_index0)``。"""
    flat: List[Tuple[int, str, int]] = []
    for item in outline or []:
        if isinstance(item, list):
            flat.extend(_flatten_pdf_outline(item, reader, depth + 1))
            continue
        title = ""
        page_index = -1
        try:
            title = getattr(item, "title", "") or ""
        except Exception:
            title = ""
        try:
            page_index = reader.get_destination_page_number(item)
        except Exception:
            page_index = -1
        if title and title.strip():
            flat.append((depth, title.strip(), page_index))
    return flat


def _extract_pdf(path: str) -> Tuple[List[str], List[TocEntry]]:
    try:
        from pypdf import PdfReader
    except ImportError as exc:
        raise UnsupportedBookError(
            "解析 PDF 需要 pypdf，请先执行：pip install pypdf"
        ) from exc

    reader = PdfReader(path)
    raw_pages: List[str] = []
    for page in reader.pages:
        try:
            raw_pages.append(page.extract_text() or "")
        except Exception:
            raw_pages.append("")
    cleaned_pages = _strip_running_headers_footers(raw_pages)

    # 逐页切段并记录「页 → 该页首个段下标」，供目录（PDF 只有页号）映射
    paragraphs: List[str] = []
    page_first_para: Dict[int, int] = {}
    for page_index, page_text in enumerate(cleaned_pages):
        page_first_para[page_index] = len(paragraphs)
        paragraphs.extend(_split_paragraphs(page_text))

    if not paragraphs:
        raise UnsupportedBookError("PDF 未提取到任何文本（可能是扫描件/图片型 PDF）")

    toc: List[TocEntry] = []
    try:
        outline = reader.outline
    except Exception:
        outline = []
    for depth, title, page_index in _flatten_pdf_outline(outline, reader):
        idx = page_first_para.get(page_index, 0) if page_index >= 0 else 0
        toc.append(TocEntry(title=title, level=depth, paragraph_index=idx))
    return paragraphs, toc


# ===========================================================================
# MOBI / AZW —— PalmDOC LZ77（对齐 mobitext.py 与服务端）
# ===========================================================================

PDB_HEADER_SIZE = 78          # PDB 头（含末尾 2 字节记录数）至少 78 字节
DEFAULT_RECORD_SIZE = 4096    # 单条文本记录解压后的上限（record0 的 recordSize）


def _be_uint16(data: bytes, offset: int) -> int:
    return (data[offset] << 8) | data[offset + 1]


def _be_uint32(data: bytes, offset: int) -> int:
    return (
        (data[offset] << 24) | (data[offset + 1] << 16)
        | (data[offset + 2] << 8) | data[offset + 3]
    )


def palmdoc_decompress(data: bytes, max_output: Optional[int] = None) -> bytes:
    """PalmDOC LZ77 解压。

    码表：
        0x00        → 字面量 0x00
        0x01-0x08   → 从输入流复制 byte 个字面量字节
        0x09-0x7F   → 字面量字节
        0x80-0xBF   → 两字节：distance=(pair>>3)&0x7FF，length=(pair&7)+3
        0xC0-0xFF   → 空格(0x20) + (byte XOR 0x80)

    回引要**逐字节从活动缓冲读**（距离小于长度时会重叠，快照式回读会复制错）。
    ``max_output``：最多产出多少字节（MOBI 一条文本记录解压后不超过 recordSize）。
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
            out += data[pos: pos + copy_len]
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
    """把 MOBI 的正文记录解压成 HTML 字节数组。失败抛 :class:`UnsupportedBookError`。

    踩过的坑（与 mobitext.py 一致）：

    * PDB 头只跳 **76** 字节读记录数（UInt16 BE），游标正好落在 78（记录表起点）；
    * MOBI 记录头是 **big-endian**（与 PalmDOC 的 little-endian 相反）；
    * 每条文本记录解压后不超过 recordSize（压缩流尾部有填充字节，不限长会多解）；
    * 正文总长按 record0 的 textLength 截断（后面可能还挂着目录/封面标记）。
    """
    if raw is None or len(raw) < PDB_HEADER_SIZE:
        raise UnsupportedBookError("MOBI 文件过小，无法解析")

    num_records = _be_uint16(raw, 76)
    if num_records < 2:
        raise UnsupportedBookError("MOBI 记录数过少：%d" % num_records)

    offsets: List[int] = []
    pos = PDB_HEADER_SIZE
    for _ in range(num_records):
        if pos + 8 > len(raw):
            raise UnsupportedBookError("MOBI 记录信息表被截断")
        offsets.append(_be_uint32(raw, pos))
        pos += 8  # offset(4) + attributes(1) + uniqueID(3)

    for index, offset in enumerate(offsets):
        if offset < 0 or offset > len(raw):
            raise UnsupportedBookError("MOBI 记录偏移异常：offsets[%d]=%d" % (index, offset))
        if index > 0 and offset < offsets[index - 1]:
            raise UnsupportedBookError("MOBI 记录偏移非单调：offsets[%d]" % index)

    if offsets[1] <= offsets[0]:
        raise UnsupportedBookError("MOBI record0 偏移非法")

    record0 = raw[offsets[0]: offsets[1]]
    if len(record0) < 16:
        raise UnsupportedBookError("MOBI PalmDOC 头部过短")

    compression = _be_uint16(record0, 0)
    text_length = _be_uint32(record0, 4)
    record_count = _be_uint16(record0, 8)
    record_size = _be_uint16(record0, 10)

    if compression not in (1, 2):
        raise UnsupportedBookError(
            "MOBI 压缩类型无效：%d（若是 DRM 加密的 AZW3，请先解密）" % compression
        )

    if record_count > num_records - 1:
        record_count = num_records - 1
    if record_count < 1:
        raise UnsupportedBookError("MOBI 文本记录数为 0")
    if record_size <= 0:
        record_size = DEFAULT_RECORD_SIZE

    chunks: List[bytes] = []
    for index in range(1, record_count + 1):
        if index + 1 < num_records:
            size = offsets[index + 1] - offsets[index]
        else:
            size = len(raw) - offsets[index]
        if size <= 0:
            continue
        record = raw[offsets[index]: offsets[index] + size]
        if compression == 2:
            chunks.append(palmdoc_decompress(record, max_output=record_size))
        else:
            chunks.append(record[:record_size])
    if not chunks:
        raise UnsupportedBookError("MOBI 没有可用的文本记录")

    text = b"".join(chunks)
    if 0 < text_length <= len(text):
        text = text[:text_length]
    return text


def _decode_mobi_bytes(data: bytes) -> str:
    """解码正文：优先 UTF-8（服务端同口径），失败退 cp1252 / latin-1。"""
    for encoding in ("utf-8", "cp1252", "latin-1"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def _extract_mobi(path: str) -> Tuple[List[str], List[TocEntry]]:
    """MOBI / AZW：解压出 HTML，再走与 EPUB 同一套 HTML 清洗。"""
    raw = Path(path).read_bytes()
    decompressed = decompress_mobi_text(raw)
    if not decompressed:
        raise UnsupportedBookError("MOBI 解压后没有内容")
    html = _decode_mobi_bytes(decompressed)

    paragraphs = _split_paragraphs(_html_to_text(html))
    headings = _html_headings(html)
    toc = _build_toc_from_headings(paragraphs, headings)
    return paragraphs, toc


# ===========================================================================
# 对外统一入口
# ===========================================================================

def extract_text(file_path: str) -> Tuple[List[str], List[TocEntry]]:
    """提取电子书正文，返回 ``(paragraphs, toc)``：段落列表 + 目录条目。

    按文件后缀分派。失败抛异常（``UnsupportedBookError`` / ``FileNotFoundError``），
    不吞异常 ——「这本书读不出来」必须让上层看见，否则会表现成「正文是空的」。
    """
    book_path = Path(file_path)
    if not book_path.is_file():
        raise FileNotFoundError(f"电子书不存在：{book_path}")

    suffix = book_path.suffix.lower().lstrip(".")
    if suffix in ("txt", "text"):
        return _extract_txt(str(book_path))
    if suffix in ("md", "markdown", "mkd"):
        return _extract_markdown(str(book_path))
    if suffix == "epub":
        return _extract_epub(str(book_path))
    if suffix == "pdf":
        return _extract_pdf(str(book_path))
    if suffix in ("mobi", "azw", "azw3", "prc"):
        return _extract_mobi(str(book_path))
    # 未知后缀：按纯文本试一次，能读出来就用
    try:
        return _extract_txt(str(book_path))
    except Exception as exc:
        raise UnsupportedBookError(
            f"不支持的电子书格式：{suffix or '（无后缀）'}"
        ) from exc
