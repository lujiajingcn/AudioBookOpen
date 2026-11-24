package com.audiobook.audiobookplayer

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.whenResumed
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.Book
import com.audiobook.audiobookplayer.model.RetryFailedTasksRequest
import com.audiobook.audiobookplayer.model.ShelfEntry
import com.audiobook.audiobookplayer.model.Task
import com.audiobook.audiobookplayer.moss.LocalSynthesisService
import com.audiobook.audiobookplayer.moss.MossLocalEngine
import com.audiobook.audiobookplayer.moss.showSynthesisOutcome
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BookListFragment"

/**
 * 书架页。
 *
 * 原「任务列表」页已下线：合成任务不再单独成页，而是按书名聚合后挂在对应
 * 书籍条目下展示（见 ShelfEntry / ShelfAdapter），任务的状态、进度与下载、
 * 阅读、重试等操作全部收敛到这里。
 */
class BookListFragment : Fragment() {

    companion object {
        /** 单段音频下载体积上限（正常单段远小于此；超限视为服务端异常响应，弃段保进程） */
        private const val MAX_SEGMENT_BYTES = 50L * 1024 * 1024

        /** 分类筛选：null = 全部。可选项见 res/menu/shelf_filter_menu.xml */
        private val CATEGORY_ALL = null
    }

    private lateinit var viewModel: BookViewModel
    private lateinit var adapter: ShelfAdapter
    private lateinit var recyclerViewBooks: RecyclerView
    private lateinit var btnRetryAllFailed: Button
    /** 顶部右上角「更多(...)」：语音合成 / 导入电子书 / 链接导入 三个入口收在这里 */
    private lateinit var btnMore: ImageButton
    /** 标题栏上的放大镜，点击进入独立搜索页 */
    private lateinit var btnSearch: ImageButton
    /** 标题栏上的漏斗图标，点击弹出分类筛选菜单 */
    private lateinit var btnFilter: ImageButton
    private lateinit var tvShelfTitle: TextView
    /** 「长按可拖动排序」的操作提示（书架非空时显示） */
    private lateinit var tvDragHint: TextView
    private lateinit var llEmpty: LinearLayout
    private lateinit var ivEmpty: ImageView
    private lateinit var tvEmpty: TextView
    private lateinit var btnEmptyImport: Button

    /** 当前选中的分类（null = 全部） */
    private var selectedCategory: String? = CATEGORY_ALL

    /** 新手引导弹窗，持有引用以便在 onDestroyView 里 dismiss，避免泄漏 Activity */
    private var onboardingDialog: Dialog? = null

    /**
     * 本进程内引导是否已经弹出。
     * 「已看过」标记是在对话框 onShow 回调里异步落盘的，落盘之前若视图重建，
     * 只查 prefs 会再弹一个一模一样的对话框；两层完全重叠时关掉上面那层看起来
     * 仍然在原处，等同于「关不掉」。
     */
    private var onboardingShown = false

    /** 当前失败任务集合（「重试全部失败」按钮的操作范围，随书架数据刷新） */
    private var failedTasksCache: List<Task> = emptyList()

    private val importEbookLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val clipData = result.data?.clipData
            val dataIntent = result.data

