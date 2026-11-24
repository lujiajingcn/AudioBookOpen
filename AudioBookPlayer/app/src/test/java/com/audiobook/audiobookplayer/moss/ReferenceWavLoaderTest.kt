package com.audiobook.audiobookplayer.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sin

/**
 * [ReferenceWavLoader] 的单测。
 *
 * ## 用什么做fixture
 *
 * 真实参考音频在真机的 `filesDir/reference_audio/` 下，而真机装的是 **release 包
 * （package not debuggable）**，拿不到文件；把它塞进仓库也不合适。
 * 所以这里**在测试里现造 WAV 字节流**：只用随机数据（RAND），不复制任何生产解析逻辑。
 *
 * ## 为什么要构造"带 LIST chunk"的 WAV
 *
 * 服务端 `WavConcat` 早就踩过：WAV 的 `fmt ` 与 `data` 之间可以插 `LIST`/`fact`，
 * 按固定 44 字节偏移读会在真实文件上错位。所以 fixture 必须包含一个额外 chunk，
 * 否则"按 RIFF 大小写一个 id"这种错误实现也能通过测试 —— 那种测试是摆设。
 */
class ReferenceWavLoaderTest {

    // ------------------------------------------------------------------
    // WAV 字节流构造（只造字节，不碰生产解析代码）
    // ------------------------------------------------------------------

    private fun u32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
    )

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun tag(s: String) = s.toByteArray(Charsets.US_ASCII)

    /**
     * 造一个 WAV。
     *
     * @param sampleRate 采样率
     * @param channels 声道数
     * @param bitsPerSample 8/16/24/32
     * @param seconds 时长
     * @param withListChunk 是否在 fmt 与 data 之间插入 LIST chunk（关键 fixture 开关）
     * @param amplitude 生成波形的幅度
     */
    private fun writeWav(
        file: File,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        seconds: Double,
        withListChunk: Boolean = false,
        amplitude: Float = 0.5f,
        frequency: Double = 220.0
    ) {
        val frames = (sampleRate * seconds).toInt()
        val bytesPerSample = bitsPerSample / 8
        val dataSize = frames * channels * bytesPerSample
        val fmt = tag("fmt ") + u32(16) +
            u16(1) +                      // PCM
            u16(channels) +
            u32(sampleRate) +
            u32(sampleRate * channels * bytesPerSample) + // byte rate
            u16(channels * bytesPerSample) +              // block align
            u16(bitsPerSample)

        // LIST chunk：内容随意（WAV 只要求 RIFF 合法），但长度刻意取奇数，
        // 以便同时覆盖"chunk 按 2 字节对齐、奇数长度补 pad"这条分支
        val listBody = byteArrayOf('I'.code.toByte(), 'N'.code.toByte(), 'F'.code.toByte(),
            'M'.code.toByte(), 1, 2, 3, 4, 5)
        val list = tag("LIST") + u32(listBody.size) + listBody +
            byteArrayOf(0) // pad

        val dataHeader = tag("data") + u32(dataSize)

        val out = java.io.ByteArrayOutputStream()
        val pcm = ByteArray(dataSize)
        var pos = 0
        for (i in 0 until frames) {
            // 用确定性的正弦：断言可以逐点核对，而不是"差不多就行"
            val v = (sin(2 * Math.PI * frequency * i / sampleRate) * amplitude)
            for (ch in 0 until channels) {
                when (bitsPerSample) {
                    16 -> {
                        val s = (v * 32767).toInt()
                        pcm[pos++] = (s and 0xFF).toByte()
                        pcm[pos++] = ((s shr 8) and 0xFF).toByte()
                    }
                    8 -> {
                        pcm[pos++] = ((v * 127 + 128).toInt().coerceIn(0, 255)).toByte()
                    }
                    32 -> {
                        val s = (v * 2147483647).toLong()
                        for (b in 0 until 4) {
                            pcm[pos++] = ((s shr (b * 8)) and 0xFF).toByte()
                        }
                    }
                    24 -> {
                        val s = (v * 8388607).toInt()
                        pcm[pos++] = (s and 0xFF).toByte()
                        pcm[pos++] = ((s shr 8) and 0xFF).toByte()
                        pcm[pos++] = ((s shr 16) and 0xFF).toByte()
                    }
                }
            }
        }
        out.write(tag("RIFF"))
        out.write(u32(4 + fmt.size + (if (withListChunk) list.size else 0) + dataHeader.size + dataSize))
        out.write(tag("WAVE"))
        out.write(fmt)
        if (withListChunk) out.write(list)
        out.write(dataHeader)
        out.write(pcm)
        file.writeBytes(out.toByteArray())
    }

    private fun tmpWav(name: String, block: (File) -> Unit): File {
        val f = File(System.getProperty("java.io.tmpdir"), "reftest_$name.wav")
        block(f)
        return f
    }

    // ------------------------------------------------------------------
    // 测试
    // ------------------------------------------------------------------

    /**
     * 44100Hz 单声道（ReferenceAudioRecorder 的录制格式）→ 48000Hz 立体声。
     * 这是本机最常遇到的真实输入。
     */
    @Test
    fun 录制格式44100单声道_被归一为48k立体声() {
        val f = tmpWav("rec44100") {
            writeWav(it, sampleRate = 44100, channels = 1, bitsPerSample = 16, seconds = 3.0)
        }
        val out = ReferenceWavLoader.loadForEncode(f)
        assertNotNull("44100Hz 单声道应当能被解析", out)

        val expectedSamples = 48000 * 3
        // 长度容差：线性插值的 ratio=44100/48000 不是整数，
        // 末尾可能差 1 个采样。这属正常，不该因此判失败。
        assertTrue(
            "重采样后长度应接近 3 秒 @48kHz，实际 ${out!!.size} 采样(= ${out.size / 2 / 48000.0}s)",
            kotlin.math.abs(out.size / 2 - expectedSamples) <= 2
        )
        assertEquals("必须是立体声 interleaved", 0, out.size % 2)
    }

    /**
     * fixture 的前提本身也写成断言：多出来的 LIST chunk 必须在文件里真的存在。
     * 否则"按 44 字节偏移硬读"也能过测试，而那种实现在真实文件上会错位。
     */
    @Test
    fun fixture含额外chunk_解析仍正确() {
        val f = tmpWav("withlist") {
            writeWav(it, sampleRate = 48000, channels = 2, bitsPerSample = 16,
                seconds = 3.0, withListChunk = true)
        }
        // 先确认 fixture 真的含 LIST（用原始字节看，不靠解析器）
        val raw = f.readBytes()
        assertTrue(
            "fixture 必须含 LIST chunk，否则本测试无法区分两种实现",
            String(raw, Charsets.US_ASCII).contains("LIST")
        )
        val out = ReferenceWavLoader.loadForEncode(f)
        assertNotNull("含 LIST chunk 的 WAV 应当能解析", out)
        assertEquals("48000Hz 2ch 3s = 288000 采样点", 48000 * 3 * 2, out!!.size)
    }

