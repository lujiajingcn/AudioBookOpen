package com.audiobook.audiobookplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架任务状态文字**可点性**规则测试。
 *
 * 背景：需求是「点书名下方的状态信息，合成结束时也能进阅读页；
 * 合成没结束时点了不响应」。
 *
 * 判据抽成纯函数 [isSynthesisFinished] 后，才能把「哪些状态可点」钉死。
 *
 * ## 为什么这类判定必须有测试
 *
 * `ShelfAdapter.bindTasks` 会**复用**子 View（任务数不变时不重新 inflate）。
 * 可点性判定一旦漏写某个分支，上一条任务的可点性就会静默串到下一条——
 * 不崩溃、不闪烁、lint 也过，代码审查看不出来。
 */
class ShelfTaskStatusTest {

    @Test
    fun `合成完成时状态文字可点`() {
        assertTrue(isSynthesisFinished("completed", null))
    }

    @Test
    fun `合成进行中状态文字不可点`() {
        assertFalse(isSynthesisFinished("processing", null))
    }

    @Test
    fun `排队中与等待派发都不可点`() {
        assertFalse(isSynthesisFinished("queued", null))
        assertFalse(isSynthesisFinished("pending", null))
    }

    @Test
    fun `未知状态按进行中处理_不可点`() {
        // 保守：宁可不可点，也不把「还在合成」误判成「已结束」
        assertFalse(isSynthesisFinished("some_unknown_status", null))
        assertFalse(isSynthesisFinished("", null))
    }

    @Test
    fun `部分完成与失败都是终态_可点`() {
        assertTrue(isSynthesisFinished("partial", null))
        assertTrue(isSynthesisFinished("failed", null))
    }

    @Test
    fun `下载中不可点_状态文字显示的是下载进度`() {
        // 合成可能已经完成，但这一行此刻讲的是下载，不是合成结论
        assertFalse(isSynthesisFinished("completed", "downloading"))
        assertFalse(isSynthesisFinished("processing", "downloading"))
    }

    @Test
    fun `下载失败时按下载态处理_不可点`() {
        assertFalse(isSynthesisFinished("completed", "failed"))
        assertFalse(isSynthesisFinished("failed", "failed"))
    }

    @Test
    fun `下载完成后回到合成结论_可点`() {
        // downloadStatus = "completed" 表示下载正常结束，
        // 状态文字回到合成结论（已完成/失败），此时应当可点
        assertTrue(isSynthesisFinished("completed", "completed"))
        assertTrue(isSynthesisFinished("failed", "completed"))
    }

    /**
     * 矩阵：任何状态 × 任何下载态下，可点性必须与「合成是否已结束」完全一致。
     *
     * 判据的等价刻画：
     * - 下载中 / 下载失败 → 一定不可点（显示的是下载态）
     * - 其余 → 只有 completed / partial / failed 可点
     */
    @Test
    fun `可点性与合成终态矩阵完全一致`() {
        val statuses = listOf(
            "processing", "queued", "pending", "completed", "partial", "failed", "weird"
        )
        val downloads = listOf(null, "downloading", "failed", "completed")
        var clickableCount = 0
        for (s in statuses) for (d in downloads) {
            val expected = d != "downloading" && d != "failed" &&
                (s == "completed" || s == "partial" || s == "failed")
            assertEqualsWithMsg("状态($s) 下载态($d) 的可点性判定不符", expected, isSynthesisFinished(s, d))
            if (expected) clickableCount++
        }
        assertTrue("矩阵应覆盖到可点的组合（否则用例本身没鉴别力）", clickableCount > 0)
    }

    private fun assertEqualsWithMsg(msg: String, expected: Boolean, actual: Boolean) {
        if (expected != actual) {
            throw AssertionError("$msg：期望 $expected，实际 $actual")
        }
    }
}
