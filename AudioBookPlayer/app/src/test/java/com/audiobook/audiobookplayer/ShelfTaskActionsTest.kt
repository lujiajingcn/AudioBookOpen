package com.audiobook.audiobookplayer

import com.audiobook.audiobookplayer.moss.LocalSynthesisService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架任务行**操作按钮**可见性规则测试。
 *
 * 背景：合成结束后任务行曾挂着「阅读并播放」按钮，但它与书籍行的点击是同一件事
 * （都进`BookContentActivity`），于是同一条已完成任务出现两个等价入口。
 * 现在任务行只保留**纯任务态**操作：暂停/继续、重试 / 继续合成、下载音频，
 * 进阅读器统一走书籍行。
 *
 * ## 为什么这类判定必须有测试
 *
 * `ShelfAdapter.bindTasks` 会**复用**子 View（任务数不变时不重新 inflate）。
 * 可见性判定一旦漏写某个分支，上一条任务的状态就会静默串到下一条——
 * 不崩溃、不闪烁、lint 也过，代码审查看不出来。
 * 把规则抽成纯函数 [computeTaskActions] 后，才能用矩阵把它钉死。
 */
class ShelfTaskActionsTest {

    /** 本机合成音色名前缀，用于构造 isLocalTask = true */
    private val localAudio = LocalSynthesisService.AUDIO_NAME_PREFIX + "晓妍"

    @Test
    fun `合成完成后不再显示任何操作按钮`() {
        val a = computeTaskActions(
            status = "completed",
            downloadStatus = null,
            hasAudio = true,
            isLocalTask = false
        )
        assertFalse("已完成不该有重试按钮", a.showRetry)
        assertFalse("已有本地音频就不该再有下载按钮", a.showDownload)
        assertFalse("整行操作区应收起", a.anyVisible)
    }

    @Test
    fun `本机合成完成后同样收起操作行`() {
        val a = computeTaskActions("completed", null, hasAudio = true, isLocalTask = true)
        assertFalse("本机 completed 不是 partial，不给「继续合成」", a.showRetry)
        assertFalse(a.showDownload)
        assertFalse(a.anyVisible)
    }

    @Test
    fun `已完成但尚无本地音频时保留下载入口`() {
        val a = computeTaskActions("completed", null, hasAudio = false, isLocalTask = false)
        assertFalse(a.showRetry)
        assertTrue("没有本地音频必须能下载", a.showDownload)
    }

    @Test
    fun `已有音频但上次下载失败时仍能补下缺失片段`() {
        val a = computeTaskActions("completed", "failed", hasAudio = true, isLocalTask = false)
        assertFalse(a.showRetry)
        assertTrue("部分片段下载失败要有补下入口", a.showDownload)
    }

    @Test
    fun `本机部分完成时给继续合成且不显示重试语义`() {
        val a = computeTaskActions("partial", null, hasAudio = true, isLocalTask = true)
        assertTrue("本机 partial 要能继续补段", a.showRetry)
        assertFalse("已有音频不必再下载", a.showDownload)
    }

    @Test
    fun `本机部分完成但剩余0段时不给继续合成`() {
        // 场景：最后一段刚写完、状态还没刷成 completed（或服务端下发的是
        // total == completed 的 partial）。此时「继续合成」点下去会空跑一遍、
        // 一段也不补 —— 按钮存在只会被当成功能坏了。
        val a = computeTaskActions(
            status = "partial",
            downloadStatus = null,
            hasAudio = true,
            isLocalTask = true,
            remainingSegments = 0
        )
        assertFalse("剩余 0 段不该给「继续合成」", a.showRetry)
        assertFalse("本机任务没有下载入口", a.showDownload)
        assertFalse("没有任何可操作按钮，操作行应收起", a.anyVisible)
    }

    @Test
    fun `本机部分完成且仍有剩余段时给继续合成`() {
        // 与上一条成对：判据必须是「剩余 > 0」而不是「状态 == partial」，
        // 否则容易把规则改成永不显示。
        val a = computeTaskActions(
            status = "partial",
            downloadStatus = null,
            hasAudio = true,
            isLocalTask = true,
            remainingSegments = 3
        )
        assertTrue("还剩 3 段时要能继续", a.showRetry)
    }

    @Test
    fun `在线部分完成剩余为0时不给补齐按钮`() {
        // 剩余 0 的 partial 是「末段刚写完、状态还没刷成 completed」。
        // 点「补齐」服务端会回「没有需要重试的片段」—— 一个必然失败的死按钮。
        val noLeft = computeTaskActions(
            "partial", null, hasAudio = false, isLocalTask = false, remainingSegments = 0
        )
        assertFalse("剩余 0 段没有可补的", noLeft.showRetry)
        assertTrue("在线任务音频没下完仍要能下载", noLeft.showDownload)
    }

