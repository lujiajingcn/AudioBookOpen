package com.audiobook.audiobookplayer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.audiobook.audiobookplayer.model.ReferenceAudio
import com.audiobook.audiobookplayer.model.ServerReferenceAudio
import com.audiobook.audiobookplayer.model.SharedReferenceAudio
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 本地参考音频仓库：「我的参考音频列表」的增删查。
 *
 * 这张列表是**手机本地的登记表**，文件本体是 App 私有目录下的一份副本
 * （filesDir/reference_audio/）。由此得出两条关键性质：
 * - 从手机导入 / 从服务器下载，都是往私有目录写一份副本并登记一条记录；
 * - 删除时只删这条记录 + 私有目录里的副本，**绝不删除用户手机上的原始文件**
 *   （原文件从未被移动过，只是被读取复制）。
 */
class ReferenceAudioRepository(private val context: Context) {

    companion object {
        private const val TAG = "RefAudioRepo"
        private const val DIR_NAME = "reference_audio"

        /** 单文件上限 50MB，与服务端 maxFileSizeBytes 一致 */
        const val MAX_BYTES = 50L * 1024 * 1024

        /** 服务端 isAllowedReferenceExt 的白名单（音频 + 视频，视频服务端转 WAV） */
        private val ALLOWED_EXT = setOf(
            "wav", "mp3", "flac", "aac", "ogg", "m4a",
            "mp4", "avi", "mkv", "mov", "flv", "wmv", "webm", "m4v", "3gp"
        )

        /** 供 UI 提示用：支持的格式说明 */
        val ALLOWED_EXT_DISPLAY: String = ALLOWED_EXT.joinToString("、")

        /** MIME 类型 → 扩展名兜底（resolveDisplayName 拿不到文件名时按 MIME 判定：
         *  rawName==null 时若整段跳过白名单、默认 .wav，会把任意文件以 wav 入库，
         *  服务端按 wav 转码 → 静默产出无声音频） */
        private val MIME_TO_EXT = mapOf(
            "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav",
            "audio/mpeg" to "mp3",
            "audio/flac" to "flac", "audio/x-flac" to "flac",
            "audio/aac" to "aac",
            "audio/ogg" to "ogg", "application/ogg" to "ogg",
            "audio/mp4" to "m4a", "audio/x-m4a" to "m4a",
            "video/mp4" to "mp4",
            "video/x-matroska" to "mkv",
            "video/quicktime" to "mov",
            "video/x-flv" to "flv",
            "video/x-ms-wmv" to "wmv",
            "video/webm" to "webm",
            "video/3gpp" to "3gp",
            "video/x-m4v" to "m4v"
        )
    }

    private val repo = AppRepository.getInstance(context)

