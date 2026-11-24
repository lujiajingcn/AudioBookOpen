package com.audiobook.audiobookplayer

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.audiobook.audiobookplayer.model.Book
import com.audiobook.audiobookplayer.moss.LocalSynthesisService
import com.audiobook.audiobookplayer.model.Favorite
import com.audiobook.audiobookplayer.model.LineItem
import com.audiobook.audiobookplayer.model.ShelfEntry
import com.audiobook.audiobookplayer.model.Task
import com.audiobook.audiobookplayer.model.TocEntry
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import com.audiobook.audiobookplayer.parser.EbookSegmentMap
import com.audiobook.audiobookplayer.parser.LocalEbookParser
import com.audiobook.audiobookplayer.parser.ParagraphSplitter
import com.audiobook.audiobookplayer.parser.PdfPreviewParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayList
import kotlin.math.min

/** 读 TXT 时单块喂给 [ParagraphSplitter] 的字符数（约 16KB 内存，避免整本驻留堆） */
private const val READ_CHUNK_CHARS = 8 * 1024

/**
 * 书城「在线读」时，播放位置之后预取的段数（滑动窗口）。
 * 太大浪费流量/存储，太小则播放容易追上下载而卡顿；4 段（约十几秒语音）足够在网络正常时保持无缝。
 */
private const val ONLINE_PREFETCH_WINDOW = 4

// 书架有声覆盖徽章三档配色（ARGB int，与 PC shelf_page 徽章色一致）：
// 绿=本机可播、蓝=云端可下载、橙=另一设备已合成/本机待合成。
private const val BADGE_GREEN = 0xFF2C6E49.toInt()
private const val BADGE_BLUE = 0xFF2B6CB0.toInt()
private const val BADGE_ORANGE = 0xFFB7791F.toInt()

/** 导入结果：成功 / 失败原因 / 文件名（用于 Toast 文案）。 */
private data class ImportResult(
    val imported: Boolean,
    val errorMsg: String?,
    val fileName: String?
)

/**
 * 书架「有声覆盖图」徽章（Phase3 跨端）。由 [BookViewModel] 依据 audio_coverage 缓存 + 本机
 * 可播放段算出，按 book.id 下发给 [ShelfAdapter] 着色。三档诚实语义（与 PC 端 shelf_page 对齐）：
 *  - 本机确有字节 → 绿「✔ 本机有声 · 可直接播放」；
 *  - 服务端有字节 → 蓝「☁ 云端有声 · 可下载播放」；
 *  - 仅另一设备合成（服务端无字节）→ 橙「◍ 另一设备已合成 · 本机待合成」，**绝不称可播**。
 *
 * [colorArgb] 是打包好的 ARGB int，直接喂给 `TextView.setTextColor`。
 */
data class ShelfBadge(
    val text: String,
    val colorArgb: Int
)

/** 本地可导入的扩展名白名单（能否最终解析由后续阅读逻辑决定；这里只挡住明显不可能的格式）。 */
private val SUPPORTED_IMPORT_EXTENSIONS = setOf("txt", "epub", "pdf", "mobi", "md")

private const val TAG = "BookVM"

class BookViewModel(application: Application) : AndroidViewModel(application) {

    private val context = getApplication<Application>()

    // A6-4：本类不再持有 databaseHelper，也不再出现任何 SQL —— 全部经下面三个 Repository。
    // 表名校验同样下沉到 BookStorage.validateTableName（唯一接触动态内容表的地方）。
    private val taskRepository = TaskRepository(context)
    private val favoriteRepository = FavoriteRepository(context)
    private val bookStorage = BookStorage(context)
    /** 书城书架跨端同步（服务端为真源，本机走「在线占位条目」承接） */
    private val shelfSyncManager = ShelfSyncManager(context)
    /** 有声覆盖图跨端同步（服务端为真源并集，本机走 audio_coverage 缓存承接徽章） */
    private val coverageSyncManager = AudioCoverageSyncManager(context)

    // ---------- 阅读页的一次性标记 ----------
    //
    // 这两个标记**必须**放 ViewModel，不能放 Activity 字段。原因是：
    // Activity 的字段在旋转等配置变更后被重置，而 `Activity.getIntent()` 在配置变更后
    // 返回的仍是**原始启动 Intent**（extra 一个不少）。于是「读一次就清零」的标记
    // 实际上根本没被清掉，重新 onCreate 时又原样读回来。后果有两面：
    //   1) 待定位段号未清零 → 每次旋转都重复执行一次定位滚动；
    //   2) 「已续播」标记被重置成 false → 用户已经暂停的书在旋转后「诈尸」重新起播。
    // ViewModel 跨配置变更存活，正好是放这类标记的地方。

    /** 待定位的段号；-1 表示无需定位。被 [consumeScrollToLine] 取走后自动置回 -1。 */
    private var pendingScrollToLine: Int = -1

    /**
     * 待定位的**音频段号**（2026-11-01 新增，用于「点通知卡片回阅读页」）。
     *
     * <p>为什么不复用 [pendingScrollToLine]：那个字段的消费方是三层兜底
     * （文本 → 行号直配 → 段号换算，见 BookContentActivity.applyPendingNavigation），
     * 段号若塞进去会**先撞上「行号直配」** —— 某行的 `lineNumber` 恰好等于段号时
     * 就定位到完全无关的一行。段号必须有自己的一层，语义不能混。
     *
     * <p>为什么放 ViewModel：与 [pendingScrollToLine] 同理，旋转后 Activity 会重新
     * 从**原始 Intent** 读回 extra，标记放 Activity 字段等于没清，会重复定位。
     */
    private var pendingScrollToSegment: Int = -1

    /**
     * 待定位的**锚点文本**（收藏时选中的原文片段）；null 表示没有。
     * 与 [pendingScrollToLine] 一样是「读一次即作废」的标记，故同样必须放 ViewModel。
     */
    private var pendingScrollText: String? = null

    /** 本次会话是否已经执行过断点续播 */
    private var autoResumeDone = false

    /**
     * 记录「内容加载完成后要滚动到哪一段」（-1 表示不定位）。
     *
     * @param lineNumber 收藏时存的行号，仅作**兜底与偏置**（切分粒度一变行号就漂）
     * @param anchorText 收藏时选中的原文片段，定位的**第一判据**；为空则退回按行号定位
     */
    fun requestScrollToLine(lineNumber: Int, anchorText: String? = null) {
        pendingScrollToLine = lineNumber
        pendingScrollText = anchorText
    }

    /** 取出待定位段号并作废（一次性） */
    fun consumeScrollToLine(): Int {
        val value = pendingScrollToLine
        pendingScrollToLine = -1
        return value
    }

    /**
     * 记录「内容加载完成后要滚动到哪个**音频段**」（-1 表示不定位）。
     *
     * 由「点通知栏/锁屏的播放卡片回到阅读页」使用：那条 Intent 只知道正在播第几段，
     * 不知道对应正文的哪一行，所以要等正文结构就绪后再换算（[rowIndexOfSegment]）。
     */
    fun requestScrollToSegment(segmentNumber: Int) {
        pendingScrollToSegment = segmentNumber
    }

    /** 取出待定位段号并作废（一次性） */
    fun consumeScrollToSegment(): Int {
        val value = pendingScrollToSegment
        pendingScrollToSegment = -1
        return value
    }

    /** 取出待定位锚点文本并作废（一次性） */
    fun consumeScrollText(): String? {
        val value = pendingScrollText
        pendingScrollText = null
        return value
    }

    /**
     * 按收藏的原文片段定位阅读页行下标。
     *
     * 收藏存下来的行号是**切分粒度的函数**（同一本书把行切分规则一改，行号就指向别的文字），
     * 所以定位以内容为准，行号只作为兜底与「多命中时选哪一个」的偏置。
     * 实现在 [EbookSegmentMap.locateRowByText]，与「段号 ↔ 行号」映射同源（同一套忽略空白的比对）。
     */
    fun rowIndexByAnchor(anchorText: String?, lineNumberHint: Int): Int {
        if (anchorText.isNullOrEmpty()) return -1
        return EbookSegmentMap.locateRowByText(lineList.map { it.text }, anchorText, lineNumberHint)
    }

    fun isAutoResumeDone(): Boolean = autoResumeDone

    fun markAutoResumeDone() {
        autoResumeDone = true
    }


    // 任务轮询统一由进程级单例 TaskPollingManager 负责：
    // 多个 BookViewModel 实例（MainActivity / BookContentActivity / BookListActivity）
    // 共享同一条轮询线程，避免重复请求服务端
    private val pollingListener = object : TaskPollingManager.Listener {
        override fun getDownloadingTaskIds(): Set<Long> =
            _tasks.value?.filter { it.downloadStatus == "downloading" }
                ?.map { it.id }?.toSet() ?: emptySet()

        override fun onTaskStatusChanged() {
            loadTasks()
        }
    }

    /**
     * 「任务表已变更」监听（进程级回调，来自 [TaskRepository]）。
     *
     * ## 为什么要自己再去抖一下
     * 回调在**写库线程**上被调用，且一次合成可能连着写好几行（进度、阶段文案、
     * 终态各一次）。直接 loadTasks() 会把一次合成放大成好几次全表查询，
     * 而且并发查询可能让**旧快照后到**覆盖新快照（谁都 postValue，谁最后到谁赢）。
     * 所以这里只做一件事：记下"该刷新了"，真正刷新交给 [scheduleTasksReload] 去抖。
     */
    private val tasksChangedListener: () -> Unit = { scheduleTasksReload() }

    /** 去抖后的刷新任务：写库频繁时合并成一次查询 */
    private var tasksReloadJob: Job? = null

    /** loadTasks 的并发序号：只有最新一次查询的结果才允许写进 LiveData */
    private val tasksLoadSeq = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 任务表变化后的去抖刷新。
     *
     * 300ms 窗口是权衡出来的：本机合成一段音频要几秒到几十秒，进度值得实时跟；
     * 而"每段一次全表查询"（tasks 表很小，几十行）在 300ms 合并下完全无压力。
     */
    private fun scheduleTasksReload() {
        tasksReloadJob?.cancel()
        tasksReloadJob = viewModelScope.launch {
            delay(300)
            loadTasks()
        }
    }

    // 书籍列表
    private val _books = MutableLiveData<List<Book>>()
    val books: LiveData<List<Book>> = _books

    // 电子书列表
    private val _ebooks = MutableLiveData<List<Book>>()
    val ebooks: LiveData<List<Book>> = _ebooks

    // 任务列表
    private val _tasks = MutableLiveData<List<Task>>()
    val tasks: LiveData<List<Task>> = _tasks

    /**
     * 书城音频段到达信号：每次 [StoreAudioDownloadManager] 为当前书写入新片段时 post。
     *
     * 为什么需要它：书城下载的书只写 `audio_segments` 表、不建 `tasks` 行，
     * 而 [BookContentActivity] 的 `bookHasAudio` 原本只从 tasks 推导 ⇒ 永远 false ⇒
     * 播放按钮走系统 TTS 分支。本信号让 Activity 知道"音频到了"，触发一次
     * [checkHasAudio] 直接查 `audio_segments`，把 `bookHasAudio` 翻为 true。
     */
    private val _storeAudioArrived = MutableLiveData<String>()
    val storeAudioArrived: LiveData<String> = _storeAudioArrived

    /**
     * 书城「在线读」模式下本书是否有服务端合成音频。
     *
     * 在线读时本地 `audio_segments` 还没缓存、也没有 `tasks` 行，Activity 原本推不出
     * `bookHasAudio`。用它直接告知「服务端有音频」⇒ 底部播放键走「合成音频 + 按需下载」
     * 而不是系统 TTS。值来自 `/segments` 是否返回了已完成片段。
     */
    private val _onlineAudioAvailable = MutableLiveData<Boolean>()
    val onlineAudioAvailable: LiveData<Boolean> = _onlineAudioAvailable

    /**
     * 书架聚合列表：ebooks ∪ tasks。
     *
     * 任务挂在对应书籍下展示，因此书架的数据源不再只是 ebooks 表。合并规则：
     * 1. ebooks 中的每本书，挂上 book_name 与 display_name 相同的任务；
     * 2. 只有任务、没有电子书记录的书（如服务端合成后尚未登记本地文件），
     *    构造 filePath 为空的虚拟书籍条目，保证任务不会从界面上消失。
     */
    private val _shelfEntries = MediatorLiveData<List<ShelfEntry>>()
    val shelfEntries: LiveData<List<ShelfEntry>> = _shelfEntries

    /**
     * 书架有声覆盖徽章（Phase3 跨端）：book.id → [ShelfBadge]。
     * 由 [refreshCoverageBadges] 依据本地 audio_coverage 缓存 + 本机可播放段算出；
     * [ShelfAdapter] 观测后灌入着色。覆盖图同步（[syncAudioCoverageFromServer]）成功后刷新。
     */
    private val _coverageBadges = MutableLiveData<Map<Long, ShelfBadge>>()
    val coverageBadges: LiveData<Map<Long, ShelfBadge>> = _coverageBadges

    /** 合并 ebooks 与 tasks，产出书架条目（在主线程执行） */
    private fun mergeShelf() {
        val ebooks = _ebooks.value.orEmpty()
        val tasks = _tasks.value.orEmpty()

        // loadTasks 已按 created_at DESC 取出，groupBy 保持该顺序，最新任务在前
        val tasksByBook = tasks.groupBy { it.bookName }

        val entries = mutableListOf<ShelfEntry>()
        val matchedBookNames = mutableSetOf<String>()

        for (book in ebooks) {
            matchedBookNames.add(book.displayName)
            entries.add(ShelfEntry(book, tasksByBook[book.displayName].orEmpty()))
        }

        // 仅有任务、无电子书记录的书：补虚拟条目，避免任务信息丢失
        for ((bookName, bookTasks) in tasksByBook) {
            if (bookName in matchedBookNames) continue
            entries.add(
                ShelfEntry(
                    Book(
                        id = 0,
                        tableName = bookName,
                        displayName = bookName,
                        filePath = "",
                        hasAudio = bookTasks.any { it.hasAudio }
                    ),
                    bookTasks
                )
            )
        }

        _shelfEntries.value = entries
    }

    /**
     * 更新某个任务的下载状态（在任务列表中实时显示下载进度，而非弹 Toast）。
     *
     * @param taskId            本地任务 id
     * @param downloadStatus    null | "downloading" | "completed" | "failed"
     * @param downloadProgress  已下载片段数
     */
    fun updateTaskDownloadStatus(taskId: Long, downloadStatus: String?, downloadProgress: Int) {
        // 读-改-写必须串行：调用方都在 IO 协程里（下完一段回调一次）并发进来，
        // 若各自在 IO 线程「读 _tasks.value → map → postValue」，两条协程会基于同一份旧快照计算，
        // 后 post 的把先 post 的整批覆盖掉 —— 表现为进度回跳、任务卡在 downloading。
        // 切到主线程后每段更新按到达顺序执行，且 .value 的读写不再跨线程。
        viewModelScope.launch(Dispatchers.Main) {
            val current = _tasks.value ?: return@launch
            _tasks.value = current.map { task ->
                if (task.id == taskId) {
                    task.copy(downloadStatus = downloadStatus, downloadProgress = downloadProgress)
                } else {
                    task
                }
            }
        }
    }

