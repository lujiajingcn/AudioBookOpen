package com.audiobook.audiobookplayer.moss

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.audiobook.audiobookplayer.BookStorage
import com.audiobook.audiobookplayer.parser.LocalEbookParser
import com.audiobook.audiobookplayer.parser.ParagraphSplitter
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 本机（离线）合成有声书的编排。
 *
 * ## 与在线合成的分工
 * 在线合成是「服务端排队 + 逐段下载」；本机合成是「本机逐段推理 + 直接入库」。
 * 两者最终都落到同一个 [BookViewModel.saveAudioSegmentStreaming]，因此播放、
 * 定位、原文映射这些下游逻辑**一行都不用改** —— 这是刻意选的接法：
 * 另起一套存储格式会让阅读器的音频定位、缓存管理全部要跟着分叉。
 *
 * ## 并行：为什么是"多个引擎"而不是"多开线程"
 * 最直觉的做法是把段丢进线程池、让几个线程同时调同一个引擎。**这条路是错的**：
 * ONNX Runtime 的 `OrtSession.run()` 不是线程安全的，同一 session 并发调用属于
 * 未定义行为 —— 它可能返回错数据而不是抛异常，那比崩溃更难查。
 * 所以并行的单位只能是**独立的引擎实例**，各自持有自己的 OrtEnvironment 与四个 session。
 *
 * 由此产生两个必须正面处理的后果（只加线程池而忽略这两点，结果会比串行更慢）：
 *
 * 1. **瓶颈是内存，不是 CPU。** 每个引擎都要把模型权重映射进来，实测整机占用
 *    约 700 MB。N 个引擎就是 N 份。4 GB 机型上开 4 个必然被 LMK 杀掉，
 *    所以并行度按**可用内存**反推，而不按 CPU 核数。
 * 2. **线程数会相乘。** 每个引擎内部还有 IntraOp 线程。若每引擎 4 线程、开 3 个引擎，
 *    就是 12 条线程抢 8 个核。因此并行度提高时**必须把每引擎线程数降下来**
 *    （见 [planParallelism]）。
 *
 * ## 段间可并行，段内不可
 * 一段音频是自回归逐帧生成的（94 帧要 94 次 decode_step），帧与帧严格有先后依赖，
 * 无法并行。可并行的只有不同段之间 —— 这也正是收益来源：原来段间是串行的，
 * 每段只吃满一个引擎，其余 CPU 闲着。
 */
object MossLocalSynthesizer {

    private const val TAG = "MossLocalSynth"

    /**
     * 单段最大帧数（硬上限）。manifest 的 generation_defaults.max_new_frames = 375（≈30 秒）。
     * 超过这个长度模型本身也生成不出来。
     */
    private const val DEFAULT_MAX_FRAMES = 375

    /**
     * 静音占位的采样率。
     *
     * 取 48kHz 是为了与引擎 `codec_config.sample_rate` 对齐 —— 播放链路用
     * `MediaPlayer` 按文件实际采样率解码，占位段与真段采样率一致就不会在
     * 拼接播放列表时有参数切换开销。
     */
    private const val SILENT_SAMPLE_RATE_HINT = 48000

    /**
     * 帧数估算：与服务端 `VoiceCloningTtsService.estimateFrames` **同一套公式**。
     *
     * `帧数 = 基准 + 字符数 × 每字帧数`，再夹到 `[下限, DEFAULT_MAX_FRAMES]`。
     *
     * ## 为什么必须按字数估，不能一律给 375
     *
     * `maxFrames` 是解码循环的**上界**，而每一步都要往 `pastResult` 塞一份 KV cache
     * （见 `MossLocalEngine.runDecode`）。给 30 字的小片也传 375，解码循环会一直跑到
     * 375 步或模型自己停 —— 前者意味着峰值内存一点没降，**拆分就白拆了**。
     *
     * 换算依据：引擎 12.5 帧/秒 ⇒ 375 帧 ≈ 30 秒；每字 4.0 帧 ≈ 3.1 字/秒，
     * 这个语速刻意保守，宁可多留余量也不要半句话被截断。
     */
    private const val FRAMES_BASE = 30
    private const val FRAMES_PER_CHAR = 4.0
    private const val FRAMES_MIN = 60

    /** 按文本长度估算该给解码循环多少帧上限。见 [FRAMES_PER_CHAR] 的说明。 */
    fun estimateMaxFrames(text: String): Int {
        val desired = FRAMES_BASE + text.length * FRAMES_PER_CHAR
        return desired.toInt().coerceIn(FRAMES_MIN, DEFAULT_MAX_FRAMES)
    }

    /**
     * 单个引擎的**真实**内存开销，含权重 + ORT 工作区 + KV cache + 算子中间张量。
     *
     * ## 为什么从 1009MB 改成 2100MB（2026-10-05，真机两次 LMK 后的修正）
     *
     * 旧值 1009MB 来自早期「加载完就量 PSS」的测量，那个口径**只统计了加载阶段的
     * 稳态**，没有把**推理过程中**的工作区算进去，因而严重偏低。
     *
     * 修好 arena 开关（`setCPUArenaAllocator(false)`，旧代码写的
     * `addConfigEntry("memory.enable_memory_arena","0")` 键名在 ORT 1.24.3 里根本不存在，
     * 一直是个静默空操作）之后，在 K30 Pro 上又做了两次真机续跑，
     * 两次都被 lowmemorykiller 杀掉，`killinfo` 里的实测驻留是：
     *
     *     并行度=2 → rss 4261868kB + swap 2617580kB  ≈ 6.6 GB
     *     并行度=2 → rss 3814352kB + swap 4797180kB  ≈ 8.2 GB
     *
     * 也就是**每引擎真实占用约 2.1GB**（4.2GB ÷ 2），不是 1GB。
     *
     * 后果很具体：这台机 `MemTotal` 只有 7.4GB，旧的
     * `4473MB × 0.5 ÷ 1009MB = 2` 会算出「开 2 个引擎」，
     * 而按真实值 `4473 × 0.5 ÷ 2100 = 1` 才安全 ——
     * **旧值直接把一个跑得完的任务算成了必然被杀的任务。**
     *
     * 宁可高估。低估的代价（进度全丢、用户以为功能坏了）远大于高估的代价（慢一点）。
     */
    private const val BYTES_PER_ENGINE = 2100L * 1024 * 1024

    /**
     * 可用内存里最多动用多大比例。
     *
     * ## 这个值是被实测数据打脸后改保守的
     * 起初按 0.7 估，原以为能在 4 GB 设备上开 2 个引擎。但**干净环境下的对照实测**
     * （各配置之间不做任何 adb/logcat 轮询，避免外部抢 CPU）给出的是：
     *
     *     并行度=1, 4 线程   实时率 0.547 ~ 0.569   PSS 1397 MB
     *     并行度=2, 2 线程   实时率 0.487（**更慢**）  PSS 2406 MB
     *     并行度=2, 4 线程   进程被 lowmemorykiller 杀死
     *     并行度=4, 2 线程   进程被 lowmemorykiller 杀死
     *
     * 即：**在这台 4 GB / 4 核设备上并行不但没提速，反而更慢**；
     * 开到 4 个直接被杀。原因大概率在内存而非 CPU —— 每个引擎自带一份完整权重，
     * 2 个引擎就把 PSS 推到 2.4 GB，逼近 4 GB 设备的可用上限，
     * 系统开始回收/换页，两份矩阵运算也在抢内存带宽。
     *
     * （早前有一轮测到 2x2 = 0.599 > 1x4 = 0.407，看着像"并行快 47%"。
     *   那轮脚本每 30 秒跑一次 adb logcat 抓进度，而串行那组耗时更长、
     *   被这个外部负载惩罚得更重 —— 结论是被测量噪声污染的，不可信。
     *   教训：**A/B 测量中任何持续的外部负载都会系统性偏袒较快的那一组**，
     *   因为慢的那组暴露在负载下的时间更久。）
     *
     * 故取 0.5，且每引擎开销按实测值算 —— 4 GB 设备上并行度自动落在 1（串行），
     * 只有可用内存 ≥ 约 4.2 GB 的设备才会启用 2 个引擎。
     * 宁可在大内存设备上少赚，也不能在小内存设备上把进程搞死。
     */
    private const val MEMORY_BUDGET_RATIO = 0.5

    /** 并行度上限。再多收益会被内存带宽与调度开销吃掉，且内存风险陡增 */
    private const val MAX_PARALLELISM = 4

    /**
     * 再加一个引擎前，要求可用内存比"一个引擎的开销"还要多出这么多。
     * 留 1.5 GB 余量：多引擎时 PSS 会逼近上限，余量给窄了就会触发 LMK
     * （实测正是这样被杀掉的），而少开一个引擎最多只是慢一点。
     */
    private const val EXTRA_ENGINE_HEADROOM_BYTES = 1536L * 1024 * 1024

    /**
     * **合成过程中**的可用内存下限：低于它就退化为单引擎串行。
     *
     * 与 [EXTRA_ENGINE_HEADROOM_BYTES]（加载前判断能否再开一个引擎）分工不同：
     * 那个管"要不要加引擎"，这个管"要不要**减**并发"。
     *
     * 取 1 GB 的依据（K30 Pro 连续两次真机崩溃的数据）：
     * 系统连环杀后台进程时 `MemAvailable` 约 371 MB，而 LMK 给出的理由是
     * `swap 0kB < 629144kB and thrashing`。
     * 留 1 GB 是为了让降级发生在系统动手**之前**：此时只是变慢，降下去就还能
     * 把剩下的段跑完；等降到 371MB 才发现，整本都白跑了。
     *
     * ⚠️ **为什么不能用 availMem 作为唯一判据（2026-10-05 实测踩坑）**
     * `ActivityManager.MemoryInfo.availMem` 只是"未被使用的物理内存"，
     * **不反映 swap 压力**。K30 Pro 上实测：合成到第 27/31 段被 LMK 杀掉时，
     * 进程 swap 已达 5236 MB、整机 `SwapFree` 归零，但逐帧探针一次都没触发
     * —— 因为那些被换出到 swap 的页**在 availMem 眼里仍是"已分配但可回收"**，
     * availMem 长时间维持在阈值以上。LMK 真正看的是 swap 与 watermark，
     * 所以探针也必须看 swap，见 [isSwapPressureHigh]。
     */
    private const val MOSS_MIN_AVAIL_BYTES = 1024L * 1024 * 1024

