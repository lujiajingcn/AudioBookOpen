package com.audiobook.audiobookplayer

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.audiobook.audiobookplayer.network.RetrofitClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 离线英汉词典（ECDICT）的安装、查询与卸载。**进程级单例**。
 *
 * ## 词典是什么
 * 一个预构建的 SQLite 库（`dict.db`，55.3MB / 768739 词条），由服务端脚本
 * `_dict_build/build_dict.py` 从 ECDICT 的 ecdict.csv 转出。分发时 gzip 成23.1MB，
 * 客户端下载后解压即可用 Android 自带的 SQLite 直接查，不需要任何解析代码。
 *
 * ## 表结构（建库脚本定的，客户端只能按这个查）
 * ```
 * dict(sw TEXT, word TEXT, phonetic TEXT, translation TEXT,
 *      exchange TEXT, tag TEXT, collins INTEGER, oxford INTEGER,
 *      PRIMARY KEY(sw, word)) WITHOUT ROWID
 * ```
 *
 * ## ⭐ 三条不能搞错的约定
 *
 * **① 查询必须走 `sw`，不能只做 `word = ?`**
 * `sw` = strip-word = 「去掉所有非字母数字字符 + 转小写」。用户长按选中的是书里的形态，
 * 可能是 `take-off`（连字符）、`don't`（撇号）、`Running`（大小写）。
 * 直接用原词精确匹配会大量落空 —— 这是本功能最常见的「查不到」原因。
 * [stripWord] 必须与建库脚本的 `strip_word()` **逐字符一致**。
 *
 * **② `translation` 里的换行是字面量反斜杠+n 两个字符，不是真换行**
 * 实测全库 768739 行中含真换行（U+000A）的有 **0** 行，
 * 含字面量 `\n`（两字符）的有 **52999** 行。
 * 按真换行切分会得到「一整坨释义」，弹窗直接糊屏。
 *
 * **③ `sw` 有 23482 个键对应多个 word**
 * 去符号后撞车：`hood` / `Hood` / `'hood` 都是 `hood`。
 * 因此一个 `sw` 可能返回多行，**都要显示**（`take-off` 的 sw=`takeoff` 命中 3 行）。
 * 只取第一行会漏掉用户真正想看的那个义项。
 *
 * ## 并发与线程
 * 下载/解压/安装走 [Dispatchers.IO]，查询也走 IO（SQLite 读 55MB 库不是瞬时的）。
 * 界面通过 [state] 观察安装状态。SQLite 连接**用完即关**而不是长期持有：
 * 词典库与业务库（DatabaseHelper）分属不同文件，不会互相锁；
 * 而长期持有一个连接会让「卸载」时的文件删除在部分机型上失败（句柄未释放）。
 */
object DictionaryManager {

    private const val TAG = "DictionaryManager"

    /** 词典库文件名（解压后的） */
    private const val DICT_FILE_NAME = "dict.db"

    /** 下载中的临时文件（gzip 包） */
    private const val PART_FILE_NAME = "dict.db.gz.part"

    /** 安装信息文件名（记录已装版本 / MD5 / 词条数） */
    private const val INFO_FILE_NAME = "dict.info"

    /**
     * 进度投递节流：距上次投递不足该值则跳过（除非百分比变化 ≥ 1%）。
     *
     * 照抄 BookStoreViewModel.PROGRESS_POST_INTERVAL_MS 的口径：
     * 23MB 在慢速网络上会产生上万次读循环，逐次 postValue 会把主线程消息队列打爆
     * （表现为主线程卡顿、进度数字乱跳）。
     */
    private const val PROGRESS_POST_INTERVAL_MS = 200L

    /** 下载体积上限：服务端产物是 23.1MB，留 3 倍余量仍远超，超过即视为异常响应 */
    private const val MAX_DOWNLOAD_BYTES = 80L * 1024 * 1024

    /** 安装状态。 */
    sealed interface DictState {
        /** 未安装 */
        data object NotInstalled : DictState

        /** 已安装。[version] 为服务端版本号，[rows] 为词条数 */
        data class Installed(val version: String, val rows: Long, val sizeBytes: Long) : DictState

        /** 下载/解压/校验中 */
        data class Downloading(val downloaded: Long, val total: Long, val percent: Int) : DictState

        /** 下载完成，正在解压 gzip（此时 [Downloading.total] 为压缩包大小） */
        data class Installing(val downloaded: Long, val total: Long, val percent: Int) : DictState

        /** 出错。[message] 可直接展示给用户 */
        data class Error(val message: String) : DictState
    }

    private val _state = MutableStateFlow<DictState>(DictState.NotInstalled)
    val state: StateFlow<DictState> = _state.asStateFlow()

    private var appContext: Context? = null

    /** 串行化 [downloadAndInstall]，见该函数内的说明 */
    private val downloadLock = Mutex()

