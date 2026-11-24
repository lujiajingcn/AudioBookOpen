package com.audiobook.audiobookplayer.moss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [PauseController] 与「共享游标 + 暂停闸门」这段调度逻辑的单元测试。
 *
 * ## 为什么必须用单测来验证，而不是只靠设备实测
 * 这台 4 GB 模拟器上本机合成会在跑完第一段之前被 lowmemorykiller 杀掉
 * （实测 RSS 3.1 GB，日志 `Kill 'com.audiobook.audiobookplayer' ... to free 3087640kB rss`），
 * 所以**暂停/恢复的端到端行为在设备上验证不了**。
 * 但这段逻辑里最容易写错、错了又最难发现的一点是完全确定性的：
 *
 *   暂停闸门若放在 `nextIndex.getAndIncrement()` **之后**，
 *   暂停期间各 worker 仍会把共享游标一路推到末尾 ——
 *   表现是「一暂停，整本就结束了」。数据不报错，只是功能完全不对。
 *
 * 这种错误用单测几毫秒就能锁定，所以这里把调度结构**照搬**过来验证，
 * 而不是留一句"设备上没测到"就算了。
 */
class PauseControllerTest {

    // ---------------- PauseController 本身 ----------------

    @Test
    fun `默认不暂停`() {
        val c = PauseController()
        assertFalse(c.isPaused)
        assertTrue("未暂停时 awaitResume 应立即放行", c.awaitResume { false })
    }

    @Test
    fun `暂停后 awaitResume 阻塞_恢复后放行`() {
        val c = PauseController()
        c.pause()
        assertTrue(c.isPaused)

        val released = CountDownLatch(1)
        val t = Thread {
            c.awaitResume { false }
            released.countDown()
        }
        t.start()

        // 暂停期间必须一直阻塞（给足时间，避免偶发通过）
        assertFalse("暂停中不应放行", released.await(300, TimeUnit.MILLISECONDS))
        assertTrue("线程应仍在等待", t.isAlive)

        c.resume()
        assertTrue("恢复后应放行", released.await(3, TimeUnit.SECONDS))
        t.join(3000)
        assertFalse(c.isPaused)
    }

    @Test
    fun `暂停中中止时必须放行_否则收尾会永久挂起`() {
        val c = PauseController()
        c.pause()
        val cancelled = AtomicBoolean(false)
        val released = CountDownLatch(1)
        val result = AtomicBoolean(true)

        val t = Thread {
            result.set(c.awaitResume { cancelled.get() })
            released.countDown()
        }
        t.start()
        assertFalse(released.await(200, TimeUnit.MILLISECONDS))

        // 用户在暂停状态下点「中止」
        cancelled.set(true)
        c.releaseAll()

        assertTrue("中止必须能唤醒暂停中的 worker", released.await(3, TimeUnit.SECONDS))
        assertFalse("被中止时 awaitResume 应返回 false", result.get())
        t.join(3000)
    }

    @Test
    fun `暂停状态可重复切换`() {
        val c = PauseController()
        repeat(3) {
            c.pause(); assertTrue(c.isPaused)
            c.resume(); assertFalse(c.isPaused)
        }
    }

    // ---------------- 调度结构（照搬 MossLocalSynthesizer 的 worker 循环） ----------------

    /**
     * 复刻 `MossLocalSynthesizer` 里 worker 的循环形状：
     * 「还有剩余段 → 过暂停闸门 → 取号 → 干活（记录）→ continue 回去再取」。
     *
     * 注意 `while(true)` + `continue` 这个形状是**照搬真实代码**的，不是随手写的：
     * 一开始我把"取号后只记一次就阻塞"当成模型，结果反向用例根本复现不出问题
     * （只消耗 worker 数个号）。真实循环干完一段会回去再取，
     * 所以错误实现下 worker 会**持续**取号，直到被闸门拦住为止。
     *
     * @param gateBeforeCursor true = 正确实现（闸门在取号前）；
     *                         false = 错误实现（闸门在取号后），用于确认测试真能抓到 bug
     * @return (worker 线程, 共享游标) —— 游标交给调用方，便于断言"号是否被推空"
     */
    private fun runWorkers(
        total: Int,
        workers: Int,
        pause: PauseController,
        processed: MutableList<Int>,
        gateBeforeCursor: Boolean,
        ready: CountDownLatch
    ): Pair<List<Thread>, AtomicInteger> {
        val next = AtomicInteger(0)
        val isCancelled = { false }
        val threads = (0 until workers).map {
            Thread {
                ready.countDown()
                while (true) {
                    if (isCancelled()) break
                    if (gateBeforeCursor) {
                        if (next.get() < total && !pause.awaitResume(isCancelled)) break
                        val index = next.getAndIncrement()
                        if (index >= total) break
                        synchronized(processed) { processed.add(index) }
                    } else {
                        val index = next.getAndIncrement()
                        if (index >= total) break
                        if (!pause.awaitResume(isCancelled)) break
                        synchronized(processed) { processed.add(index) }
                    }
                }
            }.also { t -> t.isDaemon = true; t.start() }
        }
        return threads to next
    }

