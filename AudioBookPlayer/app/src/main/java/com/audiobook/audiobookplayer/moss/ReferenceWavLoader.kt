package com.audiobook.audiobookplayer.moss

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 读取参考音频 WAV 并转成codec encode 要求的形状。
 *
 * ## 为什么需要它
 *
 * MOSS 的音色克隆不是"把音频文件喂给模型"，而是把参考音频先用**同一个 codec 编码器**
 * 变成一串离散码（prompt_audio_codes），再当作提示插进 prefill 的输入里
 * （见 [MossLocalEngine.buildInputRows]）。所以本机要用自己的声音，
 * 必须能在本地跑编码方向 —— 而参考音频来自用户，相册/录音/服务端下载，
 * 格式与采样率五花八门，codec 只认一种。
 *
 * ## 格式契约（来自 codec_browser_onnx_meta.json）
 * ```
 * sample_rate    : 48000
 * channels       : 2
 * downsample_rate: 3840     // 每帧的输入采样点数 = 48000 / 3840 = 12.5 的整数倍约束由模型保证
 * ```
 * 也就是说 encode 要的是**48kHz 立体声 float**。而 `ReferenceAudioRecorder` 录的是
 * 44100Hz 单声道，用户从相册导入的什么都可能。于是这一步必须做三件事：
 * 解析 RIFF chunk → 归一到目标采样率 → 补齐/裁剪到双声道。
 *
 * ## RIFF 解析为什么不能假设 44 字节头
 * 与服务端 `util/WavConcat.java` 同源教训：WAV 的 `fmt ` 与 `data` 之间可以插入
 * `LIST`/`fact` 等 chunk，按固定偏移读会在真实文件上错位。所以走 chunk 链表。
 *
 * ## 采样率转换口径
 * 用线性插值，不引入带通滤波/重采样库：
 *   · 参考音频只是**音色提示**，不是最终输出，最终音质由 codec decode 决定；
 *   · 提示片段通常只有几秒，插值带来的高频损失对音色克隆的影响远小于"跑不起来"；
 *   · 引入重采样依赖会让模块包体积和失败面都变大。
 * 若将来听到明显金属味，再换 sinc 插值——但那是优化，不是能不能用的前提。
 */
object ReferenceWavLoader {

    private const val TAG = "ReferenceWavLoader"

    /** codec encode 要求的采样率（Hz），对应 codec_config.sample_rate */
    const val TARGET_SAMPLE_RATE = 48000

    /** codec encode 要求的声道数，对应 codec_config.channels */
    const val TARGET_CHANNELS = 2

    /** 超过这个时长就截断：音色提示不需要长音频，且直接决定 encode 的耗时与内存 */
    const val MAX_SECONDS = 10

    const val MIN_SECONDS = 2

/** 单声道 PCM + 其采样率。抽成内部可见，避免 public 函数暴露 private 类型。 */
internal data class MonoPcm(val samples: FloatArray, val sampleRate: Int)

    /**
     * 读一个 WAV，返回 48kHz 立体声 interleaved float。
     *
     * @return `[样本数 * 2]` 的 interleaved 布局（左声道、右声道交替），
     *   即 `out[2i]`=左、`out[2i+1]`=右。失败返回 null 而不抛异常 ——
     *   合成入口需要据此提示用户，而不能让整个合成流程崩掉。
     */
    fun loadForEncode(file: File): FloatArray? {
        if (!file.isFile) {
            Log.w(TAG, "参考音频不存在: ${file.absolutePath}")
            return null
        }
        return try {
            val pcm = readWavMonoAsFloat(file) ?: return null
            if (pcm.samples.isEmpty()) {
                Log.w(TAG, "参考音频无采样: ${file.name}")
                return null
            }
            val seconds = pcm.samples.size.toDouble() / pcm.sampleRate
            Log.i(TAG, "读入参考音频 ${file.name}: ${pcm.sampleRate}Hz ${seconds.toInt()}s")
            if (seconds < MIN_SECONDS) {
                Log.w(TAG, "参考音频仅 ${seconds.toInt()}s，短于 ${MIN_SECONDS}s，音色信息不足")
                return null
            }
            val resampled = resampleToTarget(pcm, TARGET_SAMPLE_RATE)
            val trimmed = trimToMaxSeconds(resampled, TARGET_SAMPLE_RATE)
            interleaveToStereo(trimmed)
        } catch (e: Exception) {
            Log.e(TAG, "解析参考音频失败: ${file.name}", e)
            null
        }
    }

    /** 单声道 PCM + 其采样率 */

