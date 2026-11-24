package com.audiobook.audiobookplayer

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.audiobook.audiobookplayer.model.WordBookEntry
import com.audiobook.audiobookplayer.model.WordBookUploadRequest
import com.audiobook.audiobookplayer.model.SyncWordBookRequest
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单词本仓库：查词弹窗收藏的英文单词的增删查。**进程级单例**。
 *
 * ## 为什么存快照而不是只存词
 * 查词依赖 ECDICT（55MB 词典库）。若只存单词本身，用户卸载词典后单词本就变成一列
 * 没有释义的英文，收藏的意义就没了。所以这里存的是**词条快照**（音标 + 释义），
 * 卸载词典后仍可读；快照只在**再次收藏同一个词**时才刷新。
 *
 * ## 为什么用唯一索引而不是「先查再插」
 * `sw` 上有唯一索引（`idx_word_book_sw_unique`），收藏走 `CONFLICT_IGNORE`：
 * 连点收藏按钮、或同一个词从不同形态点进来，都只会有一条。手工「先查后插」在
 * 两个入口并发时会插出重复行 —— 唯一索引把这层竞态交给 SQLite 兜住。
 *
 * ## 跨端同步（v11 起）
 * 服务端 `/api/word-book` 是唯一真源，本地是读缓存：
 *  - [syncWithServer]：本地 `server_id IS NULL` 的词条作为「待上传」批量 POST
 *    `/api/word-book/sync`，服务端按 (userId, sw) 合并去重后返回全量列表 → 重建本地；
 *    无待上传时直接 GET 拉全量。网络失败 / 未登录一律保留本地、返回 false，
 *    与 [FavoriteRepository.syncWithServer] 完全同族。
 *  - [add]：仅写本地（server_id NULL），下次 sync 时上云。**故意不即时 POST**：
 *    用户可能在离线时点星、或连续点几十下；每次都上云会被限流打回、也让 UI
 *    等网络。批量 sync 更合适。
 *  - [remove]：本地删除成功 → 若该条有 server_id 则按 id 删远端；否则按 sw 删
 *    （老存量兜底）。远端失败保本地已删，下次 sync 服务端若还有该 sw 会重新
 *    拉回 —— 相当于"这本书又回来了"，用户能自愈。
 *
 * ## 分隔符约定
 *  - **本地存储**：`translations` 用字面量 `\n`（反斜杠+n，**2 个字符**）拼接，
 *    与 [DictionaryManager.parseTranslations] 的切分口径一致；
 *  - **wire 层**：服务端与 PC 端都用真换行符 LF（`0x0A`）分隔。
 *  [syncWithServer] 在 sync 边界做双向转换（`split(字面量)` → `join(LF)` 上行、
 *  `split(LF)` → `join(字面量)` 下行），保持与两端既有代码零改动兼容。
 *
 * ## 线程
 * 全部方法 suspend 且切到 [Dispatchers.IO]：SQLite 读写不放主线程
 * （读本身很快，但表在增长、且 WAL 下写要等锁，仍不该占主线程）。
 */
object WordBookRepository {

    private const val TAG = "WordBookRepo"

    /** translations 在库里用这个分隔符拼成单列（与 DictionaryManager 的字面量 `\n` 同口径） */
    private const val SEP = "\\n"

    /** wire 层用真换行符（服务端 UserWordBook.translations 约定） */
    private const val WIRE_SEP = "\u000A"

    @Volatile
    private var appContext: Context? = null

    /** 由 [AudioBookApplication] 早期调用（与 DictionaryManager.init 同一位置/理由）。 */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun db() = AppRepository.getInstance(
        appContext ?: error("WordBookRepository 未初始化")
    ).writableDatabase

    private val api by lazy { RetrofitClient.apiService }

