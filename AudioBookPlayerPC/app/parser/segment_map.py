"""阅读页「原文行 ↔ 音频片段」的位置索引。

严格移植 Android 客户端 ``EbookSegmentMap.kt``（``AudioBookPlayer/app/.../parser/EbookSegmentMap.kt``）
的算法规则，保证 PC 端与 Android 端的高亮/滚动定位行为一致。

## 为什么需要它

阅读页显示的是**电子书原文行**，而音频片段是按服务端切段规则（``segmenter.sentences``
= 完整句子）切出来的 —— 两种粒度**边界天然不对齐**：一个片段可能横跨两行，
一行里也可能压着好几个片段。于是「行号 == 段号」这个隐含假设不成立，
按行号直接播放会串音。本模块把每条片段文本逐一定位回整本原文，得到
``段号 → 原文区间``，再换算出 ``段号 ↔ 行号``，供点击播放与高亮使用。

## 定位规则（与 Android 一致）

1. **忽略空白**：比较时丢掉所有空白字符，因此一个片段可以跨行 —— 换行、缩进都不构成障碍。
2. **顺序推进**：第 n 段只从第 n-1 段结束之后开始找（游标只在匹配成功时前移），
   重复出现的句子（叠句/章节标题）不会被前一段抢占。
3. **锚点回退**：整段匹配失败 → 取前 ``ANCHOR_LENGTH`` 字做锚点（**只在游标之后找**）；
   再失败则该段记为「未定位」，**不影响后面的段**。
4. **区间不重叠**：锚点估算的区间可能越过下一段起点，由 :meth:`_clamp_overlaps` 裁齐 ——
   重叠是「点 A 播 B」的直接成因。

## 坐标约定

「全文坐标」= 各行文本用单个 ``\\n`` 连接后的下标。行区间 ``[row_start, row_end)``；
片段区间 ``[segment_start(n), segment_end(n))``。段号从 **1** 起。
"""
from __future__ import annotations

import bisect
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

from app.models.models import Segment

# 锚点长度：整段匹配失败时用来「找到大概位置」的前缀长度
ANCHOR_LENGTH = 8
# 锚点下限：短于这个长度不做锚点匹配（太容易命中无关位置，反而把后续段带偏）
ANCHOR_MIN = 4
# 行连接符：全文坐标里行与行之间恰好占一个字符
ROW_SEPARATOR = "\n"


@dataclass
class _Normalized:
    """去掉所有空白后的文本，外加「归一化下标 → 原文下标」的映射。"""

    text: str
    to_original: List[int]

    @property
    def length(self) -> int:
        return len(self.text)

    def index_of(self, query: str, from_: int = 0) -> int:
        if from_ > len(self.text):
            return -1
        return self.text.find(query, from_)

    def original_index(self, normalized_index: int) -> int:
        if not self.to_original:
            return 0
        idx = min(max(normalized_index, 0), len(self.to_original) - 1)
        return self.to_original[idx]


def _normalize(src: str) -> _Normalized:
    """丢掉所有空白，保留「归一化位置 → 原文位置」映射。

    之所以丢掉空白而不是折叠成单个空格：原文与片段的空白本来就不一致，
    折叠规则稍有出入就会让整段匹配失败；而中文语料里空白不承载语义，丢掉最稳。
    """
    chars: List[str] = []
    mapping: List[int] = []
    for i, ch in enumerate(src):
        if not ch.isspace():
            mapping.append(i)
            chars.append(ch)
    return _Normalized("".join(chars), mapping)


