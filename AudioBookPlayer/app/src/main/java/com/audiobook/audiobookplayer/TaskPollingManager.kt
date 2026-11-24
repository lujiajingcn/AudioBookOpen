package com.audiobook.audiobookplayer

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.util.Log
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级任务轮询管理器。
 *
 * 约束：BookViewModel 实例按 Activity 作用域创建——MainActivity 的各 Fragment 共享一个，
 * 而 BookContentActivity / BookListActivity 各自又创建一个。每个实例各起一个
 * 2 秒周期的 ScheduledExecutor 轮询服务端时，阅读页与主页同存会让同一批任务被重复轮询，
 * 网络请求与 DB 刷新成倍放大。
 *
 * 方案：进程内单例持有唯一轮询线程，监听者按引用计数 attach/detach：
 * 第一个监听者接入时启动，最后一个移除时关闭。每轮把状态变化写入 DB 后
 * 统一通知所有监听者各自刷新 LiveData——无论存在多少个页面，服务端每轮只被请求一次。
 *
 * 附带优化：仅当状态/进度字段真正变化（UPDATE 的 WHERE 带差异条件）才通知刷新，
 * 避免"每次轮询响应都 loadTasks()"造成的无谓 DB 重查与列表闪动。
 */
object TaskPollingManager {

    private const val TAG = "TaskPollingManager"
    private const val POLL_INTERVAL_SECONDS = 2L
    // 「无进展」超时上限（wall-clock）：服务端 completed 数超过该时长完全不变才判定卡死。
    // 按服务端返回值做快照比较，进度持续推进的长任务（>1h 仍在合成的大书）不会被误杀；
    // 按轮询次数计（1800 次 ≈1h@2s）会与指数退避冲突，把上限漂移到数十小时
    private const val MAX_TASK_POLL_WINDOW_MS = 60 * 60 * 1000L // 1h 无进展
    // 绝对上限兜底：即使任务持续推进，超过该时长也停止轮询，避免无限轮询
    private const val MAX_TASK_ABSOLUTE_MS = 24 * 60 * 60 * 1000L // 24h
    // server_task_id 为空任务的降级窗口：上传成功前进程被杀/写库竞态会留下
    // 永久为 null 的任务，超过该时长标记 failed 并提示重新提交（正常上传远不需要这么久）
    private const val MAX_NULL_SERVER_ID_MS = 30 * 60 * 1000L // 30min
    // 指数退避：仅请求失败时增长、成功即复位；基础 2s 按 2^n 增长，封顶 60s
    // （避免无效高频轮询打爆服务端）
    private const val POLL_BACKOFF_BASE_MS = 2000L
    private const val POLL_BACKOFF_MAX_MS = 60000L

    interface Listener {
        /** 正在下载中的本地任务 ID 集合：这些任务跳过服务端轮询（UI 正在本地刷新进度）。可能在后台线程被调用 */
        fun getDownloadingTaskIds(): Set<Long>

        /** 本轮有任务状态/进度变化（DB 已写入），监听者应刷新自己的任务列表 */
        fun onTaskStatusChanged()
    }

    // 监听者列表在 attach/detach（主线程）增删，在 pollOnce（IO 线程）遍历。
    // mutableListOf + .toList() 快照虽能避免 CME，但无锁读可能看到撕裂的列表状态；
    // 故用 CopyOnWriteArrayList，增删与快照迭代均线程安全
    private val listeners = CopyOnWriteArrayList<Listener>()

