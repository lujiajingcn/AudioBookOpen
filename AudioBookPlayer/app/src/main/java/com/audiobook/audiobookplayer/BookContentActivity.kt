package com.audiobook.audiobookplayer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.Spannable
import android.text.Selection
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.Book
import com.audiobook.audiobookplayer.model.LineItem
import com.audiobook.audiobookplayer.model.WordSource
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch

class BookContentActivity : BaseActivity() {

    private lateinit var viewModel: BookViewModel
    private lateinit var adapter: LineAdapter
    private lateinit var recyclerView: RecyclerView

    // 顶部菜单栏（2026-09-29 按需求加回）：返回 / 目录 / 字体大小 / 搜索本书内容
    private lateinit var btnBack: ImageButton
    private lateinit var btnToc: ImageButton

    /**
     * 当前是否处于「结构仍在解析中」（[BookViewModel.Phase] 未到StructureReady）。
     *
     * 目录依赖正文与服务端解析结果，所以它在解析完成前是**不可用**的。
     * 单靠 [BookViewModel.toc] 为空判断不出来 —— 解析完成但这本书本身没有目录时，
     * toc 同样为空，但那时按钮该是「不可见」而不是「可见但不可用」。故单独记一个阶段字段。
     */
    private var isStructureResolving = true
    // 图标就是字母 A，布局里用 TextView 画（矢量描字形不划算），所以类型是 TextView 而非 ImageButton
    private lateinit var btnFontSize: TextView
    private lateinit var btnSearch: ImageButton

    /**
     * 「点读」开关按钮（2026-10-09）。
     *
     * 开启后正文的**单击语义改变**：不再播该句音频，改为「点英文单词 → 底部弹释义 + 朗读」。
     * 开关状态存 [AppPrefs]（见那里的取舍说明），所以进页面时要按偏好回填按钮两态。
     *
     * 两态由 `isSelected` 驱动 —— `app:tint="@color/tint_point_read"` 是
     * ColorStateList，选中态自动变主色，不需要代码里换图标资源。
     */
    private lateinit var btnPointRead: ImageButton
    private var pointReadEnabled = false

    /**
     * #21：阅读页「实时朗读」开关（2026-10-18 从顶栏迁到底部播放栏）+ 是否正在进行朗读。
     *
     * <p>入口与的控制统一在底部播放栏：
     *  · **无音频的书**：播放/暂停键 [btnPlayToggle] 直接当朗读启动键，本按钮不显示；
     *    后退/前进 [btnPlayPrev]/[btnPlayNext] 常驻，作「上一段/下一段」跳转（跳转单位
     *    与自动播放一致：一次一段）；
     *  · **有音频的书**：播放键保持「播/设有声书」语义，后退/前进跳音频段，朗读由本按钮启停。
     * 两者都复用底部播放条做暂停/继续（见 [setupPlaybackControlBar]）。
     */
    private lateinit var btnReadAloudBar: ImageButton
    private var readAloudActive = false

    /**
     * 本书是否有音频（服务端 TTS 任务合成的段）。
     *
     * <p>决定底部播放栏的两处行为：
     *  · 播放键未在朗读时的语义：有音频 ⇒ 播/设有声书；无音频 ⇒ 启动朗读；
     *  · [btnReadAloudBar] 与「定时/倍速」的显隐 —— 这两者对纯文本朗读无意义；
     *    后退/播放/前进是标准媒体三联键，两类书都常驻。
     *
     * 由 [viewModelTasks] 观察 `tasks` 重算（Task.hasAudio + bookName 匹配）。
     */
    private var bookHasAudio = false

    /**
     * 朗读时用户**手动翻页**离开了朗读位置 ⇒ 暂停自动跟随（详见 [highlightReadLine]）。
     * 点「回到播放处」或重新开始朗读时清回 false。
     */
    private var readAloudUserScrolledAway = false

    /**
     * **有声书播放**时用户手动翻页离开了播放位置 ⇒ 暂停自动跟随。
     *
     * <p>为什么必须与 [readAloudUserScrolledAway] 分开：两者语义不同、且可能同时存在
     * （朗读当前页 + 后台还有一本有声书在播）。混用一个标记会让「朗读中翻页」顺带
     * 掐掉后台有声书的跟随，反之亦然。
     *
     * <p>为什么需要有它：`currentPlayingPosition` 的观察者**原先无条件**
     * `smoothScrollToPosition` —— 用户滚到别处看内容，下一段一开始就被硬拽回播放位置，
     * 「正在看的」与「正在听的」互相打架。这与朗读当年的问题同构，解法也一样：
     * 用户手动翻走后停止跟随，把「我要回去」变成显式动作（点「回到播放处」）。
     *
     * <p>复位时机：点「回到播放处」、播放位置变 -1（停止/换书）、重新开始播放。
     */
    private var audioUserScrolledAway = false

    /** 朗读当前走到原列表的第几行 —— 「回到播放处」的目标行 */
    private var readAloudCurrentRow = -1

    /**
     * 上一次已渲染的朗读高亮（行 → 行内闭区间），用于**去重**。
     *
     * 句级高亮由系统 TTS 的 `onRangeStart` 驱动，它在**每个词**开始发音时回调一次
     * （几百毫秒一次）。同一句里的每次回调算出来的区间完全一样，
     * 不去重就是给同一行反复 `notifyItemChanged` —— 每次都要重新 bind、
     * 重新构造 Spannable，滚动时肉眼可见地掉帧。
     */
    private var readAloudLastSpan: Pair<Int, IntRange>? = null

    /**
     * 朗读**首行**在原行文本里的起点（「从此处读」= 选区位置，普通朗读 = 0）。
     *
     * 句级回调的下标是相对**入队文本**的：`readFromHere` 入队的第一行是
     * `lineText.substring(offset)`（选区之后的残余），所以第 0 行的坐标要
     * 加回 `offset` 才能落在原文的对应字符上。
     */
    private var readAloudCharBase = 0

    /**
     * 「回到播放处」那枚准星图标的**一次性**引导是否已弹过（见 [maybeShowBackToPlayHint]）。
     *
     * 内存缓存 + SharedPreferences 双写：滚动时 `updateBackToReadPositionVisibility`
     * 挂在 `onScrolled` 上、几乎每帧都跑，不能每次都去查磁盘；
     * 而只存内存又会在每次冷启动后重弹一遍（那就不是"一次性"了）。
     * 初值在 onCreate 里从 [AppPrefs.isBackToPlayHintShown] 载入。
     */
    private var backToPlayHintShown = false

    // 底部播放控制栏：倍速 / 睡眠定时 / 播放控制
    private lateinit var btnPlayPrev: ImageButton
    private lateinit var btnPlayToggle: ImageButton
    private lateinit var btnPlayNext: ImageButton
    private lateinit var btnSleepTimer: Button
    private lateinit var btnSpeed: Button

    /** 「回到播放处」：仅朗读时可见，把正文瞬时定位回当前朗读行 */
    private lateinit var btnBackToReadPosition: Button

    // 逐段音色绑定入口（仅本书有服务端 TTS 任务时显示）
    private lateinit var btnSegmentVoice: ImageButton

    /**
     * 解析期间的加载指示层（盖在正文之上）。
     *
     * 存在的唯一理由：**正文还没到达时不能是白屏**。PDF 首次打开要把整本上传给服务端
     * 提取文本，弱网下可达几十秒，此前这段时间界面完全空白。
     * 由 [BookViewModel.Phase] 驱动：只在 Resolving（正文未到）时显示，
     * 一旦正文 post 出来就隐藏 —— 对应「先显示文本，再解析结构」。
     */
    private lateinit var readerLoadingOverlay: View

    /**
     * 解析中的**文字**提示（居中，正文区为空时显示"正在解析… 第 N/M 页"）。
     *
     * 只有一条 2dp 进度条时，用户看到的是「一片白 + 一道细线」，分不出是在解析还是卡死。
     * 页数来自 [BookViewModel.parsePageProgress]（PDF 本地逐页提取上报）。
     */
    private lateinit var readerLoadingHint: TextView

    // 点击正文中央区域切换显隐的两条栏：顶部菜单栏 + 底部播放栏
    private lateinit var readerTopBar: View
    private lateinit var playbackControlBar: View
    /**
     * 常驻的切栏把手（方案 D 主入口）。
     *
     * 用 [android.widget.ImageButton] 而不是自定义 View：它自带 48dp 触摸目标、
     * 无障碍朗读（contentDescription）、点击语义，linter 也不会报可点击性缺失。
     */
    private lateinit var btnChromeHandle: ImageButton
    private var readerChromeVisible = true

    // 服务器任务 ID（从任务列表打开时传递）
    private var serverTaskId: Long = -1L

    // 书城「在线读」入口标记：true = 本次打开的是书城书且不落电子书文件（正文/音频按需在线取）。
    // storeBookId 用于拉正文与音频；bookName 仍取书城书名（= audio_segments.book_name 的虚拟书名）。
    private var storeOnline: Boolean = false
    private var storeBookId: Long = -1L

    // 当前书籍名称，用于收藏时关联书籍
    private var bookName: String = ""

    /**
     * 当前显示的对话框。
     * 目录 / 倍速 / 睡眠定时三个对话框都是裸 `Dialog(this)` / `AlertDialog.Builder(this)`，
     * 一旦 show 出去就由 WindowManager 持有，并强引用创建它的 Activity。
     * 旋转屏幕时旧 Activity 被销毁而对话框还挂着 → 整页泄漏（logcat 会打
     * "Activity has leaked window"）。统一登记到这个字段，在 [onDestroy] 里 dismiss。
     */
    private var activeDialog: Dialog? = null

    companion object {
        /** 滚动停稳后再记阅读位置的延迟（毫秒）：滚动事件连发几十次，直接写会把服务端打爆 */
        private const val READING_POSITION_SAVE_DELAY_MS = 800L

        private const val MENU_COPY = 1
        private const val MENU_FAVORITE = 2
        private const val MENU_TRANSLATE = 3
        /**
         * 「查字典」：查**单个汉字**的读音与释义（随包 [ChinDictManager]）。
         *
         * ⚠️ 与 `MENU_TRANSLATE` 的分工：翻译查**英**词典（ECDICT，需下载），
         * 查字典查**中**字库（随包、免下载）。两者不互斥，各按选区内容显示。
         */
        private const val MENU_DICT = 4
        /**
         * 「从此处读」：从长按选中处起调用系统 TTS 朗读（见 [readFromHere]）。
         *
         * 显隐规则（onCreateActionMode）：无合成音频的书**常驻**；有音频的书仅在
         * 朗读已开启（readAloudActive）时出现。⚠️ 同时在 onPrepareActionMode 的
         * keepIds 白名单里，新增/调整显隐规则时别忘了它不能被当系统项摘掉。
         */
        private const val MENU_READ_FROM_HERE = 5

        /** 「轻点」判据：位移超过这个距离就算在滚动，不算点击（dp） */
        private const val TAP_SLOP_DP = 12
        /**
         * 「轻点」判据：按下到抬起超过这个时长就算长按（选词），不算点击。
         *
         * 必须与系统长按阈值 [android.view.ViewConfiguration.getLongPressTimeout] 对齐：
         * 若这里比系统短，用户 300~500ms 的长按会被判成「轻点」⇒ 长按选词半路被切栏打断；
         * 若比系统长，一次正常点击的尾巴可能被 TextView 判成上下文菜单。
         * 取运行时值（见 [resolveTapTimeoutMs]），不要写死。
         */
        private const val TAP_TIMEOUT_MS = 300L
        /**
         * 「轻点」判据的实际取值：与系统长按阈值对齐，取不到时回退 [TAP_TIMEOUT_MS]。
         *
         * 系统 [android.view.ViewConfiguration.getLongPressTimeout] 通常是 500ms。
         * 早期实现写死 300ms，导致 300~500ms 的长按在抬起时同时满足
         * 「没移动」+「没过 300ms」⇒ 被切栏手势吃掉 ⇒ 长按选词被中途打断。
         * 两处阈值不一致属于实现疏漏，不是设计取舍。
         */
        private var tapTimeoutMs = TAP_TIMEOUT_MS

        private fun resolveTapTimeoutMs() {
            tapTimeoutMs = try {
                val ms = ViewConfiguration.getLongPressTimeout().toLong()
                // 防御：某些定制 ROM（MIUI 尤其）会返回异常小的值，反而会误吃掉长按。
                if (ms >= 400L) ms else TAP_TIMEOUT_MS
            } catch (_: Throwable) {
                TAP_TIMEOUT_MS
            }
        }
        /** 菜单栏/播放栏滑出滑入的时长 */
        private const val CHROME_ANIM_MS = 200L

        /**
         * 目录按钮「解析中不可用」时的透明度。
         *
         * 0.4：足够明显地灰下去（不是 0.1 那种几乎看不出），又不会淡到像被禁用而误以为坏了。
         * 用 alpha 而不是换图标/换色，是为了让顶栏图标的**位置和尺寸完全不变** ——
         * 灰化前后按钮占位相同，顶栏不会因为状态切换而抖动。
         */
        private const val TOC_DISABLED_ALPHA = 0.4f
        /**
         * 「切换工具栏」的实现方式：**常驻把手 + 双击正文**（方案 D，2026-10-04）。
         *
         * ## 为什么不用侧带（方案 A 的实测代价）
         *
         * 方案 A 是「左右各留一条 162px 带，点带切栏」。在 Redmi K30 Pro 上实测：
         * 屏宽 1080、`item_line.xml` 的 padding 16dp = 44px、正文行 bounds x∈[44,1036]、
         * 16sp 字宽 44px ⇒ 每行 22.5 字。侧带 `max(1080*0.15, 48dp) = 162px`，
         * 右侧带覆盖 918~1036 = 118px = **2.7 字**，左侧同理
         * ⇒ **每行 5.4 字（约 24%）点不到播**。
         *
         * 方向没错（正文中间确实该还给「点哪句播哪句」），但**带宽取错了**：
         * 正文行有 16dp padding，**x<44 本身就是真留白、一个字都没有**，
         * 侧带超出的 162-44 = 118px 才是白扔的字。
         *
         * 更根本的是频率不匹配：切栏是低频操作（想看目录/调速/跳段），
         * 却让高频的「点句播」让出四分之一的可点面积。
         *
         * ## 方案 D 怎么解决
         *
         * 热区从「整列像素」缩成「一个 24×48dp 的控件」⇒ 每一行字 100% 可点播。
         * 代价只是屏幕上多一个小元素，而它**看一眼就会**（不像横滑手势要专门学）。
         * 保留双击正文作为第二入口，照顾已经形成肌肉记忆的用户。
         *
         * 不变式：阅读页正文的**全部像素**都属于「点句播」，不再有任何全局手势抢占。
         */
        private const val DOUBLE_TAP_TIMEOUT_MS = 280L
        /**
         * 双击判定的位移上限：第二击必须落在第一击附近（[DOUBLE_TAP_SLOP_DP] 内）才算双击。
         *
         * 用 24dp 而不是 [TAP_SLOP_DP] 的 12dp：双击的两下之间手会有轻微抖动，
         * 12dp 在高密度屏上只有 33px，容易把「想点另一句」判成双击。
         */
        private const val DOUBLE_TAP_SLOP_DP = 24f

        /** 字号档位（百分比）：仅作用于阅读页正文，默认 100% */
        private const val MIN_FONT_PERCENT = 80
        private const val MAX_FONT_PERCENT = 160

        /**
         * 点击弹窗里小 A / 大 A 一次的步长（**百分点**，不是相对百分比）。
         *
         * 用百分点而非「乘 0.95/1.05」：乘算的话步长会随当前值漂移
         * （100% 时点一下 +5%，160% 时就变成 +8%），手感不一致且很难解释；
         * 百分点则是恒定的 5 格。范围 80~160 能被 5 整除 ⇒ 上下边界也落在整格上。
         */
        private const val FONT_STEP_PERCENT = 5

        /** 字号预览行的基准字号（sp），预览实际大小 = 它 × 缩放比例 */
        private const val PREVIEW_TEXT_SP = 16f

        /**
         * 搜索结果**展示**条数上限。
         *
         * 关键词过于常见时（比如「的」），全量结果没人看得完，列表也会变慢。
         * 注意 `searchInBook` 实际会多收一条（上限 + 1）：那条不用来显示，
         * 只用来区分「还有更多」与「恰好这么多」，见该函数的注释。
         */
        private const val MAX_SEARCH_HITS = 200

    }

    // ===== 兜底选词（2026-10-14 方案 A）的状态机 =====
    // 真机定案：MIUI 选词器对部分行静默失败（无选区、无菜单、无日志），
    // 由 LineAdapter 在检测到失败时回调 startFallbackSelection 接管。
    // 三个状态合起来回答两个问题：现在能不能弹兜底菜单？兜底菜单由谁收尾？

    /** 系统（框架）选词 ActionMode 当前是否存活 */
    private var systemSelectionActionModeActive = false

    /**
     * 上一次**系统**选词 ActionMode 结束的时刻（elapsedRealtime）。
     * 防「用户刚点完菜单项（mode.finish 清了选区）⇒ 300ms 后兜底误判为失败
     * 又弹一张菜单」的竞态：系统菜单刚结束的短窗口内不让兜底抢跑。
     */
    private var lastSystemActionModeDestroyedAt = 0L

    /** 兜底菜单当前实例（null = 不存在）。收尾由 [dispatchTouchEvent] 与 onDestroyActionMode 协作完成 */
    private var fallbackActionMode: ActionMode? = null

