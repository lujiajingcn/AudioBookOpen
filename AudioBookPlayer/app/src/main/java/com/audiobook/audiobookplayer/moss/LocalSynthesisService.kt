package com.audiobook.audiobookplayer.moss

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.audiobook.audiobookplayer.BookStorage
import com.audiobook.audiobookplayer.MainActivity
import com.audiobook.audiobookplayer.R
import com.audiobook.audiobookplayer.TaskRepository
import com.audiobook.audiobookplayer.model.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 本机合成的前台服务：让合成在用户离开页面/切到后台后继续跑。
 *
 * ## 为什么必须是前台服务
 * 本机合成一段 10 分钟音频要跑 20~100 分钟。如果把它绑在 Fragment 的
 * lifecycleScope 上，用户一切走页面协程就被取消 —— 也就是"点了开始就必须盯着屏幕"，
 * 这对一个几十分钟的任务是不可接受的。
 *
 * 但仅"不绑 UI 生命周期"还不够：普通后台进程随时可能被系统回收或冻结
 * （尤其本机合成还要占 1 GB 以上内存，正是 LMK 的首要目标）。
 * 所以必须走**前台服务**：带常驻通知、声明 [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC]、
 * 并在合成期间持有 PARTIAL_WAKE_LOCK —— 否则屏幕一黑 CPU 就降频/睡眠，
 * 几十秒才推进一段，用户会以为卡死了。
 *
 * 用 dataSync 而不是 mediaPlayback：这里在做的是**计算**不是播放，
 * 只有 dataSync 语义对得上（mediaPlayback 在部分机型/系统版本上会被
 * 音频焦点与播放策略干扰，且上架审核时用途不符会被质疑）。
 *
 * ## 进度如何呈现
 * 不自己画 UI，而是复用已有的「任务」机制：开始合成时建一条 Task
 * （bookName + audioName + totalSegments），每完成一段就
 * `updateTaskStatus(..., completedSegments, ...)`。
 * 书架本来就会把每本书关联的任务渲染成「x / y」进度条
 * （见 ShelfAdapter.bindTask），因此**书架上的进度是自动生效的**，一行 UI 代码都不用加。
 */
class LocalSynthesisService : Service() {