    /** 兜底建表（与 DatabaseHelper.onCreate/ onUpgrade 同一真源）。 */
    private fun ensureTable(database: android.database.sqlite.SQLiteDatabase) {
        database.execSQL(DatabaseHelper.DDL_WORD_BOOK)
        database.execSQL(DatabaseHelper.DDL_WORD_BOOK_UNIQUE_INDEX)
    }

    /**
     * 该词（按归一化键）是否已在单词本里。用于收藏按钮的初始两态。
     *
     * 返回 false 而不是抛异常：查不到就当作「未收藏」，按钮显示未选中，
     * 用户点一下就是收藏 —— 一次失败的查询不该让按钮变成不可用状态。
     */
    suspend fun isSaved(sw: String): Boolean = withContext(Dispatchers.IO) {
        val key = sw.trim()
        if (key.isEmpty()) return@withContext false
        try {
            val database = db()
            ensureTable(database)
            database.query(
                DatabaseHelper.TABLE_WORD_BOOK,
                arrayOf("id"),
                "sw = ?",
                arrayOf(key),
                null, null, null
            ).use { cursor -> cursor.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "查询收藏状态失败: ${e.message}")
            false
        }
    }

    /**
     * 收藏一个词（幂等）。已收藏时刷新快照（音标/释义可能随词典版本变），不新增行。
     *
     * **只写本地**，`server_id` 留 NULL；下次 [syncWithServer] 会作为"待上传"批量上报。
     * 不在这里同步 POST 到服务端：用户可能连着点几十个词、或此刻离线，逐条上云
     * 既慢也容易被限流打回；批量 sync 才是这个功能的正确形态。
     *
     * ## 出处（v13）
     * 末尾 6 个出处参数都给默认值，兼容其它调用点 / 老链路（不传 = 该次收藏无出处）。
     * INSERT 与「重复收藏」的 UPDATE **都写这 6 列** —— 重复收藏 = 「记最近一次出处」，
     * 与服务端「最近一次带出处的上报刷新出处」同语义。仍**不动 server_id**（下方
     * 刷新快照的注释依旧成立）：已上云的词重复收藏后不该退化成待上传词重新上报。
     *
     * @param sw归一化键，用 [DictionaryManager.stripWord] 算，不要自己拼
     * @param lineNumber 行号 hint（会随切分粒度漂移，仅兜底）；sentenceText 定位第一判据
     * @return true = 本次调用后该词已在单词本里（无论是否新插入）
     */
    suspend fun add(
        sw: String,
        word: String,
        phonetic: String,
        translations: List<String>,
        bookName: String? = null,
        bookKey: String? = null,
        storeBookId: Long? = null,
        segmentNumber: Int? = null,
        lineNumber: Int? = null,
        sentenceText: String? = null
    ): Boolean =
        withContext(Dispatchers.IO) {
            val key = sw.trim()
            val display = word.trim()
            if (key.isEmpty() || display.isEmpty()) return@withContext false
            try {
                val database = db()
                ensureTable(database)
                val values = ContentValues().apply {
                    put("sw", key)
                    put("word", display)
                    put("phonetic", phonetic)
                    put("translations", translations.joinToString(SEP))
                    // 出处 6 列（全部可空）：重复收藏同样写 ⇒ 刷新为「最近一次」出处。
                    // ContentValues.put(列, null) 等价于 putNull，与下面 put("server_id", 可空)
                    // 同一写法，旧数据/无出处时落 NULL。
                    put("book_name", bookName)
                    put("book_key", bookKey)
                    put("store_book_id", storeBookId)
                    put("segment_number", segmentNumber)
                    put("line_number", lineNumber)
                    put("sentence_text", sentenceText)
                    // 刷新快照语义：**不动 server_id** —— 之前上传过的一次仍保持
                    // 与服务端对齐（下次 sync 不会重传）。释义漂了怎么办？走"删除重加"
                    // 语义（本期不做 PUT 编辑端点）。
                }
                // CONFLICT_IGNORE：命中唯一索引时静默跳过（已收藏），
                // 再单独 UPDATE 刷新快照 —— 这样「重复收藏」不会插出第二条，
                // 又能让旧快照跟上当前词典版本。
                val inserted = database.insertWithOnConflict(
                    DatabaseHelper.TABLE_WORD_BOOK, null, values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
                )
                if (inserted == -1L) {
                    database.update(
                        DatabaseHelper.TABLE_WORD_BOOK, values, "sw = ?", arrayOf(key)
                    )
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "收藏失败: ${e.message}")
                false
            }
        }