    /**
     * 标记「正在启动兜底菜单」：startActionMode 会**同步**回调 onCreateActionMode，
     * 那时 fallbackActionMode 还没赋值，靠这个标志区分系统/兜底两种来源。
     */
    private var startingFallbackActionMode = false

    /**
     * ⭐ 兜底选区的自绘拖拽手柄（2026-10-14 方案 A 补充）。
     * `Selection.setSelection` 建的选区不带框架手柄（Editor 私有，MIUI 重写后更拿不到），
     * 这里在 decorView 上挂两个自绘手柄补齐「拖拽扩选」交互。
     * 生命周期与 [fallbackActionMode] 同步：attach 于兜底菜单创建成功后、
     * detach 于其销毁（onDestroyActionMode / Activity onDestroy）。
     */
    private val fallbackHandles: FallbackSelectionHandles = FallbackSelectionHandles(this)

    init {
        // 拖动中：浮动工具栏跟随选区（只刷定位，不重建菜单）
        fallbackHandles.onSelectionChanged = { fallbackActionMode?.invalidateContentRect() }
        // 拖动结束：「查字典」按选区字数动态显隐。
        // ⚠️ 不能依赖 mode.invalidate() → onPrepareActionMode：AOSP FloatingActionMode
        // 的 repositionToolbar 只在 contentRect **变化**时才 updateLayout 重读菜单项，
        // 而本页 onGetContentRect 锚定 selectionStart 所在行 —— 拖终点手柄时起点
        // 不变 ⇒ rect 不变 ⇒ 菜单改了视图也不重建（真机实证：拖完「查字典」残留）。
        // 这里直接做菜单手术，再用 1px 偏移骗两次 updateLayout 强制重绘。
        fallbackHandles.onDragEnd = {
            fallbackActionMode?.let { mode ->
                fallbackHandles.attachedTextView?.let { tv ->
                    syncDictMenuItemDirect(mode.menu, tv)
                }
                toolbarNudge = true
                mode.invalidateContentRect()
                toolbarNudge = false
                mode.invalidateContentRect()
            }
        }
        // 选区所在行被回收（滚动出屏等）⇒ 菜单失去锚点，整体收掉
        fallbackHandles.onInvalid = { fallbackActionMode?.finish() }
    }

    /**
     * [FallbackActionModeCallback.onGetContentRect] 的 1px 抖动开关：
     * true 时把 contentRect 下移 1px，让 FloatingActionMode 认为 rect 变了、
     * 走 updateLayout 重读菜单项（见 [fallbackHandles] onDragEnd 的注释）。
     */
    private var toolbarNudge = false

    /** 「查字典」按选区字数直接增删（不经 onPrepareActionMode，拖动手柄后用） */
    private fun syncDictMenuItemDirect(menu: Menu, tv: TextView) {
        val start = tv.selectionStart
        val end = tv.selectionEnd
        val selected = if (start >= 0 && end > start && end <= tv.text.length) {
            tv.text.substring(start, end)
        } else {
            ""
        }
        val shouldShow = ChinDictManager.hasSingleCjk(selected)
        val item = menu.findItem(MENU_DICT)
        when {
            shouldShow && item == null -> menu.add(Menu.NONE, MENU_DICT, Menu.NONE, "查字典")
            !shouldShow && item != null -> menu.removeItem(MENU_DICT)
        }
    }

    /**
     * 文本选择操作菜单提供者。
     * 当用户在 RecyclerView 的某个 TextView 上长按选中文本时，TextView 会触发 ActionMode，
     * 在操作菜单中显示"复制"和"收藏"两个菜单项。
     */
    private val textActionModeProvider = object : TextActionModeProvider {

        override fun getActionModeCallback(textView: TextView, item: LineItem): ActionMode.Callback {
            return object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    // 系统起的选词菜单记为「系统存活」；自己起的兜底菜单不算
                    //（区分依据见 [startingFallbackActionMode] 的注释）。
                    if (!startingFallbackActionMode) {
                        systemSelectionActionModeActive = true
                    }
                    // 先清空菜单：系统会在 ActionMode 里预置 Copy / Share / Select all
                    // （`smart_text_share_enabled=true` 时还有「分享」，`smart_selection_enabled=true`
                    // 时甚至追加「翻译」「地图」等智能项）。这些项与我们的菜单混排后
                    // 中英文夹杂、字号样式参差，且「Select all」在长文本上会把整章选进来。
                    // 本页只提供「复制」「收藏」「翻译」三项语义，故全部清掉再加自己的。
                    //
                    // ⭐ 这里的「翻译」是**本项目自己的离线词典**，不依赖系统智能项：
                    // 系统那套走的是 IME/Translate Service，会跳浏览器或弹系统应用，
                    // 既不认用户装的词典，也受制于 SelectActionModeHelper 那200ms 超时
                    // （上一轮已用 TextClassifier.NO_OP 掐断，见 LineAdapter）。
                    menu.clear()
                    menu.add(Menu.NONE, MENU_COPY, Menu.NONE, "复制")
                    menu.add(Menu.NONE, MENU_FAVORITE, Menu.NONE, "收藏")
                    menu.add(Menu.NONE, MENU_TRANSLATE, Menu.NONE, "翻译")
                    // ⭐ 「从此处读」：始终显示。
                    //   · 无合成音频的书 → 从选中处起调用系统 TTS 朗读（readFromHere）；
                    //   · 有合成音频的书 → 从选中处起播放合成音频（playLine）。
                    menu.add(Menu.NONE, MENU_READ_FROM_HERE, Menu.NONE, "从此处读")
                    // ⭐ 「查字典」**按选区内容动态增删**：只在恰好选中**一个汉字**时出现。
                    // 固定添加的话，选中一整句时也会显示「查字典」，点了必然失败
                    //（多字不是单字）—— 那是「菜单项存在但一定点不通」的最坏形态。
                    syncDictMenuItem(menu, textView)
                    return true
                }

                /**
                 * 按当前选区同步「查字典」项的**存在性**。
                 *
                 * 需要在两处调用：
                 * - [onCreateActionMode]：首次建菜单；
                 * - [onPrepareActionMode]：用户**拖动手柄调整选区范围**后刷新 ——
                 *   选中单字时有「查字典」，拖成两个字后必须消失；反向也要成立。
                 *
                 * 用 `menu.findItem(...) == null` 判存在性，而不是每次都 remove + add：
                 * 后者会让菜单项在每次刷新时**重新获得焦点**，用户点第二下时
                 * 目标项可能已经换了位置。
                 */
                private fun syncDictMenuItem(menu: Menu, tv: TextView) {
                    val dictItem = menu.findItem(MENU_DICT)
                    val shouldShow = ChinDictManager.hasSingleCjk(getSelectedText(tv))
                    when {
                        shouldShow && dictItem == null ->
                            menu.add(Menu.NONE, MENU_DICT, Menu.NONE, "查字典")
                        !shouldShow && dictItem != null ->
                            menu.removeItem(MENU_DICT)
                    }
                }

                /**
                 * 收尾清理：把「全选」等**系统后补项**去掉，只留本项目的四项。
                 *
                 * 为什么 `onCreateActionMode` 里的 `menu.clear()` 不够：
                 * `TextView` 的选区菜单由 `Editor.SelectionActionModeCallback` 组装，
                 * `onCreateActionMode` 只是其中一步；返回之后系统还会**再补**一次
                 * 「Select all」（以及可能的智能项）。
                 *
                 * 为什么同步 `onPrepareActionMode` 仍不够（2026-10-15 真机实锤）：
                 * 中文 ROM（MIUI）会在**调用完我们的 `onPrepareActionMode` 之后**，
                 * 于其内部 `onPrepareActionMode` 再补「全选」及智能选词图标项（搜索/词典）。
                 * 同步移除抓不到它们 → 菜单头部多两个系统图标、尾部多「全选」。
                 * 解法：本回调内先用白名单摘一次，再 `textView.post` 延迟到当前消息队列
                 * 末尾（系统已补完）摘第二次。选区手柄拖动会再次触发本回调，二次清理同样生效。
                 *
                 * 判据用**纯 id 白名单**（不猜系统项长什么样）：系统项文案/形态随 ROM、
                 * 系统语言任意变化，无法枚举，唯一可靠的反向判据是
                 * **不在白名单 = 系统项 = 摘**。
                 *
                 * ⚠️ 五个自定义 id 都要显式排除。新增菜单项时若忘了加这里，
                 * 该项会被当成系统项摘掉，表现为「菜单项莫名消失」。
                 */
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                    val keepIds = intArrayOf(MENU_COPY, MENU_FAVORITE, MENU_TRANSLATE, MENU_DICT, MENU_READ_FROM_HERE)
                    var removed = removeSystemItems(menu, keepIds)
                    // ⭐ 关键：MIUI/中文 ROM 会在**调用完本回调之后**，于其内部
                    //   onPrepareActionMode 再补「全选」及智能选词图标项（搜索/词典）——
                    //   同步移除抓不到它们。延迟到当前消息队列末尾（系统已补完）再摘一次，
                    //   跨 ROM 一致。选区手柄拖动会再次触发本回调，二次清理同样生效。
                    textView.post {
                        try {
                            removeSystemItems(menu, keepIds)
                            syncDictMenuItem(menu, textView)
                        } catch (_: Throwable) {
                            // ActionMode 已结束（菜单被关）则忽略
                        }
                    }
                    // ⭐ 选区被手柄拖动后必须重算「查字典」的存在性。
                    //   放在摘系统项**之后** —— 「查字典」在 keepIds 白名单里，
                    //   顺序上让「先清理、再同步」更不容易出错。
                    syncDictMenuItem(menu, textView)
                    // 选中文本非英文（含中文/纯符号）时不提供「翻译」：翻译走 ECDICT 英汉
                    // 离线词典，非英文必然查空。与「查字典」按选区内容显隐同哲学；选区为空
                    // 不摘，交 handleTranslate 的空选区提示兜底。放在 onPrepareActionMode
                    // （选区已就绪、每次刷新都跑）而非 onCreateActionMode（选区未必就绪），
                    // 避免误藏英文。MENU_TRANSLATE 在 keepIds 白名单内，textView.post 的
                    // 二次清理（removeSystemItems）不会复活它，移除稳定生效。
                    val sel = getSelectedText(textView)
                    if (sel.isNotEmpty() && !DictionaryManager.isEnglishWord(sel)) {
                        menu.removeItem(MENU_TRANSLATE)
                        removed = true
                    }
                    return removed
                }

                /**
                 * 把白名单之外的菜单项全部摘掉（白名单 = 复制/收藏/翻译/查字典）。
                 * 系统项文案/形态随 ROM、系统语言任意变化，无法枚举，
                 * 唯一可靠的反向判据：**不在白名单 = 系统项 = 摘**。
                 *
                 * ⚠️ 五个自定义 id 都要显式排除。新增菜单项时若忘了加这里，
                 *   该项会被当成系统项摘掉，表现为「菜单项莫名消失」。
                 */
                private fun removeSystemItems(menu: Menu, keepIds: IntArray): Boolean {
                    val stale = ArrayList<MenuItem>()
                    for (i in 0 until menu.size()) {
                        val item = menu.getItem(i)
                        if (item.itemId !in keepIds) {
                            stale.add(item)
                        }
                    }
                    for (item in stale) {
                        menu.removeItem(item.itemId)
                    }
                    return stale.isNotEmpty()
                }

                override fun onActionItemClicked(mode: ActionMode, menuItem: MenuItem): Boolean {
                    return when (menuItem.itemId) {
                        MENU_COPY -> {
                            val selectedText = getSelectedText(textView)
                            if (selectedText.isNotEmpty()) {
                                copyToClipboard(selectedText)
                                Toast.makeText(this@BookContentActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                mode.finish()
                            }
                            // 选区为空时保持菜单，让用户继续操作
                            true
                        }
                        MENU_FAVORITE -> {
                            val selectedText = getSelectedText(textView)
                            if (selectedText.isNotEmpty()) {
                                lifecycleScope.launch {
                                    val result = viewModel.addFavorite(bookName, item.lineNumber, selectedText)
                                    Toast.makeText(this@BookContentActivity, result.message, Toast.LENGTH_SHORT).show()
                                }
                                mode.finish()
                            }
                            // 选区为空时保持菜单
                            true
                        }
                        MENU_TRANSLATE -> {
                            val selectedText = getSelectedText(textView)
                            if (selectedText.isEmpty()) {
                                // 与上面两项保持一致：空选区保持菜单。
                                // 曾经无条件 finish()，而 handleTranslate 首行就
                                // isBlank() return ⇒ 用户点「翻译」菜单消失、零反馈，
                                // 分不清是选区空还是词典没装。
                                Toast.makeText(
                                    this@BookContentActivity,
                                    "请先选中要查询的单词",
                                    Toast.LENGTH_SHORT
                                ).show()
                                true
                            } else {
                                // ⚠️ 必须在 finish() 之前取选区：finish() 会清选区
                                handleTranslate(selectedText, wordSourceOf(item))
                                mode.finish()
                                true
                            }
                        }
                        MENU_DICT -> {
                            val ch = ChinDictManager.singleCjkOf(getSelectedText(textView))
                            if (ch == null) {
                                // 理论上点不到（菜单项只在该条件下显示），
                                // 但拖动手柄与点击之间可能发生选区变化，兜一手。
                                Toast.makeText(
                                    this@BookContentActivity, "请只选中一个字", Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                mode.finish()
                                handleLookupChinese(ch)
                            }
                            true
                        }
                        MENU_READ_FROM_HERE -> {
                            // 先取选区起点（finish() 会清选区，必须在之前取）
                            val selStart = textView.selectionStart
                            val selText = getSelectedText(textView)
                            if (selText.isNotEmpty()) {
                                // ⭐ 起点用**长按落点**（手指按到的那一个字），不是选区起点。
                                //
                                // 为什么不直接用 selectionStart：系统选词与兜底选词都会把落点
                                // 扩成**整词**（英文）或**整句**（中文）⇒ 选区起点比落点更早。
                                // 真机实测（Redmi K30 Pro）：长按落在 "Transform" 的 n 上
                                // （落点 21），选区却是整个词（起点 18）⇒ 朗读从 18 开始，
                                // 与用户按的位置对不上（「语音却从当前位置开始，不精准匹配」）。
                                //
                                // 「此处」= 用户按的那一处 ⇒ 用落点。落点不可得时（-1）
                                // 才回落到选区起点。
                                val touchOffset = longPressOffsetOf(textView)
                                val start = if (touchOffset >= 0) touchOffset else selStart
                                if (bookHasAudio) {
                                    // 有合成音频：从选中位置播放合成音频（不走系统 TTS）
                                    viewModel.playLine(item, start)
                                } else {
                                    // 无合成音频：从选中位置起调用系统 TTS 朗读
                                    readFromHere(item, start)
                                }
                            }
                            mode.finish()
                            true
                        }
                        else -> false
                    }
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    if (mode === fallbackActionMode) {
                        // 兜底菜单收尾：自己建的选区自己清（系统路径的收尾
                        // 由框架完成，插手反而可能清掉用户拖出来的选区）。
                        fallbackActionMode = null
                        // 自绘手柄随菜单一起撤（幂等；onInvalid 路径已经 detach 过也不怕）
                        fallbackHandles.detach()
                        (textView.text as? Spannable)?.let { Selection.removeSelection(it) }
                    } else {
                        // 系统菜单结束：记录时刻，短窗口内不让兜底抢跑（见字段注释）
                        systemSelectionActionModeActive = false
                        lastSystemActionModeDestroyedAt = SystemClock.elapsedRealtime()
                    }
                }

                private fun getSelectedText(tv: TextView): String {
                    val start = tv.selectionStart
                    val end = tv.selectionEnd
                    return if (start >= 0 && end > start && end <= tv.text.length) {
                        tv.text.substring(start, end)
                    } else {
                        ""
                    }
                }

                private fun copyToClipboard(text: String) {
                    copySelection(text)
                }
            }
        }

