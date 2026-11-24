package com.audiobook.audiobookplayer

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.audiobook.audiobookplayer.moss.MossModuleActivity
import com.audiobook.audiobookplayer.moss.MossModuleManager
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import com.audiobook.audiobookplayer.update.UpdateDialog
import com.audiobook.audiobookplayer.update.UpdateManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment() {

    private lateinit var tvBalance: TextView
    private lateinit var rowCredits: View

    /**
     * 本机合成模块入口右侧的状态文案（未安装 / 已安装）。
     * 用可空字段而不是 lateinit：从模块管理页返回时要刷新它，
     * 而返回回调可能发生在视图已销毁之后（见 mossLauncher 的说明）。
     */
    private var tvMossState: TextView? = null

    /**
     * 存储管理弹窗引用：清理成功后要把书从弹窗列表里摘掉，列表清空时关弹窗，
     * 「全部清理」成功也要关。可空：未打开时为 null，关闭时置空。
     */
    private var storageDialog: AlertDialog? = null

    /** 从模块管理页返回后刷新模块状态文案（用户可能刚下载完或刚卸载） */
    private val mossLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (view == null) return@registerForActivityResult
        refreshMossState()
    }

    /** 存储管理入口：懒加载 BookViewModel（与书架/任务列表共享同一实例） */
    private val bookViewModel: BookViewModel
        get() = ViewModelProvider(requireActivity())[BookViewModel::class.java]

    /** 从充值页返回后刷新积分余额 */
    private val rechargeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 回调挂的是 Fragment 生命周期而非视图生命周期：进程被杀后重建时，它可能在
        // onCreate 之后、onViewCreated 之前就触发。此时 viewLifecycleOwner 会直接抛
        // IllegalStateException，tvBalance 也还不存在，必须先挡一道
        if (view == null) return@registerForActivityResult
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            loadBalance()
            android.widget.Toast.makeText(requireContext(), "充值成功，积分已更新", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    /**
     * 刷新「本机语音合成」右侧状态文案。
     *
     * 只读本地文件（[MossModuleManager.isInstalled] 做的是 stat + 大小比对，毫秒级），
     * 不联网 —— 这只是列表里的一行附属信息，不该为它付一次网络往返的代价。
     */
    private fun refreshMossState() {
        val tv = tvMossState ?: return
        val ctx = context ?: return
        tv.text = if (MossModuleManager.isInstalled(ctx)) {
            val mb = MossModuleManager.installedBytes(ctx) / 1048576.0
            "已安装 %.0f MB".format(mb)
        } else {
            "未安装"
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        val btnMyFavorites = view.findViewById<View>(R.id.btnMyFavorites)
        val btnWordBook = view.findViewById<View>(R.id.btnWordBook)
        val btnStorageManage = view.findViewById<View>(R.id.btnStorageManage)
        val btnReferenceAudio = view.findViewById<View>(R.id.btnReferenceAudio)
        val btnMossModule = view.findViewById<View>(R.id.btnMossModule)
        tvMossState = view.findViewById(R.id.tvMossState)
        val btnDictionary = view.findViewById<View>(R.id.btnDictionary)
        val btnCheckUpdate = view.findViewById<View>(R.id.btnCheckUpdate)
        val tvCurrentVersion = view.findViewById<TextView>(R.id.tvCurrentVersion)
        tvBalance = view.findViewById(R.id.tvBalance)
        rowCredits = view.findViewById(R.id.rowCredits)

        // 夜间模式开关（右上角图标）。
        // 「显示与阅读」卡片（字体大小 SeekBar）已按需求移除，字体大小改由阅读页顶部
        // 菜单的「字号 A」调整；这里只保留夜间模式这一项。
        setupNightModeToggle(view)

        // 账号相关头部 UI（用户名/登录引导、退出按钮显隐、积分余额）统一由 renderAuthState 渲染，
        // 登录成功回跳后也会复用它原地刷新，无需重建整个页面。
        renderAuthState()

        // 积分：整行点击进充值中心（原来是一个独立的「充值中心」按钮，
        // 改成行样式后按钮取消，点整行即进入，与其它功能入口的交互一致）
        // 充值/积分属账号功能：游客点击弹「需要登录」，登录后自动接回进入充值页。
        rowCredits.setOnClickListener {
            (activity as? MainActivity)?.requireLogin("充值积分需要登录后使用") {
                val intent = Intent(requireContext(), RechargeActivity::class.java)
                rechargeLauncher.launch(intent)
            } ?: run {
                val intent = Intent(requireContext(), RechargeActivity::class.java)
                rechargeLauncher.launch(intent)
            }
        }

        // "我的收藏"入口：宿主为 MainActivity 时直接切换 fragment
        btnMyFavorites.setOnClickListener {
            val activity = activity
            if (activity is MainActivity) {
                activity.navigateToFavorites()
            } else {
                Toast.makeText(requireContext(), "请从主界面进入收藏", Toast.LENGTH_SHORT).show()
            }
        }

        // 单词本：阅读页查词弹窗收藏的英文单词。宿主为 MainActivity 时切换 fragment
        btnWordBook.setOnClickListener {
            val activity = activity
            if (activity is MainActivity) {
                activity.navigateToWordBook()
            } else {
                Toast.makeText(requireContext(), "请从主界面进入单词本", Toast.LENGTH_SHORT).show()
            }
        }

        // 存储管理：查看音频缓存占用，按书或全部清理（保留电子书）
        btnStorageManage.setOnClickListener {
            showStorageManager()
        }

        // 本机语音合成模块：进入模块管理页（下载/卸载 725 MB 的端侧模型）
        btnMossModule.setOnClickListener {
            mossLauncher.launch(Intent(requireContext(), MossModuleActivity::class.java))
        }
        // 进页面时先按本地已装文件判断一次，不必联网（isInstalled 只做 stat 与大小比对）
        refreshMossState()

        // 参考音频管理：入口对游客开放。
        // 「录制 / 本机导入 / 删除 / 试听」四件事完全是本地文件与本地 SQLite，
        // ReferenceAudioRepository 里对应的 importFromUri / importFromRecording / listAll /
        // delete 都没有 AuthManager 判据；游客录一段自己的声音就能作为 MOSS-TTS-Nano
        // 本机合成的音色来源。
        // 「从服务器添加 / 上传到我的库 / 分享 / 上报使用」这四条真正写云端的路径，
        // 各自在 ReferenceAudioFragment 里按点击就地 requireLogin（loadServerReferences、
        // toggleShare 等），入口这一层不再一刀切。
        btnReferenceAudio.setOnClickListener {
            val activity = activity
            if (activity is MainActivity) {
                activity.navigateToReferenceAudio()
            } else {
                Toast.makeText(requireContext(), "请从主界面进入", Toast.LENGTH_SHORT).show()
            }
        }

        // 离线词典（ECDICT）：下载/卸载。阅读页「翻译」菜单依赖它，
        // 未安装时那里会提示用户来这里（见 BookContentActivity.handleTranslate）。
        btnDictionary.setOnClickListener {
            val host = activity as? AppCompatActivity ?: return@setOnClickListener
            DictManageDialog.show(host, host) {
                // 弹窗关闭时同步刷新行右侧的状态文案（下载可能在弹窗打开期间完成）
                refreshDictState(view)
            }
        }
        refreshDictState(view)

        // 词典状态会因「别处发起的下载/卸载」而变（如阅读页提示后用户先去别处装了），
        // 用 StateFlow 观察而不是只在 onResume 读一次。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DictionaryManager.state.collect { refreshDictState(view) }
            }
        }

        // 检查更新：展示当前版本，点击查询新版本（手动检查不受「本次忽略」影响）
        tvCurrentVersion.text = "v${UpdateManager.currentVersionName(requireContext())}"
        btnCheckUpdate.setOnClickListener {
            val host = activity as? AppCompatActivity ?: return@setOnClickListener
            UpdateDialog.checkManually(host)
        }

        // 退出登录与积分余额的加载/显隐已收敛到 renderAuthState()（见上），此处不再重复。
        refreshStorageUsage()

        // 音频缓存占用走 StateFlow 观察，刷新入口行右侧占用文案
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                bookViewModel.audioStorageStats.collect { stats ->
                    val tv = view.findViewById<TextView>(R.id.tvStorageUsage) ?: return@collect
                    tv.text = if ((stats?.totalBytes ?: 0) > 0) formatMB(stats!!.totalBytes) else ""
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从其它页面返回时刷新占用显示（下载音频后回来能看到最新值）
        refreshStorageUsage()
        // 单词本计数：用户可能刚在阅读页收藏完词返回，这里对齐一次（本地库读取，毫秒级）
        view?.let { refreshWordBookCount(it) }
        // 词典可能在弹窗里装好了/被卸载了，回来时对齐一次（StateFlow 已覆盖大部分场景，
        // 这里再兜一次是为了「从阅读页提示后直接跳过来」这种没经过弹窗的路径）
        view?.let { refreshDictState(it) }
        // 模式可能在别处被改过（例如去系统设置切了深色模式），回来时把图标对齐到当前实际模式。
        // 幂等操作，重复调用无副作用。
        view?.findViewById<ImageButton>(R.id.btnNightMode)?.let { applyNightModeIcon(it) }
    }

    /** 单词本入口行右侧的收藏条数。0 条时留空（与存储管理无占用时的处理一致）。 */
    private fun refreshWordBookCount(root: View) {
        val tv = root.findViewById<TextView>(R.id.tvWordBookCount) ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val n = WordBookRepository.count()
            withContext(Dispatchers.Main) {
                // 协程回来时视图可能已销毁（用户已离开「我的」页），
                // 此时写 TextView 会拿到失效视图 —— 用 isAdded 挡一道。
                if (!isAdded) return@withContext
                tv.text = if (n > 0) "$n 词" else ""
            }
        }
    }

    /** 词典入口行右侧的状态文案。下载中显示百分比，失败时显示「下载失败」。 */
    private fun refreshDictState(root: View) {
        val tv = root.findViewById<TextView>(R.id.tvDictState) ?: return
        tv.text = when (val s = DictionaryManager.state.value) {
            is DictionaryManager.DictState.NotInstalled -> "未安装"
            is DictionaryManager.DictState.Installed ->
                // 版本号对普通用户无意义，只报词条数（更有信息量）
                if (s.rows > 0) "${s.rows} 词条" else "已安装"
            is DictionaryManager.DictState.Downloading ->
                if (s.percent >= 0) "下载中 ${s.percent}%" else "下载中…"
            is DictionaryManager.DictState.Installing -> "解压中…"
            is DictionaryManager.DictState.Error -> "下载失败"
        }
    }

    /**
     * 渲染「我的」页与登录态相关的头部 UI，是账号显隐的唯一入口。
     * onViewCreated 里调用一次；游客点登录成功回跳后、以及退出登录后都复用它在原地刷新，
     * 避免整页 recreate。视图已销毁时静默返回（after-login 闭包可能在离开本页后才触发）。
     */
    private fun renderAuthState() {
        val root = view ?: return
        val tvUsername = root.findViewById<TextView>(R.id.tvUsername) ?: return
        val btnLogout = root.findViewById<View>(R.id.btnLogout)

        if (AuthManager.isAuthenticated) {
            tvUsername.text = "欢迎，${AuthManager.username ?: "用户"}"
            tvUsername.setOnClickListener(null)
            tvUsername.isClickable = false
            btnLogout?.visibility = View.VISIBLE
            btnLogout?.setOnClickListener {
                // 退出后回到「游客态」：应用仍可继续离线使用（本地导入/阅读/系统朗读/书城浏览），
                // 因此只清凭证并原地刷新，不再 finish 宿主 MainActivity（否则游客就没有宿主了）。
                AuthManager.logout()
                tvBalance.text = ""
                renderAuthState()
                Toast.makeText(requireContext(), "已退出登录，可继续离线使用", Toast.LENGTH_SHORT).show()
            }
            loadBalance()
        } else {
            tvUsername.text = "未登录，点击登录"
            tvUsername.isClickable = true
            tvUsername.setOnClickListener {
                (activity as? MainActivity)?.requireLogin(
                    "登录后可使用积分、云同步、发现社区等账号功能"
                ) {
                    renderAuthState()
                } ?: run {
                    startActivity(Intent(requireContext(), LoginActivity::class.java))
                }
            }
            btnLogout?.visibility = View.GONE
            tvBalance.text = ""
        }
    }

    private fun loadBalance() {
        // 游客不请求积分：/api/credits/my 需鉴权，无 token 时不发这次注定 401 的往返。
        if (AuthManager.token.isNullOrEmpty()) {
            tvBalance.text = ""
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // token 已由 AuthInterceptor 统一注入，无需手传，避免与拦截器重复/不一致
                val resp = RetrofitClient.creditApi.getMyCredits()
                if (resp.isSuccessful) {
                    val balance = resp.body()?.balance ?: 0
                    withContext(Dispatchers.Main) {
                        // 带单位：右侧这一列其它行也都带单位（存储显示 MB、更新显示 v1.17），
                        // 只写个数字会让人不确定是积分还是别的计数
                        if (isAdded) tvBalance.text = "$balance 积分"
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 余额加载失败不阻塞页面 */ }
        }
    }

    // ---------- 显示 ----------

    /**
     * 日间/夜间切换（右上角图标）。
     *
     * 图标显示**当前**模式（太阳=日间 / 月亮=夜间），点击切到另一种。
     * 偏好仍存 AppPrefs（-1 跟随系统 / 1 日间 / 2 夜间），但用户点过图标后一律落到显式的
     * MODE_NIGHT_NO / MODE_NIGHT_YES —— 界面上只有一个二态入口，「跟随系统」这一档随之取消。
     * 首次进入（偏好还是 -1）由 [isNightActive] 按系统当前实际生效值决定图标画哪一个。
     *
     * 注：字体大小原先也在这张「显示与阅读」卡片里，该卡片已按需求移除；
     * 字号改由**阅读页**顶部菜单的「字号 A」设置（dialog_font_size），本页不再重复放一份。
     *⚠️ 那条偏好现在是**只作用于阅读页正文**（LineAdapter 量的 item_line 那个 TextView），
     *   曾经是经 BaseActivity 注入 Configuration.fontScale 的全 App 缩放，已移除。
     */
    private fun setupNightModeToggle(view: View) {
        val ctx = requireContext()
        val btnNightMode = view.findViewById<ImageButton>(R.id.btnNightMode)

        // 日间/夜间：先按当前模式画图标，再挂点击
        applyNightModeIcon(btnNightMode)
        btnNightMode.setOnClickListener {
            // 取反的是「当前实际生效值」而不是偏好值：偏好可能是 -1（跟随系统），
            // 此时直接读偏好会把「系统已经是夜间」当成日间 ⇒ 图标画成太阳、点击却往夜间切，
            // 而界面本来就是深色，看着像「点了没反应」。详见 isNightActive()。
            val target = if (isNightActive()) AppCompatDelegate.MODE_NIGHT_NO
            else AppCompatDelegate.MODE_NIGHT_YES
            AppPrefs.setNightMode(ctx, target)
            // setDefaultNightMode 自身就会重建已启动的 Activity，这里的 recreate 是
            // 沿用原先 Spinner 的写法（行为一致、已验证过），别删。
            AppCompatDelegate.setDefaultNightMode(target)
            requireActivity().recreate()
        }
    }

    /**
     * 当前**实际生效**的是不是夜间。
     *
     * 不能只看 AppPrefs：默认值是 MODE_NIGHT_FOLLOW_SYSTEM(-1)，此时偏好里没有答案，
     * 真正的答案在系统 Configuration 的 uiMode 里。若按「偏好不是 YES 就算日间」判断，
     * 系统正处深色时图标会画成太阳、点一下却切成日间 —— 界面原本就是深的，
     * 用户看到的是「点了没反应」，要点两次才切到日间。这是本页最容易踩的一处。
     */
    private fun isNightActive(): Boolean {
        val ctx = requireContext()
        return when (AppPrefs.getNightMode(ctx)) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> {
                val mask = ctx.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
                mask == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    /**
     * 把当前模式画到图标上：日间=太阳、夜间=月亮。
     * contentDescription 反向描述点击结果 —— 读屏用户听到的是「点它会变成什么」，
     * 与视觉上「图标=当前状态」互补，不会误报。
     */
    private fun applyNightModeIcon(btn: ImageButton) {
        val night = isNightActive()
        btn.setImageResource(if (night) R.drawable.ic_dark_mode else R.drawable.ic_light_mode)
        btn.contentDescription = if (night) "切换到日间模式" else "切换到夜间模式"
    }

    // ---------- 存储管理 ----------

    /** 刷新入口行右侧的占用文案（无音频时留空）。 */
    private fun refreshStorageUsage() {
        bookViewModel.loadAudioStorageStats()
    }

    private fun showStorageManager() {
        // 挂起等待最新音频缓存统计（先清空再重新拉取，保证打开即最新）
        viewLifecycleOwner.lifecycleScope.launch {
            bookViewModel.clearAudioStorageStats()
            bookViewModel.loadAudioStorageStats()
            val stats = bookViewModel.audioStorageStats.filterNotNull().first()
            if (!isAdded) return@launch
            if (stats.totalBytes <= 0 && stats.books.isEmpty()) {
                Toast.makeText(requireContext(), "暂无音频缓存", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle("存储管理")
                .setView(buildStorageManagerView(stats) { storageDialog?.dismiss() })
                .setPositiveButton("全部清理") { _, _ -> confirmClearAll() }
                .setNegativeButton("取消", null)
                .setOnDismissListener { storageDialog = null }
                .show()
            storageDialog = dialog
        }
    }

    /**
     * 构建存储管理弹窗内容：摘要文字 + 书籍列表。
     *
     * 不用 setItems()：Material 1.12.0 的 mtrl_alert_dialog.xml 的 contentPanel 里
     * 没有 listView 节点（listView 只存在于 appcompat 的 abc_alert_dialog_material.xml），
     * AlertController 找不到 listView 时会把 items 分支整体跳过——setItems 传的
     * 数组被静默吞掉，弹窗只剩消息和按钮（2026-10-22 在 K30 Pro 真机复现，
     * uiautomator dump 显示 contentPanel 下只有 scrollView/message）。
     * 改用 setView() 走 customPanel，列表由 dialog_storage_manager.xml 自己渲染。
     *
     * 列表持有可变副本：清理成功后要同步把书从弹窗列表里摘掉并更新摘要
     * （旧实现只刷新了「我的」页入口文案，弹窗列表停留在打开时的快照，
     * 被清理的书还挂在列表里）；摘空后回调 onListEmptied 关弹窗。
     */
    // dialog 内容视图没有宿主 parent，inflate 传 null 是标准写法
    @SuppressLint("InflateParams")
    private fun buildStorageManagerView(
        stats: AudioStorageStats,
        onListEmptied: () -> Unit,
    ): View {
        val view = layoutInflater.inflate(R.layout.dialog_storage_manager, null)
        val summary = view.findViewById<TextView>(R.id.tvStorageSummary)
        val listBooks = view.findViewById<ListView>(R.id.listBooks)
        val bookNames = ArrayList(stats.books.map { it.bookName })
        val bytesOf = stats.books.associate { it.bookName to it.bytes }
        val labels = ArrayList(bookNames.map { "${it}（${formatMB(bytesOf.getValue(it))}）" })
        var totalBytes = stats.totalBytes
        summary.text = buildStorageSummary(totalBytes, bookNames.size)
        val adapter = ArrayAdapter(
            requireContext(), R.layout.dialog_storage_book_item, labels
        )
        listBooks.adapter = adapter
        listBooks.setOnItemClickListener { _, _, which, _ ->
            confirmClearBook(bookNames[which]) {
                // 清理成功：从弹窗列表摘掉这本书，摘要同步扣减大小与本书数
                val removedBytes = bytesOf[bookNames[which]] ?: 0L
                bookNames.removeAt(which)
                labels.removeAt(which)
                totalBytes -= removedBytes
                adapter.notifyDataSetChanged()
                summary.text = buildStorageSummary(totalBytes, bookNames.size)
                if (bookNames.isEmpty()) onListEmptied()
            }
        }
        return view
    }

    /** 存储管理弹窗摘要文案（打开时与清理后刷新共用，保证口径一致）。 */
    private fun buildStorageSummary(totalBytes: Long, bookCount: Int): String =
        "音频缓存共 ${formatMB(totalBytes)}，涉及 $bookCount 本书。\n" +
            "点击下方列表中的书籍可单独清理该书的音频（电子书保留，合成记录一并删除）。"

    private fun confirmClearBook(bookName: String, onCleared: (() -> Unit)? = null) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("清理音频")
            .setMessage(
                "将删除《$bookName》的本地音频，并一并删除该书的合成记录。\n" +
                    "删除后需要重新合成才能播放。确定清理吗？"
            )
            .setPositiveButton("清理") { _, _ ->
                // 挂起函数返回是否成功
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val ok = bookViewModel.clearAudioForBook(bookName)
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            Toast.makeText(
                                requireContext(),
                                if (ok) "已清理" else "清理失败",
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshStorageUsage()
                            // 成功才回调：把这本书从存储管理弹窗的列表里摘掉
                            if (ok) onCleared?.invoke()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清理全部音频")
            .setMessage("将删除所有书籍的本地音频，并一并删除全部合成记录。\n" +
                "删除后需要重新合成才能播放。确定清理吗？")
            .setPositiveButton("全部清理") { _, _ ->
                // 挂起函数返回是否成功
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val ok = bookViewModel.clearAllAudio()
                    withContext(Dispatchers.Main) {
                        if (isAdded) {
                            Toast.makeText(
                                requireContext(),
                                if (ok) "已清理全部音频" else "清理失败",
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshStorageUsage()
                            // 全部清完后列表已无意义，直接关存储管理弹窗
                            if (ok) storageDialog?.dismiss()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatMB(bytes: Long): String {
        if (bytes >= 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        }
        if (bytes >= 1024) {
            return String.format("%.0f KB", bytes / 1024.0)
        }
        return "$bytes B"
    }
}