    @Test
    fun `在线部分完成且已下载_补齐与下载同时给`() {
        // 这是「已下载的书永远缺那几段」的回归基线：原先 showRetry 要求 isLocalTask，
        // 在线 partial 一旦下载完，两个按钮都没有，用户再也不想不起这本书还差 4 段。
        val a = computeTaskActions(
            "partial", "completed", hasAudio = true, isLocalTask = false, remainingSegments = 4
        )
        assertTrue("在线 partial 也要能补齐缺段", a.showRetry)
        assertTrue("补齐后要靠下载把新增的段拉下来", a.showDownload)
    }

    @Test
    fun `在线完成的任务不给补齐按钮`() {
        // 只有 partial 才有「补」的语义；completed 是全部成功，多给一个按钮只会让人以为漏了什么。
        val a = computeTaskActions(
            "completed", "completed", hasAudio = true, isLocalTask = false, remainingSegments = 0
        )
        assertFalse(a.showRetry)
        assertFalse("已完成且已下载，没有可操作的", a.anyVisible)
    }

    @Test
    fun `未提供剩余段数时按旧规则处理`() {
        // 老调用点不传 remainingSegments（null = 未知），此时不能突然把按钮藏掉。
        val a = computeTaskActions("partial", null, hasAudio = true, isLocalTask = true)
        assertTrue("未知剩余段数时维持原行为", a.showRetry)
    }

    // ---------- 段数 → 剩余段数（含「总数未知」） ----------

    @Test
    fun `正常段数相减`() {
        assertEquals(7, remainingSegmentsOf(total = 10, completed = 3))
        assertEquals(0, remainingSegmentsOf(total = 10, completed = 10))
    }

    @Test
    fun `已完成超过总数时按0处理`() {
        // 数据对不齐（上一轮留下的 completed 比本轮 total 大）不该出现负剩余
        assertEquals(0, remainingSegmentsOf(total = 5, completed = 9))
    }

    @Test
    fun `总数为0表示未知_返回null而不是0`() {
        // 本机任务行先建行（total_segments = 0）后解析；解析前进程被杀 →
        // 孤儿对账把状态改成 partial，而总数仍是 0。若这里返回 0，
        // 「继续合成」会被判成「一段不剩」而藏掉，用户再也续不上这条任务。
        assertNull(remainingSegmentsOf(total = 0, completed = 0))
        assertNull(remainingSegmentsOf(total = -1, completed = 0))
    }

    @Test
    fun `总数为0的partial行仍然显示继续合成`() {
        // 与上一条串起来：remainingSegmentsOf 返回 null → computeTaskActions 维持
        // 按 partial 显示按钮（旧行为），而不是把它藏掉。
        val a = computeTaskActions(
            status = "partial",
            downloadStatus = null,
            hasAudio = true,
            isLocalTask = true,
            remainingSegments = remainingSegmentsOf(total = 0, completed = 0)
        )
        assertTrue("总数未知的 partial 任务必须能续跑", a.showRetry)
    }

    @Test
    fun `在线部分完成时没有本地音频_补齐与下载都给`() {
        val a = computeTaskActions("partial", null, hasAudio = false, isLocalTask = false)
        assertTrue("在线 partial 要能补齐缺段", a.showRetry)
        assertTrue(a.showDownload)
    }

    @Test
    fun `合成失败时给重试入口`() {
        val a = computeTaskActions("failed", null, hasAudio = false, isLocalTask = false)
        assertTrue(a.showRetry)
        assertFalse("失败不是下载态问题", a.showDownload)
    }

    @Test
    fun `本机任务音频被清理后不给下载按钮`() {
        // 本机合成没有服务端可下载（没有 server_task_id）：音频被清理后若还挂着
        // 「下载音频」，点下去只会得到「任务尚未同步到服务器」—— 是个死按钮。
        // 本机音频没了只有一条路：重新合成。
        val a = computeTaskActions("partial", null, hasAudio = false, isLocalTask = true)
        assertFalse("本机任务不该有下载入口", a.showDownload)
        assertTrue("本机 partial 要能重新合成", a.showRetry)
    }

    @Test
    fun `在线任务音频被清理后仍给下载按钮`() {
        // 在线任务的音频在服务端，重新下载可行，入口必须留着。
        val a = computeTaskActions("completed", null, hasAudio = false, isLocalTask = false)
        assertTrue("在线任务音频没了要能重新下载", a.showDownload)
        assertFalse(a.showRetry)
    }

    @Test
    fun `在线任务的运行中与排队中都不显示操作按钮`() {
        // 在线合成的状态在服务端，本机挂不住它 ⇒ 不给暂停按钮。
        for (status in listOf("processing", "queued", "pending", "some_unknown_status")) {
            val a = computeTaskActions(status, null, hasAudio = false, isLocalTask = false)
            assertFalse("$status 不该有重试", a.showRetry)
            assertFalse("$status 不该有下载", a.showDownload)
            assertFalse("$status 不该有暂停", a.showPause)
            assertFalse("$status 不该显示操作行", a.anyVisible)
        }
    }

