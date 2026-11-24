package com.audiobook.audiobookplayer.model

/**
 * 生词本上传请求体（新增 / 批量同步共用，与服务端 WordBookItemRequest 对齐）。
 *
 * 服务端唯一身份 = sw（strip-word 归一化键，由客户端 [com.audiobook.audiobookplayer.DictionaryManager.stripWord]
 * 计算，服务端不重算），跨端 (user_id, sw) 唯一。translations 用 LF (0x0A) 分隔义项，
 * Android 本地存字面量 `\n` 两字符，wire 层转换在 sync 边界做。
 *
 * ## 出处 6 字段与 Gson 的 null 处理（关键决策）
 * 字段名用 Gson 默认驼峰（`bookName`/`bookKey`/`storeBookId`/`segmentNumber`/
 * `lineNumber`/`sentenceText`），与服务端 JSON 键逐字一致，**无需** @SerializedName。
 *
 * RetrofitClient 全局配了 `GsonBuilder().serializeNulls()`（不是 Gson 默认的丢弃 null），
 * 所以这些字段为 null 时会**显式**发出 `"bookName": null` 之类。经核对服务端
 * `WordBookItemRequest.hasSource()` 的判定是「bookName 或 bookKey 非 null 且非空白」，
 * 显式 null ⇒ hasSource()=false ⇒ 服务端**保留已有出处**、绝不把跳转信息清成 null ——
 * 这恰好就是契约里「旧客户端不带出处时服务端保留原值」的语义，安全。
 * 因此这里既不用再开 serializeNulls（全局已开），也不用 @SerializedName，更不用
 * 为「无出处」特判：有出处（非 null）就带 6 键让服务端按「最近一次」刷新，
 * 无出处（null）就发 null 让服务端保留。若哪天改成默认 Gson（丢弃 null），
 * 语义仍成立（省略键 = 不带出处），无需再动本类。
 */
data class WordBookUploadRequest(
    val sw: String,
    val word: String,
    val phonetic: String? = null,
    /** LF 分隔的义项列表（不是字面量 "\n" 两字符） */
    val translations: String? = null,
    /** 书名/展示名 */
    val bookName: String? = null,
    /** 跨端统一书键 s:{storeBookId}/t:{taskId}/n:{归一化书名} */
    val bookKey: String? = null,
    /** 书城书 id；本地书为 null */
    val storeBookId: Long? = null,
    /** 段号，1-based */
    val segmentNumber: Int? = null,
    /** 行号 hint（会随切分粒度漂移，仅兜底） */
    val lineNumber: Int? = null,
    /** 收藏时所在句子/段落原文（定位第一判据） */
    val sentenceText: String? = null
)

/** 批量同步请求体：一次性上报本地已有词条，服务端合并去重后返回全量列表 */
data class SyncWordBookRequest(
    val words: List<WordBookUploadRequest>
)
