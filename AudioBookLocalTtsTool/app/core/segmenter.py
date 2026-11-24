"""切句：与 AudioBookServer 的 TextSegmenter 保持同一口径。

## 为什么必须复刻而不是自己另写一套

服务端 `TextSegmenter.sentences()` 定义的「片段」是**一条完整句子**，
且 `audio_segments` 表里存的就是这些句子的原文。客户端播放时按段号对齐
「文字 ↔ 声音」，所以**片段边界一旦和服务器不一样，同一本书在两端就会错位**：
服务端存 100 句、工具存 87 段，段号对不上，播放时文字与声音完全对不上号。

因此这里逐条对齐服务端的实现
（AudioBookServer/src/main/java/com/audiobook/server/util/TextSegmenter.java）：

  - 句末标点 + 其后的收尾符号（闭引号/闭括号/书名号）+ 其后的空白一并切进同一句：
    「…。」这种「句号后紧跟闭引号」极常见，只认句号会把闭引号甩到下一段，
    甚至切出单字符片段 —— 片段是独立合成的，用户会听到一段只念一个引号的声音。
  - ASCII 句点必须前后都不是「点」也不是「数字」：Word 导出的中文 PDF 目录页
    普遍用点线引导（`（一）序言 ..........`），无约束地把 `.` 当句末标点，
    会把整本目录切出几千条内容为单个 `.` 的片段（线上事故：占全书段数 56%）。
  - 换行**不算**句边界：跨行对话仍算一句，否则会切出独占一行的单字符片段。
  - 切出来的句子 trim 后丢弃空串。

## 超长句怎么办

服务端在**送引擎前**还会把超长句拆成小于等于 45 字的小片（`subPieces`），
每片单独合成再拼回一条音频。工具这边由 MOSS 引擎自己按 token 预算分块
（`voice_clone_max_text_tokens`），落库仍是「一条句子 = 一段」，
与服务端的存储口径一致。这里保留 `sub_pieces`，需要时可对齐细节。
"""

from __future__ import annotations

import re

# 句末标点之后的收尾字符集：空白 + 右引号类 + 右括号类。
# 用 re.escape 统一转义（Python 的 re 不支持 POSIX 那种 "]" 打头的写法）。
#
# ⚠️ **空白集必须与 Java 的 `\s` 逐字一致**（= `[ \t\n\x0B\f\r]`）：
# Python 的 `\s` 还会匹配 U+3000 等 Unicode 空白，而 Java 的 `\s` 不会 ——
# 早年这里多写了一个 `chr(0x3000)`，于是「《后汉书》 ，完全被…」这种**全角空格**
# 在工具侧被当成句末空白吃掉、在服务端侧留着 ⇒ 两端**段数不同**（工具少 6 段）
# ⇒ 段号错位。本机 320 条样本交叉验证实测：Java 2785 句 / 工具 2779 句。
# 现在按 Java 的字符集列举，全角空格**留在句内**（它不是句末空白）。
_SENTENCE_TAIL = (
    "["
    + re.escape(
        chr(9) + chr(10) + chr(11) + chr(12) + chr(13) + " "
        + "」』”’" + ")" + "]" + "）】》"
    )
    + "]"
)

# 与服务端 TextSegmenter.SENTENCE_END 完全一致
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


def _java_trim(text: str) -> str:
    """等价于 Java 的 `String.trim()`：去掉首尾所有码位 ≤ U+0020 的字符。

    Python 的 `str.strip()` 只去空白（不处理 \\x01 这类控制字符），
    而服务端的 `TextSegmenter.addTrimmed` 用的是 Java trim() ——
    两者对「段首带控制字符」的处理会不同，导致段文本与服务器不一致。
    """
    start = 0
    end = len(text)
    while start < end and ord(text[start]) <= 0x20:
        start += 1
    while end > start and ord(text[end - 1]) <= 0x20:
        end -= 1
    return text[start:end]


# 英文单词被换行劈开时（hello\nworld）→ 用空格接上。
# 直接删会把两个词粘成一个（helloworld），读出来是个怪词。
_ASCII_WORD_BREAK = re.compile(
    r"(?<=[0-9A-Za-z])[ \t\u3000]*\n[ \t\u3000]*(?=[0-9A-Za-z])"
)

