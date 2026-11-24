"""EPUB 的读与写。

只做我们真正需要的事：
  * 按 ``container.xml → OPF → spine`` 拿到**真实的阅读顺序**（很多书的文件名并不是顺序的）；
  * 从 OPF 里取书名、作者、语言；
  * 写回时保持 EPUB 2 的老结构（mimetype 不压缩塞第一个），把改过的页面替换掉，
    被整页删掉的章节同步从 manifest / spine / NCX 里剪掉，保证阅读器不会翻到空白页。
"""

from __future__ import annotations

import posixpath
import re
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path

CONTAINER_PATH = "META-INF/container.xml"
MIMETYPE = "application/epub+zip"

_ATTR_RE = re.compile(r'([\w:-]+)\s*=\s*"([^"]*)"')
_ITEM_RE = re.compile(r"<item\b([^>]*?)/?>", re.S)
_ITEMREF_RE = re.compile(r"<itemref\b([^>]*?)/?>", re.S)


def _attrs(s: str) -> dict[str, str]:
    return {k.lower(): v for k, v in _ATTR_RE.findall(s)}


@dataclass
class ManifestItem:
    id: str
    href: str
    media_type: str
    properties: str = ""

    @property
    def is_xhtml(self) -> bool:
        mt = self.media_type.lower()
        return mt in ("application/xhtml+xml", "text/html") or self.href.lower().endswith(
            (".xhtml", ".html", ".htm")
        )

    @property
    def is_nav(self) -> bool:
        return "nav" in self.properties.split()


class EpubBook:
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self._zip = zipfile.ZipFile(self.path, "r")
        self.names = self._zip.namelist()
        self._name_set = set(self.names)

        self.opf_path = self._find_opf()
        self.opf_dir = posixpath.dirname(self.opf_path)
        raw = self._zip.read(self.opf_path).decode("utf-8", "replace")
        self.opf_xml = raw

        self.meta = self._read_metadata(raw)
        self.manifest: list[ManifestItem] = self._read_manifest(raw)
        self.spine_ids: list[str] = self._read_spine(raw)
        self.toc_href = self._find_ncx(raw)

        self._by_id = {m.id: m for m in self.manifest}

    # ---- 基础 --------------------------------------------------------
    def close(self) -> None:
        self._zip.close()

    def __enter__(self) -> "EpubBook":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def _find_opf(self) -> str:
        try:
            xml = self._zip.read(CONTAINER_PATH).decode("utf-8", "replace")
        except KeyError:
            xml = ""
        m = re.search(r'full-path="([^"]+)"', xml)
        if m and m.group(1) in self._name_set:
            return m.group(1)
        # 兜底：找第一个 .opf
        for n in self.names:
            if n.lower().endswith(".opf"):
                return n
        raise ValueError(f"{self.path.name} 里找不到 OPF，可能不是有效的 EPUB")

    def _read_metadata(self, xml: str) -> dict[str, str]:
        def grab(tag: str) -> str:
            m = re.search(rf"<(?:dc:)?{tag}\b[^>]*>(.*?)</(?:dc:)?{tag}>", xml, re.S | re.I)
            return re.sub(r"<[^>]+>", "", m.group(1)).strip() if m else ""

        return {
            "title": grab("title"),
            "creator": grab("creator"),
            "publisher": grab("publisher"),
            "language": grab("language"),
            "identifier": grab("identifier"),
            "description": grab("description"),
        }

    def _read_manifest(self, xml: str) -> list[ManifestItem]:
        out: list[ManifestItem] = []
        for m in _ITEM_RE.finditer(xml):
            a = _attrs(m.group(1))
            if "href" not in a or "id" not in a:
                continue
            out.append(
                ManifestItem(
                    id=a["id"],
                    href=a["href"],
                    media_type=a.get("media-type", ""),
                    properties=a.get("properties", ""),
                )
            )
        return out

    def _read_spine(self, xml: str) -> list[str]:
        m = re.search(r"<spine\b[^>]*>(.*?)</spine>", xml, re.S)
        if not m:
            return []
        return [a["idref"] for a in (_attrs(x.group(1)) for x in _ITEMREF_RE.finditer(m.group(1)))
                if "idref" in a]

    def _find_ncx(self, xml: str) -> str:
        m = re.search(r"<spine\b[^>]*\btoc\s*=\s*\"([^\"]+)\"", xml)
        if m:
            item = self._by_id_any(m.group(1))
            if item:
                return item.href
        for item in self._read_manifest(xml):
            if item.media_type == "application/x-dtbncx+xml" or item.href.lower().endswith(".ncx"):
                return item.href
        return ""

    def _by_id_any(self, item_id: str) -> ManifestItem | None:
        for item in self.manifest:
            if item.id == item_id:
                return item
        return None

    # ---- 访问 --------------------------------------------------------
    @property
    def pages(self) -> list[ManifestItem]:
        """按 spine 顺序的正文页。"""
        out: list[ManifestItem] = []
        seen: set[str] = set()
        for sid in self.spine_ids:
            item = self._by_id.get(sid)
            if item and item.is_xhtml and item.id not in seen:
                seen.add(item.id)
                out.append(item)
        return out

    @property
    def title(self) -> str:
        return self.meta.get("title") or self.path.stem

    def internal_path(self, href: str) -> str:
        return posixpath.normpath(posixpath.join(self.opf_dir, href)) if self.opf_dir else href

    def exists(self, href: str) -> bool:
        return self.internal_path(href) in self._name_set

    def read_text(self, href: str) -> str:
        return self._zip.read(self.internal_path(href)).decode("utf-8", "replace")

    def read_raw(self, internal_name: str) -> bytes:
        return self._zip.read(internal_name)

    def drop_xhtml_files(self) -> set[str]:
        """除正文页以外的 xhtml（比如 nav.xhtml），写回时要特殊保留。"""
        pages = {p.id for p in self.pages}
        return {m.href for m in self.manifest if m.is_xhtml and m.id not in pages}