    /**
     * **本进程** swap 上限（`/proc/self/status` 的 `VmSwap` 超过它就认为危险）。
     *
     * 取 1 GB 的依据（本应用自身口径）：
     *   · 一份引擎常驻 ~1.5 GB 物理内存，合成到后段时被换出 1 GB 属正常；
     *   · K30 Pro 崩溃那轮，本进程 `VmSwap` 涨到 4 GB 以上，随后 swap 被榨干；
     *   · 反过来，19:26 那轮实测合成顺利时 `VmSwap` 只有 **59 MB**。
     * 1 GB 相当于"引擎被换出了一大半"的量级，既不会误伤（59 MB 差两个数量级），
     * 又能在真逼近危险时给出信号。
     */
    private const val MOSS_OWN_SWAP_LIMIT_BYTES = 1024L * 1024 * 1024

    /**
     * **整机** swap 兜底上限（`/proc/meminfo` 已用 swap 超过它才认为危险）。
     *
     * ⚠️ 这个值**不是**"本应用该担心"的阈值，只是"整台机器真的腾不出空间了"
     * 的最后一道闸 —— K30 Pro `SwapTotal = 6291452 kB`（约 6 GB），崩溃时
     * 整机 swap 被榨干、`SwapFree = 0` 触发 thrashing 保护，连后台进程一起杀，
     * 最后才轮到 `oom_score_adj 0` 的前台进程。
     *
     * 原来这里是 1536 MB（整机 swap 的 25%），而被 [isMemoryCritical] 当成
     * 主要判据使用 —— 这就是 19:26 那轮在可用内存 4.4 GB、进程零次被杀的情况下
     * 自行放弃 12 段的直接原因：K30 Pro 上别人占 1.5~2 GB swap 是常态，
     * 拿它当自己的红线，等于随时被别人触发死刑。
     * 现在取 4608 MB（约 75%）：只剩 1.5 GB 余量，系统回收变激进时才拦。
     */
    private const val MOSS_SWAP_PRESSURE_BYTES = 4608L * 1024 * 1024

    /**
     * 一份引擎连续服务多少段就 close 掉重新 load。
     *
     * 取 6 的依据（K30 Pro 实测）：
     *   · 崩溃那轮跑到第 27 段被杀，每段约 25 秒 ⇒ 27 段 ≈ 11 分钟常驻；
     *   · 降到单引擎后 17 段（8 分钟）被杀，说明单份引擎常驻的"安全窗口"
     *     在 20 段以内，但那段窗口内系统还有其他应用在抢内存，不能压满；
     *   · 每份引擎的常驻代价 ~1.5GB（4 个 ORT session 的权重 + 工作区），
     *     6 段 ≈ 2.5 分钟常驻，内存占用还没爬到 thrashing 就主动让出去。
     *
     * 代价：每批多一次模型加载（实测约 12 秒），即每 6 段多花 12 秒，
     * 约 20% 的时间开销。换来的是不再整轮丢进度 —— 这是划算的。
     *
     * 调大 → 单批更快但更容易被杀；调小 → 更安全但更慢。6 是当前实测的折中点。
     */
    private const val SEGMENTS_PER_ENGINE_BATCH = 6

    /**
     * 允许重载引擎所需的最低可用内存（比 [MOSS_MIN_AVAIL_BYTES] 更严）。
     *
     * 为什么更严：重载有**瞬时峰值** —— 新引擎的 ~1.5GB 是在旧引擎刚 close、
     * 系统还没来得及回收时就建起来的。K30 Pro 崩溃那轮 `MemTotal` 才 7.4GB，
     * 在 `MOSS_MIN_AVAIL_BYTES`（1GB）就动手重载，很可能等新引擎加载到一半就被杀，
     * 反而比"不重载、继续常驻"更糟。
     * 留 1.8GB：够旧内存被收走 + 新引擎建起来还有余量。
     */
    private const val MOSS_RELOAD_GUARD_BYTES = 1843L * 1024 * 1024

    /**
     * 内存告警后释放引擎、等待系统真正回收的时长（毫秒）。
     *
     * 为什么必须等：close 掉 OrtSession 只是**解除引用**，内核要把那些页从
     * 进程地址空间里摘走（`madvise(MADV_DONTNEED)` / 页表拆除）不是瞬时的。
     * 实测 7.4GB 机器上 ORT 释放 1.5GB 需要数秒才反映到 `MemAvailable`。
     * 立刻重查水位会得到"没降下来"的结论，从而误判为回收失败而放弃本轮 ——
     * 那就退回了 18:54 那个"为了 1.5GB 丢掉剩余段"的老问题。
     *
     * 取 5 秒：与段间重载的紧张档（`isMemoryCritical` → 4000ms）同一量级，
     * 略长一点，因为这里后面还要重载引擎、值得多等那一点。
     */
    private const val OOM_RECLAIM_WAIT_MS = 5000L

    /**
     * 同一轮里，因内存告警而重跑段落的次数上限。
     *
     * 2 次是权衡：1 次可能碰上瞬时尖峰就误放弃；3 次以上在 7.4GB 机器上
     * 基本等于反复折腾（每次都要 close + 等 5 秒 + 重载 5~12 秒）。
     * 超过就按"该段失败"计入并继续 —— 后面还有别的段，不能被一条长段拖住整轮。
     */
    private const val MAX_OOM_RETRY = 2

    /**
     * 收尾时等待每个 worker 结束的最长分钟数。
     *
     * 取 30 而不是原先的 10：合成被外部拖慢时（K30 Pro 上实测每段要 30~45 秒，
     * 一本 31 段的书就是 15~23 分钟），10 分钟根本不够等 worker 走完 ——
     * 2026-10-05 20:18 那轮就是 10 分钟到点触发 `cancel(true)`，
     * 把第 23 段**已经算好、正在入库**的音频直接丢掉（见 join 处注释）。
     *
     * 这个值是"最后一道兜底"而非日常耗时：正常情况下 worker 会在一两秒内收工。
     * 留 30 分钟意味着真卡死时收尾最多多等半小时，可接受。
     */
    private const val WORKER_JOIN_TIMEOUT_MINUTES = 30L

    interface ProgressListener {
        /**
         * 解析完成、开始合成之前回调一次，告知总段数。
         *
         * 为什么需要它：调用方要在书架的任务列表里显示「已完成 / 总数」。
         * 若只在 onProgress 里拿总数，第一次回调之前那段时间分母是 0，
         * 书架会显示成「0 / 0」——看起来像任务已经空转结束。
         * 提前给一次总数，进度条一出现就是对的。
         *
         * @param totalSegments 全书总段数
         * @param alreadyDone **本地已有音频的段数**（续跑时 > 0，首次为 0）。
         *   调用方必须把它计入进度起点：续跑时若用 0 当起点，
         *   书架上的进度条会从「已有 N 段」**倒退**成「0 / 总数」——
         *   用户刚点完「继续合成」就看着进度归零，只会以为功能坏了。
         *   同一批已有段在整个会话内是常量，可以只取一次。
         */
        fun onParsed(totalSegments: Int, alreadyDone: Int = 0)

        /** @param done 已完成段数；@param total 总段数；@param etaMs 预计剩余毫秒（未知为 -1） */
        fun onProgress(done: Int, total: Int, etaMs: Long)
        fun onStage(stage: String)
    }

    sealed class Result {
        /**
         * @param cancelled 是否由用户中止。**必须与"完成"区分开**：
         *   中止时已完成段数可能远小于总段数，报成"完成 0/1 段"会让用户以为合成失败了，
         *   而实际上已入库的那几段是好的、能正常播放，只是用户主动停了。
         * @param parallelism 实际并行度（供日志与排查）
         */
        data class Success(
            val savedSegments: Int,
            val totalSegments: Int,
            val cancelled: Boolean = false,
            val parallelism: Int = 1,
            /**
             * 推理或入库失败的段数。
             *
             * 必须外传，否则调用方会把「31 段里成功 29、失败 2」当成完整成功
             * 报成 completed —— 书架显示「已完成」，用户以为整本齐了，
             * 实际那 2 段永远没有声音，且没有任何地方能看出少了东西。
             */
            val failedSegments: Int = 0,
            /**
             * 本次**没有重算**、直接沿用本地已有音频的段数（续跑时 > 0）。
             *
             * 与 [savedSegments] 分开是因为两者含义不同：savedSegments 含它，
             * 所以任务行能显示正确的「x / 总数」；但日志与「本次做了多少活」
             * 的判断要靠这一个。
             */
            val skippedSegments: Int = 0
        ) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * 读取当前可用内存（字节）。读不到时返回 Long.MAX_VALUE —— 让调用方
     * 不要因为"读不到"就拒绝并行；真正的兜底是加载时的逐次校验。
     */
    private fun availableBytes(context: Context): Long = try {
        val memInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.getMemoryInfo(memInfo)
        memInfo.availMem
    } catch (e: Throwable) {
        Long.MAX_VALUE
    }

    /**
     * 已使用的 swap（字节）。
     *
     * **为什么必须单独读 swap**：见 [MOSS_MIN_AVAIL_BYTES] 的说明 ——
     * `availMem` 看不见 swap 压力，而 LMK 恰恰是靠 swap 决定杀谁。
     * K30 Pro 实测本应用一家吃掉 5236 MB swap 时 `availMem` 仍然"看起来正常"，
     * 结果探针一次没触发、进程被直接杀掉。
     *
     * 读 `/proc/meminfo` 是唯一途径（`ActivityManager` 不暴露 swap）。
     * 部分 ROM（如 MIUI 的低内存模式）会限制读 `/proc`，读不到就返回 0 ——
     * 也就是"不额外拦截"，退回只看 availMem 的旧行为，不会误杀合成。
     */
    private fun usedSwapBytes(): Long = try {
        BufferedReader(FileReader("/proc/meminfo")).use { reader ->
            var total = -1L
            var free = -1L
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val text = line ?: break
                when {
                    text.startsWith("SwapTotal:") -> total = parseMeminfoKb(text)
                    text.startsWith("SwapFree:") -> free = parseMeminfoKb(text)
                }
                if (total >= 0 && free >= 0) break
            }
            if (total < 0 || free < 0) 0L else (total - free) * 1024
        }
    } catch (e: Throwable) {
        0L
    }

