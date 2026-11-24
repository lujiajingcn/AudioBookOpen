package com.audiobook.audiobookplayer

/**
 * 「回到播放处」按钮的**目标行**与**显隐**判定。
 *
 * ## 为什么抽成纯函数
 *
 * 这枚按钮同时服务两种播放形态（朗读 / 有声书），判定又是"置位—判定—复位"三处时序
 * 交织（起播、位置推进、用户滚动、跳段、停止）。逻辑留在 Activity 里就只能靠真机点，
 * 而这类 bug 的典型表现是**按钮该出不出、该收不收**——不崩溃、不报错，肉眼很难穷举。
 *
 * 抽出来后用矩阵钉死；Activity 侧只剩"取当前值 + 套用结果"。
 */

/** 没有目标行时的哨兵值（与 RecyclerView.NO_POSITION 同值）。 */
internal const val NO_PLAYBACK_TARGET = -1

/**
 * 算出「回到播放处」此刻该跳哪一行。
 *
 * 朗读优先于有声书。两者在**同一个阅读页里互斥**，这不是巧合而是产品结构决定的：
 * · 有音频的书 —— 朗读入口 `btnReadAloudBar` 始终隐藏（见 applyPlaybackBarForBookType），
 *   只有声书播放入口；
 * · 无音频的书 —— 播放键即朗读入口，没有有声书会话，`currentPlayingPosition` 恒为 -1。
 *
 * 另外 `currentPlayingPosition` 自身也带「同书」保护（BookViewModel 里只有
 * `service.currentBookName() == currentBookTable` 时才写入行号，否则写 -1），
 * 所以后台跨书播放的行号**不会**被拿到本书来用 —— 那两本书的行号没有任何对应关系。
 *
 * @param readAloudActive 是否正在朗读
 * @param readAloudRow    朗读当前行（未朗读时为 [NO_PLAYBACK_TARGET]）
 * @param audioPlayingRow 有声书当前播放段的首行（未播放时为 [NO_PLAYBACK_TARGET]）
 */
internal fun resolveBackToPlayTargetRow(
    readAloudActive: Boolean,
    readAloudRow: Int,
    audioPlayingRow: Int
): Int {
    if (readAloudActive && readAloudRow >= 0) return readAloudRow
    return if (audioPlayingRow >= 0) audioPlayingRow else NO_PLAYBACK_TARGET
}

/**
 * 目标行不在视口内时才显示按钮。
 *
 * @param targetRow     [resolveBackToPlayTargetRow] 的结果
 * @param firstVisible  列表首个可见行；-1（RecyclerView.NO_POSITION）表示列表空/还没布局完
 * @param lastVisible   列表末个可见行
 *
 * 三种"不该显示"的情形：
 *  · 没有目标行（没在播/没在读）—— 按钮没有意义；
 *  · 目标行就在眼前 —— 正文已经在播放位置，"回去"是空操作，纯属噪声；
 *  · 列表还没布局完 —— 保守不显示（比误导用户强）。
 */
internal fun shouldShowBackToPlayButton(
    targetRow: Int,
    firstVisible: Int,
    lastVisible: Int
): Boolean {
    if (targetRow < 0) return false
    if (firstVisible < 0 || lastVisible < 0) return false
    return targetRow !in firstVisible..lastVisible
}
