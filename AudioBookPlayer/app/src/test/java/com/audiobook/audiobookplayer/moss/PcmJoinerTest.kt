package com.audiobook.audiobookplayer.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [PcmJoiner] 的拼接与接缝淡出行为规格。
 *
 * 承袭服务端 `WavConcatTest` 的规格思路：淡出**只该影响接缝附近**，
 * 其余采样必须逐字节不变——这是最容易写错、也最不容易听出来的地方。
 */
class PcmJoinerTest {

    private val rate = 48000

    @Test
    fun `单片原样返回`() {
        val pcm = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertTrue(pcm.contentEquals(PcmJoiner.concat(listOf(pcm), rate)))
    }

    @Test
    fun `空列表返回零长度`() {
        assertEquals(0, PcmJoiner.concat(emptyList(), rate).size)
    }

    @Test
    fun `拼接后长度等于各片之和`() {
        val a = FloatArray(100) { 0.1f }
        val b = FloatArray(200) { 0.2f }
        val c = FloatArray(50) { 0.3f }
        assertEquals(350, PcmJoiner.concat(listOf(a, b, c), rate, jointFadeMillis = 0).size)
    }

    @Test
    fun `关闭淡出时各片采样逐字节不变`() {
        // 这是「淡出改副本」的结构性保证：平铺阶段不动任何采样
        val a = FloatArray(100) { 0.5f }
        val b = FloatArray(100) { -0.5f }
        val out = PcmJoiner.concat(listOf(a, b), rate, jointFadeMillis = 0)
        for (i in a.indices) assertEquals(a[i], out[i], 1e-9f)
        for (i in b.indices) assertEquals(b[i], out[a.size + i], 1e-9f)
    }

    @Test
    fun `淡出只影响接缝两侧_首尾不动`() {
        // ⚠️ 片段长度必须**远大于**淡出区，否则本用例测不到它声称的性质。
        // 原来这里用 1000 采样（20.8 ms）配 15 ms 淡出，`fade = min(720, len/2)`
        // 被 len/2 夹到 500 ⇒ 淡出区吃掉每片一半，「远离接缝处」压根不存在，
        // 于是断言 out[a.size]（那正是接缝本身，增益 1/500）必然红。
        // 真实小片约 1 秒，淡出 15 ms 只占 1.5%，性质才成立。
        val len = rate // 1 秒
        val a = FloatArray(len) { 0.8f }
        val b = FloatArray(len) { 0.8f }
        val out = PcmJoiner.concat(listOf(a, b), rate, jointFadeMillis = 15)
        val fade = minOf(15 * rate / 1000, len / 2)
        assertEquals("fixture 前提：淡出区应远小于片长", 720, fade)

        val seam = a.size // 接缝 = 第二片第 0 个采样的下标

        // 首片开头、末片结尾都在淡出区之外，必须逐字节保持原值
        assertEquals("整段开头不该被淡入削掉", 0.8f, out[0], 1e-6f)
        assertEquals("整段末尾不该被淡出削掉", 0.8f, out[out.size - 1], 1e-6f)
        // 淡出区之外的两侧（真正"远离接缝"）
        assertEquals(0.8f, out[seam - fade - 1], 1e-6f)
        assertEquals(0.8f, out[seam + fade], 1e-6f)
        // 接缝两侧本身必须被衰减，否则等于没淡出
        assertTrue("接缝前应被衰减，实际=${out[seam - 1]}", out[seam - 1] < 0.8f * 0.9f)
        assertTrue("接缝后应被衰减，实际=${out[seam]}", out[seam] < 0.8f * 0.9f)
    }

    @Test
    fun `淡出区两端增益恰为1`() {
        // 片段同样取 1 秒：1000 采样会让 fade 被 len/2 夹成 500，
        // 那样测到的"淡出区首采样"是巧合而非契约（见上一条用例的注释）。
        val len = rate
        val a = FloatArray(len) { 0.5f }
        val b = FloatArray(len) { 0.5f }
        val fade = minOf(15 * rate / 1000, len / 2)
        val out = PcmJoiner.concat(listOf(a, b), rate, jointFadeMillis = 15)
        val fadeStart = a.size - fade
        assertEquals("淡出区首采样增益应恰为 1.0", 0.5f, out[fadeStart], 1e-6f)
    }

    @Test
    fun `淡出确实压低了接缝处的突变`() {
        // 构造最恶劣场景：第一片末采样停在 +1，第二片首采样停在 -1（相位反相的阶跃）
        val a = FloatArray(2000) { 0f }.also { it[it.lastIndex] = 1f }
        val b = FloatArray(2000) { -1f }
        val noFade = PcmJoiner.concat(listOf(a, b), rate, jointFadeMillis = 0)
        val faded = PcmJoiner.concat(listOf(a, b), rate, jointFadeMillis = 15)
        val jumpBefore = abs(noFade[a.size] - noFade[a.size - 1])
        val jumpAfter = abs(faded[a.size] - faded[a.size - 1])
        assertTrue("接缝突变应被压低：前=$jumpBefore 后=$jumpAfter", jumpAfter < jumpBefore / 2)
    }

    @Test
    fun `静音占位长度正确`() {
        val pcm = PcmJoiner.silentPcm(rate)
        assertEquals(rate * 300 / 1000, pcm.size)
        assertTrue(pcm.all { it == 0f })
    }

    @Test
    fun `采样率不一致时抛异常而不是静默降级`() {
        // 静默降级会把「引擎返回了异常音频」变成「用户听到速度不对的声音」，无从排查
        val a = FloatArray(100) { 0.1f }
        val b = FloatArray(100) { 0.1f }
        try {
            // concat 本身不校验采样率（各片共用一个 rate 参数），
            // 所以这里验证的是：调用方传入非法 rate 时不会被悄悄接受
            PcmJoiner.concat(listOf(a, b), 0, jointFadeMillis = 15)
            // rate=0 且有采样内容时应抛
            org.junit.Assert.fail("采样率为 0 时应当抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("采样率"))
        }
    }
}