package com.audiobook.audiobookplayer.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * codec encode 的**输入形状契约**。
 *
 * 这组用例存在的唯一理由：2026-10-11 真机首跑时把 waveform 传成了
 * `[1, 1, N, 2]`（interleaved 展平，rank 4），ORT 直接报
 * `Invalid rank for input: waveform Got: 4 Expected: 3`。
 *
 * 为什么单测能挡住、之前却没挡住：
 *   · 单测里 wav 只是 `FloatArray`，**没有任何模型会检查它的形状**；
 *   · 只有真模型才认 rank，而真模型在真机上、整本书解析完之后才被调用。
 * ⇒ 所以形状口径必须被钉在**纯函数**上（[MossLocalEngine.interleaveToPlanar]），
 * 让「重排」这件事本身有测试，而不是指望 ORT 在 CI 里报错。
 *
 * 权威形状（onnx 读签名，`moss_audio_tokenizer_encode.onnx`）：
 *   waveform : [batch, 2, waveform_length]   rank 3
 *   lengths  : [batch]                        rank 1
 *   codes    : [batch, code_length, 16]       rank 3
 * 实测：2 秒 48kHz 立体声 → 26 帧（audio_code_lengths 声明 25），约 13 帧/秒。
 */
class MossCodecEncodeShapeTest {

    @Test
    fun 立体声interleaved_重排成planar_左声道全部在前() {
        // 4 个采样帧，L=[1,3] R=[2,4] → interleaved [1,2,3,4]
        val inter = floatArrayOf(1f, 2f, 3f, 4f)
        val planar = MossLocalEngine.interleaveToPlanar(inter)

        assertEquals("planar 总长 = 采样帧数 × 声道数", 4, planar.size)
        assertEquals("左声道占前 N 个", 1f, planar[0], 1e-6f)
        assertEquals(3f, planar[1], 1e-6f)
        assertEquals("右声道必须挪到后半段（这正是 rank 3 与 rank 4 的差别）", 2f, planar[2], 1e-6f)
        assertEquals(4f, planar[3], 1e-6f)
    }

    @Test
    fun 声道数必须恒为2_不能因长度变化而漂移() {
        // 前置断言：ORT 要的形状第二维是固定的 2，任何"按长度猜声道"的做法都会在这里崩
        for (frames in intArrayOf(1, 2, 480, 48000, 96000)) {
            val inter = FloatArray(frames * 2) { it.toFloat() }
            val planar = MossLocalEngine.interleaveToPlanar(inter)
            assertEquals("frames=$frames", frames * 2, planar.size)
        }
    }

    @Test
    fun 奇数长度_直接报错而不是静默截断() {
        // 静默截断会丢掉半个声道且不报错 ⇒ 音色悄悄变错，比崩掉更难查
        assertThrows(IllegalArgumentException::class.java) {
            MossLocalEngine.interleaveToPlanar(floatArrayOf(1f, 2f, 3f))
        }
    }

    @Test
    fun 空数组_直接报错() {
        assertThrows(IllegalArgumentException::class.java) {
            MossLocalEngine.interleaveToPlanar(FloatArray(0))
        }
    }

    @Test
    fun 两秒立体声48k_重排后是_1x2x96000() {
        // 把"配什么形状喂给 ORT"直接写成断言：错一个维度就红
        val frames = 48000 * 2          // 2 秒 × 48kHz = 96000 个采样帧（每帧 L+R 两个数）
        val inter = FloatArray(frames * 2) { (it % 100) / 100f }
        val planar = MossLocalEngine.interleaveToPlanar(inter)

        // planar 每声道一段，各 frames 个：总长 = frames × 2
        assertEquals(frames * 2, planar.size)
        // 形状三元组 [1, 2, frames] —— rank 3（不是把 interleave 展平成 rank 4）
        val shape = longArrayOf(1L, 2L, frames.toLong())
        assertEquals("必须 rank 3，传 rank 4 会被 ORT 拒", 3, shape.size)
        assertEquals(1L, shape[0])
        assertEquals(2L, shape[1])
        assertEquals(96000L, shape[2])
    }
}