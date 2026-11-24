"""极简 XHTML 树，只为「改写 EPUB 的 body」服务。

为什么不用 lxml / BeautifulSoup：
  * 本工具要能在没有网络的机器上一把跑起来，不装任何包；
  * 我们只需要「按块读 → 删块 / 换块 → 重新序列化」，不需要 CSS 选择器、不需要容错修复。

文本节点在树里就是 ``str``，元素是 :class:`Node`。混排在一个列表里，
顺序天然就是文档顺序，改起来直观。
"""

from __future__ import annotations

import html as _html
from html.parser import HTMLParser

#: 自闭合元素（序列化时输出 ``<br />`` 形式）
VOID_TAGS = frozenset(
    "area base br col embed hr img input link meta param source track wbr".split()
)


class Node:
    """一个元素节点。``children`` 里可以混放 :class:`Node` 和 ``str``。"""

    __slots__ = ("tag", "attrs", "children", "parent")

    def __init__(self, tag: str, attrs: dict[str, str] | None = None):
        self.tag = tag.lower()
        self.attrs = dict(attrs or {})
        self.children: list[Node | str] = []
        self.parent: Node | None = None

    # ---- 结构操作 ----------------------------------------------------
    def append(self, child: Node | str) -> None:
        if isinstance(child, Node):
            child.parent = self
        self.children.append(child)

    def extend(self, children) -> None:
        for c in children:
            self.append(c)

    def remove_child(self, child: Node | str) -> None:
        try:
            self.children.remove(child)
        except ValueError:
            pass

    def detach(self) -> None:
        if self.parent is not None:
            self.parent.remove_child(self)
            self.parent = None

    # ---- 属性便捷访问 -------------------------------------------------
    def get(self, name: str, default: str = "") -> str:
        return self.attrs.get(name, default)

    @property
    def classes(self) -> list[str]:
        raw = self.attrs.get("class", "")
        return [c for c in raw.replace("\u3000", " ").split(" ") if c]

    def has_class(self, *needles: str) -> bool:
        """类名里只要**任意一项**被包含即算命中（中文类名常带前缀/后缀）。"""
        joined = " ".join(self.classes)
        return any(n in joined for n in needles)

    def id_text(self) -> str:
        return self.attrs.get("id", "") or ""

    # ---- 文本 --------------------------------------------------------
    def text(self, sep: str = "") -> str:
        """自身及所有后代的纯文本。``sep`` 用于块级元素之间插分隔符。"""
        return _text_of(self, sep)

    def set_text(self, value: str) -> None:
        self.children = [value]

    # ---- 查找 --------------------------------------------------------
    def iter(self):
        """深度优先遍历所有元素节点（含自身）。"""
        yield self
        for c in self.children:
            if isinstance(c, Node):
                yield from c.iter()

    def find_all(self, tag: str) -> list[Node]:
        tag = tag.lower()
        return [n for n in self.iter() if n.tag == tag]

    def find(self, tag: str) -> Node | None:
        for n in self.iter():
            if n.tag == tag:
                return n
        return None

    def __repr__(self) -> str:  # pragma: no cover - 调试用
        cls = self.attrs.get("class", "")
        return f"<Node {self.tag}{' .' + cls if cls else ''} children={len(self.children)}>"


# ----------------------------------------------------------------------
# 遍历 / 文本
# ----------------------------------------------------------------------
def _text_of(node: Node | str, sep: str = "") -> str:
    if isinstance(node, str):
        return node
    if node.tag == "br":
        return "\n"
    parts: list[str] = []
    for c in node.children:
        parts.append(_text_of(c, sep))
    return sep.join(p for p in parts if p) if sep else "".join(parts)


def iter_nodes(node: Node):
    yield from node.iter()


# ----------------------------------------------------------------------
# 序列化
# ----------------------------------------------------------------------
def escape_text(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def escape_attr(s: str) -> str:
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def serialize(node: Node | str, pretty: bool = False, _indent: int = 0) -> str:
    if isinstance(node, str):
        return escape_text(node)

    tag = node.tag
    attrs = "".join(f' {k}="{escape_attr(v)}"' for k, v in node.attrs.items())

    if tag in VOID_TAGS:
        return f"<{tag}{attrs} />"

    inner = "".join(serialize(c, pretty, _indent + 1) for c in node.children)

    # 只含文本时保持紧凑，避免把正文切碎
    only_text = all(isinstance(c, str) for c in node.children)
    if not pretty or only_text:
        return f"<{tag}{attrs}>{inner}</{tag}>"

    pad = "  " * _indent
    pad_in = "  " * (_indent + 1)
    parts = [f"<{tag}{attrs}>"]
    for c in node.children:
        if isinstance(c, str):
            s = c.strip()
            if s:
                parts.append(pad_in + escape_text(s))
        else:
            parts.append(pad_in + serialize(c, pretty, _indent + 1).strip())
    parts.append(f"{pad}</{tag}>")
    return "\n".join(parts)


# ----------------------------------------------------------------------
# 解析
# ----------------------------------------------------------------------
class _TreeBuilder(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = Node("#document")
        self.stack: list[Node] = [self.root]

    # HTMLParser 会把 <script>/<style> 内容塞进 data，这里无所谓
    def handle_starttag(self, tag, attrs):
        node = Node(tag, {k: (v or "") for k, v in attrs})
        self.stack[-1].append(node)
        if node.tag not in VOID_TAGS:
            self.stack.append(node)

    def handle_startendtag(self, tag, attrs):
        node = Node(tag, {k: (v or "") for k, v in attrs})
        self.stack[-1].append(node)

    def handle_endtag(self, tag):
        tag = tag.lower()
        # 容忍不闭合：从栈顶往下找最近的同名元素
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                return

    def handle_data(self, data):
        if data:
            self.stack[-1].append(data)

    def handle_entityref(self, name):  # convert_charrefs=True 时基本不触发
        self.stack[-1].append(_html.unescape(f"&{name};"))

    def handle_charref(self, name):
        self.stack[-1].append(_html.unescape(f"&#{name};"))


def parse(source: str) -> Node:
    """把 XHTML 源码解析成树，返回 ``#document`` 根。"""
    builder = _TreeBuilder()
    builder.feed(source)
    builder.close()
    return builder.root


def find_body(root: Node) -> Node | None:
    return root.find("body")


def find_head(root: Node) -> Node | None:
    return root.find("head")


def make(tag: str, text: str | None = None, attrs: dict[str, str] | None = None) -> Node:
    node = Node(tag, attrs)
    if text:
        node.append(text)
    return node
