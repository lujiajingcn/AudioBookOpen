package com.audiobook.audiobookplayer

import android.os.Bundle
import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.StoreBook
import com.audiobook.audiobookplayer.model.StoreTag
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 书城 Tab：首页榜单（热门/新书）+ 分类筛选 + 搜索 + 书籍网格 + 详情下载。
 * 下载完成后书籍进入"我的书架"（BookListFragment），与手动导入路径一致。
 */
class BookStoreFragment : Fragment() {

    private lateinit var storeViewModel: BookStoreViewModel
    private lateinit var bookViewModel: BookViewModel

    private lateinit var etSearch: EditText
    private lateinit var categoryContainer: LinearLayout
    private lateinit var categoryScroll: HorizontalScrollView
    private lateinit var tagContainer: LinearLayout
    private lateinit var tagScroll: HorizontalScrollView
    private lateinit var recyclerViewStore: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var tvStoreTitle: TextView

    /** 仅 WiFi 下载开关 + 整本书下载进度对话框 */
    private lateinit var switchOnlyWifi: Switch
    private var downloadProgressDialog: AlertDialog? = null
    private var downloadProgressBar: ProgressBar? = null
    private var downloadProgressText: TextView? = null

    /**
     * 最近一帧下载进度（电子书）。
     * 对话框的渲染统一以「它 + 两个下载标志位」为依据，见 [syncDownloadDialog]。
     */
    private var lastProgress: DownloadProgress? = null

    /**
     * 合成音频的后台下载状态（来自 [StoreAudioDownloadManager]，不是 LiveData）。
     *
     * 音频下载已经搬到进程级、与界面解耦，所以这里只作为**观察者**缓存最近一帧：
     * 用户在别页/别的进程状态下，下载照跑；回到本页时 [StoreAudioDownloadManager.addListener]
     * 会立刻补一帧现状，进度对话框随之重新出现。
     */
    private var audioState: StoreAudioDownloadManager.State? = null

    /**
     * 用户是否已主动关掉进度对话框（「后台继续」/ 返回键 / 点窗外）。
     *
     * 为什么需要这个闩：进度对话框的唯一渲染入口 [syncDownloadDialog] 被**每一段**的状态帧
     * 驱动（合成音频是逐段入库的，下完一段就来一帧）。没有这个闩时，用户点「后台继续」的
     * 下一秒，下一段的状态帧就把同一个窗又 show() 出来 —— 于是「下载可以在后台跑、用户可以
     * 离开本页」变成「用户被按在书城页，每关一次就弹一次」。
     *
     * 语义：关掉后进入静默期，只在「下载从无到有」时自动解除；用户也可以随时在书籍详情弹窗
     * 里点「查看下载进度」强制重开（force=true）。
     */
    private var progressDialogDismissedByUser = false

    private val audioDownloadListener = StoreAudioDownloadManager.Listener { state ->
        audioState = state
        syncDownloadDialog()
        // 收尾一帧：提示结果并刷新书架（「有音频」图标看的是 audio_segments）
        if (state != null && state.isTerminal) {
            if (state.status == StoreAudioDownloadManager.Status.COMPLETED) {
                bookViewModel.refreshLibraryAfterDownload()
            }
            context?.let { Toast.makeText(it, state.message, Toast.LENGTH_LONG).show() }
        }
    }

    private lateinit var adapter: BookStoreAdapter

    /** 当前顶部分类选中项（null=首页/全部） */
    private var selectedCategoryButton: MaterialButton? = null

    /** 当前标签选中项（null=不按标签筛选）；与分类选中项互不影响 */
    private var selectedTagButton: MaterialButton? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_book_store, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        storeViewModel = ViewModelProvider(this)[BookStoreViewModel::class.java]
        bookViewModel = ViewModelProvider(requireActivity())[BookViewModel::class.java]