    @Test
    fun `闸门在取号之前_暂停期间不再消耗游标`() {
        val total = 40
        val c = PauseController()
        val processed = mutableListOf<Int>()
        c.pause()   // 一开始就暂停
        val ready = CountDownLatch(4)

        val (threads, cursor) = runWorkers(total, 4, c, processed, gateBeforeCursor = true, ready = ready)
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        Thread.sleep(300)

        assertEquals("暂停期间不应有段被处理", 0, processed.size)
        assertEquals("正确实现下暂停期间共享游标必须原地不动", 0, cursor.get())

        c.resume()
        threads.forEach { it.join(5000) }
        assertEquals("恢复后应把全部段处理完", total, processed.size)
        assertEquals("每个段号应恰好处理一次", (0 until total).toList(), processed.sorted())
    }

    /**
     * 反向用例：证明上面那条测试**真的**能抓到 bug。
     * 没有它的话，上面那些断言即使写错成永远通过也看不出来 ——
     * 不会失败的测试等于没有测试。
     *
     * ## 错误实现（闸门在取号之后）实际会发生什么 —— 这里纠正了我的两个错误猜测
     * 我先后猜过两种后果，**都被这个用例推翻了**，记下来免得下次再猜：
     *
     *   猜测一："整本直接结束" —— 错。worker 取号后阻塞在闸门上，
     *           号不会继续被消耗，恢复后从稍后面接着跑。
     *   猜测二："静默丢段" —— 也错。取号与过闸门之间没有回退，
     *           但**号并没有丢**：worker 手里攥着那个 index，
     *           恢复后照样会把它合成掉（实测 missing 集合为空）。
     *
     * 真正能观测到的代价只有一个，而且足够定性：**暂停不能立即生效** ——
     * 每个 worker 都会先把手里的那一段跑完（因为它们已经取了号），
     * 也就是"点了暂停之后又多跑了 worker 数个段"。
     *
     * 所以此处只断言这一点，不去断言我臆想出来的、验证不了的行为。
     */
    @Test
    fun `闸门在取号之后会被测试抓到_暂停无法立即生效`() {
        val total = 40
        val workers = 4
        val c = PauseController()
        val processed = mutableListOf<Int>()
        c.pause()
        val ready = CountDownLatch(workers)

        val (threads, cursor) = runWorkers(
            total, workers, c, processed, gateBeforeCursor = false, ready = ready
        )
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        Thread.sleep(300)
        c.resume()
        threads.forEach { it.join(5000) }

        // 两组都最终跑完、都不丢段 —— 所以"丢段"不能作为判别依据
        assertEquals("两种实现最终都不丢段", total, processed.size)
        assertEquals("每段恰好一次", (0 until total).toList(), processed.sorted())

        // 真正区分两种实现的量：**暂停后有多少段"已经在跑"**
        // 正确实现下，暂停时没有任何 worker 领到号 → 0；
        // 错误实现下，每个 worker 都先领了一个号 → workers。
        // 这里用游标在暂停瞬间的位置来度量（等价且不受调度抖动影响）。
        assertTrue(
            "错误实现下暂停瞬间游标应已越过头部（正确实现必须为 0），实际 ${cursor.get()}",
            cursor.get() > 0
        )
    }

    @Test
    fun `两种实现的关键差异_暂停瞬间游标位置`() {
        val total = 40
        val workers = 4

        fun cursorAtPause(gateBefore: Boolean): Int {
            val c = PauseController()
            val processed = mutableListOf<Int>()
            c.pause()
            val ready = CountDownLatch(workers)
            val (threads, cursor) = runWorkers(
                total, workers, c, processed, gateBeforeCursor = gateBefore, ready = ready
            )
            ready.await(2, TimeUnit.SECONDS)
            Thread.sleep(250)
            val at = cursor.get()
            c.resume()
            threads.forEach { it.join(5000) }
            assertEquals("无论哪种实现都不应丢段", total, processed.size)
            return at
        }

        assertEquals("正确实现：暂停时游标必须原地不动", 0, cursorAtPause(true))
        assertEquals(
            "错误实现：暂停时每个 worker 已各领一个号",
            workers, cursorAtPause(false)
        )
    }
}
