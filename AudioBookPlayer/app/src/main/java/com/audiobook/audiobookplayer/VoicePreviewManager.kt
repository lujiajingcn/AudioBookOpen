package com.audiobook.audiobookplayer

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

/**
 * 音色试听：用参考音频（克隆音色）合成一段固定样例句并播放。
 *
 * 复用服务端交互式端点 /api/tts/clone（text + prompt_audio → WAV）。合成结果落临时 WAV
 * 文件后由 MediaPlayer 播放，全程不把音频整段读进堆（沿用工程的「显式限流拷贝」口径）。
 *
 * 同时被「我的参考音频库」列表（ReferenceAudioFragment）与 TTS 页（TtsFragment）复用；
 * 内部只持有一个 MediaPlayer，多个试听不会叠加发声。
 */
object VoicePreviewManager {

    private const val TAG = "VoicePreview"

    /** 试听固定样例句：中性、能体现音色特点的短句（用户无需每次输入） */
    const val PREVIEW_TEXT = "床前明月光，疑是地上霜。举头望明月，低头思故乡。"

    /** 单段试听音频体积上限；样例句很短，正常远小于此，超限视为异常响应并丢弃 */
    private const val MAX_PREVIEW_BYTES = 10L * 1024 * 1024

    @Volatile private var mediaPlayer: MediaPlayer? = null
    /** 当前正在播放的试听标记（用条目 id 区分来源），防止多个试听叠加 */
    @Volatile private var playingToken: Any? = null
    /** 当前试听产生的临时 WAV，播放结束后清理，避免 cacheDir 堆积 */
    private var currentWavFile: File? = null

    /**
     * 用参考音频合成样例句并返回 WAV 临时文件；任何失败返回 null。
     * 必须在 IO 协程调用。
     */
    suspend fun fetchPreviewWav(context: Context, referenceFile: File): File? =
        withContext(Dispatchers.IO) {
            try {
                if (AuthManager.token == null) {
                    Log.w(TAG, "用户未登录，无法试听")
                    return@withContext null
                }
                if (!referenceFile.exists()) {
                    Log.w(TAG, "参考音频文件不存在: ${referenceFile.absolutePath}")
                    return@withContext null
                }
                val textPart = PREVIEW_TEXT.toRequestBody("text/plain".toMediaType())
                val audioPart = MultipartBody.Part.createFormData(
                    "prompt_audio",
                    referenceFile.name,
                    referenceFile.asRequestBody("audio/*".toMediaType())
                )
                val resp = RetrofitClient.heavyApi.previewVoiceClone(textPart, audioPart)
                if (!resp.isSuccessful) {
                    Log.e(TAG, "试听合成失败: HTTP ${resp.code()} ${resp.message()}")
                    return@withContext null
                }
                val body = resp.body() ?: run {
                    Log.e(TAG, "试听合成返回空响应体")
                    return@withContext null
                }
                writeToCache(context, body)
            } catch (e: Exception) {
                Log.e(TAG, "试听合成异常: ${e.message}", e)
                null
            }
        }

    /**
     * 用**预设音色**合成样例句并返回 WAV 临时文件；任何失败返回 null。
     * 必须在 IO 协程调用。
     *
     * 走的是同一个 /api/tts/clone，只是把 `prompt_audio` 换成 `voiceId`。
     * 这样「在线预设音色」也能试听 —— 在此之前用户只能看名字盲选，
     * 而名字恰恰是最不可靠的信息（2026-11-02 UI 走查 P2-5）。
     */
    suspend fun fetchPreviewWavForVoice(context: Context, voiceId: String): File? =
        withContext(Dispatchers.IO) {
            try {
                if (AuthManager.token == null) {
                    Log.w(TAG, "用户未登录，无法试听预设音色")
                    return@withContext null
                }
                if (voiceId.isBlank()) return@withContext null
                val textPart = PREVIEW_TEXT.toRequestBody("text/plain".toMediaType())
                val voicePart = voiceId.toRequestBody("text/plain".toMediaType())
                val resp = RetrofitClient.heavyApi.previewVoiceById(textPart, voicePart)
                if (!resp.isSuccessful) {
                    Log.e(TAG, "预设音色试听失败: HTTP ${resp.code()} ${resp.message()}")
                    return@withContext null
                }
                val body = resp.body() ?: run {
                    Log.e(TAG, "预设音色试听返回空响应体")
                    return@withContext null
                }
                writeToCache(context, body)
            } catch (e: Exception) {
                Log.e(TAG, "预设音色试听异常: ${e.message}", e)
                null
            }
        }

