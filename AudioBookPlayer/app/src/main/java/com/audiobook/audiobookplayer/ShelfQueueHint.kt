package com.audiobook.audiobookplayer

/**
 * 任务行的进度文案要不要换成**服务端排队规模**（`队列约 N 个任务`）的判定。
 *
 * ## 为什么要判断「行类型」而不是只判断「有没有数字」
 *
 * 排队规模是**全局**的（`TaskSyncManager.queuedCount` 统计所有账号在 `queued`
 * 状态的任务数），但只有**在线**的「排队中 / 等待派发」行才受它影响：
 *
 * - **本机合成**不进服务端队列（`LocalSynthesisService` 自己排队、自己跑），
 *   给它显示「队列约 12 个任务」会让人以为自己的书还要等 12 个，
 *   而实际上它下一秒就可能开始 —— 属于凭空制造焦虑；
 * - 其它状态（合成中 / 已完成 / 失败）本来就有确切进度（`x / N`），
 *   换成队列数反而把真正在动的那条信息盖掉了。
 *
 * ## 为什么单独抽成函数
 *
 * 除了 bind 时用它决定文案，`ShelfAdapter` 还要用它回答另一个问题：
 * **排队规模变了，哪些行需要重绑？**（DiffUtil 看不见这个值，见
 * [ShelfAdapter.submitShelfItems] 的注释。）两处判定必须是同一个口径，
 * 否则会出现「该重绑的没重绑」（数字停在旧值）或「重绑了不该动的行」（无谓闪烁）。
 *
 * @param status 合成状态：queued / pending / processing / completed / partial / failed
 * @param isLocalTask 是否本机合成（音色名带 `LocalSynthesisService.AUDIO_NAME_PREFIX` 前缀）
 */
internal fun isQueueHintRow(status: String, isLocalTask: Boolean): Boolean =
    !isLocalTask && (status == "queued" || status == "pending")
