package com.audiobook.audiobookplayer

import android.app.Application
import android.util.Log
import com.audiobook.audiobookplayer.model.ServerTaskSnapshot
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 从 `/api/tts/tasks` 响应里取**全局排队规模**。
 *
 * @return 排队任务数；**字段缺失返回 `null`**（= 这次没拿到），而不是 0。
 *   两者必须区分：老服务端不带这个字段，兜底成 0 会把已知的排队规模抹成
 *   「无人排队」，书架上那句「队列约 N 个任务」随之消失，看着像队伍突然清空。
 *   调用方只在非 null 时更新缓存，见 [TaskSyncManager.sync]。
 *
 * 数值统一走 `as? Number`：Gson 把 JSON 数字反序列化到 `Map<String, Any>` 时
 * 一律变成 `Double`，直接 `as? Int` 会永远拿到 null（"同步成功但队列数恒为 0"）。
 * 负数（服务端统计口径出错时）按 0 处理 —— 界面不该出现「队列约 -1 个任务」。
 */
internal fun parseQueuedCount(body: Map<String, Any?>): Int? =
    (body["queuedCount"] as? Number)?.toInt()?.coerceAtLeast(0)

/**
 * 服务端任务历史 → 本地 `tasks` 表的单向同步。
 *
 * ## 为什么必须有它
 * 合成任务的行只在本机 `tasks` 表里创建（提交时 `upsertLocalTask` 写、轮询回写进度）。
 * 换机、重装、清应用数据之后这张表是空的，而服务端任务、已扣积分、已合成的音频都在。
 * 结果是：用户付过钱的书在 App 里彻底消失，只能等客诉；Web 端反而看得到，口径不一致。
 *
 * ## 设计取舍
 * - **只在启动 / 登录成功时各跑一次**，不放进 `loadTasks`：后者是高频刷新路径，
 *   每次进度变化都发网络请求既费流量又会被限流。
 * - **只增不删**：服务端没有、本地有的任务一律保留（可能是本机合成任务，也可能是
 *   用户在另一台设备上删了但这里还留着的记录）。删本地数据必须有用户明确操作。
 * - **失败静默降级**：拿不到列表就当没同步过 —— 恢复是"锦上添花"，绝不能因为它
 *   让 App 起不来或书架报错。
 * - **同一时刻只允许一次**（[inFlight]）：启动与登录可能几乎同时触发。
 */
object TaskSyncManager {

    private const val TAG = "TaskSync"

    /** 与 `PageUtil.MAX_PAGE_SIZE` 对齐（服务端会把 size 钳到 100）。 */
    private const val PAGE_SIZE = 100

    /**
     * 单次同步最多翻几页 = 500 条，与服务端 `MAX_TASKS_BULK_LIMIT` 一致。
     * 再老的记录对"恢复已付费音频"没有意义，翻下去只会拖慢启动。
     */
    private const val MAX_PAGES = 5

    /** 同步协程与 App 同生命周期：不跟随任何 Activity，用户切页/旋屏都不会中断。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 用 CAS 做"同一时刻只跑一次"的闸门；[AtomicBoolean] 比 synchronized 轻，且不需要锁对象。 */
    private val inFlight = AtomicBoolean(false)

    /**
     * 服务端当前**排队规模**（`/api/tts/tasks` 响应里的 `queuedCount`）。
     *
     * 这是全局排队任务数，不是"我排第几"—— 服务端 `RedisTaskService.getSystemStats()`
     * 统计的是所有账号在 `queued` 状态的任务。语义与 Web 端 TaskList 一致
     * （「队列约 N 个任务」），用来解释"为什么我的任务一直没开始"，
     * 避免用户以为卡死了。
     *
     * 只在本进程内缓存、不落库：它是随时会变的瞬时值，写入 SQLite 反而要处理失效。
     * 拿不到（未登录 / 请求失败 / 老服务端没这个字段）时保持上一次的值，
     * 为 0 表示"暂无排队"，此时界面不显示队列文案。
     */
    @Volatile
    var queuedCount: Int = 0
        private set

