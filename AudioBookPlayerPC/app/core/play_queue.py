"""播放队列窗口：把「本机可得的音频段」裁成一条**从起播段开始的连续链**。

## 为什么必须是连续的
书城书 / 云端任务是**逐段**下载的，本机的段号集合天然会带缺口
（实测某本书本机只有 9–26 与 35–49，服务端有 1–119）。

旧实现直接把「本机所有已下载段按段号排序」当播放队列，于是：
  - 播到 26 直接跳到 35 —— 正文与声音错位（用户听到的是后面章节的内容）；
  - 「边下载边播放」新落盘的段一律 ``append`` 到队尾，排在 49 之后 ⇒
    27–31 会在 49 之后才播，36–40 甚至可能重复出现。
结果就是「不能连播」：跳段、乱序、播到某处再也接不上。

所以队列的构造规则收敛到这一个纯函数模块：**只放连续段**，
缺口由「按需下载下一段」补，补齐后再 :func:`extend` 追加。
"""
from __future__ import annotations

from typing import Dict, List, Mapping, Optional, Sequence

# available: {段号: 本地路径}；只关心键（段号）是否可得。
Available = Mapping[int, str]


def build_window(available: Available, start: int,
                 limit: Optional[int] = None) -> List[int]:
    """从 ``start`` 起、在 ``available`` 里连续可得的最长段号序列（升序）。

    ``start`` 本身不可得时返回空列表 —— 调用方据此去下载这一段，
    而不是「往后找一个能播的」把中间的内容跳掉。

    :param limit: 最多取多少段（0/None 表示不限）
    """
    if start is None or start <= 0:
        return []
    out: List[int] = []
    n = int(start)
    while n in available:
        out.append(n)
        if limit and len(out) >= limit:
            break
        n += 1
    return out


def extend(queued: Sequence[int], available: Available) -> List[int]:
    """以队尾 +1 为起点继续取连续可得段；返回**新增**的段号（可能为空）。

    ``queued`` 为空时返回空列表（没有队尾可供延展，应由 :func:`build_window` 重建）。
    """
    if not queued:
        return []
    last = int(queued[-1])
    return build_window(available, last + 1)


def is_contiguous(nums: Sequence[int]) -> bool:
    """段号序列是否严格连续升序（单测/自检用）。"""
    for a, b in zip(nums, nums[1:]):
        if int(b) != int(a) + 1:
            return False
    return True
