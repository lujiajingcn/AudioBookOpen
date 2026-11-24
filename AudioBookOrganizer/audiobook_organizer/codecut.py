"""代码块分析：判断这段代码是什么，并生成**可被原文验证**的一句话概述。

核心原则：只说读得出来的事实。
  * 「执行了 type、ls、cp 三条命令」—— 从会话提示符和加粗输入里取出，是真事；
  * 「用来演示循环」—— 从 while/do/done 判断，是真事；
  * 「这段代码实现了文件监控」—— 规则读不出来，绝不说。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from .classify import clean_ws
from .htmltree import Node
from . import phrasing

#: 会话提示符：``[me@linuxbox ~]$ `` / ``user@host:~$ `` / 行首单个 ``$ ``
PROMPT_RE = re.compile(
    r"^\s*(?:\[[^\]\n]{1,60}\]|[\w.\-]{1,30}@[\w.\-]{1,40}[^\s]{0,40})\s*[$#]\s?"
    r"|^\s*[$#]\s+",
    re.M,
)
#: 纯符号拼的示意图（ASCII art / 盒图）
ASCII_ART_RE = re.compile(r"[+|]\s*-{2,}|-{3,}[+|]|^\s*[|/\\]\s*[|/\\]\s*$", re.M)
#: 行首注释
_COMMENT_RE = re.compile(r"^\s*#\s*(.+?)\s*$")
_CJK_RE = re.compile(r"[\u4e00-\u9fa5]")
_FIRSTWORD_RE = re.compile(r"[A-Za-z_./][\w.\-/]*")


@dataclass
class CodeInfo:
    kind: str                       # session / script / syntax / output / code / ascii
    raw: str
    lines: list[str] = field(default_factory=list)
    commands: list[str] = field(default_factory=list)
    comments: list[str] = field(default_factory=list)
    lang: str = ""
    structure: str = ""
    prompt_lines: int = 0

    @property
    def n_lines(self) -> int:
        return len(self.lines)

    @property
    def primary_command(self) -> str:
        return self.commands[0] if self.commands else ""

    @property
    def cjk_comment(self) -> str:
        """最有引用价值的那条注释：优先中文、长度适中。"""
        for c in self.comments:
            if _CJK_RE.search(c) and 4 <= len(c) <= 60:
                return c
        return ""


# ----------------------------------------------------------------------
# 分析
# ----------------------------------------------------------------------
def _strong_texts(node: Node) -> list[str]:
    """会话块里被加粗的，通常就是用户敲进去的那部分。"""
    out: list[str] = []
    for n in node.iter():
        if n.tag in ("strong", "b"):
            t = clean_ws(n.text())
            if t:
                out.append(t)
    return out


def _extract_commands(raw: str, strongs: list[str]) -> list[str]:
    """按出现顺序还原「执行过的命令」，去重保序。"""
    cmds: list[str] = []

    # 1) 提示符后面紧跟的那一段
    for line in raw.split("\n"):
        m = PROMPT_RE.match(line)
        if not m:
            continue
        rest = line[m.end():].strip()
        if not rest:
            continue
        w = _FIRSTWORD_RE.match(rest)
        if w:
            cmds.append(w.group(0))

    # 2) 提示符行没捞到时，退回到加粗文本
    if not cmds:
        for s in strongs:
            w = _FIRSTWORD_RE.match(s.strip())
            if w:
                cmds.append(w.group(0))

    seen: set[str] = set()
    uniq: list[str] = []
    for c in cmds:
        key = c.lower()
        if key in seen or key in ("sudo", "echo"):
            continue
        seen.add(key)
        uniq.append(c)
    return uniq


def analyze(node: Node) -> CodeInfo:
    """输入 pre（或含 code 的容器），输出 :class:`CodeInfo`。"""
    raw = node.text()
    raw = raw.replace("\r\n", "\n").replace("\r", "\n")
    # 全角空格在代码里就是缩进，保留结构但压缩多重空行
    lines = [ln.rstrip() for ln in raw.split("\n")]
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    body = "\n".join(lines)
    trimmed = [ln for ln in lines if ln.strip()]

    comments = [m.group(1) for m in (_COMMENT_RE.match(ln) for ln in lines) if m]
    prompt_lines = len(PROMPT_RE.findall(body))
    strongs = _strong_texts(node)

    info = CodeInfo(kind="code", raw=body, lines=trimmed, comments=comments,
                    prompt_lines=prompt_lines)

    # --- 排序很重要：示意图 → 会话 → 语法 → 脚本 → 输出 → 代码 ---
    if ASCII_ART_RE.search(body) and prompt_lines == 0:
        info.kind = "ascii"
        return info

    has_shebang = bool(re.search(r"^#!", body, re.M))
    if prompt_lines >= 1 and (strongs or prompt_lines >= 2) and not has_shebang:
        info.kind = "session"
        info.commands = _extract_commands(body, strongs)
        return info

    # 语法模板：极短，且参数是占位符（<em> 在 XHTML 里表示斜体占位）
    has_placeholder = node.find("em") is not None or re.search(r"<\w+>", body)
    if len(trimmed) <= 3 and has_placeholder:
        info.kind = "syntax"
        w = _FIRSTWORD_RE.match(trimmed[0]) if trimmed else None
        info.commands = [w.group(0)] if w else []
        return info

    info.lang = phrasing.lang_of(body)
    info.structure = phrasing.structure_of(body)

    if has_shebang or (info.structure and len(trimmed) >= 4):
        info.kind = "script"
        if not info.lang and has_shebang:
            info.lang = "shell 脚本"
        return info

    if len(trimmed) <= 3 and not info.structure:
        # 一到三行的片段：多半是「命令 + 输出」里的命令或结果
        w = _FIRSTWORD_RE.match(trimmed[0]) if trimmed else None
        if w and phrasing.gloss_of(w.group(0)):
            info.kind = "session"
            info.commands = [w.group(0)]
            return info

    if not info.lang and not info.structure and len(trimmed) >= 2:
        # 一堆短行、没有关键字 ⇒ 多半是输出/数据
        avg = sum(len(x) for x in trimmed) / len(trimmed)
        if avg <= 24 and not any(k in body for k in ("=", "{", "(", ";")):
            info.kind = "output"
            return info

    return info


# ----------------------------------------------------------------------
# 概述
# ----------------------------------------------------------------------
def _list_cmds(cmds: list[str], limit: int = 3) -> str:
    shown = cmds[:limit]
    s = "、".join(shown)
    if len(cmds) > limit:
        s += f" 等共 {len(cmds)} 条命令"
    return s


#: 同一句式连说 949 遍会很折磨耳朵，轮着来
_SESSION_VARIANTS = (
    "{head}演示了 {cmd} 命令，它用来{gloss}",
    "{head}是 {cmd} 的一个例子，它用来{gloss}",
    "{head}试了一下 {cmd} 命令，作用是{gloss}",
)


def summarize(info: CodeInfo, lead: str = "接下来", follow: bool = False,
              variant: int = 0) -> str:
    """生成替代文案。

    lead / follow 由上下文决定：
      * 前一段以「如下所示」结尾时 follow=True —— 不再重复「接下来」；
      * 否则用 lead 起头（可轮换，避免通篇一个词）。
    variant 用来轮换句式：949 个代码块如果都是同一句式，听感会很糟。
    """
    head = "" if follow else lead

    if info.kind == "ascii":
        return "这里有一张用字符拼出的示意图，略过。"

    if info.kind == "syntax":
        cmd = info.primary_command
        if cmd:
            return f"书中给出了 {cmd} 命令的语法格式"
        return "书中给出了这条命令的语法格式"

    if info.kind == "session":
        cmds = info.commands
        if not cmds:
            return f"{head}是几条命令的执行结果，共 {info.n_lines} 行，从略"

        unknown = [c for c in cmds if not phrasing.gloss_of(c)]
        # 词表里没有的，多半是书里自己写的脚本名，说「命令」会显得外行
        if len(cmds) > 1:
            s = f"{head}连续执行了 {_list_cmds(cmds)}"
            gloss = phrasing.gloss_of(cmds[0])
            if gloss:
                s += f"，其中 {cmds[0]} 用来{gloss}"
            return s

        cmd = cmds[0]
        gloss = phrasing.gloss_of(cmd)
        if not gloss and cmd in unknown:
            return f"{head}执行了 {cmd}"
        if not gloss:
            return f"{head}演示了 {cmd} 命令"
        return _SESSION_VARIANTS[variant % len(_SESSION_VARIANTS)].format(
            head=head, cmd=cmd, gloss=gloss
        )

    if info.kind == "script":
        n = info.n_lines
        lang = info.lang or "shell 脚本"
        s = f"{head}是一段 {n} 行的 {lang}"
        if info.structure:
            s += f"，主体是{info.structure}"
        cmt = info.cjk_comment
        if cmt:
            s += f"。脚本注释写着：{cmt.rstrip('。.')}"
        return s

    if info.kind == "output":
        return f"{head}是前面命令的输出，共 {info.n_lines} 行，略过"

    # code：按行数给不同程度的存在感，1 行也说「一段」太夸张
    if info.n_lines <= 1:
        s = f"{head}有一行命令示例"
    elif info.n_lines <= 3:
        s = f"{head}有一小段代码"
    else:
        s = f"{head}是一段 {info.n_lines} 行的代码"
    if info.lang:
        s += f"，{info.lang}"
    if info.structure:
        s += f"，主体是{info.structure}"
    return s
