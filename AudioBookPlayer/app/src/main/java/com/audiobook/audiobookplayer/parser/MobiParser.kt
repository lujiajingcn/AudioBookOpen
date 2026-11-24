package com.audiobook.audiobookplayer.parser

import android.util.Log
import com.audiobook.audiobookplayer.model.TocEntry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * MOBI / AZW 本地解析器（服务端 `BookTextExtractor` 的 1:1 移植）。
 *
 * ## 为什么 MOBI 可以本地化，PDF 不行
 *
 * 服务端的 MOBI 解析（`extractMobi` / `decompressMobiText` / `palmDocDecompress` /
 * `stripHtmlTags` / `scanMobiHeadings`）是**纯自研算法**：PalmDOC 解压 + 正则清 HTML +
 * 扫标题，不依赖任何第三方库，只用 JDK 标准库的正则与字符串 API。Kotlin/JVM 与之同源
 * （`java.util.regex` 就是同一份实现），因此可以逐字符复现，不存在「库版本漂移」。
 *
 * PDF 改走本地 **PDFium** 降级预览（[PdfPreviewParser]，io.legere:pdfiumandroid:1.0.31），
 * 服务端仍是 PDFBox 3.0.2 —— 两者实测字符级输出一致。本地预览只用于先上屏，
 * 正文/目录/映射仍以服务端权威结果覆盖（见 BookViewModel.readPdfRowsTwoPhase），
 * 服务端结果缓存到 DatabaseHelper 的 ebook_content_cache。
 *
 * 注：早期注释里引用的「实测 1.1%~2.1% 的音频段定位失败（detail#B10）」已删除 ——
 * 项目记忆里查不到 B10 那条数据（DETAILS.md 无此编号），无法核实，不应作为依据。
 *
 * ## 输出口径必须与服务端一致
 *
 * 阅读页显示的是「原文行」，由服务端 `splitRowsWithStarts` 定义：按 `\n` 拆行、
 * **只裁行尾（stripTrailing）、保留行首缩进**、丢弃拆出来的纯空白行。本地照同一口径产出，
 * 于是 MOBI 从服务端路径切到本地路径时，阅读页**逐字符不变**，音频段映射结果也不变。
 *
 * ## 与 Java 的语义对齐（三个容易静默跑偏的点）
 *
 * 1. Java `String.trim()` 去掉的是 `char <= ' '`（含 U+0000~U+0020），**不是**
 *    `Character.isWhitespace`。这里一律写 `trim { it <= ' ' }`，别用 Kotlin 的无参 `trim()`。
 * 2. Java `String.stripTrailing()` 去掉的是 `Character.isWhitespace(c)`，它**不含**
 *    U+00A0 / U+2007 / U+202F 这三个「空格类但不是 Java 空白」的字符；而 Kotlin 的
 *    `Char.isWhitespace()` 是 `Character.isWhitespace || Character.isSpaceChar`，会多删这三个。
 *    所以写 `trimEnd { Character.isWhitespace(it) }`。
 * 3. 正则标志 `(?is)` = Kotlin 的 `DOT_MATCHES_ALL` + `IGNORE_CASE`；`(?i)` 只加
 *    `IGNORE_CASE`（Kotlin 的 `IGNORE_CASE` 底层就是 `Pattern.CASE_INSENSITIVE`，与 Java 一致）。
 *
 * 以上三点都有单测钉住（[MobiParserTest]），
 * golden 由服务端同一份 Java 代码现场产出，比对的是**逐字符相等**。
 *
 * ## MOBI 的两个长度约束（真实文件才会暴露）
 *
 * 解压时必须同时守住两条，否则正文会被读坏：
 *
 * 1. **单条文本记录解压后不超过 recordSize（通常 4096）**：
 *    压缩流末尾带填充字节（真实文件里常见 `00 81`），不限长就多解出几字节，
 *    后面的正文**整体错位**；
 * 2. **正文总长 = record0 的 textLength**：
 *    最后一条文本记录后面可能还挂着别的数据（目录、封面标记），不截断就把杂字节当正文。
 *
 * 两条都缺时的症状：每 4096 字节就有一段非法 UTF-8，`String(bytes, UTF_8)` 把它变成
 * `�`，阅读页每隔几页冒一个乱码。服务端 `BookTextExtractor` 已按同样两条规则修过，
 * 客户端这里保持同源。
 */
object MobiParser {

    private const val TAG = "MobiParser"

