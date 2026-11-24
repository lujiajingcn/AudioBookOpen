"""主编排：把一本书从「印刷体」改写成「有声稿」。

处理单元是**块**（段落 / 代码 / 表格 / 插图 / 提示框……）。
对每一块做三种决定之一：
  * 保留 —— 正文、标题、有信息量的提示；
  * 改写 —— 代码/表格/插图，换成一句可被原文验证的说明；
  * 丢弃 —— 版权页、目录页、页码、纯装饰、无信息量的标签。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from . import classify, codecut, prose, tabulate
from .classify import (
    EMPTY, HEADING, HR, IMAGE, IMAGE_CAPTION, LIST, NOTE, OTHER, PARA,
    PRE, TABLE, TABLE_CAPTION, TOC_ENTRY, Block,
)
from .epub import EpubBook, EpubWriter
from .htmltree import Node, find_body, parse, serialize

#: 整节丢弃的标题关键词（版权、索引之类，念了只有噪音）
DROP_SECTION_HINTS = (
    "版权信息", "版权声明", "版权页", "图书在版编目", "cip 数据", "cip数据",
    "免责声明", "作者简介", "作者介绍", "索引", "符号表", "图表索引",
    "出版信息", "图书信息", "关于作者", "封面", "书名页", "扉页",
)
#: 提示标签本身（纯图标文字），内容是空的
_PURE_TAG_RE = re.compile(r"^(提示|注意|警告|小心|窍门|贴士|技巧|说明|注|note|tip|warning|caution)\s*[:：]?$", re.I)
#: 「如下所示」这类引出句（末尾常带句号，必须容忍）
_FOLLOW_RE = re.compile(
    r"(如下(所述|所示)?|下面|以下|格式为?如下|例子?如下|样子如下|所示)\s*[：:。．，,]?\s*$"
)

#: 替换块的标记类名
MARK = "abp-note"


@dataclass
class Config:
    mode: str = "rewrite"          # rewrite | strip（只删不补） | audit（只统计不改）
    formats: tuple[str, ...] = ("epub", "txt")
    merge_threshold: int = 2       # 连续多少个同类替代块开始合并
    speak_table_max_chars: int = 160
    chapter_transition: bool = True
    front_matter_note: bool = True
    keep_note_blocks: bool = True
    verbose: bool = False


@dataclass
class PageStat:
    href: str = ""
    title: str = ""
    chars_in: int = 0
    chars_out: int = 0
    kept: int = 0
    dropped: int = 0
    rewritten: int = 0
    code_blocks: int = 0
    code_chars: int = 0
    tables: int = 0
    images: int = 0
    dropped_page: bool = False
    drop_reason: str = ""

    def as_row(self) -> dict:
        return {
            "章节": self.title or self.href,
            "原字数": self.chars_in,
            "保留字数": self.chars_out,
            "压缩率": f"{(1 - self.chars_out / self.chars_in) * 100:.0f}%" if self.chars_in else "-",
            "代码块": self.code_blocks,
            "表格": self.tables,
            "插图": self.images,
            "丢弃段": self.dropped,
        }


@dataclass
class Result:
    out_epub: Path | None = None
    out_txt: Path | None = None
    report: dict = field(default_factory=dict)
    pages: list[PageStat] = field(default_factory=list)


# ----------------------------------------------------------------------
class Organizer:
    def __init__(self, config: Config | None = None) -> None:
        self.cfg = config or Config()
        self._lead_idx = 0

    # ---- 措辞轮换 ----------------------------------------------------
    def _lead(self) -> str:
        from .phrasing import LEADS
        s = LEADS[self._lead_idx % len(LEADS)]
        self._lead_idx += 1
        return s

    # ---- 主流程 ------------------------------------------------------
    def run(self, input_path: str | Path, out_dir: str | Path) -> Result:
        input_path = Path(input_path)
        out_dir = Path(out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)

        with EpubBook(input_path) as book:
            new_title = f"{book.title}_精简有声化"
            result = Result()
            new_pages: dict[str, str] = {}
            plain_pages: list[tuple[str, list[str]]] = []
            dropped: set[str] = set()
            page_titles: list[tuple[str, str]] = []   # (href, title) 用来看下一章

            # 先算每页「下一章是谁」，章末衔接要用
            for item in book.pages:
                src = book.read_text(item.href)
                page_titles.append((item.href, self._probe_title(src)))

            front_inserted = False
            for idx, item in enumerate(book.pages):
                src = book.read_text(item.href)
                nxt = page_titles[idx + 1][1] if idx + 1 < len(page_titles) else ""
                html, plain, stat, drop = self._process_page(
                    book, item.href, src, nxt, first_page=(idx == 0),
                    insert_front=(not front_inserted) and self.cfg.front_matter_note,
                )
                result.pages.append(stat)
                if drop:
                    dropped.add(item.href)
                    continue
                if stat.chars_out > 0 or self.cfg.mode == "audit":
                    front_inserted = front_inserted or self.cfg.front_matter_note
                    new_pages[item.href] = html
                    plain_pages.append((stat.title or item.href, plain))
                else:
                    dropped.add(item.href)

            result.report = self._build_report(book, result.pages, new_title)

            if self.cfg.mode == "audit":
                result.report["note"] = "audit 模式：只统计，未生成文件"
                return result

            if "epub" in self.cfg.formats:
                writer = EpubWriter(book, out_dir / f"{new_title}.epub", new_title)
                result.out_epub = writer.write(new_pages, dropped)

            if "txt" in self.cfg.formats:
                result.out_txt = self._write_txt(out_dir / f"{new_title}.txt", new_title, plain_pages)

            return result

    # ---- 单页 --------------------------------------------------------
    def _process_page(
        self, book: EpubBook, href: str, src: str, next_title: str,
        first_page: bool = False, insert_front: bool = False,
    ):
        stat = PageStat(href=href)
        doc = parse(src)
        body = find_body(doc)
        if body is None:
            stat.dropped_page = True
            stat.drop_reason = "无 body"
            return "", [], stat, True

        raw_blocks = [classify.classify(n) for n in classify.flatten_blocks(body)]
        stat.chars_in = sum(len(b.text) for b in raw_blocks)

        # 只有图片、没有文字 ⇒ 封面页，原样保留
        if raw_blocks and all(b.kind in (IMAGE, EMPTY) for b in raw_blocks):
            stat.title = "封面"
            stat.chars_out = 0
            stat.dropped_page = True
            stat.drop_reason = "封面/纯图页"
            return src, [], stat, True

        out_nodes: list[Node] = []
        plain: list[str] = []
        prev_kept_text = ""
        state = {"section_drop": False, "h1_seen": False, "first_h1": ""}

        if insert_front:
            note = prose.book_front_matter(book.title)
            node = self._mk(MARK, note)
            out_nodes.append(node)
            plain.append(note)

        table_caption_pending = ""
        i = 0
        while i < len(raw_blocks):
            blk = raw_blocks[i]

            # 段落 / 标题 -------------------------------------------------
            if blk.kind == HEADING:
                title = classify.clean_ws(blk.text)
                if blk.level == 1:
                    state["section_drop"] = self._should_drop_section(title)
                    state["h1_seen"] = True
                    # 标题取「第一个被保留的 h1」，否则整章被判成版权页会误导报表
                    if not state["first_h1"] and not state["section_drop"]:
                        state["first_h1"] = title
                        stat.title = title
                if state["section_drop"]:
                    stat.dropped += 1
                    i += 1
                    continue
                if classify.heading_junk(title):
                    stat.dropped += 1
                    i += 1
                    continue
                out_nodes.append(blk.node)
                plain.append(("[H%d] " % blk.level) + title)
                prev_kept_text = title
                stat.kept += 1
                i += 1
                continue

            if state["section_drop"]:
                stat.dropped += 1
                i += 1
                continue

            if blk.kind in (TABLE_CAPTION, IMAGE_CAPTION):
                table_caption_pending = (
                    prose.clean_prose(blk.text) if blk.kind == TABLE_CAPTION else ""
                )
                i += 1
                continue

            # 代码 -------------------------------------------------------
            if blk.kind == PRE:
                stat.code_blocks += 1
                stat.code_chars += len(blk.text)
                rep = self._rewrite_code(blk, prev_kept_text)
                if rep:
                    out_nodes.append(rep[0])
                    plain.append(rep[1])
                    stat.rewritten += 1
                i += 1
                continue

            # 表格 -------------------------------------------------------
            if blk.kind == TABLE:
                stat.tables += 1
                rep = self._rewrite_table(blk, table_caption_pending, prev_kept_text)
                table_caption_pending = ""
                if rep:
                    out_nodes.append(rep[0])
                    plain.append(rep[1])
                    stat.rewritten += 1
                i += 1
                continue

            # 插图 -------------------------------------------------------
            if blk.kind == IMAGE:
                stat.images += 1
                cap = self._find_caption_after(raw_blocks, i)
                rep = self._rewrite_image(cap, blk, prev_kept_text)
                if rep:
                    out_nodes.append(rep[0])
                    plain.append(rep[1])
                    stat.rewritten += 1
                i += 1
                continue

            # 提示框 -----------------------------------------------------
            if blk.kind == NOTE:
                if not self.cfg.keep_note_blocks:
                    stat.dropped += 1
                    i += 1
                    continue
                t = classify.clean_ws(blk.text)
                if _PURE_TAG_RE.match(t) or not t:
                    stat.dropped += 1
                    i += 1
                    continue
                cleaned = prose.clean_prose(t)
                if not cleaned:
                    stat.dropped += 1
                    i += 1
                    continue
                node = self._mk(MARK, prose.ensure_ending(cleaned))
                out_nodes.append(node)
                plain.append(node.text())
                prev_kept_text = cleaned
                stat.kept += 1
                i += 1
                continue

            # 普通段落 / 列表 / 其他 -------------------------------------
            if blk.kind == LIST:
                # 列表整块塞一段，TTS 会把七八条挤成一口气 ⇒ 拆成逐条段落
                for nd in self._explode_list(blk.node):
                    txt = prose.clean_prose(nd.text() or "")
                    if not txt:
                        continue
                    nd.children = [prose.ensure_ending(txt) if self.cfg.mode != "strip" else txt]
                    out_nodes.append(nd)
                    plain.append(nd.text())
                prev_kept_text = classify.clean_ws(blk.text)[-20:]
                stat.kept += 1
                i += 1
                continue

            if blk.kind in (PARA, OTHER):
                cleaned = prose.clean_prose(blk.text)
                if not cleaned:
                    stat.dropped += 1
                    i += 1
                    continue
                if self.cfg.mode == "strip":
                    prose.tidy_inline(blk.node)
                    out_nodes.append(blk.node)
                    plain.append(cleaned)
                else:
                    prose.tidy_inline(blk.node)
                    out_nodes.append(blk.node)
                    plain.append(prose.ensure_ending(cleaned))
                prev_kept_text = cleaned
                stat.kept += 1
                i += 1
                continue

            # 目录条目 / 空 / 分隔线 / 其他
            stat.dropped += 1
            i += 1

        # 目录页：整页只剩「目录」二字 ⇒ 丢
        if not state["h1_seen"] and stat.kept == 0 and stat.rewritten == 0:
            stat.dropped_page = True
            stat.drop_reason = "纯目录/空页"
            return "", [], stat, True

        out_nodes = self._merge_consecutive(out_nodes, plain)
        out_nodes = self._stepify(out_nodes, plain)

        if self.cfg.chapter_transition and next_title and not state["section_drop"]:
            outro = prose.chapter_outro(next_title)
            if outro:
                out_nodes.append(self._mk(MARK, outro))
                plain.append(outro)

        stat.chars_out = sum(len(t) for t in plain)

        # 重新组装 body
        body.children = []
        body.extend(out_nodes)
        html = self._serialize_doc(src, doc)
        return html, plain, stat, False

    # ---- 各类改写 ----------------------------------------------------
    def _rewrite_code(self, blk: Block, prev_text: str):
        if self.cfg.mode == "strip":
            return None
        info = codecut.analyze(blk.node)
        follow = bool(_FOLLOW_RE.search(prev_text or ""))
        lead = self._lead()
        text = codecut.summarize(info, lead=lead, follow=follow, variant=self._lead_idx)
        text = prose.ensure_ending(text)
        node = self._mk(MARK, text, kind="code")
        return node, text

    def _rewrite_table(self, blk: Block, caption: str, prev_text: str):
        if self.cfg.mode == "strip":
            return None
        table = blk.meta.get("table") or blk.node
        info = tabulate.analyze(table, caption=caption)
        follow = bool(_FOLLOW_RE.search(prev_text or ""))
        text = tabulate.summarize(info, lead=self._lead(), follow=follow)
        text = prose.ensure_ending(text)
        node = self._mk(MARK, text, kind="table")
        return node, text

    def _rewrite_image(self, caption: str, blk: Block, prev_text: str):
        if self.cfg.mode == "strip":
            return None
        alt = ""
        img = blk.node if blk.node.tag == "img" else blk.node.find("img")
        if img is not None:
            alt = img.get("alt", "")
        follow = bool(_FOLLOW_RE.search(prev_text or ""))
        text = prose.image_summary(caption, alt, lead=self._lead(), follow=follow)
        node = self._mk(MARK, text, kind="image")
        return node, text

    # ---- 后处理 ------------------------------------------------------
    def _merge_consecutive(self, nodes: list[Node], plain: list[str]) -> list[Node]:
        """连续多段「这里有一张表/一段代码」会变成噪音，合并掉后半截。"""
        if self.cfg.mode != "rewrite" or self.cfg.merge_threshold <= 0:
            return nodes

        out: list[Node] = []
        run: list[Node] = []
        run_kinds: list[str] = []

        def flush():
            nonlocal run, run_kinds
            if not run:
                return
            keep = max(0, self.cfg.merge_threshold)
            out.extend(run[:keep])
            rest = run[keep:]
            if rest:
                kinds = set(run_kinds[keep:])
                word = "表格和代码示例" if len(kinds) > 1 else (
                    "类似的代码示例" if "code" in kinds else
                    "类似的表格" if "table" in kinds else "类似的插图"
                )
                merged = f"这里还有 {len(rest)} 处{word}，一并略过。"
                out.append(self._mk(MARK, merged, kind="merged"))
            run = []
            run_kinds = []

        for n in nodes:
            kind = n.attrs.get("data-abp-kind", "")
            if kind and kind in ("code", "table", "image"):
                run.append(n)
                run_kinds.append(kind)
            else:
                flush()
                out.append(n)
        flush()

        if len(out) != len(nodes):
            # plain 与节点对不齐时，重建
            plain[:] = [n.text() for n in out]
        return out

    def _stepify(self, nodes: list[Node], plain: list[str]) -> list[Node]:
        """把「1．取案板。」这种伪列表转成「第一步，取案板。」"""
        if self.cfg.mode == "strip":
            return nodes
        i = 0
        changed = False
        n = len(nodes)
        while i < n:
            parsed = prose.step_number(nodes[i].text() or "")
            if parsed and parsed[0] == 1:
                j = i
                seq: list[tuple[int, str]] = []
                while j < n:
                    p = prose.step_number(nodes[j].text() or "")
                    if p and p[0] == len(seq) + 1:
                        seq.append(p)
                        j += 1
                    else:
                        break
                if len(seq) >= 3:
                    for k, (num, body) in enumerate(seq):
                        nodes[i + k].children = [f"第{prose.cn_number(num)}步，{body.rstrip('。')}。"]
                    changed = True
                    i = j
                    continue
            i += 1
        if changed:
            plain[:] = [nd.text() for nd in nodes]
        return nodes

    # ---- 小工具 ------------------------------------------------------
    def _explode_list(self, node: Node) -> list[Node]:
        """把 ``<ul><li>…`` 拆成一条一行的段落，嵌套列表不重复计入外层。"""
        items = [n for n in node.iter() if n.tag == "li"]
        if not items:
            items = [n for n in node.iter() if n.tag in ("dt", "dd")]
        out: list[Node] = []
        for li in items:
            txt = prose.clean_prose(_text_excluding(li, ("ul", "ol", "dl")))
            if not txt:
                continue
            p = Node("p")
            p.append(txt)
            out.append(p)
        return out

    def _mk(self, cls: str, text: str, kind: str = "") -> Node:
        node = Node("p", {"class": f"{cls} {cls}-{kind}" if kind else cls})
        if kind:
            node.attrs["data-abp-kind"] = kind
        node.append(text)
        return node

    def _should_drop_section(self, title: str) -> bool:
        t = (title or "").strip().lower()
        return any(h in t for h in DROP_SECTION_HINTS)

    def _find_caption_after(self, blocks: list[Block], idx: int) -> str:
        for k in (idx + 1, idx + 2):
            if k < len(blocks) and blocks[k].kind == IMAGE_CAPTION:
                return blocks[k].text
        return ""

    def _probe_title(self, src: str) -> str:
        doc = parse(src)
        body = find_body(doc)
        if body is None:
            return ""
        for n in classify.flatten_blocks(body):
            b = classify.classify(n)
            if b.kind == HEADING and b.level == 1:
                return classify.clean_ws(b.text)
        return ""

    def _serialize_doc(self, src: str, doc: Node) -> str:
        head = "\n".join([link for link in re.findall(r"^\s*(?:<\?xml[^>]*\?>|<!DOCTYPE[^>]*>)",
                                                       src, re.I | re.M)])
        html = doc.find("html")
        if html is None:
            return src
        body = serialize(html)
        parts = []
        if "<?xml" not in head.lower():
            parts.append('<?xml version="1.0" encoding="utf-8"?>')
        if head:
            parts.append(head)
        parts.append(body)
        return "\n".join(parts) + "\n"

    # ---- 汇总 --------------------------------------------------------
    def _build_report(self, book: EpubBook, pages: list[PageStat], title: str) -> dict:
        kept = [p for p in pages if not p.dropped_page]
        total_in = sum(p.chars_in for p in pages)
        total_out = sum(p.chars_out for p in pages)
        return {
            "input": book.path.name,
            "output_title": title,
            "pages": len(pages),
            "pages_kept": len(kept),
            "pages_dropped": len(pages) - len(kept),
            "chars_in": total_in,
            "chars_out": total_out,
            "compress_ratio": round(total_out / total_in, 3) if total_in else 0,
            "code_blocks": sum(p.code_blocks for p in pages),
            "code_chars": sum(p.code_chars for p in pages),
            "tables": sum(p.tables for p in pages),
            "images": sum(p.images for p in pages),
            "paragraphs_kept": sum(p.kept for p in pages),
            "paragraphs_dropped": sum(p.dropped for p in pages),
            "paragraphs_rewritten": sum(p.rewritten for p in pages),
        }

    def _write_txt(self, path: Path, title: str, pages: list[tuple[str, list[str]]]) -> Path:
        lines: list[str] = [title, ""]
        for page_title, paras in pages:
            lines += ["", f"=== {page_title} ===", ""]
            first_h1_skipped = False
            for p in paras:
                if p.startswith("[H"):
                    lvl = int(p[2])
                    txt = p[4:].strip()
                    # 页标题和章内第一个 h1 往往重名，TXT 里只留一份
                    if not first_h1_skipped and lvl == 1 and txt == page_title:
                        first_h1_skipped = True
                        continue
                    lines += ["", ("#" * lvl) + " " + txt, ""]
                else:
                    lines += [p.strip(), ""]
        path.write_text("\n".join(lines), encoding="utf-8")
        return path


def _text_excluding(node: Node, skip_tags: tuple[str, ...]) -> str:
    """取文本但跳过指定子元素（用于剔除列表项里的嵌套子列表）。"""
    parts: list[str] = []
    for c in node.children:
        if isinstance(c, str):
            parts.append(c)
        elif c.tag not in skip_tags:
            parts.append(_text_excluding(c, skip_tags))
    return "".join(parts)