            if (clipData != null) {
                // 多选情况
                for (i in 0 until clipData.itemCount) {
                    val uri = clipData.getItemAt(i).uri
                    viewModel.importEbook(uri)
                }
                Toast.makeText(requireContext(), "正在导入 ${clipData.itemCount} 个电子书...", Toast.LENGTH_SHORT).show()
            } else {
                // 单选情况
                dataIntent?.data?.let { uri ->
                    viewModel.importEbook(uri)
                    Toast.makeText(requireContext(), "正在导入电子书...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_book_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerViewBooks = view.findViewById(R.id.recyclerView)
        viewModel = ViewModelProvider(requireActivity())[BookViewModel::class.java]

        btnRetryAllFailed = view.findViewById(R.id.btnRetryAllFailed)
        btnMore = view.findViewById(R.id.btnMore)
        btnSearch = view.findViewById(R.id.btnSearch)
        btnFilter = view.findViewById(R.id.btnFilter)
        tvShelfTitle = view.findViewById(R.id.tvShelfTitle)
        tvDragHint = view.findViewById(R.id.tvDragHint)

        adapter = ShelfAdapter(
            onBookClick = { entry -> onShelfEntryClick(entry) },
            onDeleteClick = { entry ->
                // 分三类：
                //  ① 有本地电子书文件 → 走原有删书（连带任务/音频/收藏级联）
                //  ② 书城「在线占位条目」（无本地文件 + store_book_id>0）
                //     → 走**非破坏式**「从书架移出」：只删本机 ebooks 那一行、通知
                //     服务端 DELETE /api/shelf，不动收藏/音频缓存/关联任务
                //  ③ 只有云端任务、没电子书也没占位 → 沿用原「删任务记录」流程
                val book = entry.book
                if (entry.hasEbookFile) {
                    showDeleteConfirmation(book)
                } else if (book.storeBookId > 0L) {
                    showRemoveShelfPlaceholderConfirmation(entry)
                } else {
                    showDeleteTasksConfirmation(entry)
                }
            },
            onDownloadClick = { task -> downloadTaskAudio(task) },
            onRetryClick = { task -> confirmRetryTask(task) },
            onContinueLocalClick = { task -> continueLocalSynthesis(task) },
            onPauseClick = { task -> toggleLocalSynthesisPause(task) },
            onTaskStatusClick = { entry, task -> openShelfEntryForTask(entry, task) },
            onCategoryClick = { entry -> showCategoryEditDialog(entry.book) }
        )

        recyclerViewBooks.adapter = adapter
        recyclerViewBooks.layoutManager = LinearLayoutManager(requireContext())

        // 书架为空引导
        llEmpty = view.findViewById(R.id.llEmpty)
        ivEmpty = view.findViewById(R.id.ivEmpty)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        btnEmptyImport = view.findViewById(R.id.btnEmptyImport)
        btnEmptyImport.setOnClickListener { openFilePicker() }

        // 书架数据 = 电子书 ∪ 合成任务，由 ViewModel 聚合后统一分发
        viewModel.shelfEntries.observe(viewLifecycleOwner) { all ->
            refreshFailedTasksBanner(all)
            refreshList()
            // 书架内容变了（导入/删除/本机合成落库/下载完成）→ 依据本地缓存重算覆盖徽章，
            // 让「本机有声」这档即时反映，不必等一次跨端同步。
            viewModel.refreshCoverageBadges()
        }

        // 有声覆盖图徽章（Phase3 跨端）：VM 依据本地缓存算好 book.id→徽章后灌给适配器着色。
        viewModel.coverageBadges.observe(viewLifecycleOwner) { badges ->
            adapter.setCoverageBadges(badges)
        }

        // 首次启动轻量新手引导（只弹一次）
        maybeShowOnboarding()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.deleteImpact.collect { result ->
                    result ?: return@collect
                    showDeleteImpactDialog(result.book, result.impact)
                    viewModel.clearDeleteImpact()
                }
            }
        }

        // 本机合成的暂停态单独订阅：它**不改数据库**（改的只是按钮文案与状态文字），
        // 所以合成进度那条刷新链路带不动它 —— 不订阅的话，用户在合成页点了暂停，
        // 回到书架看到的按钮仍是「暂停」，再点一下就成了「暂停两次」。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LocalSynthesisService.paused.collect { paused ->
                    adapter.isLocalPaused = paused
                }
            }
        }

        // 本机合成的**终态结论**也必须在这里接一份。
        //
        // 书架是「继续合成」的入口之一（见 continueLocalSynthesis），而那条路径
        // 根本不会创建 TtsFragment —— 只在合成页做提示等于对这条路完全静默：
        // 用户点完「继续合成」，等几分钟回来看，任务行要么「失败」要么消失，
        // 中间发生了什么、为什么失败，一个字都没有（走查 N1/N2）。
        //
        // 两个 Fragment 同时 STARTED 时靠 consumeOutcome 的 CAS 去重，只会弹一次。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LocalSynthesisService.outcome.collect { incoming ->
                    if (incoming == null) return@collect
                    // 先确认视图可用再认领：先认领后判断会把这条结论永久吞掉，
                    // 而合成页那边也已经在 CAS 上输了。
                    if (!isAdded) return@collect
                    val ctx = context ?: return@collect
                    val outcome = LocalSynthesisService.consumeOutcome(incoming) ?: return@collect
                    showSynthesisOutcome(ctx, outcome)
                    // P2 #7：本机合成落库改变了覆盖图（source=local），静默推一次服务端，
                    // 让别的设备尽快亮起橙色「另一设备已合成·本机待合成」徽章，不必等下次进页。
                    // FAILED 通常一段都没落，跳过省一次网络。两个 Fragment 靠 consumeOutcome 的
                    // CAS 去重，恰好只有一个会走到这里 → 全局只推一次（与 TtsFragment 的同一处对齐）。
                    if (outcome.level != LocalSynthesisService.Outcome.Level.FAILED) {
                        viewModel.syncAudioCoverageFromServer()
                    }
                }
            }
        }

        btnMore.setOnClickListener { showShelfMoreMenu() }
        btnSearch.setOnClickListener { openSearchPage() }
        btnFilter.setOnClickListener { showShelfFilterMenu() }

        btnRetryAllFailed.setOnClickListener {
            if (failedTasksCache.isNotEmpty()) confirmRetryAllFailed(failedTasksCache)
        }

        setupShelfDragSort()
    }

    /**
     * 书架「长按拖放调整书籍顺序」。
     *
     * 手势：长按书籍行即进入拖拽（RecyclerView 的长按手势），
     * 拖动时其余书籍让位，松手落库。
     *
     * 两个必须处理的坑：
     *  · **拖拽中不能刷新列表**：合成进度每几秒写库一次，刷新会把列表重排回
     *    数据库顺序，用户手里的书会"跳"回去。适配器在拖拽期间把刷新暂存
     *    （见 ShelfAdapter.isDragging / submitShelfItems）。
     *  · **筛选状态下拖的是子集**：落库时要把可见项的新顺序映射回**全量**列表，
     *    不可见项保持各自原来的位置（见 mergeShelfOrderIntoFullList），
     *    否则没显示出来的书会被挤到最后。
     */
    private fun setupShelfDragSort() {
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = adapter.moveItem(
                viewHolder.bindingAdapterPosition,
                target.bindingAdapterPosition
            )

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    // 进入拖拽态：期间的列表刷新先暂存，别把书"跳"回去
                    adapter.beginDrag()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val newVisibleOrder = adapter.endDrag()
                persistShelfOrder(newVisibleOrder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // 不支持滑动删除：删除有独立的删除按钮与二次确认
            }
        })
        touchHelper.attachToRecyclerView(recyclerViewBooks)
    }

    /**
     * 落库拖拽后的顺序。
     *
     * @param newVisibleIds 拖拽后**可见项**从上到下的书籍 id 顺序
     */
    private fun persistShelfOrder(newVisibleIds: List<Int>) {
        if (newVisibleIds.size < 2) return
        val all = viewModel.shelfEntries.value ?: return
        // 可见子集的新顺序 → 映射回全量（不可见项保持原位）
        val merged = mergeShelfOrderIntoFullList(all, newVisibleIds)
        // 只有真正有电子书行的条目能存顺序（虚拟条目在 ebooks 表里没有行）
        val ids = merged.filter { it.hasEbookFile }.map { it.book.id }
        if (ids.size < 2) return
        viewModel.reorderShelfBooks(ids)
    }

    /**
     * 进入搜索页：跳到独立 SearchFragment，压入返回栈。
     * 走 MainActivity 的导航入口以便一并隐藏底部导航栏。
     */
    private fun openSearchPage() {
        val host = activity as? MainActivity ?: return
        host.navigateToSearch()
    }

    /**
     * 按当前分类重算列表并刷新。
     *
     * 搜索由独立 SearchFragment 承担，本页只剩「分类筛选」一个过滤维度：
     * 分类切换、书架数据变化两条路径共用这一处。
     * 空状态必须基于**过滤后**的列表判断，用未过滤的原始数据会永远不显示。
     */
    private fun refreshList() {
        val all = viewModel.shelfEntries.value ?: return
        val visible = applyFilter(all)
        adapter.submitShelfItems(visible)
        updateShelfTitle()

        val cat = selectedCategory
        val isEmpty = visible.isEmpty()
        llEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerViewBooks.visibility = if (isEmpty) View.GONE else View.VISIBLE
        // 拖拽是隐藏手势：书架有书时挂一行提示，否则用户压根不知道能拖
        tvDragHint.visibility = if (isEmpty) View.GONE else View.VISIBLE
        // 分类筛选下没有书时，别再显示「书架还是空的 + 导入电子书」——
        // 书其实在别的分类里，那句文案会让人以为书丢了
        if (cat == null) {
            tvEmpty.text = "书架还是空的\n导入一本电子书，或合成你的第一本有声书吧"
            btnEmptyImport.visibility = View.VISIBLE
        } else {
            tvEmpty.text = "「$cat」分类下暂无书籍\n换个分类看看，或取消筛选显示全部"
            btnEmptyImport.visibility = View.GONE
        }
    }

    /** 首次启动展示一次轻量新手引导 */
    private fun maybeShowOnboarding() {
        val ctx = context ?: return
        if (onboardingShown) return
        if (AppPrefs.isOnboardingDone(ctx)) return
        // 不在 onViewCreated 里直接 show()：此刻宿主 Activity 还在 onStart 阶段，
        // 窗口没有输入焦点，此时添加的对话框窗口可能拿不到焦点，
        // 用户点按钮、按返回键、点弹窗外部全都无响应。
        // whenResumed 是一次性的（只在首次到达 RESUMED 时执行），
        // 宿主被系统授权框等覆盖时处于 PAUSED，不会提前触发；
        // 再 post 一帧，确保窗口已附着并完成布局。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.whenResumed {
                view?.post { showOnboardingDialog(ctx) }
            }
        }
    }

    /** 新手引导弹窗是否正在显示（宿主据此与系统授权框错开，避免两个弹窗同屏） */
    fun isOnboardingDialogShowing(): Boolean = onboardingDialog?.isShowing == true

    private fun showOnboardingDialog(ctx: Context) {
        if (onboardingShown) return
        val host = activity ?: return
        if (!isAdded || view == null || host.isFinishing || host.isDestroyed) return
        onboardingShown = true

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("欢迎使用 AI 有声书 🎧")
            .setMessage(
                "三件事，马上就能用：\n\n" +
                "• 导入电子书：把 epub / txt / pdf 变成有声书\n" +
                "• 语音合成：选音色，一键生成朗读音频\n" +
                "• 发现社区：分享参考音色、交流读书心得\n\n" +
                "现在就从导入第一本书开始吧！"
            )
            // 显式 dismiss，不依赖「listener 传 null 时由 AlertDialog 自动关闭」的隐式行为，
            // 让按钮始终是一条确定可用的关闭出路。
            .setPositiveButton("开始使用") { d, _ -> d.dismiss() }
            .setCancelable(true)
            .create()
        // 返回键与点击弹窗外部同样可关闭，避免用户被困在弹窗里
        dialog.setCanceledOnTouchOutside(true)
        // 落标记必须在对话框**真正显示出来之后**：
        // 若先 setOnboardingDone 再 show()，此刻 Activity 正在 finish、或 show() 抛异常时，
        // 用户一次都没看到引导，标记却已经写进去了，再也补不回来。
        dialog.setOnShowListener { AppPrefs.setOnboardingDone(ctx.applicationContext) }
        dialog.setOnDismissListener {
            onboardingDialog = null
            // 引导弹窗关闭后再让宿主申请通知权限，两个弹窗不同屏
            (host as? MainActivity)?.onOnboardingDialogClosed()
        }
        // 对话框强引用 Activity，不随视图销毁 dismiss 就会 WindowLeaked
        onboardingDialog = dialog
        dialog.show()
    }

    /**
     * 回到书架就刷一次任务。
     *
     * 本机合成跑在 `LocalSynthesisService`（独立进程级服务）里，任务行由它写库；
     * 而 TtsFragment 订阅 `progress` 用的是 `repeatOnLifecycle(STARTED)` ——
     * **用户不在合成页时订阅已解绑，服务结束那一刻的进度事件根本没人接**。
     * 于是「合成已完成 / 已中止」这个事实永远传不到书架。
     *
     * 真实表现（Redmi K30 Pro / Android 12，debug 包 + `run-as` 直查库确认）：
     * 合成 4 段全部完成，`tasks` 表里 `status=completed`、`completed_segments=4`、
     * `book_name` 与书架显示名完全一致，但书架上只有书名 + 分类角标，**没有任务行**。
     *
     * 放在 onResume 而不是 onViewCreated：MainActivity 用 hide/show 切 Fragment，
     * 视图常驻，onViewCreated 只在首次创建时跑一次，切回来不会重跑。
     */
    override fun onResume() {
        super.onResume()
        viewModel.loadTasks()
        // 书城书架跨端同步（服务端为真源）：进书架页即对账一次，
        // 别的设备加/移的书会体现在本机（新增走「在线占位条目」，
        // 移出仅清占位行、不动已下载的本地文件与音频）。
        // 未登录 / 网络失败 → 保留本地、不弹提示；有实际变化时轻提示一次。
        viewModel.syncShelfFromServer { result ->
            val ctx = context ?: return@syncShelfFromServer
            if (!result.ok) return@syncShelfFromServer // 静默：不打扰，下次进页自愈
            if (result.added > 0 || result.removed > 0) {
                val parts = buildList {
                    if (result.added > 0) add("新增 ${result.added} 本")
                    if (result.removed > 0) add("移出 ${result.removed} 本")
                }
                Toast.makeText(ctx, "书架已同步：${parts.joinToString("、")}", Toast.LENGTH_SHORT).show()
            }
        }
        // 有声覆盖图跨端同步（Phase3）：与书架同步一起进页触发。本机有音频→上报并集，
        // 无音频→拉全量；无论成败都据本地缓存刷新书架徽章（未登录时也能显示本机视角覆盖）。
        // 静默进行，不弹 Toast（徽章本身即反馈）。
        viewModel.syncAudioCoverageFromServer()
        // 每书元数据（sortOrder / lastReadAt）跨端同步：只落地 ebooks 两列、绝不碰 category，
        // 影子快照前移保证不二次上报；远端值写库后由包装层刷新书架。
        viewModel.syncBookMeta()
    }

    /**
     * 对话框由 WindowManager 持有并强引用 Activity，视图销毁时不 dismiss 就会
     * 「Activity has leaked window」。在 super.onDestroyView() 之前 dismiss 掉还在显示的引导弹窗。
     */
    override fun onDestroyView() {
        // 视图销毁引起的 dismiss 不算「用户关掉了引导」，
        // 先摘掉监听，避免此时回头去申请通知权限（Activity 可能正在重建）。
        onboardingDialog?.setOnDismissListener(null)
        onboardingDialog?.takeIf { it.isShowing }?.dismiss()
        onboardingDialog = null
        super.onDestroyView()
    }

    /**
     * 顶部「筛选」图标菜单：全部 / 本地 / 书城 / URL / 未分类。
     *
     * 原来这些是一行常驻 chips，占了首屏一整行；收进弹出菜单后，
     * 列表上方只剩标题栏，选中的分类改为体现在标题里（见 updateShelfTitle）。
     */
    private fun showShelfFilterMenu() {
        // 点击回调里 Fragment 可能已被切走，requireContext() 会抛 IllegalStateException
        val ctx = context ?: return
        // Gravity.END：菜单贴着锚点右侧展开，不被屏幕边缘截断
        val popup = PopupMenu(ctx, btnFilter, Gravity.END)
        popup.inflate(R.menu.shelf_filter_menu)
        // 回显当前生效的分类（group 是 single-checkable，勾一个即可）
        popup.menu.findItem(categoryToMenuItemId(selectedCategory))?.isChecked = true
        popup.setOnMenuItemClickListener { item ->
            selectedCategory = menuItemIdToCategory(item.itemId)
            refreshList()
            true
        }
        popup.show()
    }

    /** 分类 → 菜单项 id（null = 全部） */
    private fun categoryToMenuItemId(category: String?): Int = when (category) {
        "本地" -> R.id.action_filter_local
        "书城" -> R.id.action_filter_store
        "URL" -> R.id.action_filter_url
        "未分类" -> R.id.action_filter_uncategorized
        else -> R.id.action_filter_all
    }

    /** 菜单项 id → 分类（null = 全部） */
    private fun menuItemIdToCategory(itemId: Int): String? = when (itemId) {
        R.id.action_filter_local -> "本地"
        R.id.action_filter_store -> "书城"
        R.id.action_filter_url -> "URL"
        R.id.action_filter_uncategorized -> "未分类"
        else -> CATEGORY_ALL
    }

    /**
     * 顶部标题栏的文案只在「筛选生效」时出现（如「筛选：本地」）。
     *
     * 筛选条件收进弹出菜单后屏幕上没有任何常驻提示，用户看到列表变短却不知道为什么；
     * 未筛选时标题完全隐藏（gone，不占位置），保持顶部只有图标的干净观感。
     */
    private fun updateShelfTitle() {
        val cat = selectedCategory
        if (cat == null) {
            tvShelfTitle.visibility = View.GONE
            tvShelfTitle.text = ""
        } else {
            tvShelfTitle.visibility = View.VISIBLE
            tvShelfTitle.text = "筛选：$cat"
        }
    }

    /**
     * 书架条目过滤：仅按分类。
     *
     * 顺序固定为「导入时间倒序」（getEbooks 的 created_at DESC 自然顺序）。
     * 搜索由独立 SearchFragment 承担，本页不再做关键字过滤。
     */
    private fun applyFilter(entries: List<ShelfEntry>): List<ShelfEntry> {
        val cat = selectedCategory
        return entries.filter { entry ->
            val book = entry.book
            val bookCat = book.category.ifBlank { "未分类" }
            cat == null || bookCat == cat
        }
    }

    /** 点击分类标签 → 弹窗改分类（用户可手动打标签） */
    private fun showCategoryEditDialog(book: Book) {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            setText(book.category.ifBlank { "" })
            hint = "输入分类（如 小说 / 教材 / 本地）"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("修改《${book.displayName}》的分类")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newCat = input.text.toString().trim()
                viewModel.setBookCategory(book.displayName, newCat)
                Toast.makeText(ctx, "已更新分类：${newCat.ifBlank { "未分类" }}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 顶部右上角「更多(...)」菜单：语音合成 / 导入电子书 / 链接导入。
     *
     * 用 PopupMenu 而不是常驻三个大按钮：这三个入口都是低频操作，
     * 常驻会占掉首屏一整行，把搜索、筛选与书籍列表挤下去。
     */
    private fun showShelfMoreMenu() {
        // 点击回调里 Fragment 可能已被切走，requireContext() 会抛 IllegalStateException
        val ctx = context ?: return
        // Gravity.END：菜单贴着锚点右侧展开，不被屏幕边缘截断
        val popup = PopupMenu(ctx, btnMore, Gravity.END)
        popup.inflate(R.menu.shelf_more_menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sync_now -> syncNow()
                R.id.action_tts -> showTts()
                R.id.action_import_ebook -> openFilePicker()
                R.id.action_import_url -> showUrlImportDialog()
            }
            true
        }
        popup.show()
    }

    /**
     * P2 #6：用户主动点「立即同步」时走这里。与进页 [onResume] 的**静默**自动同步不同，
     * 显式操作必须给出**分级反馈**——成功/未变化/失败都要说清楚，失败原因用 P0 #2 已分好类的
     * [AudioCoverageSyncResult.failure]（404=服务端太旧、401=登录过期、5xx=服务端故障、网络不可达）
     * 翻译成用户能行动的话，而不是笼统一句"同步失败"。
     *
     * 书架与覆盖图两条同步**串行**跑（书架先、覆盖图后），最后合并成**一条** Toast，
     * 避免两个 Toast 相互顶掉导致前一条信息丢失。未登录时先弹登录（[MainActivity.requireLogin]），
     * 登录成功后再真正发起同步。
     */
    private fun syncNow() {
        val ctx = context ?: return
        // 未登录：引导登录后重试。sync 管理器内部也会判 NOT_AUTHED，但显式操作应主动提示而非静默跳过。
        if (!AuthManager.isAuthenticated) {
            (activity as? MainActivity)?.requireLogin("跨端同步需要登录后使用") { syncNow() }
            return
        }
        Toast.makeText(ctx, "正在同步跨端数据…", Toast.LENGTH_SHORT).show()
        // 第一步：书架同步（服务端为真源）。回调在主线程。
        viewModel.syncShelfFromServer { shelf ->
            val shelfPart = when {
                !shelf.ok -> "书架同步失败"
                shelf.added > 0 || shelf.removed > 0 -> buildList {
                    if (shelf.added > 0) add("新增 ${shelf.added} 本")
                    if (shelf.removed > 0) add("移出 ${shelf.removed} 本")
                }.joinToString("、", prefix = "书架：")
                else -> "书架已是最新"
            }
            // 第二步：覆盖图同步（分级反馈的主要来源）。
            viewModel.syncAudioCoverageFromServer { cov ->
                val c = context ?: return@syncAudioCoverageFromServer
                val covPart = describeCoverageResult(cov)
                Toast.makeText(c, "$shelfPart；$covPart", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 把覆盖图同步结果翻译成一句用户可读的话（消费 P0 #2 的 [AudioCoverageSyncFailure] 分级）。
     * [AudioCoverageSyncResult.message] 在多数分支已是可读文案，这里做兜底与「未变化」措辞。
     */
    private fun describeCoverageResult(r: AudioCoverageSyncResult): String = when {
        r.ok && r.notModified -> "云端有声覆盖已是最新"
        r.ok -> if (r.serverTotal > 0) "有声覆盖已同步（${r.serverTotal} 条）" else "有声覆盖已同步"
        // 失败：优先用分级后的 message（已含"服务端太旧/登录过期/网络不可达"等可行动提示）
        r.message.isNotBlank() -> r.message
        else -> "有声覆盖同步失败"
    }

    /** 从链接导入电子书：弹窗输入 URL */
    private fun showUrlImportDialog() {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            hint = "粘贴电子书链接（http/https）"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("从链接导入")
            .setMessage("支持 txt / epub / pdf / mobi 直链，下载到本机后入库。")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    Toast.makeText(ctx, "正在从链接导入…", Toast.LENGTH_SHORT).show()
                    viewModel.importFromUrl(url)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 根据失败任务数量刷新「重试全部失败」入口的可见性与文案 */
    private fun refreshFailedTasksBanner(entries: List<ShelfEntry>) {
        // 批量重试走**服务端**接口（需要 serverTaskId），而本机合成任务没有服务端 id
        // —— 它必须在任务行里用「继续合成」入口处理。
        // 原先把本机任务也算进 banner：按钮上的计数会包含它们，但 retryAllFailed
        // 里的 mapNotNull 会把它们静默丢掉 ⇒ 用户看到「重试全部失败（3）」，
        // 点下去只有 1 个真被重试，还以为另外 2 个又失败了。
        failedTasksCache = entries.flatMap { it.tasks }
            .filter { it.status == "failed" && it.serverTaskId != null }
        btnRetryAllFailed.visibility =
            if (failedTasksCache.isEmpty()) View.GONE else View.VISIBLE
        btnRetryAllFailed.text = "重试全部失败（${failedTasksCache.size}）"
    }

    /**
     * 跳转到语音合成。当前宿主是 MainActivity 时直接切换 fragment，
     * 否则（如作为独立 Activity 的内容）启动新的 MainActivity。
     */
    private fun showTts() {
        val activity = activity
        if (activity is MainActivity) {
            activity.showTts()
        } else {
            startActivity(Intent(requireContext(), MainActivity::class.java))
        }
    }

    /**
     * 点击书籍条目的处理：
     * - 有本地电子书文件 → 直接打开阅读器（原行为）
     * - 书城「在线占位条目」（无本地文件 + store_book_id>0）→ 走书城「在线读」链路：
     *   正文按 storeBookId 走 `GET api/store/book/{id}/content`、音频按需下载，
     *   与书城页点「在线阅读」同一条路径（复用 [openStoreBookOnline]），零额外实现。
     * - 只有合成任务、没有本地文件 → 用任务的服务端 ID 打开阅读器，
     *   文本与音频从 audio_segments 表读取
     */
    private fun onShelfEntryClick(entry: ShelfEntry) {
        val book = entry.book
        if (entry.hasEbookFile) {
            openBookContent(book)
            return
        }

        // 书城占位条目：本机从未下载过这本书，但服务端有正文与（可能的）音频。
        // 直接进在线读，别掉进"该书暂无可阅读内容"分支——那是给"云端任务未落
        // 本地"的兜底，书城在架的书不该看到它。
        if (book.storeBookId > 0L) {
            openStoreBookOnline(book)
            return
        }

        val task = entry.latestTask
        if (task != null && task.hasAudio) {
            openBookReader(task)
        } else {
            Toast.makeText(requireContext(), "该书暂无可阅读内容", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 点击任务行的状态信息（「已完成」等）→ 进阅读页。
     *
     * 与 [onShelfEntryClick] 的区别只有一处：**用被点的那条任务**，
     * 而不是 `latestTask`。
     *
     * ## 为什么不能直接复用 latestTask
     *
     * 一本书可以有多条任务（换音色重合成），任务行是一条一条列出来的。
     * 用户点第 2 条任务的「已完成」，期待的是**那条音色**的音频；
     * 用 latestTask 会打开最新那条 —— 明明点的是 A 音色，读到的是 B 音色。
     *
     * ## 为什么本地电子书文件优先
     *
     * 有本地文件时走 [openBookContent]，与点书名完全一致：
     * 阅读器读的是本地正文，音频按书名从 audio_segments 取，
     * 跟是哪条任务无关（audio_segments 不存音色，见 MossLocalSynthesizer 注释）。
     */
    private fun openShelfEntryForTask(entry: ShelfEntry, task: Task) {
        if (entry.hasEbookFile) {
            openBookContent(entry.book)
            return
        }
        if (task.hasAudio) {
            openBookReader(task)
        } else {
            // 与点书名一致：没有可听的内容时给明确提示，而不是进一个空页
            Toast.makeText(requireContext(), "该书暂无可阅读内容", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBookContent(book: Book) {
        if (book.filePath.isEmpty()) {
            Toast.makeText(requireContext(), "该书籍暂无内容", Toast.LENGTH_SHORT).show()
            return
        }

        // 写入最近阅读时间（书架「最近阅读」排序依据）
        viewModel.markBookOpened(book.displayName)

        val intent = Intent(requireContext(), BookContentActivity::class.java).apply {
            putExtra("BOOK_TABLE_NAME", book.displayName)
            putExtra("FILE_PATH", book.filePath)
        }
        startActivity(intent)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/plain",
                "text/markdown",
                "application/pdf",
                "application/epub+zip",
                "application/x-mobipocket-ebook",
                "application/zip",
                "text/html",
                "application/xhtml+xml"
            ))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        importEbookLauncher.launch(intent)
    }

    private fun showDeleteConfirmation(book: Book) {
        // 触发查询，结果通过 deleteImpact StateFlow 观察后弹窗
        viewModel.loadDeleteImpact(book)
    }

    /**
     * 删除影响面弹窗（数据来自 deleteImpact StateFlow）。
     * 动态展示删除后果，避免用户删完才发现合成任务被取消、积分不退、音频没了。
     */
    private fun showDeleteImpactDialog(book: Book, impact: com.audiobook.audiobookplayer.DeleteImpact) {
        // Fragment 可能已切换或销毁，requireContext() 会抛 IllegalStateException，必须先判空
        val ctx = context ?: return
        val lines = buildList {
            add("确定要删除《${book.displayName}》吗？")
            if (impact.activeTasks > 0) {
                add("• 有 ${impact.activeTasks} 个进行中的合成任务：删除后将停止跟踪；服务端正在合成的任务会继续消耗积分且不退还")
            }
            if (impact.totalTasks > 0) {
                add("• 关联的 ${impact.totalTasks} 个任务记录将一并删除")
            }
            if (impact.audioSegments > 0) {
                add("• 已下载的 ${impact.audioSegments} 段音频将一并删除，需重新下载才能播放")
            }
            if (impact.totalTasks == 0 && impact.audioSegments == 0) {
                add("该书没有关联任务与本地音频。")
            }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("删除电子书")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("删除") { _, _ ->
                viewModel.deleteEbook(book)
                Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 删除「只有任务、没有本地电子书文件」条目的任务记录。
     *
     * 这类条目若不给删除入口，孤儿任务（服务端合成过、但本地电子书缺失或已删）
     * 会永久占据书架而无法清除。
     */
    private fun showDeleteTasksConfirmation(entry: ShelfEntry) {
        val ctx = context ?: return
        val count = entry.tasks.size
        val hasActive = entry.tasks.any {
            it.status == "pending" || it.status == "queued" || it.status == "processing"
        }
        val lines = buildList {
            add("《${entry.book.displayName}》没有本地电子书文件，仅有 $count 条合成任务记录。")
            if (hasActive) {
                add("• 其中有进行中的任务：删除后将停止跟踪，服务端正在合成的部分会继续消耗积分且不退还")
            }
            add("• 删除后这些任务将从书架移除，重新合成需再次消耗积分")
            add("• 已下载到本地的音频不会删除，可在「我的 - 存储管理」中清理")
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("删除任务记录")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val deleted = viewModel.deleteTasksForBook(entry.book.displayName)
                    withContext(Dispatchers.Main) {
                        context?.let {
                            Toast.makeText(
                                it,
                                if (deleted > 0) "已删除 $deleted 条任务记录" else "删除失败",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 书城「在线占位条目」的**跨设备移出**确认弹窗。
     *
     * 与 [showDeleteConfirmation] / [showDeleteTasksConfirmation] 都不同 ——
     * 后两者涉及级联清理或"删任务"提示，占位条目既没有本地电子书文件也没有
     * 需要跟踪的本机任务，只需要一条"从书架移出"的普通确认即可。
     *
     * 强调"所有设备都将不再显示"，让用户明白这不是本地清理；同时说明本机
     * 收藏 / 已下载音频不会被删除 —— 那是跨设备资产，别处下架也不该丢。
     */
    private fun showRemoveShelfPlaceholderConfirmation(entry: ShelfEntry) {
        val ctx = context ?: return
        val book = entry.book
        MaterialAlertDialogBuilder(ctx)
            .setTitle("从书架移出")
            .setMessage(
                "确定要将《${book.displayName}》从书架移出吗？\n\n" +
                    "• 所有设备都将不再显示这本书\n" +
                    "• 本机已有的收藏、已下载音频与正文缓存不会一并删除\n" +
                    "• 从书城重新点击「加入书架」可再次收到这本书"
            )
            .setPositiveButton("移出") { _, _ ->
                viewModel.removeShelfPlaceholder(book) { remoteOk ->
                    if (remoteOk) {
                        Toast.makeText(ctx, "已从书架移出", Toast.LENGTH_SHORT).show()
                    } else {
                        // 本地已删，云端可能没删成（离线/未登录/服务端异常）：
                        // 下次 syncShelfFromServer 若服务端仍记录该书在架，会重新
                        // 建占位条目 —— 相当于"这本书又回来了"，用户能自愈。
                        Toast.makeText(
                            ctx,
                            "已从本机移出，云端未同步；下次同步可能重新出现",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开书城「在线读」（不下载电子书文件，正文与音频按 storeBookId 在线取）。
     *
     * 与 [com.audiobook.audiobookplayer.BookStoreFragment.openStoreBookOnline] 同一条
     * 路径：Intent extras `STORE_ONLINE=true` + `STORE_BOOK_ID=<id>` + `BOOK_TABLE_NAME`，
     * 落到 `BookViewModel.loadEbookFromStoreOnline` 里走
     * `GET api/store/book/{id}/content` 与音频按需下载。这里传的是书架上的
     * 占位条目（ebooks 行）而不是书城卡片，但阅读器逻辑完全一致。
     */
    private fun openStoreBookOnline(book: Book) {
        val ctx = context ?: return
        if (book.storeBookId <= 0L) {
            Toast.makeText(ctx, "书城 id 缺失，无法打开在线读", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(ctx, BookContentActivity::class.java).apply {
            putExtra("BOOK_TABLE_NAME", book.displayName)
            putExtra("STORE_ONLINE", true)
            putExtra("STORE_BOOK_ID", book.storeBookId)
        }
        ctx.startActivity(intent)
        // 更新最近打开时间（与 openBookContent 一致，书架「最近阅读」排序需要）
        viewModel.markBookOpened(book.displayName)
    }

    // ---------- 以下为原「任务列表」页迁入的任务能力 ----------

    /**
     * 继续合成「部分完成」的本机任务。
     *
     * 合成器会跳过本地已有音频的段（见 MossLocalSynthesizer 的续跑逻辑），
     * 所以点这个按钮**只补缺的那几段**，已经合成好的不会重算。
     * 因此这里不需要「会消耗积分」那句提示 —— 本机合成全程不扣积分。
     */
    private fun continueLocalSynthesis(task: Task) {
        val context = context ?: return
        // 判重与服务统一用 isBusy：解析与加载模型阶段还没有任何 progress，
        // isRunning 那时恒为 false，用户连点会起第二份任务（≈1 GB 内存）
        if (LocalSynthesisService.isBusy) {
            Toast.makeText(context, "已有本机合成在进行，请先中止或等它结束", Toast.LENGTH_SHORT).show()
            return
        }
        // 数据源必须是 **ebooks**，不是 books —— 两个列表完全不同的东西：
        //   books  = 书城书（getBookTables() 查 sqlite_master 的动态表）
        //   ebooks = 导入/下载的书（ebooks 表，filePath 指向 filesDir/ebooks/ 里的真实文件）
        // 本机合成只对「有本地电子书文件」的书有意义 ⇒ 必然在 ebooks 里。
        // 原先写 books ⇒ 本机任务在书城书列表里找 displayName ⇒ 恒为 null
        // ⇒ **本机任务点「继续合成」永远弹「找不到这本书的本地电子书」**，
        // 而库里 filePath 明明有值（真机 audiobook.db 实测证实）。
        val book = viewModel.ebooks.value?.firstOrNull { it.displayName == task.bookName }
        if (book == null || book.filePath.isEmpty()) {
            // 只有任务没有电子书文件时，合成器会在解析那一步就失败。
            // 与其让它跑起来再报「电子书文件不存在」，不如在这里说清。
            Toast.makeText(context, "找不到这本书的本地电子书，无法继续合成", Toast.LENGTH_LONG).show()
            return
        }
        // 总段数可能未知（本机任务行先建行、后解析；解析前进程被杀时 total_segments 仍是 0）。
        // 那种情况下不能把「缺失 0 段」写进文案 —— 用户会以为没什么可补的，
        // 而实际是还没解析过。null 时改用不带数字的说法。
        val missing = remainingSegmentsOf(task.totalSegments, task.completedSegments)
        MaterialAlertDialogBuilder(context)
            .setTitle("继续合成")
            .setMessage(
                if (missing != null) {
                    "将只合成《${task.bookName}》中缺失的 $missing 段，" +
                        "已完成的段会跳过。不消耗积分，可随时中止。"
                } else {
                    "将只补全《${task.bookName}》中缺失的段，" +
                        "已完成的段会跳过。不消耗积分，可随时中止。"
                }
            )
            .setPositiveButton("继续合成") { _, _ ->
                val ctx = context.applicationContext
                // 音色取**这条任务自己的**音色（audio_name 去掉前缀），
                // 不能写死 DEFAULT_VOICE —— 否则用户选了 Ava/Aria 的那条任务
                // 点「继续合成」会被换成 Junhao，出来的声音和之前那一版对不上。
                val voice = task.audioName.removePrefix(LocalSynthesisService.AUDIO_NAME_PREFIX)
                    .ifBlank { MossLocalEngine.DEFAULT_VOICE }
                LocalSynthesisService.start(ctx, listOf(book), voice)
                Toast.makeText(context, "已开始，可离开此页面", Toast.LENGTH_LONG).show()
                viewModel.loadTasks()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 书架任务行上的「暂停/继续」。
     *
     * 暂停是 **LocalSynthesisService 的进程级开关**：一旦挂起，当前正在跑的本机合成
     * 会在**当前段跑完之后**停下（不会掐断正在推理的那一段），恢复同理。
     * 所以提示语必须写明「等当前段结束」，否则用户点了没反应会以为按钮坏了。
     *
     * 动作按**服务的真实状态**决定，而不是按按钮上的文案 ——
     * 文案与真实状态之间可能隔着一次写库/回传的延迟，按文案走会连点两次
     * 变成「暂停了两次」（第二次用户其实想继续）。
     */
    private fun toggleLocalSynthesisPause(task: Task) {
        val ctx = context ?: return
        // 服务没在跑时按钮就不该出现（见 ShelfTaskActions.canPauseLocal）；
        // 这里再兜一层，避免竞态下点到一个空按钮。
        if (!LocalSynthesisService.isBusy) {
            Toast.makeText(ctx, "当前没有进行中的本机合成", Toast.LENGTH_SHORT).show()
            return
        }
        val appContext = ctx.applicationContext
        if (LocalSynthesisService.paused.value) {
            LocalSynthesisService.resume(appContext)
            Toast.makeText(ctx, "已恢复《${task.bookName}》的合成", Toast.LENGTH_SHORT).show()
        } else {
            LocalSynthesisService.pause(appContext)
            Toast.makeText(ctx, "将在当前段合成结束后暂停《${task.bookName}》", Toast.LENGTH_SHORT).show()
        }
    }

    /** 单任务重试：二次确认；本机任务、在线 partial、在线 failed 三种话术必须分开 */
    private fun confirmRetryTask(task: Task) {
        val context = context ?: return
        // 本机合成的任务不扣积分，且重试只补缺段。
        // 原先一律写「积分已退还，重试将重新消耗积分」——本机用户看到这句会以为要扣钱，
        // 直接选「取消」，功能等于不存在。
        if (task.audioName.startsWith(LocalSynthesisService.AUDIO_NAME_PREFIX)) {
            continueLocalSynthesis(task)
            return
        }
        // partial 与 failed 的代价完全不同，不能共用一句话：
        // failed 是「一段都没成、积分已全额退还，重试要重新扣费」；
        // partial 是「钱没退过（已交付一部分），只补失败的段，通常不额外扣费」。
        // 用 failed 的话术让用户确认 partial，用户会以为要再付一次钱而放弃补齐。
        val missing = remainingSegmentsOf(task.totalSegments, task.completedSegments)
        val isPartial = task.status == "partial"
        val message = if (isPartial) {
            // ⚠️ 必须写 ${countText} 而不是 $countText：中文是合法 Kotlin 标识符字符，
            // `$countText没有…` 会被整体解析成一个标识符，编译报 Unresolved reference。
            val countText = missing?.let { "还剩 $it 段" } ?: "还有段落"
            "这本书${countText}没有合成成功。补齐只会重新合成失败的段落，" +
                "已完成的 ${task.completedSegments} 段与本地音频都会保留，不会重做整本。" +
                "确定补齐《${task.bookName}》吗？"
        } else {
            "任务失败时积分已退还，重试将按规则重新消耗积分。确定重试《${task.bookName}》吗？"
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(if (isPartial) "补齐缺失段落" else "重试失败任务")
            .setMessage(message)
            .setPositiveButton(if (isPartial) "补齐" else "重试") { _, _ -> retryTask(task) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun retryTask(task: Task) {
        if (AuthManager.token == null) {
            (activity as? MainActivity)?.requireLogin("重试云端任务需要登录后使用") { retryTask(task) }
                ?: Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }
        val serverId = task.serverTaskId
        if (serverId == null) {
            Toast.makeText(context, "该任务缺少服务端ID，无法重试", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.retryTask(serverId)
                val msg = if (resp.isSuccessful) {
                    resp.body()?.get("message") as? String ?: "已重新调度"
                } else {
                    extractServerMessage(resp.errorBody()?.string()) ?: "重试失败"
                }
                withContext(Dispatchers.Main) {
                    context?.let { ctx -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                    viewModel.loadTasks()
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    context?.showError("重试失败：${e.toUserMessage()}")
                }
            }
        }
    }

    /** 批量重试：二次确认并提示会重新消耗积分 */
    private fun confirmRetryAllFailed(failedList: List<Task>) {
        // requireContext() 在点击回调里若 Fragment 已销毁会抛 IllegalStateException 崩进程，
        // 故改用 context ?: return（同文件其它异步回调的写法）
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("重试全部失败任务")
            .setMessage("将为 ${failedList.size} 个失败任务重新调度。任务失败时积分已退还，重试将按规则重新消耗积分（部分完成的任务不重复收费）。")
            .setPositiveButton("全部重试") { _, _ -> retryAllFailed(failedList) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun retryAllFailed(failedList: List<Task>) {
        if (AuthManager.token == null) {
            (activity as? MainActivity)?.requireLogin("批量重试云端任务需要登录后使用") { retryAllFailed(failedList) }
                ?: Toast.makeText(context, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }
        val serverIds = failedList.mapNotNull { it.serverTaskId }
        if (serverIds.isEmpty()) {
            Toast.makeText(context, "失败任务缺少服务端ID，无法批量重试", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.retryFailedTasks(
                    RetryFailedTasksRequest(serverIds)
                )
                val msg = if (resp.isSuccessful) {
                    resp.body()?.get("message") as? String ?: "已重新调度"
                } else {
                    extractServerMessage(resp.errorBody()?.string()) ?: "批量重试失败"
                }
                withContext(Dispatchers.Main) {
                    context?.let { ctx -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
                    viewModel.loadTasks()
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    context?.showError("批量重试失败：${e.toUserMessage()}")
                }
            }
        }
    }

    /** 下载某条任务的音频片段（已存在的片段会跳过，只补缺失部分） */
    private fun downloadTaskAudio(task: Task) {
        val uiContext = context ?: return
        // 下载协程跑在 viewModelScope 里，因此**不能**持有 Activity/Fragment 的
        // Context —— 否则整本书下载期间（可能几十分钟）Activity 都无法回收。
        // 提示类 Toast 用 Application Context 即可，它与界面生命周期无关。
        val appContext = viewModel.getApplication<Application>().applicationContext
        if (AuthManager.token == null) {
            (activity as? MainActivity)?.requireLogin("下载云端合成音频需要登录后使用") { downloadTaskAudio(task) }
                ?: Toast.makeText(uiContext, "用户未登录", Toast.LENGTH_SHORT).show()
            return
        }

        // 标记为下载中，UI 会切换到进度显示模式
        viewModel.updateTaskDownloadStatus(task.id, "downloading", 0)

        // 下载是"用户发起的一次性后台作业"，生命期应当跟 ViewModel 而不是跟视图：
        // 用 viewLifecycleOwner.lifecycleScope 时，用户一切走这个 Tab / 旋转屏幕 /
        // 打开阅读器，Fragment 视图销毁就会取消协程：任务永久停在 "downloading"，
        // 进度条再也不动，重试按钮也不出现，只能杀进程重进。
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                val queryId = task.getQueryId()
                if (queryId == null) {
                    // server_task_id 尚未回填。回退到本地 rowid 去查服务端接口必然 404，
                    // 用户只看到含糊的「获取片段列表失败」。
                    Log.w(TAG, "任务尚未同步到服务器，无法下载: localId=${task.id}, status=${task.status}")
                    viewModel.updateTaskDownloadStatus(task.id, "failed", 0)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "任务尚未同步到服务器，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                Log.d(TAG, "下载音频使用ID: localId=${task.id}, serverId=$queryId")

                // Step 1: 获取片段列表（含文本）
                val segmentsResponse = RetrofitClient.apiService.getTaskSegmentsList(queryId)
                if (!segmentsResponse.isSuccessful) {
                    viewModel.updateTaskDownloadStatus(task.id, "failed", 0)
                    Log.e(TAG, "获取片段列表失败: HTTP ${segmentsResponse.code()}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "获取片段列表失败", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val segmentsBody = segmentsResponse.body()
                @Suppress("UNCHECKED_CAST")
                val segmentsMeta = segmentsBody?.get("segments") as? List<Map<String, Any>>
                if (segmentsMeta.isNullOrEmpty()) {
                    viewModel.updateTaskDownloadStatus(task.id, "failed", 0)
                    Log.w(TAG, "没有音频片段")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "该任务没有音频片段", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val totalSegments = segmentsMeta.size
                Log.d(TAG, "共有 $totalSegments 个片段，开始下载")

                // Step 2: 逐个下载音频，下完一段立刻入库一段（流式保存，任何时刻
                // 内存里只驻留一段音频；攒完整本书再写库，大书直接 OOM）。
                // 已有本地音频的片段直接跳过（补下载模式：只拉缺失的，不重复下载整本书）
                var newSaved = 0
                var skippedLocal = 0
                var failedCount = 0
                for ((index, segment) in segmentsMeta.withIndex()) {
                    val segmentId = (segment["id"] as? Number)?.toLong() ?: continue
                    val segmentNumber = (segment["segmentNumber"] as? Number)?.toInt() ?: (index + 1)
                    val textContent = segment["textContent"] as? String ?: "段落 $segmentNumber"

                    if (viewModel.hasLocalAudioSegment(task.bookName, segmentNumber)) {
                        skippedLocal++
                    } else {
                        var downloaded = false
                        try {
                            val downloadResponse = RetrofitClient.heavyApi.downloadSegment(segmentId)
                            if (downloadResponse.isSuccessful) {
                                // 单段限流读取（上限 50MB），超限弃段保进程
                                val audioBytes = downloadResponse.body()?.let { body ->
                                    val out = java.io.ByteArrayOutputStream()
                                    val buf = ByteArray(16 * 1024)
                                    var total = 0L
                                    body.byteStream().use { input ->
                                        var read: Int
                                        while (input.read(buf).also { read = it } != -1) {
                                            total += read
                                            if (total > MAX_SEGMENT_BYTES) {
                                                throw java.io.IOException("单段音频超过 ${MAX_SEGMENT_BYTES / 1024 / 1024}MB 上限，放弃该段")
                                            }
                                            out.write(buf, 0, read)
                                        }
                                    }
                                    out.toByteArray()
                                }
                                if (audioBytes != null && audioBytes.isNotEmpty()) {
                                    if (viewModel.saveAudioSegmentStreaming(task.bookName, segmentId, segmentNumber, textContent, audioBytes)) {
                                        newSaved++
                                        downloaded = true
                                        Log.d(TAG, "片段 $segmentNumber 下载并入库成功 (${audioBytes.size} bytes)")
                                    } else {
                                        Log.w(TAG, "片段 $segmentNumber 入库失败")
                                    }
                                }
                            } else {
                                Log.w(TAG, "片段 $segmentNumber 下载失败: HTTP ${downloadResponse.code()}")
                            }
                        } catch (e: CancellationException) {
                            // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "片段 $segmentNumber 下载异常: ${e.message}")
                        }
                        if (!downloaded) failedCount++
                    }

                    // 更新进度条
                    viewModel.updateTaskDownloadStatus(task.id, "downloading", index + 1)
                }

                // 把缓冲里最后凑不满一个批量窗口的片段强制落盘。
                // 不调这一句，任务会显示"已完成"，但播放到最后几段没声音，
                // 而且进程一被杀这些片段就永久丢失。
                viewModel.flushPendingAudioSegments()

                // 批量提交失败过的片段数（不查的话一次批量失败会静默丢掉一整批音频）
                val droppedSegments = viewModel.consumeFlushFailures()
                if (droppedSegments > 0) {
                    Log.e(TAG, "有 $droppedSegments 段音频写入本地失败")
                }
                val lostSegments = droppedSegments > 0

                Log.d(TAG, "下载完成，本次新下 $newSaved，本地已有 $skippedLocal，失败 $failedCount / 共 $totalSegments")

                // 清除下载状态后再刷新列表，避免 loadTasks() 合并时保留过期的 "downloading"
                // 若仍有缺失片段，或本地写入失败过，保留 failed 状态让"补下缺失片段"入口可见
                viewModel.updateTaskDownloadStatus(
                    task.id,
                    if (failedCount > 0 || lostSegments) "failed" else null,
                    0
                )
                // Step 3: 片段已在下载过程中逐段入库，这里只做列表状态统一刷新
                if (newSaved > 0) {
                    viewModel.refreshLibraryAfterDownload()
                    // P2 #7：刚下载完的音频改变了本机覆盖图，主动**静默**推一次服务端，
                    // 让别的设备尽快看到「这本书本机已有声」，而不必等下次进书架页的自动同步。
                    // 只在真的新增了段（newSaved>0）时推——补下载若全部命中本地（skippedLocal）
                    // 则覆盖图没变，无需再推。静默：徽章刷新即反馈，不打扰用户。
                    viewModel.syncAudioCoverageFromServer()
                }

                withContext(Dispatchers.Main) {
                    viewModel.ensureEbookRegistered(task.bookName)
                    if (lostSegments) {
                        Toast.makeText(appContext, "有 $droppedSegments 段音频写入本地失败，请重新下载补齐", Toast.LENGTH_LONG).show()
                    } else if (failedCount > 0) {
                        Toast.makeText(appContext, "已下载 ${newSaved + skippedLocal}/$totalSegments 段，$failedCount 段失败，可再次点击下载补齐", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(appContext, "下载完成！共 ${totalSegments} 个音频片段", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: CancellationException) {
                // 协程被取消时同样要把任务从 "downloading" 里摘出来。
                // 只 `throw e` 会让任务状态永远停在下载中：进度条不动、重试按钮不出现，
                // 用户只能杀进程重进。取消不是失败，但"卡在下载中"是必须收拾的残局。
                viewModel.updateTaskDownloadStatus(task.id, "failed", 0)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "下载异常: ${e.message}", e)
                viewModel.updateTaskDownloadStatus(task.id, "failed", 0)
                withContext(Dispatchers.Main) {
                    appContext.showError("下载失败：${e.toUserMessage()}")
                }
            } finally {
                // 异常/取消路径同样要落盘。否则已经下成功的那几十段会跟着一起丢，
                // 用户重新下载得从头再来。NonCancellable 保证收尾不被取消打断。
                withContext(NonCancellable) {
                    viewModel.flushPendingAudioSegments()
                    val dropped = viewModel.consumeFlushFailures()
                    if (dropped > 0) {
                        Log.e(TAG, "收尾落盘时有 $dropped 段音频写入失败")
                        viewModel.updateTaskDownloadStatus(task.id, "failed", 0)
                    }
                }
            }
        }
    }

    /** 打开阅读器：优先带上本地电子书路径，阅读器会自动优先从 audio_segments 读取文本与音频 */
    private fun openBookReader(task: Task) {
        // 写入最近阅读时间（书架「最近阅读」排序依据）
        viewModel.markBookOpened(task.bookName)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val filePath = viewModel.findEbookFilePathByDisplayName(task.bookName)
            withContext(Dispatchers.Main) {
                // Fragment 可能已销毁
                val ctx = context ?: return@withContext
                val intent = Intent(ctx, BookContentActivity::class.java).apply {
                    putExtra("BOOK_TABLE_NAME", task.bookName)
                    putExtra("BOOK_DISPLAY_NAME", task.bookName)
                    // 本地 rowid 不是服务端任务 ID，未同步时干脆不传，
                    // BookContentActivity 取到的默认值 -1 与「无服务端任务」同义
                    task.getQueryId()?.let { putExtra("SERVER_TASK_ID", it) }
                    if (filePath != null) {
                        putExtra("FILE_PATH", filePath)
                    }
                }
                startActivity(intent)
            }
        }
    }
}