    /** `/proc/meminfo` 每行的数值部分，形如 `SwapFree:  123456 kB`。 */
    private fun parseMeminfoKb(line: String): Long =
        line.split(Regex("\\s+")).drop(1).firstOrNull()?.toLongOrNull() ?: -1L

    /**
     * **本进程自己**占用的 swap（字节），读 `/proc/self/status` 的 `VmSwap`。
     *
     * ## 为什么必须区分「本进程」与「整机」
     *
     * 2026-10-05 19:26 那轮压测的现场（K30 Pro，单引擎跑 31 段）：
     *
     * ```
     * [240 s] 段=17  avail=1495M swapUsed(整机)=1354M
     * [270 s] 段=18  avail=4435M swapUsed(整机)=2060M
     * [300 s] 段=19  failed|19|31   ← 主动放弃剩余 12 段
     * 被 LMK 杀: 0 次
     * ```
     *
     * 三条事实同时成立：进程活到收尾、没被 LMK 杀一次、可用内存一度有 **4.4 GB**。
     * 也就是说这轮**根本不是内存不够**，是我们自己把它判成了内存不够。
     *
     * 原因就在 [usedSwapBytes]：它读 `/proc/meminfo` 的 `SwapTotal - SwapFree`，
     * 那是**整机所有进程**的 swap 占用 —— 微信、QQ、系统服务、甚至 LMK 之前
     * 杀掉又残留的其它应用都算在里面。同一时刻本进程自己的 `VmSwap` 只有
     * **59 MB**。拿别人的 swap 去给**自己**的合成下死刑，等于让邻居的内存
     * 占用决定我们跑不跑得完。
     *
     * 后果链是致命的：`isMemoryCritical()` 因「整机 swap 高」而恒为 true ⇒
     * 帧级探针在解码途中就抛 `OutOfMemoryError` ⇒ 释放引擎、查回收 ⇒
     * **整机 swap 当然不会因为我们释放 1.5 GB 而降下来** ⇒ 判定「回收失败」
     * ⇒ `oomAborted` ⇒ 丢掉剩余 12 段。**探针越是灵敏，误判越快。**
     *
     * 读不到时返回 0（= 不额外拦截），退回整机判据，不会凭空制造危险。
     */
    private fun ownSwapBytes(): Long = try {
        BufferedReader(FileReader("/proc/self/status")).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val text = line ?: break
                if (text.startsWith("VmSwap:")) {
                    return parseMeminfoKb(text) * 1024
                }
            }
            0L
        }
    } catch (e: Throwable) {
        0L
    }

    /**
     * 内存是否已进入危险区。
     *
     * 三个条件取"或"，任一成立即认为危险：
     *   · 物理可用内存低于 [MOSS_MIN_AVAIL_BYTES]
     *   · **本进程** swap 超过 [MOSS_OWN_SWAP_LIMIT_BYTES]
     *   · 整机 swap 超过 [MOSS_SWAP_PRESSURE_BYTES]（兜底，阈值很高）
     *
     * **为什么是"或"而不是只看 swap**：swap 在不同 ROM 上差异极大
     * （有的机器没开 zram，`SwapTotal` 可能是 0），只看 swap 会在这些机器上
     * 完全失效；而 availMem 虽看不见 swap 压力，仍是有效的兜底。
     * 两者结合才覆盖得住真机上出现过的两种杀法。
     */
    private fun isMemoryCritical(context: Context): Boolean {
        val avail = availableBytes(context)
        val availLow = avail in 1 until MOSS_MIN_AVAIL_BYTES
        if (availLow) return true
        // 主判据：本进程自己的 swap。它才是"我们该为释放内存做点什么"的信号。
        if (ownSwapBytes() >= MOSS_OWN_SWAP_LIMIT_BYTES) return true
        // 兜底：整机 swap 逼近榨干（LMK 的最终触发条件）。
        // 阈值刻意远高于 [MOSS_OWN_SWAP_LIMIT_BYTES]：这一条只能在整机真的
        // 腾不出空间时才有意义，用它去过早中止就是"替别人的内存买单"。
        return usedSwapBytes() >= MOSS_SWAP_PRESSURE_BYTES
    }

    /**
     * 决定并行度与每引擎线程数。
     *
     * 只有两个约束同时满足才有意义：
     *   · **内存**：可用内存 × [MEMORY_BUDGET_RATIO] ÷ 每引擎开销。
     *     这条是硬约束 —— 实测并行度 4 会直接把进程送进 LMK（见 BYTES_PER_ENGINE 的说明），
     *     所以宁可按紧了估，也不能乐观；
     *   · **CPU**：每引擎保底 1 线程（并行度已经 >= 2 时，单引擎线程少一点没关系，
     *     因为多引擎已经能填满核心），且 并行度 × 每引擎线程数 不超过核数。
     *
     * @return (并行度, 每引擎线程数)
     */
    fun planParallelism(context: Context): Pair<Int, Int> {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val avail = availableBytes(context)
        val budget = (avail * MEMORY_BUDGET_RATIO).toLong()
        val byMemory = (budget / BYTES_PER_ENGINE).toInt().coerceIn(1, MAX_PARALLELISM)

        // 并行度 1 时让单引擎吃满核；否则按核数均分（每引擎至少 1 线程）
        val parallelism = minOf(byMemory, MAX_PARALLELISM, maxOf(1, cores))
        val perEngineThreads = if (parallelism <= 1) {
            cores.coerceIn(1, 4)
        } else {
            (cores / parallelism).coerceIn(1, 4)
        }
        Log.i(
            TAG,
            "并行规划: 核数=$cores 可用内存=${avail / 1048576}MB" +
                " 预算=${budget / 1048576}MB 每引擎=${BYTES_PER_ENGINE / 1048576}MB" +
                " → 并行度=$parallelism 每引擎线程=$perEngineThreads"
        )
        return parallelism to perEngineThreads
    }

    /**
     * 取出待合成的段落，并保证它与**阅读器看到的原文同源**。
     *
     * ## 为什么要多这一层：合成必须读**权威**文本，而不是本地预览
     *
     * 客户端有 PDF 解析器（PDFium，见 [PdfPreviewParser]），但它的产出是**降级预览**，
     * **不是权威**：权威始终是服务端 PDFBox 3.0.2 的结果。所以本机合成**只读
     * `ebook_content_cache`**，绝不读本地预览 —— 预览文本与服务端可能存在细微差异
     * （换引擎前 PDFBox-Android 2.0.27 实测少 127 个「最」字），
     * 而「两端文本不同源」对本机合成是**致命的**：入库的段落文本若与阅读页显示的行对不上，
     * [EbookSegmentMap] 的定位就会漂移，表现为点读/高亮串音。
     *
     * ⚠️ 同理，**预览绝不能写 `ebook_content_cache`** —— 那是本方法与
     * `MossLocalSynthesizer.resolveParagraphs` 的唯一数据源，
     * 写了预览文本会让同名书出现两种段号。
     *
     * 好在**服务端的解析结果本来就缓存在本地**（`ebook_content_cache`，见
     * `BookViewModel.readServerRowsWithCache`）：用户在阅读页打开过这本书，PDF 的行就
     * 已经躺在本地库里，且与服务端逐字一致。读它来做合成，两端口径天然统一。
     *
     * ## 为什么未命中就失败，而不是偷偷上传整本
     *
     * 本机合成的卖点是「不依赖服务端、不消耗积分」。为了离线合成而把整本 PDF
     * 上传（实测 1.4MB 的书限速 57.8KB/s 时 100 秒都出不来），既慢又违背用途，
     * 失败文案必须**指路**而不是让用户以为是 bug。
     *
     * ## 切分口径
     *
     * 缓存里存的是**行**（阅读页的显示单位），合成要的是**段落**。这里用
     * [ParagraphSplitter] 二次切分，口径与 EPUB/MOBI 的本地路径共用同一套类
     * —— 刻意不另写一套，否则本机合成的段边界就会随格式漂移。
     * `lineBreakAsParagraph = false`：PDF 的换行来自 `stripTags`/版面抽取，
     * 与 EPUB 同属「不是作者排版」，按同一口径处理。
     *
     * ⚠️ 段落与行**不一一对应**是允许的：[EbookSegmentMap] 走的是
     * 「归一化后全文 indexOf」（忽略空白），只要段落文本是原文的子串就能定位。
     */
    private suspend fun resolveParagraphs(
        file: File,
        storage: BookStorage
    ): LocalEbookParser.Result {
        val extension = file.extension.lowercase()
        return try {
            LocalEbookParser.parse(file)
        } catch (e: UnsupportedOperationException) {
            // 只有「客户端解析不了、但服务端解析过」的格式才有机会靠缓存救回来。
            // 未知格式（LocalEbookParser 只对 pdf 抛，其余不支持格式也抛）不该
            // 被这句吞掉：查了缓存也未必命中，白走一趟 IO。
            if (extension != "pdf") throw e

            Log.i(TAG, "${file.name} 客户端无解析器，改用服务端解析缓存")
            val cached = storage.loadEbookContentCache(file)
            if (cached == null || cached.rows.isEmpty()) {
                Log.w(TAG, "解析缓存未命中（用户还没在阅读页打开过这本书）")
                throw UnsupportedOperationException(
                    "这本书还没有服务端解析缓存。请先到阅读页打开一次，" +
                        "让服务端完成 PDF 文本提取后再回来本机合成；或改用在线合成"
                )
            }
            val paragraphs = ParagraphSplitter.splitRows(cached.rows)
            Log.i(
                TAG,
                "解析缓存命中：${cached.rows.size} 行 → ${paragraphs.size} 段" +
                    "（目录 ${cached.toc.size} 项）"
            )
            LocalEbookParser.Result(paragraphs, cached.toc)
        }
    }

    /**
     * 把一本书在本机合成并入库。
     *
     * 是 suspend 的：入库（[BookStorage.saveAudioSegmentDirect]）与收尾
     * （flushPendingAudioSegments）本身就是挂起函数，内部有 IO 与数据库操作。
     * 调用方需自行切到 IO 线程（推理是 CPU 密集的，不能占着主线程）。
     *
     * @param storage 只用作入库通道。**刻意不接 [BookViewModel]**：ViewModel 的 init
     *   会 attach 三个进程级单例（TaskPollingManager / StoreAudioDownloadManager /
     *   PlaybackService 绑定），注销只写在 `onCleared()` 里；本机合成在 Service 里调用，
     *   手工构造的 ViewModel 永不被回收 ⇒ 轮询线程永不 shutdown、每轮泄漏一个实例。
     *   BookStorage 的写库缓冲在 companion 进程级共享，直接构造即可。
     * @param voice 内置音色名（[MossLocalEngine.builtinVoices] 之一）
     * @param referenceAudioFile 参考音频 WAV；非空时用它的音色合成（覆盖 [voice]）。
     *   为 null 表示用内置音色。
     * @param parallelism 并行引擎数；<=0 表示自动（见 [planParallelism]）
     */
    suspend fun synthesizeBook(
        context: Context,
        book: com.audiobook.audiobookplayer.model.Book,
        storage: BookStorage,
        voice: String,
        referenceAudioFile: File? = null,
        parallelism: Int = 0,
        /** 暂停/恢复闸门。只作用于段边界，见 [PauseController] 的说明。 */
        pauseController: PauseController = PauseController(),
        listener: ProgressListener,
        /** 返回 true 表示用户要求中止 */
        isCancelled: () -> Boolean
    ): Result {
        val file = File(book.filePath)
        if (!file.isFile) {
            return Result.Failed("电子书文件不存在：${book.filePath}")
        }

        // ---- 1) 本地解析出段落 ----
        // 复用阅读器同一套解析（LocalEbookParser）：段落边界必须与阅读器一致，
        // 否则入库的段落文本与阅读页对不上，音频定位会错位。
        listener.onStage("正在解析电子书")
        val parsed = try {
            resolveParagraphs(file, storage)
        } catch (e: UnsupportedOperationException) {
            return Result.Failed(e.message ?: "该格式暂不支持本机合成")
        } catch (e: Exception) {
            Log.e(TAG, "解析电子书失败", e)
            return Result.Failed("解析电子书失败：${e.message}")
        }

        val paragraphs = parsed.paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) {
            return Result.Failed("这本书没有可合成的文本")
        }
        // ---- 1.5) 续跑：本地已有的段直接跳过 ----
        //
        // 「部分完成」的书再点一次合成时，已有音频的段**必须**跳过：
        // 一段 30 秒音频要几十秒到几分钟，不跳过就是让用户为已有的内容再付一遍时间
        // （一本 200 段的书等于白等几小时）。
        //
        // ⚠️ **当前成立的前提**：`audio_segments` 表**不存音色**，而本机合成
        // 只有一个内置音色（MossLocalEngine.DEFAULT_VOICE，见 LocalSynthesisService
        // 的 start 调用点），所以「已有段」一定是同一音色合出来的，跳过是安全的。
        // 将来一旦支持选音色，跳过判据必须同步加上音色比对
        // （否则换音色重合成会被这里全部跳过，用户听见的还是旧音色，
        // 而且因为 Success 报的是 completed，界面上看不出任何异常）。
        //
        // 查询放在 onParsed **之前**，因为 onParsed 要把「已有段数」当进度起点报给调用方 ——
        // 续跑时若从 0 起算，书架进度条会从「已有 N 段」倒退成「0 / 总数」。
        //
        // 放在**加载模型之前**：全部段都已有时直接返回 Success，
        // 省掉加载模型那 13~42 秒 —— 那段时间界面只能显示「正在加载模型」，
        // 什么都不做却要干等，对用户毫无意义。
