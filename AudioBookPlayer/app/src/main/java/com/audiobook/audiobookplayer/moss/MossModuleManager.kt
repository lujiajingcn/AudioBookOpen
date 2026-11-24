package com.audiobook.audiobookplayer.moss

import android.content.Context
import android.util.Log
import java.io.File

/**
 * MOSS-TTS-Nano 模块的本地管理：安装目录、就绪判断、分词器缓存。
 *
 * ## 安装位置的选择
 * 用 **应用私有目录** `filesDir/moss/`，不用外置存储：
 *   · Android 10+ 分区存储后，App 对外置存储的自由读写被大幅限制，
 *     私有目录不需要任何权限也不会因用户清理"下载"目录而失效；
 *   · 卸载 App 即随之清理，不留 727 MB 垃圾 —— 这对几百 MB 的模型很重要，
 *     放在公共目录的话用户卸载后往往不会想起来手动删。
 * 代价是用户无法用文件管理器直接查看，但本模块本来也只需要 App 自己用。
 *
 * ## 为什么要有 `isInstalled` 这种"轻校验"
 * 只在下载完成时做一次完整 sha256 是不够的：用户可能因为空间不足、
 * 清理工具、或安装过程中断导致文件残缺。全量校验 727 MB 每次启动都做太慢，
 * 所以分两档：
 *   · [isInstalled]：只查**必需文件是否齐全 + 大小是否对得上**（读 11 次 stat，毫秒级），
 *     用于每次进页面时判断"能不能用"；
 *   · [verifyAll]：逐文件 sha256（约数秒），只在下载/解压后、以及用户主动"校验"时跑。
 */
object MossModuleManager {

    private const val TAG = "MossModuleManager"

    /** 模块在私有目录下的子目录名 */
    const val DIR_NAME = "moss"

    /**
     * 模块清单文件名。它同时是 [REQUIRED_FILES] 的第一项与 [builtinVoices] 的读取目标，
     * 单点定义 —— 三处各写一个字面量，改名时会漏掉其中一处。
     */
    private const val MANIFEST_FILE = "browser_poc_manifest.json"

    /**
     * 必需文件清单：与 .tmp_moss/pack_module.py 的 GROUPS 保持一致。
     * 这里刻意把清单写死在客户端而不是"动态读 manifest"：
     * 缺文件时必须能在**没有 manifest 可读**的情况下判断出来（manifest 本身也可能缺）。
     *
     * ⚠️ **不要把 encode 侧列进这里**。encode 只在"用参考音频克隆音色"时需要，
     * 而旧模块包（1.0.0）本来就没有它。若把它列为必需项，所有老用户升级 App 后
     * 会被判成"模块没装全"、要求重下 725 MB（1.1.0 起随包下发 encode），而他们其实
     * 完全能用内置音色合成。
     * encode 的有无改由 [MossLocalEngine.supportsVoiceClone] 在**真正要用时**判断，
     * 不影响安装判定。
     */
    private val REQUIRED_FILES = listOf(
        MANIFEST_FILE,
        "tts_browser_onnx_meta.json",
        "codec_browser_onnx_meta.json",
        "tokenizer.model",
        "moss_tts_prefill.onnx",
        "moss_tts_decode_step.onnx",
        "moss_tts_local_fixed_sampled_frame.onnx",
        "moss_tts_global_shared.data",
        "moss_tts_local_shared.data",
        "moss_audio_tokenizer_decode_full.onnx",
        "moss_audio_tokenizer_decode_shared.data",
    )

    /** 官方各文件字节数，用于"轻校验"。取自 hf-mirror 的模型 API（见 .tmp_moss/download_models.py）。 */
    private val EXPECTED_SIZES = mapOf(
        "browser_poc_manifest.json" to 503354L,
        "tts_browser_onnx_meta.json" to 4487L,
        "codec_browser_onnx_meta.json" to 17036L,
        "tokenizer.model" to 470897L,
        "moss_tts_prefill.onnx" to 283305L,
        "moss_tts_decode_step.onnx" to 291483L,
        "moss_tts_local_fixed_sampled_frame.onnx" to 471262L,
        "moss_tts_global_shared.data" to 440813568L,
        "moss_tts_local_shared.data" to 229678080L,
        "moss_audio_tokenizer_decode_full.onnx" to 681902L,
        "moss_audio_tokenizer_decode_shared.data" to 44198912L,
    )

    fun moduleDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 已安装文件占用的总字节数（用于 UI 显示与卸载提示） */
    fun installedBytes(context: Context): Long =
        moduleDir(context).listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    // ---------------------------------------------------------------- 体积文案