    companion object {
        private const val TAG = "LocalSynthesis"
        private const val CHANNEL_ID = "local_synthesis"
        private const val NOTIFICATION_ID = 8801

        /**
         * 当前进度，供 UI 观察。
         * 用 StateFlow 而不是广播：Activity/Fragment 重建后能立刻拿到当前值，
         * 不需要"先注册再等下一次回调"（那会有一段空白期，用户看到进度消失）。
         */
        private val _progress = MutableStateFlow<Progress?>(null)
        val progress: StateFlow<Progress?> = _progress.asStateFlow()

        /** 是否正在合成（UI 据此决定按钮可用性与是否重连） */
        val isRunning: Boolean get() = _progress.value?.running == true

        /**
         * 合成会话是否正在进行 —— **含「还没产出任何进度」的阶段**。
         *
         * 与 [isRunning] 的差别正是本项目反复踩的那个坑：首个 progress 要等到
         * 解析完成或首段推理结束才发布，在此之前 isRunning 恒为 false。若拿它当
         * 「能不能开始新任务」的判据，就会出现状态分叉 ——
         * 服务端已经受理并加载模型，UI 却认为「没在跑」；
         * 用户再点一次开始，服务只能拒绝，而 UI 已经先把按钮切成「进行中」并
         * 提示「已开始」：用户看到的是一个永远不动的进度条。
         *
         * 放在 companion 是因为它描述的是**进程级**状态（服务实例同一时刻只有一个），
         * UI 与服务必须读同一个值。
         */
        private val sessionFlag = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 合成会话是否正在进行（判重用它，不要用 [isRunning]） */
        val isBusy: Boolean get() = sessionFlag.get()

        /**
         * 是否处于暂停状态。
         *
         * 由服务持有而不是 UI 持有：暂停状态要在**通知栏**和**合成页**两处都能
         * 正确显示与切换，而通知栏点「暂停」时页面可能根本没打开。
         * 让服务做唯一事实来源，两边都读它。
         */
        private val _paused = MutableStateFlow(false)
        val paused: StateFlow<Boolean> = _paused.asStateFlow()

        /**
         * 最近一次合成的终态结论。与 [progress] 一样是进程级的 ——
         * 服务实例同一时刻只有一个，谁在前台谁消费。
         *
         * 用 StateFlow 而不是一次性事件通道（Channel）：Fragment 重建后
         * 「先接收再注册」会丢掉那次事件，而 StateFlow 会把当前值重放给新订阅者，
         * 用户回到页面仍能看到刚才为什么失败。
         */
        private val _outcome = MutableStateFlow<Outcome?>(null)
        val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

        /**
         * 认领一条终态结论。**CAS 保证多个收集器里只有一个能拿到**，
         * 返回 null 表示别人已经消费过（或传进来的本来就是过期的旧值）。
         *
         * 调用方必须**先**确认自己真的能展示（视图还在），再调用本方法 ——
         * 先消费后判断会把这条结论永久吞掉，而另一个收集器也已经收到了同样的值、
         * 只是在 CAS 上输了。
         */
        fun consumeOutcome(expected: Outcome?): Outcome? {
            if (expected == null) return null
            return if (_outcome.compareAndSet(expected, null)) expected else null
        }

        fun pause(context: Context) {
            context.startService(
                Intent(context, LocalSynthesisService::class.java).apply { action = ACTION_PAUSE }
            )
        }

        fun resume(context: Context) {
            context.startService(
                Intent(context, LocalSynthesisService::class.java).apply { action = ACTION_RESUME }
            )
        }

        /**
         * 本机合成任务的 audioName 前缀。
         *
         * 由本机合成产生的任务记录统一带上它，用于把这类任务与**在线合成**的区分开 ——
         * 两者的「孤儿任务」处理方式不同：本机合成的终态写在进程内，
         * 进程被 LMK 杀掉就永远停在「合成中」，必须由 App 下次启动时对账；
         * 而在线合成的状态在服务端，轮询就能追回来。
         *
         * 单点定义，避免服务与对账逻辑各写一份字符串而漂移。
         */
        const val AUDIO_NAME_PREFIX = "本机合成 · "

        /** 本机合成的任务显示名（书架里那条记录） */
        fun audioName(voice: String = MossLocalEngine.DEFAULT_VOICE): String =
            AUDIO_NAME_PREFIX + voice

        /**
         * 暂停时写进任务行的阶段文案。
         *
         * 单点定义：服务写库用它、书架读它判断「这一行是不是暂停住了」，
         * 两边各写一份字符串早晚会漂移（写的是「已暂停」、读的是「暂停中」，
         * 于是暂停后书架上的状态列永远停在「合成中」）。
         */
        const val PAUSED_PROGRESS_TEXT = "已暂停"

        /**
         * @param referenceAudioPath 参考音频的**绝对路径**；null/空表示用内置音色。
         *
         * 为什么传路径而不是 File：跨进程 Intent 只能塞基本类型与 Parcelable，
         * File 不在其中。参考音频本来就在 `filesDir/reference_audio/` 下，
         * 传路径最直接（同应用多进程共享同一个 filesDir，路径在另一进程同样有效）。
         */
        fun start(context: Context, books: List<Book>, voice: String, referenceAudioPath: String? = null) {
            val intent = Intent(context, LocalSynthesisService::class.java).apply {
                action = ACTION_START
                // 传 Book 的四个必要字段而不是 Parcelable 整体：模型里没有实现
                // Parcelable，而这几项足够重建出与书架一致的标识
                // （tableName 决定动态表名，缺了它入库会落到错误的表）。
                putStringArrayListExtra(EXTRA_BOOK_NAMES, ArrayList(books.map { it.displayName }))
                putStringArrayListExtra(EXTRA_BOOK_PATHS, ArrayList(books.map { it.filePath }))
                putStringArrayListExtra(
                    EXTRA_BOOK_TABLES,
                    ArrayList(books.map { it.tableName.ifEmpty { it.displayName } })
                )
                putIntegerArrayListExtra(EXTRA_BOOK_IDS, ArrayList(books.map { it.id }))
                putExtra(EXTRA_VOICE, voice)
                putExtra(EXTRA_REFERENCE_AUDIO, referenceAudioPath)
            }
            // 从后台启动前台服务在 Android 12+ 会被拒；本服务的启动点都在
            // 用户点击之后（Activity 处于前台），满足 BFSL 条件。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, LocalSynthesisService::class.java).apply { action = ACTION_CANCEL }
            )
        }

