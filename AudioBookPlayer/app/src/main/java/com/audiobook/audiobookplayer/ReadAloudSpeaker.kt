package com.audiobook.audiobookplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 阅读页「实时朗读」控制器：用系统 [TextToSpeech] 把电子书正文念出来。
 *
 * ## 与 [TextToSpeaker]（英文单词点读）的区别
 *  · 语言固定 [Locale.SIMPLIFIED_CHINESE]（中文正文）；
 *  · 用 [TextToSpeech.QUEUE_ADD] 把**逐句**串联成一串朗读，而不是 QUEUE_FLUSH 只念最后一个；
 *  · 带逐句进度回调（高亮到句 + 滚动当前行）、暂停 / 继续 / 停止、段落跳转；
 *  · **不写文件、不入库** —— 音频由 TTS 引擎直接播放，播完即弃（这是本类的核心约束，
 *    区别于「整本合成落库」那套做法）。
 *
 * ## 沿用 [TextToSpeaker] 的成熟范式
 * 单例 + 异步就绪（[TextToSpeech.OnInitListener]）+ 主线程约束（构造与 speak/stop 都必须
 * 在主线程，否则部分 ROM 抛 CalledFromWrongThreadException）+ 无引擎时降级。
 *
 * ## 窗口式入队（2026-10-18 重写，跳段异常的根因修复）
 * 旧实现每次起播/跳转都把**全书剩余行**一次性入队（本书 2508 行 ⇒ 单次 2500+ 条 utterance）。
 * 真机日志证实这会让每次 `tts.stop()`（暂停/跳转/起播）冲刷 2500+ 条队列项：主线程被
 * onStop 回调风暴堵死（入队速率从 ~600 行/0.5s 掉到 ~10 行/0.5s），引擎侧队列深度与
 * 主线程拥塞叠加出未定义状态——曾两次观察到朗读头被冲到全书末尾（currentLine≈2502）。
 *
 * 现改为**窗口式入队**：只向引擎提交 [QUEUE_WINDOW] 条，播到窗口尾部（余量
 * [TOPUP_MARGIN] 条）时再补下一窗。stop/flush 最多波及 ~25 条，风暴与未定义状态随之消失。
 *
 * ## 逐句入队（2026-11-02，UI 走查 P2-10 的句级高亮）
 * 入队单位是**句子**而不是行（[SpeakUnit]）：一行常常是一整个自然段，按行朗读时高亮只能
 * 整段亮起，用户看不出"念到第几句"。
 *
 * 关键取舍：句级进度**不能靠引擎**。先前的实现用 `onRangeStart`（API 26+）取发音区间，
 * 该回调要求引擎主动实现 —— 真机 K30 Pro（`com.xiaomi.mibrain.speech`）实测朗读 20 余秒、
 * 几十条 utterance，`onRangeStart` 一次都没回调，高亮等于没改。改成「一条 utterance =
 * 一句话」后，`onStart` 本身就精确对应一句，**任何引擎都能工作**。
 * 代价是句间可能有极短的引擎停顿，远小于"看不出念到哪儿"的代价。
 *
 * ## 代数（epoch）过滤陈旧回调
 * 跳转/暂停会 stop() 清队，引擎对**已被清掉**的 utterance 仍可能补发 onStart/onDone
 * （乱序、且可能带全书最大下标）。utteranceId 带上 `ra-<epoch>-<单元号>`（单元=一句），回调代数与
 * 当前不一致即丢弃——陈旧回调再也无法把 currentLine 冲向末尾或误触发「读完」。
 *
 * ## 降级路径（本工程允许「没引擎也能读正文」）
 * 设备可能没装 TTS 引擎、或引擎不支持中文（常见于国产 ROM 只带英文引擎）。
 * 这时 [prepare] 的 onUnavailable 回调触发，由调用方弹 Toast 指引装中文语音包。
 */
object ReadAloudSpeaker {

    private const val TAG = "ReadAloudSpeaker"

