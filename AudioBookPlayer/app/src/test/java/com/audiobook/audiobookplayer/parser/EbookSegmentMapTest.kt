package com.audiobook.audiobookplayer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「原文行 ↔ 音频片段」定位算法的单测。
 *
 * 这是本次改动的核心风险点：只要本地原文与服务端切段所用文本不同源（编码差异、
 * 解析差异、空白差异），映射就会**静默错位** —— 页面看着正常，点击却播错段落。
 * 所以这里逐条钉住：跨行、忽略空白、重复句不错配、单段失败不带偏后续、行/段互换。
 */
class EbookSegmentMapTest {

    /** 某段在原文中的实际文本（已去掉空白，便于与片段文本直接对比） */
    private fun locatedText(rows: List<String>, map: EbookSegmentMap, segmentNumber: Int): String {
        val full = rows.joinToString("\n")
        return full.substring(map.segmentStart(segmentNumber), map.segmentEnd(segmentNumber))
            .replace(WHITESPACE, "")
    }

    private fun normalized(text: String) = text.replace(WHITESPACE, "")

    @Test
    fun `片段区间可以跨行，原文的换行不构成障碍`() {
        // 行 0 内部带一个 CRLF（TXT 路径原样保留 \r\n，这是线上真实情况）
        val rows = listOf(
            "红菜汤的香味，\r\n从厨房里飘送过来。",
            "少有的晴空，融着太阳的暖意。",
            "他没有说话。"
        )
        val segments = listOf(
            1 to "红菜汤的香味，从厨房里飘送过来。",
            2 to "少有的晴空，",
            3 to "融着太阳的暖意。",
            4 to "他没有说话。"
        )
        val map = EbookSegmentMap.build(rows, segments)!!

        assertEquals(4, map.locatedCount)
        for ((number, text) in segments) {
            assertEquals("段 $number 定位到的原文应与片段文本一致", normalized(text), locatedText(rows, map, number))
        }
        // 段 1 跨了行 0 内部的换行，仍应整段落在行 0
        assertEquals(0, map.rowOfSegmentNumber(1))
        assertEquals(1, map.rowOfSegmentNumber(2))
        assertEquals(1, map.rowOfSegmentNumber(3))
        assertEquals(2, map.rowOfSegmentNumber(4))
    }

    @Test
    fun `重复出现的句子按顺序推进，不会被前一段抢占`() {
        val rows = listOf("他说过。他又说了一遍。他说。")
        val map = EbookSegmentMap.build(
            rows,
            listOf(1 to "他说过。", 2 to "他又说了一遍。", 3 to "他说。")
        )!!

        assertEquals(0, map.segmentStart(1))
        assertEquals(4, map.segmentStart(2))
        // 若没有「顺序推进」，第三段会命中下标 0 的那个「他说」前缀
        assertEquals(11, map.segmentStart(3))
        assertEquals(3, map.locatedCount)
    }

    @Test
    fun `整段匹配失败时用锚点回退，且不把后续段带偏`() {
        val rows = listOf("今天天气很好，我们去公园散步，回家了。", "明天会下雨吗？")
        val segments = listOf(
            1 to "今天天气很好，我们去公园散步，然后回家。",  // 与原文不一致，只能靠前 8 字锚定
            2 to "明天会下雨吗？"
        )
        val map = EbookSegmentMap.build(rows, segments)!!

        assertTrue("锚点回退后仍应算定位成功", map.isLocated(1))
        assertEquals("前 8 字锚点应命中行首", 0, map.segmentStart(1))
        // 关键：段 1 是估算区间，不能影响段 2 的精确匹配
        assertEquals(2, map.locatedCount)
        assertEquals("段 2 起点应等于行 1 在全文中的位置", map.offsetOfRow(1), map.segmentStart(2))
        assertEquals(normalized("明天会下雨吗？"), locatedText(rows, map, 2))
    }