    @Test
    fun `下载中覆盖一切状态_即使已完成也收起按钮`() {
        // 回归基线：旧实现里「下载中」是靠每个分支各写一遍visibility 实现的，
        // 一旦新增状态忘了写，就会露出按钮。
        for (status in listOf("completed", "partial", "failed", "processing")) {
            val a = computeTaskActions(status, "downloading", hasAudio = true, isLocalTask = true)
            assertFalse("下载中($status) 不该显示操作行", a.anyVisible)
        }
    }

    // ---------- 暂停/继续（本机合成进行中） ----------

    @Test
    fun `本机合成进行中显示暂停按钮`() {
        val a = computeTaskActions(
            status = "processing",
            downloadStatus = null,
            hasAudio = false,
            isLocalTask = true,
            canPauseLocal = true
        )
        assertTrue("本机合成进行中要能暂停", a.showPause)
        assertEquals("按钮文案应为「暂停」", PAUSE_LABEL, a.pauseLabel)
        assertTrue(a.anyVisible)
    }

    @Test
    fun `暂停后按钮文案变成继续`() {
        val a = computeTaskActions(
            status = "processing",
            downloadStatus = null,
            hasAudio = false,
            isLocalTask = true,
            isPaused = true,
            canPauseLocal = true
        )
        assertTrue("已暂停时要能继续", a.showPause)
        assertEquals("按钮文案应为「继续」", RESUME_LABEL, a.pauseLabel)
    }

    @Test
    fun `在线合成进行中没有暂停按钮`() {
        // 在线合成的状态在服务端，本机没有任何能挂起它的开关 ——
        // 挂一个按钮点下去必然没反应。
        val a = computeTaskActions(
            status = "processing",
            downloadStatus = null,
            hasAudio = false,
            isLocalTask = false,
            canPauseLocal = true
        )
        assertFalse("在线任务挂不住", a.showPause)
        assertFalse(a.anyVisible)
    }

    @Test
    fun `服务没在跑时不显示暂停按钮`() {
        // 进程被杀留下的孤儿「合成中」行：服务根本不在跑，
        // 挂上去就是个点了没反应的死按钮。
        val a = computeTaskActions(
            status = "processing",
            downloadStatus = null,
            hasAudio = false,
            isLocalTask = true,
            canPauseLocal = false
        )
        assertFalse(a.showPause)
        assertFalse(a.anyVisible)
    }

    @Test
    fun `暂停按钮只出现在合成中`() {
        for (status in listOf("queued", "pending", "completed", "partial", "failed", "weird")) {
            val a = computeTaskActions(
                status = status,
                downloadStatus = null,
                hasAudio = false,
                isLocalTask = true,
                isPaused = true,
                canPauseLocal = true
            )
            assertFalse("$status 不该有暂停按钮", a.showPause)
        }
    }

    /**
     * 判「操作行可见性一致」的等价刻画：任何状态、任何下载态下，
     * `anyVisible` 必须与三个按钮的可见性完全一致，且暂停按钮只出现在
     * 「本机 + 合成中 + 服务在跑」这一种组合上。
     */
    @Test
    fun `任何组合下操作行的按钮集合与anyVisible一致`() {
        val statuses = listOf("processing", "queued", "pending", "completed", "partial", "failed", "weird")
        val downloads = listOf(null, "downloading", "failed", "completed")
        var visibleCount = 0
        for (s in statuses) for (d in downloads) for (local in listOf(true, false))
            for (audio in listOf(true, false)) for (busy in listOf(true, false))
                for (paused in listOf(true, false)) {
            val a = computeTaskActions(s, d, audio, local, isPaused = paused, canPauseLocal = busy)
            if (a.showRetry) visibleCount++
            if (a.showDownload) visibleCount++
            if (a.showPause) visibleCount++
            if (a.anyVisible) {
                assertTrue(
                    "anyVisible 必须在至少一个按钮可见时才为 true ($s/$d)",
                    a.showRetry || a.showDownload || a.showPause
                )
            } else {
                assertFalse("anyVisible 为 false 时三个按钮都要隐藏 ($s/$d)", a.showRetry || a.showDownload || a.showPause)
            }
            if (a.showPause) {
                assertTrue("暂停按钮只给本机合成", local)
                assertTrue("暂停按钮只在合成中出现", s == "processing")
                assertTrue("暂停按钮要求服务在跑", busy)
            }
        }
        assertTrue("矩阵应覆盖到按钮可见的组合（否则用例本身没鉴别力）", visibleCount > 0)
    }
}
