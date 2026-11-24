"""把一页 XHTML 的 body 切成「块」，并给每块定性。

判决顺序是刻意安排的：先排除空块和容器壳，再按「有明确语义的标签/类名」往下走，
最后才落到普通段落。这样即使换个出版社的排版（类名从中文换成英文），也还能认出来。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from .htmltree import Node

# ---- 块类型 -----------------------------------------------------------
HEADING = "heading"
PARA = "para"
LIST = "list"
PRE = "pre"
TABLE = "table"
IMAGE = "image"
TABLE_CAPTION = "table_caption"
IMAGE_CAPTION = "image_caption"
NOTE = "note"
TOC_ENTRY = "toc_entry"
HR = "hr"
EMPTY = "empty"
OTHER = "other"

# ---- 类名/标签关键词（中英双语，子串匹配） ------------------------------
CODE_HINTS = (
    "代码", "程序清单", "源码", "code", "sourcecode", "source-code",
    "programlisting", "listing", "highlight", "syntax", "example",
)
NOTE_HINTS = (
    "提示", "注意", "警告", "贴士", "窍门", "注解", "注：", "小贴士",
    "note", "tip", "hint", "warning", "caution", "important", "sidebar", "aside",
)
TABLE_CAPTION_HINTS = ("表题", "表名", "表注", "tablecaption", "table-caption", "tabletitle")
IMAGE_CAPTION_HINTS = ("图题", "图注", "图名", "figcaption", "figure-caption", "image-caption")
TOC_HINTS = ("toc", "目录", "contents", "nav-list")
CONTAINER_HINTS = (
    "chapter", "section", "content", "body", "text", "page", "article",
    "entry", "fragment", "main",
)

#: 纯容器壳：本身不带语义，只用来包住若干块
_CONTAINER_TAGS = {"div", "section", "article", "main", "body", "center", "blockquote"}
_BLOCK_TAGS = {"p", "ul", "ol", "dl", "pre", "table", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "figure", "div"}

_HEADING_RE = re.compile(r"^h[1-6]$")
#: 形如「第 4 章　操作文件与目录」「4.1　通配符」「29.2 while」
_HEADING_TEXT_RE = re.compile(
    r"^\s*(第\s*[0-9〇零一二三四五六七八九十百]+\s*[章节部篇回]|[0-9]+(\.[0-9]+)*\s*[\u3000 ]?\S)"
)
_WS_RE = re.compile(r"[ \t\u00a0\u3000]+")


@dataclass
class Block:
    kind: str
    node: Node
    level: int = 0
    text: str = ""
    meta: dict = field(default_factory=dict)

    def __repr__(self) -> str:  # pragma: no cover
        preview = self.text[:28].replace("\n", " ")
        return f"<{self.kind} lvl={self.level} {preview!r}>"


def clean_ws(s: str) -> str:
    """压掉连续空白（含全角空格），但保留换行。"""
    s = s.replace("\r\n", "\n").replace("\r", "\n")
    s = _WS_RE.sub(" ", s)
    return s.strip()


def is_empty(node: Node) -> bool:
    """没有文字、也没有图片/表格等实质内容。"""
    for n in node.iter():
        if n.tag in ("img", "table", "svg", "hr"):
            return False
    return not clean_ws(node.text()).strip("　 \n\t|-_=*·.。")


def _has_block_child(node: Node) -> bool:
    return any(isinstance(c, Node) and c.tag in _BLOCK_TAGS for c in node.children)


def looks_like_container(node: Node) -> bool:
    """这一层只是壳，真正的块在下一层。

    注意三种「看起来像壳、其实是整体语义单元」的例外：提示框、代码块、目录条目 ——
    尤其目录条目，一旦被展开，里面的链接文字会变成一堆裸段落，整页目录就删不掉了。
    """
    if node.tag not in _CONTAINER_TAGS:
        return False
    if node.has_class(*NOTE_HINTS):
        return False
    if node.has_class(*CODE_HINTS):
        return False
    if node.has_class(*TOC_HINTS):
        return False
    if node.tag == "blockquote" and not node.has_class(*CONTAINER_HINTS):
        return False
    return _has_block_child(node)


def flatten_blocks(container: Node) -> list[Node]:
    """展开纯容器，返回同一层的块节点列表（文档顺序不变）。"""
    out: list[Node] = []
    for child in container.children:
        if isinstance(child, str):
            if clean_ws(child):
                # 裸文本：包一层 p 才好作为块处理
                wrapper = Node("p")
                wrapper.append(child)
                out.append(wrapper)
            continue
        if looks_like_container(child):
            out.extend(flatten_blocks(child))
        else:
            out.append(child)
    return out


# ----------------------------------------------------------------------
# 定性
# ----------------------------------------------------------------------
def classify(node: Node) -> Block:
    """给一个块节点定性。"""
    tag = node.tag
    text = clean_ws(node.text())

    if is_empty(node):
        return Block(EMPTY, node, text=text)

    # 代码块：pre，或挂了代码类名的容器（里面真的含 code/pre 才算）
    if tag == "pre":
        return Block(PRE, node, text=text)
    if tag in ("div", "p") and node.has_class(*CODE_HINTS) and node.find("pre") is not None:
        return Block(PRE, node, text=text)

    # 表格
    if tag == "table":
        return Block(TABLE, node, text=text, meta={"table": node})
    if tag in ("div", "figure", "center"):
        inner = node.find("table")
        if inner is not None:
            return Block(TABLE, node, text=text, meta={"table": inner})

    # 插图
    if tag == "img":
        return Block(IMAGE, node, text=text)
    if tag in ("figure", "div", "p") and node.find("img") is not None:
        return Block(IMAGE, node, text=text)

    # 图/表题（必须早于普通段落）
    if node.has_class(*IMAGE_CAPTION_HINTS):
        return Block(IMAGE_CAPTION, node, text=text)
    if node.has_class(*TABLE_CAPTION_HINTS):
        return Block(TABLE_CAPTION, node, text=text)
    if tag == "figcaption":
        return Block(IMAGE_CAPTION, node, text=text)

    # 提示框
    if node.has_class(*NOTE_HINTS):
        return Block(NOTE, node, text=text)

    # 目录条目
    if node.has_class(*TOC_HINTS):
        return Block(TOC_ENTRY, node, text=text)

    # 标题
    if _HEADING_RE.match(tag):
        return Block(HEADING, node, level=int(tag[1]), text=text)
    if tag in ("p", "div") and node.has_class("title", "heading", "headline") and len(text) <= 60:
        return Block(HEADING, node, level=2, text=text)

    if tag == "hr":
        return Block(HR, node, text=text)

    if tag in ("ul", "ol", "dl"):
        return Block(LIST, node, text=text)

    if tag == "p":
        # 有些书把「第 4 章 xxx」做成加粗段落
        if len(text) <= 40 and _HEADING_TEXT_RE.match(text) and _all_bold(node):
            return Block(HEADING, node, level=1, text=text)
        return Block(PARA, node, text=text)

    if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
        return Block(HEADING, node, level=int(tag[1]), text=text)

    return Block(OTHER, node, text=text)


def _all_bold(node: Node) -> bool:
    """整段都被 strong/b 包住（或自身加粗），视为伪标题。"""
    if not clean_ws(node.text()):
        return False
    meaningful = [c for c in node.children if not (isinstance(c, str) and not c.strip())]
    if not meaningful:
        return False
    return all(isinstance(c, Node) and c.tag in ("strong", "b") for c in meaningful)


def heading_junk(title: str) -> bool:
    """标题本身没有朗读价值（页码、纯符号、纯编号）。"""
    t = clean_ws(title)
    if not t:
        return True
    if re.fullmatch(r"[\d\-–—_=~·.*#\s|]+", t):
        return True
    if re.fullmatch(r"第?\s*\d+\s*页", t):
        return True
    return False
