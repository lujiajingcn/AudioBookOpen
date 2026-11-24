package com.audiobook.audiobookplayer

import java.util.Locale

/**
 * 有声覆盖图（Phase3 跨端同步）编解码工具 —— 必须与服务端
 * `com.audiobook.server.util.DoneRanges` / `com.audiobook.server.util.BookKeys`、
 * PC 端 `app/core/coverage.py` **逐字符一致**。
 *
 * 覆盖图用「1-based 闭区间压缩串」描述某本书里已经完成合成的段号集合：
 * 逗号分隔、升序、相邻或重叠区间合并；单点写成 `"41"`，区间写成 `"41-50"`。
 * 例：`"1-40,41,45-50"`。
 *
 * 三端对同一个完成段集合，[encode] 出的规范串必须完全相同，否则跨端并集/去重会漂。
 * 规范算法固定为：
 *
 *   所有区间归一为 (start, end) 整数对 → 按 start 升序（再按 end）→
 *   相邻/重叠（`next.start <= cur.end + 1`）合并 → 输出 `start==end ? n : s-e`。
 *
 * 同时提供 [bookKey] 生成：身份口径 `s:{storeBookId}` / `t:{serverTaskId}` /
 * `n:{normalizeName(displayName)}`，三端一致；n: 尾部按 [normalizeName] 归一化
 * （去扩展名 + Locale.ROOT 小写 + 压缩空白），避免同一本书在不同设备上因文件名大小写、
 * 扩展名带不带、空格数量差异而裂成多行覆盖图。
 */
object CoverageCodec {

    /** doneRanges 单条上限（与 Java isValid / DTO @Size、Python MAX_DONE_RANGES_LEN 对齐） */
    const val MAX_DONE_RANGES_LEN = 8192

    /** 一个 1-based 闭区间 */
    data class Range(val start: Int, val end: Int)

    /** 非法 doneRanges 串时抛出（供 isValid 捕获判定合法性，与 Python ValueError 语义对应） */
    class DoneRangesFormatException(message: String) : Exception(message)

    // ---------- 解析 ----------

    private fun parseNum(s: String): Int {
        if (s.isEmpty()) throw DoneRangesFormatException("doneRanges 含空数字")
        // 只允许纯 ASCII 数字（与 Java parseLong / Python isdigit+不LeadingZero 口径一致）
        for (c in s) {
            if (c < '0' || c > '9') throw DoneRangesFormatException("doneRanges 含非数字: $s")
        }
        if (s.length > 1 && s[0] == '0') {
            throw DoneRangesFormatException("doneRanges 数字不得以 0 开头: $s")
        }
        val n = try {
            s.toLong().toInt()   // 防溢出：超长数字串 toInt 会抛
        } catch (e: NumberFormatException) {
            throw DoneRangesFormatException("doneRanges 数字超出范围: $s")
        }
        if (n < 1) throw DoneRangesFormatException("doneRanges 段号必须 ≥1: $s")
        return n
    }

    /** 解析单个 token：`"n"` → (n,n)；`"a-b"` → (a,b)。非法抛 [DoneRangesFormatException]。 */
    private fun parseToken(tok: String): Range {
        if (tok.isEmpty()) throw DoneRangesFormatException("doneRanges 含空数字")
        val dash = tok.indexOf('-')
        if (dash < 0) {
            val n = parseNum(tok)
            return Range(n, n)
        }
        val a = parseNum(tok.substring(0, dash))
        val b = parseNum(tok.substring(dash + 1))
        if (a > b) throw DoneRangesFormatException("doneRanges 区间起点大于终点: $tok")
        return Range(a, b)
    }

    /** 解析规范串为区间列表。null/空 → 空列表。非法 token → 抛 [DoneRangesFormatException]。 */
    fun parse(s: String?): List<Range> {
        val out = mutableListOf<Range>()
        if (s == null) return out
        val t = s.trim()
        if (t.isEmpty()) return out
        for (token in t.split(",")) {
            val tok = token.trim()
            if (tok.isEmpty()) continue
            out.add(parseToken(tok))
        }
        return out
    }

    // ---------- 归一化 / 并集 ----------