    private val storageDir: File
        get() = File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 读取原始文件的展示名（用于默认命名与扩展名推断） */
    fun resolveDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取文件名失败: ${e.message}")
            null
        }
    }

    /** 扩展名白名单校验（与服务端 isAllowedReferenceExt 同口径，避免上传后才被拒） */
    fun isAllowedName(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        return !ext.isNullOrEmpty() && ext in ALLOWED_EXT
    }

        /** 列表（按创建时间倒序） */
        suspend fun listAll(): List<ReferenceAudio> = withContext(Dispatchers.IO) {
            repo.listReferenceAudios()
        }

        /**
         * 解析导入文件的扩展名：优先用文件名后缀，拿不到文件名（部分文件管理器不暴露
         * DISPLAY_NAME）时按 MIME 类型兜底。返回 null 表示该文件既无合法后缀、MIME 也不在白名单，
         * 调用方应拒绝，而不是默认 .wav。
         */
        private fun resolveExt(rawName: String?, uri: Uri): String? {
            val fromName = rawName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in ALLOWED_EXT }
            if (fromName != null) return fromName
            val mime = context.contentResolver.getType(uri)?.lowercase() ?: return null
            return MIME_TO_EXT[mime]
        }

    /**
     * 从手机导入：把选中的音频复制一份进私有目录并登记，原文件保持不动。
     *
     * @param customName 用户自定义名称，为空则用原始文件名（去扩展名）
     */
    suspend fun importFromUri(uri: Uri, customName: String? = null): Result<ReferenceAudio> =
        withContext(Dispatchers.IO) {
            val rawName = resolveDisplayName(uri)

            // 扩展名判定：优先文件名，拿不到文件名时按 MIME 兜底；两者都不合法则拒绝，
            // 不再默认 .wav。
            val ext = resolveExt(rawName, uri)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("无法识别音频格式，仅支持：$ALLOWED_EXT_DISPLAY")
                )

            val display = customName?.trim()?.takeIf { it.isNotEmpty() }
                ?: rawName?.substringBeforeLast('.')?.takeIf { it.isNotEmpty() }
                ?: "参考音频"

            val destFile = newFile(ext)
            try {
                // 先用 provider 申报的大小做一次预判。
                // 整份拷完再判断 bytes > MAX_BYTES 等于上限形同虚设：
                // 用户选一个 2GB 的文件，App 会先把 2GB 全写进内部存储、把手机空间撑满，
                // 然后才弹出一句「超过 50MB 上限」并删掉文件——磁盘已经被祸害过了。
                //
                // 预判只是快路径（SIZE 列由 provider 自报，可能为 0/-1/缺列），
                // 真正的拦截在 saveToWithLimit 里按实际读到的字节数做，边拷边拦。
                val declared = queryDeclaredSize(uri)
                if (declared != null && declared > MAX_BYTES) {
                    destFile.delete()
                    return@withContext Result.failure(
                        IllegalArgumentException("音频超过 ${MAX_BYTES / 1024 / 1024}MB 上限")
                    )
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.saveToWithLimit(destFile, MAX_BYTES)
                }
                if (bytes == null) {
                    destFile.delete()
                    return@withContext Result.failure(IOException("无法读取所选文件"))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                destFile.delete()
                return@withContext Result.failure(e)
            }

            insert(
                ReferenceAudio(
                    name = display,
                    filePath = destFile.absolutePath,
                    source = ReferenceAudio.SOURCE_LOCAL,
                    fileSize = destFile.length()
                )
            )
        }

    /**
     * 把**录制**得到的那份文件收进私有目录并登记为本地参考音频。
     *
     * 与 [importFromUri] 分开写而不是复用，是因为两者输入性质不同：
     * - 录音文件是 App 自己录在 cacheDir 的，随时可能被系统清掉，**必须复制一份**；
     * - 它没有「原始文件」，所以不存在「不要删用户原文件」这层约束（那份是缓存，
     *   落库后由调用方 markConsumed，再由 recorder 决定要不要删）。
     *
     * 扩展名固定 m4a（MediaRecorder 的 MPEG_4 + AAC 输出），在白名单内，
     * 因此服务端拒收的风险与「从手机导入 m4a」完全一致。
     *
     * @param name 展示名（调用方可带时间戳，避免多条录音重名）
     */
    suspend fun importFromRecording(source: File, name: String): Result<ReferenceAudio> =
        withContext(Dispatchers.IO) {
            if (!source.exists() || source.length() == 0L) {
                return@withContext Result.failure(IOException("录音文件为空，请重新录制"))
            }
            if (source.length() > MAX_BYTES) {
                return@withContext Result.failure(
                    IllegalArgumentException("录音超过 ${MAX_BYTES / 1024 / 1024}MB 上限")
                )
            }
            val destFile = newFile("m4a")
            try {
                source.inputStream().use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                destFile.delete()
                return@withContext Result.failure(e)
            }

            insert(
                ReferenceAudio(
                    name = name.trim().takeIf { it.isNotEmpty() } ?: "录音参考音频",
                    filePath = destFile.absolutePath,
                    source = ReferenceAudio.SOURCE_LOCAL,
                    fileSize = destFile.length()
                )
            )
        }

    /**
     * 从服务器共享库下载到手机并登记为本地参考音频。
     * 同一服务端条目已存在时不重复下载。
     */
    suspend fun importFromServer(item: ServerReferenceAudio): Result<ReferenceAudio> =
        withContext(Dispatchers.IO) {
            AuthManager.token ?: return@withContext Result.failure(BusinessException("请先登录"))

            if (listAll().any { it.serverId == item.id }) {
                return@withContext Result.failure(
                    BusinessException("《${item.name}》已在列表中，无需重复添加")
                )
            }

            val destFile = newFile(resolveExt(item.fileName))
            val download = downloadRemote(destFile) {
                RetrofitClient.heavyApi.downloadServerReference(item.id)
            }
            if (download.isFailure) {
                return@withContext Result.failure(download.exceptionOrNull()!!)
            }

            insert(
                ReferenceAudio(
                    name = item.name,
                    filePath = destFile.absolutePath,
                    source = ReferenceAudio.SOURCE_SERVER,
                    serverId = item.id,
                    description = item.description,
                    fileSize = destFile.length()
                )
            )
        }

    /**
     * 从「发现 - 参考音频」分享排行榜下载到手机并登记为本地参考音频。
     * 同一服务端条目已存在时不重复下载。
     */
    suspend fun importFromShared(item: SharedReferenceAudio): Result<ReferenceAudio> =
        withContext(Dispatchers.IO) {
            AuthManager.token ?: return@withContext Result.failure(BusinessException("请先登录"))

            if (listAll().any { it.isFromSharedLibrary && it.serverId == item.id }) {
                return@withContext Result.failure(
                    BusinessException("《${item.name}》已在列表中，无需重复添加")
                )
            }

            val destFile = newFile(resolveExt(item.fileName))
            val download = downloadRemote(destFile) {
                RetrofitClient.heavyApi.downloadSharedReference(item.id)
            }
            if (download.isFailure) {
                return@withContext Result.failure(download.exceptionOrNull()!!)
            }

            // 来源标记为 SOURCE_SHARED（别人的分享）：只有这样才能在后续分享/计数时
            // 正确区分 serverId 属于 user_reference_audio 表而非管理员共享库
            insert(
                ReferenceAudio(
                    name = item.name,
                    filePath = destFile.absolutePath,
                    source = ReferenceAudio.SOURCE_SHARED,
                    serverId = item.id,
                    description = item.description,
                    fileSize = destFile.length()
                )
            )
        }

    /**
     * 保留服务端真实扩展名，否则上传时服务端按扩展名判格式会误判；
     * 扩展名缺失或不在白名单时兜底 "wav"。
     */
    private fun resolveExt(fileName: String?): String =
        fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in ALLOWED_EXT } ?: "wav"

    /**
     * 下载远端参考音频到 [destFile]：流式写盘（响应体不整包进堆）+ 大小上限校验，
     * 任何失败都清理半成品文件。供 [importFromServer] / [importFromShared] 共用——
     * 两个入口的差异仅在「去重条件」「接口」「来源常量」。
     */
    private suspend fun downloadRemote(
        destFile: File,
        fetch: suspend () -> Response<okhttp3.ResponseBody>
    ): Result<Unit> {
        try {
            val resp = fetch()
            if (!resp.isSuccessful) {
                destFile.delete()
                return Result.failure(BusinessException("下载失败：HTTP ${resp.code()}"))
            }
            val body = resp.body()
            if (body == null) {
                destFile.delete()
                return Result.failure(IOException("下载内容为空"))
            }

            var total = 0L
            body.byteStream().use { input ->
                destFile.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        total += read
                        if (total > MAX_BYTES) {
                            throw IOException("参考音频超过 ${MAX_BYTES / 1024 / 1024}MB 上限")
                        }
                        output.write(buf, 0, read)
                    }
                }
            }
            if (total == 0L) {
                destFile.delete()
                return Result.failure(IOException("下载内容为空"))
            }
            return Result.success(Unit)
        } catch (e: CancellationException) {
            // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
            destFile.delete()
            throw e
        } catch (e: Exception) {
            destFile.delete()
            return Result.failure(e)
        }
    }

    /**
     * 把本地的一条参考音频上传到「我的服务端参考音频库」，返回服务端 id。
     *
     * 这是分享的前置步骤：本地导入的音频只在手机上，服务端没有记录，
     * 必须先入服务端我的库（user_reference_audio）才能开启分享。
     */
    suspend fun uploadToMyLibrary(item: ReferenceAudio): Result<Long> =
        withContext(Dispatchers.IO) {
            AuthManager.token ?: return@withContext Result.failure(BusinessException("请先登录"))

            val file = File(item.filePath)
            if (!file.exists()) {
                return@withContext Result.failure(IOException("音频文件已丢失，请重新添加"))
            }
            val ext = file.extension.lowercase().takeIf { it in ALLOWED_EXT } ?: "wav"

            val part = MultipartBody.Part.createFormData(
                "reference_audio",
                "${item.name}.$ext",
                file.asRequestBody("audio/*".toMediaType())
            )
            val nameBody = item.name.toRequestBody("text/plain".toMediaType())
            val descBody = item.description?.toRequestBody("text/plain".toMediaType())

            return@withContext try {
                val resp = RetrofitClient.heavyApi.uploadUserReference(
                    part, nameBody, descBody
                )
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        BusinessException("上传失败：HTTP ${resp.code()}")
                    )
                }
                val body = resp.body()
                val id = (body?.get("id") as? Number)?.toLong()
                if (body?.get("success") != true || id == null) {
                    return@withContext Result.failure(
                        BusinessException(body?.get("message") as? String ?: "上传失败")
                    )
                }
                Result.success(id)
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 把本地记录的来源改成 [ReferenceAudio.SOURCE_MINE] 并记下服务端 id。
     * 在「上传成功 + 开启分享成功」之后调用：此后这条本地副本与服务端条目绑定，
     * 再点按钮就是直接切换分享状态，不必重复上传。
     */
    suspend fun markAsMine(id: Long, serverId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val db = repo.writableDatabase
            val values = ContentValues().apply {
                put("source", ReferenceAudio.SOURCE_MINE)
                put("server_id", serverId)
            }
            db.update(
                DatabaseHelper.TABLE_REFERENCE_AUDIO, values, "id = ?",
                arrayOf(id.toString())
            ) > 0
        }

    /** 上报一次使用量（服务端 user_reference_audio.use_count + 1） */
    suspend fun reportSharedUse(serverId: Long): Boolean = withContext(Dispatchers.IO) {
        AuthManager.token ?: return@withContext false
        return@withContext try {
            val resp = RetrofitClient.apiService.reportSharedReferenceUse(serverId)
            // 上报失败不影响本地副本，调用方按「已下载成功」处理
            resp.isSuccessful && resp.body()?.get("success") == true
        } catch (e: CancellationException) {
            // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "上报参考音频使用量失败: ${e.message}")
            false
        }
    }

    /**
     * 删除：移除登记记录 + 私有目录里的副本。
     * 用户手机上的原始文件不会被删除。
     */
    suspend fun delete(item: ReferenceAudio): Boolean = withContext(Dispatchers.IO) {
        val db = repo.writableDatabase
        val rows = db.delete(
            DatabaseHelper.TABLE_REFERENCE_AUDIO, "id = ?", arrayOf(item.id.toString())
        )
        // 私有目录的副本随记录一起清理；用户原文件从未被移动，自然不受影响
        if (rows > 0) {
            runCatching { File(item.filePath).delete() }
                .onFailure { Log.w(TAG, "删除参考音频副本失败: ${item.filePath}") }
        }
        rows > 0
    }

    /**
     * 读取 content provider 申报的文件大小；取不到返回 null。
     *
     * [OpenableColumns.SIZE] 是 provider **自报**的值：部分 provider 不提供该列（缺列）、
     * 或返回 0 / -1 表示未知。因此它只能作为「一次查询就能挡掉超大文件」的快路径，
     * 不能当唯一依据——真正的拦截始终由 [saveToWithLimit] 按实际读到的字节数完成。
     */
    private fun queryDeclaredSize(uri: Uri): Long? {
        if (uri.scheme != "content") return null
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
                ?: return null
            cursor.use { c ->
                val index = c.getColumnIndex(OpenableColumns.SIZE)
                if (index == -1) return null
                if (!c.moveToFirst()) return null
                if (c.isNull(index)) null else c.getLong(index)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取文件大小失败，跳过预判: ${e.message}")
            null
        }
    }

    private fun newFile(ext: String): File {
        if (!storageDir.exists()) storageDir.mkdirs()
        return File(
            storageDir,
            "ref_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext"
        )
    }

    /** 写库；失败时清理已落盘的副本，避免孤儿文件 */
    private fun insert(item: ReferenceAudio): Result<ReferenceAudio> {
        return try {
            val db = repo.writableDatabase
            val values = ContentValues().apply {
                put("name", item.name)
                put("file_path", item.filePath)
                put("source", item.source)
                put("server_id", item.serverId)
                put("description", item.description)
                put("file_size", item.fileSize)
            }
            val id = db.insertOrThrow(DatabaseHelper.TABLE_REFERENCE_AUDIO, null, values)
            Result.success(item.copy(id = id, createdAt = System.currentTimeMillis()))
        } catch (e: Exception) {
            runCatching { File(item.filePath).delete() }
            Log.e(TAG, "登记参考音频失败: ${e.message}")
            Result.failure(IOException("保存失败：${e.message}"))
        }
    }
}
