package com.audiobook.audiobookplayer.moss

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 模块包的下载 / 校验 / 解压安装。
 *
 * ## 为什么值得单独写这么一段（而不是直接用 DownloadManager）
 * 系统 DownloadManager 能做续传，但它把文件放在公共下载目录、由系统托管，
 * 我们**拿不到"边下边算哈希"的机会**，也没法把"下到一半的状态"与安装流程绑在一起；
 * 而 725 MB 的包在移动网络下必然会被中断，续传与断点校验是这个功能的生死线。
 * 所以自己实现，把三件事做实：
 *
 * 1. **断点续传**：用 HTTP Range 从 `.part` 已有字节接着下；服务端 nginx 配了
 *    `Accept-Ranges`。每次续传后重算已下载长度，不信任任何本地记录的长度。
 *
 * 2. **下载完成即刻校验整包 sha256 才改名**：`.part` → `.zip` 的改名是唯一的
 *    "提交点"。绝不能让一个没校验完的包被当成可用包 —— 否则解压出来的是一堆
 *    无法定位的垃圾数据。
 *
 * 3. **解压后逐文件校验**：见 [MossManifest] 的说明。任何一条不符就整体回滚，
 *    不做"部分可用"这种含糊状态。
 *
 * ## 空间检查
 * 需要同时容纳"压缩包"和"解压后的文件"，峰值约 [MossManifest.downloadBytes] +
 * [MossManifest.installedBytes] ≈ 1.37 GB。空间不足必须**在开始下载前**就告诉用户，
 * 而不是下了 20 分钟才失败。
 */
object MossDownloader {

    private const val TAG = "MossDownloader"

    /**
     * 当前正在阻塞读取的网络流（v5/A3）。
     *
     * <p>为什么需要它：{@code withContext(Dispatchers.IO)} 被 cancel **不会** interrupt 线程，
     * {@code InputStream.read()} 该阻塞多久还阻塞多久。唯一能让它立刻返回的办法是
     * **把流关掉** —— close 会让阻塞中的 read 抛 IOException。
     */
    @Volatile
    private var activeStream: java.io.Closeable? = null

    /**
     * 取消是否由我们主动发起。
     *
     * <p>用于把「关流导致的 IOException: Stream closed」翻译成 CancellationException ——
     * 调用方对后者有专门的收尾分支（保留断点、提示「已取消」），对前者只会报「安装失败」。
     */
    @Volatile
    private var cancelRequested = false

    /**
     * 主动中断正在进行的下载（用户点「取消下载」时调用）。
     *
     * <p>只关流、不删 {@code .part}：断点续传的语义必须保住。
     */
    fun cancelActiveTransfer() {
        cancelRequested = true
        val stream = activeStream ?: return
        runCatching { stream.close() }
            .onFailure { Log.w(TAG, "关闭下载流失败（忽略，等 read 超时）: ${it.message}") }
    }

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val BUFFER = 1 shl 16

    /**
     * 下载互斥锁（**非**挂起式）。
     *
     * 用 `tryLock` 而不是 `withLock`：这里跑在 IO 线程的阻塞读循环里，
     * 拿不到协程上下文；而且第二个请求应当立刻失败并提示用户，
     * 而不是排队等前一个 725 MB 任务下完 —— 那正是「两个下载同时写 .part」的来源。
     */
    private val downloadLock = java.util.concurrent.locks.ReentrantLock()