class SegmentMap:
    """原文行与音频片段之间的位置索引。用 :meth:`build` 工厂方法构造。"""

    def __init__(
        self,
        rows: List[str],
        row_starts: List[int],
        row_ends: List[int],
        seg_starts: List[int],
        seg_ends: List[int],
        seg_rows: List[int],
        seg_estimated: List[bool],
    ) -> None:
        self._rows = rows
        self._row_starts = row_starts
        self._row_ends = row_ends
        self._seg_starts = seg_starts   # 下标即段号，index 0 不用
        self._seg_ends = seg_ends
        self._seg_rows = seg_rows       # 段起点所在行
        self._seg_estimated = seg_estimated

    # ---------------- 诊断计数 ----------------

    @property
    def row_count(self) -> int:
        return len(self._row_starts)

    @property
    def segment_count(self) -> int:
        return len(self._seg_starts) - 1

    @property
    def located_count(self) -> int:
        return sum(1 for n in range(1, self.segment_count + 1) if self._seg_starts[n] >= 0)

    @property
    def estimated_count(self) -> int:
        return sum(1 for n in range(1, self.segment_count + 1) if self._seg_estimated[n])

    # ---------------- 行 ↔ 全文坐标 ----------------

    def offset_of_row(self, row: int) -> int:
        """行下标 → 该行首字符的全文坐标；越界返回 -1。"""
        if 0 <= row < len(self._row_starts):
            return self._row_starts[row]
        return -1

    def row_length(self, row: int) -> int:
        if 0 <= row < len(self._row_starts):
            return self._row_ends[row] - self._row_starts[row]
        return 0

    # ---------------- 段 ↔ 全文坐标 ----------------

    def is_located(self, segment_number: int) -> bool:
        return self.segment_start(segment_number) >= 0

    def is_estimated(self, segment_number: int) -> bool:
        return 1 <= segment_number <= self.segment_count and self._seg_estimated[segment_number]

    def segment_start(self, segment_number: int) -> int:
        """段号 → 该段在原文中的起始下标；未定位/越界返回 -1。"""
        if 0 <= segment_number < len(self._seg_starts):
            return self._seg_starts[segment_number]
        return -1

    def segment_end(self, segment_number: int) -> int:
        if 0 <= segment_number < len(self._seg_ends):
            return self._seg_ends[segment_number]
        return -1

    def row_of_segment(self, segment_number: int) -> int:
        """段号 → 该段**起点**所在行下标；未定位/越界返回 -1（滚动定位用）。"""
        if 0 <= segment_number < len(self._seg_rows):
            return self._seg_rows[segment_number]
        return -1

    def rows_of_segment(self, segment_number: int) -> List[int]:
        """段号 → 该段**覆盖到的所有行**（连续升序，含首尾）；未定位返回空列表。"""
        start = self.segment_start(segment_number)
        end = self.segment_end(segment_number)
        if start < 0 or end <= start:
            return []
        first = self._row_index_of_offset(start)
        last = self._row_index_of_offset(end - 1)
        if first < 0 or last < first:
            return []
        return list(range(first, last + 1))

    def spans_in_segment_rows(self, segment_number: int) -> Dict[int, Tuple[int, int]]:
        """段号 → 该段在**它覆盖的每一行**内的行内字符区间 ``{行下标: (起, 止)}``。

        左闭右开、行内相对坐标；供高亮按行分片：整行底色给覆盖的每一行，
        行内句底色给这里的对应区间。完全覆盖不到的行不进结果。
        """
        rows = self.rows_of_segment(segment_number)
        spans: Dict[int, Tuple[int, int]] = {}
        for row in rows:
            span = self.span_in_row(row, segment_number)
            if span is not None:
                spans[row] = span
        return spans

    def span_in_row(self, row: int, segment_number: int) -> Optional[Tuple[int, int]]:
        """段号在指定行内的字符区间 ``(起, 止)``（行内相对、左闭右开）；无交集返回 None。"""
        start = self.segment_start(segment_number)
        end = self.segment_end(segment_number)
        if start < 0 or not (0 <= row < len(self._row_starts)):
            return None
        from_ = max(start, self._row_starts[row]) - self._row_starts[row]
        to = min(end, self._row_ends[row]) - self._row_starts[row]
        if to <= from_:
            return None
        return (from_, to)

    # ---------------- 点击位置 → 段号 ----------------

    def segment_at_offset(self, char_offset: int) -> Optional[int]:
        """全文坐标 → 覆盖该位置的段号；落在段间空白或未定位处返回 ``None``。

        按段号升序返回**首个**覆盖者 —— 这就是「点击文本行，判断属于哪个片段」的判据。
        """
        for n in range(1, self.segment_count + 1):
            s = self._seg_starts[n]
            if s >= 0 and s <= char_offset < self._seg_ends[n]:
                return n
        return None

    def segment_at_or_after_offset(self, char_offset: int) -> Optional[int]:
        """全文坐标 → 该位置起（含）最近的一段；所有段都在其之前结束则返回 ``None``。"""
        for n in range(1, self.segment_count + 1):
            s = self._seg_starts[n]
            if s >= 0 and self._seg_ends[n] > char_offset:
                return n
        return None

    def segment_of_row(self, row: int) -> Optional[int]:
        """行下标 → 该行（按行首位置）应播放的段号；该行之后没有音频返回 ``None``。"""
        offset = self.offset_of_row(row)
        if offset < 0:
            return None
        covering = self.segment_at_offset(offset)
        if covering is not None:
            return covering
        return self.segment_at_or_after_offset(offset)

    # ---------------- 内部：坐标换算 ----------------

    def _row_index_of_offset(self, offset: int) -> int:
        """全文坐标 → 行下标（二分）；落在行尾连接符上时归到下一行。"""
        starts = self._row_starts
        ends = self._row_ends
        if not starts:
            return -1
        # 最后一个 starts[i] <= offset 的 i
        ans = bisect.bisect_right(starts, offset) - 1
        if ans < 0:
            return 0
        if offset > ends[ans] and ans + 1 < len(starts):
            return ans + 1
        return ans

    # ---------------- 工厂方法 ----------------

    @classmethod
    def build(cls, rows: List[str], segments: List[Segment]) -> Optional["SegmentMap"]:
        """建立映射。

        :param rows: 阅读页逐行文本（顺序即显示顺序）
        :param segments: 音频片段（每项含 ``segment_number`` 与 ``text_content``）
        :return: 任一入参为空时返回 ``None``（没有可映射的东西，调用方保持旧行为）
        """
        if not rows or not segments:
            return None

        row_starts: List[int] = [0] * len(rows)
        row_ends: List[int] = [0] * len(rows)
        cursor = 0
        for i, text in enumerate(rows):
            row_starts[i] = cursor
            row_ends[i] = cursor + len(text)
            cursor = row_ends[i] + len(ROW_SEPARATOR)

        # 段号 → 文本，按段号升序
        pairs: List[Tuple[int, str]] = sorted(
            ((s.segment_number, s.text_content) for s in segments if s.segment_number > 0),
            key=lambda p: p[0],
        )
        if not pairs:
            return None

        max_segment = max(number for number, _ in pairs)
        seg_starts = [-1] * (max_segment + 1)
        seg_ends = [-1] * (max_segment + 1)
        seg_estimated = [False] * (max_segment + 1)

        full_text = ROW_SEPARATOR.join(rows)
        cls._locate(full_text, pairs, seg_starts, seg_ends, seg_estimated)
        cls._clamp_overlaps(seg_starts, seg_ends, seg_estimated, max_segment)

        seg_rows = [-1] * (max_segment + 1)
        for n in range(1, max_segment + 1):
            if seg_starts[n] >= 0:
                seg_rows[n] = _row_index_of_offset_static(row_starts, row_ends, seg_starts[n])

        return cls(rows, row_starts, row_ends, seg_starts, seg_ends, seg_rows, seg_estimated)

    # ---------------- 按文本定位行的轻量构造（收藏跳转用）----------------

    @classmethod
    def for_text_lookup(cls, rows: List[str]) -> "SegmentMap":
        """只用于 :meth:`locate_row_by_text` 的轻量构造（单词本「跳转到原书」用）。

        按文本定位行**不需要**「段 ↔ 行」的坐标映射，所以这里只填 ``_rows``，其余数组用
        空占位 —— 这样纯阅读（无音频段）的书也能用同一套文本定位规则：:meth:`build` 在
        segments 为空时会直接返回 ``None``，不适用于收藏跳转场景。
        """
        return cls(list(rows), [], [], [-1], [-1], [-1], [False])

    # ---------------- 内部：定位算法 ----------------

    @staticmethod
    def _locate(
        full_text: str,
        pairs: List[Tuple[int, str]],
        seg_starts: List[int],
        seg_ends: List[int],
        seg_estimated: List[bool],
    ) -> None:
        """逐段顺序定位，结果写入 ``seg_starts``/``seg_ends``（下标即段号，-1 = 未定位）。
        游标只在**匹配成功**时前移 —— 一段失败不该把后面各段一起带偏。
        """
        norm = _normalize(full_text)
        cursor = 0
        for number, text in pairs:
            if number <= 0 or number >= len(seg_starts):
                continue
            query = _normalize(text)
            if query.length == 0:
                continue

            hit = norm.index_of(query.text, cursor)
            if hit >= 0:
                seg_starts[number] = norm.original_index(hit)
                seg_ends[number] = norm.original_index(hit + query.length - 1) + 1
                cursor = hit + query.length
                continue

            # 回退：锚点（只在游标之后找）。整段对不上时用前若干字定住起点，
            # 区间长度按片段长度估算（末尾越界由 _clamp_overlaps 裁掉）。
            if query.length >= ANCHOR_MIN:
                anchor = query.text[: min(ANCHOR_LENGTH, query.length)]
                at = norm.index_of(anchor, cursor)
                if at >= 0:
                    tail = min(norm.length - 1, at + query.length - 1)
                    seg_starts[number] = norm.original_index(at)
                    seg_ends[number] = norm.original_index(tail) + 1
                    seg_estimated[number] = True
                    cursor = at + len(anchor)
                    continue
            # 彻底找不到：保持未定位，且不动游标

    @staticmethod
    def _clamp_overlaps(
        seg_starts: List[int],
        seg_ends: List[int],
        seg_estimated: List[bool],
        max_segment: int,
    ) -> None:
        """把估算区间裁到「下一段的起点」，消除重叠。

        锚点回退只定住**起点**，区间长度按片段文本推算，片段与原文有出入时会越过
        下一段起点 ⇒ 两段交叠 ⇒ 「点 A 播 B」。按段号升序把每段终点压到下一已定位段起点；
        裁完变空（区间 ≤ 起点）则降级为未定位。
        """
        for n in range(1, max_segment + 1):
            if seg_starts[n] < 0:
                continue
            nxt = next((m for m in range(n + 1, max_segment + 1) if seg_starts[m] >= 0), None)
            if nxt is None:
                continue
            if seg_ends[n] > seg_starts[nxt]:
                seg_ends[n] = seg_starts[nxt]
                if seg_ends[n] <= seg_starts[n]:
                    seg_starts[n] = -1
                    seg_ends[n] = -1
                    seg_estimated[n] = False

    # ---------------- 按内容定位行（收藏跳转用）----------------

    def locate_row_by_text(self, text: str, line_number_hint: int = -1) -> int:
        """在本映射的行里按**内容**定位行下标（收藏跳转用）；找不到返回 -1。

        收藏表存的是「当时那一行的行号」，而行号是**切分粒度的函数** —— 改一次行切分
        规则旧行号就指向别的文字。文字内容不随切分规则变化，所以定位必须以内容为准。
        规则与段号定位同源（忽略所有空白后比对）：先整段包含，失败再退前缀锚点；
        多命中时取与 ``line_number_hint``（**从 1 起**，≤0 表示无提示）最接近的那行。
        """
        return self._locate_row_by_text(self._rows, text, line_number_hint)

    @staticmethod
    def _locate_row_by_text(rows: List[str], needle: str, line_number_hint: int = -1) -> int:
        if not rows or not needle or needle.isspace():
            return -1
        query = _normalize(needle)
        if query.length == 0:
            return -1

        candidates = [
            i for i in range(len(rows))
            if len(rows[i]) >= query.length and query.text in _normalize(rows[i]).text
        ]
        if not candidates and query.length >= ANCHOR_MIN:
            anchor = query.text[: min(ANCHOR_LENGTH, query.length)]
            candidates = [
                i for i in range(len(rows))
                if len(rows[i]) >= len(anchor) and anchor in _normalize(rows[i]).text
            ]
        if not candidates:
            return -1
        if len(candidates) == 1 or line_number_hint <= 0:
            return candidates[0]
        preferred = line_number_hint - 1
        return min(candidates, key=lambda idx: abs(idx - preferred))


def _row_index_of_offset_static(starts: List[int], ends: List[int], offset: int) -> int:
    """``SegmentMap._row_index_of_offset`` 的静态版，供 ``build`` 构造期调用。"""
    if not starts:
        return -1
    ans = bisect.bisect_right(starts, offset) - 1
    if ans < 0:
        return 0
    if offset > ends[ans] and ans + 1 < len(starts):
        return ans + 1
    return ans