    /** 查词缓存的 SQLite 连接（懒加载，用完不长期持有，卸载时必须显式关闭） */
    @Volatile
    private var cachedDb: SQLiteDatabase? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        refreshStateFromDisk()
    }

    // ==================== 安装状态 ====================

    private fun dictDir(context: Context): File =
        File(context.filesDir, "dict").apply { if (!exists()) mkdirs() }

    private fun dictFile(context: Context) = File(dictDir(context), DICT_FILE_NAME)

    private fun partFile(context: Context) = File(dictDir(context), PART_FILE_NAME)

    private fun infoFile(context: Context) = File(dictDir(context), INFO_FILE_NAME)

    /**
     * 只读的词典文件定位：**不建目录**。
     *
     * `isInstalled()` 由 `handleTranslate` 在主线程调用，若走 dictDir() 就会
     * 每次「翻译」都执行一次 mkdirs() 系统调用（StrictMode DiskWriteViolation）。
     * 目录要么已由 init/安装流程建好，要么本来就没有词典文件（此时 isFile 为假）。
     */
    private fun dictFileNoCreate(context: Context) = File(File(context.filesDir, "dict"), DICT_FILE_NAME)

    /** 从磁盘实况刷新状态（不依赖内存里的旧值） */
    fun refreshStateFromDisk() {
        val ctx = appContext ?: return
        val f = dictFileNoCreate(ctx)
        if (!f.isFile || f.length() <= 0L) {
            _state.value = DictState.NotInstalled
            return
        }
        val info = readInfo(ctx)
        _state.value = DictState.Installed(
            version = info["version"] ?: "unknown",
            rows = info["rows"]?.toLongOrNull() ?: 0L,
            sizeBytes = f.length()
        )
    }

    /** 词典是否已安装可用（主线程安全：只做 stat，不建目录不写盘） */
    fun isInstalled(): Boolean {
        val ctx = appContext ?: return false
        val f = dictFileNoCreate(ctx)
        return f.isFile && f.length() > 0L
    }

    /**
     * 未安装时，阅读页「翻译」菜单应提示的文案。
     * 已安装返回 null。
     */
    fun notInstalledHint(): String? =
        if (isInstalled()) null else "未安装词典，无法查词。请在「我的 - 离线词典」下载"

    // ==================== 下载 + 安装 ====================

    /**
     * 下载并安装词典。
     *
     * @param onDone 成功回调（参数为安装后的词条数；失败/取消不回调）
     *
     * 流程：查元信息 → 流式下载到 `.part`（**支持断点续传**）→ 校验 MD5 →
     * 解压成 `dict.db` → 删除 `.part` → 写 `dict.info`。
     *
     * MD5 校验针对**下载件（gzip 包）**，与服务端 `/api/app/dict/info` 返回的
     * `downloadMd5` 同一口径。拿它去校验解压结果必然失败（那是另一个文件的摘要）。
     *
     * ⚠️ 本函数**整体**必须包在 `withContext(Dispatchers.IO)` 里，不能只在下载那几行包。
     * 调用方是 `DictManageDialog` 的 `lifecycleScope.launch`，默认跑在 **Main** 上；
     * 而 Retrofit 的 `suspend` 函数只保证「发请求」在 IO 线程，**返回后交还调用方的
     * 上下文**——于是 `respBody.byteStream().read()` 是在主线程读 socket，直接抛
     * `NetworkOnMainThreadException`（设备端实测：下载一开就「失败：词典安装失败」，
     * logcat 里是 StrictMode$AndroidBlockGuardPolicy.onNetwork）。
     * 同理 23MB 的 MD5 与 gzip 解压也绝不能放主线程。
     */
    suspend fun downloadAndInstall(onDone: ((Long) -> Unit)? = null): Unit =
        withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext
        // ⚠️ 进程级去重：DictionaryManager 是 object 单例且本函数是 public，
        //   唯一的防线曾只有 `DictManageDialog` 里那个**弹窗实例级**的 `downloading` 标志。
        //   将来再加一个入口（比如阅读页「未安装词典」时弹「立即安装」提示），
        //   两个协程就会同时 `FileOutputStream(part, append)` 写同一个文件 ⇒ 数据损坏。
        //   这里 tryLock 是唯一能覆盖所有入口的位置；拿不到锁就直接返回。
        if (!downloadLock.tryLock()) {
            Log.w(TAG, "已有下载/安装在途，忽略本次请求")
            return@withContext
        }
        try {
        try {
            _state.value = DictState.Downloading(0L, -1L, 0)

            // ---- 1. 元信息 ----
            val infoResp = RetrofitClient.heavyApi.getDictInfo()
            if (!infoResp.isSuccessful) {
                fail("查询词典信息失败（HTTP ${infoResp.code()}）")
                return@withContext
            }
            val body: Map<String, Any> = infoResp.body() ?: emptyMap()
            if (body["available"] != true) {
                fail("服务器尚未部署词典")
                return@withContext
            }
            val remoteMd5 = (body["downloadMd5"] as? String)?.trim().orEmpty()
            val remoteSize = (body["downloadSize"] as? Number)?.toLong() ?: 0L
            val version = (body["version"] as? String)?.trim().orEmpty()
            val rows = (body["rows"] as? Number)?.toLong() ?: 0L
            if (remoteMd5.isEmpty()) {
                fail("服务器未提供词典 MD5，无法校验")
                return@withContext
            }

            // ---- 2. 下载（断点续传）----
            val part = partFile(ctx)
            var done = if (part.isFile) part.length() else 0L
            // 已下载部分超过服务端声明的大小 => 换了版本或文件损坏，丢弃重来
            if (remoteSize > 0 && done > remoteSize) {
                Log.w(TAG, "本地残留 $done 字节 > 远端 $remoteSize，丢弃重来")
                part.delete()
                done = 0L
            }
            // ⚠️⚠️ 只有「疑似已下完」（长度 == 远端声明）才能用 MD5 判断完整性。
            //   部分文件的摘要与完整文件的摘要**必然不相等**，二者语义完全不同。
            //   曾在这里对「任何长度的残留」都算一遍 MD5 去比远端全量摘要，
            //   结果每次断点续传都判定「远端已变」⇒ 删除残留、从 0 重下，
            //   「断点续传」一次都没生效过。
            // 长度未知（remoteSize<=0）时无法判断完整性，只能信任并靠末尾的 MD5 兜底。
            var alreadyComplete = false
            if (done > 0 && remoteSize > 0 && done == remoteSize) {
                val localMd5 = md5Of(part)
                if (localMd5 == remoteMd5) {
                    // 文件已完整且摘要相符，跳过下载直接进校验/解压
                    alreadyComplete = true
                } else {
                    Log.w(TAG, "残留 .part 长度已足但 MD5 不符($localMd5 != $remoteMd5)，丢弃重来")
                    part.delete()
                    done = 0L
                }
            }

            if (!alreadyComplete) {
                val range = if (done > 0) "bytes=$done-" else null
                val resp = RetrofitClient.heavyApi.downloadDict(range)
                if (!resp.isSuccessful) {
                    // ⚠️ 416 = Range 起点越界（.part 恰好等于/超过服务端长度）。
                    //   服务端 parseRange 对 start >= length 返回 null ⇒ 416。
                    //   此时必须删掉 .part 重来，否则每次重试都是同一条路径 ⇒ 永久死循环。
                    if (resp.code() == 416) {
                        Log.w(TAG, "Range 被拒(416)，丢弃 .part 后从头下载")
                        part.delete()
                    }
                    resp.errorBody()?.close()
                    fail("下载词典失败（HTTP ${resp.code()}）")
                    return@withContext
                }
                val respBody = resp.body()
                if (respBody == null) {
                    resp.errorBody()?.close()
                    fail("词典下载内容为空")
                    return@withContext
                }

                val total = resp.headers()["Content-Range"]?.let { parseTotal(it) }
                    ?: remoteSize.takeIf { it > 0 }
                    ?: 0L
                val append = resp.code() == 206 && done > 0
                // ⚠️ 服务端若忽略 Range 回 200（code()==200），此时必须**覆写**而不是追加，
                //   否则拼出「两份内容」的文件，MD5 必然失败。
                val finalTotal = if (resp.code() == 200) respBody.contentLength().takeIf { it > 0 } ?: total
                else total
                // ⚠️ 覆写时 done 必须归零：它初值是残留长度，不清零会继续往上累加，
                //   算出 43MB/23MB = 186% 的进度（被 coerceIn 夹成恒 100%），阈值也失效。
                if (!append) done = 0L

                var lastPostAt = 0L
                var lastPercent = -1
                _state.value = DictState.Downloading(done, finalTotal, percentOf(done, finalTotal))
                respBody.byteStream().use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(8192)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            if (done > MAX_DOWNLOAD_BYTES) {
                                throw IOException("词典文件异常（超过 ${MAX_DOWNLOAD_BYTES / 1048576}MB），已中止")
                            }
                            val now = System.currentTimeMillis()
                            val percent = percentOf(done, finalTotal)
                            if (now - lastPostAt >= PROGRESS_POST_INTERVAL_MS ||
                                (percent >= 0 && percent != lastPercent)
                            ) {
                                lastPostAt = now
                                lastPercent = percent
                                _state.value = DictState.Downloading(done, finalTotal, percent)
                            }
                        }
                    }
                }
            }

            // ---- 3. MD5 校验 ----
            //⚠️ 失败时必须删掉 .part：留着它，下次进来会走续传分支，
            //   而 MD5 又永远对不上 ⇒ 用户反复重试都下载不成功（死循环）。
            val actualMd5 = md5Of(part)
            if (actualMd5 != remoteMd5) {
                Log.e(TAG, "词典 MD5 不匹配: 期望=$remoteMd5 实际=$actualMd5")
                part.delete()
                fail("词典校验失败（文件不完整），请重试")
                return@withContext
            }

            // ---- 4. 解压 ----
            val target = dictFile(ctx)
            // ⚠️ closeDb 必须在 gunzip **之前**：解压出来的库是新文件，
            //   替换发生在 gunzip 内部。若先解压再关旧连接，中间窗口里并发 lookup
            //   会拿到已被 unlink 的旧 inode（读到旧数据），或撞上关闭抛
            //   SQLiteDatabaseClosedException 被 catch 吞成 emptyList()
            //   ⇒ 用户看到「词典未收录该词」。
            closeDb()
            _state.value = DictState.Installing(0L, part.length(), 0)
            val uncompressed = gunzip(part, target) { read, total ->
                _state.value = DictState.Installing(read, total, percentOf(read, total))
            }
            part.delete()

            if (uncompressed <= 0L) {
                target.delete()
                fail("词典解压失败")
                return@withContext
            }

            // ---- 5. 落安装信息 ----
            writeInfo(
                ctx,
                mapOf(
                    "version" to version,
                    "rows" to rows.toString(),
                    "md5" to remoteMd5,
                    "uncompressedSize" to uncompressed.toString()
                )
            )
            _state.value = DictState.Installed(version, rows, uncompressed)
            Log.i(TAG, "词典安装完成: version=$version rows=$rows size=$uncompressed")
            // ⚠️ onDone 现在跑在 **IO 线程**（整个函数体都在 withContext 内）。
            // 调用方（DictManageDialog）当前只在回调里 dismiss 自己、不碰 View，
            // 所以安全；但这是个隐性契约 —— 将来谁在回调里直接操作 View 就会崩，
            // 因此这里显式切回 Main，别依赖调用方自觉。
            withContext(Dispatchers.Main) { onDone?.invoke(rows) }
        } catch (e: CancellationException) {
            // 用户取消：不是错误，但要把状态从「下载中」拉回来，否则界面永远转圈
            _state.value = currentStateFromDisk()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "词典安装失败", e)
            fail(e.message ?: "词典安装失败")
        }
        } finally {
            downloadLock.unlock()
        }
    }

    /**
     * 取消/失败后回到磁盘实况（下载中的 .part 保留，供下次续传）。
     *
     * ⚠️ 必须真的读磁盘。曾写成「读内存 `_state`，非 Installed 就报 NotInstalled」，
     *   而唯一调用点是 `CancellationException` 分支——此时 `_state` 恰好是 `Downloading`，
     *   于是**旋转屏幕取消协程**（lifecycleScope 被 cancel）后：
     *   磁盘上躺着 55MB 可用词典，UI 却全说「未安装」，
     *   且 `DictManageDialog` 只在 `Installed` 分支显示卸载按钮 ⇒ 用户清不掉它。
     */
    private fun currentStateFromDisk(): DictState {
        refreshStateFromDisk()
        return _state.value
    }

    private fun fail(message: String) {
        Log.w(TAG, "词典操作失败: $message")
        _state.value = DictState.Error(message)
    }

    private fun percentOf(done: Long, total: Long): Int =
        if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1

    /** 从 `Content-Range: bytes 100-24253535/24253536` 里取总长 */
    private fun parseTotal(contentRange: String): Long {
        val slash = contentRange.indexOf('/')
        if (slash < 0) return 0L
        return contentRange.substring(slash + 1).toLongOrNull() ?: 0L
    }

    private fun md5Of(file: File): String {
        if (!file.isFile) return ""
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "计算 MD5 失败: ${file.name}", e)
            ""
        }
    }

    /**
     * 解压 gzip 到目标文件。
     *
     * 逐块解压而不是 `GZIPInputStream.copyTo(FileOutputStream(target))` ——
     * 后者在目标已存在时行为依赖copyTo 的实现（部分版本直接覆写、部分追加），
     * 而这里必须**确定地覆写**（重装词典时目标已存在）。
     *
     * @param onProgress 已解压字节 / 解压后总字节（总字节未知时传 -1）
     * @return 解压后字节数；失败抛 IOException
     */
    private fun gunzip(src: File, target: File, onProgress: (Long, Long) -> Unit): Long {
        val tmp = File(target.parentFile, target.name + ".tmp")
        var written = 0L
        // ⚠️ 清理必须放finally：gzip 包被截断/损坏时 GZIPInputStream.read 会抛
        //   EOFException/IOException，此时下面三条正常路径的 tmp.delete() 全都够不着，
        //   55MB 的 .tmp 会一直留在磁盘上（只有 uninstall() 会清）。
        try {
            GZIPInputStream(src.inputStream().buffered(64 * 1024)).use { gz ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (gz.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        written += n
                        // 解压进度按已写入量给，总量用「压缩包大小的 2.5 倍」估算
                        // （实测压缩比 41.8%，即解压后约为 2.39 倍，留一点余数）
                        onProgress(written, -1L)
                    }
                    out.fd.sync()
                }
            }
            // 校验解压产物确实是 SQLite：读文件头
            if (!looksLikeSqlite(tmp)) {
                throw IOException("解压产物不是有效的 SQLite 库")
            }
            if (target.exists() && !target.delete()) {
                throw IOException("无法替换旧词典文件")
            }
            if (!tmp.renameTo(target)) {
                throw IOException("无法把解压结果改名为词典文件")
            }
            return written
        } finally {
            // 成功路径上 tmp 已被 rename 走，delete 找不到文件、无副作用
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 判断文件是否是 SQLite 库：前16 字节必须是 `SQLite format 3\0`。
     *
     * 为什么必须查：解压中途磁盘满 / 进程被杀会留下**截断的 .tmp**，
     * 而它同样是「解压成功返回」的路径（`gunzip` 只在抛异常时才走到清理）。
     * 一个截断的库会在第一次查词时抛 SQLiteDatabaseCorruptException，
     * 用户看到的却是「词典已安装」——极难定位。
     */
    private fun looksLikeSqlite(file: File): Boolean = try {
        file.inputStream().use { input ->
            val header = ByteArray(16)
            var read = 0
            while (read < 16) {
                val n = input.read(header, read, 16 - read)
                if (n < 0) return false
                read += n
            }
            String(header, Charsets.US_ASCII) == "SQLite format 3\u0000"
        }
    } catch (e: Exception) {
        Log.w(TAG, "读取 SQLite 文件头失败", e)
        false
    }

    private fun readInfo(context: Context): Map<String, String> {
        val f = infoFile(context)
        if (!f.isFile) return emptyMap()
        return try {
            f.readLines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()
        } catch (e: Exception) {
            Log.w(TAG, "读取词典安装信息失败", e)
            emptyMap()
        }
    }

    private fun writeInfo(context: Context, map: Map<String, String>) {
        try {
            infoFile(context).writeText(map.entries.joinToString("\n") { "${it.key}=${it.value}" })
        } catch (e: Exception) {
            Log.w(TAG, "写入词典安装信息失败", e)
        }
    }

    // ==================== 卸载 ====================

    /**
     * 卸载词典：删库 + 删安装信息 + 关闭连接。
     *
     * 删不掉的 .part / .tmp 一并清掉 —— 留着它们下次下载会走续传分支，
     * 而 MD5 对不上必然重来，白占一份磁盘。
     *
     * @return true 表示确实删掉了词典库
     */
    fun uninstall(): Boolean {
        val ctx = appContext ?: return false
        closeDb()
        val target = dictFile(ctx)
        val removed = !target.exists() || target.delete()
        partFile(ctx).delete()
        File(dictDir(ctx), "${DICT_FILE_NAME}.tmp").delete()
        infoFile(ctx).delete()
        _state.value = DictState.NotInstalled
        Log.i(TAG, "词典已卸载: removed=$removed")
        return removed
    }

    /** 关闭并丢弃缓存的 SQLite 连接（卸载/重装前必须调用） */
    private fun closeDb() {
        cachedDb?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭词典库连接失败", e)
            }
        }
        cachedDb = null
    }

    // ==================== 查词 ====================

    /** 一个词条的查询结果 */
    data class DictEntry(
        val word: String,
        val phonetic: String,
        /** 已按字面量 `\n` 切分并去空，界面直接逐条显示 */
        val translations: List<String>,
        /** 原始 exchange 串，如 `p:ran/i:running/d:run` */
        val exchange: String,
        /** 词频标签，已按常见度排序，如 `[cet4, zk]` */
        val tags: List<String>,
        val collins: Int,
        val oxford: Boolean
    )

    /**
     * strip-word：去非字母数字 + 小写。
     *
     * ⚠️ 必须与 `_dict_build/build_dict.py` 的 `strip_word()` **逐字符一致**。
     * 这里用 [Char.isLetterOrDigit]（等价 Python 的 `str.isalnum()` 的 Unicode 语义），
     * 连字符、撇号、空格全部被剔除 —— 所以 `take-off` / `Take Off` / `TAKEOFF`
     * 归一到同一个键，正是预期行为。
     */
    fun stripWord(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * 判断选区是否是「可查的英文词」。
     *
     * 规则（与界面提示文案配套）：
     *  - 含 CJK 字符 → 不是英文词，调用方提示「仅支持查英文」
     *  - stripWord 后为空（纯符号/标点）→ 同上
     */
    fun isEnglishWord(text: String): Boolean {
        if (text.isBlank()) return false
        // CJK / 全角 统一表意文字、假名、韩文 —— 一律判为「非英文」
        if (text.any { it.code in 0x2E80..0x9FFF || it.code in 0xAC00..0xD7AF || it.code in 0xF900..0xFAFF }) {
            return false
        }
        return stripWord(text).isNotEmpty()
    }

    /**
     * 从选区文本里提取要查的词。
     *
     * 用户长按选中的**从来不是**一个干净的单词，实际形态是：
     *  - 尾部带标点：`word,` / `"book"` / `Running.`
     *  - 长按划过多个词：`hi hello`
     *  - 带连字符或撇号：`take-off` / `don't`（这两个**必须原样保留**，
     *    它们是词典里的真实词形；靠[stripWord] 归一去命中）
     *
     * 处理顺序：
     *  1. 含空白 ⇒ 判定为多词，选 strip 后最长的一段（`hi hello` 取 `hello`）；
     *  2. 否则剥掉**首尾**非字母数字字符后返回（`hello,` → `hello`）；
     *  3. 含中文 / strip 后为空 ⇒ 返回 null，由调用方提示「仅支持查英文」。
     *
     * 只剥**首尾**不动中间的符号：`take-off` 的连字符必须留着，
     * 否则词形就变了（`takeoff` 与 `take-off` 在库里是两个词条）。
     *
     * @return 用于显示与查询的原文（保持大小写），null 表示不是可查的英文词
     */
    fun extractQueryWord(selection: String): String? {
        val trimmed = selection.trim()
        if (trimmed.isEmpty()) return null
        // ⚠️ 判定「含空白」与「按空白切分」必须用**同一个**字符集。
        //   Character.isWhitespace('\u00A0') == false（NBSP 被刻意排除），
        //   而 Java 正则的 \s == [ \t\n\x0B\f\r]，两者都不含 U+00A0 / U+3000。
        //   曾经这里用 isWhitespace 判、用 Regex("\\s+") 切：EPUB 里选中
        //   `hello\u00A0world` 会走「无空白」分支返回整串 → stripWord 剔掉 NBSP
        //   变成 "helloworld" → 必然查不到。
        if (WS.containsMatchIn(trimmed)) {
            return trimmed.split(WS)
                .filter { it.isNotEmpty() }
                .mapNotNull { stripEdge(it) }
                .maxByOrNull { it.length }
        }
        return stripEdge(trimmed)
    }

    /**
     * 「空白」= Unicode 空白 + NBSP + 全角空格 + 各类细空格。
     * 提取查询词与切分词组共用它，避免两处判定集不一致。
     */
    private val WS = Regex("[\\s\\u00A0\\u3000\\u2000-\\u200A\\u202F\\u205F]+")

    /** 剥掉首尾非字母数字字符；不是英文词时返回 null */
    private fun stripEdge(s: String): String? {
        if (!isEnglishWord(s)) return null
        return s.trim { !it.isLetterOrDigit() }.ifEmpty { null }
    }

    /**
     * 词内允许出现的「连接符」——必须在**词的内部**才继续扩展。
     *
     * 只有当连字符/撇号两侧都是字母数字时才算一个词的一部分：
     *  - `take-off` / `don't` / `it's` ⇒ 整个是一个词（ECDICT 里是真词条）
     *  - `word — word`（破折号两侧有空格）、`引号"`、句尾 `.` ⇒ 不是
     *
     * 判定「两侧有字母」是关键：不带这个条件，`hello-world` 连字符两侧都有字母会被当一整个词，
     * 而 `he--ll` 这种畸形串会被切碎；更重要的是 `don't` 若不整体保留，
     * [stripWord] 归一后仍能命中（it 与 don't 是两个词条），但用户点 `don't` 想读的是整个缩写。
     */
    private fun isInnerJoiner(text: CharSequence, i: Int): Boolean {
        if (i <= 0 || i >= text.length - 1) return false
        val c = text[i]
        if (c != '-' && c != '\u2019' && c != '\u2018' && c != '\'') return false
        return text[i - 1].isLetterOrDigit() && text[i + 1].isLetterOrDigit()
    }

    /**
     * 「点读」取词：从落点 [offset] 向两侧扩展，取出它所在的**完整英文单词**。
     *
     * 与 [extractQueryWord] 的分工（两者不可互换）：
     *  · [extractQueryWord] 作用于**长按选区** —— 选区已经是词了，只需剥掉首尾标点；
     *  · 本函数作用于**单击落点** —— 落点可能落在词中间（点 `hello` 的 `e`）、
     *    也可能落在词外的空白/标点上，必须自己确定词的边界。
     *
     * @return 命中的词（保持原文大小写，可直接拿去查词典与朗读）；
     *   **落点不在英文词上返回 null**（点中文、点标点、点空白都属此列）。
     *
     * ## 为什么落点在词外必须返回 null 而不是「找右边最近的词」
     * 那会让用户点句号弹出上一个词、或点中文字却查到英文 —— 定位错觉比「没反应」更糟。
     * 调用方拿到 null 应当什么都不做，不要自己找替补词。
     *
     * ## CJK 一律不命中
     * 中文没有空格分词，「落点所在的词」无法定义；且 ECDICT 是英汉词典，查中文必然落空。
     * 命中判定要求 [Char.isLetter] 且不是 CJK（口径同 [isEnglishWord]），
     * 保证「能否点读」与「能否查词」两处一致 —— 分叉就会出现「点读认得但查不到」的错位。
     *
     * ## 数字要不要并入词内
     * **要**（`COVID19` / `COVID-19` 要能整体取到）——扩展时按 [Char.isLetterOrDigit] 走，
     * 查询侧再由 [stripWord] 归一（连字符、撇号都被剔掉）命中词条。
     * 落点本身必须是**字母**（`Char.isLetter`），所以点 `COVID19` 的 `9` 不命中 ——
     * 刻意如此：`19` 这类数字串不是可朗读的英文词。
     */
    /**
     * 点读取词的**区间**版本：返回命中词在 [text] 里的**闭区间**（行内下标）。
     *
     * 单独暴露位置是为了画「选中态」高亮 —— 只拿到词就不知道该涂哪几个字符。
     * [pointReadWordAt] 改为复用本函数，两者判定口径由构造方式保证**不可能漂移**
     * （早先拆成两份时，取词与高亮范围不一致的风险就是这么来的）。
     */
    fun pointReadWordRangeAt(text: CharSequence, offset: Int): IntRange? {
        if (text.isEmpty() || offset < 0 || offset >= text.length) return null
        // 落点本身必须是字母且非 CJK。落在标点/空白/汉字上一律不命中。
        val seed = text[offset]
        if (!seed.isLetter() || seed.isCjkOrKana()) return null

        var start = offset
        while (start > 0) {
            // 连接符：仅当两侧都是字母数字时才并入词内（take-off / don't），见 isInnerJoiner
            if (isInnerJoiner(text, start - 1)) {
                start -= 2
                continue
            }
            if (!text[start - 1].isLetterOrDigit()) break
            start--
        }
        var end = offset + 1
        while (end < text.length) {
            if (isInnerJoiner(text, end)) {
                end += 2
                continue
            }
            if (!text[end].isLetterOrDigit()) break
            end++
        }

        // 前导数字退让已不需要：扩展靠 [Char.isLetterOrDigit] 走，但空格/标点天然截断，
        // 「2021 was」点 w 只会拿到 w（空格断开了），不存在吸到前导数字的路径。
        val word = text.substring(start, end)
        if (word.isEmpty() || !isEnglishWord(word)) return null
        // end 是开区间上界，转成 Kotlin 的**闭区间**
        return start..(end - 1)
    }

    /** 点读取词（只要词，不要位置）。区间版见 [pointReadWordRangeAt]。 */
    fun pointReadWordAt(text: CharSequence, offset: Int): String? =
        pointReadWordRangeAt(text, offset)?.let { text.substring(it.first, it.last + 1) }

    /**
     * 是否是 CJK 统一表意文字 / 假名 / 韩文。
     *
     * 与 [isEnglishWord] 用**完全相同**的码位区间，保证「能否点读」与「能否查词」口径一致 ——
     * 两处判定一旦分叉，就会出现「点读认得但查不到」或「查得到但点不中」的错位。
     */
    private fun Char.isCjkOrKana(): Boolean =
        code in 0x2E80..0x9FFF || code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF

    /**
     * 切分 ECDICT 的 `translation` 字段（约定 ②）。
     *
     * ⚠️ 必须按**字面量** `\n`（反斜杠 + n 两个字符）切，不是真换行：
     * ECDICT 的 translation 里存的是字面量转义，实测 768739 行中含真换行的有 0 行。
     * 若改成 `split("\n")`，上万字的释义会被挤成一行糊屏。
     *
     * 抽成 `internal` 是为了让单测能直接锁住**生产实现**：
     * 曾经这个测试在测试文件里把切分逻辑重写了一遍再断言自己，
     * 于是真去改生产代码时测试依然全绿，等于没有回归锁。
     */
    internal fun parseTranslations(raw: String?): List<String> =
        raw.orEmpty()
            .split("\\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * 查词。**必须在 IO 线程调用**（内部会切一次，可安全从任意线程调起）。
     *
     * @return 命中列表；未命中返回空列表
     */
    suspend fun lookup(word: String): List<DictEntry> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext emptyList()
        if (!isInstalled()) return@withContext emptyList()
        val sw = stripWord(word)
        if (sw.isEmpty()) return@withContext emptyList()
        val db = openDb(ctx) ?: return@withContext emptyList()
        try {
            // ⚠️ 查sw 而非 word：见类注释的约定 ①
            // 一个 sw可能对应多个 word（hood/Hood/'hood），**全部返回**（约定 ③）
            db.rawQuery(
                "SELECT word, phonetic, translation, exchange, tag, collins, oxford " +
                    "FROM dict WHERE sw = ? ORDER BY word",
                arrayOf(sw)
            ).use { c ->
                val out = ArrayList<DictEntry>(c.count)
                while (c.moveToNext()) {
                    out.add(
                        DictEntry(
                            word = c.getString(0) ?: word,
                            phonetic = c.getString(1)?.trim().orEmpty(),
                            translations = parseTranslations(c.getString(2)),
                            exchange = c.getString(3)?.trim().orEmpty(),
                            tags = parseTags(c.getString(4)),
                            collins = c.getInt(5),
                            oxford = c.getInt(6) == 1
                        )
                    )
                }
                out
            }
        } catch (e: Exception) {
            // 词典库损坏 / 文件被卸载：不该让阅读页崩掉
            Log.e(TAG, "查词失败: word=$word", e)
            emptyList()
        }
    }

    /**
     * ECDICT 的 `tag` 是空格分隔的频率标签，取值含
     * `zky`(中考) `gk`(高考) `cet4` `cet6` `ky`(考研) `toef`(托福) `ielts` `gre` `zk`。
     * 这里按「学习阶段由低到高」排序，界面取前几个展示。
     */
    private fun parseTags(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val order = listOf("zky", "gk", "zk", "cet4", "cet6", "ky", "ielts", "toef", "gre")
        val tags = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        tags.sortBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return tags
    }

    /**
     * 打开词典库连接（懒加载）。
     *
     * 用 [SQLiteDatabase.openDatabase] 而非 SQLiteOpenHelper：这个库**只读**，
     * 不需要 onCreate/onUpgrade，也不会被写入，不需要版本号管理。
     * 另外必须传 `null` CursorFactory 以拿到默认实现。
     *
     * ⭐ 只读打开是刻意的：词典库是成品数据，任何写入（包括 SQLite 自动创建
     * journal/WAL 的副作用）都没有意义，却会让「卸载时删文件」在部分机型上失败。
     */
    private fun openDb(context: Context): SQLiteDatabase? {
        cachedDb?.let { if (it.isOpen) return it }
        val f = dictFile(context)
        if (!f.isFile) return null
        return try {
            SQLiteDatabase.openDatabase(
                f.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).also { cachedDb = it }
        } catch (e: Exception) {
            Log.e(TAG, "打开词典库失败: ${f.absolutePath}", e)
            null
        }
    }

    /**
     * 词形变化的中文标签，供弹窗展示「过去式 / 复数」等。
     *
     * exchange 的键含义（ECDICT 定义）：
     * `p` 过去式 / `d` 过去分词 / `i` 现在分词 / `3` 第三人称单数 /
     * `r` 比较级 / `t` 最高级 / `s` 复数 / `0` lemma 本身 / `1` lemma 变换形式。
     *
     * ⚠️ **同一个形态可能对应多个键**，这是数据本身的歧义而非实现缺陷：
     * `run` 的 exchange 是 `p:ran/i:running/d:run/0:run/1:d/3:runs/s:runs` ——
     * `runs` 既出现在 `3:runs`（第三人称单数）也出现在 `s:runs`（复数），
     * 因为 run 的三单与复数同形。实测查 `runs` 时本方法返回「第三人称单数」。
     *
     * 取**第一个**命中（ECDICT 的排列顺序里 `3` 在 `s` 之前）是有意的：
     * 一次只给一个标签足够回答「这是什么形态」。
     * 需要展示全部标签的场景（弹窗的「词形变化」行）请用
     * [exchangeLabelsAll]，别在这里凑合。
     */
    fun exchangeLabel(exchange: String, form: String): String? =
        exchangeLabelsAll(exchange, form).firstOrNull()

    /**
     * 取出某形态对应的**全部**中文标签。
     *
     * 与 [exchangeLabel] 的区别只在于「不早退」：`runs` 会返回
     * `["第三人称单数", "复数"]` 两个标签，而前者只返回第一个。
     */
    fun exchangeLabelsAll(exchange: String, form: String): List<String> {
        if (exchange.isBlank() || form.isBlank()) return emptyList()
        val out = ArrayList<String>(2)
        for (part in exchange.split("/")) {
            val idx = part.indexOf(':')
            if (idx <= 0) continue
            val key = part.substring(0, idx)
            val value = part.substring(idx + 1)
            if (!value.equals(form, ignoreCase = true)) continue
            val label = when (key) {
                "p" -> "过去式"
                "d" -> "过去分词"
                "i" -> "现在分词"
                "3" -> "第三人称单数"
                "r" -> "比较级"
                "t" -> "最高级"
                "s" -> "复数"
                "0" -> "原形"
                else -> null
            }
            if (label != null) out.add(label)
        }
        return out
    }

    /** 仅供测试：清掉缓存连接 */
    internal fun resetForTest() {
        closeDb()
        _state.value = DictState.NotInstalled
    }
}