# 其余换行（中文等）→ 连同两侧空白一起删掉。
_INNER_NEWLINE = re.compile(r"[ \t\u3000]*\n[ \t\u3000]*")


def clean_inner_newlines(sentence: str) -> str:
    """去掉句子**内部**的换行（连同紧邻的空白）。

    ## 为什么必须清

    PDF / EPUB / MOBI 抽出来的正文里，换行常常只是**版面换行**，会把词或书名
    劈成两半：

        当记者后啃过《汉
        书》和《后汉书》……

    切句时换行不算句边界（跨行对话仍算一句），所以这个换行会**留在句子里**，
    而送进 TTS 的文本里带换行 ⇒ 引擎在换行处停顿 ⇒ 听感上像多了一个断句
    （线上反馈：「《汉书》中间有个换行符，导致合成音频时有停顿」）。

    ## 规则（与服务端 `TextSegmenter.cleanInnerNewlines` 逐字一致）

    * 换行两侧都是 ASCII 字母/数字 ⇒ 换成**一个空格**（英文单词不能粘连）；
    * 其余（中文、书名号、标点）⇒ 连同换行两侧的空白**一起删掉**，
      于是「《汉\\n书》」变成「《汉书》」。

    只处理句内换行：句末标点之后的换行本来就属于「句末空白」，
    在切句时就已经被 `SENTENCE_END` 的尾部字符集吃掉了。
    """
    if "\n" not in sentence and "\r" not in sentence:
        return sentence
    s = sentence.replace("\r\n", "\n").replace("\r", "\n")
    s = _ASCII_WORD_BREAK.sub(" ", s)
    return _INNER_NEWLINE.sub("", s)


def sentences(text: str | None) -> list[str]:
    """把正文切成完整句子（不限长度，每句原样保留）。

    与服务端 `TextSegmenter.sentences()` 逐字对齐。
    """
    if not text:
        return []
    result: list[str] = []
    start = 0
    for match in SENTENCE_END.finditer(text):
        piece = _java_trim(text[start : match.end()])
        if piece:
            result.append(clean_inner_newlines(piece))
        start = match.end()
    tail = _java_trim(text[start:])
    if tail:
        result.append(clean_inner_newlines(tail))
    return result


def _choose_break_point(text: str, max_length: int) -> int:
    """在 [max_length*ratio, max_length) 区间内从右往左找次级停顿标点。

    找不到就按 max_length 硬切。优先在标点后落刀是为了不把词劈成两半
    （实测「农民」「带领」被硬切劈开的线上事故）。
    """
    limit = min(max_length, len(text))
    floor = int(max_length * BREAK_FALLBACK_MIN_RATIO)
    for i in range(limit - 1, floor - 1, -1):
        if text[i] in BREAK_FALLBACK_CHARS:
            return i + 1
    return max_length


def sub_pieces(sentence: str, max_length: int = DEFAULT_MAX_LENGTH) -> list[str]:
    """把超长句拆成「送引擎的小片」，尾片过短则并入前一片。

    与服务端 `TextSegmenter.subPieces()` 对齐。落库仍按整句（一段），
    这里只是给「需要自己控制单次请求长度」的场景用。
    """
    if not sentence:
        return []
    s = sentence.strip()
    if not s:
        return []
    if len(s) <= max_length:
        return [s]

    pieces: list[str] = []
    rest = s
    while rest:
        if len(rest) <= max_length:
            pieces.append(rest)
            break
        cut = _choose_break_point(rest, max_length)
        pieces.append(rest[:cut])
        rest = rest[cut:]

    if len(pieces) > 1 and len(pieces[-1]) < SUB_PIECE_MIN_TAIL:
        pieces[-2] += pieces[-1]
        pieces.pop()
    return pieces


def has_readable_content(text: str) -> bool:
    """片段里是否含有至少一个可朗读字符（汉字/字母/数字）。

    只含标点/空白/下划线的片段没有可朗读内容：引擎的鲁棒性归一化会把纯下划线
    清成空串（空文本 → 0 帧 → 近乎静音的音频），服务端也做了同样的过滤。
    """
    return any(ch.isalnum() for ch in (text or ""))
