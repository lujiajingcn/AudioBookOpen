package com.audiobook.audiobookplayer

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.util.Log
import com.audiobook.audiobookplayer.model.Book
import com.audiobook.audiobookplayer.model.LineItem
import com.audiobook.audiobookplayer.model.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 删除影响面（关联任务/进行中任务/本地音频）。 */
data class DeleteImpact(val totalTasks: Int, val activeTasks: Int, val audioSegments: Int)

/**
 * 服务端解析结果的缓存载荷。
 *
 * @param rows 阅读页原文行（服务端 `rows`，保留行首缩进）
 * @param toc  目录（[TocEntry.paragraphIndex] 已是**行下标**，与服务端响应的语义一致）
 */
data class CachedEbookContent(val rows: List<String>, val toc: List<TocEntry>)

/**
 * 书架上某本书的**定位信息**（按书名查到的结果）。
 *
 * @param displayName 书架里的展示名（**可能与查进来的书名不同**，如带/不带扩展名）
 * @param filePath    本地电子书文件路径；空 = 这本书没有本地文件（纯书城在线读）
 * @param storeBookId 书城 id；-1 = 不是书城来的（本地导入）
 */
data class ShelfBookLocation(
    val displayName: String,
    val filePath: String,
    val storeBookId: Long
)

/** 删除影响面（带书籍引用），供确认弹窗展示后果。 */
data class DeleteImpactResult(val book: Book, val impact: DeleteImpact)

/** 某本书的本地音频占用。 */
data class BookAudioSize(val bookName: String, val bytes: Long)

/** 音频缓存总览：总占用 + 各书占用（按大小降序）。 */
data class AudioStorageStats(val totalBytes: Long, val books: List<BookAudioSize>)

/**
 * 一本「书 × 来源」的有声覆盖行（Phase3 跨端）。
 *
 * 来自本地缓存表 audio_coverage（服务端权威并集视图），供书架/阅读器画徽章：
 *  - [source] = "server" → 服务端有这份字节，可下载后播放；
 *  - [source] = "local"  → 某台设备本机合成，服务端**没有**字节，只能「看见」不能播。
 * [doneRanges] 是 1-based 闭区间压缩串（见 [CoverageCodec]），[doneCount] 是覆盖段数。
 */
data class AudioCoverageRow(
    val bookKey: String,
    val source: String,
    val storeBookId: Long?,
    val serverTaskId: Long?,
    val totalSegments: Int,
    val doneRanges: String,
    val doneCount: Int,
    val updatedAt: String
)

/**
 * 级联删除的结果：DB 事务已提交，剩下需要调用方在事务外继续处理的部分。
 *
 * @property taskRefs       被删任务的引用 (localTaskId, serverTaskId?)——
 *                          用于清理 TaskPollingManager 的轮询状态，并尽力取消服务端任务
 * @property skippedCascade 书架里还有同名书，跳过了级联清理（避免误删另一本书的数据）
 */
data class CascadeDeleteResult(
    val taskRefs: List<Pair<Long, Long?>>,
    val skippedCascade: Boolean = false
)

/**
 * 书籍/音频持久化层（A1 拆分）。
 *
 * 原 BookViewModel 内与 ebooks / audio_segments 表相关的建表、记录增删、书架查询、
 * 音频统计、删除影响面、清理音频、导入落库、自动登记等逻辑全部下沉到这里。
 * 本类不持有 LiveData，所有方法返回纯数据；刷新 UI 由 BookViewModel 负责。
 *
 * 另含「已删除书名黑名单」（进程内有效），用于阻止在途下载把音频片段插回、
 * 以及阻止 ensureEbookRegistered 把删除失败的书复活成幽灵书。
 */
class BookStorage(private val context: Application) {

    private val databaseHelper = AppRepository.getInstance(context).databaseHelper

    companion object {
        /** 已删除书名黑名单：进程内共享（所有 BookStorage 实例共用），阻止在途下载复活音频片段、阻止自动登记复活幽灵书。 */
        private val deletedBookNames = ConcurrentHashMap.newKeySet<String>()
        private const val TAG = "BookVM"

        /**
         * 书架顺序（排序子句）。
         *
         * sort_order DESC —— **值越大越靠前**：
         *  · 新导入的书取 MAX(sort_order)+1，于是排在最前，与旧版
         *    「按 created_at DESC（新书在前）」的观感一致；
         *  · 拖拽重排时按新位置重新赋值（越大越靠前）。
         * created_at DESC 兜底 —— 存量记录 sort_order 全为 0，靠导入时间维持原顺序，
         * 否则从旧版升级上来时书架顺序会整个乱掉。
         */
        const val SHELF_ORDER_BY = "sort_order DESC, created_at DESC"

        /** 应用内部表集合：删除书籍时绝不允许 DROP 其中任何一张 */
        private val INTERNAL_TABLES = setOf(
            DatabaseHelper.TABLE_EBOOKS,
            DatabaseHelper.TABLE_TASKS,
            DatabaseHelper.TABLE_AUDIO_SEGMENTS,
            DatabaseHelper.TABLE_FAVORITES,
            // 参考音频表不是书，漏在这里会被当成一本书列进书架
            DatabaseHelper.TABLE_REFERENCE_AUDIO
        )

        // 防止 SQL 注入：禁止能逃逸引号 / 终结语句的字符。
        // 表名使用时统一以 "name" 双引号包裹，所以只需拦住双引号、分号、单引号、
        // 反斜杠、括号与控制字符；中文等 Unicode 字符必须放行 —— 表名即书名，
        // 书架里大量中文书名在旧的 [a-zA-Z_][a-zA-Z0-9_]* 规则下全部校验失败，
        // 打不开书且界面无任何提示（表现为白屏）。
        private val TABLE_NAME_PATTERN = Regex("^[^\"';\\\\()\\[\\]\\x00-\\x1F]+$")

        // ---------- 音频片段批量写入 ----------
        //
        // 缓冲放在 companion object（进程级）并用 [pendingLock] 加锁，因为**本类不是单例**：
        // BookViewModel / PlaybackService / 各 Fragment 各自持有实例。
        // 若把缓冲放到实例字段，同一本书的下载会被拆成多个互不可见的缓冲，批量窗口形同虚设。

        /** 累积到这么多段就提交一次 */
        private const val FLUSH_SEGMENT_COUNT = 24

        /** 或者距本批首次入队超过这么久就提交，避免最后几段迟迟凑不满而长时间不落盘 */
        private const val FLUSH_INTERVAL_MS = 2_000L

        /** 单条待写入的音频片段 */
        private class PendingSegment(
            val bookName: String,
            val serverSegmentId: Long?,
            val segmentNumber: Int,
            val textContent: String,
            val audioData: ByteArray,
            /** 音频来源：'server'=服务端有这份字节（可跨端下载播放）；'local'=本机合成（服务端无字节）。见 Phase3 覆盖图。 */
            val source: String = "local"
        )

        private val pendingSegments = mutableListOf<PendingSegment>()
        private val pendingLock = Any()

        /** 本批首次入队时刻（elapsedRealtime），提交或清空时归零 */
        private var firstPendingAtMs = 0L

        /** 因写入失败而被丢弃的片段数；由下载收尾处取走并提示用户 */
        private var droppedSegmentCount = 0

        /**
         * 校验书籍动态内容表名，返回可安全拼进 SQL 的原名。
         *
         * @throws IllegalArgumentException 表名为空/纯空白，或含可逃逸引号的字符
         */
        @Throws(IllegalArgumentException::class)
        fun validateTableName(name: String): String {
            // 空/纯空白表名会拼出 `""` 这样的无效标识符，单独拦截以便提示更明确
            if (name.isBlank()) {
                throw IllegalArgumentException("书籍标识为空")
            }
            if (!TABLE_NAME_PATTERN.matches(name)) {
                throw IllegalArgumentException("非法的书籍标识: $name")
            }
            return name
        }
    }

    fun markBookDeleted(bookName: String) {
        deletedBookNames.add(bookName)
    }

    /**
     * 解除某本书的「已删除」标记。
     *
     * 黑名单只应在「删除之后、进程结束之前」这段窗口内拦截在途回调。用户重新导入
     * 同名书是一次明确的复活意图，必须撤销标记 —— 否则
     * saveAudioSegments / saveAudioSegmentStreaming 的开头都会命中黑名单并静默丢弃，
     * 表现为「导入成功、合成也成功，但这本书永远没有声音」。
     */
    fun unmarkBookDeleted(bookName: String) {
        deletedBookNames.remove(bookName)
    }

    fun isBookDeleted(bookName: String): Boolean = bookName in deletedBookNames

    // ---------------- 建表 ----------------

