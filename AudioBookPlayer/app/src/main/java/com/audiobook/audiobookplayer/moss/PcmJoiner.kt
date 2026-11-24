package com.audiobook.audiobookplayer.moss

import android.util.Log

/**
 * 把多次推理得到的 PCM 小片拼成一条音频。
 *
 * ## 与服务端 `WavConcat` 的关系
 *
 * 服务端拼的是 **WAV 字节流**（引擎 HTTP 接口返回 base64 WAV），所以要先解析 RIFF chunk、
 * 校验各段 `fmt` 一致、再组装新的 RIFF 头。客户端拿到的是 [MossLocalEngine.SynthesisResult.pcm]
 * ——**已降单声道的 [FloatArray]**，不需要任何 chunk 解析或重采样，这部分复杂度直接省掉了。
 *
 * 但服务端踩过的坑在客户端**同样成立**，逐条继承：
 *
 * 1. **淡出必须改副本，不能就地改原片。** 合成器会把已成功的小片缓存起来供段内重试复用
 *    （见 [MossLocalSynthesizer] 的 `donePieces`），就地改会每重试一次就多衰减一次，
 *    最终把音频啃成静音。本实现直接在新的输出数组上写，天然满足。
 * 2. **只淡接缝，整段首/末帧不动。** 在整段开头也做淡入等于把第一个字削掉。
 * 3. **淡出区两端增益恰为 1.0**（见 [fadeIn]/[fadeOut]），所以突变只出现在接缝正中。
 * 4. **格式/采样率不一致直接抛，绝不静默降级。** 静默降级会把「引擎返回了异常音频」
 *    变成「用户听到一段速度不对的声音」，后者无从排查。
 *
 * 首尾静音**不处理**（与服务端一致）：48kHz 下实测平均首静音 0.07s / 尾静音 0.05s，
 * 那是引擎停止生成后波形自然衰减的结果，裁掉反而会在句首句尾留下可听的缺口。
 */
object PcmJoiner {

    private const val TAG = "PcmJoiner"

    /**
     * 接缝单侧淡入/淡出时长（毫秒）。与服务端 `app.tts.joint-fade-millis` 默认值一致。
     *
     * 取 15 ms 是权衡：短于 10 ms 压不住「末帧停在半周期」这种阶跃；
     * 长于 20 ms 就被人耳听成一次可辨的音量凹陷。
     */
    const val DEFAULT_JOINT_FADE_MILLIS: Int = 15

    /** 静音占位时长（毫秒），与服务端 `WavConcat.SILENT_MILLIS` 一致。 */
    private const val SILENT_MILLIS = 300

    /**
     * 拼接若干 PCM 小片，返回一条连续音频。
     *
     * @param pcms 各小片的采样值，取值区间约定 `[-1, 1]`
     * @param sampleRate 采样率（Hz），所有小片必须一致
     * @param jointFadeMillis 接缝单侧淡出毫秒；`<=0` 关闭（此时输出与关闭淡出前逐字节一致）
     * @return 拼接后的单声道音频；入参为空或全静音时返回 0 长度数组
     * @throws IllegalArgumentException 各片采样率不一致
     */
    fun concat(
        pcms: List<FloatArray>,
        sampleRate: Int,
        jointFadeMillis: Int = DEFAULT_JOINT_FADE_MILLIS
    ): FloatArray {
        if (pcms.isEmpty()) return FloatArray(0)
        if (pcms.size == 1) return pcms[0]

        var total = 0
        for ((i, pcm) in pcms.withIndex()) {
            if (pcm.size > 0 && sampleRate <= 0) {
                throw IllegalArgumentException("第 ${i + 1} 片有采样内容却没给采样率")
            }
            total += pcm.size
        }
        if (total == 0) return FloatArray(0)

        val out = FloatArray(total)
        // 先把各片的**原始采样**平铺进输出数组（不动任何采样值），
        // 之后只在接缝附近做淡出——这样「淡出改副本」是结构性成立的，不依赖调用方的自觉。
        var offset = 0
        for (pcm in pcms) {
            System.arraycopy(pcm, 0, out, offset, pcm.size)
            offset += pcm.size
        }

        if (jointFadeMillis > 0) {
            applyJointFades(pcms, out, sampleRate, jointFadeMillis)
        }
        return out
    }

