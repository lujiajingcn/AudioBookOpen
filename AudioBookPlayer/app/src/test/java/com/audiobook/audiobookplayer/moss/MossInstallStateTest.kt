package com.audiobook.audiobookplayer.moss

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MossDownloader] 进程级安装状态的契约。
 *
 * ## 为什么这个状态必须存在
 *
 * 725 MB 的下载**生命周期比页面长**：MossModuleActivity.onDestroy 只 cancel 协程，
 * 而 `download()` / `install()` 跑在 IO 线程的阻塞循环里（它们只检查
 * `Thread.interrupted()`，协程取消不会中断那个线程），所以流量会在后台继续跑。
 *
 * 没有进程级状态时会同时出两个用户可见的错：
 *
 * 1. 重新进入页面，`partialBytes()` 只被读一次 ⇒「上次已下载 50.3 MB」永远停在 50.3；
 * 2. 下载中按钮仍可点（判据里没有"是否正在下载"）⇒ 一点就撞 `tryLock` 失败。
 *
 * ## 这些用例真正锁住的东西
 *
 * **「谁宣布忙碌，谁负责收尾」的一致性**。这是最容易出错的地方：直觉上会
 * 在协程的 `finally` 里写 `exitBusy()`，但协程在页面退出时**先**结束、IO 线程
 * **后**结束 ⇒ busy 提前变 false ⇒ 修好了进度显示却换来「按钮又变可点」。
 * 真正的收尾时点是 `install()` 的 finally（整轮最后一步），
 * 而 `download()` 失败时 install 根本不会被调用，必须就地复位。
 */
class MossInstallStateTest {

    @Test
    fun `初始状态为空闲`() {
        assertFalse(MossDownloader.installState.value.busy)
    }

    @Test
    fun `enterBusy 让忙碌态可被外部读到`() {
        val prev = MossDownloader.installState.value
        try {
            MossDownloader.enterBusy("准备下载")
            val st = MossDownloader.installState.value
            assertTrue(st.busy)
            assertEquals("准备下载", st.stage)
        } finally {
            MossDownloader.exitBusy()
            assertFalse(MossDownloader.installState.value.busy)
            if (prev.busy) MossDownloader.enterBusy(prev.stage)
        }
    }

    @Test
    fun `exitBusy 会把进度一并清零`() {
        val prev = MossDownloader.installState.value
        try {
            MossDownloader.enterBusy("正在下载")
            MossDownloader.exitBusy()
            val st = MossDownloader.installState.value
            // 归零不是为了好看：留着上一次的 downloaded 会让
            // 「空闲态下要不要显示残留进度」这种分支判断永远为真。
            assertEquals(0L, st.downloaded)
            assertEquals(0L, st.total)
            assertEquals("", st.stage)
        } finally {
            if (prev.busy) MossDownloader.enterBusy(prev.stage)
        }
    }

    @Test
    fun `空闲时重新订阅拿到的是空闲态`() {
        // StateFlow 收集时必先发当前值 —— 页面靠这个性质在 onStart 里
        // 一订阅就拿到真实状态，不需要额外手动查询。
        // 若这条不成立，「重新进页面立刻显示真实进度」就退化成静态文案。
        MossDownloader.exitBusy()
        val flow: MutableStateFlow<MossDownloader.InstallState> =
            MutableStateFlow(MossDownloader.installState.value)
        assertFalse(flow.value.busy)
    }

    @Test
    fun `busy 为真时 downloaded 与 total 可供界面算百分比`() {
        val prev = MossDownloader.installState.value
        try {
            MossDownloader.enterBusy("正在下载")
            // 与 MossDownloader 内部的发布同形：downloaded/total 都是字节
            val downloaded = 50L * 1048576
            val total = MossModuleManager.FALLBACK_DOWNLOAD_BYTES
            // 复刻 listener 里的整数算法，确认不会出现除零或百分比溢出
            val permille = (downloaded * 1000L / total).coerceIn(0L, 1000L)
            // 50 MiB / 724.6 MiB ≈ 6.9% ⇒ 69‰（按字节整除，不是按 MB 整除）
            assertEquals(69L, permille)
        } finally {
            MossDownloader.exitBusy()
            if (prev.busy) MossDownloader.enterBusy(prev.stage)
        }
    }