    @Test
    fun `定位失败的段保持未定位，游标不后移`() {
        val rows = listOf("甲乙丙丁戊己庚辛。", "壬癸。")
        val map = EbookSegmentMap.build(
            rows,
            listOf(1 to "完全不相干的文字内容。", 2 to "甲乙丙丁戊己庚辛。", 3 to "壬癸。")
        )!!

        assertFalse("原文里根本没有的段应未定位", map.isLocated(1))
        assertEquals(-1, map.segmentStart(1))
        assertEquals(-1, map.rowOfSegmentNumber(1))
        assertEquals(2, map.locatedCount)
        assertEquals(0, map.segmentStart(2))
        assertEquals(map.offsetOfRow(1), map.segmentStart(3))
    }

    @Test
    fun `行与段互相换算：一行里的多个段都归到该行`() {
        val rows = listOf(
            "红菜汤的香味，\r\n从厨房里飘送过来。",
            "少有的晴空，融着太阳的暖意。",
            "他没有说话。"
        )
        val map = EbookSegmentMap.build(
            rows,
            listOf(
                1 to "红菜汤的香味，从厨房里飘送过来。",
                2 to "少有的晴空，",
                3 to "融着太阳的暖意。",
                4 to "他没有说话。"
            )
        )!!

        // 行 → 应播放的段号：取覆盖行首的那一段
        assertEquals(1, map.segmentOfRow(0))
        assertEquals(2, map.segmentOfRow(1))
        assertEquals(4, map.segmentOfRow(2))

        // 行内区间：段 2 是行 1 的前 6 个字，段 3 紧随其后
        assertEquals(0 until 6, map.spanInRow(1, 2))
        assertEquals(6 until 14, map.spanInRow(1, 3))
        // 段 1 跨了行 0 内部的换行，在行 0 里应覆盖整行
        assertEquals(0 until map.rowLength(0), map.spanInRow(0, 1))
        // 段与行无交集时不返回区间
        assertNull(map.spanInRow(0, 2))
    }

    @Test
    fun `点击落在段间空白时，取其后最近的一段`() {
        val rows = listOf("红菜汤的香味，\r\n从厨房里飘送过来。", "他没有说话。")
        val map = EbookSegmentMap.build(
            rows,
            listOf(1 to "红菜汤的香味，从厨房里飘送过来。", 2 to "他没有说话。")
        )!!

        val gap = map.segmentEnd(1)  // 行间连接符所在的位置，不属于任何一段
        assertEquals(-1, map.segmentAtOffset(gap))
        assertEquals("落空时应顺延到下一段", 2, map.segmentAtOrAfterOffset(gap))

        // 段内任意位置都应解析到该段
        assertEquals(1, map.segmentAtOffset(map.segmentStart(1)))
        assertEquals(1, map.segmentAtOffset(map.segmentEnd(1) - 1))
        assertEquals(2, map.segmentAtOffset(map.segmentStart(2)))
    }

    @Test
    fun `该行之后没有音频时不给段号`() {
        val rows = listOf("有音频的一行。", "没有音频的尾巴。")
        val map = EbookSegmentMap.build(rows, listOf(1 to "有音频的一行。"))!!

        assertEquals(1, map.segmentOfRow(0))
        assertEquals(-1, map.segmentOfRow(1))
        assertEquals(1, map.locatedCount)
    }

    @Test
    fun `缺少任一侧输入时不建立映射`() {
        assertNull(EbookSegmentMap.build(emptyList(), listOf(1 to "文字。")))
        assertNull(EbookSegmentMap.build(listOf("文字。"), emptyList()))
    }

    // ---------- 收藏跳转：按内容定位行 ----------