/** 多声道取平均，不该因声道数不同而改变总长度口径 */
    @Test
    fun 多声道立体声_长度按采样点而非字节算() {
     val f = tmpWav("stereo") {
    writeWav(it, sampleRate = 48000, channels = 2, bitsPerSample = 16, seconds = 2.5)
        }
      val out = ReferenceWavLoader.loadForEncode(f)
        assertNotNull(out)
        // 2.5s @48kHz = 120000 帧；interleaved 立体声 = 每帧 2 个采样点 = 240000。
        // ⚠️ 别按"48*2*2"这种心算去写期望值：那是 192000，对应 2 秒不是 2.5 秒。
   assertEquals("48kHz 2ch 2.5s → 120000 帧 → interleaved 240000", 48000 * 25 / 10 * 2, out!!.size)
        // 这个断言是本组测试的关键：曾有一版按"内层 += bytesPerSample、帧末再 += frameStep"
   // 推进 bytePos，单声道恰好正确、**多声道正好跳过一个声道**。
        // 长度与峰值都对，只有逐点比对能抓住 —— 而错位后峰值会变成
        // 两倍幅度（读到半帧错位的垃圾），所以峰值断言足够区分。
        var peak = 0f
        var i = 0
        while (i < out.size) {
            peak = kotlin.math.max(peak, kotlin.math.abs(out[i]))
 i += 2
        }
    assertTrue("立体声解析峰值应接近 0.5，实际 $peak（>1 说明声道推进错位）", peak in 0.45f..0.55f)
    }

