package com.audiobook.audiobookplayer

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.audiobook.audiobookplayer.model.StoreBook
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 书城「合成音频」的**后台逐段下载器**（进程级单例，不绑任何界面生命周期）。
 *
 * ## 它解决什么
 * 旧实现是「一次拉整包 zip → 解压入库」（见 git 历史里的
 * `BookStoreViewModel.downloadAudio`），三个问题：
 *  1. **必须整本下完才能听**：zip 是 chunked 大流，首段音频要等整包落盘 + 解压完才入库；
 *  2. **跑在书城页的 ViewModel 作用域里**：用户切 Tab / 进阅读页 / 进程被杀即中断，
 *     而且已下的 zip 是纯垃圾（应用内无入口），下次只能从 0 重来；
 *  3. **进度是字节数**，长书几十分钟没有「还差多少段」这种用户真正关心的信息。
 *
 * 服务端早已提供逐段接口（`StoreReadingController`）：`/segments` 拿段列表、
 * `/segments/{id}/audio` 拉单段 mp3。于是这里改成**按段号升序逐段下载、下完一段立刻入库**：
 *  - 段序 = 电子书文本顺序（服务端的 `segment_number` 就是切段顺序，天然与原文对齐）；
 *  - 下到第 N 段时前 N 段已经能播（阅读页的播放队列直读 `audio_segments`）；
 *  - 已下过的段直接跳过 ⇒ **天然断点续传**，中断后重进只补缺的段。
 *
 * ## 后台到什么程度
 * Application 级 scope：离开书城页、锁屏、进阅读页收听都不受影响；收听期间
 * [PlaybackService] 是前台服务，进程被系统保活的优先级很高。**进程被杀的极端情况下
 * 下载会暂停**（没有独立前台服务），但任务已写进 `AppPrefs`，下次启动进书城自动续传，
 * 已下的段一个都不会重下。
 *
 * ## 并发口径
 * 同一时刻只跑一个任务，其余排队（多个任务并发会把带宽切成几份，每本都变慢，
 * 而用户真正在听的那本并没有优先权）。取消 = 停止后续下载，**已下的段保留**。
 */
object StoreAudioDownloadManager {

    private const val TAG = "StoreAudioDl"

    /** 单段音频上限：正常单段（约 30 字语音）只有几十到几百 KB，触到它说明服务端回了异常内容 */
    private const val MAX_SEGMENT_BYTES = 50L * 1024 * 1024

    /** 单段失败后的重试次数（仅网络类/5xx 重试，4xx 与超时不重试，与项目既有失败码语义一致） */
    private const val SEGMENT_RETRY = 1

    /** 重试前的等待（给瞬时抖动一点恢复时间，避免立刻再撞一次） */
    private const val RETRY_DELAY_MS = 1_500L

    /** 服务端片段状态：只有 completed 的段才有音频（未完成段请求单段接口必然 4xx） */
    private const val STATUS_COMPLETED = "completed"

    /** 任务状态。QUEUED 之外都代表「当前活动任务」的状态 */
    enum class Status { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

    /**
     * 对 UI 暴露的一份快照。
     *
     * [total] 是**本次要下的段数**（服务端已完成段的个数），[saved] 是其中已在本地的个数
     * （含本次下载前的存量）—— 所以它是「这本书现在能听多少段 / 一共多少段」，
     * 而不是「这一轮下了多少」，用户看到的进度与播放能力一致。
     */
    data class State(
        val bookId: Long,
        val title: String,
        val bookName: String?,
        val total: Int,
        val saved: Int,
        val failed: Int,
        val status: Status,
        val message: String = ""
    ) {
        /** 已入库占本次可下段的百分比（total 未知时为 0） */
        val percent: Int get() = if (total <= 0) 0 else (saved * 100 / total)

        /** 是否会再变（UI 据此决定是留在进度对话框上还是收尾） */
        val isTerminal: Boolean
            get() = status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED
    }

    /**
     * 一次下载任务。
     *
     * [bookName] 是**本地书名**（`audio_segments.book_name` 的合法取值），要等进到本地书架里
     * 查得到才能确定，故可空 —— 由 worker 首步解析（见 [BookStorage.resolveLocalBookName]）。
     */
    data class JobSpec(
        val bookId: Long,
        val title: String,
        val fileName: String,
        val fileExtension: String?,
        val bookName: String? = null
    )

