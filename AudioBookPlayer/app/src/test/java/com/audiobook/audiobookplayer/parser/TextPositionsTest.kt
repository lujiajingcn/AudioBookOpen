package com.audiobook.audiobookplayer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPUB 目录定位坐标的回归测试。
 *
 * 背景：目录条目只知道「属于哪一章」，要跳转就得把「章」换算成「段落下标」。
 * 换算要求章节侧与段落侧**同源**，历史实现两端不同源：
 *
 * - 章节侧用章节原文的**真实字符长度**累加；
 * - 段落侧用 `paragraphs[i].length + 1` 累加 —— 属**压缩坐标**，因为
 *   [ParagraphSplitter] 切分时会丢掉句末标点后的空白与每句首尾的空白。
 *
 * 段落坐标偏小 ⇒ 映射出的段落下标**偏大**（越往后越明显）⇒ 表现为「点目录跳过头」。
 *
 * 现在两侧统一为 [TextPositions] 的「非空白坐标」。本测试同时断言：
 * 1. 新坐标能定位到「包含该章开头的段落」；
 * 2. 旧的混用坐标（真实章节长度 vs 压缩段落位置）确实会**跳到更后面**，防止有人改回去。
 */
class TextPositionsTest {

    /** 模拟 EpubParser 的喂入方式：逐章 feed(chapter + "\n") */
    private fun paragraphsOf(chapters: List<String>): List<String> {
        val splitter = ParagraphSplitter()
        chapters.forEach { splitter.feed(it + "\n") }
        return splitter.finish()
    }

    // 每句 9 个 ASCII 字母 + 句号 = 10 字；每句后接一个换行
    private fun chapterBody(sentences: Int, head: String = ""): String {
        val sb = StringBuilder(head)
        repeat(sentences) { sb.append("abcdefghi。\n") }
        return sb.toString()
    }

    @Test
    fun `非空白坐标能定位到包含该章开头的段落`() {
        val chapter1 = chapterBody(100)
        val chapter2 = chapterBody(100, head = "第二章开头乙。")
        val chapters = listOf(chapter1, chapter2)

        val paragraphs = paragraphsOf(chapters)
        val positions = TextPositions.paragraphPositions(paragraphs)

        // 逐章累加「非空白字符数」，与 EpubParser 里的做法一致
        var pos = 0
        val spinePositions = chapters.map { ch ->
            val p = pos
            pos += TextPositions.countNonBlank(ch)
            p
        }

        // 第二章开头所在段落：
        // 第一章 100 句、每 10 句一段 → 10 段；带 head 的第二章首段从下标 10 开始
        val idx = TextPositions.indexOfContainingParagraph(spinePositions[1], positions)
        assertEquals(10, idx)
        assertTrue(
            "第二章应定位到包含其开头的段落，实际: ${paragraphs[idx]}",
            paragraphs[idx].contains("第二章开头乙")
        )

        // 首章必然定位到第 0 段
        assertEquals(0, TextPositions.indexOfContainingParagraph(spinePositions[0], positions))
    }

    @Test
    fun `旧的压缩坐标会跳到更后面（防止改回混用坐标）`() {
        val chapters = listOf(chapterBody(100), chapterBody(100), chapterBody(100), chapterBody(100))
        val paragraphs = paragraphsOf(chapters)

        // —— 新坐标：章节与段落都用「非空白字符数」 ——
        val newPositions = TextPositions.paragraphPositions(paragraphs)
        var newPos = 0
        val newSpine = chapters.map { ch ->
            val p = newPos
            newPos += TextPositions.countNonBlank(ch)
            p
        }

        // —— 旧坐标（复现缺陷）：章节用真实字符长度，段落用 length + 1 累加 ——
        val oldPositions = IntArray(paragraphs.size + 1)
        var old = 0
        for (i in paragraphs.indices) {
            oldPositions[i] = old
            old += paragraphs[i].length + 1
        }
        oldPositions[paragraphs.size] = old
        var oldPos = 0
        val oldSpine = chapters.map { ch ->
            val p = oldPos
            oldPos += ch.length + 1
            p
        }

        // 四章内容等长且规则，真实段落下标应为 0 / 10 / 20 / 30
        val expected = listOf(0, 10, 20, 30)
        expected.forEachIndexed { i, exp ->
            assertEquals(
                "第 ${i + 1} 章用新坐标应定位到第 $exp 段",
                exp,
                TextPositions.indexOfContainingParagraph(newSpine[i], newPositions)
            )
        }

        // 末章：旧坐标必须跳到更后面（缺陷本体的方向性断言）
        val newIdx = TextPositions.indexOfContainingParagraph(newSpine[3], newPositions)
        val oldIdx = TextPositions.indexOfContainingParagraph(oldSpine[3], oldPositions)
        assertTrue(
            "旧坐标应系统性偏大（跳过头），实际 new=$newIdx old=$oldIdx",
            oldIdx > newIdx
        )
    }

    @Test
    fun `边界：空段落列表与单段落不会越界`() {
        assertEquals(0, TextPositions.indexOfContainingParagraph(0, IntArray(1)))
        assertEquals(0, TextPositions.indexOfContainingParagraph(12345, IntArray(1)))

        val single = TextPositions.paragraphPositions(listOf("只有一段。"))
        assertEquals(2, single.size)
        assertEquals(0, single[0])
        assertEquals(5, single[1]) // 哨兵 = 非空白字符总数：只、有、一、段、。共 5 个

        // 位置落在任意处都不得越界
        assertEquals(0, TextPositions.indexOfContainingParagraph(0, single))
        assertEquals(0, TextPositions.indexOfContainingParagraph(99, single))
    }

    @Test
    fun `非空白计数忽略换行与缩进`() {
        assertEquals(0, TextPositions.countNonBlank(""))
        assertEquals(0, TextPositions.countNonBlank(" \n\t\r\n  "))
        assertEquals(3, TextPositions.countNonBlank("甲\n乙\t 丙"))
    }
}
