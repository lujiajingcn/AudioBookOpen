package com.audiobook.audiobookplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 单词朗读的极简封装：读一个词，并把「引擎有没有、能不能用」的状态汇报给调用方。
 *
 * ## 为什么是单例 + 异步就绪，而不是每次 new 一个 TextToSpeech
 *
 * 1. 构造会**同步**向系统 TTS 服务发起绑定，服务忙时耗时可达数百毫秒 ——
 *    放在点击回调里直接掉帧。首次 [prepare] 后引擎常驻，后续 [speak] 只是入队，微秒级。
 * 2. 用户连点几个单词时每次都新建 ⇒ 不断与 TTS 服务反复绑定/解绑，
 *    表现为「越点越慢」，甚至被系统限流。
 * 3. 引擎初始化是**异步回调**（[TextToSpeech.OnInitListener]）。第一次点单词时可能还没就绪，
 *    直接返回失败会让用户觉得「这功能坏了」；所以维护 [initializing] 与 [pendingWord]，
 *    未就绪就把词存下，就绪后自动补播。
 *
 * ## 线程约束（框架要求，不可协商）
 * [TextToSpeech] 的构造与 [speak]/[stop] 都必须在**主线程**，否则部分 ROM 抛
 * `CalledFromWrongThreadException`。本类对外的 [speak]/[stop] 都会切回主线程，
 * 调用方无需关心自己在哪条线程。
 *
 * ## 降级路径（本工程允许「没引擎也能用」）
 * 设备可能压根没装 TTS 引擎（部分国产 ROM、精简系统、**不带 Google TTS 的模拟器镜像**）。
 * 此时 [onInit] 收到 ERROR ⇒ [isAvailable] 恒 false ⇒ [speak] 返回 false，
 * 由调用方弹 Toast 提示。**查词释义本身不依赖引擎**，朗读是增强而非前置条件。
 *
 * ## 语言固定 [Locale.US]，不用 [Locale.getDefault]
 * 查的是 ECDICT **英汉**词、词本身是英文。用设备默认 locale 时，
 * 中文手机上会拿中文语音念英文单词（部分引擎直接回落成默读），发音明显不对。
 */
object TextToSpeaker {

    private const val TAG = "TextToSpeaker"

    /**
     * 连点时后者赢：QUEUE_FLUSH 会打断上一声。
     * 单个词不需要 QUEUE_ADD 排队 —— 用户连点三个词，他要的是最后那个。
     */
    private const val QUEUE_MODE = TextToSpeech.QUEUE_FLUSH

    /** 略慢于默认。单词朗读场景下慢一点更清楚，也更像"念给你听" */
    private const val SPEECH_RATE = 0.92f

    private var tts: TextToSpeech? = null

    /** 引擎是否已成功初始化。false 期间 [speak] 走排队或直接失败 */
    @Volatile
    var isAvailable: Boolean = false
        private set

    /** 已发起 init 但回调未到的中间态；用来避免重复构造 */
    @Volatile
    private var initializing: Boolean = false

    /** 已彻底失败过（收到过 ERROR / 构造抛异常）。避免每次点击都重试惊动 TTS 服务 */
    @Volatile
    private var failed: Boolean = false

    /**
     * init 就绪前攒下的待播单词。
     *
     * **只留最后一个**：连点时前一个已被 QUEUE_FLUSH 打断，没有保留价值。
     * 读多线程只经主线程（[speak] 已切主线程），不需要额外加锁。
     */
    private var pendingWord: String? = null

    /** [pendingWord] 有值的标记。空串是合法的「用户读了空词」，不能靠值本身判断 */
    private var hasPending = false

