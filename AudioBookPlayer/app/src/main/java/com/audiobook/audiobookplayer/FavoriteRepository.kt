package com.audiobook.audiobookplayer

import android.app.Application
import android.util.Log
import com.audiobook.audiobookplayer.model.Favorite
import com.audiobook.audiobookplayer.model.FavoriteUploadRequest
import com.audiobook.audiobookplayer.model.SyncFavoritesRequest
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 收藏结果（success + 提示文案），替代 (Boolean, String) 回调。 */
data class FavoriteResult(val success: Boolean, val message: String)

/**
 * 「按项对账」结果统计（P1 #4）。同包下 [FavoriteRepository] / [WordBookRepository] 共用，
 * 与 PC 端 ``LocalDatabase.reconcile_*_from_server`` 返回的 dict 字段一一对应。
 *
 * @property inserted       服务端有、本地无 → 新插入的行数
 * @property updated        服务端有、本地有 → 更新的行数（含 server_id 回填）
 * @property deleted        本地已上云 (server_id 非空) 但服务端本次未返回 → 删除的行数
 * @property pendingMissing 本地待上传 (server_id 为空) 但服务端本次未回执 → **保留原样**的行数，
 *                          下次同步再试。旧版全或无 guard 会因此跳过整体重建，新版按项处理不阻塞。
 */
data class ReconcileStats(
    val inserted: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val pendingMissing: Int = 0
)

/**
 * 收藏持久化层（A1 拆分 + 收藏上云）。
 *
 * 本地 SQLite 仍是「读缓存」，但服务端 /api/favorites 才是唯一真源：
 * - [syncWithServer] 把未上云（server_id 为空）的本地收藏一次性上传合并，再用服务端全量列表重建本地表；
 * - 未登录时静默跳过，行为与纯本地一致；
 * - 网络失败时保留本地数据（待上传项下次再传），绝不因同步失败清空本地。
 *
 * 身份口径与服务端一致：bookName + lineNumber（lineNumber 上云时映射为服务端 segmentNumber）。
 * 本类不持有 LiveData，所有方法返回纯数据；刷新 UI 由 BookViewModel 负责。
 */
class FavoriteRepository(private val context: Application) {

    private val databaseHelper = AppRepository.getInstance(context).databaseHelper
    private val api by lazy { RetrofitClient.apiService }

    suspend fun createTableIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase
            db.execSQL(DatabaseHelper.DDL_FAVORITES)

            // 按书名+段落号+内容去重索引
            db.execSQL(DatabaseHelper.DDL_FAVORITES_INDEX)
            // 同步定位索引（与服务端唯一身份口径一致）
            db.execSQL(DatabaseHelper.DDL_FAVORITES_BOOK_LINE_INDEX)