    /**
     * 取消收藏（按归一化键）。本地删除成功 → 尽力通知服务端。
     *
     * 服务端删除失败**不影响本地已删**：下次 [syncWithServer] 若服务端仍返回这个 sw，
     * 会通过 rebuildFromServer 把它拉回来 —— 相当于"这本书又回来了"，用户能自愈。
     * 这个取舍与 [FavoriteRepository.delete] 完全一致。
     */
    suspend fun remove(sw: String): Boolean = withContext(Dispatchers.IO) {
        val key = sw.trim()
        if (key.isEmpty()) return@withContext false
        try {
            val database = db()
            ensureTable(database)
            // 先取出 server_id（删除本地后就查不到了）
            val serverId = database.query(
                DatabaseHelper.TABLE_WORD_BOOK,
                arrayOf("server_id"),
                "sw = ?",
                arrayOf(key),
                null, null, null
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

            val deleted = database.delete(
                DatabaseHelper.TABLE_WORD_BOOK, "sw = ?", arrayOf(key)
            ) > 0
            if (deleted) deleteOnServer(key, serverId)
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "取消收藏失败: ${e.message}")
            false
        }
    }

    /** 单词列表（按收藏时间倒序，与收藏页/参考音频一致）。 */
    suspend fun listAll(): List<WordBookEntry> = withContext(Dispatchers.IO) {
        val out = mutableListOf<WordBookEntry>()
        try {
            val database = db()
            ensureTable(database)
            database.query(
                DatabaseHelper.TABLE_WORD_BOOK,
                arrayOf(
                    "id", "sw", "word", "phonetic", "translations", "created_at", "server_id",
                    "book_name", "book_key", "store_book_id", "segment_number", "line_number",
                    "sentence_text"
                ),
                null, null, null, null,
                "created_at DESC, id DESC"
            ).use { c ->
                while (c.moveToNext()) {
                    val rawTranslations = if (c.isNull(4)) "" else c.getString(4)
                    out.add(
                        WordBookEntry(
                            id = c.getLong(0),
                            sw = c.getString(1),
                            word = c.getString(2),
                            phonetic = if (c.isNull(3)) "" else c.getString(3),
                            translations = parseTranslations(rawTranslations),
                            createdAt = if (c.isNull(5)) null else c.getString(5),
                            serverId = if (c.isNull(6)) null else c.getLong(6),
                            bookName = if (c.isNull(7)) null else c.getString(7),
                            bookKey = if (c.isNull(8)) null else c.getString(8),
                            storeBookId = if (c.isNull(9)) null else c.getLong(9),
                            segmentNumber = if (c.isNull(10)) null else c.getInt(10),
                            lineNumber = if (c.isNull(11)) null else c.getInt(11),
                            sentenceText = if (c.isNull(12)) null else c.getString(12)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取单词本失败: ${e.message}")
        }
        out
    }

    /** 收藏总数（「我的」页入口右侧的计数）。 */
    suspend fun count(): Int = withContext(Dispatchers.IO) {
        try {
            val database = db()
            ensureTable(database)
            database.rawQuery(
                "SELECT COUNT(*) FROM ${DatabaseHelper.TABLE_WORD_BOOK}", null
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } catch (e: Exception) {
            Log.w(TAG, "统计收藏数失败: ${e.message}")
            0
        }
    }

    /**
     * 与云端的生词本同步（服务端为唯一真源，P1 #4 起改为**按项对账**）：
     *
     * 1) 收集本地 `server_id IS NULL` 的词条作为「待上传」；
     * 2) 有待上传 → `POST /api/word-book/sync` 一次性合并；否则 → `GET /api/word-book` 拉全量；
     * 3) **按项对账**（不再清空重建，见 [reconcileFromServer]）：
     *    - 服务端 items 按 sw upsert 到本地：命中→UPDATE 并回填 server_id，未命中→INSERT；
     *    - 本地已上云行 (server_id 非空) 若不在 items 中 → DELETE（服务端删词/账号切换镜像）；
     *    - 本地待上传行 (server_id 为空) 若不在 items 中 → **保留原样**，下次同步再试
     *      （旧版是全或无 guard：一项未确认就整体跳过重建；新版按项处理，不阻塞其它项）。
     *
     * 未登录 / 网络失败 / 结构异常一律保留本地、返回 false。
     * 与 [FavoriteRepository.syncWithServer] 逐条对齐（不同点只在身份键：
     * favorites = (bookName, segmentNumber)，wordBook = sw）。
     *
     * @return 是否成功完成一次同步（对账已执行；即使有 pendingMissing 也算成功）
     */
    suspend fun syncWithServer(): Boolean = withContext(Dispatchers.IO) {
        if (!AuthManager.isAuthenticated) return@withContext false
        try {
            val database = db()
            ensureTable(database)
            val local = listAll()
            val pending = local.filter { it.serverId == null }

            val serverItems: List<WordBookEntry>? = if (pending.isNotEmpty()) {
                val body = SyncWordBookRequest(pending.map { it.toUploadRequest() })
                val res = api.syncWordBook(body)
                if (res.isSuccessful) parseServerList(res.body()) else null
            } else {
                val res = api.getWordBook()
                if (res.isSuccessful) parseServerList(res.body()) else null
            }
            if (serverItems == null) {
                Log.w(TAG, "生词本同步：服务端返回不可用，保留本地")
                return@withContext false
            }

            // P1 #4：按项对账（取代旧的「全或无 guard + 清空重建」）。
            // 未确认的待上传词保留原样，其它词的 server_id 回填 / 服务端删除镜像照常进行。
            val stats = reconcileFromServer(database, serverItems)
            Log.d(
                TAG,
                "生词本同步完成：本地 ${local.size} → 服务端 ${serverItems.size}" +
                    "（上传 ${pending.size}；对账 +${stats.inserted}/~${stats.updated}" +
                    "/-${stats.deleted}；未回执待补传 ${stats.pendingMissing}）"
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "生词本同步失败（保留本地）: ${e.message}")
            false
        }
    }

    /**
     * 按项对账（P1 #4）。事务内执行，异常整体回滚。
     *
     * 身份 = ``sw``（归一化键，对齐服务端唯一约束）。算法与 PC 端
     * ``LocalDatabase.reconcile_words_from_server`` / [FavoriteRepository.reconcileFromServer]
     * 逐条对齐，不同点只在身份键与列名。
     *
     * translations 分隔符：入参 `items` 里 `WordBookEntry.translations` 是 wire 层 LF 拆好的
     * List<String>；落库时按本地口径 `SEP` (字面量 `\n`) 拼回单列。
     */
    private fun reconcileFromServer(
        database: android.database.sqlite.SQLiteDatabase,
        items: List<WordBookEntry>
    ): ReconcileStats {
        var inserted = 0
        var updated = 0
        var deleted = 0
        var pendingMissing = 0

        // 服务端 items 索引：sw → WordBookEntry；关键字段空的直接跳过
        val serverMap = HashMap<String, WordBookEntry>(items.size)
        for (it in items) {
            if (it.sw.isBlank() || it.word.isBlank()) continue
            serverMap[it.sw] = it
        }

        database.beginTransaction()
        try {
            // 1) upsert 服务端 items（命中→UPDATE、未命中→INSERT；同时回填 server_id）
            for ((sw, item) in serverMap) {
                // 服务端 toItem **总是** put 这 6 键（值可能为 null）。「本次是否带出处」
                // 用与服务端 WordBookItemRequest.hasSource() 相同的口径判定：书名或 bookKey 非空。
                val hasServerSource = !item.bookName.isNullOrBlank() || !item.bookKey.isNullOrBlank()
                val values = ContentValues().apply {
                    put("sw", sw)
                    put("word", item.word)
                    put("phonetic", item.phonetic)
                    put("translations", item.translations.joinToString(SEP))
                    put("server_id", item.serverId)
                    if (item.createdAt != null) put("created_at", item.createdAt)
                    // 出处 6 列**务必在这个白名单里**：漏列时从服务端拉回的新词条走
                    // INSERT 分支，6 列会落成默认 NULL —— 一同步出处就被抹掉。
                    //
                    // 策略 = 「服务端带出处才覆盖」（需求建议）：
                    //  · 服务端该项带出处 → 写 6 列，用服务端权威值刷新（最近一次上报）；
                    //  · 服务端该项出处全 null（老数据 / 旧客户端上报）→ **一列都不写**：
                    //    UPDATE 分支不动这 6 列 ⇒ 保留本地可能更新的出处；
                    //    INSERT 分支落默认 NULL ⇒ 本来就没有出处。
                    // 绝不拿服务端的 null 去抹掉本地已有的出处。
                    if (hasServerSource) {
                        put("book_name", item.bookName)
                        put("book_key", item.bookKey)
                        put("store_book_id", item.storeBookId)
                        put("segment_number", item.segmentNumber)
                        put("line_number", item.lineNumber)
                        put("sentence_text", item.sentenceText)
                    }
                }
                val existingId = database.query(
                    DatabaseHelper.TABLE_WORD_BOOK,
                    arrayOf("id"),
                    "sw = ?",
                    arrayOf(sw),
                    null, null, null
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

                if (existingId != null) {
                    database.update(
                        DatabaseHelper.TABLE_WORD_BOOK, values, "id = ?",
                        arrayOf(existingId.toString()))
                    updated++
                } else {
                    database.insert(DatabaseHelper.TABLE_WORD_BOOK, null, values)
                    inserted++
                }
            }

            // 2) 遍历本地 sw 非空的行，处理未被服务端覆盖的
            database.query(
                DatabaseHelper.TABLE_WORD_BOOK,
                arrayOf("id", "sw", "server_id"),
                "sw IS NOT NULL AND sw <> ''",
                null, null, null, null
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val sw = c.getString(1) ?: ""
                    val hasServerId = !c.isNull(2)
                    if (serverMap.containsKey(sw)) continue
                    if (hasServerId) {
                        database.delete(
                            DatabaseHelper.TABLE_WORD_BOOK, "id = ?", arrayOf(id.toString()))
                        deleted++
                    } else {
                        // 未确认的待上传词：保留原样，下次同步再试
                        pendingMissing++
                    }
                }
            }

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return ReconcileStats(inserted, updated, deleted, pendingMissing)
    }

    /**
     * 本地条目 → 上传请求体（translations 从字面量 `\n` 分隔转 LF 分隔）。
     *
     * 出处 6 字段一并带出：新收藏 / 重复刷新过的词条有出处 ⇒ 服务端按「最近一次」刷新；
     * 无出处的老词条 6 字段为 null ⇒ 全局 serializeNulls 会显式发 null，服务端
     * hasSource()=false ⇒ 保留原值，绝不抹掉（详见 [WordBookUploadRequest] 注释）。
     */
    private fun WordBookEntry.toUploadRequest(): WordBookUploadRequest =
        WordBookUploadRequest(
            sw = sw,
            word = word,
            phonetic = phonetic.ifBlank { null },
            translations = translations.joinToString(WIRE_SEP),
            bookName = bookName,
            bookKey = bookKey,
            storeBookId = storeBookId,
            segmentNumber = segmentNumber,
            lineNumber = lineNumber,
            sentenceText = sentenceText
        )

    /**
     * 解析 `{ success, words: [{id, sw, word, phonetic, translations(LF), createdAt, updatedAt,
     * bookName, bookKey, storeBookId, segmentNumber, lineNumber, sentenceText}] }`。
     * 结构不符 / `success != true` → 返回 null，调用方保留本地不重建。
     *
     * 出处 6 键服务端 `toItem` **总是 put**（值可能为 null，老数据即如此），本端必须解析
     * 并写回 WordBookEntry，否则从服务端拉回后本地出处会被当成「没有」。JSON number 经
     * Gson 的 `Map<String, Any>` 会变成 Double/Integer，故统一用 `Number?.toLong/toInt` 取值，
     * 缺失或 null 一律落成可空 null（UI 据此禁用跳转）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseServerList(body: Map<String, Any>?): List<WordBookEntry>? {
        if (body == null) return null
        if (body["success"] != true) return null
        val raw = body["words"] as? List<*> ?: return null
        val out = ArrayList<WordBookEntry>(raw.size)
        for (o in raw) {
            val m = o as? Map<String, Any> ?: continue
            val id = (m["id"] as? Number)?.toLong() ?: continue
            val sw = m["sw"] as? String ?: continue
            val word = m["word"] as? String ?: continue
            val phonetic = (m["phonetic"] as? String).orEmpty()
            val wireTranslations = (m["translations"] as? String).orEmpty()
            // 服务端 LF 分隔；本地按字面量 `\n` 存，先按 LF 切列表，
            // WordBookEntry.translations 是 List<String>，重建时再按 SEP 拼回本地。
            val translations = if (wireTranslations.isEmpty()) {
                emptyList()
            } else {
                wireTranslations.split(WIRE_SEP).filter { it.isNotEmpty() }
            }
            // 出处 6 键（全部可空）。注意 JSON number → Number 而非直接 as Long/Int。
            val bookName = m["bookName"] as? String
            val bookKey = m["bookKey"] as? String
            val storeBookId = (m["storeBookId"] as? Number)?.toLong()
            val segmentNumber = (m["segmentNumber"] as? Number)?.toInt()
            val lineNumber = (m["lineNumber"] as? Number)?.toInt()
            val sentenceText = m["sentenceText"] as? String
            out.add(
                WordBookEntry(
                    id = 0L, // 本地重建时自增，这里不关心
                    sw = sw,
                    word = word,
                    phonetic = phonetic,
                    translations = translations,
                    createdAt = m["createdAt"] as? String,
                    serverId = id,
                    bookName = bookName,
                    bookKey = bookKey,
                    storeBookId = storeBookId,
                    segmentNumber = segmentNumber,
                    lineNumber = lineNumber,
                    sentenceText = sentenceText
                )
            )
        }
        return out
    }

    /**
     * 删除服务端词条（best-effort：失败仅告警，下次同步会拉回重试）。
     * 有 serverId 走 id 端点；没有则走 by-sw（老存量兜底）。
     */
    private suspend fun deleteOnServer(sw: String, serverId: Long?) {
        try {
            if (serverId != null) {
                api.deleteWord(serverId)
            } else {
                api.deleteWordBySw(sw)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "删除云端词条失败（下次同步重试）sw=$sw id=$serverId: ${e.message}")
        }
    }

    /**
     * 把库里那列用 [SEP] 拼起来的释义拆回列表。
     *
     * 复用 [DictionaryManager.parseTranslations]（`internal`，测试已锁它对字面量 `\n`
     * 的切分口径），不要在这里另写一份 —— 两处口径漂移会让「弹窗里一行、
     * 单词本里挤成一坨」这种问题极难定位。
     */
    private fun parseTranslations(raw: String): List<String> =
        DictionaryManager.parseTranslations(raw)
}
