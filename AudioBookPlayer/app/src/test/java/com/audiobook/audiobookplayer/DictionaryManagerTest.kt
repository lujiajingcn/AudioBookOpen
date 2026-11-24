package com.audiobook.audiobookplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DictionaryManager] 的纯逻辑契约。
 *
 * 只测不依赖 Android Context 的函数（stripWord / isEnglishWord /
 * extractQueryWord / exchangeLabel）——下载与查词部分是真机验收的事。
 *
 * ## 为什么要锁[stripWord]
 * 它必须与建库脚本 `_dict_build/build_dict.py` 的 `strip_word()`
 * **逐字符一致**：词典的 `sw` 列是用那个函数算出来的，客户端算出的键对不上
 * 就永远查不到词。而这种不一致**没有任何运行时症状**（只是「查不到」），
 * 是最难在真机上发现的一类错，所以在此用跨语言golden 值锁死。
 */
class DictionaryManagerTest {

    // ---------- stripWord：与 Python strip_word() 逐字符对齐 ----------

    @Test
    fun `stripWord 去符号并小写`() {
        assertEquals("hello", DictionaryManager.stripWord("hello"))
        assertEquals("hello", DictionaryManager.stripWord("Hello"))
        assertEquals("hello", DictionaryManager.stripWord("HELLO"))
        // 连字符 / 撇号 / 空格都要被剔除 —— 这正是 sw 列的语义
        assertEquals("takeoff", DictionaryManager.stripWord("take-off"))
        assertEquals("takeoff", DictionaryManager.stripWord("Take Off"))
        assertEquals("dont", DictionaryManager.stripWord("don't"))
        // 多个 sw 归一到同一个键（实测 hood/Hood/'hood 都是 hood）
        assertEquals(DictionaryManager.stripWord("hood"), DictionaryManager.stripWord("'hood"))
    }

    @Test
    fun `stripWord 保留数字与Unicode 字母`() {
        assertEquals("mp3", DictionaryManager.stripWord("MP3"))
        assertEquals("co2", DictionaryManager.stripWord("CO2"))
        // 非ASCII 字母保留（Python str.isalnum() 同样保留）
        assertEquals("café", DictionaryManager.stripWord("Café"))
    }

    @Test
    fun `stripWord 纯符号得到空串`() {
        assertEquals("", DictionaryManager.stripWord("..."))
        assertEquals("", DictionaryManager.stripWord("，。！？"))
        assertEquals("", DictionaryManager.stripWord("   "))
    }

    /**
     * 回归锁：库里真实存在的一组词。
     * 若stripWord 的实现被改成「只保留 ASCII」，本例会立刻失败
     * （takeoff / hello 两个键都会变）。
     */
    @Test
    fun `stripWord 与建库脚本的键一致（golden）`() {
        val golden = mapOf(
            "hello" to "hello",
            "running" to "running",
            "take-off" to "takeoff",
            "well-known" to "wellknown",
            "don't" to "dont",
            "Can't" to "cant"
        )
        golden.forEach { (input, expected) ->
            assertEquals("输入=$input", expected, DictionaryManager.stripWord(input))
        }
    }

    // ---------- isEnglishWord：中文要能被判出来 ----------

    @Test
    fun `isEnglishWord 接受英文`() {
        assertTrue(DictionaryManager.isEnglishWord("hello"))
        assertTrue(DictionaryManager.isEnglishWord("Running"))
        assertTrue(DictionaryManager.isEnglishWord("take-off"))
        assertTrue(DictionaryManager.isEnglishWord("MP3"))
    }

    @Test
    fun `isEnglishWord 拒绝中文与纯符号`() {
        assertFalse(DictionaryManager.isEnglishWord("你好"))
        assertFalse(DictionaryManager.isEnglishWord("世界"))
        // 中英混排也算中文 —— 提示「仅支持查英文」
        assertFalse(DictionaryManager.isEnglishWord("hello 你好"))
        assertFalse(DictionaryManager.isEnglishWord("，。！？"))
        assertFalse(DictionaryManager.isEnglishWord(""))
        assertFalse(DictionaryManager.isEnglishWord("   "))
    }

