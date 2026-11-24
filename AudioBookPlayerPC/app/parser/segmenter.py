"""切句：与 AudioBookServer 的 ``TextSegmenter`` 保持**完全同一口径**。

## 为什么必须逐字复刻，而不是自己另写一套

服务端 ``TextSegmenter.sentences()`` 定义的「片段」是**一条完整句子**，
``audio_segments`` 表里存的就是这些句子的原文，段号 = 句子序号。阅读页要按段号
对齐「文字 ↔ 声音」，所以**片段边界一旦和服务器不一样，同一本书在两端就会错位**：
服务端切 100 句、本机切 87 段，段号对不上，跟读时文字与声音完全串位。

本模块对齐的参考实现：

* 服务端权威版 ``AudioBookServer/.../util/TextSegmenter.java``
* 本地工具版 ``AudioBookLocalTtsTool/app/core/segmenter.py``

两处口径一致，因此本文件同时等价于二者。关键约束：

1. **句末标点 + 其后收尾符号 + 空白**（``。！？!?；;…`` 及闭引号/括号/书名号）
   一并切进同一句 —— 只认句号会把闭引号甩到下一段，甚至切出单字符片段。
2. **ASCII 句点必须前后都不是「点」也不是「数字」** —— 否则 Word 导出的 PDF 目录
   页那种点线引导（``（一）序言 ..........``）会被切成几千条内容为单个 ``.`` 的片段
   （线上事故：占全书段数 56%）。
3. **换行不算句边界** —— 跨行对话仍算一句，否则会切出独占一行的单字符片段。
4. 切出来的句子 **trim 后丢弃空串**；trim 采用 Java ``String.trim()`` 语义
   （去所有码位 ≤ ``U+0020`` 的字符），与 ``str.strip()`` 不同。

``DEFAULT_MAX_LENGTH`` 与切句无关：它是「引擎单次合成文本上限」，只决定
一个超长句在**送引擎时**拆成几小片（``sub_pieces``）。落库、段号、阅读页都以
**整句**为粒度，绝不能用 ``sub_pieces`` 的结果去编号，否则与音频段号错位。
"""
from __future__ import annotations

import re
from typing import List

# ---------------------------------------------------------------------------
# 句末标点模式（与服务端 TextSegmenter.SENTENCE_END 完全一致）
# ---------------------------------------------------------------------------
#
# ⚠️ **空白集必须与 Java 的 ``\s`` 逐字一致**（= ``[ \t\n\x0B\f\r]``）：
# Python 的 ``\s`` 还会匹配 U+3000 等 Unicode 空白，而 Java 的 ``\s`` 不会 ——
# 若把全角空格当句末空白吃掉，两端**段数会不同**、段号错位。全角空格**留在句内**。
_SENTENCE_TAIL = (
    "["
    + re.escape(
        chr(9) + chr(10) + chr(11) + chr(12) + chr(13) + " "
        + "」』”’" + ")" + "]" + "）】》"
    )
    + "]"
)

#   (?<![.]) (?<![0-9])  → 句点前不是「点」也不是「数字」
#   (?!.)    (?![0-9])  → 句点后不是「点」也不是「数字」
SENTENCE_END = re.compile(
    "(?:[。！？]|[!?]|(?<![.])(?<![0-9])[.](?![.])(?![0-9]))" + _SENTENCE_TAIL + "*"
)

# 超长句硬切时的「回退落刀点」候选：次级停顿标点（不含句末标点）
BREAK_FALLBACK_CHARS = "，、；：,;"

# 引擎单次合成的文本长度上限（服务端 TextSegmenter.DEFAULT_MAX_LENGTH）
DEFAULT_MAX_LENGTH = 45

# 尾片并入前一片的阈值（服务端 TextSegmenter.SUB_PIECE_MIN_TAIL）
SUB_PIECE_MIN_TAIL = 4

# 回退落刀点的下限比例（服务端 TextSegmenter.BREAK_FALLBACK_MIN_RATIO）
BREAK_FALLBACK_MIN_RATIO = 0.6

# 英文单词被换行劈开时（hello\nworld）→ 用**一个空格**接上；
# 直接删会把两个词粘成一个（helloworld），读出来是个怪词。
_ASCII_WORD_BREAK = re.compile(
    r"(?<=[0-9A-Za-z])[ \t\u3000]*\n[ \t\u3000]*(?=[0-9A-Za-z])"
)