    /**
     * 体积兜底常量：**只有拿不到服务端清单时**的静态文案才用它们。
     *
     * 取自线上 `/moss/manifest.json` **v1.1.0**（`packagedAt` 2026-10-11）的
     * `totals`：`downloadBytes` = 759,778,844 B、`installedBytes` = 762,738,267 B。
     * 按本类 [formatMb] 的折算口径（1 MB = 1024×1024）即 **724.6 MB / 727.4 MB**，
     * 界面上取整说「约 725 MB / 约 727 MB」。
     *
     * ⚠️ **只有静态文案需要人工同步**：合成页的模式提示、模块页「需要注意」那几行。
     * 凡是能拿到清单的地方（下载页的体积与进度、模块页状态区的占用）一律用清单真值，
     * 上游重新打包后那些地方会自动跟着变。这里的数字曾经写着 682/684 MB，
     * 那是 v1.0.0（不含 encode 模型）的体积，与线上早已不符 —— 改这两个数前
     * 先看一次线上 manifest 的 `totals`，别凭印象填。
     */
    const val FALLBACK_DOWNLOAD_BYTES = 759778844L
    const val FALLBACK_INSTALLED_BYTES = 762738267L

    /** 体积折算口径：与下载页进度、模块页占用显示完全一致（1 MB = 1024×1024，一位小数）。 */
    fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1048576.0)

    /**「约 725 MB」：静态文案里说下载体积用 */
    fun approxDownloadMb(): String =
        "约 ${Math.round(FALLBACK_DOWNLOAD_BYTES / 1048576.0).toInt()} MB"

    /**「约 727 MB」：静态文案里说安装后占用用 */
    fun approxInstalledMb(): String =
        "约 ${Math.round(FALLBACK_INSTALLED_BYTES / 1048576.0).toInt()} MB"

    /** 模块版本（用于静态文案里点明模型包版本；拿不到清单时按线上默认值） */
    const val FALLBACK_VERSION = "1.1.0"

    /**
     * 轻校验：必需文件是否齐全、大小是否与官方一致。
     * 不做哈希 —— 见类注释里的取舍说明。
     *
     * @param manifestFiles 服务端 manifest 的文件清单（含真实 size）。
     *   **有清单时以它为准，而不是 [EXPECTED_SIZES]**：后者是某次 hf-mirror 导出
     *   的快照，一旦上游重新导出权重（哪怕只是字节数变了），
     *   安装流程明明已经按 manifest 的 sha256 逐文件验过全部通过，
     *   却会被这里的旧常量判成"缺少 xxx"，而用户看到的提示指向一个其实完好无损的文件。
     *   这个模块的设计前提就是"服务端能独立升级、不逼客户端发版"，
     *   硬编码大小表与该前提直接矛盾。清单里没有的条目才回退到常量。
     */
    @JvmOverloads
    fun isInstalled(context: Context, manifestFiles: Collection<MossManifest.FileEntry> = emptyList()): Boolean {
        val dir = moduleDir(context)
        if (!dir.isDirectory) return false
        val fromManifest = manifestFiles.associate { it.name to it.size }
        for (name in REQUIRED_FILES) {
            val f = File(dir, name)
            if (!f.isFile) {
                Log.w(TAG, "缺少文件: $name")
                return false
            }
            val expected = fromManifest[name]?.takeIf { it > 0L } ?: EXPECTED_SIZES[name]
            if (expected != null && f.length() != expected) {
                Log.w(TAG, "文件大小不符: $name 期望 $expected 实得 ${f.length()}")
                return false
            }
        }
        return true
    }