    // ---------- extractQueryWord：长按选区常带标点 ----------

    @Test
    fun `extractQueryWord 整段就是词时原样返回`() {
        assertEquals("hello", DictionaryManager.extractQueryWord("hello"))
        assertEquals("Running", DictionaryManager.extractQueryWord("Running"))
    }

    @Test
    fun `extractQueryWord 剥离首尾标点但保留词内连字符`() {
        assertEquals("hello", DictionaryManager.extractQueryWord("hello,"))
        assertEquals("word", DictionaryManager.extractQueryWord("word."))
        assertEquals("book", DictionaryManager.extractQueryWord("\"book\""))
        // ⚠️ 连字符/撇号必须留在词里：take-off 与 takeaway 在库里是两个不同词条，
        // 靠 stripWord 归一去命中；这里若把连字符也剥掉，显示的词形就与原文不一致
        assertEquals("take-off", DictionaryManager.extractQueryWord("take-off,"))
        assertEquals("don't", DictionaryManager.extractQueryWord("don't"))
    }

    @Test
    fun `extractQueryWord 多段取最长`() {
        // 长按划过两个词时取较长者，而不是把两者拼一起去查
        assertEquals("hello", DictionaryManager.extractQueryWord("hi hello"))
    }

    @Test
    fun `extractQueryWord 纯中文返回 null`() {
        assertNull(DictionaryManager.extractQueryWord("你好世界"))
        assertNull(DictionaryManager.extractQueryWord("，。！？"))
        assertNull(DictionaryManager.extractQueryWord("   "))
    }

    // ---------- exchangeLabel：词形变化中文标签 ----------

    @Test
    fun `exchangeLabel 识别各形态`() {
        // 真实取自库里 run 的exchange（`d:run` 与 `0:run` 都是自指：
        // 「run 的过去分词」就是 run 本身、「run 的 lemma」也是 run 本身）
        val exchange = "p:ran/i:running/d:run/0:run/1:d/3:runs/s:runs"
        assertEquals("过去式", DictionaryManager.exchangeLabel(exchange, "ran"))
        assertEquals("现在分词", DictionaryManager.exchangeLabel(exchange, "running"))
        // 自指项取「先命中的那个」= d（过去分词），这符合 exchange 的排列顺序
        assertEquals("过去分词", DictionaryManager.exchangeLabel(exchange, "run"))
    }

    /**
     * 回归锁：**同一形态可能对应多个标签**。
     *
     * `runs` 在 run 的 exchange 里既出现在 `3:runs`（第三人称单数）又出现在
     * `s:runs`（复数）——因为 run 的三单与复数同形。这是数据本身的歧义。
     *
     * 曾经的实现用 firstOrNull 提前返回，于是查 `runs` 只显示「第三人称单数」，
     * 把「复数」这个同样正确的标签吞掉了。弹窗的「词形变化」行用
     * [exchangeLabelsAll] 取全部。
     */
    @Test
    fun `exchangeLabelsAll 同一形态返回全部标签`() {
        val exchange = "p:ran/i:running/d:run/0:run/1:d/3:runs/s:runs"
        assertEquals(
            listOf("第三人称单数", "复数"),
            DictionaryManager.exchangeLabelsAll(exchange, "runs")
        )
        // 无歧义的形态只返回一个
        assertEquals(
            listOf("过去式"),
            DictionaryManager.exchangeLabelsAll(exchange, "ran")
        )
        assertEquals(
            listOf("现在分词"),
            DictionaryManager.exchangeLabelsAll(exchange, "running")
        )
        assertEquals(emptyList<String>(), DictionaryManager.exchangeLabelsAll(exchange, "zzz"))
        assertEquals(emptyList<String>(), DictionaryManager.exchangeLabelsAll("", "ran"))
    }

    @Test
    fun `exchangeLabel 原形项`() {
        // 只含 `0:xxx` 的词条（lemma 本身就是该词，没有其它形态）
        assertEquals("原形", DictionaryManager.exchangeLabel("0:happy", "happy"))
    }