    // ---------------------------------------------------------------- 进程级状态
    //
    // 为什么必须有它：**下载的生命周期比页面长**。
    // MossModuleActivity.onDestroy 只 cancel 协程，而 download() 跑在 IO 线程的
    // 阻塞读循环里停不下来（见 downloadLocked 的取消检查），于是用户退出页面后
    // 流量照跑、`.part` 照长 —— 但那个 listener 是 Activity 的私有对象，
    // 早就随页面一起没了。**重新进入页面时能拿到的只有 `partialBytes()` 的一次性
    // 快照**，于是 50.3 MB 这个数字会一直停在 50.3，直到用户再点一次按钮。
    //
    // 同一个根因还有第二个后果更严重的：按钮判据里没有"是否正在下载"这一项，
    // 所以下载中重新进页面，`btnPrimary` 仍然是 enabled —— 用户一点就撞上
    // tryLock 失败，被弹一句「已有下载任务在进行，请稍候」，而页面上那个
    // 正在增长的下载跟他毫无关系。**用户看得见的按钮状态必须来自真实运行态。**
    data class InstallState(
        /** 是否有一轮「下载→校验→解压安装」正在进行（与页面无关，进程级） */
        val busy: Boolean = false,
        /** 当前阶段文案（开始下载 / 校验安装包完整性 / 解压安装包 …） */
        val stage: String = "",
        val downloaded: Long = 0,
        val total: Long = 0,
        val bytesPerSecond: Long = 0,
        /**
         * 当前忙碌是否处在**可暂停的下载阶段**。
         *
         * 只有"正在从网络读字节"的那个阶段能暂停：停传输只需 close 流，
         * 断点已写在 .part 里，点「继续」走 Range 续传即可。
         * 校验（sha256 725 MB）/ 解压 / 安装阶段是本地的不可中断 IO，
         * 点了也不会有任何反应 —— 那时按钮必须保持置灰，
         * 否则又是一个"按了没反应的按钮"。
         */
        val pausable: Boolean = false
    )

    private val _installState = MutableStateFlow(InstallState())

    /**
     * 安装流程的进程级状态，供任意页面订阅。
     *
     * 页面在 `STARTED` 期间收集即可：StateFlow 收集时**必定先发一次当前值**，
     * 所以「重新进入页面立刻看到真实进度」不需要额外写一次手动查询
     * （这正是记忆里那条「事件驱动 + onResume 重查两条路」在这里的形态 ——
     * 本场景只有一条就够了，因为真值本身就在这里）。
     */
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private fun publish(block: (InstallState) -> InstallState) {
        _installState.value = block(_installState.value)
    }

    /**
     * 编排开始：宣布进入忙碌态。必须在**拿到 manifest 之后**调，避免拉清单失败也显示"下载中"。
     *
     * 同时置 `pausable = true`：enterBusy 之后紧接着就是网络传输阶段，
     * 那是整轮流程里唯一停得下来的部分（见 [InstallState.pausable]）。
     */
    fun enterBusy(stage: String) = publish { InstallState(busy = true, stage = stage, pausable = true) }

    /** 编排结束（成功/失败/取消都算）：复位成空闲态 */
    fun exitBusy() = publish { InstallState() }

    /**
     * 推进下载进度。
     *
     * 单独抽出来而不是在循环里内联 `publish`，是为了让这段"每 500ms 写一次
     * 进程级状态"的语义有一个可以被单测直接驱动的入口 —— 「重新进入页面的
     * 订阅者能拿到最后写入的值」正是靠它成立的。
     */
    fun publishProgress(downloaded: Long, total: Long, bytesPerSecond: Long) = publish {
        it.copy(
            stage = "正在下载",
            downloaded = downloaded,
            total = total,
            bytesPerSecond = bytesPerSecond
        )
    }

    /** 下载进度回调（都在后台线程调用） */
    interface ProgressListener {
        /** @param downloaded 已下载字节；@param total 总字节（-1 表示服务端没给 Content-Length） */
        fun onProgress(downloaded: Long, total: Long, bytesPerSecond: Long)
        fun onStage(stage: String)
    }

    /** 下载目录（与模块安装目录同级，卸载模块时一并清理） */
    private fun downloadDir(context: Context): File =
        File(context.filesDir, "moss_download").apply { mkdirs() }

