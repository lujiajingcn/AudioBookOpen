package com.audiobook.audiobookplayer.parser

/**
 * 「非空白坐标」工具 —— 供 EPUB 目录定位使用。
 *
 * ## 为什么需要它
 *
 * 目录条目只知道「属于哪一章」，要跳到正确位置就得把「章」换算成「段落下标」。
 * 换算离不开一个坐标系，而这里有一个历史上的坑：
 *
 * - **段落侧的坐标**：段落是 [ParagraphSplitter] 流式切出来的，切分会丢掉句末标点后的
 *   空白与每句首尾的空白，所以「把段落长度加起来」得到的是**压缩坐标**，永远小于原文下标。
 * - **章节侧的坐标**：早期用的是章节原文的**真实长度**累加。
 *
 * 两端不同源 ⇒ 目录映射出的段落下标系统性偏大、越往后越明显，表现为**点目录跳过头**。
 *
 * ## 现在的做法
 *
 * 两侧统一改用**非空白字符计数**：段落文本与原文只相差空白字符（切分只丢空白，不丢任何
 * 非空白字符），所以「非空白计数」是真实字符下标的一个**保序变换** —— 用它定位到的段落
 * 与用真实下标定位到的**完全一致**，而且可以在「流式、不驻留整本」的约束下增量累加。
 *
 * 因此本文件里的三个函数**必须成对使用**：章节位置与段落位置要么都用本坐标，要么都不用，
 * 混用就会退回「跳过头」的缺陷。
 */
internal object TextPositions {

    /** 统计非空白字符个数，作为坐标单位。 */
    fun countNonBlank(s: CharSequence): Int {
        var n = 0
        for (i in s.indices) {
            if (!s[i].isWhitespace()) n++
        }
        return n
    }

    /**
     * 计算每个段落的起始位置，末位放**哨兵**（全文非空白字符总数）。
     *
     * 哨兵位不可省略、也不可留 0：若它遗留为 0，[indexOfContainingParagraph] 会把
     * 「位置 0 的段落」误判在最后一个索引上，章位置一旦超过末段起点就会返回
     * `paragraphs.size`（越界 1），阅读器拿到越界索引会把目录项跳到书末。
     */
    fun paragraphPositions(paragraphs: List<String>): IntArray {
        val positions = IntArray(paragraphs.size + 1)
        var pos = 0
        for (i in paragraphs.indices) {
            positions[i] = pos
            pos += countNonBlank(paragraphs[i])
        }
        positions[paragraphs.size] = pos
        return positions
    }

    /**
     * 取「包含该位置」的段落下标（最近邻，最后一个起始位置 <= [pos] 的段落）。
     *
     * 查找范围只到 `size - 2`（跳过哨兵），返回值因此恒落在 `[0, 段落数-1]`，不会越界。
     */
    fun indexOfContainingParagraph(pos: Int, paragraphPositions: IntArray): Int {
        if (paragraphPositions.size < 2) return 0
        var paraIndex = 0
        for (i in 0..(paragraphPositions.size - 2)) {
            if (pos >= paragraphPositions[i]) {
                paraIndex = i
            } else {
                break
            }
        }
        return paraIndex
    }
}