    /** 串联逐句：QUEUE_ADD 让多句排队依次播，而不是互相打断 */
    private const val QUEUE_MODE = TextToSpeech.QUEUE_ADD

    /** 朗读语速：1.0 = 引擎默认值 */
    private const val SPEECH_RATE = 1.0f

    /** 单次入队的最大**句数**（窗口大小）。队列深度有界 ⇒ stop 冲刷有界、主线程无风暴 */
    private const val QUEUE_WINDOW = 20

    /** 播到距窗口尾部还剩几句时补下一窗（留足主线程 post + 入队的时间，不断档） */
    private const val TOPUP_MARGIN = 3

    private var tts: TextToSpeech? = null

    /** 引擎是否已成功初始化且支持中文。false 期间 [start] 返回 false */
    @Volatile
    var isAvailable: Boolean = false
        private set

    @Volatile
    private var initializing: Boolean = false

    /** 已彻底失败过（收过 ERROR / 构造抛异常 / 不支持中文）。避免每次点击都重试惊动 TTS 服务 */
    @Volatile
    private var failed: Boolean = false

    /** 是否正在朗读（含暂停态） */
    @Volatile
    var isActive: Boolean = false
        private set

    /** 是否处于暂停态（被 pause() 打断、等待 resume） */
    @Volatile
    var isPaused: Boolean = false
        private set

    /** 当前正在朗读的行在传入列表里的下标；-1 = 未开始 */
    @Volatile
    var currentLine: Int = -1
        private set

    @Volatile
    private var lines: List<String> = emptyList()

    /**
     * 入队代数：每次 start / jumpTo / pause 递增并写入 utteranceId。
     * 回调里代数不符 ⇒ 该 utterance 属于已被清掉的旧队列，直接丢弃。
     */
    @Volatile
    private var epoch: Int = 0

    @Volatile
    private var onLine: ((Int) -> Unit)? = null

    /**
     * 句级进度回调：（行下标，行内起点，行内终点），**闭区间**，坐标以传入的行原文为准。
     *
     * 由**逐句入队**驱动（见 [SpeakUnit]）：每条 utterance 就是一句，`onStart` 一到
     * 就精确知道"正在读哪一句"，不依赖引擎上报字符区间。
     *
     * ⚠️ 早先的实现靠 `onRangeStart`（API 26+）拿发音区间再归到句子上。该回调要求
     * 引擎**主动实现**，真机（K30 Pro / `com.xiaomi.mibrain.speech`）实测一次都不回调，
     * 高亮便一直停在整行 —— 症状与修复前完全一样，等于没修。
     */
    @Volatile
    private var onSentence: ((Int, Int, Int) -> Unit)? = null

    /** 一条待朗读单元 = 一句话：所属行、行内闭区间、送进引擎的文本 */
    private data class SpeakUnit(val row: Int, val start: Int, val end: Int, val text: String)

    /** 全书切好的句子队列（[start] 时一次性构建） */
    @Volatile
    private var units: List<SpeakUnit> = emptyList()

    /** 最后一条可朗读单元的下标（全书读完的判据） */
    @Volatile
    private var lastUnit: Int = -1

    /** 下一条待入队单元的下标（入队窗口的右边界，不含） */
    @Volatile
    private var enqueuedUnitUpTo: Int = 0

    /** 当前正在朗读的**单元**下标；-1 = 未开始。暂停后续读要从这里接，不能退回到行首句 */
    @Volatile
    private var currentUnit: Int = -1

    @Volatile
    private var onFinished: (() -> Unit)? = null

    /**
     * 每行入队时 trim 掉的**前缀空白长度**（行下标 → 偏移）。
     *
     * ⚠️ 自「逐句入队」改造后**已不再需要**：句子区间本身就是相对行原文算出来的
     * （[ReadAloudSentences.sentenceRanges] 会跳过行首空白），不存在"入队文本被 trim、
     * 回调坐标要补偏移"的问题。保留这段说明是为了防止有人照着旧注释又把它加回来。
     */