    /**
     * 压缩包落盘路径。
     *
     * ⚠️ `archiveFile` 来自服务端 JSON，而 [java.io.File] 会把 `../` 归一化掉 ——
     * 形如 `../shared_prefs/xxx.xml` 的值会真的落到 `filesDir` 下 moss_download
     * **之外**的目录上。解压处对 zip entry 名做了 `File(entry.name).name` 防逃逸，
     * 这里这条路径必须有同等处理，否则 clearDownloads()（只删 downloadDir）
     * 清不掉它，而续传时的 `part.delete()` 还可能删掉那个路径上的既有文件。
     * 触发条件是 manifest 端点被入侵 / nginx location 配错回落到别的站点 /
     * 调试包把地址指向了第三方镜像。
     */
    private fun archiveFile(context: Context, manifest: MossManifest): File =
        File(downloadDir(context), File(manifest.archiveFile).name)

    private fun partFile(context: Context, manifest: MossManifest): File =
        File(downloadDir(context), File(manifest.archiveFile).name + ".part")

    // ---------------------------------------------------------------- 查询清单

    /**
     * 拉取服务端清单。
     *
     * 只接受**合法 JSON 且带 files 校验集**的响应：nginx 对不存在的路径会 fallback
     * 到前端 index.html，此时返回的是 200 + text/html + 696 字节的 HTML。
     * 若不判断内容，App 会把那段 HTML 当成清单解析失败，报出与真实原因
     * （location 没配好）毫不相干的错误。
     */
    fun fetchManifest(context: Context): MossManifest {
        val url = MossModuleSource.manifestUrl(context)
        Log.i(TAG, "拉取清单: $url")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            // 清单必须每次拿新的，否则用户会一直看到旧版本号
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("清单请求失败：HTTP $code")
            val contentType = conn.contentType ?: ""
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (!contentType.contains("json", ignoreCase = true)) {
                throw IOException(
                    "清单返回的不是 JSON（Content-Type=$contentType，${body.length} 字节）。" +
                        "模块目录可能没配 nginx location —— 未知路径会被 fallback 到前端页面。"
                )
            }
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IOException("清单不是合法 JSON（${body.length} 字节）", e)
            }
            return MossManifest.fromJson(json)
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- 空间检查

    /** 安装所需的峰值空间：压缩包 + 解压结果。解压完成后压缩包会被删掉。 */
    fun requiredPeakBytes(manifest: MossManifest): Long =
        manifest.downloadBytes + manifest.installedBytes + (32L shl 20) // 留 32MB 余量

    fun availableBytes(context: Context): Long = try {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) {
        Log.w(TAG, "读取可用空间失败: ${e.message}")
        Long.MAX_VALUE   // 读不到就别拦着用户，让下载过程自己去失败
    }

    /**
     * 峰值空间要**扣掉已下载的 .part**。
     *
     * 续传时真正缺的是 `downloadBytes - 已有 .part` 字节，而已下载的那部分
     * 已经占掉了可用空间。不扣的话，同一个判断会出现 UI 放行、执行时拦截的矛盾：
     * 按钮按 `requiredPeakBytes - partialBytes` 判定为「空间够」而可点，
     * 真正下载时却按完整 `requiredPeakBytes` 检查 → 弹出「空间不足」，
     * 用户明明只差几十 MB 却被要求腾出 1.3 GB，与断点续传的设计目的直接冲突。
     * 这与调用方 [MossModuleActivity] 的按钮判据必须同口径。
     */
    fun hasEnoughSpace(context: Context, manifest: MossManifest): Boolean =
        availableBytes(context) + partialBytes(context, manifest) >= requiredPeakBytes(manifest)

    // ---------------------------------------------------------------- 下载