# 其余换行（中文等）连同两侧空白一起删掉：「《汉\n书》」→「《汉书》」。
_INNER_NEWLINE = re.compile(r"[ \t\u3000]*\n[ \t\u3000]*")


def _java_trim(text: str) -> str:
    """等价于 Java 的 ``String.trim()``：去掉首尾所有码位 ≤ ``U+0020`` 的字符。

    Python 的 ``str.strip()`` 只去空白（不处理 ``\\x01`` 这类控制字符），而服务端的
    ``addTrimmed`` 用的是 Java ``trim()`` —— 两者对「段首带控制字符」的处理会不同，
    进而让段文本与服务器不一致。
    """
    start = 0
    end = len(text)
    while start < end and ord(text[start]) <= 0x20:
        start += 1
    while end > start and ord(text[end - 1]) <= 0x20:
        end -= 1
    return text[start:end]


def clean_inner_newlines(text: str) -> str:
    """去掉句子**内部**的换行（连同紧邻的空白）。规则与服务端逐字一致：

    * 换行两侧都是 ASCII 字母/数字 ⇒ 换成**一个空格**（英文单词不能粘连）；
    * 其余（中文、书名号、标点）⇒ 连同换行两侧的空白**一起删掉**。

    只处理句内换行：句末标点之后的换行本来就属于「句末空白」，切句时已被
    ``SENTENCE_END`` 的尾部字符集吃掉。
    """
    if not text or ("\n" not in text and "\r" not in text):
        return text
    s = text.replace("\r\n", "\n").replace("\r", "\n")
    s = _ASCII_WORD_BREAK.sub(" ", s)
    return _INNER_NEWLINE.sub("", s)


def sentences(text: str) -> List[str]:
    """把正文切成**完整句子**（不限长度，每句原样保留）。

    与服务端 ``TextSegmenter.sentences()`` 逐字对齐：这是「片段」的唯一来源。
    为 ``None``/空时返回空列表。
    """
    if not text:
        return []
    result: List[str] = []
    start = 0
    for match in SENTENCE_END.finditer(text):
        piece = _java_trim(text[start: match.end()])
        if piece:
            result.append(clean_inner_newlines(piece))
        start = match.end()
    tail = _java_trim(text[start:])
    if tail:
        result.append(clean_inner_newlines(tail))
    return result


def _choose_break_point(text: str, max_len: int) -> int:
    """在 ``[max_len*ratio, max_len)`` 区间内从右往左找最近的次级停顿标点，
    返回该标点**后一个位置**（标点留在段尾），从而不把词劈成两半；找不到就硬切。
    """
    limit = min(max_len, len(text))
    floor = int(max_len * BREAK_FALLBACK_MIN_RATIO)
    for i in range(limit - 1, floor - 1, -1):
        if text[i] in BREAK_FALLBACK_CHARS:
            return i + 1
    return max_len


def sub_pieces(sentence: str, max_len: int = DEFAULT_MAX_LENGTH) -> List[str]:
    """把一个（可能超长的）句子拆成「送引擎的小片」，每片 ≤ ``max_len`` 字。

    优先落在句子内部的次级停顿标点（``，、；：,;``）之后，找不到才按上限硬切
    （复用 :func:`_choose_break_point`）。尾片过短则并入前一片。

    不超过 ``max_len`` 的句子原样返回单元素列表。与服务端 ``subPieces`` 对齐。
    """
    if not sentence:
        return []
    s = _java_trim(sentence)
    if not s:
        return []
    if len(s) <= max_len:
        return [s]

    pieces: List[str] = []
    rest = s
    while rest:
        if len(rest) <= max_len:
            pieces.append(rest)
            break
        cut = _choose_break_point(rest, max_len)
        pieces.append(rest[:cut])
        rest = rest[cut:]

    # 尾片过短则并入前一片（单字片单独发一次请求，代价远高于收益）。
    if len(pieces) > 1 and len(pieces[-1]) < SUB_PIECE_MIN_TAIL:
        pieces[-2] += pieces[-1]
        pieces.pop()
    return pieces


def has_speakable_content(text: str) -> bool:
    """该片段是否含至少一个「可朗读」字符（汉字/字母/数字）。

    只含标点/空白/下划线/点线的片段没有可朗读内容：引擎的鲁棒性归一化会把纯下划线
    清成空串（空文本 → 0 帧 → 近乎静音的音频 → 引擎 500）。这道过滤**只用于送引擎前**，
    不参与段落切分 —— 改切分口径会改变段号，那意味着整本书必须重合成。
    """
    return any(ch.isalnum() for ch in (text or ""))