    /**
     * 确保引擎就绪（幂等，可重复调）。
     *
     * @param onReady     本次调用把状态推进到就绪时触发一次（已就绪则**立刻在主线程**触发）
     * @param onUnavailable 引擎缺失 / 不支持中文时触发（用于弹 Toast 指引装语音包）
     */
    fun prepare(context: Context, onReady: () -> Unit, onUnavailable: () -> Unit) {
        val appContext = context.applicationContext
        if (isAvailable) {
            runOnMain(onReady)
            return
        }
        if (failed) {
            runOnMain(onUnavailable)
            return
        }
        if (initializing) return

        initializing = true
        try {
            tts = TextToSpeech(appContext) { status ->
                initializing = false
                if (status != TextToSpeech.SUCCESS) {
                    failed = true
                    Log.w(TAG, "TTS 引擎初始化失败（status=$status），本机可能未安装语音引擎")
                    runOnMain(onUnavailable)
                    return@TextToSpeech
                }
                val t = tts
                if (t == null) {
                    failed = true
                    runOnMain(onUnavailable)
                    return@TextToSpeech
                }
                // ⚠️ setLanguage 返回 LANG_MISSING_DATA / LANG_NOT_SUPPORTED 时，speak() 会
                //  静默不出声（不报错、不回调）。必须显式判一次，否则「引擎在但不支持中文」
                //  会被误当成可用，用户只看到点了没声音。
                val langResult = runCatching { t.setLanguage(Locale.SIMPLIFIED_CHINESE) }.getOrNull()
                isAvailable = langResult != TextToSpeech.LANG_MISSING_DATA &&
                    langResult != TextToSpeech.LANG_NOT_SUPPORTED
                if (!isAvailable) {
                    failed = true
                    Log.w(TAG, "TTS 引擎不支持中文（setLanguage=$langResult），无法朗读")
                    runOnMain(onUnavailable)
                    return@TextToSpeech
                }
                t.setSpeechRate(SPEECH_RATE)
                attachProgress()
                runOnMain(onReady)
            }
        } catch (e: Throwable) {
            // 少数 ROM 上构造本身会抛（TTS 服务被禁用 / 安全策略拦截）
            initializing = false
            failed = true
            Log.w(TAG, "构造 TextToSpeech 失败", e)
            runOnMain(onUnavailable)
        }
    }

    /**
     * 开始朗读：从 [lines] 的 [fromIndex] 行起，**逐句** QUEUE_ADD 串联（窗口式，见类注释）。
     *
     * 入队单位是**句**而不是行：一条 utterance = 一句话，`onStart` 就能精确告诉调用方
     * "此刻念的是第几行的第几个字到第几个字"，句级高亮因此不依赖引擎能力。
     * 空行、纯空白句跳过（不朗读、不回调），行下标保持与调用方传入的列表一致。
     *
     * @return true = 已入队；false = 引擎不可用（调用方应先 [prepare]）
     */
    fun start(
        lines: List<String>,
        fromIndex: Int,
        onLine: (Int) -> Unit,
        onFinished: () -> Unit,
        onSentence: ((Int, Int, Int) -> Unit)? = null
    ): Boolean {
        if (!isAvailable) return false
        val t = tts ?: return false
        this.lines = lines
        this.onLine = onLine
        this.onSentence = onSentence
        this.onFinished = onFinished
        isActive = true
        isPaused = false
        currentLine = -1
        currentUnit = -1
        units = buildUnits(lines)
        lastUnit = units.lastIndex
        epoch++ // 旧队列的回调全部作废
        val fromUnit = firstUnitAtRow(fromIndex)

        // 主线程入队（TextToSpeech.speak 必须在主线程）
        runOnMain {
            t.stop() // 清空可能残留的队列
            if (!enqueueFrom(t, fromUnit)) {
                // 没有可朗读的内容（全是空行 / 起点就在末尾之后）
                isActive = false
                onFinished()
            }
        }
        return true
    }