# ----------------------------------------------------------------------
# 写
# ----------------------------------------------------------------------
class EpubWriter:
    """把改写后的页面写成一个新的 EPUB。"""

    def __init__(self, book: EpubBook, out_path: Path, title: str) -> None:
        self.book = book
        self.out_path = Path(out_path)
        self.title = title

    def write(self, pages: dict[str, str], dropped_hrefs: set[str]) -> Path:
        """pages: ``{href: 新 xhtml 源码}``；dropped_hrefs: 整页丢弃的 href。"""
        book = self.book
        new_uid = f"urn:uuid:{uuid.uuid4()}"
        opf = self._rewrite_opf(new_uid, dropped_hrefs)
        dropped_internal = {book.internal_path(h) for h in dropped_hrefs}

        self.out_path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(self.out_path, "w", zipfile.ZIP_DEFLATED) as z:
            # EPUB 铁律：mimetype 必须是第一个条目且不压缩
            info = zipfile.ZipInfo("mimetype")
            info.compress_type = zipfile.ZIP_STORED
            z.writestr(info, MIMETYPE)

            z.writestr(CONTAINER_PATH, self._container_xml())

            skip_internal = set(dropped_internal)
            skip_internal.add(book.opf_path)
            skip_internal.add("mimetype")          # 已经在最前面手写过，别再写一遍
            if book.toc_href:
                skip_internal.add(book.internal_path(book.toc_href))
            skip_internal.update(book.internal_path(h) for h in pages)

            for name in book.names:
                if name in skip_internal or name == CONTAINER_PATH:
                    continue
                z.writestr(name, book.read_raw(name))

            z.writestr(book.opf_path, opf)

            if book.toc_href and book.exists(book.toc_href):
                ncx = self._rewrite_ncx(new_uid, dropped_hrefs)
                if ncx is not None:
                    z.writestr(book.internal_path(book.toc_href), ncx)

            for href, src in pages.items():
                z.writestr(book.internal_path(href), src)

        return self.out_path

    # ---- OPF --------------------------------------------------------
    def _container_xml(self) -> str:
        return (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">\n'
            "  <rootfiles>\n"
            f'    <rootfile full-path="{self.book.opf_path}" '
            'media-type="application/oebps-package+xml"/>\n'
            "  </rootfiles>\n"
            "</container>\n"
        )

    def _rewrite_opf(self, new_uid: str, dropped_hrefs: set[str]) -> str:
        xml = self.book.opf_xml
        book = self.book

        xml = re.sub(
            r"(<(?:dc:)?title\b[^>]*>).*?(</(?:dc:)?title>)",
            lambda m: m.group(1) + _xml_escape(self.title) + m.group(2),
            xml, count=1, flags=re.S,
        )
        xml = re.sub(
            r"(<(?:dc:)?identifier\b[^>]*>).*?(</(?:dc:)?identifier>)",
            lambda m: m.group(1) + new_uid + m.group(2),
            xml, count=1, flags=re.S,
        )

        note = f"有声化精简版：删去代码清单、命令输出与图表，改写为文字说明。据《{book.title}》整理。"
        if re.search(r"<(?:dc:)?description\b", xml, re.I):
            xml = re.sub(
                r"(<(?:dc:)?description\b[^>]*>).*?(</(?:dc:)?description>)",
                lambda m: m.group(1) + _xml_escape(note) + m.group(2),
                xml, count=1, flags=re.S,
            )
        else:
            xml = xml.replace(
                "</metadata>",
                f"    <dc:description>{_xml_escape(note)}</dc:description>\n   </metadata>",
                1,
            )

        # manifest：去掉整页丢弃的
        if dropped_hrefs:
            keep_ids = {m.id for m in book.manifest if m.href not in dropped_hrefs}
            xml = self._filter_items(xml, keep_ids)
            xml = re.sub(
                r"<itemref\b[^>]*?/>",
                lambda m: m.group(0) if _attrs(m.group(0)).get("idref") in keep_ids else "",
                xml,
            )
        return xml

    def _filter_items(self, xml: str, keep_ids: set[str]) -> str:
        def repl(m: re.Match) -> str:
            a = _attrs(m.group(1))
            if "id" in a and a["id"] not in keep_ids:
                return ""
            return m.group(0)

        return _ITEM_RE.sub(repl, xml)

    # ---- NCX --------------------------------------------------------
    def _rewrite_ncx(self, new_uid: str, dropped_hrefs: set[str]) -> str | None:
        book = self.book
        try:
            xml = book.read_text(book.toc_href)
        except (KeyError, OSError):
            return None
        if not xml.strip():
            return None

        xml = re.sub(
            r'(<meta\s+name="dtb:uid"\s+content=")[^"]*(")',
            lambda m: m.group(1) + new_uid + m.group(2),
            xml, count=1,
        )
        xml = re.sub(
            r"(<docTitle>\s*<text>).*?(</text>)",
            lambda m: m.group(1) + _xml_escape(self.title) + m.group(2),
            xml, count=1, flags=re.S,
        )

        if not dropped_hrefs:
            return xml

        dropped = {posixpath.basename(h) for h in dropped_hrefs}
        return _prune_navpoints(xml, dropped)


