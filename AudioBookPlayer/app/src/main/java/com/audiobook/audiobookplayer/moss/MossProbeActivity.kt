package com.audiobook.audiobookplayer.moss

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.audiobook.audiobookplayer.BuildConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 端侧合成"能不能跑"的探针页（内部工具，不做产品 UI）。
 *
 * ## 为什么需要它
 * 端侧推理是这一整块需求里**风险最高、最不可预测**的一环：模型 640 MB、
 * 要在手机 CPU 上做自回归解码、还要经 ONNX Runtime 的 native 层。
 * 在把下载/UI/播放链路全部搭好之前，必须先用最小代价回答三个问题：
 *   1. 模型在真机上能不能**加载**（内存够不够、ORT 会不会拒绝这些图）；
 *   2. 能不能**出声**（张量编排、prompt 模板、token 顺序是否都对）；
 *   3. **多快**（实时率多少，直接决定这个功能对用户是否有意义）。
 * 把这三件事压到一个自包含的页面里，跑通之后再谈产品化。
 *
 * ## 模型从哪来
 * 开发期用 adb 把模型推进 `/data/local/tmp/moss/`，本页负责复制进 App 私有目录
 * （正式版本改由下载模块完成，见 MossModuleManager 的说明）。
 */
class MossProbeActivity : Activity() {