    @Test
    fun `收藏按内容定位：段首缩进与行内空白都不构成障碍`() {
        // 真实排版：段首 8 个半角空格缩进、续行 4 个（《钟鼓楼-节选几段》线上原文）
        val rows = listOf(
            "        大约一百多年前。清朝光绪皇帝载?登基不久。",
            "    是一个月黑夜。在北",
            "        贝子虽是逊于亲王、郡王、贝勒的第四等贵族，"
        )

        // 用户只选中了行 2 中间的几个字（不含行首缩进）
        assertEquals(2, EbookSegmentMap.locateRowByText(rows, "逊于亲王、郡王、贝勒", -1))
        // 少选尾部、多带行内空白，都应命中
        assertEquals(0, EbookSegmentMap.locateRowByText(rows, "大约一百多年前。清朝光绪", -1))
        assertEquals(0, EbookSegmentMap.locateRowByText(rows, "  大约一百多年前。  ", -1))
    }

    @Test
    fun `行切分粒度变化后，旧收藏按内容命中而不是按已漂移的行号`() {
        // 新版本的行列表：目标文字在第 5 行（下标 4）
        val rows = listOf("甲甲甲甲。", "乙乙乙乙。", "丙丙丙丙。", "丁丁丁丁。", "    钟鼓楼的钟声。")

        // 收藏时存的是旧粒度的行号 3（那时整本只切了 3~5 行，含义完全不同）
        val hit = EbookSegmentMap.locateRowByText(rows, "钟鼓楼的钟声。", 3)

        // 若直接信行号（下标 2 = 行号 3）就会跳到「丙丙丙丙。」—— 这正是要修掉的缺陷
        assertEquals("必须按内容命中", 4, hit)
    }

    @Test
    fun `内容重复时用行号提示选最接近的那处`() {
        val rows = listOf("他说。", "填充。", "填充。", "他说。")

        // 无提示 ⇒ 取第一处（与段号定位的「顺序推进」取向一致）
        assertEquals(0, EbookSegmentMap.locateRowByText(rows, "他说。", -1))
        // 有提示 ⇒ 取与提示最接近的那处；行号虽会漂移，但「大概在书的什么位置」仍然有效
        assertEquals(3, EbookSegmentMap.locateRowByText(rows, "他说。", 4))
        assertEquals(0, EbookSegmentMap.locateRowByText(rows, "他说。", 1))
    }

    @Test
    fun `整段对不上时退 8 字锚点，仍能落到正确行`() {
        val rows = listOf("红菜汤的香味，从厨房里飘送过来。", "他没有说话。")

        // 老收藏里存的是服务端片段文本（与原文只差一两个字），整段比对必然失败
        assertEquals(0, EbookSegmentMap.locateRowByText(rows, "红菜汤的香味，从厨房里飘送过来了。", 1))
    }

    @Test
    fun `找不到时返回 -1，且过短的内容不做锚点匹配`() {
        val rows = listOf("他说过。他又说了一遍。")

        assertEquals(-1, EbookSegmentMap.locateRowByText(rows, "书里根本没有的这句话。", 1))
        // 短于锚点下限：宁可判未命中，也不要拿 1~2 个字去乱命中（会把跳转带到无关位置）
        assertEquals(-1, EbookSegmentMap.locateRowByText(rows, "甲乙", 1))
        assertEquals(-1, EbookSegmentMap.locateRowByText(rows, "   ", 1))
        assertEquals(-1, EbookSegmentMap.locateRowByText(emptyList(), "他说过。", 1))
    }







    // ---------- 方案 A：整行底色覆盖该段涉及的每一行 ----------