    suspend fun createEbookTableIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase
            db.execSQL(DatabaseHelper.DDL_EBOOKS)
            Log.d(TAG, "Ebook table created/verified")
        } catch (e: Exception) {
            Log.e(TAG, "创建 ebook 表失败: ${e.message}", e)
        }
    }

    suspend fun createAudioSegmentsTableIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase
            db.execSQL(DatabaseHelper.DDL_AUDIO_SEGMENTS)

            // 唯一索引（与 DatabaseHelper.onCreate 同一真源）：INSERT OR REPLACE 依赖
            // (book_name, segment_number) 唯一约束才能真正 upsert。
            db.execSQL(DatabaseHelper.DDL_AUDIO_SEGMENTS_UNIQUE_INDEX)

            Log.d(TAG, "audio_segments table created/verified")
        } catch (e: Exception) {
            Log.e(TAG, "创建 audio_segments 表失败: ${e.message}", e)
        }
    }

    // ---------------- 书架/书籍查询 ----------------

    /** 批量查询 audio_segments 表中有音频的所有书名（避免 N+1）。 */
    suspend fun getBooksWithAudioSet(): Set<String> = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.readableDatabase
            db.rawQuery("SELECT DISTINCT book_name FROM ${DatabaseHelper.TABLE_AUDIO_SEGMENTS}", null).use { cursor ->
                val set = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    set.add(cursor.getString(0))
                }
                set
            }
        } catch (e: Exception) {
            Log.e(TAG, "批量查询音频书名失败: ${e.message}")
            emptySet()
        }
    }

    /** 查询 audio_segments 表是否有该书音频。 */
    suspend fun checkHasAudio(bookName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.readableDatabase
            val hasAudio = db.rawQuery(
                "SELECT COUNT(*) FROM ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} WHERE book_name = ?",
                arrayOf(bookName)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) > 0 else false }

            if (hasAudio) {
                Log.d(TAG, "✅ audio_segments 中有 ${bookName} 的音频数据")
            } else {
                Log.d(TAG, "❌ audio_segments 中没有 ${bookName} 的音频数据")
            }
            hasAudio
        } catch (e: Exception) {
            Log.e(TAG, "检查音频失败: ${e.message}")
            false
        }
    }

    /** 全部电子书（用于书架），带 hasAudio 标记。 */
    suspend fun getEbooks(): List<Book> = withContext(Dispatchers.IO) {
        val booksWithAudio = getBooksWithAudioSet()
        try {
            val db = databaseHelper.readableDatabase
            db.query(
                DatabaseHelper.TABLE_EBOOKS,
                arrayOf("id", "display_name", "file_path", "last_read_at", "category", "sort_order", "store_book_id"),
                null, null, null, null,
                // 书架顺序：sort_order DESC（值越大越靠前，用户拖出来的顺序）
                // 兜底 created_at DESC（存量记录 sort_order 全是 0，靠导入时间保持
                // 「新书在前」的旧观感；否则升级后书架顺序会乱掉）。
                "$SHELF_ORDER_BY"
            ).use { cursor ->
                val result = mutableListOf<Book>()
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(0)
                    val displayName = cursor.getString(1)
                    val filePath = cursor.getString(2)
                    val lastReadAt = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    val category = cursor.getString(4) ?: ""
                    val sortOrder = if (cursor.isNull(5)) 0 else cursor.getInt(5)
                    val storeBookId = if (cursor.isNull(6)) -1L else cursor.getLong(6)
                    val hasAudio = displayName in booksWithAudio
                    result.add(
                        Book(
                            id = id,
                            tableName = displayName,
                            displayName = displayName,
                            filePath = filePath,
                            hasAudio = hasAudio,
                            lastReadAt = lastReadAt,
                            category = category,
                            sortOrder = sortOrder,
                            storeBookId = storeBookId
                        )
                    )
                }
                // 逐本打印会因为书架刷新（每次进书架、每次下载完成）重复刷出上百条，
                // 淹没真正的错误日志，改成循环外一条汇总。
                Log.d(TAG, "📖 书架共 ${result.size} 本，其中 ${result.count { it.hasAudio }} 本有音频")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load ebooks failed: ${e.message}")
            emptyList()
        }
    }

    /** 选择列表用电子书（id/display_name/file_path）。 */
    suspend fun getEbooksForSelection(): List<Book> = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.readableDatabase
            db.query(
                DatabaseHelper.TABLE_EBOOKS,
                arrayOf("id", "display_name", "file_path", "last_read_at", "category", "sort_order"),
                null, null, null, null,
                // 与书架同序：用户在书架拖出来的顺序，选书页也照这个顺序显示，
                // 两处不一致会让用户以为拖了没生效。
                "$SHELF_ORDER_BY"
            ).use { cursor ->
                val list = mutableListOf<Book>()
                while (cursor.moveToNext()) {
                    list.add(
                        Book(
                            id = cursor.getInt(0),
                            tableName = cursor.getString(1),
                            displayName = cursor.getString(1),
                            filePath = cursor.getString(2),
                            lastReadAt = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                            category = cursor.getString(4) ?: "",
                            sortOrder = if (cursor.isNull(5)) 0 else cursor.getInt(5)
                        )
                    )
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load ebooks for selection failed: ${e.message}")
            emptyList()
        }
    }

    /** 所有书籍表名（排除系统表与应用内部表），供「书」列表展示。 */
    suspend fun getBookTables(): List<Book> = withContext(Dispatchers.IO) {
        // 复用 [INTERNAL_TABLES]，不要另抄一份清单：两处一旦漂移，漏在
        // getBookTables 里的内部表就会被当成一本书列进书架（如 reference_audio）
        val internalTables = INTERNAL_TABLES.toList()
        val notInClause = internalTables.joinToString(", ") { "?" }
        try {
            val db = databaseHelper.readableDatabase
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT IN ($notInClause) ORDER BY name",
                internalTables.toTypedArray()
            ).use { cursor ->
                val list = mutableListOf<Book>()
                while (cursor.moveToNext()) {
                    list.add(Book(tableName = cursor.getString(0), displayName = cursor.getString(0)))
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load books failed: ${e.message}")
            emptyList()
        }
    }

    // ---------------- 书籍动态内容表（表名 = 书名） ----------------

    /**
     * 读取某本书的动态内容表（按 line_number 升序）。
     *
     * @param tableName 已通过 [validateTableName] 校验的表名
     * @return 行列表；**表不存在或读取失败时返回 null**。
     *         书籍动态表缺失而入口记录仍在时（建表失败/被清理，如通知栏点击打开），
     *         直接 query 会抛 SQLiteException 导致崩溃 —— 这里降级为 null，
     *         由调用方决定是展示空内容页还是错误提示。
     */
    suspend fun loadBookLines(tableName: String): List<LineItem>? = withContext(Dispatchers.IO) {
        val safeName = try {
            validateTableName(tableName)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "${e.message}")
            return@withContext null
        }
        try {
            val db = databaseHelper.readableDatabase
            if (!tableExists(db, safeName)) {
                Log.w(TAG, "书籍表不存在: $safeName")
                return@withContext null
            }
            db.query(
                "\"$safeName\"",  // 转义表名（已通过正则校验）
                arrayOf("id", "line_number", "text"),
                null, null, null, null,
                "line_number ASC"
            ).use { cursor ->
                val list = mutableListOf<LineItem>()
                while (cursor.moveToNext()) {
                    list.add(LineItem(cursor.getInt(0), cursor.getInt(1), cursor.getString(2) ?: ""))
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开书籍失败: ${e.message}", e)
            null
        }
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(name)
        ).use { c -> c.moveToFirst() }

    // ---------------- 导入落库 ----------------

    /** 同文件路径是否已存在电子书记录（去重用）。 */
    suspend fun existsByFilePath(filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            databaseHelper.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM ${DatabaseHelper.TABLE_EBOOKS} WHERE file_path = ?",
                arrayOf(filePath)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) > 0 else false }
        } catch (e: Exception) {
            Log.e(TAG, "查重失败: ${e.message}")
            false
        }
    }

    /**
     * 写入一条电子书记录（不做刷新，由调用方决定是否 loadEbooks）。
     *
     * @param storeBookId 书城书籍 id：**> 0 表示这本书来自书城**，打开阅读时可直接
     *        `GET api/store/book/{id}/content` 取正文（零上传）；-1 表示手工导入。
     */
    suspend fun insertEbookRecord(
        displayName: String,
        filePath: String,
        category: String = "",
        storeBookId: Long = -1L
    ) = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase
            // sort_order 取 MAX+1 ⇒ 越大越靠前 ⇒ 新导入的书排在最前，
            // 与旧版「按 created_at DESC（新书在前）」的观感一致。
            // 不写这一列的话新书只能拿 DEFAULT 0，会插到已拖过序的老书后面去，
            // 表现为「刚导入的书跑到了列表中间」。
            val nextOrder = db.rawQuery(
                "SELECT IFNULL(MAX(sort_order), 0) + 1 FROM ${DatabaseHelper.TABLE_EBOOKS}",
                null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 1 }
            db.execSQL(
                "INSERT INTO ${DatabaseHelper.TABLE_EBOOKS} " +
                    "(display_name, file_path, category, sort_order, store_book_id) VALUES (?, ?, ?, ?, ?)",
                arrayOf(displayName, filePath, category, nextOrder, storeBookId)
            )
            Log.d(
                TAG,
                "✅ 电子书记录写入成功: $displayName -> $filePath " +
                    "(category=$category, order=$nextOrder, storeBookId=$storeBookId)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ 插入电子书记录失败: ${e.message}")
        }
    }

    /**
     * 反查某本本地电子书的书城 id。
     *
     * @return 书城书籍 id；手工导入的书、或查不到记录时返回 **-1**
     *         （调用方据此退回「整本上传给服务端解析」的旧路径）。
     */
    suspend fun findStoreBookIdByFilePath(filePath: String): Long = withContext(Dispatchers.IO) {
        try {
            databaseHelper.readableDatabase.query(
                DatabaseHelper.TABLE_EBOOKS,
                arrayOf("store_book_id"),
                "file_path = ?",
                arrayOf(filePath),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            }
        } catch (e: Exception) {
            Log.e(TAG, "反查书城 id 失败: ${e.message}")
            -1L
        }
    }

    /**
     * 回填某本本地电子书的书城 id。
     *
     * 只写「还没有 id」的条目（`store_book_id <= 0`），不覆盖已有值 —— 回填发生在
     * 书城列表加载时，可能是很久以后的一次刷新，不该改动已经确定下来的绑定。
     *
     * @return 是否真的更新了一行
     */
    suspend fun setStoreBookIdIfMissing(filePath: String, storeBookId: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (storeBookId <= 0) return@withContext false
            try {
                val db = databaseHelper.writableDatabase
                val values = android.content.ContentValues().apply {
                    put("store_book_id", storeBookId)
                }
                val updated = db.update(
                    DatabaseHelper.TABLE_EBOOKS,
                    values,
                    "file_path = ? AND IFNULL(store_book_id, -1) <= 0",
                    arrayOf(filePath)
                )
                if (updated > 0) {
                    Log.d(TAG, "🔗 回填书城 id: storeBookId=$storeBookId -> $filePath")
                }
                updated > 0
            } catch (e: Exception) {
                Log.e(TAG, "回填书城 id 失败: ${e.message}")
                false
            }
        }

    /**
     * 本地已有的「书城来源」书籍 id 集合（`store_book_id > 0`，无论是否已下载到本地）。
     * 供书架跨端同步判定「服务端在架的书本机是否已有」，避免逐本查询。
     */
    suspend fun getExistingStoreBookIdSet(): Set<Long> = withContext(Dispatchers.IO) {
        try {
            databaseHelper.readableDatabase.rawQuery(
                "SELECT store_book_id FROM ${DatabaseHelper.TABLE_EBOOKS} " +
                    "WHERE IFNULL(store_book_id, -1) > 0",
                null
            ).use { c ->
                val set = mutableSetOf<Long>()
                while (c.moveToNext()) set.add(c.getLong(0))
                set
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询本地书城 id 集合失败: ${e.message}")
            emptySet()
        }
    }

    /**
     * 为服务端在架、但本机既没下过、也没有占位的书城书建一条**在线占位条目**
     * （ebooks 行：`file_path = ''`、`store_book_id = <id>`、分类「书城」）。
     * 点开走在线正文（`GET api/store/book/{id}/content`），与书城「在线阅读」同一条链路。
     *
     * 只在「本机完全没有这个 store_book_id」时插入；已下载或有占位则不动，
     * 以免覆盖用户已改的书名/已存在的本地文件绑定。返回是否真的插入了一行。
     */
    suspend fun insertStoreShelfPlaceholder(
        storeBookId: Long,
        title: String,
        hasAudio: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (storeBookId <= 0) return@withContext false
        try {
            val db = databaseHelper.writableDatabase
            val exists = db.query(
                DatabaseHelper.TABLE_EBOOKS,
                arrayOf("id"),
                "store_book_id = ?",
                arrayOf(storeBookId.toString()),
                null, null, null
            ).use { it.moveToFirst() }
            if (exists) return@withContext false
            val displayName = title.ifBlank { "未命名" }
            val nextOrder = db.rawQuery(
                "SELECT IFNULL(MAX(sort_order), 0) + 1 FROM ${DatabaseHelper.TABLE_EBOOKS}",
                null
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 1 }
            // 占位条目：空 file_path ⇒ 书架 hasEbookFile=false，点击走在线读；
            // hasAudio 由服务端元信息给出，供书架▶图标显示。
            db.execSQL(
                "INSERT INTO ${DatabaseHelper.TABLE_EBOOKS} " +
                    "(display_name, file_path, category, sort_order, store_book_id) VALUES (?, ?, ?, ?, ?)",
                arrayOf(displayName, "", "书城", nextOrder, storeBookId)
            )
            Log.d(TAG, "✅ 写入书城在架占位条目: $displayName (storeBookId=$storeBookId, hasAudio=$hasAudio)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 写入书城占位条目失败: storeBookId=$storeBookId - ${e.message}")
            false
        }
    }

    /**
     * 删除服务端的**在线占位条目**（[insertStoreShelfPlaceholder] 建的那批）：
     * 本机没下载过（`file_path = ''`）、`store_book_id > 0` 且已不在服务端书架集合中的行。
     *
     * **只清理空 file_path 的占位行**，绝不碰已下载到本地（有文件、可能有音频）的书 ——
     * 「在别的设备移出书架」不应把本机的付费下载内容一并删掉。返回删除条数。
     */
    suspend fun pruneServerShelfPlaceholders(serverIds: Set<Long>): Int = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase
            val placeholders = db.rawQuery(
                "SELECT id, store_book_id FROM ${DatabaseHelper.TABLE_EBOOKS} " +
                    "WHERE IFNULL(store_book_id, -1) > 0 AND (file_path IS NULL OR file_path = '')",
                null
            ).use { c ->
                val out = mutableListOf<Pair<Long, Long>>() // (rowId, storeBookId)
                while (c.moveToNext()) out.add(c.getLong(0) to c.getLong(1))
                out
            }
            var removed = 0
            db.beginTransaction()
            try {
                for ((rowId, storeBookId) in placeholders) {
                    if (storeBookId in serverIds) continue
                    db.delete(DatabaseHelper.TABLE_EBOOKS, "id = ?", arrayOf(rowId.toString()))
                    removed++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (removed > 0) Log.d(TAG, "🧹 清理失效书城占位条目 $removed 本（服务端已移出）")
            removed
        } catch (e: Exception) {
            Log.e(TAG, "清理书城占位条目失败: ${e.message}")
            0
        }
    }

    // ================= Phase3 有声覆盖图（跨端元数据，不传字节） =================

    /**
     * 聚合**本机**当前有声覆盖（供上报到服务端做并集）。镜像 PC `local_audio_coverage()`。
     *
     * 每行 = 一个 (bookKey, source) 的完成段区间压缩串：
     *  - bookKey 三端一致，优先 s:{store_book_id} > t:{server_task_id} > n:{trim(book_name)}
     *    （store_book_id 取自 ebooks 同名行、server_task_id 取自 tasks 同名行，见 [CoverageCodec.bookKey]）；
     *  - source 直接读 audio_segments.source 列（v12 新增）：'server' / 'local'；缺列或异常值按 'local'。
     *
     * 返回项形如 {bookKey, source, storeBookId?, serverTaskId?, totalSegments, doneRanges, doneCount}，
     * 与 POST /api/audio-coverage/sync 的 items 结构对齐（额外字段服务端忽略即可）。
     */
    suspend fun localAudioCoverage(): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.readableDatabase

            // book_name -> store_book_id（书城来源，>0 才用）
            val storeByName = HashMap<String, Long>()
            db.rawQuery(
                "SELECT display_name, store_book_id FROM ${DatabaseHelper.TABLE_EBOOKS} " +
                    "WHERE IFNULL(store_book_id, -1) > 0",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    storeByName[name.trim()] = c.getLong(1)
                }
            }

            // book_name -> (server_task_id, total_segments)；同名多任务保留首个正 id、取最大 total
            val stidByName = HashMap<String, Long>()
            val totalByName = HashMap<String, Int>()
            db.rawQuery(
                "SELECT book_name, server_task_id, total_segments FROM ${DatabaseHelper.TABLE_TASKS}",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim() ?: continue
                    val stid = if (c.isNull(1)) 0L else c.getLong(1)
                    if (stid > 0 && !stidByName.containsKey(name)) stidByName[name] = stid
                    val tot = c.getInt(2)
                    if (tot > (totalByName[name] ?: 0)) totalByName[name] = tot
                }
            }

            // 聚合音频段：按 (bookKey, source) 收集段号，并记住该行元信息
            class Agg {
                val nums = sortedSetOf<Int>()
                var storeBookId: Long? = null
                var serverTaskId: Long? = null
                var totalSegments: Int = 0
            }
            val agg = HashMap<Pair<String, String>, Agg>()

            db.rawQuery(
                "SELECT book_name, segment_number, source FROM ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} " +
                    "WHERE status = 'completed'",
                null
            ).use { c ->
                val srcIdx = c.getColumnIndex("source") // 老库升级前的极罕见竞态：列不存在则 -1
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim() ?: continue
                    val seg = c.getInt(1)
                    if (seg < 1) continue
                    val src = if (srcIdx >= 0 && !c.isNull(srcIdx)) c.getString(srcIdx) else null
                    val source = if (src == "server") "server" else "local"
                    val sbid = storeByName[name]?.takeIf { it > 0 }
                    val stid = stidByName[name]?.takeIf { it > 0 }
                    val bk = CoverageCodec.bookKey(sbid, stid, name)
                    if (bk.isEmpty()) continue
                    val a = agg.getOrPut(bk to source) { Agg() }
                    a.nums.add(seg)
                    if (a.storeBookId == null) a.storeBookId = sbid
                    if (a.serverTaskId == null) a.serverTaskId = stid
                    if (a.totalSegments == 0) a.totalSegments = totalByName[name] ?: 0
                }
            }

            val items = ArrayList<Map<String, Any?>>(agg.size)
            for ((key, a) in agg) {
                val (bk, source) = key
                val ranges = CoverageCodec.fromNumbers(a.nums)
                val m = LinkedHashMap<String, Any?>()
                m["bookKey"] = bk
                m["source"] = source
                a.storeBookId?.let { m["storeBookId"] = it }
                a.serverTaskId?.let { m["serverTaskId"] = it }
                m["totalSegments"] = a.totalSegments
                m["doneRanges"] = ranges
                m["doneCount"] = a.nums.size
                items.add(m)
            }
            items
        } catch (e: Exception) {
            Log.e(TAG, "聚合本机有声覆盖图失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 用服务端返回的**全量并集覆盖图**重建本地缓存 audio_coverage（镜像 PC rebuild_audio_coverage_from_server）。
     *
     * 覆盖图是纯感知层、绝不删本机任何音频，所以这里**直接镜像、无护栏**（不同于 favorites/word_book）。
     * 一次全删全插，事务内完成。[items] 每项形如
     * {bookKey, source, storeBookId?, serverTaskId?, totalSegments, doneRanges, doneCount?, hasAudio?, updatedAt?}。
     * doneCount 缺失时用 [CoverageCodec.count] 兜底。
     *
     * @return 写入的行数
     */
    suspend fun rebuildAudioCoverageFromServer(items: List<Map<String, Any?>>): Int =
        withContext(Dispatchers.IO) {
            try {
                val db = databaseHelper.writableDatabase
                db.beginTransaction()
                var n = 0
                try {
                    db.delete(DatabaseHelper.TABLE_AUDIO_COVERAGE, null, null)
                    for (item in items) {
                        val bk = (item["bookKey"] as? String)?.trim().orEmpty()
                        val source = (item["source"] as? String)?.takeIf { it == "server" || it == "local" }
                            ?: "local"
                        if (bk.isEmpty()) continue
                        val doneRanges = (item["doneRanges"] as? String) ?: ""
                        val sbid = (item["storeBookId"] as? Number)?.toLong()
                        val stid = (item["serverTaskId"] as? Number)?.toLong()
                        val total = (item["totalSegments"] as? Number)?.toInt() ?: 0
                        val dc = (item["doneCount"] as? Number)?.toInt()
                            ?: CoverageCodec.count(doneRanges)
                        val updatedAt = (item["updatedAt"] as? String) ?: ""
                        db.execSQL(
                            "INSERT OR REPLACE INTO ${DatabaseHelper.TABLE_AUDIO_COVERAGE} " +
                                "(book_key, source, store_book_id, server_task_id, total_segments, " +
                                "done_ranges, done_count, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf(bk, source, sbid, stid, total, doneRanges, dc, updatedAt)
                        )
                        n++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                Log.d(TAG, "🗂 重建有声覆盖图缓存：$n 行")
                n
            } catch (e: Exception) {
                Log.e(TAG, "重建有声覆盖图缓存失败: ${e.message}")
                0
            }
        }

    /** 读取全部覆盖图缓存行（供书架徽章一次性计算，避免逐行查询）。 */
    suspend fun getAllAudioCoverage(): List<AudioCoverageRow> = withContext(Dispatchers.IO) {
        try {
            databaseHelper.readableDatabase.rawQuery(
                "SELECT book_key, source, store_book_id, server_task_id, total_segments, " +
                    "done_ranges, done_count, updated_at FROM ${DatabaseHelper.TABLE_AUDIO_COVERAGE} " +
                    "ORDER BY updated_at DESC",
                null
            ).use { c ->
                val out = ArrayList<AudioCoverageRow>(c.count)
                while (c.moveToNext()) {
                    val ranges = c.getString(5) ?: ""
                    val dc = if (c.getInt(6) > 0) c.getInt(6) else CoverageCodec.count(ranges)
                    out.add(
                        AudioCoverageRow(
                            bookKey = c.getString(0),
                            source = c.getString(1),
                            storeBookId = if (c.isNull(2)) null else c.getLong(2),
                            serverTaskId = if (c.isNull(3)) null else c.getLong(3),
                            totalSegments = c.getInt(4),
                            doneRanges = ranges,
                            doneCount = dc,
                            updatedAt = c.getString(7) ?: ""
                        )
                    )
                }
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取有声覆盖图缓存失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 计算 bookKey 集合：本机 audio_segments 里**确有字节**（可立即播放）的那些书。
     * 覆盖图徽章的「本机有声」档判定靠它（本机既下过也合成过都算，无论 source）。
     */
    suspend fun getLocalPlayableBookKeySet(): Set<String> = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.readableDatabase
            val storeByName = HashMap<String, Long>()
            db.rawQuery(
                "SELECT display_name, store_book_id FROM ${DatabaseHelper.TABLE_EBOOKS} " +
                    "WHERE IFNULL(store_book_id, -1) > 0",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    storeByName[name.trim()] = c.getLong(1)
                }
            }
            val stidByName = HashMap<String, Long>()
            db.rawQuery(
                "SELECT book_name, server_task_id FROM ${DatabaseHelper.TABLE_TASKS} " +
                    "WHERE IFNULL(server_task_id, 0) > 0",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim() ?: continue
                    if (!stidByName.containsKey(name)) stidByName[name] = c.getLong(1)
                }
            }
            val set = mutableSetOf<String>()
            db.rawQuery(
                "SELECT DISTINCT book_name FROM ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} " +
                    "WHERE status = 'completed'",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim() ?: continue
                    val bk = CoverageCodec.bookKey(storeByName[name], stidByName[name], name)
                    if (bk.isNotEmpty()) set.add(bk)
                }
            }
            set
        } catch (e: Exception) {
            Log.e(TAG, "计算本机可播放覆盖键失败: ${e.message}")
            emptySet()
        }
    }

    /**
     * ebooks.id → bookKey 映射（覆盖图徽章按书架行 id 落地用，避免在 UI 层重算 bookKey）。
     *
     * bookKey 三端一致口径（见 [CoverageCodec.bookKey]）：优先 s:{store_book_id} >
     * t:{同名任务的 server_task_id} > n:{trim(display_name)}；三者皆空则不给键（该 id 不入图）。
     */
    suspend fun getShelfBookKeyMap(): Map<Long, String> = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.readableDatabase
            // book_name(trim) -> 首个正 server_task_id
            val stidByName = HashMap<String, Long>()
            db.rawQuery(
                "SELECT book_name, server_task_id FROM ${DatabaseHelper.TABLE_TASKS} " +
                    "WHERE IFNULL(server_task_id, 0) > 0",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim() ?: continue
                    if (!stidByName.containsKey(name)) stidByName[name] = c.getLong(1)
                }
            }
            val out = HashMap<Long, String>()
            db.rawQuery(
                "SELECT id, display_name, store_book_id FROM ${DatabaseHelper.TABLE_EBOOKS}",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = (c.getString(1) ?: "").trim()
                    val sbid = if (c.isNull(2)) -1L else c.getLong(2)
                    val bk = CoverageCodec.bookKey(
                        sbid.takeIf { it > 0 },
                        stidByName[name],
                        name
                    )
                    if (bk.isNotEmpty()) out[id] = bk
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "计算书架 bookKey 映射失败: ${e.message}")
            emptyMap()
        }
    }

    /**
     * **仅删 ebooks 行**，不清理任务/收藏/音频/内容缓存/动态正文表。
     * 用于书架「移出书城占位条目」：这本书的正文与音频都在服务端，收藏也在服务端
     * 兜底，本机只是入口。删掉行就等于「本机不再显示这本书」，别的数据一概不动。
     *
     * 只按主键 id 精确定位，不做任何同名判断——书架里可能存在同名本地书与占位书并存，
     * 用 display_name 匹配有误删风险。调用方需自行保证传入的 id 是占位条目
     * （[com.audiobook.audiobookplayer.model.Book.storeBookId] > 0 且 filePath 为空）。
     *
     * @return 实际删除的行数（0 或 1）
     */
    suspend fun removeShelfPlaceholderOnly(bookId: Int): Int = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase
            val n = db.delete(
                DatabaseHelper.TABLE_EBOOKS,
                "id = ?",
                arrayOf(bookId.toString())
            )
            if (n > 0) Log.d(TAG, "🧹 移出书架占位条目: ebookId=$bookId")
            n
        } catch (e: Exception) {
            Log.e(TAG, "❌ 移出书架占位条目失败: ebookId=$bookId - ${e.message}")
            0
        }
    }

    /**
     * 批量写回书架顺序（拖拽结束后调用）。
     *
     * @param orders 书籍 id → 新的顺序值（**值越大越靠前**）。
     *   传入的是「从上到下」的完整顺序时，分配方式见 [assignShelfSortOrders]。
     *
     * 用事务一次写完：拖拽后要更新的是**整本书架**（顺序是相对值），
     * 逐条 UPDATE 会在拖动频繁时产生大量写放大，且中途崩溃会留下半套顺序。
     */
    suspend fun updateEbookOrders(orders: Map<Int, Int>) = withContext(Dispatchers.IO) {
        if (orders.isEmpty()) return@withContext
        try {
            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                for ((id, order) in orders) {
                    db.execSQL(
                        "UPDATE ${DatabaseHelper.TABLE_EBOOKS} SET sort_order = ? WHERE id = ?",
                        arrayOf(order, id)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Log.d(TAG, "✅ 书架顺序已写回：${orders.size} 本")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 写回书架顺序失败: ${e.message}")
        }
    }

    /** 更新某本书的最近阅读时间（打开阅读时调用）。 */
    suspend fun updateLastReadAt(displayName: String) = withContext(Dispatchers.IO) {
        try {
            databaseHelper.writableDatabase.execSQL(
                "UPDATE ${DatabaseHelper.TABLE_EBOOKS} SET last_read_at = ? WHERE display_name = ?",
                arrayOf(System.currentTimeMillis().toString(), displayName)
            )
        } catch (e: Exception) {
            Log.e(TAG, "更新最近阅读时间失败: ${e.message}")
        }
    }

    /** 更新某本书的分类标签（用户在书架手动改分类时调用）。 */
    suspend fun updateCategory(displayName: String, category: String) = withContext(Dispatchers.IO) {
        try {
            databaseHelper.writableDatabase.execSQL(
                "UPDATE ${DatabaseHelper.TABLE_EBOOKS} SET category = ? WHERE display_name = ?",
                arrayOf(category, displayName)
            )
        } catch (e: Exception) {
            Log.e(TAG, "更新书籍分类失败: ${e.message}")
        }
    }

    /**
     * 应用服务端权威「每书元数据」：按 bookKey 匹配本机 ebooks 行，
     * **只** UPDATE `sort_order` 与 `last_read_at` 两列。
     *
     * 与 [getShelfBookKeyMap] 用同一套 bookKey 口径（借 tasks 表按书名反查 serverTaskId），
     * 保证三端对同一本书算出同一个 key。响应里本机没有的 bookKey 直接跳过 ——
     * ⛔ 绝不 INSERT/DELETE 任何书籍行，⛔ 绝不动 `category`（跨端分类语义不同，同步会互相污染）。
     *
     * 纯静默写库：不发 LiveData/notify、不回调上层，避免触发 loadEbooks → 再次上报的循环；
     * 影子快照由 [com.audiobook.audiobookplayer.UserSettingsSyncManager] 同步更新。
     *
     * @param items 每项形如 {bookKey:String, sortOrder:Int/Number, lastReadAt:Long/Number}
     * @return 实际被更新的行数（同名同键可能对应多行）
     */
    suspend fun applyBookMetaFromServer(items: List<Map<String, Any?>>): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0
        try {
            val db = databaseHelper.writableDatabase
            // id -> bookKey，反转为 bookKey -> ids（复用现成口径，不再手抄一遍 SQL）
            val idToKey = getShelfBookKeyMap()
            val keyToIds = HashMap<String, MutableList<Long>>()
            for ((id, bk) in idToKey) {
                keyToIds.getOrPut(bk) { ArrayList() }.add(id)
            }
            db.beginTransaction()
            var n = 0
            try {
                for (item in items) {
                    val bk = (item["bookKey"] as? String)?.trim().orEmpty()
                    if (bk.isEmpty()) continue
                    val ids = keyToIds[bk] ?: continue // 本机没有这本书：跳过，不 INSERT
                    val sort = (item["sortOrder"] as? Number)?.toInt() ?: continue
                    val last = (item["lastReadAt"] as? Number)?.toLong() ?: continue
                    for (id in ids) {
                        // 只写两列，category / file_path / store_book_id 等一概不碰
                        db.execSQL(
                            "UPDATE ${DatabaseHelper.TABLE_EBOOKS} " +
                                "SET sort_order = ?, last_read_at = ? WHERE id = ?",
                            arrayOf(sort, last, id)
                        )
                        n++
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Log.d(TAG, "✅ 应用远端书籍元数据：$n 行（仅 sort_order/last_read_at）")
            n
        } catch (e: Exception) {
            Log.e(TAG, "❌ 应用远端书籍元数据失败: ${e.message}")
            // 重新抛出：调用方（UserSettingsSyncManager）据此放弃更新影子快照，
            // 否则会「影子已按远端值前移、本地库却还是旧值」→ 下次误判为脏项把旧值推回去覆盖服务端。
            throw e
        }
    }

    /** 根据书名（displayName）查找电子书文件路径。 */
    suspend fun findEbookFilePathByDisplayName(displayName: String): String? = withContext(Dispatchers.IO) {
        try {
            databaseHelper.readableDatabase.query(
                DatabaseHelper.TABLE_EBOOKS,
                arrayOf("file_path"),
                "display_name = ?",
                arrayOf(displayName),
                null, null, null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            Log.e(TAG, "查找电子书路径失败: ${e.message}")
            null
        }
    }

    /** 根据文件路径反查书架展示名（书城下载重复入库时用于判断是否只是改名）。 */
    suspend fun findDisplayNameByFilePath(filePath: String): String? = withContext(Dispatchers.IO) {
        try {
            databaseHelper.readableDatabase.query(
                DatabaseHelper.TABLE_EBOOKS,
                arrayOf("display_name"),
                "file_path = ?",
                arrayOf(filePath),
                null, null, null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            Log.e(TAG, "反查书架展示名失败: ${e.message}")
            null
        }
    }

    /** 全部电子书的「展示名 → 文件路径」映射，供书名对齐时一次性取出，避免逐本查询。 */
    suspend fun getEbookPathsByName(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            databaseHelper.readableDatabase.query(
                DatabaseHelper.TABLE_EBOOKS,
                arrayOf("display_name", "file_path"),
                null, null, null, null, null
            ).use { cursor ->
                val map = mutableMapOf<String, String>()
                while (cursor.moveToNext()) {
                    map[cursor.getString(0)] = cursor.getString(1) ?: ""
                }
                map
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取书架展示名映射失败: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 按书名找书架里的书，返回**定位所需的全部信息**（文件路径 + 书城 id）。
     *
     * ## 为什么不能只按 display_name 精确查
     * 同一个「书」在不同来源下书名可能不同：
     * - 书城在线读：书名 = 服务端标题（`Linux命令行大全_一句话解释`，**无扩展名**）；
     * - 本地导入/下载到书架：书名 = 规范本地名（`Linux命令行大全_一句话解释.txt`，**有扩展名**，
     *   见 [FileUtils.storeBookLocalName]）。
     *
     * 收藏是在「书城在线读」时记下的（书名无扩展名），而书架里那条**有**扩展名
     * ⇒ 精确查查不到 ⇒ 打开阅读页时 `openBook` 找不到书籍表 ⇒
     * 「书籍表不存在或读取失败，以空内容打开」⇒ 正文空白 ⇒ 收藏跳不过去。
     *
     * 所以这里做**宽松匹配**：先精确查，查不到再按「去掉扩展名后相等」匹配。
     *
     * @return 找到返回 [ShelfBookLocation]，找不到返回 null
     */
    suspend fun findShelfBookLocation(bookName: String): ShelfBookLocation? =
        withContext(Dispatchers.IO) {
            try {
                val db = databaseHelper.readableDatabase
                val columns = arrayOf("display_name", "file_path", "store_book_id")
                // ① 精确匹配
                db.query(
                    DatabaseHelper.TABLE_EBOOKS, columns,
                    "display_name = ?", arrayOf(bookName), null, null, null
                ).use { c ->
                    if (c.moveToFirst()) return@withContext c.toShelfBookLocation()
                }
                // ② 宽松匹配：去掉扩展名后相等（`.txt` / `.pdf` / `.epub` / `.mobi` / `.md`）
                //
                // ⚠️ **不能只在「书名带扩展名」时才做**：收藏记录里的书名往往**没有**扩展名
                // （书城在线读时用的是服务端标题），而书架里那条**有**扩展名
                // ⇒ `stem == bookName` ⇒ 若把宽松匹配放在这个条件里，它永远不执行。
                val stem = bookName.substringBeforeLast('.', bookName)
                if (stem.isNotEmpty()) {
                    db.query(
                        DatabaseHelper.TABLE_EBOOKS, columns,
                        null, null, null, null, null
                    ).use { c ->
                        while (c.moveToNext()) {
                            val name = c.getString(0) ?: continue
                            if (name.substringBeforeLast('.', name).equals(stem, ignoreCase = true)) {
                                return@withContext c.toShelfBookLocation()
                            }
                        }
                    }
                }
                Log.w(TAG, "按书名找书架条目失败: '$bookName'（书架现有 ${allDisplayNames(db)}）")
                null
            } catch (e: Exception) {
                Log.e(TAG, "按书名找书架条目失败: ${e.message}")
                null
            }
        }

    /** 书架里所有展示名（诊断用：找不到书时打出来，便于对比差在哪）。 */
    private fun allDisplayNames(db: android.database.sqlite.SQLiteDatabase): List<String> =
        try {
            db.query(
                DatabaseHelper.TABLE_EBOOKS, arrayOf("display_name"),
                null, null, null, null, null
            ).use { c ->
                val out = ArrayList<String>()
                while (c.moveToNext()) out.add(c.getString(0) ?: "")
                out
            }
        } catch (e: Exception) {
            emptyList()
        }

    /** 当前 cursor 指向的一行 → [ShelfBookLocation]。 */
    private fun android.database.Cursor.toShelfBookLocation(): ShelfBookLocation {
        val storeId = if (isNull(2)) -1L else getLong(2)
        return ShelfBookLocation(
            displayName = getString(0) ?: "",
            filePath = getString(1) ?: "",
            storeBookId = storeId
        )
    }

    /**
     * 把一本书的**书名键**从 [oldName] 改为 [newName]。
     *
     * 用途：书城下载改用「原始书名」作为本机书名后，历史条目里还留着服务端加的
     * `<32 位随机码>_` 前缀。直接在书架上看到随机码是用户可见的瑕疵，重新下载又不该
     * 是唯一出路，故就地改名。
     *
     * **改名范围与 [deleteBookCascade] 的级联口径逐条对齐** —— 凡以书名为键的地方都要跟着改，
     * 漏一处就会出现半损坏状态（改名后「书能打开但没了声音」或「收藏全丢」）：
     *   1. `ebooks.display_name`（书架条目本身）
     *   2. `audio_segments.book_name`（音频片段；书架「有音频」图标与播放队列都读它）
     *   3. `favorites.book_name`（收藏；与服务端按 (book_name, line_number) 对齐身份）
     *   4. `tasks.book_name`（合成任务）
     *   5. 以书名为名的动态内容表（阅读页原文本）—— 只在该表真实存在时 `ALTER ... RENAME TO`
     *
     * **目标名被占用时放弃，而不是合并**：两本书的数据混进同一个书名键后无法再拆开，
     * 远比书名不漂亮严重。此时返回 false，调用方保持原状即可。
     *
     * **文件系统不动**：本方法只碰数据库。物理文件与 `file_path` 保持不变 ——
     * 从服务端文件名派生物理路径可避免同名书互相覆盖，且路径本身对用户不可见。
     *
     * @return true 表示改名成功（含「本来就叫 newName，无需处理」）；
     *         false 表示目标名被占用或执行失败（事务已回滚，数据保持原样）
     */
    suspend fun realignBookName(oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        if (oldName == newName) return@withContext true
        val db = databaseHelper.writableDatabase
        db.beginTransaction()
        try {
            val occupied = db.rawQuery(
                "SELECT COUNT(*) FROM ${DatabaseHelper.TABLE_EBOOKS} WHERE display_name = ?",
                arrayOf(newName)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            if (occupied > 0) {
                // 未标记 successful ⇒ 结束事务时回滚，不留半成品
                Log.w(TAG, "书名对齐放弃：目标名已被占用 $oldName -> $newName")
                return@withContext false
            }

            db.execSQL(
                "UPDATE ${DatabaseHelper.TABLE_EBOOKS} SET display_name = ? WHERE display_name = ?",
                arrayOf(newName, oldName)
            )
            db.execSQL(
                "UPDATE ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} SET book_name = ? WHERE book_name = ?",
                arrayOf(newName, oldName)
            )
            db.execSQL(
                "UPDATE ${DatabaseHelper.TABLE_FAVORITES} SET book_name = ? WHERE book_name = ?",
                arrayOf(newName, oldName)
            )
            db.execSQL(
                "UPDATE ${DatabaseHelper.TABLE_TASKS} SET book_name = ? WHERE book_name = ?",
                arrayOf(newName, oldName)
            )

            // 动态内容表：按真实存在的表名取（中文书名用 ASCII 正则匹配会漏），
            // 且排除与应用内部表同名的表（书名恰为 "tasks" 时绝不能改名内部表）
            val actualTable = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(oldName)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (actualTable != null && actualTable !in INTERNAL_TABLES) {
                if (tableExists(db, newName)) {
                    Log.w(TAG, "目标内容表已存在，保留原表: $newName")
                } else {
                    db.execSQL(
                        "ALTER TABLE \"${actualTable.replace("\"", "\"\"")}\" " +
                            "RENAME TO \"${newName.replace("\"", "\"\"")}\""
                    )
                }
            }

            db.setTransactionSuccessful()
            Log.d(TAG, "书名对齐完成: $oldName -> $newName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "书名对齐失败: $oldName -> $newName, ${e.message}", e)
            false
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 合成完成后自动将电子书登记到书架（去重 + 在 ebooks 目录按名匹配文件）。
     * 不做刷新（由 BookViewModel 在调用后 loadEbooks）。
     */
    suspend fun ensureEbookRegistered(bookName: String) = withContext(Dispatchers.IO) {
        try {
            // 已删除的书不重新登记：删除本地文件失败（被占用）时，下载结束的登记逻辑
            // 会把它以幽灵书形式放回书架。
            if (bookName in deletedBookNames) {
                Log.w(TAG, "书籍已删除，跳过自动登记: $bookName")
                return@withContext
            }
            val db = databaseHelper.readableDatabase

            // Step 1: 查重
            val exists = db.query(
                DatabaseHelper.TABLE_EBOOKS,
                arrayOf("id"),
                "display_name = ?",
                arrayOf(bookName),
                null, null, null
            ).use { cursor -> cursor.moveToFirst() }

            if (exists) {
                Log.d(TAG, "✅ 电子书已存在于书架，跳过登记: $bookName")
                return@withContext
            }

            // Step 2: 在 ebooks 目录中查找同名文件
            val ebookFile = File(File(context.filesDir, "ebooks"), bookName)
            if (!ebookFile.exists()) {
                Log.w(TAG, "⚠️ ebooks 目录中找不到电子书文件: $bookName，尝试按书名（无扩展名）匹配")
                val bookNameNoExt = bookName.removeFileExtension()
                val ebooksDir = File(context.filesDir, "ebooks")
                val matched = ebooksDir.listFiles()?.find {
                    it.name == bookNameNoExt || it.name.startsWith(bookNameNoExt + ".")
                }
                if (matched != null) {
                    Log.d(TAG, "📖 匹配到文件: ${matched.name}")
                    insertEbookRecord(matched.name, matched.absolutePath, "书城")
                } else {
                    Log.w(TAG, "❌ 找不到匹配文件，无法自动登记: $bookName")
                }
            } else {
                Log.d(TAG, "📖 在 ebooks 目录中找到文件: ${ebookFile.name}")
                insertEbookRecord(bookName, ebookFile.absolutePath, "书城")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 自动登记电子书失败: ${e.message}")
        }
    }

    // ---------------- 服务端解析结果缓存 ----------------

    /**
     * 读服务端解析结果缓存。
     *
     * 命中条件**不只是路径相同**：`file_size` 与 `file_mtime` 也必须一致。
     * 同名文件被重新导入时 mtime 必变 ⇒ 缓存自然失效，不会拿旧正文去对新音频建映射
     * （那会让「段号 ↔ 行号」整体错位，表现为点读串音）。
     *
     * @return 命中返回内容；未命中/已过期/读取异常返回 null（调用方照常走服务端）
     */
    suspend fun loadEbookContentCache(file: File): CachedEbookContent? = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.readableDatabase
            db.query(
                DatabaseHelper.TABLE_EBOOK_CONTENT_CACHE,
                arrayOf("rows_text", "toc_json", "file_size", "file_mtime"),
                "file_path = ?",
                arrayOf(file.absolutePath),
                null, null, null
            ).use { c ->
                if (!c.moveToNext()) return@use null
                val size = c.getLong(2)
                val mtime = c.getLong(3)
                if (size != file.length() || mtime != file.lastModified()) {
                    Log.d(TAG, "解析缓存已过期（文件已变）: ${file.name}")
                    return@use null
                }
                val rowsText = c.getString(0)
                if (rowsText == null) return@use null
                val rows = if (rowsText.isEmpty()) emptyList() else rowsText.split("\n")
                CachedEbookContent(rows, parseToc(c.getString(1)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取解析缓存失败: ${e.message}")
            null
        }
    }

    /** 写服务端解析结果缓存（同一路径 REPLACE）。失败只记日志：缓存是加速项，不是正确性依赖。 */
    suspend fun saveEbookContentCache(
        file: File,
        bookName: String,
        content: CachedEbookContent
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put("file_path", file.absolutePath)
                put("file_size", file.length())
                put("file_mtime", file.lastModified())
                put("book_name", bookName)
                // rows 里每行都不含 '\n'（切行时已按 '\n' 拆开），用 '\n' 连接是无损编码，
                // 也免掉 JSON 的转义与解析开销
                put("rows_text", content.rows.joinToString("\n"))
                put("toc_json", tocToJson(content.toc))
            }
            databaseHelper.writableDatabase.insertWithOnConflict(
                DatabaseHelper.TABLE_EBOOK_CONTENT_CACHE,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            ) >= 0
        } catch (e: Exception) {
            Log.w(TAG, "写入解析缓存失败: ${e.message}")
            false
        }
    }

    /**
     * 书城「在线读」正文缓存的键前缀。在线阅读没有本地文件，用 `store:<id>` 作为
     * `file_path` 主键复用 [DatabaseHelper.TABLE_EBOOK_CONTENT_CACHE]，`file_size` 恒 0。
     *
     * `file_mtime` 存的是**缓存格式版本号**（不是文件时间 —— 在线书没有本地文件）。
     * 为什么要版本号：缓存里存着服务端返回的**目录**，而目录语义会随服务端修复而变
     * （实测：服务端修好 PDF 书签页码解析前，返回的目录所有条目 paragraphIndex 都是 0
     * ⇒ 客户端点任何章节都跳到书首）。若没有版本判据，客户端会一直命中这份坏缓存，
     * 服务端修好了也看不到效果 —— 必须让旧缓存自动作废。
     *
     * 改动缓存**内容结构**（rows/toc 的字段含义）时递增此版本号，旧缓存即自动失效。
     */
    private fun storeCacheKey(bookId: Long) = "store:$bookId"

    /**
     * 书城在线正文缓存的格式版本。
     *
     * v2：服务端修好 PDF 书签页码解析（/Dest 直数组形式）之前写的缓存里，
     * 目录的 paragraphIndex 全是 0（点目录永远跳书首）⇒ 必须作废。
     */
    private val storeContentCacheVersion = 2L

    /** 读书城在线正文缓存（按 bookId）；无缓存或缓存版本过期返回 null。 */
    suspend fun loadStoreContentCache(bookId: Long): CachedEbookContent? = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.readableDatabase
            db.query(
                DatabaseHelper.TABLE_EBOOK_CONTENT_CACHE,
                arrayOf("rows_text", "toc_json", "file_mtime"),
                "file_path = ?",
                arrayOf(storeCacheKey(bookId)),
                null, null, null
            ).use { c ->
                if (!c.moveToNext()) return@use null
                val version = c.getLong(2)
                if (version != storeContentCacheVersion) {
                    Log.d(
                        TAG,
                        "书城在线正文缓存版本过期（缓存=$version 期望=$storeContentCacheVersion），重新拉取"
                    )
                    return@use null
                }
                val rowsText = c.getString(0) ?: return@use null
                val rows = if (rowsText.isEmpty()) emptyList() else rowsText.split("\n")
                CachedEbookContent(rows, parseToc(c.getString(1)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取书城在线正文缓存失败: ${e.message}")
            null
        }
    }

    /** 写书城在线正文缓存（同一 bookId REPLACE）。失败只记日志：缓存是加速项，不影响本次阅读。 */
    suspend fun saveStoreContentCache(
        bookId: Long,
        bookName: String,
        content: CachedEbookContent
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put("file_path", storeCacheKey(bookId))
                put("file_size", 0)
                // 在线书没有本地文件 ⇒ file_mtime 存**缓存格式版本号**（见 storeCacheKey 的注释）
                put("file_mtime", storeContentCacheVersion)
                put("book_name", bookName)
                put("rows_text", content.rows.joinToString("\n"))
                put("toc_json", tocToJson(content.toc))
            }
            databaseHelper.writableDatabase.insertWithOnConflict(
                DatabaseHelper.TABLE_EBOOK_CONTENT_CACHE,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            ) >= 0
        } catch (e: Exception) {
            Log.w(TAG, "写入书城在线正文缓存失败: ${e.message}")
            false
        }
    }

    /** 目录 → JSON。标题内部可能含换行（`<h1>标题<br>副标题</h1>` 清完标签就是两行），所以不能自定义分隔符。 */
    private fun tocToJson(toc: List<TocEntry>): String {
        val arr = JSONArray()
        toc.forEach { entry ->
            arr.put(
                JSONObject().apply {
                    put("title", entry.title)
                    put("segmentNumber", entry.segmentNumber)
                    put("paragraphIndex", entry.paragraphIndex)
                    put("pageNumber", entry.pageNumber)
                    put("level", entry.level)
                }
            )
        }
        return arr.toString()
    }

    /** 目录 JSON → 条目；任何异常都退化为空目录（目录缺失只影响跳转面板，不该让整本书打不开）。 */
    private fun parseToc(json: String?): List<TocEntry> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TocEntry(
                    title = o.optString("title", ""),
                    segmentNumber = o.optInt("segmentNumber", -1),
                    paragraphIndex = o.optInt("paragraphIndex", -1),
                    pageNumber = o.optInt("pageNumber", -1),
                    level = o.optInt("level", 0)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析目录缓存失败: ${e.message}")
            emptyList()
        }
    }

    // ---------------- 音频片段 ----------------

    /** 从 audio_segments 表加载某本书的所有段落文本（按 segment_number 升序）。 */
    suspend fun loadSegmentsFromAudioTable(bookName: String): List<LineItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<LineItem>()
        try {
            val db = databaseHelper.readableDatabase
            db.query(
                DatabaseHelper.TABLE_AUDIO_SEGMENTS,
                arrayOf("id", "segment_number", "text_content"),
                "book_name = ?",
                arrayOf(bookName),
                null, null, "segment_number ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(0)
                    val segmentNumber = cursor.getInt(1)
                    val text = cursor.getString(2) ?: ""
                    items.add(LineItem(id = id, lineNumber = segmentNumber, text = text))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "从 audio_segments 加载失败: ${e.message}")
        }
        items
    }

    /**
     * 清空某本书的全部本地音频片段。
     *
     * 只有**整本覆盖式下载**该用它（书城「下载合成音频」把整包解压后逐段入库）。
     * 与 [saveAudioSegmentStreaming] 的语义相反：那个是**增量补下载**（逐段补缺、
     * 不碰已有行），而整本重下必须先清空，否则服务端这次没打包进来的旧段会残留，
     * 表现为「播放到某一段音频与文字对不上」，并且 [hasAudio] 之类的判断会被旧数据误导。
     *
     * 调用前应先 [flushPendingAudioSegments]：缓冲区里若还压着这本书的段，
     * 清空之后它们才落盘，等于没清。
     *
     * @return 删除的行数；异常返回 -1
     */
    suspend fun clearAudioSegments(bookName: String): Int = withContext(Dispatchers.IO) {
        try {
            databaseHelper.writableDatabase.delete(
                DatabaseHelper.TABLE_AUDIO_SEGMENTS,
                "book_name = ?",
                arrayOf(bookName)
            )
        } catch (e: Exception) {
            Log.e(TAG, "清空音频片段失败: $bookName - ${e.message}", e)
            -1
        }
    }

    /**
     * 批量写入下载的音频片段（先判黑名单，再事务 INSERT OR REPLACE）。
     *
     * @return 是否全部写入成功。返回 Boolean 而不是 Unit：原先异常只打日志、调用方
     *         无条件提示「已写入」，用户以为下载成功，其实一条都没落库。
     */
    suspend fun saveAudioSegments(bookName: String, segments: List<Map<String, Any>>): Boolean = withContext(Dispatchers.IO) {
        try {
            // 下载与删除并发时：书已删则丢弃这批片段，避免把级联清理掉的行插回
            if (bookName in deletedBookNames) {
                Log.w(TAG, "书籍已删除，丢弃下载的音频片段: $bookName")
                return@withContext false
            }
            val db = databaseHelper.writableDatabase

            var insertedCount = 0
            db.beginTransaction()
            try {
                for (segment in segments) {
                    val segmentNumber = (segment["segmentNumber"] as? Number)?.toInt() ?: continue
                    val textContent = segment["textContent"] as? String ?: "段落 $segmentNumber"
                    val audioData = (segment["audioData"] as? ByteArray) ?: continue

                    if (audioData.isEmpty()) continue

                    val serverSegId = (segment["serverSegmentId"] as? Number)?.toLong()
                    // 批量下载路径：段号带 server_segment_id 即来自服务端（可跨端播放），否则视作本机
                    val src = if (serverSegId != null) "server" else "local"

                    db.execSQL(
                        "INSERT OR REPLACE INTO ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} " +
                            "(book_name, server_segment_id, segment_number, text_content, audio_data, status, source) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            bookName,
                            serverSegId,
                            segmentNumber,
                            textContent,
                            audioData,
                            "completed",
                            src
                        )
                    )
                    insertedCount++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            Log.d(TAG, "✅ 已写入 $insertedCount 个音频片段到 audio_segments (book=$bookName)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 保存音频片段到 SQLite 失败: ${e.message}")
            false
        }
    }

    /**
     * 逐段写入下载的音频片段。
     *
     * 每段都单独 `execSQL` 时，每条 DML 在 autocommit 下都是**一个隐式事务**，
     * 每次提交都要 fsync 一次。一本 1000 段的书就是 1000 次 fsync —— 在机械盘/低端
     * eMMC 上这几乎是纯开销，下载速度被磁盘拖死，且期间读写互斥（已开 WAL 缓解）。
     *
     * 这里用进程级事务窗口：累积到 [FLUSH_SEGMENT_COUNT] 段或距首段入队超过
     * [FLUSH_INTERVAL_MS] 就整批提交一次，1000 段从 1000 次 fsync 降到约 42 次。
     *
     * **调用方必须在下载收尾的 `finally` 里调用 [flushPendingAudioSegments]**，
     * 否则最后不满一批的片段会一直留在缓冲里，表现为「下载完成但最后几段没声音」。
     *
     * @return true 表示已入队（**不代表已落盘**）；写入失败由 [consumeFlushFailures] 暴露
     */
    suspend fun saveAudioSegmentStreaming(
        bookName: String,
        serverSegmentId: Long?,
        segmentNumber: Int,
        textContent: String,
        audioData: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        if (audioData.isEmpty()) return@withContext false
        // 下载与删除并发时：书已删则丢弃该段，避免把级联清理掉的行插回
        if (bookName in deletedBookNames) {
            Log.w(TAG, "书籍已删除，丢弃下载的音频片段 (段落=$segmentNumber): $bookName")
            return@withContext false
        }
        val row = PendingSegment(bookName, serverSegmentId, segmentNumber, textContent, audioData, "server")
        val due = synchronized(pendingLock) {
            pendingSegments.add(row)
            if (firstPendingAtMs == 0L) firstPendingAtMs = android.os.SystemClock.elapsedRealtime()
            pendingSegments.size >= FLUSH_SEGMENT_COUNT ||
                android.os.SystemClock.elapsedRealtime() - firstPendingAtMs >= FLUSH_INTERVAL_MS
        }
        if (due) flushPendingAudioSegments()
        true
    }

    /**
     * **绕过内存缓冲，立即把单段落盘。** 本机合成专用。
     *
     * ## 为什么本机合成不能用 [saveAudioSegmentStreaming]
     *
     * 那个接口是给**下载**设计的：网络流可以续，进程被杀后重下即可，
     * 用「攒 24 段 / 2 秒」换掉上千次 fsync 很划算。
     *
     * 本机合成的处境完全相反：
     *  1. **合成一段要几十秒到几分钟**，是全书最贵的操作，丢了要重算；
     *  2. **推理常驻 1 GB 内存**，正是 LMK 的首选目标，进程被杀是**常态**而非意外；
     *  3. 合成器只在**全部 worker 结束后**才调 [flushPendingAudioSegments]，
     *     中途被杀 ⇒ 缓冲里最多 [FLUSH_SEGMENT_COUNT] 段音频**永久丢失**，
     *     而任务行已被 onProgress 记成「已完成」⇒ 用户看到「11 / 31 完成了」但那几段没声音。
     *
     * 真机实测就是这个现象：`tasks.completed_segments = 11` 而
     * `audio_segments` 只有 10 行，差的 1 段连同缓冲里其余段一起没了。
     *
     * 每段一次 fsync 的开销对本机合成可以接受 —— 相比重新合成一段音频的两分钟，
     * 几毫秒 fsync 完全可以忽略。
     *
     * @return true 表示**确实已落库**（与 [saveAudioSegmentStreaming] 的「已入队」不同）
     */
    suspend fun saveAudioSegmentDirect(
        bookName: String,
        serverSegmentId: Long?,
        segmentNumber: Int,
        textContent: String,
        audioData: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        if (audioData.isEmpty()) return@withContext false
        if (bookName in deletedBookNames) {
            Log.w(TAG, "书籍已删除，丢弃合成片段 (段落=$segmentNumber): $bookName")
            return@withContext false
        }
        val row = PendingSegment(bookName, serverSegmentId, segmentNumber, textContent, audioData, "local")
        try {
            writeSegmentsLocked(listOf(row))
            true
        } catch (e: Exception) {
            // 单段失败不重试：重试的是「同一段音频再写一次」，而合成器上层已经
            // 有 failedSegments 计数与「可重试补齐」的提示，重试只会拖慢整本
            Log.e(TAG, "合成片段落盘失败 (段落=$segmentNumber): ${e.message}", e)
            false
        }
    }

    /**
     * 把缓冲中的片段一次性写库。
     *
     * @return 成功写入的段数；全部失败返回 -1；缓冲为空返回 0
     */
    // 这里做的是一个数据库事务（beginTransaction + 逐条 executeInsert + fsync），
    // 但早前它是普通 fun —— API 形状对线程零约束，任何一个新调用点放到主线程就是
    // 主线程事务 + fsync，直接 ANR。现有调用点恰好都在 IO 上才没出事。
    // 改成 suspend + withContext(IO) 后由编译期挡住主线程调用。
    suspend fun flushPendingAudioSegments(): Int = withContext(Dispatchers.IO) {
        val rows = synchronized(pendingLock) {
            if (pendingSegments.isEmpty()) return@withContext 0
            val snapshot = ArrayList(pendingSegments)
            // 先取出再写库：写库期间新到达的片段进新的一批，不会被回滚连带清掉
            pendingSegments.clear()
            firstPendingAtMs = 0L
            snapshot
        }
        try {
            writeSegmentsLocked(rows)
            rows.size
        } catch (e: Exception) {
            // 失败时**原地同步重试一次**。
            //
            // 不能把 rows 塞回 pendingSegments 头部重试：那样同一批数据会在之后每次
            // flush 时反复重试、反复失败，把一次瞬态故障变成持续卡顿。
            try {
                Log.w(TAG, "音频片段批量写入失败，重试一次: ${e.message}")
                writeSegmentsLocked(rows)
                rows.size
            } catch (e2: Exception) {
                Log.e(TAG, "音频片段批量写入失败，丢弃 ${rows.size} 段", e2)
                synchronized(pendingLock) { droppedSegmentCount += rows.size }
                -1
            }
        }
    }

    /**
     * 在一个事务里写入整批片段；任一失败整批回滚，不会留下「半本书」。
     *
     * 用预编译 [SQLiteStatement] 复用同一条 SQL，避免整批重复解析/编译。
     */
    private fun writeSegmentsLocked(rows: List<PendingSegment>) {
        val db = databaseHelper.writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} " +
                    "(book_name, server_segment_id, segment_number, text_content, audio_data, status, source) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)"
            )
            try {
                for (r in rows) {
                    stmt.clearBindings()
                    stmt.bindString(1, r.bookName)
                    if (r.serverSegmentId != null) {
                        stmt.bindLong(2, r.serverSegmentId)
                    } else {
                        stmt.bindNull(2)
                    }
                    stmt.bindLong(3, r.segmentNumber.toLong())
                    stmt.bindString(4, r.textContent)
                    stmt.bindBlob(5, r.audioData)
                    stmt.bindString(6, "completed")
                    stmt.bindString(7, r.source)
                    stmt.executeInsert()
                }
            } finally {
                stmt.close()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 取走并清零「因写入失败被丢弃的片段数」。
     *
     * 缓冲机制把「写失败」从**每段可见**变成了**只在提交时可见**，如果调用方不查这个计数，
     * 一次批量失败就会静默丢掉最多 [FLUSH_SEGMENT_COUNT] 段音频，而任务照样显示成功。
     * 下载收尾处必须检查它。
     */
    fun consumeFlushFailures(): Int = synchronized(pendingLock) {
        val n = droppedSegmentCount
        droppedSegmentCount = 0
        n
    }

    /** 单次从 BLOB 里抠出来的字节数（CursorWindow 共享内存 + 堆拷贝都有上限，分块最稳）。 */
    private val BLOB_CHUNK_BYTES = 256 * 1024

    /**
     * 把单个音频片段的 BLOB **分块**写进 [target]。
     *
     * 一次性 `cursor.getBlob()` 把整段音频物化成 ByteArray 再 `writeBytes` 落盘，
     * 峰值堆占用是「整段音频 × 2」：单段上限 50MB 就是 100MB，
     * 在低端机/小堆进程上直接 OOM，且 OOM 发生在播放准备阶段，连带整个播放服务崩溃。
     *
     * Android 的 CursorWindow 把 BLOB 放在共享内存，`getBlob()` 会整体拷进 Java 堆；
     * 这里用 SQLite `substr(audio_data, ?, ?)` 每次只读 [BLOB_CHUNK_BYTES] 字节，
     * 边读边写文件，全程堆里只有「一个分块」大小的数据。
     *
     * 落盘走「临时文件 + 原子改名」：写一半失败（断网/被杀）不会在 cacheDir 留下
     * 截断的 .mp3，否则 MediaPlayer 会把它当成「有音频」却播不出来，表现为随机静音。
     *
     * @return true 表示已成功写出完整文件；缺失/为空/写出失败返回 false
     */
    suspend fun writeAudioBlobTo(bookName: String, segmentNumber: Int, target: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val db = databaseHelper.readableDatabase
                val total = db.rawQuery(
                    "SELECT length(audio_data) FROM ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} " +
                        "WHERE book_name = ? AND segment_number = ?",
                    arrayOf(bookName, segmentNumber.toString())
                ).use { c ->
                    if (c.moveToFirst()) c.getLong(0) else -1L
                }
                if (total <= 0L) {
                    Log.w(TAG, "音频片段缺失或为空: $bookName §$segmentNumber")
                    return@withContext false
                }

                val tmp = File(target.parentFile, "${target.name}.part")
                val wroteAll = tmp.outputStream().use { out ->
                    var offset = 1L
                    while (offset <= total) {
                        val chunk = readBlobChunk(db, bookName, segmentNumber, offset, BLOB_CHUNK_BYTES.toLong())
                            ?: return@use false
                        out.write(chunk)
                        offset += chunk.size
                    }
                    offset > total
                }
                if (!wroteAll) {
                    tmp.delete()
                    return@withContext false
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return@withContext false
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "导出音频 BLOB 失败: ${e.message}")
                false
            }
        }

    /** 从 audio_data 抠出 [len] 字节（从 1 基的 [offset] 起）；越界/为空返回 null。 */
    private fun readBlobChunk(
        db: android.database.sqlite.SQLiteDatabase,
        bookName: String,
        segmentNumber: Int,
        offset: Long,
        len: Long
    ): ByteArray? {
        db.rawQuery(
            "SELECT substr(audio_data, ?, ?) FROM ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} " +
                "WHERE book_name = ? AND segment_number = ?",
            arrayOf(offset.toString(), len.toString(), bookName, segmentNumber.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val b = c.getBlob(0) ?: return null
            if (b.isEmpty()) return null
            return b
        }
    }

    /** 某段落是否已有本地音频（补下载时跳过已有片段）。只做存在性探测，不物化 BLOB。 */
    suspend fun hasLocalAudioSegment(bookName: String, segmentNumber: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.readableDatabase
            db.rawQuery(
                "SELECT 1 FROM ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} " +
                    "WHERE book_name = ? AND segment_number = ? " +
                    "AND IFNULL(length(audio_data), 0) > 0 LIMIT 1",
                arrayOf(bookName, segmentNumber.toString())
            ).use { cursor -> cursor.moveToFirst() }
        } catch (e: Exception) {
            Log.e(TAG, "查询本地片段失败: ${e.message}")
            false
        }
    }

    /**
     * 这本书本地已有的**段号集合**（只取有音频数据的行）。
     *
     * 供逐段下载时一次性判重：整本几百段逐段查 [hasLocalAudioSegment] 就是几百次查询，
     * 而下载是串行的、判重是纯本地操作 —— 一次全取回来说是 O(1) 次查询。
     */
    suspend fun loadLocalSegmentNumbers(bookName: String): Set<Int> = withContext(Dispatchers.IO) {
        val numbers = mutableSetOf<Int>()
        try {
            val db = databaseHelper.readableDatabase
            db.rawQuery(
                "SELECT segment_number FROM ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} " +
                    "WHERE book_name = ? AND IFNULL(length(audio_data), 0) > 0",
                arrayOf(bookName)
            ).use { cursor ->
                while (cursor.moveToNext()) numbers.add(cursor.getInt(0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询本地段号集合失败: $bookName - ${e.message}")
        }
        numbers
    }

    /**
     * 解析书在**本地**的书名 —— 它是 `ebooks.display_name` 与 `audio_segments.book_name` 的取值。
     *
     * 不能只用服务端文件名推导：书名可能是
     *  1. **原始书名**（当前命名，见 [FileUtils.storeBookLocalName]）；
     *  2. **服务端文件名的清洗结果**（升级前下载的条目，含随机码前缀）；
     *  3. 原始文件名兜底（服务端文件名里可能带被清洗掉的字符）。
     * 只认其中一种，另一种书就会被判成「本地没有」，逐段下载被挡在
     * 「请先把《X》下载到书架」的提示上。
     *
     * @return 匹配到的本地书名；本地还没有这本书时返回 null（调用方应提示先下电子书）
     */
    suspend fun resolveLocalBookName(
        title: String,
        fileName: String,
        fileExtension: String?
    ): String? {
        val candidates = listOf(
            FileUtils.storeBookLocalName(title, fileName, fileExtension),
            FileUtils.sanitizeFileName(fileName),
            fileName
        ).distinct()
        for (name in candidates) {
            if (name.isNotEmpty() && findEbookFilePathByDisplayName(name) != null) return name
        }
        return null
    }

    // ---------------- 音频缓存统计 / 删除影响 / 清理 ----------------

    /** 音频缓存占用（audio_segments 的 BLOB 总大小，BLOB 不进 Java 堆）。 */
    suspend fun computeAudioStorageStats(): AudioStorageStats = withContext(Dispatchers.IO) {
        var stats = AudioStorageStats(0, emptyList())
        try {
            val db = databaseHelper.readableDatabase
            val books = db.rawQuery(
                "SELECT book_name, IFNULL(SUM(length(audio_data)), 0) AS size " +
                    "FROM ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} GROUP BY book_name ORDER BY size DESC",
                null
            ).use { c ->
                val list = mutableListOf<BookAudioSize>()
                while (c.moveToNext()) {
                    list.add(BookAudioSize(c.getString(0), c.getLong(1)))
                }
                list
            }
            stats = AudioStorageStats(books.sumOf { it.bytes }, books)
        } catch (e: Exception) {
            Log.e(TAG, "查询音频缓存统计失败: ${e.message}")
        }
        stats
    }

    /** 删除某本书会连带影响什么（关联任务/进行中任务/本地音频段）。 */
    suspend fun computeDeleteImpact(book: Book): DeleteImpact = withContext(Dispatchers.IO) {
        var impact = DeleteImpact(0, 0, 0)
        try {
            val db = databaseHelper.readableDatabase
            val totalTasks = db.rawQuery(
                "SELECT COUNT(*) FROM ${DatabaseHelper.TABLE_TASKS} WHERE book_name = ?",
                arrayOf(book.displayName)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val activeTasks = db.rawQuery(
                "SELECT COUNT(*) FROM ${DatabaseHelper.TABLE_TASKS} WHERE book_name = ? AND status IN ('pending','queued','processing')",
                arrayOf(book.displayName)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val audioSegments = db.rawQuery(
                "SELECT COUNT(*) FROM ${DatabaseHelper.TABLE_AUDIO_SEGMENTS} WHERE book_name = ?",
                arrayOf(book.displayName)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            impact = DeleteImpact(totalTasks, activeTasks, audioSegments)
        } catch (e: Exception) {
            Log.e(TAG, "查询删除影响失败: ${e.message}")
        }
        impact
    }

    /**
     * 事务内级联删除一本书：ebooks 元信息 + audio_segments + favorites + tasks + 动态内容表。
     *
     * **只做 DB 部分**：本地文件删除与服务端任务取消由调用方在事务提交后执行
     * （文件 I/O 与网络请求不能挂在 SQLite 事务里，否则失败会连带回滚整本书的删除）。
     *
     * @return 被删任务的引用，供调用方清理轮询状态并尽力取消服务端任务。
     * @throws Exception 任一 SQL 失败即整事务回滚（ebooks 行也不会被删）
     */
    suspend fun deleteBookCascade(book: Book): CascadeDeleteResult = withContext(Dispatchers.IO) {
        val db = databaseHelper.writableDatabase
        val bookName = book.displayName
        val taskRefs = mutableListOf<Pair<Long, Long?>>() // (localTaskId, serverTaskId)
        var skippedCascade = false

        db.beginTransaction()
        try {
            // audio_segments / favorites / tasks / 动态内容表 都以 book_name（书名）关联，
            // 而 ebooks 对 display_name 无唯一约束（去重只按 file_path）——书架里若还有
            // 其它同名书，无法区分数据归属，此时跳过级联清理以免误删另一本书的数据。
            // 计数查询放在事务内，避免事务外读取与并发删除/导入之间的 TOCTOU
            val sameNameCount = db.rawQuery(
                "SELECT COUNT(*) FROM ${DatabaseHelper.TABLE_EBOOKS} WHERE display_name = ? AND id != ?",
                arrayOf(bookName, book.id.toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

            // 1. 电子书元信息
            db.delete(DatabaseHelper.TABLE_EBOOKS, "id = ?", arrayOf(book.id.toString()))

            if (sameNameCount == 0) {
                // 收集任务引用：事务提交后尽力取消服务端任务 + 清理轮询状态
                db.query(
                    DatabaseHelper.TABLE_TASKS,
                    arrayOf("task_id", "server_task_id"),
                    "book_name = ?",
                    arrayOf(bookName),
                    null, null, null
                ).use { c ->
                    while (c.moveToNext()) {
                        taskRefs.add(
                            c.getLong(0) to if (c.isNull(1)) null else c.getLong(1)
                        )
                    }
                }
                // 2. 关联音频片段（book_name = 书名）
                db.delete(DatabaseHelper.TABLE_AUDIO_SEGMENTS, "book_name = ?", arrayOf(bookName))
                // 3. 关联收藏（book_name = 书名）
                db.delete(DatabaseHelper.TABLE_FAVORITES, "book_name = ?", arrayOf(bookName))
                // 4. 关联合成任务：书已删除，其任务记录（含 processing 状态）不再展示、
                //    不再被 TaskPollingManager 轮询
                db.delete(DatabaseHelper.TABLE_TASKS, "book_name = ?", arrayOf(bookName))
                // 5. 服务端解析结果缓存（book_name = 书名）—— 不删也能靠 mtime 失效，
                //    但会白白占空间（一本书的行文本可达数百 KB）
                db.delete(DatabaseHelper.TABLE_EBOOK_CONTENT_CACHE, "book_name = ?", arrayOf(bookName))
                // 6. 动态内容表：从 sqlite_master 精确查找实际存在的表名后 DROP。
                //    用 ASCII 正则校验表名时，中文/带扩展名书名（如 三体.txt）
                //    永远不匹配，表残留会让删除的书以“幽灵书”重新出现在书架。
                //    表名来自参数化查询的 sqlite_master 结果（真实存在的表），
                //    DROP 前转义双引号防注入；同时排除与内部表同名的表（如书名恰为
                //    "tasks" 的无扩展名文件），绝不允许误删应用内部表
                val actualTable = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                    arrayOf(bookName)
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (actualTable != null && actualTable !in INTERNAL_TABLES) {
                    db.execSQL("DROP TABLE IF EXISTS \"${actualTable.replace("\"", "\"\"")}\"")
                } else if (actualTable != null) {
                    Log.w(TAG, "内容表名与应用内部表冲突，跳过 DROP: $actualTable")
                }
            } else {
                skippedCascade = true
                Log.w(TAG, "书架中存在同名书籍，跳过级联清理: $bookName")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        CascadeDeleteResult(taskRefs, skippedCascade)
    }

    /**
     * 清理某本书的本地音频（保留电子书与任务记录）。
     *
     * ⚠️ **必须先丢弃内存缓冲里该书的段**（[discardPendingSegments]），再删表。
     * 顺序反了会出事：缓冲里的段是「已经下载好、正等着批量落盘」的，
     * DELETE 执行完之后它们才落盘 —— 于是刚清掉的音频又回来了，
     * 书架上的 ▶ 重新出现，用户以为清理没生效（这个坑本文件里早有注释提醒）。
     */
    suspend fun clearAudioForBook(bookName: String): Boolean = withContext(Dispatchers.IO) {
        var ok = false
        try {
            // 先丢缓冲，再删表
            discardPendingSegments(bookName)
            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                db.delete(DatabaseHelper.TABLE_AUDIO_SEGMENTS, "book_name = ?", arrayOf(bookName))
                db.setTransactionSuccessful()
                ok = true
            } finally {
                db.endTransaction()
            }
            vacuumIfPossible(db)
        } catch (e: Exception) {
            Log.e(TAG, "清理书籍音频失败: ${e.message}")
        }
        ok
    }

    /**
     * 丢弃内存缓冲里属于某本书（或全部）的待写入片段，**不落盘**。
     *
     * 存在的意义：这些段已经下载/合成完成、正排队等批量写入，
     * 用户在存储管理里点了「清理」，若不丢弃它们，它们会在清理之后落盘，
     * 让刚删掉的音频"复活"（▶ 图标重新出现），用户完全无法理解。
     *
     * @param bookName null = 丢弃全部缓冲
     * @return 被丢弃的段数
     */
    fun discardPendingSegments(bookName: String?): Int = synchronized(pendingLock) {
        if (pendingSegments.isEmpty()) return@synchronized 0
        val before = pendingSegments.size
        val kept = if (bookName == null) {
            pendingSegments.clear()
            emptyList()
        } else {
            val remain = pendingSegments.filter { it.bookName != bookName }
            val removed = before - remain.size
            pendingSegments.clear()
            pendingSegments.addAll(remain)
            Log.i(TAG, "丢弃音频缓冲中属于《$bookName》的 $removed 段（清理音频，避免落盘后复活）")
            remain
        }
        if (pendingSegments.isEmpty()) firstPendingAtMs = 0L
        before - kept.size
    }

    /** 清理全部本地音频缓存（保留所有电子书）。 */
    suspend fun clearAllAudio(): Boolean = withContext(Dispatchers.IO) {
        var ok = false
        try {
            // 同 clearAudioForBook：先丢缓冲，再删表，否则缓冲里的段随后落盘、音频复活
            discardPendingSegments(null)
            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                db.delete(DatabaseHelper.TABLE_AUDIO_SEGMENTS, null, null)
                db.setTransactionSuccessful()
                ok = true
            } finally {
                db.endTransaction()
            }
            vacuumIfPossible(db)
        } catch (e: Exception) {
            Log.e(TAG, "清理全部音频失败: ${e.message}")
        }
        ok
    }

    /**
     * 清理后压缩数据库文件：SQLite 删除行只释放页内空间，VACUUM 才真正回收磁盘。
     * 可能因并发访问失败（SQLITE_BUSY），此时空间留待后续自然回收，不视为清理失败。
     */
    private fun vacuumIfPossible(db: SQLiteDatabase) {
        try {
            db.execSQL("VACUUM")
            Log.d(TAG, "VACUUM 完成，数据库空间已回收")
        } catch (e: Exception) {
            Log.w(TAG, "VACUUM 未执行（可能有并发访问，空间稍后回收）: ${e.message}")
        }
    }

    private fun String.removeFileExtension(): String {
        val dotIndex = this.lastIndexOf('.')
        return if (dotIndex > 0) {
            this.substring(0, dotIndex)
        } else {
            this
        }
    }

}

