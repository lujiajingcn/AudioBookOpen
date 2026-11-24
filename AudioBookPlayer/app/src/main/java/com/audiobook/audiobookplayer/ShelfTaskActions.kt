package com.audiobook.audiobookplayer

/**
 * 书架任务行里**操作按钮**的可见性判定（唯一真源）。
 *
 * ## 为什么要抽成纯函数
 *
 * 这套判定原先散在 `ShelfAdapter.bindTask` 的三个分支里（下载中 / 终态 / 其它状态），
 * 每条分支各写一遍 `visibility`。而 `bindTasks` 是**复用**子 View 的——
 * 任务数不变时不会重新 inflate，于是「漏写一个分支」不会崩、不会闪、
 * lint 也过，只会让**上一条任务的可见性串到这一条**上。
 * 这类 bug 代码审查看不出来，必须靠单测钉死。
 *
 * ## 规则
 *
 * | 状态 | 重试/继续合成/补齐 | 下载音频 | 暂停/继续 |
 * |---|---|---|---|
 * | 下载中 | 否 | 否（只显示下载进度） | 否 |
 * | completed | 否 | 无本地音频或上次下载失败（**在线任务专属**） | 否 |
 * | partial | **剩余段数 > 0 时给**：本机=「继续合成」、在线=「补齐缺失段」 | **在线任务**一律给（补齐后要靠它拉新段） | 否 |
 * | failed | 是（重试） | 否 | 否 |
 * | processing（**本机**合成中） | 否 | 否 | **是** |
 * | 其它（排队中/等待派发/paused/未知） | 否 | 否 | 否 |
 *
 * ⚠️ 「下载音频」只对**在线**任务成立：本机合成的音频只存在本机、没有
 * server_task_id，给它挂下载入口只会得到「任务尚未同步到服务器」的死按钮。
 *
 * ⚠️ 「暂停/继续」也只对**本机**任务成立：在线合成的状态在服务端，
 * 本机没有任何能挂起它的开关，给它挂一个按钮点了必然没反应。
 * 服务端的 paused 任务（只可能由管理端暂停书城任务产生）在 App 里**没有任何操作入口**，
 * 唯一出口是管理端的「继续合成」—— 这里刻意不给按钮，而不是给一个点了报错的按钮。
 *
 * ⚠️ 「补齐缺失段」只对**在线** partial 成立，且与 failed 的「重试」共用同一个按钮位：
 * 两者都走服务端 `/task/{id}/retry`（重跑 failed+pending 段），差别只在文案与确认话术。
 * 原先在线 partial 一个按钮都没有（`showRetry` 要求 isLocalTask），已下载的书
 * 就永远停在「部分完成 + 永远缺那几段」，且没有任何提示。
 *
 * ⚠️ 这里**没有**「阅读并播放」：合成结束后任务的入口是**书籍行本身**
 * （`onShelfEntryClick`），任务行不再挂第三个按钮。
 */

/** 暂停按钮文案（合成进行中，可点下去挂起） */
internal const val PAUSE_LABEL = "暂停"

/** 继续按钮文案（已暂停，可点下去恢复） */
internal const val RESUME_LABEL = "继续"

internal data class TaskActions(
    val showRetry: Boolean,
    val showDownload: Boolean,
    /**
     * 是否显示「暂停/继续」按钮。
     *
     * 只对本机合成进行中的任务成立，见 [computeTaskActions] 的规则表。
     */
    val showPause: Boolean = false,
    /**
     * 暂停按钮上的文案。
     *
     * 合成中显示「暂停」，已暂停显示「继续」—— 同一个按钮位按状态切换，
     * 而不是并排放两个按钮（通知栏那边也是这么做的，见 LocalSynthesisService）。
     */
    val pauseLabel: String = PAUSE_LABEL
) {
    /** 是否需要显示操作行容器——按钮全隐藏时容器也要收起，否则留一条空白横条 */
    val anyVisible: Boolean get() = showRetry || showDownload || showPause
}

/**
 * 从任务行的段数推算**还剩几段**，作为 [computeTaskActions] 的 `remainingSegments`。
 *
 * @return 剩余段数；`null` 表示**总数未知**（`total <= 0`）。
 *
 * ## 为什么总数未知必须返回 null 而不是 0
 *
 * 本机任务行是**先建行、后解析**的（`saveTask` 写入 `total_segments = 0`），
 * 总段数要等解析完成才由 `updateStatus` 补上。如果进程在解析/加载模型阶段被杀，
 * 孤儿对账会把这一行改成 `partial`，而它的 `totalSegments` 仍是 0 —— 此时
 * `total - completed` 算出来正好是 0。若把它当成「一段都不剩」，
 * 「继续合成」按钮会被藏掉，用户就**再也无法续跑这条任务**（只能删掉重来）。
 *
 * 0 在这里是有歧义的（「真的一段不剩」还是「还不知道总数」），而
 * [computeTaskActions] 对 `null` 的语义正是「调用方不知道」→ 维持按 partial 显示。
 */