    /**
     * 线上真实夹具（《沉重的翅膀-节选.txt》，14 行 / 32 段，设备 logcat 与之一致）。
     *
     * 钉住这次修掉的错位：片段横跨两行时，**段首行常常只是上一行行尾的一个标点**，
     * 整行底色曾经只画在那一行上。修法 = 底色覆盖该段涉及的每一行。
     *
     * 行首缩进原样保留（`\\u3000`）—— 它不参与定位（归一化丢空白），
     * 但**参与行内区间的坐标**，写丢了就对不上。
     *
     * ⚠️ 下文注释里的「行 N」一律是**行下标（0 基）**，与 `rows` 列表一致；
     * 与人看的「第 N 行」差 1（段 13 = 第 6 行尾那个句号 + 第 7 行开头那段）。
     */
    @Test
    fun `跨行段的整行底色覆盖两行，重心行必亮`() {
        val rows = listOf(
            "\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000一",
            "\u3000\u3000令人馋涎欲滴的红菜汤的香味，从厨房里飘送过来。案板上，还响着切菜刀轻快的节奏。",
            "\u3000\u3000也许因为身体已经恢复了健康，叶知秋的心情就像窗外那片冬日少有的晴空，融着太阳的暖意。",
            "\u3000\u3000发了几天烧，身子软软的，嘴里老有一股苦味，什么也吃不下去。",
            "\u3000\u3000厨房里送过来的香味，诱发着叶知秋的食欲。她跟许多善良的人一样，一点儿顺心的小事，都会使她加倍地感到生活的乐趣。",
            "\u3000\u3000比方说，一个好天气；一封盼望已久的来信；看了一部好电影；电车上有个吊儿郎当的小青年给老太太让了座……现在呢，只是因为这晴朗的天；病后的好胃口；莫征周到而又不露形迹的关切。",
            "\u3000\u3000多亏莫征。如果没有他，谁能这样细心地照料她呢?抓药、煎药、变着法儿地调换着伙食的花样……但这番感慨莫征是不要听的，他会拿眼睛翻她，还会不屑地从鼻子里往外喷冷气儿，好像她是卖梨膏糖的。",
            "\u3000\u3000她高兴。不由得想说两句无伤大雅的废话——你叫它耍贫嘴也行，或是唱几嗓子。她试着咕咕噜噜地哼了几句，不行，嗓子是嘶哑的，还带着齄齄的鼻音，两个鼻管里仍旧塞满了没有打扫干净的浊物。",
            "\u3000\u3000她索然地发了一会儿呆，便收起了心。真的，一个人，即使在自己家里，也不能太过放肆。这种放纵自己的行为，如果成为一种习惯，然后不知不觉地带到办公室，或者是带到公共场合里去，就会引起莫名其妙的指责或非议。",
            "何况她在别人眼里，已经是个行为荒诞、不合时宜的人物。",
            "\u3000\u3000她愣怔了一会儿，突然想起了久已忘记的法文，不禁高声地问了一句：“今天中午吃什么?”",
            "\u3000\u3000莫征在厨房用法文嚷道：“红菜汤、腊肠和面包。”",
            "\u3000\u3000这孩子真不赖，竟然没有忘记。这当然因为他自小生活在一个有教养的家庭。",
            "\u3000\u3000有教养的家庭?——他现在什么也没有了，真正地成了一个孤儿，就像她一样。",
        )
        val segments = listOf(
            1 to "\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000\u3000一 \r\n\u3000\u3000令人馋涎欲滴的",
            2 to "红菜汤的香味，从厨房里飘送过来。",
            3 to "案板上，还响着切菜刀轻快的节奏。",
            4 to "\u3000\u3000也许因为身体已经恢复了健康，叶知秋的心情就像窗外那片冬日",
            5 to "少有的晴空，融着太阳的暖意。",
            6 to "\u3000\u3000发了几天烧，身子软软的，嘴里老有一股苦味，",
            7 to "什么也吃不下去。\u3000\u3000厨房里送过来的香味，诱发着叶知秋的食欲。",
            8 to "她跟许多善良的人一样，一点儿顺心的小事，",
            9 to "都会使她加倍地感到生活的乐趣。",
            10 to "\u3000\u3000比方说，一个好天气；一封盼望已久的来信；看了一部好电影；",
            11 to "电车上有个吊儿郎当的小青年给老太太让了座……现在呢，",
            12 to "只是因为这晴朗的天；病后的好胃口；莫征周到而又不露形迹的关切",
            13 to "。\u3000\u3000多亏莫征。如果没有他，谁能这样细心地照料她呢?",
            14 to "抓药、煎药、变着法儿地调换着伙食的花样……但这番感慨莫征是不",
            15 to "要听的，他会拿眼睛翻她，还会不屑地从鼻子里往外喷冷气儿，",
            16 to "好像她是卖梨膏糖的。\u3000\u3000她高兴。",
            17 to "不由得想说两句无伤大雅的废话——你叫它耍贫嘴也行，",
            18 to "或是唱几嗓子。",
            19 to "她试着咕咕噜噜地哼了几句，不行，嗓子是嘶哑的，",
            20 to "还带着齄齄的鼻音，两个鼻管里仍旧塞满了没有打扫干净的浊物。",
            21 to "\u3000\u3000她索然地发了一会儿呆，便收起了心。",
            22 to "真的，一个人，即使在自己家里，也不能太过放肆。",
            23 to "这种放纵自己的行为，如果成为一种习惯，",
            24 to "然后不知不觉地带到办公室，或者是带到公共场合里去，",
            25 to "就会引起莫名其妙的指责或非议。",
            26 to "何况她在别人眼里，已经是个行为荒诞、不合时宜的人物。",
            27 to "\u3000\u3000她愣怔了一会儿，突然想起了久已忘记的法文，",
            28 to "不禁高声地问了一句：“今天中午吃什么?",
            29 to "” \r\n\u3000\u3000莫征在厨房用法文嚷道：“红菜汤、腊肠和面包。",
            30 to "” \r\n\u3000\u3000这孩子真不赖，竟然没有忘记。",
            31 to "这当然因为他自小生活在一个有教养的家庭。\u3000\u3000有教养的家庭?",
            32 to "——他现在什么也没有了，真正地成了一个孤儿，就像她一样。",
        )
        val map = EbookSegmentMap.build(rows, segments)!!

        assertEquals(14, map.rowCount)
        assertEquals(32, map.locatedCount)

        // 段 13：起点是行 5 **行尾那个句号**，其余 24 个字
        //（把行 6 行首那两个全角缩进也算进去 —— 它是段文本的一部分，归一化时才丢）
        // 在行 6 ⇒ 两行都要亮。
        assertEquals("段首行仍是行 5（滚动定位用它）", 5, map.rowOfSegmentNumber(13))
        assertEquals(5..6, map.rowsOfSegmentNumber(13))
        val spans13 = map.spansInRowsOfSegment(13)
        assertEquals(setOf(5, 6), spans13.keys)
        assertEquals("行 5 里只剩行尾那个句号",
            (map.rowLength(5) - 1) until map.rowLength(5), spans13[5])
        // 行 6 只有**开头 25 个字**属于这一段，其余归后面的段 ——
        // 整行底色亮整行（行级），句内底色只到该段自己的那一截（行内级）。
        assertEquals(0 until 25, spans13[6])

        // 段 7：行 4 占 8 字、行 5 占 22 字 —— 重心在下一行
        assertEquals(3..4, map.rowsOfSegmentNumber(7))
        // 段 1：「一」独占行 0（章节分隔），正文从行 1 开始
        assertEquals(0..1, map.rowsOfSegmentNumber(1))
        // 段 16 也跨行，但重心**就在**段首行 —— 不跨行的段不能因此多亮一行
        assertEquals(6..7, map.rowsOfSegmentNumber(16))
        // 不跨行的段仍是单行（与旧行为一致）
        assertEquals(1..1, map.rowsOfSegmentNumber(2))

        // ★ 验收判据（与设备端 H2 同源）：每段的**字符重心行**必须落在覆盖行集合内。
        //   修复前 段 1/7/13/29/30 这 5 段不满足 —— 底色画在上一行，正文在下一行。
        for (n in 1..32) {
            val rowsOf = map.rowsOfSegmentNumber(n)!!
            val spans = map.spansInRowsOfSegment(n)
            val center = spans.maxByOrNull { it.value.last - it.value.first + 1 }!!.key
            assertTrue("段 $n 的字符重心行 $center 必须被点亮", center in rowsOf)
            assertEquals("段 $n 的覆盖行应与区间键一致", rowsOf.toList(), spans.keys.toList())
        }
    }

