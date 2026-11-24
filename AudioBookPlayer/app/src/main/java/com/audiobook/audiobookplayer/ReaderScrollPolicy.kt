package com.audiobook.audiobookplayer

/**
 * 正文「跟随播放位置」时的滚动策略：**近距离平滑，远距离瞬跳**。
 *
 * ## 为什么需要区分
 *
 * 播放位置推进时正文要跟随显示，原先一律 `smoothScrollToPosition`。近距离（下一段在屏幕
 * 下方一两行）平滑滚过去是对的，眼睛能跟上；但**远距离**时它变成灾难：
 *
 * - 打开一本书时如果上次听到第 50 段，`currentPlayingPosition` 首次回调就是 50，
 *   正文从第 1 行开始**一路平滑滚到第 50 行**，用户看到的是屏幕"哗哗往下翻"好几秒，
 *   既不知道发生了什么，也点不动（滚动动画期间触摸会被打断或抢走）；
 * - 点「上一段/下一段」跳很远、或从通知栏换段后回到阅读页，同样会触发一次长途滚动。
 *
 * 用户的原话是「能不能直接定位」—— 远距离他要的是**瞬移到位**，不是看动画。
 *
 * ## 为什么近距离还要保留平滑
 *
 * 自动跟随是**用户没在操作时**发生的：每段推进都瞬跳会让正文"啪啪"抖动，
 * 反而更难受。一屏以内的位移，平滑动画让眼睛能接上。
 *
 * ## 阈值为什么按「行数」而不是像素
 *
 * 行高随字号设置变化（本项目有正文缩放），用行数表达的"大约一屏"在 14sp 和 24sp 下
 * 都成立；用像素写死会在极端字号下失准。
 */

/** 平滑滚动的最大行距（约一屏）。超过它就直接瞬跳。 */
internal const val FOLLOW_SMOOTH_MAX_ROWS = 20

/**
 * 这次跟随该不该「瞬跳」而不是「平滑滚动」。
 *
 * @param targetRow    目标行（段首行）；负数表示没有目标
 * @param firstVisible 当前首个可见行；负数（RecyclerView.NO_POSITION）表示列表空/未布局
 * @param lastVisible  当前末个可见行
 * @return true = 用瞬时定位（scrollToPositionWithOffset）；false = 用平滑滚动
 *
 * 三种返回 true 的情形：
 *  · **距离超过 [smoothMaxRows]** —— 就是这次要修的长途滚动；
 *  · **视口范围未知**（first/last 为 -1）—— 没有锚点可算距离，平滑动画可能乱滚，
 *    直接跳更可控；
 *  · 目标就是不可见时距离必然 > 0，由上面两条覆盖。
 *
 * 返回 false 的情形：目标**已在视口内**（零位移，平滑无害）或**一屏以内**。
 */
internal fun shouldJumpInsteadOfSmooth(
    targetRow: Int,
    firstVisible: Int,
    lastVisible: Int,
    smoothMaxRows: Int = FOLLOW_SMOOTH_MAX_ROWS
): Boolean {
    if (targetRow < 0) return false
    if (firstVisible < 0 || lastVisible < 0) return true
    // 已在视口内：不需要任何滚动
    if (targetRow in firstVisible..lastVisible) return false
    // 到视口边界的行距
    val distance = if (targetRow < firstVisible) {
        firstVisible - targetRow
    } else {
        targetRow - lastVisible
    }
    return distance > smoothMaxRows
}
