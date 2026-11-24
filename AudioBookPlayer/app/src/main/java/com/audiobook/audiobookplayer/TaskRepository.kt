package com.audiobook.audiobookplayer

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import com.audiobook.audiobookplayer.model.ServerTaskSnapshot
import com.audiobook.audiobookplayer.model.Task
import com.audiobook.audiobookplayer.moss.LocalSynthesisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 任务（TTS 合成）持久化层。
 *
 * tasks 表相关的建表、增删改查、状态/服务端ID/音频路径更新、
 * 按书名级联删除等逻辑集中在这里。BookViewModel 仅保留对外方法签名，
 * 委托本类并在结果返回后刷新自身 LiveData，保证 fragment 行为不变。
 *
 * 注意：本类不持有任何 LiveData，所有方法返回纯数据；刷新 UI 由 BookViewModel 负责。
 */
class TaskRepository(private val context: Application) {

    private val databaseHelper = AppRepository.getInstance(context).databaseHelper

    /** 建表 + 增量补全字段（server_task_id / audio_files_path / error_message）。 */
    suspend fun createTableIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase

            // 查询表是否存在
            val tableExists = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(DatabaseHelper.TABLE_TASKS)
            ).use { cursor -> cursor.moveToFirst() }

            if (!tableExists) {
                Log.d(TAG, "tasks 表不存在,开始创建...")
            } else {
                // 查询列信息
                val columnInfo = db.rawQuery("PRAGMA table_info(${DatabaseHelper.TABLE_TASKS})", null)
                    .use { colCursor ->
                        var hasServerTaskIdColumn = false
                        var hasAudioFilesPathColumn = false
                        var hasErrorMessageColumn = false
                        var hasProgressTextColumn = false
                        while (colCursor.moveToNext()) {
                            when (colCursor.getString(1)) {
                                "server_task_id" -> hasServerTaskIdColumn = true
                                "audio_files_path" -> hasAudioFilesPathColumn = true
                                "error_message" -> hasErrorMessageColumn = true
                                "progress_text" -> hasProgressTextColumn = true
                            }
                        }
                        arrayOf(
                            hasServerTaskIdColumn, hasAudioFilesPathColumn,
                            hasErrorMessageColumn, hasProgressTextColumn
                        )
                    }

                val (hasServerTaskIdColumn, hasAudioFilesPathColumn, hasErrorMessageColumn,
                    hasProgressTextColumn) = columnInfo

                if (!hasServerTaskIdColumn) {
                    Log.w(TAG, "tasks 表缺少 server_task_id 字段,尝试添加...")
                    try {
                        db.execSQL("ALTER TABLE ${DatabaseHelper.TABLE_TASKS} ADD COLUMN server_task_id INTEGER")
                        Log.d(TAG, "✅ 成功添加 server_task_id 字段")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 添加字段失败: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "✅ tasks 表结构验证通过,包含 server_task_id 字段")
                }

                if (!hasAudioFilesPathColumn) {
                    Log.w(TAG, "tasks 表缺少 audio_files_path 字段,尝试添加...")
                    try {
                        db.execSQL("ALTER TABLE ${DatabaseHelper.TABLE_TASKS} ADD COLUMN audio_files_path TEXT")
                        Log.d(TAG, "✅ 成功添加 audio_files_path 字段")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 添加字段失败: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "✅ tasks 表结构验证通过,包含 audio_files_path 字段")
                }

                if (!hasErrorMessageColumn) {
                    Log.w(TAG, "tasks 表缺少 error_message 字段,尝试添加...")
                    try {
                        db.execSQL("ALTER TABLE ${DatabaseHelper.TABLE_TASKS} ADD COLUMN error_message TEXT")
                        Log.d(TAG, "✅ 成功添加 error_message 字段")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 添加字段失败: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "✅ tasks 表结构验证通过,包含 error_message 字段")
                }

                if (!hasProgressTextColumn) {
                    // 缺这一列时书架只会显示「0 / N」而在加载模型期间一动不动，
                    // 用户会以为卡住；补列后能显示「正在加载模型」这类阶段文案。
                    Log.w(TAG, "tasks 表缺少 progress_text 字段,尝试添加...")
                    try {
                        db.execSQL("ALTER TABLE ${DatabaseHelper.TABLE_TASKS} ADD COLUMN progress_text TEXT")
                        Log.d(TAG, "✅ 成功添加 progress_text 字段")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 添加字段失败: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "✅ tasks 表结构验证通过,包含 progress_text 字段")
                }
            }

            db.execSQL(DatabaseHelper.DDL_TASKS)

            Log.d(TAG, "Task table created/verified")
        } catch (e: Exception) {
            Log.e(TAG, "创建表失败: ${e.message}", e)
        }
    }

    /**
     * 读取全部任务（带 hasAudio 标记）。
     * downloadStatus / downloadProgress 等内存瞬态由 BookViewModel.loadTasks 合并，
     * 这里只返回数据库原始结果。
     */
    suspend fun getTasks(booksWithAudio: Set<String>): List<Task> = withContext(Dispatchers.IO) {
        val taskList = mutableListOf<Task>()
        try {
            val db = databaseHelper.readableDatabase
            db.query(
                DatabaseHelper.TABLE_TASKS,
                arrayOf(
                    "id", "book_name", "audio_name", "task_id", "status",
                    "total_segments", "completed_segments", "audio_files_path",
                    "server_task_id", "error_message", "progress_text"
                ),
                null, null, null, null,
                "created_at DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val bookName = cursor.getString(1)
                    val audioName = cursor.getString(2)
                    val status = cursor.getString(4)
                    val totalSegments = cursor.getInt(5)
                    val completedSegments = cursor.getInt(6)
                    val audioFilesPath = if (cursor.isNull(7)) null else cursor.getString(7)
                    val serverTaskId = if (cursor.isNull(8)) null else cursor.getLong(8)
                    val errorMessage = if (cursor.isNull(9)) null else cursor.getString(9)
                    val progressText = if (cursor.isNull(10)) null else cursor.getString(10)
                    val hasAudio = bookName in booksWithAudio
                    taskList.add(
                        Task(
                            id, bookName, audioName, status, 0, totalSegments,
                            completedSegments, audioFilesPath = audioFilesPath,
                            serverTaskId = serverTaskId, hasAudio = hasAudio,
                            errorMessage = errorMessage, progressText = progressText
                        )
                    )
                }
                // 逐条打印会在每次刷新时刷出上百行，淹没真正的错误日志，改为循环外汇总。
                Log.d(TAG, "Loaded ${taskList.size} tasks")
            }
        } catch (e: SQLiteException) {
            // DB 异常（如损坏/锁等待超时）不能被笼统 catch 吞成空列表，
            // 否则书架连同「只有合成任务」的虚拟条目一起消失，用户误以为任务被删。
            // 区分 SQLiteException 并向上抛出，交由 loadTasks 的 catch 暴露错误，
            // 而非静默掩盖成「无任务」。
            Log.e(TAG, "Load tasks failed (DB): ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Load tasks failed: ${e.message}")
        }
        taskList
    }

    /**
     * 开始一轮本机合成时登记任务行：**同一本书复用已有的行，不新建**。
     *
     * 【为什么不能无条件 INSERT —— 真机踩过】
     * 原先 [saveTask] 每次都 INSERT，而续跑入口（书架「继续合成剩余 N 段」）
     * 每点一次就调一次服务，于是每点一次就多一行任务。真机实测《测试.txt》
     * 堆了 5 行（id=8~12，状态分别是 partial/partial/partial/partial/failed），
     * 全部指向同一本书、全部停在 10/31 或 27/31 —— 用户看到的是"这本书有 5 个
     * 合成任务，进度还各不相同"，完全无法理解。
     * 根因不止于显示：**点击的入口是某一行，而合成进度写的是新建的那一行**，
     * 于是那一行的按钮算出的"剩余段数"永远停在旧值（点它会说"还缺 21 段"，
     * 实际只剩 4 段），越点越乱。
     *
     * 【复用条件】`book_name` + `audio_name` 相同（即同一本书的同一种合成方式）
     * 且状态属于"未终结"：processing / partial / failed。
     * `completed` 不复用 —— 那是已完成的历史记录，续跑按钮也不会出现在它上面。
     * 复用时**保留原有的 completed_segments**：那是已落盘的段数，
     * 重置成 0 会让用户"已完成 27 段"瞬间变 "0 段"，看着像倒退。
     *
     * @return 实际使用的 taskId（复用时是旧行的 task_id）
     */
    suspend fun upsertLocalTask(
        bookName: String,
        audioName: String,
        newTaskId: Long
    ): Long = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase
            // 只查一条，取 rowid 最大（=最近建立）的那行
            db.query(
                DatabaseHelper.TABLE_TASKS,
                arrayOf("task_id", "completed_segments"),
                "book_name = ? AND audio_name = ? AND status IN (?,?,?)",
                arrayOf(bookName, audioName, "processing", "partial", "failed"),
                null, null, "id DESC", "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val reusedId = cursor.getLong(0)
                    val keptDone = cursor.getInt(1)
                    // 状态回到 processing，进度数字**保持** keptDone 不动。
                    //
                    // ⚠️ **必须清掉 server_task_id**：这是复用行时最容易漏的一步。
                    // 复用意味着「这一行属于新一轮合成」，而 server_task_id 是**上一轮**
                    // 服务端任务的主键。留着它，TtsFragment 的 SSE/轮询会把新一轮的进度
                    // 挂到旧任务上（`TaskPollingManager` 按 server_task_id 匹配），
                    // 表现是：这一行永远匹配不上任何服务端结果，卡在 processing。
                    // 本机合成侧另有硬约束：本机任务的 server_task_id **必须为 null**，
                    // 写了假 id 会让轮询去查不存在的服务端任务。
                    db.execSQL(
                        "UPDATE ${DatabaseHelper.TABLE_TASKS} SET status = ?, " +
                            "completed_segments = ?, error_message = NULL, " +
                            "server_task_id = NULL WHERE task_id = ?",
                        arrayOf("processing", keptDone, reusedId)
                    )
                    // 收敛：删掉**同一本书、同一种合成方式的更旧残留行**。
                    // 「复用」只能防止今后不再新增，改动前堆下的那几行不会自己消失，
                    // 书架上仍会看到多条同名任务各自停在不同时的进度上。
                    //
                    // 只删 status != 'processing' 的：真的正在跑的那一轮（如果另有
                    // 一轮在跑）必须留着，不能因为"清理显示"把活干掉。
                    //
                    // ⚠️ 更要加 `server_task_id IS NULL`：在线合成的行带着服务端任务
                    // 主键，它是**服务端历史**的锚点（出错时靠它回查、用户看历史
                    // 进度也靠它）。删掉等于丢历史。本机合成的行该字段恒为 null，
                    // 所以这条条件恰好只清理本机侧堆出来的脏行 —— 而堆脏行的
                    // 恰恰只有本机侧（每次点续跑都 INSERT 一次）。
                    val removed = db.delete(
                        DatabaseHelper.TABLE_TASKS,
                        "book_name = ? AND audio_name = ? AND task_id != ? " +
                            "AND status != ? AND server_task_id IS NULL",
                        arrayOf(bookName, audioName, reusedId.toString(), "processing")
                    )
                    if (removed > 0) {
                        Log.i(TAG, "清理同一本书的 $removed 条历史残留任务行")
                    }
                    Log.i(
                        TAG,
                        "复用已有本机任务行 taskId=$reusedId" +
                            "（$bookName，已完成段数保持 $keptDone）"
                    )
                    // ⚠️ 复用分支也必须通知：这里已经改了库（状态回到 processing、
                    // 清掉上一轮的错误文案、删掉残留行），而本方法的通知只在
                    // 「新建」路径上（见下方 return newTaskId 之前那句）。
                    // 少了它，用户点「继续合成」后书架仍显示「部分完成」+ 上次的失败原因，
                    // 直到解析阶段第一次 updateStatus 才刷新 —— 大书模型加载 + 解析
                    // 要十几到几十秒，这段时间界面是错的，用户还可能再点一次。
                    notifyTasksChanged()
                    return@withContext reusedId
                }
            }
            // 没有可复用的行（首次合成）⇒ 走原来的新建逻辑。
            // newTaskId 由调用方给，必须**真的插进去了**才算数（见下方回读校验）。
            saveTask(bookName, audioName, newTaskId)
            if (!localTaskRowExists(taskId = newTaskId)) {
                // 插入失败（最常见是 task_id 撞 UNIQUE —— 调用方用
                // System.currentTimeMillis() 当 id，同一毫秒内创建两本书就会撞；
                // 低端机时钟粒度 1~10ms，批量合成多本时概率不低）。
                // 绝不能就这么把 newTaskId 返回出去：它对应的行不存在，
                // 后续所有 `WHERE task_id = ?` 的 UPDATE 都会**命中 0 行且不报错**，
                // 于是合成跑完了、书架上却连一条记录都没有，用户完全无法理解。
                throw IllegalStateException(
                    "本机任务行插入后查不到：taskId=$newTaskId（多半是 task_id 撞唯一约束）"
                )
            }
            // 新建/复用都要通知：合成页往往在任务行建好之前就被关掉了，
            // 不通知的话书架上要等用户切回来才看得到这条任务。
            notifyTasksChanged()
            newTaskId
        } catch (e: Exception) {
            Log.e(TAG, "登记本机任务失败: ${e.message}", e)
            // 登记失败不该阻断合成，但**绝不能返回一个库里不存在的 id**。
            // 这里向上抛，由调用方（LocalSynthesisService）决定怎么办；
            // 早先的写法是再试一次 saveTask 然后照样返回 newTaskId，
            // 结果就是「合成跑完、书架空空如也」且没有任何错误日志指向真正原因。
            throw e
        }
    }

    /** 该 task_id 在库里是否真有对应行（用于插入后的回读校验）。 */
    private suspend fun localTaskRowExists(taskId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            databaseHelper.readableDatabase.query(
                DatabaseHelper.TABLE_TASKS,
                arrayOf("task_id"),
                "task_id = ?",
                arrayOf(taskId.toString()),
                null, null, null, "1"
            ).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.e(TAG, "校验本机任务行是否存在时出错: ${e.message}", e)
            false
        }
    }

    /**
     * 保存本地任务记录。
     * suspend + withContext(IO)，由调用方 await，与 updateServerTaskId
     * 的另一次 IO 派发之间才有先后保证（fire-and-forget 乱序时 UPDATE 会命中 0 行）。
     */
    suspend fun saveTask(bookName: String, audioName: String, taskId: Long) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "========== 开始保存任务 ==========")
            Log.d(TAG, "参数: bookName=$bookName, audioName=$audioName, taskId=$taskId")

            val db = databaseHelper.writableDatabase
            Log.d(TAG, "获取数据库连接成功")

            val sql = "INSERT INTO ${DatabaseHelper.TABLE_TASKS} " +
                "(book_name, audio_name, task_id, server_task_id, status, total_segments, completed_segments) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)"
            Log.d(TAG, "SQL: $sql")
            Log.d(TAG, "参数值: [$bookName, $audioName, $taskId, null, processing, 0, 0]")

            db.execSQL(sql, arrayOf(bookName, audioName, taskId, null, "processing", 0, 0))

            Log.d(TAG, "✅ 任务保存成功! taskId=$taskId")

            db.query(
                DatabaseHelper.TABLE_TASKS,
                arrayOf("task_id", "server_task_id", "status"),
                "task_id = ?",
                arrayOf(taskId.toString()),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val savedServerTaskId = if (cursor.isNull(1)) "null" else cursor.getLong(1).toString()
                    val savedStatus = cursor.getString(2)
                    Log.d(TAG, "✅ 验证成功: task_id=$taskId, server_task_id=$savedServerTaskId, status=$savedStatus")
                } else {
                    Log.e(TAG, "❌ 验证失败: 找不到 task_id=$taskId 的记录!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 保存任务失败: ${e.message}", e)
            Log.e(TAG, "保存任务失败", e)
        }
    }

    /**
     * 把服务端任务列表合并进本地 `tasks` 表（跨端恢复的唯一写入口）。
     *
     * ## 解决什么问题
     * 任务行原本只在本机创建，服务端没有任何东西能把它拉回来。换机 / 重装 / 清数据后：
     * 服务端任务还在、积分已扣、音频也在，App 里却一条记录都没有 —— 用户拿不回自己
     * 付过钱的音频，也看不到"那本书合成到哪了"。
     *
     * ## 匹配规则（**按 server_task_id 匹配，绝不按书名**）
     * 书名会重复（同名文件、改名、书城同名书），按书名合并会串到别人的任务上；
     * `server_task_id` 是服务端主键，天然唯一。
     * 已经存在 → 只补写状态相关列（status / completed_segments / total_segments /
     * error_message），**不动** `book_name`、`audio_name`、`audio_files_path`：
     * 本地可能已经改过名、指定过音色、下载过音频，覆盖等于把用户的本地状态抹掉。
     * 不存在 → 插入一行"占位"任务，`audio_name` 记为 [RESTORED_AUDIO_NAME]
     * （服务端不保存音色/参考音频名，编不出来就如实说这是云端任务）。
     *
     * ## 为什么 task_id 用负数
     * `task_id` 有 UNIQUE 约束，本地新建时取 `System.currentTimeMillis()`（正数）。
     * 用 `-serverTaskId` 既能保证唯一，又永远不会和时间戳撞车。
     * 副作用是它的绝对值不等于时间戳 —— 但 `task_id` 只是"业务 ID"，真正的排序
     * 走 `created_at`，轮询按 `(task_id, server_task_id)` 成对使用，不受影响。
     *
     * `created_at` 显式写入服务端创建时间（已归一化为 `yyyy-MM-dd HH:mm:ss`），
     * 否则恢复出来的任务全部按"同步时刻"排到最前面，把用户真正最新的任务压下去。
     *
     * @return 实际新增或更新的行数（0 = 与服务端完全一致）
     */
    suspend fun upsertServerTasks(tasks: List<ServerTaskSnapshot>): Int = withContext(Dispatchers.IO) {
        if (tasks.isEmpty()) return@withContext 0
        var changed = 0
        try {
            val db = databaseHelper.writableDatabase
            // 整批一个事务：恢复通常是几十条，逐条自动提交会让书架在同步过程中
            // 看到"补了一半"的中间态（刷新回调是按行触发的）。
            db.beginTransaction()
            try {
                for (snapshot in tasks) {
                    val local = db.query(
                        DatabaseHelper.TABLE_TASKS,
                        arrayOf("status", "completed_segments", "total_segments", "error_message"),
                        "server_task_id = ?",
                        arrayOf(snapshot.serverTaskId.toString()),
                        null, null, null, "1"
                    ).use { c ->
                        if (!c.moveToFirst()) null
                        else LocalTaskState(
                            c.getString(0),
                            c.getInt(1),
                            c.getInt(2),
                            if (c.isNull(3)) null else c.getString(3)
                        )
                    }

                    if (local == null) {
                        val values = ContentValues().apply {
                            put("book_name", snapshot.bookName)
                            put("audio_name", RESTORED_AUDIO_NAME)
                            put("task_id", -snapshot.serverTaskId)
                            put("server_task_id", snapshot.serverTaskId)
                            put("status", snapshot.status)
                            put("total_segments", snapshot.totalSegments)
                            put("completed_segments", snapshot.completedSegments)
                            put("error_message", snapshot.errorMessage)
                            if (snapshot.createdAt != null) put("created_at", snapshot.createdAt)
                        }
                        // CONFLICT_IGNORE 而不是抛异常：`task_id` 万一已被占（例如上一轮
                        // 同步插过、但 server_task_id 被别处清空过），忽略即可，
                        // 不能让一条脏数据打断整批恢复。
                        val rowId = db.insertWithOnConflict(
                            DatabaseHelper.TABLE_TASKS, null, values, SQLiteDatabase.CONFLICT_IGNORE
                        )
                        if (rowId != -1L) {
                            changed++
                        } else {
                            Log.w(TAG, "恢复任务被忽略（task_id 冲突）: serverTaskId=${snapshot.serverTaskId}")
                        }
                        continue
                    }

                    if (local.status == snapshot.status &&
                        local.completedSegments == snapshot.completedSegments &&
                        local.totalSegments == snapshot.totalSegments &&
                        local.errorMessage == snapshot.errorMessage
                    ) {
                        // 无变化就不写：每次启动都会同步，无谓的 UPDATE 会产生
                        // 变更通知 → 书架刷新闪烁。
                        continue
                    }
                    val values = ContentValues().apply {
                        put("status", snapshot.status)
                        put("completed_segments", snapshot.completedSegments)
                        put("total_segments", snapshot.totalSegments)
                        put("error_message", snapshot.errorMessage)
                    }
                    db.update(
                        DatabaseHelper.TABLE_TASKS, values,
                        "server_task_id = ?", arrayOf(snapshot.serverTaskId.toString())
                    )
                    changed++
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            // 恢复失败不能影响 App 正常启动：本地已有的任务照旧可用，
            // 下次启动会再同步一遍。
            Log.e(TAG, "恢复服务端任务失败: ${e.message}", e)
            return@withContext 0
        }
        if (changed > 0) {
            Log.i(TAG, "从服务端恢复任务：新增/更新 $changed 条")
            notifyTasksChanged()
        }
        changed
    }

    /**
     * 由「任务表之外的数据源」触发书架重读。
     *
     * 目前唯一使用者是 [TaskSyncManager]：服务端下发的是**全局排队规模**
     * （`queuedCount`），它不落 `tasks` 表、也不属于任何一条任务，
     * 但「排队中 / 等待派发」那几行上要显示「队列约 N 个任务」。
     * 规模变了而任务行数据没变时，[upsertServerTasks] 不会触发刷新 ——
     * 那种情况下书架上的数字就会停在旧值，所以留这个入口。
     *
     * 回调语义与 [addTasksChangedListener] 完全相同：在调用方线程上同步执行，
     * 实现方自己切线程。
     */
    fun notifyExternalTasksChange() = notifyTasksChanged()

    /** [upsertServerTasks] 读取本地行时用的中转结构（只关心"是否变化"）。 */
    private data class LocalTaskState(
        val status: String,
        val completedSegments: Int,
        val totalSegments: Int,
        val errorMessage: String?
    )

    /**
     * 更新任务状态，终态（completed/partial/failed）时发系统通知。
     * 不负责刷新 LiveData（由 BookViewModel 在调用后 loadTasks）。
     */
    suspend fun updateStatus(taskId: Long, status: String, completedSegments: Int, totalSegments: Int) =
        withContext(Dispatchers.IO) {
            try {
                val db = databaseHelper.writableDatabase
                db.execSQL(
                    "UPDATE ${DatabaseHelper.TABLE_TASKS} SET status = ?, completed_segments = ?, total_segments = ? WHERE task_id = ?",
                    arrayOf(status, completedSegments, totalSegments, taskId)
                )
                Log.d(TAG, "Task updated: taskId=$taskId, status=$status, progress=$completedSegments/$totalSegments")
                // 写完就通知：调用方（Service / 轮询线程）不会自己刷新书架，
                // 不通知的话书架上的状态会一直停在旧快照上。
                notifyTasksChanged()

                // 终态同样发系统通知（TaskPollingManager 也会发，TaskNotificationHelper 内部按任务去重）
                if (status == "completed" || status == "partial" || status == "failed") {
                    val bookName = db.query(
                        DatabaseHelper.TABLE_TASKS,
                        arrayOf("book_name"),
                        "task_id = ?",
                        arrayOf(taskId.toString()),
                        null, null, null
                    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    if (bookName != null) {
                        TaskNotificationHelper.notifyTerminal(context, taskId, bookName, status)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update task failed: ${e.message}")
            }
            Unit
        }

    /**
     * 只更新任务表中的「阶段文案」（书架里那条任务记录显示当前在干什么）。
     *
     * 与 [updateStatus] 分开是刻意的：updateStatus 在终态会发系统通知，
     * 而阶段文案（「正在加载模型」这类）会频繁变更，走同一条路会不断触发通知，
     * 也会每次都把 completed/total 一起重写。这里只动一列。
     */
    suspend fun updateProgressText(taskId: Long, progressText: String?) = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase
            db.execSQL(
                "UPDATE ${DatabaseHelper.TABLE_TASKS} SET progress_text = ? WHERE task_id = ?",
                arrayOf(progressText, taskId)
            )
            notifyTasksChanged()
        } catch (e: Exception) {
            // 阶段文案纯粹是锦上添花，写失败绝不能影响合成的正常推进
            Log.w(TAG, "更新阶段文案失败（不影响合成）: ${e.message}")
        }
        Unit
    }

    /**
     * 按**行主键 rowid** 更新状态，供「拿着 [Task] 列表回写」的场景使用。
     *
     * ## 为什么必须单独有一套按 rowid 的方法
     * 本类其余更新方法的 WHERE 键都是 `task_id` 列 —— 那是**业务 ID 空间**，
     * 值是创建任务时取的 `System.currentTimeMillis()`（见 [saveTask]）。
     * 而 [getTasks] 返回的 [Task.id] 是 **tasks 表的行主键**，与 `task_id` 列
     * 是两个不同的数：rowid 从 1 递增，task_id 是 13 位毫秒时间戳。
     *
     * 拿 rowid 去 `WHERE task_id = ?` 会**命中 0 行且不报错** ——
     * 表现为「代码明明写了、书架却毫无变化」，是极难定位的一类静默失败。
     * [Task] 又没有暴露 `task_id` 列（见 [getTasks] 的投影：读的是 index 0 的 `id`），
     * 所以这类场景只能按 rowid 定位。
     *
     * ⚠️ 两种 ID 空间在本仓库反复踩坑（见 [AppRepository.getActivePollingTasks]
     * 与 [updateStatus] 的注释）。**改动前务必确认调用方手里拿到的是哪一个。**
     */
    suspend fun updateStatusByRowId(
        rowId: Long,
        status: String,
        completedSegments: Int,
        totalSegments: Int
    ) = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase
            db.execSQL(
                "UPDATE ${DatabaseHelper.TABLE_TASKS} SET status = ?, completed_segments = ?, total_segments = ? WHERE id = ?",
                arrayOf(status, completedSegments, totalSegments, rowId)
            )
            val bookName = db.query(
                DatabaseHelper.TABLE_TASKS,
                arrayOf("book_name"),
                "id = ?",
                arrayOf(rowId.toString()),
                null, null, null
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            // 改用 rowid 作为通知 id：终态通知按 id 去重，而这里手里只有 rowid
            if (bookName != null &&
                (status == "completed" || status == "partial" || status == "failed")
            ) {
                TaskNotificationHelper.notifyTerminal(context, rowId, bookName, status)
            }
            notifyTasksChanged()
        } catch (e: Exception) {
            // 对账失败必须留痕：否则任务会继续挂在「合成中」上，正是本方法要消灭的现象
            Log.e(TAG, "按 rowid 更新任务状态失败: rowId=$rowId: ${e.message}")
        }
        Unit
    }

    /** [updateProgressText] 的 rowid 版本，用途与限制见 [updateStatusByRowId]。 */
    suspend fun updateProgressTextByRowId(rowId: Long, progressText: String?) =
        withContext(Dispatchers.IO) {
            try {
                val db = databaseHelper.writableDatabase
                db.execSQL(
                    "UPDATE ${DatabaseHelper.TABLE_TASKS} SET progress_text = ? WHERE id = ?",
                    arrayOf(progressText, rowId)
                )
                notifyTasksChanged()
                } catch (e: Exception) {
                Log.w(TAG, "按 rowid 更新阶段文案失败（不影响合成）: ${e.message}")
            }
            Unit
        }

    suspend fun updateServerTaskId(localTaskId: Long, serverTaskId: Long) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "========== 更新服务器任务ID ==========")
            Log.d(TAG, "本地ID: $localTaskId -> 服务器ID: $serverTaskId")

            val db = databaseHelper.writableDatabase

            val existingServerId = db.query(
                DatabaseHelper.TABLE_TASKS,
                arrayOf("task_id", "server_task_id"),
                "task_id = ?",
                arrayOf(localTaskId.toString()),
                null, null, null
            ).use { checkCursor ->
                if (checkCursor.moveToFirst()) {
                    if (checkCursor.isNull(1)) "null" else checkCursor.getLong(1).toString()
                } else {
                    Log.w(TAG, "⚠️ 警告: 找不到 task_id=$localTaskId 的记录!")
                    null
                }
            }
            if (existingServerId != null) {
                Log.d(TAG, "更新前: task_id=$localTaskId, server_task_id=$existingServerId")
            }

            db.execSQL(
                "UPDATE ${DatabaseHelper.TABLE_TASKS} SET server_task_id = ? WHERE task_id = ?",
                arrayOf(serverTaskId, localTaskId)
            )

            Log.d(TAG, "✅ Server task ID updated successfully")

            db.query(
                DatabaseHelper.TABLE_TASKS,
                arrayOf("task_id", "server_task_id"),
                "task_id = ?",
                arrayOf(localTaskId.toString()),
                null, null, null
            ).use { verifyCursor ->
                if (verifyCursor.moveToFirst()) {
                    val newServerId = if (verifyCursor.isNull(1)) "null" else verifyCursor.getLong(1).toString()
                    Log.d(TAG, "✅ 验证成功: task_id=$localTaskId, server_task_id=$newServerId")
                } else {
                    Log.e(TAG, "❌ 验证失败: 记录丢失!")
                }
            }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Update server task ID failed: ${e.message}", e)
            }
            Unit
        }

    /**
     * 更新任务失败原因（tasks.error_message）。
     *
     * 与 saveTask 同样的考虑：suspend + withContext(IO)，调用方 await 后才刷新列表，
     * 否则「写库」与随后的 loadTasks 可能乱序，界面仍显示上一次的旧文案。
     */
    suspend fun updateErrorMessage(taskId: Long, errorMessage: String?) = withContext(Dispatchers.IO) {
        try {
            databaseHelper.writableDatabase.execSQL(
                "UPDATE ${DatabaseHelper.TABLE_TASKS} SET error_message = ? WHERE task_id = ?",
                arrayOf(errorMessage, taskId)
            )
            Log.d(TAG, "Task error message updated: taskId=$taskId, error=$errorMessage")
        } catch (e: Exception) {
            Log.e(TAG, "Update task error message failed: ${e.message}")
        }
    }

    suspend fun updateAudioPath(taskId: Long, audioFilesPath: String) = withContext(Dispatchers.IO) {
        try {
            val db = databaseHelper.writableDatabase
            db.execSQL(
                "UPDATE ${DatabaseHelper.TABLE_TASKS} SET audio_files_path = ? WHERE task_id = ?",
                arrayOf(audioFilesPath, taskId)
            )
            Log.d(TAG, "Task audio path updated: taskId=$taskId, path=$audioFilesPath")
        } catch (e: Exception) {
            Log.e(TAG, "Update task audio path failed: ${e.message}")
        }
    }

    /**
     * [deleteForBook] 的结果：删除的本地行数 + 需同步通知服务端删除的任务 ID。
     *
     * `serverTaskIds` 只含非空值（本机合成任务没有服务端任务 ID，会被跳过）。
     * 必须在删行**之前**取出，否则删完就查不到了。
     */
    data class DeleteForBookResult(
        val deleted: Int,
        val serverTaskIds: List<Long>
    )

    /**
     * 删除某本书的全部合成任务记录（按 book_name），同步 forget TaskPollingManager。
     * 不动 audio_segments（本地音频可由「存储管理」单独清理）。
     *
     * 删行前会先取出这些任务关联的服务端任务 ID 一并返回：调用方据此通知服务端
     * 删除，否则冷启动时 [TaskSyncManager]（服务端为真源）会把它们重新拉回本地。
     *
     * @return [DeleteForBookResult]（删除条数 + 非空服务端任务 ID 列表）
     */
    suspend fun deleteForBook(bookName: String): DeleteForBookResult = withContext(Dispatchers.IO) {
        var deleted = 0
        val serverTaskIds = mutableListOf<Long>()
        try {
            val db = databaseHelper.writableDatabase
            // 先取 task_id + server_task_id：前者用于删完同步 forget TaskPollingManager
            // （其内部 Map 以 task_id 为 key）；后者要在删行**之前**取出，供调用方
            // 通知服务端删除，否则服务端任务会在下次同步时被拉回本地。
            val taskIds = mutableListOf<Long>()
            db.query(
                DatabaseHelper.TABLE_TASKS,
                arrayOf("task_id", "server_task_id"),
                "book_name = ?",
                arrayOf(bookName),
                null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    taskIds.add(cursor.getLong(0))
                    if (!cursor.isNull(1)) serverTaskIds.add(cursor.getLong(1))
                }
            }

            deleted = db.delete(DatabaseHelper.TABLE_TASKS, "book_name = ?", arrayOf(bookName))
            Log.d(TAG, "已删除《$bookName》的 $deleted 条任务记录")

            taskIds.forEach { TaskPollingManager.forgetTask(it) }
        } catch (e: Exception) {
            Log.e(TAG, "删除任务记录失败: ${e.message}")
        }
        DeleteForBookResult(deleted, serverTaskIds)
    }

    /**
     * 删除**全部**任务记录（存储管理里「全部清理音频」时用）。
     *
     * 与 [deleteForBook] 同样的要求：删完必须把 [TaskPollingManager] 的内部状态
     * 一并 forget，否则轮询线程还在查已经删掉的任务。
     */
    suspend fun deleteAll(): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        try {
            val db = databaseHelper.writableDatabase
            val taskIds = mutableListOf<Long>()
            db.query(
                DatabaseHelper.TABLE_TASKS,
                arrayOf("task_id"),
                null, null, null, null, null
            ).use { cursor ->
                while (cursor.moveToNext()) taskIds.add(cursor.getLong(0))
            }
            deleted = db.delete(DatabaseHelper.TABLE_TASKS, null, null)
            Log.d(TAG, "已删除全部 $deleted 条任务记录")
            taskIds.forEach { TaskPollingManager.forgetTask(it) }
        } catch (e: Exception) {
            Log.e(TAG, "删除全部任务记录失败: ${e.message}")
        }
        deleted
    }

    // ---------------- 轮询回写（供 TaskPollingManager 调用） ----------------    // 这几个方法刻意是**同步**的：调用方 TaskPollingManager 跑在 Dispatchers.IO 上，
    // 且需要「更新了几行」来判断本轮有没有变化，包成 suspend 只会徒增一层调度。
    // 调用前请确认不在主线程。

    /**
     * 轮询回写：仅当 status / completed_segments / total_segments 三者之一真正变化时才写库。
     *
     * 差异条件放在 WHERE 里而不是「先查后写」：既避免无变化的轮询产生 DB 写入，
     * 也让返回值直接表达「本轮有没有变化」——TaskPollingManager 据此决定是否通知刷新，
     * 否则每轮都刷新会造成列表无谓闪动。
     *
     * @return 实际更新的行数（0 = 无变化）
     */
    fun updateStatusIfChanged(
        taskId: Long,
        status: String,
        completedSegments: Int,
        totalSegments: Int
    ): Int {
        return try {
            val values = ContentValues().apply {
                put("status", status)
                put("completed_segments", completedSegments)
                put("total_segments", totalSegments)
            }
            val rows = databaseHelper.writableDatabase.update(
                DatabaseHelper.TABLE_TASKS,
                values,
                "task_id = ? AND (status != ? OR completed_segments != ? OR total_segments != ?)",
                arrayOf(taskId.toString(), status, completedSegments.toString(), totalSegments.toString())
            )
            // 只有真的变了才通知：轮询每 2 秒问一次，不变就刷屏没意义
            if (rows > 0) notifyTasksChanged()
            rows
        } catch (e: Exception) {
            Log.e(TAG, "回写任务状态失败: ${e.message}")
            0
        }
    }

    /** 取任务所属书名（终态通知需要）；任务行不存在时返回 null。 */
    fun findBookName(taskId: Long): String? {
        return try {
            databaseHelper.readableDatabase.query(
                DatabaseHelper.TABLE_TASKS,
                arrayOf("book_name"),
                "task_id = ?",
                arrayOf(taskId.toString()),
                null, null, null
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Log.e(TAG, "查询任务书名失败: ${e.message}")
            null
        }
    }

    /**
     * 反向查：由书名取该任务对应的服务端任务 ID（跨端进度同步键）。
     * 用于将本地播放进度上传到正确的服务端任务。任务行不存在或未关联服务端任务时返回 null。
     */
    fun findServerTaskIdByBookName(bookName: String): Long? {
        return try {
            databaseHelper.readableDatabase.query(
                DatabaseHelper.TABLE_TASKS,
                arrayOf("server_task_id"),
                "book_name = ?",
                arrayOf(bookName),
                null, null, null
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询服务端任务ID失败: ${e.message}")
            null
        }
    }

    /**
     * 收敛失败任务行：**同一本书只保留 rowid 最大（即最近建立）的那条 failed**，
     * 其余 failed 行删掉。
     *
     * ## 为什么需要它
     *
     * 失败行是**只进不出**的：
     *  · 轮询只捞活跃任务（见 [AppRepository.getActivePollingTasks]），
     *    一旦落 failed 就再没有任何机制会碰它；
     *  · [upsertLocalTask] 的复用键是 `book_name + audio_name`，换一款音色 /
     *    换一条参考音频就是另一个 audio_name（在线合成时它就是参考音频文件名），
     *    于是每次重新提交都会**新增**一行；
     *  · 线上唯一的人工清理入口是书架上的垃圾桶，而它按书名**整本删**，
     *    用户为了清两条失败提示得把整本书的任务历史（含已完成的）一起删掉。
     *
     * 三者叠加的结果就是截图里的现象：同一本书下面挂着一条又一条
     * 「失败 + 重试」，越点越 多，而其中没拿到服务端任务 ID 的那几条
     * 重试按钮本来就是死的（[confirmRetryTask] 会直接 Toast 拒绝）。
     *
     * ## 为什么保留最新那条、而不是全删
     *
     * 失败行上唯一的可操作入口就是「重试」。全删等于把用户唯一的重试入口
     * 也拿走，等于逼他回合成页重新提交；留最新那条，既保住了重试能力，
     * 又让同一本书的失败提示**恒定只有一条**，从根上断掉累积。
     *
     * 只动 failed：`partial`（本机部分完成）带着「继续合成剩余 N 段」入口，
     * `completed` 是历史成果，都必须留着。
     *
     * 调用时机：每次刷新书架（[BookViewModel.loadTasks]）前跑一次 ——
     * 所有状态回写路径（TtsFragment 的 SSE、LocalSynthesisService、
     * TaskPollingManager 的超时判定）最终都会触发刷新，在这里收口，
     * 就不必在每个写入点各写一遍。
     *
     * @return 删掉的行数（0 = 没有可收敛的残留）
     */
    suspend fun compactFailedTasks(): Int = withContext(Dispatchers.IO) {
        try {
            val removed = databaseHelper.writableDatabase.delete(
                DatabaseHelper.TABLE_TASKS,
                // 同表子查询：SQLite 允许 DELETE 的 WHERE 里引用自身（不像 MySQL
                // 会报 "can't specify target table"），因此无需先查再删。
                "status = ? AND id NOT IN (" +
                    "SELECT MAX(id) FROM ${DatabaseHelper.TABLE_TASKS} " +
                    "WHERE status = ? GROUP BY book_name)",
                arrayOf("failed", "failed")
            )
            if (removed > 0) {
                Log.i(TAG, "收敛失败任务：删除同书较旧的 $removed 条失败行")
            }
            removed
        } catch (e: Exception) {
            // 收敛是「显示整洁度」问题，失败不该影响任务本身的读写。
            Log.e(TAG, "收敛失败任务行出错: ${e.message}", e)
            0
        }
    }

    /**
     * 把仍处于活跃状态（pending / queued / processing）的任务标记为 failed 并写入原因。
     *
     * 条件里限定活跃状态：已终态的任务不该被超时判定反复覆盖错误文案。
     *
     * @return 实际更新的行数（0 = 任务已终态或不存在）
     */
    fun markFailedIfActive(taskId: Long, message: String): Int {
        return try {
            val values = ContentValues().apply {
                put("status", "failed")
                put("error_message", message)
            }
            val rows = databaseHelper.writableDatabase.update(
                DatabaseHelper.TABLE_TASKS,
                values,
                "task_id = ? AND status IN (?, ?, ?)",
                arrayOf(taskId.toString(), "pending", "queued", "processing")
            )
            if (rows > 0) notifyTasksChanged()
            rows
        } catch (e: Exception) {
            Log.e(TAG, "标记任务失败出错: ${e.message}")
            0
        }
    }

    companion object {
        // 沿用 BookVM 日志标签，保持既有日志输出不变，便于排查
        private const val TAG = "BookVM"

        /**
         * 从服务端恢复的任务行使用的 `audio_name`。
         *
         * 服务端不保存"用户当时选了哪个音色 / 哪条参考音频"，恢复不出来就不能瞎编：
         * 编成某个音色名会让用户以为这条任务用的是那个音色。书架上这一列就是给人看的
         * 一句话，如实写"云端任务"即可。
         *
         * ⚠️ **不能带 [LocalSynthesisService.AUDIO_NAME_PREFIX]**：带前缀会被
         * `ShelfAdapter` 判成本机合成（显示"本机 · 不扣积分"且不给下载按钮），
         * `AppRepository.getActivePollingTasks` 也会把它排除在轮询之外 ——
         * 恢复出来的任务将永远不更新进度。
         */
        private const val RESTORED_AUDIO_NAME = "云端任务"

        /**
         * 「任务表已变更」的进程级监听集合。
         *
         * ## 为什么需要它
         * 任务的进度/状态写库分散在三个互不相识的地方：
         *   · 本机合成：[moss.LocalSynthesisService] 自己拿 repository 写；
         *   · 在线合成：[TaskPollingManager] 写；
         *   · 页面侧：[BookViewModel] 自己的方法写。
         * 以前只有"页面自己写的"那条路会顺带刷新 UI（因为调用点都跟着
         * `loadTasks()`），另外两条路写完库**谁也不通知**，于是书架上的状态
         * 一直停在最后一次刷新的快照上 —— 本机合成整本书跑完，书架可能还
         * 显示"合成中"或干脆连任务行都没有。
         *
         * 广播/ContentObserver 都救不了这个场景：写库方在 Service 里，
         * 刷新区在 Activity 的 ViewModel 里，两边生命周期不重叠
         * （合成页被 replace 掉后订阅就解绑了）。**同进程**的回调才能兜住。
         *
         * ## 契约
         * 回调在**写库线程**上被调用（多数是 IO 线程），实现方必须自己切线程；
         * 回调里只应做"标记需要刷新"这类轻活，不要直接查库。
         */
        private val tasksChangedListeners = CopyOnWriteArraySet<() -> Unit>()

        /** 注册"任务表已变更"监听。返回的注册对象需在不用时 [removeTasksChangedListener]。 */
        fun addTasksChangedListener(listener: () -> Unit) {
            tasksChangedListeners.add(listener)
        }

        /** 注销监听（ViewModel 销毁时必须调用，否则回调会一直拽着旧的 Activity 作用域）。 */
        fun removeTasksChangedListener(listener: () -> Unit) {
            tasksChangedListeners.remove(listener)
        }

        /**
         * 通知所有监听方"任务表变了"。
         *
         * 用 runCatching 兜住单个监听方的异常：监听方的问题不能反过来影响
         * 写库方（合成进度写入是主链路，UI 刷新是附属动作）。
         */
        private fun notifyTasksChanged() {
            tasksChangedListeners.forEach { listener ->
                runCatching { listener() }
                    .onFailure { Log.w(TAG, "任务变更回调异常: ${it.message}") }
            }
        }
    }
}