    companion object {
        private const val TAG = "MossProbe"
        private const val STAGING = "/data/local/tmp/moss"

        /** 探针文本：短、含中英标点，能覆盖分词与合成的常见路径 */
        private const val PROBE_TEXT = "这是一段用于验证本机语音合成的测试文本。"

        /** 用 Intent extra 驱动探针（无需点击），见 onCreate 里的说明 */
        const val EXTRA_AUTO_STAGE = "auto_stage"
        const val EXTRA_AUTO_VERIFY = "auto_verify"
        const val EXTRA_AUTO_SYNTH = "auto_synth"
        const val EXTRA_AUTO_BENCH = "auto_bench"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var stageButton: Button
    private lateinit var verifyButton: Button
    private lateinit var synthButton: Button
    private lateinit var benchButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⚠️ 只在 debug 包里存在。release 包若被 `am start` 拉起，这里直接退出 ——
        // 探针页会复制模型、跑满 CPU、写 WAV 到外部目录，绝不能让它成为一个
        // 任何人都能通过 adb 触发的入口（manifest 里为了 adb 调试把 exported 开了 true）。
        if (!BuildConfig.DEBUG) {
            Toast.makeText(this, "该页面仅在调试版本可用", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        title = "端侧合成探针"

        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        stageButton = Button(this).apply {
            text = "0) 从 adb 暂存目录复制模型（开发用）"
            setOnClickListener { runAsync("复制模型") { stageModels() } }
        }
        verifyButton = Button(this).apply {
            text = "1) 校验已安装模块"
            setOnClickListener { runAsync("校验模块") { verifyInstalled() } }
        }
        synthButton = Button(this).apply {
            text = "2) 试合成"
            setOnClickListener { runAsync("试合成") { synthProbe() } }
        }
        benchButton = Button(this).apply {
            text = "3) 并行度对比实测（串行 vs 并行）"
            setOnClickListener { runAsync("并行度对比") { benchParallelism() } }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(stageButton)
            addView(verifyButton)
            addView(synthButton)
            addView(benchButton)
            addView(
                logView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(ScrollView(this).apply { addView(content) })

        appendLog("模块目录: ${MossModuleManager.moduleDir(this).absolutePath}")
        appendLog("已安装: ${MossModuleManager.isInstalled(this)}")
        appendLog("缺失文件: ${MossModuleManager.missingFiles(this)}")
        appendLog("设备 ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        appendLog("可用堆上限: ${Runtime.getRuntime().maxMemory() / 1048576} MB")

        // ---- 非交互模式 ----
        // 真机测试时遇到一个现实障碍：部分厂商 ROM（实测小米/MIUI）默认禁止
        // adb 注入输入事件（SecurityException: Injecting to another application
        // requires INJECT_EVENTS permission），`input tap` 完全不可用。
        // 而探针页的操作全靠点击 —— 于是"能不能自动跑"变成了要看机型脸色。
        //
        // 这里支持用 Intent extra 触发同一批动作，例如：
        //   adb shell am start -n <pkg>/.moss.MossProbeActivity --ez auto_stage true
        // 这样在没有输入注入权限的设备上也能完整跑完验证与性能测量，
        // 也让这套探针能被脚本/CI 复用，而不是只能靠手点。
        //
        // 仅存在于 debug 包（本 Activity 在 release 里会直接 finish）。
        if (intent?.getBooleanExtra(EXTRA_AUTO_STAGE, false) == true) {
            runAsync("复制模型") { stageModels() }
        }
        if (intent?.getBooleanExtra(EXTRA_AUTO_VERIFY, false) == true) {
            runAsync("校验模块") { verifyInstalled() }
        }
        if (intent?.getBooleanExtra(EXTRA_AUTO_SYNTH, false) == true) {
            runAsync("试合成") { synthProbe() }
        }
        if (intent?.getBooleanExtra(EXTRA_AUTO_BENCH, false) == true) {
            runAsync("并行度对比") { benchParallelism() }
        }
    }


    /**
     * 校验**当前已安装**的模块：逐文件与发布清单里的 sha256 比对。
     *
     * 为什么这一步是"下载链路正确"的关键证据：
     * 前面那些"下载成功、解压成功"只说明流程走完了；只有逐文件哈希一致，
     * 才能证明**到手机的这份内容与发布的那份逐字节相同**（传输没坏、解压没错位）。
     * 这份清单由服务端 `/moss/manifest.json` 提供，不是本地猜的。
     */
    private fun verifyInstalled() {
        require(MossModuleManager.isInstalled(this)) { "模块未安装完整，先安装或执行第 0 步" }
        val manifest = MossDownloader.fetchManifest(this)
        appendLog("清单版本 ${manifest.version}，待校验 ${manifest.files.size} 个文件")

        val dir = MossModuleManager.moduleDir(this)
        var ok = 0
        val bad = ArrayList<String>()
        for (f in manifest.files) {
            val file = File(dir, f.name)
            if (!file.isFile) {
                appendLog("  缺失: ${f.name}")
                bad.add(f.name)
                continue
            }
            val actual = MossModuleManager.sha256(file)
            if (actual.equals(f.sha256, ignoreCase = true) && file.length() == f.size) {
                ok++
            } else {
                appendLog("  不符: ${f.name} 期望 ${f.sha256.take(12)}… 实得 ${actual.take(12)}…")
                bad.add(f.name)
            }
        }
        appendLog("逐文件 sha256: $ok/${manifest.files.size} 一致")
        if (bad.isEmpty()) {
            appendLog("✅ 已安装内容与发布清单逐字节一致")
        } else {
            appendLog("❌ 有 ${bad.size} 个文件不一致: $bad")
        }
    }

    /**
     * 并行度对比实测：同一批文本，分别用「串行」与「并行」跑一遍，比总墙钟时间。
     *
     * ## 为什么要做这个对照，而不是直接相信"并行更快"
     * 这些段**同时**在跑，每个引擎内部又有 IntraOp 线程 —— 总线程数是
     * 并行度 × 每引擎线程数。8 核机器上开 4 个引擎 × 4 线程 = 16 条线程抢 8 个核，
     * 光是上下文切换与内存带宽争抢就可能把收益吃光，甚至反向变慢。
     * "并行一定更快"在这种 CPU 密集 + 大内存带宽的场景里是不成立的假设，
     * 必须实测。所以这个按钮存在的意义就是**给出本机上的真实数字**，
     * 而不是让 [MossLocalSynthesizer.planParallelism] 的公式自说自话。
     *
     * 同时报告内存：每个引擎会把模型映射进来，N 个引擎就是 N 份 ——
     * 这正是并行度的真正上限，比 CPU 更容易先撞墙。
     */
    private fun benchParallelism() {
        require(MossModuleManager.isInstalled(this)) { "模块未安装完整" }
        val tokenizer = MossModuleManager.tokenizer(this)

        // 三段等长文本，避免"段长短不一"掩盖并行收益（长短搭配时更容易看出差异，
        // 但作为对照实验，等长更干净）
        val paragraphs = listOf(
            "这是本机合成并行度的对照测试文本，第一段。",
            "这是本机合成并行度的对照测试文本，第二段。",
            "这是本机合成并行度的对照测试文本，第三段。"
        ).map { tokenizer.encode(it) }

        val (autoP, autoT) = MossLocalSynthesizer.planParallelism(this)
        appendLog("自动规划: 并行度=$autoP 每引擎线程=$autoT")
        val rt = Runtime.getRuntime()
        appendLog("堆: 已用 ${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB / 上限 ${rt.maxMemory() / 1048576} MB")

        // 同一次运行里跑多种配置，设备状态相近，可比性最好。
        //
        // ⚠️ 这里**刻意不再试 4 个引擎**：上一轮实测到并行度=4 时，
        // 加载过程把可用内存吃光，系统 lowmemorykiller 直接把本进程杀掉
        // （日志：Kill 'com.audiobook.audiobookplayer' to free 2406384kB rss），
        // 结果不是"慢"而是"整个 App 没了"。生产代码已经不会再开那么多
        // （planParallelism 会按内存压低），基准测试也不该故意去撞那堵墙。
        //
        // 2×1 这个配置是**最干净的对照**：它和 1×4 的总线程数相同（都是 4），
        // 因此能回答"引擎级并行本身有没有用"，而不被"线程总数变了"混淆。
        // 1×4 → 2×2 那种比法把两个变量一起改了，结论不可靠。
        val configs = listOf(1 to 4, 2 to 4, 2 to 1, autoP to autoT).distinct()

        for ((engines, threads) in configs) {
            appendLog("\n---- 并行度=$engines 每引擎线程=$threads ----")
            val pool = ArrayList<MossLocalEngine>(engines)
            try {
                val loadStart = System.currentTimeMillis()
                for (i in 0 until engines) {
                    pool.add(MossLocalEngine(MossModuleManager.moduleDir(this), threads))
                }
                val loadMs = System.currentTimeMillis() - loadStart
                val memAfterLoad = usedMemoryMb()
                appendLog("  加载 $engines 个引擎耗时 ${loadMs}ms，加载后已用内存 ${memAfterLoad} MB")

                val wallStart = System.currentTimeMillis()
                var audioMs = 0L
                if (engines == 1) {
                    for ((i, ids) in paragraphs.withIndex()) {
                        val r = pool[0].synthesize(ids, maxFrames = 120, seed = 1234L + i)
                        audioMs += r.durationMs
                    }
                } else {
                    val next = java.util.concurrent.atomic.AtomicInteger(0)
                    val executor = java.util.concurrent.Executors.newFixedThreadPool(engines)
                    // 显式写成 Callable<Long>：ExecutorService.submit 同时有
                    // Runnable 与 Callable 两个重载，传 lambda 时 Kotlin 会因
                    // 返回类型推断不清而报"没有匹配的函数"。
                    val futures = (0 until engines).map { w ->
                        executor.submit(java.util.concurrent.Callable<Long> {
                            var localAudio = 0L
                            while (true) {
                                val i = next.getAndIncrement()
                                if (i >= paragraphs.size) break
                                val r = pool[w].synthesize(paragraphs[i], maxFrames = 120, seed = 1234L + i)
                                localAudio += r.durationMs
                            }
                            localAudio
                        })
                    }
                    try {
                        audioMs = futures.sumOf { it.get() }
                    } finally {
                        // v5/A11：任意 future 抛 ExecutionException 时也必须关池 ——
                        // 固定线程池的线程默认非 daemon，漏关会一直挂到进程结束。
                        executor.shutdownNow()
                    }
                }
                val wallMs = System.currentTimeMillis() - wallStart
                val heapMb = usedMemoryMb() - memAfterLoad
                appendLog(
                    "  墙钟 ${wallMs}ms  音频 ${audioMs}ms  实时率 %.3f"
                        .format(audioMs.toDouble() / wallMs)
                )
                // ⚠️ 只报告 Java 堆增量是不够的：ONNX Runtime 的权重是 **native 内存**，
                // 不占 Java 堆。所以"堆只涨了几 MB"完全不能说明没有内存压力 ——
                // 真正的占用要看 dumpsys meminfo 的 TOTAL PSS（我在设备侧另行抓取）。
                // 这里两个都报，避免把 heap 增量误读成模型占用。
                appendLog("  Java 堆增量 ${heapMb} MB（注意：模型是 native 内存，不在此列）")
                appendLog("  当前 totalPss: ${readTotalPssMb()} MB")
            } catch (t: Throwable) {
                appendLog("  失败: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                pool.forEach { runCatching { it.close() } }
            }
        }
        appendLog("\n结论看实时率：越大越快。若并行度提高后实时率反而下降，说明撞到了内存带宽或内存上限。")
    }

    private fun usedMemoryMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576
    }

    /**
     * 读本进程的 totalPss。
     * 这是唯一能反映"模型到底占了多少"的指标 —— 它是 native 内存，
     * 不出现在 Runtime 的堆统计里。读不到时返回 -1，绝不假装是 0。
     */
    private fun readTotalPssMb(): Long {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(info)
            info.totalPss / 1024L
        } catch (e: Throwable) {
            -1L
        }
    }

    private fun runAsync(label: String, block: () -> Unit) {
        stageButton.isEnabled = false
        synthButton.isEnabled = false
        appendLog("\n===== $label 开始 =====")
        Thread {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "$label 失败", t)
                appendLog("[$label] 失败: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                mainHandler.post {
                    stageButton.isEnabled = true
                    synthButton.isEnabled = true
                }
            }
        }.start()
    }

    /** 把 /data/local/tmp/moss 下的模型复制进 App 私有目录 */
    private fun stageModels() {
        val src = File(STAGING)
        require(src.isDirectory) { "暂存目录不存在: $STAGING（先用 adb push 推模型）" }
        val dst = MossModuleManager.moduleDir(this).apply { mkdirs() }
        val files = src.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        require(files.isNotEmpty()) { "暂存目录为空: $STAGING" }
        var total = 0L
        for (f in files) {
            val target = File(dst, f.name)
            if (target.isFile && target.length() == f.length()) {
                appendLog("  跳过（已存在且大小一致）: ${f.name}")
                continue
            }
            val t0 = System.currentTimeMillis()
            f.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            total += target.length()
            appendLog("  复制 ${f.name}  ${target.length() / 1048576.0} MB  ${System.currentTimeMillis() - t0}ms")
        }
        MossModuleManager.clearCaches()
        appendLog("复制完成，本次写入 ${total / 1048576} MB")
        appendLog("isInstalled = ${MossModuleManager.isInstalled(this)}")
        appendLog("缺失文件 = ${MossModuleManager.missingFiles(this)}")
    }

    /** 真正跑一次合成，打印耗时与实时率，并把 WAV 写到外部目录便于拉回来听 */
    private fun synthProbe() {
        require(MossModuleManager.isInstalled(this)) { "模块未安装完整，先执行第 1 步" }

        val t0 = System.currentTimeMillis()
        val tokenizer = MossModuleManager.tokenizer(this)
        appendLog("分词器加载完成 ${System.currentTimeMillis() - t0}ms, vocab=${tokenizer.size}")

        val ids = tokenizer.encode(PROBE_TEXT)
        appendLog("文本: $PROBE_TEXT")
        appendLog("token ids(${ids.size}): ${ids.toList()}")

        val engine = MossLocalEngine(MossModuleManager.moduleDir(this), cpuThreads = 4)
        try {
            appendLog("可用内置音色: ${engine.builtinVoices}")
            // 先来一小段（80 帧 ≈ 6.4 秒音频）作为冒烟；跑通后再看全速
            for (frames in intArrayOf(40, 80)) {
                val t1 = System.currentTimeMillis()
                val result = engine.synthesize(ids, voice = MossLocalEngine.DEFAULT_VOICE, maxFrames = frames)
                val wall = System.currentTimeMillis() - t1
                appendLog(
                    "maxFrames=$frames -> 生成 ${result.generatedFrames} 帧, " +
                        "音频 ${result.durationMs}ms, 耗时 ${result.elapsedMs}ms, " +
                        "实时率 %.3f, 采样率 %d".format(result.realtimeFactor, result.sampleRate)
                )
                val out = File(getExternalFilesDir(null), "moss_probe_$frames.wav")
                writeWav(result.pcm, result.sampleRate, out)
                appendLog("  已写 WAV: ${out.absolutePath} (${out.length() / 1024} KB, 外层 $wall ms)")
            }
        } finally {
            engine.close()
        }
    }

    /** 与官方示例一致的 16-bit 单声道 WAV 写出 */
    private fun writeWav(pcm: FloatArray, sampleRate: Int, out: File) {
        out.parentFile?.mkdirs()
        val dataSize = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        buf.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(1)
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray(StandardCharsets.US_ASCII))
        buf.putInt(dataSize)
        for (sample in pcm) {
            buf.putShort((sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        out.writeBytes(buf.array())
    }

    private fun appendLog(message: String) {
        Log.i(TAG, message)
        mainHandler.post { logView.append("$message\n") }
    }
}