    /** 排序 + 相邻/重叠合并，得到规范区间列表。 */
    fun normalize(raw: List<Range>): List<Range> {
        val lst = raw.sortedWith(compareBy({ it.start }, { it.end }))
        val merged = mutableListOf<Range>()
        for (r in lst) {
            if (merged.isEmpty()) {
                merged.add(Range(r.start, r.end))
            } else {
                val last = merged.last()
                if (r.start <= last.end + 1) {   // 相邻或重叠都合并
                    merged[merged.lastIndex] = Range(last.start, maxOf(last.end, r.end))
                } else {
                    merged.add(Range(r.start, r.end))
                }
            }
        }
        return merged
    }

    /** 并集：合并多组区间（自动排序 + 相邻/重叠合并）。 */
    fun union(vararg groups: List<Range>?): List<Range> {
        val all = mutableListOf<Range>()
        for (g in groups) {
            if (g != null) all.addAll(g)
        }
        return normalize(all)
    }

    /** 规范串 encode。 */
    fun encode(ranges: List<Range>): String =
        ranges.joinToString(",") { if (it.start == it.end) it.start.toString() else "${it.start}-${it.end}" }

    /** 两串并集 → 规范串。 */
    fun merge(a: String?, b: String?): String = encode(union(parse(a), parse(b)))

    /** 覆盖段数。 */
    fun count(s: String?): Int {
        var n = 0
        for (r in parse(s)) n += (r.end - r.start + 1)
        return n
    }

    /** 从一组段号（可乱序/重复）生成规范覆盖串。忽略 <1 的非法段号。 */
    fun fromNumbers(nums: Iterable<Int>): String {
        val ranges = nums.filter { it >= 1 }.map { Range(it, it) }
        return encode(normalize(ranges))
    }

    /** 覆盖串展开为段号集合（供播放可行性判定）。 */
    fun expand(s: String?): Set<Int> {
        val out = mutableSetOf<Int>()
        for (r in parse(s)) {
            var n = r.start
            while (n <= r.end) {
                out.add(n)
                n++
            }
        }
        return out
    }

    /** 覆盖串是否包含某段号。 */
    fun contains(s: String?, seg: Int): Boolean {
        for (r in parse(s)) {
            if (r.start <= seg && seg <= r.end) return true
        }
        return false
    }

    /** 校验（供上报前自检）：null/空串合法；能 parse 且不超长。 */
    fun isValid(s: String?): Boolean {
        if (s == null) return true
        if (s.length > MAX_DONE_RANGES_LEN) return false
        return try {
            parse(s)
            true
        } catch (e: DoneRangesFormatException) {
            false
        }
    }

    // ---------- bookKey 身份口径（三端必须逐字符一致） ----------

    /**
     * 已知电子书扩展名（**按长度倒序 + 字母序**，防止 `.azw` 遮蔽 `.azw3`、`.doc` 遮蔽 `.docx`）。
     * 三端必须使用完全相同的列表和顺序，与服务端 `BookKeys.EXTENSIONS`、
     * PC 端 `coverage._EXTENSIONS` 一致。
     */
    private val EXTENSIONS = arrayOf(
        ".azw3", ".docx", ".djvu", ".epub", ".mobi",
        ".pdf",  ".azw",  ".doc",  ".fb2",  ".txt"
    )

    /**
     * 生成覆盖图身份键。优先级：书城 s: > 在线任务 t: > 本地名 n:。
     *
     * 与 Java/PC 的 bookKey 规则一致：
     *  - storeBookId 为正整数 → `s:{storeBookId}`
     *  - 否则 serverTaskId 为正整数 → `t:{serverTaskId}`
     *  - 否则 [normalizeName] 后非空 → `n:{normalizeName(displayName)}`
     *  - 都不满足 → 空串（非法，调用方应跳过）
     *
     * n: 分支的归一化（去扩展名/小写/压缩空白）保证同一本书在不同设备上产出相同键，
     * 避免大小写/扩展名/空格差异导致覆盖图裂行。
     *
     * 传 Long 以便直接接 Gson 反序列化出的数字（Gson 把 JSON number 变 Double/Integer 皆可）。
     */
    fun bookKey(storeBookId: Long?, serverTaskId: Long?, displayName: String?): String {
        val sbid = posInt(storeBookId)
        if (sbid != null) return "s:$sbid"
        val stid = posInt(serverTaskId)
        if (stid != null) return "t:$stid"
        val name = normalizeName(displayName)
        if (name.isNotEmpty()) return "n:$name"
        return ""
    }