    /**
     * 下载一段**远端原声**到缓存再播放，返回本地文件；任何失败返回 null。
     *
     * 用于「服务器共享参考音频」弹窗里的试听（2026-11-02 UI 走查 P2-3）：
     * 那个弹窗原来只有名字和大小，用户只能看名字盲选。远端音频是流式接口，
     * [MediaPlayer] 直接 setDataSource 拿不到 Authorization 头（接口需登录），
     * 所以先落到 cacheDir 再播。
     *
     * @param fileNameHint 远端文件名，仅用来推断扩展名（部分 ROM 的 MediaPlayer
     *                     对无扩展名的文件判错格式）
     */
    suspend fun fetchRemoteForPreview(
        context: Context,
        fileNameHint: String?,
        fetch: suspend () -> retrofit2.Response<okhttp3.ResponseBody>
    ): File? = withContext(Dispatchers.IO) {
        try {
            val resp = fetch()
            if (!resp.isSuccessful) {
                Log.e(TAG, "试听远端音频失败: HTTP ${resp.code()} ${resp.message()}")
                return@withContext null
            }
            val body = resp.body() ?: return@withContext null
            val ext = fileNameHint?.substringAfterLast('.', "")?.lowercase()
                ?.takeIf { it.isNotEmpty() && it.length <= 5 } ?: "wav"
            writeToCache(context, body, ext, prefix = "remote_preview_")
        } catch (e: Exception) {
            Log.e(TAG, "试听远端音频异常: ${e.message}", e)
            null
        }
    }

    /**
     * 把响应体按「显式限流拷贝」落进 cacheDir，返回临时音频文件。
     * 三条试听路径（克隆 / 预设音色 / 远端原声）共用，
     * 体积上限与清理口径只在这里定义一处。必须在 IO 协程调用。
     */
    private fun writeToCache(
        context: Context,
        body: okhttp3.ResponseBody,
        ext: String = "wav",
        prefix: String = "voice_preview_"
    ): File? {
        val outFile = File(
            context.cacheDir,
            "$prefix${System.currentTimeMillis()}.$ext"
        )
        var total = 0L
        body.byteStream().use { input ->
            FileOutputStream(outFile).use { output ->
                val buf = ByteArray(16 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    total += read
                    if (total > MAX_PREVIEW_BYTES) {
                        outFile.delete()
                        Log.w(TAG, "试听音频超过上限，丢弃")
                        return null
                    }
                    output.write(buf, 0, read)
                }
            }
        }
        if (total == 0L) {
            outFile.delete()
            Log.w(TAG, "试听合成返回空音频")
            return null
        }
        return outFile
    }