    /** 状态观察者（书城页的进度对话框）。回调一律在主线程。 */
    fun interface Listener {
        fun onState(state: State?)
    }

    /**
     * 「又有一段入库了」的观察者。
     *
     * 播放侧据此把队列接长并自动续播，阅读页据此重建「段号 ↔ 行号」映射。
     * **保证在段落已提交到数据库之后回调**（不是写进缓冲就算），否则观察者去查库会查空。
     */
    fun interface SegmentListener {
        fun onSegmentSaved(bookName: String, segmentNumber: Int)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val segmentListeners = CopyOnWriteArrayList<SegmentListener>()

    /** 待处理队列（含正在跑的那个，始终取队首），bookId → 任务 */
    private val queue = LinkedHashMap<Long, JobSpec>()
    private val queueLock = Any()

    private var workerActive = false

    /** 当前活动任务的状态快照（主线程可读） */
    @Volatile
    private var activeState: State? = null

    /** 最近一次结束的任务状态：离开书城页期间下完的，回来时还能看到结果 */
    @Volatile
    private var lastState: State? = null

    /** 取消标志：置位后 worker 在下一个段边界停下（不打断当前段的读写） */
    @Volatile
    private var cancelRequested = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var storageRef: BookStorage? = null

    // ---------------- 对外 API ----------------

    /**
     * 把一本书的合成音频加入下载队列。
     *
     * 同一个 bookId 重复调用只入队一次（幂等）：用户反复点「下载」不会排出多条任务，
     * 也不会把正在下的那本重新排队。
     */
    fun start(book: StoreBook) {
        enqueue(
            JobSpec(
                bookId = book.id,
                title = book.title,
                fileName = book.fileName,
                fileExtension = book.fileExtension
            ),
            persist = true
        )
    }

    /** 取消某本书的下载（已入库的段保留，之后重新点下载会从中断处继续）。 */
    fun cancel(bookId: Long) {
        val removed = synchronized(queueLock) {
            if (queue.values.firstOrNull()?.bookId == bookId) {
                // 正在跑：交给 worker 在段边界收尾，才能保证当前段写完再停
                cancelRequested = true
                false
            } else {
                queue.remove(bookId) != null
            }
        }
        if (removed) {
            val cancelled = State(
                bookId = bookId, title = "", bookName = null,
                total = 0, saved = 0, failed = 0,
                status = Status.CANCELLED, message = "已取消下载"
            )
            lastState = cancelled
            persist()
            dispatchState(cancelled)
        }
    }

    /** 当前活动任务 / 最近一次结束任务的状态；没有则 null。回调与 UI 都读它。 */
    fun currentState(): State? = activeState ?: lastState

    fun stateOf(bookId: Long): State? =
        (activeState ?: lastState)?.takeIf { it.bookId == bookId }

    /** 这本书（本地书名）是否有下载任务在跑或排队 —— 播放侧据此决定「等一段」还是「结束会话」。 */
    fun isActiveFor(bookName: String): Boolean {
        if (bookName.isEmpty()) return false
        synchronized(queueLock) {
            if (queue.values.any { it.bookName == bookName }) return true
        }
        // 在线按需播放：只要还有「正在/待下载」的段，就算活动 —— 播放到本地队尾时
        // PlaybackService 才会挂起等待新段到达，而不是直接结束会话。
        return onlineHasPending(bookName)
    }

    /** 这本书本次要下的总段数（未知返回 0），播放侧用它判断「还有没有没到的段」 */
    fun totalFor(bookName: String): Int {
        val state = activeState ?: return 0
        return if (state.bookName == bookName) state.total else 0
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        // 立刻补一帧现状：离开书城页再回来时，正在下的任务要能重新看到进度
        val snapshot = currentState()
        mainHandler.post { listener.onState(snapshot) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun addSegmentListener(listener: SegmentListener) {
        segmentListeners.addIfAbsent(listener)
    }

    fun removeSegmentListener(listener: SegmentListener) {
        segmentListeners.remove(listener)
    }

    /**
     * 恢复上次没下完的任务（进程被杀 / 应用被回收后）。
     *
     * 由登录后的主界面调用。未登录时直接返回 —— 请求会 401，任务留在持久化列表里，
     * 等真正登录后再续（不让「没登录」把任务标记成失败）。
     */
    fun resumePending() {
        val ctx = appContext() ?: return
        if (AuthManager.token == null) {
            Log.d(TAG, "未登录，暂不恢复书城音频下载任务")
            return
        }
        val pending = AppPrefs.getStoreAudioJobs(ctx)
        if (pending.isEmpty()) return
        Log.d(TAG, "恢复 ${pending.size} 个未完成的书城音频下载任务")
        pending.forEach { enqueue(it, persist = false) }
    }

    // ---------------- 队列 / worker ----------------

    private fun enqueue(spec: JobSpec, persist: Boolean) {
        val added = synchronized(queueLock) {
            if (queue.containsKey(spec.bookId)) return else queue[spec.bookId] = spec
            true
        }
        if (!added) {
            Log.d(TAG, "任务已在队列中: bookId=${spec.bookId}")
            return
        }
        if (persist) {
            persist()
            // 新入队：先给 UI 一帧「排队中」，点了按钮立刻有反馈
            val queued = State(
                bookId = spec.bookId, title = spec.title, bookName = spec.bookName,
                total = 0, saved = 0, failed = 0,
                status = Status.QUEUED, message = "已加入下载队列"
            )
            if (activeState == null) lastState = queued
            dispatchState(queued)
        }
        ensureWorker()
    }

    private fun ensureWorker() {
        synchronized(queueLock) {
            if (workerActive) return
            if (queue.isEmpty()) return
            workerActive = true
        }
        scope.launch { workerLoop() }
    }

    /**
     * 串行消费队列。
     *
     * 只有 [Outcome.PAUSED]（仅 WiFi 开关拦下）会把任务留在队首并停掉 worker，
     * 等网络回调重新拉起；其余结局都出队。
     */
    private suspend fun workerLoop() {
        while (true) {
            val spec = synchronized(queueLock) {
                val first = queue.values.firstOrNull()
                if (first == null) {
                    workerActive = false
                    activeState = null
                    return
                }
                cancelRequested = false
                first
            }
            when (val outcome = runJob(spec)) {
                Outcome.PAUSED -> {
                    synchronized(queueLock) { workerActive = false }
                    return
                }
                else -> {
                    synchronized(queueLock) { queue.remove(spec.bookId) }
                    persist()
                    if (outcome == Outcome.DONE || outcome == Outcome.FAILED) {
                        // 收尾 flush：最后一段不能留在缓冲里（否则「提示已完成但最后一段没声音」）
                        flushAndCount()
                    }
                }
            }
        }
    }

    private enum class Outcome { DONE, FAILED, CANCELLED, PAUSED }

    /**
     * 下完一本书的全部分段。
     *
     * 顺序**严格按 segmentNumber 升序** —— 段号即电子书文本顺序，用户从头听时
     * 需要的段总是最先到，播放体验与「边下边听」的直觉一致。
     */
    private suspend fun runJob(spec: JobSpec): Outcome {
        val ctx = appContext()
        val storage = ctx?.let { storage(it) }
        if (ctx == null || storage == null) {
            publish(spec, 0, 0, 0, Status.FAILED, "应用上下文不可用")
            return Outcome.FAILED
        }
        if (AuthManager.token == null) {
            publish(spec, 0, 0, 0, Status.FAILED, "登录已过期，请重新登录后再下载")
            return Outcome.FAILED
        }

        // ① 本地书名：音频片段必须以它入库，否则书架「有音频」图标与播放队列都认不出这本书
        val bookName = spec.bookName ?: storage.resolveLocalBookName(
            spec.title, spec.fileName, spec.fileExtension
        )
        if (bookName.isNullOrEmpty()) {
            publish(
                spec, 0, 0, 0, Status.FAILED,
                "请先把《${spec.title}》下载到书架，再下载合成音频"
            )
            return Outcome.FAILED
        }
        val job = spec.copy(bookName = bookName)
        synchronized(queueLock) { queue[spec.bookId] = job }
        persist()

        // ② 段列表（服务端已按段号升序返回，这里再排一次是显式保证顺序语义）
        publish(job, 0, 0, 0, Status.RUNNING, "正在获取音频列表…")
        val segments = fetchSegments(job) ?: return Outcome.FAILED
        if (segments.isEmpty()) {
            publish(job, 0, 0, 0, Status.FAILED, "这本书暂无已合成的音频片段")
            return Outcome.FAILED
        }

        // ③ 本地已有的段一次性取回：补下载只拉缺的（断点续传的实质）
        val localNumbers = storage.loadLocalSegmentNumbers(bookName).toMutableSet()
        val total = segments.size
        var saved = segments.count { it.number in localNumbers }
        var failed = 0
        publish(job, total, saved, failed, Status.RUNNING)

        for (segment in segments) {
            if (cancelRequested) {
                flushAndCount()
                publish(job, total, saved, failed, Status.CANCELLED, "已取消下载")
                return Outcome.CANCELLED
            }
            if (segment.number in localNumbers) continue

            if (!wifiAllowed(ctx)) {
                // 仅 WiFi 开关拦下：任务留在队首，等 WiFi 恢复自动续
                flushAndCount()
                publish(
                    job, total, saved, failed, Status.PAUSED,
                    "已开启「仅 WiFi 下载」，连接 WiFi 后自动继续"
                )
                registerNetworkCallback()
                return Outcome.PAUSED
            }

            val bytes = downloadSegment(job.bookId, segment.id)
            if (bytes == null || bytes.isEmpty()) {
                failed++
                publish(job, total, saved, failed, Status.RUNNING)
                continue
            }
            val queued = storage.saveAudioSegmentStreaming(
                bookName, segment.id, segment.number, segment.text, bytes
            )
            if (!queued) {
                failed++
                publish(job, total, saved, failed, Status.RUNNING)
                continue
            }
            // 立刻落盘：播放队列直读数据库，留在缓冲里等于「下好了但播不到」
            val written = flushAndCount()
            if (written <= 0) {
                failed++
                publish(job, total, saved, failed, Status.RUNNING)
                continue
            }
            saved++
            localNumbers.add(segment.number)
            publish(job, total, saved, failed, Status.RUNNING)
            notifySegmentSaved(bookName, segment.number)
        }

        // 收尾：兜底 flush + 取回写入失败计数（一次批量失败会静默丢一整批音频）
        flushAndCount()

        return if (failed == 0 && saved >= total) {
            publish(job, total, saved, 0, Status.COMPLETED, "已全部下载，共 $total 段")
            Outcome.DONE
        } else if (saved > 0) {
            publish(
                job, total, saved, failed, Status.FAILED,
                "已下载 $saved/$total 段，$failed 段失败，可再次下载补齐"
            )
            Outcome.FAILED
        } else {
            publish(job, total, 0, failed, Status.FAILED, "下载失败，请稍后重试")
            Outcome.FAILED
        }
    }

    /**
     * 一个待下载的服务端片段。
     *
     * public：在线按需播放（[ensureOnlineSegment] / [prefetchOnlineAhead]）与
     * [BookViewModel] 建「段号 ↔ 原文行」映射都要读它。
     */
    data class StoreSegment(val id: Long, val number: Int, val text: String)

    /** 把 `/segments` 响应里的 `segments` 数组解析成升序的已完成片段列表；格式异常返回 null。 */
    private fun parseCompletedSegments(raw: List<*>?): List<StoreSegment>? {
        if (raw == null) return null
        return raw.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val map = item as? Map<String, Any> ?: return@mapNotNull null
            // 只取已完成段：未完成的段没有音频，请求它必然 4xx，白费一次往返
            if ((map["status"] as? String) != STATUS_COMPLETED) return@mapNotNull null
            val id = (map["id"] as? Number)?.toLong() ?: return@mapNotNull null
            val number = (map["segmentNumber"] as? Number)?.toInt() ?: return@mapNotNull null
            val text = map["textContent"] as? String ?: "段落 $number"
            StoreSegment(id, number, text)
        }.sortedBy { it.number }
    }

    /**
     * 取本书的片段列表，只保留已完成（有音频）的段。
     *
     * 失败时把原因写进状态再返回 null：`onComplete` 式的回调早已取消，
     * UI 只能通过状态文本知道发生了什么（旧实现有同样的注释口径）。
     */
    private suspend fun fetchSegments(job: JobSpec): List<StoreSegment>? {
        return try {
            val resp = RetrofitClient.heavyApi.getStoreBookSegments(job.bookId)
            if (!resp.isSuccessful) {
                val reason = if (resp.code() == 404) "书籍不存在或已下架" else "HTTP ${resp.code()}"
                publish(job, 0, 0, 0, Status.FAILED, "获取音频列表失败：$reason")
                return null
            }
            val raw = resp.body()?.get("segments") as? List<*>
            val parsed = parseCompletedSegments(raw)
            if (parsed == null) {
                publish(job, 0, 0, 0, Status.FAILED, "音频列表格式异常")
                return null
            }
            parsed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "获取音频列表异常: ${e.message}", e)
            publish(job, 0, 0, 0, Status.FAILED, "获取音频列表失败：${e.toUserMessage()}")
            null
        }
    }

    /**
     * 下载单段音频，网络类错误重试 [SEGMENT_RETRY] 次。
     *
     * **4xx 与超时不重试**（与项目既有失败码语义一致）：4xx 是服务端明确拒绝
     * （段不存在、无音频来源），重试只是重复挨一次拒绝；超时说明该段本身就慢，
     * 再等一轮只会把整本书的下载拖长。
     */
    private suspend fun downloadSegment(bookId: Long, segmentId: Long): ByteArray? {
        var attempt = 0
        while (true) {
            try {
                val resp = RetrofitClient.heavyApi.downloadStoreSegmentAudio(bookId, segmentId)
                if (!resp.isSuccessful) {
                    val retryable = resp.code() >= 500 || resp.code() == 429
                    if (retryable && attempt < SEGMENT_RETRY) {
                        attempt++
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    Log.w(TAG, "片段下载失败: segmentId=$segmentId, HTTP ${resp.code()}")
                    return null
                }
                val body = resp.body() ?: return null
                return readLimited(body.byteStream())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt < SEGMENT_RETRY) {
                    attempt++
                    delay(RETRY_DELAY_MS)
                    continue
                }
                Log.e(TAG, "片段下载异常: segmentId=$segmentId, ${e.message}")
                return null
            }
        }
    }

    /** 分块读取单段音频，超上限立即放弃该段 —— 入库存的是 BLOB，整段物化进堆的峰值就是它的长度。 */
    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        input.use {
            while (true) {
                val read = it.read(buf)
                if (read == -1) break
                total += read
                if (total > MAX_SEGMENT_BYTES) {
                    throw IOException("单段音频超过 ${MAX_SEGMENT_BYTES / 1024 / 1024}MB 上限，放弃该段")
                }
                out.write(buf, 0, read)
            }
        }
        return out.toByteArray()
    }

