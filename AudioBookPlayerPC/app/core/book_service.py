"""书架 / 书籍管理编排。

负责「导入 → 落库 → 打开解析 → 删除 / 重排 / 书城下载」这条主干，
把散落在 UI、网络、数据库、解析器之间的动作收拢到一处。

## 对 app.parser 的依赖（本模块的解析主力）

正文抽取与切句按架构约定由 ``app.parser`` 提供，本模块优先调用它们：

  - ``app.parser.local_parser.parse_book(path) -> ParseResult``
        一次给出「阅读行（一句一行，带 segment_number）+ 目录 + 段落」；
  - ``app.parser.segmenter.sentences / sub_pieces / has_speakable_content``
        与服务端 ``TextSegmenter`` 严格同口径；
  - ``app.parser.booktext.extract_text(path) -> (paragraphs, toc)``。

为避免「队友还没写完 parser 时本模块就 import 失败」，所有引用都在函数内 **惰性 import**，
取不到时退回本文件内置的最小实现（txt/md + zipfile-EPUB + pypdf-PDF），保证
book_service / tts_local 在任何情况下都能 import 且能跑。
"""
from __future__ import annotations

import re
import shutil
import zipfile
from dataclasses import dataclass, field
from html.parser import HTMLParser
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

from app.core.api_client import ApiClient, ApiException
from app.core.config import AppConfig
from app.db.database import LocalDatabase
from app.models.models import Book, LineItem, Task, TocEntry

# 支持的电子书后缀（与 BookCategory / Android 端一致）
SUPPORTED_EXT = {"txt", "md", "markdown", "epub", "pdf", "mobi", "azw", "azw3", "prc"}

# 编码探测顺序：中文电子书常见 UTF-8 / BOM / GBK(GB18030) / Big5
_ENCODINGS = ("utf-8-sig", "utf-8", "gb18030", "big5", "latin-1")


class BookParseError(RuntimeError):
    """无法从文件中抽取正文（格式不支持、损坏、扫描版 PDF 等）。"""


@dataclass
class ParseResult:
    """打开一本书的解析结果：阅读行 + 目录 + 音频片段文本。

    - lines：阅读页显示粒度（一句一行，LineItem.segment_number 即音频段号）
    - toc：目录（TocEntry.paragraph_index 是「行下标」）
    - segments：与服务端口径一致的音频片段文本（每段一句），段号 = 下标+1
    """
    lines: List[LineItem] = field(default_factory=list)
    toc: List[TocEntry] = field(default_factory=list)
    segments: List[str] = field(default_factory=list)
    book: Optional[Book] = None
    raw_text: str = ""


# ==========================================================================
# 切句器：优先 app.parser.segmenter，缺失时退回本地实现
# ==========================================================================

_TAIL_CHARS = "[" + re.escape(
    "\t\n\x0B\f\r " + "」』”’)]）】》"
) + "]*"
_SENTENCE_END = re.compile(
    "(?:[。！？]|[!?]|(?<![.])(?<![0-9])[.](?![.])(?![0-9]))" + _TAIL_CHARS
)
_SUB_PIECE_MAX = 45
_SUB_PIECE_MIN_TAIL = 4


def _clean_inner_newlines(s: str) -> str:
    if "\n" not in s and "\r" not in s:
        return s
    s = s.replace("\r\n", "\n").replace("\r", "\n")
    s = re.sub(r"(?<=[0-9A-Za-z])[ \t\u3000]*\n[ \t\u3000]*(?=[0-9A-Za-z])", " ", s)
    s = re.sub(r"[ \t\u3000]*\n[ \t\u3000]*", "", s)
    return s


def _fallback_sentences(text: Optional[str]) -> List[str]:
    if not text:
        return []
    result: List[str] = []
    start = 0
    for m in _SENTENCE_END.finditer(text):
        piece = text[start:m.end()].strip()
        if piece:
            result.append(_clean_inner_newlines(piece))
        start = m.end()
    tail = text[start:].strip()
    if tail:
        result.append(_clean_inner_newlines(tail))
    return result