internal fun remainingSegmentsOf(total: Int, completed: Int): Int? =
    if (total <= 0) null else (total - completed).coerceAtLeast(0)

/**
 * 计算一条任务的操作按钮可见性。
 *
 * @param status 合成状态：processing / queued / pending / completed / partial / failed
 * @param downloadStatus 下载状态：null / downloading / failed / completed
 * @param hasAudio 本地 audio_segments 是否已有该书音频
 * @param isLocalTask 是否本机合成（音色名带 [LocalSynthesisService.AUDIO_NAME_PREFIX] 前缀）
 * @param isPaused 本机合成当前是否处于暂停态（来自 `LocalSynthesisService.paused`）
 * @param canPauseLocal 本机合成服务此刻是否真的有会话在跑
 *   （`LocalSynthesisService.isBusy`）。**必须有它**：进程被杀留下的孤儿
 *   「合成中」行上若也挂上暂停按钮，点下去服务根本没在跑 —— 一个纯死按钮。
 * @param remainingSegments 还没合成的段数 = `totalSegments - completedSegments`。
 *   只用来判「继续合成」是否有意义：`partial` 但剩余为 0 的行（末段刚写完、
 *   状态还没刷成 completed）不该露出这个按钮 —— 点下去空跑一遍、一段也不补，
 *   用户只会认为功能坏了。传 `null` 表示调用方不知道剩余段数，此时维持旧行为
 *   （按 partial 显示），**新调用点必须显式传真实值**。
 */
internal fun computeTaskActions(
    status: String,
    downloadStatus: String?,
    hasAudio: Boolean,
    isLocalTask: Boolean,
    isPaused: Boolean = false,
    canPauseLocal: Boolean = false,
    remainingSegments: Int? = null
): TaskActions {
    // 下载中：只显示下载进度，操作按钮全部收起
    if (downloadStatus == "downloading") return TaskActions(false, false)

    return when (status) {
        // 已完成 / 部分完成：有得操作才显示按钮。
        // 本机 partial 给「继续合成」——合成器会跳过本地已有的段，只补缺的。
        // **在线** partial 给「补齐缺失段」——服务端 retryTask 同样只重跑 failed/pending 段。
        // ⚠️ 剩余段数为 0 时不给：那样的行（末段写完但状态没刷成 completed）
        //    点下去会空跑一遍、一段也不补。`null` = 调用方未提供，维持旧行为。
        "completed", "partial" -> TaskActions(
            showRetry = status == "partial" &&
                (remainingSegments == null || remainingSegments > 0),
            // ⚠️ 本机合成**没有服务端可下载**（它没有 server_task_id，音频只在本机）。
            // 之前用 `!hasAudio` 一刀切，会给本机任务也挂上「下载音频」——
            // 音频被清理后这个按钮点下去只会得到「任务尚未同步到服务器」，
            // 是个纯死按钮。本机音频没了只有一条路：重新合成。
            //
            // partial 时**无论是否已下载**都保留下载入口：补齐之后新增的段要靠它拉下来，
            // 否则用户补完段却拿不到新音频（下载器会跳过本地已有的段，只补缺的）。
            showDownload = !isLocalTask &&
                (!hasAudio || downloadStatus == "failed" || status == "partial")
        )
        // 失败：给重试入口（确认弹窗在 Fragment 层做）
        "failed" -> TaskActions(showRetry = true, showDownload = false)
        // 合成中：**本机**合成给「暂停/继续」。
        //  - 在线合成挂不住（状态在服务端），不挂；
        //  - 服务确实在跑（canPauseLocal）才挂，否则是死按钮；
        //  - 按钮文案按当前暂停态切换：合成中「暂停」、已暂停「继续」。
        "processing" -> TaskActions(
            showRetry = false,
            showDownload = false,
            showPause = isLocalTask && canPauseLocal,
            pauseLabel = if (isPaused) RESUME_LABEL else PAUSE_LABEL
        )
        // 排队中 / 等待派发 / 未知状态：一律无操作按钮
        else -> TaskActions(false, false)
    }
}
