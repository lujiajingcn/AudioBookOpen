package com.audiobook.audiobookplayer.moss

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.Random
import kotlin.math.min

/**
 * MOSS-TTS-Nano 端侧推理引擎（ONNX Runtime / CPU）。
 *
 * ## 来源与许可
 * 本文件的算法与张量编排改写自官方示例
 * `OpenMOSS/MOSS-TTS-Nano/examples/android_onnx_runtime/.../MossOnnxDemoEngine.kt`
 * （Apache-2.0，Copyright 2026 OpenMOSS Team, Fudan University, SII and MOSI）。
 * 官方那份是"演示"，主要差别在于：
 *   1. 官方只跑**预分词好的**样例，不接文本。本类接在 [SentencePieceTokenizer] 之后，
 *      真正接受文本 —— 这是它没铺完的那一段（官方 README 明说 Android 上还没做分词）。
 *   2. 官方把 WAV 写到 cacheDir 就完事。本类把结果按段落返回，交给播放器用。
 *   3. 官方硬编码 2 线程；本类把线程数暴露出来，方便按机型调。
 *
 * ## 数据流（与官方一致，四张图 + 两处权重分片）
 *   文本 -> token ids
 *        -> prefill（整段提示 + 文本一次性前向，产出 KV cache）
 *        -> decode 循环（每帧一次 decode_step + 一次 local_fixed_sampled_frame 采样）
 *        -> 音频 token 序列
 *        -> codec decode_full（token -> 48kHz 立体声波形）
 *        -> 单声道 16bit PCM
 *
 * ## 必须在 UI 层处理的代价
 * 这是 **0.1B 参数的自回归模型**，且要在手机上 CPU 推理：
 *   · 权重常驻约 640 MB（global 440MB + local 229MB 会被 mmap，但活跃页仍占内存）
 *   · 每帧要走一次 decode_step，12.5 帧/秒音频；一段 10 秒音频约 125 帧
 * 具体每帧耗时必须**实测**（见 elapsedMs / framesPerSecond），不要凭想象给用户承诺。
 * 因此本引擎设计成"一段一段合成"，而不是"一次把整本书读完"。
 */