            Log.d(TAG, "favorites table created/verified")
        } catch (e: Exception) {
            Log.e(TAG, "创建 favorites 表失败: ${e.message}", e)
        }
    }

    /**
     * 收藏选中的文本内容。返回 FavoriteResult（success + 提示文案）。
     * 不负责刷新收藏列表（由 BookViewModel 在调用后 loadFavorites）。
     * 新增记录 server_id 为空，随后由 syncWithServer 上传补全。
     */
    suspend fun add(bookName: String, lineNumber: Int, content: String): FavoriteResult =
        withContext(Dispatchers.IO) {
            try {
                val trimmed = content.trim()
                if (trimmed.isEmpty()) {
                    return@withContext FavoriteResult(false, "收藏内容不能为空")
                }

                val db = databaseHelper.writableDatabase

                // 与服务端同一身份口径（书名 + 段落号）去重，避免「同名同段」在本地出现两条
                val exists = db.query(
                    DatabaseHelper.TABLE_FAVORITES,
                    arrayOf("id"),
                    "book_name = ? AND line_number = ?",
                    arrayOf(bookName, lineNumber.toString()),
                    null, null, null
                ).use { cursor -> cursor.moveToFirst() }

                if (exists) {
                    return@withContext FavoriteResult(false, "已收藏过该内容")
                }

                db.execSQL(
                    "INSERT INTO ${DatabaseHelper.TABLE_FAVORITES} (book_name, line_number, content) VALUES (?, ?, ?)",
                    arrayOf(bookName, lineNumber, trimmed)
                )

                // 收藏内容（trimmed）属于用户阅读内容（PII），不得写入日志。
                // 仅记录书名与段落号用于排查
                Log.d(TAG, "✅ 收藏成功: [$bookName] §$lineNumber")
                FavoriteResult(true, "已收藏")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 收藏失败: ${e.message}")
                FavoriteResult(false, "收藏失败: ${e.message}")
            }
        }

    /**
     * 加载收藏（bookName 为空则加载全部）。
     * @return 收藏列表（按 created_at DESC）
     */
    suspend fun getFavorites(bookName: String?): List<Favorite> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Favorite>()
        try {
            val db = databaseHelper.readableDatabase
            val columns = arrayOf("id", "book_name", "line_number", "content", "created_at", "server_id")

            val cursor = if (bookName.isNullOrEmpty()) {
                db.query(
                    DatabaseHelper.TABLE_FAVORITES,
                    columns,
                    null, null, null, null,
                    "created_at DESC"
                )
            } else {
                db.query(
                    DatabaseHelper.TABLE_FAVORITES,
                    columns,
                    "book_name = ?",
                    arrayOf(bookName),
                    null, null,
                    "created_at DESC"
                )
            }

            cursor.use { c ->
                while (c.moveToNext()) {
                    list.add(
                        Favorite(
                            id = c.getLong(0),
                            bookName = c.getString(1),
                            lineNumber = c.getInt(2),
                            content = c.getString(3),
                            createdAt = if (c.isNull(4)) null else c.getString(4),
                            serverId = if (c.isNull(5)) null else c.getLong(5)
                        )
                    )
                }
            }
            Log.d(TAG, "Loaded ${list.size} favorites for ${bookName ?: "all books"}")
        } catch (e: Exception) {
            Log.e(TAG, "Load favorites failed: ${e.message}")
        }
        list
    }

    /** 删除指定收藏记录（本地 + 服务端）。不负责刷新（由 BookViewModel 在调用后 loadFavorites）。 */
    suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase
            // 先取出服务端 id（删除本地后就查不到了）
            val serverId = db.query(
                DatabaseHelper.TABLE_FAVORITES,
                arrayOf("server_id"),
                "id = ?",
                arrayOf(id.toString()),
                null, null, null
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

            db.delete(
                DatabaseHelper.TABLE_FAVORITES,
                "id = ?",
                arrayOf(id.toString())
            )
            Log.d(TAG, "Deleted favorite id=$id")

            // 需先删服务端再删本地？不必：本地已删，服务端删除失败只会下次同步拉回，属可接受的最终一致
            serverId?.let { deleteOnServer(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Delete favorite failed: ${e.message}")
        }
    }

    /**
     * 与云端的收藏同步（服务端为准，P1 #4 起改为**按项对账**）：
     *
     * 1) 收集本地 server_id 为空的收藏作为「待上传」；
     * 2) 有待上传项 → /favorites/sync 一次性上传合并；否则 → GET /favorites 拉取；
     * 3) **按项对账**（不再清空重建）：
     *    - 服务端 items 按 (bookName, lineNumber) upsert 到本地：命中→UPDATE 并回填 server_id，
     *      未命中→INSERT；
     *    - 本地已上云行 (server_id 非空) 若不在 items 中 → DELETE（服务端删除/账号切换镜像）；
     *    - 本地待上传行 (server_id 为空) 若不在 items 中 → **保留原样**，下次同步再试
     *      （旧版是全或无 guard：一项未确认就整体跳过重建；新版按项处理，不阻塞其它项）。
     *
     * 未登录直接跳过；网络失败 / 返回异常一律保留本地、返回 false。
     * @return 是否成功完成一次同步（对账已执行；即使有 pendingMissing 也算成功）
     */
    suspend fun syncWithServer(): Boolean = withContext(Dispatchers.IO) {
        if (!AuthManager.isAuthenticated) return@withContext false
        try {
            val db = databaseHelper.writableDatabase
            val local = getFavorites(null)
            val pending = local.filter { it.serverId == null }

            val serverItems: List<Favorite>? = if (pending.isNotEmpty()) {
                val body = SyncFavoritesRequest(
                    pending.map { FavoriteUploadRequest(it.bookName, it.lineNumber, it.content) }
                )
                val res = api.syncFavorites(body)
                if (res.isSuccessful) parseServerList(res.body()) else null
            } else {
                val res = api.getFavorites()
                if (res.isSuccessful) parseServerList(res.body()) else null
            }
            if (serverItems == null) {
                Log.w(TAG, "收藏同步：服务端返回不可用，保留本地")
                return@withContext false
            }

            val stats = reconcileFromServer(db, serverItems)
            Log.d(
                TAG,
                "收藏同步完成：本地 ${local.size} → 服务端 ${serverItems.size}" +
                    "（上传 ${pending.size}；对账 +${stats.inserted}/~${stats.updated}" +
                    "/-${stats.deleted}；未回执待补传 ${stats.pendingMissing}）"
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "收藏同步失败（保留本地）: ${e.message}")
            false
        }
    }

    /**
     * 按项对账（P1 #4）。事务内执行，异常整体回滚，绝不留半同步状态。
     *
     * 身份 = (book_name, line_number)，与服务端唯一键一致。算法与 PC 端
     * ``LocalDatabase.reconcile_favorites_from_server`` 逐条对齐。
     */
    private fun reconcileFromServer(
        db: android.database.sqlite.SQLiteDatabase,
        serverItems: List<Favorite>
    ): ReconcileStats {
        var inserted = 0
        var updated = 0
        var deleted = 0
        var pendingMissing = 0

        // 服务端 items 索引：(bookName, lineNumber) → Favorite；缺关键字段的直接跳过
        val serverMap = HashMap<Pair<String, Int>, Favorite>(serverItems.size)
        for (it in serverItems) {
            if (it.bookName.isEmpty() || it.lineNumber <= 0 || it.content.isEmpty()) continue
            serverMap[it.bookName to it.lineNumber] = it
        }

        db.beginTransaction()
        try {
            // 1) upsert 服务端 items
            for ((key, item) in serverMap) {
                val (bn, ln) = key
                val existing = db.query(
                    DatabaseHelper.TABLE_FAVORITES,
                    arrayOf("id"),
                    "book_name = ? AND line_number = ?",
                    arrayOf(bn, ln.toString()),
                    null, null, null
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

                if (existing != null) {
                    db.execSQL(
                        "UPDATE ${DatabaseHelper.TABLE_FAVORITES} " +
                            "SET content = ?, server_id = ?, created_at = ? WHERE id = ?",
                        arrayOf<Any?>(item.content, item.serverId, item.createdAt, existing)
                    )
                    updated++
                } else {
                    db.execSQL(
                        "INSERT INTO ${DatabaseHelper.TABLE_FAVORITES} " +
                            "(book_name, line_number, content, server_id, created_at) " +
                            "VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any?>(bn, ln, item.content, item.serverId, item.createdAt)
                    )
                    inserted++
                }
            }

            // 2) 遍历本地行，处理未被服务端覆盖的
            db.query(
                DatabaseHelper.TABLE_FAVORITES,
                arrayOf("id", "book_name", "line_number", "server_id"),
                null, null, null, null, null
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val bn = c.getString(1) ?: ""
                    val ln = c.getInt(2)
                    val hasServerId = !c.isNull(3)
                    if (serverMap.containsKey(bn to ln)) continue
                    if (hasServerId) {
                        db.delete(
                            DatabaseHelper.TABLE_FAVORITES, "id = ?", arrayOf(id.toString()))
                        deleted++
                    } else {
                        // 未确认的待上传项：保留原样，下次同步再试（旧 guard 想护的东西）
                        pendingMissing++
                    }
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ReconcileStats(inserted, updated, deleted, pendingMissing)
    }

    /** 删除服务端收藏（best-effort：失败仅告警，下次同步会拉回重试） */
    private suspend fun deleteOnServer(serverId: Long) {
        try {
            api.deleteFavorite(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "删除服务端收藏失败（下次同步重试）: ${e.message}")
        }
    }

    /** 解析 { success, favorites: [...] }，结构不符返回 null（调用方保留本地） */
    @Suppress("UNCHECKED_CAST")
    private fun parseServerList(body: Map<String, Any>?): List<Favorite>? {
        if (body == null) return null
        if (body["success"] != true) return null
        val raw = body["favorites"] as? List<*> ?: return null
        val out = ArrayList<Favorite>(raw.size)
        for (o in raw) {
            val m = o as? Map<String, Any> ?: continue
            val bookName = m["bookName"] as? String ?: continue
            val segment = (m["segmentNumber"] as? Number)?.toInt() ?: continue
            val content = m["content"] as? String ?: continue
            out.add(
                Favorite(
                    id = 0L,
                    bookName = bookName,
                    lineNumber = segment,
                    content = content,
                    createdAt = m["createdAt"] as? String,
                    serverId = (m["id"] as? Number)?.toLong()
                )
            )
        }
        return out
    }

    companion object {
        private const val TAG = "BookVM"
    }
}
