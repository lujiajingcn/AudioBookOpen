package com.audiobook.audiobookplayer.model

/**
 * 目录条目。
 *
 * 按场景选择对应的目标索引：
 * - 有音频且能换算时：使用 [segmentNumber] 定位到 audio_segments 的段落
 *   （TTS 任务直接给出，其余来源由「行下标 → 段号」在本地补上）
 * - 否则：使用 [paragraphIndex] 定位，它统一是**阅读页行下标**
 *   （本地 EPUB/MOBI 解析的结果、以及服务端返回后换算过的行下标，语义一致）
 *
 * [level] 表示层级缩进（0 为顶级）
 */
data class TocEntry(
    val title: String,
    val segmentNumber: Int = -1,
    val paragraphIndex: Int = -1,
    val pageNumber: Int = -1,
    val level: Int = 0
)