    /**
     * 解析 RIFF/WAVE，混出单声道 float。
     *
     * 支持 8/16/24/32 位整型 PCM 与 32 位 float；多声道取各声道求平均
     * （encode 要立体声，稍后复制回去；先混成单声道可以避免"左右声道内容不同时
     * 取哪一路"这个没有正确答案的问题）。
     */
    private fun readWavMonoAsFloat(file: File): MonoPcm? {
        RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()
            if (fileLen < 12) return null

            fun u32(off: Long): Long {
                // v5/A10：显式 seek。原实现不读 off、也不 seek，纯粹依赖「上一个调用
                // 恰好把文件指针停在这里」—— 当前唯一调用点（tagAt(off) 之后读 off+4）
                // 碰巧成立，任何调用顺序变化都会**静默读错 chunk 长度**（不报错、只解析出垃圾）。
                raf.seek(off)
                return (raf.readByte().toLong() and 0xFF) or
                    ((raf.readByte().toLong() and 0xFF) shl 8) or
                    ((raf.readByte().toLong() and 0xFF) shl 16) or
                    ((raf.readByte().toLong() and 0xFF) shl 24)
            }

            fun u16(off: Long): Int {
                raf.seek(off)
                return (raf.readByte().toInt() and 0xFF) or
                    ((raf.readByte().toInt() and 0xFF) shl 8)
            }

            fun tagAt(off: Long): String {
                raf.seek(off)
                val b = ByteArray(4)
                raf.readFully(b)
                return String(b, Charsets.US_ASCII)
            }

            if (tagAt(0) != "RIFF" || tagAt(8) != "WAVE") {
                Log.w(TAG, "不是 WAV 文件: ${file.name}")
                return null
            }

            var fmtFound = false
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var audioFormat = 0
            var dataOffset = -1L
            var dataLen = 0L

            // chunk 链表：data 之前遇到 LIST/fact 等 chunk 都要跳过
            var off = 12L
            while (off + 8 <= fileLen) {
                val tag = tagAt(off)
                val size = u32(off + 4)
                val body = off + 8
                when (tag) {
                    "fmt " -> {
                        audioFormat = u16(body)
                        channels = u16(body + 2)
                        sampleRate = u16(body + 4).let { it or (u16(body + 6) shl 16) } // 采样率是 32 位
                        bitsPerSample = u16(body + 14)
                        fmtFound = true
                    }
                    "data" -> {
                        dataOffset = body
                        dataLen = min(size, fileLen - body)
                        // data 通常在最后；继续找也没意义，直接收尾
                        break
                    }
                }
                // chunk 按 2 字节对齐，奇数长度会补一个 pad 字节
                off = body + size + (size and 1L)
            }

            if (!fmtFound || dataOffset < 0) {
                Log.w(TAG, "WAV 缺少 fmt/data chunk: ${file.name}")
                return null
            }
            if (sampleRate <= 0 || channels <= 0) {
                Log.w(TAG, "WAV 参数异常: sr=$sampleRate ch=$channels")
                return null
            }

            val bytesPerSample = bitsPerSample / 8
            if (bytesPerSample <= 0) return null
            val frameCount = dataLen / (bytesPerSample.toLong() * channels)
            if (frameCount <= 0) return null

            // v5/A5：只读 MAX_SECONDS 秒。原实现把整个 data chunk 一次性读进堆，
            // 再 resample、最后才 trim —— 峰值同时驻留「原始 byte[] + FloatArray + 重采样结果」，
            // 是数倍膨胀；而且 (帧数 × 声道 × 字节) 超过 Int.MAX 时 `.toInt()` 会溢出成负数，
            // 直接抛 NegativeArraySizeException。按秒封顶后，一小时录音也只读 10 秒。
            //
            // trimToMaxSeconds 仍然保留：它处理的是「重采样后长度」这一层，两者互补。
            val maxFrames = (sampleRate.toLong() * MAX_SECONDS).coerceAtLeast(1)
            val readFrames = frameCount.coerceAtMost(maxFrames)
            val buf = ByteArray((readFrames * channels * bytesPerSample).toInt())
            raf.seek(dataOffset)
            raf.readFully(buf)

            val out = FloatArray(readFrames.toInt())
            val frameStep = channels * bytesPerSample
            val scale = 1.0f / (1 shl (bitsPerSample - 1))
            for (i in out.indices) {
                var acc = 0f
                // bytePos 在**每帧开头**算一次，帧内按 channels x bytesPerSample 顺序取。
                //
                // 曾经写成「内层循环里 bytePos += bytesPerSample，循环后再 += frameStep」
                // ——那样每帧总共前进 2 * frame = 2 * channels * bytesPerSample，
                // 单声道恰好正确、**多声道正好跳过一个声道**，读到的是右声道、左右错位的半帧。
                // 单声道 fixture 测不出来（2x1 = 2 恰好等于帧长），
                // 只能靠立体声 fixture + 峰值断言抓住。
                val frameStart = i * frameStep
                for (ch in 0 until channels) {
                    val bytePos = frameStart + ch * bytesPerSample
                    val v = when {
                        audioFormat == 3 && bitsPerSample == 32 ->
                            java.nio.ByteBuffer.wrap(buf, bytePos, 4)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN).float
bitsPerSample == 16 -> {
                            // ⚠️ 必须先`and 0xFF` 取无符号再左移。
              // 直接写 `buf[bytePos + 1].toInt() shl 8` 是**符号扩展**：
              // 高字节 ≥ 0x80 时 toInt() 得负数，左移 8 位等于减了 256 的整数倍，
      // 幅度整体错乱且**不报错**。实测：0.5 幅度的 48k 单声道被读成峰值 2.5
    // （用 Python 复刻两种语义对比定位到的，不是猜的）。
val lo = buf[bytePos].toInt() and 0xFF
      val hi = buf[bytePos + 1].toInt() and 0xFF
              val raw = lo or (hi shl 8)
       (if (raw and 0x8000 != 0) raw - 0x10000 else raw) * scale
        }
                        bitsPerSample == 8 ->
                            ((buf[bytePos].toInt() and 0xFF) - 128) / 128f
                        bitsPerSample == 32 ->
                            (((buf[bytePos].toLong() and 0xFF) or
                                ((buf[bytePos + 1].toLong() and 0xFF) shl 8) or
                                ((buf[bytePos + 2].toLong() and 0xFF) shl 16) or
                                ((buf[bytePos + 3].toLong() and 0xFF) shl 24)).toInt()) * scale
bitsPerSample == 24 -> {
          // 同样必须先 and 0xFF：第 2 字节 ≥ 0x80 时会符号扩展，
      // 左移 8 位后整体偏移 256 的整数倍（实测峰值 0.5 -> 2.5）。
    val b0 = buf[bytePos].toInt() and 0xFF
val b1 = buf[bytePos + 1].toInt() and 0xFF
       val b2 = buf[bytePos + 2].toInt() and 0xFF
                val raw = b0 or (b1 shl 8) or (b2 shl 16)
 // 减 2^24 手工补符号位。不用 `or -0x1000000`：那会把 32 位高位全置 1，
      // 连已就位的低 24 位一起污染（0x123456 -> 0xFF123456 而不是 -0xEDCBA），
              // 幅度全错且不报错。
         (if (raw and 0x800000 != 0) raw - 0x1000000 else raw) / 8388608f
   }
                        else -> 0f
                    }
                    acc += v
                }
                out[i] = acc / channels
            }