    /** 便捷重载：接受 Number（Gson 的 storeBookId/serverTaskId 可能是 Integer/Double/Long）。 */
    fun bookKey(storeBookId: Number?, serverTaskId: Number?, displayName: String?): String =
        bookKey(storeBookId?.toLong(), serverTaskId?.toLong(), displayName)

    /**
     * 归一化书名（不含 `n:` 前缀）。**必须与服务端 `BookKeys.normalizeName`、
     * PC 端 `coverage.normalize_name` 逐字符一致**——三端对同一输入必须产出同一字符串，
     * 否则跨端合并会漂。
     *
     * 规则（顺序固定，禁止增删步骤）：
     *  1. 去两端"书键空白"（见 [isBookWhitespace]）；
     *  2. Unicode 小写化（[Locale.ROOT]，与 Python `str.lower()` 语义一致）；
     *  3. 若尾部匹配 [EXTENSIONS] 中某扩展名（且长度严格大于扩展名），剥掉一次；
     *  4. 压缩内部连续空白为单个 ASCII 空格（0x20），并再次去两端空白。
     */
    fun normalizeName(name: String?): String {
        if (name == null) return ""
        var s = trimBookWhitespace(name)
        if (s.isEmpty()) return ""
        s = s.lowercase(Locale.ROOT)
        for (ext in EXTENSIONS) {
            if (s.length > ext.length && s.endsWith(ext)) {
                s = s.substring(0, s.length - ext.length)
                break
            }
        }
        return compressWhitespace(s)
    }

    /**
     * 归一化整个 bookKey。仅对 `n:` 前缀生效；`s:`/`t:`/未知前缀原样返回（去两端空白后）。
     * 客户端一般不用它（直接调 [bookKey]），但接收服务端回传时可用它做防御性对齐。
     */
    fun normalizeBookKey(bookKey: String?): String {
        if (bookKey == null) return ""
        val t = trimBookWhitespace(bookKey)
        if (t.isEmpty()) return ""
        if (t.startsWith("n:")) {
            val tail = normalizeName(t.substring(2))
            return if (tail.isEmpty()) "" else "n:$tail"
        }
        return t
    }

    /**
     * 判定一个字符是否属于"书键空白"集合。显式列举，避免 Kotlin `Char.isWhitespace()` /
     * Java `Character.isWhitespace()` / Python `str.isspace()` 三者定义不一致导致跨端漂。
     *
     * 包含：
     *  - ASCII：0x09 TAB、0x0A LF、0x0B VT、0x0C FF、0x0D CR、0x20 SPACE
     *  - Unicode：0xA0 NBSP、0x1680 OGHAM SPACE MARK、0x2000-0x200A 排版空格、
     *    0x2028 LINE SEP、0x2029 PARA SEP、0x202F NNBSP、0x205F MMSP、0x3000 全角空格
     */
    fun isBookWhitespace(c: Char): Boolean {
        val code = c.code
        return code == 0x20 ||
            (code in 0x09..0x0D) ||
            code == 0xA0 ||
            code == 0x1680 ||
            (code in 0x2000..0x200A) ||
            code == 0x2028 ||
            code == 0x2029 ||
            code == 0x202F ||
            code == 0x205F ||
            code == 0x3000
    }

    private fun trimBookWhitespace(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isBookWhitespace(s[a])) a++
        while (b > a && isBookWhitespace(s[b - 1])) b--
        return if (a == 0 && b == s.length) s else s.substring(a, b)
    }

    private fun compressWhitespace(s: String): String {
        val sb = StringBuilder(s.length)
        var inWsRun = false
        for (c in s) {
            if (isBookWhitespace(c)) {
                inWsRun = true
            } else {
                if (inWsRun && sb.isNotEmpty()) sb.append(' ')
                inWsRun = false
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /** 正整数才有效，否则返回 null（对应 Python `_pos_int` 返回 0 再判 falsy）。 */
    private fun posInt(v: Long?): Long? = if (v != null && v > 0) v else null
}