class MossLocalEngine(
    modelRoot: File,
    private val cpuThreads: Int = 4
) : Closeable {

    companion object {
        private const val TAG = "MossLocalEngine"

        /**
         * 官方示例把 maxFrames 限到 160 做冒烟测试（1 帧 ≈ 1/12.5 秒）。
         * manifest 里的 generation_defaults.max_new_frames = 375（≈30 秒）。
         * 这里取 manifest 值作为上限，由调用方按段落长度决定实际要多少帧。
         */
        const val DEFAULT_VOICE = "Junhao"

        /**
         * 每隔多少帧调一次内存探针。1 帧 ≈ 80ms 音频，8 帧 ≈ 0.64 秒。
         *
         * 权衡：太稀则在 lowmemorykiller 动手前来不及收尾（实测 63 秒才被杀，
         * 但那段是 2 引擎并行；单引擎下更短），太密则每帧都去问
         * ActivityManager.getMemoryInfo() 本身的开销不划算。
         */
        const val MEMORY_PROBE_INTERVAL = 8

        /**
         * 把 interleaved 立体声（`[L0,R0,L1,R1,…]`）重排成 codec 要的 planar（`[L…L][R…R]`）。
         *
         * ⚠️ 模型签名 `waveform: [batch, 2, waveform_length]`（**rank 3**），
         * 不是「interleaved 原样展平成 `[1,1,N,2]`」（rank 4）。传 rank 4 真机实测报
         * `ORT_INVALID_ARGUMENT - Invalid rank for input: waveform Got: 4 Expected: 3`。
         * 抽成 companion 纯函数是为了让单测能覆盖它 —— 单测里没有真模型，
         * 形状错了 CI 不会红，只有真机上整本书解析完才炸。
         *
         * @return 长度 = 采样帧数 × 2 的 planar 缓冲，配形状 `[1, 2, numSamples]`
         */
        @JvmStatic
        fun interleaveToPlanar(interleaved: FloatArray): FloatArray {
            require(interleaved.isNotEmpty()) { "参考音频为空" }
            require(interleaved.size % 2 == 0) { "参考音频必须是立体声 interleaved" }
            val n = interleaved.size / 2
            val out = FloatArray(n * 2)
            var li = 0
            var ri = 1
            for (i in 0 until n) {
                out[i] = interleaved[li]
                out[n + i] = interleaved[ri]
                li += 2
                ri += 2
            }
            return out
        }

        /**
         * 定位模块清单。
         *
         * 两种目录形态都要认：
         *   · 官方示例那种**带子目录**的结构（models/MOSS-TTS-Nano-100M-ONNX/…）
         *   · 本工程模块包的**扁平**结构（所有文件直接躺在模块目录下）
         * 扁平结构是 App 解压后的形态，官方结构用于开发期直接用 adb 推官方的 models 目录，
         * 两种都得能跑，否则"照官方文档推文件"就会莫名其妙报找不到清单。
         */
        private fun resolveManifestPath(modelRoot: File): File {
            val candidates = listOf(
                File(modelRoot, "browser_poc_manifest.json"),
                File(modelRoot, "MOSS-TTS-Nano-100M-ONNX/browser_poc_manifest.json"),
                File(modelRoot, "MOSS-TTS-Nano-ONNX-CPU/browser_poc_manifest.json")
            )
            return candidates.firstOrNull { it.isFile }
                ?: error("browser_poc_manifest.json 未找到，尝试过: ${candidates.joinToString { it.absolutePath }}")
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val manifestFile: File = resolveManifestPath(modelRoot)
    private val manifestDir: File = manifestFile.parentFile ?: modelRoot
    private val manifest: ModelManifest = ModelManifest.fromJson(readJson(manifestFile))
    private val ttsMetaPath = resolveRelative(manifest.modelFiles.ttsMeta)
    private val codecMetaPath = resolveRelative(manifest.modelFiles.codecMeta)
    private val ttsMeta = TtsMeta.fromJson(readJson(ttsMetaPath))
    private val codecMeta = CodecMeta.fromJson(readJson(codecMetaPath))
    private val ttsDir = ttsMetaPath.parentFile ?: manifestDir
    private val codecDir = codecMetaPath.parentFile ?: manifestDir

    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(cpuThreads.coerceAtLeast(1))
        setInterOpNumThreads(1)
        // 关掉内存 arena：ONNX Runtime 默认用 BFC arena 做内存池，为了后续推理快
        // 而**不把空闲块还给系统**。本机合成是长时间、大块头的工作负载，
        // 峰值内存比那点分配开销重要得多，因此这里关掉它，
        // 让中间张量用完即还。代价是每段推理会多一些 malloc/free。
        setMemoryPatternOptimization(false)
        // ⚠️ 这里必须用 ORT 的**真 API**，不能用 addConfigEntry 传键名。
        //
        // 【踩坑记录 / 2026-10-05 真机崩溃根因】原先写的是：
        //     addConfigEntry("memory.enable_memory_arena", "0")
        //     addConfigEntry("session.use_env_allocators", "0")
        // 这两个键名在 ORT 1.24.3 里**不存在**。ORT 对未知 config key **不报错**
        // （见 onnxruntime/core/session/inference_session.cc 的
        //  AddConfigEntry 调用点：只存进 map，不校验），所以这段"关 arena 救命"的代码
        // 一直是个**静默的空操作** —— 注释里写的"实测省内存"从未发生。
        //
        // 代价是真机上被 LMK 杀了：K30 Pro（MemTotal 7.8GB）跑到第 10/31 段时
        //  Native Heap 已达 4.6GB、PSS 7.85GB、SwapPss 3.1GB，进程实际驻留 8.8GB
        //  超过整机物理内存；系统换出 5.3GB 仍跌破最低水位，遂 SIGKILL：
        //      lowmemorykiller: Kill 'com.audiobook.audiobookplayer' (28328),
        //        oom_score_adj 0 to free 3526600kB rss, 5310868kB swap;
        //        reason: min watermark is breached even after kill
        //      Zygote: Process 28328 exited due to signal 9 (Killed)
        //  没有 tombstone、没有 FATAL —— 因为这不是崩溃，是被杀。
        //
        // 正确写法是 Java API：setCPUArenaAllocator(false)（javap 可见
        // OrtSession$SessionOptions 有此方法）。改完务必看下面那条日志确认生效。
        setCPUArenaAllocator(false)
        Log.i(
            TAG,
            "ORT 内存策略: CPU arena=false, memoryPattern=false, " +
                "intraOp=$cpuThreads interOp=1 optLevel=ALL_OPT"
        )
    }

    private val prefillSession = createSession(File(ttsDir, ttsMeta.files.prefill))
    private val decodeSession = createSession(File(ttsDir, ttsMeta.files.decodeStep))
    private val localFixedFrameSession = createSession(File(ttsDir, ttsMeta.files.localFixedSampledFrame))
    private val codecDecodeSession = createSession(File(codecDir, codecMeta.files.decodeFull))

    /**
     * 音频**编码**会话（音色克隆用），模块包里没有对应文件时为 null。
     *
     * 与上面几个会话不同，这个是**可选加载**的：老模块包（1.0.0）只带decode，
     * 若在这里无条件 createSession 就会把「没装新模块」的用户卡在引擎构造阶段——
     * 合成完全起不来，而实际只是不能用自定义音色。故惰性创建 + 失败降级。
     */
    private val codecEncodeSession: OrtSession? = runCatching {
        val encodeName = codecMeta.files.encodeOrNull() ?: return@runCatching null
        val f = resolveRelative(encodeName)
        if (!f.isFile) {
            Log.w(TAG, "模块包缺少 encode 模型，跳过音色克隆: ${f.name}")
            return@runCatching null
        }
        createSession(f)
    }.onFailure { Log.w(TAG, "encode 会话创建失败，音色克隆不可用", it) }.getOrNull()

    /** 本引擎是否支持用参考音频克隆音色（模块包带没带 encode 模型） */
    val supportsVoiceClone: Boolean get() = codecEncodeSession != null

    /** 可用的内置音色名（来自 manifest，无需用户自备参考音频） */
    val builtinVoices: List<String> = manifest.builtinVoices
        .filter { it.promptAudioCodes.isNotEmpty() }
        .map { it.voice }

    /**
     * 合成一段文本。
     *
     * @param textTokenIds 由 [SentencePieceTokenizer] 产出的 id
     * @param voice 内置音色名；不在列表里则退回第一个可用音色
     * @param maxFrames 最多生成多少帧（1 帧 ≈ 1/12.5 s ≈ 80 ms 音频）
     * @param referencePromptCodes 由 [encodeReferenceAudio] 算出的参考音频码。
     *   非空时**优先于** [voice]：这正是"用自己的声音合成"的入口。
     *   为空则回落到内置音色（老行为不变）。
     */
    @Suppress("LongParameterList")
    fun synthesize(
        textTokenIds: IntArray,
        voice: String = DEFAULT_VOICE,
        maxFrames: Int = manifest.generationDefaults.maxNewFrames,
        seed: Long = 1234L,
        referencePromptCodes: List<IntArray>? = null,
        /**
         * 帧级内存探针。返回 true 表示内存已到危险水位，调用方应尽快收尾。
         *
         * ## 为什么需要它（2026-10-05 两次真机 LMK）
         * 之前所有内存判断都在**段边界**做（合成器领新段前查一次 `availableBytes`），
         * 但 lowmemorykiller **不按段边界杀人** —— 实测在 K30 Pro 上，
         * 第二次续跑时 21 段**一段都没做完**，2 个引擎加载完 63 秒后进程就被杀：
         *     lowmemorykiller: Kill 'com.audiobook.audiobookplayer' (11485),
         *       oom_score_adj 0 to free 3814352kB rss, 4797180kB swap
         * 也就是说危险是在**单段解码过程里**出现的，段边界的闸门根本没机会开。
         *
         * 现在把探针放进解码循环：每 N 帧问一次，逼近水位就抛
         * [OutOfMemoryError] 出去，让上层走"已入库 N 段、剩余待续跑"的正常收尾
         * —— 保住已完成的进度，而不是让 LMK 把整个进程连同进度一起杀掉。
         */
        isMemoryCritical: () -> Boolean = { false }
    ): SynthesisResult {
        require(textTokenIds.isNotEmpty()) { "textTokenIds 不能为空" }
        val startedAt = System.currentTimeMillis()

        val promptCodes = referencePromptCodes?.takeIf { it.isNotEmpty() }
            ?: selectBuiltinVoicePromptCodes(voice)
        val inputRows = buildInputRows(textTokenIds, promptCodes)
        val prefillResult = runPrefill(inputRows)
        val audioTokens = runDecode(prefillResult, maxFrames, seed, isMemoryCritical)
        val pcm = decodeAudioTokens(audioTokens)
        val sampleRate = codecMeta.codecConfig.sampleRate

        val elapsed = System.currentTimeMillis() - startedAt
        Log.i(
            TAG,
            "合成完成: frames=${audioTokens.size} 采样率=${sampleRate}Hz " +
                "时长=${pcm.size * 1000L / sampleRate}ms 耗时=${elapsed}ms " +
                "语音时长比=${if (elapsed > 0) String.format("%.3f", pcm.size * 1000.0 / sampleRate / elapsed) else "?"}"
        )
        return SynthesisResult(
            pcm = pcm,
            sampleRate = sampleRate,
            generatedFrames = audioTokens.size,
            elapsedMs = elapsed
        )
    }

override fun close() {
        runCatching { codecEncodeSession?.close() }
        runCatching { codecDecodeSession.close() }
        runCatching { localFixedFrameSession.close() }
        runCatching { decodeSession.close() }
        runCatching { prefillSession.close() }
        runCatching { sessionOptions.close() }
    }

    // ------------------------------------------------------------------
    // 以下是官方示例的移植部分，逻辑保持一致，只做了必要的适配与注释
    // ------------------------------------------------------------------

    private fun createSession(modelFile: File): OrtSession {
        require(modelFile.isFile) { "缺少 ONNX 文件: ${modelFile.absolutePath}" }
        return env.createSession(modelFile.absolutePath, sessionOptions)
    }

    /**
     * manifest 里 codec_meta 写的是相对路径 `../MOSS-Audio-Tokenizer-Nano-ONNX/xxx.json`，
     * 官方示例还兼容过 `-CPU` 后缀的目录名。本工程的模块包是**扁平**结构
     * （所有文件同目录），故先按相对路径试，再按"同目录同名"兜底。
     */
    private fun resolveRelative(relativePath: String): File {
        val direct = File(manifestDir, relativePath).canonicalFile
        if (direct.isFile) return direct
        val flat = File(manifestDir, File(relativePath).name).canonicalFile
        if (flat.isFile) return flat
        // 兼容官方仓库的目录命名（带 -CPU 后缀）
        val alias = relativePath
            .replace("MOSS-TTS-Nano-ONNX-CPU", "MOSS-TTS-Nano-100M-ONNX")
            .replace("MOSS-Audio-Tokenizer-Nano-ONNX-CPU", "MOSS-Audio-Tokenizer-Nano-ONNX")
        return File(manifestDir, alias).canonicalFile
    }

    /**
     * 把一段音频编成 prompt 码序列（音色克隆的核心一步）。
     *
     * @param wav48kStereo 48kHz 立体声 interleaved float，见 [ReferenceWavLoader.loadForEncode]
     * @return 每帧一个 `[nVq]` 的码数组，形状与 manifest 内置音色完全一致
     *   ——因此下游 [buildInputRows] 不需要区分"内置音色"和"参考音色"。
     */
    fun encodeReferenceAudio(wav48kStereo: FloatArray): List<IntArray> {
        val session = codecEncodeSession
            ?: error("模块包不含 encode 模型，无法使用参考音频（supportsVoiceClone=false）")
        require(wav48kStereo.isNotEmpty()) { "参考音频为空" }
        require(wav48kStereo.size % 2 == 0) { "参考音频必须是立体声 interleaved" }

        val numSamples = wav48kStereo.size / 2
        // codec 的下采样率是 3840，而输入是 48000Hz —— 3840 不是 48000 的整数倍，
        // 说明模型内部自带分数步长重采样。这里只负责把**原始采样点**喂进去，
        // 输入长度 = 采样点数，不要自己先做"按帧对齐"的裁剪：那是模型内部的职责，
        // 提前按 12.5 的整数倍截断反而会让末尾丢掉半个帧、改变音色。
        //
        // ⚠️ waveform 的形状是 **[batch, 2, waveform_length]（rank 3）**，不是
        // interleave 展平的 [1,1,N,2]（rank 4）。真机实测传 rank 4 直接报
        //   ORT_INVALID_ARGUMENT - Invalid rank for input: waveform Got: 4 Expected: 3
        // 所以必须 planar 重排，见 [interleaveToPlanar] 与同名单测。
        val planar = interleaveToPlanar(wav48kStereo)
        val startedAt = System.currentTimeMillis()
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(planar), longArrayOf(1, 2, numSamples.toLong())
        ).use { waveform ->
            OnnxTensor.createTensor(
                env, IntBuffer.wrap(intArrayOf(numSamples)), longArrayOf(1)
            ).use { lengths ->
                session.run(
                    mapOf(
                        "waveform" to waveform,
                        "input_lengths" to lengths
                    )
                ).use { outputs ->
                    val codes = outputs.requiredTensor("audio_codes")
                    val flat = flattenIntTensorValue(codes.value)
                    val nVq = manifest.ttsConfig.nVq
                    // 模型签名：audio_codes = [batch, code_length, 16]，
                    // 而 nVq 也应是 16 —— 不一致说明模块与 App 版本错配，早点报错比
                    // 让下游 buildInputRows 拿着错宽度的行去 prefill 崩掉好定位。
                    if (nVq.toLong() != codes.info.shape[2]) {
                        error("nVq=$nVq 与模型输出第 3 维 ${codes.info.shape[2]} 不一致")
                    }
                    // 帧数以模型给的 audio_code_lengths 为准；拿不到才回退到整除推断
                    val declared = runCatching {
                        flattenIntTensorValue(outputs.requiredTensor("audio_code_lengths").value).firstOrNull()
                    }.getOrNull()
                    if (flat.size % nVq != 0) {
                        error("encode 输出 ${flat.size} 不能被 nVq=$nVq 整除")
                    }
                    val total = flat.size / nVq
                    val frames = declared?.takeIf { it in 1..total } ?: total
                    val result = ArrayList<IntArray>(frames)
                    for (i in 0 until frames) {
                        result.add(flat.copyOfRange(i * nVq, (i + 1) * nVq))
                    }
                    val elapsed = System.currentTimeMillis() - startedAt
                    // 码指纹：把「编码成功」升级成「可与外部逐位对照」。
                    //
                    // 只有帧数的话，"参考码" 和 "内置音色码" 只要长度相同就无法区分
                    // （内置音色恰好也有 98~102 帧的），等于没证明音色真的换了。
                    // 输出 首帧全部 nVq 个码 + 全部码的稳定 checksum，
                    // 外部（本机 Python 跑同一个 wav + 同一个 encode 模型）可以
                    // 算出同样的两个数对上；换一个参考音频这两个数就会变。
                    //
                    // ⚠️ 不要用 String.hashCode() 之类的实现细节做 checksum ——
                    // 它随 JDK 版本变。这里用固定的 FNV-1a 32bit，口径写死在
                    // [codesFingerprint] 里，任何一侧都能复现。
                    val fp = codesFingerprint(result)
                    Log.i(
                        TAG,
                        "参考音频编码完成: ${numSamples} 采样(${"%.1f".format(numSamples / 48000.0)}s) " +
                            "-> $frames 帧 codes（模型声明 ${declared ?: "无"}），耗时=${elapsed}ms"
                    )
                    Log.i(
                        TAG,
                        "参考音频码指纹: nVq=$nVq frames=$frames " +
                            "sum=${fp.sum} first=[${fp.first.joinToString(",")}] last=[${fp.last.joinToString(",")}]"
                    )
                    return result
                }
            }
        }
    }

    private fun buildInputRows(textTokenIds: IntArray, promptAudioCodes: List<IntArray>): InputRows {
        val cfg = manifest.ttsConfig
        val rowWidth = cfg.nVq + 1
        val prefixTokens = manifest.promptTemplates.userPromptPrefixTokenIds + cfg.audioStartTokenId
        val suffixTokens = intArrayOf(cfg.audioEndTokenId) +
            manifest.promptTemplates.userPromptAfterReferenceTokenIds +
            textTokenIds +
            manifest.promptTemplates.assistantPromptPrefixTokenIds +
            intArrayOf(cfg.audioStartTokenId)

        val rows = ArrayList<IntArray>()
        rows += buildTextRows(prefixTokens, cfg, rowWidth)
        rows += buildAudioRows(promptAudioCodes, cfg, rowWidth)
        rows += buildTextRows(suffixTokens, cfg, rowWidth)
        return InputRows(rows.toTypedArray(), IntArray(rows.size) { 1 })
    }

    private fun buildTextRows(tokens: IntArray, cfg: TtsConfig, rowWidth: Int): List<IntArray> =
        tokens.map { token ->
            IntArray(rowWidth) { index -> if (index == 0) token else cfg.audioPadTokenId }
        }

    private fun buildAudioRows(audioCodes: List<IntArray>, cfg: TtsConfig, rowWidth: Int): List<IntArray> =
        audioCodes.map { codeRow ->
            IntArray(rowWidth) { index ->
                when {
                    index == 0 -> cfg.audioUserSlotTokenId
                    index - 1 < min(codeRow.size, cfg.nVq) -> codeRow[index - 1]
                    else -> cfg.audioPadTokenId
                }
            }
        }

    private fun selectBuiltinVoicePromptCodes(voice: String): List<IntArray> {
        val selected = manifest.builtinVoices.firstOrNull {
            it.voice == voice && it.promptAudioCodes.isNotEmpty()
        } ?: manifest.builtinVoices.firstOrNull { it.promptAudioCodes.isNotEmpty() }
        return selected?.promptAudioCodes
            ?: error("manifest 里没有任何内置音色，模块包可能不完整: ${manifestFile.absolutePath}")
    }

    private fun runPrefill(inputRows: InputRows): PrefillResult {
        val seqLen = inputRows.inputIds.size
        val rowWidth = inputRows.inputIds[0].size
        val inputIdsFlat = IntArray(seqLen * rowWidth)
        var offset = 0
        for (row in inputRows.inputIds) {
            for (value in row) inputIdsFlat[offset++] = value
        }
        OnnxTensor.createTensor(
            env, IntBuffer.wrap(inputIdsFlat), longArrayOf(1, seqLen.toLong(), rowWidth.toLong())
        ).use { inputIdsTensor ->
            OnnxTensor.createTensor(
                env, IntBuffer.wrap(inputRows.attentionMask), longArrayOf(1, seqLen.toLong())
            ).use { maskTensor ->
                val outputs = prefillSession.run(
                    mapOf("input_ids" to inputIdsTensor, "attention_mask" to maskTensor)
                )
                return PrefillResult(
                    globalHidden = extractLastHiddenTensor(outputs.requiredTensor("global_hidden")),
                    pastValidLengths = seqLen,
                    pastResult = outputs
                )
            }
        }
    }

    private fun runDecode(
        prefillResult: PrefillResult,
        maxFrames: Int,
        seed: Long,
        isMemoryCritical: () -> Boolean
    ): List<IntArray> {
        val cfg = manifest.ttsConfig
        val audioTokens = ArrayList<IntArray>()
        val rowWidth = cfg.nVq + 1
        val cappedMaxFrames = maxFrames.coerceAtMost(manifest.generationDefaults.maxNewFrames)
        val previousTokenSets = Array(cfg.nVq) { HashSet<Int>() }
        val decodePastInputNames = ttsMeta.onnx.decodeInputNames.drop(2)
        val decodePresentOutputNames = ttsMeta.onnx.decodeOutputNames.drop(1)
        val random = Random(seed)
        var pastValidLengths = prefillResult.pastValidLengths
        var globalHidden = prefillResult.globalHidden
        var pastResult: OrtSession.Result? = prefillResult.pastResult

        try {
            for (step in 0 until cappedMaxFrames) {
                // 帧级内存探针。每 8 帧查一次：太少则发现太晚（来不及收尾），
                // 太多则 ActivityManager.getMemoryInfo() 本身的开销不划算。
                // 1 帧 ≈ 80ms 音频，8 帧 ≈ 0.64 秒，足够在 LMK 动手前收尾。
                if (step % MEMORY_PROBE_INTERVAL == 0 && isMemoryCritical()) {
                    throw OutOfMemoryError(
                        "本机合成：解码到第 $step 帧时系统内存已到危险水位，" +
                            "主动中止本段以保住已入库的进度"
                    )
                }
                val frameResult = runLocalFixedSampledFrame(globalHidden, previousTokenSets, random)
                if (!frameResult.shouldContinue) break

                val audioRow = IntArray(rowWidth) { index ->
                    if (index == 0) cfg.audioAssistantSlotTokenId else cfg.audioPadTokenId
                }
                for (quantizer in 0 until cfg.nVq) {
                    val token = frameResult.frame[quantizer]
                    audioRow[quantizer + 1] = token
                    previousTokenSets[quantizer].add(token)
                }
                audioTokens += frameResult.frame

                OnnxTensor.createTensor(
                    env, IntBuffer.wrap(audioRow), longArrayOf(1, 1, rowWidth.toLong())
                ).use { inputTensor ->
                    OnnxTensor.createTensor(
                        env, IntBuffer.wrap(intArrayOf(pastValidLengths)), longArrayOf(1)
                    ).use { pastTensor ->
                        val feeds = linkedMapOf<String, OnnxTensorLike>(
                            "input_ids" to inputTensor,
                            "past_valid_lengths" to pastTensor
                        )
                        val previousPastResult = pastResult ?: error("缺少 decode 的 KV cache")
                        for (index in decodePastInputNames.indices) {
                            feeds[decodePastInputNames[index]] =
                                previousPastResult.requiredTensor(decodePresentOutputNames[index])
                        }
                        val outputs = decodeSession.run(feeds)
                        val nextGlobalHidden = extractLastHiddenTensor(outputs.requiredTensor("global_hidden"))
                        globalHidden.close()
                        previousPastResult.close()
                        pastResult = outputs
                        globalHidden = nextGlobalHidden
                        pastValidLengths += 1
                    }
                }
            }
        } finally {
            globalHidden.close()
            pastResult?.close()
        }
        return audioTokens
    }

    private fun runLocalFixedSampledFrame(
        globalHidden: OnnxTensor,
        previousTokenSets: Array<HashSet<Int>>,
        random: Random
    ): LocalFrameResult {
        val cfg = manifest.ttsConfig
        val audioCodebookSize = cfg.audioCodebookSizes.firstOrNull() ?: 1024
        val seenMask = IntArray(cfg.nVq * audioCodebookSize)
        for (channelIndex in previousTokenSets.indices) {
            val channelOffset = channelIndex * audioCodebookSize
            for (tokenId in previousTokenSets[channelIndex]) {
                if (tokenId in 0 until audioCodebookSize) {
                    seenMask[channelOffset + tokenId] = 1
                }
            }
        }
        val assistantRandom = floatArrayOf(random.nextDouble().coerceIn(1e-6, 1.0 - 1e-6).toFloat())
        val audioRandom = FloatArray(cfg.nVq) {
            random.nextDouble().coerceIn(1e-6, 1.0 - 1e-6).toFloat()
        }
        OnnxTensor.createTensor(
            env, IntBuffer.wrap(seenMask),
            longArrayOf(1, cfg.nVq.toLong(), audioCodebookSize.toLong())
        ).use { seenTensor ->
            OnnxTensor.createTensor(
                env, FloatBuffer.wrap(assistantRandom), longArrayOf(1)
            ).use { assistantTensor ->
                OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(audioRandom), longArrayOf(1, cfg.nVq.toLong())
                ).use { audioTensor ->
                    val outputs = localFixedFrameSession.run(
                        mapOf(
                            "global_hidden" to globalHidden,
                            "repetition_seen_mask" to seenTensor,
                            "assistant_random_u" to assistantTensor,
                            "audio_random_u" to audioTensor
                        )
                    )
                    outputs.use {
                        return LocalFrameResult(
                            shouldContinue = it.requiredTensor("should_continue").scalarInt() > 0,
                            frame = it.requiredTensor("frame_token_ids").intArrayValue()
                        )
                    }
                }
            }
        }
    }

    private fun decodeAudioTokens(audioTokens: List<IntArray>): FloatArray {
        require(audioTokens.isNotEmpty()) { "没有生成任何音频 token" }
        val numFrames = audioTokens.size
        val numQuantizers = manifest.ttsConfig.nVq
        val audioCodesFlat = IntArray(numFrames * numQuantizers)
        var offset = 0
        for (frame in audioTokens) {
            for (quantizer in 0 until numQuantizers) {
                audioCodesFlat[offset++] = frame[quantizer]
            }
        }
        OnnxTensor.createTensor(
            env, IntBuffer.wrap(audioCodesFlat),
            longArrayOf(1, numFrames.toLong(), numQuantizers.toLong())
        ).use { codesTensor ->
            OnnxTensor.createTensor(
                env, IntBuffer.wrap(intArrayOf(numFrames)), longArrayOf(1)
            ).use { lengthsTensor ->
                val outputs = codecDecodeSession.run(
                    mapOf("audio_codes" to codesTensor, "audio_code_lengths" to lengthsTensor)
                )
                outputs.use {
                    val audio = it.requiredTensor("audio").value as Array<*>
                    val batch = audio[0] as Array<*>
                    val channels = batch.map { ch -> ch as FloatArray }
                    val reportedLength = it.requiredTensor("audio_lengths").scalarInt()
                    val length = min(reportedLength, channels.minOfOrNull { ch -> ch.size } ?: 0)
                    // 官方示例把立体声降成单声道便于写 WAV；本工程播放链路也是单声道，
                    // 保持一致以免下游采样率/声道数对不上。
                    //
                    // ⚠️ 这里**不能**写成
                    //     FloatArray(length) { i -> channels.sumOf { it[i].toDouble() }.toFloat() / n }
                    // 那个写法每取一个采样点都要把各声道装箱成 java.lang.Double 再拆箱：
                    // 48kHz × 15.8 秒 = 75.8 万个 Double 对象（每个 16 字节），
                    // 一段就制造 ~12 MB 垃圾。而这段音频此刻正与 ONNX 的大块工作区
                    // 共存于内存紧张期，多出来的 ART 堆会直接推高 PSS
                    //（崩溃前日志里 `GC freed ... 36(2448KB) LOS objects` 就是它）。
                    // 改成手写循环：一次求和、零装箱、零中间对象。
                    val channelCount = channels.size
                    val out = FloatArray(length)
                    if (channelCount == 1) {
                        val mono = channels[0]
                        System.arraycopy(mono, 0, out, 0, length)
                    } else {
                        for (sampleIndex in 0 until length) {
                            var acc = 0f
                            for (ch in channels) acc += ch[sampleIndex]
                            out[sampleIndex] = acc / channelCount
                        }
                    }
                    return out
                }
            }
        }
    }

    private fun readJson(file: File): JSONObject {
        require(file.isFile) { "缺少 JSON 文件: ${file.absolutePath}" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private data class InputRows(val inputIds: Array<IntArray>, val attentionMask: IntArray)
    private data class PrefillResult(
        val globalHidden: OnnxTensor,
        val pastValidLengths: Int,
        val pastResult: OrtSession.Result
    )
    private data class LocalFrameResult(val shouldContinue: Boolean, val frame: IntArray)
}

/** 合成结果：单声道 16-bit PCM（Float 归一化到 [-1,1]） */
data class SynthesisResult(
    val pcm: FloatArray,
    val sampleRate: Int,
    val generatedFrames: Int,
    val elapsedMs: Long
) {
    val durationMs: Long get() = if (sampleRate > 0) pcm.size * 1000L / sampleRate else 0L

    /** 实时率：>1 表示比实时快。手机上大概率远小于 1，UI 要据此给预期。 */
    val realtimeFactor: Double
        get() = if (elapsedMs > 0) durationMs.toDouble() / elapsedMs else 0.0
}

// ---- manifest / meta 的数据类（字段名与官方 JSON 对齐） ----

private data class ModelManifest(
    val modelFiles: ModelFiles,
    val ttsConfig: TtsConfig,
    val promptTemplates: PromptTemplates,
    val generationDefaults: GenerationDefaults,
    val builtinVoices: List<BuiltinVoice>
) {
    companion object {
        fun fromJson(json: JSONObject) = ModelManifest(
            modelFiles = ModelFiles.fromJson(json.getJSONObject("model_files")),
            ttsConfig = TtsConfig.fromJson(json.getJSONObject("tts_config")),
            promptTemplates = PromptTemplates.fromJson(json.getJSONObject("prompt_templates")),
            generationDefaults = GenerationDefaults.fromJson(json.optJSONObject("generation_defaults")),
            builtinVoices = json.optJSONArray("builtin_voices")?.let { voices ->
                List(voices.length()) { BuiltinVoice.fromJson(voices.getJSONObject(it)) }
            } ?: emptyList()
        )
    }
}

private data class ModelFiles(val ttsMeta: String, val codecMeta: String) {
    companion object {
        fun fromJson(json: JSONObject) =
            ModelFiles(json.getString("tts_meta"), json.getString("codec_meta"))
    }
}

private data class TtsConfig(
    val nVq: Int,
    val audioPadTokenId: Int,
    val audioStartTokenId: Int,
    val audioEndTokenId: Int,
    val audioUserSlotTokenId: Int,
    val audioAssistantSlotTokenId: Int,
    val audioCodebookSizes: IntArray
) {
    companion object {
        fun fromJson(json: JSONObject) = TtsConfig(
            nVq = json.getInt("n_vq"),
            audioPadTokenId = json.getInt("audio_pad_token_id"),
            audioStartTokenId = json.getInt("audio_start_token_id"),
            audioEndTokenId = json.getInt("audio_end_token_id"),
            audioUserSlotTokenId = json.optInt("audio_user_slot_token_id", 8),
            audioAssistantSlotTokenId = json.getInt("audio_assistant_slot_token_id"),
            audioCodebookSizes = json.getJSONArray("audio_codebook_sizes").toIntArrayCompat()
        )
    }
}

private data class PromptTemplates(
    val userPromptPrefixTokenIds: IntArray,
    val userPromptAfterReferenceTokenIds: IntArray,
    val assistantPromptPrefixTokenIds: IntArray
) {
    companion object {
        fun fromJson(json: JSONObject) = PromptTemplates(
            userPromptPrefixTokenIds = json.getJSONArray("user_prompt_prefix_token_ids").toIntArrayCompat(),
            userPromptAfterReferenceTokenIds =
                json.getJSONArray("user_prompt_after_reference_token_ids").toIntArrayCompat(),
            assistantPromptPrefixTokenIds =
                json.getJSONArray("assistant_prompt_prefix_token_ids").toIntArrayCompat()
        )
    }
}

private data class GenerationDefaults(val maxNewFrames: Int = 375) {
    companion object {
        fun fromJson(json: JSONObject?) =
            GenerationDefaults(maxNewFrames = json?.optInt("max_new_frames", 375) ?: 375)
    }
}

private data class BuiltinVoice(val voice: String, val promptAudioCodes: List<IntArray>) {
    companion object {
        fun fromJson(json: JSONObject) = BuiltinVoice(
            voice = json.optString("voice", ""),
            promptAudioCodes = json.optJSONArray("prompt_audio_codes")?.let { outer ->
                List(outer.length()) { index ->
                    val row = outer.getJSONArray(index)
                    IntArray(row.length()) { row.getInt(it) }
                }
            } ?: emptyList()
        )
    }
}

private data class TtsMeta(val files: TtsFiles, val onnx: TtsOnnxNames) {
    companion object {
        fun fromJson(json: JSONObject) = TtsMeta(
            files = TtsFiles.fromJson(json.getJSONObject("files")),
            onnx = TtsOnnxNames.fromJson(json.getJSONObject("onnx"))
        )
    }
}

private data class TtsFiles(
    val prefill: String,
    val decodeStep: String,
    val localFixedSampledFrame: String
) {
    companion object {
        fun fromJson(json: JSONObject) = TtsFiles(
            prefill = json.getString("prefill"),
            decodeStep = json.getString("decode_step"),
            localFixedSampledFrame = json.getString("local_fixed_sampled_frame")
        )
    }
}

private data class TtsOnnxNames(
    val decodeInputNames: List<String>,
    val decodeOutputNames: List<String>
) {
    companion object {
        fun fromJson(json: JSONObject) = TtsOnnxNames(
            decodeInputNames = json.getJSONArray("decode_input_names").toStringList(),
            decodeOutputNames = json.getJSONArray("decode_output_names").toStringList()
        )
    }
}

private data class CodecMeta(val files: CodecFiles, val codecConfig: CodecConfig) {
    companion object {
        fun fromJson(json: JSONObject) = CodecMeta(
            files = CodecFiles.fromJson(json.getJSONObject("files")),
            codecConfig = CodecConfig.fromJson(json.getJSONObject("codec_config"))
        )
    }
}

/**
 * codec 侧模型文件名。
 *
 * [encode] 是**可选**的：只有要支持「用自己的参考音频克隆音色」才需要它。
 * 用 optString + 默认 null 而不是 getString，是为了让**旧模块包仍能正常加载**
 * ——老包里根本没有 encode 这个键，用 getString 会直接抛 JSONException，
 * 于是所有用内置音色的老用户也会一起崩，而他们其实完全不需要 encode。
 */
private data class CodecFiles(
    val decodeFull: String,
    val encode: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject) = CodecFiles(
            decodeFull = json.getString("decode_full"),
            encode = json.optString("encode").takeIf { it.isNotBlank() }
        )
    }
}