    /**
     * 确保引擎就绪（幂等，可重复调）。
     *
     * 已就绪 ⇒ 立刻回调 [onReady]；正在初始化 ⇒ 直接返回（回调会在就绪时触发，
     * 但**不**再调 onReady —— 调用方若需要「等一下」，应走 [speak] 的排队路径）。
     *
     * @param onReady 仅在**本次调用把状态推进到就绪**时触发一次
     *   （幂等重入时同样会回调，便于「打开页面先预热 + 立刻用」的写法）。
     */
    fun prepare(context: Context, onReady: (() -> Unit)? = null) {
        if (isAvailable) {
            onReady?.invoke()
            return
        }
        if (initializing || failed) return

        initializing = true
        val appContext = context.applicationContext
        try {
            tts = TextToSpeech(appContext) { status ->
                initializing = false
                if (status != TextToSpeech.SUCCESS) {
                    failed = true
                    Log.w(TAG, "TTS 引擎初始化失败（status=$status），本机可能未安装语音引擎")
                    return@TextToSpeech
                }
                val t = tts
                if (t == null) {
                    failed = true
                    return@TextToSpeech
                }
                // ⚠️ setLanguage 返回 LANG_MISSING_DATA / LANG_NOT_SUPPORTED 时，
                //   speak() 会**静默不出声**（不报错、不回调）。必须显式判一次，
                //   否则「引擎在但不支持英语」会被误当成可用，用户只看到点了没声音。
                val langResult = runCatching { t.setLanguage(Locale.US) }.getOrNull()
                isAvailable = langResult != TextToSpeech.LANG_MISSING_DATA &&
                    langResult != TextToSpeech.LANG_NOT_SUPPORTED
                if (!isAvailable) {
                    Log.w(TAG, "TTS 引擎不支持英语（setLanguage=$langResult），朗读不可用")
                    return@TextToSpeech
                }
                t.setSpeechRate(SPEECH_RATE)
                attachDiagnostics()
                // 就绪了：把初始化期间攒下的词补播掉，别让用户白点一次
                val pending = pendingWord
                if (hasPending) {
                    pendingWord = null
                    hasPending = false
                    if (pending != null) enqueueSpeak(t, pending)
                }
                onReady?.invoke()
            }
        } catch (e: Throwable) {
            // 少数 ROM 上构造本身会抛（TTS 服务被禁用 / 安全策略拦截）
            initializing = false
            failed = true
            Log.w(TAG, "构造 TextToSpeech 失败", e)
        }
    }

    /**
     * 读一个单词。可从任意线程调用。
     *
     * @return true = 已入队（或正在初始化，词已排好队就绪后补播）；
     *         false = 引擎不可用（调用方应提示用户去装语音引擎）。
     */
    fun speak(context: Context, word: String): Boolean {
        val w = word.trim()
        if (w.isEmpty()) return false
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return speakOnMain(appContext, w)
        }
        // 非主线程：不阻塞调用方（UI 层），结果由主线程那次调用决定
        Handler(Looper.getMainLooper()).post { speakOnMain(appContext, w) }
        return true
    }

    /** 停止朗读（离开阅读页时调用，避免词还在念）。可从任意线程调用。 */
    fun stop() {
        Handler(Looper.getMainLooper()).post {
            runCatching { tts?.stop() }
        }
    }

    /**
     * 发声路径的同步部分，**必须在主线程**。
     *
     * 引擎不可用时的处理分两种：
     *  · 还在初始化中 ⇒ 把词存进 [pendingWord]，就绪回调里补播（用户不必再点一次）；
     *  · 已经 failed ⇒ 立刻返回 false 让调用方提示，绝不吞掉（否则用户只看到没声音、
     *    分不清是没引擎还是没这个功能）。
     *
     * @return 是否真的发出了声音（排队补播也算 true）
     */
    private fun speakOnMain(context: Context, word: String): Boolean {
        prepare(context)
        if (!isAvailable) {
            if (initializing) {
                pendingWord = word
                hasPending = true
                // 已排队，就绪后会补播 ⇒ 对调用方算成功
                return true
            }
            return false
        }
        val t = tts ?: return false
        return enqueueSpeak(t, word)
    }

    /**
     * 入队发音（**调用方必须在主线程**）。
     *
     * 用 API 21+ 的 utteranceId 重载：minSdk 是 24，这条恒成立；
     * 旧的 `(CharSequence, int, HashMap)` 重载在部分 ROM 上不回调 onDone。
     *
     * `params` 传 null：不走 PlaybackService 的流控（那是给有声书播放用的，
     * 单个词不需要被媒体会话管起来）。
     */
    private fun enqueueSpeak(t: TextToSpeech, word: String): Boolean = try {
        t.speak(word, QUEUE_MODE, null, "pr-${System.nanoTime()}")
        true
    } catch (e: Throwable) {
        Log.w(TAG, "speak 失败：$word", e)
        false
    }

    /**
     * 朗读结束/出错的诊断日志。
     *
     * 只记日志不做界面反馈：单词读完用户没有可操作的事，
     * 弹「读完了」反而打断阅读。
     */
    private fun attachDiagnostics() {
        val t = tts ?: return
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            @Deprecated("API 21 起的默认实现，保留是为了兼容旧签名")
            override fun onDone(utteranceId: String?) {}

            override fun onError(utteranceId: String?) {
                Log.w(TAG, "朗读出错：$utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "朗读出错：$utteranceId code=$errorCode")
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {}
        })
    }

    /**
     * 释放引擎。
     *
     * 只在应用真正退出时调（进程结束系统会回收，页面销毁**不要**调）——
     * 单例要活到进程结束，否则退出阅读页再进来就得重新绑定。
     */
    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        isAvailable = false
        initializing = false
        failed = false
        pendingWord = null
        hasPending = false
    }
}