    /**
     * 下载模块包（支持续传）。返回校验通过的 zip 文件。
     * 必须在线程池/协程的 IO 线程调用。
     */
    fun download(
        context: Context,
        manifest: MossManifest,
        listener: ProgressListener
    ): File {
        cancelRequested = false
        val target = archiveFile(context, manifest)

        // 已存在且大小正确：说明之前下过，直接复用（哈希交给调用方验证）
        if (target.isFile && target.length() == manifest.archiveSize) {
            listener.onStage("已存在完整安装包，跳过下载")
            return target
        }

        // 并发互斥：MossDownloader 是 object（进程级单例），而调用方
        // MossModuleActivity 是 Activity —— 退出页面时 onDestroy 只 cancel 协程，
        // 下面那个阻塞读循环当时**停不下来**（见下），它会继续在后台写 .part。
        // 用户重新进入页面再点一次下载，就会出现两个 download() 同时用
        // RandomAccessFile(part, "rw") 写同一个文件、各自 seek 到不同偏移，
        // 结果必然是 sha256 校验失败、报错还指向「网络/校验」，
        // 而用户实际丢掉的是全部续传进度。
        // tryLock 失败就直接拒绝，语义比事后清理可靠。
        if (!downloadLock.tryLock()) {
            throw IOException("已有下载任务在进行，请稍候（若刚离开本页，请等待当前下载结束）")
        }
        // 成功走到 downloadLocked 的末尾（sha256 也过了）才置 true。
        // 失败时 busy 必须**在这里**复位而不是交给 install()：
        // download() 一旦抛出，调用方就再也不会调 install()，busy 会永久卡在 true
        // ⇒ 页面按钮永久禁用，用户只能杀进程重进。
        var handedOverToInstall = false
        try {
            val archive = downloadLocked(context, manifest, listener, target)
            handedOverToInstall = true
            return archive
        } finally {
            // 成功时不复位：download() 返回后调用方紧接着跑 install()（解压 725 MB，
            // 同样要几分钟）。若在解锁时就宣称空闲，用户重新进页面会看到
            // 「未安装 + 上次已下载 725 MB，点按钮从断点继续」——而实际正在解压，
            // 一点按钮就撞 tryLock 失败。由 install() 的 finally 收尾。
            if (!handedOverToInstall) exitBusy()
            downloadLock.unlock()
        }
    }

