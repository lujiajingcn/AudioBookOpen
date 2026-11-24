package com.audiobook.audiobookplayer.model

/**
 * 书架条目：一本书 + 它关联的全部合成任务。
 *
 * 任务按 book_name 与书籍的 display_name 关联；一本书可能有多条任务
 * （例如用不同音色重复合成），因此这里是列表而非单个任务。
 *
 * @param book  书籍记录。若该书只有任务、没有本地电子书文件，则由
 *              BookViewModel 构造一个 filePath 为空的"虚拟书籍"条目，
 *              保证任务不会因为缺少电子书记录而从书架上消失。
 * @param tasks 该书关联的任务，按创建时间倒序（最新在前）
 */
data class ShelfEntry(
    val book: Book,
    val tasks: List<Task> = emptyList()
) {
    /** 该书是否存在本地电子书文件（false 表示仅有合成任务的虚拟条目） */
    val hasEbookFile: Boolean get() = book.filePath.isNotEmpty()

    /** 最近一条任务（用于书籍行上展示概要状态） */
    val latestTask: Task? get() = tasks.firstOrNull()
}
