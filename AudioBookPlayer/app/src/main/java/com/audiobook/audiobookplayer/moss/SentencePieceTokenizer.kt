package com.audiobook.audiobookplayer.moss

import java.io.File

/**
 * SentencePiece 分词器（纯 Kotlin 实现，直接读 tokenizer.model）。
 *
 * ## 为什么必须自己实现
 * MOSS-TTS-Nano 的 ONNX 图只接受 **token id**，不接受文本。官方 Android 示例
 * （examples/android_onnx_runtime）刻意只演示"预分词好的样例"，并在 README 里写明
 * 生产环境需要另配 SentencePiece —— 也就是说到 Android 这条路官方**没有铺完**。
 * 可选方案有三种，本实现选了第三种：
 *   1. 引入 sentencepiece 的 JNI/native 库：要自己交叉编译 protobuf + sentencepiece，
 *      且部分实现带 GPL 风险，依赖链重；
 *   2. 把分词放到服务端：那就不是"本机合成"了，离线场景直接失效；
 *   3. **纯 Kotlin 解析模型 + 复现算法**（本实现）：零原生依赖、零额外体积，
 *      代价是要把算法写对 —— 靠 test/…SentencePieceTokenizerTest 用 Python
 *      sentencepiece 产出的参考向量逐条比对来保证（分词错一个 id 不会报错，
 *      只会合成出乱码音频，没有测试根本发现不了）。
 *
 * ## 模型实测形态（tokenizer.model，470,897 B，sha256 c353ee14…）
 *   · 16,384 个 piece；TrainerSpec.model_type = **2 (BPE)**，不是 unigram
 *   · piece 类型分布：NORMAL 16113 / BYTE 256 / USER_DEFINED 11 / CONTROL 3 / UNKNOWN 1
 *   · 分数范围 -16112.0 .. 0.0（BPE 的 merge 分就是 piece 的 score）
 *   · NormalizerSpec 带 237,561 B 的 precompiled_charsmap（三字段都没显式置位，
 *     故按 proto 默认值走：add_dummy_prefix=true、remove_extra_whitespaces=true、
 *     escape_whitespaces=true）
 *
 * ## 关于归一化：一个必须说明的取舍
 * precompiled_charsmap 是 sentencepiece 编译期把 normalization_rule_tsv 压成的
 * Darts 双数组 trie，用来做 NFKC 类的字符映射。完整复现它需要移植 Darts 结构，
 * 投入产出比很低。本实现改用 **java.text.Normalizer 的 NFKC**，效果在中英混排、
 * 全半角、标点这几类上是等价的（参考向量里专门覆盖了这些）。
 * 已知差异：charsmap 里额外挂了一些 sentencepiece 自定义的映射规则，
 * 极端字符（罕见符号、部分 emoji 变体）可能与 Python 结果不同。
 * **这不是"大致对就行"的妥协** —— 一旦出现不一致，会由向量测试暴露出来，
 * 届时再针对具体字符补映射表，而不是让错误静默通过。
 */