    /** 落盘并返回成功写入的段数（≤0 视为失败）；顺带把丢弃计数并入失败口径。 */
    private suspend fun flushAndCount(): Int {
        val storage = storageRef ?: return 0
        val written = storage.flushPendingAudioSegments()
        storage.consumeFlushFailures()
        return written
    }

    // ---------------- 在线按需播放（书城书「点开即听」，不整本下载） ----------------

    /**
     * 在线按需下载与整本后台下载的区别：
     *  - **范围有界**：只下「当前段 + 后续若干段」（滑动窗口），不主动下整本；
     *  - **不受「仅 WiFi」约束**：这些是「为了能立刻播出来」的音频，任何网络都下
     *    （整本后台下载仍走 [runJob] 的 [wifiAllowed] 闸门）；
     *  - **无需先入书架**：以书城书名作为 `audio_segments.book_name` 的虚拟书名直接入库，
     *    与整本下载共用同一张表 —— 日后真下到书架（同名）还能复用这些已缓存的段。
     */

    /** 每本书的服务端片段列表缓存（在线模式一次拉取，重复播放不再请求 /segments） */
    private val onlineSegmentsCache = ConcurrentHashMap<Long, List<StoreSegment>>()

    /** 每本书「仍在下载中/待下载」的段号集合；[onlineHasPending] 据此让播放侧挂起等待 */
    private val onlinePending = ConcurrentHashMap<String, MutableSet<Int>>()

