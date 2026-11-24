package com.audiobook.audiobookplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文跟随播放位置时的「瞬跳 vs 平滑」判定。
 *
 * ## 为什么必须有
 *
 * 用户报的问题：点锁屏/通知栏的播放卡片回到阅读页，正文**一直往下滚好长时间**才停。
 * 根因是跟随逻辑无条件 `smoothScrollToPosition` —— 正在听第 1979 段时，正文要从
 * 第 1 行平滑滚到第 1979 行。
 *
 * 这类 bug 不会崩溃、不会报错，只有"听靠后的段落 + 点通知卡片"才复现，手工测很难覆盖
 * 全部距离区间。抽成纯函数后用边界值钉死：正好 20 行平滑、21 行瞬跳。
 */
class ReaderScrollPolicyTest {

    @Test
    fun `近距离向下跟随用平滑`() {
        // 下一段就在屏幕下方几行：平滑滚过去，眼睛能跟上
        assertFalse(shouldJumpInsteadOfSmooth(targetRow = 32, firstVisible = 20, lastVisible = 30))
    }

    @Test
    fun `近距离向上跟随用平滑`() {
        assertFalse(shouldJumpInsteadOfSmooth(targetRow = 15, firstVisible = 20, lastVisible = 30))
    }

    @Test
    fun `目标已在视口内不需要滚动`() {
        // 返回 false 让调用方走平滑分支 —— 平滑到可见项位移为零，等于不动
        assertFalse(shouldJumpInsteadOfSmooth(targetRow = 25, firstVisible = 20, lastVisible = 30))
        assertFalse(shouldJumpInsteadOfSmooth(targetRow = 20, firstVisible = 20, lastVisible = 30))
        assertFalse(shouldJumpInsteadOfSmooth(targetRow = 30, firstVisible = 20, lastVisible = 30))
    }

    @Test
    fun `恰好在阈值距离上仍用平滑`() {
        // last=30，目标 50 ⇒ 距离 20 == FOLLOW_SMOOTH_MAX_ROWS ⇒ 平滑（边界取闭区间）
        assertFalse(
            shouldJumpInsteadOfSmooth(
                targetRow = 30 + FOLLOW_SMOOTH_MAX_ROWS,
                firstVisible = 20,
                lastVisible = 30
            )
        )
    }

    @Test
    fun `超过阈值一行就瞬跳`() {
        // 距离 21 > 20 ⇒ 瞬跳。这就是本次要修的"滚好长时间"
        assertTrue(
            shouldJumpInsteadOfSmooth(
                targetRow = 30 + FOLLOW_SMOOTH_MAX_ROWS + 1,
                firstVisible = 20,
                lastVisible = 30
            )
        )
    }

    @Test
    fun `远距离向上同样瞬跳`() {
        // 用户从第 1979 段翻回开头，再让播放跟随 —— 往上也是长途
        assertTrue(
            shouldJumpInsteadOfSmooth(
                targetRow = 5,
                firstVisible = 500,
                lastVisible = 515
            )
        )
    }

    @Test
    fun `视口范围未知时直接瞬跳`() {
        // first/last 都是 NO_POSITION(-1)：没有锚点，平滑动画可能乱滚，直接跳更可控
        assertTrue(shouldJumpInsteadOfSmooth(targetRow = 0, firstVisible = -1, lastVisible = -1))
        assertTrue(shouldJumpInsteadOfSmooth(targetRow = 100, firstVisible = -1, lastVisible = -1))
    }

    @Test
    fun `没有目标行时不动`() {
        assertFalse(shouldJumpInsteadOfSmooth(targetRow = -1, firstVisible = 20, lastVisible = 30))
    }

    @Test
    fun `阈值可注入以便按字号调整`() {
        // 大字号下一屏只能放 8 行，调用方可以把阈值调小
        assertTrue(shouldJumpInsteadOfSmooth(55, 20, 30, smoothMaxRows = 8))
        assertFalse(shouldJumpInsteadOfSmooth(35, 20, 30, smoothMaxRows = 8))
    }
}
