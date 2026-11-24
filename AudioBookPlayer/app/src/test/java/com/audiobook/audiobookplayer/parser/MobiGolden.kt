package com.audiobook.audiobookplayer.parser

/**
 * MOBI 本地解析的**服务端基准值**（自动生成，勿手改）。
 *
 * 由 `experiments/mobi_align/gen_golden_kt.py` 从服务端 `BookTextExtractor`
 * （Java 侧现场运行）的输出生成 —— 也就是说，这里是「服务端解析同一份 MOBI 会得到什么」
 * 的**唯一权威答案**。客户端 [MobiParser] 的移植版必须逐字符复现它。
 *
 * 重新生成：改样本后跑 `gen_samples.py` → `GoldenMain` → `gen_golden_kt.py`。
 */
internal object MobiGolden {

    /** 样本 A：`stripHtmlTags` 的输出全文（服务端 Java 版） */
    const val A_STRIP_HTML: String = "测试 \n \n \n \n\n第一章 起风了\n\n　　全角空格缩进的段落开始。这是一句很长的正文，用来触发回引压缩：这是一句很长的正文。\n\n 半角空格开头的段落 & 实体 <尖括号> \"引号\" \"再来\" '单引号' 中文中\n\nDIV 里的内容\n换行之后\n再来一次\n\n第二章 带子标签 的 标题 \n\n \n列表项一\n\n列表项二\n \n\n表格行一\n\n表格行二\n\n第三章 & 实体标题（纯文本侧会被解码，故扫不到 → 覆盖「游标不推进」分支）\n\n末尾段落，行尾故意留空格 \n\n连续空行之后 \n\n结束。"

    /** 样本 A：阅读页原文行 */
    val A_ROWS: List<String> = listOf(
        "测试",
        "第一章 起风了",
        "　　全角空格缩进的段落开始。这是一句很长的正文，用来触发回引压缩：这是一句很长的正文。",
        " 半角空格开头的段落 & 实体 <尖括号> \"引号\" \"再来\" '单引号' 中文中",
        "DIV 里的内容",
        "换行之后",
        "再来一次",
        "第二章 带子标签 的 标题",
        "列表项一",
        "列表项二",
        "表格行一",
        "表格行二",
        "第三章 & 实体标题（纯文本侧会被解码，故扫不到 → 覆盖「游标不推进」分支）",
        "末尾段落，行尾故意留空格",
        "连续空行之后",
        "结束。"
    )

    /** 样本 A：每行行首**实字**在原文中的下标 */
    val A_ROW_STARTS: IntArray = intArrayOf(
        0, 11, 20, 66, 108, 117, 122, 128, 146, 152, 160, 166, 172, 213, 228, 237
    )

    /** 样本 A：每行行尾**实字**在原文中的下标 */
    val A_ROW_ENDS: IntArray = intArrayOf(
        1, 17, 62, 105, 115, 120, 125, 140, 149, 155, 163, 169, 210, 224, 233, 239
    )

    /** 样本 A：目录条目（标题 to 该标题在纯文本中的 charOffset） */
    val A_HEADINGS: List<Pair<String, Int>> = listOf(
        "第一章 起风了" to 11,
        "第二章 带子标签的标题" to 128,
        "第三章 &amp; 实体标题（纯文本侧会被解码，故扫不到 → 覆盖「游标不推进」分支）" to 141
    )

    /** 未压缩样本（compression=1）：正文 */
    const val U_TEXT: String = "未压缩章节\n\n未压缩正文 一段。"

    /** 未压缩样本：阅读页原文行 */
    val U_ROWS: List<String> = listOf(
        "未压缩章节",
        "未压缩正文 一段。"
    )

    val U_ROW_STARTS: IntArray = intArrayOf(
        0, 7
    )

    val U_ROW_ENDS: IntArray = intArrayOf(
        4, 15
    )

    /**
     * 病态 PalmDOC 字节流的解压结果（十六进制）。
     * 覆盖了全部五个分支与两个异常回引边界（distance==0、distance>已输出长度）。
     */
    const val PATHOLOGICAL_HEX: String = "00414243444120414141414141412020202020207f0807"

    // ---------------- 长度约束样本（recordSize 限长 + textLength 截断） ----------------

    /**
     * `sample_length_limits.mobi`：同时踩中真实 MOBI 的两个长度约束。
     *
     * * record1 的压缩流尾部带 `00 81` 填充 —— 不限长会多解出 1 字节 NUL；
     * * record2 的正文后面挂着 8 字节杂数据（真实文件里是目录/封面标记之类）——
     *   不按 textLength 截断就会把杂字节当正文。
     *
     * 下面的值由服务端同一份 Java 代码（`BookTextExtractor`）现场产出。
     */
    const val LENGTH_LIMITS_DECOMPRESSED_BYTES: Int = 4107

    /** 解压结果的 SHA-256（服务端权威值）：4096 个 'A' + `<p>TAIL</p>` */
    const val LENGTH_LIMITS_DECOMPRESSED_SHA256: String =
        "eae3e8af657ba760ae1b8329bb14e80463d0d3f1987f311cbd9f26f0057a148f"

    /** 解压结果里正文部分的尾部（`<p>TAIL</p>`） */
    const val LENGTH_LIMITS_TAIL: String = "<p>TAIL</p>"

    /** 阅读页原文行（服务端权威值） */
    val LENGTH_LIMITS_ROWS: List<String> = listOf(
        "A".repeat(4096),
        "TAIL"
    )

    /** 每行行首**实字**在原文中的下标 */
    val LENGTH_LIMITS_ROW_STARTS: IntArray = intArrayOf(
        0, 4097
    )

    /** 每行行尾**实字**在原文中的下标 */
    val LENGTH_LIMITS_ROW_ENDS: IntArray = intArrayOf(
        4095, 4100
    )
}
