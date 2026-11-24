package com.audiobook.audiobookplayer.parser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * MOBI 本地解析的**跨语言一致性**单测。
 *
 * 服务端的 MOBI 解析是纯自研算法（PalmDOC 解压 + 正则清 HTML + 扫标题），没有任何第三方库，
 * 所以客户端可以 1:1 移植 —— 但也正因为是「两套实现」，任何一处 Java/Kotlin 语义差异
 * （trim 语义、空白判定、正则标志）都会让阅读页文本与音频段映射**静默错位**：
 * 页面看着正常，点读/目录跳转却对不上。
 *
 * 因此这里的判据不是「看起来对」，而是**逐字符等于服务端输出**：
 * 期望值 [MobiGolden] 由服务端同一份 Java 代码（`BookTextExtractor`）现场运行产出
 * （见 `experiments/mobi_align/`），本文件只负责断言相等。
 */
class MobiParserTest {

    private fun res(name: String): File {
        val url = javaClass.classLoader!!.getResource("mobi/$name")
            ?: error("测试资源缺失: mobi/$name")
        return File(url.toURI())
    }

    private fun hexOf(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ---------------- PalmDOC 解压 ----------------

    @Test
    fun `PalmDOC 解压逐字节等于服务端（含 distance 为 0 与超出已输出长度两个异常回引）`() {
        val got = MobiParser.palmDocDecompress(res("pathological_compressed.bin").readBytes())
        // 这份字节流是手工构造的，刻意覆盖全部分支：
        //   0x00 字面量 / 0x01-0x08 复制 / 0x09-0x7F 字面量 / 0x80-0xBF 回引 / 0xC0-0xFF 空格压缩
        // 两个异常回引边界：distance==0（回读到尚未写入的下标 ⇒ 重复末字节）、
        // distance > 当前输出长度（旧实现会直接下标越界，正确行为是按惯例补空格）
        assertEquals(MobiGolden.PATHOLOGICAL_HEX, hexOf(got))
    }

    @Test
    fun `PalmDOC 回引可越过已输出末尾而不崩溃（补空格）`() {
        // 0x83 0x20 ⇒ distance=100, copyLen=3，而此刻输出只有 14 字节
        val got = MobiParser.palmDocDecompress(byteArrayOf(0x41, 0x83.toByte(), 0x20))
        assertEquals("41" + "202020", hexOf(got))
    }

    // ---------------- HTML 清洗 ----------------

    @Test
    fun `HTML 清洗逐字符等于服务端`() {
        val html = res("sample_a.html").readText(Charsets.UTF_8)
        assertEquals(MobiGolden.A_STRIP_HTML, MobiParser.stripHtmlTags(html))
    }

    @Test
    fun `尾随 NBSP 不被裁掉（Java stripTrailing 用的是 Character_isWhitespace，不是 Kotlin 的 trimEnd）`() {
        // U+00A0 是「空格类字符」但**不是** Java 的空白（Character.isWhitespace 为 false），
        // 而 Kotlin 的 Char.isWhitespace() = isWhitespace || isSpaceChar ⇒ 会多删它。
        // 一旦用错，行尾的 NBSP 会在本地消失、在服务端保留 —— 行坐标随之整体漂移。
        val split = MobiParser.splitRowsWithStarts("a\u00A0\nb")
        assertArrayEquals(arrayOf("a\u00A0", "b"), split.rows.toTypedArray())
    }

    @Test
    fun `正文首尾按 Java trim 语义裁剪（去掉不超过 0x20 的字符，不去 NBSP）`() {
        assertEquals("\u00A0x", MobiParser.stripHtmlTags("\n\u00A0x\t"))
    }

    // ---------------- 正文行与行坐标 ----------------

    @Test
    fun `样本 A 的正文行与行首行尾实字下标逐字符等于服务端`() {
        val parsed = MobiParser.parse(res("sample_a.mobi"))
        val text = MobiParser.stripHtmlTags(res("sample_a.html").readText(Charsets.UTF_8))
        val split = MobiParser.splitRowsWithStarts(text)

        assertEquals(MobiGolden.A_ROWS, split.rows)
        assertArrayEquals(MobiGolden.A_ROW_STARTS, split.starts)
        assertArrayEquals(MobiGolden.A_ROW_ENDS, split.ends)
        // parse() 返回的 rows 必须与直接切行完全一致（否则阅读页与坐标两套口径）
        assertEquals(MobiGolden.A_ROWS, parsed.rows)
    }

    @Test
    fun `未压缩样本（compression=1）解析结果一致`() {
        val parsed = MobiParser.parse(res("sample_uncompressed.mobi"))
        assertEquals(MobiGolden.U_ROWS, parsed.rows)
        val split = MobiParser.splitRowsWithStarts(MobiGolden.U_TEXT)
        assertArrayEquals(MobiGolden.U_ROW_STARTS, split.starts)
        assertArrayEquals(MobiGolden.U_ROW_ENDS, split.ends)
    }

    // ---------------- 目录 ----------------

    @Test
    fun `目录标题与字符偏移逐字符等于服务端`() {
        val html = res("sample_a.html").readText(Charsets.UTF_8)
        assertEquals(MobiGolden.A_HEADINGS, MobiParser.scanHeadings(html))
    }

    @Test
    fun `目录偏移换算出的行下标与服务端一致（含未命中时不推进游标的那一条）`() {
        val parsed = MobiParser.parse(res("sample_a.mobi"))
        val rows = MobiGolden.A_ROWS
        val expectedRows = MobiGolden.A_HEADINGS.map { (_, off) ->
            MobiParser.rowIndexOfOffset(
                MobiGolden.A_ROW_STARTS, MobiGolden.A_ROW_ENDS, off
            )
        }
        // parse() 内部就是用同一套 starts/ends 换算的，这里断言两处结果一致，
        // 并逐条核对落到哪一行（第三个标题含 `&amp;`，纯文本侧已解码 ⇒ 扫不到 ⇒ 游标不推进）
        assertEquals(expectedRows, parsed.toc.map { it.paragraphIndex })
        assertEquals(1, expectedRows[0])   // 「第一章 起风了」在第 1 行
        assertEquals(7, expectedRows[1])   // 「第二章 …」在第 7 行
        assertTrue("未命中的标题应沿用上一处位置（不倒退）", expectedRows[2] >= expectedRows[1])
        parsed.toc.forEachIndexed { i, entry ->
            assertEquals(MobiGolden.A_HEADINGS[i].first, entry.title)
            assertTrue("行下标必须落在正文范围内", entry.paragraphIndex in rows.indices)
        }
    }

    // ---------------- 降级路径 ----------------

    @Test
    fun `偏移表非单调的损坏文件抛 IOException（调用方据此回退服务端）`() {
        try {
            MobiParser.parse(res("sample_broken.mobi"))
            error("损坏文件不应解析成功")
        } catch (e: IOException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    @Test
    fun `过小文件抛 IOException`() {
        try {
            MobiParser.parse(res("sample_too_small.mobi"))
            error("过小文件不应解析成功")
        } catch (e: IOException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    // ---------------- 与服务端同一出口 ----------------

    @Test
    fun `LocalEbookParser 把 mobi 路由到本地解析`() {
        val result = LocalEbookParser.parse(res("sample_a.mobi"))
        assertEquals(MobiGolden.A_ROWS, result.paragraphs)
        assertEquals(MobiGolden.A_HEADINGS.size, result.toc.size)
    }

    // ---------------- off-by-2 头部偏移回归（记录数在偏移 76，不是 78） ----------------

    /**
     * 用内存构造的最小真实布局 MOBI 钉死这次回归：
     * numRecords(UInt16) 是 78 字节 PDB 头的**最后 2 字节**（偏移 76），
     * 记录信息表从偏移 78 开始。早期实现从偏移 78 读记录数，读到的是
     * record[0].offset 的高 2 字节（真实 MOBI 里恒为 0x0000）⇒ numRecords=0 ⇒ 全盘失败。
     */
    private fun buildRealLayoutMobi(numRecordsAt76: Boolean): ByteArray {
        val text = "回归正文".toByteArray(StandardCharsets.UTF_8)
        val numRecords = 2
        val rec0Off = 78 + numRecords * 8          // 94
        val rec1Off = rec0Off + 16                 // 110
        val total = rec1Off + text.size
        val buf = java.nio.ByteBuffer.allocate(total)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        // --- PDB 头 78 字节（逐字段精确构造） ---
        buf.put("MobiTest".toByteArray(StandardCharsets.US_ASCII).copyOf(32)) // 0..32 name
        buf.putShort(0)                  // 32 attributes
        buf.putShort(0)                  // 34 version
        buf.putInt(0); buf.putInt(0); buf.putInt(0) // 36 ctime, 40 mtime, 44 btime
        buf.putInt(0)                    // 48 modnum
        buf.putInt(0)                    // 52 appInfoID
        buf.putInt(0)                    // 56 sortInfoID
        buf.put("BOOK".toByteArray(StandardCharsets.US_ASCII)) // 60 type
        buf.put("MOBI".toByteArray(StandardCharsets.US_ASCII)) // 64 creator
        buf.putInt(0)                    // 68 uidSeed
        buf.putInt(0)                    // 72 nextListID
        // 76 numRecords：真实布局写在这里；错误布局写 0 而把值放到 78
        buf.putShort((if (numRecordsAt76) numRecords else 0).toShort())
        // --- 记录信息表 @78，每条 8 字节：offset(u32)+attributes(u8)+uniqueID(u24) ---
        buf.putInt(rec0Off); buf.put(0); buf.put(0); buf.put(0); buf.put(0)
        buf.putInt(rec1Off); buf.put(0); buf.put(0); buf.put(0); buf.put(0)
        // --- Record 0: PalmDOC 头 16 字节 ---
        buf.putShort(1)                  // compressionType=1 未压缩
        buf.putShort(0)                  // reserved
        buf.putInt(text.size)            // textLength
        buf.putShort(1)                  // recordCount=1
        buf.putShort(4096)               // recordSize
        buf.putShort(0)                  // encryptionType
        buf.putShort(0)                  // reserved
        // --- Record 1: 正文（未压缩） ---
        buf.put(text)
        return buf.array()
    }

    @Test
    fun `真实布局（numRecords 在偏移 76）可正常解析`() {
        val raw = buildRealLayoutMobi(numRecordsAt76 = true)
        // 偏移 78 处是 record[0].offset 的高 2 字节 = 0x0000：旧代码在此读记录数会得到 0
        assertEquals("偏移 78 高字节应为 0（正是旧 bug 触发点）", 0x00, raw[78].toInt() and 0xFF)
        assertEquals("偏移 76 才是记录数", 2, (((raw[76].toInt() and 0xFF) shl 8) or (raw[77].toInt() and 0xFF)))
        val parsed = MobiParser.parse(writeTempMobi(raw))
        assertEquals(listOf("回归正文"), parsed.rows)
    }

    @Test
    fun `错误布局（记录数只在偏移 78）被判损坏`() {
        // 把 numRecords 从 76 挪到 78（模拟修复前的读法所「期望」的布局）：
        // 正确实现从 76 读到 0 ⇒ 记录数过少 ⇒ decompressText 返回 null ⇒ parse 抛 IOException
        val raw = buildRealLayoutMobi(numRecordsAt76 = false)
        try {
            MobiParser.parse(writeTempMobi(raw))
            error("错误布局不应解析成功")
        } catch (e: IOException) {
            assertTrue(e.message!!.isNotEmpty())
        }
    }

    private fun writeTempMobi(bytes: ByteArray): File {
        val f = File.createTempFile("mobi_regression_", ".mobi")
        f.deleteOnExit()
        f.outputStream().use { it.write(bytes) }
        return f
    }

    // ---------------- 长度约束（recordSize 限长 + textLength 截断） ----------------

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun `解压结果逐字节等于服务端（含 recordSize 限长与 textLength 截断）`() {
        val html = MobiParser.decompressText(res("sample_length_limits.mobi").readBytes())!!
        assertEquals(MobiGolden.LENGTH_LIMITS_DECOMPRESSED_BYTES, html.size)
        assertEquals(MobiGolden.LENGTH_LIMITS_DECOMPRESSED_SHA256, sha256(html))
    }

    @Test
    fun `压缩流尾部的填充字节不能多解（recordSize 限长）`() {
        val html = MobiParser.decompressText(res("sample_length_limits.mobi").readBytes())!!
        // record1 的压缩流尾部带 `00 81`：不限长会多解出 1 字节 NUL（0x00），
        // 于是总长变成 4108、且 textLength 截断的位置也跟着偏 —— 正文整体错位。
        assertEquals(4096 + MobiGolden.LENGTH_LIMITS_TAIL.length, html.size)
        assertTrue("正文首段必须全是 'A'", html.copyOfRange(0, 4096).all { it == 'A'.code.toByte() })
        assertEquals(
            MobiGolden.LENGTH_LIMITS_TAIL,
            String(html.copyOfRange(4096, html.size), StandardCharsets.UTF_8)
        )
    }

    @Test
    fun `正文总长按 textLength 截断（尾巴杂字节不能当正文）`() {
        val html = MobiParser.decompressText(res("sample_length_limits.mobi").readBytes())!!
        // 不按 textLength 截断时，record2 的 8 字节杂数据会留在正文尾部（总长 4115）
        assertEquals(4096 + 11, html.size)
        assertTrue("正文里不能有杂字节", html.none { it == 0x00.toByte() })
        assertEquals(
            MobiGolden.LENGTH_LIMITS_TAIL,
            String(html.copyOfRange(4096, html.size), StandardCharsets.UTF_8)
        )
    }

    @Test
    fun `解压上限可显式指定（限长后多出的字节被截掉）`() {
        val src = "ABCDEFGH".toByteArray(StandardCharsets.US_ASCII)
        // 限长 3：只留前 3 字节
        assertEquals("ABC", String(MobiParser.palmDocDecompress(src, 3), StandardCharsets.US_ASCII))
        // 不限长（默认参数）时保持原样
        assertEquals(
            "ABCDEFGH",
            String(MobiParser.palmDocDecompress(src), StandardCharsets.US_ASCII)
        )
    }

    @Test
    fun `长度约束样本的阅读页原文行与坐标逐字符等于服务端`() {
        val parsed = MobiParser.parse(res("sample_length_limits.mobi"))
        assertEquals(MobiGolden.LENGTH_LIMITS_ROWS, parsed.rows)
        val text = MobiParser.stripHtmlTags(
            String(
                MobiParser.decompressText(res("sample_length_limits.mobi").readBytes())!!,
                StandardCharsets.UTF_8
            )
        )
        val split = MobiParser.splitRowsWithStarts(text)
        assertArrayEquals(MobiGolden.LENGTH_LIMITS_ROW_STARTS, split.starts)
        assertArrayEquals(MobiGolden.LENGTH_LIMITS_ROW_ENDS, split.ends)
    }
}
