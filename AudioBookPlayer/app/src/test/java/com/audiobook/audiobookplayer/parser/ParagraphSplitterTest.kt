package com.audiobook.audiobookplayer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TXT 阅读页「段首缩进」与「原文行结构」的单测。
 *
 * ## 背景（线上实例）
 *
 * 书城的《钟鼓楼-节选几段》是一份**手工排版**的 TXT：段首 8 个半角空格、同段续行
 * 4 个半角空格、CRLF 换行、行尾还带一个空格。用户报「阅读页里段落首的缩进丢了」。
 *
 * 根因在 [ParagraphSplitter]：
 * 1. 换行被当成普通空白 —— `findSentenceEnd` 吞「句末标点之后的空白」时把 `\n` 一起
 *    吞了，于是原文 14 行被合并成 5 个大块（100 字打包），段落结构消失；
 * 2. 每段首尾都过 `trim()` —— 段首那 8 个空格就是在这一步没的；
 * 3. 顺带地，合并出来的大块**行内还残留 `\r\n`**，渲染时会出现意料之外的换行。
 *
 * 修法：给 [ParagraphSplitter] 加 `lineBreakAsParagraph`（TXT 传 true），把换行当段落
 * 边界、行首空白单独摘出来贴回本行第一段、只裁行尾。默认值仍是老行为，EPUB 不受影响
 * （EPUB 的换行来自 XHTML 源码缩进，是噪音 —— 见该类参数的注释）。
 *
 * 这些用例全部用**该书真实原文**做判据，而不是自造短句。
 */
class ParagraphSplitterTest {

    /** 原文段首缩进：8 个半角空格 */
    private val indent8 = " ".repeat(8)

    /** 原文同段续行缩进：4 个半角空格 */
    private val indent4 = " ".repeat(4)

    /** 《钟鼓楼-节选几段》原文（与服务器 `/data/audiobook/store_books/其他/` 下的 txt 逐字符一致） */
    private val zhonggulou: String = buildString {
        append("        大约一百多年前。清朝光绪皇帝载?登基不久。 \r\n")
        append("        是一个月黑夜。 \r\n")
        append("        在北京北城，离钟楼、鼓楼不远的一所贝子府中，忽然有一声凄 \r\n")
        append("    厉的惨叫。 \r\n")
        append("        贝子虽是逊于亲王、郡王、贝勒的第四等贵族，但那府第也颇为 \r\n")
        append("    轩昂华丽。值夜的仆人和巡更的更夫听见了那声转瞬即逝的惨叫，慌 \r\n")
        append("    忙行动起来，点燃了许多摇曳著红舌的蜡烛，动用了若干盏羊角提灯， \r\n")
        append("    立即在全府中进行了紧急巡查。回廊曲折、花木蓊翳的后花园自然是 \r\n")
        append("    巡查的重点。 \r\n")
        append("        天上没有半点星光，阵阵小风掠过，厅堂檐角的「铁马」发出杂 \r\n")
        append("    遝的音响。 \r\n")
        append("        被惊动的主持家务的姨娘和府内总管，在议事厅里听取了各路仆 \r\n")
        append("    人的搜寻报告：各处门户皆无异常，整个邸宅没有发现任何侵入的人 \r\n")
        append("    和物。 ")
    }