        /**
         * ⭐⭐ 兜底选词（2026-10-14 方案 A）：系统选词器静默失败时由这里接管。
         *
         * 建的选区：中文 = 落点**单字**（配合菜单里按需出现的「查字典」）；
         * 英文 = 落点所在的**整词**（单字母对「翻译」没有意义）。
         * 菜单 = [getActionModeCallback] —— 与系统成功路径**同一个浮动工具栏**
         *（复制/收藏/翻译/查字典），交互仍是单一路径。
         *
         * ⚠️ 「点外部关闭」不在框架手里（我们自己 startActionMode 起的菜单没有
         * Editor 那层管理），由 [dispatchTouchEvent] 兜住；收尾清选区在
         * onDestroyActionMode 的兜底分支里做。
         */
        override fun startFallbackSelection(
            textView: TextView,
            item: LineItem,
            charOffset: Int
        ): Boolean {
            // 系统菜单还活着（用户正拖着它的手柄/看着它的菜单）⇒ 不是失败场景，别抢
            if (systemSelectionActionModeActive) return false
            // 系统菜单刚被点掉（用户点了复制/收藏等，mode.finish 清了选区）——
            // 这不是「选词失败」，短窗口内不让兜底再弹一张
            if (SystemClock.elapsedRealtime() - lastSystemActionModeDestroyedAt < 500L) return false

            val text = textView.text
            if (text !is Spannable || text.isEmpty()) return false

            // 落点夹进 [0, len-1]：getOffsetForPosition 在行尾可能返回 len
            var start = charOffset.coerceIn(0, text.length - 1)
            var end = start + 1
            // 英文落点扩到整词边界（纯字母）
            fun isAsciiLetter(c: Char) = c in 'A'..'Z' || c in 'a'..'z'
            if (isAsciiLetter(text[start])) {
                while (start > 0 && isAsciiLetter(text[start - 1])) start--
                while (end < text.length && isAsciiLetter(text[end])) end++
            }
            Selection.setSelection(text, start, end)

            startingFallbackActionMode = true
            try {
                fallbackActionMode = textView.startActionMode(
                    FallbackActionModeCallback(
                        getActionModeCallback(textView, item),
                        textView,
                        nudge = { toolbarNudge }
                    ),
                    ActionMode.TYPE_FLOATING
                )
            } finally {
                startingFallbackActionMode = false
            }
            // 菜单起来了才有「拖拽扩选」的需求 —— 挂上自绘手柄
            //（attach 内部会用当前选区定位；失败场景由 detach/onInvalid 兜底）
            if (fallbackActionMode != null) {
                fallbackHandles.attach(textView)
            }
            return fallbackActionMode != null
        }
    }

    /**
     * 兜底菜单的定位包装（[ActionMode.Callback2]）。
     *
     * TYPE_FLOATING 的浮动工具栏默认锚定整个 View 的 contentRect ——
     * 多行 TextView 会飘到屏幕中央。这里把 contentRect 指到**选区所在视觉行**，
     * 与系统菜单的位置语义一致。其余全部委托给 [delegate]
     * （与系统成功路径同一个回调 ⇒ 同一个菜单）。
     */
    private class FallbackActionModeCallback(
        private val delegate: ActionMode.Callback,
        private val tv: TextView,
        /** 见 [BookContentActivity.toolbarNudge]：true 时把 contentRect 下移 1px 骗 updateLayout */
        private val nudge: () -> Boolean
    ) : ActionMode.Callback2() {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean =
            delegate.onCreateActionMode(mode, menu)

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean =
            delegate.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, menuItem: MenuItem): Boolean =
            delegate.onActionItemClicked(mode, menuItem)

        override fun onDestroyActionMode(mode: ActionMode) =
            delegate.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val layout = tv.layout
            if (layout == null || layout.lineCount == 0) {
                super.onGetContentRect(mode, view, outRect)
                return
            }
            val offset = tv.selectionStart.coerceIn(0, tv.text.length)
            val lineBounds = Rect()
            tv.getLineBounds(layout.getLineForOffset(offset), lineBounds)
            outRect.set(lineBounds)
            // 拖动结束强制工具栏重读菜单项的 1px 抖动（见 toolbarNudge 注释）
            if (nudge()) {
                outRect.offset(0, 1)
            }
        }
    }

    /**
     * ⭐ 兜底菜单的「点外部关闭」（2026-10-14 方案 A）。
     *
     * 系统浮动工具栏由 Editor 管理、点外部自动收起；我们自己 startActionMode
     * 起的菜单**没有**这层管理，不处理的话菜单会一直挂着。
     * 浮动工具栏是独立弹窗，点在工具栏上的触摸**不会**走进宿主 Activity 的
     * dispatchTouchEvent —— 所以「ACTION_DOWN 一律收掉兜底菜单」恰好等价于
     * 系统语义「点工具栏外任意处关闭」，不会误伤菜单项点击。
     *
     * ⚠️ 唯一例外：DOWN 落在**自绘拖拽手柄**上（手柄是 decorView 的子 View，
     * 触摸会先经过这里的 dispatchTouchEvent）——那是「拖拽扩选」的起点，
     * 必须放行给 [fallbackHandles]，不能当成「点外部」把菜单收掉。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && fallbackActionMode != null) {
            // finish() 会同步回调 onDestroyActionMode，选区清理与引用置空都在那边做
            if (!fallbackHandles.wantsTouch(ev)) {
                fallbackActionMode?.finish()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun copySelection(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ebook_text", text))
    }

    /**
     * 「查字典」菜单的处理：查**单个汉字**的读音与释义（随包新华字典）。
     *
     * 与 [handleTranslate] 的关键差异：
     *  - **不需要下载**（库随 APK 打包），所以没有「未安装」分支；
     *  - 只认**单个汉字**，多字/英文一律不查（菜单项本来就不会显示）。
     *
     * 首次调用会把 assets 里的 4 MB 库释放到私有目录（约几十毫秒，4.xG 设备上更久），
     * 故整段放在协程里 + `ChinDictManager` 内部的 IO 线程，
     * **主线程不做任何磁盘与数据库操作**。
     */
    private fun handleLookupChinese(ch: Char) {
        lifecycleScope.launch {
            // 先确保释放（首次会做 4 MB 文件拷贝）。失败才提示 ——
            // 成功与否都继续查，因为库里可能本来就有（释放过）。
            val installed = ChinDictManager.ensureInstalled(this@BookContentActivity) != null
            if (!installed) {
                Toast.makeText(
                    this@BookContentActivity,
                    "字典数据释放失败，请重试",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val entries = ChinDictManager.lookup(ch)
            if (entries.isEmpty()) {
                Toast.makeText(
                    this@BookContentActivity,
                    "《新华字典》未收录这个字",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            ChinDictSheet.show(supportFragmentManager, ch, entries)
        }
    }

    /**
     * 组装「当前查词落点所在行」的收藏出处，供单词本长按「跳转到原书」。
     *
     * - bookName：Activity 字段（收藏/音频缓存都用它，见 onCreate 从 Intent 赋值）。
     * - bookKey：交给 [WordSource.build] → [CoverageCodec.bookKey] 统一算，口径与覆盖图
     *   三端一致：书城在线读 `s:{storeBookId}` > 在线任务 `t:{serverTaskId}` > 本地书 `n:{归一化书名}`。
     * - sentenceText：整行原文（[LineItem.text]），**定位第一判据**，build 内截断 2000 字。
     * - lineNumber：[LineItem.lineNumber]，仅作兜底（切分粒度一变就漂）。
     * - segmentNumber：阅读页落点只有行号 + 行原文，段号需靠「段↔行」映射反查且结构
     *   可能尚未就绪，不强算，留 null（wire 可空；定位靠 sentenceText + lineNumber 已足够）。
     */
    private fun wordSourceOf(item: LineItem): WordSource = WordSource.build(
        bookName = bookName,
        storeBookId = storeBookId,
        serverTaskId = serverTaskId,
        lineNumber = item.lineNumber,
        sentenceText = item.text
    )

    /**
     * 「翻译」菜单的处理：查本应用自带的离线英汉词典（ECDICT）。
     *
     * 四条分支，按用户能否得到答案排序：
     *  1. **选区为空** → 直接返回（菜单已finish，不弹任何东西）；
     *  2. **未安装词典** → 提示去「我的 - 离线词典」下载。**不是**直接给浏览器链接 ——
     *     装词典是本功能的一部分，把用户踢出去等于让功能退回到系统浏览器。
     *  3. **选中的是中文** → 提示「仅支持查英文」。ECDICT 是英汉词典，查中文必然落空。
     *  4. **查词** → 命中则弹底部释义卡；未收录则提示，并给出「复制到剪贴板去搜」的出路。
     *
     * 查词在协程里做（[DictionaryManager.lookup] 内部切IO），
     * 主线程不做任何磁盘/数据库操作。
     */
    private fun handleTranslate(selectedText: String, source: WordSource?) {
        if (selectedText.isBlank()) return

        // ② 未装词典：先拦在这里，不要发起查询（装了也查不了）
        if (!DictionaryManager.isInstalled()) {
            Toast.makeText(
                this,
                "未安装词典，无法查词。请到「我的 - 离线词典」下载",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // ③ 中文 / 纯符号：ECDICT 是英汉词典，提前拦掉省一次无意义的查询
        if (!DictionaryManager.isEnglishWord(selectedText)) {
            Toast.makeText(this, "仅支持查英文", Toast.LENGTH_SHORT).show()
            return
        }

        // 选区可能带标点/ 是多词，提取出真正要查的那个词
        val query = DictionaryManager.extractQueryWord(selectedText)
        if (query == null) {
            Toast.makeText(this, "仅支持查英文", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val entries = DictionaryManager.lookup(query)
            if (entries.isEmpty()) {
                Toast.makeText(
                    this@BookContentActivity,
                    "词典未收录该词：$query",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            DictLookupSheet.show(
                this@BookContentActivity,
                query,
                entries,
                "",
                source = source
            )
        }
    }

    /**
     * 把「点读」按钮的两态刷到界面上。
     *
     * 只需要 [View.setSelected] —— 颜色由 `@color/tint_point_read` 这个
     * ColorStateList 按 `state_selected` 自动切换（见布局注释）。
     * **不要**在这里 setImageResource 换图标：两态是「同一图标不同颜色」，
     * 换资源反而多一套drawable 且与 tint 打架。
     *
     * 同时把 contentDescription 改成当前动作（而不是笼统的「点读」），
     * 无障碍朗读（TalkBack）读出来的才是「开启点读」/「关闭点读」。
     */
    private fun applyPointReadButtonState() {
        if (!::btnPointRead.isInitialized) return
        btnPointRead.isSelected = pointReadEnabled
        btnPointRead.contentDescription =
            if (pointReadEnabled) "关闭点读" else "开启点读：点英文单词查词并朗读"
    }

    /**
     * 「点读」模式下单击正文：取落点所在的英文词 → 弹释义 + 朗读。
     *
     * ## 与 [handleTranslate] 的分工
     * 两者共用 [DictionaryManager] 的查询与 [DictLookupSheet] 的展示，差别只在
     * **词从哪来**与**要不要出声**：
     *  · 长按「翻译」→ 词来自**选区**（[DictionaryManager.extractQueryWord]），不朗读；
     *  · 点读单击  → 词来自**落点**（[DictionaryManager.pointReadWordAt]），并朗读。
     * 所以这里不调handleTranslate：它对空选区直接 return，且没有朗读这一步。
     *
     * ## 落点没命中英文词时**什么都不做**
     * 点中文、点标点、点空白都走这条分支。这是刻意选择 —— 见
     * [DictionaryManager.pointReadWordAt] 的说明：「找右边最近的词」会制造定位错觉，
     * 比「点了没反应」更糟。用户此时若想听句子，会意识到自己处于点读模式并去关掉它。
     *
     * ## 未装词典时：就地询问下载，不把用户踢去「我的」
     * 问一句「是否现在下载」→ 同意则拉起下载进度窗 → 装完自动回来查**刚才点的这个词**。
     * 旧做法（Toast「请到我的-离线词典下载」）要求用户中断阅读、去另一个页面找入口、
     * 装完再自己回来重新点一次那个词 —— 三次打断才走完一次查词。
     *
     * ## 未装词典时不朗读
     * 先查得到释义再发声。反过来（先念后查）的话，用户点了词典未收录的词，
     * 会听到读音却等不到释义弹窗，体验像是坏了。
     */
    private fun handlePointRead(item: LineItem, charOffsetInRow: Int, position: Int) {
        val offset = if (charOffsetInRow >= 0) {
            charOffsetInRow
        } else {
            // itemView 上的点击拿不到字符下标（传的是 -1），此时无从取词。
            // 不用几何中心猜：那是长按 CJK 兜底的做法（那里本来就没有别的信息源），
            // 这里宁可不做，也不给出「点了 A 行却弹出 B 行单词」的错觉。
            return
        }
        val range = DictionaryManager.pointReadWordRangeAt(item.text, offset) ?: return

        // ⭐ 先点亮选中的词，再去查词。
        // 顺序是有意的：即使词典没装、或者这个词词典未收录，用户也已经看到
        // 「我点的是这几个字母」—— 否则弹窗之外没有任何痕迹，像点了没反应。
        if (position >= 0) {
            adapter.setPointReadHighlight(mapOf(position to range))
        }

        if (!DictionaryManager.isInstalled()) {
            promptDictDownloadThenLookup(item.text.substring(range.first, range.last + 1), wordSourceOf(item))
            return
        }

        lookupAndShow(item.text.substring(range.first, range.last + 1), wordSourceOf(item))
    }

    /**
     * 查词 → 弹释义卡 → 朗读。
     *
     * 点读直接查、以及「词典刚下载完接续查刚才那个词」共用这一段，
     * 保证两条路径的「未收录 / 先弹窗后出声」行为完全一致。
     */
    private fun lookupAndShow(word: String, source: WordSource?) {
        lifecycleScope.launch {
            val entries = DictionaryManager.lookup(word)
            if (entries.isEmpty()) {
                Toast.makeText(
                    this@BookContentActivity,
                    "词典未收录该词：$word",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            DictLookupSheet.show(
                this@BookContentActivity,
                word,
                entries,
                "",
                source = source
            )
            // 查到释义才朗读。**先弹窗再出声**：底部弹窗有展开动画，
            // 让语音紧接着起播会与动画的声音重叠、听不清。
            speakWordOrExplain(word)
        }
    }

    /**
     * 未安装词典时的确认框：同意 → 拉起下载进度窗，装完自动查 [word]。
     *
     * ## 为什么复用 [DictManageDialog] 而不自己写个进度条
     * 它已经有完整的「下载/解压进度 + 失败重试 + 状态渲染」，而状态源
     * [DictionaryManager.state] 是**全局 StateFlow** —— 另写一份必然漂移
     * （下载完了新弹窗还显示「下载中」这类）。新增的 `autoStart` 默认 false、
     * `dismissOnInstalled` 默认 false，所以「我的 - 离线词典」入口的行为
     * **一点没变**。
     *
     * ## 为什么装完要自动关掉它（`dismissOnInstalled = true`）
     * 这条链路里用户只点了**一个单词**：装完紧接着弹出来的是那个词的释义卡，
     * 而进度窗会原地变成「已安装 · 版本 · 词条数」继续挂在屏幕上 ——
     * 两个弹窗叠在一起，其中那个管理窗纯属多余（用户主动去「我的 - 离线词典」
     * 时才需要它留在原地看结果）。
     *
     * ## 并发兜底
     * [DictionaryManager.downloadAndInstall] 自带进程级 `tryLock`，
     * 与「我的 - 离线词典」同时发起也只会真正下载一次（这里拿到锁失败就直接返回）。
     * 因此这里若发现已在下载中，直接把已有的进度窗拉出来看，不重复询问。
     */
    private fun promptDictDownloadThenLookup(word: String, source: WordSource?) {
        // 已经在下载/解压中：别再问一遍「要不要下载」，直接把进度窗拉出来
        val st = DictionaryManager.state.value
        if (st is DictionaryManager.DictState.Downloading ||
            st is DictionaryManager.DictState.Installing
        ) {
            DictManageDialog.show(
                this,
                this,
                dismissOnInstalled = true,
                onInstalled = { lookupAndShow(word, source) }
            )
            return
        }

        AlertDialog.Builder(this)
            .setTitle("需要下载离线词典")
            .setMessage(
                "点读查词需要离线英汉词典（约 23 MB，MIT 许可，可随时卸载）。" +
                    "是否现在下载？"
            )
            .setPositiveButton("下载") { _, _ ->
                DictManageDialog.show(
                    this,
                    this,
                    autoStart = true,
                    // 装完直接关掉进度窗，把屏幕让给单词释义卡：
                    // 留着它就等于在释义卡上面再压一个「已安装」的管理弹窗，
                    // 对"我只是点了个单词"这件事来说纯属多余。
                    dismissOnInstalled = true,
                    onInstalled = { lookupAndShow(word, source) }
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 朗读一个单词；引擎不可用时明确提示，**不静默失败**。
     *
     * 降级原因：设备可能没装 TTS 引擎（含不带 Google TTS 的模拟器镜像）。
     * 这时 [TextToSpeaker.speak] 返回 false，用户点了却毫无声响 ⇒ 必须告诉他原因，
     * 否则会以为「点读的朗读功能坏了」而不再使用。释义弹窗此时照常显示。
     */
    private fun speakWordOrExplain(word: String) {
        if (!TextToSpeaker.speak(this, word)) {
            Toast.makeText(
                this,
                "本机未安装语音引擎，无法朗读（释义仍可查看）",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // =========================================================================
    // #21：阅读页「实时朗读」（系统 TextToSpeech，不入库、播完即弃）
    // =========================================================================

    /** 朗读开关：开 → 从当前可见行起逐行念；关 → 停止 */
    private fun toggleReadAloud() {
        if (readAloudActive) stopReadAloud() else startReadAloud()
    }

    private fun startReadAloud() {
        startReadAloudFrom(firstVisibleLine())
    }

    /**
     * 从指定行起朗读（底部播放栏「后退/前进」在无音频书未朗读时的入口）：
     * 后退 = 当前可见段的上一段，前进 = 下一段，与朗读中的跳转 [jumpReadAloud] 同语义。
     */
    private fun startReadAloudFrom(index: Int) {
        if (adapter.currentList.isEmpty()) {
            Toast.makeText(this, "正文尚未加载好", Toast.LENGTH_SHORT).show()
            return
        }
        if (index < 0 || index >= adapter.currentList.size) {
            toastReadAloudEdge(index < 0)
            return
        }
        // prepare 异步就绪；就绪后 beginReadAloud 在主线程被回调，失败则弹 Toast 指引装语音包。
        ReadAloudSpeaker.prepare(
            this,
            onReady = { beginReadAloud(index) },
            onUnavailable = { toastReadAloudUnavailable() }
        )
    }

    /** 当前可见的第一行正文下标（朗读起点 / 未朗读时跳段的基准） */
    private fun firstVisibleLine(): Int {
        val lm = recyclerView.layoutManager as? LinearLayoutManager
        return (lm?.findFirstVisibleItemPosition() ?: 0).coerceAtLeast(0)
    }

    // ---------- 纯阅读（无音频）时的阅读位置 ----------

    /** 滚动停稳后再记位置（滚动事件连发几十次，直接写会把服务端打爆）。 */
    private val readingPositionHandler = Handler(Looper.getMainLooper())
    private val readingPositionRunnable = Runnable { saveReadingPosition() }

    /** 安排一次「记录阅读位置」。重复调用会重置计时（只在最后一次滚动后真正落盘）。 */
    private fun scheduleReadingPositionSave() {
        readingPositionHandler.removeCallbacks(readingPositionRunnable)
        readingPositionHandler.postDelayed(readingPositionRunnable, READING_POSITION_SAVE_DELAY_MS)
    }

    /**
     * 记录纯阅读时「读到哪一行」。
     *
     * 只有**没有音频**时才需要：有音频时位置由播放段决定（更准），
     * 这里不覆盖播放位置。
     */
    private fun saveReadingPosition() {
        if (isFinishing || isDestroyed) return
        val hasAudio = (viewModel.currentPlayingPosition.value ?: -1) >= 0
        if (hasAudio) return
        val lineNo = firstVisibleLine()
        if (lineNo < 0) return
        viewModel.setReadingLineNumber(lineNo)
        // 段号口径：与服务端 /content 的 paragraphs 下标同源（1-based）。
        // 本地书用行号兜底（无映射时行号即段号，与旧口径一致）。
        val segment = viewModel.segmentNumberOfLine(lineNo)
        if (segment <= 0) return
        viewModel.saveReadingProgress(bookName, segment, 0)
    }

    /**
     * 朗读中跳段（底部「后退/前进」）：一次一段，与自动播放读完一段进到下一段的
     * 推进单位相同。暂停态调用会顺带解除暂停（跳转即继续读）。
     */
    private fun jumpReadAloud(delta: Int) {
        val total = ReadAloudSpeaker.lineCount
        if (total <= 0) return
        // currentLine 还没被回调过（刚起读、尚未开口）时，退到可见行作基准
        val cur = if (ReadAloudSpeaker.currentLine >= 0) {
            ReadAloudSpeaker.currentLine
        } else {
            firstVisibleLine()
        }
        val target = cur + delta
        if (target < 0 || target >= total) {
            toastReadAloudEdge(target < 0)
            return
        }
        if (ReadAloudSpeaker.jumpTo(target)) {
            // 跳转后仍在朗读：播放键回到「暂停」图标（jumpTo 内部已解除暂停态）
            btnPlayToggle.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    /** 跳段越界提示：已经是第一段/最后一段（不重读、不跳转，避免误触把朗读搞停） */
    private fun toastReadAloudEdge(isFirst: Boolean) {
        Toast.makeText(
            this,
            if (isFirst) "已经是第一段了" else "已经是最后一段了",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun beginReadAloud(fromIndex: Int? = null) {
        val lines = adapter.currentList.map { it.text }
        val startPos = fromIndex ?: firstVisibleLine()
        // 从头（或从可见行）读：第 0 行的坐标不需要任何偏移
        readAloudCharBase = 0
        val ok = ReadAloudSpeaker.start(
            lines = lines,
            fromIndex = startPos,
            onLine = { idx -> runOnUiThread { highlightReadLine(idx) } },
            onFinished = { runOnUiThread { endReadAloud() } },
            // 行是段落，只亮整行看不出「念到哪句」；朗读按句入队，回调即给出该句区间
            onSentence = { idx, start, end ->
                runOnUiThread { highlightReadSpan(idx, start, end) }
            }
        )
        if (!ok) {
            toastReadAloudUnavailable()
            return
        }
        // 新一次朗读：清掉上一轮的跟读状态，从头自动跟随
        resetReadAloudFollowState()
        readAloudActive = true
        btnReadAloudBar.isSelected = true
        showReadAloudBar()
    }

    /**
     * 配置变更（旋转/分屏）后恢复朗读 UI 与回调（v5/A4）。
     *
     * <p>三个动作缺一不可：
     *  1. 把本地状态与按钮/播放条恢复（否则用户看到"没在朗读"）；
     *  2. **重新绑定回调**（否则单例还在往已销毁的旧实例投递，新实例永远收不到）；
     *  3. 补一次当前行高亮（旋转期间可能已经念到下一行）。
     */
    private fun restoreReadAloudAfterRecreate() {
        if (!ReadAloudSpeaker.isActive) return
        readAloudActive = true
        btnReadAloudBar.isSelected = true
        showReadAloudBar()
        ReadAloudSpeaker.rebind(
            onLine = { idx -> runOnUiThread { highlightReadLine(idx) } },
            onFinished = { runOnUiThread { endReadAloud() } },
            onSentence = { idx, start, end ->
                runOnUiThread { highlightReadSpan(idx, start, end) }
            }
        )
        val current = ReadAloudSpeaker.currentLine
        if (current >= 0) {
            highlightReadLine(current)
        }
    }

    /**
     * 「从此处读」：从长按选中的文本位置接力朗读，而非从头读整本。
     *
     * 菜单显隐规则见 `onCreateActionMode`：**无合成音频的书常驻**（点它即从选中处
     * 起播，是这类书唯一定位起播的手段）；有音频的书仅在朗读已开启时出现（接力）。
     * 选中行从选区起点起的剩余子串作为朗读首段，其后接原列表剩余行；
     * 高亮按下标映射回原行，与 [beginReadAloud] 共用同一套高亮/滚动/播放条。
     */
    private fun readFromHere(item: LineItem, selStart: Int) {
        val list = adapter.currentList
        val linePos = list.indexOf(item).coerceAtLeast(0)
        val lineText = item.text
        val offset = selStart.coerceIn(0, lineText.length)
        val rest = if (offset < lineText.length) lineText.substring(offset) else ""
        // 选中起点恰好在某行末尾时，首段为空，朗读实际从下一行开始，高亮基准下移一行
        val baseOrig = if (rest.isNotBlank()) linePos else linePos + 1
        val lines = buildList {
            if (rest.isNotBlank()) add(rest)
            for (i in (linePos + 1)..list.lastIndex) add(list[i].text)
        }
        android.util.Log.i("ReadAloudSpeaker", "readFromHere: linePos=$linePos selStart=$selStart offset=$offset rest=[${rest.take(24)}] baseOrig=$baseOrig lines=${lines.size} first=[${lines.firstOrNull()?.take(24)}]")
        // 复用 prepare：已开过朗读则立即 onReady；未就绪（极端）则先准备再读
        ReadAloudSpeaker.prepare(
            this,
            onReady = {
                // 首行入队的是 lineText.substring(offset)：句级回调的行内下标要加回 offset
                readAloudCharBase = if (rest.isNotBlank()) offset else 0
                val ok = ReadAloudSpeaker.start(
                    lines = lines,
                    fromIndex = 0,
                    onLine = { idx ->
                        runOnUiThread {
                            // 首段（idx==0 且选中处不在行尾）只从选区起点开始高亮；
                            // 其余行、以及"选中恰在行尾"的退化情形都按整行高亮。
                            val hlStart = if (idx == 0 && rest.isNotBlank()) offset else 0
                            highlightReadLine(baseOrig + idx, hlStart)
                        }
                    },
                    onFinished = { runOnUiThread { endReadAloud() } },
                    onSentence = { idx, start, end ->
                        runOnUiThread {
                            val base = if (idx == 0) readAloudCharBase else 0
                            // 首行从选区起点起念：高亮不得越过选区之前的文字
                            highlightReadSpan(baseOrig + idx, base + start, base + end, base)
                        }
                    }
                )
                if (ok) {
                    // 从选中处重新起读：同样先复位跟读状态（否则可能带着上轮的"已翻走"）
                    resetReadAloudFollowState()
                    readAloudActive = true
                    btnReadAloudBar.isSelected = true
                    showReadAloudBar()
                } else {
                    toastReadAloudUnavailable()
                }
            },
            onUnavailable = { toastReadAloudUnavailable() }
        )
    }

    /**
     * 取该行**长按落点**（用户手指按到的那一个字）的行内字符下标；取不到返回 -1。
     *
     * 落点由 [LineAdapter] 在 ACTION_DOWN/UP 时写进 TextView 的 tag
     * （见 `R.id.tag_long_press_offset`）。
     *
     * ## 为什么「从此处读」要用它而不是选区起点
     * 系统选词与兜底选词都会把落点**扩成整词（英文）或整句（中文）**，
     * 于是选区起点比落点更早。真机实测（Redmi K30 Pro / MIUI）：
     * 长按落在 "Explanations that Transform the World" 的 "Transform" 的 n 上（落点 21），
     * 选区却是整个词 "Transform"（起点 18）⇒ 朗读从 18 开始，与用户按的位置差 3 个字符。
     *
     * 「此处」= 用户按的那一处 ⇒ 用落点；落点不可得时才回落到选区起点。
     */
    private fun longPressOffsetOf(textView: TextView): Int {
        val tag = textView.getTag(R.id.tag_long_press_offset)
        return if (tag is Int) tag else -1
    }

    /**
     * 高亮当前朗读行并滚动使其可见。
     *
     * @param idx            原列表里的行下标
     * @param highlightStart 该行内高亮的**起点**（0 = 整行）。「从此处读」的首行传选区
     *                       起点 ⇒ 只亮选中处到行尾，让用户一眼看出从哪个字起念。
     *
     * 走独立的朗读高亮层（[LineAdapter.setReadAloudHighlight]）而**不是**音频播放的
     * 播放高亮层：否则朗读结束时会 `updatePlayState(-1)` 误清有声书正在播的高亮。
     */
    private fun highlightReadLine(idx: Int, highlightStart: Int = 0) {
        if (idx !in adapter.currentList.indices) return
        val rowText = adapter.currentList[idx].text
        val from = highlightStart.coerceIn(0, rowText.length)
        // 起点越界（空行等）⇒ 本行无区间可亮，交给下一个被读到的行
        applyReadAloudSpan(idx, if (from < rowText.length) from..(rowText.length - 1) else null)
        // 记住当前朗读行：「回到播放处」要跳到这里（哪怕此刻并没有滚动）
        readAloudCurrentRow = idx
        // ⚠️ 用户手动翻页后**不再自动跟随**。否则正文会被下一句朗读硬拉回去 ——
        // 正在看的内容与正在读的内容互相打架。点「回到播放处」才跳回（并恢复跟随）。
        if (!readAloudUserScrolledAway) {
            followRow(idx)
        }
        // 朗读位置前进了：若此刻它已在视口内（例如刚点完"回到播放处"），按钮就该收起
        updateBackToReadPositionVisibility()
    }

    /**
     * 句级高亮：只点亮**正在读的那一句**，而不是整段。
     *
     * 区间由 [ReadAloudSpeaker] 直接给出：朗读按**句**入队，一条 utterance 就是一句，
     * 引擎 `onStart` 一到就知道念的是哪一行的哪个区间 —— 不依赖引擎上报发音区间
     * （真机上那套 `onRangeStart` 根本不回调，见 [ReadAloudSpeaker] 类注释）。
     *
     * @param start      该句在本行原文里的起点（闭区间）
     * @param end        该句终点（闭区间，含句末标点）
     * @param clampStart 高亮**不得早于**这个下标。「从此处读」时首句从选区起点起念，
     *                   而选区常常落在句子中间 —— 若不夹一刀，高亮会把选区之前
     *                   那句尚未念到的前半句一起点亮。
     */
    private fun highlightReadSpan(row: Int, start: Int, end: Int, clampStart: Int = 0) {
        if (row !in adapter.currentList.indices) return
        val text = adapter.currentList[row].text
        val hiEnd = end.coerceIn(0, text.length - 1)
        val hiStart = start.coerceIn(0, hiEnd)
        val range = if (clampStart > hiStart) {
            clampStart.coerceAtMost(hiEnd)..hiEnd
        } else {
            hiStart..hiEnd
        }
        applyReadAloudSpan(row, range)
        readAloudCurrentRow = row
        if (!readAloudUserScrolledAway) followRow(row)
        updateBackToReadPositionVisibility()
    }

    /**
     * 把「某一行的高亮区间」写进适配器，**相同区间直接跳过**。
     *
     * 同一句的区间会重复回报（跳转/续读/补高亮各一次），而区间一旦相同，
     * 重新绑定的结果也完全一样；不去重就是同一行反复重绑（每次都要重建 Spannable），
     * 滑动时肉眼可见地掉帧。
     */
    private fun applyReadAloudSpan(row: Int, range: IntRange?) {
        val next = if (range == null) null else (row to range)
        if (next == readAloudLastSpan) return
        readAloudLastSpan = next
        adapter.setReadAloudHighlight(if (range == null) emptyMap() else mapOf(row to range))
    }

    /**
     * 「回到播放处」唯一入口：按当前播放形态分发。
     *
     * · 朗读中 → [backToReadAloudPosition]（还要恢复朗读的自动跟随）；
     * · 仅有声书 → 跳到 `currentPlayingPosition` 并恢复播放跟随。
     *
     * 两种形态共用同一枚按钮与同一条显隐规则（见 [updateBackToReadPositionVisibility]），
     * 避免底栏再多一个入口 —— 28dp 高的栏里放不下第二个。
     */
    private fun backToPlaybackPosition() {
        if (readAloudActive && readAloudCurrentRow >= 0) {
            backToReadAloudPosition()
            return
        }
        val target = viewModel.currentPlayingPosition.value ?: -1
        if (target < 0) {
            Toast.makeText(this, "还没有开始播放", Toast.LENGTH_SHORT).show()
            return
        }
        // 恢复自动跟随：用户点这个按钮的语义就是"我要跟着听的那段走"。
        audioUserScrolledAway = false
        jumpToPosition(target)
        // 同朗读：不能等下一段的位置回调来收按钮 —— 那可能隔几十秒，
        // 期间按钮会一直挂着（甚至用户又滚走了它还留着）。
        updateBackToReadPositionVisibility()
    }

    /**
     * 「回到播放处」：把正文瞬时定位回当前朗读行，并恢复自动跟随。
     *
     * 用户手动翻页后朗读仍在推进（高亮照走），只是不再拽着正文跑；这个按钮就是那根
     * "带我回去"的线。用 [jumpToPosition]（瞬时定位、目标落在视口顶端）而不是
     * smoothScrollToPosition —— 中间隔了几十行时，平滑动画会滑很久才到。
     */
    private fun backToReadAloudPosition() {
        val target = readAloudCurrentRow
        if (target < 0) {
            Toast.makeText(this, "还没有开始朗读", Toast.LENGTH_SHORT).show()
            return
        }
        readAloudUserScrolledAway = false // 恢复自动跟随
        jumpToPosition(target)
        // 回到朗读页 ⇒ 「回到播放处」已无意义，立即收起。
        // ⚠️ 不能等 highlightReadLine 来判：jumpToPosition 是瞬时的，而下一行的朗读
        // 回调可能还没到（甚至当前行就在长句中间），按钮会多挂一会儿。
        updateBackToReadPositionVisibility()
    }

    /** 一次朗读结束/停止后的跟读状态复位，下次朗读从头自动跟随 */
    private fun resetReadAloudFollowState() {
        readAloudUserScrolledAway = false
        readAloudCurrentRow = -1
        // 去重缓存必须一起清：下一轮朗读第 0 行若恰好落在同一个区间，
        // 不清就会因为「和上次一样」而整段不亮。
        readAloudLastSpan = null
        readAloudCharBase = 0
        // ⚠️ 不能无条件 GONE：这枚按钮 2026-10-30 起是「朗读 + 有声书播放」共用的。
        // 朗读结束（自然读完/手动停）时，若有声书仍在播且播放行不在视口内，
        // 它必须继续服务有声书；没有播放位置时 updateBackToReadPositionVisibility
        // 自己会把它置 GONE。
        updateBackToReadPositionVisibility()
    }

    /**
     * 按「本书有无音频 + 是否正在朗读」重排底部播放栏的按钮（2026-10-18，朗读入口迁往底部）。
     *
     * <p>三态：
     *  · **朗读中**：不动 —— 显隐由 [showReadAloudBar] 管（倍速/定时收起，「回到播放处」
     *    按需显示；后退/播放/前进是媒体三联键，朗读中改作跳段/暂停继续）；
     *  · **未朗读 + 有音频**：完整有声书控制台（后退/前进跳音频段、定时/倍速齐全），
     *    外加 [btnReadAloudBar] 作为系统 TTS 朗读的启停入口；
     *  · **未朗读 + 无音频**：后退/播放/前进三联键常驻 —— 点播放即从当前可见段起朗读，
     *    点后退/前进则从上一段/下一段起朗读（跳转单位与自动播放一致）。
     *    定时/倍速对纯文本朗读无意义，继续隐藏。
     *
     * <p>调用时机：tasks 变化（hasAudio 重算）、朗读结束/停止（收回播放条时按书类型重排）。
     */
    private fun applyPlaybackBarForBookType() {
        // 有音频的书不需要独立 TTS 朗读入口；无音频的书由播放键（btnPlayToggle）
        // 直接触发 startReadAloud()，也不需要这个按钮。始终隐藏。
        btnReadAloudBar.visibility = View.GONE
        if (readAloudActive) return // 朗读中的显隐由 showReadAloudBar 负责，这里不碰
        // 后退/播放/前进：标准媒体三联键，两类书、朗读与否都常驻
        btnPlayPrev.visibility = View.VISIBLE
        btnPlayNext.visibility = View.VISIBLE
        btnSpeed.visibility = if (bookHasAudio) View.VISIBLE else View.GONE
        btnSleepTimer.visibility = if (bookHasAudio) View.VISIBLE else View.GONE
        // 「回到播放处」：有声书播放中也可能需要它（用户滚走看别处），
        // 此处按当前播放位置重判一次；没有播放位置时它自己会 GONE。
        updateBackToReadPositionVisibility()
    }

    /**
     * 朗读进行中：复用底部播放条（隐藏倍速/定时这类只对有声书有意义的入口）。
     * 后退/前进此时是「上一段/下一段」跳转键（见 [setupPlaybackControlBar]），保持可见；
     * 停止朗读 = 退出阅读页（onDestroy 收尾），与有声书"没有停止键、退出即停"同范式。
     */
    private fun showReadAloudBar() {
        playbackControlBar.visibility = View.VISIBLE
        btnPlayToggle.setImageResource(android.R.drawable.ic_media_pause)
        btnSpeed.visibility = View.GONE
        btnSleepTimer.visibility = View.GONE
        btnPlayPrev.visibility = View.VISIBLE
        btnPlayNext.visibility = View.VISIBLE
        // 「回到播放处」：朗读时右侧「定时/倍速」让位给它（那两者对朗读无意义）。
        // ⚠️ 不能写死 VISIBLE —— 刚起读时朗读行就在眼前，此时并无"回到"的必要。
        // 2026-10-30 起这枚按钮同时也服务有声书播放（见 updateBackToReadPositionVisibility）。
        updateBackToReadPositionVisibility()
    }

    /**
     * 「回到播放处」的按需显隐：**有播放/朗读位置 且 该行不在当前视口内**才显示。
     *
     * <p>2026-10-30 起覆盖两种播放形态（此前只管朗读）：
     * · **朗读中** → 目标是 [readAloudCurrentRow]；
     * · **有声书播放中** → 目标是 `viewModel.currentPlayingPosition`。
     *
     * 两个条件缺一不可：
     *  · 没有播放位置 ⇒ 这个按钮没有意义（停止播放/结束朗读都会把位置清成 -1）；
     *  · 目标行**就在眼前** ⇒ 正文已经在播放位置，"回去"是空操作，按钮纯属噪声。
     *    所以用户手动翻回那一页后，它会自己消失。
     *
     * 调用于：起播、每行高亮推进、列表滚动、底栏按书类型重排 ——
     * 也就是"播放位置"与"视口"任一发生变化时都会重新判定，不存在状态对不上的窗口。
     */
    private fun updateBackToReadPositionVisibility() {
        if (!::btnBackToReadPosition.isInitialized) return
        val target = currentPlaybackRow()
        val lm = recyclerView.layoutManager as? LinearLayoutManager
        val first = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val last = lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val shouldShow = shouldShowBackToPlayButton(target, first, last)
        btnBackToReadPosition.visibility = if (shouldShow) View.VISIBLE else View.GONE
        // 真的显示了才算"见到过"：刚起读/刚开始播时目标行就在眼前、按钮根本不出现，
        // 那一刻并没有可以提示的对象，不该把这次机会用掉。
        // ⚠️ 提示文案讲的是「跟读」（朗读专属概念），有声书播放时不该弹 ——
        // 否则用户会被告知一个他没开启的功能。
        if (shouldShow && readAloudActive) maybeShowBackToPlayHint()
    }

    /**
     * 「回到播放处」的目标行；没有可回去的位置时返回 -1。
     *
     * 判定本身在 [resolveBackToPlayTargetRow]（纯函数，有单测）；这里只负责取当前状态。
     */
    private fun currentPlaybackRow(): Int = resolveBackToPlayTargetRow(
        readAloudActive = readAloudActive,
        readAloudRow = readAloudCurrentRow,
        audioPlayingRow = viewModel.currentPlayingPosition.value ?: NO_PLAYBACK_TARGET
    )

    /**
     * 「回到播放处」图标化的**一次性**引导（2026-10-29）。
     *
     * 为什么需要：按钮上的文字换成准星之后，图标本身猜不出含义，而"长按出提示"是
     * **隐藏交互** —— 不知道能长按的人永远看不到那行文案。所以在用户第一次真正
     * 看到这枚图标时主动说一次：这是什么、怎么查。
     *
     * 为什么只弹一次、且存在 SharedPreferences：见 [AppPrefs.isBackToPlayHintShown]。
     * 用 LENGTH_LONG 而不是 SHORT：这句话要传达两件事（已开启跟读 / 可长按），
     * 朗读进行中 SHORT 的时长常被正文的朗读声盖过去。
     */
    private fun maybeShowBackToPlayHint() {
        if (backToPlayHintShown) return
        backToPlayHintShown = true
        AppPrefs.setBackToPlayHintShown(this)
        Toast.makeText(this, "已开启跟读，长按此按钮可回到播放处", Toast.LENGTH_LONG).show()
    }

    // 注：「目标行在不在视口内」的判定已抽到纯函数 shouldShowBackToPlayButton
    // （见 BackToPlayTarget.kt，有单测覆盖）。这里不再保留一层薄封装 ——
    // 多一层包装只会让"到底按哪套规则判"变得含糊。

    /** 自然读完：复位状态、清高亮、收回播放条（按书类型重排，不再整体消失） */
    private fun endReadAloud() {
        readAloudActive = false
        btnReadAloudBar.isSelected = false
        // 只清朗读层。原实现调 updatePlayState(-1) 会把有声书正在播的高亮一并清掉
        adapter.setReadAloudHighlight(emptyMap())
        resetReadAloudFollowState()
        // 2026-10-18：原实现把整条播放栏设 GONE —— 朗读入口迁到底部后这不可接受：
        // 无音频书的播放键就在这条栏里，GONE 之后用户再没有入口开朗读；有音频书也会
        // 顺手丢掉整套播放控制（暂停/上段/下段/定时/倍速）。改为收回但保持可见，
        // 按钮显隐交给 applyPlaybackBarForBookType 按书类型重排。
        playbackControlBar.visibility = View.VISIBLE
        btnPlayToggle.setImageResource(android.R.drawable.ic_media_play)
        applyPlaybackBarForBookType()
    }

    /** 用户主动停止（再点有音频书的朗读开关 [btnReadAloudBar]） */
    private fun stopReadAloud() {
        ReadAloudSpeaker.stop()
        readAloudActive = false
        btnReadAloudBar.isSelected = false
        adapter.setReadAloudHighlight(emptyMap())
        resetReadAloudFollowState()
        // 同 endReadAloud：收回但不消失，按书类型重排（无音频书留三联键作下次入口）
        playbackControlBar.visibility = View.VISIBLE
        btnPlayToggle.setImageResource(android.R.drawable.ic_media_play)
        applyPlaybackBarForBookType()
    }

    private fun toastReadAloudUnavailable() {
        Toast.makeText(
            this,
            "本机未安装或不支持中文语音引擎，无法朗读（请到系统设置安装中文语音包）",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_content)

        // 初始化控件（必须在访问 intent extras 前完成，避免 early-return 时 finish() 空指针）
        recyclerView = findViewById(R.id.recyclerView)
        btnBack = findViewById(R.id.btnBack)
        btnToc = findViewById(R.id.btnToc)
        btnFontSize = findViewById(R.id.btnFontSize)
        btnSearch = findViewById(R.id.btnSearch)
        btnPointRead = findViewById(R.id.btnPointRead)
        btnReadAloudBar = findViewById(R.id.btnReadAloudBar)
        btnPlayPrev = findViewById(R.id.btnPlayPrev)
        btnPlayToggle = findViewById(R.id.btnPlayToggle)
        btnPlayNext = findViewById(R.id.btnPlayNext)
        btnSleepTimer = findViewById(R.id.btnSleepTimer)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnBackToReadPosition = findViewById(R.id.btnBackToReadPosition)
        btnSegmentVoice = findViewById(R.id.btnSegmentVoice)
        readerLoadingOverlay = findViewById(R.id.readerLoadingOverlay)
        readerLoadingHint = findViewById(R.id.readerLoadingHint)
        readerTopBar = findViewById(R.id.readerTopBar)
        playbackControlBar = findViewById(R.id.playbackControlBar)
        btnChromeHandle = findViewById(R.id.btnChromeHandle)
        // 「回到播放处」图标的一次性引导：载入"是否已弹过"的持久化标记（见字段注释）。
        backToPlayHintShown = AppPrefs.isBackToPlayHintShown(this)

        val bookTableName = intent.getStringExtra("BOOK_TABLE_NAME")
        val filePath = intent.getStringExtra("FILE_PATH")
        serverTaskId = intent.getLongExtra("SERVER_TASK_ID", -1L)
        // 书城「在线读」入口（BookStoreFragment「在线阅读」传入）：不落电子书文件，
        // 正文按 bookId 在线取、音频播到哪下到哪。
        storeOnline = intent.getBooleanExtra("STORE_ONLINE", false)
        storeBookId = intent.getLongExtra("STORE_BOOK_ID", -1L)
        // 定位段号交给 ViewModel 保管（见 BookViewModel 里的同名注释）。
        // 放在 Activity 字段里的话，旋转后 intent 会原样带回来，标记等于没清。
        val pendingScrollToLine = intent.getIntExtra("SCROLL_TO_LINE", -1)
        // 收藏时选中的原文片段：定位的第一判据（行号会随切分粒度漂移，文字不会）
        val pendingScrollText = intent.getStringExtra("SCROLL_TO_TEXT")
        // 音频段号：「点通知栏/锁屏的播放卡片回阅读页」时由 PlaybackService 传入。
        // 它只知道段号、不知道行号，等正文结构就绪后再换算（见 applyPendingNavigation）。
        val pendingScrollToSegment = intent.getIntExtra("SCROLL_TO_SEGMENT", -1)

        if (bookTableName.isNullOrEmpty()) {
            Toast.makeText(this, "未指定书籍", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 记录当前书籍名称，用于收藏功能
        bookName = bookTableName

        // 顶部菜单栏（2026-09-29 按需求加回）：返回 / 目录 / 字体大小 / 搜索本书内容。
        // 栏内**没有书名** —— 2026-09-28 去掉的是「书名栏」（书名文字 + 旧返回按钮），
        // 本次加回的是纯功能入口，两件事不冲突。
        btnBack.setOnClickListener { finish() }

        // 目录按钮：可见/可用性由「目录数据 + 解析阶段」共同决定，见applyTocButtonState。
        // 这里只挂点击分发 —— 解析中也要能点（给 Toast 反馈），不能 setEnabled(false)，
        // 否则 Android 不会派发点击，用户按下去毫无反应，像是按钮坏了。
        btnToc.setOnClickListener {
            if (isStructureResolving) {
                Toast.makeText(this, "正在解析电子书，目录稍后就绪", Toast.LENGTH_SHORT).show()
            } else {
                showTocDialog()
            }
        }

        // 字体大小：只调阅读页正文字号（写 AppPrefs.reader_text_scale，由 LineAdapter 量正文）
        btnFontSize.setOnClickListener { showFontSizeDialog() }

        // 搜索本书内容：在内存里的全量正文行上匹配，点结果跳转并高亮
        btnSearch.setOnClickListener { showSearchDialog() }

        // 「点读」开关：切换单击语义（点英文单词 → 弹释义 + 朗读，不再播句子）。
        // 状态先从偏好回填再挂监听 —— 否则旋转重建后按钮两态会与实际行为不一致。
        pointReadEnabled = AppPrefs.isPointReadEnabled(this)
        applyPointReadButtonState()
        btnPointRead.setOnClickListener {
            pointReadEnabled = !pointReadEnabled
            AppPrefs.setPointReadEnabled(this, pointReadEnabled)
            applyPointReadButtonState()
            Toast.makeText(
                this,
                if (pointReadEnabled) "点读已开启：点英文单词可查词并朗读" else "点读已关闭",
                Toast.LENGTH_SHORT
            ).show()
            // ⚠️ 切换时必须停掉正在念的单词，否则会出现「关掉点读后声音还在继续」。
            if (!pointReadEnabled) {
                TextToSpeaker.stop()
                // 选中高亮也是点读的一部分：关掉之后那个词若还亮着，
                // 用户会以为还有别的点击语义没关掉。
                adapter.setPointReadHighlight(emptyMap())
            }
        }

        // #21：阅读页「实时朗读」开关。开 → 从当前可见行起逐行念；再点 → 停止。
        // 暂停/继续/停止走底部播放条（见 setupPlaybackControlBar 的分支）。
        btnReadAloudBar.setOnClickListener { toggleReadAloud() }

        // 常驻切栏把手（方案 D 主入口）：点它显隐两条栏。
        // 挂在按钮自身的 onClick 上、而不是全局手势 ⇒ 判定范围就是这个 24×48dp 的控件，
        // 正文的每一个像素都不会再被切栏逻辑碰到。
        btnChromeHandle.setOnClickListener { toggleReaderChrome() }
        // 拖动定位的 listener 由 setupChromeHandleDrag() 统一安装（它要接管整条手势流，
        // 抬手时再 performClick() 转发回上面这个 onClick）——这里不要重复 setOnTouchListener。

        // 逐段音色绑定入口（仅当本书挂载了服务端 TTS 任务时开放）
        if (serverTaskId > 0) {
            btnSegmentVoice.visibility = View.VISIBLE
            btnSegmentVoice.setOnClickListener { showSegmentVoiceDialog() }
        }

        viewModel = ViewModelProvider(this)[BookViewModel::class.java]
        // 本书有无音频 ⇒ 底部播放栏的按钮语义与显隐（见 [applyPlaybackBarForBookType]）。
        // ⚠️ 必须放在 viewModel 赋值**之后**：viewModel 是 lateinit 手动初始化（1376 行），
        // 早于它访问就是 UninitializedPropertyAccessException（2026-10-18 踩过，开机即崩）。
        // bookName 在上面已赋值，回调里按书名过滤 tasks 即可。
        viewModel.tasks.observe(this) { list ->
            val fromTasks = list.any { it.bookName == bookName && it.hasAudio }
            if (fromTasks) {
                bookHasAudio = true
            } else if (!storeOnline) {
                // 在线读的「有无音频」由 [onlineAudioAvailable] 决定（本地此刻可能一段未缓存、
                // tasks 里也无行），这里绝不能把它重置回 false，否则播放栏语义会被打回无音频。
                bookHasAudio = false
            }
            if (!bookHasAudio) {
                // 书城下载的书只写 audio_segments、不建 task 行 ⇒ tasks 里查不到。
                // 异步兜底：直接查 audio_segments 表，有片段就翻为 true。
                lifecycleScope.launch {
                    if (!bookHasAudio && viewModel.checkHasAudio(bookName)) {
                        bookHasAudio = true
                        applyPlaybackBarForBookType()
                    }
                }
            }
            applyPlaybackBarForBookType()
        }
        // 书城音频「边下边听」：每写入一段就 post 书名；这里收到后重查 audio_segments，
        // 把 bookHasAudio 翻为 true（覆盖"打开书时还没下完、下载过程中才到第一段"的场景）。
        viewModel.storeAudioArrived.observe(this) { arrivedBook ->
            if (arrivedBook == bookName && !bookHasAudio) {
                lifecycleScope.launch {
                    if (viewModel.checkHasAudio(bookName)) {
                        bookHasAudio = true
                        applyPlaybackBarForBookType()
                    }
                }
            }
        }
        // 书城「在线读」：本书是否有合成音频由服务端片段表决定（本地此刻可能一段都没缓存，
        // tasks 里也没有行）。收到 true 就把底部播放栏切成「有音频」语义 —— 点播放键会走
        // playFromFirstSegment → playLine 的在线分支，按需下载首段后起播。
        viewModel.onlineAudioAvailable.observe(this) { available ->
            if (storeOnline && available && !bookHasAudio) {
                bookHasAudio = true
                applyPlaybackBarForBookType()
            }
        }
        // 只在首次创建时登记：旋转后 ViewModel 存活，标记已被消费过，不该再登记一次
        if (savedInstanceState == null) {
            viewModel.requestScrollToLine(pendingScrollToLine, pendingScrollText)
            viewModel.requestScrollToSegment(pendingScrollToSegment)
        }

        adapter = LineAdapter(
            // 第二个参数是点击处在该行内的字符下标：阅读页显示的是原文，
            // 一行里可能压着好几个音频片段，靠它才能「点哪句播哪句」
            //
            // ⚠️ 点读开启时**同一个手势被换掉了语义**（不再播句子，改为点词查词+朗读），
            //   所以必须在这里分流，不能把两件事都塞进 ViewModel：
            //   「点读」要弹 BottomSheet + 调TTS，这些都只属于 Activity 的界面职责。
            onItemClick = { item, charOffsetInRow, position ->
                if (pointReadEnabled) {
                    handlePointRead(item, charOffsetInRow, position)
                } else if (!bookHasAudio) {
                    // 有合成音频的书：单击不做处理（播放由底栏控制，定位由长按「从此处读」）
                    // 无合成音频的书：单击触发系统 TTS 朗读该行
                    viewModel.playLine(item, charOffsetInRow)
                }
            },
            textActionModeProvider = textActionModeProvider
        )
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)
        // ⚠️ 必须在这里（adapter 建好之后）回填已存的正文字号，否则旋转/重进阅读页时
        //   正文会退回 16sp 基准 —— 局部缩放不像全局 fontScale 那样由 Context 自动携带，
        //   它只活在 Adapter 这一个字段里，Activity 重建就得重新喂一次。
        adapter.setTextScale(AppPrefs.getReaderTextScale(this))
        setupChromeToggle()
        setupChromeHandleDrag()
        // 恢复把手位置必须等首帧布局完成：[rv.top] 与把手高度此时才有值，
        // 直接调会拿到 0 而把 [clampHandleTop] 的 maxTop<=minTop 分支走掉。
        // 用 post 而不是 doOnLayout：与本文件既有的 [recyclerView.post] 同一写法，
        // 不为一个时机问题引入新的 import 依赖。
        recyclerView.post { applyChromeHandlePosition() }

        // v5/A4：旋转回来时把「正在朗读」的 UI 与回调接回来。
        // ReadAloudSpeaker 是进程级单例，朗读不会因为旋转中断；但 Activity 重建后
        // readAloudActive 归 false、按钮未选中、播放条消失，且单例手里还是旧实例的回调。
        restoreReadAloudAfterRecreate()

        viewModel.lines.observe(this) { list ->
            adapter.submitList(list)
            // 正文一到就撤掉加载层 —— 这正是「先显示文本」的那一步：
            // 剩下的位置映射/目录在后台继续建，不再遮挡已经可读的正文。
            hideReaderLoading()
            // ⚠️ 定位与自动续播**不能**在这里做：它们依赖 [BookViewModel] 的段号↔行号映射，
            //   而正文现在比映射先到（见 BookViewModel.loadEbookFromFile 的分阶段说明）。
            //   映射未就绪时 [viewModel.rowIndexOfSegment] 会退回「行号即段号」的旧口径，
            //   拿保存的段号当行下标 ⇒ 打开就跳到完全无关的一行。
            //   因此改由 [viewModel.phase] 的 StructureReady 触发，见 applyPendingNavigation。
        }

        // 阶段驱动：解析中显示顶部提示条；结构就绪后才做定位与续播（依赖映射）。
        viewModel.phase.observe(this) { phase ->
            // 目录可用性依赖阶段，必须在每次阶段变化时重算（解析中 → 灰；就绪 → 正常）
            isStructureResolving = phase != BookViewModel.Phase.StructureReady
            applyTocButtonState()
            when (phase) {
                BookViewModel.Phase.Resolving -> showReaderLoading()
                BookViewModel.Phase.ContentReady,
                BookViewModel.Phase.StructureReady -> {
                    hideReaderLoading()
                    // 正文与结构都可能先于本回调到达（LiveData 重放 / 旋转重建），
                    // 所以只在真正就绪的那一刻跑一次。
                    if (phase == BookViewModel.Phase.StructureReady) {
                        applyPendingNavigation()
                    }
                }
            }
        }

        // PDF 逐页解析进度：只更新提示文字（提示条此时正在显示，正文还没到）。
        // 大 PDF（1281 页 / 234 万字）本机提取要好几秒，页数在跳用户才知道没卡死。
        viewModel.parsePageProgress.observe(this) { progress ->
            if (::readerLoadingHint.isInitialized &&
                readerLoadingHint.visibility == View.VISIBLE
            ) {
                readerLoadingHint.text = describeParseProgress(progress)
            }
        }

        viewModel.currentPlayingPosition.observe(this) { position ->
            refreshPlayHighlight()
            if (position == -1) {
                // 播放位置清空（停止播放 / 换书）⇒ 跟随状态复位，按钮由
                // updateBackToReadPositionVisibility 自动收起（currentPlaybackRow 返回 -1）。
                audioUserScrolledAway = false
                updateBackToReadPositionVisibility()
                return@observe
            }
            // 滚动只看**段首行**：段起点比「字符重心行」更靠前，把可见范围往前留一点更稳。
            // ⚠️ 用户手动翻走（audioUserScrolledAway）时不得拽回：否则正在看的正文会被
            // 下一段的播放位置强行拉走，「回去」变成唯一出路却没有出口 —— 这正是本次
            // 补「回到播放处」按钮的原因，两者必须成对改。
            if (!audioUserScrolledAway) {
                followRow(position)
            }
            // 播放位置前进了：若它已在视口内（刚点完"回到播放处"），按钮就该收起。
            updateBackToReadPositionVisibility()
        }
        // 高亮必须单独观察区间表，不能只在位置回调里顺带刷新：
        // ① 同一行内换句时位置不变、区间变；② 片段跨行时位置也不变，但**覆盖的行集合**会变
        // （后一种就是本次修掉的「高亮行与实际阅读行错位」）。
        viewModel.currentPlayingSpans.observe(this) { refreshPlayHighlight() }
        viewModel.isPlaying.observe(this) { playing ->
            // 行内已无播放/暂停图标，isPlaying 只驱动底部控制栏的主按钮图标。
            // ⚠️ 朗读中不得覆盖：此刻播放键的图标归朗读状态管（暂停/继续），
            // 后台若有声书会话还在（跨书播放），它的 isPlaying 会把朗读的暂停图标抢掉。
            if (::btnPlayToggle.isInitialized && !readAloudActive) {
                btnPlayToggle.setImageResource(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
            }
        }

        // 底部播放控制栏：播放控制 / 睡眠定时 / 倍速
        setupPlaybackControlBar()

        // 目录按钮状态：目录数据（toc）与解析阶段（phase）任一变化都要重算。
        // 两者分开observe 会在「解析完成但目录为空」时出现中间态闪烁，故都走同一个入口。
        viewModel.toc.observe(this) { applyTocButtonState() }
        applyTocButtonState()

        // 打开失败（如书名含无法作为数据表标识的字符）时给出可见提示并退出，
        // 避免停在空白页让用户无从判断发生了什么
        viewModel.bookOpenError.observe(this) { message ->
            if (!message.isNullOrEmpty()) {
                Toast.makeText(this, "无法打开书籍：$message", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        // 加载内容只在**首次创建**时执行。
        //
        // 旋转屏幕、切换深色模式、语言变更等配置变更都会重走 onCreate，但 ViewModel 存活
        // （lines / toc 的数据都还在），重新加载纯属浪费。对 PDF 尤其严重：它走的是
        // `loadEbookFromFile` → 服务端解析 的路径（虽有本地缓存，但缓存未命中时仍是整本上传）。
        // 也就是说用户每转一次屏，整本书就可能重新上传一遍。
        if (savedInstanceState == null) {
            if (storeOnline && storeBookId > 0) {
                // 书城在线读：不下载电子书文件，正文按 bookId 在线取（带本地缓存）
                viewModel.loadEbookFromStoreOnline(storeBookId, bookTableName)
            } else if (!filePath.isNullOrEmpty()) {
                openEbookFromFile(filePath, bookTableName)
            } else {
                viewModel.openBook(Book(tableName = bookTableName, displayName = bookTableName))
                // 预置书籍（有 serverTaskId）尝试加载目录
                if (serverTaskId > 0) {
                    viewModel.loadTocForTask(serverTaskId)
                }
            }
        }
    }

    /**
     * 把 ViewModel 的播放状态一次性推给列表。
     *
     * 位置与区间表是**两个** LiveData（滚动用前者、高亮用后者），任一变化都要重推一次；
     * 这里集中取值，避免两处 observe 各拼一半参数、漏传导致高亮残留。
     */
    private fun refreshPlayHighlight() {
        adapter.updatePlayState(
            viewModel.currentPlayingPosition.value ?: -1,
            viewModel.currentPlayingSpans.value ?: emptyMap()
        )
    }

    /**
     * 目录按钮的唯一状态计算处（2026-10-10）。
     *
     * 三种状态，判据是**两个维度**的组合，不能只看 `toc` 是否为空：
     *
     * | 解析中 | 有目录 | 结果 |
     * |---|---|---|
     * | 是 | - | 可见 + **灰化**（alpha 0.4），点击弹「稍后就绪」 |
     * | 否 | 是 | 可见 + 正常，可点开目录 |
     * | 否 | 否 | GONE（解析完成仍没有目录 ⇒ 本来就没目录，不占顶栏位置） |
     *
     * ## 为什么解析中要「灰」而不是直接 GONE
     * 需求是「解析中不可用、解析结束可用」。若沿用旧逻辑（toc 空即隐藏），
     * 按钮会在解析完成那一刻凭空出现，顶栏会跳一下；而且用户完全看不出
     * 「这里本来会有个目录」。灰化既表达「有但用不了」，又不会让顶栏布局跳动。
     *
     * ## 为什么不用 setEnabled(false)
     * 禁用态控件**不派发点击** ⇒ 按下去毫无反应，用户会以为按钮坏了。
     * 保持 enabled、只灰化，点击时给 Toast 解释，才是可用的降级。
     */
    private fun applyTocButtonState() {
        if (!::btnToc.isInitialized) return
        val hasToc = !viewModel.toc.value.isNullOrEmpty()
        if (isStructureResolving) {
            btnToc.visibility = View.VISIBLE
            btnToc.alpha = TOC_DISABLED_ALPHA
        } else {
            btnToc.visibility = if (hasToc) View.VISIBLE else View.GONE
            btnToc.alpha = 1f
        }
    }

    private fun openEbookFromFile(filePath: String, displayName: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(this, "文件不存在: $filePath", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // loadEbookFromFile 内部会根据格式决定走服务端还是本地，并加载目录
        viewModel.loadEbookFromFile(filePath, displayName, serverTaskId)
    }

    /**
     * 打开「逐段音色绑定」对话框。
     * 复用 [showManagedDialog] 统一管理，避免对话框泄漏 Activity。
     */
    private fun showSegmentVoiceDialog() {
        if (serverTaskId <= 0) {
            Toast.makeText(this, "当前书籍没有可编辑的 TTS 任务", Toast.LENGTH_SHORT).show()
            return
        }
        showManagedDialog(SegmentVoiceDialog(this, serverTaskId))
    }

    /**
     * 正文**双击** → 切换顶部菜单栏与底部播放栏的显隐（方案 D 的第二入口）。
     *
     * ## 语义：双击 = 「播这句 + 显示工具栏」，不是一个纯切栏手势
     *
     * 第一击的抬起**不拦截**，所以行的 onClick 照常触发 ⇒ 该句开始播放；
     * 第二击才 return true 吃掉（子 View 收到 CANCEL，不会播第二遍）。
     * 于是双击的复合效果是「播这一句，同时把工具栏唤出来」——
     * 这两个意图不冲突（用户双击某句时，多半既想听它也想看进度），
     * 所以**不需要**为双击加防抖或延迟单击，那会牺牲「点哪句播哪句」的即时性。
     *
     * ## 为什么不用 GestureDetector
     *
     * `GestureDetector` 的双击要等第二击判定期满才回调，期间**单击也会被延迟**
     * （`onSingleTapConfirmed` 的存在就是为了这个）。阅读页的单击是最高频动作，
     * 为它加 280ms 延迟不划算。这里自己记两击的时间/位置，多写十几行换单击零延迟。
     *
     * ## 为什么用 OnItemTouchListener、而不是给 RecyclerView 加一层点击监听
     *
     * 正文行本身已经有「点哪句播哪句」的 onClick（[LineAdapter] 的 onItemClick），
     * 再铺一层点击必然二选一打架。这里只在**确认是双击的第二击**时才 return true；
     * 有位移（= 在滚动）、超时（= 长按选词）、单击一律 return false 放行，
     * 复制 / 收藏 / 翻页 / 点播全部照旧。
     */
    private fun setupChromeToggle() {
        resolveTapTimeoutMs()
        val slopPx = TAP_SLOP_DP * resources.displayMetrics.density
        val doubleSlopPx = DOUBLE_TAP_SLOP_DP * resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var moved = false
        // 上一击轻点的落点与时刻；用于判定「这一击是不是第二击」
        var lastTapX = 0f
        var lastTapY = 0f
        var lastTapAt = 0L

        recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        downAt = System.currentTimeMillis()
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.x - downX) > slopPx || abs(e.y - downY) > slopPx) moved = true
                    }
                    MotionEvent.ACTION_UP -> {
                        val now = System.currentTimeMillis()
                        if (moved || now - downAt >= tapTimeoutMs) {
                            // 滚动 / 长按：不参与双击判定，但要清掉上一击，
                            // 否则「点一下 → 长按选词 → 立刻点一下」会被拼成双击。
                            lastTapAt = 0L
                            return false
                        }
                        val isDoubleTap = now - lastTapAt <= DOUBLE_TAP_TIMEOUT_MS &&
                            abs(e.x - lastTapX) <= doubleSlopPx &&
                            abs(e.y - lastTapY) <= doubleSlopPx
                        if (isDoubleTap) {
                            toggleReaderChrome()
                            // 吃掉第二击：第一击已播过这句，这里再放行就会重播。
                            // 消费后必须清零，否则三击连点会连切两次栏。
                            lastTapAt = 0L
                            return true
                        }
                        lastTapX = e.x
                        lastTapY = e.y
                        lastTapAt = now
                    }
                    // 事件流被系统中断（来电、窗口失焦）后状态不可信，丢弃而不是继续拼双击
                    MotionEvent.ACTION_CANCEL -> lastTapAt = 0L
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    /**
     * 把手的拖动定位。
     *
     * ## 为什么点与拖要共用一个手势流
     *
     * 「点一下切栏」和「按住拖到别处」在触摸上是同一条流（DOWN→MOVE…→UP），
     * 必须在一个 listener 里用位移阈值分流。若拖动另起 `setOnTouchListener`，
     * 它与 `setOnClickListener` 的执行顺序依赖平台实现（onTouch 先于 onClick），
     * 一旦顺序反了就会出现「拖完顺便切一次栏」。所以这里 **onTouch 一律返回 true 吃掉
     * 全流程**，抬手时若判定为「没移动」就手动 `performClick()` 触发原 onClick。
     *
     * ## 为什么 `performClick()` 不能省
     *
     * 吃掉 DOWN 之后 View 不会再走自己的点击流程，`onClickListener` 不会被调用。
     * Android 要求可点击 View 在自己处理完触摸后显式调 `performClick()`
     * （无障碍服务据此朗读 contentDescription），省掉它 lint 会报
     * `ClickableViewAccessibility`。所以这里是「onTouch 独占 + 抬手转发」，
     * 不是「onTouch 和 onClick 并行」。
     *
     * ## 位移阈值取 [TAP_SLOP_DP] 而非更小的值
     *
     * 手抖和「按住想拖」在按下瞬间完全一样，只有移动后才分得开。
     * 12dp（@2.75x = 33px）是系统判定「这是滑动」的标准量级，用它能保证
     * 「按住不动想点」不会被误判成拖动。
     */
    private fun setupChromeHandleDrag() {
        val slopPx = TAP_SLOP_DP * resources.displayMetrics.density
        var downRawY = 0f
        var downTop = 0
        var dragging = false

        btnChromeHandle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    downTop = (view.layoutParams as FrameLayout.LayoutParams).topMargin
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(event.rawY - downRawY) > slopPx) dragging = true
                    if (dragging) {
                        val lp = view.layoutParams as FrameLayout.LayoutParams
                        lp.topMargin = clampHandleTop(downTop + (event.rawY - downRawY).toInt())
                        view.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        persistHandlePosition()
                    } else {
                        // 没移动 = 是一次点击，转交给 onClickListener（切栏）
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 被父容器或系统打断：回到按下时的位置，不落一个半途的位置
                    if (dragging) {
                        val lp = view.layoutParams as FrameLayout.LayoutParams
                        lp.topMargin = downTop
                        view.layoutParams = lp
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 把把手纵向位置夹到「正文区内」：上不越过正文顶、下不让把手底部越出正文底。
     *
     * 夹取依据是 [recyclerView] 的实时 bounds 而不是屏幕尺寸：
     * 顶部栏/播放栏显隐时 rv 的上下边界会变（隐藏后 rv 变高），
     * 按屏幕算会让把手有机会停在栏底下被遮住。
     */
    private fun clampHandleTop(desiredTop: Int): Int {
        val rv = recyclerView
        val minTop = rv.top
        val maxTop = rv.bottom - btnChromeHandle.height
        // rv 尚未布局（首帧）时 minTop>maxTop，先给 0 让它别跑出屏幕
        if (maxTop <= minTop) return 0
        return desiredTop.coerceIn(minTop, maxTop)
    }

    /**
     * 把当前纵向位置换算成比例存进偏好（见 [AppPrefs.getChromeHandleRatio] 的注释：
     * 存比例而非像素，屏高变化后仍然有效）。
     *
     * 存之前先重算一次范围再取比例，**不能**直接用旧范围算 ——
     * 拖动过程中上下边界不会变，所以此处等价；但夹取失败/首帧的 0 值要防一手，
     * 除法前判分母。
     */
    private fun persistHandlePosition() {
        val rv = recyclerView
        val minTop = rv.top
        val maxTop = rv.bottom - btnChromeHandle.height
        if (maxTop <= minTop) return
        val ratio = ((btnChromeHandle.top - minTop).toFloat() / (maxTop - minTop))
            .coerceIn(0f, 1f)
        AppPrefs.setChromeHandleRatio(this, ratio)
    }

    /**
     * 按保存的比例把把手摆回原位。在 onCreate 之后调（需要 rv 与把手都已有尺寸）。
     *
     * 恢复而不是每次都居中：拖动的意义就是「下次打开还在顺手的位置」。
     */
    private fun applyChromeHandlePosition() {
        val ratio = AppPrefs.getChromeHandleRatio(this)
        val rv = recyclerView
        val minTop = rv.top
        val maxTop = rv.bottom - btnChromeHandle.height
        if (maxTop <= minTop) return
        val lp = btnChromeHandle.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = (minTop + ratio * (maxTop - minTop)).toInt().coerceIn(minTop, maxTop)
        btnChromeHandle.layoutParams = lp
    }

    private fun toggleReaderChrome() {
        readerChromeVisible = !readerChromeVisible
        slideBar(readerTopBar, readerChromeVisible, toTop = true)
        slideBar(playbackControlBar, readerChromeVisible, toTop = false)
        // 栏是「动画结束才 GONE」（见 [slideBar]），rv 的上下边界在那时才变。
        // 把手是绝对定位的 marginTop，不会跟着重排 ⇒ 拖到靠近正文底/顶的位置时，
        // 切栏后可能落到栏外被遮住。在两条栏都落定后把把手夹回正文区。
        val settle = Runnable { clampHandleIntoContent() }
        playbackControlBar.removeCallbacks(settle)
        playbackControlBar.postDelayed(settle, CHROME_ANIM_MS + 50L)
    }

    /**
     * 把把手纵向夹回正文区内，并同步更新已保存的比例。
     *
     * 需要在**每次改变 rv 尺寸之后**调用：栏显隐（切栏）、旋转、进后台再回。
     * 只夹位置、不改用户设定的手感 —— 夹完存回去，下次开书还是在这个相对位置。
     */
    private fun clampHandleIntoContent() {
        if (isFinishing || isDestroyed) return
        val lp = btnChromeHandle.layoutParams as FrameLayout.LayoutParams
        val clamped = clampHandleTop(lp.topMargin)
        if (clamped == lp.topMargin) return
        lp.topMargin = clamped
        btnChromeHandle.layoutParams = lp
        persistHandlePosition()
    }

    /**
     * 把一条栏滑出 / 滑回。
     *
     * 隐藏时先平移出屏幕、**动画结束才 GONE**：只做平移会在原位留下一条空白，
     * GONE 之后正文才真正拿回这块高度（这也是隐藏的意义：多读几行）。
     * 显示时复用残留的 translationY（GONE 时它还在），VISIBLE 后从偏移处滑回 0。
     */
    private fun slideBar(bar: View, show: Boolean, toTop: Boolean) {
        bar.animate().cancel()
        if (show) {
            bar.visibility = View.VISIBLE
            bar.animate()
                .translationY(0f)
                .setDuration(CHROME_ANIM_MS)
                .setListener(null)
                .start()
        } else {
            val distance = if (toTop) -bar.height.toFloat() else bar.height.toFloat()
            bar.animate()
                .translationY(distance)
                .setDuration(CHROME_ANIM_MS)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        bar.visibility = View.GONE
                    }
                })
                .start()
        }
    }

    /**
     * 瞬时跳转到指定行，**不走平滑动画**。
     *
     * 原来用的是 smoothScrollToPosition：它走 LinearSmoothScroller，动画时长按滚动距离
     * **线性增长** —— 目标越远滑得越久。不管是点一个靠后的章节，还是从收藏页回到书末的
     * 收藏位置，用户都得眼睁睁看正文一路滑过去 ⇒ 改成直接定位。
     *
     * 用 scrollToPositionWithOffset(…, 0) 而不是 scrollToPosition：后者只保证目标"可见"，
     * 反向滚动时可能把它甩到屏幕底部；带 offset = 0 才能让目标行稳定落在视口顶端。
     */
    private fun jumpToPosition(position: Int) {
        if (position < 0) return
        val lm = recyclerView.layoutManager as? LinearLayoutManager
        if (lm != null) lm.scrollToPositionWithOffset(position, 0)
        else recyclerView.scrollToPosition(position)
    }

    /**
     * 跟随播放/朗读位置到 [position]：**近距离平滑，远距离瞬跳**。
     *
     * 原先两处跟随（有声书 `currentPlayingPosition`、朗读 `highlightReadLine`）都是无条件的
     * `smoothScrollToPosition`。近距离没问题，远距离就成了灾难 —— 最典型的是**点通知栏/
     * 锁屏的播放卡片回到阅读页**：那条 `contentIntent` 只带书名与文件路径、**不带定位行**
     * （见 PlaybackService.buildNotification），于是定位全靠这个回调。若正在听第 1979 段，
     * 正文会从第 1 行一路平滑滚到第 1979 行，用户看到屏幕往下翻好几秒，还以为卡住了。
     *
     * 判定见纯函数 [shouldJumpInsteadOfSmooth]（有单测）。
     */
    private fun followRow(position: Int) {
        if (position < 0) return
        val lm = recyclerView.layoutManager as? LinearLayoutManager
        val first = lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        val last = lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
        if (shouldJumpInsteadOfSmooth(position, first, last)) {
            // 远距离 / 视口未知：瞬移到位，不留滚动动画
            jumpToPosition(position)
        } else {
            recyclerView.smoothScrollToPosition(position)
        }
    }

    // ---------- 打开阶段：解析提示条与就绪后的定位 ----------

    /**
     * 显示解析提示条。
     *
     * 2026-10-10 起它是正文顶部的一条 2dp 细进度条，**不再遮挡正文、也不吞触摸**
     * （旧形态是盖住整个正文区的全屏遮罩，解析期间顶栏/底栏/目录按钮全点不了）。
     * 方法名沿用 showReaderLoading：它控制的View 没变，变的只是那个 View 的形态。
     */
    private fun showReaderLoading() {
        if (::readerLoadingOverlay.isInitialized) {
            readerLoadingOverlay.visibility = View.VISIBLE
        }
        // 文字提示与进度条同一时机显示：正文区此刻是空的，把「在做什么」写出来，
        // 页数由 [viewModel.parsePageProgress] 的观察者持续刷新。
        if (::readerLoadingHint.isInitialized) {
            readerLoadingHint.text = describeParseProgress(viewModel.parsePageProgress.value)
            readerLoadingHint.visibility = View.VISIBLE
        }
    }

    /**
     * 隐藏解析提示条。
     *
     * 正文与结构是两阶段到达的，因此两个回调点都要调：
     * 正文一 post 就先撤掉（用户马上能读），结构就绪时再兜一次
     * （解析失败走 [BookViewModel.postOpenError] 时不会有正文回调，只有结构阶段）。
     */
    private fun hideReaderLoading() {
        if (::readerLoadingOverlay.isInitialized) {
            readerLoadingOverlay.visibility = View.GONE
        }
        if (::readerLoadingHint.isInitialized) {
            readerLoadingHint.visibility = View.GONE
        }
    }

    /**
     * 解析进度的展示文案。
     *
     * 拿不到页数（还没开始逐页解析 / 非 PDF / PDF 走服务端那条路）时给一句概括，
     * 不要显示"第 0 / 0 页"这种更让人以为出错的东西。
     */
    private fun describeParseProgress(progress: Pair<Int, Int>?): String {
        val (done, total) = progress ?: return "正在解析电子书…"
        return if (total > 0) "正在解析 PDF… 第 $done / $total 页" else "正在解析电子书…"
    }

    /**
     * 结构就绪后执行「一次性」的后续动作：收藏定位 + 全自动续播。
     *
     * ## 为什么必须等 [BookViewModel.Phase.StructureReady]
     * 两条路径都依赖**段号 ↔ 行号映射**（[BookViewModel.rowIndexOfSegment]）：
     * 进度里存的是段号，而阅读页显示的是按原文分行的列表。
     * 映射未建好时它会退回「行号即段号」的旧口径，把段号当行下标用 ⇒
     * 打开就跳到完全无关的一行，或者续播到错误的音频段。
     * 正文先显示之后，这个窗口是真实存在的，因此触发点必须挂在结构阶段上。
     *
     * 幂等性由 ViewModel 的一次性标记保证（`consumeScrollToLine` / `markAutoResumeDone`）：
     * 旋转导致 LiveData 重放时它们已经作废，不会重复定位或重复起播。
     */
    private fun applyPendingNavigation() {
        val list = viewModel.lines.value.orEmpty()
        if (list.isEmpty()) return

        // ① 音频段号（点通知卡片回阅读页）：语义最明确，**优先于**下面的三层兜底。
        //    段号→行号必须走 rowIndexOfSegment，绝不能塞进 targetLine 那条链 ——
        //    那条链第二层是「行号直配」，段号会误命中 lineNumber 相同的行。
        val seg = viewModel.consumeScrollToSegment()
        if (seg > 0) {
            val pos = viewModel.rowIndexOfSegment(seg)
            if (pos >= 0) {
                // 瞬时定位：这是"回到正在听的地方"，不该让用户看滚动动画。
                // post 等列表完成本次布局（与下方收藏定位同一口径）。
                recyclerView.post { jumpToPosition(pos) }
                // 已定位 → 作废自动续播，避免重复起播（正在播时下面本来也会跳过）
                viewModel.markAutoResumeDone()
                return
            }
            // 换算不出（结构还没就绪/段号越界）→ 不拦路，落到下面的逻辑；
            // currentPlayingPosition 的回调随后也会把画面带到位（followRow）。
        }

        // 从收藏页跳转：内容加载完成后自动定位到目标段落（标记为一次性，取走即作废）
        val targetLine = viewModel.consumeScrollToLine()
        val anchorText = viewModel.consumeScrollText()
        if (targetLine > 0 || !anchorText.isNullOrEmpty()) {
            // 定位顺序（**文本优先**）：
            //   ① 收藏时选中的文字 —— 行号会随切分粒度变化而漂移，文字不会；
            //   ② 行号直配 —— 老收藏或文本匹配失败时的兜底；
            //   ③ 「段号 → 行号」换算一次 —— 更老的版本里阅读页显示的是片段文本，
            //      那时存下来的其实是段号。
            var targetPos = viewModel.rowIndexByAnchor(anchorText, targetLine)
            if (targetPos < 0) targetPos = list.indexOfFirst { it.lineNumber == targetLine }
            if (targetPos < 0) targetPos = viewModel.rowIndexOfSegment(targetLine)
            if (targetPos >= 0) {
                // 与点目录章节同一口径：瞬时定位（原来用 smoothScrollToPosition，
                // 收藏在书末时要看着正文一路滑过去）。放 post 里等列表完成本次布局再滚。
                recyclerView.post { jumpToPosition(targetPos) }
            }
            // 已定位：同时作废自动续播，避免重复起播
            viewModel.markAutoResumeDone()
            return
        }

        if (viewModel.isAutoResumeDone()) return
        // 全自动续播：打开书即从上次段落 + 段内偏移自动起播，无需用户点击。
        // 跳过该书正在后台播放的情况（从通知栏回到阅读页，服务回调直接高亮当前段）。
        viewModel.markAutoResumeDone()
        if (viewModel.isPlayingBook(bookName)) return

        // 续播执行：优先用合并后的进度（段号命中才真正起播）
        val resume: (Pair<Int, Int>?) -> Unit = { progress ->
            progress?.let { (segment, offsetMs) ->
                // 进度里存的是段号，而列表是按原文分行的 ⇒ 必须换算成行下标，
                // 否则「行号当段号」会续播到一段无关的音频
                val targetPos = viewModel.rowIndexOfSegment(segment)
                if (targetPos >= 0) {
                    recyclerView.scrollToPosition(targetPos)
                    viewModel.playFromProgress(list[targetPos], segment, offsetMs)
                }
            }
        }
        // 跨端身份：书城书用 s:{bookId}，有服务端任务的用 t:{taskId}，本地书用 n:{归一化书名}。
        // 与 PC/Web 同一套口径（CoverageCodec.bookKey），三端才能认出同一本书。
        val bookKey = viewModel.serverBookKey(
            if (storeBookId > 0) storeBookId else null,
            if (serverTaskId > 0) serverTaskId else null,
            bookName
        )
        if (bookKey.isNotEmpty()) {
            // 跨端同步：拉取服务端进度并与本地合并（取更靠后的一份）后再续播。
            // 回调运行在 IO 线程，需切回主线程操作 UI；并防 Activity 已销毁。
            viewModel.resolveResumeProgressByKey(bookName, bookKey) { progress ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    resume(progress)
                }
            }
        } else {
            resume(viewModel.getSavedProgress(bookName))
        }
    }

    // ---------- 字体大小 ----------

    /**
     * 阅读页正文字号：80%~160%（默认 100%）。
     *
     * ## 作用域
     *
     * 只影响**阅读页电子书正文**（[LineAdapter] 量的 `item_line` 那个 TextView），
     * 顶部菜单栏、底部播放栏、目录/搜索/查词弹窗、以及其他页面全部保持系统字号不变。
     * 早期实现是写 [BaseActivity] 的 `Configuration.fontScale`（全局），会把全 App 的
     * sp 文本一起缩放，与需求相反，已随 [BaseActivity] 一并移除。
     *
     * 因此**这里不再需要 recreate()** —— 改完直接 `adapter.setTextScale()` 重绘正文即可，
     * 顺带解决了 recreate 会白闪一次、且把滚动位置重置回去的老问题。
     *
     * ## 两种调节方式
     *
     * · 左侧小 A / 右侧大 A：**每次 ±5 个百分点**（范围两端夹紧）；
     * · 中间 SeekBar：连续拖动精调。
     * 两者共用一个 [percent]，点 A 之后 SeekBar 位置会跟着走，反之亦然。
     * 拖动/点击过程只更新预览行与百分比文案，点「确定」才落到正文上。
     */
    private fun showFontSizeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_font_size, null)
        val seek = view.findViewById<SeekBar>(R.id.seekFontSize)
        val tvPreview = view.findViewById<TextView>(R.id.tvFontPreview)
        val tvValue = view.findViewById<TextView>(R.id.tvFontScaleValue)
        val btnSmaller = view.findViewById<TextView>(R.id.btnFontSmaller)
        val btnLarger = view.findViewById<TextView>(R.id.btnFontLarger)

        val current = (AppPrefs.getReaderTextScale(this) * 100).toInt()
            .coerceIn(MIN_FONT_PERCENT, MAX_FONT_PERCENT)

        // percent 用一个可变整数持有，三个入口（小 A / 大 A / SeekBar）都改它——
        // 否则点过 A 之后再拖进度条时基线会取错。
        var percent = current

        val applyPreview = { p: Int ->
            percent = p
            tvValue.text = "$p%"
            tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREVIEW_TEXT_SP * p / 100f)
        }

        // SeekBar 的 max = 80、进度 0 对应下限：把 80% 当 0 用，回调里就不必再做减法换算
        seek.progress = current - MIN_FONT_PERCENT
        applyPreview(current)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyPreview(progress + MIN_FONT_PERCENT)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        // 小 A / 大 A：各 ±5 个百分点。点完后同步 SeekBar 位置——
        // 不同步的话滑块会停在上一次的刻度，与右侧百分比对不上，
        // 用户接着拖一下会突然跳 5 个点。
        btnSmaller.setOnClickListener {
            val next = (percent - FONT_STEP_PERCENT).coerceAtLeast(MIN_FONT_PERCENT)
            applyPreview(next)
            seek.progress = next - MIN_FONT_PERCENT
        }
        btnLarger.setOnClickListener {
            val next = (percent + FONT_STEP_PERCENT).coerceAtMost(MAX_FONT_PERCENT)
            applyPreview(next)
            seek.progress = next - MIN_FONT_PERCENT
        }

        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("字体大小")
                .setView(view)
                .setPositiveButton("确定") { _, _ ->
                    // 局部缩放不需要重建 Activity，直接让 adapter 重量正文字号即可
                    if (percent != current) {
                        AppPrefs.setReaderTextScale(this, percent / 100f)
                        adapter.setTextScale(percent / 100f)
                    }
                }
                .setNegativeButton("取消", null)
                .create()
        )
    }

    // ---------- 搜索本书内容 ----------

    /**
     * 搜索本书内容。
     *
     * 数据源是**已经在内存里**的全量正文行（[BookViewModel.lines]，打开书时整本已解析完成），
     * 所以不访问网络、不落库 —— 只是遍历一次 List，几万行也是毫秒级。
     * 输入即搜；点结果 → 瞬时定位 + 高亮命中区间 → 关掉对话框
     * （接下来用户要看的是正文，不是列表）。
     */
    private fun showSearchDialog() {
        val rows = viewModel.lines.value.orEmpty()
        if (rows.isEmpty()) {
            Toast.makeText(this, "正文尚未加载完成", Toast.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_search_book, null)
        val etKeyword = view.findViewById<EditText>(R.id.etSearchKeyword)
        val tvSummary = view.findViewById<TextView>(R.id.tvSearchSummary)
        val tvEmpty = view.findViewById<TextView>(R.id.tvSearchEmpty)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerViewSearchResult)

        // 结果回调里要 dismiss，而对话框对象在回调注册之后才创建 ⇒ 用可空引用转一手
        var dialogRef: Dialog? = null
        val hitAdapter = SearchHitAdapter { hit ->
            jumpToPosition(hit.rowIndex)
            // 区间用左闭右闭（IntRange），与 LineAdapter 的 playingSpans 同语义
            adapter.setSearchHighlight(
                mapOf(hit.rowIndex to IntRange(hit.matchStart, hit.matchEnd - 1))
            )
            dialogRef?.dismiss()
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = hitAdapter

        etKeyword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString().orEmpty().trim()
                val found = if (keyword.isEmpty()) emptyList() else searchInBook(rows, keyword)
                // searchInBook 可能多返回一条（「还有更多」的证据），只显示前 MAX 条
                val truncated = found.size > MAX_SEARCH_HITS
                val hits = if (truncated) found.take(MAX_SEARCH_HITS) else found
                hitAdapter.submitList(hits)
                if (keyword.isEmpty()) {
                    tvEmpty.text = "输入关键词开始搜索"
                    tvEmpty.visibility = View.VISIBLE
                    tvSummary.text = ""
                } else {
                    tvEmpty.text = "没有找到「$keyword」"
                    tvEmpty.visibility = if (hits.isEmpty()) View.VISIBLE else View.GONE
                    // 截断时**不报总数**：真实总数没数过，报出来只能是上限值本身
                    tvSummary.text = if (truncated) {
                        "已显示前 $MAX_SEARCH_HITS 处匹配，还有更多"
                    } else {
                        "共 ${hits.size} 处匹配"
                    }
                }
            }
        })
        // 键盘上的「搜索」键只收键盘、不关对话框 —— 用户多半还要看结果列表
        etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(etKeyword.windowToken, 0)
                true
            } else {
                false
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("搜索本书内容")
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()
        dialogRef = dialog
        showManagedDialog(dialog)
        // 进对话框就是为了打字：自动聚焦 + 弹键盘
        etKeyword.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    /**
     * 在正文行里找关键词（大小写不敏感），同一行可能命中多处。
     *
     * ⚠️ 最多返回 [MAX_SEARCH_HITS] **+ 1** 条：多收的那一条只用来判断「是否还有更多」，
     * 由调用方丢掉。少收一条的话，「收满上限」与「恰好只有上限条」两种情形完全无法区分，
     * 只好把总数写成上限值 —— 用户会看到「共 200 处匹配（只显示前 200 处）」
     * 这种自相矛盾、而且总数是编造的文案。
     *
     * 每条结果都带上**所属章节标题**：只给「第 N 行」的话，在一本上千行的书里
     * 用户没法判断这条命中离自己读到的地方有多远、属于哪一部分。
     */
    private fun searchInBook(rows: List<LineItem>, keyword: String): List<SearchHit> {
        // 目录索引建一次、全部命中复用：逐条调 buildChapterIndex 会把 O(目录项)
        // 的排序重复上千遍。
        val chapters = buildChapterIndex(viewModel.toc.value.orEmpty())
        val hits = ArrayList<SearchHit>()
        for ((index, row) in rows.withIndex()) {
            var from = 0
            while (true) {
                val at = row.text.indexOf(keyword, from, ignoreCase = true)
                if (at < 0) break
                hits.add(
                    SearchHit(
                        rowIndex = index,
                        lineNumber = row.lineNumber,
                        lineText = row.text,
                        matchStart = at,
                        matchEnd = at + keyword.length,
                        chapter = chapterTitleAt(chapters, index)
                    )
                )
                // 收到「上限 + 1」才停：这一条就是「还有更多」的证据
                if (hits.size > MAX_SEARCH_HITS) return hits
                from = at + keyword.length
            }
        }
        return hits
    }

    private fun showTocDialog() {
        val tocList = viewModel.toc.value ?: return
        if (tocList.isEmpty()) {
            Toast.makeText(this, "暂无目录", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_toc)

        val recyclerViewToc = dialog.findViewById<RecyclerView>(R.id.recyclerViewToc)
        recyclerViewToc.layoutManager = LinearLayoutManager(this)
        val tocAdapter = TocListAdapter { entry ->
            // 点击目录项，滚动到对应位置。
            // 目录项可能带「段号」（TTS 任务、或由本地换算补上的段号），也可能只有「行下标」：
            // 前者要经「段号 → 行号」换算（阅读页显示的是原文，段号与行号不是一回事），
            // 后者本身就是行下标，直接用。
            val targetPos = when {
                entry.segmentNumber > 0 -> {
                    val row = viewModel.rowIndexOfSegment(entry.segmentNumber)
                    if (row >= 0) row else entry.paragraphIndex
                }
                else -> entry.paragraphIndex
            }
            if (targetPos >= 0) {
                jumpToPosition(targetPos)
            }
            dialog.dismiss()
        }
        recyclerViewToc.adapter = tocAdapter
        tocAdapter.submitList(tocList)

        // 目录面板做成**贴屏幕左侧的侧栏**：
        //   · 宽度 = 屏宽 80%，高度撑满 —— 让目录占住左侧一条，而不是居中悬空的小窗；
        //   · gravity 用 START（LTR 下即"左"，RTL 自动镜像），配合 TOP 从顶部开始铺；
        //   · 窗口背景置透明：窗口默认背景自带 inset，全高时会在面板边缘留一圈缝，
        //     面板底色改由 dialog_toc.xml 的根布局提供（见该文件注释）。
        dialog.window?.apply {
            setGravity(Gravity.START or Gravity.TOP)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.8f).toInt(),
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        showManagedDialog(dialog)
    }

    /**
     * 统一登记并显示对话框。
     * 见 [activeDialog] 的注释：对话框强引用 Activity，不随生命周期销毁就会泄漏整页。
     */
    private fun showManagedDialog(dialog: Dialog) {
        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.setOnDismissListener {
            // 只在仍是当前对话框时清空，避免新对话框把它自己的字段误清成 null
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()
    }

    // ---------- 底部播放控制栏 ----------

    /**
     * 上一段/播放暂停/下一段 + 睡眠定时 + 倍速。
     * 有音频书：三个键均作用于 PlaybackService 当前会话，与通知栏控件行为一致。
     * 无音频书：播放键启停系统 TTS 朗读，后退/前进按「段」跳转（跳转单位与自动播放相同）。
     */
    private fun setupPlaybackControlBar() {
        // #21：朗读进行中时，底部播放条改作朗读控制（暂停/继续 + 段落跳转）。
        // 2026-10-18：朗读启动入口从顶栏迁到本按钮 —— 未朗读时按本书有无音频分流：
        //  · 有音频 ⇒ 播/设有声书（原语义不变）；
        //  · 无音频 ⇒ 点它就是开朗读（从当前可见段起），后退/前进从上一段/下一段起朗读。
        // 2026-10-20：打开书籍后「直接点」播放键 = 从第一句起播，而不是空操作：
        //  · 有音频 ⇒ 本书已有活动会话才走暂停/继续（togglePlayPause）；无会话时
        //    togglePlayPause 是空转，改走 playFromFirstSegment() 从第一段起播合成音频；
        //  · 无音频 ⇒ 从全书第一行（index 0）起 TTS，而不是当前可见行。
        btnPlayToggle.setOnClickListener {
            when {
                readAloudActive -> {
                    if (ReadAloudSpeaker.isPaused) {
                        ReadAloudSpeaker.resume()
                        btnPlayToggle.setImageResource(android.R.drawable.ic_media_pause)
                    } else {
                        ReadAloudSpeaker.pause()
                        btnPlayToggle.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
                bookHasAudio -> {
                    if (viewModel.isPlayingBook(bookName)) {
                        viewModel.togglePlayPause()
                    } else {
                        // 从头起播是一次**新会话**：把上一轮的"用户已翻走"清掉，
                        // 否则新播放不会跟随，用户会以为自动跟随坏了。
                        audioUserScrolledAway = false
                        viewModel.playFromFirstSegment()
                    }
                }
                else -> startReadAloudFrom(0)
            }
        }
        // 后退/前进（媒体三联键，两类书都常驻）：
        //  · 朗读中 ⇒ 上一段/下一段跳转（一次一段，同自动播放的推进单位）；
        //  · 未朗读 + 有音频 ⇒ 跳音频段（PlaybackService）；
        //  · 未朗读 + 无音频 ⇒ 从上一段/下一段起开朗读（可见行 ± 1）。
        //
        // ⚠️ 有声书跳段前必须复位 audioUserScrolledAway：跳段是**显式**要求画面跟着走
        // 的动作（用户就是要看下一段在哪儿）。若保留"用户已翻走"标记，新位置不会滚入
        // 视口，按钮还得再点一次才看得见内容。
        btnPlayPrev.setOnClickListener {
            when {
                readAloudActive -> jumpReadAloud(-1)
                bookHasAudio -> {
                    audioUserScrolledAway = false
                    viewModel.skipPrevious()
                }
                else -> startReadAloudFrom(firstVisibleLine() - 1)
            }
        }
        btnPlayNext.setOnClickListener {
            when {
                readAloudActive -> jumpReadAloud(1)
                bookHasAudio -> {
                    audioUserScrolledAway = false
                    viewModel.skipNext()
                }
                else -> startReadAloudFrom(firstVisibleLine() + 1)
            }
        }
        btnSpeed.setOnClickListener { showSpeedDialog() }
        btnSleepTimer.setOnClickListener { showSleepTimerDialog() }
        btnBackToReadPosition.setOnClickListener { backToPlaybackPosition() }
        // 按钮上只剩一枚准星图标（见 activity_book_content.xml 的注释），文案降级为**长按提示**：
        // TooltipCompat 在 API 26+ 走系统 tooltip、以下用 AppCompat 自绘的小黑条，
        // 两档都在按钮正上方弹出，不额外占用底栏宽度。
        // ⚠️ 别再给这个按钮挂 OnLongClickListener —— TooltipCompat 用的就是 long-click 通道，
        // 一旦有人占用，长按提示会静默失效（不报错，最难查的那种）。
        // 重复调用无害：setupPlaybackControlBar 每次重排底栏都会走一遍。
        TooltipCompat.setTooltipText(btnBackToReadPosition, "回到播放处")

        // ⭐ 「手动翻页」的判据：只有**手指拖动**（DRAGGING）才算用户主动翻页。
        // 程序性滚动（jumpToPosition / smoothScrollToPosition / 目录跳转 / 跳转高亮行）
        // 走的都是 SETTLING 或 IDLE，不会命中这个分支 —— 否则自动跟读自己造成的滚动
        // 会被误判成"用户翻走"，自动跟随会莫名其妙地卡住不动。
        //
        // 2026-10-30：有声书播放同样纳入（此前只有朗读置位）。否则用户滚到别处看内容，
        // 下一段的 currentPlayingPosition 回调立刻把正文拽回播放位置 —— 正在看的与
        // 正在听的互相打架。
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                super.onScrollStateChanged(rv, newState)
                if (newState != RecyclerView.SCROLL_STATE_DRAGGING) return
                if (readAloudActive) {
                    readAloudUserScrolledAway = true
                } else if ((viewModel.currentPlayingPosition.value ?: -1) >= 0) {
                    // ⚠️ 用「有播放位置」而不是「isPlaying」：暂停时滚走看别处，
                    // 恢复播放同样不该把屏幕拽回去。判据是"用户离开了播放位置"，
                    // 与此刻是否正在出声无关。
                    audioUserScrolledAway = true
                }
            }

            // 「回到播放处」按需显隐：滚动发生后视口就变了，重新判定一次。
            // ⚠️ 用 onScrolled（而非在 onScrollStateChanged 里判 IDLE）：前者只在
            // **真的滚动了**才回调，能天然避开"拖到一半"的中间态；后者在 DRAGGING
            // 转 SETTLING 的瞬间就触发，那一刻视口还没停稳，判出来的结果会闪。
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                updateBackToReadPositionVisibility()
                // 纯阅读（无音频）时记录「读到哪」：进度原先只在播放时更新，
                // 没有音频的书永远记不住位置（换端打开只能从头开始）。
                scheduleReadingPositionSave()
            }
        })

        // API 23 以下无 MediaPlayer 变速能力：禁用入口
        if (!viewModel.isSpeedSupported()) {
            btnSpeed.isEnabled = false
            btnSpeed.alpha = 0.4f
        }

        // 倍速文字（服务侧持久化，初始值在服务连接时同步）
        viewModel.playbackSpeed.observe(this) { speed ->
            btnSpeed.text = formatSpeed(speed)
        }

        // 睡眠定时状态：>0 倒计时剩余(ms)；-1 「播完本段」已开启；
        // <= -1000 段数模式（剩余段数 = -value - 1000）；0 关闭
        viewModel.sleepTimer.observe(this) { value ->
            btnSleepTimer.text = when {
                value > 0 -> formatCountdown(value)
                value == -1L -> "播完本段"
                value <= -1000L -> "剩 ${(-value) - 1000} 段"
                else -> "定时"
            }
        }
    }

    /** 倍速选择：档位与 Web 播放器 RATE_OPTIONS 对齐（0.75/1/1.25/1.5/2） */
    private fun showSpeedDialog() {
        val speeds = PlaybackConfig.SPEED_OPTIONS
        val labels = speeds.map { formatSpeed(it) }.toTypedArray()
        val current = viewModel.playbackSpeed.value ?: PlaybackConfig.DEFAULT_SPEED
        val checked = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.001f }
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    viewModel.setPlaybackSpeed(speeds[which])
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .create()
        )
    }

    /** 睡眠定时：关闭 / 15·30·60 分钟 / 播完本段 / 1·3·5 章后暂停 */
    private fun showSleepTimerDialog() {
        val labels = arrayOf(
            "关闭", "15 分钟", "30 分钟", "60 分钟",
            "播完本段", "1 章后", "3 章后", "5 章后"
        )
        val modes = intArrayOf(
            PlaybackService.SLEEP_TIMER_OFF,
            15, 30, 60,
            PlaybackService.SLEEP_TIMER_END_OF_SEGMENT,
            PlaybackService.SLEEP_TIMER_SEGMENTS,
            PlaybackService.SLEEP_TIMER_SEGMENTS,
            PlaybackService.SLEEP_TIMER_SEGMENTS
        )
        // 段数模式下每段对应的目标段数（非段数模式为 0）
        val segmentCounts = intArrayOf(0, 0, 0, 0, 0, 1, 3, 5)
        val currentMode = viewModel.getSleepTimerMode()
        // 段数模式在 modes 里重复出现三次（1/3/5 章），indexOf 只会命中第一个 ——
        // 于是真实设为 3 或 5 章时，对话框仍勾选「1 章后」，用户据此改错设置。
        // 段数模式改按「目标段数」反查下标。
        val checked = if (currentMode == PlaybackService.SLEEP_TIMER_SEGMENTS) {
            segmentCounts.indexOf(viewModel.getSleepTimerSegments())
        } else {
            modes.indexOf(currentMode)
        }.let { if (it < 0) 0 else it }
        showManagedDialog(
            AlertDialog.Builder(this)
                .setTitle("睡眠定时")
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    if (modes[which] == PlaybackService.SLEEP_TIMER_SEGMENTS) {
                        viewModel.setSleepTimerSegments(segmentCounts[which])
                    } else {
                        viewModel.setSleepTimer(modes[which])
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .create()
        )
    }

    /** 倍速显示：整倍速保留一位小数（1.0x/2.0x），其余按原值（0.75x/1.25x/1.5x） */
    private fun formatSpeed(speed: Float): String =
        if (speed == speed.toLong().toFloat()) {
            String.format(java.util.Locale.US, "%.1fx", speed)
        } else {
            "${speed}x"
        }

    /** 定时倒计时显示：mm:ss（超过 1 小时则 h:mm:ss），向上取整保证刚设置时显示完整时长 */
    private fun formatCountdown(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", m, s)
        }
    }

    override fun onDestroy() {
        // 必须在 super.onDestroy() **之前** dismiss。
        // 对话框由 WindowManager 持有并强引用本 Activity，晚一步 dismiss
        // 就会让旧 Activity 连同整页视图一起泄漏到下一次 GC 之后。
        activeDialog?.dismiss()
        activeDialog = null
        // 兜底选区的自绘手柄挂在 decorView 上，同样强引用本 Activity，一并撤掉
        fallbackHandles.detach()

        super.onDestroy()
        // 旋转屏幕（!isFinishing）不打断播放：ViewModel 跨配置变更存活，播放继续。
        // 真正退出页面时：只落盘续听进度，不再停止播放——
        // 播放已迁移到前台 PlaybackService，锁屏/切后台/退出页面均继续收听，
        // 由通知栏控件负责暂停/停止。
        if (isFinishing) {
            viewModel.savePlaybackProgress()
            // ⚠️ 只在**真正退出**时停朗读，旋转不打断（与上面播放的处理一致）：
            //   单词朗读是秒级的瞬时动作，不是长音频，不存在「锁屏继续听」的需求；
            //   离开页面后还在念会被用户当成后台异常。TextToSpeaker 是进程级单例，
            //   这里**只 stop 不 shutdown** —— shutdown 会让下次进页面重新绑定引擎。
            if (pointReadEnabled) TextToSpeaker.stop()
            // #21：退出页面时停掉正在念的朗读（与上面处理一致：旋转不打断，真正退出才停）。
            // v5/A4：判据从本地 readAloudActive 改成单例的 isActive —— 旋转后本地标志
            // 可能是 false，但单例其实还在念；用本地标志会导致"退出页面了声音还在"。
            if (ReadAloudSpeaker.isActive) ReadAloudSpeaker.stop()
        } else {
            // v5/A4：旋转（非退出）**无条件解绑回调**。
            // 不解绑的话，单例会一直持有已销毁 Activity 的 lambda（整页 View 树泄漏），
            // 且新实例永远收不到逐行进度。新实例的 onCreate 会重新 rebind。
            ReadAloudSpeaker.detachCallbacks()
        }
    }
}