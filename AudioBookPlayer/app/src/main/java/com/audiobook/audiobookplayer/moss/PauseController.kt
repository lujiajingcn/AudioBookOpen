package com.audiobook.audiobookplayer.moss

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 本机合成的「暂停 / 恢复」闸门。
 *
 * ## 为什么暂停只能发生在段边界
 * 一次推理就是 ONNX Runtime 的一次 `session.run()`，跑在 native 里且不可中断
 * （这也是中止功能同样只能在段边界生效的原因）。所以：
 * - 点「暂停」后，**当前正在跑的那一段会先跑完**，之后的段才不再开始；
 * - 按钮文案要如实写「等当前段结束」，不能让人以为点了就立刻停。
 * 一段最长约 30 秒音频（`max_new_frames=375`），按实时率 0.5 算大约十几秒，
 * 所以这个等待是可以接受的。
 *
 * ## 为什么用 lock/condition 而不是一个布尔标志 + 忙等
 * 最直觉的写法是 `while (paused.get()) {}` 空转等 —— **这是错的**：
 * 本机合成的 worker 数等于引擎数，空转会把 CPU 占满，而暂停的意义正是
 * 让机器停下来（省电、让用户先干别的）。这里用 Condition 让 worker 真正挂起，
 * 恢复时被唤醒，期间不消耗 CPU。
 *
 * ## 与「中止」的关系
 * 暂停与中止必须能同时表达：用户在暂停状态下点中止，worker 必须能退出，
 * 而不是永远卡在 awaitResume 里。因此 [awaitResume] 的等待条件是
 * 「已恢复 **或** 已中止」，两者任一成立就放行，由调用方再判断该怎么走。
 */
class PauseController {

    private val lock = ReentrantLock()
    private val resumeSignal = lock.newCondition()

    private val pausedFlag = AtomicBoolean(false)

    /** 是否处于暂停状态（供 UI 与通知判断显示「暂停」还是「继续」） */
    val isPaused: Boolean get() = pausedFlag.get()

    /** 暂停。已经暂停时重复调用无副作用。 */
    fun pause() {
        if (pausedFlag.compareAndSet(false, true)) {
            lock.withLock { /* 拿到锁即可，保证与 awaitResume 的判定不交错 */ }
        }
    }

    /** 恢复。会唤醒所有正在等待的 worker。 */
    fun resume() {
        if (pausedFlag.compareAndSet(true, false)) {
            lock.withLock { resumeSignal.signalAll() }
        }
    }

    /** 中止时调用：确保卡在暂停里的 worker 能被放出来，否则收尾会永远等下去。 */
    fun releaseAll() {
        pausedFlag.set(false)
        lock.withLock { resumeSignal.signalAll() }
    }

    /**
     * 在段边界调用。若处于暂停状态则挂起当前线程，直到恢复或中止。
     *
     * @param isCancelled 中止判定，用来避免"暂停中点中止"时永久挂起
     * @return true 表示被放行且**未**中止（可以继续合成下一段）
     */
    fun awaitResume(isCancelled: () -> Boolean): Boolean {
        if (!pausedFlag.get()) return !isCancelled()
        lock.withLock {
            // 双重检查：可能在取锁期间已经被恢复/中止
            while (pausedFlag.get() && !isCancelled()) {
                // await 会在 signal 或**虚假唤醒**时返回，因此必须放在循环里重新判定。
                // 只 if 一次的实现会在虚假唤醒时提前放行。
                resumeSignal.await()
            }
        }
        return !isCancelled()
    }
}