    /** 期望输出：14 段，逐段等于原文对应行（保留行首缩进、裁掉行尾空白与 CRLF） */
    private val expectedRows: List<String> = listOf(
        indent8 + "大约一百多年前。清朝光绪皇帝载?登基不久。",
        indent8 + "是一个月黑夜。",
        indent8 + "在北京北城，离钟楼、鼓楼不远的一所贝子府中，忽然有一声凄",
        indent4 + "厉的惨叫。",
        indent8 + "贝子虽是逊于亲王、郡王、贝勒的第四等贵族，但那府第也颇为",
        indent4 + "轩昂华丽。值夜的仆人和巡更的更夫听见了那声转瞬即逝的惨叫，慌",
        indent4 + "忙行动起来，点燃了许多摇曳著红舌的蜡烛，动用了若干盏羊角提灯，",
        indent4 + "立即在全府中进行了紧急巡查。回廊曲折、花木蓊翳的后花园自然是",
        indent4 + "巡查的重点。",
        indent8 + "天上没有半点星光，阵阵小风掠过，厅堂檐角的「铁马」发出杂",
        indent4 + "遝的音响。",
        indent8 + "被惊动的主持家务的姨娘和府内总管，在议事厅里听取了各路仆",
        indent4 + "人的搜寻报告：各处门户皆无异常，整个邸宅没有发现任何侵入的人",
        indent4 + "和物。"
    )

    /** 按 [chunk] 个字符一块喂入（chunk <= 0 表示整本一次喂入） */
    private fun split(text: String, chunk: Int = 0): List<String> {
        val splitter = ParagraphSplitter(lineBreakAsParagraph = true)
        if (chunk <= 0) splitter.feed(text) else text.chunked(chunk).forEach { splitter.feed(it) }
        return splitter.finish()
    }

    @Test
    fun `段首缩进与原文行结构都保留（线上那本书的真实原文）`() {
        val rows = split(zhonggulou)

        assertEquals("原文 14 行应产出 14 段（修复前是 5 段）", expectedRows.size, rows.size)
        expectedRows.forEachIndexed { i, expect ->
            assertEquals("第 ${i + 1} 段", expect, rows[i])
        }
        // 段首缩进真的在：8 空格 6 处（段首行）、4 空格 8 处（同段续行）
        // ⚠️ 两个断言必须互斥：`startsWith(4 空格)` 对 8 空格的行同样成立
        assertEquals(6, rows.count { it.startsWith(indent8) })
        assertEquals(8, rows.count { it.startsWith(indent4) && !it.startsWith(indent8) })
    }

    @Test
    fun `行内不再残留 CR LF（修复前大块内部还夹着原文换行）`() {
        val rows = split(zhonggulou)
        assertTrue(rows.none { it.contains('\r') || it.contains('\n') })
        // 行尾空白也要裁掉：原文每行末尾都有一个空格
        assertTrue(rows.none { it != it.trimEnd() })
    }

    @Test
    fun `分块喂入与一次喂入结果完全一致（含 CRLF 与缩进被切在两块之间）`() {
        val whole = split(zhonggulou)
        // chunk = 1 会覆盖所有可能的切点：`\r` 与 `\n` 之间、8 个空格的中间、半个汉字处
        for (chunk in listOf(1, 2, 3, 5, 8, 13, 64, 8192)) {
            assertEquals("chunk=$chunk 的分块结果应与整本一致", whole, split(zhonggulou, chunk))
        }
    }

    @Test
    fun `空行不产出空段`() {
        val rows = split("${indent8}第一段。\r\n\r\n\r\n${indent8}第二段。")
        assertEquals(listOf(indent8 + "第一段。", indent8 + "第二段。"), rows)
    }

    @Test
    fun `超长行按上限拆段，缩进只贴第一段`() {
        val rows = split(indent8 + "甲".repeat(160) + "\r\n" + indent4 + "乙")

        assertEquals(3, rows.size)
        assertEquals(indent8.length + 100, rows[0].length)
        assertTrue(rows[0].startsWith(indent8))
        assertEquals(60, rows[1].length)
        assertFalse("续段不该再带缩进", rows[1].startsWith(" "))
        assertEquals(indent4 + "乙", rows[2])
    }

    @Test
    fun `全角空格缩进同样保留`() {
        val rows = split("　　全角缩进的一段。\r\n　　第二段。")
        assertEquals(listOf("　　全角缩进的一段。", "　　第二段。"), rows)
    }