val existingSegments = try {
            storage.loadLocalSegmentNumbers(book.displayName)
        } catch (e: Throwable) {
            // 查不到就当作全都要合成：宁可多算一次，也不要谎报「已完成」
            // 而让用户以为合成好了、实际某几段是空的。
            Log.e(TAG, "查询已有段失败，本次将合成全部段落", e)
            emptySet()
        }

        // ⚠️ **用参考音频合成时，一个旧段都不能跳**（上方注释预言的坑，已在这里堵上）。
        //
        // `audio_segments` 表**不存音色**，而跳过逻辑只看「该段有没有 audio_data」。
        // 于是这个组合会静默失效：
        //   先用内置音色合成了一半 → 换成郭德纲.wav 再点合成
        // → 已有段被全部跳过，只补剩下的一半 → 用户听见的仍是旧音色，
        // 而且结果是 completed，界面上看不出任何异常。
        //
        // 正确做法是「音色不一致就重合成」，但表里没有音色可比。
        // 与其加一个假的判据，不如：选了参考音频就整本重来。
        // 代价是用户要重跑已合成的那部分 —— 这与「听到的音色不对」相比是显式且可理解的。
        val skipExisting = referenceAudioFile == null
        if (!skipExisting && existingSegments.isNotEmpty()) {
            Log.i(
                TAG,
                "使用参考音频合成：${existingSegments.size} 个已有段是其他音色产出，" +
                    "本次整本重合成（audio_segments 不存音色，无法逐段判别）"
            )
        }
        val pendingIndices = paragraphs.indices.filter {
            !skipExisting || (it + 1) !in existingSegments
        }
        val skippedExisting = if (skipExisting) existingSegments.size else 0
