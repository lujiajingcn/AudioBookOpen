package com.audiobook.audiobookplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「回到播放处」按钮的目标行与显隐判定。
 *
 * ## 为什么必须有
 *
 * 这枚按钮 2026-10-30 起同时服务**朗读**与**有声书播放**两种形态，而它此前只认朗读 ——
 * 有声书播放时滚到别处，底栏什么都不会出现（本次修复的问题）。这类 bug 的表现是
 * "该出的没出"，不崩溃、不报错，只能靠真机手工滚一遍才发现；而真机又不方便穷举
 * 「朗读中 / 播放中 / 两者并存 / 列表未布局」这些组合。
 *
 * 抽成纯函数后用矩阵钉死，Activity 侧只剩"取当前值 + 套用结果"。
 */
class BackToPlayTargetTest {

    // ---------- resolveBackToPlayTargetRow ----------

    @Test
    fun `有声书播放时目标行是播放位置`() {
        // 本次修复的核心场景：没在朗读，只有声书在播，也要能算出一个目标行 ——
        // 否则按钮永远不会出现。
        assertEquals(
            42,
            resolveBackToPlayTargetRow(
                readAloudActive = false,
                readAloudRow = NO_PLAYBACK_TARGET,
                audioPlayingRow = 42
            )
        )
    }

    @Test
    fun `朗读时目标行是朗读行`() {
        assertEquals(
            7,
            resolveBackToPlayTargetRow(
                readAloudActive = true,
                readAloudRow = 7,
                audioPlayingRow = NO_PLAYBACK_TARGET
            )
        )
    }

    @Test
    fun `朗读与有声书同时存在时朗读优先`() {
        // 朗读是当前页面上正在进行的动作；有声书可能只是后台跨书播放的残留会话。
        assertEquals(
            7,
            resolveBackToPlayTargetRow(
                readAloudActive = true,
                readAloudRow = 7,
                audioPlayingRow = 99
            )
        )
    }

    @Test
    fun `朗读刚起尚未定位到行时落到有声书位置`() {
        // readAloudActive=true 但 readAloudRow=-1（还没读到第一行）：不能因此返回 -1，
        // 那会把正在播的有声书的出口也一起吞掉。
        assertEquals(
            99,
            resolveBackToPlayTargetRow(
                readAloudActive = true,
                readAloudRow = NO_PLAYBACK_TARGET,
                audioPlayingRow = 99
            )
        )
    }

    @Test
    fun `两种播放都没有时返回哨兵值`() {
        assertEquals(
            NO_PLAYBACK_TARGET,
            resolveBackToPlayTargetRow(
                readAloudActive = false,
                readAloudRow = NO_PLAYBACK_TARGET,
                audioPlayingRow = NO_PLAYBACK_TARGET
            )
        )
    }

    @Test
    fun `朗读中但没有位置也没有音频位置时返回哨兵值`() {
        assertEquals(
            NO_PLAYBACK_TARGET,
            resolveBackToPlayTargetRow(
                readAloudActive = true,
                readAloudRow = NO_PLAYBACK_TARGET,
                audioPlayingRow = NO_PLAYBACK_TARGET
            )
        )
    }

    // ---------- shouldShowBackToPlayButton ----------

    @Test
    fun `目标行在视口之上时显示`() {
        // 用户往下滚了，播放位置在上方
        assertTrue(shouldShowBackToPlayButton(targetRow = 5, firstVisible = 20, lastVisible = 35))
    }

    @Test
    fun `目标行在视口之下时显示`() {
        // 用户往上滚了，播放位置在下方
        assertTrue(shouldShowBackToPlayButton(targetRow = 80, firstVisible = 20, lastVisible = 35))
    }

    @Test
    fun `目标行就在视口内不显示`() {
        // 正文已经在播放位置，"回去"是空操作
        assertFalse(shouldShowBackToPlayButton(targetRow = 25, firstVisible = 20, lastVisible = 35))
    }

    @Test
    fun `目标行正好是首个或末个可见行时不显示`() {
        // 边界：部分可见也算"人在这一页上"
        assertFalse(shouldShowBackToPlayButton(targetRow = 20, firstVisible = 20, lastVisible = 35))
        assertFalse(shouldShowBackToPlayButton(targetRow = 35, firstVisible = 20, lastVisible = 35))
    }

    @Test
    fun `没有目标行时不显示`() {
        // 没播放也没朗读：按钮没有任何意义
        assertFalse(
            shouldShowBackToPlayButton(
                targetRow = NO_PLAYBACK_TARGET,
                firstVisible = 20,
                lastVisible = 35
            )
        )
    }

    @Test
    fun `列表还没布局完时不显示`() {
        // first/last 都是 NO_POSITION(-1)：此刻判不出可见范围，
        // 保守不显示 —— 总比短暂闪一下按钮再消失好。
        // 注意这里 targetRow=0 是合法行号，不能因为"0 看起来像假值"就误判。
        assertFalse(shouldShowBackToPlayButton(targetRow = 0, firstVisible = -1, lastVisible = -1))
        assertFalse(shouldShowBackToPlayButton(targetRow = 5, firstVisible = -1, lastVisible = 10))
        assertFalse(shouldShowBackToPlayButton(targetRow = 5, firstVisible = 10, lastVisible = -1))
    }

    @Test
    fun `单行可见且正是目标行时不显示`() {
        assertFalse(shouldShowBackToPlayButton(targetRow = 0, firstVisible = 0, lastVisible = 0))
    }
}