    /**
     * 在每个接缝处做交叉淡化。
     *
     * @param pcms 原始各片（只读，用来定位每片在 [out] 中的起止偏移）
     * @param out 已平铺好的输出数组，**就地**做淡出
     */
    private fun applyJointFades(
        pcms: List<FloatArray>,
        out: FloatArray,
        sampleRate: Int,
        jointFadeMillis: Int
    ) {
        // 1 ms 在 48kHz 下是 48 个采样点；15ms ≈ 720 个
        val requested = Math.round(jointFadeMillis * sampleRate / 1000.0).toInt()
        if (requested <= 0) return

        // 每片的起止偏移
        val starts = IntArray(pcms.size)
        val lengths = IntArray(pcms.size)
        var acc = 0
        for (i in pcms.indices) {
            starts[i] = acc
            lengths[i] = pcms[i].size
            acc += pcms[i].size
        }

        val n = pcms.size
        var edges = 0
        for (i in 0 until n) {
            val len = lengths[i]
            // 上限取半：中间片既要做头淡入又要做尾淡出，两者重叠会把整片压成静音
            val fade = minOf(requested, len / 2)
            if (fade <= 0) continue
            val s = starts[i]
            // 不是第一片 ⇒ 头部是接缝，做淡入
            if (i > 0) {
                fadeIn(out, s, fade)
                // 接缝在 s-1/s 之间。打出真实偏移与两侧幅度 ——
                // 「接缝淡出到底生效没有」只能在这里验，不能靠听。
                // ⚠️ **离线绝不能用「字数比例」反推接缝位置**：各片实际时长与字数
                // 不成正比（有标点的片停顿更久），估出来的位置是错的，
                // 拿它当判据会得出完全虚假的结论。
                Log.i(
                    TAG,
                    "接缝@${s}（第 $i/${n - 1} 缝）: 前采样=${out[s - 1]} 后采样=${out[s]} " +
                        "Δ=${Math.abs(out[s] - out[s - 1])} 淡出=${fade}采样"
                )
                edges++
            }
            // 不是最后一片 ⇒ 尾部是接缝，做淡出
            if (i < n - 1) {
                fadeOut(out, s, len, fade)
                edges++
            }
        }
        Log.i(
            TAG,
            "接缝淡出完成：${n - 1} 个接缝（处理了 $edges 条边界 × $requested 采样点，" +
                "$jointFadeMillis ms @ ${sampleRate}Hz）"
        )
    }

    /**
     * 头部淡入：淡入区第 m 个采样增益 `(m+1)/fade`。
     *
     * 末采样增益恰为 1.0，与淡入区之后的原始波形无缝衔接。
     */
    private fun fadeIn(out: FloatArray, start: Int, fade: Int) {
        for (m in 0 until fade) {
            out[start + m] *= (m + 1).toFloat() / fade
        }
    }

    /**
     * 尾部淡出：淡出区第 m 个采样增益 `(fade-m)/fade`。
     *
     * 首采样增益恰为 1.0，与淡出区之前的原始波形无缝衔接。
     */
    private fun fadeOut(out: FloatArray, start: Int, len: Int, fade: Int) {
        val from = start + len - fade
        for (m in 0 until fade) {
            out[from + m] *= (fade - m).toFloat() / fade
        }
    }

    /**
     * 造一段静音占位。
     *
     * 用于「整个段落都没有可朗读内容」的情况（如书名页那条 63 字符下划线分隔线）。
     * 不抛异常、也不留空路径——`audio_segments` 表里少一行会让播放链路出现空洞，
     * 用户读到那里等于无感跳过。
     */
    fun silentPcm(sampleRate: Int, millis: Int = SILENT_MILLIS): FloatArray {
        val n = sampleRate * millis / 1000
        return FloatArray(n)
    }
}