    @Test
    fun `exchangeLabel 大小写不敏感`() {
        assertEquals("过去式", DictionaryManager.exchangeLabel("p:Ran", "ran"))
    }

    @Test
    fun `exchangeLabel 未收录或空串返回 null`() {
        assertNull(DictionaryManager.exchangeLabel("p:ran", "zzz"))
        assertNull(DictionaryManager.exchangeLabel("", "run"))
        assertNull(DictionaryManager.exchangeLabel("p:ran", ""))
    }

    // ---------- 释义切分：字面量 \n 而非真换行 ----------

    /**
     * 回归锁：ECDICT 的 translation 里是**字面量反斜杠+n 两个字符**，
     * 实测 768739 行中含真换行的有 0 行。
     * 这里锁住「按字面量切」的判据，避免有人改成 split("\n")（真换行）后
     * 弹窗把上万字释义挤成一行糊屏。
     *
     * ⚠️ 必须调**生产实现** `DictionaryManager.parseTranslations`。
     *   曾经这个测试在测试文件里把切分逻辑重写了一遍再断言自己，
     *   于是真去改 `DictionaryManager` 的切法时测试依然全绿 —— 假通过。
     */
    @Test
    fun `释义按字面量反斜杠n切分（不是真换行）`() {
        val raw = "n. 罩；风帽\\nv. 覆盖；用头巾包\\n[网络] 头巾"
        assertEquals(
            listOf("n. 罩；风帽", "v. 覆盖；用头巾包", "[网络] 头巾"),
            DictionaryManager.parseTranslations(raw)
        )
        // 对照：若按真换行切，上面这段只能得到 1 段（说明切法必须用字面量）
        assertEquals(1, raw.split("\n").size)
    }

    /** null / 空串不能抛异常，弹窗要显示「该词条暂无中文释义」而不是崩 */
    @Test
    fun `释义为 null 或空串时返回空列表`() {
        assertEquals(emptyList<String>(), DictionaryManager.parseTranslations(null))
        assertEquals(emptyList<String>(), DictionaryManager.parseTranslations(""))
        assertEquals(emptyList<String>(), DictionaryManager.parseTranslations("\\n \\n"))
    }

    /** 两端空白与空段必须剔除，否则弹窗出现空白行 */
    @Test
    fun `释义切分剔除首尾空白与空段`() {
        assertEquals(
            listOf("a", "b"),
            DictionaryManager.parseTranslations("  a  \\n\\n  b  \\n")
        )
    }

    /**
     * 回归锁：NBSP / 全角空格必须能被识别为空白并切开。
     * 曾经「判空白」用 `Character.isWhitespace`、「切分」用 `Regex("\\s+")`，
     * 两处字符集不一致（两者都不含 U+00A0 / U+3000），
     * EPUB 里选中 `hello<NBSP>world` 会返回整串 → stripWord 变成 helloworld → 查不到。
     *
     * ⚠️ 用长度不对称的例子：`extractQueryWord` 的语义是「取选区里**最长**的词」，
     *   `hello` 与 `world` 都是 5 字符时 maxByOrNull 取先出现的那个（=hello），
     *   那样断言会误判成「没切开」。所以这里让两边长度明显不同。
     */
    @Test
    fun `NBSP 与全角空格按空白切分而不是当词内字符`() {
        assertEquals("world", DictionaryManager.extractQueryWord("hi\u00A0world"))
        assertEquals("world", DictionaryManager.extractQueryWord("hi\u3000world"))
        // 等长时取先出现的那一个（这是 maxByOrNull 的既有语义，不是缺陷）
        assertEquals("hello", DictionaryManager.extractQueryWord("hello\u00A0world"))
        // 关键：结果绝不能是「整串未切」的样子
        //（JUnit4 的 assertFalse 只有 (String, Boolean) 这一个带消息的重载，消息要放前面；
        //  `code` 是 Char 的属性，sep 是 String 所以要取 sep[0]）
        for (sep in listOf("\u00A0", "\u3000", "\u202F", "\u2009")) {
            val got = DictionaryManager.extractQueryWord("hi${sep}world")
            assertFalse(
                "分隔符 U+%04X 未被识别为空白，结果=$got".format(sep[0].code),
                got!!.contains(sep)
            )
        }
    }