if (pendingIndices.isEmpty()) {
            Log.i(TAG, "全部 ${paragraphs.size} 段本地已有音频，无需合成")
            listener.onParsed(paragraphs.size, skippedExisting)
            listener.onProgress(paragraphs.size, paragraphs.size, 0L)
            return Result.Success(
                savedSegments = paragraphs.size,
                totalSegments = paragraphs.size,
                cancelled = false,
                parallelism = 1,
                skippedSegments = skippedExisting
            )
        }
        // 先告诉调用方总段数与起点：书架的任务行要靠它显示进度
        listener.onParsed(paragraphs.size, skippedExisting)
        if (skippedExisting > 0) {
            Log.i(
                TAG,
                "续跑：${paragraphs.size} 段中已有 $skippedExisting 段，" +
                    "本次只合成剩余 ${pendingIndices.size} 段"
            )
            listener.onStage("继续合成剩余 ${pendingIndices.size} 段")
        } else {
            listener.onStage("正在准备")
        }

        val (autoParallel, autoThreads) = planParallelism(context)
        val engineCount = if (parallelism > 0) parallelism.coerceIn(1, MAX_PARALLELISM) else autoParallel
        val threadsPerEngine = autoThreads
        Log.i(
            TAG,
            "共 ${paragraphs.size} 段待合成（需新合成 ${pendingIndices.size} 段），" +
                "音色=$voice，并行度=$engineCount，每引擎线程=$threadsPerEngine"
        )

        // ---- 2) 加载引擎池 ----
        // 逐个报告进度：加载 N 份模型是这里最慢的一步（每份数百 MB 的映射，实测约 12 秒），
        // 不给反馈用户会以为卡住了。
        //
        // ⚠️ **每加载一个之前都重新查一次可用内存**，而不是只在开头算一次。
        // 实测教训：基准测试里一次开了 4 个引擎，加载过程中可用内存被吃光，
        // 系统 lowmemorykiller 直接把本进程杀掉（Kill 'com.audiobook.audiobookplayer'
        // to free 2406384kB rss），合成到一半的数据全丢、界面也没有任何提示。
        // 少开一个引擎最多慢一点；被杀掉则是彻底失败 —— 所以这里宁可少开。
        val tokenizer = MossModuleManager.tokenizer(context)
        val pool = ArrayList<MossLocalEngine?>(engineCount)
        var skippedForMemory = 0
        try {
            for (i in 0 until engineCount) {
                if (i > 0) {
                    val avail = availableBytes(context)
                    val need = BYTES_PER_ENGINE + EXTRA_ENGINE_HEADROOM_BYTES
                    if (avail < need) {
                        skippedForMemory = engineCount - i
                        Log.w(
                            TAG,
                            "可用内存 ${avail / 1048576}MB 不足以再加载引擎" +
                                "（需要 ≥ ${need / 1048576}MB），并行度降为 $i"
                        )
                        break
                    }
                }
                listener.onStage(
                    if (engineCount == 1) "正在加载模型" else "正在加载模型 ${i + 1}/$engineCount"
                )
                val loadStart = System.currentTimeMillis()
                pool.add(MossLocalEngine(MossModuleManager.moduleDir(context), threadsPerEngine))
                Log.i(
                    TAG,
                    "引擎 ${i + 1}/$engineCount 加载完成，耗时 ${System.currentTimeMillis() - loadStart}ms，" +
                        "可用内存 ${availableBytes(context) / 1048576}MB"
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "加载模型失败", e)
            pool.forEach { eng -> runCatching { eng?.close() } }
            return Result.Failed("加载本机合成模型失败：${e.message}")
        }
        if (pool.isEmpty()) {
            return Result.Failed("无法加载本机合成模型（内存不足）")
        }
        val actualEngines = pool.size
        if (skippedForMemory > 0) {
            Log.i(TAG, "因内存不足少开 $skippedForMemory 个引擎，实际并行度 $actualEngines")
        }

        // ---- 2.5) 参考音频 → 音色码（整本书只做一次）----
        //
        // 为什么放在引擎加载**之后**：encode 会话挂在引擎上，得先有引擎。
        // 为什么只做一次：encode 是整段音频的前向（几秒），而段有几百上千段，
        // 每段重编一遍等于白烧几百倍时间。编出来的码是纯数据，所有引擎共用。
        //
        // ⚠️ 这一步失败**必须整本书失败**，不能"退回内置音色接着合成"：
        // 用户选了自己的声音却听见内置音色，正是这个 bug 的原始现象
        // （参见 TtsFragment 里本机路径过去完全忽略参考音频）。
        // 静默降级会把"没生效"包装成"成功了"。
        var referencePromptCodes: List<IntArray>? = null
        if (referenceAudioFile != null) {
            // v5/A2：整段包 try/catch(Throwable)。
            // ReferenceWavLoader.loadForEncode 内部只 catch(Exception)，而「大 WAV 解析」
            // 最可能抛的是 OutOfMemoryError（属 Error）—— 它会一路逃出本块，池里每份约 1GB
            // 的 native 引擎就再也没人关（原实现只在显式失败分支里关池）。
            try {
                val firstEngine = pool.first() ?: return Result.Failed("引擎未就绪")
                if (!firstEngine.supportsVoiceClone) {
                    pool.forEach { eng -> runCatching { eng?.close() } }
                    return Result.Failed(
                        "当前模型模块不支持参考音频克隆，请重新下载最新的合成模型模块"
                    )
                }
                listener.onStage("正在解析参考音频")
                val wav = ReferenceWavLoader.loadForEncode(referenceAudioFile)
                if (wav == null) {
                    pool.forEach { eng -> runCatching { eng?.close() } }
                    return Result.Failed(
                        "参考音频无法解析（需 2~10 秒的有效音频）：${referenceAudioFile.name}"
                    )
                }
                try {
                    referencePromptCodes = firstEngine.encodeReferenceAudio(
                        ReferenceWavLoader.normalizePeak(wav)
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "参考音频编码失败", e)
                    pool.forEach { eng -> runCatching { eng?.close() } }
                    return Result.Failed("参考音频编码失败：${e.message}")
                }
                Log.i(TAG, "使用参考音频音色：${referenceAudioFile.name}，${referencePromptCodes.size} 帧码")
            } catch (t: Throwable) {
                // 无论 Error 还是 Exception，先把已加载的引擎池关掉再决定怎么上报
                pool.forEach { eng -> runCatching { eng?.close() } }
                if (t is Error) {
                    throw t            // OOM/LinkageError 必须继续上抛，让上层按既有策略处理
                }
                Log.e(TAG, "参考音频处理失败", t)
                return Result.Failed("参考音频处理失败：${t.message}")
            }
        }

        val doneCount = AtomicInteger(0)
        val savedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val elapsedTotal = AtomicLong(0)
        val nextIndex = AtomicInteger(0)
        // 记录上一段完成的时间戳，用来在日志里体现"后台是否还在推进"。
        // 排查"切走之后到底有没有继续跑"时，只看总进度不够 ——
        // 需要看到**每一段的时间点**，才能区分"一直在跑"和"卡住了"。
        val lastDoneAt = AtomicLong(System.currentTimeMillis())

        // 内存闸门的开合状态。声明放在 try **之外**，因为收尾处要读 oomAborted
        // 来决定报 Success 还是 Failed；放在 try 内则那个位置根本看不到它（编译不过）。
        //   · memoryPressure：内存**接近**水位 ⇒ 停掉 worker 1..N，留 worker 0 串行跑完；
        //   · oomAborted   ：内存**已经耗尽**（帧级探针抛 OutOfMemoryError）⇒ 全员停止。
        // 见下面 [MOSS_MIN_AVAIL_BYTES] 处的说明为什么需要它们。
        val memoryPressure = AtomicBoolean(false)
        val oomAborted = AtomicBoolean(false)
        // 内存告警后"重跑该段"的次数。**必须有上限**：同一段可能反复触发告警
        // （段落特别长 ⇒ 工作区一直压不下去），无上限就是
        // 重跑 → 告警 → 释放 → 重跑… 永远出不来。见 [MAX_OOM_RETRY]。
        val oomRetryCount = AtomicInteger(0)
        // 引擎重载次数。同样要声明在 try **之外** —— 收尾日志（try 外）要读它，
        // 放在 try 内则那个位置根本看不到，编译不过。
        val batchReloadCount = AtomicInteger(0)

        try {
            // ---- 3) 并行合成 ----
            //
            // 用"共享游标"而不是预先切分下标区间：段落长短差异很大，
            // 预先切分会让某个 worker 分到若干长段而拖住整体（尾延迟）。
            // 共享游标自平衡 —— 谁先空谁先拿下一段。
            val workers = minOf(actualEngines, pendingIndices.size)
            val executor = Executors.newFixedThreadPool(workers) { r ->
                Thread(r, "moss-synth").apply { isDaemon = true }
            }
            // ---- 3.5) 段间释放引擎（关键 / 2026-10-05 真机三次崩溃后的结论）----
            //
            // 【为什么必须这么做】worker 的 `val engine = pool[workerId]` 意味着
            // 引擎在整个合成过程中**常驻**：K30 Pro 实测单个引擎把 4 个 ORT session
            // 的权重 + 工作区撑到 ~1.5GB，加上被换出到 swap 的页，总驻留冲到
            // 7.5GB（rss 2.4GB + swap 5.2GB），而整机 MemTotal 只有 7.4GB。
            // 于是系统在跑到第 27/31 段时 thrashing，把整个进程连同进度一起杀掉：
            //     lowmemorykiller: Kill 'com.audiobook.audiobookplayer',
            //       oom_score_adj 0 to free 2498664kB rss, 5236940kB swap;
            //       reason: device is low on swap (3852kB < 629144kB) and thrashing (11%)
            //
            // 试过的三条路都**不**能解决这个问题，必须说明清楚以免重复踩：
            //   1. 关 BFC arena —— 确实生效（日志 `CPU arena=false`），但只省掉空闲块
            //      缓存，**不减少权重本身**的驻留。崩溃仍然发生。
            //   2. 降低并行度到 1 —— 从 0 段撑到 17 段（8 分钟），量级改善但没根治：
            //      单引擎自己就够呛。崩溃仍在第 27 段发生。
            //   3. 逐段查内存降级 / 帧级探针 —— 判据选错了指标，见 [isMemoryCritical]：
            //      之前只看 `availMem`，而崩溃时 swap 已吃光而 availMem 仍"正常"，
            //      探针一次都没触发。
            //
            // 【本机制的做法】每合成 [SEGMENTS_PER_ENGINE_BATCH] 段就 close 掉引擎、
            // 让系统把内存收回去，下一段再重新 load。这让**驻留内存变成锯齿形**
            // （合成时高、释放时低）而不是单调爬升，系统有机会在低谷回收。
            // 代价是每批多付一次模型加载时间（实测约 12 秒/引擎），换来的是
            // 不再整轮丢进度 —— 已入库的段本来就有 saveAudioSegmentDirect 落盘保护，
            // 但被 LMK 杀掉时正在合成的那一段仍然白做。
            try {
                val futures = (0 until workers).map { workerId ->
                    executor.submit {
                        // 引擎是**可变**的：每合成够 [SEGMENTS_PER_ENGINE_BATCH] 段就
                        // close 掉重新 load，让系统有机会回收权重占的那 1.5GB。
                        // 每个 worker 只动自己那一份，因此不需要跨线程协调。
                        var engine: MossLocalEngine? = pool[workerId]
                        var engineReusedSegments = 0
                        // 本轮是否已放弃重载。一旦因内存不足跳过重载，继续用现有引擎
                        // 跑完剩下的段就是唯一正确选择 —— 内存只会越来越紧，
                        // 之后再问一遍也只会得到同样的答案，日志还会每段刷一条。
                        var reloadSuppressed = false
                        // 因内存告警作废、需要重跑的段号。内存回收成功后优先消费它们，
                        // 不再从共享游标取 —— 见下面 catch 分支里的说明。
                        val retryQueue = java.util.concurrent.ConcurrentLinkedQueue<Int>()
                        while (true) {
                            if (isCancelled()) break
                            // 还有剩余段才需要等待；否则直接走到下面退出循环。
                            //
                            // 暂停闸门必须放在 `nextIndex.getAndIncrement()` **之前**：
                            // 若放在取号之后，暂停期间各 worker 仍会把游标一路推到末尾，
                            // 恢复时发现"没有剩余段了"，表现为**暂停一下就整本结束** ——
                            // 数字没算错，但功能完全不是用户要的。
                            // 放在取号之前，暂停期间没有 worker 领新段，游标原地不动。
                            if (nextIndex.get() < pendingIndices.size &&
                                !pauseController.awaitResume(isCancelled)
                            ) {
                                break
                            }
                            // ---- 内存闸门：紧张时退化为单 worker ----
                            //
                            // 【为什么需要 / 2026-10-05 真机崩溃】加载引擎前只查一次可用内存
                            // 是**不够**的：那次判断时内存还充裕，但合成一段要几十秒、
                            // 期间 ONNX 工作区与 swap 都会持续变化。真机上正是这样被杀的 ——
                            // 跑到第 10/31 段时 PSS 7.85GB、SwapPss 3.1GB，而系统
                            // `MemAvailable` 只剩 371MB，远低于加载时算出的余量：
                            //     lowmemorykiller: Kill 'com.audiobook.audiobookplayer' (28328),
                            //       oom_score_adj 0 to free 3526600kB rss, 5310868kB swap;
                            //       reason: min watermark is breached even after kill
                            // 被杀的是**整个进程**，已经入库的那几段也跟着丢。
                            //
                            // ⚠️ 两种降级必须分开，因为语义不同：
                            //   · [oomAborted] 内存**已经耗尽**（帧级探针抛了 OutOfMemoryError）
                            //     ⇒ 连 worker 0 也要停，否则它会继续领段、再被系统杀一次；
                            //   · [memoryPressure] 只是**接近**水位，尚未耗尽
                            //     ⇒ 只停 worker 1..N，留 worker 0 串行跑完剩余段（变慢但不失败）。
                            // 上一版把两者混成一个标志，导致 OOM 后 worker 0 仍会继续干活。
                            if (oomAborted.get()) {
                                Log.w(
                                    TAG,
                                    "内存已耗尽，本轮合成提前收尾，worker=$workerId 不再领新段"
                                )
                                break
                            }
                            if (workerId != 0 && memoryPressure.get()) {
                                Log.w(
                                    TAG,
                                    "内存紧张（可用 ${availableBytes(context) / 1048576}MB " +
                                        "< ${MOSS_MIN_AVAIL_BYTES / 1048576}MB），" +
                                        "worker=$workerId 停止领新段，退化为单引擎串行"
                                )
                                break
                            }
                            if (workerId == 0 && !memoryPressure.get() && isMemoryCritical(context)) {
                                memoryPressure.set(true)
                                Log.w(
                                    TAG,
                                    "内存进入危险区（可用 ${availableBytes(context) / 1048576}MB" +
                                        "，阈值 ${MOSS_MIN_AVAIL_BYTES / 1048576}MB；" +
                                        "本进程 swap ${ownSwapBytes() / 1048576}MB" +
                                        "，红线 ${MOSS_OWN_SWAP_LIMIT_BYTES / 1048576}MB）" +
                                        "，后续段降为单引擎串行以免被 lowmemorykiller 杀掉"
                                )
                            }
                            // ---- 段间引擎重载：把内存锯齿化，而不是单调爬升 ----
                            //
                            // ⚠️ 必须放在取号（`nextIndex.getAndIncrement()`）**之前**。
                            // 若放在之后，一旦重载判定为"内存不足、提前收尾"而 break，
                            // 那个已取到的号就**既没合成、也没人再领**——
                            // 段号被永久跳过，且 `doneCount` 不会 +1，
                            // 结果是这一段既不在库里、也不计入任何统计，成了隐形缺口。
                            // 放在取号之前，"放弃"就等于"没领过"，续跑会重新领到它。
                            //
                            // 此刻本线程没有任何 native 推理在跑，close 旧引擎是安全的。
                            if (engineReusedSegments >= SEGMENTS_PER_ENGINE_BATCH && !reloadSuppressed) {
                                // 已经没有段可做了就别白花 12 秒重新加载引擎。
                                // 这个检查必须放在 close 旧引擎**之前** ——
                                // 否则会出现"释放了、结果没事做"的荒唐开销。
                                if (nextIndex.get() >= pendingIndices.size || isCancelled()) {
                                    break
                                }
                                // ⚠️ **先判据再重载**：重载不是免费的 —— 新建引擎要有
                                // ~1.5GB 的**瞬时峰值**（旧的那份刚 close 还没被系统收走，
                                // 新的一份已经建起来了）。如果此刻内存已经紧张，
                                // 硬上重载会让人从"跑得慢"直接变成"立刻被杀"。
                                //
                                // 【曾经的缺陷 —— 2026-10-05 真机实测】
                                // 原先这里判据不过就 `break`（放弃整轮），日志证据：
                                //     内存已不足以安全重载引擎（可用 1792MB，重载需 ≥ 1843MB）
                                //     落盘完成: 本次入库 12 跳过(已有) 0 合计可用 12/31
                                //     服务结束：合成失败：设备内存不足，本轮已保存 12/31 段
                                // **重载只是省内存的优化手段，不是继续合成的前提。**
                                // 为省 1.5GB 峰值而丢掉剩余 19 段（≈8 分钟已算的进度 +
                                // 用户再来一轮的耐心），是本末倒置。而且此处内存
                                // （可用 1792MB、swap 1450MB）**远未到危险线**
                                // （MOSS_MIN_AVAIL_BYTES=1GB 才触发探针中止），
                                // 单引擎稳定跑完 12 段的事实也证明它是安全的。
                                //
                                // 正确做法：**放弃这次重载，用现有引擎继续跑完**。
                                // 代价是常驻内存不回落（锯齿变平缓），但已入库的段
                                // 逐段落盘、进程也不再被杀 —— 这正是本该优先保的东西。
                                val availForReload = availableBytes(context)
                                val swapForReload = ownSwapBytes()
                                if (availForReload in 1 until MOSS_RELOAD_GUARD_BYTES ||
                                    swapForReload >= MOSS_OWN_SWAP_LIMIT_BYTES
                                ) {
                                    Log.w(
                                        TAG,
                                        "内存不足以重载引擎（可用 ${availForReload / 1048576}MB" +
                                            "，重载需 ≥ ${MOSS_RELOAD_GUARD_BYTES / 1048576}MB；" +
                                            "本进程 swap ${swapForReload / 1048576}MB），" +
                                            "跳过本次重载、继续用现有引擎合成剩余段" +
                                            "（已入库 ${savedCount.get()} 段）"
                                    )
                                    // 不 break、不置 oomAborted：继续跑。
                                    // 但要把阈值抬高到不可能再触发，否则每段都会白打一条日志；
                                    // 引擎已积累超过一个批次，这里直接置一个足够大的值
                                    // 让本轮不再进入重载分支。
                                    engineReusedSegments = 0
                                    reloadSuppressed = true
                                } else {
                                    val before = swapForReload
                                    val availBefore = availForReload
                                    Log.i(
                                        TAG,
                                        "已合成 $engineReusedSegments 段，释放引擎回收内存" +
                                            "（释放前可用 ${availBefore / 1048576}MB，" +
                                            "swap 已用 ${before / 1048576}MB）"
                                    )
                                    // close 会归还 4 个 ORT session 的权重与工作区。
                                    // pool 里那一项随后被置空，避免收尾时二次 close。
                                    synchronized(pool) { pool[workerId] = null }
                                    runCatching { engine?.close() }
                                        .onFailure { Log.w(TAG, "释放引擎异常: ${it.message}") }
                                    engine = null
                                    // 给系统一点时间把刚释放的页真正收走（madvise/madvise_dontneed
                                    // 不是瞬时的）；内存充裕时不必等，紧张时多等一会儿。
                                    val waitMs = if (isMemoryCritical(context)) 4000L else 1200L
                                    System.gc()
                                    runCatching { Thread.sleep(waitMs) }
                                    val reloadStart = System.currentTimeMillis()
                                    engine = try {
                                        MossLocalEngine(MossModuleManager.moduleDir(context), threadsPerEngine)
                                    } catch (e: Throwable) {
                                        // 重载失败就明确失败：不能"跳过重载继续用旧引擎"，
                                        // 那等于回到崩溃前的常驻状态，只会换个时间点被杀。
                                        // 注意这与上面「跳过重载继续跑」是**不同的两件事**：
                                        // 那时旧引擎还在手里（能继续跑），这里是引擎已
                                        // close 且新的没建起来（手上没有引擎可用）。
                                        Log.e(TAG, "重新加载引擎失败", e)
                                        oomAborted.set(true)
                                        null
                                    }
                                    if (engine == null) break
                                    engineReusedSegments = 0
                                    batchReloadCount.incrementAndGet()
                                    Log.i(
                                        TAG,
                                        "引擎重载完成，耗时 ${System.currentTimeMillis() - reloadStart}ms" +
                                            "，当前可用 ${availableBytes(context) / 1048576}MB，" +
                                            "本进程 swap ${ownSwapBytes() / 1048576}MB"
                                    )
                                    listener.onStage(
                                        "内存回收后继续合成（剩余 ${pendingIndices.size - doneCount.get()} 段）"
                                    )
                                }
                            }

                            // 取段号：优先重试队列（内存告警作废的段），其次共享游标。
                            // 两者都没有剩余 ⇒ 本 worker 收工。
                            var index: Int
                            val retried = retryQueue.poll()
                            if (retried != null) {
                                index = retried
                            } else {
                                val slot = nextIndex.getAndIncrement()
                                if (slot >= pendingIndices.size) break
                                // 续跑时游标走的是「待合成下标表」，不是原始段号
                                index = pendingIndices[slot]
                            }
                            val paragraph = paragraphs[index]

                            // ---- 拆小片：超长段先切，必要时逐片合成再合并 ----
                            //
                            // 此前是整段直接喂模型，两个后果：
                            //  ① 撞 [DEFAULT_MAX_FRAMES]=375（≈30 秒）被**静默截断**，
                            //     用户听到的话缺了后半截，而任务却报 completed；
                            //  ② 单次解码要累积 375 步的 KV cache（见 MossLocalEngine.runDecode
                            //     每步往 pastResult 塞一份），峰值内存被这个长解码推上去。
                            //     ORT 的 CPU arena 只增不减，**峰值决定整个进程的 PSS 上限**
                            //     ⇒ 这正是本机合成反复被 lowmemorykiller 杀掉的主因之一。
                            //
                            // 拆成 ≤30 字的小片后，每片只需约 150 帧，单次解码峰值降到四成左右，
                            // 接缝用 [PcmJoiner] 做 15ms 交叉淡化缝起来。
                            //
                            // ⚠️ 片数**串行**跑，不要并行：同一个引擎的 decode_step 是顺序执行的，
                            //    并行只会让每片的峰值内存叠加，且 KV cache 相互竞争。
                            val pieces = MossTextSplitter.subPieces(paragraph)
                            // 丢弃无可朗读内容的片（纯下划线/空白会被上游归一化成空串 → 0 帧 → 抛异常）
                            val speakable = pieces.filter { MossTextSplitter.hasSpeakableContent(it) }
                            if (speakable.size != pieces.size) {
                                Log.w(
                                    TAG,
                                    "第 ${index + 1} 段丢弃 ${pieces.size - speakable.size} 个" +
                                        "无可朗读内容的小片（如整行下划线/点线/空白）"
                                )
                            }

                            val synth = try {
                                if (speakable.isEmpty()) {
                                    // 整段都没有可朗读内容 ⇒ 给静音占位，不调用引擎。
                                    // 不这么做就会在 decodeAudioTokens 的
                                    // require(audioTokens.isNotEmpty()) 上抛异常，
                                    // 而「每段一条音频」是上层（表结构、播放器）依赖的契约，
                                    // 缺行会让播放链路出现空洞。
                                    Log.w(
                                        TAG,
                                        "第 ${index + 1} 段（${paragraph.length} 字）无可朗读内容，" +
                                            "生成静音占位"
                                    )
                                    SynthesisResult(
                                        pcm = PcmJoiner.silentPcm(SILENT_SAMPLE_RATE_HINT),
                                        sampleRate = SILENT_SAMPLE_RATE_HINT,
                                        generatedFrames = 0,
                                        elapsedMs = 0L
                                    )
                                } else if (speakable.size == 1) {
                                    // 绝大多数段落走这条路：与改造前逐字节一致
                                    val ids = tokenizer.encode(speakable[0])
                                    if (ids.isEmpty()) {
                                        throw IllegalStateException("第 ${index + 1} 段分词为空")
                                    }
                                    // 每段用 index 派生种子：同一个 index 无论被哪个 worker 拿到，
                                    // 结果都一致。否则并行会让同一本书每次合成结果都不同，
                                    // 一旦出问题无法复现。
                                    engine!!.synthesize(
                                        ids,
                                        voice = voice,
                                        referencePromptCodes = referencePromptCodes,
                                        maxFrames = estimateMaxFrames(speakable[0]),
                                        seed = 1234L + index,
                                        // 帧级内存探针：解码途中一旦逼近水位就抛
                                        // OutOfMemoryError，由下面那个 catch 分支处理成
                                        // "中止本轮、保留已入库进度"，而不是等 lowmemorykiller
                                        // 把整个进程连同进度一起杀掉。
                                        isMemoryCritical = { isMemoryCritical(context) }
                                    )
                                } else {
                                    // 长段 ⇒ 逐片合成，最后在 PCM 层拼成一条。
                                    // 这里**不复用**跨段重试的片缓存：真机上内存紧张，
                                    // 攥着一段音频的多个副本等重试，正是 LMK 的目标。
                                    // 重试整段重来一次，代价只是多花几十秒，换来峰值内存确定更低。
                                    val piecePcMs = ArrayList<FloatArray>(speakable.size)
                                    var pieceRate = 0
                                    var pieceFrames = 0
                                    var pieceElapsed = 0L
                                    for ((p, piece) in speakable.withIndex()) {
                                        if (isCancelled()) break
                                        val pieceIds = tokenizer.encode(piece)
                                        if (pieceIds.isEmpty()) {
                                            Log.w(
                                                TAG,
                                                "第 ${index + 1} 段第 ${p + 1}/${speakable.size} 片分词为空，跳过"
                                            )
                                            continue
                                        }
                                        val one = engine!!.synthesize(
                                            pieceIds,
                                            voice = voice,
                                            referencePromptCodes = referencePromptCodes,
                                            maxFrames = estimateMaxFrames(piece),
                                            // 同段内所有小片共用一个种子：拼接结果与片的顺序绑定，
                                            // 重跑整段时结果一致，便于复现。
                                            seed = 1234L + index,
                                            isMemoryCritical = { isMemoryCritical(context) }
                                        )
                                        if (pieceRate != 0 && one.sampleRate != pieceRate) {
                                            throw IllegalStateException(
                                                "第 ${index + 1} 段第 ${p + 1} 片采样率 ${one.sampleRate} " +
                                                    "与前一片 $pieceRate 不一致，无法拼接"
                                            )
                                        }
                                        pieceRate = one.sampleRate
                                        piecePcMs.add(one.pcm)
                                        pieceFrames += one.generatedFrames
                                        pieceElapsed += one.elapsedMs
                                    }
                                    Log.i(
                                        TAG,
                                        "第 ${index + 1} 段 ${paragraph.length} 字 ⇒ 拆成 " +
                                            "${speakable.size} 片（最大 ${MossTextSplitter.MAX_PIECE_LENGTH} 字），" +
                                            "合并为 ${piecePcMs.sumOf { it.size }} 个采样 @ ${pieceRate}Hz"
                                    )
                                    SynthesisResult(
                                        pcm = PcmJoiner.concat(piecePcMs, pieceRate),
                                        sampleRate = pieceRate,
                                        generatedFrames = pieceFrames,
                                        elapsedMs = pieceElapsed
                                    )
                                }
                            } catch (e: OutOfMemoryError) {
                                // ⚠️ 这里曾是「内存耗尽 = 停掉整轮」，但那等于**用
                                // 1.5GB 换掉剩余的几段**。真机 18:54 的现场：
                                //
                                //   18:54:22  合成完成: frames=375 时长=30000ms 耗时=83824ms
                                //   18:54:22  内存进入危险区（可用 4630MB，swap 已用 4037MB）
                                //   18:54:23  OutOfMemoryError: 解码到第 0 帧…
                                //   18:54:23  落盘完成: 合计可用 28/31（内存耗尽中止）
                                //
                                // 两个关键事实：
                                //  ① **可用内存 4630MB 很健康**，危险的只有 swap（4037MB）。
                                //     这不是"内存不够"，是"内存换出去了还没还回来"。
                                //  ② 抛 OOM 时**引擎还占着 ~1.5GB** —— 也就是说想继续跑
                                //     也没内存可用，而不释放就永远回收不了。
                                //
                                // 正确处置顺序（先腾地方，再决定）：
                                //  1. 立刻 close 引擎，把 ~1.5GB 还给系统（这是唯一能把 swap
                                //     压下来的手段，`System.gc()` 对 ORT 的 native 分配无效）；
                                //  2. 等系统真正回收（madvise 回收不是瞬时的，实测要几秒）；
                                //  3. **重新查水位** —— 降下来了就重载引擎继续跑完剩下的段；
                                //     没降下来才认输退出。
                                //
                                // 这一段本身必然是白做的（解码到第 0 帧就抛），但后面几段
                                // 不该陪葬。少丢几段比"干净地失败"重要得多。
                                Log.e(
                                    TAG,
                                    "解码中内存告警（已入库 ${savedCount.get()} 段），" +
                                        "先释放引擎回收内存再决定是否继续",
                                    e
                                )
                                memoryPressure.set(true)
                                // 让段边界的降级闸门立即生效：其它 worker 就地收工，
                                // 不再领新段（此时内存紧张，多一个 worker 只是更快耗尽）。
                                synchronized(pool) { pool[workerId] = null }
                                runCatching { engine?.close() }
                                    .onFailure { Log.w(TAG, "告警时释放引擎异常: ${it.message}") }
                                engine = null
                                System.gc()
                                runCatching { Thread.sleep(OOM_RECLAIM_WAIT_MS) }
                                val availAfter = availableBytes(context)
                                val ownSwapAfter = ownSwapBytes()
                                val totalSwapAfter = usedSwapBytes()
                                Log.w(
                                    TAG,
                                    "释放引擎后：可用 ${availAfter / 1048576}MB，" +
                                        "本进程 swap ${ownSwapAfter / 1048576}MB" +
                                        "（整机 ${totalSwapAfter / 1048576}MB）" +
                                        "（重载需 ≥ ${MOSS_RELOAD_GUARD_BYTES / 1048576}MB 可用" +
                                        " 且本进程 swap < ${MOSS_OWN_SWAP_LIMIT_BYTES / 1048576}MB）"
                                )
                                // ⚠️ 判据用**本进程** swap，不是整机的。
                                // 释放引擎能降下来的是本进程占的那份；整机 swap 由
                                // 其它应用决定，我们 close 引擎对它毫无影响 ——
                                // 拿它判"回收是否成功"必然得出"没成功"，于是白白
                                // 放弃剩余的段（2026-10-05 19:26 那轮就是这么丢的 12 段）。
                                val recovered = ownSwapAfter < MOSS_OWN_SWAP_LIMIT_BYTES &&
                                    availAfter >= MOSS_RELOAD_GUARD_BYTES
                                if (recovered) {
                                    // ⚠️ 重跑必须有上限：同一段可能反复触发告警
                                    // （段落特别长 ⇒ 工作区一直压不下去）。
                                    // 无上限就是无限循环：重跑 → 告警 → 释放 → 重跑…
                                    // 每次都"回收成功"，永远出不去。
                                    // 超过 [MAX_OOM_RETRY] 次就认输，按段失败计入并继续 ——
                                    // 后面还有别的段要合成，不能被这一段拖住整轮。
                                    if (oomRetryCount.get() >= MAX_OOM_RETRY) {
                                        Log.e(
                                            TAG,
                                            "第 ${index + 1} 段已因内存告警重跑 $MAX_OOM_RETRY 次，" +
                                                "放弃该段并继续后续段落（已入库 ${savedCount.get()} 段）"
                                        )
                                        failedCount.incrementAndGet()
                                        doneCount.incrementAndGet()
                                        // 引擎已在上面释放掉了，得先补回来才能继续跑
                                        val recovered2 = try {
                                            MossLocalEngine(
                                                MossModuleManager.moduleDir(context), threadsPerEngine
                                            )
                                        } catch (e2: Throwable) {
                                            Log.e(TAG, "重建引擎失败，本轮收尾", e2)
                                            oomAborted.set(true)
                                            null
                                        }
                                        if (recovered2 == null) break
                                        synchronized(pool) { pool[workerId] = recovered2 }
                                        engine = recovered2
                                        engineReusedSegments = 0
                                        reloadSuppressed = false
                                        memoryPressure.set(false)
                                        continue
                                    }
                                    oomRetryCount.incrementAndGet()
                                    // 回收成功 ⇒ 把这次告警当作"这一段失败"处理，
                                    // 重新加载引擎后继续跑剩余的段。
                                    Log.i(
                                        TAG,
                                        "内存已回收，本段作废（第 ${index + 1} 段重跑），" +
                                            "继续合成剩余 ${pendingIndices.size - doneCount.get()} 段"
                                    )
                                    // ⚠️ 这一段要**重跑**，但不能靠 `nextIndex.decrementAndGet()`
                                    // 把游标往回拨 —— 那是共享游标（多个 worker 都在
                                    // getAndIncrement），一拨就会与别的 worker 的取号打架，
                                    // 表现为某些段被重复合成、某些段被跳过。
                                    // 改成放进独立的重试队列，由本 worker 优先消费。
                                    retryQueue.add(index)
                                    // doneCount 不能减：它统计"已尝试完的段"，收尾的
                                    // 「x/N」进度靠它。少算这一段只影响显示，
                                    // 而重复扣减会让分子超过分母。
                                    memoryPressure.set(false)
                                    val reloaded = try {
                                        MossLocalEngine(
                                            MossModuleManager.moduleDir(context), threadsPerEngine
                                        )
                                    } catch (e2: Throwable) {
                                        Log.e(TAG, "告警后重载引擎失败，本轮收尾", e2)
                                        oomAborted.set(true)
                                        null
                                    }
                                    if (reloaded == null) break
                                    synchronized(pool) { pool[workerId] = reloaded }
                                    engine = reloaded
                                    engineReusedSegments = 0
                                    reloadSuppressed = false
                                    memoryPressure.set(false)
                                    batchReloadCount.incrementAndGet()
                                    continue
                                }
                                Log.e(
                                    TAG,
                                    "内存回收后仍处于危险水位，停止本轮合成" +
                                        "（已入库 ${savedCount.get()} 段，稍后可从断点继续）"
                                )
                                oomAborted.set(true)
                                // 不是用户中止，所以 isCancelled() 仍为 false ——
                                // 收尾走的是"部分完成"，用户可以从断点继续。
                                break
                            } catch (e: Throwable) {
                                // 单段失败不放弃整本：记录并继续，最后由调用方汇总
                                Log.e(TAG, "第 ${index + 1} 段合成失败: ${e.message}", e)
                                failedCount.incrementAndGet()
                                doneCount.incrementAndGet()
                                continue
                            }
                            elapsedTotal.addAndGet(synth.elapsedMs)

                            val wav = pcmToWav(synth.pcm, synth.sampleRate)
                            // serverSegmentId 传 null：这条音频不是服务端产生的，没有服务端段号。
                            // 传本地序号进去会与真实的服务端 id 撞号，之后按 serverSegmentId
                            // 回查服务端的功能（如逐段音色）就会张冠李戴。
                            val saved = try {
                                // saveAudioSegmentDirect 是**立即落盘**，不是 saveAudioSegmentStreaming：
                                // 后者只保证「已入队」，段还躺在进程内存缓冲里（攒够 24 段或 2 秒才提交），
                                // 而合成器直到全部 worker 结束才 flush ⇒ 中途被 LMK 杀掉（1 GB 内存
                                // 是首选目标，属常态）就永久丢那批音频，任务行却已被 onProgress
                                // 记成「已完成」。真机实测过：completed_segments=11 而
                                // audio_segments 只有 10 行。用 Direct 后 savedCount 才有「已进库」含义。
                                //
                                // 在 worker 线程上 runBlocking 是**有意**的，不是偷懒：
                                // 入库必须逐个串行（BookStorage 内部虽然有锁，但让 N 个 worker
                                // 同时压一个 SQLite 只会互相等锁、拉长尾延迟），
                                // 而推理本来就跑在其它 worker 上，这里阻塞不影响整体吞吐。
                                runBlocking {
                                    storage.saveAudioSegmentDirect(
                                        bookName = book.displayName,
                                        serverSegmentId = null,
                                        segmentNumber = index + 1,
                                        textContent = paragraph,
                                        audioData = wav
                                    )
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "第 ${index + 1} 段入库异常: ${e.message}", e)
                                false
                            }
                            if (saved) {
                                savedCount.incrementAndGet()
                                val now = System.currentTimeMillis()
                                val sinceLast = now - lastDoneAt.getAndSet(now)
                                Log.i(
                                    TAG,
                                    "第 ${index + 1}/${paragraphs.size} 段入库成功" +
                                        "（${wav.size / 1024} KB，worker=$workerId，" +
                                        "距上一段 ${sinceLast}ms，累计已入库 ${savedCount.get()} 段）"
                                )
                            } else {
                                Log.w(TAG, "第 ${index + 1} 段入库失败（返回 false）")
                                // 失败段同样计入 doneCount（它确实尝试完了，不再阻塞后面的段），
                                // 但**不能计入 savedCount**，也不能进 onProgress 的分子 ——
                                // 否则「x / N」会把没落盘的段算成已完成，用户看到 11/31 以为够用了，
                                // 实际那几段没声音。真机踩过：tasks.completed_segments=11
                                // 而 audio_segments 只有 10 行。
                                failedCount.incrementAndGet()
                            }

                            // 本段已经彻底跑完（含入库），计入"当前这份引擎已服务的段数"。
                            // 放在这里而不是入库分支里：入库失败也算用过一次引擎
                            // （工作区已经分配又释放过了），同样会推高内存水位。
                            engineReusedSegments++

                            val done = doneCount.incrementAndGet()
                            // 用已完成段的平均耗时估算剩余。前几段样本太少，估出来会很难看，
                            // 故至少累积 3 段再给 ETA。注意要除以 worker 数 ——
                            // 多段是同时在跑的，否则会把剩余时间高估成 worker 倍。
                            //
                            // 分母是**本次待合成数**（不是全书段数）：续跑时 ETA 要回答的是
                            // "剩下的还要多久"，用全书段数会把已经合成好的算进剩余时间里。
                            val eta = if (done >= 3) {
                                // ⚠️ `pendingIndices.size - done` 必须是**非负**的：
                                // doneCount 在 OOM 重试路径上会被**重复增加**
                                // （放弃该段时 ++，重跑该段时又 ++），所以它可能超过
                                // pendingIndices.size。原先不夹紧就会算出负数，
                                // 而 TtsFragment 把它渲染成「剩余约 -3 分 -20 秒」——
                                // 用户看到负的剩余时间，比不显示 ETA 更糟。
                                val remaining = (pendingIndices.size - done).coerceAtLeast(0)
                                if (remaining == 0) -1L
                                else (elapsedTotal.get() / done) * remaining /
                                    workers.coerceAtLeast(1)
                            } else -1L
                            // 上报给 UI 的分子是「跳过 + **真正落库**」：任务行显示的是
                            // 「整本书已有 / 整本书总段数」。既不能只报本次新合成数
                            //（续跑后会停在「3 / 100」却标着已完成），也不能把入库失败的段算进去。
                            listener.onProgress(
                                skippedExisting + savedCount.get(),
                                paragraphs.size,
                                eta
                            )
                        }
                    }
                }
                // 等全部 worker 结束。用户中止时 worker 会在段边界自行退出；
                // 这里不做 interrupt（推理正卡在 native 调用里，interrupt 无用）。
                //
                // ⚠️ 必须带超时：原先写的是无参 it.get()，一旦某个 worker 卡在
                // native 推理里就永久阻塞，而下面 finally 里的
                // awaitTermination(10min) 是在这之后才执行的，根本轮不到它 ——
                // 注释承诺的「最多再等 10 分钟」实际上从未生效，收尾逻辑会无限挂住。
                //
                // ⚠️⚠️ **超时后绝不能 cancel(true)**（2026-10-05 真机日志）：
                //   20:18:12  W 等待 worker 结束超时（10 分钟）
                //   20:18:33  E 第 23 段入库异常: null
                //   20:18:33  E java.lang.InterruptedException
                //   20:18:33  W 第 23 段入库失败（返回 false）
                // `cancel(true)` 会给 worker 线程发中断，而 worker 正卡在入库的
                // `runBlocking { saveAudioSegmentDirect }` 上（推理已完成、WAV 已在手里）
                // ⇒ **把已经算好的 185 帧音频直接丢掉**，而这一段白花了 22 秒推理。
                // 推理卡在 native 里时 interrupt 本来就无用（见上一段注释），
                // 所以这里只记日志：让它自己跑完，finally 里的
                // awaitTermination 才是最后一道兜底。
                futures.forEach {
                    runCatching { it.get(WORKER_JOIN_TIMEOUT_MINUTES, TimeUnit.MINUTES) }
                        .onFailure { e ->
                            Log.w(
                                TAG,
                                "等待 worker 结束超时（${WORKER_JOIN_TIMEOUT_MINUTES} 分钟）" +
                                    "：${e.message}。不中断它（会把已算好的段丢掉），" +
                                    "交给 finally 的 awaitTermination 兜底"
                            )
                        }
                }
            } finally {
                // ⚠️ 用 shutdown() 而不是 shutdownNow()：后者会给所有 worker 发
                // 中断信号，同样会把"已算好、正在入库"的那一段打掉
                // （2026-10-05 20:18 的第 23 段就是这么丢的）。
                // worker 领完段号自己会退出，shutdown() 足够；
                // 真卡在 native 里的那一个交给下面的 awaitTermination 兜底。
                executor.shutdown()
                // 不无限等：worker 若还卡在一次 native 推理里，最多再等
                // [WORKER_JOIN_TIMEOUT_MINUTES] 分钟，避免极端情况下永久挂住
                // 收尾逻辑（收尾要复位 UI、清 job）。
                runCatching { executor.awaitTermination(WORKER_JOIN_TIMEOUT_MINUTES, TimeUnit.MINUTES) }
            }
        } finally {
            // 元素可空：worker 在段间重载机制里会把已释放的那一项置空
            // （已 close 过，这里再 close 一次是幂等的，但不能 NPE）。
            pool.forEach { eng -> runCatching { eng?.close() } }
        }

        // 与在线下载同口径：强制落盘最后不满一批的片段，否则"合成完成"但最后几段没声音
        Log.i(TAG, "循环结束，落盘缓冲…")
        val flushed = storage.flushPendingAudioSegments()
        val dropped = storage.consumeFlushFailures()
        val cancelled = isCancelled()
        val availableAfter = skippedExisting + savedCount.get()
        Log.i(
            TAG,
            "落盘完成: flushed=$flushed dropped=$dropped 本次入库 ${savedCount.get()}" +
                " 跳过(已有) $skippedExisting 合计可用 $availableAfter/${paragraphs.size}" +
                " 失败=${failedCount.get()} 并行度=$actualEngines" +
                " 引擎重载=${batchReloadCount.get()} 次" +
                " 收尾可用内存=${availableBytes(context) / 1048576}MB" +
                " 收尾本进程swap=${ownSwapBytes() / 1048576}MB" +
                " (整机swap=${usedSwapBytes() / 1048576}MB)" +
                (if (cancelled) "（用户中止）" else "") +
                (if (oomAborted.get()) "（内存耗尽中止）" else "")
        )
        // ⚠️ 内存耗尽中止时**不能**报 Success。
        // Success 的语义是"该跑的都跑了"，上层会据此把任务标成 completed。
        // 实测踩过：内存耗尽提前收尾后任务行显示「已完成 10/31」，
        // 用户点开发现后面 21 段没声音，且没有任何地方提示还能续跑。
        // 走 Failed(可重试) 通道，让上层把状态留在"中断"而不是"完成"。
        if (oomAborted.get() && availableAfter < paragraphs.size) {
            return Result.Failed(
                "设备内存不足，本轮已保存 $availableAfter/${paragraphs.size} 段后暂停。" +
                    "关闭其它应用释放内存后可继续合成剩余 ${paragraphs.size - availableAfter} 段"
            )
        }
        return Result.Success(
            // savedSegments 必须是「已有 + 本次新合成」：它是书架上「x / 总数」的分子。
            // 只填本次新合成数的话，续跑成功后任务行会停在「3 / 100」却标着已完成。
  savedSegments = skippedExisting + savedCount.get(),
            totalSegments = paragraphs.size,
            cancelled = cancelled,
            parallelism = actualEngines,
            failedSegments = failedCount.get(),
            skippedSegments = skippedExisting
        )
    }

    /**
     * Float PCM -> 16bit 单声道 WAV 字节。
     *
     * 与官方 Android 示例一致（它也是把立体声降成单声道写 WAV）；本工程播放链路
     * 是单声道，保持统一避免下游采样率/声道数对不上。
     * 返回字节数组而不是写文件：入库接口收的就是 bytes，少一次落盘再读回。
     */
    fun pcmToWav(pcm: FloatArray, sampleRate: Int): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        header.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)                       // PCM
        header.putShort(1)                       // 单声道
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)            // byteRate
        header.putShort(2)                       // blockAlign
        header.putShort(16)                      // bitsPerSample
        header.put("data".toByteArray(StandardCharsets.US_ASCII))
        header.putInt(dataSize)
        out.write(header.array())

        val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) {
            body.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        out.write(body.array())
        return out.toByteArray()
    }
}