    @Test
    fun `末尾没有换行时最后一行也要产出`() {
        val rows = split("${indent8}第一段。\r\n${indent4}没有换行结尾的第二段。")
        assertEquals(listOf(indent8 + "第一段。", indent4 + "没有换行结尾的第二段。"), rows)
    }

    /**
     * 本次改动的**关键假设**：改的是「行」的粒度（5 行 -> 14 行），而「段号 ↔ 行号」的
     * 映射由 [EbookSegmentMap] 按**忽略空白**的文本定位完成，所以点击播放、目录跳转、
     * 断点续播这些依赖映射的功能都不该受影响。
     *
     * 段文本取自线上库 `audio_segments where task_id=1`（15 段，含被服务端切在段中间的
     * CRLF —— 段 13 末尾的 `\r` 与段 14 开头的 `\n` 原本是同一个换行）。
     */
    @Test
    fun `改行粒度不影响段号定位：服务端 15 段仍能全部落到新的 14 行里`() {
        val rows = split(zhonggulou)
        assertEquals(14, rows.size)

        val segments = listOf(
            1 to "大约一百多年前。清朝光绪皇帝载?登基不久。是一个月黑夜。",
            2 to "在北京北城，离钟楼、鼓楼不远的一所贝子府中，",
            3 to "忽然有一声凄 \r\n    厉的惨叫。",
            4 to "贝子虽是逊于亲王、郡王、贝勒的第四等贵族，",
            5 to "但那府第也颇为 \r\n    轩昂华丽。",
            6 to "值夜的仆人和巡更的更夫听见了那声转瞬即逝的惨叫，",
            7 to "慌 \r\n    忙行动起来，点燃了许多摇曳著红舌的蜡烛，",
            8 to "动用了若干盏羊角提灯， \r\n    立即在全府中进行了紧急巡",
            9 to "查。",
            10 to "回廊曲折、花木蓊翳的后花园自然是 \r\n    巡查的重点。",
            11 to "天上没有半点星光，阵阵小风掠过，厅堂檐角的「铁马」发出杂 \r",
            12 to "遝的音响。",
            13 to "被惊动的主持家务的姨娘和府内总管，在议事厅里听取了各路仆 \r",
            14 to "\n    人的搜寻报告：各处门户皆无异常，",
            15 to "整个邸宅没有发现任何侵入的人 \r\n    和物。"
        )

        val map = EbookSegmentMap.build(rows, segments)!!

        assertEquals("段号定位不该受行粒度变化影响", segments.size, map.locatedCount)
        assertEquals(0, map.rowOfSegmentNumber(1))   // 段 1 跨第 1、2 行
        assertEquals(2, map.rowOfSegmentNumber(2))   // 段 2 从第 3 行开始
        assertEquals(4, map.rowOfSegmentNumber(4))   // 段 4 从第 5 行开始
        // 段 15 起点是「整」，落在**第 13 行**（该行末）；它换行之后的「和物。」才在第 14 行
        // —— 行号取的是**段起点所在行**，不是段结束行（rowOfSegmentNumber 的语义）
        assertEquals(12, map.rowOfSegmentNumber(15))
    }

    // ---------------- 默认模式（EPUB 走这条）的回归保护 ----------------

    @Test
    fun `默认模式仍是老行为：换行当空白、段首被裁、短句合并`() {
        val splitter = ParagraphSplitter()
        splitter.feed("        他说。\n    他没说。")
        val rows = splitter.finish()

        // 两行的缩进都被 trim，且两句话被合进同一段（≤100 字）—— 这正是 EPUB 需要的行为
        assertEquals(listOf("他说。他没说。"), rows)
    }

    @Test
    fun `默认模式的超长句硬切段保持原样（既有约定，别改）`() {
        val splitter = ParagraphSplitter()
        splitter.feed("甲".repeat(250))
        val rows = splitter.finish()

        assertEquals(3, rows.size) // 100 + 100 + 50
        assertEquals(100, rows[0].length)
        assertEquals(100, rows[1].length)
        assertEquals(50, rows[2].length)
    }
}
