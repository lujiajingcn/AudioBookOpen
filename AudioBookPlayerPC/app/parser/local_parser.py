"""阅读器高层解析入口：把一本电子书文件变成「可读的行 + 目录 + 待填充的片段」。

本模块只做**编排**，不含独立的解析逻辑：

1. 调 :func:`app.parser.booktext.extract_text` 把文件读成「自然段列表 + 目录」；
2. 对每个段落调 :func:`app.parser.segmenter.sentences`（服务端同口径）切成**完整句子**；
3. **一句 = 一 = 一个音频片段**：``LineItem.segment_number`` 即服务端 ``audio_segments``
   的段号（从 1 起、与句子序号严格 1:1），供后续建「段号 ↔ 行号」映射与高亮；
4. ``ParseResult.segments`` 初始为空 —— 音频到位后由上层按 ``segment_number``
   填入 :class:`~app.models.models.Segment`，再用 :class:`~app.parser.segment_map.SegmentMap`
   把 ``lines`` 当 ``rows``、这些 ``segments`` 建立精确映射。

``char_start``/``char_end`` 取「各行文本用单个 ``\\n`` 连接后的全文坐标」—— 与
``segment_map.SegmentMap`` 的行连接符约定**完全一致**，两者可互相印证。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List

from app.models.models import LineItem, Segment, TocEntry
from app.parser import booktext, segmenter


@dataclass
class ParseResult:
    """一次解析的产物。"""

    lines: List[LineItem] = field(default_factory=list)
    toc: List[TocEntry] = field(default_factory=list)
    segments: List[Segment] = field(default_factory=list)  # 音频到位后填充
    # 附带信息（便于上层展示 / 调试）
    file_path: str = ""
    extension: str = ""
    paragraphs: List[str] = field(default_factory=list)

    @property
    def segment_count(self) -> int:
        """切出的片段总数（= 句子总数 = 阅读页行数）。"""
        return len(self.lines)

    def line_at_segment(self, segment_number: int) -> int:
        """段号（从 1 起）→ 行下标（从 0 起）；越界返回 -1。"""
        idx = segment_number - 1
        return idx if 0 <= idx < len(self.lines) else -1


def parse_book(file_path: str) -> ParseResult:
    """解析整本书，返回 :class:`ParseResult`。

    提取/解析失败时向上抛出 ``booktext.UnsupportedBookError`` 等异常，交由调用方
    转成界面提示（不吞异常，否则「读不出来」会表现成「正文是空的」）。
    """
    paragraphs, raw_toc = booktext.extract_text(file_path)

    lines: List[LineItem] = []
    para_first_line: Dict[int, int] = {}
    segment_number = 1

    for p_index, paragraph in enumerate(paragraphs):
        # 记录该段首句对应的行下标，供目录把「段落」换算成「行/段」
        para_first_line[p_index] = len(lines)
        for sentence in segmenter.sentences(paragraph):
            lines.append(
                LineItem(
                    index=len(lines),
                    text=sentence,
                    segment_number=segment_number,
                )
            )
            segment_number += 1

    # 全文坐标：各行用单个 "\n" 连接后的下标（与 SegmentMap 的行连接符约定一致）
    offset = 0
    for item in lines:
        item.char_start = offset
        item.char_end = offset + len(item.text)
        offset = item.char_end + 1

    # 目录：paragraph_index 由「段落」重映射为「行下标」，阅读页可直接跳行
    mapped_toc: List[TocEntry] = []
    total_lines = len(lines)
    for entry in raw_toc:
        line_index = para_first_line.get(entry.paragraph_index)
        if line_index is None:
            # toc 段落下标越界（异常输入）：贴到最后一行；空文则归 0
            if total_lines == 0:
                line_index = 0
            else:
                fallback = para_first_line.get(max(para_first_line, default=0), total_lines)
                if entry.paragraph_index > max(para_first_line, default=-1):
                    fallback = total_lines
                line_index = min(max(fallback, 0), max(total_lines - 1, 0))
        mapped_toc.append(
            TocEntry(title=entry.title, level=entry.level, paragraph_index=line_index)
        )

    return ParseResult(
        lines=lines,
        toc=mapped_toc,
        segments=[],
        file_path=str(file_path),
        extension=Path(file_path).suffix.lower().lstrip("."),
        paragraphs=paragraphs,
    )