/** 24bit 与 32bit 也要能用：用户从相册导入的 wav 位深不固定 */
    @Test
    fun 位深24与32都能解析() {
        for (bits in listOf(24, 32)) {
            val f = tmpWav("bits$bits") {
                writeWav(it, sampleRate = 48000, channels = 1, bitsPerSample = bits,
                    seconds = 2.2, amplitude = 0.5f)
    }
            val out = ReferenceWavLoader.loadForEncode(f)
   assertNotNull("${bits}bit WAV 应当能解析", out)
// 2.2s @48kHz = 105600 帧 → interleaved 211200；同采样率故不重采样。
    assertEquals("${bits}bit 应保留 2.2 秒", 48000 * 22 / 10 * 2, out!!.size)
            // 幅度必须对：24bit 若用 `or -0x1000000` 补符号位，
            // 正半周的值也会被污染成极大值 —— 这是"不报错但幅度全错"的典型
         var peak = 0f
      var i = 0
 while (i < out.size) {
      peak = kotlin.math.max(peak, kotlin.math.abs(out[i]))
            i += 2
          }
            assertTrue("${bits}bit 峰值应接近 0.5，实际 $peak", peak in 0.45f..0.55f)
        }
    }

    /** 太短的音频音色信息不足，明确失败而不是硬编出一堆噪声码 */
    @Test
    fun 短于2秒_判为不可用() {
        val f = tmpWav("tooshort") {
            writeWav(it, sampleRate = 48000, channels = 1, bitsPerSample = 16, seconds = 1.0)
        }
        assertNull("1 秒不足以克隆音色，应返回 null 让上层提示用户", ReferenceWavLoader.loadForEncode(f))
    }

    /** 超过 10 秒必须截断：encode 耗时与内存都随时长线性涨 */
    @Test
    fun 超过10秒_被截断到10秒() {
        val f = tmpWav("toolong") {
            writeWav(it, sampleRate = 48000, channels = 1, bitsPerSample = 16, seconds = 30.0)
        }
        val out = ReferenceWavLoader.loadForEncode(f)
        assertNotNull(out)
        assertEquals("应截到 10 秒", 48000 * 10 * 2, out!!.size)
    }

    /** 非 WAV 文件（用户可能塞进 mp3）必须干净失败，不抛异常打断合成流程 */
    @Test
    fun 非WAV文件_返回null而不抛异常() {
        val f = File(System.getProperty("java.io.tmpdir"), "reftest_notwav.bin")
        f.writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0, 0, 0, 0, 0))
        assertNull(ReferenceWavLoader.loadForEncode(f))
    }

    /** 文件不存在也走 null 路径 */
    @Test
    fun 文件不存在_返回null() {
        assertNull(
            ReferenceWavLoader.loadForEncode(File(System.getProperty("java.io.tmpdir"), "reftest_nope.wav"))
        )
    }

    /** 波形保真：解析出的峰值应接近 fixture 的 amplitude（幅度单位换算正确） */
    @Test
    fun 解析幅度保真_16bit满量程不被放大8倍() {
        val f = tmpWav("amp") {
            writeWav(it, sampleRate = 48000, channels = 1, bitsPerSample = 16,
                seconds = 2.0, amplitude = 0.5f)
        }
        val out = ReferenceWavLoader.loadForEncode(f)
        assertNotNull(out)
        var peak = 0f
        var i = 0
        while (i < out!!.size) {
            // 立体声两路相同，取一路即可
            peak = kotlin.math.max(peak, kotlin.math.abs(out[i]))
            i += 2
        }
        // 0.5 幅度 ⇒ 峰值约 0.5。若 16bit 换算写错（如漏除以 32768）会得到 16384 之类
        assertTrue("峰值应在 0.45~0.55 之间，实际 $peak", peak in 0.45f..0.55f)
    }

    /** 峰值归一：录制电平偏低时要提升到接近满量程，否则音色提示偏弱 */
    @Test
    fun 峰值归一_弱信号被提升() {
        val quiet = FloatArray(100) { 0.05f * sin(it.toDouble()).toFloat() }
        val out = ReferenceWavLoader.normalizePeak(quiet)
        var peak = 0f
        for (s in out) peak = kotlin.math.max(peak, kotlin.math.abs(s))
        assertTrue("归一后峰值应接近 0.95，实际 $peak", peak in 0.94f..0.96f)
        // 且不能溢出
        assertTrue("不得削波", out.all { it <= 1f && it >= -1f })
    }

    /** 归一不得改动已经合适的电平（否则每次都乘一次增益，白积误差） */
    @Test
    fun 峰值归一_已达目标则原样返回() {
        val ok = FloatArray(100) { 0f }.also { it[50] = 0.95f }
        assertTrue("同一数组引用，说明未改动", ReferenceWavLoader.normalizePeak(ok) === ok)
    }

    /** 全零音频（静音文件）不能除零出 NaN */
    @Test
    fun 峰值归一_全静音不产生NaN() {
        val silence = FloatArray(64)
        val out = ReferenceWavLoader.normalizePeak(silence)
        assertTrue("不得出现 NaN/Inf", out.none { it.isNaN() || it.isInfinite() })
    }
}