def _fallback_sub_pieces(sentence: str, max_length: int = _SUB_PIECE_MAX) -> List[str]:
    s = (sentence or "").strip()
    if not s:
        return []
    if len(s) <= max_length:
        return [s]
    pieces: List[str] = []
    rest = s
    while rest:
        if len(rest) <= max_length:
            pieces.append(rest)
            break
        cut = max_length
        floor = int(max_length * 0.6)
        for i in range(min(max_length, len(rest)) - 1, floor - 1, -1):
            if rest[i] in "，、；：,;":
                cut = i + 1
                break
        pieces.append(rest[:cut])
        rest = rest[cut:]
    if len(pieces) > 1 and len(pieces[-1]) < _SUB_PIECE_MIN_TAIL:
        pieces[-2] += pieces[-1]
        pieces.pop()
    return pieces


def _fallback_has_speakable(text: str) -> bool:
    return any(ch.isalnum() for ch in (text or ""))


def get_segmenter() -> Tuple[Callable[[Optional[str]], List[str]],
                             Callable[[str], List[str]],
                             Callable[[str], bool]]:
    """返回 (sentences, sub_pieces, has_speakable)。

    优先用 ``app.parser.segmenter``（与服务端严格对齐），缺失时退回本地实现。
    tts_local / open_book 共用这一入口，保证切句口径一致。
    """
    try:
        from app.parser import segmenter  # type: ignore
        # 该模块的谓词叫 has_speakable_content，这里统一对外叫 has_speakable
        return (segmenter.sentences, segmenter.sub_pieces,
                getattr(segmenter, "has_speakable_content",
                        getattr(segmenter, "has_readable_content", _fallback_has_speakable)))
    except Exception:
        return _fallback_sentences, _fallback_sub_pieces, _fallback_has_speakable


# ==========================================================================
# 正文抽取 / 解析：优先 app.parser，缺失时退回本地
# ==========================================================================

_BLOCK_TAGS = {"p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
               "section", "article", "blockquote", "pre", "td"}
_SKIP_TAGS = {"script", "style", "head", "title"}


class _TextHarvester(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: List[str] = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        tag = tag.lower()
        if tag in _SKIP_TAGS:
            self._skip += 1
        elif tag in _BLOCK_TAGS:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        tag = tag.lower()
        if tag in _SKIP_TAGS:
            self._skip = max(0, self._skip - 1)
        elif tag in _BLOCK_TAGS:
            self.parts.append("\n")

    def handle_data(self, data):
        if self._skip == 0:
            self.parts.append(data)

    def text(self) -> str:
        raw = "".join(self.parts)
        raw = re.sub(r"[ \t]+\n", "\n", raw)
        raw = re.sub(r"\n{3,}", "\n\n", raw)
        return raw.strip()


def _html_to_text(html_str: str) -> str:
    h = _TextHarvester()
    try:
        h.feed(html_str)
        h.close()
    except Exception:
        pass
    return h.text()


def _read_text_file(path: Path) -> str:
    raw = path.read_bytes()
    for enc in _ENCODINGS:
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="ignore")


def _natural_key(name: str) -> tuple:
    return tuple(int(p) if p.isdigit() else p.lower() for p in re.split(r"(\d+)", name))


def _is_epub_resource(name: str) -> bool:
    lower = name.lower()
    if lower.startswith("meta-inf/"):
        return True
    return lower.endswith((
        ".opf", ".ncx", ".css", ".js", ".json", ".png", ".jpg", ".jpeg", ".gif",
        ".svg", ".webp", ".ttf", ".otf", ".woff", ".woff2", ".mp3", ".mp4", ".m4a",
    ))


def _extract_epub(path: Path) -> str:
    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
        order = _epub_spine_order(zf, names)
        entries = [n for n in order if not _is_epub_resource(n)]
        if not entries:
            entries = sorted((n for n in names if not _is_epub_resource(n)), key=_natural_key)
        chunks: List[str] = []
        for name in entries:
            try:
                text = _html_to_text(zf.read(name).decode("utf-8", errors="ignore"))
            except KeyError:
                continue
            if text:
                chunks.append(text)
    return "\n\n".join(chunks)