/** meta 里写了 encode 才返回文件名；没写返回 null（=不支持克隆） */
private fun CodecFiles.encodeOrNull(): String? = encode

private data class CodecConfig(val sampleRate: Int) {
    companion object {
        fun fromJson(json: JSONObject) = CodecConfig(sampleRate = json.getInt("sample_rate"))
    }
}

private fun JSONArray.toIntArrayCompat(): IntArray = IntArray(length()) { getInt(it) }
private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }

/**
 * 参考音频 prompt 码的稳定指纹。
 *
 * 用途只有一个：**让「音色真的换了」这件事可被外部逐位证实**。
 * 帧数单独不够用 —— 内置音色里恰好有 98 / 102 帧的（Junhao 98、Yuewen 102），
 * 和参考音频的 100 帧同量级，光看帧数根本区分不开。所以同时给出：
 *   · `sum`    ：全部码按 FNV-1a 32bit 顺序累计（与顺序有关，改一个码就变）
 *   · `first`  ：首帧全部 nVq 个码（人眼可核）
 *   · `last`   ：末帧全部 nVq 个码
 *
 * ## 为什么用 FNV-1a 而不是 `String.hashCode()` / `list.hashCode()`
 * 后者的取值是 JDK 实现细节，换个版本就变，外部无法复现。这里的算法写死在下面，
 * 任何语言按同样的字节序列算都能得到同一个数。
 */