    // 切后台后不再每 2s 打服务端（省电/省流量）。用 ActivityLifecycleCallbacks
    // 跟踪前后台：任一 Activity 处于 resumed 即视为前台，全部 paused 即后台。
    private val appInForeground = AtomicBoolean(true)
    private var lifecycleCallbacksRegistered = false
    private var resumedActivities = 0
    private val foregroundCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivities++
            appInForeground.set(true)
        }

        override fun onActivityPaused(activity: Activity) {
            resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
            if (resumedActivities == 0) appInForeground.set(false)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    private var executor: ScheduledExecutorService? = null
    private var appContext: Context? = null
    /**
     * 任务库访问入口（tasks 表的 SQL 只在 TaskRepository 里）。
     * 在 [attach] 时随 appContext 一起初始化，非空保证与 appContext 一致。
     */
    private var taskRepository: TaskRepository? = null

    // 上一轮尚未完成则跳过本轮——防止慢网络下旧结果覆盖新结果
    private val pollingRunning = AtomicBoolean(false)

    // 协程作用域：并发轮询各任务，避免 runBlocking 串行阻塞
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 每个任务连续失败的轮询次数（指数退避档位，请求成功即清零）
    private val taskAttempts = mutableMapOf<Long, Int>()
    private val taskAttemptsLock = Any()
    // 每个任务的下一次允许轮询时间（指数退避用）
    private val taskNextPollAt = mutableMapOf<Long, Long>()
    private val taskBackoffLock = Any()
    // 每个任务的进展快照：(首次采样时间, 当时的服务端 completed 数)——无进展超时判定用
    private val taskFirstSample = mutableMapOf<Long, Pair<Long, Int>>()
    // 每个任务首次参与轮询的时间（绝对上限兜底，进度变化也不重置）
    private val taskFirstSeenAt = mutableMapOf<Long, Long>()
    // server_task_id 为空的任务首次被跳过的时间（降级判定）
    private val taskNullServerSince = mutableMapOf<Long, Long>()

    /** 接入轮询（ViewModel init 时调用）。重复接入同一监听者不会重复计数 */
    @Synchronized
    fun attach(context: Context, listener: Listener) {
        if (appContext == null) appContext = context.applicationContext
        if (appContext is Application && !lifecycleCallbacksRegistered) {
            (appContext as Application).registerActivityLifecycleCallbacks(foregroundCallbacks)
            lifecycleCallbacksRegistered = true
        }
        if (taskRepository == null) {
            // applicationContext 就是 Application 实例（TaskRepository 要求 Application）
            taskRepository = TaskRepository(context.applicationContext as Application)
        }
        if (listeners.contains(listener)) return
        listeners.add(listener)
        if (executor == null) {
            val exec = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "TaskPollingThread").apply { isDaemon = true }
            }
            executor = exec
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            exec.scheduleWithFixedDelay({ scope.launch { pollOnce() } }, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
            Log.d(TAG, "轮询启动（listeners=${listeners.size}）")
        } else {
            Log.d(TAG, "监听者接入（listeners=${listeners.size}），复用现有轮询线程")
        }
    }

    /** 断开轮询（ViewModel onCleared 时调用）；最后一个监听者离开时停止线程 */
    @Synchronized
    fun detach(listener: Listener) {
        if (!listeners.remove(listener)) return
        if (listeners.isEmpty()) {
            executor?.shutdownNow()
            executor = null
            pollingRunning.set(false)
            scope.cancel()
            if (lifecycleCallbacksRegistered) {
                (appContext as? Application)?.unregisterActivityLifecycleCallbacks(foregroundCallbacks)
                lifecycleCallbacksRegistered = false
            }
            Log.d(TAG, "轮询停止（无监听者）")
        } else {
            Log.d(TAG, "监听者离开（listeners=${listeners.size}）")
        }
    }

    private suspend fun pollOnce() {
        if (!pollingRunning.compareAndSet(false, true)) return
        try {
            // 后台不轮询，避免无谓的服务端请求（返回前台后下一轮自动恢复）
            if (!appInForeground.get()) return
            val context = appContext ?: return
            AuthManager.token ?: return // 未登录无法轮询服务端

            val activeTasks = AppRepository.getInstance(context).getActivePollingTasks()
            if (activeTasks.isEmpty()) {
                synchronized(taskAttemptsLock) {
                    taskAttempts.clear()
                    taskFirstSample.clear()
                    taskFirstSeenAt.clear()
                }
                synchronized(taskBackoffLock) {
                    taskNextPollAt.clear()
                    taskNullServerSince.clear()
                }
                return
            }

            // 汇总所有监听者"正在下载"的任务并集，跳过轮询
            val downloading = mutableSetOf<Long>()
            listeners.toList().forEach { l ->
                try {
                    downloading.addAll(l.getDownloadingTaskIds())
                } catch (e: Exception) {
                    Log.e(TAG, "getDownloadingTaskIds failed: ${e.message}")
                }
            }
            val toPoll = activeTasks.filterNot { it.first in downloading }
            if (toPoll.isEmpty()) {
                Log.d(TAG, "所有活跃任务正在下载，跳过本轮")
                return
            }

            // 并发轮询每个任务，避免 runBlocking 串行阻塞拖慢整体
            val results = toPoll.map { (localId, serverTaskId) ->
                scope.async { pollAndUpdate(localId, serverTaskId) }
            }.awaitAll()
            val changed = results.any { it }
            if (changed) {
                listeners.toList().forEach { l ->
                    try {
                        l.onTaskStatusChanged()
                    } catch (e: Exception) {
                        Log.e(TAG, "onTaskStatusChanged failed: ${e.message}")
                    }
                }
            }
        } catch (e: CancellationException) {
            // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "轮询失败: ${e.message}")
        } finally {
            pollingRunning.set(false)
        }
    }

    /** 轮询单个任务状态并按差异写 DB；返回是否发生了变化 */
    @Suppress("UNCHECKED_CAST")
    private suspend fun pollAndUpdate(
        localTaskId: Long,
        serverTaskId: Long?
    ): Boolean {
        val now = System.currentTimeMillis()

        // 优先使用服务端真实 taskId 轮询；本地毫秒 ID 不是合法服务端 ID。
        // 正常流程上传成功后 updateServerTaskId 会立即回填；进程被杀/写库竞态会留下
        // 永久为 null 的任务。这里用 wall-clock 窗口降级：超时后标记 failed 提示重新提交，
        // 避免任务永远停在 processing 且每 2 秒刷一条日志
        if (serverTaskId == null) {
            val since = synchronized(taskBackoffLock) {
                val s = taskNullServerSince[localTaskId]
                if (s != null) s else now.also { taskNullServerSince[localTaskId] = it }
            }
            if (now - since > MAX_NULL_SERVER_ID_MS) {
                Log.w(TAG, "任务 $localTaskId 长时间无服务端ID，标记失败")
                // 上传可能在服务端已成功建任务并开始扣费（响应丢失/进程被杀），
                // 提示用户先确认服务端任务，避免盲目重提造成重复任务、重复扣费
                return markTaskFailed(
                    localTaskId,
                    "任务异常：未能获取服务端任务ID（可能上传中断）。请先到任务列表确认服务端任务状态，避免重复提交"
                )
            }
            // 仅首次登记时打一条日志，避免每个任务每 2 秒刷屏直到降级窗口结束
            if (since == now) {
                Log.d(TAG, "任务 $localTaskId 暂无服务端ID，跳过轮询")
            }
            return false
        }
        // 拿到服务端 ID 后清理降级计时
        synchronized(taskBackoffLock) { taskNullServerSince.remove(localTaskId) }

        // 指数退避：仅请求失败时增长、成功即复位（见下方），避免无效高频请求打爆服务端
        val nextAt = synchronized(taskBackoffLock) { taskNextPollAt[localTaskId] ?: 0L }
        if (now < nextAt) return false

        // 绝对上限兜底：请求发出【之前】登记首次参与时间——即使请求持续失败
        // （服务端 500/网络异常，响应永远解析不成功）也能在 24h 后停止轮询，
        // 不会无限空转。按成功轮询次数计会让失败路径绕过所有上限
        val absoluteOver = synchronized(taskAttemptsLock) {
            val firstSeen = taskFirstSeenAt[localTaskId]
                ?: now.also { taskFirstSeenAt[localTaskId] = it }
            now - firstSeen > MAX_TASK_ABSOLUTE_MS
        }
        if (absoluteOver) {
            Log.w(TAG, "任务 $localTaskId 超过 24h 未完成，标记失败")
            return markTaskFailed(localTaskId, "任务超过 24 小时未完成，已停止轮询")
        }

        return try {
            val response = RetrofitClient.apiService.getTaskStatus(serverTaskId)
            if (!response.isSuccessful) {
                growBackoff(localTaskId, now)
                // 404 = 服务端任务已不存在（如在 Web 端删除）：本地永远轮不到进展，
                // 直接落终态并提示，而不是让任务列表永远显示 processing
                if (response.code() == 404) {
                    Log.w(TAG, "服务端任务不存在(404)，标记失败: $serverTaskId")
                    return markTaskFailed(localTaskId, "任务已不存在（可能已在其他端删除）")
                }
                Log.w(TAG, "轮询任务 $serverTaskId 失败: HTTP ${response.code()}")
                return false
            }
            val task = response.body()?.get("task") as? Map<String, Any>
            if (task == null) {
                growBackoff(localTaskId, now)
                Log.w(TAG, "轮询任务 $serverTaskId 响应无 task 字段")
                return false
            }
            val status = task["status"] as? String ?: "processing"
            val completed = (task["completedSegments"] as? Number)?.toInt() ?: 0
            val total = (task["totalSegments"] as? Number)?.toInt() ?: 0

            // 「无进展」超时判定：快照比较服务端返回的 completed 数（不依赖本管理器
            // 是否写库成功——TtsFragment 抢先写入相同状态时 rows=0 也能正确感知进展）。
            // 进度有变化即重置窗口，长书（>1h 但持续推进）不会被误判失败
            // 「无进展」超时判定：仅对 processing 状态生效。快照比较服务端返回的
            // completed 数（不依赖本管理器是否写库成功——TtsFragment 抢先写入相同
            // 状态时 rows=0 也能正确感知进展），进度有变化即重置窗口。
            // queued/pending 的任务排队可能超过 1h（批量合成、队列积压），completed
            // 恒 0 不是「卡死」——误判会诱导用户重复提交、重复扣费，它们只受 24h
            // 绝对上限约束（请求发出前已登记，见上）
            if (status == "processing") {
                val noProgressOver = synchronized(taskAttemptsLock) {
                    val sample = taskFirstSample[localTaskId]
                    if (sample == null) {
                        taskFirstSample[localTaskId] = now to completed
                        false
                    } else if (sample.second != completed) {
                        taskFirstSample[localTaskId] = now to completed
                        false
                    } else {
                        now - sample.first > MAX_TASK_POLL_WINDOW_MS
                    }
                }
                if (noProgressOver) {
                    Log.w(TAG, "任务 $localTaskId 超过 1h 无进展，标记失败")
                    return markTaskFailed(localTaskId, "任务长时间无进展，已停止轮询，请到任务列表确认后重试")
                }
            } else {
                // 离开 processing（queued/pending 或未知状态）时清除快照，
                // 回到 processing 后重新基线采样
                synchronized(taskAttemptsLock) { taskFirstSample.remove(localTaskId) }
            }

            // 仅字段真正变化时 update 才命中（WHERE 带差异条件），避免无变化的轮询触发刷新
            val repo = taskRepository ?: return false
            val rows = repo.updateStatusIfChanged(localTaskId, status, completed, total)
            // 请求成功即复位退避（任务仍在进行时保持 2s 快节奏刷新）。
            // 不能只在 rows>0 时复位：TtsFragment 自带的轮询会先把相同状态写入 DB，
            // 全局轮询每轮 rows=0，退避会一路涨到 60s，任务列表进度刷新最长滞后 60s
            synchronized(taskAttemptsLock) { taskAttempts.remove(localTaskId) }
            synchronized(taskBackoffLock) { taskNextPollAt.remove(localTaskId) }
            if (rows > 0) {
                Log.d(TAG, "任务 $localTaskId 更新: status=$status, progress=$completed/$total")
            }
            // 终态到达：清理该任务的全部轮询状态（下一轮起它不再出现在活跃查询里），
            // 避免长时间运行进程中 Map 随历史任务数单调增长；同时发系统通知
            if (status == "completed" || status == "partial" || status == "failed") {
                notifyTerminal(localTaskId, status)
                forgetTask(localTaskId)
            }
            rows > 0
        } catch (e: CancellationException) {
            // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
            throw e
        } catch (e: Exception) {
            growBackoff(localTaskId, now)
            Log.e(TAG, "轮询任务 $localTaskId 出错: ${e.message}")
            false
        }
    }

    /** 清理单个任务的全部轮询状态（任务达终态 / 本地任务行被删除时调用） */
    fun forgetTask(localTaskId: Long) {
        synchronized(taskAttemptsLock) {
            taskAttempts.remove(localTaskId)
            taskFirstSample.remove(localTaskId)
            taskFirstSeenAt.remove(localTaskId)
        }
        synchronized(taskBackoffLock) {
            taskNextPollAt.remove(localTaskId)
            taskNullServerSince.remove(localTaskId)
        }
        // 顺带清掉通知去重表这一项：它只在这次调用里被遗忘，忘了就永久占一条
        // (taskId -> 终态)。用户反复合成时该 Map 单调增长，是长生命周期进程里的稳定泄漏。
        TaskNotificationHelper.forgetTask(localTaskId)
    }

    /** 任务进入终态时发系统通知（去重由 TaskNotificationHelper 保证，可被多处安全调用） */
    private fun notifyTerminal(localTaskId: Long, status: String) {
        try {
            val repo = taskRepository ?: return
            val bookName = repo.findBookName(localTaskId) ?: return
            val ctx = appContext ?: return
            TaskNotificationHelper.notifyTerminal(ctx, localTaskId, bookName, status)
        } catch (e: Exception) {
            Log.w(TAG, "任务终态通知失败: ${e.message}")
        }
    }

    /** 请求失败时增长该任务的退避间隔（连续失败次数越多，间隔越长，封顶 60s） */
    private fun growBackoff(localTaskId: Long, now: Long) {
        val failures = synchronized(taskAttemptsLock) {
            val n = (taskAttempts[localTaskId] ?: 0) + 1
            taskAttempts[localTaskId] = n
            n
        }
        val delayMs = (POLL_BACKOFF_BASE_MS shl (failures - 1).coerceAtMost(30))
            .toLong().coerceAtMost(POLL_BACKOFF_MAX_MS)
        synchronized(taskBackoffLock) { taskNextPollAt[localTaskId] = now + delayMs }
    }

    /** 把任务标记为 failed 并写入错误信息（仅当任务仍处于活跃状态时生效）；返回是否有变化 */
    private fun markTaskFailed(localTaskId: Long, message: String): Boolean {
        return try {
            val repo = taskRepository ?: return false
            val rows = repo.markFailedIfActive(localTaskId, message)
            if (rows > 0) {
                // 终态已写入：清理该任务的全部轮询状态，并通知用户
                notifyTerminal(localTaskId, "failed")
                forgetTask(localTaskId)
            }
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "标记任务失败出错: ${e.message}")
            false
        }
    }
}