    @Test
    fun `锚点估算的区间越过下一段时必须被裁到下一段起点`() {
        // 段 1 整段匹配不上（原文没有「然后」），只能靠前 8 字锚点定位。
        // 但它的区间长度是按**整段 14 字**估算的，而锚点只推进 8 个字符 ——
        // 于是估算区间一路伸到原文 offset 15，越过了段 2 的起点 11。
        // 后果：offset 11（行 1 开头，文本是「子丑寅卯辰巳。」= 段 2）同时被
        // 段 1 的区间 [0,15) 覆盖，而 segmentAtOffset 升序返回**首个**覆盖者
        // ⇒ 点行 1 的开头播的是段 1，即「点 A 播 B」。
        val rows = listOf("甲乙丙丁戊己庚辛壬癸。", "子丑寅卯辰巳。")
        val segments = listOf(
            1 to "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午。",
            2 to "子丑寅卯辰巳。"
        )
        val map = EbookSegmentMap.build(rows, segments)!!

        assertTrue("锚点回退后段 1 仍算定位成功", map.isLocated(1))
        assertEquals("段 2 精确命中行 1 开头", map.offsetOfRow(1), map.segmentStart(2))
        assertEquals(
            "段 1 的估算区间不得越过段 2 的起点",
            map.offsetOfRow(1), map.segmentEnd(1)
        )
        // ★ 核心判据：点行 1 的开头必须播段 2，而不是段 1
        assertEquals(
            "点行 1 开头应播段 2（修复前返回 1）",
            2, map.segmentAtOffset(map.offsetOfRow(1))
        )
        assertEquals(2, map.segmentOfRow(1))
        // 裁剪不能反过来把段 1 也搞没：它仍然覆盖行 0
        assertEquals(0..0, map.rowsOfSegmentNumber(1))
    }