def _epub_spine_order(zf: zipfile.ZipFile, names: List[str]) -> List[str]:
    opf_name = None
    if "META-INF/container.xml" in names:
        try:
            container = zf.read("META-INF/container.xml").decode("utf-8", errors="ignore")
            m = re.search(r"full-path\s*=\s*[\"']([^\"']+)[\"']", container)
            if m:
                opf_name = m.group(1)
        except Exception:
            opf_name = None
    if opf_name is None:
        cands = [n for n in names if n.lower().endswith(".opf")]
        opf_name = sorted(cands)[0] if cands else None
    if not opf_name:
        return []
    try:
        opf = zf.read(opf_name).decode("utf-8", errors="ignore")
    except KeyError:
        return []
    base = opf_name.rsplit("/", 1)[0] if "/" in opf_name else ""
    manifest: Dict[str, str] = {}
    for item in re.findall(r"<item\b[^>]*/?>", opf, flags=re.IGNORECASE):
        iid = re.search(r"\bid\s*=\s*[\"']([^\"']+)[\"']", item)
        href = re.search(r"\bhref\s*=\s*[\"']([^\"']+)[\"']", item)
        if iid and href:
            manifest[iid.group(1)] = href.group(1)
    ordered: List[str] = []
    for ref in re.findall(r"<itemref\b[^>]*/?>", opf, flags=re.IGNORECASE):
        idref = re.search(r"\bidref\s*=\s*[\"']([^\"']+)[\"']", ref)
        if not idref:
            continue
        href = manifest.get(idref.group(1))
        if not href:
            continue
        p = (f"{base}/{href}" if base else href).replace("../", "")
        if p in names and p not in ordered:
            ordered.append(p)
    return ordered


def _extract_pdf(path: Path) -> str:
    try:
        from pypdf import PdfReader  # type: ignore
    except ImportError as exc:
        raise BookParseError("解析 PDF 需要 pypdf：pip install pypdf") from exc
    reader = PdfReader(str(path))
    pages: List[str] = []
    for page in reader.pages:
        try:
            pages.append(page.extract_text() or "")
        except Exception:
            pages.append("")
    return "\n\n".join(pages).strip()


def _extract_book_text_local(path: Path) -> str:
    """内置兜底正文抽取（仅 txt/md/epub/pdf；mobi 交 app.parser）。"""
    suffix = path.suffix.lower().lstrip(".")
    if suffix in ("txt", "md", "markdown", "text"):
        return _read_text_file(path)
    if suffix == "epub":
        return _extract_epub(path)
    if suffix == "pdf":
        return _extract_pdf(path)
    if suffix in ("mobi", "azw", "azw3", "prc"):
        raise BookParseError(f"内置兜底不解析 .{suffix}，需 app.parser")
    try:
        return _read_text_file(path)
    except Exception as exc:
        raise BookParseError(f"不支持的电子书格式：{suffix or '（无后缀）'}") from exc


def extract_book_text(path: str | Path) -> str:
    """抽取电子书正文为纯文本。

    优先 ``app.parser.booktext.extract_text``（返回 ``(paragraphs, toc)``），把段落
    用空行拼成整篇文本；取不到时用本文件内置实现兜底。
    """
    p = Path(path)
    if not p.is_file():
        raise BookParseError(f"电子书不存在：{p}")
    try:
        from app.parser import booktext  # type: ignore
        paragraphs, _toc = booktext.extract_text(str(p))
        if paragraphs:
            return "\n\n".join(paragraphs)
    except Exception:
        pass
    return _extract_book_text_local(p)


def parse_book_lines(path: str | Path) -> Tuple[List[LineItem], List[TocEntry], List[str]]:
    """用 ``app.parser.local_parser.parse_book`` 得到（阅读行, 目录, 片段文本）。

    每行即一个句子，LineItem.segment_number 与服务端音频段号严格 1:1。
    parse_book 不可用时，退回「extract_book_text + 切句」自拼（口径尽量一致）。
    """
    p = Path(path)
    try:
        from app.parser.local_parser import parse_book  # type: ignore
        pr = parse_book(str(p))
        segments = [li.text for li in pr.lines]
        return pr.lines, pr.toc, segments
    except Exception:
        text = extract_book_text(p)
        sentences, _sub, _has = get_segmenter()
        segs = sentences(text)
        lines = [LineItem(index=i, text=s, segment_number=i + 1)
                 for i, s in enumerate(segs)]
        return lines, [], segs