    /** 每本书一把锁：串行化对同名书的按需下载，天然去重（并发请求同一段时后者会命中本地已存在） */
    private val onlineMutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(bookName: String): Mutex =
        onlineMutexes.getOrPut(bookName) { Mutex() }

    private fun addPending(bookName: String, number: Int) {
        val set = onlinePending.getOrPut(bookName) { java.util.Collections.synchronizedSet(mutableSetOf()) }
        set.add(number)
    }

    private fun removePending(bookName: String, number: Int) {
        onlinePending[bookName]?.remove(number)
    }

    private fun onlineHasPending(bookName: String): Boolean {
        val set = onlinePending[bookName] ?: return false
        return set.isNotEmpty()
    }

    /**
     * 取本书的服务端片段元数据（带缓存）。供 [BookViewModel] 建「段号 ↔ 原文行」映射，
     * 以及按需下载时把段号解析成 segmentId。失败返回 null。
     */
    suspend fun onlineSegments(bookId: Long): List<StoreSegment>? {
        onlineSegmentsCache[bookId]?.let { return it }
        return try {
            val resp = RetrofitClient.heavyApi.getStoreBookSegments(bookId)
            if (!resp.isSuccessful) {
                Log.w(TAG, "在线获取片段列表失败: bookId=$bookId, HTTP ${resp.code()}")
                return null
            }
            val parsed = parseCompletedSegments(resp.body()?.get("segments") as? List<*>) ?: return null
            onlineSegmentsCache[bookId] = parsed
            parsed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "在线获取片段列表异常: bookId=$bookId, ${e.message}")
            null
        }
    }