    /**
     * PDB 头共 78 字节，其**最后 2 字节**（偏移 76）就是记录数 numRecords(UInt16)；
     * 记录信息表从偏移 78 开始。注意：记录数不是「78 字节头之后再跟 2 字节」——
     * 早期注释这样写、并据此从偏移 78 读记录数，实际读到的是 record[0].offset 的高 2 字节
     * （真实 MOBI 里 record[0].offset=78=0x0000004E ⇒ 高 2 字节=0x0000），
     * 于是 numRecords 恒为 0、任何标准 MOBI 都解析失败。与服务端同一处修复保持同源。
     */
    private const val PDB_HEADER_BYTES = 78

    /** numRecords(UInt16) 在 PDB 头中的偏移（头 78 字节的最后 2 字节） */
    private const val NUM_RECORDS_OFFSET = 76

    /**
     * 解析结果。
     *
     * @param rows 阅读页原文行（与服务端 `splitRows` 同口径：只裁行尾、保留行首缩进）
     * @param toc  目录条目，[TocEntry.paragraphIndex] 为**行下标**（与服务端返回值的语义一致）
     */
    data class Parsed(val rows: List<String>, val toc: List<TocEntry>)

    // ---------------- 正则（与服务端逐字一致） ----------------

    private val SCRIPT_BLOCK =
        Regex("<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private val STYLE_BLOCK =
        Regex("<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private val BR_TAG = Regex("<br\\s*/?>", setOf(RegexOption.IGNORE_CASE))
    private val P_TAG = Regex("</?p[^>]*>", setOf(RegexOption.IGNORE_CASE))
    private val DIV_TAG = Regex("</?div[^>]*>", setOf(RegexOption.IGNORE_CASE))
    private val BLOCK_TAG = Regex("</?(h[1-6]|li|tr|table)[^>]*>", setOf(RegexOption.IGNORE_CASE))
    private val ANY_TAG = Regex("<[^>]+>")

    private val NUMERIC_ENTITY = Regex("&#(\\d+);")
    private val HEX_ENTITY = Regex("&#x([0-9a-fA-F]+);")

    private val CRLF = Regex("\r\n|\r")
    private val SPACE_RUN = Regex("[ \t]+")
    private val BLANK_RUN = Regex("\n{3,}")

    /** MOBI 章节标题：`<h1>`~`<h6>`（DOTALL，允许标题内嵌子标签） */
    private val MOBI_HEADING =
        Regex("<h[1-6][^>]*>(.*?)</h[1-6]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    // ---------------- 对外入口 ----------------

    /**
     * 解析 MOBI 文件。
     *
     * @throws IOException 文件损坏、非标准格式或提取不到文本（调用方据此回退服务端）
     */
    fun parse(file: File): Parsed {
        // 整本读入堆：与 `extractMobi` 一致（导入上限 ≤50MB，文本提取固有行为，非流式）
        val raw = file.readBytes()
        val decompressed = decompressText(raw)
        if (decompressed == null || decompressed.isEmpty()) {
            throw IOException("MOBI 文件解压失败或提取不到文本内容（文件可能已损坏或非标准格式）")
        }
        val htmlContent = String(decompressed, StandardCharsets.UTF_8)
        val text = stripHtmlTags(htmlContent)
        if (text.isEmpty()) {
            throw IOException("MOBI 文件未提取到文本内容")
        }

        val split = splitRowsWithStarts(text)
        // 目录：标题在纯文本里的 charOffset → 行下标。
        // 与服务端同一套坐标（`splitRowsWithStarts` 的 starts/ends 就是 splitRows 的行首/行尾），
        // 所以「点目录跳到第几行」在两条路径下一致。
        val toc = scanHeadings(htmlContent).map { (title, charOffset) ->
            TocEntry(
                title = title,
                paragraphIndex = rowIndexOfOffset(split.starts, split.ends, charOffset)
            )
        }
        Log.d(TAG, "MOBI 本地解析完成：${split.rows.size} 行、${toc.size} 目录项")
        return Parsed(split.rows, toc)
    }

    // ---------------- 解压（服务端 decompressMobiText / palmDocDecompress 的移植） ----------------

    /**
     * 解压 MOBI 文本内容，返回原始 HTML 字节数组；任何异常一律返回 null
     * （由调用方降级为「提取失败」而非抛出崩溃）。
     */
    internal fun decompressText(raw: ByteArray): ByteArray? {
        if (raw.size < PDB_HEADER_BYTES) {
            Log.w(TAG, "MOBI 文件过小，无法解析")
            return null
        }
        try {
            // numRecords 在偏移 76（头 78 字节的最后 2 字节）；记录信息表从偏移 78 开始
            val numRecords = readUShort(raw, NUM_RECORDS_OFFSET)
            var pos = PDB_HEADER_BYTES
            if (numRecords < 2) {
                Log.w(TAG, "MOBI 记录数过少: $numRecords")
                return null
            }
            // 偏移表长度校验：服务端用 DataInputStream 读到 EOF 会抛异常并被同一层 catch 吞掉，
            // 这里显式判断，行为等价但不必靠异常驱动
            if (pos + numRecords * 8 > raw.size) {
                Log.w(TAG, "MOBI 偏移表越界: 需要 ${pos + numRecords * 8} 字节，实际 ${raw.size}")
                return null
            }

            val offsets = LongArray(numRecords)
            for (i in 0 until numRecords) {
                offsets[i] = readUInt32(raw, pos)
                pos += 8 // 4 字节偏移 + attributes(1) + uniqueID(3)
            }

            // 偏移表合法性校验：必须落在文件范围内且单调递增，否则视为损坏
            for (i in 0 until numRecords) {
                if (offsets[i] > raw.size) {
                    Log.w(TAG, "MOBI 记录偏移异常 offsets[$i]=${offsets[i]}，文件长度=${raw.size}")
                    return null
                }
                if (i > 0 && offsets[i] < offsets[i - 1]) {
                    Log.w(
                        TAG,
                        "MOBI 记录偏移非单调 offsets[$i]=${offsets[i]} < offsets[${i - 1}]=${offsets[i - 1]}"
                    )
                    return null
                }
            }

            // --- Record 0: PalmDOC header ---
            if (offsets[1] <= offsets[0]) {
                Log.w(TAG, "MOBI record0 偏移非法")
                return null
            }
            val record0 = readRecord(raw, offsets[0], ((offsets[1] - offsets[0]) and 0xFFFFFFFFL).toInt())
            if (record0.size < 16) {
                Log.w(TAG, "MOBI PalmDOC 头部过短")
                return null
            }

            val compressionType = ((record0[0].toInt() and 0xFF) shl 8) or (record0[1].toInt() and 0xFF)
            // 正文总长（textLength，偏移 4~7）与单条记录上限（recordSize，偏移 10~11），都是大端。
            // 缺了它们，解压结果会多出尾巴上的杂字节：
            //   * 每条文本记录压缩流的末尾带填充（如 `00 81`），不限长就会多解出几字节，
            //     后面的正文整体错位；
            //   * 最后一条文本记录后面可能还挂着别的数据（目录、封面标记），
            //     不按 textLength 截断就会把杂字节当正文。
            // （服务端 BookTextExtractor 已按同样两条规则修过，两端口径保持一致。）
            val textLength = readUInt32(record0, 4)
            var recordCount = ((record0[8].toInt() and 0xFF) shl 8) or (record0[9].toInt() and 0xFF)
            var recordSize = readUShort(record0, 10)
            if (recordSize <= 0) {
                recordSize = 4096 // 标准值；个别文件这个字段写 0
            }

            if (compressionType != 1 && compressionType != 2) {
                Log.w(TAG, "MOBI 压缩类型无效: $compressionType")
                return null
            }
            if (recordCount > numRecords - 1) {
                Log.w(TAG, "MOBI 文本记录数 $recordCount 超过可用记录数 ${numRecords - 1}，截断处理")
                recordCount = numRecords - 1
            }
            if (recordCount < 1) {
                Log.w(TAG, "MOBI 文本记录数为 0")
                return null
            }

            val out = ByteArrayOutputStream()
            for (i in 1..recordCount) {
                // 安全计算记录大小：最后一条记录（i+1 越界）用文件末尾兜底，杜绝越界
                val size = if (i + 1 < numRecords) {
                    ((offsets[i + 1] - offsets[i]) and 0xFFFFFFFFL).toInt()
                } else {
                    raw.size - offsets[i].toInt()
                }
                if (size <= 0) {
                    Log.w(TAG, "MOBI 记录 $i 大小异常($size)，跳过")
                    continue
                }
                val record = readRecord(raw, offsets[i], size)
                if (compressionType == 2) {
                    // 限长：压缩流尾部有填充字节，不限长会多解出几字节、正文整体错位
                    out.write(palmDocDecompress(record, recordSize))
                } else {
                    out.write(record, 0, minOf(record.size, recordSize))
                }
            }
            val text = out.toByteArray()
            // 按 record0 声明的正文总长截断（声明值不合理时保留原样，宁可多留也不能截短正文）
            return if (textLength > 0 && textLength <= text.size) {
                text.copyOf(textLength.toInt())
            } else {
                text
            }
        } catch (e: Exception) {
            Log.w(TAG, "MOBI 解压失败: ${e.message}")
            return null
        }
    }

    /**
     * PalmDOC LZ77 解压。
     *
     * ```
     * 0x00        → 输出字面量 0x00
     * 0x01-0x08   → 从输入流复制 byte 个字面量字节
     * 0x09-0x7F   → 字面量字节，直接输出
     * 0x80-0xBF   → 两字节序列，编码距离和长度
     * 0xC0-0xFF   → 空格(0x20) + (byte XOR 0x80)
     * ```
     *
     * 回引（0x80-0xBF 分支）**直接按索引回读活动缓冲**：改成「先 toByteArray() 快照再回读」
     * 会让重叠回引（distance < copyLen）出错（LZ77 要求逐字节引用已扩展的输出，
     * 如 distance=2 应交替复制最后两字节，快照式只能反复复制快照末字节），
     * 大文档回引上万次时还会退化成平方级内存拷贝。
     *
     * @param maxOutput 最多产出多少字节（MOBI 一条文本记录解压后不超过 recordSize）；
     *                   压缩流尾部常有填充字节，不限长会多解出几字节、正文整体错位。
     *                   不限长时用 [Int.MAX_VALUE]。
     */
    internal fun palmDocDecompress(compressed: ByteArray, maxOutput: Int = Int.MAX_VALUE): ByteArray {
        val out = IndexedOutputBuffer(compressed.size * 2)
        var pos = 0
        val len = compressed.size

        while (pos < len && out.size < maxOutput) {
            val b = compressed[pos++].toInt() and 0xFF
            when {
                b == 0x00 -> out.write(0x00)
                b <= 0x08 -> {
                    val copyLen = minOf(b, len - pos)
                    out.write(compressed, pos, copyLen)
                    pos += copyLen
                }
                b <= 0x7F -> out.write(b)
                b <= 0xBF -> {
                    if (pos >= len) break
                    val b2 = compressed[pos++].toInt() and 0xFF
                    val pair = (b shl 8) or b2
                    // distance = (pair >> 3) & 0x7FF, length = (pair & 0x07) + 3
                    val distance = (pair shr 3) and 0x7FF
                    val copyLen = (pair and 0x07) + 3
                    var srcPos = out.size - distance
                    for (j in 0 until copyLen) {
                        when {
                            // 回引越过输出起点：按 PalmDOC 惯例补空格
                            srcPos < 0 -> out.write(0x20)
                            srcPos >= out.size -> out.write(out.get(out.size - 1).toInt())
                            else -> out.write(out.get(srcPos).toInt())
                        }
                        srcPos++
                    }
                }
                else -> { // 0xC0-0xFF
                    out.write(0x20)
                    out.write(b xor 0x80)
                }
            }
        }
        val result = out.toByteArray()
        // 最后一个码字可能写超了（回引一次最多 10 字节），按上限截齐
        return if (result.size > maxOutput) result.copyOf(maxOutput) else result
    }

    // ---------------- HTML → 纯文本（服务端 stripHtmlTags 的移植） ----------------

    /**
     * 去除 HTML 标签，保留纯文本；同时处理常见 HTML 实体与空白规范化。
     *
     * ⚠️ 它含 `trim()` 与 `[ \t]+`/`\n{3,}` 这类**全局**归一化，因此**不能**拿它对 HTML 的
     * 分段前缀分别调用再累加长度来求偏移（结果不相等）—— 目录坐标必须取自同一次调用的输出。
     */
    internal fun stripHtmlTags(html: String): String {
        if (html.isEmpty()) return ""

        // 去 script/style 块
        var cleaned = SCRIPT_BLOCK.replace(html, " ")
        cleaned = STYLE_BLOCK.replace(cleaned, " ")

        // 块级标签换成换行
        cleaned = BR_TAG.replace(cleaned, "\n")
        cleaned = P_TAG.replace(cleaned, "\n")
        cleaned = DIV_TAG.replace(cleaned, "\n")
        cleaned = BLOCK_TAG.replace(cleaned, "\n")

        // 其余标签一律去掉
        cleaned = ANY_TAG.replace(cleaned, " ")

        // 常见实体
        cleaned = cleaned
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")

        // 数字实体
        cleaned = NUMERIC_ENTITY.replace(cleaned) { it.groupValues[1].toInt().toChar().toString() }
        cleaned = HEX_ENTITY.replace(cleaned) { it.groupValues[1].toInt(16).toChar().toString() }

        // 空白规范化
        cleaned = CRLF.replace(cleaned, "\n")
        cleaned = SPACE_RUN.replace(cleaned, " ")
        cleaned = BLANK_RUN.replace(cleaned, "\n\n")

        return cleaned.trim { it <= ' ' }
    }

    // ---------------- 目录（服务端 scanMobiHeadings 的移植） ----------------

    /**
     * 扫描 MOBI 的 HTML，返回「章节标题 + 该标题在 [stripHtmlTags] 输出中的真实下标」。
     *
     * 坐标必须取自 `stripHtmlTags` 的输出（即阅读正文那一份），而且与正文是**同一个函数调用**。
     * 早期服务端拿 `matcher.start()`（HTML 里的下标）当目录偏移 —— 两个坐标系毫无关系，
     * 表现为「点任何目录都跳到最后」。
     *
     * 未命中（例如标题里含 HTML 实体，纯文本侧已被解码）时**不推进游标**，
     * 沿用上一处位置，保证下标单调不倒退。
     */
    internal fun scanHeadings(htmlContent: String): List<Pair<String, Int>> {
        val plainText = stripHtmlTags(htmlContent)
        val result = ArrayList<Pair<String, Int>>()
        var searchFrom = 0
        for (match in MOBI_HEADING.findAll(htmlContent)) {
            // 去除内部子标签（如 <span>、<b>）得到纯文本标题
            val title = ANY_TAG.replace(match.groupValues[1], "").trim { it <= ' ' }
            if (title.isEmpty()) continue
            val span = searchIgnoringBlank(plainText, title, searchFrom)
            val offset = if (span != null) span[0] else searchFrom
            if (span != null) searchFrom = span[1]
            result.add(title to offset)
        }
        return result
    }

    // ---------------- 行切分（服务端 splitRowsWithStarts 的移植） ----------------

    /** [splitRowsWithStarts] 的结果：行文本，以及每行在**原文**中的首/末实字下标 */
    internal class RowSplit(val rows: List<String>, val starts: IntArray, val ends: IntArray)

    /**
     * 把原文切成「阅读页要显示的行」：按 `\n` 拆行，**只裁行尾、保留行首缩进**，
     * 并丢弃拆出来的纯空白行。
     *
     * `starts`/`ends` 是行首/行尾**实字**在原文中的下标（跳过行首缩进、`stripTrailing` 之后的
     * 末字符），与目录偏移同一坐标系 —— 两者都由同一次 `stripHtmlTags` 的输出派生。
     */
    internal fun splitRowsWithStarts(text: String): RowSplit {
        val rows = ArrayList<String>()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val len = text.length
        var from = 0
        while (from <= len) {
            val nl = text.indexOf('\n', from)
            val end = if (nl < 0) len else nl
            // 只裁行尾：CRLF 的 '\r' 与行尾空格都在这步去掉，行首缩进原样留下。
            // 用 Character.isWhitespace 而非 Kotlin 的 trimEnd()，见类注释第 2 条
            val row = text.substring(from, end).trimEnd { Character.isWhitespace(it) }
            if (row.isNotEmpty()) {
                rows.add(row)
                var begin = from
                while (begin < end && text[begin] <= ' ') begin++
                starts.add(begin)
                // stripTrailing 之后行尾必是实字 ⇒ 末个实字就是裁剪后那段的最后一个字符
                ends.add(from + row.length - 1)
            }
            if (nl < 0) break
            from = nl + 1
        }
        return RowSplit(rows, starts.toIntArray(), ends.toIntArray())
    }

    // ---------------- 空白容忍的搜索/定位（服务端同名方法的移植） ----------------

    /**
     * 在 [text] 中从 [from] 起搜索 [needle] 的首次出现，**忽略两侧空白差异**。
     *
     * 起点不确定，所以必须能退回去从下一个候选起点重试 —— 否则搜「第二章 转折」时，
     * 正文首段「第一章的正文…」首字相同、第二字就不符，一次失配直接判未命中，
     * 坐标会退化成游标并表现为「目录瞎跳」。
     *
     * @return `[起点下标, 末字符下标 + 1]`；未找到返回 null
     */
    internal fun searchIgnoringBlank(text: String, needle: String, from: Int): IntArray? {
        if (needle.isEmpty()) return null
        var start = maxOf(from, 0)
        while (start < text.length) {
            if (text[start] <= ' ') {
                start++
                continue // 起点必须是非空白字符
            }
            val span = locateIgnoringBlank(text, needle, start)
            if (span != null) return span
            start++
        }
        return null
    }

    /**
     * 在 [text] 中从 [from] 起顺序定位 [para]，忽略原文中的空白
     * （切段会 trim 首尾空白，段落文本往往不是原文的连续子串）。
     *
     * @return `[段落首字符下标, 段落末字符下标 + 1]`；未命中返回 null
     */
    private fun locateIgnoringBlank(text: String, para: String, from: Int): IntArray? {
        if (para.isEmpty()) return null
        var j = 0
        var i = from
        var start = -1
        while (j < para.length) {
            val p = para[j]
            if (p <= ' ') {
                // 段落侧空白同样要跳过，不能只跳原文侧：句子内部的空白会被保留，
                // 硬切分支还可能让段落**以空白开头** —— 两边各自跳过才能保持对齐
                j++
                continue
            }
            while (i < text.length && text[i] <= ' ') i++
            if (i >= text.length || text[i] != p) return null
            if (start < 0) start = i
            i++
            j++
        }
        // i 是「最后一个匹配字符的下标 + 1」，正好可作为后续段的搜索起点
        return if (start >= 0) intArrayOf(start, i) else null
    }

    /**
     * 行首/行尾实字下标 → 行下标。
     *
     * 落在行尾那个换行符上时归到下一行（该字符不属于任何一行的正文），
     * 与服务端 `paragraphIndexOfOffset`、客户端 [EbookSegmentMap] 的行定位同一套规则 ——
     * 保证「目录跳转」在所有路径下落在同一行。
     */
    internal fun rowIndexOfOffset(starts: IntArray, ends: IntArray, offset: Int): Int {
        if (starts.isEmpty()) return -1
        var lo = 0
        var hi = starts.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (starts[mid] <= offset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (ans < 0) return 0
        if (offset > ends[ans] && ans + 1 < starts.size) return ans + 1
        return ans
    }

    // ---------------- 字节读取（大端，与服务端 DataInputStream 一致） ----------------

    private fun readUShort(raw: ByteArray, pos: Int): Int =
        ((raw[pos].toInt() and 0xFF) shl 8) or (raw[pos + 1].toInt() and 0xFF)

    /** 读 32 位**无符号**整数（服务端是 `Integer.toUnsignedLong(readInt())`） */
    private fun readUInt32(raw: ByteArray, pos: Int): Long {
        var v = 0L
        for (k in 0 until 4) {
            v = (v shl 8) or (raw[pos + k].toLong() and 0xFF)
        }
        return v
    }

    private fun readRecord(raw: ByteArray, offset: Long, size: Int): ByteArray {
        val off = offset.toInt()
        if (off < 0 || off >= raw.size) {
            Log.w(TAG, "记录偏移越界: offset=$off, rawLength=${raw.size}")
            return ByteArray(0)
        }
        var n = size
        if (n < 0) n = 0
        if (off + n > raw.size) n = raw.size - off
        return raw.copyOfRange(off, off + n)
    }

    /**
     * 可增长字节输出缓冲：供 [palmDocDecompress] 在解压时**按索引回读**已写入内容。
     * （`ByteArrayOutputStream` 不暴露内部缓冲，每次 `toByteArray()` 都是全量复制。）
     */
    private class IndexedOutputBuffer(initialCapacity: Int) {
        private var data = ByteArray(maxOf(16, initialCapacity))
        var size = 0
            private set

        fun get(index: Int): Byte = data[index]

        fun write(b: Int) {
            if (size == data.size) grow()
            data[size++] = b.toByte()
        }

        fun write(src: ByteArray, off: Int, len: Int) {
            while (size + len > data.size) grow()
            System.arraycopy(src, off, data, size, len)
            size += len
        }

        /** 1.5 倍扩容，避免每次 +1 触发频繁复制 */
        private fun grow() {
            data = data.copyOf(data.size + (data.size shr 1) + 1)
        }

        fun toByteArray(): ByteArray = data.copyOf(size)
    }
}