    /**
     * 后台触发一次同步（不阻塞调用方，不需要 await 结果）。
     *
     * 未登录时直接返回：游客没有服务端任务，发请求只会拿到 401。
     */
    fun syncAsync(context: Application) {
        if (!AuthManager.isAuthenticated) return
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                sync(context)
            } catch (e: Exception) {
                // 兜底：sync 内部已逐层 catch，这里防的是意料之外的运行时异常
                //（例如 Retrofit 初始化失败、协程被取消），不能让异常逃到默认处理器。
                Log.w(TAG, "同步服务端任务异常: ${e.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * 同步一次并返回新增/更新的行数（0 = 无变化或未登录）。
     *
     * 分页拉取而不是只取第一页：服务端单次上限 500 条且带 `truncated` 标记，
     * 一页 100 条翻到够为止，避免"任务多的老用户只恢复出最新 100 条"。
     */
    suspend fun sync(context: Application): Int = withContext(Dispatchers.IO) {
        if (!AuthManager.isAuthenticated) return@withContext 0

        val snapshots = mutableListOf<ServerTaskSnapshot>()
        var page = 1
        // queuedCount 是**全局**值，每页都一样，只解析首页即可。
        // 用 null 表示"这次没拿到"，与"拿到了 0"区分开 —— 前者不能覆盖旧值。
        var latestQueuedCount: Int? = null
        while (page <= MAX_PAGES) {
            val response = try {
                RetrofitClient.apiService.getUserTasks(page = page, size = PAGE_SIZE)
            } catch (e: Exception) {
                Log.w(TAG, "拉取服务端任务失败（第 $page 页）: ${e.message}")
                break
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "拉取服务端任务失败（第 $page 页）: HTTP ${response.code()}")
                break
            }
            val body = response.body() ?: break
            if (body["success"] as? Boolean != true) {
                Log.w(TAG, "拉取服务端任务被拒绝: ${body["message"]}")
                break
            }
            // 排队规模只在首页取；同样要防 Gson 把 JSON 数字解析成 Double。
            if (page == 1) {
                latestQueuedCount = parseQueuedCount(body)
            }
            val rawTasks = body["tasks"] as? List<*> ?: emptyList<Any>()
            rawTasks.forEach { item ->
                parse(item as? Map<*, *>)?.let { snapshots.add(it) }
            }
            // 不足一页说明已经是最后一页；正好整除时多翻一页也会立刻拿到空列表，
            // 代价是一次请求，换来"不会漏条"。
            if (rawTasks.size < PAGE_SIZE) break
            page++
        }

        val queueChanged = latestQueuedCount != null && latestQueuedCount != queuedCount
        if (latestQueuedCount != null) queuedCount = latestQueuedCount

        if (snapshots.isEmpty()) {
            // 服务端一条任务都没有（或全被解析丢弃），但排队规模可能变了：
            // 队伍是全局的，我这边可能一条任务都没有、别人还在排队。
            // 不通知的话书架上「排队中」行的「队列约 N 个」会停在旧值。
            if (queueChanged) TaskRepository(context).notifyExternalTasksChange()
            return@withContext 0
        }
        Log.d(TAG, "拉取到 ${snapshots.size} 条服务端任务，开始合并到本地")
        val changed = TaskRepository(context).upsertServerTasks(snapshots)
        // upsertServerTasks 只在**任务行**有变化时才通知书架；排队规模不在 tasks 表里，
        // 它单独变了就得单独通知一次，否则界面上的队列数字不会更新。
        if (queueChanged && changed == 0) TaskRepository(context).notifyExternalTasksChange()
        changed
    }

    /**
     * 把一条服务端 DTO 解析成 [ServerTaskSnapshot]。
     *
     * Gson 反序列化到 `Map<String, Any>` 时，JSON 数字一律变成 `Double`，
     * 所以数值字段必须走 `as? Number` 再转 —— 直接 `as? Long` 会永远拿到 null，
     * 表现是"同步成功但一条都没恢复"。
     */
    private fun parse(raw: Map<*, *>?): ServerTaskSnapshot? {
        if (raw == null) return null
        val serverTaskId = when (val id = raw["taskId"]) {
            is Number -> id.toLong()
            is String -> id.toLongOrNull()
            else -> null
        } ?: return null
        val status = raw["status"] as? String ?: return null
        return ServerTaskSnapshot(
            serverTaskId = serverTaskId,
            // original_book_name 对早期任务是空的；给个占位名保证 NOT NULL 列可写，
            // 也比在书架上显示一片空白好。
            bookName = (raw["originalBookName"] as? String)?.takeIf { it.isNotBlank() } ?: "未命名书籍",
            status = status,
            totalSegments = (raw["totalSegments"] as? Number)?.toInt() ?: 0,
            completedSegments = (raw["completedSegments"] as? Number)?.toInt() ?: 0,
            errorMessage = raw["errorMessage"] as? String,
            createdAt = normalizeCreatedAt(raw["createdAt"])
        )
    }

    /**
     * 把服务端时间归一化成 SQLite `CURRENT_TIMESTAMP` 的格式 `yyyy-MM-dd HH:mm:ss`。
     *
     * 服务端 `LocalDateTime` 默认序列化成 `2026-10-30T09:48:12.345`（带 T 与小数秒），
     * 本地行是 `2026-10-30 09:48:12`（带空格）。两者混在同一列里、
     * 用 `ORDER BY created_at DESC` 排序时，同一日期里 `T`(0x54) > `空格`(0x20)，
     * 恢复出来的任务会被排到当天所有本机任务之前 —— 书架顺序凭空错乱。
     * 截到秒并统一空格，两种来源才可比。
     *
     * 若服务端把日期写成数组（`WRITE_DATES_AS_TIMESTAMPS` 风格的
     * `[2026,10,30,9,48,12]`），这里也一并拼装，避免整套恢复因格式变化而静默失效。
     */
    private fun normalizeCreatedAt(raw: Any?): String? {
        val text = when (raw) {
            null -> return null
            is String -> raw
            is List<*> -> {
                val nums = raw.mapNotNull { (it as? Number)?.toInt() }
                if (nums.size < 3) return null
                val y = nums[0]
                val mo = nums.getOrElse(1) { 1 }
                val d = nums.getOrElse(2) { 1 }
                val h = nums.getOrElse(3) { 0 }
                val mi = nums.getOrElse(4) { 0 }
                val s = nums.getOrElse(5) { 0 }
                return "%04d-%02d-%02d %02d:%02d:%02d".format(y, mo, d, h, mi, s)
            }
            else -> return null
        }
        val cleaned = text.replace('T', ' ')
        return if (cleaned.length >= 19) cleaned.substring(0, 19) else cleaned
    }
}
