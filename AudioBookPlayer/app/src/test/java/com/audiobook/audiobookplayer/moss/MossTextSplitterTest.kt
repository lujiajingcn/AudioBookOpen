package com.audiobook.audiobookplayer.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MossTextSplitter] 与服务端 `TextSegmenter.subPieces` 的行为契约。
 *
 * 这些用例是**两端一致性的锁**：客户端与服务端必须切出相同的片段，
 * 否则同一本书在两个平台上合成的段落对不上。
 */
class MossTextSplitterTest {

    @Test
    fun `短段原样返回单片`() {
        val text = "这是一句不超过三十字的话"
        val pieces = MossTextSplitter.subPieces(text)
        assertEquals(1, pieces.size)
        assertEquals(text, pieces[0])
    }

    @Test
    fun `恰好三十字不拆`() {
        val text = "一".repeat(30)
        assertEquals(1, MossTextSplitter.subPieces(text).size)
    }

    @Test
    fun `三十四个字拆成两片`() {
        // 34 = 30 + 4，尾片恰好等于 SUB_PIECE_MIN_TAIL ⇒ 保留拆分。
        // ⚠️ 不能拿 31 字来验证「超上限必拆」：31 拆出来是 [30, 1]，
        // 尾片只有 1 字会被并回前片，最终仍是 1 片（见下面那条用例）。
        // 当时我就是在这儿把期望值写成了 2，测试红了三轮。
        val pieces = MossTextSplitter.subPieces("一".repeat(34))
        assertEquals(2, pieces.size)
        assertEquals(30, pieces[0].length)
        assertEquals(4, pieces[1].length)
    }

    @Test
    fun `拆分后不丢字符`() {
        // 不变式：拼接所有小片必须等于原文（trim 后）。丢一个标点就是内容损坏。
        val original = "这是一个很长的句子，它包含了多个逗号，需要被拆分成若干个小片，" +
            "最后还要保证所有字符都能完整地保留下来，一个都不能少，否则朗读出来的内容就与原文不符了。"
        val pieces = MossTextSplitter.subPieces(original)
        assertEquals(original, pieces.joinToString(""))
        assertTrue("应该被拆成多片，实际 ${pieces.size}", pieces.size > 1)
    }

    @Test
    fun `每片不超过上限`() {
        val original = "一".repeat(200)
        val pieces = MossTextSplitter.subPieces(original)
        // 尾片允许并入后超过 30，最坏 33
        assertTrue("存在超长片: ${pieces.map { it.length }}", pieces.all { it.length <= 33 })
    }

    @Test
    fun `优先在标点处落刀而不是硬切`() {
        // 逗号在第 25 个字符后，硬切会落在 30（把词劈开）
        val text = "一".repeat(25) + "，" + "二".repeat(40)
        val pieces = MossTextSplitter.subPieces(text)
        assertEquals(26, pieces[0].length)
        assertTrue("标点应留在段尾", pieces[0].endsWith("，"))
    }

    @Test
    fun `尾片过短并入前一片`() {
        // 拆出 [30, 1] 的形状：尾片只有一个字，不值得单独跑一次解码
        val text = "甲".repeat(30) + "乙"
        val pieces = MossTextSplitter.subPieces(text)
        assertEquals(1, pieces.size)
        assertEquals(31, pieces[0].length)
    }

    @Test
    fun `无可朗读内容的片被判为不可朗读`() {
        // 2026-09-27 线上事故：纯下划线会被上游归一化成空串 → 0 帧 → 引擎抛异常
        assertTrue(!MossTextSplitter.hasSpeakableContent("_".repeat(63)))
        assertTrue(!MossTextSplitter.hasSpeakableContent("   "))
        assertTrue(!MossTextSplitter.hasSpeakableContent(""))
        assertTrue(!MossTextSplitter.hasSpeakableContent(null))
        assertTrue(!MossTextSplitter.hasSpeakableContent("....."))
    }

    @Test
    fun `含字母或数字即视为可朗读`() {
        assertTrue(MossTextSplitter.hasSpeakableContent("第 1 章"))
        assertTrue(MossTextSplitter.hasSpeakableContent("Hello"))
        assertTrue(MossTextSplitter.hasSpeakableContent("第1章：楔子"))
    }

    @Test
    fun `纯标点不算可朗读`() {
        // 与服务端 TextSegmenter.hasSpeakableContent 同一口径（存在字母或数字才算）。
        // 纯标点的片送去引擎只换回一段近乎静音的音频，还要白花一次解码 ——
        // 这是刻意与 SUB_PIECE_MIN_TAIL 同向的取舍，不是漏判。
        assertFalse(MossTextSplitter.hasSpeakableContent("。！？，"))
        assertFalse(MossTextSplitter.hasSpeakableContent("——"))
    }

    @Test
    fun `空输入返回空列表`() {
        assertEquals(0, MossTextSplitter.subPieces("").size)
        assertEquals(0, MossTextSplitter.subPieces("   ").size)
    }
}