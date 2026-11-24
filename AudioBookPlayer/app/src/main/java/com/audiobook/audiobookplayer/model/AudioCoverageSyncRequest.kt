package com.audiobook.audiobookplayer.model

/**
 * 有声覆盖图上报项（Phase3 跨端，与服务端 `AudioCoverageItemRequest` 对齐）。
 *
 * 只报**元数据、不传字节**：某本书（bookKey）在某个来源（source）下已经完成合成的段号集合
 * （doneRanges，1-based 闭区间压缩串）。服务端按 (userId, bookKey, source) 做**并集**合并。
 *
 * 身份三端逐字符一致（见 [com.audiobook.audiobookplayer.CoverageCodec]）：
 *  - bookKey：`s:{storeBookId}` > `t:{serverTaskId}` > `n:{trim(displayName)}`；
 *  - source："server"（服务端有这份字节，可跨端下载播放）| "local"（本机合成，服务端无字节）。
 *
 * Gson 用 `serializeNulls()`，可选字段留 null 会显式发 `null`，服务端 DTO 以可空 Integer/Long 接收，无碍。
 */
data class AudioCoverageItemRequest(
    val bookKey: String,
    /** "server" | "local"（服务端 @Pattern 校验，其它值会被拒） */
    val source: String,
    /** 1-based 闭区间压缩串，如 "1-40,41,45-50"；空串表示暂无覆盖 */
    val doneRanges: String = "",
    val totalSegments: Int? = null,
    val storeBookId: Long? = null,
    val serverTaskId: Long? = null
)

/**
 * 批量同步请求体：一次性上报本机全部覆盖行，服务端并集合并后返回全量并集视图
 * `{success, items:[...], total}`（响应键名是 **items**，非 words）。
 */
data class SyncAudioCoverageRequest(
    val items: List<AudioCoverageItemRequest>
)
