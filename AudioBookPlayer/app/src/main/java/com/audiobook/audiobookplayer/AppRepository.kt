package com.audiobook.audiobookplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.audiobook.audiobookplayer.model.ReferenceAudio
import com.audiobook.audiobookplayer.moss.LocalSynthesisService

/**
 * 单一数据访问层。
 *
 * 本类作为**唯一入口**持有 DatabaseHelper，统一暴露数据库句柄与跨文件共享的 CRUD，
 * 各调用方经本类访问，避免散落 SQL 与表名重复定义——调用方自带的本地常量
 * （如 `TASK_TABLE_NAME = "tasks"`）会与 DatabaseHelper 重复，存在「建表语义漂移」风险。
 * （DatabaseHelper 仍负责建表/onUpgrade，是表结构单一真源；其 TABLE_* 常量即表名唯一真源。）
 */
object AppRepository {

    @Volatile
    private var helper: DatabaseHelper? = null

    /** 绑定 applicationContext 并持有进程内唯一 DatabaseHelper 单例；返回本单例。 */
    fun getInstance(context: Context): AppRepository {
        if (helper == null) {
            synchronized(this) {
                if (helper == null) {
                    helper = DatabaseHelper.getInstance(context.applicationContext)
                }
            }
        }
        return this
    }

    /** 底层 SQLiteOpenHelper（单例）。未初始化时抛错，提示调用方先 getInstance(ctx)。 */
    val databaseHelper: DatabaseHelper
        get() = helper ?: error("AppRepository 尚未初始化，请先调用 getInstance(context)")

    val readableDatabase: SQLiteDatabase get() = databaseHelper.readableDatabase
    val writableDatabase: SQLiteDatabase get() = databaseHelper.writableDatabase

    // ---- 跨文件共享的 CRUD ----

    /**
     * 取活跃轮询任务 (localId, serverTaskId?)：status 为 pending/queued/processing。
     *
     * ⚠️ **排除本机合成任务**（`audio_name` 带「本机合成 · 」前缀）。
     * 本机任务永远没有 `server_task_id`（它不上传、不建服务端任务），
     * 而 [TaskPollingManager] 对"没有服务端 ID"的任务有一个 30 分钟的降级判定：
     * 超时就把它标成 failed（"未能获取服务端任务ID"）。本机合成一本要跑
     * 20~100 分钟 —— 合成还在跑，书架上却变成"失败"，而且配的报错文案驴唇不对马嘴。
     *
     * 不能简单按 `server_task_id IS NULL` 排除：在线任务在上传成功前也是 null，
     * 那段时间正需要那个降级机制兜底（上传中断就别再假装在合成了）。
     * 所以按**本机/在线**区分，而不是按有没有服务端 ID。
     */
    fun getActivePollingTasks(): List<Pair<Long, Long?>> {
        val db = readableDatabase
        db.query(
            DatabaseHelper.TABLE_TASKS,
            arrayOf("task_id", "server_task_id"),
            "status IN (?, ?, ?) AND (audio_name IS NULL OR audio_name NOT LIKE ?)",
            arrayOf("pending", "queued", "processing", LocalSynthesisService.AUDIO_NAME_PREFIX + "%"),
            null, null, null
        ).use { cursor ->
            val list = mutableListOf<Pair<Long, Long?>>()
            while (cursor.moveToNext()) {
                list.add(cursor.getLong(0) to if (cursor.isNull(1)) null else cursor.getLong(1))
            }
            return list
        }
    }

    /**
     * 参考音频列表（按创建时间倒序）。原 ReferenceAudioRepository.listAll 内联 rawQuery，现统一到此处。
     */
    fun listReferenceAudios(): List<ReferenceAudio> {
        val db = readableDatabase
        db.rawQuery(
            """
            SELECT id, name, file_path, source, server_id, description, file_size,
                   CAST(strftime('%s', COALESCE(created_at, 'now')) AS INTEGER) * 1000 AS created_ts
            FROM ${DatabaseHelper.TABLE_REFERENCE_AUDIO}
            ORDER BY created_at DESC, id DESC
            """.trimIndent(),
            null
        ).use { cursor ->
            val list = mutableListOf<ReferenceAudio>()
            while (cursor.moveToNext()) {
                list.add(
                    ReferenceAudio(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        filePath = cursor.getString(2),
                        source = cursor.getString(3),
                        serverId = if (cursor.isNull(4)) null else cursor.getLong(4),
                        description = if (cursor.isNull(5)) null else cursor.getString(5),
                        fileSize = cursor.getLong(6),
                        createdAt = cursor.getLong(7)
                    )
                )
            }
            return list
        }
    }
}