internal data class CodesFingerprint(
    val sum: String,
    val first: IntArray,
    val last: IntArray
)

internal fun codesFingerprint(codes: List<IntArray>): CodesFingerprint {
    require(codes.isNotEmpty()) { "码序列为空" }
    // FNV-1a 32bit：offset basis 0x811C9DC5，prime 0x01000193
    var h = 0x811C9DC5L
    for (row in codes) {
        for (v in row) {
            // 每个码按 4 字节小端喂入，负数（码空间是 0..1023，这里不会为负）
            // 也要可复现，所以先 and 0xFF 逐字节喂。
            for (b in 0 until 4) {
                h = (h xor ((v ushr (8 * b)) and 0xFF).toLong()) and 0xFFFFFFFFL
                h = (h * 0x01000193L) and 0xFFFFFFFFL
            }
        }
    }
    return CodesFingerprint(
        sum = h.toString(16).padStart(8, '0'),
        first = codes.first().copyOf(),
        last = codes.last().copyOf()
    )
}

private fun OrtSession.Result.requiredValue(name: String): OnnxValue =
    get(name).orElseThrow { IllegalStateException("ONNX 输出缺少 $name") }

private fun OrtSession.Result.requiredTensor(name: String): OnnxTensor =
    requiredValue(name) as OnnxTensor

