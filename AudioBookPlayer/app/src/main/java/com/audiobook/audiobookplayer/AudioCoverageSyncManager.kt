package com.audiobook.audiobookplayer

import android.app.Application
import android.util.Log
import com.audiobook.audiobookplayer.model.AudioCoverageItemRequest
import com.audiobook.audiobookplayer.model.SyncAudioCoverageRequest
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 覆盖图同步失败的分级类别（供 UI 决定要不要重试 / 弹登录框 / 提示升级服务端）。
 *
 * 为什么要分级：覆盖图接口是 Phase3 才加的，老服务端会返 404。如果只把 "同步失败(404)"
 * 甩给用户，用户会以为是自己操作错了；明确告诉用户"服务端版本过旧"才能引导正确的行动。
 * 同理 401（登录过期）、5xx（服务端故障）、网络不可达（本地问题）也要分开说。
 */
enum class AudioCoverageSyncFailure {
    /** 成功（无失败） */
    NONE,
    /** 未登录（本地状态，没发请求） */
    NOT_AUTHED,
    /** 401：登录过期 */
    AUTH_EXPIRED,
    /** 403：无权限 */
    FORBIDDEN,
    /** 404：服务端未部署 Phase3，接口不存在 */
    SERVER_TOO_OLD,
    /** 5xx：服务端故障 */
    SERVER_ERROR,
    /** IOException / 连接失败 / 超时 */
    NETWORK,
    /** 200 但响应结构异常 */
    BAD_RESPONSE,
    /** 其它未知错误 */
    UNKNOWN
}

/**
 * 有声覆盖图同步结果（Phase3 跨端）。字段都是「本次同步对本机做了什么」，供 UI 反馈 / 日志。
 *
 * @property ok          同步流程是否整体成功（未登录 / 网络失败 / 服务端返回异常 = false）
 * @property pushed      本次上报到服务端的本机覆盖行数（本机无音频时为 0，改走 GET 拉取）
 * @property rows        重建本地缓存写入的行数（服务端权威并集视图落到 audio_coverage 的行数）
 * @property serverTotal 服务端返回的覆盖行总数（帮助排查异常，如返回 0 但本地明明有音频）
 * @property message     面向用户的简短说明（失败时可直接 Toast）
 * @property failure     分级失败类别（成功时为 [AudioCoverageSyncFailure.NONE]）
 * @property httpCode    失败时的 HTTP 状态码（成功 / 网络异常时为 0）
 */
data class AudioCoverageSyncResult(
    val ok: Boolean,
    val pushed: Int = 0,
    val rows: Int = 0,
    val serverTotal: Int = 0,
    val message: String = "",
    val failure: AudioCoverageSyncFailure = AudioCoverageSyncFailure.NONE,
    val httpCode: Int = 0,
    /**
     * P1 #3：True = 服务端回 **304 Not Modified**，本地缓存原样保留、未重建。
     * 此时 [ok] 仍为 true（同步流程成功完成，只是没有新数据），[rows] = 0，
     * [serverTotal] = 0（304 无 body，拿不到 items 数）。
     * 调用方可据此跳过书架徽章刷新之外的重活，或在 UI 上显示"覆盖图未变化"。
     */
    val notModified: Boolean = false
)

/**
 * 有声覆盖图跨端同步（服务端为单一真源，身份 = (userId, bookKey, source)，合并语义 = 并集只增）。
 *
 * ## 与书架 [ShelfSyncManager] 的异同
 *
 *  相同：「服务端真源 + 失败保本地 + 未登录跳过 + 用 by-lazy api / withContext(IO) + Gson Number.toLong
 *  解析 + CancellationException 单独 re-throw」的整套骨架照搬。
 *
 *  不同（关键）：**没有安全护栏**。收藏 / 生词本重建前要检查「本机待上传项是否都被服务端确认」，
 *  未确认就不重建（怕误删还没上云的数据）。覆盖图不同 —— 它是**纯感知层**：本地缓存 audio_coverage
 *  只是「哪本书在哪个来源处理到哪些段」的镜像视图，重建它**绝不会删除本机任何音频**（音频真相在
 *  audio_segments）。所以这里直接镜像服务端返回的并集全量，无需护栏。
 *
 * ## 同步方向（与 PC 端 `sync._sync_audio_coverage` 逐条对齐）
 *
 *  - 本机**有**音频覆盖（[BookStorage.localAudioCoverage] 非空）→ `POST /api/audio-coverage/sync`，
 *    服务端把本机 doneRanges 与已有记录**并集**后返回合并全量；
 *  - 本机**无**音频（全新设备 / 只下了电子书）→ `GET /api/audio-coverage` 直接拉全量并集视图；
 *  - 无论哪条路径，拿到服务端 `items` 后都 [BookStorage.rebuildAudioCoverageFromServer] 全量重建缓存。
 *
 *  并集「只增不删」是服务端语义决定的：v1 没有 DELETE 覆盖图端点，单机删本地音频也不该抹掉
 *  其它端「这本书曾被合成过」的感知。所以本机缓存最终等于服务端权威并集，不反映「本机是否已删某段」。
 */
