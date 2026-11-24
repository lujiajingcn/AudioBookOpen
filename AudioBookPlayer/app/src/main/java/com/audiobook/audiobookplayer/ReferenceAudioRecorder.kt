package com.audiobook.audiobookplayer

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * 录制参考音频的共用组件（MediaRecorder 录制 + MediaPlayer 试听）。
 *
 * **为什么要独立成类**：这段逻辑原先是写死在 TtsFragment（语音合成页）里的，
 * 与页面上的开始/停止/试听/删除四个按钮、一个状态文案强耦合。需求要求把「录制」
 * 挪到参考音频管理页，如果照抄一份就会有两套 MediaRecorder 生命周期要维护
 * （录音资源泄漏、页面切后台、权限回调都很容易两边改漏一处）。
 * 抽成这个类之后，页面只负责把 state 画到自己的控件上。
 *
 * 生命周期约定（调用方必须遵守）：
 *  - [start] 前自行确认 RECORD_AUDIO 权限（本类不做权限判断，只抛异常）；
 *  - 页面 [onStop] 调 [pausePlayback]、[onDestroyView]/[onDestroy] 调 [release]；
 *  - 录音文件放在 cacheDir，[release] 时若仍未落库就会被删掉（避免堆积 .m4a）。
 *
 * 「落库」由调用方负责：录完把 [recordedFile] 交给 ReferenceAudioRepository 存成
 * 正式副本，**存成功后必须调 [markConsumed]** 交出版权 —— 否则 release 时会把
 * 已经属于用户素材的文件删掉。
 */