    /**
     * 确保某一段音频已在本地（没有就立即下载），下载完成/已存在返回 true。
     *
     * 冷启动播放（本地一段都没缓存）时由 [BookViewModel.playLine] 先 await 它，
     * 保证 PlaybackService 加载队列时目标段已入库 —— 否则空队列会被当成「本书没有音频」直接拆会话。
     */
    suspend fun ensureOnlineSegment(bookId: Long, bookName: String, number: Int): Boolean {
        if (bookName.isEmpty() || number <= 0) return false
        val ctx = appContext() ?: return false
        val storage = storage(ctx) ?: return false
        // 单段音频对游客开放（在线阅读）：服务端 /api/store/book/*/segments/*/audio 已 permitAll。
        if (storage.hasLocalAudioSegment(bookName, number)) return true
        addPending(bookName, number)
        try {
            return downloadOneOnline(bookId, bookName, number, storage)
        } finally {
            removePending(bookName, number)
        }
    }

    /**
     * 预取「从 [fromNumber] 起的 [count] 段」到本地（fire-and-forget，滑动窗口）。
     *
     * 由播放侧在每次段落切换时重新武装，使下载始终领先播放位置若干段：
     * 播放追到本地队尾时下一段多半已到（无缝）；即便没到，[isActiveFor] 为真会让
     * PlaybackService 挂起等待而非结束会话，段一入库即自动接上。
     */
    fun prefetchOnlineAhead(bookId: Long, bookName: String, fromNumber: Int, count: Int) {
        if (bookName.isEmpty() || count <= 0 || fromNumber <= 0) return
        scope.launch {
            val ctx = appContext() ?: return@launch
            val storage = storage(ctx) ?: return@launch
            // 预取同样对游客开放：在线阅读边听边预取后续若干段，接口已 permitAll。
            val segs = onlineSegments(bookId) ?: return@launch
            val targets = segs.asSequence()
                .filter { it.number >= fromNumber }
                .take(count)
                .toList()
            if (targets.isEmpty()) return@launch
            val local = storage.loadLocalSegmentNumbers(bookName)
            val missing = targets.filter { it.number !in local }
            if (missing.isEmpty()) return@launch
            missing.forEach { addPending(bookName, it.number) }
            try {
                for (seg in missing) {
                    downloadOneOnline(bookId, bookName, seg.number, storage)
                }
            } finally {
                missing.forEach { removePending(bookName, it.number) }
            }
        }
    }

