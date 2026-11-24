package com.audiobook.audiobookplayer.moss

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * SentencePiece 分词器与 Python 权威实现的一致性测试。
 *
 * ## 为什么这个测试是这套端侧合成里最关键的一环
 * 分词错了**不会报错**：token id 依旧是合法整数，ONNX 图照常跑完，只是产出的
 * 音频是乱的（或像另一种语言）。没有这个测试，就只能靠人耳在几百 MB 模型装好之后
 * 才发现"声音不对"，而且根本定位不到是哪一步错的。
 *
 * ## 数据从哪来
 * tokenizer_vectors.json 由 `.tmp_moss/make_tokenizer_vectors.py` 用官方
 * sentencepiece 包对同一份 tokenizer.model 生成，覆盖：空串、单字符、纯中/英、
 * 混排、数字、全半角标点、多空格、首尾空格、换行制表、emoji、生僻字、
 * 日韩俄阿文、纯符号，外加一条长句的**逐字符前缀**（能把不一致精确到第几个字符）。
 *
 * ## 为什么默认跳过
 * 测试依赖两个**不进 git** 的大文件（模型 470 KB 与向量 JSON），
 * 且路径在临时工作目录里。缺文件时 assumeTrue 会让用例标记为 skipped 而不是 failed ——
 * 否则任何一台没跑过模型下载的机器（含 CI）都会红。真正要验证时：
 *     ./gradlew :app:testDebugUnitTest -Dmoss.model=<tokenizer.model 路径>
 */
class SentencePieceTokenizerTest {

    private fun resolveModel(): File? {
        val candidates = listOfNotNull(
            System.getProperty("moss.model"),
            System.getenv("MOSS_TOKENIZER_MODEL"),
            // 从 app/ 目录往上两级回到仓库根，再进临时工作目录
            File("../../.tmp_moss/moss_model/tokenizer.model").canonicalFile.takeIf { it.isFile }?.path,
            File("../.tmp_moss/moss_model/tokenizer.model").canonicalFile.takeIf { it.isFile }?.path,
            File(".tmp_moss/moss_model/tokenizer.model").canonicalFile.takeIf { it.isFile }?.path
        )
        return candidates.map(::File).firstOrNull { it.isFile }
    }

    private fun resolveVectors(): File? {
        val candidates = listOfNotNull(
            System.getProperty("moss.vectors"),
            System.getenv("MOSS_TOKENIZER_VECTORS"),
            File("../../.tmp_moss/tokenizer_vectors.json").canonicalFile.takeIf { it.isFile }?.path,
            File("../.tmp_moss/tokenizer_vectors.json").canonicalFile.takeIf { it.isFile }?.path,
            File(".tmp_moss/tokenizer_vectors.json").canonicalFile.takeIf { it.isFile }?.path
        )
        return candidates.map(::File).firstOrNull { it.isFile }
    }

    /**
     * 词表结构不变量。
     *
     * 存在的意义：一致性测试用的是"端到端文本 -> id"，一旦前缀/类型解析出问题，
     * 它只会报"结果不一致"，不会告诉你**为什么**。这里把几个必须成立的硬约束单独钉住 ——
     * 尤其是 type 数组长度必须等于词表大小：模型只在 type != NORMAL 时才写该字段
     * （16384 条里只有 271 条带显式 type），漏补默认值会让类型数组整体错位，
     * 把普通字符误判成 <0xNN>，分词结果全废且不报错。
     */
    @Test
    fun `词表结构不变量`() {
        val modelFile = resolveModel()
        assumeTrue("未找到 tokenizer.model，跳过", modelFile != null)
        val t = SentencePieceTokenizer.fromFile(modelFile!!)

        assertEquals("词表大小应为 16384（与官方 tokenizer.model 一致）", 16384, t.size)
        assertEquals("unk 应为 0", 0, t.pieceToId("<unk>"))
        assertEquals("<s> 应为 1", 1, t.pieceToId("<s>"))
        assertEquals("</s> 应为 2", 2, t.pieceToId("</s>"))
        assertEquals("<pad> 应为 3", 3, t.pieceToId("<pad>"))
        assertEquals("audio_start 应为 6", 6, t.pieceToId("<|audio_start|>"))
        assertEquals("audio_end 应为 7", 7, t.pieceToId("<|audio_end|>"))
        assertEquals("byte piece <0x00> 应为 15（其后 256 个字节 token 连续排列）", 15, t.pieceToId("<0x00>"))
        assertEquals("byte piece <0xFF> 应为 270", 270, t.pieceToId("<0xFF>"))
        // ▁ 是 sentencepiece 的空格标记，前缀字符必须正确
        assertEquals("▁你 应为 3985", 3985, t.pieceToId("\u2581你"))

        println("vocab=${t.size}  样例编码 你好，世界。 -> ${t.encode("你好，世界。").toList()}")
    }

