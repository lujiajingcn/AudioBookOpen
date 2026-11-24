package com.audiobook.audiobookplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.ShelfEntry
import com.audiobook.audiobookplayer.model.Task
import com.audiobook.audiobookplayer.moss.LocalSynthesisService

/**
 * 书架适配器：一个条目 = 一本书，书下方内嵌该书关联的全部合成任务。
 *
 * 任务不再单独成页，而是作为书籍记录的一部分展示与操作。
 */
class ShelfAdapter(
    private val onBookClick: (ShelfEntry) -> Unit,
    private val onDeleteClick: (ShelfEntry) -> Unit = {},
    private val onDownloadClick: (Task) -> Unit = {},
    private val onRetryClick: (Task) -> Unit = {},
    /**
     * 「继续合成」——只对本机合成的**部分完成**任务出现。
     *
     * 与 [onRetryClick] 分开是两个原因，别合并：
     *  - 语义不同：重试是「整本重来」（在线链路），继续是「只补未完成的段」（本机链路）；
     *  - 接口不同：在线走服务端 retryTask，本机走前台服务，参数与前置校验都不一样。
     * 合并成一个回调会逼着 Fragment 里写 if/else 判断任务类型，
     * 而那个判断很容易在漏判时把本机任务发给服务端接口（必然 404/403）。
     */
    private val onContinueLocalClick: (Task) -> Unit = {},
    /**
     * 「暂停/继续」——只对本机合成**进行中**的任务出现。
     *
     * 暂停是 **LocalSynthesisService 的进程级开关**（挂起整个本机合成队列，
     * 当前段跑完后不再开新段），所以适配器只负责把「用户点了」这件事抛出去，
     * 真正调服务的 pause/resume 由 Fragment 做（那边才知道应用上下文）。
     */
    private val onPauseClick: (Task) -> Unit = {},
    /**
     * 「点状态文字进阅读页」—— 合成**已结束**的任务，其状态文字可点，
     * 落地页与点击书籍名称完全一致（见 [isSynthesisFinished]）。
     *
     * 合成没结束时这个回调根本不会被触发（连监听器都不挂），
     * 点了就是点了 —— 界面自洽，不弹 Toast 打断用户。
     *
     * 传 [ShelfEntry] 是因为落地页要按「这本书有没有本地电子书文件」分流，
     * 而任务行只知道自己的 [Task]；传 [Task] 是因为一本书可能有多条任务
     * （不同音色），点哪条就该进哪条，不能一律用 `latestTask`。
     */
    private val onTaskStatusClick: (ShelfEntry, Task) -> Unit = { _, _ -> },
    private val onCategoryClick: (ShelfEntry) -> Unit = {}
) : RecyclerView.Adapter<ShelfAdapter.ShelfViewHolder>() {

    /**
     * 当前显示的条目（**唯一真源**）。
     *
     * ## 为什么不用 ListAdapter
     *
     * ListAdapter 的数据由 AsyncListDiffer 托管，`currentList` 是只读的，
     * 而拖拽排序必须**同步**改顺序并立刻 `notifyItemMoved` ——
     * RecyclerView 的 move 通知是**相对**操作（从 from 移到 to），不是绝对定位：
     * 手动 move 过一次之后，differ 再 apply 一次同样的 move，两次叠加，
     * 结果是书名与位置对不上（甚至一本书显示两次）。
     *
     * 自己持有列表 + 自己算 diff：拖拽后的顺序就是数据本身，
     * 之后数据库刷回来的列表与它一致 → diff 为空 → 不会有第二次 move ✅
     */
    private val items = mutableListOf<ShelfEntry>()

    private val diffCallback = ShelfDiffCallback()

    /**
     * 拖拽是否进行中。
     *
     * 拖拽期间**不**接受外部刷新：合成进度每几秒就会写库触发一次列表刷新，
     * 刷新一旦把列表重排回数据库顺序，用户手里的书就会"跳"回去 ——
     * 拖拽体验直接崩掉。所以刷新先暂存，拖完再统一应用。
     */
    var isDragging: Boolean = false
        private set

    /** 拖拽期间收到的最新列表（拖完应用） */
    private var pendingItems: List<ShelfEntry>? = null

    /**
     * 上一次**真正画到屏幕上**的服务端排队规模。
     *
     * 与 [TaskSyncManager.queuedCount] 分开存：后者随时会被同步线程改，
     * 而这里记的是「界面当前显示的数字」。两者不等 ⇒ 有行需要重绑。
     */
    private var renderedQueueSize = TaskSyncManager.queuedCount

    /**
     * book.id → 有声覆盖图徽章（Phase3 跨端）。由 Fragment 观测 ViewModel 的
     * `coverageBadges` 后经 [setCoverageBadges] 灌入。徽章不在 [ShelfEntry] 里，
     * DiffUtil 看不见它的变化，所以只能像排队规模那样**单独重绑受影响的行**。
     */
    private var coverageBadges: Map<Long, ShelfBadge> = emptyMap()

    /**
     * 灌入最新覆盖徽章，只重绑**徽章发生变化**的那些行（新增/删除/文案颜色变了）。
     * 全表 notifyDataSetChanged 会让书架在每次合成进度刷新时整体闪一下，代价太大。
     */
    fun setCoverageBadges(badges: Map<Long, ShelfBadge>) {
        if (badges == coverageBadges) return
        val old = coverageBadges
        val changed = HashSet<Long>()
        for ((k, v) in badges) if (old[k] != v) changed.add(k)
        for (k in old.keys) if (!badges.containsKey(k)) changed.add(k)
        coverageBadges = badges
        if (changed.isEmpty()) return
        for (index in items.indices) {
            if (items[index].book.id.toLong() in changed) notifyItemChanged(index)
        }
    }

    /** diff 序号：只认最后一次提交的结果，避免旧 diff 覆盖新数据 */
    private var submitSeq = 0L

    /** diff 计算线程：算 diff 不能卡住主线程（与 AsyncListDiffer 同样的考虑） */
    private val diffExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "shelf-diff").apply { isDaemon = true }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 提交一份新列表（书架刷新的唯一入口）。
     *
     * 拖拽中 → 先暂存，拖完再应用（见 [isDragging]）。
     * 否则 → 后台算 diff、主线程派发更新，保留「只重绑变化的那几行」的精细刷新。
     */
    fun submitShelfItems(newItems: List<ShelfEntry>) {
        if (isDragging) {
            pendingItems = newItems
            return
        }
        val seq = ++submitSeq
        val old = items.toList()
        // 排队规模**不在 tasks 表里**，DiffUtil 根本看不见它：规模变了而任务行数据
        // 没变时，areContentsTheSame 全为 true、没有任何一条 update 事件，
        // 「队列约 N 个任务」就会永远停在第一次画上去的那个数字上。
        // 所以提交时带一份快照，落地后单独把会显示它的行重绑一次。
        val queueSize = TaskSyncManager.queuedCount
        diffExecutor.execute {
            // recyclerview 1.3.2 的 DiffUtil 只有 calculateDiff(Callback[, detectMoves])，
            // 没有 ItemCallback 重载 —— 用桥接类把 ItemCallback 转成 Callback。
            // detectMoves = true：顺序变化才能被识别成 move 而不是删了再加。
            val result = DiffUtil.calculateDiff(
                ShelfDiffCallbackBridge(diffCallback, old, newItems),
                true
            )
            mainHandler.post {
                // 期间又提交了新列表：这次结果作废，由更新的那次提交负责画。
                if (seq != submitSeq) return@post
                if (isDragging) {
                    // 拖拽开始得比这次 diff 更晚：结果**不能直接丢**。
                    // endDrag 只回放 pendingItems，而这条路径之前从不往那儿放 ——
                    // 若拖拽期间没有第二次刷新（合成结束往往只写一次库），
                    // 这次「已完成 / 任务被删」的变化就永远画不上去了。
                    pendingItems = newItems
                    return@post
                }
                items.clear()
                items.addAll(newItems)
                result.dispatchUpdatesTo(this)
                if (queueSize != renderedQueueSize) {
                    renderedQueueSize = queueSize
                    rebindQueueHintRows()
                }
            }
        }
    }

    /**
     * 重绑**会显示排队规模**的行（见 [isQueueHintRow]）。
     *
     * 只重绑这几行、不 `notifyItemRangeChanged(0, itemCount)`：排队数是全局的，
     * 与其它行无关；而合成进度每隔几秒就会刷一次列表，全表重绑会让书架
     * 整体跟着闪 —— 为了一个数字付这个代价不值。
     */
    private fun rebindQueueHintRows() {
        for (index in items.indices) {
            val showsQueueSize = items[index].tasks.any { task ->
                isQueueHintRow(
                    status = task.status,
                    isLocalTask = task.audioName.startsWith(LocalSynthesisService.AUDIO_NAME_PREFIX)
                )
            }
            if (showsQueueSize) notifyItemChanged(index)
        }
    }

    /**
     * 拖拽移动：把 [from] 位置的条目挪到 [to] 位置。
     *
     * @return 是否真的移动了（不可移动的条目返回 false）
     */
    fun moveItem(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        // 虚拟条目（只有合成任务、没有电子书文件）没有数据库行，
        // 顺序存不住 —— 拖了也白拖，直接拒绝。
        if (!isSortable(items[from]) || !isSortable(items[to])) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }

    /**
     * 某个条目能否参与排序。
     *
     * 判据是「有电子书文件」：只有任务的虚拟条目在 ebooks 表里没有行，
     * 没有可写的 sort_order，拖动它无法持久化。
     */
    fun isSortable(entry: ShelfEntry): Boolean = entry.hasEbookFile

    /** 拖拽开始：进入拖拽态，期间的刷新改为暂存 */
    fun beginDrag() {
        isDragging = true
    }

    /**
     * 拖拽结束：返回**从上到下**的可排序条目 id 顺序（用于落库）。
     *
     * @return 书籍 id 顺序，只含可排序的条目
     */
    fun endDrag(): List<Int> {
        isDragging = false
        // 拖拽期间收到的刷新，现在应用。
        //
        // ⚠️ 不能直接 submit 那份暂存列表：它来自数据库，`sort_order` 还是**拖之前**
        // 的值，直接替换会把刚拖好的行整体打回原位（松手先跳回去、等落库回来再跳回来）。
        // 先按当前（已拖好的）顺序重排，见 sortIncomingByCurrentOrder。
        pendingItems?.let { pending ->
            pendingItems = null
            submitShelfItems(sortIncomingByCurrentOrder(items, pending))
        }
        return items.filter { isSortable(it) }.map { it.book.id }
    }

    /**
     * 本机合成当前是否处于暂停态。
     *
     * 由 Fragment 从 `LocalSynthesisService.paused` 推进来：服务是暂停状态的
     * 唯一事实来源，但**暂停/恢复不改任何一行数据库**（改的只是按钮文案与
     * 状态文字），所以必须显式触发一次重绑，否则按钮会一直停在「暂停」上，
     * 用户点了没反应。
     */
    var isLocalPaused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShelfViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shelf_book, parent, false)
        return ShelfViewHolder(view)
    }

    override fun onBindViewHolder(holder: ShelfViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ShelfViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val bookRow: LinearLayout = itemView.findViewById(R.id.bookRow)
        private val textView: TextView = itemView.findViewById(android.R.id.text1)
        private val ivAudioIndicator: ImageView = itemView.findViewById(R.id.ivAudioIndicator)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.btnDelete)
        private val taskContainer: LinearLayout = itemView.findViewById(R.id.taskContainer)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvCategory)
        private val tvCoverageBadge: TextView = itemView.findViewById(R.id.tvCoverageBadge)

        fun bind(entry: ShelfEntry) {
            val book = entry.book
            textView.text = book.displayName
            ivAudioIndicator.visibility = if (book.hasAudio) View.VISIBLE else View.GONE
            // 两类条目都可删除：有电子书记录 → 删书（连带任务）；
            // 仅有任务的虚拟条目 → 只删任务记录。分流在 Fragment 层按 hasEbookFile 判断，
            // 否则孤儿任务会永久占据书架且无法清除
            deleteButton.visibility = View.VISIBLE

            // 分类标签：点击可改分类。空分类显示「未分类」
            val cat = book.category.ifBlank { "未分类" }
            tvCategory.text = cat
            tvCategory.visibility = View.VISIBLE
            tvCategory.setOnClickListener { onCategoryClick(entry) }

            // 有声覆盖图徽章（Phase3 跨端，纯感知不点击）。按 book.id 取 VM 预算好的徽章；
            // 虚拟条目（id<=0）与无覆盖的书自然取不到 → GONE。
            val badge = coverageBadges[book.id.toLong()]
            if (badge != null) {
                tvCoverageBadge.visibility = View.VISIBLE
                tvCoverageBadge.text = badge.text
                tvCoverageBadge.setTextColor(badge.colorArgb)
            } else {
                tvCoverageBadge.visibility = View.GONE
            }

            bookRow.setOnClickListener { onBookClick(entry) }
            deleteButton.setOnClickListener { onDeleteClick(entry) }

            bindTasks(entry)
        }

        /**
         * 绑定任务区：复用已有子 View，仅在任务数变化时才增删。
         *
         * 下载过程中每下完一段都会触发一次全列表刷新，若每次都
         * removeAllViews + 重新 inflate，会造成明显闪烁与无谓开销。
         * 复用是安全的——bindTask 会重写所有文本、可见性与点击回调。
         */
        private fun bindTasks(entry: ShelfEntry) {
            val tasks = entry.tasks
            val inflater = LayoutInflater.from(taskContainer.context)
            for (i in tasks.indices) {
                val taskView = if (i < taskContainer.childCount) {
                    taskContainer.getChildAt(i)
                } else {
                    inflater.inflate(R.layout.item_shelf_task, taskContainer, false).also {
                        taskContainer.addView(it)
                    }
                }
                bindTask(taskView, tasks[i], entry)
            }
            // 任务数减少时（如删除任务）移除多余的子 View
            while (taskContainer.childCount > tasks.size) {
                taskContainer.removeViewAt(taskContainer.childCount - 1)
            }
        }

        /** 单条任务的展示逻辑（与原任务列表条目一致，去掉了重复的书名） */
        private fun bindTask(view: View, task: Task, entry: ShelfEntry) {
            val tvStatus = view.findViewById<TextView>(R.id.tvTaskStatus)
            val tvSource = view.findViewById<TextView>(R.id.tvTaskSource)
            val tvAudioName = view.findViewById<TextView>(R.id.tvTaskAudioName)
            val tvError = view.findViewById<TextView>(R.id.tvTaskErrorMessage)
            val progressBar = view.findViewById<ProgressBar>(R.id.progressBarTask)
            val tvProgress = view.findViewById<TextView>(R.id.tvTaskProgress)
            val actionsRow = view.findViewById<LinearLayout>(R.id.llTaskActions)
            val btnDownload = view.findViewById<Button>(R.id.btnDownload)
            val btnRetry = view.findViewById<Button>(R.id.btnRetry)
            val btnPause = view.findViewById<Button>(R.id.btnPause)

            // 状态行的可点性必须在**任何分支之前**统一设置：
            // bindTask 里有多条提前 return 的分支（如「下载中」），
            // 漏设就会把上一条任务的可点性串到这一条（不崩不闪，lint 也过）。
            applyStatusClickable(view, task, entry)

            // 进度条是否该显示。默认 false（终态），运行态分支里改回 true。
            // 见下方注释：必须每条分支都赋值，否则复用的 View 会串可见性。
            var progressVisible = false

            // 来源角标：用户需要一眼看出这条会不会扣积分、能不能随时停。
            // 两条任务的 status 文字（「合成中」）和音色名完全一样，
            // 不加标识就只能靠记忆去猜哪条是本机的。
            val isLocalTask = task.audioName.startsWith(LocalSynthesisService.AUDIO_NAME_PREFIX)
            tvSource.visibility = if (isLocalTask) View.VISIBLE else View.GONE
            if (isLocalTask) {
                tvSource.text = "本机 · 不扣积分"
                tvSource.setBackgroundResource(R.drawable.bg_source_badge)
                tvSource.setTextColor(colorOf(view, R.color.primary))
            }

            // 这一行是不是**正挂着**的暂停态。
            //
            // isLocalPaused 是服务级的全局状态（一次暂停挂起整个本机合成队列），
            // 但只有本机合成进行中的那一行才配显示「已暂停」——
            // 把它套在在线任务上会凭空多出一个假状态。
            val localPaused = isLocalTask && isLocalPaused

            // 本机合成自带音色前缀，「本机合成 · 」这半截在界面上是纯噪音；
            // 在线任务的音色名是用户自己起的，必须原样显示。
            tvAudioName.text = if (isLocalTask) {
                task.audioName.removePrefix(LocalSynthesisService.AUDIO_NAME_PREFIX)
            } else {
                task.audioName
            }

            // 下载中：只显示下载进度，操作行整体收起
            if (task.downloadStatus == "downloading") {
                tvStatus.text = "下载中"
                tvStatus.setTextColor(colorOf(view, R.color.primary))
                setProgressVisible(true, progressBar, tvProgress)
                progressBar.max = task.totalSegments
                progressBar.progress = task.downloadProgress
                tvProgress.text = "下载: ${task.downloadProgress} / ${task.totalSegments}"
                tvError.visibility = View.GONE
                applyActions(TaskActions(false, false), actionsRow, btnRetry, btnDownload, btnPause, task)
                return
            }

            // 终态（合成已结束）一律收起进度条与「x / N」——它们描述的是
            // 「过程进行到哪」，而过程已经结束了。留着的坏处不是多余，而是
            // 误导：一条已完成的合成永远挂着满格的进度条，分不清是真跑完了
            // 还是卡在 99%；失败/部分完成同理。
            // ⚠️ 这里必须**每条分支**都显式设，而不是只在终态设一次 ——
            // bindTasks 会复用子 View（任务数不变时不重新 inflate），
            // 少写一个分支就等于把上一条任务的可见性串到这一条上。
            // 兜底在函数末尾也再设一次（见下方 progressVisible）。
            if (task.downloadStatus == "failed") {
                tvStatus.text = "下载失败"
                tvStatus.setTextColor(colorOf(view, android.R.color.holo_red_dark))
                showError(tvError, task.errorMessage)
                progressVisible = false
            } else {
                when (task.status) {
                    "completed" -> {
                        tvStatus.text = "已完成"
                        tvStatus.setTextColor(colorOf(view, android.R.color.holo_green_dark))
                        tvError.visibility = View.GONE
                        progressVisible = false
                    }
                    "failed" -> {
                        tvStatus.text = "失败"
                        tvStatus.setTextColor(colorOf(view, android.R.color.holo_red_dark))
                        showError(tvError, task.errorMessage)
                        progressVisible = false
                    }
                    "partial" -> {
                        tvStatus.text = "部分完成"
                        tvStatus.setTextColor(colorOf(view, android.R.color.holo_orange_dark))
                        tvError.visibility = View.GONE
                        progressVisible = false
                    }
                    "queued" -> {
                        tvStatus.text = "排队中"
                        tvStatus.setTextColor(colorOf(view, android.R.color.holo_orange_dark))
                        tvError.visibility = View.GONE
                        progressVisible = true
                    }
                    "pending" -> {
                        tvStatus.text = "等待派发"
                        tvStatus.setTextColor(colorOf(view, android.R.color.holo_orange_dark))
                        tvError.visibility = View.GONE
                        progressVisible = true
                    }
                    "paused" -> {
                        // 服务端书城任务被管理员挂起。App 侧没有恢复入口（暂停/继续
                        // 只有管理端有），所以这里只保证「说得对」：
                        // 原先没有这个分支，会落到下面的 else 显示「处理中」，
                        // 进度条一直不动，用户以为服务卡死。
                        tvStatus.text = "已暂停"
                        tvStatus.setTextColor(colorOf(view, android.R.color.holo_orange_dark))
                        tvError.visibility = View.GONE
                        // 保留进度：暂停时最想看的就是「停在第几段」
                        progressVisible = true
                    }
                    "processing" -> {
                        // 暂停后状态列必须改口：还写着「合成中」的话，用户在书架上
                        // 看到进度卡住会以为服务挂了，而不是被暂停了。
                        if (localPaused) {
                            tvStatus.text = LocalSynthesisService.PAUSED_PROGRESS_TEXT
                            tvStatus.setTextColor(colorOf(view, android.R.color.holo_orange_dark))
                        } else {
                            tvStatus.text = "合成中"
                            tvStatus.setTextColor(colorOf(view, R.color.primary))
                        }
                        tvError.visibility = View.GONE
                        progressVisible = true
                    }
                    else -> {
                        // 未知状态按运行态处理：宁可多显示一条进度，也不把
                        // 「还在合成」误判成「已结束」。
                        tvStatus.text = "处理中"
                        tvStatus.setTextColor(colorOf(view, R.color.primary))
                        tvError.visibility = View.GONE
                        progressVisible = true
                    }
                }
            }

            setProgressVisible(progressVisible, progressBar, tvProgress)
            if (progressVisible) {
                progressBar.max = task.totalSegments
                progressBar.progress = task.completedSegments
                // 有阶段文案时优先显示它，而不是「0 / N」。
                //
                // 原因是本机合成在首段推理前要先加载模型（实测 13~42 秒），
                // 这段时间 completedSegments 恒为 0：只显示「0 / N」的话用户看到的是
                // 一个纹丝不动的数字，很容易以为点了没反应。显示「正在加载模型」，
                // 用户就知道它确实在干活、只是还没到出音频的阶段。
                val stage = task.progressText?.takeIf { it.isNotBlank() }
                // 排队中 / 等待派发：显示**服务端全局排队规模**，而不是恒为「0 / N」——
                // 队列没轮到它之前 completed 一直是 0，那个「0 / N」看不出任何进展，
                // 用户只会以为卡住。口径与 Web 端任务列表一致（「队列约 N 个任务」）。
                // 本机任务不进服务端队列，它显示服务端队列数只会误导（本机合成不受其影响）。
                val queueSize = TaskSyncManager.queuedCount
                // 「哪类行受排队规模影响」的唯一判据在 isQueueHintRow
                // （submitShelfItems 重绑时用的是同一口径）
                val waitingInQueue = isQueueHintRow(task.status, isLocalTask) && queueSize > 0
                // 暂停时**不**显示阶段文案：那一刻的阶段文案就是「已暂停」，
                // 而状态列已经写了「已暂停」—— 两处重复，而「x / N」才是
                // 用户真正想看的（停在第几段）。
                tvProgress.text = when {
                    stage != null && task.status == "processing" && !localPaused -> stage
                    waitingInQueue -> "队列约 $queueSize 个任务"
                    else -> "${task.completedSegments} / ${task.totalSegments}"
                }
            }

            // 操作按钮：**唯一**判定入口在 computeTaskActions。
            //
            // ⚠️ 不要在这里再补分支写 visibility —— bindTasks 复用子 View，
            // 少写一条就把上一条任务的状态串过来（不崩不闪，lint 也过）。
            // 新增按钮/新状态一律去改 ShelfTaskActions.kt，那里才有测试。
            val actions = computeTaskActions(
                status = task.status,
                downloadStatus = task.downloadStatus,
                hasAudio = task.hasAudio,
                isLocalTask = isLocalTask,
                isPaused = localPaused,
                canPauseLocal = LocalSynthesisService.isBusy,
                // 「继续合成」只在**还有段没合完**时才有意义。判 partial 不够：
                // 最后一段写完但状态没来得及刷成 completed 的行，剩余为 0，
                // 点「继续合成」只会空跑一遍、什么也不补。
                // ⚠️ 总数未知（total_segments = 0，解析前就被打断的孤儿行）必须传 null，
                //    否则会被算成「剩余 0」而把按钮藏掉 —— 那条任务就再也续不上了。
                remainingSegments = remainingSegmentsOf(
                    total = task.totalSegments,
                    completed = task.completedSegments
                )
            )
            applyActions(actions, actionsRow, btnRetry, btnDownload, btnPause, task)
        }

        /**
         * 任务行**状态区**的点击绑定。
         *
         * 需求：合成**已结束**时，点状态信息与点书名一样进阅读页；
         * 合成没结束（合成中/排队中/下载中）时点了不响应。
         *
         * ## 为什么整条状态行都挂，而不是只挂状态文字
         *
         * 状态文字（「已完成」）只有几个字，触摸目标太小 —— 移动端 48dp 的
         * 最小可点区域都不到，用户想点「已完成」却总点偏。
         * 状态区这一行（状态 + 角标 + 音色名 + 进度）横向铺开，天然是个好靶子。
         *
         * ## 「书名 ↔ 状态文字」之间那条空隙为什么也点得着
         *
         * 那条空隙原先有一半落在 `taskContainer` 的 `marginTop` 上 ——
         * 那是容器**外缘**，不属于任何可点 View，点它两边都不响应（死区）。
         * 布局已把它整条挪进 `llTaskStatus` 自己的 `paddingTop`
         * （见 item_shelf_task.xml 的注释），而 padding 在 View 的 bounds 内 ——
         * 所以空隙与状态文字是**同一个可点区域**，点哪都跳。
         *
         * ## 为什么不可点时**不挂监听**而不是挂一个空实现
         *
         * 挂了空监听，这行就会吃掉点击事件（onClick 消费掉），
         * 用户按下时看不到任何涟漪反馈，以为界面卡了。
         * 不挂监听 ⇒ 这行完全不参与触摸分发 ⇒ 点击直接穿透到别的层，
         * 行为与「没有这个控件」完全一致，零副作用。
         *
         * ## 为什么必须**每次绑定都重设**
         *
         * `bindTasks` 会复用子 View（任务数不变时不重新 inflate）：
         * 一条「已完成」的行复用到一条「合成中」的行上时，
         * 上一条挂上的点击监听会**留在**这一条上 —— 于是合成中的书
         * 点状态也能跳阅读页，而且不崩不闪、lint 也过，代码审查看不出来。
         * 所以这里是「设」而不是「有才设」。
         *
         * ⚠️ 连 `isClickable` / `isLongClickable` 也要显式复位：
         * `setOnClickListener(null)` **不会**把 clickable 标志位清掉
         * （它只在设监听时置 true），不复位的话复用的行会继续吞掉点击，
         * 且长按也就不再触发拖拽排序了。
         */
        private fun applyStatusClickable(view: View, task: Task, entry: ShelfEntry) {
            val statusRow = view.findViewById<LinearLayout>(R.id.llTaskStatus)
            val clickable = isSynthesisFinished(task.status, task.downloadStatus)
            if (clickable) {
                statusRow.setOnClickListener { onTaskStatusClick(entry, task) }
                // 长按不能触发拖拽：长按手势会冒泡到条目的长按监听，
                // 于是"按住状态文字"会变成"开始拖动这本书"。
                // 这里消费掉长按事件（返回 true 表示已处理）。
                statusRow.setOnLongClickListener { true }
                // 可点的状态区给个视觉暗示：按下有涟漪，用户知道这里能点。
                statusRow.setBackgroundResource(R.drawable.bg_task_status_clickable)
            } else {
                // ⚠️ 必须显式清掉：复用的 View 上还留着上一条任务的监听器
                statusRow.setOnClickListener(null)
                statusRow.setOnLongClickListener(null)
                // 标志位也要复位（setOnClickListener(null) 不会清它）
                statusRow.isClickable = false
                statusRow.isLongClickable = false
                // 背景也要清：合成中的那一行若留着涟漪背景，
                // 用户按下去看到波纹却不跳转 —— 比完全没反馈更像界面坏了。
                statusRow.setBackgroundResource(0)
            }
        }

        /**
         * 把 [TaskActions] 落到几个控件上，并按需收起/放出操作行容器。
         *
         * 容器必须跟着一起切：按钮全 GONE 时容器仍有 `layout_marginTop=4dp`
         * 和 wrap_content 高度，会在任务行末尾留一条空白横条。
         */
        private fun applyActions(
            actions: TaskActions,
            actionsRow: LinearLayout,
            btnRetry: Button,
            btnDownload: Button,
            btnPause: Button,
            task: Task
        ) {
            actionsRow.visibility = if (actions.anyVisible) View.VISIBLE else View.GONE

            // 本机合成的「部分完成」给「继续合成」，其余（failed）走「重试」。
            // 合成器会跳过本地已有的段（见 MossLocalSynthesizer 的续跑逻辑），
            // 所以「继续合成」只会真正合成缺的那几段，不会把已有的重算一遍。
            val isContinueLocal = actions.showRetry &&
                task.status == "partial" &&
                task.audioName.startsWith(LocalSynthesisService.AUDIO_NAME_PREFIX)
            // 在线任务的部分完成：走服务端 retryTask（只重跑 failed/pending 段），
            // 文案必须与「重试」分开 —— 用户看到「重试」会以为整本要重烧 6500 段。
            val isGapRetryOnline = actions.showRetry && task.status == "partial" && !isContinueLocal
            btnRetry.visibility = if (actions.showRetry) View.VISIBLE else View.GONE
            btnRetry.text = when {
                isContinueLocal -> {
                    // 总数未知（本机任务行先建行、后解析，解析前被打断时 total 仍是 0）时
                    // 不写数字：文案退化成「继续合成剩余 0 段」会让用户以为没什么可补，
                    // 于是不去点它 —— 按钮明明在，却自己把自己劝退了。
                    val missing = remainingSegmentsOf(task.totalSegments, task.completedSegments)
                    if (missing != null) "继续合成剩余 $missing 段" else "继续合成"
                }
                isGapRetryOnline -> {
                    val missing = remainingSegmentsOf(task.totalSegments, task.completedSegments)
                    if (missing != null) "补齐剩余 $missing 段" else "补齐缺失段"
                }
                else -> "重试"
            }
            btnRetry.setOnClickListener {
                if (isContinueLocal) onContinueLocalClick(task) else onRetryClick(task)
            }

            // 长按这三个按钮**不能**触发拖拽：长按手势会冒泡到条目的长按监听，
            // 于是"按住重试按钮"会变成"开始拖动这本书"—— 用户以为在点按钮，
            // 结果书被拖走了。这里消费掉长按事件（返回 true 表示已处理）。
            btnRetry.setOnLongClickListener { true }

            btnDownload.visibility = if (actions.showDownload) View.VISIBLE else View.GONE
            btnDownload.setOnClickListener { onDownloadClick(task) }
            btnDownload.setOnLongClickListener { true }

            // 「暂停/继续」：同一个按钮位按状态切换文案。
            // 动作判断放在 Fragment 里按**服务的真实状态**做（那边读
            // LocalSynthesisService.paused），不依赖这里的文案 ——
            // 文案与真实状态之间可能隔着一次写库/回传的延迟。
            btnPause.visibility = if (actions.showPause) View.VISIBLE else View.GONE
            btnPause.text = actions.pauseLabel
            btnPause.setOnClickListener { onPauseClick(task) }
            btnPause.setOnLongClickListener { true }
        }

        /**
         * 同时收起/放出进度条与它的「x / N」文字。
         *
         * 两个控件必须一起切：只藏进度条会留一个孤零零的「31 / 31」在行尾，
         * 只藏文字会留一条永远满格或永远卡住的长条。
         */
        private fun setProgressVisible(visible: Boolean, progressBar: ProgressBar, tvProgress: TextView) {
            val v = if (visible) View.VISIBLE else View.GONE
            progressBar.visibility = v
            tvProgress.visibility = v
        }

        private fun showError(tvError: TextView, errorMessage: String?) {
            if (!errorMessage.isNullOrEmpty()) {
                tvError.text = errorMessage
                tvError.visibility = View.VISIBLE
            } else {
                tvError.visibility = View.GONE
            }
        }

        private fun colorOf(view: View, colorRes: Int): Int =
            ContextCompat.getColor(view.context, colorRes)
    }

    /**
     * 把 [DiffUtil.ItemCallback] 桥接成 [DiffUtil.Callback]。
     *
     * recyclerview 1.3.2 的 `DiffUtil` 只有 `calculateDiff(Callback[, detectMoves])`，
     * 没有 1.4.0 才加的 `calculateDiff(ItemCallback, List, List)` 重载 ——
     * 想用 ItemCallback 那套「按对象比」的语义，只能自己转一层。
     */
    private class ShelfDiffCallbackBridge(
        private val itemCallback: DiffUtil.ItemCallback<ShelfEntry>,
        private val oldList: List<ShelfEntry>,
        private val newList: List<ShelfEntry>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            itemCallback.areItemsTheSame(oldList[oldItemPosition], newList[newItemPosition])

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            itemCallback.areContentsTheSame(oldList[oldItemPosition], newList[newItemPosition])
    }

    class ShelfDiffCallback : DiffUtil.ItemCallback<ShelfEntry>() {
        override fun areItemsTheSame(oldItem: ShelfEntry, newItem: ShelfEntry): Boolean {
            return oldItem.book.id == newItem.book.id &&
                oldItem.book.tableName == newItem.book.tableName
        }

        override fun areContentsTheSame(oldItem: ShelfEntry, newItem: ShelfEntry): Boolean {
            return oldItem.book == newItem.book &&
                tasksContentsEqual(oldItem.tasks, newItem.tasks)
        }

        /**
         * 逐项比较任务列表，不直接使用 List 的 equals：
         * lint 的 DiffUtilEquals 认为 List 是接口、equals 行为不可信。
         */
        private fun tasksContentsEqual(old: List<Task>, new: List<Task>): Boolean {
            if (old.size != new.size) return false
            for (i in old.indices) {
                if (!taskContentsEqual(old[i], new[i])) return false
            }
            return true
        }

        /** 只比较影响展示的字段，createdAt 等无关字段变化不触发重绑 */
        private fun taskContentsEqual(old: Task, new: Task): Boolean {
            return old.id == new.id &&
                old.status == new.status &&
                old.completedSegments == new.completedSegments &&
                old.totalSegments == new.totalSegments &&
                old.hasAudio == new.hasAudio &&
                old.downloadStatus == new.downloadStatus &&
                old.downloadProgress == new.downloadProgress &&
                old.errorMessage == new.errorMessage &&
                // progressText 必须比较：本机合成的阶段（「正在加载模型」→「合成中」）
                // 变化时 completed/total 可能完全没变，漏掉这一项的话
                // DiffUtil 会判定"无变化"，界面上就一直停在加载模型的文案上，
                // 直到第一段完成才跳 —— 而这中间可能有几十秒。
                old.progressText == new.progressText
        }
    }
}
