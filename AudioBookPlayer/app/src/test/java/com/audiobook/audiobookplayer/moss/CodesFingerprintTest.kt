package com.audiobook.audiobookplayer.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [codesFingerprint] 的口径锁死。
 *
 * ## 为什么这个测试必须写死期望值，而不是"跑两次看一样吗"
 * `codesFingerprint` 的用途是让**外部脚本**（本机 Python 跑同一个 encode 模型）
 * 能算出同一个指纹，用来证明真机那批 prompt 码确实来自某个参考音频。
 * 一旦算法细节（字节序、哈希变体、掩码范围）被无意改动，两边会算出不同的数，
 * 而"本地自测仍然通过"—— 所以期望值必须是**预先算好的常量**，不是现场算的。
 *
 * 下面的期望值由 Python 独立实现同一个 FNV-1a 32bit 算出：
 *   fnv([[0..15]])             = 93aab7c5
 *   fnv([[0..15],[16..31]])   = 80421a45
 *   fnv([[0..15]] * 3)         = d4c4ebc5
 * 换算法 → 这三条必挂；换字节序 → 也会挂。
 */
class CodesFingerprintTest {

    @Test
    fun `单帧固定向量指纹与外部实现一致`() {
        val fp = codesFingerprint(listOf(IntArray(16) { it }))
        assertEquals("93aab7c5", fp.sum)
        assertEquals("0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15", fp.first.joinToString(","))
        assertEquals(fp.first.joinToString(","), fp.last.joinToString(","))
    }

    @Test
    fun `两帧固定向量指纹与外部实现一致`() {
        val codes = listOf(IntArray(16) { it }, IntArray(16) { it + 16 })
        assertEquals("80421a45", codesFingerprint(codes).sum)
    }

    @Test
    fun `三帧相同内容指纹与外部实现一致`() {
        val codes = List(3) { IntArray(16) { it } }
        assertEquals("d4c4ebc5", codesFingerprint(codes).sum)
    }

    @Test
    fun `指纹对码顺序敏感——首帧反序必须不同`() {
        val normal = codesFingerprint(listOf(IntArray(16) { it })).sum
        val reversed = codesFingerprint(listOf(IntArray(16) { 15 - it })).sum
        assertNotEquals(normal, reversed)
        assertEquals("20183fc5", reversed)
    }

    @Test
    fun `指纹对帧顺序敏感——交换两帧必须不同`() {
        val a = listOf(IntArray(16) { it }, IntArray(16) { it + 16 })
        val b = listOf(a[1], a[0])
        assertNotEquals(codesFingerprint(a).sum, codesFingerprint(b).sum)
    }

    @Test
    fun `改动一个码就会改指纹`() {
        val base = List(4) { f -> IntArray(16) { f * 16 + it } }
        val mutated = base.map { it.copyOf() }.also { it[2][7] += 1 }
        assertNotEquals(codesFingerprint(base).sum, codesFingerprint(mutated).sum)
    }

    @Test
    fun `空码序列直接抛异常而不是返回垃圾指纹`() {
        assertThrows(IllegalArgumentException::class.java) {
            codesFingerprint(emptyList())
        }
    }

    @Test
    fun `返回的首末帧是拷贝——外部改动不污染原序列`() {
        val codes = listOf(IntArray(16) { it }, IntArray(16) { it + 16 })
        val fp = codesFingerprint(codes)
        fp.first[0] = 999
        fp.last[0] = 999
        assertEquals(0, codes[0][0])
        assertEquals(16, codes[1][0])
    }
}