/**
     * 模块清单里声明的**内置音色名**列表（`builtin_voices[].voice`）。
     *
     * 这些音色**不需要用户自备参考音频**，也不需要 encode 模型：MOSS-TTS-Nano 是
     * 零样本克隆模型，必须喂一段 prompt 参考音频，而模块包把官方示例 `assets/audio`
     * 目录里那十几段参考音频（wav）**离线编码成的音频 token 码**直接写进了
     * `browser_poc_manifest.json` 的 `prompt_audio_codes`。运行时由
     * [MossLocalEngine.builtinVoices] / `selectBuiltinVoicePromptCodes` 取用。
     *
     * ## 为什么单独提供这个方法（而不是让 UI 去 new 一个引擎）
     * [MossLocalEngine] 的构造会建ORT 会话、加载两个模型（几百 MB、常驻内存），
     * 而「给用户列一下可选音色」只需要读一次 500 KB 的 JSON。让 UI 为了下拉列表
     * 去构造引擎，等于每次进页面都付一次模型加载的代价 —— 那是本机合成**真正开始**
     * 才该付的钱。
     *
     * ## 判据与引擎侧保持一致
     * 过滤掉 `prompt_audio_codes` 为空的条目：那种条目在
     * [MossLocalEngine.builtinVoices] 里也会被滤掉，列出来会让用户选到一个
     * 运行时必然回落到别的音色的选项。
     *
     * @return 音色名（未安装模块 / 清单缺失或损坏时为空列表，调用方按"没有可选音色"处理）
     */
    fun builtinVoices(context: Context): List<String> {
        val manifest = File(moduleDir(context), MANIFEST_FILE)
        if (!manifest.isFile) return emptyList()
        return try {
            val array = org.json.JSONObject(manifest.readText()).optJSONArray("builtin_voices")
                ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val name = entry.optString("voice", "")
                val codes = entry.optJSONArray("prompt_audio_codes")
                if (name.isNotEmpty() && codes != null && codes.length() > 0) name else null
            }
        } catch (e: Exception) {
            // 清单读不了不是致命错误：合成真正开始时引擎会再报一次更具体的错，
            // 这里静默降级成"没有可选音色"，由 UI 提示去模块管理页查看。
            Log.w(TAG, "读取模块内置音色失败: ${e.message}", e)
            emptyList()
        }
    }

    /** 缺失清单（供 UI 精确提示"缺了什么"，而不是笼统一句"模块损坏"） */
    fun missingFiles(context: Context): List<String> {
        val dir = moduleDir(context)
        return REQUIRED_FILES.filter { name ->
            val f = File(dir, name)
            val expected = EXPECTED_SIZES[name]
            !f.isFile || (expected != null && f.length() != expected)
        }
    }

    /**
     * 已安装的模块是否带encode 模型（即是否支持「用参考音频克隆音色」）。
     *
     * 与 [isInstalled] 的区别：encode 是**可选能力**，不参与安装判定 ——
     * 1.0.0 的模块包没有它，但内置音色合成完全正常，不该因此判成"没装"。
     * 这个方法只用来给 UI 一句准确的提示（「当前版本不支持克隆音色，卸载后可获得」），
     * 免得用户选了参考音频却得到内置音色、还以为是模型不准。
     *
     * 判定只看文件是否存在：真要跑还需要 meta 里写了 encode 键并能建会话，
     * 那部分由 [MossLocalEngine.supportsVoiceClone] 在实际使用时把关。
     */
    fun hasVoiceCloneModel(context: Context): Boolean {
        val dir = moduleDir(context)
        return ENCODE_FILES.all { File(dir, it).isFile }
    }

    /** encode 侧文件名（1.1.0 起随包下发） */
    private val ENCODE_FILES = listOf(
        "moss_audio_tokenizer_encode.onnx",
        "moss_audio_tokenizer_encode.data",
    )

    /**
     * 全量 sha256 校验。慢（约数秒）但能发现"大小对、内容坏"的情况。
     * @param expected name -> sha256；由服务端 manifest.json 提供
     * @return 校验失败的文件名列表（空 = 全部通过）
     */
    fun verifyAll(context: Context, expected: Map<String, String>): List<String> =
        verifyAllAt(moduleDir(context), expected)

    /**
     * 同上，但校验**指定目录**。
     * 安装流程需要它：解压先落在 staging 目录，必须在改名提交之前就把内容验完，
     * 否则一个残缺的目录会被当成安装成功。
     */
    fun verifyAllAt(dir: File, expected: Map<String, String>): List<String> {
        val bad = ArrayList<String>()
        for ((name, want) in expected) {
            val f = File(dir, name)
            if (!f.isFile) {
                bad.add(name)
                continue
            }
            val actual = sha256(f)
            if (!actual.equals(want, ignoreCase = true)) {
                Log.w(TAG, "sha256 不符: $name 期望 ${want.take(16)}… 实得 ${actual.take(16)}…")
                bad.add(name)
            }
        }
        return bad
    }

    fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 卸载：删掉整个模块目录 */
    fun uninstall(context: Context): Boolean {
        val dir = moduleDir(context)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    // ---- 分词器缓存 ----
    // tokenizer.model 只有 470 KB，但每次合成都重新解析 protobuf 是白费功夫；
    // 缓存在内存里，卸载模块时置空以免指向已删除的文件。
    @Volatile
    private var cachedTokenizer: SentencePieceTokenizer? = null

    fun tokenizer(context: Context): SentencePieceTokenizer {
        cachedTokenizer?.let { return it }
        synchronized(this) {
            cachedTokenizer?.let { return it }
            val f = File(moduleDir(context), "tokenizer.model")
            require(f.isFile) { "模块未安装或缺少 tokenizer.model" }
            return SentencePieceTokenizer.fromFile(f).also { cachedTokenizer = it }
        }
    }

    fun clearCaches() {
        synchronized(this) {
            cachedTokenizer = null
        }
    }
}
