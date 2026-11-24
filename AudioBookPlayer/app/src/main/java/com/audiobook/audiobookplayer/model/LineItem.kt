package com.audiobook.audiobookplayer.model

data class LineItem(
    val id: Int,           // 数据库主键
    val lineNumber: Int,   // 行号（唯一）
    val text: String       // 文本内容
)