        tvStoreTitle = view.findViewById(R.id.tvStoreTitle)
        etSearch = view.findViewById(R.id.etSearch)
        categoryContainer = view.findViewById(R.id.categoryContainer)
        categoryScroll = view.findViewById(R.id.categoryScroll)
        tagContainer = view.findViewById(R.id.tagContainer)
        tagScroll = view.findViewById(R.id.tagScroll)
        recyclerViewStore = view.findViewById(R.id.recyclerViewStore)
        progressBar = view.findViewById(R.id.progressBar)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        // 仅 WiFi 下载开关
        switchOnlyWifi = view.findViewById(R.id.switchOnlyWifi)
        switchOnlyWifi.isChecked = AppPrefs.isOnlyWifiDownload(requireContext())
        switchOnlyWifi.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setOnlyWifiDownload(requireContext(), isChecked)
            // 通知下载器重新判断放行条件：关掉开关要立刻续传被拦下的任务，
            // 否则在蜂窝网络下它永远等不到「WiFi 恢复」事件（开关看着打开了却没动静）。
            StoreAudioDownloadManager.onOnlyWifiPreferenceChanged()
            if (isChecked) {
                Toast.makeText(requireContext(), "已开启：仅在 WiFi 下下载大书", Toast.LENGTH_SHORT).show()
            }
        }

        setupRecyclerView()
        setupSearch()
        observeViewModel()
        observeDownloadProgress()

        // 首次进入加载书城首页
        storeViewModel.loadHome()
    }

    /**
     * 观察下载状态，用同一个对话框展示「电子书 / 合成音频」的下载进度。
     *
     * 两个来源：
     *  - [BookStoreViewModel.downloadProgress] + [BookStoreViewModel.downloading]：电子书**字节级**进度；
     *  - [StoreAudioDownloadManager]：合成音频的**段级**进度（每段下完即入库）。
     *
     * 合成音频这条过去是挂在 ViewModel 上的 LiveData，下载协程随该书城页的 ViewModel 存亡 ——
     * 用户切走 Tab 或进阅读页就断，且断掉的 zip 是废包。现在它跑在进程级下载器里，
     * 这里退化成纯展示方：离开页面下载照跑，回来时补一帧现状。
     */
    private fun observeDownloadProgress() {
        storeViewModel.downloadProgress.observe(viewLifecycleOwner) { p ->
            lastProgress = p
            syncDownloadDialog()
        }
        storeViewModel.downloading.observe(viewLifecycleOwner) { syncDownloadDialog() }
        StoreAudioDownloadManager.addListener(audioDownloadListener)
    }

    /**
     * 下载进度对话框的唯一渲染入口：同时读三个状态源（电子书标志位 / 电子书进度帧 /
     * 音频下载状态），谁先到都能画对，不依赖 postValue 的投递顺序。
     *
     * @param force 用户显式要求看进度（书籍详情弹窗的「查看下载进度」）。
     *   它会解除 [progressDialogDismissedByUser] 的静默期 —— 否则用户点「后台继续」之后
     *   再点「查看下载进度」会什么都看不到，成为一个明显坏掉的按钮。
     */
    private fun syncDownloadDialog(force: Boolean = false) {
        val bookDownloading = storeViewModel.downloading.value == true
        // 音频只在「进行中」时占用对话框：终态（完成/失败/取消）由 listener 用 Toast 收尾，
        // 不该再留一个挡住书城页的模态窗
        val audio = audioState?.takeIf { !it.isTerminal }
        if (!bookDownloading && audio == null) {
            downloadProgressDialog?.takeIf { it.isShowing }?.dismiss()
            lastProgress = null
            // 没有在跑的任务 ⇒ 下次有新下载时重新自动弹一次
            progressDialogDismissedByUser = false
            return
        }
        if (force) progressDialogDismissedByUser = false
        // 用户在静默期：状态照收（[audioState]/[lastProgress] 已更新），只是不再把窗弹回来
        if (progressDialogDismissedByUser) return

        val dlg = ensureDownloadProgressDialog()
        if (!dlg.isShowing) dlg.show()

        if (audio != null) {
            renderAudioProgress(dlg, audio)
        } else {
            renderBookProgress(dlg, lastProgress)
        }
    }

    /** 合成音频：段级进度条（已入库 n / 共 m 段） */
    private fun renderAudioProgress(dlg: AlertDialog, state: StoreAudioDownloadManager.State) {
        val bar = downloadProgressBar ?: return
        val text = downloadProgressText ?: return
        dlg.setTitle("下载合成音频")
        val heading = when (state.status) {
            StoreAudioDownloadManager.Status.QUEUED -> "排队中…"
            StoreAudioDownloadManager.Status.PAUSED -> state.message.ifEmpty { "已暂停" }
            else -> "正在下载（可离开本页，边下边听）…"
        }
        if (state.total > 0) {
            bar.isIndeterminate = false
            bar.max = state.total
            bar.progress = state.saved
            text.text = "$heading\n已入库 ${state.saved} / ${state.total} 段" +
                if (state.failed > 0) "（${state.failed} 段失败）" else ""
        } else {
            bar.isIndeterminate = true
            text.text = "$heading\n${state.message.ifEmpty { "正在获取音频列表…" }}"
        }
    }

    /** 电子书：字节级进度条（有总长用确定态，chunked 响应退化成不确定态 + 已下载字节数） */
    private fun renderBookProgress(dlg: AlertDialog, p: DownloadProgress?) {
        val bar = downloadProgressBar ?: return
        val text = downloadProgressText ?: return
        dlg.setTitle("下载书籍")
        val heading = if (p?.resuming == true) "继续下载…" else "正在下载书籍…"
        if (p != null && p.total > 0) {
            bar.isIndeterminate = false
            bar.max = if (p.total > Int.MAX_VALUE) Int.MAX_VALUE else p.total.toInt()
            bar.progress = if (p.downloaded > Int.MAX_VALUE) Int.MAX_VALUE else p.downloaded.toInt()
            val percent = (p.downloaded * 100 / p.total).coerceIn(0, 100)
            text.text = "$heading\n${FileUtils.formatSize(p.downloaded)} / " +
                "${FileUtils.formatSize(p.total)}（$percent%）"
        } else {
            bar.isIndeterminate = true
            text.text = if (p != null && p.downloaded > 0) {
                "$heading\n已下载 ${FileUtils.formatSize(p.downloaded)}"
            } else {
                heading
            }
        }
    }

    private fun ensureDownloadProgressDialog(): AlertDialog {
        if (downloadProgressDialog == null) {
            val ctx = requireContext()
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 48, 60, 48)
            }
            downloadProgressText = TextView(ctx).apply { text = "正在下载…"; textSize = 15f }
            downloadProgressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 24 }
            }
            container.addView(downloadProgressText)
            container.addView(downloadProgressBar)
            // 可关闭：合成音频的下载**跑在进程级下载器里**，关掉这个窗只是不看进度，
            // 下载照常继续（用户点「后台继续」就能去阅读页收听已经下好的部分）。
            // 原来 setCancelable(false) 是为了防止「有下载在跑却没意识到」，现在有 Toast 收尾，
            // 强制不可关反而把用户按在书城页。
            downloadProgressDialog = MaterialAlertDialogBuilder(ctx)
                .setTitle("下载中")
                .setView(container)
                .setCancelable(true)
                // 只在**用户动作**上置静默闩：点「后台继续」或返回键/点窗外。
                // 不能用 setOnDismissListener —— Dialog.dismiss() 的 dismiss 回调是走 Handler
                // **异步**投递的，于是「任务结束时我们主动 dismiss」也会在下一轮主循环里把闩置 true，
                // 结果下一次下载的进度窗再也弹不出来（假死成「点了没反应」）。
                .setNegativeButton("后台继续") { _, _ -> progressDialogDismissedByUser = true }
                .setOnCancelListener { progressDialogDismissedByUser = true }
                .create()
        }
        return downloadProgressDialog!!
    }

    /**
     * 进度对话框会被 WindowManager 持有并强引用 Activity，不在视图销毁时 dismiss 就会
     * 「Activity has leaked window」+ 整页泄漏。这里在视图销毁前主动 dismiss 并断开引用。
     */
    override fun onDestroyView() {
        downloadProgressDialog?.takeIf { it.isShowing }?.dismiss()
        downloadProgressDialog = null
        downloadProgressBar = null
        downloadProgressText = null
        // 视图销毁时一并丢掉缓存的进度帧。下载本身跑在进程级下载器里仍在继续，
        // 重新进入本页时 addListener 会补一帧现状，对话框会按当前状态重新弹出。
        lastProgress = null
        audioState = null
        // 静默期只属于「当前这一页」。用户回到书城页时应当能直接看到进度，
        // 而不是被上一次「后台继续」的记忆一直压着不显示。
        progressDialogDismissedByUser = false
        StoreAudioDownloadManager.removeListener(audioDownloadListener)
        super.onDestroyView()
    }

    private fun setupRecyclerView() {
        adapter = BookStoreAdapter(onItemClick = { book ->
            showBookDetailDialog(book)
        })
        // 2 列网格
        recyclerViewStore.layoutManager = GridLayoutManager(requireContext(), 2)
        recyclerViewStore.adapter = adapter
    }

    private fun setupSearch() {
        etSearch.inputType = InputType.TYPE_CLASS_TEXT
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val keyword = etSearch.text.toString()
                if (keyword.isBlank()) {
                    storeViewModel.backToHome()
                } else {
                    storeViewModel.search(keyword)
                }
                true
            } else {
                false
            }
        }
    }

    /**
     * 所有数据观察者一律只做一件事——数据变了就按当前模式整体重画一次。
     *
     * 若让两个观察者互相依赖对方的当前值：
     *  - browseMode 观察者只在 `!browse` 时干活；
     *  - storeBooks 观察者只在 `browseMode.value == true` 时干活。
     * 而这两个值来自 ViewModel 里两条先后发出的 postValue（见
     * `BookStoreViewModel.loadBooks`：先 `_storeBooks`、后 `_browseMode`），投递顺序固定。
     * 于是首次从首页点进「全部/分类」时会两边都跳过——列表停在旧内容上，
     * **必须再点一次才刷新**。
     *
     * 收敛成单一 [render] 后，无论谁先到都按同一份当前状态渲染，不再依赖投递顺序。
     */
    private fun observeViewModel() {
        storeViewModel.browseMode.observe(viewLifecycleOwner) { browse ->
            if (!browse) {
                // 回到首页模式时清掉搜索框。LiveData 只在值变化时回调，
                // 所以不会在用户输入过程中被热书/新书的回调误清掉。
                etSearch.setText("")
            }
            render()
        }

        storeViewModel.hotBooks.observe(viewLifecycleOwner) { render() }
        storeViewModel.newBooks.observe(viewLifecycleOwner) { render() }
        storeViewModel.storeBooks.observe(viewLifecycleOwner) { render() }

        // 分类 Chips
        storeViewModel.categories.observe(viewLifecycleOwner) { categories ->
            buildCategoryButtons(categories.map { it.name })
        }

        // 标签 Chips：没有标签时整块 gone（旧服务端不返回 tags，不该留一行空白）
        storeViewModel.tags.observe(viewLifecycleOwner) { tags ->
            tagScroll.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
            buildTagButtons(tags)
        }

        // 加载状态
        storeViewModel.loading.observe(viewLifecycleOwner) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        // 加载/下载失败 → 统一错误提示（原先只在日志里，界面停在空白列表上）
        storeViewModel.errorEvent.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                context?.showError(message)
                storeViewModel.consumeErrorEvent()
            }
        }

        // 下载完成 → 入库书架 + 提示
        storeViewModel.downloadEvent.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                // displayName 与 fileName 必须分开传：入库的书名是原始书名，
                // 而文件在磁盘上沿用服务端文件名（含防重名的随机码前缀）
                bookViewModel.importEbookFromFile(result.fileName, result.displayName)
                // 视图可能已被销毁，用 context?.let 避免 requireContext() 崩溃
                context?.let { ctx ->
                    Toast.makeText(
                        ctx,
                        "已加入书架：《${result.displayName.substringBeforeLast('.')}》",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                storeViewModel.consumeDownloadEvent()
            }
        }

        // 书城页顺手做了书名对齐（历史条目去掉随机码前缀）→ 书架需要重新读一次。
        // 必须用 refreshLibraryAfterDownload()（ebooks + tasks 一起刷）而不是 loadEbooks()：
        // 书架条目是 merge(ebooks, tasks) 出来的，只刷 ebooks 会让**同名任务**留着旧书名，
        // 于是书架出现两张卡片（一张新名、一张还带随机码的旧名），直到下次重启才消失。
        storeViewModel.libraryChanged.observe(viewLifecycleOwner) { changed ->
            if (changed == true) {
                bookViewModel.refreshLibraryAfterDownload()
                storeViewModel.consumeLibraryChanged()
            }
        }
    }

    /**
     * 按当前模式整体重画一次。
     * 只读当前值、不做任何「看另一个 LiveData 到没到」的提前返回。
     */
    private fun render() {
        if (storeViewModel.browseMode.value == true) {
            tvStoreTitle.text = buildListTitle()
            tvStoreTitle.visibility = View.VISIBLE
            submitList(storeViewModel.storeBooks.value ?: emptyList())
        } else {
            // 首页不显示「书城」二字（按需求去掉），标题整行 gone：
            // 底部导航已经写着「书城」，页内再印一遍是重复。
            // 注意只 gone 掉本控件、保留上下文标题能力 —— 切到分类/搜索时
            // 上面的分支会把同一控件重新 visible 并显示「分类：X / 搜索：X」。
            tvStoreTitle.visibility = View.GONE
            // 首页榜单：热门在前，去重后拼上新书
            val hot = storeViewModel.hotBooks.value ?: emptyList()
            val new = storeViewModel.newBooks.value ?: emptyList()
            submitList((hot + new).distinctBy { it.id })
        }
    }

    private fun buildListTitle(): String {
        val category = storeViewModel.currentCategory
        val keyword = storeViewModel.currentKeyword
        val tag = storeViewModel.currentTag
        // 分类 / 标签 / 搜索是叠加关系，标题把所有生效的条件都列出来，
        // 否则用户看到列表变短却不知道是哪一维筛的
        val parts = mutableListOf<String>()
        if (!keyword.isNullOrEmpty()) parts.add("搜索：$keyword")
        if (!category.isNullOrEmpty()) parts.add("分类：$category")
        if (!tag.isNullOrEmpty()) parts.add("标签：$tag")
        return if (parts.isEmpty()) "全部书籍" else parts.joinToString(" · ")
    }

    private fun submitList(books: List<StoreBook>) {
        adapter.submitList(books)
        tvEmpty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        recyclerViewStore.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 构建分类按钮（"全部" + 各分类） */
    private fun buildCategoryButtons(categories: List<String>) {
        categoryContainer.removeAllViews()
        selectedCategoryButton = null

        val all = listOf("推荐", "全部") + categories
        for ((index, name) in all.withIndex()) {
            val button = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = name
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = if (index < all.size - 1) 12 else 0
                }
                setOnClickListener { onCategoryClick(this, name) }
            }
            categoryContainer.addView(button)
            if (index == 0) {
                markCategorySelected(button)
            }
        }
    }

    private fun onCategoryClick(button: MaterialButton, name: String) {
        markCategorySelected(button)
        when (name) {
            // 回首页会清掉 viewModel 里的标签条件，标签栏的选中态必须同步复位，
            // 否则界面上还高亮着某个标签、列表却是全站书籍
            "推荐" -> { storeViewModel.backToHome(); resetTagSelection() }
            "全部" -> storeViewModel.browseAll()
            else -> storeViewModel.filterByCategory(name)
        }
    }

    private fun markCategorySelected(button: MaterialButton) {
        selectedCategoryButton?.alpha = 0.6f
        button.alpha = 1.0f
        selectedCategoryButton = button
    }

    /**
     * 构建标签按钮：第一个是「不限」（清除标签筛选），其余是各标签。
     *
     * 必须有「不限」这一项：标签按钮是 toggle 式的，若只有已选中的那个能取消，
     * 用户点错标签后要靠"再点一次"才恢复，而选中态本身用的是透明度差别、不够显眼。
     */
    private fun buildTagButtons(tags: List<StoreTag>) {
        tagContainer.removeAllViews()
        selectedTagButton = null
        if (tags.isEmpty()) return

        val current = storeViewModel.currentTag
        val labels: List<String?> = listOf(null) + tags.map { it.name }
        for ((index, value) in labels.withIndex()) {
            val button = MaterialButton(
                requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = value ?: "不限"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = if (index < labels.size - 1) 12 else 0
                }
                alpha = if (value == current) 1.0f else 0.6f
                setOnClickListener { onTagClick(this, value) }
            }
            tagContainer.addView(button)
            if (value == current) selectedTagButton = button
        }
    }

    private fun onTagClick(button: MaterialButton, tag: String?) {
        markTagSelected(button)
        storeViewModel.filterByTag(tag)
    }

    /** 标签是单选式筛选（点一个筛一个），选中态用透明度区分，与分类按钮同一套视觉语言 */
    private fun markTagSelected(button: MaterialButton) {
        for (index in 0 until tagContainer.childCount) {
            (tagContainer.getChildAt(index) as? MaterialButton)?.alpha = 0.6f
        }
        button.alpha = 1.0f
        selectedTagButton = button
    }

    /** 复位标签选中态到「不限」（首页/清除筛选时用） */
    private fun resetTagSelection() {
        for (index in 0 until tagContainer.childCount) {
            (tagContainer.getChildAt(index) as? MaterialButton)?.alpha = if (index == 0) 1.0f else 0.6f
        }
        selectedTagButton = null
    }

    /**
     * 书籍详情弹窗。
     *
     * 首选动作是「在线阅读」：点开即读、不下载电子书文件，音频播到哪下到哪（见
     * [openStoreBookOnline] 与 `BookViewModel.loadEbookFromStoreOnline`）。
     * 原有的「下载到书架 / 下载合成音频」两项整本离线动作收进「下载/离线…」二级弹窗
     * （[showDownloadOptionsDialog]）—— Material 弹窗只有三个按钮位，放不下第四个，
     * 而「在线即读」正是本次要突出的默认路径。
     */
    private fun showBookDetailDialog(book: StoreBook) {
        val sizeText = book.fileSize?.let {
            when {
                it >= 1024 * 1024 -> String.format("%.1f MB", it / 1024.0 / 1024.0)
                it >= 1024 -> String.format("%.1f KB", it / 1024.0)
                else -> "$it B"
            }
        } ?: "未知"

        // 合成音频下载状态（进程级下载器）：入队情况与已入库段数都直接反映在这里
        val dlState = StoreAudioDownloadManager.stateOf(book.id)
        val message = buildString {
            append("作者：").append(book.author ?: "佚名").append('\n')
            append("分类：").append(book.category ?: "未分类").append('\n')
            // 标签：多标签以「、」连成一行；没有标签时整行省略，不留「标签：」空行
            val tagText = book.tags?.filter { it.isNotBlank() }.orEmpty()
            if (tagText.isNotEmpty()) {
                append("标签：").append(tagText.joinToString("、")).append('\n')
            }
            append("格式：").append(book.fileExtension?.uppercase() ?: "未知")
            append("　大小：").append(sizeText).append('\n')
            append("下载次数：").append(book.downloadCount ?: 0)
            when {
                book.taskId == null -> append("\n\n本书暂无合成音频（可在线阅读文字，点读用系统朗读）")
                dlState != null && !dlState.isTerminal ->
                    append("\n\n合成音频下载中：已入库 ${dlState.saved}/${dlState.total} 段")
                dlState != null && dlState.status == StoreAudioDownloadManager.Status.COMPLETED ->
                    append("\n\n合成音频已全部下载（共 ${dlState.total} 段）")
                else -> append("\n\n可直接「在线阅读」：正文即时呈现，音频播到哪下到哪、并预取后续若干段")
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("《${book.title}》")
            .setMessage(message)
            .setPositiveButton("在线阅读") { _, _ -> openStoreBookOnline(book) }
            .setNeutralButton("下载/离线…") { _, _ -> showDownloadOptionsDialog(book) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开书城「在线读」：不落电子书文件，正文按 bookId 在线取（带本地缓存）、
     * 音频按需下载。用书城书名作为虚拟书名（= audio_segments.book_name），
     * 与日后真下载到书架的同名书共用同一份音频缓存。
     */
    private fun openStoreBookOnline(book: StoreBook) {
        val ctx = context ?: return
        val intent = Intent(ctx, BookContentActivity::class.java).apply {
            putExtra("BOOK_TABLE_NAME", book.title)
            putExtra("STORE_ONLINE", true)
            putExtra("STORE_BOOK_ID", book.id)
        }
        ctx.startActivity(intent)
    }

    /**
     * 二级弹窗：整本离线动作。
     *  - 「下载到书架」：下载电子书文件到本地书架（离线阅读，读的是文件）。
     *  - 「下载合成音频」/「查看下载进度」（仅 taskId 非 null）：整本后台下载音频，
     *    受「仅 WiFi」约束（与在线按需下载不同 —— 后者任何网络都下）。
     */
    private fun showDownloadOptionsDialog(book: StoreBook) {
        val dlState = StoreAudioDownloadManager.stateOf(book.id)
        // ⚠️ 按钮顺序：AlertDialog 的按钮栏固定按 [neutral | negative | positive]
        // 从左到右排。**「返回」必须占 neutral（最左）**——原先它是 negative、
        // 夹在「下载到书架」和「下载合成音频」中间，导致：
        //  ① 主操作「下载到书架」右边紧贴「返回」（都是 neutral 色、宽度相近），
        //     手指按上去极容易误触，直接关掉弹窗；
        //  ② 两个"下载"动作被「返回」隔在两端，看不出它们是同一类选择。
        // 现在两个下载动作相邻靠右、返回单独靠左，扫一眼就知道自己在选什么。
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle("《${book.title}》")
            .setMessage("下载到书架后可完全离线阅读；合成音频可整本后台下载（受「仅 WiFi」设置约束）。")
            .setNeutralButton("返回", null)
            .setNegativeButton("下载到书架") { _, _ ->
                requireLoginThen("下载书籍到书架需要登录后使用") {
                    storeViewModel.downloadBook(book) { success, msg ->
                        if (!success) {
                            // 下载跑在 ViewModel 作用域里，可能比 Fragment 的视图活得更久。
                            // 用户下完单就切走 Tab 时，回调仍会在主线程触发，此时 requireContext()
                            // 抛 IllegalStateException（Fragment not attached）。用 context?.let 静默跳过即可：
                            // 人已经不在这一页，提示没有意义，崩溃却很致命。
                            context?.let { ctx ->
                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        // 任务已合成（taskId 非 null）时增加「下载音频」按钮
        if (book.taskId != null) {
            val running = dlState != null && !dlState.isTerminal
            builder.setPositiveButton(if (running) "查看下载进度" else "下载合成音频") { _, _ ->
                requireLoginThen("下载整本合成音频需要登录后使用") {
                    if (running) {
                        // 这个弹窗是**打开那一刻**的快照（文案与动作都定死了），
                        // 而下载在进程级下载器里继续跑。等用户点下来时它可能已经结束，
                        // 此时进度窗会因为「没有活跃任务」被直接收起来 ——
                        // 点了「查看下载进度」屏幕上什么都不发生，就是一个死按钮。
                        // 所以这里现读一次状态：终态给一句结论，仍在跑才拉进度窗。
                        val now = StoreAudioDownloadManager.stateOf(book.id)
                        if (now == null || now.isTerminal) {
                            val msg = when (now?.status) {
                                StoreAudioDownloadManager.Status.COMPLETED ->
                                    "音频已下载完成，可在书架收听"
                                StoreAudioDownloadManager.Status.FAILED ->
                                    "音频下载已失败，可重新发起下载"
                                else -> "音频下载已结束"
                            }
                            context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
                        } else {
                            // 只把进度对话框拉起来 —— 下载本身在进程级下载器里跑着，不受本页影响。
                            // force=true：用户是明确要看进度，得能越过「后台继续」留下的静默期。
                            syncDownloadDialog(force = true)
                        }
                    } else {
                        StoreAudioDownloadManager.start(book)
                        // 立刻给反馈：任务已入队、可离开本页。真正的进度走进度对话框 / Toast，
                        // 完成后刷新书架（「有音频」图标读的是 audio_segments）
                        context?.let { ctx ->
                            Toast.makeText(
                                ctx,
                                "已开始后台下载，可离开本页，随时打开书籍收听已下好的部分",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
        builder.show()
    }

    /**
     * 书城「写操作」游客门禁：转发到宿主 [MainActivity.requireLogin]，
     * 未登录时弹「需要登录」、登录成功后自动接回本次操作（下载/整本音频）。
     * 宿主理论上是 MainActivity；万一不是则直接执行，避免功能失联。
     */
    private fun requireLoginThen(message: String, action: () -> Unit) {
        val host = activity as? MainActivity
        if (host != null) host.requireLogin(message, action) else action()
    }
}