    @Test
    fun `定位结果的区间两两不重叠`() {
        // 混合场景：一段精确、一段锚点估算、一段彻底失败。
        // 重叠会让「升序首个覆盖者」返回错段，是错位的直接成因，所以
        // 这里不只验最终点播结果，还验**任意两段区间不交叠**这个不变量。
        val rows = listOf(
            "甲乙丙丁戊己庚辛壬癸。",
            "子丑寅卯辰巳。",
            "午未申酉戌亥。",
            "第二段完全对不上。",
            "这里只剩一个字。",
            "还有第三段。",
            "末尾一段。"
        )
        val segments = listOf(
            1 to "甲乙丙丁戊己庚辛壬癸。",              // 精确
            2 to "子丑寅卯辰巳再补几个字凑长一点。",      // 锚点估算（区间会超长）
            3 to "书里根本没有的句子。",                  // 彻底失败
            4 to "午未申酉戌亥。",                        // 精确
            5 to "这里只剩一个字",                       // 缺句号，锚点回退
            6 to "还有第三段。",                          // 精确
            7 to "末尾一段。"                             // 精确
        )
        val map = EbookSegmentMap.build(rows, segments)!!

        val located = (1..7).filter { map.isLocated(it) }
        for (i in located.indices) {
            val a = located[i]
            val aStart = map.segmentStart(a)
            val aEnd = map.segmentEnd(a)
            assertTrue("段 $a 的区间应非空且递增", aEnd > aStart)
            for (j in i + 1 until located.size) {
                val b = located[j]
                assertTrue(
                    "段 $a 的区间 [$aStart,$aEnd) 与段 $b 的区间 " +
                        "[${map.segmentStart(b)},${map.segmentEnd(b)}) 不得重叠",
                    map.segmentEnd(b) > map.segmentStart(a)
                )
            }
        }
        // 顺序不变量：定位成功段的起点必须随段号严格递增
        for (i in 1 until located.size) {
            assertTrue(
                "段 ${located[i]} 的起点应大于段 ${located[i - 1]} 的终点",
                map.segmentStart(located[i]) >= map.segmentEnd(located[i - 1])
            )
        }
        // 逐行点播：每行都该落到「该行文本真正所属的那一段」
        assertEquals(1, map.segmentOfRow(0))
        assertEquals(4, map.segmentOfRow(2))
        assertEquals(7, map.segmentOfRow(6))
    }

