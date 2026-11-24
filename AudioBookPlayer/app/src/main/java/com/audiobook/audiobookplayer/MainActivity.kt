package com.audiobook.audiobookplayer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.audiobook.audiobookplayer.network.AuthManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : BaseActivity() {

    companion object {
        private const val REQUEST_CODE_NOTIFICATIONS = 1001
    }

    private lateinit var bottomNavigation: BottomNavigationView

    /** 通知权限只申请一次：重复弹授权框会被系统直接拒绝，同时打扰用户 */
    private var notificationPermissionRequested = false

    /**
     * 游客点击「需要登录」的功能后，登录成功要自动接回的那件事。
     *
     * 用可空闭包持有：登录回跳后执行一次即清空。若在 LoginActivity 期间宿主 Activity
     * 被系统回收重建（如横竖屏旋转），闭包会丢失——此时不做自动接回，用户回到原页
     * 再点一次即可，属可接受的优雅降级（不为一个低概率场景把闭包序列化进 savedInstanceState）。
     */
    private var pendingAfterLogin: (() -> Unit)? = null

    private val loginLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && AuthManager.isAuthenticated) {
            pendingAfterLogin?.invoke()
        }
        pendingAfterLogin = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AuthManager.init(this)

        // 游客可直接进入主界面（默认书架页）：本地导入、阅读、系统 TTS 朗读、书城浏览与
        // 在线阅读等与登录无关的功能都能用。与登录相关的标签/操作不在此处整体拦截，
        // 而是各自通过 requireLogin 在被触发时按需弹「需要登录」引导，登录后自动接回。

        // 恢复上次没下完的书城合成音频任务（进程被杀/被回收后）。
        // 放这里而不是 Application.onCreate：下载请求要带 token，而 AuthManager
        // 是登录后才初始化的（见上面的 AuthManager.init）。未登录时它自己会跳过，
        // 任务留在持久化列表里等真正登录后再续。
        StoreAudioDownloadManager.resumePending()

        // 把服务端已有的合成任务补回本地 tasks 表。
        //
        // 解决的是"换机 / 重装 / 清数据后，已扣积分的任务在 App 里彻底消失"：
        // 本地表空了，但服务端任务与音频都在。同步后书架会重新出现这些书
        // （没有本地文件时是"只有任务"的虚拟条目），用户至少能把付费音频下载回来。
        //
        // 未登录时该方法内部直接返回；真正的写入在 IO 线程，且只在数据有变化时
        // 才通知书架刷新，不会干扰首屏。
        TaskSyncManager.syncAsync(application)
        // 跨端单值偏好（倍速 / 默认音色）：一次 PUT 完成拉+推，未登录内部跳过。
        UserSettingsSyncManager.syncAsync(application)

        // 通知权限的申请时机见 onResume / onOnboardingDialogClosed：
        // 必须与书架页的「新手引导」弹窗错开，同屏会让弹窗拿不到输入焦点。

        bottomNavigation = findViewById(R.id.bottomNavigation)

        // 返回栈变化时同步底栏显隐。
        // 这是兜底：任何出栈路径（系统返回键、页内返回按钮、代码里直接 popBackStack）
        // 都会走到这里，底栏不会再因为某条路径忘了恢复而永久消失。
        supportFragmentManager.addOnBackStackChangedListener {
            syncBottomNavigationVisibility()
        }

        // 返回键处理：有全屏 Fragment 在返回栈时先出栈，否则退出应用
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fm = supportFragmentManager
                val current = fm.fragments.lastOrNull()
                if (current != null && isFullScreenFragment(current)) {
                    // 全屏页面：出栈并恢复底栏显示
                    if (fm.backStackEntryCount > 0) {
                        fm.popBackStack()
                        // popBackStack 是异步的，post 一帧再同步，
                        // 否则读到的仍是出栈前的 Fragment，底栏还是不会显示
                        bottomNavigation.post { syncBottomNavigationVisibility() }
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_discover -> {
                    // 发现社区对游客开放浏览：帖子列表 / 参考音频榜单 / 编辑推荐 都走
                    // 服务端 permitAll 的 GET 端点；写操作（发帖 / 评论 / 收藏 / 举报 /
                    // 使用参考音频）由 DiscoverFragment 及其子 Fragment 按需调
                    // [requireLogin] 拦截，不在底栏这一层一刀切。
                    loadFragment(DiscoverFragment(), addToBackStack = false)
                    true
                }
                R.id.nav_books -> {
                    loadFragment(BookListFragment(), addToBackStack = false)
                    true
                }
                R.id.nav_store -> {
                    loadFragment(BookStoreFragment(), addToBackStack = false)
                    true
                }
                R.id.nav_profile -> {
                    loadFragment(ProfileFragment(), addToBackStack = false)
                    true
                }
                else -> false
            }
        }

        if (savedInstanceState == null ||
            supportFragmentManager.findFragmentById(R.id.fragmentContainer) == null
        ) {
            // 冷启动（或系统重建后 Fragment 没被恢复）显式装载书架页。
            // 不能改用 selectedItemId 触发上面的监听器：监听器必须**先注册**才能收到回调，
            // 而这里若把设置写在注册之前，回调不会触发，fragmentContainer 会一直空着
            // ——表现为进主界面后一片空白，必须手动切一次底栏再切回书架才有内容。
            // 菜单第一项即「书架」，BottomNavigationView 默认已选中它，选中态天然一致，
            // 因此这里只装载内容、不重复触发选中回调（也就不会创建两个 BookListFragment）。
            // 已由系统恢复 Fragment 的场景（如旋转后正停在书城/发现页）则交给系统，
            // 否则新实例会覆盖恢复出来的 Fragment，旋转后被弹回书架。
            loadFragment(BookListFragment(), addToBackStack = false)
        }

        // 从帖子详情页「用这个声音读我的书」跳转过来：落地即进入 TTS 并预选音色
        if (intent.getBooleanExtra("OPEN_TTS", false)) {
            showTts()
        }

        // 默认书架页由上方 savedInstanceState == null 分支显式装载（冷启动）
    }

    /**
     * 只在宿主 Activity 已 RESUMED、且前台没有新手引导弹窗时才申请通知权限。
     *
     * 引导没看过（标记未落盘）时交给 [onOnboardingDialogClosed] 在弹窗关闭后触发；
     * 引导弹窗正在显示时同样跳过——授权框是独立 Activity，会盖在弹窗之上，
     * 被盖住的对话框拿不到输入焦点（点按钮、按返回键全无反应）。
     */
    override fun onResume() {
        super.onResume()
        if (AppPrefs.isOnboardingDone(this) && !isOnboardingDialogShowing()) {
            requestNotificationPermissionIfNeeded()
        }
    }

    /**
     * Android 13+（API 33）起，通知栏播放控制需要 POST_NOTIFICATIONS 运行时权限。
     * 用户拒绝也不阻断使用（播放仍可工作，只是失去通知栏控制入口）；
     * 低版本默认拥有该权限，无需申请。整个 Activity 生命周期内只申请一次。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (notificationPermissionRequested) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (isFinishing || isDestroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return

        notificationPermissionRequested = true
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_CODE_NOTIFICATIONS
        )
    }

    /** 书架页的新手引导弹窗此刻是否正在显示 */
    private fun isOnboardingDialogShowing(): Boolean =
        supportFragmentManager.fragments
            .filterIsInstance<BookListFragment>()
            .any { it.isOnboardingDialogShowing() }

    /**
     * 新手引导弹窗关闭后由 [BookListFragment] 回调。
     * 此时前台没有其它弹窗，是申请通知权限的安全时机。
     */
    fun onOnboardingDialogClosed() {
        requestNotificationPermissionIfNeeded()
    }

    /**
     * 全屏页面：隐藏底部导航栏，且返回键先出栈。
     * 新增全屏页面时在这里登记即可，避免返回键处理与底栏显隐两处判断不一致。
     */
    private fun isFullScreenFragment(fragment: Fragment): Boolean =
        fragment is TtsFragment || fragment is FavoritesFragment ||
            fragment is ReferenceAudioFragment || fragment is SearchFragment ||
            fragment is WordBookFragment

    /**
     * 底部导航栏显隐：唯一计算处。
     *
     * 只按「当前显示的 Fragment 是否全屏页」判断，不在各个出栈点单独赋值——
     * 分散赋值的结果就是某条返回路径忘了恢复，底栏从此消失（搜索页返回就是这么坏的）。
     */
    private fun syncBottomNavigationVisibility() {
        val current = supportFragmentManager.fragments.lastOrNull()
        bottomNavigation.visibility =
            if (current != null && isFullScreenFragment(current)) View.GONE else View.VISIBLE
    }

    private fun loadFragment(fragment: Fragment, addToBackStack: Boolean = false) {
        // 进入方向：立即切换，避免全屏页出现时底栏还多留一帧
        bottomNavigation.visibility =
            if (isFullScreenFragment(fragment)) View.GONE else View.VISIBLE

        val transaction = supportFragmentManager.beginTransaction().replace(
            R.id.fragmentContainer, fragment
        )
        if (addToBackStack) {
            transaction.addToBackStack(null)
        }
        transaction.commit()
    }

    /**
     * 全屏页内部的返回按钮统一走这里。
     *
     * 直接调 `parentFragmentManager.popBackStack()` 是错的：那只做出栈，
     * 不会恢复底部导航栏，返回书架后底栏会永久消失（系统返回键有单独处理，所以只有
     * 点页内返回箭头才复现）。
     */
    fun popFullScreenFragment() {
        val fm = supportFragmentManager
        if (fm.backStackEntryCount > 0) {
            fm.popBackStack()
            // 出栈是异步的，post 一帧再同步，确保读到的是出栈后的 Fragment
            bottomNavigation.post { syncBottomNavigationVisibility() }
        } else {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    /**
     * 显示语音合成界面（由 BookListFragment 调用）。
     * 语音合成不在底部导航栏中，此处切换 fragment 并压入返回栈。
     */
    fun showTts() {
        loadFragment(TtsFragment(), addToBackStack = true)
    }

    fun navigateToFavorites() {
        loadFragment(FavoritesFragment(), addToBackStack = true)
    }

    /**
     * 进入搜索页（由书架页右上角放大镜触发）。
     * 搜索页是全屏页（隐藏底栏），走返回栈让系统返回键能直接退回书架。
     */
    fun navigateToSearch() {
        loadFragment(SearchFragment(), addToBackStack = true)
    }

    /** 跳到书城（发现页「推荐」Tab 的入口按钮调用） */
    fun navigateToStore() {
        bottomNavigation.selectedItemId = R.id.nav_store
    }

    /** 打开参考音频管理页（由「我的」页进入） */
    fun navigateToReferenceAudio() {
        loadFragment(ReferenceAudioFragment(), addToBackStack = true)
    }

    /** 打开单词本页（由「我的」页进入） */
    fun navigateToWordBook() {
        loadFragment(WordBookFragment(), addToBackStack = true)
    }

    /**
     * 回到书架页（用于语音合成提交成功后查看任务进度）。
     * 任务已合并进书架，提交后直接回书架即可看到对应书籍下的任务状态。
     * 不压入返回栈，行为与底部导航栏切换一致。
     */
    fun navigateToBooks() {
        bottomNavigation.selectedItemId = R.id.nav_books
        // 显式再装载一次：selectedItemId 与当前选中项相同时，不保证再次触发监听器
        // （Material 各版本行为不一致），只靠它可能出现底栏选中已切换、内容却没换的情况。
        // 重复装载只是 replace 同一个容器，不会叠加页面。
        loadFragment(BookListFragment(), addToBackStack = false)
    }

    /**
     * 游客功能门禁：已登录则立即执行 [after]；未登录则弹「需要登录」引导，
     * 用户确认后跳登录页（RETURN_ONLY，登录成功即 finish 回本页），
     * 登录成功经 [loginLauncher] 自动执行暂存的 [after]。
     *
     * 宿主是 MainActivity 的各 Fragment（书城/书架/我的）统一经
     * `(activity as? MainActivity)?.requireLogin(...) { 原本要做的操作 }` 调用，
     * 从而复用同一套登录回跳逻辑，避免每个页面各写一份启动器与结果处理。
     */
    fun requireLogin(message: String = "该功能需要登录后使用", after: (() -> Unit)? = null) {
        if (AuthManager.isAuthenticated) {
            after?.invoke()
            return
        }
        pendingAfterLogin = after
        MaterialAlertDialogBuilder(this)
            .setTitle("需要登录")
            .setMessage(message)
            .setNegativeButton("稍后再说", null)
            .setPositiveButton("去登录") { _, _ ->
                loginLauncher.launch(
                    Intent(this, LoginActivity::class.java)
                        .putExtra("RETURN_ONLY", true)
                )
            }
            .show()
    }
}
