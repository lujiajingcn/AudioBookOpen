package com.audiobook.audiobookplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 句级高亮的判句金标。
 *
 * 这些用例锁的是「高亮边界落在哪」—— 边界错一格，用户就会觉得
 * 高亮"多亮/少亮了半个字"，而这类问题在真机上很难复查（要一边听一边盯屏幕）。
 *
 * 朗读按**句**入队：一条 utterance = [ReadAloudSentences.sentenceRanges] 给出的一个区间，
 * 引擎播到哪一条就高亮哪一个区间，所以这里的切分结果直接决定屏上亮的是哪一截。
 */
class ReadAloudSentencesTest {

    @Test
    fun splitsTwoChineseSentences() {
        val text = "第一句。第二句！"
        assertEquals(listOf(0..3, 4..7), ReadAloudSentences.sentenceRanges(text))
    }

    @Test
    fun keepsClosingQuoteWithSentence() {
        // 他说：“走吧。”我们跟上。
        val text = "他说：“走吧。”我们跟上。"
        // 句末标点「。」在 6，紧随的收尾引号「”」并入本句 ⇒ 0..7；引号之后是新的一句
        assertEquals(listOf(0..7, 8..12), ReadAloudSentences.sentenceRanges(text))
    }

    @Test
    fun handlesConsecutiveTerminators() {
        val text = "真的吗？！我不信。"
        // 「？！」连写，两句末标点一起收束
        assertEquals(listOf(0..4, 5..8), ReadAloudSentences.sentenceRanges(text))
    }

    @Test
    fun paragraphWithoutTerminatorIsOneSentence() {
        val text = "这一整段没有句号所以只能整段高亮"
        assertEquals(listOf(0..(text.length - 1)), ReadAloudSentences.sentenceRanges(text))
    }

    @Test
    fun emptyTextReturnsEmptyList() {
        assertEquals(emptyList<IntRange>(), ReadAloudSentences.sentenceRanges(""))
        assertEquals(emptyList<IntRange>(), ReadAloudSentences.sentenceRanges("   \n  "))
    }

    @Test
    fun englishTerminatorsWork() {
        val text = "First one. Second one!"
        assertEquals(listOf(0..9, 11..21), ReadAloudSentences.sentenceRanges(text))
    }

    @Test
    fun decimalPointIsNotSentenceEnd() {
        // 3.14 里的点后面紧跟数字，不能当句末；整句只有结尾的「。」才算
        val text = "圆周率约等于 3.14。它是无理数。"
        assertEquals(listOf(0..11, 12..17), ReadAloudSentences.sentenceRanges(text))
    }

    @Test
    fun leadingAndTrailingWhitespaceExcluded() {
        // 行首全角缩进 / 行尾空格不该被点亮（高亮范围直接取自区间）
        val text = "　　他走了。我留下。 "
        //           0123456789A  ← 0/1 是缩进，2 才是「他」；A 是行尾空格
        assertEquals(listOf(2..5, 6..9), ReadAloudSentences.sentenceRanges(text))
    }

    @Test
    fun multiSentenceParagraphSplitInOrder() {
        // 一行 90 字的长段落正是 P2-10 的场景：必须切成多句，且顺序、首尾相接
        val text = "第一句话在这里。第二句话在这里！第三句话在这里？"
        val ranges = ReadAloudSentences.sentenceRanges(text)
        assertEquals(3, ranges.size)
        assertEquals(0, ranges.first().first)
        assertEquals(text.length - 1, ranges.last().last)
        for (i in 0 until ranges.size - 1) {
            assertEquals("第 $i 句与第 ${i + 1} 句之间不该有空隙", ranges[i + 1].first, ranges[i].last + 1)
        }
    }
}
