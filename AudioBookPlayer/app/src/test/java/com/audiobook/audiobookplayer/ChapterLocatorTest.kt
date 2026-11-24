package com.audiobook.audiobookplayer

import com.audiobook.audiobookplayer.model.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 书内搜索结果「所属章节」定位测试。
 *
 * ## 为什么这块必须有测试
 *
 * 章节名是个**软信息**：算错了不会崩、不会闪、也不影响点击跳转，
 * 只会在结果列表里显示一个错误的章节 —— 代码审查看不出来，真机上也很少有人
 * 注意到，但它会直接把用户带到错误的位置去翻。这类"错了也像真的"的判定，
 * 只能靠用例钉死边界。
 *
 * 覆盖的边界：没有目录 / 命中在首章标题之前 / 正中章首 / 章中间 /
 * 目录乱序 / 无行下标的条目混入 / 多个标题落在同一行。
 */
class ChapterLocatorTest {

    /** 造一条只关心「标题 + 行下标」的目录项，其余字段取默认值。 */
    private fun toc(title: String, paragraphIndex: Int, level: Int = 0) =
        TocEntry(title = title, paragraphIndex = paragraphIndex, level = level)

    /** 三章的标准样书：第 0 行是书名页，10/20/30 行开始各一章。 */
    private val sample = listOf(
        toc("第一章 起风", 10),
        toc("第二章 落雨", 20),
        toc("第三章 天晴", 30)
    )

    @Test
    fun `没有目录时返回空串`() {
        // TXT 书没有目录，此时界面应退化成只显示行号，不能瞎猜一个"第一章"。
        assertEquals("", chapterTitleAt(buildChapterIndex(emptyList()), 5))
    }

    @Test
    fun `命中在首章标题之前时返回空串`() {
        // 书名页、作者页这类内容不属于任何一章。
        val index = buildChapterIndex(sample)
        assertEquals("", chapterTitleAt(index, 0))
        assertEquals("", chapterTitleAt(index, 9))
    }

    @Test
    fun `命中正好落在章首行时归该章`() {
        val index = buildChapterIndex(sample)
        assertEquals("第一章 起风", chapterTitleAt(index, 10))
        assertEquals("第二章 落雨", chapterTitleAt(index, 20))
    }

    @Test
    fun `命中落在章中间时归所在章`() {
        val index = buildChapterIndex(sample)
        assertEquals("第一章 起风", chapterTitleAt(index, 15))
        assertEquals("第二章 落雨", chapterTitleAt(index, 29))
    }

    @Test
    fun `命中在全书最后一行时归最后一章`() {
        val index = buildChapterIndex(sample)
        assertEquals("第三章 天晴", chapterTitleAt(index, 99_999))
    }

    @Test
    fun `目录乱序时也能正确定位`() {
        // 目录顺序来自解析器，并不保证升序；不排序的二分会跳到错误分支，
        // 症状只是"章节名偶尔错一个"，极难发现。
        val shuffled = listOf(
            toc("第三章 天晴", 30),
            toc("第一章 起风", 10),
            toc("第二章 落雨", 20)
        )
        val index = buildChapterIndex(shuffled)
        assertEquals(listOf(10, 20, 30), index.map { it.paragraphIndex })
        assertEquals("第二章 落雨", chapterTitleAt(index, 25))
    }

    @Test
    fun `没有行下标的目录项不参与定位`() {
        // 这类条目只有段号（TTS 任务下发的目录），混进有序列表会破坏二分前提。
        val mixed = listOf(
            TocEntry(title = "只有段号的章", segmentNumber = 7, paragraphIndex = -1),
            toc("第一章 起风", 10),
            TocEntry(title = "", paragraphIndex = 40)
        )
        val index = buildChapterIndex(mixed)
        assertEquals(1, index.size)
        assertEquals("第一章 起风", chapterTitleAt(index, 50))
    }

    @Test
    fun `同一位置有多个标题时取更靠后的那条`() {
        // 「第一部分」与「第一章」常被解析到同一行，越靠后越具体。
        val nested = listOf(
            toc("第一部分 春", 10, level = 0),
            toc("第一章 起风", 10, level = 1)
        )
        val index = buildChapterIndex(nested)
        assertEquals("第一章 起风", chapterTitleAt(index, 10))
        assertEquals("第一章 起风", chapterTitleAt(index, 12))
    }

    @Test
    fun `无序且含无效条目时仍按行下标升序`() {
        // 两个修正同时生效的组合用例：过滤在前、排序在后，顺序不能颠倒。
        val messy = listOf(
            toc("第二章 落雨", 20),
            TocEntry(title = "无行下标", paragraphIndex = -1),
            toc("第一章 起风", 10)
        )
        val index = buildChapterIndex(messy)
        assertEquals(listOf("第一章 起风", "第二章 落雨"), index.map { it.title })
        assertEquals("第一章 起风", chapterTitleAt(index, 19))
        assertEquals("第二章 落雨", chapterTitleAt(index, 21))
    }
}