class AudioCoverageSyncManager(private val context: Application) {

    private val bookStorage = BookStorage(context)
    private val api by lazy { RetrofitClient.apiService }

    /**
     * 从服务端拉 / 推一次覆盖图并重建本地缓存。返回 [AudioCoverageSyncResult]。
     * 未登录直接跳过（ok=false，不改动本地缓存）。调用方在成功后自行刷新书架徽章。
     *
     * P1 #3：本机无音频时走**条件 GET**——带上次缓存的 ETag 发 ``If-None-Match``，
     * 服务端未变则回 304，本地缓存原样保留、跳过重建（[AudioCoverageSyncResult.notModified] = true）。
     * 本机有音频时仍走 POST 并集上报，响应里的新 ETag 落盘供下次条件 GET 用。
     */
    suspend fun syncFromServer(): AudioCoverageSyncResult = withContext(Dispatchers.IO) {
        if (!AuthManager.isAuthenticated) {
            return@withContext AudioCoverageSyncResult(
                ok = false,
                message = "未登录，跳过覆盖图同步",
                failure = AudioCoverageSyncFailure.NOT_AUTHED
            )
        }
        try {
            val local = bookStorage.localAudioCoverage()

            // 本机有音频→POST 并集上报；本机无音频→条件 GET（带缓存 ETag）。与 PC 完全一致。
            val resp = if (local.isNotEmpty()) {
                api.syncAudioCoverage(SyncAudioCoverageRequest(toRequestItems(local)))
            } else {
                val cached = loadCoverageEtag()
                api.getAudioCoverageConditional(cached.ifBlank { null })
            }

            // 304：服务端未变，本地缓存仍权威。仅 GET 路径会走到这里（POST 必返 200）。
            if (resp.code() == 304) {
                // 304 也可能带 ETag（RFC 7232 §4.1），若与缓存不同则更新
                val etag304 = resp.headers()["ETag"] ?: ""
                if (etag304.isNotBlank() && etag304 != loadCoverageEtag()) {
                    saveCoverageEtag(etag304)
                }
                Log.d(TAG, "覆盖图同步：304 未变化，保留本地缓存（上报 ${local.size} 行）")
                return@withContext AudioCoverageSyncResult(
                    ok = true,
                    pushed = local.size,
                    rows = 0,
                    serverTotal = 0,
                    message = "覆盖图未变化",
                    failure = AudioCoverageSyncFailure.NONE,
                    httpCode = 304,
                    notModified = true
                )
            }

            if (!resp.isSuccessful) {
                val code = resp.code()
                val (failure, msg) = classifyHttpError(code)
                Log.w(TAG, "覆盖图同步：HTTP $code → $failure，保留本地缓存")
                return@withContext AudioCoverageSyncResult(
                    ok = false,
                    message = msg,
                    failure = failure,
                    httpCode = code
                )
            }

            // 200：落盘响应 ETag 供下次条件 GET（老服务端不发时存空串，等价于"无缓存"）
            val etag = resp.headers()["ETag"] ?: ""
            if (etag.isNotBlank()) {
                saveCoverageEtag(etag)
            }

            val items = parseCoverageItems(resp.body())
            if (items == null) {
                Log.w(TAG, "覆盖图同步：响应结构异常，保留本地缓存")
                return@withContext AudioCoverageSyncResult(
                    ok = false,
                    message = "同步响应格式异常",
                    failure = AudioCoverageSyncFailure.BAD_RESPONSE
                )
            }

            val rows = bookStorage.rebuildAudioCoverageFromServer(items)
            Log.d(
                TAG,
                "覆盖图同步完成：上报 ${local.size} 行 → 服务端 ${items.size} 行 → 本机缓存 $rows 行"
            )
            AudioCoverageSyncResult(
                ok = true,
                pushed = local.size,
                rows = rows,
                serverTotal = items.size,
                message = "覆盖图已同步",
                failure = AudioCoverageSyncFailure.NONE,
                httpCode = resp.code()
            )
        } catch (e: CancellationException) {
            throw e   // 协程取消不是失败，原样抛出
        } catch (e: IOException) {
            // 网络层异常（连接失败 / 超时 / DNS 解析失败等）
            Log.w(TAG, "覆盖图同步：网络异常 ${e.message}")
            AudioCoverageSyncResult(
                ok = false,
                message = "网络不可达或超时，请检查网络后重试",
                failure = AudioCoverageSyncFailure.NETWORK
            )
        } catch (e: Exception) {
            Log.e(TAG, "覆盖图同步异常（保留本地缓存）: ${e.message}", e)
            AudioCoverageSyncResult(
                ok = false,
                message = "同步异常：${e.message ?: "未知"}",
                failure = AudioCoverageSyncFailure.UNKNOWN
            )
        }
    }