def get_book_sentences(path: str | Path) -> List[str]:
    """整本书的音频片段文本（每段一句，与服务端口径一致）。"""
    return parse_book_lines(path)[2]


def book_title_from_path(path: str | Path) -> str:
    return Path(path).stem


# ==========================================================================
# BookService
# ==========================================================================

class BookService:
    """书籍管理编排。"""

    def __init__(self, config: AppConfig, api: ApiClient, db: LocalDatabase):
        self.config = config
        self.api = api
        self.db = db

    # ---------- 导入 ----------

    def import_book(self, file_path: str) -> Book:
        """校验 + 拷贝到 books_dir + 落库。"""
        src = Path(file_path)
        if not src.is_file():
            raise BookParseError(f"文件不存在：{file_path}")
        ext = src.suffix.lower().lstrip(".")
        if ext and ext not in SUPPORTED_EXT:
            raise BookParseError(f"不支持的格式：.{ext}")

        # 尽早暴露「扫描版 PDF / 加密 mobi / 空正文」
        try:
            segs = get_book_sentences(src)
        except BookParseError:
            raise
        except Exception as exc:
            raise BookParseError(f"解析失败：{exc}") from exc
        if not segs:
            raise BookParseError("正文为空（可能是扫描版 PDF 或加密文件）")

        dst = self._unique_target(src)
        try:
            shutil.copyfile(src, dst)
        except OSError as exc:
            raise BookParseError(f"复制到书架失败：{exc}") from exc

        books = self.db.get_books()
        book = Book(
            display_name=src.stem,
            file_path=str(dst),
            file_ext=ext,
            has_audio=False,
            category=ext,
            sort_order=max((b.sort_order for b in books), default=0) + 1,
        )
        book.id = self.db.add_book(book)
        return book

    def import_from_url(self, url: str) -> Book:
        """从 http(s) URL 下载电子书再导入。"""
        if not url.lower().startswith(("http://", "https://")):
            raise BookParseError("仅支持 http/https 链接")
        import requests

        name = Path(url.split("?")[0]).name or "downloaded_book"
        tmp = self.config.resolved_data_dir / "tmp"
        tmp.mkdir(parents=True, exist_ok=True)
        dest = tmp / name
        try:
            with requests.get(url, stream=True, timeout=(15, 300)) as r:
                r.raise_for_status()
                with open(dest, "wb") as f:
                    for chunk in r.iter_content(65536):
                        f.write(chunk)
        except Exception as exc:
            dest.unlink(missing_ok=True)
            raise BookParseError(f"下载失败：{exc}") from exc

        try:
            return self.import_book(str(dest))
        finally:
            dest.unlink(missing_ok=True)

    def _unique_target(self, src: Path) -> Path:
        """在 books_dir 里取一个不重名的目标路径。"""
        base = src.stem
        ext = src.suffix.lower()
        target = self.config.books_dir / f"{base}{ext}"
        i = 1
        while target.exists():
            target = self.config.books_dir / f"{base}_{i}{ext}"
            i += 1
        return target

    # ---------- 书架 ----------

    def get_shelf_items(self) -> List[dict]:
        """合并本地书籍与合成任务（对标 Android mergeShelf）。

        每项含 book 信息与按书名归组的 tasks；仅有任务、没有书记录的书名补一条
        type="task_only" 的虚拟条目，避免任务信息丢失。
        """
        books = self.db.get_books()
        tasks = self.db.get_tasks()

        by_name: Dict[str, List[Task]] = {}
        for t in tasks:
            by_name.setdefault(t.book_name or "", []).append(t)

        items: List[dict] = []
        matched: set[str] = set()
        for b in books:
            matched.add(b.display_name)
            items.append({
                "type": "book",
                "book_id": b.id,
                "display_name": b.display_name,
                "file_path": b.file_path,
                "file_ext": b.file_ext,
                "has_audio": b.has_audio or any(
                    t.status == "completed" for t in by_name.get(b.display_name, [])),
                "category": b.category,
                "store_book_id": b.store_book_id,
                "sort_order": b.sort_order,
                "tasks": [self._task_dict(t) for t in by_name.get(b.display_name, [])],
            })

        for name, tks in by_name.items():
            if name in matched:
                continue
            items.append({
                "type": "task_only",
                "book_id": 0,
                "display_name": name,
                "file_path": "",
                "file_ext": "",
                "has_audio": any(t.status == "completed" for t in tks),
                "category": "",
                "store_book_id": None,
                "sort_order": 0,
                "tasks": [self._task_dict(t) for t in tks],
            })
        return items

    @staticmethod
    def _task_dict(t: Task) -> dict:
        return {
            "id": t.id,
            "server_task_id": t.server_task_id,
            "book_name": t.book_name,
            "audio_name": t.audio_name,
            "status": t.status,
            "total": t.total_segments,
            "completed": t.completed_segments,
            "is_local": t.is_local,
            "terminal": t.is_terminal,
            "created_at": t.created_at,
        }

    # ---------- 目录 ----------

    def get_toc(self, book: "Book") -> List[TocEntry]:
        """返回本地书籍的章节目录（TocEntry.paragraph_index 为「行/段下标」）。

        供阅读页「目录」按钮使用。优先用 ``app.parser.booktext.extract_text`` 直接给出的
        toc；退回 ``parse_book_lines``；都拿不到时返回空列表（不抛异常）。
        （旧版本无此方法，阅读页 ``self.call(book_service, 'get_toc', book)`` 恒返 None，
        导致本地 epub/pdf/mobi 永远「没有可用目录」。）
        """
        path = getattr(book, "file_path", "") or ""
        if not path:
            return []
        p = Path(path)
        if not p.is_file():
            return []
        # 1) 首选 booktext（多数格式能给出 toc）
        try:
            from app.parser import booktext  # type: ignore
            _paras, toc = booktext.extract_text(str(p))
            if toc:
                return list(toc)
        except Exception:  # noqa: BLE001
            pass
        # 2) 退回 parse_book_lines（其内部再退到 local_parser.parse_book）
        try:
            _lines, toc, _segs = parse_book_lines(p)
            if toc:
                return list(toc)
        except Exception:  # noqa: BLE001
            pass
        return []

    # ---------- 打开 / 解析 ----------

    def open_book(self, book_id: int) -> ParseResult:
        """解析一本书：本地文件走 app.parser，书城书走服务端内容接口。"""
        book = self.db.get_book(book_id)
        if book is None:
            raise BookParseError(f"书架上不存在书籍 #{book_id}")

        if book.store_book_id:
            result = self._open_store_book(book)
        else:
            result = ParseResult(book=book)
            lines, toc, segs = parse_book_lines(book.file_path)
            result.lines = lines
            result.toc = toc
            result.segments = segs
            result.raw_text = "\n\n".join(li.text for li in lines)

        self.db.update_book_last_read(book.id)
        return result

    def _open_store_book(self, book: Book) -> ParseResult:
        result = ParseResult(book=book)
        try:
            body = self.api.get_store_book_content(book.store_book_id)
        except ApiException as exc:
            raise BookParseError(f"获取书城正文失败：{exc.message}") from exc

        paras = body.get("paragraphs") or []
        para_texts = [p.get("text", "") if isinstance(p, dict) else str(p) for p in paras]

        server_rows = [r for r in (body.get("rows") or []) if isinstance(r, str) and r.strip()]
        display = server_rows if server_rows else para_texts

        lines: List[LineItem] = []
        offset = 0
        for i, row in enumerate(display):
            lines.append(LineItem(index=i, text=row, char_start=offset, char_end=offset + len(row)))
            offset += len(row) + 1
        result.lines = lines

        toc: List[TocEntry] = []
        for t in (body.get("toc") or []):
            if not isinstance(t, dict):
                continue
            toc.append(TocEntry(title=t.get("title", ""),
                                level=int(t.get("level", 0) or 0),
                                paragraph_index=int(t.get("paragraphIndex", -1) or -1)))
        result.toc = toc

        result.segments = para_texts
        return result

    # ---------- 删除 / 重排 ----------

    def delete_book(self, book_id: int) -> None:
        """从书架移除 + 清理该书相关音频文件。"""
        book = self.db.get_book(book_id)
        if book is None:
            return
        for t in self.db.get_tasks():
            if t.book_name != book.display_name:
                continue
            for seg in self.db.get_segments(t.id):
                self._remove_audio_file(seg.audio_file_path)

        try:
            fp = Path(book.file_path)
            if fp.is_file() and self._is_under(fp, self.config.books_dir):
                fp.unlink()
        except OSError:
            pass

        self.db.remove_book(book_id)

    def _remove_audio_file(self, path: str) -> None:
        if not path:
            return
        try:
            p = Path(path)
            if p.is_file():
                p.unlink()
        except OSError:
            pass

    @staticmethod
    def _is_under(file_path: Path, directory: Path) -> bool:
        try:
            file_path.resolve().relative_to(directory.resolve())
            return True
        except ValueError:
            return False

    def reorder_book(self, book_id: int, new_order: List[int]) -> None:
        """按给定的 id 顺序重写整个书架的 sort_order。

        ``new_order`` 是「屏幕上从上到下」的书 id 列表。get_books 按 sort_order DESC
        取，于是越靠前的书分到越大的 sort_order，下次打开顺序保持。
        """
        if not new_order:
            return
        n = len(new_order)
        for i, bid in enumerate(new_order):
            self.db.update_book_sort(int(bid), n - i)

    # ---------- 书城下载 ----------

    def download_store_book(self, store_book_id: int) -> Book:
        """从服务端下载书城电子书文件并加入书架（已存在则直接返回）。"""
        existing = next((b for b in self.db.get_books()
                         if b.store_book_id == store_book_id), None)
        if existing is not None:
            return existing

        try:
            detail = self.api.get_store_book_detail(store_book_id)
        except ApiException as exc:
            raise BookParseError(f"获取书城详情失败：{exc.message}") from exc

        ext = (detail.file_extension or "").lower().lstrip(".")
        target = self.config.books_dir / self._unique_store_name(detail.title, ext)
        try:
            self.api.download_store_book(store_book_id, str(target))
        except Exception as exc:
            target.unlink(missing_ok=True)
            raise BookParseError(f"下载书城电子书失败：{exc}") from exc

        try:
            self.api.add_to_shelf(store_book_id)
        except Exception:
            pass

        # 用户重新加入了这本书 ⇒ 清掉「本机已移出」墓碑，
        # 否则它会一直被同步跳过，这本书永远回不到书架上。
        try:
            self.db.clear_store_book_dismissal(store_book_id)
            # 任务墓碑也要清：否则这本书的合成任务拉不回来（书回来了却显示「未合成」）。
            self.db.clear_task_dismissals_for_book(detail.title or target.stem)
        except Exception:
            pass

        books = self.db.get_books()
        book = Book(
            display_name=detail.title or target.stem,
            file_path=str(target),
            file_ext=ext,
            has_audio=detail.task_id is not None,
            category="store",
            store_book_id=store_book_id,
            author=detail.author,
            description=detail.description,
            sort_order=max((b.sort_order for b in books), default=0) + 1,
        )
        book.id = self.db.add_book(book)
        return book

    def _unique_store_name(self, title: str, ext: str) -> str:
        base = (title or "store_book").strip().replace("/", "_").replace("\\", "_")
        suffix = f".{ext}" if ext else ""
        candidate = f"{base}{suffix}"
        i = 1
        while (self.config.books_dir / candidate).exists():
            candidate = f"{base}_{i}{suffix}"
            i += 1
        return candidate


# ==========================================================================
# 便捷函数（供外部直接取切句器 / 抽取正文 / 片段，无需实例化 BookService）
# ==========================================================================

def sentences(text: Optional[str]) -> List[str]:
    return get_segmenter()[0](text)


def sub_pieces(sentence: str) -> List[str]:
    return get_segmenter()[1](sentence)


def has_speakable(text: str) -> bool:
    return get_segmenter()[2](text)
