package com.audiobook.audiobookplayer

import android.app.Application
import android.util.Log
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书架同步结果。字段都是「本次同步对本机做了什么」，供 UI 反馈/日志。
 *
 * @property ok            同步流程是否整体成功（未登录 / 网络失败 / 服务端返回异常 = false）
 * @property added         新落地的「在线占位条目」数量（服务端在架、本机原本没这 id）
 * @property removed       因服务端已移出而清理的本机占位条目数量
 * @property serverTotal   服务端返回的在架总数（帮助排查异常，如返回 0 但本地仍有大量在架书）
 * @property message       面向用户的简短说明（失败时可直接 Toast）
 */
data class ShelfSyncResult(
    val ok: Boolean,
    val added: Int = 0,
    val removed: Int = 0,
    val serverTotal: Int = 0,
    val message: String = ""
)

/**
 * 书城书架跨端同步（服务端为单一真源，身份 = `storeBookId`）。
 *
 * ## 为什么要专门一个 Manager
 *
 * 安卓原本的书架是纯本地：下载一本就 insert 一条 `ebooks`，删书就走
 * [BookStorage.deleteBookCascade] 把任务/收藏/音频一起级联清掉。书城加入/移出
 * 只在**当前设备**生效，PC 端加或删除在安卓看不到，反之亦然。这个类把
 * `GET /api/shelf` 的结果落到本地 `ebooks` 表，用**在线占位条目**（`file_path = ''`、
 * `store_book_id > 0`）承接跨端在架状态。点开时走书城已有的「在线读」链路
 * （`GET api/store/book/{id}/content` + 按需下载音频），零改动复用现有能力。
 *
 * ## 同步语义（与 PC 端 sync._pull_shelf 逐条对齐）
 *
 *  - **加入方向**（add）：服务端每一项，若本机没有对应 `store_book_id` →
 *    走 [BookStorage.insertStoreShelfPlaceholder] 建一条占位条目；本机已有
 *    （下载过或已占位）则**完全不覆盖**，避免抹掉用户已改的书名 / 已存在的
 *    本地文件绑定 / 已缓存正文。
 *  - **移出方向**（delete）：本机的「占位条目」（`file_path = ''` 且
 *    `store_book_id > 0`）若已不在服务端列表 → 走
 *    [BookStorage.pruneServerShelfPlaceholders] 删除。**已下载到本地
 *    （有 `file_path`）的行永远不动**：「在别的设备移出书架」不应把本机
 *    的付费下载内容一并清掉，与 Audible 一致的保守语义，也和 PC 保持口径。
 *  - **失败保本地**：任何异常 / 未登录 / 服务端返回不可用 → 原样返回 false，
 *    既不加也不删。这一条尤其重要：如果因为一次网络失败就把本地在架标记清空，
 *    下次同步拉回之前用户会看到书架少一堆书，观感像 bug。
 *
 * ## 与收藏同步 [FavoriteRepository.syncWithServer] 的对应
 *
 *  同样是「服务端真源 + 失败保本地 + 未登录跳过」；不同点是收藏的身份是
 *  (bookName, segmentNumber)，书架是 storeBookId，且书架不做「上传待同步项」——
 *  加入书架的动作在书城页/下载流程里当场调 `POST /api/shelf/{id}` 就完成上云，
 *  这里只做拉取对账。若未来要做「本机加的书不立即上云、批量提交」再扩。
 */
class ShelfSyncManager(private val context: Application) {

    private val bookStorage = BookStorage(context)
    private val api by lazy { RetrofitClient.apiService }

