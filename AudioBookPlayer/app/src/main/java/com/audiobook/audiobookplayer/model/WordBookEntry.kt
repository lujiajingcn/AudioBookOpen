package com.audiobook.audiobookplayer.model

/**
 * 单词本条目，对应 SQLite 中的 word_book 表。
 *
 * 存的是**词条快照**（音标 + 释义），不是指向词典库的外键 —— 查词要装ECDICT（55MB），
 * 收藏不该被这个前提绑住：用户清理词典后，已收藏的词仍要能看。
 *
 * @param id主键
 * @param sw          归一化键（strip-word：去非字母数字 + 转小写）。**唯一身份**。
 *                    与 [com.audiobook.audiobookplayer.DictionaryManager.stripWord] 逐字符一致，
 *                    所以 `hood` / `Hood` / `'hood` 视为同一个词，收藏按钮不会插出三条。
 * @param word        展示用的原词（用户书里点到的那个形态，保留大小写与连字符）
 * @param phonetic    音标，可能为空串（ECDICT 未必每个词条都给）
 * @param translations 释义列表，已按字面量 `\n` 切分去空；为空表示该词条没有中文释义
 * @param createdAt   收藏时间（数据库 CURRENT_TIMESTAMP，可能是 null）
 * @param serverId    服务端 `UserWordBook.id`。null = 本机收藏尚未上云（离线时点星、
 *                    v11 迁移之前的存量数据），首次 [com.audiobook.audiobookplayer.WordBookRepository.syncWithServer]
 *                    会把它当"待上传"批量上报；非空 = 已与服务端对齐，删除时可直接按此 id 走远端。
 * @param bookName/bookKey/storeBookId/segmentNumber/lineNumber/sentenceText
 *                    收藏出处（v13，与服务端 wire 对齐，全部可空）。null = 旧收藏 / 服务端
 *                    没有出处 ⇒ 单词本「跳转到原书」禁用或提示，绝不崩、绝不白屏。
 *                    `lineNumber` 只是会漂移的行号 hint，`sentenceText` 才是定位第一判据。
 *                    带默认值保证既有构造点（如单元测试、解析）不炸。
 */
data class WordBookEntry(
    val id: Long = 0L,
    val sw: String,
    val word: String,
    val phonetic: String = "",
    val translations: List<String> = emptyList(),
    val createdAt: String? = null,
    val serverId: Long? = null,
    val bookName: String? = null,
    val bookKey: String? = null,
    val storeBookId: Long? = null,
    val segmentNumber: Int? = null,
    val lineNumber: Int? = null,
    val sentenceText: String? = null
)