    /** 暂停：停掉当前朗读并清空队列，记住当前行以便继续（不是停止） */
    fun pause() {
        if (!isActive || isPaused) return
        epoch++ // 冲刷产生的 onStart/onDone 不得被当作进度或"读完"
        runOnMain { tts?.stop() }
        isPaused = true
    }

    /**
     * 继续：从被暂停的那**一句**接着读。
     *
     * 不退回行首：一行常有 3~6 句，暂停在段落中间时从头重念整段会很吵，
     * 也与高亮位置对不上。句子队列在 pause 期间保持不变，直接从 [currentUnit] 起补队列。
     */
    fun resume() {
        if (!isActive || !isPaused) return
        val t = tts ?: return
        isPaused = false
        val from = when {
            currentUnit in units.indices -> currentUnit
            else -> firstUnitAtRow(currentLine.coerceAtLeast(0))
        }
        epoch++ // 暂停前入队的 utterance 回调全部作废
        runOnMain {
            t.stop()
            if (!enqueueFrom(t, from)) {
                isActive = false
                onFinished?.invoke()
            }
        }
    }

    /** 已入队的总行数（底部播放栏跳段要做边界检查：越界即首/末段） */
    val lineCount: Int
        get() = lines.size

    /**
     * 跳转到指定**行**继续朗读（底部播放栏「上一段 / 下一段」，2026-10-18）。
     *
     * 与 [resume] 的分工：resume 在被暂停的行**续读**；本方法**换行**——停掉当前行与
     * 整个队列，从 [index] 行的**第一句**起重新入队，并**立即**回调一次（高亮/滚动马上
     * 跟上，不等引擎 onStart）。暂停态调用会顺带解除暂停（跳转即继续读）。
     *
     * 跳转单位与自动播放一致：一次一行（一个段落）——即「与自动播放时跳转的范围相同」。
     * 空行跳过规则同 [start]：index 本身是空行时规范到其后第一个非空行，高亮不会落空。
     *
     * @return true = 已跳（含"往后全是空行、按读完收场"）；false = 引擎不可用 / 列表为空
     */
    fun jumpTo(index: Int): Boolean {
        if (!isAvailable) return false
        val t = tts ?: return false
        if (lines.isEmpty()) return false
        // jumpTo 也可能在 start 之前被调（例如恢复现场的顺序变了）：按需补建句子队列
        if (units.isEmpty()) {
            units = buildUnits(lines)
            lastUnit = units.lastIndex
        }
        val target = index.coerceIn(0, lines.lastIndex)
        val targetUnit = firstUnitAtRow(target)
        if (targetUnit > lastUnit) {
            // 目标段往后全是空行 ⇒ 没有可读内容，按"读完"收场
            isActive = false
            isPaused = false
            runOnMain { onFinished?.invoke() }
            return true
        }
        isActive = true
        isPaused = false
        epoch++ // 旧队列（含可能带大下标的陈旧回调）全部作废
        runOnMain {
            t.stop() // 停掉当前行并清空队列（窗口式：最多 ~QUEUE_WINDOW 条）
            if (!enqueueFrom(t, targetUnit)) {
                isActive = false
                onFinished?.invoke()
                return@runOnMain
            }
            val unit = units[targetUnit]
            currentUnit = targetUnit
            currentLine = unit.row
            notifyUnit(unit)
        }
        return true
    }

