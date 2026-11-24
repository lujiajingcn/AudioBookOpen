"""表格处理：区分「值得念的对照表」和「不该念的参数矩阵」。

计算机书里的表格大致两类：
  * 两列的术语表（``cd`` → 换个目录）—— 这是知识密度最高的地方，**应该念**；
  * 多列参数矩阵、ASCII 布局图 —— 念出来是灾难，压成一句概览。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from .classify import clean_ws
from .htmltree import Node

_CJK_RE = re.compile(r"[\u4e00-\u9fa5]")
#: 念整张表的字数上限，超过就只举例（240 字约等于 6～8 行小表）
MAX_SPEAK_CHARS = 240


@dataclass
class TableInfo:
    headers: list[str] = field(default_factory=list)
    rows: list[list[str]] = field(default_factory=list)
    kind: str = "data"          # glossary / list / matrix
    caption: str = ""

    @property
    def n_rows(self) -> int:
        return len(self.rows)

    @property
    def n_cols(self) -> int:
        return len(self.headers) if self.headers else (len(self.rows[0]) if self.rows else 0)


def _cell_text(node: Node | None) -> str:
    if node is None:
        return ""
    t = clean_ws(node.text())
    t = t.replace("**", "").replace("＊", "")
    return re.sub(r"\s+", " ", t).strip()


def _row_cells(tr: Node) -> list[str]:
    cells = [c for c in tr.children if isinstance(c, Node) and c.tag in ("td", "th")]
    return [_cell_text(c) for c in cells]


def analyze(table: Node, caption: str = "") -> TableInfo:
    """读出表头与数据行。支持 thead/tbody，也支持没有 thead 的裸表。"""
    info = TableInfo(caption=caption)

    trs: list[Node] = []
    thead = table.find("thead")
    if thead is not None:
        trs = [c for c in thead.iter() if c.tag == "tr"]
        if trs:
            info.headers = _row_cells(trs[0])
            trs = trs[1:]
    for tb in table.find_all("tbody"):
        trs.extend([c for c in tb.iter() if c.tag == "tr"])

    if not trs:  # 没有 thead/tbody 的裸表
        trs = [c for c in table.iter() if c.tag == "tr"]
        if trs and not info.headers:
            first = trs[0]
            if any(isinstance(c, Node) and c.tag == "th" for c in first.children):
                info.headers = _row_cells(first)
                trs = trs[1:]

    rows = [_row_cells(tr) for tr in trs]
    info.rows = [r for r in rows if any(x.strip() for x in r)]

    if not info.headers and info.rows:
        # 首行当表头用（很多中文排版直接 <td> 铺第一行）
        info.headers = info.rows[0]
        info.rows = info.rows[1:]

    info.kind = _classify(info)
    return info


def _classify(info: TableInfo) -> str:
    cols = info.n_cols
    if cols <= 1:
        return "list"
    if cols == 2:
        right = [r[1] for r in info.rows if len(r) > 1 and r[1].strip()]
        if not right:
            return "data"
        avg = sum(len(x) for x in right) / len(right)
        # 右边是成句的说明 ⇒ 术语对照表
        if avg >= 6 or any(_CJK_RE.search(x) and len(x) >= 8 for x in right):
            return "glossary"
        return "data"
    return "matrix"


def _shorten(s: str, limit: int = 34) -> str:
    s = s.strip().rstrip("。.")
    if len(s) <= limit:
        return s
    # 尽量在标点处断开
    for sep in ("，", "；", "、", "。", " "):
        idx = s.rfind(sep, 0, limit)
        if idx > limit // 2:
            return s[:idx]
    return s[:limit]


def summarize(info: TableInfo, lead: str = "接下来", follow: bool = False) -> str:
    """返回替代文案。表题会被带进去，保证「表 4-1」这个引用不落空。"""
    cap = _cap_ref(info.caption)

    if cap:
        opener = cap if follow else f"{lead}是{cap}"
    else:
        opener = "这张表" if follow else f"{lead}有一张表"

    if info.kind == "list":
        items = [r[0] for r in info.rows if r and r[0].strip()][:8]
        if not items:
            return "这里有一张列表，内容从略。"
        return f"{opener}，列出 {len(info.rows)} 项内容：{'，'.join(items)}。"

    if info.kind == "glossary":
        left_c = info.headers[0] if info.headers else "名称"
        right_c = info.headers[1] if len(info.headers) > 1 else "说明"
        picks = [(r[0], r[1]) for r in info.rows if len(r) > 1 and r[0].strip() and r[1].strip()]
        spoken = [f"{_shorten(k, 16)}，{_shorten(v, 30)}" for k, v in picks]
        full = "；".join(spoken)
        # 短表整张念完；长表只念前几条
        if len(full) <= MAX_SPEAK_CHARS:
            return f"{opener}，一共 {len(picks)} 行，对照了{left_c}和{right_c}：{full}。"
        sample = "；".join(spoken[:3])
        return (
            f"{opener}，一共 {len(picks)} 行，对照了{left_c}和{right_c}。"
            f"比如：{sample}。其余各行意思相近，就不再逐条念了。"
        )

    if info.kind == "matrix":
        cols_desc = "、".join([h for h in info.headers if h.strip()][:4]) or "若干项"
        return (
            f"{opener}，{info.n_rows} 行 {info.n_cols} 列，列出的是{cols_desc}。"
            f"这种参数矩阵念出来没有意义，跳过。"
        )

    # data：两列但右边很短，像代号表
    return f"{opener}，{info.n_rows} 行 {info.n_cols} 列，内容从略。"


_CAP_NUM_RE = re.compile(r"^(表\s*[0-9]+(?:\s*[-–—.．]\s*[0-9]+)*)\s*(.*)$")


def _cap_ref(caption: str) -> str:
    """把「表4-2　常用的字符类**」整理成「表4-2《常用的字符类》」。"""
    cap = (caption or "").replace("**", "").replace("*", "").strip()
    cap = re.sub(r"\s+", " ", cap)
    if not cap:
        return ""
    m = _CAP_NUM_RE.match(cap)
    if m:
        num = re.sub(r"\s+", "", m.group(1))
        title = m.group(2).strip(" 　:：")
        return f"{num}《{title}》" if title else num
    return f"《{cap}》"
