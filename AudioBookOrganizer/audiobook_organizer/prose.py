"""正文清洗与衔接：把「看起来能读」的文字变成「听起来不别扭」的文字。"""

from __future__ import annotations

import re

from .classify import clean_ws
from .htmltree import Node

#: 页眉页脚残留：「| 第 4 章　操作文件与目录」「第 12 页」「12」
_PAGE_HEADER_RE = re.compile(r"^\s*[|｜]\s*(第\s*\d+\s*[章节].{0,40})$")
_PAGE_NO_RE = re.compile(r"^\s*[|｜]?\s*(第\s*)?\d{1,4}\s*(页|/\s*\d+)?\s*[|｜]?\s*$")
#: 纯装饰线
_RULE_RE = re.compile(r"^[\s\-–—_=~*·・.|+]{3,}$")
#: 行内 URL
_URL_RE = re.compile(r"(https?://|ftp://|www\.)[^\s，。；）)】\]]+", re.I)
#: 脚注角标：[1] [12] ¹ 
_FOOTNOTE_RE = re.compile(r"\[\d{1,3}\]|【\d{1,3}】")
#: 连续标点
_REPEAT_PUNCT_RE = re.compile(r"([，。、；：！？])\1+")
#: 步骤编号：1． / 1. / （1） / ①
_STEP_RE = re.compile(r"^\s*(?:[（(]?(\d{1,2})[）)．.、,]\s*|[①②③④⑤⑥⑦⑧⑨⑩]\s*)(.{2,})$")
#: 停顿标记：作者用 ··· 表示略去
_ELLIPSIS_RE = re.compile(r"[·••]{2,}|\.{3,}[。]?")

_CN_NUM = "零一二三四五六七八九十"


def step_number(text: str) -> tuple[int, str] | None:
    m = _STEP_RE.match(text)
    if not m:
        return None
    if m.group(1):
        return int(m.group(1)), m.group(2).strip()
    ch = text.strip()[0]
    idx = "①②③④⑤⑥⑦⑧⑨⑩".find(ch)
    return (idx + 1, text.strip()[1:].strip()) if idx >= 0 else None


def cn_number(n: int) -> str:
    if 0 <= n <= 10:
        return _CN_NUM[n] if n < 10 else "十"
    if n < 20:
        return "十" + _CN_NUM[n - 10]
    if n < 100:
        tens, ones = divmod(n, 10)
        return _CN_NUM[tens] + "十" + (_CN_NUM[ones] if ones else "")
    return str(n)


def clean_prose(text: str, drop_urls: bool = True) -> str:
    """段落的朗读化清洗。返回空串表示这段该丢。"""
    s = text.replace("\u00a0", " ").replace("\u3000", " ")
    s = s.replace("\r", "\n")

    m = _PAGE_HEADER_RE.match(s)
    if m:
        return ""
    if _PAGE_NO_RE.match(s) and len(s.strip()) <= 8:
        return ""
    if _RULE_RE.match(s):
        return ""

    if drop_urls:
        s = _URL_RE.sub("", s)
    s = _FOOTNOTE_RE.sub("", s)
    s = _ELLIPSIS_RE.sub("，", s)
    s = s.replace("**", "").replace("\\*", "")

    s = _REPEAT_PUNCT_RE.sub(r"\1", s)
    s = re.sub(r"[ \t]+", " ", s)
    s = re.sub(r"\s*\n\s*", "", s)
    s = re.sub(r"（\s*）|\(\s*\)", "", s)
    s = s.strip(" \u3000\n")

    # 去掉孤立的对勾/方框等排版符号
    if re.fullmatch(r"[□☐■▪▫•·○●◇◆★☆✓✔→←↑↓\s]+", s):
        return ""

    if len(s) <= 1:
        return ""
    return s


def ensure_ending(s: str) -> str:
    """段末补句号，避免 TTS 把两段连成一口气。"""
    if not s:
        return s
    if s[-1] in "。！？…；：、,.!?;:）)】」』\"'”’":
        return s
    return s + "。"


def tidy_inline(node: Node) -> None:
    """就地清理段落内部的节点：去链接、去图标、去无意义 span。"""
    for child in list(node.iter()):
        if child is node:
            continue
        if child.tag == "a":
            # 保留文字，去掉跳转属性（朗读不需要 href）
            for attr in list(child.attrs):
                if attr != "class":
                    child.attrs.pop(attr, None)
        elif child.tag == "img":
            child.detach()
        elif child.tag in ("sup", "sub") and len(clean_ws(child.text())) <= 2:
            child.detach()
        elif child.tag in ("span", "font") and not child.classes:
            child.tag = "span"


# ----------------------------------------------------------------------
# 插图
# ----------------------------------------------------------------------
def image_summary(caption: str, alt: str, lead: str = "这里", follow: bool = False,
                  figure_no: str = "") -> str:
    head = "" if follow else lead
    cap = clean_ws(caption)
    if cap:
        return f"{head}配了一张插图，标题是：{cap}。"
    if figure_no:
        return f"{head}配有一张插图，就是{figure_no}，具体画面请查阅原书。"
    a = clean_ws(alt)
    # alt 常见为 TLCL02_01 / 文件路径之类，没有朗读价值
    if a and len(a) >= 6 and re.search(r"[\u4e00-\u9fa5]{2,}", a):
        return f"{head}配了一张插图，说明是：{a}。"
    return f"{head}配有一张示意图，内容从略。"


# ----------------------------------------------------------------------
# 衔接
# ----------------------------------------------------------------------
def chapter_intro(title: str) -> str:
    """章首引导句。只在标题后面没有导语时用。"""
    t = clean_ws(title).strip("　 ")
    if not t:
        return ""
    return f"{t}。"


_CHAPTER_NO_RE = re.compile(r"^第\s*[0-9〇零一二三四五六七八九十百]+\s*[章节部篇]")


def chapter_outro(next_title: str) -> str:
    t = clean_ws(next_title).strip("　 ")
    if not t:
        return ""
    # 「下一章我们来看第 30 章 故障诊断」听着别扭，标题自带编号就别再套词
    if _CHAPTER_NO_RE.match(t):
        return f"这一章就到这里。接下来是{t}。"
    return f"这一章就到这里。下一章我们来看{t}。"


def book_front_matter(book_title: str) -> str:
    return (
        f"这是《{book_title}》的有声化精简版。"
        "为了便于收听，书中的代码清单、命令输出、表格和插图，"
        "都已经改写成了文字说明；需要逐行对照的地方，请翻看原书。"
    )