    @Test
    fun `锚点只命中游标之前的重复文本时该段记为未定位而不是错位`() {
        // 段 3 的文本与段 1 完全相同。段 1 已定位并把游标推到 8 之后，
        // 段 3 在游标之后**再也找不到**这份文本 —— 只剩「游标之前的那个副本」。
        //
        // ⚠️ 这里曾经有个「全范围再找一次锚点」的兜底：它命中游标之前的 0，
        // 于是段 3 被安到 [0,8)，与段 1 区间完全重合。段 3 播完时高亮/滚动
        // 会跳回行 0，而用户明明已经听到第 3 段了。
        //
        // 而这个兜底是**可证明无效**的：indexOf(anchor, cursor) 已经覆盖
        // >= cursor 的全部位置，它返回 -1 之后 indexOf(anchor) 只可能命中 < cursor。
        // 即「全范围兜底」产出的区间必然逆序，永远是有害的，已删除。
        val rows = listOf("甲乙丙丁戊己庚辛。", "壬癸。")
        val segments = listOf(
            1 to "甲乙丙丁戊己庚辛。",
            2 to "壬癸。",
            3 to "甲乙丙丁戊己庚辛。"
        )
        val map = EbookSegmentMap.build(rows, segments)!!

        assertTrue(map.isLocated(1))
        assertTrue(map.isLocated(2))
        assertFalse("段 3 在原文里只有游标之前的副本，应记为未定位", map.isLocated(3))
        assertEquals(-1, map.segmentStart(3))
        assertEquals(-1, map.rowOfSegmentNumber(3))
        assertNull("未定位的段不该有行覆盖", map.rowsOfSegmentNumber(3))
        assertEquals("只剩两段定位成功", 2, map.locatedCount)
        // 关键：段 1、2 的区间没被段 3 污染
        assertEquals(map.offsetOfRow(0), map.segmentStart(1))
        assertEquals(map.offsetOfRow(1), map.segmentStart(2))
    }

    @Test
    fun `锚点估算的段数会被单独统计出来`() {
        // 锚点估算的段区间是**估出来的**，不是精确命中的 —— 它的点击/高亮
        // 精度低于精确段。上层要靠这个数判断「这本书的映射可信吗」，
        // 不能把两者混在一个 locatedCount 里。
        val rows = listOf("今天天气很好，我们去公园散步，回家了。", "明天会下雨吗？")
        val segments = listOf(
            1 to "今天天气很好，我们去公园散步，然后回家。",  // 只能锚点
            2 to "明天会下雨吗？"                              // 精确
        )
        val map = EbookSegmentMap.build(rows, segments)!!

        assertEquals(2, map.locatedCount)
        assertEquals("两个片段都定位到了，但只有一个是估算的", 1, map.estimatedCount)
        assertTrue(map.isEstimated(1))
        assertFalse(map.isEstimated(2))
        assertFalse("越界段号不算估算段", map.isEstimated(99))
    }

    @Test
    fun `单字符段落在行首时不会被算到上一行`() {
        // ⚠️ 回归：rowsOfSegmentNumber 若改成「起点前一个字符所在行」（rowIndexOfOffset(start - 1)），
        // 单字符段就会整体滑到上一行 —— rowIndexOfOffset 返回的是**覆盖该位置**的行，
        // 而 start - 1 属于上一行的行尾。只能按「起点」与「末字符」两端来定首末行。
        val rows = listOf("甲乙丙丁。", "戊己庚辛。")
        val map = EbookSegmentMap.build(rows, listOf(1 to "甲乙丙丁。", 2 to "戊"))!!

        assertEquals(0..0, map.rowsOfSegmentNumber(1))
        assertEquals(1..1, map.rowsOfSegmentNumber(2))
        assertEquals(0 until 1, map.spansInRowsOfSegment(2)[1])
    }

    private companion object {
        val WHITESPACE = Regex("\\s")
    }
}