    /**
     * 从 [from]（**单元**下标，不是行下标）起依次入队，最多 [QUEUE_WINDOW] 句
     * （[start] 与 [jumpTo] 共用）。
     *
     * 窗口式入队的原因见类注释：整本入队会让每次 stop() 冲刷数千条队列项，主线程被
     * onStop 风暴堵死，且曾把朗读头冲到全书末尾。改成逐句后一条 utterance 变短，
     * 但队列里的**条数**仍是 [QUEUE_WINDOW] 条 —— 这正是当年出事时以条数计的量纲，
     * 所以不能因为"一句更短"就成倍放大窗口。
     *
     * @return 是否有内容入队；**必须在主线程调用**（TextToSpeech.speak 的线程约束）
     */
    private fun enqueueFrom(t: TextToSpeech, from: Int): Boolean {
        var queued = 0
        var i = from.coerceAtLeast(0)
        while (i <= lastUnit && queued < QUEUE_WINDOW) {
            val unit = units[i]
            Log.i(TAG, "enqueue unit $i (row ${unit.row} ${unit.start}..${unit.end}): ${unit.text.take(24)}")
            val id = "ra-$epoch-$i"
            try {
                t.speak(unit.text, QUEUE_MODE, null, id)
                queued++
            } catch (e: Throwable) {
                Log.w(TAG, "speak 失败：单元 $i（行 ${unit.row}）", e)
            }
            i++
        }
        enqueuedUnitUpTo = i
        return queued > 0
    }

    /** 补下一窗：播到距窗口尾部 [TOPUP_MARGIN] 句时由 onDone 触发（主线程执行） */
    private fun topUp() {
        val t = tts ?: return
        if (enqueuedUnitUpTo <= lastUnit) {
            enqueueFrom(t, enqueuedUnitUpTo)
        }
    }

    /**
     * 把每行切成句子，展开成一条线性单元队列。
     *
     * 空行不产生单元（不朗读）；行下标保留在 [SpeakUnit.row] 里，调用方据此回落到
     * 原列表。切句规则见 [ReadAloudSentences]。
     */
    private fun buildUnits(lines: List<String>): List<SpeakUnit> {
        val out = ArrayList<SpeakUnit>(lines.size * 2)
        for (i in lines.indices) {
            val raw = lines[i]
            if (raw.isBlank()) continue
            for (r in ReadAloudSentences.sentenceRanges(raw)) {
                val text = raw.substring(r.first, r.last + 1)
                if (text.isBlank()) continue
                out.add(SpeakUnit(row = i, start = r.first, end = r.last, text = text))
            }
        }
        return out
    }

    /** 行下标 → 该行第一句在单元队列里的位置；该行及之后都没内容时返回 units.size */
    private fun firstUnitAtRow(row: Int): Int {
        val target = row.coerceAtLeast(0)
        for (idx in units.indices) {
            if (units[idx].row >= target) return idx
        }
        return units.size
    }

    /**
     * 通知调用方「现在读到某个单元的哪一段」。
     *
     * 有句级回调就走句级（[onSentence]），没有就退回行级（[onLine]）——
     * 后者只抹平高亮精度，不改变朗读本身。
     */
    private fun notifyUnit(unit: SpeakUnit) {
        val sentence = onSentence
        if (sentence != null) sentence(unit.row, unit.start, unit.end) else onLine?.invoke(unit.row)
    }

    /**
     * 重新绑定逐句/结束回调（v5/A4）。
     *
     * <p><b>为什么需要它</b>：本类是 {@code object} 单例，回调存在这里。旋转屏幕后
     * Activity 重建，旧实例被销毁、但单例仍持有**旧实例的 lambda**（捕获 Activity）——
     * 既泄漏整页 View 树，又让新实例收不到 {@code onLine}（无高亮）、收不到
     * {@code onFinished}（播完不收起播放条）。新实例在 onCreate 里调本方法接管。
     *
     * <p>未在朗读时是 no-op（不凭空造出一个"正在朗读"的状态）。
     */
    fun rebind(
        onLine: (Int) -> Unit,
        onFinished: () -> Unit,
        onSentence: ((Int, Int, Int) -> Unit)? = null
    ) {
        if (!isActive) return
        this.onLine = onLine
        this.onFinished = onFinished
        this.onSentence = onSentence
    }

