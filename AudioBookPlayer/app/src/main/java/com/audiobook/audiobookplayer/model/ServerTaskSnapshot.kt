package com.audiobook.audiobookplayer.model

/**
 * 服务端任务列表（`GET /api/tts/tasks`）里的一条任务快照。
 *
 * ## 为什么单独建一个类，而不直接复用 [Task]
 * [Task] 是**本地 tasks 表的一行**，它的 `id` 是 rowid、`taskId` 是本地毫秒时间戳，
 * 与服务端任务主键是两个不同的 ID 空间（见 TaskRepository 里反复强调的那条坑）。
 * 恢复流程手里只有服务端主键，没有本地 rowid —— 直接拿 [Task] 表达会诱导调用方
 * 把服务端 id 写进 `id` 字段，随后按 rowid 回写就会静默命中 0 行。
 * 用一个字段更少、语义更窄的类，可以在类型层面挡住这类误用。
 *
 * ## 字段口径
 * - [serverTaskId]：服务端 `tasks.id`，同时用于匹配本地已有行与回填 `server_task_id`。
 * - [bookName]：服务端 `original_book_name`。本地有同名电子书时书架会把它挂到那本书下面；
 *   没有时 mergeShelf 会造一条"只有任务、没有文件"的虚拟条目，任务不会消失。
 * - [createdAt]：已归一化为 `yyyy-MM-dd HH:mm:ss`（与 SQLite `CURRENT_TIMESTAMP` 同格式），
 *   否则 `ORDER BY created_at DESC` 会在"带 T 的 ISO 串"和"带空格的本地串"之间排错序。
 *   为 null = 服务端没给时间，落库时走列默认值。
 */
data class ServerTaskSnapshot(
    val serverTaskId: Long,
    val bookName: String,
    val status: String,
    val totalSegments: Int,
    val completedSegments: Int,
    val errorMessage: String? = null,
    val createdAt: String? = null
)