    // ---------- pointReadWordAt：「点读」单击取词 ----------

    @Test
    fun `pointReadWordAt 落点词中任意位置都返回整词`() {
        // "The prince was born in 1492."下标：T0 h1 e2 ␣3 p4..e9 ␣10 w11 a12 s13 ␣14 b15...
        val text = "The prince was born in 1492."
        // 词内每个字母位置都必须拿到同一个词（点词中间也要能扩出完整词）
        for (i in listOf(4, 5, 6, 7, 8, 9)) {
            assertEquals("落点 $i 应命中 prince", "prince", DictionaryManager.pointReadWordAt(text, i))
        }
        assertEquals("The", DictionaryManager.pointReadWordAt(text, 0))
        assertEquals("was", DictionaryManager.pointReadWordAt(text, 11))
    }

    @Test
    fun `pointReadWordAt 保留首尾标点外侧的词`() {
        val text = "\"Hello, world!\""
        assertEquals("Hello", DictionaryManager.pointReadWordAt(text, 1))
        assertEquals("world", DictionaryManager.pointReadWordAt(text, 8))
    }

    @Test
    fun `pointReadWordAt 连字符与撇号并入词内`() {
        // take-off / don't 在 ECDICT 里是真词条，必须整体取到
        assertEquals("take-off", DictionaryManager.pointReadWordAt("a take-off here", 4))
        assertEquals("take-off", DictionaryManager.pointReadWordAt("a take-off here", 7))
        assertEquals("don't", DictionaryManager.pointReadWordAt("don't stop", 0))
        assertEquals("don't", DictionaryManager.pointReadWordAt("don't stop", 4))
        // 弯引号（U+2019）同样算词内
        assertEquals("it's", DictionaryManager.pointReadWordAt("it's fine", 0))
    }

    @Test
    fun `pointReadWordAt 独立符号两侧不并词`() {
        // 破折号两侧都有空格 ⇒ 是两个词
        assertEquals("word", DictionaryManager.pointReadWordAt("a word here", 2))
        assertEquals("here", DictionaryManager.pointReadWordAt("a word here", 9))
        // 词尾句号：点句号本身不命中，但点它前面的字母仍能拿到完整词
        val t2 = "done."
        assertNull(DictionaryManager.pointReadWordAt(t2, 4))
        assertEquals("done", DictionaryManager.pointReadWordAt(t2, 1))
    }

    @Test
    fun `pointReadWordAt 空白标点与越界一律不命中`() {
        val text = "The prince."
        // 空格
        assertNull(DictionaryManager.pointReadWordAt(text, 3))
        // 越界（含负数与等于长度）
        assertNull(DictionaryManager.pointReadWordAt(text, -1))
        assertNull(DictionaryManager.pointReadWordAt(text, text.length))
        assertNull(DictionaryManager.pointReadWordAt(text, Int.MAX_VALUE))
        // 空串
        assertNull(DictionaryManager.pointReadWordAt("", 0))
    }

    @Test
    fun `pointReadWordAt 中文与数字不命中`() {
        // 中文：ECDICT 是英汉词典，「落点所在的词」也无从定义
        assertNull(DictionaryManager.pointReadWordAt("这是一段中文", 3))
        // 纯数字落点（19不是可朗读的英文词）
        assertNull(DictionaryManager.pointReadWordAt("COVID19 cases", 6))
        // 但字母+数字组成的整体要能取到（点字母部分）
        assertEquals("COVID19", DictionaryManager.pointReadWordAt("COVID19 cases", 2))
        assertEquals("cases", DictionaryManager.pointReadWordAt("COVID19 cases", 8))
    }

    @Test
    fun `pointReadWordAt 相邻数字不跨界吸入`() {
        // 点w：空格已经截断，绝不能吸成 2021was
        assertEquals("was", DictionaryManager.pointReadWordAt("2021 was the year", 6))
        assertEquals("the", DictionaryManager.pointReadWordAt("2021 was the year", 10))
        assertEquals("year", DictionaryManager.pointReadWordAt("2021 was the year", 15))
    }
}