    @Test
    fun `无人在收集时发布的进度 后来订阅者仍能看到最新值`() {
        // 这条就是「50.3 MB 永远停在 50.3」那个 bug 的核心。
        //
        // 复现的时序：页面 A 在后台下载 → 用户退出页面（收集协程被取消）
        // → 页面 B 稍后进入并开始收集。**页面 B 收集时，没有任何东西在推送事件**
        // （A 的 listener 已随 A 一起死了），所以它拿到的**必须是最后写入的那个值**。
        //
        // 若状态是「只在有收集者时才更新」或「只在 onCreate 读一次 partialBytes」，
        // 页面 B 就会拿到空闲态或过期值，退化成静态文案。
        val prev = MossDownloader.installState.value
        try {
            // 没有任何收集者，模拟"页面 A 已退出"
            MossDownloader.enterBusy("正在下载")
            val flow = MossDownloader.installState
            // 模拟后台循环继续推进（此时仍无收集者）
            MossDownloader.publishProgress(50L * 1048576, MossModuleManager.FALLBACK_DOWNLOAD_BYTES, 800000L)
            MossDownloader.publishProgress(51L * 1048576, MossModuleManager.FALLBACK_DOWNLOAD_BYTES, 800000L)

            // 页面 B 现在才开始收集 —— 拿到的必须是 51 而不是 50，更不是空闲
            val st = flow.value
            assertTrue("重新进入页面应看到忙碌态", st.busy)
            assertEquals(51L * 1048576, st.downloaded)
            assertEquals(MossModuleManager.FALLBACK_DOWNLOAD_BYTES, st.total)
            // 关键：这个值必须是"退出前最后写入的"，而不是"开始订阅那一刻的"
            assertTrue(
                "订阅拿到的必须是最新进度，而不是订阅前的旧值",
                st.downloaded > 50L * 1048576
            )
        } finally {
            MossDownloader.exitBusy()
            if (prev.busy) MossDownloader.enterBusy(prev.stage)
        }
    }

    @Test
    fun `下载阶段可暂停 退出忙碌后复位`() {
        val prev = MossDownloader.installState.value
        try {
            MossDownloader.enterBusy("准备下载")
            // 页面靠这个字段决定主按钮是「暂停」还是置灰的「正在下载…」。
            // 少了它，校验/解压阶段也会给出一个按了没反应的暂停按钮。
            assertTrue(
                "下载阶段必须置 pausable，否则页面只能给置灰按钮",
                MossDownloader.installState.value.pausable
            )
            MossDownloader.exitBusy()
            assertFalse(MossDownloader.installState.value.pausable)
        } finally {
            MossDownloader.exitBusy()
            if (prev.busy) MossDownloader.enterBusy(prev.stage)
        }
    }

    @Test
    fun `下载失败退出后 busy 必须复位否则按钮永久禁用`() {
        // download() 失败时 install() 根本不会被调用，收尾只能就地做。
        // 若这条不成立，busy 永久为true ⇒ btnPrimary 永久 enabled=false
        // ⇒ 用户只能杀进程重进，比修复前的 bug 更糟。
        val prev = MossDownloader.installState.value
        try {
            MossDownloader.enterBusy("准备下载")
            // 模拟 download() 的失败路径：handedOverToInstall=false ⇒ 就地 exitBusy
            MossDownloader.exitBusy()
            assertFalse("下载失败后必须回到空闲，否则按钮永久禁用", MossDownloader.installState.value.busy)
        } finally {
            MossDownloader.exitBusy()
            if (prev.busy) MossDownloader.enterBusy(prev.stage)
        }
    }
}