    fun updateTaskErrorMessage(taskId: Long, errorMessage: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            taskRepository.updateErrorMessage(taskId, errorMessage)
            loadTasks()
        }
    }

    // 当前打开书籍的行列表
    private val _lines = MutableLiveData<List<LineItem>>()
    val lines: LiveData<List<LineItem>> = _lines

    // 打开书籍失败时给用户看的提示：只打日志不提示会让界面停在空白页，
    // 用户既不知道发生了什么，也没有任何可操作的出口。
    private val _bookOpenError = MutableLiveData<String?>()
    val bookOpenError: LiveData<String?> = _bookOpenError

    // 目录列表
    private val _toc = MutableLiveData<List<TocEntry>>()
    val toc: LiveData<List<TocEntry>> = _toc

    /**
     * 打开一本书的**阶段**，供阅读页显示加载指示。
     *
     * 早先阅读页在正文到达前是一片空白（布局里没有任何 loading 控件），
     * 而 [loadEbookFromFile] 要等「解析正文 → 建位置映射 → 补目录」全做完才
     * 第一次 postValue，于是慢书（尤其是 PDF 要整本上传让服务端解析）
     * 首屏就是长时间白屏 —— 用户既看不到文字，也看不到任何"在做什么"的提示。
     *
     * 拆成两阶段上报后，阅读页可以做到：
     *   [Phase.Resolving] 正文还在解析 —— 显示"正在解析…"；
     *   [Phase.ContentReady] 正文已可读 —— **立刻渲染正文**，隐藏全屏 loading；
     *   [Phase.StructureReady] 位置映射/目录已就绪（正文早就显示完了，不必再遮）。
     *
     * 为什么单独暴露"结构就绪"而不合并进正文：自动续播要靠映射把段号换算成行下标
     * （见 [rowIndexOfSegment]），映射未建好时换算会退回"行号即段号"的旧口径而跳错行。
     * 阅读页因此需要区分"正文到了"和"可以续播了"两个时刻。
     */
    enum class Phase { Resolving, ContentReady, StructureReady }

    private val _phase = MutableLiveData(Phase.ContentReady)
    val phase: LiveData<Phase> = _phase

    /**
     * PDF 本地预览的**逐页进度** `(已解析页数, 总页数)`，null = 当前没有在逐页解析。
     *
     * 只服务于「白屏期间的反馈」：大部头 PDF 本机提取要好几秒
     * （实测国富论 1281 页 / 234 万字，桌面 PDFium 2.7s，手机更慢），
     * 这段时间一条 2dp 的进度条太弱，用户会当成卡死。有页数变化至少说明在动。
     *
     * 生命周期：解析开始前置 null（[Phase.Resolving] 那一步），
     * 解析完成后自然不再更新；阅读页在正文上屏时隐藏提示，不依赖它归零。
     */
    private val _parsePageProgress = MutableLiveData<Pair<Int, Int>?>(null)
    val parsePageProgress: LiveData<Pair<Int, Int>?> = _parsePageProgress

    // 当前播放行的位置（列表索引，-1 表示无）
    private val _currentPlayingPosition = MutableLiveData(-1)
    val currentPlayingPosition: LiveData<Int> = _currentPlayingPosition

    /**
     * 当前播放片段在**它覆盖的每一行**内的字符区间：行下标 → 左闭右开区间。
     *
     * 阅读页显示的是原文，一行里往往压着好几个音频片段（30 字段落 vs 原文一行一段），
     * 只有行下标还不够 —— 有了它才能高亮到「正在读的那一句」。
     *
     * ⚠️ 用整张表而不是单个区间，是因为**一个片段可以横跨两行**
     * （服务端切段时换行不是边界，而阅读页一行即一段）：段起点常常落在上一行行尾，
     * 只给「段首行的那一个区间」，下一行的正文就一点都标不出来。
     * 段不跨行时表里只有一个条目。
     *
     * 无音频（旧口径按行号播放）或该段未定位时为空 Map —— 此时只剩整行底色。
     */
    private val _currentPlayingSpans = MutableLiveData<Map<Int, IntRange>>(emptyMap())
    val currentPlayingSpans: LiveData<Map<Int, IntRange>> = _currentPlayingSpans

    // 收藏列表
    private val _favorites = MutableLiveData<List<Favorite>>()
    val favorites: LiveData<List<Favorite>> = _favorites

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    // ---------- 倍速 / 睡眠定时 ----------
    private val _playbackSpeed = MutableLiveData(PlaybackConfig.DEFAULT_SPEED)
    val playbackSpeed: LiveData<Float> = _playbackSpeed

    /**
     * 睡眠定时状态（单一信号，由服务回调驱动）：
     * >0 = 倒计时剩余毫秒；-1 = 「播完本段」已开启；0 = 关闭
     */
    private val _sleepTimer = MutableLiveData(0L)
    val sleepTimer: LiveData<Long> = _sleepTimer

    // 播放器相关：实际 MediaPlayer 由 PlaybackService（前台服务）持有，
    // ViewModel 仅保留书籍/段落列表与 UI 镜像状态
    // lineList 在 IO 线程（openBook/loadEbookFromFile 等）写入，在主线程
    // （updateCurrentSegment 等）读取；加 @Volatile 保证跨线程可见性，避免主线程读到旧的空列表
    @Volatile
    private var lineList: List<LineItem> = emptyList()

    // currentItem/currentPos 与 lineList 一样跨线程读写：stopIfDifferentBook 在 IO 协程里
    // 把它们复位为「无」，而 PlaybackService 的回调在主线程写真实的高亮位置。
    // 少了 @Volatile 主线程可能一直读到复位前的旧快照，表现为高亮闪回或停在上一行。
    @Volatile
    private var currentItem: LineItem? = null
    @Volatile
    private var currentPos: Int = -1

    // 当前书籍标识（用于播放时查询 audio_segments 表）
    private var currentBookTable: String = ""

    /**
     * 阅读页「原文行 ↔ 音频片段」的位置索引。
     *
     * 阅读页显示的是**电子书原文**，音频片段是另一种粒度（单段 ≤30 字），
     * 两者行边界不对齐 —— 点击某一行时必须靠它换算出「该播第几段」，
     * 播放状态回调也要靠它把段号换回行号。详见 [EbookSegmentMap]。
     *
     * 无本地音频（或两端文本不同源导致定位失败）时为 null，
     * 此时退回旧口径：行号即段号。
     */
    @Volatile
    private var segmentMap: EbookSegmentMap? = null

    // ---------- 书城「在线读」模式 ----------
    // 直接在线阅读书城书（不先下载电子书文件）：正文来自 GET /store/book/{id}/content，
    // 音频按需下载（播到哪下到哪 + 预取后续若干段），不自动加入书架。
    /** 是否处于在线读模式（决定 playLine 走「按需下载后播放」而非直接播本地音频） */
    @Volatile
    private var onlineMode: Boolean = false

    /** 在线读的书城 bookId（-1 = 非在线模式）；按需下载音频时用它定位服务端片段 */
    @Volatile
    private var onlineStoreBookId: Long = -1L

    /**
     * 在线读的书是否**确有合成音频**（服务端片段表非空）。
     * false 时 playLine 不走「按需下载」分支 —— 那种书点读只会拿到空队列，
     * 强行下载必然失败并弹「下载失败」，误导用户；交给既有的无音频路径（系统 TTS）处理。
     */
    @Volatile
    private var onlineAudioReady: Boolean = false

    // ---------- 断点续听 ----------

    /**
     * 纯阅读（无音频）时「读到哪一行」：由阅读页滚动时更新。
     *
     * 进度原先只在**播放**时更新，于是没有音频的书（纯阅读）永远记不住位置：
     * 用户读到哪里、换个终端打开，都只能从头开始。
     */
    @Volatile
    private var readingLineNumber: Int = -1

    // 进度持久化到 SharedPreferences：key 按 currentBookTable 隔离，记录 段落号+段内偏移(ms)
    private val progressPrefs by lazy {
        context.getSharedPreferences("playback_progress", Context.MODE_PRIVATE)
    }
    /** 待恢复的段内偏移：恢复播放某一集时 seekTo 到该位置后清空 */
    private var pendingResumeSegment: Int = -1
    private var pendingResumeOffsetMs: Int = 0

    fun savePlaybackProgress() {
        // 进度落盘由 PlaybackService 负责（它持有 MediaPlayer 与当前段落信息）
        playbackBinder?.service?.saveProgressNow()
    }

    /** 读取某本书的上次播放进度：lineNumber to 段内偏移(ms)，无记录返回 null */
    fun getSavedProgress(bookTable: String): Pair<Int, Int>? {
        val seg = progressPrefs.getInt("seg_$bookTable", -1)
        if (seg <= 0) return null
        return seg to progressPrefs.getInt("off_$bookTable", 0)
    }

    /**
     * 清除某本书的续播位置（音频已被删除时调用）。
     *
     * ## 为什么必须清
     * 续播位置存在 `playback_progress` 这个 SharedPreferences 里（**不在数据库**），
     * 键是 `seg_<书名>` / `off_<书名>`。音频被清理后这些键还在，于是：
     * 重新下载并打开该书 → [getSavedProgress] 返回旧段号 → 自动续播跳回一个
     * 已经不存在的段落；跨端合并时（[resolveResumeProgress]）服务端那份旧进度
     * 也会被拉回来"复活"旧段号。
     *
     * 口径与 [PlaybackService.clearSavedProgress] 一致（同一个 prefs、同样的键）。
     */
    fun clearSavedProgress(bookName: String) {
        progressPrefs.edit()
            .remove("seg_$bookName")
            .remove("off_$bookName")
            .apply()
        Log.d(TAG, "已清除《$bookName》的续播位置（音频已清理）")
    }

    /**
     * 清除**全部**书的续播位置（"全部清理音频"时调用）。
     *
     * 只删 `seg_` / `off_` 前缀的键：这个 prefs 里可能还有别的键，
     * 一把 removeAll 会把它们一起清掉。
     */
    fun clearAllSavedProgress() {
        val editor = progressPrefs.edit()
        var removed = 0
        for (key in progressPrefs.all.keys) {
            if (key.startsWith("seg_") || key.startsWith("off_")) {
                editor.remove(key)
                removed++
            }
        }
        editor.apply()
        Log.d(TAG, "已清除全部续播位置（$removed 个键，音频已全部清理）")
    }

    // ---------- 跨端阅读进度（bookKey 口径） ----------

    /** 记录纯阅读时「读到哪一行」（阅读页滚动时更新）。 */
    fun setReadingLineNumber(lineNumber: Int) {
        readingLineNumber = lineNumber
    }

    /**
     * 行下标 → 段号（1-based）。
     *
     * 有映射（[EbookSegmentMap]）时用映射换算；无映射（纯文本书）时**行号即段号**
     * （旧口径，服务端 /content 的 paragraphs 下标与之一致）。
     */
    fun segmentNumberOfLine(lineIndex: Int): Int {
        val list = lineList
        if (lineIndex < 0 || lineIndex >= list.size) return -1
        val lineNumber = list[lineIndex].lineNumber
        val map = segmentMap
        if (map != null) {
            val seg = map.segmentOfRow(lineIndex)
            if (seg > 0) return seg
        }
        return lineNumber
    }

    /**
     * 保存纯阅读时的阅读位置（本地 + 跨端）。
     *
     * 本地落 SharedPreferences（与播放进度同一份表，键仍是书名），
     * 保证离线时也能恢复；跨端按 bookKey 上报，三端互通。
     */
    fun saveReadingProgress(bookTable: String, segment: Int, offsetMs: Int) {
        if (segment <= 0) return
        // 本地落盘：与播放进度**同一份** prefs（键 = "seg_<书名>" / "off_<书名>"），
        // 这样「有没有播过」与「读到哪」用同一把钥匙，恢复逻辑只有一处。
        progressPrefs.edit()
            .putInt("seg_$bookTable", segment)
            .putInt("off_$bookTable", offsetMs)
            .apply()
        val bookKey = serverBookKey(
            if (onlineStoreBookId > 0) onlineStoreBookId else null,
            currentServerTaskId(),
            bookTable
        )
        uploadBookProgress(bookKey, segment, offsetMs)
    }

    /** 当前书的服务端任务 id（没有则 -1）。 */
    private fun currentServerTaskId(): Long? {
        val name = currentBookTable
        if (name.isEmpty()) return null
        return try {
            taskRepository.findServerTaskIdByBookName(name)
        } catch (e: Exception) {
            null
        }
    }


    /**
     * 当前这本书的**跨端统一身份键**（`s:`/`t:`/`n:`）。
     *
     * 与有声覆盖图同一套口径（[CoverageCodec.bookKey]）—— 三端对同一本书算出同一个 key，
     * 进度才能互通。服务端曾以 `task_id` 作进度身份，但：① 未合成的书没有 task_id，
     * 纯阅读的位置无处可存；② 书城书的音频常由管理员合成，普通用户调私有任务端点会 403。
     */
    fun serverBookKey(storeBookId: Long?, serverTaskId: Long?, displayName: String?): String =
        CoverageCodec.bookKey(storeBookId, serverTaskId, displayName)

    /**
     * 上报跨端阅读进度（按 bookKey 身份）。
     *
     * best-effort：失败只记日志，不影响本地阅读（离线时由本地 prefs 兜底）。
     */
    fun uploadBookProgress(bookKey: String, segment: Int, offsetMs: Int) {
        if (bookKey.isEmpty() || segment <= 0) return
        val token = AuthManager.token
        if (token.isNullOrEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val body = mapOf(
                    "bookKey" to bookKey,
                    "segment" to segment,
                    "offsetMs" to offsetMs
                )
                val response = RetrofitClient.apiService.putBookProgress(body)
                if (!response.isSuccessful) {
                    Log.w(TAG, "上报跨端阅读进度失败: HTTP ${response.code()}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "上报跨端阅读进度异常: ${e.message}")
            }
        }
    }

    /**
     * 拉取跨端阅读进度（按 bookKey 身份），供阅读页恢复位置。
     *
     * @param onResult 回调 (segment, offsetMs)；无进度或失败时回调 null
     */
    fun fetchBookProgress(
        bookKey: String,
        onResult: (Pair<Int, Int>?) -> Unit
    ) {
        if (bookKey.isEmpty()) {
            onResult(null)
            return
        }
        val token = AuthManager.token
        if (token.isNullOrEmpty()) {
            onResult(null)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var result: Pair<Int, Int>? = null
            try {
                val response = RetrofitClient.apiService.getBookProgress(bookKey)
                if (response.isSuccessful) {
                    val body = response.body()
                    @Suppress("UNCHECKED_CAST")
                    if (body?.get("success") == true && body["hasProgress"] == true) {
                        val seg = (body["segment"] as? Number)?.toInt() ?: 0
                        val off = (body["offsetMs"] as? Number)?.toInt() ?: 0
                        if (seg > 0) result = seg to off
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "拉取跨端阅读进度失败: ${e.message}")
            }
            onResult(result)
        }
    }

    /**
     * 跨端进度合并：取本地进度与服务端进度中"更靠后"的一份，用于自动续播。
     *
     * 仅当该书已关联服务端任务（serverTaskId > 0）且已登录时才拉取服务端进度；
     * 否则直接使用本地进度。合并规则：段号更大优先，段号相同则偏移更大优先。
     *
     * 结果通过 [onResult] 回调（运行在 IO 线程，调用方需自行切回主线程操作 UI）。
     * 本地与服务端均无进度时回调 null（不自动续播）。
     */
    /**
     * 跨端进度合并（**bookKey 口径**）：取本地进度与服务端进度中"更靠后"的一份，用于自动续播。
     *
     * 与 [resolveResumeProgress] 的区别：那个走**私有任务**端点（`/api/progress?taskId=`），
     * 服务端按任务归属校验 —— 书城书的音频常由管理员合成 ⇒ 普通用户必然 403，
     * 403 又被静默吞掉，表现为「断点续播莫名失效」；而且未合成的书根本没有 task_id，
     * 纯阅读的位置完全无处可存。本方法走**书口径**端点（`/api/book-progress`），
     * 身份是跨端统一的 bookKey ⇒ 三端能认出同一本书，无音频的书也能记位置。
     *
     * @param bookTable 本机进度主键（与 getSavedProgress 一致）
     * @param bookKey   跨端身份键（s:/t:/n:）
     * @param onResult  (segment, offsetMs)；两侧均无进度时回调 null
     */
    fun resolveResumeProgressByKey(
        bookTable: String,
        bookKey: String,
        onResult: (Pair<Int, Int>?) -> Unit
    ) {
        val local = getSavedProgress(bookTable)
        if (bookKey.isEmpty()) {
            onResult(local)
            return
        }
        val token = AuthManager.token
        if (token.isNullOrEmpty()) {
            onResult(local)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var merged = local
                val response = RetrofitClient.apiService.getBookProgress(bookKey)
                if (response.isSuccessful) {
                    val body = response.body()
                    @Suppress("UNCHECKED_CAST")
                    if (body?.get("success") == true && body["hasProgress"] == true) {
                        val seg = (body["segment"] as? Number)?.toInt() ?: 0
                        val off = (body["offsetMs"] as? Number)?.toInt() ?: 0
                        if (seg > 0) {
                            val server = seg to off
                            merged = when {
                                local == null -> server
                                // 取"更靠后"：段号更大，或段号相同且偏移更大
                                seg > local.first ||
                                    (seg == local.first && off > local.second) -> server
                                else -> local
                            }
                        }
                    }
                }
                onResult(merged)
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ 获取跨端进度失败，回退本地: ${e.message}")
                onResult(local)
            }
        }
    }

    fun resolveResumeProgress(
        bookTable: String,
        serverTaskId: Long,
        onResult: (Pair<Int, Int>?) -> Unit
    ) {
        val local = getSavedProgress(bookTable)
        val token = AuthManager.token
        if (token.isNullOrEmpty()) {
            onResult(local)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var merged = local
                val response = RetrofitClient.apiService.getProgress(serverTaskId)
                if (response.isSuccessful) {
                    val body = response.body()
                    @Suppress("UNCHECKED_CAST")
                    if (body?.get("success") == true && body["hasProgress"] == true) {
                        val seg = (body["segment"] as? Number)?.toInt() ?: 0
                        val off = (body["offsetMs"] as? Number)?.toInt() ?: 0
                        if (seg > 0) {
                            val server = seg to off
                            merged = when {
                                local == null -> server
                                // 取"更靠后"：段号更大，或段号相同且偏移更大
                                seg > local.first ||
                                    (seg == local.first && off > local.second) -> server
                                else -> local
                            }
                        }
                    }
                }
                onResult(merged)
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ 获取服务端进度失败，回退本地: ${e.message}")
                onResult(local)
            }
        }
    }

    // ---------- 播放服务（后台播放 + 通知栏控制）----------
    // MediaPlayer、音频焦点、拔耳机暂停、自动连播均在 PlaybackService 中实现，
    // ViewModel 通过 Binder 控制播放，并监听状态回调驱动 UI 高亮。
    private var playbackBinder: PlaybackService.PlaybackBinder? = null

    // 服务连接尚未建立时用户发起的播放请求，先挂起，连接建立后立即执行
    private var pendingPlayAction: (() -> Unit)? = null

    private val playbackListener = object : PlaybackService.Listener {
        override fun onStateChanged(isPlaying: Boolean, segmentNumber: Int) {
            // 高亮只对「当前打开的这本书」成立：服务可能在后台播另一本书，
            // 而「段号 → 行号」的换算依赖本书的行列表与位置映射，
            // 拿别的书的段号来算会高亮到随机一行（比不高亮更误导）。
            val service = playbackBinder?.service
            val sameBook = service != null && service.currentBookName() == currentBookTable
            val pos = if (segmentNumber > 0 && currentBookTable.isNotEmpty() && sameBook) {
                rowIndexOfSegment(segmentNumber)
            } else {
                -1
            }
            currentPos = pos
            currentItem = if (pos >= 0) lineList.getOrNull(pos) else null
            // ⚠️ 这里给的是「行下标 → 该行内区间」的**整张表**，不是单个区间：
            // 片段横跨两行时（换行不是服务端的段边界），要按行分片各涂一段，
            // 只给段首行那一个区间的话，正在念的下一行正文一点都标不出来。
            _currentPlayingSpans.postValue(if (pos >= 0) spansOfSegment(segmentNumber) else emptyMap())
            _currentPlayingPosition.postValue(pos)
            _isPlaying.postValue(isPlaying)
            // 在线模式：段落推进时把预取窗口向前滑动，使下载始终领先播放位置若干段（连播无缝）。
            // 只在「同一本书 + 有效段号」时武装，避免后台播别的书时误下本书音频。
            if (onlineMode && onlineStoreBookId > 0 && sameBook && segmentNumber > 0) {
                StoreAudioDownloadManager.prefetchOnlineAhead(
                    onlineStoreBookId, currentBookTable, segmentNumber + 1, ONLINE_PREFETCH_WINDOW
                )
            }
        }

        override fun onPlaybackEnded() {
            currentPos = -1
            currentItem = null
            _currentPlayingSpans.postValue(emptyMap())
            _currentPlayingPosition.postValue(-1)
            _isPlaying.postValue(false)
        }

        override fun onSleepTimerTick(remainingMs: Long) {
            // >0 倒计时剩余；-1 「播完本段」已开启；0 关闭/已触发
            _sleepTimer.postValue(remainingMs)
        }

        override fun onPlaybackSpeedChanged(speed: Float) {
            _playbackSpeed.postValue(speed)
        }

        override fun onAwaitingDownload(waiting: Boolean) {
            // 只在「开始等待」时提示一次：书城音频边下边听时，已下载的段播完而下一段还没到，
            // 用户看到的是「突然没声音」——给一句说明，比让他以为卡死强
            if (waiting) {
                Toast.makeText(
                    getApplication(),
                    "已播放到已下载的部分，正在等待后续音频…",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 书城音频「又下好一段」的回调：把「段号 ↔ 原文行」的映射重建一次。
     *
     * 映射是用**本地已有的片段文本**在原文里定位出来的（见 [EbookSegmentMap.build]），
     * 所以段落一边下、映射一边变长：不重建的话，用户点到刚下好的那句会被告知
     * 「这段文字没有对应的音频」，而其实音频就在库里。
     */
    private val storeAudioSegmentListener = StoreAudioDownloadManager.SegmentListener { book, _ ->
        // 在线模式：段号↔行号映射在打开书时就用服务端**完整**片段表建好了（不随下载变化）。
        // 这里若按「本地已下载片段」重建，反而会把映射缩到已下载的子集，
        // 使尚未下载的行点读被误判为「这段没有音频」。故在线模式跳过重建（映射恒为完整）。
        if (book == currentBookTable && currentBookTable.isNotEmpty() && !onlineMode) {
            // 通知 Activity：本书有音频段到达（书城下载的书没有 task 行，
            // Activity 的 bookHasAudio 原本只从 tasks 推导，需要这个信号触发重查）。
            _storeAudioArrived.postValue(book)
            viewModelScope.launch(Dispatchers.IO) {
                val rows = lineList
                if (rows.isEmpty()) return@launch
                val segments = loadSegmentsFromAudioTable(book)
                segmentMap = EbookSegmentMap.build(
                    rows.map { it.text },
                    segments.map { it.lineNumber to it.text }
                )
                Log.d(
                    TAG,
                    "书城音频新增片段，重建位置映射: $book → ${segments.size} 段" +
                        "（定位成功 ${segmentMap?.locatedCount ?: 0} 段" +
                            "，锚点估算 ${segmentMap?.estimatedCount ?: 0} 段）"
                )
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val b = service as? PlaybackService.PlaybackBinder ?: return
            playbackBinder = b
            b.service.addListener(playbackListener)
            // 同步一次倍速/睡眠定时现状（addListener 只回放播放状态）
            _playbackSpeed.postValue(b.service.getPlaybackSpeed())
            val mode = b.service.getSleepTimerMode()
            _sleepTimer.postValue(
                when {
                    mode > 0 -> b.service.getSleepTimerRemainingMs()
                    mode == PlaybackService.SLEEP_TIMER_END_OF_SEGMENT -> -1L
                    else -> 0L
                }
            )
            // 执行连接建立前挂起的播放请求
            pendingPlayAction?.invoke()
            pendingPlayAction = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackBinder = null
        }
    }

    init {
        loadBooks()
        // 建表（repository 的建表方法是 suspend，必须在协程内调用；
        // 且要等建表完成后再加载，并发 fire-and-forget 会有表未建好就查询的竞态）
        viewModelScope.launch(Dispatchers.IO) {
            bookStorage.createEbookTableIfNeeded()
            taskRepository.createTableIfNeeded()
            bookStorage.createAudioSegmentsTableIfNeeded()
            favoriteRepository.createTableIfNeeded()
            loadEbooks()
            loadTasks()
        }
        // 书架聚合：ebooks 或 tasks 任一变化都重新合并
        _shelfEntries.addSource(_ebooks) { mergeShelf() }
        _shelfEntries.addSource(_tasks) { mergeShelf() }
        // 接入进程级轮询单例：多个 ViewModel 实例共享同一条轮询线程
        TaskPollingManager.attach(context, pollingListener)
        // 书城音频边下边听：新段落库要重建位置映射（见 storeAudioSegmentListener 注释）。
        // 下载器是进程级单例，必须在这里登记、在 onCleared 注销，否则它会一直持有本 ViewModel。
        StoreAudioDownloadManager.addSegmentListener(storeAudioSegmentListener)
        // 任务表变更通知（进程级）：本机合成在 Service 里写库、在线合成在轮询线程里写库，
        // 这两处都不会替我们刷新书架。少了这个监听，书架上的合成状态只能等用户
        // 切回书架（onResume）才更新 —— 整本书合成完了，界面还停在"合成中"。
        TaskRepository.addTasksChangedListener(tasksChangedListener)
        // 绑定播放服务：同步播放状态、控制播放（服务在真正播放时才进入前台）
        context.bindService(
            Intent(context, PlaybackService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    fun loadEbooksForSelection() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ebooks = bookStorage.getEbooksForSelection()
                _ebooks.postValue(ebooks)
                Log.d(TAG, "Loaded ${ebooks.size} ebooks for selection")
            } catch (e: Exception) {
                Log.e(TAG, "Load ebooks for selection failed: ${e.message}")
            }
        }
    }

    // 加载所有书籍（表名）
    fun loadBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            val books = bookStorage.getBookTables()
            _books.postValue(books)
            Log.d(TAG, "Loaded ${books.size} books")
        }
    }

    fun loadEbooks() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ebooks = bookStorage.getEbooks()
                _ebooks.postValue(ebooks)
                Log.d(TAG, "Loaded ${ebooks.size} ebooks")
            } catch (e: Exception) {
                Log.e(TAG, "Load ebooks failed: ${e.message}")
            }
        }
    }

    /**
     * 写回书架顺序（长按拖放结束后调用）。
     *
     * @param idsInOrder 从上到下（屏幕上第 1 个在前）的书籍 id
     *
     * 顺序是**相对值**，所以整本书架都要重写一遍（不是只写被拖的那几本），
     * 用一个事务一次写完，见 [BookStorage.updateEbookOrders]。
     *
     * 写完必须重新 [loadEbooks]：列表的排序依据在数据库里，
     * 不刷新的话下次进页面顺序又回去了。
     */
    fun reorderShelfBooks(idsInOrder: List<Int>) {
        if (idsInOrder.size < 2) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                bookStorage.updateEbookOrders(assignShelfSortOrders(idsInOrder))
                loadEbooks()
            } catch (e: Exception) {
                Log.e(TAG, "写回书架顺序失败: ${e.message}")
            }
        }
    }

    suspend fun checkHasAudio(bookName: String): Boolean = bookStorage.checkHasAudio(bookName)

    /**
     * 把已下载到 filesDir/ebooks/ 的文件登记进书架（书城下载后调用）。
     * 文件已由调用方落盘，这里只负责插库 + 刷新，并在重复时跳过。
     *
     * @param fileName    本地落盘文件名（含扩展名）。书城文件的物理名带服务端防重名的
     *                    随机码前缀，它只用于定位磁盘文件，**不作书名**
     * @param displayName 书架展示名（书城下载传原始书名）；缺省沿用文件名，
     *                    与手动导入、URL 导入的行为保持一致
     */
    fun importEbookFromFile(fileName: String, displayName: String = fileName) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 清洗文件名（防路径穿越）
                val safeName = FileUtils.sanitizeFileName(fileName)
                val file = File(File(context.filesDir, "ebooks"), safeName)
                if (!file.exists()) {
                    Log.e(TAG, "importEbookFromFile: 文件不存在 ${file.absolutePath}")
                    return@launch
                }
                // 去重：同文件路径已存在则跳过
                if (bookStorage.existsByFilePath(file.absolutePath)) {
                    Log.d(TAG, "书架已存在，跳过入库: $displayName")
                    loadEbooks()
                    return@launch
                }
                // 同名书曾被删除过的话，撤销进程内黑名单，否则后续音频片段会被静默丢弃
                bookStorage.unmarkBookDeleted(displayName)
                bookStorage.insertEbookRecord(displayName, file.absolutePath)
                Log.d(TAG, "书城书籍已入库: $displayName (file=$safeName)")
                loadEbooks()
            } catch (e: Exception) {
                Log.e(TAG, "importEbookFromFile failed: ${e.message}")
            }
        }
    }

    fun importEbook(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = importFromUri(uri, "本地")
            if (result.imported) loadEbooks()
            withContext(Dispatchers.Main) {
                // 导入成功不弹提示（2026-10-20 需求）：书架随后自动刷新，新书直接出现在列表里。
                // 失败仍必须提示——静默失败会让用户以为导入了。
                if (result.errorMsg != null) {
                    Toast.makeText(getApplication(), result.errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 核心导入逻辑（本地文件 / 书城下载 / URL 导入共用）。
     *
     * 失败时会给出**具体原因**而非笼统的「失败」：
     * - 格式不支持：扩展名不在白名单
     * - 文件过大：超过 [FileUtils.MAX_IMPORT_BYTES]
     * - 文件损坏：txt 含大量空字节 / 不可打印字符，epub 不是 ZIP 头，pdf 不是 %PDF，mobi 不是 BOOK 头
     *
     * @param uri        待导入内容的 Uri（本地文档 / content provider）
     * @param category   分类标签（本地导入=「本地」，书城下载=「书城」，URL 导入=「URL」）
     */
    private suspend fun importFromUri(uri: Uri, category: String): ImportResult {
        val contentResolver = context.contentResolver
        val name = uri.getFileName(context)
        if (name.isNullOrBlank()) {
            return ImportResult(false, "无法读取文件名，文件可能已失效", null)
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_IMPORT_EXTENSIONS) {
            return ImportResult(
                false,
                "不支持的文件格式「$ext」，仅支持 txt / epub / pdf / mobi",
                name
            )
        }

        val cacheDir = File(context.filesDir, "ebooks")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val destinationFile = try {
            FileUtils.safeChildFile(cacheDir, name)
        } catch (se: SecurityException) {
            Log.w(TAG, "拒绝可疑文件名: $name", se)
            return ImportResult(false, "文件名包含非法字符，导入被拒绝", name)
        }

        // 去重前置：先判断，只有确实重复【且】文件还在时才跳过。
        val dbHasRecord = bookStorage.existsByFilePath(destinationFile.absolutePath)
        if (dbHasRecord && destinationFile.exists()) {
            Log.d(TAG, "电子书已存在，跳过入库: $name")
            return ImportResult(false, "图书「$name」已在书架中，未重复导入", name)
        }

        val inputStream = contentResolver.openInputStream(uri)
        if (inputStream == null) {
            return ImportResult(false, "无法读取文件，请确保文件未被删除", name)
        }
        try {
            // 限流 + 临时文件中转：写超上限立即中止，失败不留残 file。
            // saveToWithLimit 内部会在超限时抛 IOException（message 含「过大」）。
            inputStream.saveToWithLimit(destinationFile)
        } catch (e: IOException) {
            val msg = if (e.message?.contains("过大") == true) {
                // MAX_IMPORT_BYTES 是 FileUtils.kt 的**顶层** const（不在 object FileUtils 内），
                // 同包下直接引用即可，写成 FileUtils.MAX_IMPORT_BYTES 会编译失败。
                "文件过大，已超过 ${MAX_IMPORT_BYTES / 1024 / 1024}MB 上限，请选更小的文件"
            } else {
                "文件读取失败：${e.message}"
            }
            return ImportResult(false, msg, name)
        } catch (e: Exception) {
            return ImportResult(false, "导入失败：${e.message}", name)
        }

        // 文件已落盘：校验是否损坏。
        // 校验未过就删掉落盘文件，避免磁盘上留下坏文件（DB 记录仍可重新触发导入）。
        val corruptReason = validateEbookFile(destinationFile, ext)
        if (corruptReason != null) {
            destinationFile.delete()
            return ImportResult(false, corruptReason, name)
        }

        if (dbHasRecord) {
            // 记录还在、文件丢了：补回文件即可，无需再插一条
            Log.d(TAG, "补回丢失的书籍文件: $name")
        } else {
            // 同名书曾被删除过的话，撤销进程内黑名单，否则后续音频片段会被静默丢弃
            bookStorage.unmarkBookDeleted(destinationFile.name)
            bookStorage.insertEbookRecord(destinationFile.name, destinationFile.absolutePath, category)
            Log.d(TAG, "Ebook imported: $name (category=$category)")
        }
        return ImportResult(true, null, name)
    }

    /**
     * 校验落盘后的电子书是否损坏。
     * 仅对本地可判定的格式做头/内容探测：
     *  - txt：不允许大量 NUL 字节或不可打印字符（二进制文件伪装成 txt）；
     *  - epub：必须是 ZIP 头（PK\x03\x04）；
     *  - pdf：必须以 %PDF 开头；
     *  - mobi：必须是 PalmDB 容器，且偏移 60 的类型为 BOOK 或偏移 64 的创建者为 MOBI
     *    （**不是**文件开头 —— 开头是数据库名，见下方注释）。
     * @return 损坏原因文案；文件正常返回 null
     */
    private fun validateEbookFile(file: File, ext: String): String? {
        if (!file.exists() || file.length() == 0L) {
            return "文件为空或损坏，无法导入"
        }
        return when (ext) {
            "txt" -> {
                val head = readHeadBytes(file, 8192)
                if (head.isEmpty()) return "文件为空或损坏，无法导入"
                // 文本文件（含中文 UTF-8）几乎不会含 NUL 字节；大量 NUL 是二进制/编码错乱的强信号。
                // 注意：不能用「不可打印字符比例」判断——中文 UTF-8 多字节的高位字节是有符号负值，
                // 会被误判成「不可打印」，从而把正常的中国书当成损坏文件。故只认 NUL 主导 + 全控制字符两种信号。
                val nulls = head.count { it == 0.toByte() }
                if (nulls > head.size / 4) return "文件内容疑似损坏（含大量空字节），无法导入"
                // 可识别文本 = 可打印 ASCII / 空白 / 或任意高位字节（UTF-8 多字节，如中文）
                val hasPrintable = head.any { b ->
                    b >= 0x20.toByte() || b == '\t'.code.toByte() || b == '\n'.code.toByte()
                        || b == '\r'.code.toByte() || b < 0
                }
                if (!hasPrintable) return "文件内容疑似损坏（无可识别文本），无法导入"
                null
            }
            "epub" -> {
                val magic = readHeadBytes(file, 4)
                if (magic.size >= 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
                    && magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
                ) null else "文件并非有效的 EPUB（应为 ZIP 格式），无法导入"
            }
            "pdf" -> {
                val head = readHeadBytes(file, 5)
                if (head.toString(Charsets.US_ASCII).startsWith("%PDF")) null
                else "文件并非有效的 PDF，无法导入"
            }
            "mobi" -> {
                // MOBI 是 Palm Database（PDB）容器，格式标识**不在文件开头**：
                //   偏移 60..63 = 类型（"BOOK"），偏移 64..67 = 创建者（"MOBI"）；
                //   而 0..31 是**数据库名**，由作者随手起（"Tolkien"、"Test"、甚至中文）。
                // 早期这里读了前 4 字节去跟 "BOOK" 比 —— 于是**任何真实 MOBI 都被判为无效**，
                // 表现为「文件并非有效的 MOBI，无法导入」，而文件本身完全正常。
                val head = readHeadBytes(file, 68)
                val type = if (head.size >= 64) String(head, 60, 4, Charsets.US_ASCII) else ""
                val creator = if (head.size >= 68) String(head, 64, 4, Charsets.US_ASCII) else ""
                if (type == "BOOK" || creator == "MOBI") null
                else "文件并非有效的 MOBI（缺少 BOOK/MOBI 标识），无法导入"
            }
            else -> null
        }
    }

    /** 读取文件前 [max] 字节（上限 8KB，不整文件入堆，避免 OOM）。 */
    private fun readHeadBytes(file: File, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        file.inputStream().buffered().use { st ->
            val buf = ByteArray(4096)
            var total = 0
            while (total < max) {
                val r = st.read(buf, 0, min(max - total, buf.size))
                if (r < 0) break
                out.write(buf, 0, r)
                total += r
            }
        }
        return out.toByteArray()
    }

    /**
     * 从 URL 导入电子书。
     *
     * 纯客户端实现：下载 http/https 链接到 filesDir/ebooks/，再复用本地导入的
     * 校验与入库逻辑（含格式/损坏/大小校验）。不改动 Server —— 避免让 Server
     * 去 fetch 任意 URL 引入 SSRF 风险，下载在用户设备上进行。
     */
    fun importFromUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = downloadAndImportFromUrl(url)
            if (result.imported) loadEbooks()
            withContext(Dispatchers.Main) {
                // 导入成功不弹提示（2026-10-20 需求）：书架随后自动刷新，新书直接出现在列表里。
                // 失败仍必须提示——静默失败会让用户以为导入了。
                if (result.errorMsg != null) {
                    Toast.makeText(getApplication(), result.errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun downloadAndImportFromUrl(url: String): ImportResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || (!trimmed.startsWith("http://", ignoreCase = true)
                && !trimmed.startsWith("https://", ignoreCase = true))
        ) {
            return ImportResult(false, "仅支持 http / https 链接", null)
        }

        // 从 URL path 取文件名；无扩展名时按 txt 兜底
        val rawName = try {
            URL(trimmed).path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "book_from_url"
        } catch (e: Exception) {
            return ImportResult(false, "链接格式不正确", null)
        }
        val name = if (rawName.contains('.')) rawName else "$rawName.txt"
        val safeName = FileUtils.sanitizeFileName(name)

        val ext = safeName.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_IMPORT_EXTENSIONS) {
            return ImportResult(
                false,
                "不支持的文件格式「$ext」，仅支持 txt / epub / pdf / mobi",
                safeName
            )
        }

        val cacheDir = File(context.filesDir, "ebooks")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val dest = File(cacheDir, safeName)

        val downloaded = downloadUrlToFile(trimmed, dest)
        if (!downloaded) {
            dest.delete()
            return ImportResult(false, "下载失败，请检查链接或网络后重试", safeName)
        }

        val corruptReason = validateEbookFile(dest, ext)
        if (corruptReason != null) {
            dest.delete()
            return ImportResult(false, corruptReason, safeName)
        }

        if (bookStorage.existsByFilePath(dest.absolutePath)) {
            return ImportResult(false, "图书「$safeName」已在书架中，未重复导入", safeName)
        }
        bookStorage.unmarkBookDeleted(dest.name)
        bookStorage.insertEbookRecord(dest.name, dest.absolutePath, "URL")
        Log.d(TAG, "Ebook imported from URL: $safeName")
        return ImportResult(true, null, safeName)
    }

    /** 用 HttpURLConnection 下载（带超时 + 200MB 上限），成功返回 true。 */
    private suspend fun downloadUrlToFile(url: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            // v5/A1：成功路径与 catch 路径原先都不 disconnect，连接只能靠 GC 回收。
            // conn 在 877 行声明，这里在它**之后**套 try/finally 即可（finally 里可见 conn）。
            try {
                if (conn.responseCode !in 200..299) {
                    Log.e(TAG, "下载 URL 书籍失败: HTTP ${conn.responseCode}")
                    return@withContext false
                }
                conn.inputStream.use { input -> input.saveToWithLimit(dest) }
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载 URL 书籍异常: ${e.message}")
            false
        }
    }

    /** 标记一本书被打开（写入最近阅读时间，供书架「最近阅读」排序）。 */
    fun markBookOpened(displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookStorage.updateLastReadAt(displayName)
            loadEbooks()
        }
    }

    /** 修改某本书的分类标签（用户在书架手动改分类时调用）。 */
    fun setBookCategory(displayName: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookStorage.updateCategory(displayName, category)
            loadEbooks()
        }
    }

    fun deleteEbook(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bookName = book.displayName

                // 事务内级联清理孤儿数据：音频片段 + 收藏 + 任务 + 动态内容表。
                // 只含 DB 部分；抛异常表示整事务回滚（ebooks 行也没删），由下面的 catch 兜住
                val cascade = bookStorage.deleteBookCascade(book)
                val taskRefs = cascade.taskRefs // (localTaskId, serverTaskId)

                // 删除本地电子书文件（事务提交后执行；失败不影响 DB 一致性，但需提示用户）
                val file = File(book.filePath)
                var fileDeleted = true
                if (file.exists()) {
                    fileDeleted = file.delete()
                    if (!fileDeleted) {
                        Log.w(TAG, "本地文件删除失败（可能被占用）: ${file.absolutePath}")
                    }
                }

                // 清理已删任务行的轮询状态，避免 TaskPollingManager 内部 Map 无限增长
                taskRefs.forEach { (localId, _) -> TaskPollingManager.forgetTask(localId) }

                // 登记删除黑名单：阻止在途下载把音频片段插回、阻止 ensureEbookRegistered
                // 把删除失败的书重新登记回书架（本进程内有效即可，重启后下载循环已不存在）
                bookStorage.markBookDeleted(bookName)

                // 若这本书来自书城（storeBookId > 0），本地级联删除后服务端书架
                // （shelf_books）里仍可能留有该书的在架记录。冷启动时 syncShelfFromServer
                // 以服务端为真源，会把这本书重新建成「在线占位条目」——表现为
                // 「删除后再次打开应用，书又回到书架」。这里 best-effort 通知服务端
                // 移出书架；失败只打日志（未登录/离线/服务端异常时不阻塞本地删除，
                // 下次进书架页若服务端仍记录在架会再对齐，但至少本地删除这一动作
                // 不再静默漏掉书架移除）。
                if (book.storeBookId > 0L) {
                    shelfSyncManager.removeFromShelfRemote(book.storeBookId)
                }

                // 尽力取消服务端任务（排队中任务直接取消并退积分；合成中的任务服务端拒绝
                // 删除，失败重试一次后并入提示——取消不掉的任务会继续合成并扣积分，用户需要知情）
                val cancelServerFailed = cancelServerTasksBestEffort(taskRefs.map { it.second })

                Log.d(TAG, "Ebook deleted: $bookName")
                loadEbooks()
                loadBooks()
                loadTasks()

                val problems = buildList {
                    if (!fileDeleted) add("本地文件删除失败")
                    if (cancelServerFailed) add("部分服务端任务未能取消，请到任务列表确认")
                    // 书架里还有同名书时级联清理被整个跳过：那些任务/音频/收藏
                    // 分不清归属，不能删，必须让用户知道它们还留在库里
                    if (cascade.skippedCascade) add("书架中还有同名书籍，其任务与音频未一并删除")
                }
                if (problems.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            getApplication(),
                            "图书已从书架移除，但${problems.joinToString("、")}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                // 只打日志、不提示、不刷新会让用户点了删除书却还在架上：
                // 这里区分 SQLiteException 给出更明确的文案，并刷新书架回到一致状态
                Log.e(TAG, "Delete ebook failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    val msg = if (e is android.database.SQLException) {
                        "删除书籍失败：数据库错误，请重试"
                    } else {
                        "删除书籍失败：${e.message ?: "未知错误"}"
                    }
                    Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
                }
                loadEbooks()
            }
        }
    }

    // ---------- 书城书架跨端同步（服务端为真源，本机走「在线占位条目」承接） ----------

    /**
     * 从服务端拉一次书架并对账本地。进书架页 / 下拉刷新 / 书城下载入库后调。
     *
     * 只在**同步成功**且**有实际变化**（新增/清理 > 0）时才触发 [loadEbooks]：
     *  - 失败保本地，避免网络抖动让书架闪一下"少了一堆书"；
     *  - 完全无变化时不刷新，防止每次进页都无谓重排。
     * 回调在主线程，Fragment 可直接 Toast/更新 UI。
     */
    fun syncShelfFromServer(callback: ((ShelfSyncResult) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = shelfSyncManager.syncFromServer()
            if (result.ok && (result.added > 0 || result.removed > 0)) {
                loadEbooks()
            }
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /**
     * 每书元数据跨端同步（sortOrder / lastReadAt，身份 = bookKey）。
     *
     * 与 [syncShelfFromServer] 风格一致：进书架页时触发，服务端权威值静默落到 ebooks 两列、
     * 影子快照同步前移（不产生二次上报）。远端值确实写库后刷新一次书架，
     * 让跨端改过的排序 / 最近阅读能显示出来；失败 / 未登录静默保本地。
     * ⛔ 绝不同步 category。
     */
    fun syncBookMeta() {
        UserSettingsSyncManager.syncBookMetaAsync(context) { _ -> loadEbooks() }
    }

    /**
     * 有声覆盖图跨端同步（Phase3）。进书架页时与 [syncShelfFromServer] 一起触发。
     *
     * 语义（镜像 PC `sync._sync_audio_coverage`）：本机有音频覆盖 → POST 上报并集；本机无音频
     * → GET 拉全量并集；无论如何都重建本地 audio_coverage 缓存。**纯感知、无护栏**（不删本机音频）。
     * 未登录 / 网络失败 → 保留本地缓存不重建（徽章沿用上次结果）。同步后（或失败后）都刷新徽章，
     * 让离线时也能基于既有缓存显示。
     */
    fun syncAudioCoverageFromServer(callback: ((AudioCoverageSyncResult) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = coverageSyncManager.syncFromServer()
            val badges = buildCoverageBadges()
            withContext(Dispatchers.Main) {
                _coverageBadges.value = badges
                callback?.invoke(result)
            }
        }
    }

    /**
     * 仅依据本地缓存重算书架徽章（不发网络）。书架数据变化（导入/删除/合成落库）后调用，
     * 保证「本机有声」这档能即时反映到界面上，而不必等一次跨端同步。
     */
    fun refreshCoverageBadges() {
        viewModelScope.launch(Dispatchers.IO) {
            val badges = buildCoverageBadges()
            withContext(Dispatchers.Main) { _coverageBadges.value = badges }
        }
    }

    /**
     * 组装 book.id → [ShelfBadge]。三个数据源都来自本地：
     *  - [BookStorage.getShelfBookKeyMap] 每本（真实 ebooks 行）的 bookKey；
     *  - [BookStorage.getLocalPlayableBookKeySet] 本机确有字节的 bookKey（绿档）；
     *  - [BookStorage.getAllAudioCoverage] 服务端权威并集视图缓存（蓝/橙档）。
     *
     * 虚拟条目（仅有任务、无 ebooks 行）不在此表 → 不显示徽章（其任务行已有自己的状态展示）。
     */
    private suspend fun buildCoverageBadges(): Map<Long, ShelfBadge> {
        val keyById = bookStorage.getShelfBookKeyMap()
        if (keyById.isEmpty()) return emptyMap()
        val localSet = bookStorage.getLocalPlayableBookKeySet()
        val covByBookKey = bookStorage.getAllAudioCoverage().groupBy { it.bookKey }
        val out = HashMap<Long, ShelfBadge>(keyById.size)
        for ((bookId, bk) in keyById) {
            val badge = computeBadge(bk, localSet, covByBookKey[bk].orEmpty())
            if (badge != null) out[bookId] = badge
        }
        return out
    }

    /** 三档诚实判定（与 PC `_coverage_badge` 一致）。无命中返回 null（不显示徽章）。 */
    private fun computeBadge(
        bookKey: String,
        localSet: Set<String>,
        rows: List<AudioCoverageRow>
    ): ShelfBadge? {
        // ① 本机确有字节 → 可直接播放（无论来源是下载还是本机合成）
        if (bookKey in localSet) return ShelfBadge("✔ 本机有声 · 可直接播放", BADGE_GREEN)
        // ② 服务端有这份字节（可下载后播放）
        val server = rows.firstOrNull { it.source == "server" && it.doneCount > 0 }
        if (server != null) {
            return ShelfBadge("☁ 云端有声 · 可下载播放${countSuffix(server)}", BADGE_BLUE)
        }
        // ③ 仅另一台设备本机合成，服务端无字节 —— 只感知，绝不称可播
        val local = rows.firstOrNull { it.source == "local" && it.doneCount > 0 }
        if (local != null) {
            return ShelfBadge("◍ 另一设备已合成 · 本机待合成${countSuffix(local)}", BADGE_ORANGE)
        }
        return null
    }

    /** 徽章里的「(已完成/总数)」后缀；总数未知则退化成「(N 段)」，两者都缺则空串。 */
    private fun countSuffix(row: AudioCoverageRow): String {
        return if (row.totalSegments > 0) "（${row.doneCount}/${row.totalSegments}）"
        else if (row.doneCount > 0) "（${row.doneCount} 段）"
        else ""
    }

    /**
     * 「从书架移出」——**非破坏式**，只针对书城占位条目
     * （`filePath` 为空且 `storeBookId > 0`）。
     *
     * 做的事：通知服务端 `DELETE /api/shelf/{id}`，本机仅删 `ebooks` 那一行；
     * 收藏/音频缓存/关联任务/正文缓存**一概不动** —— 这些是跨设备资产，
     * 别处下架不该把本机已下载的付费内容一并清掉。
     *
     * 与 PC 端 shelf_page 的行为口径一致：先本地移除再通知服务端；服务端失败
     * 时 [onDone] 收到 false，UI 层给"云端未同步、下次可能拉回"提示即可，
     * 无需回滚本地（下次 syncShelfFromServer 若服务端仍记录在架，会重新建占位）。
     *
     * 传入非占位条目（如本地导入电子书 / 已下载书城书）直接短路返回 false，
     * 避免调用方走错分支。真正的"删电子书+级联清理"请走 [deleteEbook]。
     */
    fun removeShelfPlaceholder(book: Book, onDone: ((Boolean) -> Unit)? = null) {
        if (book.filePath.isNotEmpty() || book.storeBookId <= 0L) {
            Log.w(TAG, "removeShelfPlaceholder 误用：非占位条目 bookId=${book.id} " +
                "storeBookId=${book.storeBookId} filePath='${book.filePath}'")
            viewModelScope.launch(Dispatchers.Main) { onDone?.invoke(false) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = shelfSyncManager.removeFromShelfRemote(book.storeBookId)
            // 无论远端成功与否，本地都先移除——用户点了"移出"就必须看到书从书架消失。
            // 若远端失败，下次 syncShelfFromServer 服务端仍在架会重新建占位；用户看到
            // "又回来了"就是自愈提示（与 PC 端 shelf_page 一致的取舍）
            bookStorage.removeShelfPlaceholderOnly(book.id)
            loadEbooks()
            withContext(Dispatchers.Main) { onDone?.invoke(ok) }
        }
    }

    /**
     * 书城下载/入库后 best-effort 通知服务端「加入书架」。成功则下次跨设备
     * 同步能立即看到；失败也没关系，本机已经写了 ebooks 行，云端会由
     * 后续 [syncShelfFromServer] 自然对齐（服务端为真源，本机不重复插入）。
     */
    fun notifyAddedToShelf(storeBookId: Long, onResult: ((Boolean) -> Unit)? = null) {
        if (storeBookId <= 0L) {
            viewModelScope.launch(Dispatchers.Main) { onResult?.invoke(false) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = shelfSyncManager.addToShelfRemote(storeBookId)
            withContext(Dispatchers.Main) { onResult?.invoke(ok) }
        }
    }

    /**
     * 删除电子书前的影响信息：关联任务数 / 进行中任务数 / 本地音频段数，
     * 供删除确认弹窗展示后果（取消跟踪、积分不退、音频一并删除）。
     */

    /** 查询删除某本书会连带影响什么（异步回调到主线程） */
    /**
     * 删除影响面（关联任务/进行中任务/本地音频）。
     * 由 UI 触发 loadDeleteImpact(book)，再观察 deleteImpact StateFlow。
     */

    private val _deleteImpact = MutableStateFlow<DeleteImpactResult?>(null)
    val deleteImpact: StateFlow<DeleteImpactResult?> = _deleteImpact.asStateFlow()

    fun loadDeleteImpact(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            val impact = bookStorage.computeDeleteImpact(book)
            _deleteImpact.value = DeleteImpactResult(book, impact)
        }
    }

    fun clearDeleteImpact() {
        _deleteImpact.value = null
    }

    // ---------- 音频缓存存储管理 ----------

    /** 某本书的本地音频占用 */

    /** 音频缓存总览：总占用 + 各书占用（按大小降序） */

    /**
     * 音频缓存占用（audio_segments 的 BLOB 总大小）。
     * 用 SQLite 的 length(audio_data) 聚合，BLOB 不会进 Java 堆。
     * 由 UI 触发 loadAudioStorageStats()，再观察 audioStorageStats StateFlow。
     */
    private val _audioStorageStats = MutableStateFlow<AudioStorageStats?>(null)
    val audioStorageStats: StateFlow<AudioStorageStats?> = _audioStorageStats.asStateFlow()

    fun loadAudioStorageStats() {
        viewModelScope.launch(Dispatchers.IO) {
            _audioStorageStats.value = bookStorage.computeAudioStorageStats()
        }
    }

    fun clearAudioStorageStats() {
        _audioStorageStats.value = null
    }

    /**
     * 清理某本书的本地音频（保留电子书；**合成记录一并删除**）。
     * 清理后压缩数据库文件回收磁盘空间。
     *
     * 音频（audio_segments）与任务行（tasks）是两张表：删掉音频后**必须**把
     * 相关任务行一并处理，否则书架上会留着一条"音频已清理，请重新合成"的行。
     * 这里选择**直接删掉任务行**（而不是改它的状态）—— 音频都不在了，
     * 这条合成记录已经没有意义，留着既占位置又催着用户马上重合成。
     */
    suspend fun clearAudioForBook(bookName: String): Boolean {
        val ok = bookStorage.clearAudioForBook(bookName)
        if (ok) {
            // 音频没了，这本书的**任务行也一并删掉**：留着只会变成一条
            // "音频已清理，请重新合成 / 继续合成剩余 N 段"的行 —— 既占位置又催着
            // 用户马上重合成。用户要的是"清干净"，那就连状态行一起清掉。
            // （任务行是合成任务的记录，音频都不在了，这条记录已无意义；
            //   将来重新合成会由 upsertLocalTask 新建一行。）
            taskRepository.deleteForBook(bookName)
            // 续播位置也要清：它指向的是已被删掉的段落，留着会让重新下载后的
            // 自动续播跳回一个不存在的段（见 clearSavedProgress 的注释）。
            clearSavedProgress(bookName)
        }
        loadEbooks()
        loadTasks()
        return ok
    }

    /**
     * 清理全部本地音频缓存（保留所有电子书；**合成记录一并删除**）。
     * 同样要处理任务行（全部任务）。
     */
    suspend fun clearAllAudio(): Boolean {
        val ok = bookStorage.clearAllAudio()
        if (ok) {
            // 同 clearAudioForBook：音频全清 → 任务行也全删（不留"音频已清理"的行）
            taskRepository.deleteAll()
            // 全部音频都没了，所有续播位置一并失效
            clearAllSavedProgress()
        }
        loadEbooks()
        loadTasks()
        return ok
    }

    /**
     * 根据书名（displayName）从 ebooks 表查找对应的文件路径。
     * 用于：任务列表打开阅读器时，需要知道电子书文件在哪（找不到返回 null）。
     */
    suspend fun findEbookFilePathByDisplayName(displayName: String): String? =
        bookStorage.findEbookFilePathByDisplayName(displayName)

    /**
     * 按书名找书架条目（返回文件路径 + 书城 id），用于「从收藏跳转」时定位到正确的书。
     *
     * 为什么要它而不是 [findEbookFilePathByDisplayName]：后者只按 display_name **精确**查，
     * 而同一本书在不同来源下书名可能差一个扩展名（书城在线读 = 无扩展名，
     * 下载到书架 = 原始文件名带扩展名）⇒ 精确查落空 ⇒ 阅读页「以空内容打开」。
     */
    suspend fun findShelfBookLocation(bookName: String): ShelfBookLocation? =
        bookStorage.findShelfBookLocation(bookName)

    fun ensureEbookRegistered(bookName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookStorage.ensureEbookRegistered(bookName)
            loadEbooks()
        }
    }

    /**
     * 把下载的音频片段写入本地 audio_segments 表（服务端 audio_segments 的镜像）。
     * 先清空该书名旧数据，再逐个插入，保证与服务端完全一致。
     *
     * @param bookName    书名（与 task.bookName 一致，即 display_name）
     * @param segments    片段列表，每项需含 segmentNumber 和 audioData(byte[])
     */
    fun saveAudioSegments(bookName: String, segments: List<Map<String, Any>>) {
        viewModelScope.launch(Dispatchers.IO) {
            // 不再无条件报成功：BookStorage 现在如实返回写入结果，
            // 失败（含书籍已删除导致整批被丢弃）时只记 warn，避免误导排查
            val saved = bookStorage.saveAudioSegments(bookName, segments)
            if (saved) {
                Log.d(TAG, "✅ 已写入音频片段到 audio_segments (book=$bookName)")
            } else {
                Log.w(TAG, "⚠️ 音频片段未写入 audio_segments (book=$bookName)")
            }
            loadEbooks()
            loadTasks()  // 刷新任务列表，更新 hasAudio 状态（隐藏下载按钮、显示阅读按钮）
        }
    }

    suspend fun saveAudioSegmentStreaming(
        bookName: String,
        serverSegmentId: Long?,
        segmentNumber: Int,
        textContent: String,
        audioData: ByteArray
    ): Boolean {
        return bookStorage.saveAudioSegmentStreaming(bookName, serverSegmentId, segmentNumber, textContent, audioData)
    }

    /**
     * 本机合成用的**立即落盘**写入：绕过「攒批」内存缓冲，返回 true 即代表已进库。
     *
     * 与 [saveAudioSegmentStreaming] 的区别不是快慢，而是**返回值语义**：
     * 那个只保证「已入队」，进程一死缓冲就没了；合成用这个，段数才作数。
     * 原因见 BookStorage.saveAudioSegmentDirect 的注释。
     */
    suspend fun saveAudioSegmentDirect(
        bookName: String,
        serverSegmentId: Long?,
        segmentNumber: Int,
        textContent: String,
        audioData: ByteArray
    ): Boolean {
        return bookStorage.saveAudioSegmentDirect(bookName, serverSegmentId, segmentNumber, textContent, audioData)
    }

    /**
     * 把 [BookStorage] 里还攒着的音频片段强制落盘。
     *
     * 下载收尾（成功/失败/取消都得走）必须调用，否则最后凑不满一个批量窗口的那几段
     * 会一直留在进程内缓冲里：任务显示"已完成"，但播放到最后几段没声音，
     * 且进程一被杀就永久丢失。
     *
     * @return 本次落盘的段数；0 表示缓冲本来就是空的
     */
    suspend fun flushPendingAudioSegments(): Int = bookStorage.flushPendingAudioSegments()

    /**
     * 取走并清零「因写库失败被丢弃的片段数」。
     *
     * 批量写入把失败从"每段可见"变成了"只在提交时可见"，下载收尾处若不检查，
     * 一次批量失败会静默丢掉最多一整批音频，而任务照样被标记成成功。
     */
    fun consumeFlushFailures(): Int = bookStorage.consumeFlushFailures()

    fun refreshLibraryAfterDownload() {
        loadEbooks()
        loadTasks()
    }

    /** 某段落是否已有本地音频（补下载时跳过已有片段，只拉缺失的） */
    suspend fun hasLocalAudioSegment(bookName: String, segmentNumber: Int): Boolean {
        return bookStorage.hasLocalAudioSegment(bookName, segmentNumber)
    }

    /**
     * 这本书本地已有音频的段号集合（只含 `audio_data` 非空的行）。
     *
     * 供本机合成续跑时一次性判重：合成一段要几十秒到几分钟，逐段查
     * [hasLocalAudioSegment] 会把上百段的书查成几百次 SQL；这里一次全取回。
     */
    suspend fun loadLocalSegmentNumbers(bookName: String): Set<Int> {
        return bookStorage.loadLocalSegmentNumbers(bookName)
    }

    fun openBook(book: Book) {
        val tableName = try {
            BookStorage.validateTableName(book.tableName)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "${e.message}")
            // 把失败反馈给界面，而不是静默停在空白页
            lineList = emptyList()
            segmentMap = null
            _lines.postValue(emptyList())
            _bookOpenError.postValue(e.message ?: "无法打开该书籍")
            return
        }
        currentBookTable = tableName
        // 非在线路径：清掉上一本书可能留下的在线态，避免 playLine 误走在线分支
        onlineMode = false
        onlineStoreBookId = -1L
        onlineAudioReady = false
        viewModelScope.launch(Dispatchers.IO) {
            _phase.postValue(Phase.Resolving)
            // 表缺失 / 读取失败返回 null：书籍动态表不存在时（建表失败、被清理，
            // 而入口记录仍在——如通知栏点击打开）降级为空内容页，而不是闪退
            val items = bookStorage.loadBookLines(tableName)
            if (items == null) {
                Log.w(TAG, "书籍表不存在或读取失败，以空内容打开: $tableName")
                lineList = emptyList()
                segmentMap = null
                _lines.postValue(emptyList())
                _phase.postValue(Phase.StructureReady)
                return@launch
            }
            // loadBookLines 返回可空，上面已拦截 null；用显式非空局部变量让
            // NullSafeMutableLiveData 检查确认传入的是非可空值
            val safeItems: List<LineItem> = items
            lineList = safeItems
            // ⭐ 正文先发，映射后建 —— 与 [loadEbookFromFile] 同一口径。
            //   这条路径的正文来自本地 SQLite（比上传整本快得多），原本白屏不明显，
            //   但建映射的开销同样存在，且两张表的规模可能很大，行为理应一致。
            _lines.postValue(safeItems)
            _phase.postValue(Phase.ContentReady)

            // 这张动态表存的就是原文；音频片段只用来建位置映射，
            // 与 loadEbookFromFile 同一套口径，避免两条入口行为不一致
            val segments = loadSegmentsFromAudioTable(tableName)
            segmentMap = EbookSegmentMap.build(
                safeItems.map { it.text },
                segments.map { it.lineNumber to it.text }
            )
            _phase.postValue(Phase.StructureReady)
            // 打开别的书才停止当前播放；重开同一本书时保留后台播放会话
            stopIfDifferentBook(tableName)
            Log.d(TAG, "Opened book '$tableName' with ${items.size} lines")
        }
    }

    /**
     * 从文件加载电子书内容。
     *
     * **阅读页显示的始终是电子书原文**（TXT 按句打包成行、EPUB/MOBI 按本地解析出的行、
     * PDF 按服务端返回的原文行），
     * audio_segments 里的片段文本只用来建立「段号 ↔ 原文位置」的映射（见 [EbookSegmentMap]）。
     *
     * 早先的实现是「只要本地有音频就显示片段文本」，两个后果：
     * 一是每行只有 30 个字，读起来是碎片；二是行号被当成了段号，
     * 换个格式或换本书就会「点第 3 行播第 3 段」而其实对不上。
     */
    fun loadEbookFromFile(filePath: String, displayName: String, serverTaskId: Long = -1L) {
        currentBookTable = displayName
        // 非在线路径：清掉上一本书可能留下的在线态
        onlineMode = false
        onlineStoreBookId = -1L
        onlineAudioReady = false
        viewModelScope.launch(Dispatchers.IO) {
            _phase.postValue(Phase.Resolving)
            // 清掉上一本书留下的逐页进度（旋转重建 / 直接打开另一本时可能残留）
            _parsePageProgress.postValue(null)
            // ⚠️ 片段（audio_segments）**不在这里读**，挪到正文上屏之后（见 ③ 前）。
            //    它要按 book_name 把整本的段文本捞出来（书城大部头上万段），
            //    却只服务于「段号↔行号映射」与目录段号 —— 与「能不能读」无关。
            //    放在最前面等于让首屏多等一次全表扫描（国富论 11866 段）。
            val extension = filePath.substringAfterLast('.', "").lowercase()
            val isStructuredFormat = extension in listOf("pdf", "epub", "mobi")
            // 这本书是不是从书城下载的？是的话拿到它的书城 id —— 取正文可以直接
            // `GET api/store/book/{id}/content`（服务端自己找文件，客户端零上传），
            // 不必把整本 PDF 传回服务器再解析一遍（实测国富论 4MB：上传+解析 9.2s，
            // 按 id 取命中服务端快照只要 95ms）。-1 = 手工导入，保持原路径。
            val storeBookId = bookStorage.findStoreBookIdByFilePath(filePath)
            if (storeBookId > 0) {
                Log.d(TAG, "📚 本书来自书城（storeBookId=$storeBookId），正文优先按 id 取")
            }

            // ① 先拿「原文」——显示用的文本，与无音频时走完全相同的管线
            //
            // PDF 的「本地预览」**不走这里的返回值，而是在 readPdfRowsTwoPhase 内部
            // 通过 onPreview 直接上屏**：服务端那条路要先整本上传、再全书解析
            // （实测服务端本身：论持久战 237ms / 潜规则 1.2s / 国富论 9.2s，
            //  还是在本机回环上；手机上传 4MB + 下载 6MB 正文只多不少），
            // 把它挡在「能读」之前就是首次打开长时间白屏的根因。
            // 其余格式仍是一条路走到底。
            var previewRows: List<LineItem>? = null
            val content: EbookContent = when {
                // EPUB/MOBI/MD：本地优先（离线可读、完全不上传），失败时内部回退服务端并提示。
                // MD 在服务端/客户端都先「剥壳」成纯文本（见 LocalEbookParser.parseMarkdown），
                // 与 TXT 同口径；这里让它走本地解析路径，离线也能读、也能本地合成。
                extension == "epub" || extension == "mobi" || extension == "md" ->
                    readLocalRows(filePath, extension, displayName, storeBookId) ?: return@launch
                // PDF：降级预览（先上屏）→ 权威覆盖（绝不建 audio_segments 用本地文本）
                extension == "pdf" -> readPdfRowsTwoPhase(filePath, displayName, storeBookId) { preview ->
                    previewRows = preview.rows
                    lineList = preview.rows
                    _lines.postValue(preview.rows)
                    stopIfDifferentBook(displayName)
                    _phase.postValue(Phase.ContentReady)
                } ?: return@launch
                isStructuredFormat -> readServerRowsWithCache(filePath, displayName, storeBookId) ?: return@launch
                else -> readTxtRows(filePath) ?: return@launch
            }

            // ② **先把正文发出去**，让用户马上看到文字。
            //    这一步必须早于 [EbookSegmentMap.build]：该映射要按段逐条在全文里做
            //    indexOf 定位（长文本 + 上千段时是本流程里最重的一步），
            //    而它的产物只影响高亮/续播/目录段号，**不影响正文能否显示**。
            //    早先的实现在这里才 postValue，等于把「能读」绑死在「建完映射」上，
            //    于是慢书首屏全程白屏。
            //
            //    PDF 预览已经上过屏时**不再重复推同一份内容**：LiveData 再来一次会让
            //    RecyclerView 整表重绘，用户正读到一半可能被拉回顶部。服务端结果与预览
            //    真有差异（极少见，两者在 golden 样本上是字符级一致的）才覆盖。
            val shownRows = previewRows
            when {
                shownRows == null -> {
                    lineList = content.rows
                    _lines.postValue(content.rows)
                    stopIfDifferentBook(displayName)
                    _phase.postValue(Phase.ContentReady)
                }
                shownRows != content.rows -> {
                    lineList = content.rows
                    _lines.postValue(content.rows)
                    Log.d(
                        TAG,
                        "PDF 权威解析与本地预览有差异，已覆盖：${shownRows.size} 行 → ${content.rows.size} 行"
                    )
                }
                else -> {
                    // 预览已上屏且与服务端结果一致：正文不动，只补 lineList 的权威引用
                    lineList = content.rows
                }
            }

            // ③ 结构：段号 ↔ 行号映射。用片段文本在原文里定位。
            //    此刻正文已经在屏幕上了，用户可以边读边等这一步完成。
            //    片段文本也在这里才读（见方法开头那条注释）。
            val segments = loadSegmentsFromAudioTable(displayName)
            segmentMap = EbookSegmentMap.build(
                content.rows.map { it.text },
                segments.map { it.lineNumber to it.text }
            )
            Log.d(
                TAG,
                "Loaded ebook from file: $filePath, ${content.rows.size} 行," +
                    " ${segments.size} 段音频（定位成功 ${segmentMap?.locatedCount ?: 0} 段" +
                        "，其中锚点估算 ${segmentMap?.estimatedCount ?: 0} 段）"
            )

            // ④ 目录：TTS 任务自带的段号最准（它的段号就是合成任务的段号）＞ 本地目录 + 本地换算段号
            if (serverTaskId > 0) {
                if (content.toc.isNotEmpty()) _toc.postValue(content.toc)
                loadTocForTask(serverTaskId)
            } else {
                // 有音频时，「点目录跳过去接着听」需要段号，而各类本地/服务端目录带来的都是**行下标**。
                // 旧实现在这里**把整本再上传一次**只为拿服务端算好的段号（loadTocOnlyFromServer）——
                // 那次上传比首次解析还贵，且完全可以在本地换算（行 → 段的映射刚刚已经建好）。
                _toc.postValue(withSegmentNumbers(content.toc, segmentMap))
            }

            // ⑤ PDF 的「二次覆盖」曾在这里再做一遍（重建映射 + 重推正文），
            //    2026-10-30 删除：那时 readPdfRowsTwoPhase 是**阻塞**的 —— 它算完预览
            //    还要等服务端返回才把内容交出来，返回的 content 其实已经是权威内容，
            //    于是 ③④ 用权威结果建好了映射，这里又用同一份 rows 重建一次。
            //    代价是全书 indexOf 定位跑两遍（上千段时是整条链路最重的一步）。
            //    现在预览直接上屏、服务端结果作为 content 返回，③④ 用的是权威结果，
            //    覆盖只发生在 ②（且仅在内容真有差异时）。

            _phase.postValue(Phase.StructureReady)
        }
    }

    /** 从电子书文件读到的「原文」：逐行文本 + 目录（TXT 无目录，列表为空） */
    private data class EbookContent(val rows: List<LineItem>, val toc: List<TocEntry>)

    /**
     * 书城「在线读」：不把电子书文件下载到本地，直接按 bookId 从服务端拉正文并显示。
     *
     * 与 [loadEbookFromFile] 的差异：
     *  - **正文来源**：`GET api/store/book/{id}/content`（走 [fetchStoreContentFromServer]），
     *    结果落 [BookStorage.loadStoreContentCache]/[BookStorage.saveStoreContentCache] 本地缓存，
     *    再次打开同一本书秒出、不重复请求；
     *  - **不入书架**：不建 ebooks 行，`currentBookTable` 直接用书城书名作为「虚拟书名」——
     *    它同时是 `audio_segments.book_name` 的取值，使在线缓存的音频与日后真下载到书架
     *    的同名书共用一张表；
     *  - **段号↔行号映射**用服务端**完整**片段表（[StoreAudioDownloadManager.onlineSegments]）建立，
     *    而非本地已下载片段 —— 这样任何一行都能解析出段号，点读时再按需下载对应音频
     *    （见 [playLine] 的在线分支）。故该映射不随下载变化，[storeAudioSegmentListener] 在线模式跳过重建。
     *
     * @param storeBookId 书城书籍 id（拉正文 / 片段 / 音频都用它）
     * @param displayName 书城书名，作为虚拟书名（currentBookTable / audio_segments.book_name）
     */
    fun loadEbookFromStoreOnline(storeBookId: Long, displayName: String) {
        currentBookTable = displayName
        onlineMode = true
        onlineStoreBookId = storeBookId
        viewModelScope.launch(Dispatchers.IO) {
            _phase.postValue(Phase.Resolving)

            // ① 正文：缓存优先，未命中则请求服务端并落缓存（缓存写失败只记日志，不影响本次打开）
            val content: EbookContent = bookStorage.loadStoreContentCache(storeBookId)?.let { cached ->
                Log.d(TAG, "✅ 命中书城在线缓存: $displayName，${cached.rows.size} 行、${cached.toc.size} 目录项")
                EbookContent(cached.rows.toLineItems(), cached.toc)
            } ?: fetchStoreContentFromServer(storeBookId).fold(
                onSuccess = { c ->
                    bookStorage.saveStoreContentCache(
                        storeBookId,
                        displayName,
                        CachedEbookContent(c.rows.map { it.text }, c.toc)
                    )
                    c
                },
                onFailure = { e ->
                    Log.e(TAG, "❌ 书城在线阅读失败: ${e.message}")
                    postOpenError(e.message ?: "在线阅读失败")
                    return@launch
                }
            )

            // ② 正文先发，让用户马上看到文字（与本地/上传路径同口径，映射建立较重，后置）
            lineList = content.rows
            _lines.postValue(content.rows)
            stopIfDifferentBook(displayName)
            _phase.postValue(Phase.ContentReady)

            // ③ 段号↔行号映射：用服务端完整片段表，任何行都能解析出段号（点读时再按需下音频）
            val segs = StoreAudioDownloadManager.onlineSegments(storeBookId).orEmpty()
            _onlineAudioAvailable.postValue(segs.isNotEmpty())
            onlineAudioReady = segs.isNotEmpty()
            segmentMap = EbookSegmentMap.build(
                content.rows.map { it.text },
                segs.map { it.number to it.text }
            )
            Log.d(
                TAG,
                "书城在线读: $displayName，${content.rows.size} 行、${segs.size} 段音频" +
                    "（定位成功 ${segmentMap?.locatedCount ?: 0} 段）"
            )

            // ④ 目录：content.toc 已带行下标，换算成段号供「点目录跳过去接着听」
            _toc.postValue(withSegmentNumbers(content.toc, segmentMap))
            _phase.postValue(Phase.StructureReady)
        }
    }

    /**
     * 打不开书时给阅读页一条可见的错误行。
     *
     * 之前文件缺失/读取失败只记日志就 `return@launch`，用户看到的是一张永久空白的
     * 阅读页，既没有提示也没有出口，只能靠猜。这里复用同一条错误行的写法，
     * 保证三种格式（TXT 本地、EPUB/MOBI 本地、PDF 本地预览+服务端权威）的失败表现一致。
     */
    private fun postOpenError(reason: String) {
        lineList = emptyList()
        segmentMap = null
        _lines.postValue(
            listOf(LineItem(id = 0, lineNumber = 1, text = "❌ 加载电子书失败：$reason"))
        )
        // 必须一并把阶段推到「结构就绪」：阅读页的 loading 只在 Resolving 时显示，
        // 漏掉这一句会让解析失败后进度圈永远转下去 —— 比原本的空白页更糟。
        _phase.postValue(Phase.StructureReady)
    }

    /**
     * EPUB/MOBI：优先本地解析（离线可读、完全不上传），失败则提示用户并回退服务端解析。
     * 两条路都走不通时返回 null —— 此时要么已发过提示，要么已经给出错误行。
     *
     * 这两种格式走本地解析：EPUB 是自研 [EpubParser]、MOBI 是自研 [MobiParser]
     * （PalmDOC + 正则，无第三方库），都能与服务端逐字符对齐。PDF 另走
     * [PdfPreviewParser]（本地 PDFium 预览）+ 服务端权威覆盖的两阶段路径，不在此方法内。
     */
    private suspend fun readLocalRows(
        filePath: String,
        extension: String,
        bookName: String,
        storeBookId: Long = -1L
    ): EbookContent? {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $filePath")
            postOpenError("文件不存在或已被清理")
            return null
        }
        try {
            val result = LocalEbookParser.parse(file)
            Log.d(
                TAG,
                "✅ $extension 本地解析成功: ${result.paragraphs.size} 行, ${result.toc.size} 目录项"
            )
            return EbookContent(result.paragraphs.toLineItems(), result.toc)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ $extension 本地解析失败: ${e.message}")
            // 游客未登录：服务端解析会被 readServerRowsWithCache 静默跳过，
            // 这里直接给一条明确的错误行，别再弹「已切换服务端解析」这种假希望 toast。
            if (AuthManager.token.isNullOrEmpty()) {
                postOpenError(
                    "${extension.uppercase()} 本地解析失败，且未登录无法使用服务端解析。" +
                        "请登录后重试，或换用其它文件"
                )
                return null
            }
            // 已登录：回退到服务端继续处理，先提示用户，避免静默失败
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    getApplication(),
                    "${extension.uppercase()} 本地解析失败，已切换服务端解析",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        return readServerRowsWithCache(filePath, bookName, storeBookId)
    }

    /**
     * PDF 两阶段解析：**本地预览解析完立刻回调上屏**，再等权威结果。
     *
     * @param onPreview 本地预览就绪、且服务端结果尚未拿到时立即回调（调用方在这里
     *        把正文推给界面）。**缓存命中时不回调** —— 缓存就是权威，快速路径无需分段。
     * @return 服务端权威正文；服务端不可用时退回预览；两者都没有则 null。
     *
     * ## 2026-10-30 修正：预览必须真的先上屏，不能只是「先算出来」
     *
     * 旧实现把预览算出来后，**仍然阻塞**等服务端返回才把内容交回调用方
     * （返回 `preview to authoritative`），调用方那唯一一次 `_lines.postValue` 发生在
     * 服务端应答之后 ⇒ 本地预览白算了，首开白屏时长 = 本地解析 + 整本上传 + 服务端全书解析
     * + 整本正文回传。实测服务端单独一段（本机回环、不含上传）：
     *
     * | 书 | PDF 大小 | 服务端解析+回传 | 响应正文 | 本地 PDFium 提取 |
     * |---|---|---|---|---|
     * | 论持久战 | 362 KB | 237 ms | 407 KB | 81 ms（36 页） |
     * | 潜规则 | 1.3 MB | 1231 ms | 995 KB | 304 ms（145 页） |
     * | 国富论 | 4.0 MB | **9183 ms** | 5.8 MB | — |
     *
     * 手机侧还要叠加移动网络上传/下载，弱网下就是几十秒的白屏。现在预览一出来就
     * 回调上屏，白屏只剩本地 PDFium 解析这一小段。
     *
     * ## 为什么覆盖要交回调用方做
     *
     * 覆盖会牵动三样东西：正文 [LineItem] 列表、[EbookSegmentMap] 映射、目录。
     * 只换正文而不重建映射 ⇒ 高亮/点读仍按预览文本定位，而音频段是按权威文本合成的
     * ⇒ 定位静默失准。重建映射需要 `segments`，那在 [loadEbookFromFile] 手里。
     *
     * ## 阶段编排
     * ```
     * 命中缓存 ────────────────────────────────► 权威（无预览必要，0.03s）
     * 未命中 → 本地预览 ─┬─► onPreview 立即上屏 ─► 服务端权威 → 调用方按需覆盖
     *                    └─► 预览失败 ───────────► 只等权威（退化为改造前行为）
     * ```
     *
     * 预览失败**不是错误**：扫描件/加密文件/内存不足都会走到这里，
     * 此时表现与改造前完全一致（只有服务端一条路）。
     *
     * ## ⚠️ 预览结果**绝不写缓存**（本方案最重要的一条约束）
     *
     * `ebook_content_cache` 有第二个消费者：[MossLocalSynthesizer.resolveParagraphs]
     * 读它来切**音频片段**——那是要落`audio_segments`、要与段号永久对齐的。
     * 若把预览文本写进缓存，本机合成就会用另一套口径切段、与线上服务端
     * 合成的段号口径不同 ⇒ 同名书两种段号 ⇒ 点读串音。
     *
     * 所以本方法里预览结果只走上屏回调，**唯一写缓存的仍是
     * [readServerRowsWithCache] 内部的 `fetchContentFromServer` 分支**。
     * 改动时务必保持这个不变量。
     */
    private suspend fun readPdfRowsTwoPhase(
        filePath: String,
        bookName: String,
        storeBookId: Long = -1L,
        onPreview: ((EbookContent) -> Unit)? = null,
    ): EbookContent? {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $filePath")
            postOpenError("文件不存在或已被清理")
            return null
        }

        // 第 1 层：缓存命中 —— 重复打开的常态，0.03s，无需任何预览逻辑
        bookStorage.loadEbookContentCache(file)?.let { cached ->
            Log.d(TAG, "✅ 命中解析缓存: ${file.name}，${cached.rows.size} 行、${cached.toc.size} 目录项")
            return EbookContent(cached.rows.toLineItems(), cached.toc)
        }

        // 第 2 层：本地降级预览。用 Dispatchers.Default 而不是当前协程的 IO ——
        // PDFium 解析是**纯 CPU**（解压 + 字体映射），放 IO 线程池里会占住上传用的线程，
        // 让紧接着的服务端上传变慢。这里是 CPU 密集，必须给计算线程池。
        val previewContent = withContext(Dispatchers.Default) {
            runCatching {
                PdfPreviewParser.parse(getApplication(), file) { done, total ->
                    // 在解析线程上执行，只 post 不碰 View（见 parse 的参数说明）
                    _parsePageProgress.postValue(done to total)
                }
            }
                .onSuccess {
                    Log.d(
                        TAG,
                        "⚡ PDF 本地预览解析成功: ${it.paragraphs.size} 行（先上屏，随后由服务端结果覆盖）"
                    )
                }
                .onFailure {
                    Log.w(TAG, "PDF 本地预览解析失败，退化为纯服务端解析: ${it.message}")
                }
                .getOrNull()
        }?.let { EbookContent(it.paragraphs.toLineItems(), it.toc) }

        // ⭐ 预览一就绪就交给界面，**不等服务端**（这是首开白屏的根治点）
        if (previewContent != null) {
            onPreview?.invoke(previewContent)
        }

        // 第 3 层：服务端权威解析（慢就慢，用户此刻已经在读预览了）
        val authoritative = readServerRowsWithCache(filePath, bookName, storeBookId)
        if (authoritative == null) {
            // 服务端失败或未登录跳过：优先保留预览（离线可读），不要把已经显示的文字收回去
            if (previewContent != null) {
                Log.w(TAG, "服务端解析不可用，保留本地预览结果（已上屏，不撤回）")
                return previewContent
            }
            // 预览也没有 + 服务端这条路又走不通：给一条能看懂的错误行，
            // 别让阅读页停在永久空白上（游客场景尤其重要，因为服务端跳过是静默的）。
            if (AuthManager.token.isNullOrEmpty()) {
                postOpenError("未登录，且本地预览失败，无法读取此 PDF。请登录后重试，或改用 TXT/EPUB 格式")
            }
            // 已登录时 readServerRowsWithCache 内部已经 postOpenError，这里不重复
            return null
        }

        return authoritative
    }

    /**
     * PDF（以及本地解析失败后的 EPUB/MOBI）：让服务端提取文本与目录，并把结果**落本地缓存**。
     *
     * 缓存的意义：服务端解析要上传整本 —— 原实现**每次打开都重传**，
     * 实测同一本 1.4MB 的 PDF 连开三次是 2.53s / 1.94s / 2.22s（网络一差就是几十秒；
     * 限速到 57.8KB/s 时 100 秒都出不来）。缓存键取「绝对路径 + 文件大小 + mtime」，
     * 文件被换掉时自动失效。
     *
     * ⚠️ 本方法**不做本地预览**（那在 [readPdfRowsTwoPhase] 里）。
     *
     * @param storeBookId 这本书在书城的 id（`-1` = 手工导入）。**> 0 时优先按 id 取正文**：
     *        服务端自己定位文件（`GET api/store/book/{id}/content`），客户端一个字节都不用传，
     *        命中服务端正文快照时百毫秒级返回。失败（下架 404 / 暂离线）才回退到上传解析 ——
     *        回退是有意为之：书城下架后书架里这本仍应能读。
     */
    private suspend fun readServerRowsWithCache(
        filePath: String,
        bookName: String,
        storeBookId: Long = -1L,
    ): EbookContent? {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: $filePath")
            postOpenError("文件不存在或已被清理")
            return null
        }
        bookStorage.loadEbookContentCache(file)?.let { cached ->
            Log.d(TAG, "✅ 命中解析缓存: ${file.name}，${cached.rows.size} 行、${cached.toc.size} 目录项")
            return EbookContent(cached.rows.toLineItems(), cached.toc)
        }
        // ⭐ 书城下载的书：按 id 取正文，**不整本上传**。
        //    这条端点服务端是 permitAll，所以游客也能走 —— 因此要放在下面的 token 判断**前面**。
        if (storeBookId > 0) {
            val fromStore = fetchStoreContentFromServer(storeBookId)
            val content = fromStore.getOrNull()
            if (content != null) {
                bookStorage.saveEbookContentCache(
                    file,
                    bookName,
                    CachedEbookContent(content.rows.map { it.text }, content.toc)
                )
                Log.d(
                    TAG,
                    "✅ 按书城 id 取正文: storeBookId=$storeBookId，${content.rows.size} 行" +
                        "（未上传文件）"
                )
                return content
            }
            Log.w(
                TAG,
                "按书城 id 取正文失败（可能已下架/网络不可用），回退到上传解析: " +
                    "${fromStore.exceptionOrNull()?.message}"
            )
        }
        // 游客无 token：服务端解析这条路直接跳过，**不发 postOpenError**。
        // 上层（PDF 的 readPdfRowsTwoPhase / EPUB·MOBI 的 readLocalRows）自己
        // 有本地预览或本地解析兜底，只有兜底也失败时才需要真的报错。
        // 之前在这里抛「未登录，无法解析该格式」，会让 PDF 打开时先闪一条错误行、
        // 再被本地预览正文覆盖 —— 用户观感是"报错→又好了"，其实是同一次打开。
        if (AuthManager.token.isNullOrEmpty()) {
            Log.d(TAG, "未登录，跳过服务端解析（调用方按本地预览/本地解析结果继续）")
            return null
        }
        return fetchContentFromServer(file).fold(
            onSuccess = { content ->
                // 缓存是加速项：写失败只记日志，不影响本次打开
                bookStorage.saveEbookContentCache(
                    file,
                    bookName,
                    CachedEbookContent(content.rows.map { it.text }, content.toc)
                )
                content
            },
            onFailure = { e ->
                Log.e(TAG, "❌ 从服务端提取内容失败: ${e.message}")
                postOpenError(e.message ?: "服务端解析失败")
                null
            }
        )
    }

    /**
     * 给目录补上「段号」。
     *
     * 目录（本地解析出的或服务端返回的）带的都是**行下标**，而「点目录跳过去接着听」需要段号。
     * 旧实现在这里把整本**再上传一次**（`loadTocOnlyFromServer`）去要服务端算好的段号 ——
     * 那次上传比首次解析还贵，而「行 → 段」的映射（[EbookSegmentMap]）刚刚已经为这本书建好了，
     * 本地换算即可。而且它与高亮、点读同源，不会出现「目录跳的行」与「高亮标的行」不一致。
     *
     * 换算不出（该行之后没有音频、映射为空）时保持原样：目录仍可跳转，只是不带段号。
     */
    private fun withSegmentNumbers(toc: List<TocEntry>, map: EbookSegmentMap?): List<TocEntry> {
        if (toc.isEmpty() || map == null) return toc
        return toc.map { entry ->
            if (entry.segmentNumber > 0) {
                entry
            } else {
                val segment = if (entry.paragraphIndex >= 0) map.segmentOfRow(entry.paragraphIndex) else -1
                if (segment > 0) entry.copy(segmentNumber = segment) else entry
            }
        }
    }

    /**
     * TXT：本地直接读取（不含目录信息）；文件不存在或读取失败时给出错误行并返回 null。
     *
     * **必须分块喂给 [ParagraphSplitter]**：它是增量分句器，类注释明确写着设计目标就是
     * 「流式、不驻留整本」，且保证「多次 feed 与一次 feed 的切分结果逐字节一致」。
     * 早前的写法是 `ParagraphSplitter().apply { feed(file.readText()) }` —— 整本先驻留进
     * UTF-16 的 String（约等于文件体积的 2 倍），再叠加切出来的行列表；
     * 导入上限是 200MB，几十 MB 的书一打开阅读页就 OOM，且越大的书必崩。
     */
    private suspend fun readTxtRows(filePath: String): EbookContent? {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "File not found: $filePath")
                postOpenError("文件不存在或已被清理")
                null
            } else {
                val paragraphs = readParagraphsStreaming(file)
                Log.d(TAG, "Loaded ebook from file: ${file.name} with ${paragraphs.size} paragraphs")
                EbookContent(paragraphs.toLineItems(), emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ebook from file: ${e.message}")
            postOpenError(e.message ?: "读取失败")
            null
        }
    }

    /**
     * 分块读取 TXT 并按句打包成段落，全程只驻留「当前块」。
     *
     * **TXT 用「换行即段落边界」模式**（[ParagraphSplitter.lineBreakAsParagraph] = true）：
     * TXT 的换行与行首空格是作者的排版意图，把它抹平会出现两个问题 ——
     * 一是整本被合并成几大块、原文段落结构看不出来；二是每块开头被 `trim()` 裁掉，
     * **段首缩进丢失**（线上实例：《钟鼓楼-节选几段》，原文段首 8 个半角空格、续行 4 个，
     * 阅读页里全没了）。开启后原文一行即一段，行首空白原样保留、只裁行尾。
     *
     * EPUB 不走这里（它用默认模式），原因见该参数的注释：EPUB 的换行来自 XHTML 源码，
     * 行首空白是源码缩进而非排版。
     *
     * @see ParagraphSplitter.feed 可多次调用，累计结果与一次喂入完全等价
     */
    private fun readParagraphsStreaming(file: File): List<String> =
        file.bufferedReader().use { reader ->
            val splitter = ParagraphSplitter(lineBreakAsParagraph = true)
            val buffer = CharArray(READ_CHUNK_CHARS)
            // 代理对（emoji、扩展汉字）可能恰好落在块的边界上，硬切会把一个字劈成两个无效字符，
            // 因此末位是高位代理时留到下一块再拼。
            var carry: Char? = null
            while (true) {
                val n = reader.read(buffer)
                if (n <= 0) break
                val head = carry?.toString() ?: ""
                carry = null
                var end = n
                if (buffer[n - 1].isHighSurrogate()) {
                    carry = buffer[n - 1]
                    end = n - 1
                }
                if (end > 0) splitter.feed(head + String(buffer, 0, end))
            }
            carry?.let { splitter.feed(it.toString()) }
            splitter.finish()
        }

    /** 段落文本 → 阅读页行（行号从 1 起，与 [LineItem.lineNumber] 的约定一致） */
    private fun List<String>.toLineItems(): List<LineItem> =
        mapIndexed { index, text -> LineItem(id = index, lineNumber = index + 1, text = text) }

    /**
     * PDF（以及本地解析失败后的 EPUB/MOBI）：把整本上传给服务端提取文本与目录。
     *
     * 调用方是 [readServerRowsWithCache] —— 它先查本地缓存、成功后把结果落缓存；
     * 本方法只负责这一次**真正走网络**的提取。
     *
     * 服务端返回两套粒度，用途不同：
     * - `rows`：**原文行**（保留行首缩进）—— 阅读页显示这个；
     * - `paragraphs`：合成片段（≤30 字，与音频段号 1:1）—— 只用来建「段号 ↔ 行号」映射。
     *
     * 修复前只用 `paragraphs` 当正文，于是阅读页显示的是一堵没有缩进、没有段落的碎片墙
     * （片段是切给 TTS 引擎的，不是给人读的），且位置映射退化成恒等映射。
     * 服务端未返回 `rows` 时（老版本）自动退回旧行为。
     */
    private suspend fun fetchContentFromServer(file: File): Result<EbookContent> {
        if (!file.exists()) return Result.failure(IOException("文件不存在: ${file.name}"))
        if (AuthManager.token.isNullOrEmpty()) return Result.failure(IOException("未登录，无法解析该格式"))
        return try {
            val requestBody = file.asRequestBody("application/octet-stream".toMediaType())
            val part = MultipartBody.Part.createFormData("book_file", file.name, requestBody)

            val resp = RetrofitClient.heavyApi.extractContent(part)
            if (!resp.isSuccessful) {
                return Result.failure(
                    IllegalStateException("服务端不支持该文件格式或文件损坏。\nHTTP 错误码: ${resp.code()}")
                )
            }
            val body = resp.body()
            if (body?.get("success") != true) {
                return Result.failure(IllegalStateException("服务端返回 success != true"))
            }
            Result.success(parseContentBody(body))
        } catch (e: CancellationException) {
            // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 解析服务端「整本内容」响应（`extract-content` 与 `store/book/{id}/content` **同构**）：
     * `{ success, paragraphs:[{text,charOffset,paragraphIndex}], toc, extension, rows? }` → [EbookContent]。
     *
     * 抽出来共用是因为在线读（[fetchStoreContentFromServer]）与上传解析走的是同一套字段口径，
     * 两处各自维护极易漂移（尤其「显示原文行 rows、片段 paragraphs 只用于建映射」这条约定）。
     */
    private fun parseContentBody(body: Map<String, Any>): EbookContent {
        @Suppress("UNCHECKED_CAST")
        val paragraphsList = body["paragraphs"] as? List<Map<String, Any>> ?: emptyList()
        val paragraphTexts = paragraphsList.map { it["text"] as? String ?: "" }

        // 阅读页显示「原文行」（服务端 rows，保留行首缩进）；服务端没这个字段时
        // （老服务端 / TXT 不产出）退回片段文本，行为与修复前完全一致。
        val serverRows = (body["rows"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val displayRows = (if (serverRows.isNotEmpty()) serverRows else paragraphTexts).toLineItems()
        val usingServerRows = serverRows.isNotEmpty()

        @Suppress("UNCHECKED_CAST")
        val tocList = body["toc"] as? List<Map<String, Any>> ?: emptyList()
        // 服务端的 paragraphIndex 是**片段下标**（与音频段号严格 1:1，供目录换算段号用），
        // 而阅读页显示的是原文行 —— 换成行显示后必须把段落下标也换算成行下标，
        // 否则目录会「拿着片段下标去当行下标用」，跳到完全无关的位置。
        // 换算用与阅读页同一套定位实现（EbookSegmentMap，忽略空白），保证同源。
        val rowMap = if (usingServerRows) {
            EbookSegmentMap.build(
                displayRows.map { it.text },
                paragraphTexts.mapIndexed { index, text -> (index + 1) to text }
            )
        } else {
            null
        }
        val toc = tocList.map { item ->
            val paraIndex = (item["paragraphIndex"] as? Number)?.toInt() ?: -1
            TocEntry(
                title = item["title"] as? String ?: "",
                paragraphIndex = rowMap
                    ?.rowOfSegmentNumber(paraIndex + 1)
                    ?.takeIf { it >= 0 }
                    ?: paraIndex,
                pageNumber = (item["pageNumber"] as? Number)?.toInt() ?: -1,
                level = 0
            )
        }
        Log.d(
            TAG,
            "✅ 解析服务端内容: ${displayRows.size} 行（原文行=$usingServerRows）、" +
                " ${paragraphTexts.size} 片段, ${toc.size} 目录项"
        )
        return EbookContent(displayRows, toc)
    }

    /**
     * 书城「在线读」：按 bookId 从服务端拉正文（`GET api/store/book/{id}/content`），
     * 与 [fetchContentFromServer] 响应同构，复用 [parseContentBody]。**无需下载电子书文件**。
     */
    private suspend fun fetchStoreContentFromServer(bookId: Long): Result<EbookContent> {
        // 在线阅读正文对游客开放：服务端 /api/store/book/{id}/content 已 permitAll，无需 token。
        return try {
            val resp = RetrofitClient.heavyApi.getStoreBookContent(bookId)
            if (!resp.isSuccessful) {
                return Result.failure(
                    IllegalStateException("在线阅读失败。\nHTTP 错误码: ${resp.code()}")
                )
            }
            val body = resp.body()
            if (body?.get("success") != true) {
                return Result.failure(IllegalStateException("服务端返回 success != true"))
            }
            Result.success(parseContentBody(body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从 audio_segments 表加载某本书的所有段落文本（按 segment_number 升序）。
     * 仅当已下载过音频时才有数据，此时文本与服务端TTS分段完全一致。
     */
    private suspend fun loadSegmentsFromAudioTable(bookName: String): List<LineItem> {
        return bookStorage.loadSegmentsFromAudioTable(bookName)
    }

    /**
     * 加载 TTS 任务的目录（章节映射到 segmentNumber）。
     * 适用于已有音频的书籍。
     */
    fun loadTocForTask(serverTaskId: Long) {
        AuthManager.token ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.apiService.getTaskToc(serverTaskId)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.get("success") == true) {
                        @Suppress("UNCHECKED_CAST")
                        val tocList = body["toc"] as? List<Map<String, Any>> ?: emptyList()
                        val entries = tocList.map { item ->
                            val segmentNumber = (item["segmentNumber"] as? Number)?.toInt() ?: -1
                            TocEntry(
                                title = item["title"] as? String ?: "",
                                segmentNumber = segmentNumber,
                                // 服务端 toc 只给段号，这里顺带换算成行下标。
                                // 书内搜索的「所属章节」按 paragraphIndex 建索引
                                // （buildChapterIndex 会滤掉 -1 的条目），不换算的话
                                // 这份目录在有任务的书上等于没有章节信息；
                                // 目录跳转仍用 segmentNumber，不受影响。
                                paragraphIndex = rowIndexOfSegment(segmentNumber),
                                pageNumber = (item["pageNumber"] as? Number)?.toInt() ?: -1,
                                level = 0
                            )
                        }
                        _toc.postValue(entries)
                        Log.d(TAG, "✅ 加载 TTS 目录 ${entries.size} 条")
                    }
                } else {
                    Log.w(TAG, "⚠️ 获取目录失败: HTTP ${response.code()}")
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ 加载目录异常: ${e.message}")
            }
        }
    }

    // 播放/暂停 入口（由 Adapter 调用）：实际播放在 PlaybackService（前台服务）中进行，
    // 锁屏/切后台/退出阅读页不打断收听，通知栏提供 上一段/播放暂停/下一段 控制。
    /**
     * 点击文本行播放对应音频。
     *
     * @param charOffsetInRow 点击处在该行文本内的字符下标（-1 = 只知道点在这一行上）。
     *   阅读页显示的是原文，一行里常常压着好几个音频片段，只有它才能做到
     *   「点哪句播哪句」；缺省则退到该行起首的那一段。
     */
    fun playLine(item: LineItem, charOffsetInRow: Int = -1) {
        val pos = lineList.indexOf(item)
        if (pos == -1) {
            Log.e(TAG, "Item not in list")
            return
        }
        val svc = playbackBinder?.service
        if (svc == null) {
            // 服务连接尚未建立：挂起请求，连接成功后自动执行
            pendingPlayAction = { playLine(item, charOffsetInRow) }
            return
        }

        // 断点续听：调用方显式指定的段号优先 —— 它可能落在本行的中间，
        // 不能拿「行首所属的段」去覆盖（那样续播会退回到本行第一句）
        val resumeSegment = pendingResumeSegment
        val resumeOffset = pendingResumeOffsetMs
        pendingResumeSegment = -1
        pendingResumeOffsetMs = 0

        val segment = if (resumeSegment > 0) resumeSegment else resolveSegmentNumber(pos, charOffsetInRow)
        // 把换算结果留痕：线上反馈「点这句播的却是那句」时，光看界面无从判断，
        // 有这行日志就能立刻区分是位置映射算错了，还是播放侧拿错了段。
        Log.d(
            TAG,
            "点击文本：第 ${pos + 1} 行 +${charOffsetInRow} 字 → 第 $segment 段" +
                if (resumeSegment > 0) "（续播指定）" else ""
        )
        if (segment <= 0) {
            // 有位置映射却解析不出段号 = 这段文字本来就没有音频（多为书末多出来的正文）。
            // 明确提示，而不是退回「行号当段号」去播一段无关的音频。
            Toast.makeText(context, "这段文字没有对应的音频", Toast.LENGTH_SHORT).show()
            return
        }

        // 同一段落：切换暂停/继续
        if (svc.currentBookName() == currentBookTable && svc.currentSegmentNumber() == segment) {
            if (svc.isPlayingNow()) pause() else resume()
            return
        }

        val offsetMs = if (resumeSegment == segment) resumeOffset else 0
        currentItem = item
        currentPos = pos

        // 在线模式：目标段音频可能还没下到本地。PlaybackService 加载队列时若本地为空，
        // 会被当成「本书没有音频」直接拆会话（见 loadQueueAndPlay），故必须先 await 目标段
        // 下载入库再起播；同时把预取窗口向前武装，使连播时后续段多半已就绪（无缝）。
        if (onlineMode && onlineStoreBookId > 0 && onlineAudioReady) {
            val bookId = onlineStoreBookId
            val bookName = currentBookTable
            StoreAudioDownloadManager.prefetchOnlineAhead(bookId, bookName, segment + 1, ONLINE_PREFETCH_WINDOW)
            viewModelScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    StoreAudioDownloadManager.ensureOnlineSegment(bookId, bookName, segment)
                }
                if (!ok) {
                    Toast.makeText(context, "音频下载失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // 目标段已入库：起播前再补一次预取（ensure 期间窗口可能已被消费）
                StoreAudioDownloadManager.prefetchOnlineAhead(bookId, bookName, segment + 1, ONLINE_PREFETCH_WINDOW)
                playbackBinder?.service?.playSegment(bookName, segment, offsetMs)
            }
            return
        }

        svc.playSegment(currentBookTable, segment, offsetMs)
    }

    /**
     * 把「第几行的第几个字」换算成应播放的段号。
     *
     * 有位置映射（本地有音频且定位成功）时按映射查：
     * 先找覆盖该字符的段（点在哪句就是哪句），落空则顺延到其后最近的一段。
     * 没有映射时退回旧口径「行号即段号」，保证无音频的书行为不变。
     *
     * @return 段号；无法播放时 ≤ 0
     */
    private fun resolveSegmentNumber(row: Int, charOffsetInRow: Int): Int {
        val map = segmentMap ?: return lineList.getOrNull(row)?.lineNumber ?: -1
        val local = if (charOffsetInRow > 0) charOffsetInRow.coerceAtMost(map.rowLength(row)) else 0
        val offset = map.offsetOfRow(row) + local
        if (offset < 0) return -1
        map.segmentAtOffset(offset).let { if (it > 0) return it }
        return map.segmentAtOrAfterOffset(offset)
    }

    /**
     * 段号 → 阅读页行下标。
     *
     * 有映射时按映射查（一行里可能压着好几个片段，反过来一对多）；
     * 无映射（无音频）时退回旧口径「行号即段号」。找不到返回 -1。
     */
    fun rowIndexOfSegment(segmentNumber: Int): Int {
        if (segmentNumber <= 0) return -1
        val map = segmentMap
        if (map != null) return map.rowOfSegmentNumber(segmentNumber)
        return lineList.indexOfFirst { it.lineNumber == segmentNumber }
    }

    /**
     * 段号 → 该段覆盖的每一行及其行内字符区间（行下标 to 左闭右开）。
     *
     * 由 [EbookSegmentMap.spansInRowsOfSegment] 给出，段跨行时会有多个条目，
     * 高亮据此把整行底色铺到每一行、句内底色按行分片。
     * 无映射（无音频，按行号播放）或该段未定位时为空 Map —— 此时整行底色仍由
     * [rowIndexOfSegment] 给出的段首行承担，只是没有句内底色。
     */
    private fun spansOfSegment(segmentNumber: Int): Map<Int, IntRange> {
        if (segmentNumber <= 0) return emptyMap()
        return segmentMap?.spansInRowsOfSegment(segmentNumber) ?: emptyMap()
    }

    /** 设置断点续听目标：下次播放该段时 seekTo 到 offsetMs */
    fun setPendingResume(segmentNumber: Int, offsetMs: Int) {
        pendingResumeSegment = segmentNumber
        pendingResumeOffsetMs = offsetMs
    }

    fun pause() {
        playbackBinder?.service?.pause()
    }

    fun resume() {
        playbackBinder?.service?.resume()
    }

    /** 设备是否支持倍速（API 23+） */
    fun isSpeedSupported(): Boolean =
        playbackBinder?.service?.isSpeedSupported()
            ?: (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)

    /** 设置倍速（0.75/1/1.25/1.5/2，与 Web 端一致）；服务侧持久化 */
    fun setPlaybackSpeed(speed: Float) {
        playbackBinder?.service?.setPlaybackSpeed(speed)
    }

    /**
     * 设置睡眠定时。
     * @param mode 0 关闭；[PlaybackService.SLEEP_TIMER_END_OF_SEGMENT] 播完本段；>0 分钟数
     */
    fun setSleepTimer(mode: Int) {
        playbackBinder?.service?.setSleepTimer(mode)
    }

    /** 睡眠定时：若干段（章节）后暂停；count 为段数（≥1） */
    fun setSleepTimerSegments(count: Int) {
        playbackBinder?.service?.setSleepTimerSegments(count)
    }

    /** 当前睡眠定时模式（服务未连接时视为关闭） */
    fun getSleepTimerMode(): Int =
        playbackBinder?.service?.getSleepTimerMode() ?: PlaybackService.SLEEP_TIMER_OFF

    /** 段数模式的目标段数（1/3/5），用于回显睡眠定时对话框的勾选态 */
    fun getSleepTimerSegments(): Int =
        playbackBinder?.service?.getSleepTimerSegments() ?: 0

    /** 底部控制栏：播放/暂停切换（作用于当前播放会话） */
    fun togglePlayPause() {
        playbackBinder?.service?.togglePlayPause()
    }

    /**
     * 底部播放键「从头播」：清掉断点续听目标，从全书第一行/第一段起播合成音频。
     *
     * 用于打开书籍后、当前没有本书的活动播放会话时点击底部播放键 ——
     * 直接从头开始，而不是继续上次进度（[togglePlayPause] 在无会话时是空操作）。
     * 段号解析交给 [resolveSegmentNumber]：首行若本身没有音频，会顺延到其后最近的一段，
     * 因此始终落在「全书第一段有音频的位置」。
     */
    fun playFromFirstSegment() {
        pendingResumeSegment = -1
        pendingResumeOffsetMs = 0
        val first = lineList.firstOrNull()
        if (first == null) {
            Log.w(TAG, "playFromFirstSegment: lineList 为空，无法起播")
            return
        }
        playLine(first, 0)
    }

    /** 底部控制栏：下一段 */
    fun skipNext() {
        playbackBinder?.service?.skipNext()
    }

    /** 底部控制栏：上一段（已播超 3 秒则回到本段开头） */
    fun skipPrevious() {
        playbackBinder?.service?.skipPrevious()
    }

    /**
     * 全自动续播：打开书籍后直接从上次段落 + 段内偏移起播（无需用户点击）。
     * 由 BookContentActivity 在 lines 加载完成且存在续听进度时调用。
     */
    fun playFromProgress(item: LineItem, segmentNumber: Int, offsetMs: Int) {
        setPendingResume(segmentNumber, offsetMs)
        playLine(item)
    }

    /** 停止播放会话并清空 UI 播放状态 */
    fun stop() {
        playbackBinder?.service?.stopPlayback()
        currentPos = -1
        currentItem = null
        _currentPlayingSpans.postValue(emptyMap())
        _currentPlayingPosition.postValue(-1)
        _isPlaying.postValue(false)
    }

    /** 指定书籍是否正在播放（用于阅读页打开时判断是否自动续播） */
    fun isPlayingBook(bookTable: String): Boolean {
        val svc = playbackBinder?.service ?: return false
        return svc.hasActiveSession() && svc.currentBookName() == bookTable
    }

    /**
     * 加载书籍内容时停止播放——仅当服务正在播放「另一本」书时才停。
     * 原逻辑无条件 stop()，从通知栏回到阅读页时会把正在播的书停掉；
     * 同书重开时保留播放会话，UI 状态由服务回调同步。
     */
    private fun stopIfDifferentBook(bookTable: String) {
        val svc = playbackBinder?.service
        val sameBookActive = svc != null && svc.hasActiveSession() && svc.currentBookName() == bookTable
        if (svc != null && svc.hasActiveSession() && !sameBookActive) {
            svc.stopPlayback()
        }
        if (!sameBookActive) {
            currentPos = -1
            currentItem = null
            _currentPlayingSpans.postValue(emptyMap())
            _currentPlayingPosition.postValue(-1)
            _isPlaying.postValue(false)
        }
    }

    fun getPositionForIndex(index: Int): Int = index

    /** 收藏结果（success + 提示文案），替代 (Boolean, String) 回调 */

    /**
     * 挂起函数返回 FavoriteResult（含 success 与提示文案）。
     */
    suspend fun addFavorite(bookName: String, lineNumber: Int, content: String): FavoriteResult {
        val result = favoriteRepository.add(bookName, lineNumber, content)
        loadFavorites(bookName)
        return result
    }

    fun loadFavorites(bookName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 收藏上云：先与服务端同步（未登录/网络失败会静默跳过并保留本地），再读本地缓存。
                // 同步成功后本地即服务端全量列表——换设备/清数据后重新登录也能看到历史收藏。
                favoriteRepository.syncWithServer()
                val list = favoriteRepository.getFavorites(bookName)
                _favorites.postValue(list)
                Log.d(TAG, "Loaded ${list.size} favorites for ${bookName ?: "all books"}")
            } catch (e: Exception) {
                Log.e(TAG, "Load favorites failed: ${e.message}")
            }
        }
    }

    fun deleteFavorite(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                favoriteRepository.delete(id)
                Log.d(TAG, "Deleted favorite id=$id")
                loadFavorites()
            } catch (e: Exception) {
                Log.e(TAG, "Delete favorite failed: ${e.message}")
            }
        }
    }

    /**
     * 本机合成的任务是否已在本次会话里对账过。
     *
     * 只做一次：本机合成的进程内状态（`LocalSynthesisService.isBusy`）在
     * 一次会话里是可靠的，反复对账既无必要，也会在恰好的时序下误判。
     */
    private var localTasksReconciled = false

    /**
     * 对账「孤儿」的本机合成任务。
     *
     * ## 为什么需要
     * 在真机上实测到：书架里挂着一条 `本机合成 · Junhao | 合成中 | 4 / 31`，
     * 但模块**根本没装**（`files/` 为空）。没装模块就不可能有本机合成在跑，
     * 所以这只能说明 —— **App 在合成途中被杀，任务永远停在「合成中」**。
     *
     * 本机合成占 1 GB 以上内存，被 LMK 杀掉是常态而非意外；
     * 而任务状态的终态写入全都在合成协程的 finally 里，
     * 进程被强杀时那些代码根本没机会执行。
     * 于是在用户看来：一条永远不会前进、也永远不会结束的任务，
     * 既不能重试也不能删除 —— 界面说的和实际发生的完全不一致。
     *
     * ## 判据
     * 只处理**本机合成**的任务（audioName 带前缀）：在线合成由服务端兜底，
     * 进程重启后还能靠轮询把状态追回来，不该在这里被改掉。
     * 且必须 `LocalSynthesisService.isBusy == false` —— 服务真在跑时绝不能动它的任务。
     *
     * 这里必须用 isBusy 而**不是** isRunning：任务行是在**解析之前**就建好的
     * （否则解析失败会在书架上不留任何痕迹），而首个 progress 要等解析完成或
     * 首段推理结束才发布 —— 那之前 isRunning 恒为 false。
     * 若拿 isRunning 当判据，用户在解析阶段（可能几十秒）打开书架，
     * 正在解析的那本书会被当成孤儿改成「部分完成」，阶段文案也被清掉。
     *
     * 可见性为 internal 是为了让 instrumented 测试能直接调用它做断言 ——
     * 若只能通过 loadTasks() 间接验证，就得靠 sleep 等 LiveData 的异步投递，
     * 那种断言是时序相关的、会偶发失败（第一版真机测试就栽在这上面：
     * 断言读到 null 而不是状态值）。
     */
    internal suspend fun reconcileOrphanLocalTasks(tasks: List<Task>): Boolean {
        if (localTasksReconciled) return false
        if (LocalSynthesisService.isBusy) {
            Log.i(TAG, "本机合成服务在运行，跳过孤儿任务对账")
            // ⚠️ 这里**不能**置位 localTasksReconciled：
            // 「服务在跑」不等于「没有孤儿」。批量合成时，服务可能正在跑第 2 本，
            // 而第 1 本是上次进程被杀留下的孤儿；一旦此处置位，
            // 本进程内就再也不会去处理它，那条任务会永远停在「合成中」。
            // 不置位的代价只是每次 loadTasks 多做一次内存 filter（无 DB 访问），
            // 而置位错误的代价是用户看到一个永远转圈、既不能重试也不能删的任务。
            return false
        }
        val orphans = tasks.filter {
            it.status == "processing" &&
                it.audioName.startsWith(LocalSynthesisService.AUDIO_NAME_PREFIX)
        }
        if (orphans.isEmpty()) {
            // 只有「确认没有需要处理的孤儿」才允许置位，避免漏掉本次服务运行期间
            // 没能处理的那些（见上面关于提前 return 的说明）。
            localTasksReconciled = true
            return false
        }
        Log.w(TAG, "发现 ${orphans.size} 条本机合成的孤儿任务（进程被中断），改为 partial")
        for (t in orphans) {
            // 标成 partial 而不是 failed：已经合成好的段是好的、能播，
            // 这与"失败"给用户的预期不同 —— partial 在书架上显示「部分完成」，
            // 并保留重试入口，用户可以接着再合一次。
            //
            // ⚠️ 必须用按 rowid 更新的重载（WHERE id = ?）而**不是** updateStatus：
            // updateStatus 的 WHERE 键是 `task_id` 列（业务 ID，毫秒时间戳），
            // 而 t.id 是 tasks 表的行主键（从 1 递增）。两者不是一个数，
            // 拿 t.id 去 WHERE task_id = ? 会命中 0 行且不报错 ——
            // 结果就是「代码写了、状态没变」，孤儿任务永远卡在「合成中」，
            // 而 btnRetry 只在 failed 时可见，用户既不能重试也不能删。
            taskRepository.updateStatusByRowId(t.id, "partial", t.completedSegments, t.totalSegments)
            // 清掉阶段文案，否则会一直挂着「正在加载模型」这种早已过期的描述
            taskRepository.updateProgressTextByRowId(t.id, null)
        }
        localTasksReconciled = true
        return true
    }

    fun loadTasks() {
        // 序号防护：并发查询时"谁最后 post 谁赢"，先发起的慢查询可能后返回，
        // 把**旧快照**覆盖到新快照上（刷新频率变高后这不是理论问题）。
        // 只有序号等于最新的一次查询才有资格写 LiveData。
        val seq = tasksLoadSeq.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val booksWithAudio = bookStorage.getBooksWithAudioSet()
                // 先收敛失败行：同一本书只留最新一条 failed，其余删掉。
                // 放在读之前，是为了让本次查询直接读到收敛后的结果（不用二次回查）——
                // 失败行没有轮询、没有任何自动清理，不在这里收口就会一直挂在书架上累积。
                taskRepository.compactFailedTasks()
                val taskList = taskRepository.getTasks(booksWithAudio)
                // 对账孤儿任务：进程上次被杀时留下的「合成中」必须先落定，
                // 否则书架会永远显示一条不会前进的本机合成任务。
                // 有改动时重新查一次，让 LiveData 立刻反映新状态。
                val finalList = if (reconcileOrphanLocalTasks(taskList)) {
                    taskRepository.getTasks(booksWithAudio)
                } else {
                    taskList
                }
                // 保留内存中的下载状态（downloadStatus/downloadProgress），避免轮询导致正在下载的任务进度丢失
                withContext(Dispatchers.Main) {
                    // 已经有更新的查询在飞了：这份快照过期，丢掉（见 loadTasks 的序号防护）
                    if (seq != tasksLoadSeq.get()) {
                        Log.d(TAG, "丢弃过期的任务快照 seq=$seq（当前 seq=$tasksLoadSeq）")
                    } else {
                        val currentTasks = _tasks.value
                        val merged = if (currentTasks == null) {
                            finalList
                        } else {
                            val downloadState = currentTasks.associateBy(Task::id)
                            finalList.map { dbTask ->
                                downloadState[dbTask.id]?.let { memoryTask ->
                                    if (memoryTask.downloadStatus == "downloading" ||
                                        memoryTask.downloadStatus == "failed") {
                                        dbTask.copy(downloadStatus = memoryTask.downloadStatus, downloadProgress = memoryTask.downloadProgress)
                                    } else {
                                        dbTask
                                    }
                                } ?: dbTask
                            }
                        }
                        _tasks.postValue(merged)
                        Log.d(TAG, "Total tasks loaded: ${merged.size}")
                    }
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Load tasks failed: ${e.message}")
            }
        }
    }

    suspend fun saveTask(bookName: String, audioName: String, taskId: Long) {
        taskRepository.saveTask(bookName, audioName, taskId)
        loadTasks()
    }

    /**
     * 开始一轮本机合成时登记任务行；**同一本书复用已有的行**而不是新建。
     * 详见 [TaskRepository.upsertLocalTask] 里「为什么不能无条件 INSERT」的说明。
     *
     * @return 实际使用的 taskId（复用时是旧行的 task_id）—— 调用方后续所有
     *         进度/状态更新都必须用这个返回值，而不是自己生成的 id。
     */
    suspend fun upsertLocalTask(bookName: String, audioName: String, newTaskId: Long): Long {
        val actual = taskRepository.upsertLocalTask(bookName, audioName, newTaskId)
        loadTasks()
        return actual
    }

    fun updateTaskStatus(taskId: Long, status: String, completedSegments: Int, totalSegments: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            taskRepository.updateStatus(taskId, status, completedSegments, totalSegments)
            loadTasks()
        }
    }

    /**
     * 更新任务的「阶段文案」并刷新书架。
     *
     * 与 [updateTaskStatus] 一样要 loadTasks —— 否则文案写进了库，
     * 但书架的数据源（_tasks）还是旧的，界面上看不到变化。
     */
    fun updateTaskProgressText(taskId: Long, progressText: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            taskRepository.updateProgressText(taskId, progressText)
            loadTasks()
        }
    }

    fun updateServerTaskId(localTaskId: Long, serverTaskId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            taskRepository.updateServerTaskId(localTaskId, serverTaskId)
            loadTasks()
        }
    }

    fun updateTaskAudioPath(taskId: Long, audioFilesPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            taskRepository.updateAudioPath(taskId, audioFilesPath)
            loadTasks()
        }
    }

    /**
     * 尽力删除一组服务端任务（[serverTaskIds] 中非空者，自动去重）。
     *
     * [deleteEbook] 与 [deleteTasksForBook] 共用：本地任务行删掉后，若服务端任务
     * 仍在，冷启动时 [TaskSyncManager] 以服务端为真源会把它们重新 upsert 回本地，
     * 表现为「删除后重启 App，任务又回到书架」。所以删本地任务时必须同步通知服务端删除。
     *
     * 服务端语义（`TtsTaskController.deleteTask`）：
     *  - queued/pending：取消并退还已扣积分；
     *  - processing：拒绝（HTTP 400），任务继续合成、继续扣积分（无法中断）；
     *  - completed/partial/failed：删除任务及其服务端音频片段与文件。
     * 每条失败重试一次；返回是否存在最终仍未删除的任务（供调用方汇总提示）。
     */
    private suspend fun cancelServerTasksBestEffort(serverTaskIds: Iterable<Long?>): Boolean {
        var failed = false
        for (serverTaskId in serverTaskIds.filterNotNull().distinct()) {
            var cancelled = false
            for (attempt in 1..2) {
                try {
                    val resp = RetrofitClient.apiService.deleteTask(serverTaskId)
                    if (resp.isSuccessful) {
                        Log.d(TAG, "已删除服务端任务: taskId=$serverTaskId")
                        cancelled = true
                        break
                    }
                    Log.w(TAG, "删除服务端任务失败(HTTP ${resp.code()}): taskId=$serverTaskId")
                } catch (e: CancellationException) {
                    // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "删除服务端任务异常: taskId=$serverTaskId, ${e.message}")
                }
                if (attempt == 1) kotlinx.coroutines.delay(500)
            }
            if (!cancelled) failed = true
        }
        return failed
    }

    /**
     * 删除「只有任务、没有本地电子书文件」条目的任务记录，并同步通知服务端删除。
     *
     * 只删本地 tasks 行的话，冷启动时 [TaskSyncManager]（服务端为真源）会把这些
     * 任务重新 upsert 回本地 —— 表现为「删除后重启 App，任务又回到书架」。
     * 所以 [deleteForBook] 在删行前先取出服务端任务 ID，这里再 best-effort 通知
     * 服务端删除（语义见 [cancelServerTasksBestEffort]）。
     */
    suspend fun deleteTasksForBook(bookName: String): Int {
        // deleteForBook 在删行前会先取出关联的服务端任务 ID（删完就查不到了）
        val result = taskRepository.deleteForBook(bookName)
        // best-effort 通知服务端删除；失败只打日志，不阻塞本地删除（弹窗已提前告知
        // 进行中的任务会继续合成并扣积分，这里无需再额外 Toast）
        cancelServerTasksBestEffort(result.serverTaskIds)
        loadTasks()
        return result.deleted
    }

    override fun onCleared() {
        super.onCleared()
        TaskPollingManager.detach(pollingListener)
        // 进程级监听必须注销：否则 TaskRepository 会一直持有这个 ViewModel，
        // 连带它背后的 Activity 一起泄漏（且每次写库都会回调一个死掉的 ViewModel）。
        TaskRepository.removeTasksChangedListener(tasksChangedListener)
        tasksReloadJob?.cancel()
        StoreAudioDownloadManager.removeSegmentListener(storeAudioSegmentListener)
        // 仅解绑、不停止播放服务：退出页面后后台收听继续，由通知栏控制
        playbackBinder?.service?.removeListener(playbackListener)
        playbackBinder = null
        try {
            context.unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
            // 连接已断开时忽略
        }
    }

}
