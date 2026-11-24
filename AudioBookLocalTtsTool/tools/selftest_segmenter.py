r"""切句自检：把「句内换行必须清掉」这条规则钉死在回归里。

背景
----
线上反馈：用 AudioBookLocalTtsTool 合成音频时，id=78 的记录文本是

    当记者后啃过《汉
    书》和《后汉书》 ，完全被乱七八糟的人名和事件淹没了，感觉昏昏欲睡。

《汉书》中间那个换行是 PDF / EPUB 抽文本时的**版面换行**。切句时换行不算句边界
（跨行对话仍算一句），于是这个换行会**留在句子里** ⇒ 送进 TTS 的文本带换行 ⇒
引擎在换行处停顿 ⇒ 听感上像多了一个断句。

所以切句时必须把**句内**换行清掉，且规则要与服务端
`TextSegmenter.cleanInnerNewlines` **逐字一致**（否则两端段号又会错位）：

* 换行两侧都是 ASCII 字母 / 数字 ⇒ 换成**一个空格**（`hello\nworld` → `hello world`，
  直接删会把两个词粘成一个怪词）；
* 其余（中文、书名号、标点）⇒ 连同换行两侧的空白**一起删掉**
  （`《汉\n书》` → `《汉书》`）。

顺带盯住另一条容易漂的口径：**句末空白集必须与 Java 的 `\s` 一致**
（= `[ \t\n\x0B\f\r]`，**不含** U+3000）。Python 的 `\s` 会多匹配全角空格，
两端就会多切/少切段 ⇒ 段号错位。

跑法（在 AudioBookLocalTtsTool 目录下）：

    python tools/selftest_segmenter.py
    python tools/selftest_segmenter.py "E:\\电子书"     # 额外用真实书库回归
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# Windows 控制台默认 GBK：书名里带生僻字时 print 会崩 —— 统一改成 UTF-8 + 替换符兜底。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

from app.core.booktext import extract_text  # noqa: E402
from app.core.segmenter import clean_inner_newlines, sentences  # noqa: E402

# ---------------------------------------------------------------- 断言小工具

_failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print("  [OK]   %s" % name)
    else:
        _failures.append(name + ("：" + detail if detail else ""))
        print("  [FAIL] %s %s" % (name, detail))


def squeeze(text: str) -> str:
    """去掉所有空白 —— 用于验证「切句只动空白、不动实字」。"""
    return re.sub(r"\s+", "", text)


# ---------------------------------------------------------------- 用例

def test_inner_newline_removed() -> None:
    print("[1] 句内换行被去掉（线上事故场景）")
    # 线上 id=78 的真实文本
    got = sentences("当记者后啃过《汉\n书》和《后汉书》 ，完全被乱七八糟的人名和事件淹没了，感觉昏昏欲睡。")
    want = ["当记者后啃过《汉书》和《后汉书》 ，完全被乱七八糟的人名和事件淹没了，感觉昏昏欲睡。"]
    check("《汉\\n书》→《汉书》", got == want, "实际 %r" % (got,))

    # 换行两侧的空白也一并清掉
    check("换行两侧空白一起清掉", clean_inner_newlines("汉 \n 书") == "汉书",
          "实际 %r" % clean_inner_newlines("汉 \n 书"))

    # 换行不是句边界：跨行对话仍然算一句，只是句内换行被清掉
    got = sentences("他说：「你来了。」\n她说：「嗯。」")
    want = ["他说：「你来了。」", "她说：「嗯。」"]
    check("跨行对话仍是两句", got == want, "实际 %r" % (got,))

    # 英文单词被版面换行劈开 ⇒ 用一个空格接上，不能粘成一个词
    check("英文断词 → 空格", clean_inner_newlines("hello\nworld.") == "hello world.",
          "实际 %r" % clean_inner_newlines("hello\nworld."))
    check("数字断行 → 空格", clean_inner_newlines("1\n2") == "1 2",
          "实际 %r" % clean_inner_newlines("1\n2"))
    # 一侧是中文时不算「英文断词」：换行连同两侧空白一起删掉（不粘词即可）
    check("中英混排行 → 删换行", clean_inner_newlines("第 1\n章") == "第 1章",
          "实际 %r" % clean_inner_newlines("第 1\n章"))

    # Windows 换行（\r\n）也要处理
    check("CRLF → LF 后清理", clean_inner_newlines("Windows\r\n换行。") == "Windows换行。",
          "实际 %r" % clean_inner_newlines("Windows\r\n换行。"))

    # 纯 CR（旧 Mac 换行）
    check("CR → LF 后清理", clean_inner_newlines("第一\r第二") == "第一第二",
          "实际 %r" % clean_inner_newlines("第一\r第二"))

    # 句末标点之后的换行本来就属于「句末空白」，切句时已被吃掉
    check("句末换行不残留", sentences("一句话。\n下一句。") == ["一句话。", "下一句。"],
          "实际 %r" % (sentences("一句话。\n下一句。"),))


def test_sentence_tail_matches_java() -> None:
    print("[2] 句末空白集与 Java 的 \\s 一致（全角空格留在句内）")
    # U+3000 全角空格**不是**句末空白 —— Java 的 \s 不含它，Python 的 \s 含它
    got = sentences("你好\u3000。")
    check("全角空格留在句内", got == ["你好\u3000。"], "实际 %r" % (got,))

    # ASCII 空格在**标点之后**会被吃掉（属于句末空白）
    got = sentences("你好。 ")
    check("句末 ASCII 空格被吃掉", got == ["你好。"], "实际 %r" % (got,))

    # 但标点**之前**的空格属于句内空白，必须留着
    got = sentences("你好 。")
    check("句内空格保留", got == ["你好 。"], "实际 %r" % (got,))

    # 英文句点后跟空格 → 空格进下一句的开头时被 trim 掉
    got = sentences("Hello world. Next one.")
    check("英文按句点切句", got == ["Hello world.", "Next one."], "实际 %r" % (got,))

    # 竖直制表符 / 换页符属于 Java \s，也算句末空白
    got = sentences("你好。\x0b")
    check("竖直制表符算句末空白", got == ["你好。"], "实际 %r" % (got,))
    got = sentences("你好。\x0c")
    check("换页符算句末空白", got == ["你好。"], "实际 %r" % (got,))


def test_no_text_loss() -> None:
    print("[3] 不丢字不变式：切句只动空白，不动实字")
    samples = [
        "当记者后啃过《汉\n书》和《后汉书》 ，完全被淹没了。",
        "他说：「你来了。」\n她说：「嗯。」",
        "hello\nworld. The second sentence.",
        "第一章\r\n\r\n内容在这里。",
        "（一）序言 ..........\n（二）正文 .........",
        "他说：「你来了。」\n她说：「嗯。」\nhello\nworld.",
    ]
    for sample in samples:
        joined = "".join(sentences(sample))
        check("实字不变（%r）" % (sample[:16],), squeeze(joined) == squeeze(sample),
              "切句后 %r 原文 %r" % (squeeze(joined)[:30], squeeze(sample)[:30]))

    # 切出来的每一句本身都不该再含换行（这是本次修复的核心承诺）
    for sample in samples:
        bad = [s for s in sentences(sample) if "\n" in s or "\r" in s]
        check("每段都不含换行（%r）" % (sample[:16],), not bad, "残留 %r" % (bad,))


def test_real_books(folder: Path) -> None:
    print("[4] 真实书库回归：%s" % folder)
    books = sorted(folder.glob("*.txt")) + sorted(folder.glob("*.epub"))
    if not books:
        print("  （该目录下没有 .txt/.epub 文件，跳过）")
        return
    for book in books:
        try:
            text = extract_text(book)
        except Exception as exc:  # noqa: BLE001
            check("%s" % book.name[:40], False, "提取失败：%s" % exc)
            continue
        segs = sentences(text)
        bad = [s for s in segs if "\n" in s or "\r" in s]
        check("%s：%d 段，0 段含换行" % (book.name[:36], len(segs)), not bad,
              "残留 %d 段含换行，例：%r" % (len(bad), bad[:1]))
        check("%s：实字不变" % book.name[:36],
              squeeze("".join(segs)) == squeeze(text),
              "切句后长度 %d / 原文 %d" % (len(squeeze("".join(segs))), len(squeeze(text))))


def test_han_book_case() -> None:
    print("[5] 线上样本回归：《汉书》那句必须是完整一句")
    # 《潜规则-中国历史中的真实游戏.txt》里真实存在的句子
    path = Path(r"E:\study_audiobook\WorkSpace_AudioBook\测试电子书copy\潜规则-中国历史中的真实游戏.txt")
    if not path.is_file():
        print("  （找不到样本书 %s，跳过）" % path)
        return
    text = extract_text(path)
    segs = sentences(text)
    hits = [s for s in segs if "啃过" in s]
    check("找到了含「啃过」的段", bool(hits), "共 %d 段，没有命中" % len(segs))
    for hit in hits:
        check("该段含《汉书》且无换行", "《汉书》" in hit and "\n" not in hit and "\r" not in hit,
              "实际 %r" % hit[:60])
    check("整本 0 段含换行", not [s for s in segs if "\n" in s or "\r" in s],
          "共 %d 段" % len(segs))
    print("  （整本 %d 段）" % len(segs))


def main() -> int:
    print("=== 切句自检 ===\n")
    test_inner_newline_removed()
    test_sentence_tail_matches_java()
    test_no_text_loss()
    test_han_book_case()
    for arg in sys.argv[1:]:
        test_real_books(Path(arg))
    print("")
    if _failures:
        print("失败 %d 项：" % len(_failures))
        for item in _failures:
            print("  - %s" % item)
        return 1
    print("全部通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