    /**
     * 下载并入库单段（在线按需）。同名书串行，命中本地已存在直接返回 true（天然去重）。
     * 与整本下载不同：**不检查 WiFi**（这些音频是「为了马上能播」，任何网络都下）。
     */
    private suspend fun downloadOneOnline(
        bookId: Long,
        bookName: String,
        number: Int,
        storage: BookStorage
    ): Boolean {
        mutexFor(bookName).lock()
        try {
            if (storage.hasLocalAudioSegment(bookName, number)) return true
            val seg = onlineSegments(bookId)?.firstOrNull { it.number == number } ?: return false
            val bytes = downloadSegment(bookId, seg.id)
            if (bytes == null || bytes.isEmpty()) {
                Log.w(TAG, "在线按需下载失败: book=$bookName 段=$number")
                return false
            }
            val queued = storage.saveAudioSegmentStreaming(bookName, seg.id, seg.number, seg.text, bytes)
            if (!queued) return false
            val written = flushAndCount()
            if (written <= 0) return false
            // 立刻通知：PlaybackService 接长队列 / 结束等待，阅读页重建段号↔行号映射
            notifySegmentSaved(bookName, seg.number)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "在线按需下载异常: book=$bookName 段=$number, ${e.message}")
            return false
        } finally {
            mutexFor(bookName).unlock()
        }
    }

    // ---------------- 状态分发 ----------------

    private fun publish(
        spec: JobSpec,
        total: Int,
        saved: Int,
        failed: Int,
        status: Status,
        message: String = ""
    ) {
        val state = State(
            bookId = spec.bookId, title = spec.title, bookName = spec.bookName,
            total = total, saved = saved, failed = failed, status = status, message = message
        )
        // QUEUED 只更新「没有活动任务时」的快照：否则正在跑 A、刚排队 B 时，
        // 界面会被 B 的「已加入下载队列」覆盖掉 A 的真实进度。
        if (status == Status.QUEUED) {
            if (activeState == null) lastState = state
        } else {
            activeState = state
            lastState = state
        }
        Log.d(
            TAG,
            "任务状态: book=${spec.bookName ?: spec.title} status=$status $saved/$total 失败=$failed $message"
        )
        dispatchState(state)
    }

    private fun dispatchState(state: State) {
        mainHandler.post { listeners.forEach { it.onState(state) } }
    }

    private fun notifySegmentSaved(bookName: String, segmentNumber: Int) {
        mainHandler.post {
            segmentListeners.forEach { it.onSegmentSaved(bookName, segmentNumber) }
        }
    }

    // ---------------- 持久化 / 网络 / 上下文 ----------------

    private fun persist() {
        val ctx = appContext() ?: return
        val specs = synchronized(queueLock) { queue.values.toList() }
        AppPrefs.setStoreAudioJobs(ctx, specs)
    }

    private fun appContext(): Context? = AudioBookApplication.appContext

    private fun storage(ctx: Context): BookStorage? {
        storageRef?.let { return it }
        val app = ctx.applicationContext as? Application ?: return null
        return BookStorage(app).also { storageRef = it }
    }

    /** 仅 WiFi 开关：与电子书下载共用同一份偏好（用户只设一次，两处行为必须一致） */
    private fun wifiAllowed(ctx: Context): Boolean =
        !AppPrefs.isOnlyWifiDownload(ctx) || NetworkUtils.isWifiConnected(ctx)

    /**
     * 被「仅 WiFi」拦下时挂一个网络回调，WiFi 恢复即自动续传。
     *
     * 没有它，暂停的任务要等用户下次手动点下载 —— 而用户已经不在书城页了，
     * 表现就是「明明连上 WiFi 了却一直没动静」。
     */
    private fun registerNetworkCallback() {
        val ctx = appContext() ?: return
        if (networkCallback != null) return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkMaybeReady()

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) = onNetworkMaybeReady()
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "注册网络回调失败: ${e.message}")
            networkCallback = null
        }
    }

    private fun onNetworkMaybeReady() {
        val ctx = appContext() ?: return
        if (!NetworkUtils.isWifiConnected(ctx)) return
        unregisterNetworkCallback()
        Log.d(TAG, "WiFi 已恢复，续传书城音频下载")
        ensureWorker()
    }

    /**
     * 「仅 WiFi 下载」偏好刚被用户改动（书城页的开关）。
     *
     * 关掉开关是「我接受在移动网络下继续」的明确信号，必须**立刻**把被拦下的任务放行 ——
     * 只靠 [registerNetworkCallback] 会卡死：那条路要求「已经连上 WiFi」，而在蜂窝网络下
     * 关开关既不产生网络事件、onNetworkMaybeReady 也会因为不是 WiFi 直接 return，
     * 用户看到的是「关掉了开关却一直没动静」。
     * 打开开关则顺手挂上回调，等 WiFi 恢复自动续传。
     */
    fun onOnlyWifiPreferenceChanged() {
        val ctx = appContext() ?: return
        if (!wifiAllowed(ctx)) {
            registerNetworkCallback()
            return
        }
        unregisterNetworkCallback()
        Log.d(TAG, "已允许非 WiFi 下载，续传书城音频下载")
        ensureWorker()
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val ctx = appContext() ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "注销网络回调失败: ${e.message}")
        }
    }
}
