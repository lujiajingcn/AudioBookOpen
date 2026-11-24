package com.audiobook.audiobookplayer

import com.audiobook.audiobookplayer.model.TocEntry

/**
 * 「某一行正文属于哪一章」的定位实现（书内搜索结果的章节标注用）。
 *
 * ## 为什么要单独抽出来
 *
 * 搜索会在正文里命中成百上千行，每条结果都要算出所属章节。这个判定有两个
 * 容易写错、又**不会崩不会闪**的地方：
 *
 * 1. **有序性**：二分要求输入按 `paragraphIndex` 升序，而目录直接来自解析器
 *    （EPUB 的 nav 项、服务端返回的 toc 数组都不保证顺序）；
 * 2. **可比较性**：部分目录条目只有段号、没有行下标（`paragraphIndex = -1`），
 *    把它们混进有序列表会让二分跳到错误的分支，而且症状只是「章节名偶尔错一个」。
 *
 * 抽成纯函数后可以用单测把这两条边界钉死；放在 Activity 私有方法里则只能靠肉眼。
 *
 * ## 为什么章节名取「行下标 ≤ 本行的最后一条」
 *
 * 目录天然是「卷 → 章 → 节」的层级，同一行位置可能同时挂着「第一部分」和「第一章」。
 * 取最后一条 = 取层级最细的那条，与"我现在在哪一节"的直觉一致；
 * 同一位置的多条在稳定排序后保持目录原顺序，越靠后越具体。
 */

/**
 * 把目录整理成**可二分**的索引：只保留有行下标的条目，并按行下标升序。
 *
 * 行下标为负（只有段号）的条目在这里被剔除 —— 它们无法参与"这一行在哪章"的判定。
 * 标题为空的条目同样剔除：就算算出来了也没法显示。
 */
internal fun buildChapterIndex(toc: List<TocEntry>): List<TocEntry> =
    toc.filter { it.paragraphIndex >= 0 && it.title.isNotBlank() }
        .sortedBy { it.paragraphIndex }

/**
 * 第 [rowIndex] 行所属的章节标题；[chapters] 必须是 [buildChapterIndex] 的输出。
 *
 * 返回空串表示"定位不到"（本书没有目录，或这一行在第一个章节标题之前），
 * 由界面退化成只显示行号 —— 不要回退成"第一章"之类的猜测，那会让用户跳错地方。
 */
internal fun chapterTitleAt(chapters: List<TocEntry>, rowIndex: Int): String {
    if (chapters.isEmpty()) return ""
    var low = 0
    var high = chapters.size - 1
    var found = -1
    while (low <= high) {
        // ushr 而不是 / 2：low + high 在超大数组下会溢出成负数，
        // 这里虽然不可能那么长，但保持统一写法免得被抄到别处。
        val mid = (low + high) ushr 1
        if (chapters[mid].paragraphIndex <= rowIndex) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return if (found >= 0) chapters[found].title else ""
}
