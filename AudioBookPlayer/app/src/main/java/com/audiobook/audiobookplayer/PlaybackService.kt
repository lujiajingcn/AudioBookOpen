package com.audiobook.audiobookplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import com.audiobook.audiobookplayer.TaskRepository

/**
 * 有声书后台播放服务。
 *
 * 职责：
 * 1. 持有 MediaPlayer，以「前台服务 + MediaSession」方式播放，
 *    锁屏 / 切后台 / 退出阅读页均不打断收听；
 * 2. 通知栏媒体控件：上一段 / 播放暂停 / 下一段，点击通知回到阅读页；
 * 3. 段落队列自动连播：从 audio_segments 表按书加载已下载片段，
 *    播完一段自动取下一段（跳过缺失片段），全书播完自动停止；
 * 4. 断点续听进度持久化（与原 BookViewModel 相同的 SharedPreferences key）；
 * 5. 音频焦点协作 + 拔耳机自动暂停（AUDIO_BECOMING_NOISY）。
 *
 * BLOB -> 临时 mp3 文件的落盘逻辑沿用原 BookViewModel 的方案，播后清理。
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "PlaybackService"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audiobook_playback"

        // 通知栏动作（PendingIntent.getService 回传）
        const val ACTION_TOGGLE = "com.audiobook.audiobookplayer.action.TOGGLE"
        const val ACTION_PREV = "com.audiobook.audiobookplayer.action.PREV"
        const val ACTION_NEXT = "com.audiobook.audiobookplayer.action.NEXT"

        private const val PROGRESS_PREFS = "playback_progress"

        // 播放速度持久化（跨会话保留用户上次选择）
        private const val SETTINGS_PREFS = "playback_settings"
        private const val KEY_SPEED = "playback_speed"

        /** 睡眠定时模式：关闭 */
        const val SLEEP_TIMER_OFF = PlaybackConfig.SLEEP_TIMER_OFF

        /** 睡眠定时模式：播完当前段落后暂停（用 -1 表示，>0 均为分钟数） */
        const val SLEEP_TIMER_END_OF_SEGMENT = PlaybackConfig.SLEEP_TIMER_END_OF_SEGMENT

        /** 睡眠定时模式：若干段（章节）后暂停（用 -2 表示，段数由 sleepTimerSegmentCount 记录） */
        const val SLEEP_TIMER_SEGMENTS = PlaybackConfig.SLEEP_TIMER_SEGMENTS

        // 连续出错（片段损坏等）达到该次数则停止整次播放，防止坏片段导致无限跳转
        private const val MAX_CONSECUTIVE_ERRORS = 3

        // 「上一段」语义：已播放超过 3 秒则回到本段开头，否则切上一段（媒体应用惯例）
        private const val PREV_RESTART_THRESHOLD_MS = 3000

        // 睡眠定时「段数模式」tick 编码基准：remainingMs = -(SEGMENT_TICK_OFFSET + 剩余段数)，
        // 与 0（关闭）/ 正数（分钟倒计时 ms）/ -1（播完本段）区分开
        private const val SEGMENT_TICK_OFFSET = 1000L

        /** 缓存文件存活超过该时长才算「上一次会话的残留」，本次会话刚写出的文件不会被误删 */
        private const val STALE_CACHE_AGE_MS = 30 * 60 * 1000L
    }

    /** 队列中的一个片段 */
    private data class Segment(val segmentNumber: Int, val text: String)

    /** 状态回调：由 BookViewModel 注册，用于驱动阅读页高亮/按钮状态 */
    interface Listener {
        /** isPlaying 变化或切换段落时回调；segmentNumber<=0 表示无播放内容 */
        fun onStateChanged(isPlaying: Boolean, segmentNumber: Int)

        /** 全书播放完毕（或播放被彻底停止） */
        fun onPlaybackEnded()

        /** 睡眠定时剩余时间（毫秒）；0 表示定时关闭/已触发。倒计时期间每秒回调一次 */
        fun onSleepTimerTick(remainingMs: Long) {}

        /** 播放速度变化（倍速） */
        fun onPlaybackSpeedChanged(speed: Float) {}

        /**
         * 正在等待「尚未下载的后续片段」（书城音频边下边听）。
         *
         * true = 已下载的段已经播完、而音频仍在后台逐段下载中：播放会话保持，
         * 等下一段落库后自动续播；false = 已接上，或下载结束、会话正常终止。
         */
        fun onAwaitingDownload(waiting: Boolean) {}
    }

    inner class PlaybackBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = PlaybackBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 播放所需的 DB 读取统一走 BookStorage（本服务不再内联 SQL）
    private val bookStorage by lazy { BookStorage(application) }

    // 跨端进度同步：用书名反查服务端任务 ID，上传播放进度
    private val taskRepository by lazy { TaskRepository(application) }

    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioFile: File? = null

    // 当前播放会话
    private var bookName: String = ""
    private var ebookFilePath: String? = null
    private var queue: List<Segment> = emptyList()
    private var queueIndex: Int = -1

    private var consecutiveErrors = 0

    /**
     * 「已下载的段播完了，但音频还在后台下载」的等待态（书城书边下边听）。
     * 与 [consecutiveErrors] 无关：这不是错误，是队列还不够长。
     */
    private var awaitingDownload = false

    /** 等待结束后是否自动起播（睡眠定时「播完本段」进入等待时为 false，必须只准备不播） */
    private var awaitingDownloadAutoStart = true

    // 播放"代际"：每次起播/换会话自增。异步提取音频期间用户再次切段时，
    // 旧协程与旧播放器监听器因代际不符而自我作废，避免两个 MediaPlayer 同时出声。
    private var playbackGeneration = 0

    // 是否已进入前台（startForeground 幂等保护）
    private var isForeground = false

    private val listeners = mutableListOf<Listener>()

    // ---------- 播放速度（倍速播放） ----------
    private val settingsPrefs by lazy {
        getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    }

    /** 当前倍速（0.75/1/1.25/1.5/2，与 Web 端档位一致）；API<23 无法生效，仅作记录 */
    private var playbackSpeed = PlaybackConfig.DEFAULT_SPEED

    // ---------- 睡眠定时 ----------
    /** SLEEP_TIMER_OFF / SLEEP_TIMER_END_OF_SEGMENT / SLEEP_TIMER_SEGMENTS / >0 分钟数 */
    private var sleepTimerMode = SLEEP_TIMER_OFF
    private var sleepTimerDeadline = 0L // SystemClock.elapsedRealtime 基准
    /** 段数模式：目标段数与剩余段数 */
    private var sleepTimerSegmentCount = 0
    private var sleepTimerSegmentsLeft = 0

    // ---------- 下一段预取（段间无缝） ----------
    /** 预取的下一段播放器：当前段播完时直接"转正"，省去落盘+prepare 的间隙 */
    private var prefetchPlayer: MediaPlayer? = null
    private var prefetchFile: File? = null
    private var prefetchIndex = -1
    private var prefetchReady = false

    // ---------- 进度持久化（与 BookViewModel 共用同一份 prefs） ----------
    private val progressPrefs by lazy {
        getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
    }

    private fun saveProgress(segmentNumber: Int, offsetMs: Int) {
        if (bookName.isEmpty() || segmentNumber <= 0) return
        progressPrefs.edit()
            .putInt("seg_$bookName", segmentNumber)
            .putInt("off_$bookName", offsetMs)
            .apply()
    }

    private fun clearSavedProgress() {
        if (bookName.isEmpty()) return
        progressPrefs.edit()
            .remove("seg_$bookName")
            .remove("off_$bookName")
            .apply()
    }

    /** 保存当前进度（暂停时机/页面退出时调用） */
    fun saveProgressNow() {
        val seg = currentSegmentNumber()
        if (seg <= 0) return
        val offset = try {
            mediaPlayer?.currentPosition ?: 0
        } catch (_: IllegalStateException) {
            0
        }
        saveProgress(seg, offset)
        // 跨端同步：把进度上传到服务端（服务端任务存在且已登录才上报）
        uploadProgressToServer(seg, offset)
    }

    /**
     * 跨端进度同步：把当前播放进度上报到服务端。
     * 仅当该书已关联服务端任务且用户已登录时上报；失败仅记日志，不影响本地收听。
     * 整段跑在 IO 协程，避免主线程 DB 读取与网络请求。
     */
    private fun uploadProgressToServer(segmentNumber: Int, offsetMs: Int) {
        val name = bookName
        serviceScope.launch(Dispatchers.IO) {
            try {
                val serverTaskId = taskRepository.findServerTaskIdByBookName(name) ?: return@launch
                val token = AuthManager.token
                if (token.isNullOrEmpty()) return@launch
                val body = mapOf(
                    "taskId" to serverTaskId,
                    "segment" to segmentNumber,
                    "offsetMs" to offsetMs
                )
                val response = RetrofitClient.apiService.putProgress(body)
                if (response.isSuccessful) {
                    Log.d(TAG, "✅ 播放进度已上传服务端 taskId=$serverTaskId")
                } else {
                    Log.w(TAG, "上传播放进度失败: HTTP ${response.code()}")
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败，原样抛出
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ 上传播放进度异常: ${e.message}")
            }
        }
    }

    // ---------- 音频焦点（行为与原 BookViewModel 保持一致） ----------
    private var audioManager: AudioManager? = null
    private var pausedByFocusLoss = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isReallyPlaying()) {
                    pausedByFocusLoss = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 渐弱进行中不改音量，让淡出按自身节奏走。
                // 必须用带标签的 return：OnAudioFocusChangeListener 是 SAM 转换的**非内联** lambda，
                // 非内联 lambda 里不允许裸 return（会报 'return' is not allowed here）。
                if (fadingOut) return@OnAudioFocusChangeListener
                setVolume(0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (fadingOut) return@OnAudioFocusChangeListener
                setVolume(1.0f)
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    resume()
                }
            }
        }
    }

    @Suppress("DEPRECATION") // 兼容 API 21+，旧 API 在所有版本可用
    private fun requestAudioFocus(): Boolean {
        if (audioManager == null) {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        val result = audioManager?.requestAudioFocus(
            audioFocusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION") // 与 requestAudioFocus 配套，旧 API 在所有版本可用
    private fun abandonAudioFocus() {
        audioManager?.abandonAudioFocus(audioFocusListener)
    }

    // 暂停后长期持有音频焦点，会导致导航/音乐类 App 在用户暂停期间也拿不到焦点：
    // 暂停 60s 后释放焦点；恢复播放或停止会话时取消 pending 的释放。
    private val FOCUS_RELEASE_DELAY_MS = 60_000L
    private var focusReleasePending = false
    private val focusReleaseRunnable = Runnable { abandonAudioFocus() }

    private fun scheduleFocusRelease() {
        focusReleasePending = true
        mainHandler.postDelayed(focusReleaseRunnable, FOCUS_RELEASE_DELAY_MS)
    }

    private fun cancelFocusRelease() {
        if (focusReleasePending) {
            focusReleasePending = false
            mainHandler.removeCallbacks(focusReleaseRunnable)
        }
    }

    // MediaSession 进度条（蓝牙 / Android Auto / 系统媒体通知）在播放中需周期性刷新
    // position，否则进度条静止不动。每 5s 重发一次 PlaybackState。
    private val POSITION_TICK_INTERVAL_MS = 5000L
    private var positionTickerRunning = false
    private val positionTicker = object : Runnable {
        override fun run() {
            if (!positionTickerRunning) return
            updateSessionState()
            if (isReallyPlaying()) {
                mainHandler.postDelayed(this, POSITION_TICK_INTERVAL_MS)
            } else {
                positionTickerRunning = false
            }
        }
    }

    private fun startPositionTicker() {
        if (positionTickerRunning) return
        positionTickerRunning = true
        mainHandler.postDelayed(positionTicker, POSITION_TICK_INTERVAL_MS)
    }

    private fun stopPositionTicker() {
        positionTickerRunning = false
        mainHandler.removeCallbacks(positionTicker)
    }

    // ---------- 拔耳机自动暂停 ----------
    private var noisyReceiverRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(TAG, "耳机拔出/音频输出切换，自动暂停")
                pause()
            }
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: IllegalArgumentException) {
        }
        noisyReceiverRegistered = false
    }

    // ---------- MediaSession ----------
    private lateinit var mediaSession: MediaSessionCompat

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            resume()
        }

        override fun onPause() {
            pause()
        }

        override fun onSkipToNext() {
            skipNext()
        }

        override fun onSkipToPrevious() {
            skipPrevious()
        }

        override fun onSeekTo(pos: Long) {
            seekTo(pos.toInt())
        }

        override fun onStop() {
            stopPlayback()
        }
    }

    // ---------- Service 生命周期 ----------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // cleanupStaleAudioCache 是目录遍历 + 删除，放主线程会拖慢首帧且有 ANR 风险，
        // 故移入 IO 协程（serviceScope 在构造期已初始化）
        serviceScope.launch(Dispatchers.IO) { cleanupStaleAudioCache() }

        // 书城音频是「边下边听」：新段落库要立刻接进队列，否则队列停在旧长度，
        // 表现就是「下载明明在继续，播到一半却停住了」。
        StoreAudioDownloadManager.addSegmentListener(storeAudioSegmentListener)
        StoreAudioDownloadManager.addListener(storeAudioStateListener)

        // 恢复上次选择的倍速（防御异常值）
        playbackSpeed = settingsPrefs.getFloat(KEY_SPEED, PlaybackConfig.DEFAULT_SPEED).coerceIn(PlaybackConfig.MIN_SPEED, PlaybackConfig.MAX_SPEED)

        mediaSession = MediaSessionCompat(this, "AudioBookPlayer").apply {
            setCallback(sessionCallback, mainHandler)
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            isActive = true
        }

        // 把「暂停当前播放」注册到 AuthManager 的 401 回调，
        // 登录过期时由 AuthManager.handleUnauthorized 先暂停本服务再跳转登录，
        // 避免过期期间音频继续空放。pause() 内部会保存进度，重登后可续听。
        AuthManager.setPlaybackPauser { pause() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏动作经由 PendingIntent.getForegroundService（Android 8+）到达这里；
        // 耳机线控/蓝牙媒体键的 ACTION_MEDIA_BUTTON 交给 MediaSession 分发
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> MediaButtonReceiver.handleIntent(mediaSession, intent)
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_PREV -> skipPrevious()
            ACTION_NEXT -> skipNext()
        }
        // 通知栏按钮走 PendingIntent.getForegroundService（见 actionIntent），
        // 耳机/蓝牙的 ACTION_MEDIA_BUTTON 由 MediaButtonReceiver 转发 —— 两者都以
        // startForegroundService 语义唤醒本服务，系统要求到达后数秒内调用 startForeground，
        // 否则 Android 8~11 直接判「前台服务未显示通知」ANR，12+ 抛
        // ForegroundServiceDidNotStartInTimeException。
        //
        // 早前只在 isForeground 为真时补调用：服务还活着但通知已被撤下的场景
        // （teardownSession 之后被 bind 保活、或从未建立过会话）下，媒体键会把服务拖崩。
        // 「收到 action」本身就意味着用户主动操作了播放器，此时补一张通知是合理行为；
        // 已在播放时重复调用是幂等的，顺带把播放/暂停图标刷新到位。
        if (intent?.action != null || isForeground) {
            startForegroundNow()
        }
        // START_NOT_STICKY：进程被杀后不自动重启播放，由用户重新发起
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户划掉应用：未在播放则彻底停止；播放中则继续（前台服务保活）
        if (!isReallyPlaying()) {
            stopPlayback()
        }
    }

    override fun onDestroy() {
        // 解绑 401 暂停回调，避免悬挂引用导致后续 401 误调已销毁的服务
        AuthManager.setPlaybackPauser(null)
        StoreAudioDownloadManager.removeSegmentListener(storeAudioSegmentListener)
        StoreAudioDownloadManager.removeListener(storeAudioStateListener)
        mainHandler.removeCallbacks(sleepTimerTick)
        mainHandler.removeCallbacks(fadeOutRunnable)
        cancelFadeOut()
        releasePlayer()
        discardPrefetch()
        abandonAudioFocus()
        unregisterNoisyReceiver()
        try {
            mediaSession.isActive = false
            mediaSession.release()
        } catch (_: Exception) {
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------- 对外控制接口（Binder 调用可能来自其他线程，统一切主线程） ----------

    fun addListener(listener: Listener) {
        mainHandler.post {
            if (!listeners.contains(listener)) listeners.add(listener)
            // 立即同步一次当前状态，保证重新打开阅读页时高亮正确
            notifyState()
        }
    }

    fun removeListener(listener: Listener) {
        mainHandler.post { listeners.remove(listener) }
    }

    /** 当前会话的书名（无会话返回空串） */
    fun currentBookName(): String = bookName

    /** 当前播放/暂停中的段落号（无则 -1） */
    fun currentSegmentNumber(): Int =
        if (queueIndex in queue.indices) queue[queueIndex].segmentNumber else -1

    fun hasActiveSession(): Boolean = bookName.isNotEmpty() && queue.isNotEmpty()

    fun isPlayingNow(): Boolean = isReallyPlaying()

    /**
     * 播放指定书的指定段落（核心入口）。
     * 同一本书内切换段落直接定位；换书则重新从 audio_segments 加载队列。
     *
     * @param offsetMs 段内起始偏移（断点续听用），0 表示从头
     */
    fun playSegment(requestedBook: String, segmentNumber: Int, offsetMs: Int) {
        if (requestedBook.isEmpty()) return
        mainHandler.post {
            if (requestedBook == bookName && queue.isNotEmpty()) {
                val idx = queue.indexOfFirst { it.segmentNumber == segmentNumber }
                if (idx >= 0) {
                    startSegment(idx, offsetMs)
                } else {
                    // 队列里没有该段（未下载），从最近的下一段开始；一段都没有则等待/结束
                    val nextIdx = queue.indexOfFirst { it.segmentNumber > segmentNumber }
                    if (nextIdx >= 0) startSegment(nextIdx, 0) else onQueueExhausted(autoStart = true)
                }
            } else {
                loadQueueAndPlay(requestedBook, segmentNumber, offsetMs)
            }
        }
    }

    fun togglePlayPause() {
        mainHandler.post {
            if (isReallyPlaying()) pause() else resume()
        }
    }

    fun pause() {
        mainHandler.post {
            // 暂停即终止任何进行中的渐弱（如播放中被焦点抢占），避免 runnable 残留
            cancelFadeOut()
            val mp = mediaPlayer ?: return@post
            if (!isReallyPlaying()) return@post
            try {
                mp.pause()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "pause failed: ${e.message}")
                return@post
            }
            saveProgressNow()
            updateSessionAndNotification()
            notifyState()
            // 暂停后启动焦点释放倒计时并停止进度刷新
            scheduleFocusRelease()
            stopPositionTicker()
        }
    }

    fun resume() {
        mainHandler.post {
            val mp = mediaPlayer ?: return@post
            if (isReallyPlaying()) return@post
            if (!hasActiveSession()) return@post
            // 恢复播放前取消 pending 的焦点释放，避免 60s 后误释放
            cancelFocusRelease()
            if (!requestAudioFocus()) {
                Log.w(TAG, "音频焦点未获批，暂不恢复播放")
                return@post
            }
            try {
                startAtSpeed(mp)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "resume failed: ${e.message}")
                return@post
            }
            registerNoisyReceiver()
            updateSessionAndNotification()
            notifyState()
        }
    }

    fun skipNext() {
        mainHandler.post {
            if (!hasActiveSession()) return@post
            if (!advanceToNext(autoStart = true)) {
                onQueueExhausted(autoStart = true)
            }
        }
    }

    fun skipPrevious() {
        mainHandler.post {
            if (!hasActiveSession()) return@post
            val position = try {
                mediaPlayer?.currentPosition ?: 0
            } catch (_: IllegalStateException) {
                0
            }
            if (position > PREV_RESTART_THRESHOLD_MS || queueIndex <= 0) {
                seekTo(0)
            } else {
                startSegment(queueIndex - 1, 0)
            }
        }
    }

    fun seekTo(ms: Int) {
        mainHandler.post {
            val mp = mediaPlayer ?: return@post
            try {
                val clamped = ms.coerceIn(0, mp.duration.coerceAtLeast(0))
                mp.seekTo(clamped)
                updateSessionState()
                saveProgressNow()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "seekTo failed: ${e.message}")
            }
        }
    }

    /** 彻底停止本次播放会话（换书/用户停止）。进度保留，供下次续听。 */
    fun stopPlayback() {
        mainHandler.post {
            saveProgressNow()
            teardownSession()
        }
    }

    // ---------- 倍速播放 ----------

    /** API 23（Android 6.0）以下 MediaPlayer 无变速 API，UI 应据此禁用倍速入口 */
    fun isSpeedSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    fun getPlaybackSpeed(): Float = playbackSpeed

    /**
     * 设置倍速（如 0.75/1/1.25/1.5/2，与 Web 端 RATE_OPTIONS 对齐）。
     * 立即作用于当前播放器并持久化，下次起播自动沿用。
     */
    fun setPlaybackSpeed(speed: Float) {
        mainHandler.post {
            if (speed <= 0f) return@post
            playbackSpeed = speed
            settingsPrefs.edit().putFloat(KEY_SPEED, speed).apply()
            if (isReallyPlaying()) {
                applySpeedTo(mediaPlayer)
            }
            updateSessionState()
            listeners.toList().forEach {
                try {
                    it.onPlaybackSpeedChanged(speed)
                } catch (e: Exception) {
                    Log.e(TAG, "listener onPlaybackSpeedChanged failed: ${e.message}")
                }
            }
            Log.d(TAG, "倍速调整为 $speed")
        }
    }

    /** 把当前倍速应用到指定播放器（API<23 无对应能力，静默跳过） */
    private fun applySpeedTo(mp: MediaPlayer?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val player = mp ?: return
        try {
            player.playbackParams = player.playbackParams.setSpeed(playbackSpeed)
        } catch (e: Exception) {
            Log.w(TAG, "设置倍速失败: ${e.message}")
        }
    }

    /** 以当前倍速起播（start 前先应用倍速，避免部分机型参数生效时机差异） */
    private fun startAtSpeed(mp: MediaPlayer) {
        applySpeedTo(mp)
        mp.start()
        // 真正开始播放时启动进度刷新（暂停/停止由对应方法停止）
        startPositionTicker()
    }

    // ---------- 睡眠定时 ----------

    private val sleepTimerTick = object : Runnable {
        override fun run() {
            if (sleepTimerMode <= 0) return
            val remaining = sleepTimerDeadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                fireSleepTimer()
            } else {
                notifySleepTimerTick(remaining)
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    /**
     * 设置睡眠定时。
     * @param mode [SLEEP_TIMER_OFF] 关闭；[SLEEP_TIMER_END_OF_SEGMENT] 播完本段后暂停；
     *             正整数 = N 分钟后暂停
     */
    fun setSleepTimer(mode: Int) {
        mainHandler.post { setSleepTimerInternal(mode) }
    }

    /** 睡眠定时：若干段（章节）后暂停；count 为段数（≥1） */
    fun setSleepTimerSegments(count: Int) {
        mainHandler.post { setSleepTimerInternal(SLEEP_TIMER_SEGMENTS, count) }
    }

    fun getSleepTimerMode(): Int = sleepTimerMode

    /**
     * 段数模式下的目标段数（1/3/5）。UI 据此回显勾选态 ——
     * 段数模式在选项数组里重复出现多次，只靠 mode 无法区分用户选的是几章。
     */
    fun getSleepTimerSegments(): Int = sleepTimerSegmentCount

    /** 睡眠定时剩余毫秒（仅分钟模式有意义，其它模式返回 0），供 UI 恢复倒计时显示 */
    fun getSleepTimerRemainingMs(): Long =
        if (sleepTimerMode > 0) (sleepTimerDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L) else 0L

    private fun setSleepTimerInternal(mode: Int, segmentCount: Int = 0) {
        mainHandler.removeCallbacks(sleepTimerTick)
        cancelFadeOut()
        sleepTimerMode = mode
        when {
            mode > 0 -> {
                sleepTimerDeadline = SystemClock.elapsedRealtime() + mode * 60_000L
                notifySleepTimerTick(mode * 60_000L)
                mainHandler.postDelayed(sleepTimerTick, 1000L)
            }
            mode == SLEEP_TIMER_SEGMENTS -> {
                sleepTimerSegmentCount = segmentCount.coerceAtLeast(1)
                sleepTimerSegmentsLeft = sleepTimerSegmentCount
                // 用编码值下发剩余段数，与分钟倒计时 / 播完本段区分开
                notifySleepTimerTick(encodeSegmentTick(sleepTimerSegmentsLeft))
            }
            mode == SLEEP_TIMER_END_OF_SEGMENT -> {
                sleepTimerDeadline = 0L
                // -1 = 「播完本段」已开启（无倒计时），与 0（关闭）区分
                notifySleepTimerTick(-1L)
            }
            else -> {
                sleepTimerDeadline = 0L
                notifySleepTimerTick(0L)
            }
        }
        Log.d(TAG, "睡眠定时: mode=$mode" + if (mode == SLEEP_TIMER_SEGMENTS) " segments=$segmentCount" else "")
    }

    /** 定时到期（分钟模式）：先渐弱淡出，再暂停播放（进度已保存，会话保留，用户可随时继续） */
    private fun fireSleepTimer() {
        Log.d(TAG, "睡眠定时（时间到）触发，渐弱后暂停")
        // 先关定时（复位模式、取消倒计时、通知 UI 归零），再启动渐弱，二者互不干扰
        setSleepTimerInternal(SLEEP_TIMER_OFF)
        startFadeOutThenPause()
    }

    private fun notifySleepTimerTick(remainingMs: Long) {
        listeners.toList().forEach {
            try {
                it.onSleepTimerTick(remainingMs)
            } catch (e: Exception) {
                Log.e(TAG, "listener onSleepTimerTick failed: ${e.message}")
            }
        }
    }

    // ---------- 音量跟踪 + 渐弱淡出（睡眠定时到期平滑降音量再暂停） ----------

    /** 当前有效音量（0~1）。所有 setVolume 都经此统一记录，便于渐弱从当前音量起步 */
    private var currentVolume = 1.0f

    private fun setVolume(v: Float) {
        val clamped = v.coerceIn(0f, 1f)
        currentVolume = clamped
        try {
            mediaPlayer?.setVolume(clamped, clamped)
        } catch (_: IllegalStateException) {
        }
    }

    /** 渐弱时长与步进：5 秒平滑过渡，每 100ms 降一档 */
    private val FADE_OUT_DURATION_MS = 5000L
    private val FADE_OUT_STEP_MS = 100L
    private var fadingOut = false
    private var fadeOutStartVolume = 1.0f
    private var fadeOutStartTime = 0L

    private val fadeOutRunnable = object : Runnable {
        override fun run() {
            if (!fadingOut) return
            val elapsed = SystemClock.elapsedRealtime() - fadeOutStartTime
            val progress = (elapsed.toFloat() / FADE_OUT_DURATION_MS).coerceIn(0f, 1f)
            setVolume(fadeOutStartVolume * (1f - progress))
            if (progress >= 1f) {
                fadingOut = false
                pause() // 先暂停，避免复位音量瞬间回响爆音
                setVolume(1.0f) // 复位：下次播放恢复满音量
            } else {
                mainHandler.postDelayed(this, FADE_OUT_STEP_MS)
            }
        }
    }

    /** 启动渐弱后暂停；当前未在播放则直接收尾 */
    private fun startFadeOutThenPause() {
        if (fadingOut) return
        if (!isReallyPlaying()) {
            updateSessionAndNotification()
            notifyState()
            return
        }
        fadingOut = true
        fadeOutStartVolume = currentVolume.coerceAtLeast(0.05f)
        fadeOutStartTime = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(fadeOutRunnable)
        mainHandler.post(fadeOutRunnable)
    }

    /** 取消进行中的渐弱并复位音量（切换/清除定时、拆卸会话时调用） */
    private fun cancelFadeOut() {
        if (fadingOut) {
            fadingOut = false
            mainHandler.removeCallbacks(fadeOutRunnable)
            setVolume(1.0f)
        }
    }

    /** 段数模式 tick 编码：remainingMs = -(SEGMENT_TICK_OFFSET + 剩余段数) */
    private fun encodeSegmentTick(remainingSegments: Int): Long =
        -(SEGMENT_TICK_OFFSET + remainingSegments)

    // ---------- 内部实现 ----------

    private fun isReallyPlaying(): Boolean =
        try {
            mediaPlayer?.isPlaying == true
        } catch (_: IllegalStateException) {
            false
        }

    /** 从数据库加载某本书的片段队列并起播 */
    private fun loadQueueAndPlay(requestedBook: String, segmentNumber: Int, offsetMs: Int) {
        // 切换会话前先停止旧播放，并使旧会话的异步任务作废
        playbackGeneration++
        releasePlayer()
        discardPrefetch()
        abandonAudioFocus()

        bookName = requestedBook
        ebookFilePath = null
        queue = emptyList()
        queueIndex = -1
        consecutiveErrors = 0

        serviceScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadQueueFromDb(requestedBook) }
            // 加载期间用户可能又切了书：校验会话未变
            if (bookName != requestedBook) return@launch
            if (loaded.isEmpty()) {
                Log.w(TAG, "《$requestedBook》没有已下载的音频片段")
                // 只清 bookName + notifyEnded 既不退前台也不停服务，
                // 会留下标题为空的常驻通知且服务永不停止（wake lock / MediaSession 不释放），
                // 故统一走 teardownSession 收尾（含 stopSelf 退前台）
                teardownSession()
                return@launch
            }
            queue = loaded
            ebookFilePath = withContext(Dispatchers.IO) { queryEbookFilePath(requestedBook) }

            val idx = loaded.indexOfFirst { it.segmentNumber == segmentNumber }
            when {
                idx >= 0 -> startSegment(idx, offsetMs)
                else -> {
                    val nextIdx = loaded.indexOfFirst { it.segmentNumber > segmentNumber }
                    if (nextIdx >= 0) startSegment(nextIdx, 0) else onQueueExhausted(autoStart = true)
                }
            }
        }
    }

    private suspend fun loadQueueFromDb(bookName: String): List<Segment> {
        // 复用 BookStorage.loadSegmentsFromAudioTable：与这里原本的查询等价
        // （同样的 book_name 条件、同样的 segment_number 升序）
        return bookStorage.loadSegmentsFromAudioTable(bookName).map {
            Segment(segmentNumber = it.lineNumber, text = it.text)
        }
    }

    /** 从 ebooks 表按书名找文件路径，用于通知点击回到阅读页 */
    private suspend fun queryEbookFilePath(bookName: String): String? {
        return bookStorage.findEbookFilePathByDisplayName(bookName)
    }

    /** 从 audio_segments 读 BLOB 分块写临时文件（IO 线程，不整段物化进堆） */
    private suspend fun extractAudioFile(segmentNumber: Int): File? {
        val cacheFile = audioCacheFile(bookName, segmentNumber)
        return withContext(Dispatchers.IO) {
            val ok = bookStorage.writeAudioBlobTo(bookName, segmentNumber, cacheFile)
            if (!ok) {
                cacheFile.delete()
                null
            } else {
                cacheFile
            }
        }
    }

    /**
     * 播放侧音频缓存文件名。
     *
     * 书名是用户可控文本，直接拼 `audio_${bookName}_$segmentNumber.mp3` 时，
     * 含 '/' 会写到错误目录，超长会触发 ENAMETOOLONG。这里用书名 MD5 前 16 位做
     * 文件名规避非法字符与长度上限，段号保留以便排查；碰撞只影响缓存命中，不影响正确性。
     */
    private fun audioCacheFile(bookName: String, segmentNumber: Int): File {
        val md = java.security.MessageDigest.getInstance("MD5")
        val hash = md.digest(bookName.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
        return File(cacheDir, "audio_${hash}_$segmentNumber.mp3")
    }

    /**
     * 起播队列中指定下标的片段。
     * @param autoStart false = 只 prepare 不起播（睡眠定时"播完本段"后切到下一段待播用）
     */
    private fun startSegment(index: Int, offsetMs: Int, autoStart: Boolean = true) {
        if (index !in queue.indices) return
        val segment = queue[index]

        val gen = ++playbackGeneration
        releasePlayer()
        discardPrefetch() // 手动切段/会话重建：已预取内容作废

        serviceScope.launch {
            // 会话可能已切换/停止
            if (gen != playbackGeneration || bookName.isEmpty() || index !in queue.indices) return@launch

            val audioFile = extractAudioFile(segment.segmentNumber)
            if (gen != playbackGeneration || bookName.isEmpty()) {
                // 已有更新的播放请求或会话已停止：清理本次提取的临时文件后退出
                audioFile?.delete()
                return@launch
            }

            if (audioFile == null || !audioFile.exists()) {
                Log.w(TAG, "段落 ${segment.segmentNumber} 无音频数据，跳到下一段")
                if (autoStart) {
                    if (index + 1 < queue.size) startSegment(index + 1, 0) else onQueueExhausted(autoStart = true)
                }
                return@launch
            }

            currentAudioFile = audioFile
            queueIndex = index

            try {
                // 不能写 `mediaPlayer = MediaPlayer().apply { setDataSource(...) }`：
                // setDataSource 抛 IOException 时，Kotlin 表达式求值顺序导致 MediaPlayer() 实例
                // 已构造但未被任何字段引用，catch 中 releasePlayer() 因 mediaPlayer==null 而 no-op，
                // 实例只能等 GC + finalize 释放 native handle。
                // 故沿用 VoicePreviewManager.playPreview 的范本：
                //   val mp = MediaPlayer(); try { mp.setXxx; ...; mediaPlayer = mp } catch { safeRelease(mp) }
                // 把构造与赋值分开，让 catch 能拿到半构造实例并立即 release。
                val mp = MediaPlayer()
                try {
                    mp.setWakeMode(this@PlaybackService, PowerManager.PARTIAL_WAKE_LOCK)
                    mp.setDataSource(audioFile.absolutePath)
                    mp.setOnPreparedListener { ready ->
                        if (gen != playbackGeneration) return@setOnPreparedListener
                        // 断点续听：seek 到段内偏移
                        if (offsetMs > 0) {
                            try {
                                if (offsetMs < ready.duration) ready.seekTo(offsetMs)
                            } catch (e: IllegalStateException) {
                                Log.w(TAG, "恢复播放位置失败: ${e.message}")
                            }
                        }
                        if (autoStart) {
                            if (!requestAudioFocus()) {
                                Log.w(TAG, "音频焦点未获批，本段暂缓播放")
                            }
                            try {
                                startAtSpeed(ready)
                            } catch (e: IllegalStateException) {
                                Log.e(TAG, "start failed: ${e.message}")
                                handlePlaybackError()
                                return@setOnPreparedListener
                            }
                            registerNoisyReceiver()
                        }
                        consecutiveErrors = 0
                        ensureForeground()
                        // 记录"本段已开始"（offset 0），App 中途被杀最多回退到本段开头
                        saveProgress(segment.segmentNumber, 0)
                        updateSessionAndNotification()
                        notifyState()
                        if (autoStart) {
                            // 正常起播后预取下一段，段间无缝衔接
                            prefetchNext(index + 1)
                            Log.d(TAG, "开始播放《$bookName》段落 ${segment.segmentNumber}")
                        } else {
                            Log.d(TAG, "已备好下一段《$bookName》段落 ${segment.segmentNumber}（待播）")
                        }
                    }
                    mp.setOnCompletionListener(newCompletionListener(gen))
                    mp.setOnErrorListener(newErrorListener(gen))
                    mp.prepareAsync()
                    // 所有 setXxx / prepareAsync 均未抛异常后，才把实例交给字段管理
                    mediaPlayer = mp
                } catch (e: Exception) {
                    Log.e(TAG, "创建 MediaPlayer 失败: ${e.message}", e)
                    // 异常路径：半构造的 MediaPlayer 实例必须在 catch 内显式 release，
                    // 避免 native handle 泄漏到 GC
                    try { mp.release() } catch (_: Exception) {}
                    handlePlaybackError()
                }
            } catch (e: Exception) {
                // 外层 try 兜底：MediaPlayer() 自身构造失败或前面的代码异常
                Log.e(TAG, "播放初始化失败: ${e.message}", e)
                handlePlaybackError()
            }
        }
    }

    /**
     * 片段自然播完的监听：保存进度 →
     * - 段数模式：剩余段数递减，归零则停在「下一段开头」（关定时）；否则继续连播并下发新剩余段数；
     * - 播完本段模式：切下一段并停住；
     * - 其它：直接推进下一段（优先"转正"预取播放器，段间无缝）。
     */
    private fun newCompletionListener(gen: Int) = MediaPlayer.OnCompletionListener {
        if (gen != playbackGeneration) return@OnCompletionListener
        saveProgressNow()
        when (sleepTimerMode) {
            SLEEP_TIMER_SEGMENTS -> {
                sleepTimerSegmentsLeft--
                if (sleepTimerSegmentsLeft > 0) {
                    if (advanceToNext(autoStart = true)) {
                        notifySleepTimerTick(encodeSegmentTick(sleepTimerSegmentsLeft))
                    } else {
                        onQueueExhausted(autoStart = true)
                    }
                } else {
                    Log.d(TAG, "睡眠定时（若干段后）触发")
                    setSleepTimerInternal(SLEEP_TIMER_OFF)
                    if (!advanceToNext(autoStart = false)) {
                        onQueueExhausted(autoStart = false)
                    }
                }
                return@OnCompletionListener
            }
            SLEEP_TIMER_END_OF_SEGMENT -> {
                Log.d(TAG, "睡眠定时（播完本段）触发")
                setSleepTimerInternal(SLEEP_TIMER_OFF)
                if (!advanceToNext(autoStart = false)) {
                    onQueueExhausted(autoStart = false)
                }
                return@OnCompletionListener
            }
            else -> {
                if (!advanceToNext(autoStart = true)) {
                    onQueueExhausted(autoStart = true)
                }
            }
        }
    }

    private fun newErrorListener(gen: Int) = MediaPlayer.OnErrorListener { _, what, extra ->
        if (gen != playbackGeneration) return@OnErrorListener true
        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
        handlePlaybackError()
        true
    }

    // ---------- 书城音频「边下边听」 ----------

    /**
     * 书城音频新段落库的回调（来自 [StoreAudioDownloadManager]，主线程）。
     *
     * 只理当前会话这本书：后台可能正在为另一本书补段，与本会话无关。
     */
    private val storeAudioSegmentListener = StoreAudioDownloadManager.SegmentListener { book, _ ->
        mainHandler.post {
            if (book != bookName || bookName.isEmpty()) return@post
            serviceScope.launch { refreshQueueFromDb(book) }
        }
    }

    /**
     * 下载任务收尾（完成 / 失败 / 取消）时，把「等待中」的会话收掉。
     *
     * [onQueueExhausted] 只在「队列已尽」那一刻判断一次要不要等；若之后下载是
     * **失败/取消**收场的，队列永远不会再长，没有这个回调会话就会一直挂在等待态
     * （通知停在暂停、什么也不播）。收尾时先重取一次队列：最后一段可能刚好到，
     * 那就正常接上；确实没有可播的，才按播放结束收场。
     */
    private val storeAudioStateListener = StoreAudioDownloadManager.Listener { state ->
        if (state != null && state.isTerminal) {
            mainHandler.post {
                if (!awaitingDownload || state.bookName != bookName || bookName.isEmpty()) {
                    return@post
                }
                serviceScope.launch {
                    refreshQueueFromDb(bookName)
                    if (awaitingDownload) {
                        Log.d(TAG, "下载已收尾（${state.status}）且无更多片段，结束会话")
                        awaitingDownload = false
                        notifyAwaitingDownload(false)
                        playbackEnded()
                    }
                }
            }
        }
    }

    /**
     * 队列已尽时：如果这本书的音频还在后台下载，就**等待**而不是结束会话。
     *
     * 为什么不能直接 teardown：对书城书来说「队列到底」有两种截然不同的含义 ——
     *  · 全书真的播完了（下载早已结束）⇒ 结束会话、撤通知、清进度，行为不变；
     *  · 只是**还没下到**（边下边听的正常状态）⇒ 结束会话会让用户听着听着突然断掉、
     *    通知消失、进度被清，而且下载还在跑（下一段可能几秒后就到）。
     * 这里用 [StoreAudioDownloadManager.isActiveFor] 区分两者，后者挂起等待，
     * 新段落库后由 [storeAudioSegmentListener] 接上播放。
     *
     * @param autoStart 等到下一段后是否自动起播。睡眠定时「播完本段」是拿 autoStart=false
     *   进来的 —— 那种语义下必须**只准备不播放**，否则定时被静默绕过。
     */
    private fun onQueueExhausted(autoStart: Boolean) {
        if (bookName.isNotEmpty() && StoreAudioDownloadManager.isActiveFor(bookName)) {
            if (!awaitingDownload) {
                awaitingDownload = true
                awaitingDownloadAutoStart = autoStart
                Log.d(TAG, "《$bookName》已播完本地已下载的段，音频仍在下载中，等待后续片段")
                updateSessionAndNotification()
                notifyAwaitingDownload(true)
            } else {
                awaitingDownloadAutoStart = awaitingDownloadAutoStart || autoStart
            }
            return
        }
        playbackEnded()
    }

    /**
     * 从数据库重取队列（新段已入库后调用）。
     *
     * 两种情况都要处理：
     *  · **等待中**：找到比当前段更靠后的第一段直接起播/待播（这就是「自动接上」）；
     *  · **正在播/暂停中**：只把队列接长，并把 [queueIndex] 按段号**重新定位** ——
     *    新段理论上只追加在尾部，但某段失败后重下补齐时会插在中间，下标整体后移，
     *    不重定位就会切到别的段。预取播放器按旧下标预取，一并作废。
     */
    private suspend fun refreshQueueFromDb(book: String) {
        val currentSeg = currentSegmentNumber()
        val loaded = withContext(Dispatchers.IO) { loadQueueFromDb(book) }
        // 加载期间会话可能已切换/拆除
        if (book != bookName || loaded.isEmpty()) return
        if (loaded.size == queue.size && !awaitingDownload) return

        discardPrefetch()
        queue = loaded
        if (awaitingDownload) {
            val autoStart = awaitingDownloadAutoStart
            val idx = loaded.indexOfFirst { it.segmentNumber > currentSeg }
            if (idx < 0) return // 到的段还没排在当前段之后（理论上不会），继续等
            Log.d(TAG, "等待结束：接上第 ${loaded[idx].segmentNumber} 段（autoStart=$autoStart）")
            awaitingDownload = false
            awaitingDownloadAutoStart = true
            notifyAwaitingDownload(false)
            startSegment(idx, 0, autoStart)
        } else {
            if (currentSeg > 0) {
                val idx = loaded.indexOfFirst { it.segmentNumber == currentSeg }
                if (idx >= 0) queueIndex = idx
            }
            Log.d(TAG, "《$book》队列已接长到 ${loaded.size} 段")
        }
    }

    private fun notifyAwaitingDownload(waiting: Boolean) {
        listeners.forEach { it.onAwaitingDownload(waiting) }
    }

    /** 推进到下一段：优先转正预取播放器（无缝）；已是最后一段返回 false */
    private fun advanceToNext(autoStart: Boolean): Boolean {
        if (queueIndex + 1 >= queue.size) return false
        if (!promotePrefetched(autoStart)) {
            startSegment(queueIndex + 1, 0, autoStart)
        }
        return true
    }

    /**
     * 把预取的下一段播放器转正为主播放器（当前段播完 / 手动下一段时调用）。
     * 未就绪或下标不符则清理预取并返回 false，由调用方回退普通起播。
     */
    private fun promotePrefetched(autoStart: Boolean): Boolean {
        val nextIndex = queueIndex + 1
        val pp = prefetchPlayer
        val pf = prefetchFile
        if (pp == null || pf == null || !prefetchReady || prefetchIndex != nextIndex || nextIndex !in queue.indices) {
            discardPrefetch()
            return false
        }
        val segment = queue[nextIndex]
        val gen = playbackGeneration

        // 交接：预取播放器升主，旧主播放器与其缓存文件释放
        val oldPlayer = mediaPlayer
        val oldFile = currentAudioFile
        mediaPlayer = pp
        currentAudioFile = pf
        queueIndex = nextIndex
        prefetchPlayer = null
        prefetchFile = null
        prefetchIndex = -1
        prefetchReady = false
        try {
            oldPlayer?.release()
        } catch (_: Exception) {
        }
        oldFile?.delete()

        try {
            pp.setOnCompletionListener(newCompletionListener(gen))
            pp.setOnErrorListener(newErrorListener(gen))
            if (autoStart) {
                startAtSpeed(pp)
                registerNoisyReceiver()
                ensureForeground()
            }
            consecutiveErrors = 0
            saveProgress(segment.segmentNumber, 0)
            updateSessionAndNotification()
            notifyState()
            if (autoStart) prefetchNext(queueIndex + 1)
            Log.d(TAG, "预取转正：无缝播放段落 ${segment.segmentNumber}")
        } catch (e: Exception) {
            Log.e(TAG, "预取片段转正失败: ${e.message}")
            handlePlaybackError()
        }
        return true
    }

    /**
     * 预取指定下标片段：提前把 BLOB 落盘并 prepareAsync，
     * 当前段播完即可直接转正，消除段间"落盘 + prepare"空档。
     * 任一时刻最多一个预取播放器；切段/换会话/停止时丢弃。
     */
    private fun prefetchNext(index: Int) {
        if (index !in queue.indices) {
            discardPrefetch()
            return
        }
        if (prefetchIndex == index && prefetchPlayer != null) return // 已在预取/已预取
        discardPrefetch()

        val segment = queue[index]
        val gen = playbackGeneration
        prefetchIndex = index

        serviceScope.launch {
            if (gen != playbackGeneration || bookName.isEmpty() || prefetchIndex != index) return@launch
            val file = extractAudioFile(segment.segmentNumber)
            if (gen != playbackGeneration || bookName.isEmpty() || prefetchIndex != index) {
                file?.delete()
                return@launch
            }
            if (file == null || !file.exists()) {
                // 下一段暂无音频：无从预取，播放到达时按现有逻辑跳过缺失段
                prefetchIndex = -1
                return@launch
            }
            prefetchFile = file
            try {
                // 与 startSegment 同源：`prefetchPlayer = MediaPlayer().apply{}`
                // 在 setDataSource 抛异常时构造的实例未被任何字段引用。
                // 同样采用"局部变量先构造、try 内 release"的写法。
                val pp = MediaPlayer()
                try {
                    pp.setWakeMode(this@PlaybackService, PowerManager.PARTIAL_WAKE_LOCK)
                    pp.setDataSource(file.absolutePath)
                    pp.setOnPreparedListener {
                        if (gen == playbackGeneration && prefetchIndex == index) {
                            prefetchReady = true
                        }
                    }
                    pp.setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "预取片段错误: what=$what extra=$extra")
                        if (gen == playbackGeneration) discardPrefetch()
                        true
                    }
                    pp.prepareAsync()
                    prefetchPlayer = pp
                } catch (e: Exception) {
                    Log.w(TAG, "创建预取播放器失败: ${e.message}")
                    try { pp.release() } catch (_: Exception) {}
                    discardPrefetch()
                }
            } catch (e: Exception) {
                // 兜底 MediaPlayer() 构造失败
                Log.w(TAG, "预取初始化异常: ${e.message}")
                discardPrefetch()
            }
        }
    }

    /** 释放预取播放器并清理其缓存文件 */
    private fun discardPrefetch() {
        try {
            prefetchPlayer?.release()
        } catch (_: Exception) {
        }
        prefetchPlayer = null
        prefetchFile?.delete()
        prefetchFile = null
        prefetchIndex = -1
        prefetchReady = false
    }

    /** 播放出错：连续坏片段达到上限则停止，否则跳到下一段 */
    private fun handlePlaybackError() {
        releasePlayer()
        discardPrefetch()
        consecutiveErrors++
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            Log.e(TAG, "连续 $consecutiveErrors 个片段播放失败，停止播放")
            teardownSession()
            return
        }
        if (queueIndex + 1 < queue.size) {
            startSegment(queueIndex + 1, 0)
        } else {
            onQueueExhausted(autoStart = true)
        }
    }

    /** 全书播完：清除续听进度，退出前台并停止服务 */
    private fun playbackEnded() {
        Log.d(TAG, "《$bookName》播放完毕")
        clearSavedProgress()
        teardownSession()
    }

    /** 拆除会话：释放播放器、退前台、停服务、通知监听者 */
    private fun teardownSession() {
        playbackGeneration++
        releasePlayer()
        discardPrefetch()
        mainHandler.removeCallbacks(sleepTimerTick)
        cancelFadeOut()
        sleepTimerMode = SLEEP_TIMER_OFF
        sleepTimerDeadline = 0L
        abandonAudioFocus()
        cancelFocusRelease()
        stopPositionTicker()
        unregisterNoisyReceiver()
        pausedByFocusLoss = false
        if (awaitingDownload) {
            awaitingDownload = false
            notifyAwaitingDownload(false)
        }
        queue = emptyList()
        queueIndex = -1
        val hadBook = bookName.isNotEmpty()
        bookName = ""

        updateSessionState()
        notifySleepTimerTick(0L)
        notifyEnded()

        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        // 会话已拆、通知已撤，无论此前有没有真正播过书都要停止自己。
        // 早前只有 hadBook 为真才 stopSelf：被 MediaButton 拉起却从未建立会话的服务
        // 会只撤通知不停服务，剩下 ViewModel 的 BIND_AUTO_CREATE 绑定撑一个空转进程，
        // MediaSession 与回调都不释放。有绑定在时 stopSelf 要等 unbind 才真正结算，
        // 因此这里无条件停是安全的。
        stopSelf()
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        // 清理本段 BLOB 缓存文件（沿用原逻辑，避免长书累积缓存）
        currentAudioFile?.delete()
        currentAudioFile = null
    }

    // ---------- 前台与通知 ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.playback_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun ensureForeground() {
        if (isForeground) {
            refreshNotification()
            return
        }
        startForegroundNow()
    }

    /** 立即以当前播放状态进入前台（幂等，可在已处于前台时重复调用以刷新通知） */
    private fun startForegroundNow() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
        isForeground = true
    }

    @SuppressLint("NotificationPermission")
    private fun refreshNotification() {
        if (!isForeground) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        // Android 12+ 强制要求显式指定可变性；这里不需要系统回填 extras，固定 IMMUTABLE
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        // Android 8+ 禁止后台 startService。本服务由 bindService 拉起、
        // 从未以 startForegroundService 启动，锁屏/切后台时点通知栏按钮属于后台启动，
        // getService 派发的 Intent 会抛 IllegalStateException（按钮无响应甚至崩溃）。
        // 播放时服务本身就在前台，改用 getForegroundService 才是合法路径。
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, action.hashCode(), intent, flags)
        } else {
            PendingIntent.getService(this, action.hashCode(), intent, flags)
        }
    }

    private fun buildNotification(): Notification {
        val playing = isReallyPlaying()
        val segmentNo = currentSegmentNumber()

        // 点击通知回到阅读页
        val contentIntent = Intent(this, BookContentActivity::class.java).apply {
            putExtra("BOOK_TABLE_NAME", bookName)
            ebookFilePath?.let { putExtra("FILE_PATH", it) }
            // 带上正在播的段号（2026-11-01）：阅读页在正文**结构就绪**时就能直接
            // 定位过去，不必等 currentPlayingPosition 回调 —— 后者要等播放进度推送，
            // 期间正文先渲染开头，正在听靠后段落时用户会先看到一眼错的内容。
            // ⚠️ 用独立的 SCROLL_TO_SEGMENT，不能塞进 SCROLL_TO_LINE：后者那条链
            // 第二层是「行号直配」，段号会误命中 lineNumber 相同的行（见 BookViewModel 注释）。
            if (segmentNo > 0) putExtra("SCROLL_TO_SEGMENT", segmentNo)
        }
        val contentPi = PendingIntent.getActivity(
            this,
            bookName.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Android 5.0（API 21）起通知图标一律按 **alpha 蒙版** 渲染——
        // 系统只保留形状、颜色统一刷成单色。framework 的 ic_media_play / ic_media_pause
        // 是彩色 PNG（白色三角 + 暗灰圆角底），按蒙版渲染后整块变成纯白，
        // 状态栏里就只剩一个白方块。改用本工程自带的单色 vector（24×24、纯白 fillColor）。
        // MediaStyle 下的 action 图标同样会被着色，所以 toggleIcon 一并换掉。
        val toggleIcon = if (playing) R.drawable.ic_stat_pause else R.drawable.ic_stat_play
        val toggleTitle = getString(if (playing) R.string.notification_pause else R.string.notification_play)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (playing) R.drawable.ic_stat_play else R.drawable.ic_stat_pause)
            .setContentTitle(bookName)
            .setContentText(getString(R.string.notification_segment, segmentNo))
            .setContentIntent(contentPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.notification_prev), actionIntent(ACTION_PREV))
            .addAction(toggleIcon, toggleTitle, actionIntent(ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_media_next, getString(R.string.notification_next), actionIntent(ACTION_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        return builder.build()
    }

    // ---------- MediaSession 状态同步 ----------

    private fun updateSessionAndNotification() {
        updateSessionMetadata()
        updateSessionState()
        refreshNotification()
    }

    private fun updateSessionMetadata() {
        val segmentNo = currentSegmentNumber()
        val text = if (queueIndex in queue.indices) queue[queueIndex].text else ""
        val duration = try {
            mediaPlayer?.duration?.toLong() ?: 0L
        } catch (_: IllegalStateException) {
            0L
        }
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, bookName)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.notification_segment, segmentNo))
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, text.take(60))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build()
        )
    }

    private fun updateSessionState() {
        val state = when {
            isReallyPlaying() -> PlaybackStateCompat.STATE_PLAYING
            hasActiveSession() -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        val position = try {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } catch (_: IllegalStateException) {
            0L
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP
        // 倍速播放时上报真实速度，外部控制器（蓝牙/系统 UI）据此换算播放进度
        val reportSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) playbackSpeed else PlaybackConfig.DEFAULT_SPEED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) reportSpeed else 0.0f)
                .setActions(actions)
                .build()
        )
        mediaSession.isActive = state != PlaybackStateCompat.STATE_STOPPED
    }

    // ---------- 监听者通知 ----------

    private fun notifyState() {
        val playing = isReallyPlaying()
        val seg = if (hasActiveSession()) currentSegmentNumber() else -1
        listeners.toList().forEach {
            try {
                it.onStateChanged(playing, seg)
            } catch (e: Exception) {
                Log.e(TAG, "listener onStateChanged failed: ${e.message}")
            }
        }
    }

    private fun notifyEnded() {
        listeners.toList().forEach {
            try {
                it.onPlaybackEnded()
            } catch (e: Exception) {
                Log.e(TAG, "listener onPlaybackEnded failed: ${e.message}")
            }
        }
    }

    // 启动时清理上一次会话残留的音频缓存（原 BookViewModel 的职责，随播放器一并迁入）。
    //
    // 早前这里无条件删除 cacheDir 下所有 audio_*.mp3，而播放时 extractAudioFile 写的正是
    // 同目录下的同名前缀文件；清理跑在 IO 协程里，一旦落在「首次 playSegment 之后、
    // 写盘完成之后」的时间窗，就会把刚落盘的这一段删掉，随后 exists() 判定失败，
    // 表现为随机某段没声音，且因时序窗口极窄而几乎无法复现。
    // 改为只删「足够旧」的文件：本次会话写出的文件 mtime 是刚刚，天然被排除在外。
    private fun cleanupStaleAudioCache() {
        val cutoff = System.currentTimeMillis() - STALE_CACHE_AGE_MS
        try {
            cacheDir.listFiles { file -> file.name.startsWith("audio_") && file.name.endsWith(".mp3") }
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "清理残留音频缓存失败: ${e.message}")
        }
    }
}