    /**
     * 播放试听 WAV。用 [token] 区分来源：若已有别的试听在播，先停掉再播新的。
     * [onState] 在播放开始(true) / 停止或出错(false) 时回调，供 UI 更新按钮文案。
     * 必须在主线程调用（MediaPlayer.prepareAsync 需要 Looper）。
     *
     * @param deleteOnFinish 播放结束后是否删除该文件。
     *   **默认 true**，因为本方法原本只服务"试听合成"——那种 WAV 是临时产物，
     *   不删就会在 cacheDir 里越堆越多。
     *
     *   ⚠️ 但 [ReferenceAudioFragment] 新增的「试听原声」要播放的是
     *   **用户自己的参考音频副本**，绝不能删：那是 filesDir/referenceAudio 下的
     *   正式数据，删掉等于把用户的音色素材弄丢。所以那条路径必须传 false。
     *   这个参数是刻意做成显式开关的——如果只是"顺手复用"这段播放逻辑而不加区分，
     *   就会在播完一个原声之后静默删掉用户的文件，且现象是
     *   "听了一次之后这条参考音频就坏了"，很难第一时间联想到播放器。
     */
    fun playPreview(
        wavFile: File,
        token: Any,
        deleteOnFinish: Boolean = true,
        onState: (Boolean) -> Unit
    ) {
        stopInternal()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(wavFile.absolutePath)
            mp.setOnPreparedListener {
                if (playingToken != token) {
                    // 已被新的试听/停止取代，放弃本次播放
                    safeRelease(it)
                    return@setOnPreparedListener
                }
                try {
                    it.start()
                    onState(true)
                } catch (e: Exception) {
                    Log.e(TAG, "试听播放失败: ${e.message}")
                    safeRelease(it)
                    clearState(deleteOnFinish)
                    onState(false)
                }
            }
            mp.setOnCompletionListener {
                if (playingToken == token) {
                    onState(false)
                }
                safeRelease(it)
                // 仅当这是当前正在播放的那次试听时才清理共享状态，
                // 避免旧的播放器回调误删当前播放的 WAV/置空引用
                if (playingToken == token) {
                    mediaPlayer = null
                    if (deleteOnFinish) {
                        currentWavFile?.let { f -> if (f.exists()) f.delete() }
                    }
                    currentWavFile = null
                    playingToken = null
                }
            }
            mp.setOnErrorListener { mpError, what, extra ->
                Log.e(TAG, "试听播放错误: what=$what extra=$extra")
                if (playingToken == token) onState(false)
                safeRelease(mpError)
                if (playingToken == token) {
                    mediaPlayer = null
                    if (deleteOnFinish) {
                        currentWavFile?.let { f -> if (f.exists()) f.delete() }
                    }
                    currentWavFile = null
                    playingToken = null
                }
                true
            }
            mediaPlayer = mp
            playingToken = token
            currentWavFile = wavFile
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "试听播放准备失败: ${e.message}")
            safeRelease(mp)
            clearState(deleteOnFinish)
            onState(false)
        }
    }

    /** 停止当前试听（用户主动停止或页面销毁时调用） */
    fun stop() {
        stopInternal()
    }

    /**
     * 停止播放。**总是安全**：真正的删除只发生在 [playPreview] 的完成回调里，
     * 且那时是按调用方传入的 deleteOnFinish 决定 ——
     * 所以「试听原声」播放中调用 stop() 不会误删用户的参考音频文件。
     */
    private fun stopInternal() {
        val mp = mediaPlayer
        mediaPlayer = null
        playingToken = null
        try {
            if (mp?.isPlaying == true) mp.stop()
        } catch (_: Exception) { /* ignore */ }
        safeRelease(mp)
        // 注意：这里**不删** currentWavFile。
        //
        // 原实现在这里无条件删，前提是"只有临时试听 WAV 会走这条路"。
        // 加入「试听原声」后该前提不成立：用户点停止就会把参考音频副本删掉。
        // 临时 WAV 的清理改由 [playPreview] 的完成/出错回调按 deleteOnFinish 处理；
        // 未播完就被停止的那份临时 WAV 最多留到下次试听（数量极小，不影响）。
        currentWavFile = null
    }

    private fun clearState(deleteOnFinish: Boolean = true) {
        mediaPlayer = null
        playingToken = null
        if (deleteOnFinish) {
            currentWavFile?.let { if (it.exists()) it.delete() }
        }
        currentWavFile = null
    }

    private fun safeRelease(mp: MediaPlayer?) {
        try {
            mp?.release()
        } catch (_: Exception) { /* 双释放保护 */ }
    }

    /** 当前是否正在播放指定 token 的试听 */
    fun isPlaying(token: Any): Boolean = playingToken == token
}