private fun OnnxTensor.scalarInt(): Int = flattenIntTensorValue(value).firstOrNull()
    ?: error("标量 int 张量为空")

private fun OnnxTensor.intArrayValue(): IntArray = flattenIntTensorValue(value)

/** 把 ONNX 返回的嵌套 int 结构摊平；不同 ORT 版本可能给 Int/Long/Short/Byte 或数组 */
private fun flattenIntTensorValue(raw: Any?): IntArray {
    val values = ArrayList<Int>()
    fun append(value: Any?) {
        when (value) {
            is Int -> values.add(value)
            is Long -> values.add(value.toInt())
            is Short -> values.add(value.toInt())
            is Byte -> values.add(value.toInt())
            is IntArray -> value.forEach { values.add(it) }
            is LongArray -> value.forEach { values.add(it.toInt()) }
            is ShortArray -> value.forEach { values.add(it.toInt()) }
            is ByteArray -> value.forEach { values.add(it.toInt()) }
            is Array<*> -> value.forEach { append(it) }
            null -> Unit
            else -> error("不支持的 int 张量元素类型: ${value.javaClass}")
        }
    }
    append(raw)
    return values.toIntArray()
}

/** 取出最后一层 hidden state，并复制成独立张量（原输出随后会被 close） */
private fun extractLastHiddenTensor(tensor: OnnxTensor): OnnxTensor {
    val shape = tensor.info.shape
    val hidden: FloatArray = when (shape.size) {
        2 -> {
            val value = tensor.value as Array<*>
            value[0] as FloatArray
        }
        3 -> {
            val value = tensor.value as Array<*>
            val batch = value[0] as Array<*>
            batch[batch.size - 1] as FloatArray
        }
        else -> error("global_hidden 的秩异常: ${shape.size}")
    }
    return OnnxTensor.createTensor(
        OrtEnvironment.getEnvironment(),
        FloatBuffer.wrap(hidden.copyOf()),
        longArrayOf(1, hidden.size.toLong())
    )
}