    /**
     * 解除回调绑定（v5/A4）：页面销毁时**无条件**调用。
     *
     * <p>与 [stop] 的区别：stop 会结束朗读；这里只是"断开这根线"，朗读可以继续
     * （旋转时正需要这个语义）。回调本身都是 null 安全调用，断开期间不会崩。
     */
    fun detachCallbacks() {
        onLine = null
        onSentence = null
        onFinished = null
    }

    /** 停止：彻底结束朗读并清状态（离开页面 / 用户主动停止时调用，不释放引擎） */
    fun stop() {
        runOnMain { tts?.stop() }
        epoch++ // 冲刷中的回调不得再写 currentLine / 触发 onFinished
        isActive = false
        isPaused = false
        currentLine = -1
        currentUnit = -1
        lines = emptyList()
        units = emptyList()
        lastUnit = -1
        enqueuedUnitUpTo = 0
        onLine = null
        onSentence = null
        onFinished = null
    }

    /**
     * 释放引擎。仅应用退出时调（进程结束系统会回收）；页面销毁**不要**调，
     * 否则下次进阅读页得重新绑定。
     */
    fun shutdown() {
        stop()
        runOnMain { runCatching { tts?.shutdown() } }
        tts = null
        isAvailable = false
        initializing = false
        failed = false
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    /** 解析 utteranceId "ra-<epoch>-<单元号>"；格式不符返回 null */
    private fun parseUtteranceId(utteranceId: String?): Pair<Int, Int>? {
        val rest = utteranceId?.removePrefix("ra-") ?: return null
        val dash = rest.indexOf('-')
        if (dash <= 0 || dash == rest.length - 1) return null
        val ep = rest.substring(0, dash).toIntOrNull() ?: return null
        val idx = rest.substring(dash + 1).toIntOrNull() ?: return null
        return ep to idx
    }

    /**
     * 进度监听：每行开始播时回调当前下标（高亮 + 滚动）；最后一行播完触发 onFinished；
     * 播到窗口尾部补下一窗（[topUp]）。在 [prepare] 初始化成功后才挂上（tts 此时非空）。
     *
     * <p>所有回调先过**代数校验**（[parseUtteranceId]）：跳转/暂停前入队的 utterance
     * 其回调一律丢弃——它们可能乱序补发，甚至带全书最大下标，曾把 currentLine 冲爆。
     */
    private fun attachProgress() {
        val t = tts ?: return
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val (ep, idx) = parseUtteranceId(utteranceId) ?: return
                if (ep != epoch) return // 陈旧队列的回调，丢弃
                val unit = units.getOrNull(idx) ?: return
                currentUnit = idx
                currentLine = unit.row
                // 单元 = 一句 ⇒ 这里天然就是句级进度，不需要引擎上报字符区间
                notifyUnit(unit)
            }

            @Deprecated("API 21 起的默认实现，保留以兼容旧签名")
            override fun onDone(utteranceId: String?) {
                val (ep, idx) = parseUtteranceId(utteranceId) ?: return
                if (ep != epoch) return // 陈旧队列的回调，丢弃（含被 stop 冲刷掉的尾行）
                if (idx >= lastUnit) {
                    // 全书最后一句播完
                    isActive = false
                    isPaused = false
                    onFinished?.invoke()
                    return
                }
                if (idx >= enqueuedUnitUpTo - TOPUP_MARGIN) {
                    // 队列快见底，补下一窗。post 到主线程；进去后再校验一次
                    // （期间可能又来了 onDone / 跳转换了代数），避免重复入队。
                    runOnMain {
                        if (!isActive || isPaused) return@runOnMain
                        if (idx >= enqueuedUnitUpTo - TOPUP_MARGIN) topUp()
                    }
                }
            }

            @Deprecated("API 21 起的默认实现，保留以兼容旧签名")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "朗读出错：$utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "朗读出错：$utteranceId code=$errorCode")
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                // 暂停/跳转时 tts.stop() 会触发；currentLine 保留，等 resume 续播。
                // 不碰任何状态：被清掉的 utterance 可能乱序补发 onStop，不能当进度用。
            }
        })
    }
}