            Log.i(TAG, "WAV 解析: sr=$sampleRate ch=$channels bits=$bitsPerSample frames=$frameCount")
            return MonoPcm(out, sampleRate)
        }
    }

    /** 线性插值重采样到 [targetRate] */
    internal fun resampleToTarget(pcm: MonoPcm, targetRate: Int): FloatArray {
        val src = pcm.samples
        if (pcm.sampleRate == targetRate) return src
        val ratio = pcm.sampleRate.toDouble() / targetRate
        val outLen = (src.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val s0 = if (i0 < src.size) src[i0] else 0f
            val s1 = if (i0 + 1 < src.size) src[i0 + 1] else s0
            out[i] = s0 + (s1 - s0) * frac
        }
        return out
    }

    /** 截到最多 [maxSeconds]，避免 encode 在长音频上白烧时间 */
    fun trimToMaxSeconds(samples: FloatArray, sampleRate: Int, maxSeconds: Int = MAX_SECONDS): FloatArray {
        val maxLen = sampleRate * maxSeconds
        if (samples.size <= maxLen) return samples
        Log.i(TAG, "参考音频 ${samples.size / sampleRate}s 截断到 ${maxSeconds}s")
        return samples.copyOf(maxLen)
    }

    /** 单声道 → 立体声 interleaved。左右完全相同。 */
    fun interleaveToStereo(mono: FloatArray): FloatArray {
        val out = FloatArray(mono.size * TARGET_CHANNELS)
        var w = 0
        for (i in mono.indices) {
            out[w++] = mono[i]
            out[w++] = mono[i]
        }
        return out
    }

    /** 供测试用：暴露单声道读取结果，避免测试依赖私有方法 */
    internal fun readMono(file: File): Pair<FloatArray, Int>? =
        readWavMonoAsFloat(file)?.let { it.samples to it.sampleRate }

    internal fun interleaveMono(mono: FloatArray): FloatArray = interleaveToStereo(mono)

    internal fun resample(samples: FloatArray, from: Int, to: Int): FloatArray =
        resampleToTarget(MonoPcm(samples, from), to)

    /** 峰值归一：参考音频录制电平差异很大，直接编码会让音色提示偏弱或削波 */
    fun normalizePeak(samples: FloatArray, targetPeak: Float = 0.95f): FloatArray {
        var peak = 0f
        for (s in samples) peak = maxOf(peak, abs(s))
        if (peak <= 1e-6f) return samples
        val gain = targetPeak / peak
        if (abs(gain - 1f) < 0.01f) return samples
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = (samples[i] * gain).coerceIn(-1f, 1f)
        Log.i(TAG, "参考音频峰值归一: peak=${"%.3f".format(peak)} gain=${"%.2f".format(gain)}")
        return out
    }

    internal fun round(x: Double) = x.roundToInt()
}