class ReferenceAudioRecorder(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        /**
         * 状态变化，页面据此刷新自己的控件。
         * @param elapsedSeconds 录制中为已录秒数；否则为 0
         */
        fun onRecorderStateChanged(recording: Boolean, hasRecording: Boolean, elapsedSeconds: Int)

        /** 提示信息（开始/完成/失败），由页面决定用 Toast 还是行内文案 */
        fun onRecorderMessage(message: String)

        /** 试听播放状态变化，页面据此改「试听/停止」按钮文案 */
        fun onPlaybackChanged(playing: Boolean)
    }

    companion object {
        private const val TAG = "RefAudioRecorder"
    }

    private val appContext = context.applicationContext

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null

    var isRecording: Boolean = false
        private set

    /** 录制产物（cacheDir 下的 .m4a）。有值表示「已录完、待落库或待试听」。 */
    var recordedFile: File? = null
        private set

    /** 已由调用方落库接管，release 时不再删除该文件 */
    private var consumed = false

    private var recordTimer: Handler? = null
    private var recordTick: Runnable? = null
    private var recordSeconds = 0

    val hasRecording: Boolean
        get() = recordedFile?.exists() == true

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    /**
     * 开始录音。失败时抛异常（调用方提示用户），不返回半可用状态。
     *
     * 会先清掉上一段未落库的录音 —— 录音是「一次一份」的语义，保留多份只会让
     * 「当前用的是哪一段」变得含糊。
     */
    fun start() {
        stopPlayback()
        discardRecording()

        val dir = appContext.cacheDir ?: throw IllegalStateException("缓存目录不可用")
        val file = File(dir, "recorded_${System.currentTimeMillis()}.m4a")

        val recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(file.absolutePath)
        }

        try {
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败: ${e.message}", e)
            runCatching { recorder.release() }
            runCatching { file.delete() }
            throw IllegalStateException("录音启动失败，请检查麦克风是否被占用", e)
        }

        mediaRecorder = recorder
        recordedFile = file
        consumed = false
        isRecording = true
        recordSeconds = 0

        recordTick = object : Runnable {
            override fun run() {
                if (!isRecording) return
                recordSeconds++
                listener.onRecorderStateChanged(true, hasRecording, recordSeconds)
                recordTimer?.postDelayed(this, 1000)
            }
        }
        recordTimer = Handler(Looper.getMainLooper()).also { it.post(recordTick!!) }

        listener.onRecorderStateChanged(true, hasRecording, recordSeconds)
        listener.onRecorderMessage("开始录音")
    }

    /** 停止录音。返回是否录到了有效文件。 */
    fun stop(): Boolean {
        if (!isRecording) return hasRecording
        mediaRecorder?.let { r ->
            // stop() 在「启动后立刻停止」等情况下会抛 RuntimeException：
            // 此时文件里没有有效数据，交由下面的 exists() 判定为失败即可，不必上抛
            runCatching { r.stop() }.onFailure { Log.e(TAG, "停止录音失败: ${it.message}", it) }
            runCatching { r.release() }
        }
        mediaRecorder = null
        isRecording = false
        stopTimer()

        val ok = hasRecording
        if (!ok) {
            recordedFile = null
            listener.onRecorderMessage("录音文件保存失败")
        } else {
            listener.onRecorderMessage("录音完成")
        }
        listener.onRecorderStateChanged(false, ok, 0)
        return ok
    }

    /** 试听已录的音频；正在播放则停止。 */
    fun togglePlayback() {
        if (isPlaying) {
            stopPlayback()
            return
        }
        val file = recordedFile ?: return
        if (!file.exists()) {
            listener.onRecorderMessage("录音文件不存在")
            return
        }
        try {
            mediaPlayer?.release()
            // 先构造局部变量再赋给字段：MediaPlayer().apply { setDataSource(...) } 在
            // setDataSource 抛 IOException 时实例不被字段引用，catch 里无法释放
            val mp = MediaPlayer()
            try {
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener {
                    if (mediaPlayer !== mp) return@setOnPreparedListener
                    // 防后台自动播放：prepareAsync 未完成时切后台，prepared 回调直接 start()
                    // 会让声音在后台响起。这里校验生命周期，后台就放弃自动播放
                    if (isStarted) {                        mp.start()
                        mp.isLooping = false
                        listener.onPlaybackChanged(true)
                    } else {
                        mp.release()
                        mediaPlayer = null
                        listener.onPlaybackChanged(false)
                    }
                }
                mp.setOnCompletionListener {
                    if (mediaPlayer === mp) {
                        mp.release()
                        mediaPlayer = null
                        listener.onPlaybackChanged(false)
                    }
                }
                mp.setOnErrorListener { err, _, _ ->
                    if (mediaPlayer === err) {
                        err.release()
                        mediaPlayer = null
                        listener.onPlaybackChanged(false)
                    }
                    true
                }
                mediaPlayer = mp
                mp.prepareAsync()
            } catch (e: Exception) {
                runCatching { mp.release() }
                mediaPlayer = null
                listener.onPlaybackChanged(false)
                Log.e(TAG, "试听初始化失败: ${e.message}", e)
                listener.onRecorderMessage("试听失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "试听失败: ${e.message}", e)
            listener.onRecorderMessage("试听失败")
        }
    }

    /**
     * 由调用方设置「当前是否处于 STARTED 及以后」。
     * 抽成回调而不是让本类持有 LifecycleOwner：调用方在 onStart/onStop 里改，
     * 本类只管用，不需要关心是 Fragment 还是 Activity。
     */
    var isStarted: Boolean = false

    /** 页面进入后台时调用：停止试听，但不打断录音（录音应当能在后台继续） */
    fun pausePlayback() {
        stopPlayback()
    }

    /** 删除未落库的录音 */
    fun discardRecording() {
        stopPlayback()
        recordedFile?.let { if (it.exists()) it.delete() }
        recordedFile = null
        recordSeconds = 0
        consumed = false
    }

    /**
     * 交出版权：录音已由调用方落库（复制进私有目录并登记），release 时不再删它。
     * 调用后 [recordedFile] 置空，避免调用方误以为还能继续用缓存里那份。
     */
    fun markConsumed() {
        stopPlayback()
        consumed = true
        recordedFile = null
        recordSeconds = 0
    }

    /**
     * 释放全部资源。页面销毁时必须调用。
     * 未落库（[markConsumed] 未调用）的录音缓存文件在这里删掉，避免 cacheDir 堆积 .m4a。
     */
    fun release() {
        if (isRecording) {
            mediaRecorder?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            mediaRecorder = null
            isRecording = false
        }
        stopTimer()
        stopPlayback()
        if (!consumed) {
            recordedFile?.let { if (it.exists()) it.delete() }
        }
        recordedFile = null
        recordSeconds = 0
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        mediaPlayer = null
        listener.onPlaybackChanged(false)
    }

    private fun stopTimer() {
        recordTimer?.removeCallbacksAndMessages(null)
        recordTimer = null
        recordTick = null
    }
}