    /**
     * 从服务端拉一次书架并对账本地。返回 [ShelfSyncResult]。
     *
     * 未登录直接跳过（返回 ok=false，不改动本地）。调用方在成功后自行触发
     * `loadEbooks()` 刷新 UI。
     */
    suspend fun syncFromServer(): ShelfSyncResult = withContext(Dispatchers.IO) {
        if (!AuthManager.isAuthenticated) {
            return@withContext ShelfSyncResult(ok = false, message = "未登录，跳过书架同步")
        }
        try {
            val resp = api.getShelf()
            if (!resp.isSuccessful) {
                Log.w(TAG, "书架同步：HTTP ${resp.code()}，保留本地")
                return@withContext ShelfSyncResult(ok = false, message = "同步失败(${resp.code()})")
            }
            val parsed = parseShelfItems(resp.body())
            if (parsed == null) {
                Log.w(TAG, "书架同步：响应结构异常，保留本地")
                return@withContext ShelfSyncResult(ok = false, message = "同步响应格式异常")
            }
            val (serverIds, items) = parsed

            // 1. 加入：服务端在架 & 本机没有该 store_book_id → 建在线占位条目
            val existingIds = bookStorage.getExistingStoreBookIdSet()
            var added = 0
            for (item in items) {
                val sid = item.storeBookId
                if (sid <= 0L) continue
                if (sid in existingIds) continue // 已下载 / 已占位 / 用户手工同名书，都不动
                if (bookStorage.insertStoreShelfPlaceholder(sid, item.title, item.hasAudio)) {
                    added++
                }
            }

            // 2. 移出：本机占位条目 & 已不在服务端 → 清理。**只删占位**，
            //    已下载行(file_path 非空)永远保留 —— 见类头「移出方向」注释
            val removed = bookStorage.pruneServerShelfPlaceholders(serverIds)

            Log.d(
                TAG,
                "书架同步完成：服务端 ${serverIds.size} 本 → 本机 +$added / -$removed"
            )
            ShelfSyncResult(
                ok = true,
                added = added,
                removed = removed,
                serverTotal = serverIds.size,
                message = "书架已同步"
            )
        } catch (e: CancellationException) {
            // 协程取消（如页面销毁）不是失败：原样抛出，避免上层误当作"同步失败"处理
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "书架同步异常（保留本地）: ${e.message}", e)
            ShelfSyncResult(ok = false, message = "同步异常：${e.message ?: "未知"}")
        }
    }

    /**
     * best-effort 通知服务端「这本书加入我的书架」。书城下载入库 / 手动加入书架时调用。
     *
     * 失败只打日志，不影响本地流程；下次 [syncFromServer] 会以服务端为准自然对齐
     * ——如果服务端确实没记录，本机也不会莫名其妙多出什么。返回是否 HTTP 2xx。
     */
    suspend fun addToShelfRemote(storeBookId: Long): Boolean = withContext(Dispatchers.IO) {
        if (storeBookId <= 0L) return@withContext false
        if (!AuthManager.isAuthenticated) return@withContext false
        try {
            val resp = api.addToShelf(storeBookId)
            if (!resp.isSuccessful) {
                Log.w(TAG, "加入云端书架失败 HTTP ${resp.code()}: storeBookId=$storeBookId")
                false
            } else {
                Log.d(TAG, "✅ 已加入云端书架: storeBookId=$storeBookId")
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "加入云端书架异常: storeBookId=$storeBookId - ${e.message}")
            false
        }
    }

    /**
     * 从云端书架移除（对应用户在书架「移出这本书」）。本地删行由调用方处理，
     * 这里只负责通知服务端。失败保本地，下次同步会以服务端为真源拉回 ——
     * 也就是说"移除失败"实际表现为"下次进书架页那本书又出现了"，用户能自愈。
     */
    suspend fun removeFromShelfRemote(storeBookId: Long): Boolean = withContext(Dispatchers.IO) {
        if (storeBookId <= 0L) return@withContext false
        if (!AuthManager.isAuthenticated) return@withContext false
        try {
            val resp = api.removeFromShelf(storeBookId)
            if (!resp.isSuccessful) {
                Log.w(TAG, "移出云端书架失败 HTTP ${resp.code()}: storeBookId=$storeBookId")
                false
            } else {
                Log.d(TAG, "✅ 已移出云端书架: storeBookId=$storeBookId")
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "移出云端书架异常: storeBookId=$storeBookId - ${e.message}")
            false
        }
    }

    /**
     * 解析 `{success, items:[{shelfBookId, storeBookId, title, hasAudio, ...}]}`。
     * 结构不符 / `success != true` → 返回 null，调用方保留本地不重建。
     *
     * 服务端已确认字段名（[ShelfService.toItem]），Retrofit 会把 JSON 数字统一
     * 反序列化为 `Number`（Integer/Long 视大小而定），要用 `.toLong()` / `.toInt()` 兜住。
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseShelfItems(body: Map<String, Any>?): Pair<Set<Long>, List<ServerShelfItem>>? {
        if (body == null) return null
        if (body["success"] != true) return null
        val raw = body["items"] as? List<*> ?: return null
        val ids = mutableSetOf<Long>()
        val out = ArrayList<ServerShelfItem>(raw.size)
        for (o in raw) {
            val m = o as? Map<String, Any> ?: continue
            val sid = (m["storeBookId"] as? Number)?.toLong() ?: continue
            val title = (m["title"] as? String).orEmpty()
            val hasAudio = m["hasAudio"] as? Boolean ?: false
            // 「已失效」项（missing=true）也加入 serverIds 集合，避免本机占位条目
            // 被误 prune —— 用户能看见"这本书已下架"的卡片，主动删才算移出。
            ids.add(sid)
            out.add(ServerShelfItem(storeBookId = sid, title = title, hasAudio = hasAudio))
        }
        return ids to out
    }

    /** 服务端返回的书架条目（只保留同步需要的字段） */
    private data class ServerShelfItem(
        val storeBookId: Long,
        val title: String,
        val hasAudio: Boolean
    )

    companion object {
        private const val TAG = "BookVM"
    }
}