    private fun downloadLocked(
        context: Context,
        manifest: MossManifest,
        listener: ProgressListener,
        target: File
    ): File {
        val part = partFile(context, manifest)
        val url = MossModuleSource.archiveUrl(context, manifest)
        Log.i(TAG, "下载模块包: $url -> ${part.absolutePath}")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            // 断点续传：把已有长度告诉服务端
            val already = if (part.isFile) part.length() else 0L
            if (already > 0) {
                if (already >= manifest.archiveSize) {
                    part.delete()   // 比预期还大，说明本地文件坏了，重来
                } else {
                    conn.setRequestProperty("Range", "bytes=$already-")
                }
            }
            val code = conn.responseCode
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (code != 200 && !resuming) {
                throw IOException("下载失败：HTTP $code")
            }

            // 服务端不支持 Range 时会返回 200 + 完整内容；此时必须从头写，
            // 否则追加会把文件写成"前半段旧 + 后半段新"的畸形数据
            val startAt = if (resuming && part.isFile) part.length() else 0L
            if (resuming && startAt > 0) {
                val stage = "继续下载（已完成 ${startAt / 1048576} MB）"
                listener.onStage(stage)
                publish { it.copy(stage = stage, downloaded = startAt) }
            } else {
                part.delete()
                listener.onStage("开始下载")
                publish { it.copy(stage = "开始下载", downloaded = 0L) }
            }

            val contentLength = conn.contentLengthLong
            val total = if (contentLength > 0) startAt + contentLength else manifest.archiveSize

            var downloaded = startAt
            var lastReport = 0L
            var lastBytes = startAt
            val startedAt = System.currentTimeMillis()

            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(startAt)
                conn.inputStream.use { input ->
                    // v5/A3：登记当前流，供 [cancelActiveTransfer] 从外部 close。
                    // 这一步是"取消真的生效"的唯一支点 —— 只置协程标志是停不下阻塞 read 的。
                    activeStream = input
                    try {
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            // 暂停/取消检查（v5/A3 修正注释）：InputStream.read() 是不可中断的阻塞调用，
                            // Job.cancel() 只置标志、**不会** interrupt 线程，因此 isInterrupted
                            // 在这里几乎永远是 false —— 真正让循环停下来的是
                            // [cancelActiveTransfer] 把流 close 掉（read 抛 IOException）。
                            //
                            // cancelRequested 这一道是给"连接刚建立、流还没登记"的窗口用的：
                            // 用户在那个窗口里点暂停，activeStream 还是 null，close 无从关起，
                            // 只能靠循环第一轮的主动检查拦住 —— 否则整个 725 MB 会照下完，
                            // 而用户看着界面上明明白白的「已暂停」。
                            if (cancelRequested) {
                                throw CancellationException("下载已暂停（保留断点，可继续）")
                            }
                            // 这一行是第二道保险：万一将来有人改成 runInterruptible 仍能生效。
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedIOException("下载已取消（保留断点，可继续）")
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 500) {
                                val speed = (downloaded - lastBytes) * 1000L /
                                    (now - lastReport).coerceAtLeast(1)
                                // 进程级状态与页面 listener 并行发布：后者随 Activity 一起死，
                                // 前者才是重新进页面时唯一能拿到的东西。
                                publishProgress(downloaded, total, speed)
                                listener.onProgress(downloaded, total, speed)
                                lastReport = now
                                lastBytes = downloaded
                            }
                        }
                    } catch (e: IOException) {
                        // 我方主动 close 流导致的 IOException ⇒ 按取消处理（见 cancelRequested 注释）；
                        // 真正的网络错误（超时/断连）照常上抛，交给既有错误提示。
                        if (cancelRequested) {
                            throw CancellationException("下载已取消（保留断点，可继续）")
                        }
                        throw e
                    } finally {
                        activeStream = null
                    }
                }
            }
            listener.onProgress(downloaded, total, 0L)

            if (downloaded != manifest.archiveSize) {
                // 保留 .part 以便下次续传 —— 这正是续传的意义所在
                throw IOException(
                    "下载未完成：$downloaded/${manifest.archiveSize} 字节（已保留断点，可重试续传）"
                )
            }
            val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
            Log.i(TAG, "下载结束: ${downloaded / 1048576} MB, 本次耗时 ${elapsed / 1000}s")
        } finally {
            conn.disconnect()
        }

        // ---- 校验整包 sha256，通过后才改名提交 ----
        listener.onStage("校验安装包完整性")
        // 从这一刻起 pausable=false：sha256 725 MB 是本地不可中断 IO，
        // 按钮必须置灰，不能给一个按了没反应的「暂停」。
        publish { it.copy(stage = "校验安装包完整性", bytesPerSecond = 0L, pausable = false) }
        val actual = MossModuleManager.sha256(part)
        if (!actual.equals(manifest.archiveSha256, ignoreCase = true)) {
            // 哈希不符说明文件确实坏了，续传也没意义，删掉重下
            part.delete()
            throw IOException(
                "安装包校验失败（期望 ${manifest.archiveSha256.take(16)}… " +
                    "实得 ${actual.take(16)}…），已删除，请重新下载"
            )
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("无法重命名安装包")
        return target
    }

    // ---------------------------------------------------------------- 解压安装

    /**
     * 解压 + 逐文件校验 + 落到模块目录。
     *
     * 采用"先解压到临时目录、全部校验通过后再整体替换"的策略：
     * 直接往最终目录写的话，中途失败会留下一个"看起来装了、其实残缺"的目录，
     * 而 isInstalled 的轻校验只能发现大小不符，发现不了内容错位。
     */
    fun install(
        context: Context,
        archive: File,
        manifest: MossManifest,
        listener: ProgressListener
    ) {
        // 解压/校验/安装全是本地不可中断 IO：先把 pausable 关掉，
        // 让页面按钮在下载结束的那一刻就置灰，而不是等第一个 stage 发布。
        publish { it.copy(pausable = false) }
        val staging = File(context.filesDir, "moss_staging")
        val finalDir = MossModuleManager.moduleDir(context)
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        try {
            listener.onStage("解压安装包")
            publish { it.copy(stage = "解压安装包") }
            var extracted = 0L
            ZipInputStream(archive.inputStream().buffered(BUFFER)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    // 只接受扁平的文件名：防止 zip 里的 ../ 路径逃逸到目录之外
                    val name = File(entry.name).name
                    if (entry.isDirectory || name.isEmpty()) continue
                    val out = File(staging, name)
                    out.outputStream().buffered(BUFFER).use { fos ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            // 解压同样要能取消：725 MB 的包在移动网络下可能解几分钟，
                            // 用户点取消后若继续跑完，这一轮就是纯白耗电
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedIOException("解压已取消")
                            }
                            val n = zin.read(buf)
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            extracted += n
                        }
                    }
                }
            }
            Log.i(TAG, "解压完成，共 ${extracted / 1048576} MB")

            // ---- 逐文件校验 ----
            listener.onStage("校验解压结果")
            publish { it.copy(stage = "校验解压结果") }
            val bad = MossModuleManager.verifyAllAt(staging, manifest.fileSha256)
            if (bad.isNotEmpty()) {
                throw IOException("解压结果校验失败：${bad.joinToString("、")}")
            }

            // ---- 提交：整体替换 ----
            listener.onStage("安装")
            publish { it.copy(stage = "安装") }
            MossModuleManager.clearCaches()
            if (finalDir.exists() && !finalDir.deleteRecursively()) {
                throw IOException("无法清理旧模块目录")
            }
            if (!staging.renameTo(finalDir)) {
                // rename 失败（跨挂载点等）时退化为逐个复制
                finalDir.mkdirs()
                staging.listFiles()?.forEach { f ->
                    if (!File(finalDir, f.name).also { t -> f.copyTo(t, overwrite = true) }.exists()) {
                        throw IOException("复制失败：${f.name}")
                    }
                }
                staging.deleteRecursively()
            }
            if (!MossModuleManager.isInstalled(context, manifest.files)) {
                throw IOException("安装后自检未通过，缺少：${MossModuleManager.missingFiles(context)}")
            }
            // 安装包用完即删：725 MB 没必要长期占着，需要时能重新下
            archive.delete()
            Log.i(TAG, "模块安装完成: ${finalDir.absolutePath}")
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        } finally {
            // 整轮（下载 + 解压安装）到此结束，才允许对外显示为"空闲"。
            //
            // 为什么收尾放在这里、而不是调用方的协程 finally：
            // 用户退出页面时协程立刻被 cancel，而 install() 跑在 IO 线程的
            // 阻塞解压循环里（同样只查 Thread.interrupted，取消不了）——
            // 协程先结束、解压还在跑。此处若跟着协程复位，页面会显示成
            // 可点的未安装态，用户一点就撞 tryLock 失败。
            // 这里是最后一个真正结束的步骤，只有它能判断"整轮确实完了"。
            exitBusy()
        }
    }

    /** 已下载的安装包（若存在且完整），供 UI 判断"要不要重新下" */
    fun existingArchive(context: Context, manifest: MossManifest): File? =
        archiveFile(context, manifest).takeIf { it.isFile && it.length() == manifest.archiveSize }

    /** 未完成的下载进度（字节），供 UI 显示"上次下到一半" */
    fun partialBytes(context: Context, manifest: MossManifest): Long =
        partFile(context, manifest).takeIf { it.isFile }?.length() ?: 0L

    /** 清掉下载残留（用户放弃下载时调用） */
    fun clearDownloads(context: Context) {
        downloadDir(context).deleteRecursively()
    }
}
