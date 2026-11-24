package com.audiobook.audiobookplayer.moss

/**
 * 本机合成的「超长段落 → 小片」切分器。
 *
 * ## 为什么要它
 *
 * 服务端（`AudioBookServer` 的 `TextSegmenter.subPieces`）在送引擎前会把超长句拆成 ≤30 字的小片，
 * 逐片合成后在 WAV 层拼成一条。客户端此前是**整段直接喂模型**，于是：
 *
 * - 撞上 [MossLocalEngine] 的帧数上限（375 帧 ≈ 30 秒）被**静默截断**——用户听到的话缺了后半截，
 *   而任务却报 `completed`；
 * - 单次解码要累积 375 步的 KV cache（见 `MossLocalEngine.runDecode` 每步往 `pastResult` 塞一份），
 *   **峰值内存就是被这个长解码推上去的**；ORT 的 CPU arena 只增不减，峰值决定整个进程的 PSS 上限，
 *   于是本机合成反复被 lowmemorykiller 杀掉。
 *
 * 拆成小片后每片只需约 150 帧，单次解码峰值降到四成左右，接缝再用 [PcmJoiner] 淡出缝起来。
 *
 * ## 与服务端刻意不同的地方
 *
 * 服务端拆片的动因是「引擎单次文本上限 30 字 + 每次请求都要重传参考音频」；
 * 客户端是**本地推理、零请求开销**，所以这里**不需要**服务端那套「尾片并入」的省请求逻辑 ——
 * 但仍然保留，因为尾片只剩 1~3 字时合成出来的是一段近乎静音，白花一次解码。
 *
 * 落刀逻辑、比例下限、可朗读内容白名单则与服务端**逐字对齐**，避免两端切出的片段对不上。
 */
object MossTextSplitter {

    /** 单片字符数上限，与服务端 `TextSegmenter.DEFAULT_MAX_LENGTH` 一致。 */
    const val MAX_PIECE_LENGTH: Int = 30

    /** 尾片短于这个字数就并入前一片，避免为「一个标点」白跑一次解码。 */
    private const val SUB_PIECE_MIN_TAIL = 4

    /**
     * 次级停顿标点：只在这些标点之后落刀，尽量不把词劈两半。
     *
     * 与服务端同一份清单。句末标点（。！？）在这里**故意不列**——段落本身已由
     * `LocalEbookParser` 切好，本类处理的是「段落内部的次级停顿」。
     */
    private const val BREAK_FALLBACK_CHARS = "，、；：,;"

    /**
     * 回退落刀点的下限比例：只有落在 `[maxLength × 0.6, maxLength)` 区间里的标点才被选中，
     * 否则按 `maxLength` 硬切。
     *
     * 0.6 是「不劈词」与「不切碎」的折中：太高（0.9）稍有变动就退化成硬切；
     * 太低（0.3）逗号密集的长句会碎成 10~18 字一片，而**片数是解码次数的乘数**
     * （每片一次 prefill + 一段 decode），切碎直接反噬耗时。
     */
    private const val BREAK_FALLBACK_MIN_RATIO = 0.6

    /**
     * 把一段段落文本拆成若干 ≤[MAX_PIECE_LENGTH] 字的小片。
     *
     * 不超过上限的段落原样返回单元素列表——绝大多数段落走这条路，合成路径与改造前一致。
     *
     * @param paragraph 段落原文（调用方保证非空）
     * @return 小片列表，每片 ≤30 字（尾片并入后最坏 33 字）；输入为空时返回空列表
     */
    fun subPieces(paragraph: String): List<String> {
        val s = paragraph.trim()
        if (s.isEmpty()) return emptyList()
        if (s.length <= MAX_PIECE_LENGTH) return listOf(s)

        val pieces = ArrayList<String>()
        var start = 0
        while (start < s.length) {
            val remaining = s.length - start
            if (remaining <= MAX_PIECE_LENGTH) {
                pieces.add(s.substring(start))
                break
            }
            val take = chooseBreakPoint(s, start, MAX_PIECE_LENGTH)
            pieces.add(s.substring(start, start + take))
            start += take
        }

        // 尾片过短则并入前一片：单独为「。」这种内容跑一次解码，代价远高于收益
        if (pieces.size > 1) {
            val last = pieces.size - 1
            if (pieces[last].length < SUB_PIECE_MIN_TAIL) {
                pieces[last - 1] = pieces[last - 1] + pieces[last]
                pieces.removeAt(last)
            }
        }
        return pieces
    }

    /**
     * 为从 [from] 开始的这一段挑「取多少个字符」。
     *
     * 在 `[from + maxLength × ratio, from + maxLength)` 区间内从右往左找最近的次级停顿标点，
     * 返回该标点**之后**的相对偏移（标点留在段尾）；区间内无标点则按 `maxLength` 硬切。
     */
    private fun chooseBreakPoint(text: String, from: Int, maxLength: Int): Int {
        val limit = minOf(maxLength, text.length - from)
        val floor = (maxLength * BREAK_FALLBACK_MIN_RATIO).toInt()
        for (i in limit - 1 downTo floor) {
            if (BREAK_FALLBACK_CHARS.indexOf(text[from + i]) >= 0) {
                return i + 1
            }
        }
        return maxLength
    }

    /**
     * 该小片是否含有「可朗读内容」（至少一个字母或数字）。
     *
     * **这道过滤不能省。** 上游的文本归一化会把普通文本里的 `_` 一律转成空格再 `strip()`，
     * 于是一片纯下划线会被清成空串；空文本让模型输出 **0 帧**音频，
     * 而 [MossLocalEngine.decodeAudioTokens] 的 `require(audioTokens.isNotEmpty())` 会直接抛异常。
     * 服务端在 2026-09-27 为此付过一次线上事故（《潜规则》书名页有 63 字符下划线分隔线）。
     *
     * 白名单而非黑名单：连 markdown 前缀行、纯点线也一并覆盖。
     */
    fun hasSpeakableContent(piece: String?): Boolean {
        if (piece.isNullOrEmpty()) return false
        for (ch in piece) {
            if (ch.isLetterOrDigit()) return true
        }
        return false
    }
}