        private const val ACTION_START = "com.audiobook.audiobookplayer.moss.START"
        private const val ACTION_CANCEL = "com.audiobook.audiobookplayer.moss.CANCEL"
        private const val ACTION_PAUSE = "com.audiobook.audiobookplayer.moss.PAUSE"
        private const val ACTION_RESUME = "com.audiobook.audiobookplayer.moss.RESUME"
        private const val EXTRA_BOOK_NAMES = "book_names"
        private const val EXTRA_BOOK_PATHS = "book_paths"
        private const val EXTRA_BOOK_TABLES = "book_tables"
        private const val EXTRA_BOOK_IDS = "book_ids"
        private const val EXTRA_VOICE = "voice"
        private const val EXTRA_REFERENCE_AUDIO = "reference_audio"
    }

    /**
     * @param bookName 当前正在合成的书
     * @param done 已完成段数
     * @param total 总段数
     * @param etaMs 预计剩余毫秒（-1 = 暂无估计）
     * @param running false 表示这次是终态（完成/中止/失败）
     * @param message 终态时的说明文案
     */
    data class Progress(
        val bookName: String,
        val done: Int,
        val total: Int,
        val etaMs: Long,
        val running: Boolean,
        val message: String? = null
    )

    /**
     * 一轮本机合成的**终态结论**，供界面消费且只消费一次。
     *
     * ## 为什么不复用 [Progress]
     * [Progress] 表达的是「此刻在跑什么」，终态那条发完就过期了；更关键的是
     * 它只有**活着的订阅者**才收得到，而 `repeatOnLifecycle(STARTED)` 会在
     * 用户切走时解绑。于是最常见的那条路径 —— 在书架点「继续合成」、
     * 然后退出合成页 —— 结束时没有任何界面在听，失败原因只落在 logcat 里。
     *
     * 实测（Redmi K30 Pro，本机合成因缺服务端解析缓存失败）：
     * `服务结束：合成失败：这本书还没有服务端解析缓存。请先到阅读页打开一次…`
     * 这句**唯一能告诉用户该怎么办**的话，用户一次都没看到；界面只是从
     * 「合成中」闪回「开始合成」。
     *
     * ## 一次性语义
     * 书架页与合成页可能同时处于 STARTED（MainActivity 用 hide/show 切换），
     * 两个收集器都会收到同一条。用 [consumeOutcome] 做 CAS 认领，
     * 保证只弹一次 —— 否则用户会看到两个一模一样的对话框叠在一起。
     */
    data class Outcome(
        val message: String,
        val level: Level,
        val bookName: String,
        /** 失败时用来提供「去阅读页」入口；空串表示没有可跳转的本地文件 */
        val bookPath: String = ""
    ) {
        enum class Level {
            /** 整本跑完，无段级失败 */
            DONE,

            /** 有段失败但成功了一部分 —— 已入库的段能播，文案不能说成「失败」 */
            PARTIAL,

            /** 用户中止 */
            CANCELLED,

            /** 一段都没成功 */
            FAILED
        }
    }

    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 暂停/恢复闸门。服务级持有 —— 与 Companion 里的 [_paused] 对应，
     * 后者只是给 UI/通知读的镜像，真正的阻塞逻辑在这个对象里。
     */
    private val pauseController = PauseController()

    /**
     * 当前任务在本地的 task_id（书架进度那行记录）。服务级字段，不放在协程局部。
     *
     * 原因是**指令来自 onStartCommand**：用户在通知栏点暂停时，
     * 处理它的是 onStartCommand 而不是合成协程，那里也必须能改这条任务的阶段文案。
     * 若把它藏在协程局部变量里，通知栏暂停就没法更新书架状态 ——
     * 而"通知栏点了暂停、书架却还写合成中"正是最容易让人以为没生效的地方。
     */
    @Volatile private var localTaskId = 0L

    /**
     * 写库用的 repository。提为字段是因为 [onStartCommand] 里的暂停/恢复分支
     * （主线程、早于合成协程启动）也要更新书架的任务文案。
     *
     * 刻意**不持有 BookViewModel**：ViewModel 的 init 会 attach 三个进程级单例，
     * 而注销只写在 `onCleared()` 里 —— 手工 new 的实例永远不会被回收，
     * 导致 TaskPollingManager 的轮询线程永不 shutdown、每轮合成泄漏一个实例。
     * 详见 [startSynthesis] 里那段注释。
     */
    private val taskRepo by lazy { TaskRepository(application) }

    /**
     * 中止标志。由 UI 线程置位、推理线程读取，必须有跨线程可见性；
     * 且服务实例可能在不同时间收到多次 cancel，用 AtomicBoolean 最直接。
     */
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    // 会话标志（sessionFlag）刻意放在 companion：它描述的是进程级状态，
    // UI 也要读同一份（见 isBusy），放在实例字段会让 UI 看不到。
    // 由 onStartCommand 置 true、合成协程的 finally 与 onDestroy 清 false。

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                Log.i(TAG, "收到中止请求")
                cancelled.set(true)
                // 关键：中止时必须把卡在暂停里的 worker 放出来。
                // 否则「暂停中点中止」会让 worker 永远等在 awaitResume 上，
                // 服务既不结束也不释放内存 —— 比崩溃更难排查（进程还活着，
                // 通知栏一直挂着，用户以为在跑）。
                pauseController.releaseAll()
                _paused.value = false
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                if (isRunning) {
                    Log.i(TAG, "收到暂停请求（当前段跑完后停止推进）")
                    pauseController.pause()
                    _paused.value = true
                    updateNotification("已暂停（当前段仍在跑完，之后不再开始新段）")
                    // 书架那条任务也要显示「已暂停」：用户可能是在页面里点的暂停，
                    // 然后退到书架看状态 —— 那里若还写「合成中 3/16」，他会以为没暂停成功。
                    if (localTaskId != 0L) {
                        scope?.launch {
                            taskRepo.updateProgressText(localTaskId, PAUSED_PROGRESS_TEXT)
                        }
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                if (isRunning) {
                    Log.i(TAG, "收到恢复请求")
                    pauseController.resume()
                    _paused.value = false
                    updateNotification(progressSummary())
                    if (localTaskId != 0L) {
                        scope?.launch { taskRepo.updateProgressText(localTaskId, null) }
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // 判重必须用 isBusy，**不能**用 isRunning：
                // isRunning 依赖 _progress，而首个 publish 要等到解析完成或首段进度
                // 才发生 —— 在那之前它恒为 false。用户在这段窗口里连点两次「开始」，
                // 就会跑起两个协程、加载两份模型（实测 ≈1 GB/份），
                // 结果不是「跑得更快」而是被 lowmemorykiller 杀掉。
                if (isBusy) {
                    // 已有任务在跑：不叠加第二个（内存根本放不下两份模型）
                    Log.w(TAG, "已有本机合成在进行，忽略新的启动请求")
                    return START_NOT_STICKY
                }
                val names = intent.getStringArrayListExtra(EXTRA_BOOK_NAMES).orEmpty()
                val paths = intent.getStringArrayListExtra(EXTRA_BOOK_PATHS).orEmpty()
                val tables = intent.getStringArrayListExtra(EXTRA_BOOK_TABLES).orEmpty()
                val ids = intent.getIntegerArrayListExtra(EXTRA_BOOK_IDS).orEmpty()
                val voice = intent.getStringExtra(EXTRA_VOICE) ?: MossLocalEngine.DEFAULT_VOICE
                // 参考音频：空串按"没选"处理（Intent 里传 null 会退化成字符串 "null"）
                val refPath = intent.getStringExtra(EXTRA_REFERENCE_AUDIO)
                    ?.takeIf { it.isNotBlank() && it != "null" }
                // ⚠️ startForeground 必须在 startForegroundService 之后数秒内调用，
                // Android 12+ 否则抛 ForegroundServiceDidNotStartInTimeException（直接崩）。
                // 所以**即使参数不合法也要先起前台**，不能像原来那样先 stopSelf 返回 ——
                // 那条路径上一次都没调 startForeground，等于必崩。
                startForegroundWithNotification(names.firstOrNull() ?: "本机合成")
                if (names.isEmpty() || names.size != paths.size || names.size != tables.size) {
                    Log.w(TAG, "参数不合法（names=${names.size} paths=${paths.size} tables=${tables.size}），停止服务")
                    stopSelf()
                    return START_NOT_STICKY
                }
                sessionFlag.set(true)
                // 中止标志在这里清，而不是放到合成协程的首行：
                // 放在协程里的话，用户「点开始后立刻点中止」时 CANCEL 已经把标志置位，
                // 协程随后才启动并把它清回 false —— 中止被静默吞掉，合成照跑到底。
                cancelled.set(false)
                acquireWakeLock()
                runSynthesis(names, paths, tables, ids, voice, refPath)
            }
        }
        // START_NOT_STICKY：被系统杀掉后不要自动重启 ——
        // 重启时拿不到原来的参数（书籍路径），只会得到一个空任务。
        // 用户回到 App 会看到任务停在"合成中"，可以手动重试。
        return START_NOT_STICKY
    }

    private fun runSynthesis(
        bookNames: List<String>,
        bookPaths: List<String>,
        bookTables: List<String>,
        bookIds: List<Int>,
        voice: String,
        referenceAudioPath: String? = null
    ) {
        val appContext = applicationContext
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serviceScope

        serviceScope.launch {
            // 这里**不构造 BookViewModel**（曾经构造过，是个必须消除的泄漏源）。
            //
            // 【为什么不能 new BookViewModel(application)】ViewModel 的 init 会
            //   attach 三个**进程级**单例：TaskPollingManager、StoreAudioDownloadManager、
            //   以及 context.bindService(PlaybackService)；而这三者的**注销只写在
            //   BookViewModel.onCleared()** 里。手工 new 出来的实例永远不进
            //   ViewModelStore ⇒ onCleared 永不触发 ⇒
            //     · TaskPollingManager.detach() 判「listeners.isEmpty()」永远不成立
            //       ⇒ 2秒轮询线程**永不 shutdown**，只要进程活着就一直打服务端；
            //     · 每合成一轮泄漏一个 VM + 一个 SegmentListener + 一个未解绑的服务绑定；
            //     · 这些写库都挂在 viewModelScope 上，与 serviceScope 无关，
            //       onDestroy 的 scope.cancel() 收不住它们。
            // 底层 BookStorage/TaskRepository 本就是共用单例，写库对 UI 立即可见，
            // 所以直接用 repository 完全等价，且没有泄漏。
            val repo = taskRepo
            // 每本书一条任务记录，书架据此渲染进度条。
            // 用本地毫秒时间戳当本地 task_id（与在线合成的 tempTaskId 同口径），
            // server_task_id 保持 null —— 这条任务不是服务端产生的，
            // 写上假 id 会让 TaskPollingManager 去轮询一个不存在的服务端任务。
            //
            // 下面两个状态刻意**逐本书重置**（放在 for 循环内）：
            // 共用一份的话，第 2 本书会继承第 1 本已用过的节流时间戳与
            // 「阶段文案已清过」的标记，于是第 2 本的进度前几段不落库、
            // 「正在加载模型」也永远清不掉。
            var succeeded = 0
            var total = 0
            var cancelledByUser = false
            var failure: String? = null

            try {
                for ((index, name) in bookNames.withIndex()) {
                    if (cancelled.get()) {
                        cancelledByUser = true
                        break
                    }
                    // tableName 决定动态表名，必须与书架一致；displayName 用于
                    // 展示与入库关联（BookStorage 里 tableName 就是 displayName）。
                    val book = Book(
                        id = bookIds.getOrElse(index) { 0 },
                        tableName = bookTables[index].ifEmpty { name },
                        displayName = name,
                        filePath = bookPaths[index]
                    )
                    val prefix = if (bookNames.size > 1) "[${index + 1}/${bookNames.size}] " else ""
                    // 关键：把本次任务的业务 id **捕获成局部常量**再往下传。
                    // 原先三处回调都直接读 @Volatile 字段 localTaskId，而回调里是
                    // serviceScope.launch { } —— 异步执行，等协程真正跑到时
                    // 外层 for 循环可能已经进入下一本书、localTaskId 已被改写。
                    // 后果是第 1 本的 saveTask/进度被写到第 2 本的任务上。
                    //
                    // taskId 的**赋值在下面**（upsertLocalTask 返回值）：续跑要复用
                    // 旧行，真实 id 只能由 upsert 决定，此处不再自己生成。
                    var bookTotal = 0
                    // 本次开始时本地已有的段数（续跑时 > 0）。
                    // 失败分支要拿它当进度分子：写 0 会让用户已经拿到的那几段
                    // 在书架上凭空消失，看起来像"一段都没做"。
                    var bookTotalDone = 0
                    // 逐本书重置进度节流状态（见上方说明）
                    val lastWriteMs = java.util.concurrent.atomic.AtomicLong(0L)
                    val stageCleared = java.util.concurrent.atomic.AtomicBoolean(false)

                    // 任务行必须在**解析之前**就建好。
                    // 解析失败（PDF 不支持 / 文件损坏 / 解析异常）与
                    // 「正在解析电子书」这条阶段文案都发生在 onParsed 之前；
                    // 若那时才 INSERT，这些 UPDATE 会全部命中 0 行 ——
                    // 表现是「合成失败了，但书架上连一条记录都没有」，
                    // 用户既看不到失败原因，也没有重试入口。
                    //
                    // ⚠️ 用 upsert 而不是 saveTask：**返回的实际 id 才是本轮要写的那一行**。
                    // 续跑会复用旧行（复用时返回旧 task_id），若继续拿本地生成的
                    // taskId 去 UPDATE，所有进度/状态更新都命中 0 行 —— 表现是
                    // 「合成跑完了，书架上那行还是 partial、进度不动」。
                    //
                    // id 用 `currentTimeMillis() * 1000 + 序号` 而不是纯毫秒：
                    // `task_id` 上有 UNIQUE 约束，而批量合成多本书时两次调用
                    // 落在同一毫秒的概率在低端机（时钟粒度 1~10ms）上并不低
                    // ⇒ 撞约束后所有进度更新命中 0 行且不报错。
                    // 乘 1000 腾出 3 位给序号，同一毫秒内最多 1000 本不撞。
                    val taskId = try {
                        repo.upsertLocalTask(
                            bookName = name,
                            audioName = audioName(voice),
                            newTaskId = System.currentTimeMillis() * 1000 + index
                        )
                    } catch (e: Exception) {
                        // 任务行建不出来（撞唯一约束 / DB 异常）。**必须让这本书明确失败**：
                        // 若继续往下合成，进度与终态都写不进去（WHERE task_id=? 命中 0 行
                        // 且不报错），用户看到的是「合成跑完了、书架上什么都没有」，
                        // 既看不到失败原因，也没有重试入口。绝不吞掉。
                        Log.e(TAG, "为「$name」登记本机任务行失败，跳过这本书", e)
                        failure = "任务登记失败：${e.message}"
                        total++
                        break
                    }
                    localTaskId = taskId
                    Log.i(TAG, "任务记录 id=$taskId（${prefix}书架进度行）")

val result = MossLocalSynthesizer.synthesizeBook(
   context = appContext,
    book = book,
      storage = BookStorage(application),
        voice = voice,
   referenceAudioFile = referenceAudioPath?.let { java.io.File(it) },
      parallelism = 0,
                        pauseController = pauseController,
                        listener = object : MossLocalSynthesizer.ProgressListener {
                            override fun onParsed(totalSegments: Int, alreadyDone: Int) {
                                bookTotal = totalSegments
                                bookTotalDone = alreadyDone
                                // 任务行上面已经建好，这里只补真实总数与进度起点。
                                // updateTaskStatus 必须在协程里（它内部有 DB 写入）。
                                //
                                // alreadyDone 不能当 0：续跑时本地已有 N 段，
                                // 从 0 起算会让书架进度条从「N / 总数」倒退成「0 / 总数」——
                                // 用户刚点「继续合成」就看着进度归零，以为功能坏了。
                                serviceScope.launch {
                                    repo.updateStatus(
                                        taskId, "processing", alreadyDone, totalSegments
                                    )
                                }
                            }

                            // 参数名与 ProgressListener 声明的 total 保持一致：
                            // Kotlin 会在覆写参数名不同时报 warning，
                            // 而命名实参调用（onProgress(done = ..., total = ...)）会静默失配。
                            override fun onProgress(done: Int, total: Int, etaMs: Long) {
                                publish(Progress(name, done, total, etaMs, running = true))
                                // 记住**最新的真实进度**。失败分支（Result.Failed）要用它
                                // 写 completed_segments，而那个分支拿不到合成器的 savedCount
                                // （Result.Failed 只带一句 message）。
                                //
                                // 【为什么必须在这里记 —— 真机踩过】
                                // 原先 bookTotalDone 只在 onParsed 时赋值一次（等于"本轮开始时
                                // 已有的段数"），此后 onProgress 再也没写回它。于是合成到
                                // 12/31 失败时，任务行被写成 **0/31**：用户点「继续合成」
                                // 会被告知"还缺 31 段"，而库里明明已经有 12 段能播。
                                // 进度数字倒退比文案错更糟 —— 它让续跑永远追不上。
                                bookTotalDone = done
                                // 第一次拿到真实进度时清掉阶段文案，否则书架会一直停在
                                // 「正在加载模型」——而那段早已过去，界面说的和实际做的不一致。
                                val clearStage = stageCleared.compareAndSet(false, true)
                                // onProgress 是普通回调（非 suspend），而写库是 suspend，
                                // 因此在服务自己的 scope 里起一个子协程；
                                // 顺序性由 lastWriteMs 的时间戳判断保证，不依赖协程完成顺序。
                                serviceScope.launch {
                                    if (clearStage && taskId != 0L) {
                                        repo.updateProgressText(taskId, null)
                                    }
                                    lastWriteMs.set(
                                        writeProgressThrottled(
                                            repo, taskId, done, total, lastWriteMs.get()
                                        )
                                    )
                                }
                            }

                            override fun onStage(stage: String) {
                                updateNotification("$prefix$stage")
                                // 阶段也写进任务表：书架里那条记录会显示「正在加载模型」
                                // 而不是纹丝不动的「0 / N」。
                                // 加载模型实测 13~42 秒，这段时间没有任何进度数字可涨，
                                // 不给阶段文案的话用户会以为点了没反应。
                                if (taskId != 0L) {
                                    serviceScope.launch {
                                        repo.updateProgressText(taskId, stage)
                                    }
                                }
                            }
                        },
                        isCancelled = { cancelled.get() }
                    )
                    when (result) {
                        is MossLocalSynthesizer.Result.Success -> {
                            succeeded += result.savedSegments
                            total += result.totalSegments
                            if (result.cancelled) cancelledByUser = true
                            // 用局部常量 taskId，不读 localTaskId：
                            // 多书批量时后者可能已经是下一本书的 id
                            repo.updateStatus(
                                taskId,
                                // 段级失败同样只能算 partial。写 completed 会让书架显示
                                // 「已完成」，用户以为整本齐了，实际缺段且无处可查
                                if (result.cancelled || result.failedSegments > 0) "partial"
                                else "completed",
                                result.savedSegments,
                                result.totalSegments
                            )
                            if (result.failedSegments > 0) {
                                failure = "有 ${result.failedSegments} 段合成失败，可重试补齐"
                            }
                        }
                        is MossLocalSynthesizer.Result.Failed -> {
                            failure = result.message
                            // 任务行已在循环开头建好，所以这里一定更新得到：
                            // 解析失败/模型加载失败也留得下痕迹。
                            // 分子不能写 0：续跑到一半失败时，本地已有的那些段是好的，
                            // 写成 0 会让「已有 N 段」在书架上凭空消失。
                            repo.updateStatus(taskId, "failed", bookTotalDone, bookTotal)
                            break
                        }
                    }
                    if (cancelledByUser) break
                }
            } catch (t: Throwable) {
                Log.e(TAG, "本机合成异常", t)
                failure = t.message ?: t.javaClass.simpleName
                // 这里只能用 localTaskId（异常可能发生在 for 之外）：
                // 它此时指向最后进入的那本书，绝大多数异常都发生在合成协程内。
                if (localTaskId != 0L) {
                    runCatching { repo.updateStatus(localTaskId, "failed", succeeded, total) }
                }
            } finally {
                // 终态的三个消费者必须拿到**同一句话**：进度流（合成页的过程文案）、
                // 通知栏（publish 内部会写）、以及下面这条 outcome（失焦页面回来时的弹窗）。
                // 三处各拼一份文案迟早会走样，尤其是「部分完成」这种既要报段数
                // 又要报原因的分支。
                val level = when {
                    failure != null && succeeded == 0 -> Outcome.Level.FAILED
                    cancelledByUser -> Outcome.Level.CANCELLED
                    failure != null -> Outcome.Level.PARTIAL
                    else -> Outcome.Level.DONE
                }
                val message = when {
                    // 一段都没成功才算「失败」；成功了一部分而个别段失败，
                    // 报「合成失败」会让用户以为白跑了，实际已入库的段是能播的
                    failure != null && succeeded == 0 -> "合成失败：$failure"
                    cancelledByUser -> "已中止：已完成 $succeeded/$total 段"
                    failure != null -> "部分完成：$succeeded/$total 段（$failure）"
                    else -> "已完成 $succeeded/$total 段"
                }
                publish(
                    Progress(
                        bookName = bookNames.lastOrNull() ?: "",
                        done = succeeded,
                        total = total,
                        etaMs = -1,
                        running = false,
                        message = message
                    )
                )
                // 必须放在 publish 之后：UI 那边是「先按 running=false 收掉进度条，
                // 再由 outcome 决定弹对话框还是 Toast」。顺序反过来的话，
                // 对话框会在进度条还转着的时候弹出来，用户以为合成都完事了
                // 却还挂着一个转圈。
                _outcome.value = Outcome(
                    message = message,
                    level = level,
                    bookName = bookNames.lastOrNull() ?: "",
                    bookPath = bookPaths.lastOrNull() ?: ""
                )
                // 会话标志必须在这里清：否则服务虽然 stopSelf 了，
                // 只要进程还在（比如通知栏的暂停按钮再被点一次），
                // 下一次「开始合成」就会被自己判成「已有任务在跑」而永远启动不了。
                sessionFlag.set(false)
                releaseWakeLock()
                // 复位暂停状态：服务结束后若还留着 true，用户再次开始合成时
                // 通知栏会显示「继续」，而实际上根本没在暂停 —— 状态分叉。
                pauseController.releaseAll()
                _paused.value = false
                // 终态通知保留几秒再撤，让用户看到结果；随后停止前台并自杀。
                // 不撤通知直接 stopForeground(true) 会让"合成完成"这件事
                // 在通知栏一闪而过，用户回来时什么都没看到。
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                Log.i(TAG, "服务结束：$message")
            }
        }
    }

    /**
     * 把段级进度写进任务表（**限流**）。
     *
     * 为什么不每段都写：本机合成一段只要几秒，一本几百段的书就是几百次
     * updateStatus —— 每次都是一个 UPDATE 加一次 loadTasks（后者会重查整张
     * 任务表并 postValue 整份列表，触发书架整列重绘）。这既废 IO 又会让
     * 书架在几十分钟里不停闪动。
     *
     * 限流策略：**只在完成段数跨过 1% 或距上次写入超过 5 秒时落库**。
     * 界面上看到的进度因此至多是 5 秒前的，对几十分钟的任务无感；
     * 而终态一定会写（调用方在每本书结束后单独 updateTaskStatus），
     * 所以不会出现"停在 97% 却已经完成"。
     *
     * @return 本次实际写入时的时间戳（未写入则原样返回 lastWriteMs）
     */
    private suspend fun writeProgressThrottled(
        repo: TaskRepository,
        taskId: Long,
        done: Int,
        total: Int,
        lastWriteMs: Long
    ): Long {
        if (taskId == 0L || total <= 0) return lastWriteMs
        val now = System.currentTimeMillis()
        // ⚠️ 这里原来是 `(total / 100).coerceAtLeast(1)`：total=31 时 step=0→1，
        // 于是 `done % 1 == 0` **恒真** ⇒ 每完成一段都写一次库，而写库会顺带
        // loadTasks() 重查整张 tasks 表 —— 注释承诺的「避免书架不停闪动」
        // 在小书上完全失效（每段 25~45 秒，31 段就是 31 次全量刷新）。
        // 修法：用向上取整算步长，并让「跨 1%」与「超时」**都**满足才写。
        val step = maxOf(1, (total + 99) / 100)
        val crossedPercent = done % step == 0
        val stale = now - lastWriteMs >= 5_000
        if (!(crossedPercent && stale) && !(done >= total)) return lastWriteMs
        repo.updateStatus(taskId, "processing", done, total)
        return now
    }

    /** 通知栏用的一句话进度摘要（恢复时用它把「已暂停」换回来）。 */
    private fun progressSummary(): String {
        val p = _progress.value ?: return "正在准备本机合成"
        if (!p.running) return p.message ?: "已结束"
        val eta = if (p.etaMs >= 0) "，剩余约 ${p.etaMs / 60000} 分钟" else ""
        return "正在本机合成 ${p.done}/${p.total} 段$eta"
    }

    /** 进度落库 + 更新内存态 + 刷新通知（每完成一段调用一次） */
    private fun publish(p: Progress) {
        _progress.value = p
        updateNotification(
            if (p.running) {
                val eta = if (p.etaMs >= 0) "，剩余约 ${p.etaMs / 60000} 分钟" else ""
                "正在本机合成 ${p.done}/${p.total} 段$eta"
            } else {
                p.message ?: "已结束"
            }
        )
    }

    private fun startForegroundWithNotification(bookName: String) {
        createChannel()
        val notification = buildNotification("正在准备本机合成", bookName)
        // ⚠️ targetSdk 34 起必须显式给出前台服务类型，否则 startForeground 抛
        // MissingForegroundServiceTypeException（Android 14 的行为变更）。
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "本机语音合成",
                // LOW：合成是长任务，不该响铃或弹横幅打扰用户，
                // 但必须常驻可见（前台服务的要求 + 用户能随时中止它）。
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在手机本地合成有声书时的进度与中止入口"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String, bookName: String): Notification {
        // 点通知回到主界面（合成进度就在书架页，用户回来能直接看到）
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalSynthesisService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 暂停/恢复合成一个按钮位：按当前状态决定点下去是哪个动作。
        // 只放一个而不是两个并排 —— 两个按钮里总有一个是无效的，
        // 在通知这种窄区域里容易误触。
        val isPaused = _paused.value
        val toggleIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, LocalSynthesisService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("本机合成：$bookName")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "继续" else "暂停",
                toggleIntent
            )
            // 中止入口必须给：否则用户只能进 App 才能停，而长任务很可能
            // 是他已经离开 App 之后才想停的。
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "中止", cancelIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val bookName = _progress.value?.bookName ?: ""
        manager.notify(NOTIFICATION_ID, buildNotification(text, bookName))
    }

    /**
     * 合成期间持有 PARTIAL_WAKE_LOCK。
     * 不加这个的话，屏幕一黑系统就进入 Doze/降频，本机合成这种纯 CPU 任务
     * 会慢到几乎不推进 —— 用户会认为"后台就不干活了"。
     * 用 PARTIAL（不是 SCREEN_DIM）：只需要 CPU 转，不需要亮屏。
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiobook:localSynthesis").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)   // 上限 6 小时，兜底防止忘记释放
            }
        } catch (e: Throwable) {
            Log.w(TAG, "获取 WakeLock 失败（不影响合成，只是后台可能变慢）: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "释放 WakeLock 失败: ${e.message}")
        }
        wakeLock = null
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        cancelled.set(true)
        // 协程被 cancel 时 finally 大概率会跑（它负责停前台服务、写终态），
        // 但进程被杀等极端路径下不保证；这里兜底清一次，
        // 否则会话标志会永远停在 true，同进程内再也启动不了合成。
        sessionFlag.set(false)
        releaseWakeLock()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }
}
