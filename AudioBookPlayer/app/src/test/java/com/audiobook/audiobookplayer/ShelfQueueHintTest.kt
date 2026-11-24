package com.audiobook.audiobookplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「哪些任务行的进度文案会显示服务端排队规模」的判定测试。
 *
 * ## 为什么必须有测试
 *
 * 这个判定有两个使用者，而且**用途不同**：
 * 1. `ShelfAdapter.bindTask` 决定要不要把「0 / N」换成「队列约 N 个任务」；
 * 2. `ShelfAdapter.submitShelfItems` 决定排队规模变化后**哪些行需要重绑**
 *    （DiffUtil 看不见这个值，不重绑就永远停在旧数字上）。
 *
 * 两处一旦口径不一致，症状都是「不崩不闪、只是数字是错的」：
 * 判宽了 → 每几秒跟着合成进度闪一次；判窄了 → 队列数字永远不更新。
 * 这类 bug 只能靠用例钉死。
 */
class ShelfQueueHintTest {

    @Test
    fun `在线任务排队中显示排队规模`() {
        assertTrue(isQueueHintRow("queued", isLocalTask = false))
    }

    @Test
    fun `在线任务等待派发显示排队规模`() {
        assertTrue(isQueueHintRow("pending", isLocalTask = false))
    }

    @Test
    fun `本机合成排队中不显示服务端排队规模`() {
        // 本机合成有自己的一套队列，与服务端全局队列无关：
        // 给它显示「队列约 12 个任务」等于凭空告诉用户还要等 12 个。
        assertFalse(isQueueHintRow("queued", isLocalTask = true))
        assertFalse(isQueueHintRow("pending", isLocalTask = true))
    }

    @Test
    fun `有确切进度的状态一律不换成排队规模`() {
        // 合成中 / 已完成 / 部分完成 / 失败都有「x / N」或终态结论，
        // 用全局排队数盖掉它只会把真正在动的信息藏起来。
        listOf("processing", "completed", "partial", "failed").forEach { status ->
            assertFalse("$status 不该显示排队规模", isQueueHintRow(status, isLocalTask = false))
        }
    }

    @Test
    fun `未知状态保守处理_不显示排队规模`() {
        assertFalse(isQueueHintRow("whatever", isLocalTask = false))
        assertFalse(isQueueHintRow("", isLocalTask = false))
    }
}