def _xml_escape(s: str) -> str:
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


_NAV_TOKEN = re.compile(r"<navPoint\b[^>]*>|</navPoint>|<content\b[^>]*?/?>")


def _prune_navpoints(xml: str, dropped_basenames: set[str]) -> str:
    """从 NCX 里摘掉指向已删文件的 navPoint（含其子节点）。

    用栈扫一遍得到每个 navPoint 的区间和它子树里的所有 content src，
    子树内容全部指向被删文件时才整体删除 —— 目录节点可能指向一个汇总页，
    子节点指向被删页，这种父节点要留着。
    """
    stack: list[int] = []
    spans: list[tuple[int, int, set[str]]] = []
    open_spans: dict[int, set[str]] = {}

    for m in _NAV_TOKEN.finditer(xml):
        tok = m.group(0)
        if tok.startswith("</navPoint"):
            if not stack:
                continue
            start = stack.pop()
            srcs = open_spans.pop(start, set())
            if stack:  # 把子节点的 src 冒泡给父节点
                open_spans[stack[-1]] |= srcs
            spans.append((start, m.end(), srcs))
        elif tok.startswith("<navPoint"):
            stack.append(m.start())
            open_spans[m.start()] = set()
        elif tok.startswith("<content"):
            a = _attrs(tok)
            src = a.get("src", "")
            if stack and src:
                base = posixpath.basename(src.split("#")[0])
                open_spans[stack[-1]].add(base)

    to_remove = [
        (s, e) for s, e, srcs in spans
        if srcs and all(posixpath.basename(s) in dropped_basenames for s in srcs)
    ]
    for start, end in sorted(to_remove, reverse=True):
        xml = xml[:start] + xml[end:]
    return xml
