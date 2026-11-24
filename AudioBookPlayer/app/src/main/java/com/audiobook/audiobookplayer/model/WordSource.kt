package com.audiobook.audiobookplayer.model

import com.audiobook.audiobookplayer.CoverageCodec
import java.io.Serializable

/**
 * 收藏单词时记下的「出处」：哪本书 + 当前句子位置。
 *
 * 六个字段与服务端 wire（`WordBookItemRequest`）**逐字对齐、全部可空**：
 * 旧版本升级上来的存量收藏、以及服务端返回 null 的旧数据，出处都是 null；
 * 此时单词本「跳转到原书」必须禁用或提示，绝不崩、绝不白屏。
 *
 * - [bookKey] 三端统一口径 `s:{storeBookId}` / `t:{serverTaskId}` / `n:{归一化书名}`
 *   （见 [CoverageCodec.bookKey]，与覆盖图、PC、服务端完全一致）。
 * - [lineNumber] 只是**行号 hint**：行号是「当时那一行」的编号，切分粒度一变就漂，
 *   仅作兜底；[sentenceText]（收藏时所在句子/段落原文）才是**定位第一判据**，
 *   文字内容不会随切分规则变化。
 *
 * 仅经 `Bundle` 在**同一进程内**（阅读页 → 查词弹窗）传递，不做跨版本持久化，
 * 故实现 `Serializable` 即可，无需固定 `serialVersionUID`。
 */
data class WordSource(
    val bookName: String? = null,
    val bookKey: String? = null,
    val storeBookId: Long? = null,
    val segmentNumber: Int? = null,
    val lineNumber: Int? = null,
    val sentenceText: String? = null
) : Serializable {

    companion object {
        /** 句子原文入库/上云前的截断长度（服务端上限 20000，这里只留定位够用的上下文） */
        const val MAX_SENTENCE_LEN = 2000

        /**
         * 本次上报是否「带出处」，与服务端 `WordBookItemRequest.hasSource()` 同口径：
         * 书名或 bookKey 至少一个非空。旧客户端不带出处时服务端**保留**已有出处，
         * 不会把跳转信息清成 null —— 本端新收藏务必让它为 true。
         */
        fun hasSource(source: WordSource?): Boolean =
            source != null && (!source.bookName.isNullOrBlank() || !source.bookKey.isNullOrBlank())

        /**
         * 组装出处（纯函数：阅读页查词时调用，单测直接测它）。
         *
         * bookKey 交给 [CoverageCodec.bookKey] 统一生成，保证与覆盖图/PC/服务端一致：
         * 书城书优先 `s:`，在线任务书 `t:`，本地书退化 `n:{归一化书名}`。
         * 书城 id <= 0（本地书 / 未指定）按 wire 约定置 null；行号/段号只收正整数
         * （1-based，非正数视为未知）；句子原文截断到 [MAX_SENTENCE_LEN]。
         *
         * @param segmentNumber 段号：阅读页收藏落点手头只有行号 + 行原文，段号需靠
         *                      「段 ↔ 行」映射反查且结构可能尚未就绪，本端不强算，留 null。
         */
        fun build(
            bookName: String?,
            storeBookId: Long?,
            serverTaskId: Long?,
            lineNumber: Int?,
            sentenceText: String?,
            segmentNumber: Int? = null
        ): WordSource {
            val key = CoverageCodec.bookKey(storeBookId, serverTaskId, bookName)
            return WordSource(
                bookName = bookName?.takeIf { it.isNotBlank() },
                bookKey = key.ifEmpty { null },
                storeBookId = storeBookId?.takeIf { it > 0 },
                segmentNumber = segmentNumber?.takeIf { it > 0 },
                lineNumber = lineNumber?.takeIf { it > 0 },
                sentenceText = sentenceText?.take(MAX_SENTENCE_LEN)?.takeIf { it.isNotBlank() }
            )
        }

        /**
         * 是否允许「跳转到原书」。**旧收藏（出处全 null）一律 false**：
         * 只有书名无法定位，只有锚点却没书名不知道打开哪本。
         * 三参数重载供 UI 直接拿 `WordBookEntry` 的字段判定，不必先组对象。
         */
        fun canJumpToBook(source: WordSource?): Boolean =
            canJumpToBook(source?.bookName, source?.sentenceText, source?.lineNumber)

        fun canJumpToBook(bookName: String?, sentenceText: String?, lineNumber: Int?): Boolean {
            if (bookName.isNullOrBlank()) return false
            // 定位第一判据是句子原文，行号只是兜底，二者至少要有一个。
            return !sentenceText.isNullOrBlank() || (lineNumber ?: 0) > 0
        }
    }
}