class SentencePieceTokenizer private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    private val types: IntArray,
    private val pieceToId: HashMap<String, Int>,
    private val byteToId: IntArray,
    private val unkId: Int,
    private val addDummyPrefix: Boolean = true,
    private val removeExtraWhitespaces: Boolean = true,
    private val escapeWhitespaces: Boolean = true
) {

    companion object {
        /** PIECE_TYPE 常量，与 sentencepiece_model.proto 的 ModelProto.SentencePiece.Type 对齐 */
        const val TYPE_NORMAL = 1
        const val TYPE_UNKNOWN = 2
        const val TYPE_CONTROL = 3
        const val TYPE_USER_DEFINED = 4
        const val TYPE_UNUSED = 5
        const val TYPE_BYTE = 6

        /** ▁ U+2581，sentencepiece 用来表示空格的可见字符 */
        private const val SPACE_MARKER = '\u2581'

        fun fromFile(modelFile: File): SentencePieceTokenizer {
            val proto = ProtobufReader(modelFile.readBytes())
            var addDummyPrefix = true
            var removeExtraWhitespaces = true
            var escapeWhitespaces = true

            // ModelProto: pieces=1, trainer_spec=2(本实现不需要), normalizer_spec=3
            //
            // ⚠️ 三个累加器必须建在 forEachField **外面**。
            // 曾经把它们建在 lambda 里面（每次 fieldNo==1 都 new 一遍），
            // 结果每解析一条 piece 就把上一条覆盖掉，遍历完只剩最后一条 ——
            // 表现为 "pieces/scores/types 数量不一致: 1/1/0" 这种莫名其妙的数字。
            // 这类"每轮重置累加器"的错误在 protobuf/流式解析里特别常见。
            //
            // ⚠️ **type 必须每条 piece 都补一个，缺省为 NORMAL**：
            // proto 里 type 的默认值是 1(NORMAL)，实际模型只在 type != NORMAL 时才写出该字段 ——
            // 实测 16384 条 piece 里只有 271 条带显式 type（BYTE 256 + USER_DEFINED 11 +
            // CONTROL 3 + UNKNOWN 1 = 271，正好对上）。
            // 若按"读到几条就是几条"来收，会得到 pieces=16384 而 types=271 的错位：
            // 类型数组会整体前移，把普通字符当成 <0xNN>，分词结果全废。
            val pieceList = ArrayList<String>(16384)
            val scoreList = ArrayList<Float>(16384)
            val typeList = ArrayList<Int>(16384)

            proto.forEachField { fieldNo, wireType, value ->
                when {
                    fieldNo == 1 && wireType == WIRE_LENGTH -> {
                        var piece: String? = null
                        var score = 0f
                        var type = TYPE_NORMAL      // 缺省值，见上面的说明
                        ProtobufReader(value as ByteArray).forEachField { f2, w2, v2 ->
                            when {
                                f2 == 1 && w2 == WIRE_LENGTH -> piece = String((v2 as ByteArray), Charsets.UTF_8)
                                f2 == 2 && w2 == WIRE_FIXED32 -> score = Float.fromBits(v2 as Int)
                                f2 == 3 && w2 == WIRE_VARINT -> type = v2 as Int
                            }
                        }
                        pieceList.add(piece ?: error("有一条 piece 没有 piece 字段"))
                        scoreList.add(score)
                        typeList.add(type)
                    }
                    fieldNo == 3 && wireType == WIRE_LENGTH -> {
                        ProtobufReader(value as ByteArray).forEachField { f2, w2, v2 ->
                            if (w2 == WIRE_VARINT) {
                                when (f2) {
                                    3 -> addDummyPrefix = (v2 as Int) != 0
                                    4 -> removeExtraWhitespaces = (v2 as Int) != 0
                                    5 -> escapeWhitespaces = (v2 as Int) != 0
                                }
                            }
                        }
                    }
                }
            }

            require(pieceList.isNotEmpty()) { "tokenizer.model 里没有 pieces，文件可能损坏" }
            require(pieceList.size == scoreList.size && pieceList.size == typeList.size) {
                "pieces/scores/types 数量不一致: ${pieceList.size}/${scoreList.size}/${typeList.size}"
            }
            val p = pieceList
            val ty = typeList.toIntArray()
            return SentencePieceTokenizer(
                p.toTypedArray(), FloatArray(scoreList.size) { scoreList[it] }, ty,
                HashMap<String, Int>(p.size * 2).apply { p.forEachIndexed { i, s -> putIfAbsent(s, i) } },
                IntArray(256) { -1 }.also { arr ->
                    p.forEachIndexed { i, s ->
                        // <0xNN> 形式的 byte piece，取回它代表的字节值
                        if (ty[i] == TYPE_BYTE && s.length == 6 && s.startsWith("<0x") && s.endsWith(">")) {
                            s.substring(3, 5).toIntOrNull(16)?.let { b -> arr[b] = i }
                        }
                    }
                },
                // unk 的下标（type == TYPE_UNKNOWN 的那一条；正常模型恒为 0）
                ty.indexOfFirst { it == TYPE_UNKNOWN }.let { if (it < 0) 0 else it },
                addDummyPrefix, removeExtraWhitespaces, escapeWhitespaces
            )
        }
    }

    val size: Int get() = pieces.size

    fun pieceToId(piece: String): Int? = pieceToId[piece]

    /** 文本 -> token id 序列（等价于 Python 的 sp.encode(text, out_type=int)） */
    fun encode(text: String): IntArray = encodeToPieces(text).map { idOf(it) }.toIntArray()

    /** 文本 -> piece 序列（等价于 Python 的 sp.encode(text, out_type=str)），便于排障比对 */
    fun encodeToPieces(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        // BPE：按空格切成"词"，每个词内部再做 merge
        val out = ArrayList<String>()
        for (word in normalized.split(' ')) {
            if (word.isEmpty()) continue
            out.addAll(bpe(word))
        }
        return out
    }

    /**
     * 归一化 + 空格转义，对应 sentencepiece 的 Normalize()。
     *
     * 顺序很关键：先做字符归一化，再判断是否全为空白（全空白文本直接返回空 ——
     * 实测 Python 对 " " 返回 []，若少了这一步会多吐一个 ▁ 出来），
     * 最后加前缀标记并把空格替换成 ▁。
     */
    private fun normalize(text: String): String {
        var s = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        if (removeExtraWhitespaces) {
            // 连续空白压成一个、去首尾空白（sentencepiece 用 Unicode 空白定义，
            // Kotlin 侧 \s 默认不含全角空格 U+3000，故显式补上常见全角空白）
            s = s.replace(Regex("[\\s\\u3000]+"), " ").trim(' ')
        }
        if (s.isEmpty()) return ""
        if (addDummyPrefix) s = " $s"
        return if (escapeWhitespaces) s.replace(' ', SPACE_MARKER) else s
    }

    /**
     * 单个"词"内部的 BPE 合并。
     *
     * 从字符序列起步，每轮找出**合并后 piece 分数最高**的一对相邻符号并合并，
     * 直到没有任何可合并对为止。分数越高越优先 —— 这正是 BPE 在 sentencepiece
     * 里的语义（score 就是训练时该 merge 的 log 概率）。
     *
     * 用链表（prev/next 下标）而不是每轮重建列表：长文本下重建是 O(n²)，
     * 而合并过程本身需要频繁删除节点。
     */
    private fun bpe(word: String): List<String> {
        val n = word.length
        if (n == 1) return listOf(word)

        // ⚠️ 必须按 **code point** 起步，不能按 Char。
        // emoji 等增补平面字符在 UTF-16 里占两个 Char（代理对）。若逐个 Char 建初始符号，
        // 代理对会被拆成两个"半个字符"，各自再 toByteArray(UTF_8) 时会变成 U+FFFD → 0x3F('?')，
        // 于是 byte fallback 吐出的是 <0x3F> 而不是真正的 <0xF0><0x9F><0x98><0x80>。
        // 实测：'😀' 期望 [255,174,167,143]，按 Char 起步会得到 [78,78,78,78]（四个 '?'）。
        // 这类错误不会抛异常，只会让合成出来的音里多出几个莫名其妙的音素。
        val symbols = ArrayList<String>(n)
        var i = 0
        while (i < n) {
            val cp = word.codePointAt(i)
            symbols.add(String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        if (symbols.size == 1) return symbols

        while (true) {
            var bestScore = Float.NEGATIVE_INFINITY
            var bestAt = -1
            var i = 0
            while (i < symbols.size - 1) {
                val merged = symbols[i] + symbols[i + 1]
                val id = pieceToId[merged]
                if (id != null) {
                    val sc = scores[id]
                    if (sc > bestScore) {
                        bestScore = sc
                        bestAt = i
                    }
                }
                i++
            }
            if (bestAt < 0) break
            symbols[bestAt] = symbols[bestAt] + symbols[bestAt + 1]
            symbols.removeAt(bestAt + 1)
        }

        // 逐符号查 id；查不到说明该字符不在词表里 —— 走 byte fallback
        val out = ArrayList<String>(symbols.size)
        for (sym in symbols) {
            if (pieceToId.containsKey(sym)) {
                out.add(sym)
            } else {
                out.addAll(byteFallback(sym))
            }
        }
        return out
    }

    /** 未知字符 -> 逐 UTF-8 字节的 <0xNN> piece（sentencepiece 的 byte fallback） */
    private fun byteFallback(symbol: String): List<String> {
        val bytes = symbol.toByteArray(Charsets.UTF_8)
        val out = ArrayList<String>(bytes.size)
        for (b in bytes) {
            val id = byteToId[b.toInt() and 0xFF]
            if (id >= 0) {
                out.add(pieces[id])
            } else {
                // 连 byte piece 都没有：只能给 unk（正常模型不会走到这里）
                out.add(pieces[unkId])
            }
        }
        return out
    }

    private fun idOf(piece: String): Int = pieceToId[piece] ?: unkId

    override fun toString(): String = "SentencePieceTokenizer(vocab=$size)"
}

/** 极简 protobuf 读取器：只支持本类需要的那几种 wire type。 */
internal const val WIRE_VARINT = 0
internal const val WIRE_FIXED64 = 1
internal const val WIRE_LENGTH = 2
internal const val WIRE_FIXED32 = 5

internal class ProtobufReader(private val buf: ByteArray) {

    /** 遍历顶层字段。[handler] 收到 (字段号, wireType, 值)；值类型：Int(定长32) / Long(定长64) / ByteArray */
    fun forEachField(handler: (Int, Int, Any?) -> Unit) {
        var i = 0
        while (i < buf.size) {
            // ⚠️ 这里必须显式接成两个变量：readVarint 返回的是 Pair<Long, Int>，
            // 若写成 `val (len, ni) = readVarint(i)` 再 `i = ni`，
            // 因为组件1是 Long、组件2是 Int，Kotlin 会把 **值** 绑给 ni、
            // 把 **新下标** 绑给 len（名字只是名字，类型才决定绑定）——
            // 结果长度用成了"新下标"，解析出的 piece 全乱（实测表现为只解析出 1 个 piece）。
            val keyRes = readVarint(i)
            val key = keyRes.first
            i = keyRes.second
            val fieldNo = (key ushr 3).toInt()
            when (val wt = (key and 7L).toInt()) {
                WIRE_VARINT -> {
                    val res = readVarint(i)
                    i = res.second
                    handler(fieldNo, wt, res.first.toInt())
                }
                WIRE_LENGTH -> {
                    val res = readVarint(i)
                    i = res.second
                    val end = i + res.first.toInt()
                    handler(fieldNo, wt, buf.copyOfRange(i, end))
                    i = end
                }
                WIRE_FIXED32 -> {
                    var v = 0
                    for (k in 0 until 4) v = v or ((buf[i + k].toInt() and 0xFF) shl (8 * k))
                    handler(fieldNo, wt, v)
                    i += 4
                }
                WIRE_FIXED64 -> {
                    var v = 0L
                    for (k in 0 until 8) v = v or ((buf[i + k].toLong() and 0xFF) shl (8 * k))
                    handler(fieldNo, wt, v)
                    i += 8
                }
                else -> error("不支持的 wire type $wt（字段 $fieldNo）")
            }
        }
    }

    private fun readVarint(start: Int): Pair<Long, Int> {
        var i = start
        var shift = 0
        var v = 0L
        while (true) {
            val b = buf[i].toInt()
            i++
            v = v or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return v to i
            shift += 7
        }
    }
}