    @Test
    fun `与 Python sentencepiece 的分词结果逐条一致`() {
        val modelFile = resolveModel()
        val vectorFile = resolveVectors()
        assumeTrue("未找到 tokenizer.model，跳过（用 -Dmoss.model=... 指定）", modelFile != null)
        assumeTrue("未找到 tokenizer_vectors.json，跳过（用 -Dmoss.vectors=... 指定）", vectorFile != null)

        val tokenizer = SentencePieceTokenizer.fromFile(modelFile!!)
        // 注意：这里用 JsonParser().parse(...) 这套 **2.8.x 的旧 API**。
        // 工程的 gson 由 retrofit converter-gson:2.9.0 传递引入，实际锁在 2.8.5
        // （见 proguard-rules.pro 里那段关于 Gson 2.8.5 的说明），
        // 而静态方法 JsonParser.parseString 是 2.8.6 才有的 —— 直接用会编译不过。
        @Suppress("DEPRECATION")
        val root = JsonParser().parse(vectorFile!!.readText(Charsets.UTF_8)).asJsonObject
        val vectors = root.getAsJsonArray("vectors")

        assertEquals(
            "词表大小应与 Python 一致",
            root.get("vocabSize").asInt,
            tokenizer.size
        )

        var checked = 0
        val failures = ArrayList<String>()
        for (i in 0 until vectors.size()) {
            val v = vectors[i].asJsonObject
            val label = v.get("label").asString
            val text = v.get("text").asString
            val expected = v.getAsJsonArray("ids").toIntList()
            val actual = tokenizer.encode(text).toList()
            checked++
            if (expected != actual) {
                // 把 piece 也打出来：光看 id 无法判断"错在哪一步"
                val expectedPieces = v.getAsJsonArray("pieces").toStringList()
                failures.add(
                    "用例[$label] text=${text.replace("\n", "\\n")}\n" +
                        "  期望 ids=$expected pieces=$expectedPieces\n" +
                        "  实际 ids=$actual pieces=${tokenizer.encodeToPieces(text)}"
                )
            }
        }

        assertTrue("用例总数偏少，向量文件可能不完整: $checked", checked >= 40)
        assertTrue(
            "共 $checked 条用例，其中 ${failures.size} 条与 Python 不一致：\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun `空串与纯空白不产生任何 token`() {
        val modelFile = resolveModel()
        assumeTrue(modelFile != null)
        val tokenizer = SentencePieceTokenizer.fromFile(modelFile!!)
        assertTrue(tokenizer.encode("").isEmpty())
        assertTrue("纯空格应得空序列（实测 Python 对 \" \" 返回 []）", tokenizer.encode(" ").isEmpty())
        assertTrue(tokenizer.encode("   \n\t ").isEmpty())
    }

    private fun JsonArray.toIntList(): List<Int> = List(size()) { get(it).asInt }
    private fun JsonArray.toStringList(): List<String> = List(size()) { get(it).asString }
}