    /**
     * 把 HTTP 状态码翻译成 ``(失败类别, 用户可读消息)``。
     *
     * 分级理由见 [AudioCoverageSyncFailure] 注释：404 = 服务端太旧（需升级），
     * 401 = 登录过期（需重新登录），5xx = 服务端故障（稍后重试），其它 = 未知。
     */
    private fun classifyHttpError(code: Int): Pair<AudioCoverageSyncFailure, String> = when (code) {
        401 -> AudioCoverageSyncFailure.AUTH_EXPIRED to "登录已过期，请重新登录"
        403 -> AudioCoverageSyncFailure.FORBIDDEN to "无权限访问覆盖图接口"
        404 -> AudioCoverageSyncFailure.SERVER_TOO_OLD to
            "服务端版本过旧，未提供覆盖图接口（需升级服务端到 Phase3）"
        in 500..599 -> AudioCoverageSyncFailure.SERVER_ERROR to "服务端错误($code)，请稍后重试"
        else -> AudioCoverageSyncFailure.UNKNOWN to "同步失败($code)"
    }

    /** 本机覆盖行 map（来自 [BookStorage.localAudioCoverage]）→ 上报请求体。 */
    private fun toRequestItems(local: List<Map<String, Any?>>): List<AudioCoverageItemRequest> {
        val out = ArrayList<AudioCoverageItemRequest>(local.size)
        for (m in local) {
            val bk = (m["bookKey"] as? String)?.takeIf { it.isNotEmpty() } ?: continue
            val src = if (m["source"] == "server") "server" else "local"
            out.add(
                AudioCoverageItemRequest(
                    bookKey = bk,
                    source = src,
                    doneRanges = (m["doneRanges"] as? String) ?: "",
                    totalSegments = (m["totalSegments"] as? Number)?.toInt(),
                    storeBookId = (m["storeBookId"] as? Number)?.toLong(),
                    serverTaskId = (m["serverTaskId"] as? Number)?.toLong()
                )
            )
        }
        return out
    }

    /**
     * 解析 `{success, items:[{bookKey, source, storeBookId?, serverTaskId?, totalSegments,
     * doneRanges, doneCount, hasAudio, updatedAt}], total}`。
     * 结构不符 / `success != true` → null（调用方保留本地缓存不重建）。
     *
     * Retrofit+Gson 把 JSON 数字统一反序列化为 `Number`（Integer/Double/Long 视大小而定），
     * 交给 rebuild 时用 `as? Number` 取，故这里原样透传，仅规范化 source。
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseCoverageItems(body: Map<String, Any?>?): List<Map<String, Any?>>? {
        if (body == null) return null
        if (body["success"] != true) return null
        val raw = body["items"] as? List<*> ?: return null
        val out = ArrayList<Map<String, Any?>>(raw.size)
        for (o in raw) {
            val m = o as? Map<String, Any?> ?: continue
            val bk = (m["bookKey"] as? String)?.takeIf { it.isNotEmpty() } ?: continue
            val src = if (m["source"] == "server") "server" else "local"
            val norm = HashMap<String, Any?>(m)
            norm["source"] = src
            norm["bookKey"] = bk
            out.add(norm)
        }
        return out
    }

    // ---------- ETag 持久化（P1 #3 条件 GET） ----------

    /**
     * 覆盖图 ETag 的本地缓存，专用于跨启动持久化条件 GET 的校验子。
     *
     * 为什么用独立的 SharedPreferences 文件（[PREFS_NAME]）而不是塞进主配置：
     * ETag 是**纯派生缓存**——丢了顶多下次走一次 200 全量拉取，绝不该和业务数据混在一起
     * 被误清；同时它天然按「当前登录用户」维度失效：服务端 ETag 里嵌了 userId
     * （见 `AudioCoverageService.computeEtag`），换账号后服务端算出的 ETag 必然不同 → 强制 200，
     * 所以这里只需存**一份**值，无需为多账号分区。
     */
    private fun prefs() =
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    /** 读上次缓存的覆盖图 ETag；无缓存（全新设备 / 被清）返回空串，调用方据此决定是否发 If-None-Match。 */
    private fun loadCoverageEtag(): String =
        prefs().getString(KEY_ETAG, "") ?: ""

    /**
     * 落盘服务端最新 ETag，供下次「本机无音频」的条件 GET 复用。
     * 空串直接忽略（老服务端不发 ETag 时不落脏值，等价于"无缓存"→下次照常 200）。
     */
    private fun saveCoverageEtag(etag: String) {
        if (etag.isBlank()) return
        prefs().edit().putString(KEY_ETAG, etag).apply()
    }

    companion object {
        private const val TAG = "BookVM"
        private const val PREFS_NAME = "audio_coverage_sync"
        private const val KEY_ETAG = "etag"
    }
}
