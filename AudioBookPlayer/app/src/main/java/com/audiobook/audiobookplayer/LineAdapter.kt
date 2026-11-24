package com.audiobook.audiobookplayer

import android.annotation.SuppressLint
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.LineItem
import kotlin.math.abs

/**
 * 文本选择操作菜单的创建者：由 BookContentActivity 提供，用于在选中文本时
 * 在操作菜单中追加"复制""收藏"等自定义项。
 */
interface TextActionModeProvider {
    /** 返回一个 ActionMode.Callback；textView 用于获取选区文本，item 提供 lineNumber */
    fun getActionModeCallback(textView: TextView, item: LineItem): ActionMode.Callback

    /**
     * ⭐ 兜底选词（2026-10-14 方案 A）：系统选词器对某些行**静默失败**时由宿主接管。
     *
     * 真机定案（Redmi K30 Pro / MIUI）：OEM 自研中文选词器对约四成行长按/双击
     * **完全无声地失败**（无选区、无菜单、无日志；反射证实 MIUI 重写过 Editor，
     * AOSP 源码分析对其不适用）。应用层无法修复，只能兜底。
     *
     * 由 [LineAdapter] 在「确认长按手势结束且系统未建选区」时调用：
     * 宿主自建落点处的选区（中文单字 / 英文整词）并弹出**与系统成功路径
     * 完全相同的浮动工具栏菜单**（复制/收藏/翻译/查字典）—— 不是曾经的
     * TextActionSheet 底部弹窗，交互仍是单一路径。
     *
     * @param charOffset 长按落点在行文本内的字符下标（已由调用方校验非负）
     * @return true = 已接管（建了选区并起了菜单）
     */
    fun startFallbackSelection(textView: TextView, item: LineItem, charOffset: Int): Boolean
}

class LineAdapter(
    /**
     * 点击某行。
     *
     * @param item           被点击的行
     * @param charOffset     点击处在该行文本内的字符下标（-1 = 只知道点在这一行上）。
     *   阅读页显示的是电子书原文，一行里常常压着好几个音频片段，
     *   只有拿到这个下标才能做到「点哪句播哪句」。
     * @param position       该行在列表里的**当前下标**（点读画高亮要按行定位；
     *   刻意现取现用而不缓存 —— RecyclerView 的下标会随增删移动，缓存必然过期）。
     */
    private val onItemClick: (LineItem, Int, Int) -> Unit,
    private val textActionModeProvider: TextActionModeProvider? = null
) : ListAdapter<LineItem, LineAdapter.ViewHolder>(LineDiffCallback()) {

        private var playingPosition: Int = -1

    /**
     * 正在播放的片段在**它覆盖的每一行**内的字符区间（行下标 → 左闭右开区间）。
     * 键集合同时就是「该点亮整行底色的行」。
     */
    private var playingSpans: Map<Int, IntRange> = emptyMap()

    /** 当前亮着整行底色的行（= [playingSpans] 的键，另加 [playingPosition] 兜底） */
    private var playingRows: Set<Int> = emptySet()

    /**
     * 书内搜索命中的区间（行下标 → 行内**闭**区间，与 [playingSpans] 同语义）。
     *
     * 与播放高亮**独立共存**：一边听书一边搜同一个词时两种底色叠加，互不覆盖
     * （颜色也不同：句内 = @color/highlight_sentence，搜索 = @color/highlight_search）。
     * 跳转定位由 BookContentActivity 负责，这里只管画。
     */
    private var searchSpans: Map<Int, IntRange> = emptyMap()

    /**
     * 「点读」模式下被点中的那个单词（行下标 → 行内**闭**区间，与 [playingSpans] 同语义）。
     *
     * 与前两层高亮独立共存，色相也刻意不同（紫罗兰 vs 暖金 / 青碧）——
     * 否则「我点的这个词」会被误认成「正在读的句子」或「搜索命中」。
     * 同一时刻最多只有一个词处于选中态，所以这里最多一个条目。
     */
    private var pointReadSpans: Map<Int, IntRange> = emptyMap()

    /**
     * 「实时朗读」当前读到的行内区间（行下标 → 行内**闭**区间）。
     *
     * 独立于 [playingSpans]（音频播放层）：朗读只是临时把正文念出来，不该占用、也不该
     * 清掉有声书的播放高亮 —— 两者可以同时存在而互不干扰。
     * 「从此处读」时首行区间从**选区起点**开始 ⇒ 只亮选中处到行尾，而不是整行。
     */
    private var readAloudSpans: Map<Int, IntRange> = emptyMap()

    /**
     * 阅读页**正文**的字号缩放比例（默认 1.0），由 [setTextScale] 从偏好写入。
     *
     * ⚠️ 这里刻意**不用** `Configuration.fontScale`（早期实现就是那么做的）：
     * `fontScale` 是 Activity 级全局量，一旦注入，标题栏、底部导航、弹窗、
     * 列表项文字会跟正文一起变大变小 —— 与「只调阅读页电子书正文」的需求相反。
     * 局部量字号只需要在这一个 TextView 上 `setTextSize`，范围天然被限制住。
     */
    private var textScale: Float = 1.0f

    /**
     * 设置正文缩放比例并全量重绘。
     *
     * 必须 [notifyDataSetChanged] 而非只刷可见项：字号一变**每行的高度都变了**，
     * RecyclerView 的行高缓存、滚动锚点全部失效，只通知可见项会留下错位的空白。
     * 这是一次性动作（用户在弹窗里点确定），不是播放高亮那种高频路径，代价可接受。
     *
     * @param scale 缩放比例，调用方需已夹到 0.8~1.6
     */
    fun setTextScale(scale: Float) {
        if (textScale == scale) return
        textScale = scale
        notifyDataSetChanged()
    }

    /**
     * 设置（或清空）点读选中词高亮。传 [emptyMap] 即取消选中。
     *
     * 同样只重绘「新旧键集合的并集」关联的行：否则上一次点中的那个词会一直亮着，
     * 而界面上已经没有任何东西表明它被选中了。
     */
    fun setPointReadHighlight(spans: Map<Int, IntRange>) {
        val touched = pointReadSpans.keys + spans.keys
        pointReadSpans = spans
        for (row in touched) {
            if (row >= 0) notifyItemChanged(row)
        }
    }

    /**
     * 设置（或清空）实时朗读的行内高亮。传 [emptyMap] 即取消高亮。
     *
     * 只重绘「新旧键集合的并集」关联的行 —— 与 [setPointReadHighlight] 同一个道理：
     * 只 notify 新集合的话，上一次朗读到的那一行底色会一直留在屏幕上。
     */
    fun setReadAloudHighlight(spans: Map<Int, IntRange>) {
        val touched = readAloudSpans.keys + spans.keys
        readAloudSpans = spans
        for (row in touched) {
            if (row >= 0) notifyItemChanged(row)
        }
    }

    /**
     * 列表换了一轮 ⇒ 旧下标全部失效，点读过的高亮必须清掉，
     * 否则会亮在新内容里毫不相干的一行上（不会崩，只是错得很安静）。
     */
    override fun onCurrentListChanged(previousList: List<LineItem>, currentList: List<LineItem>) {
        super.onCurrentListChanged(previousList, currentList)
        pointReadSpans = emptyMap()
    }

    /**
     * 设置（或清空）搜索命中高亮。
     *
     * 只重绘「新旧键集合的并集」关联的行 —— 与 [updatePlayState] 同一个道理：
     * 只 notify 新集合的话，上一次命中的那一行底色会一直留在屏幕上，
     * 用户会以为旧命中还成立。
     */
    fun setSearchHighlight(spans: Map<Int, IntRange>) {
        val touched = searchSpans.keys + spans.keys
        searchSpans = spans
        for (row in touched) {
            if (row >= 0) notifyItemChanged(row)
        }
    }

    /**
     * 更新「正在播放」的位置与句内区间。
     *
     * 播放/暂停状态不再需要传进来：行内的播放/暂停图标已移除，行内只剩
     * 「整行底色」与「句内底色」两层高亮。
     *
     * @param position   正在播放片段**起点**所在的行下标（-1 = 无）。**只用于滚动定位** ——
     *                   段起点常常落在上一行行尾，拿它当唯一的整行底色就会错位。
     * @param spansInRows 该片段覆盖的每行 → 行内区间。整行底色铺在这些行的**每一行**上，
     *                   句内底色按行分片；段不跨行时只有一个条目。
     *                   为空表示没有映射（按行号播放）⇒ 退化为「整行底色 = [position]」。
     */
    fun updatePlayState(position: Int, spansInRows: Map<Int, IntRange> = emptyMap()) {
        val oldPosition = playingPosition
        val oldRows = playingRows
        playingPosition = position
        playingSpans = spansInRows
        playingRows = spansInRows.keys

        // ⚠️ 必须把**旧集合的每一行**也重绘：上一次亮的是「行 6 和行 7」、这一次只亮「行 7」时，
        // 行 6 不在新集合里，只 notify 新旧两个 position 的话它会残留底色。
        val touched = oldRows + playingRows + oldPosition + position
        for (row in touched) {
            if (row >= 0) notifyItemChanged(row)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // ⚠️ 兜底选词的延迟任务必须随回收取消：延迟窗口内 holder 被回收复用后，
        // 任务里读到的 tv.text 已是**另一行**，照跑会在新行上凭空弹出菜单
        // （TextActionSheet 时代踩过的实坑，别再犯）。
        holder.cancelPendingSelectionFallback()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvText: TextView = itemView.findViewById(R.id.tv_text)

        // 每个 ViewHolder 只创建一次回调，后续 bind() 时复用（避免每次刷新都 new 对象）
        private var cachedCallback: ActionMode.Callback? = null
        private var cachedItem: LineItem? = null

        /** 最近一次按下的位置（行内字符下标）；-1 表示未知 */
        private var lastTouchOffset: Int = -1

        /**
         * 挂起中的「兜底选词」延迟检查（见 [scheduleSelectionFallback]）。
         *
         * ⚠️ 必须在三处取消，漏一处就会「复用后在新行凭空弹菜单」
         * （TextActionSheet 时代踩过的实坑）：
         * ① 新手势的 ACTION_DOWN；② [bind]（换绑到别的行）；③ [onViewRecycled]。
         */
        private var pendingSelectionFallback: Runnable? = null

        init {
            installTouchOffsetProbe()
            disableTextClassification()
        }

        /**
         * 关掉系统对选中文本的「智能分类」，让长按选词的操作菜单**必定**出现。
         *
         * ## 为什么必须关
         *
         * 长按选词时，`TextView` 会走系统的 `SelectActionModeHelper`：它在 `startActionMode`
         * 之后**异步**调一次 `TextClassifier.onSuggestSelection/onClassifyText`（识别地址、
         * 电话、日期等实体，用于追加「翻译」「地图」之类智能项），并对这次调用**只等约
         * 200ms**（AOSP `SelectActionModeHelper.TEXT_CLASSIFICATION_TIMEOUT`）。
         * 分类结果要回主线程才能消费 —— 主线程稍一忙就赶不上这 200ms 窗口，超时后
         * **整个 ActionMode 被取消**，`customSelectionActionModeCallback` 根本不会被调用，
         * 用户看到的就是「长按有时弹菜单、有时毫无反应」。
         *
         * 实测（Pixel_9 / Android 17，本机 `AiAiTextClassifier` 9ms 就返回）仍然超时：
         * ```
         * I AiAiTextClassifier: ...g suggestSelectionInternal: return system response
         * D SelectActionModeHelper: Timeout in TextClassificationAsyncTask     ← 菜单被取消
         * W androidtc: TextClassifier called on main thread
         * ```
         * 连续 8 次长按 0/8 出现菜单、其中 4 次明确超时。分类器本身快也没用，
         * 瓶颈在「回主线程」这一步，所以只能**整个关掉**而不是换更快的分类器。
         *
         * [TextClassifier.NO_OP] 是一个空实现：所有回调立即返回空结果，链路被彻底掐断，
         * 超时路径不复存在。代价是失去「翻译」等系统智能项 —— 本项目的菜单本来就只提供
         * 「复制」「收藏」，这些智能项不在设计内。
         *
         * ⚠️ `setTextClassifier` 与 `TextClassifier.NO_OP` 都是 **API 26** 才有的，
         * 而本项目 `minSdk = 24`，低版本上跳过即可（API < 26 的 `SelectActionModeHelper`
         * 没有这段分类逻辑，本来就不会超时）。
         */
        private fun disableTextClassification() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // ⚠️ 用显式 setter 而不是 `tvText.textClassifier = ...`：
                // TextView 上有同名的 getter/setter（`getTextClassifier`/`setTextClassifier`），
                // 但 Kotlin 合成属性在此处解析到的是 `View.getTextClassifier()` 那套，
                // 直接赋值会报 `Val cannot be reassigned`。显式调 setter 没有歧义。
                tvText.setTextClassifier(TextClassifier.NO_OP)
            }
        }

        /**
         * 清掉上一次长按残留在 TextView 上的选区（治「选区粘滞」）。
         *
         * ## 背景：Android 的「选区粘滞」
         *
         * `SelectionModifierController.startSelection()` 首次执行会 `requestFocus()`
         * 并 `setSelection(start, end)` 建立选区。之后 ActionMode 结束（用户点空白）
         * **只关掉浮动工具栏，不清 TextView 上的 Selection**。下次长按时它发现本
         * TextView「已有 selection」⇒ 判定为续选/拖动模式 ⇒ 只挪折叠光标
         * （`selStart == selEnd`）⇒ 既不建新选区，也不起 ActionMode。
         * 真机实测（Redmi K30 Pro / release）：整个会话里只有第一次长按出选区，
         * 换 4 个不同 ViewHolder 照样全灭 —— 与复用无关。
         *
         * ## 🔴 为什么必须是 `removeSelection` 而不是 `setSelection(text, 0, 0)`
         *
         * ⚠️ `setSelection(text, 0, 0)` **不等于**「清掉选区」—— 它是在位置 0 放一个
         * **折叠光标**，选区依然存在（`getSelectionStart()` 返回 0 而不是 -1），
         * 框架照样走续选分支。真机日志逐条印证：每条 DOWN 前 `pre=[0,0]` 仍失败，
         * 改 `removeSelection` 后 `post=[-1,-1]` 才真正回到「无选区」态。
         *
         * ## 为什么不 `clearFocus()`
         *
         * `removeSelection` 已经足够（真机日志 `post=[-1,-1]` 证实）；而 DOWN 瞬间
         * 失焦会干扰框架起选区（`startSelection()` 需要焦点）。**别加回来**。
         *
         * ## 为什么不用 `tv.clearSelection()` / `tv.setSelection(...)`
         *
         * `TextView` 上**没有**这两个 API（那是 `EditText` 的；已用字节扫描核对
         * android-34 的 `TextView.class`，无 `setSelection` 符号）。
         * `Selection.removeSelection` 的形参是 `Spannable`，必须传 **`tv.text`**
         * 而不是 TextView（否则 `Type mismatch`），且要 `is Spannable` 守卫——
         * 正文经 [highlightedText] 处理后本就是 `SpannableString`。
         */
        private fun clearResidualSelection(tv: TextView) {
            val text = tv.text
            if (text is android.text.Spannable) {
                android.text.Selection.removeSelection(text)
            }
        }

        /** ⭐ 临时诊断用（2026-10-13 长按选词排查），验收后删。 */
        private fun diag(msg: String) {
            android.util.Log.i("LineSelDiag", "[vh=${System.identityHashCode(this)}] $msg")
        }

/**
         * 记下抬指处的字符下标，**并在短按时自己触发一次点击**。
         *
         * ## 为什么必须自己 `performClick()`
         *
         * `bind()` 里 `tvText.setTextIsSelectable(true)` 会给 TextView 装上
         * `ArrowKeyMovementMethod` 并置为 focusable。TextView 在这个状态下，
         * `ACTION_DOWN` 会被 movement method 当作「起选区」消费掉，
         * **同一次手势内不再走 `performClick()`** —— 也就是第一次点击
         * `setOnClickListener` 的回调根本不会执行。
         *
         * 实测（Pixel_10 / Android 17，英文行，第一行第 2 个字符处连点两次）：
         * ```
         * 第1击: probe DOWN last=-1→2 | probe UP last=2 | 无 tvText click
         * 第2击: probe DOWN last=2   | probe UP last=2 | tvText click ✅ range=0..2
         * ```
         * 用户侧表现就是「点读模式下第一次点单词没反应，第二次才弹释义」，
         * 而触点完全正确 —— 不是位置问题。
         *
         * ## 为什么不能改成「selectable 时不挂 onClickListener，这里补调」
         *
         * 第 2 击时框架**自己也会**触发 `onClick`。两边都调 ⇒ 同一次点击查两次词：
         * `DictLookupSheet.show` 会 dismiss 掉上一个再弹新的，而朗读播两遍。
         * 所以这里把点击的**唯一所有权**拿过来：`onClickListener` 一律不挂，
         * 由本探针在判定为「短按且未移动」时单点触发。
         *
         * ## 双击切栏不会被重复触发
         *
         * `RecyclerView.onInterceptTouchEvent(ACTION_UP)` 早于子 View 收到 UP 执行：
         * 第二击若被 `BookContentActivity.setupChromeToggle` 判为双击，它会
         * `return true` 吃掉整条手势流，**本探针的 ACTION_UP 分支根本不会执行**
         * ⇒ 只有「切栏」发生，不会附带一次查词。反过来第一击
         * `return false`，探针照常补一次点击。两条路互斥，无需共享双击状态。
         *
         * ## 阈值与 setupChromeToggle 的关系
         *
         * 位移取 [TAP_SLOP_DP]（与 `BookContentActivity.TAP_SLOP_DP` 一致）。
         * 时长取 [TAP_TIMEOUT_MS_FALLBACK_MS] 而**不是** Activity 那个
         * `getLongPressTimeout()` 修正值 —— 两者刻意错开：Activity 要保护长按选词
         * （阈值放长到 500ms），本探针只判「点击」（300ms 就该判定成立）。
         * 若两者相等或倒置，会出现「按住 300~500ms 抬手，既没触发点击、
         * 选词菜单也被判成滚动」的夹缝。
         *
         * 返回 false 不消费事件：长按选词、CJK 长按接管都保持原样。
         */
        @SuppressLint("ClickableViewAccessibility")
        private fun installTouchOffsetProbe() {
            val slopPx = TAP_SLOP_DP * tvText.resources.displayMetrics.density
            var downX = 0f
            var downY = 0f
            var downAt = 0L
            var moved = false
            tvText.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        downAt = System.currentTimeMillis()
                        moved = false
                        // 🔴🔴 清掉上一次长按残留的选区，详见 [bind] 里同名校验处的长注释。
                        //
                        // ⚠️ **必须在这里也清一遍**：[bind] 只在 ViewHolder 复用时跑，
                        // 「同一行反复长按」根本不会重新 bind ⇒ 只在 bind 里清，
                        // 第二次长按照样是折叠光标。而 ACTION_DOWN 早于框架的
                        // 长按判定（~500ms 后），此时清干净才来得及让
                        // `startSelection()` 走「新建选区」分支而不是「续选」分支。
                        if (view is TextView) clearResidualSelection(view)
                        // 上一轮手势可能还挂着「兜底选词」的延迟检查：新手势一开始就作废，
                        // 否则快速连续两次长按会让第二张菜单凭空弹出。
                        cancelPendingSelectionFallback()
                        // ⚠️ ACTION_DOWN 必须也记 offset：长按在 ACTION_UP **之前**触发，
                        //   只记 UP 的话，兜底选词读到的 lastTouchOffset 是上一次点击的
                        //   残留（或 -1）⇒ 建的选区会落到错误的位置。
                        val off = offsetAt(view as TextView, event.x, event.y)
                        if (off >= 0) {
                            lastTouchOffset = off
                            // 同时写进 TextView 的 tag：「从此处读」据此从**手指按到的那一个字**
                            // 起念，而不是从选区起点（选词器会把落点扩成整词/整句）。
                            view.setTag(R.id.tag_long_press_offset, off)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!moved && (abs(event.x - downX) > slopPx || abs(event.y - downY) > slopPx)) {
                            moved = true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val off = offsetAt(view as TextView, event.x, event.y)
                        if (off >= 0) {
                            lastTouchOffset = off
                            view.setTag(R.id.tag_long_press_offset, off)
                        }
                        val now = System.currentTimeMillis()
                        // 与 setupChromeToggle 同判据：位移超阈值（= 滚动）或按住超时
                        // （= 长按选词）都不算点击。选词与切栏的优先级高于点读。
                        if (!moved && now - downAt < TAP_TIMEOUT_MS_FALLBACK_MS) {
                            val pos = bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION && pos < itemCount) {
                                //⚠️ 用 getItem(pos) 而非 [cachedItem]：后者是
                                //   「上次 bind 进来的那个」，滚动复用后可能与当前位置
                                //   对不上（那是给 ActionMode.Callback 复用用的缓存）。
                                onItemClick(getItem(pos), lastTouchOffset, pos)
                            }
                        }
                        // ⭐⭐ 兜底选词（2026-10-14 方案 A，真机定案后的正式修法）：
                        // 确认为「长按手势」（按住 ≥[SELECTION_FALLBACK_MIN_PRESS_MS] 且未移动）
                        // 才安排延迟检查 —— 系统若成功，届时已有有效选区，检查自动跳过；
                        // 若仍无选区，说明系统选词器又静默失败了，由宿主接管。
                        // 轻点（<400ms）不进来：那是点读/双击切栏的领地。
                        if (!moved && now - downAt >= SELECTION_FALLBACK_MIN_PRESS_MS &&
                            textActionModeProvider != null
                        ) {
                            scheduleSelectionFallback(view as TextView, off)
                        }
                    }
                    // 手势被系统打断（来电、窗口失焦、父容器拦截滚动）：状态不可信，整段丢弃。
                    // 兜底检查一并作废 —— 手势都没走完，谈不上「系统选词失败」。
                    MotionEvent.ACTION_CANCEL -> {
                        cancelPendingSelectionFallback()
                        moved = true
                    }
                }
                false
            }
        }

        /**
         * ⭐⭐ 兜底选词（2026-10-14 方案 A）—— 真机定案：MIUI 的自研中文选词器
         * 对约四成行**静默失败**（长按/双击共用入口，失败时无选区、无菜单、无日志；
         * 反射证实 MIUI 重写过 Editor，AOSP 源码对其不适用），应用层无法修复只能兜底。
         *
         * 机制：长按手势结束（UP）后延迟 [SELECTION_FALLBACK_DELAY_MS] 再看一眼 ——
         * 系统路径若成功，此刻必然已有「有效选区」（start < end）⇒ 什么都不做；
         * 若仍无选区（含折叠光标 start == end）⇒ 判定系统静默失败，
         * 交给 [TextActionModeProvider.startFallbackSelection] 自建选区并弹
         * **与系统成功路径完全相同的浮动工具栏菜单**（不是曾经的底部弹窗）。
         *
         * ⚠️ 为什么必须延迟到 UP 之后、而不是长按瞬间做：
         * 手势期间建的选区会被 UP 的 onTouchUpEvent 清掉；系统菜单也在 UP 后才建。
         * 延迟取 300ms：系统约 40ms 就建好菜单，300ms 已能分清成败；再短会在
         * 系统菜单出现前抢跑 ⇒ 两张菜单。
         *
         * ⚠️ 已知限制：兜底选区没有拖动手柄（手柄是系统内部组件，无法触发），
         * 想扩选只能换个位置重新长按（系统成功的行仍有手柄）。
         */
        private fun scheduleSelectionFallback(tv: TextView, upOffset: Int) {
            cancelPendingSelectionFallback()
            val capturedOffset = if (upOffset >= 0) upOffset else lastTouchOffset
            if (capturedOffset < 0) return
            val pos = bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION || pos >= itemCount) return
            val capturedItem = getItem(pos)
            val r = Runnable {
                pendingSelectionFallback = null
                // 复用守卫：延迟窗口内 holder 可能被回收/换绑 ——
                // 此时 tv.text 已是另一行，capturedOffset 指向的不再是用户按的那行
                if (!tv.isAttachedToWindow) return@Runnable
                val posNow = bindingAdapterPosition
                if (posNow != pos || posNow == RecyclerView.NO_POSITION ||
                    getItem(posNow) !== capturedItem
                ) {
                    return@Runnable
                }
                if (tv.selectionEnd > tv.selectionStart) {
                    // 系统成功路径已建选区 —— 兜底让位
                    diag("FALLBACK-SKIP pos=$pos reason=has-selection") // ⭐ 临时诊断，验收后删
                    return@Runnable
                }
                diag("FALLBACK-FIRE pos=$pos off=$capturedOffset")      // ⭐ 临时诊断，验收后删
                textActionModeProvider?.startFallbackSelection(tv, capturedItem, capturedOffset)
            }
            pendingSelectionFallback = r
            tv.postDelayed(r, SELECTION_FALLBACK_DELAY_MS)
        }

        /**
         * 取消挂起的兜底检查。DOWN / bind / 回收三处都必须调（见字段注释）。
         * ⚠️ 不能标 private：[onViewRecycled] 在外层 Adapter 里也要调，
         * Kotlin 的 outer class 访问不了 inner class 的 private 成员。
         */
        fun cancelPendingSelectionFallback() {
            pendingSelectionFallback?.let { tvText.removeCallbacks(it) }
            pendingSelectionFallback = null
        }

        private fun offsetAt(textView: TextView, x: Float, y: Float): Int = try {
            if (textView.layout == null) -1 else textView.getOffsetForPosition(x, y)
        } catch (_: Exception) {
            -1
        }

        fun bind(item: LineItem) {
            // 换绑到（可能是）别的行：上一行挂起的兜底检查立即作废
            cancelPendingSelectionFallback()
            val pos = bindingAdapterPosition
            // 整行底色的判据是「这一行**属于**正在播放的片段」，不是「这一行是段首行」：
            // 片段横跨两行时（服务端换行不算段边界）两行都该亮。
            val playing = pos == playingPosition || playingRows.contains(pos)

            // 正文字号：基准 16sp（与 item_line.xml 里 tv_text 的 textSize 一致）× 用户比例。
            // 放在 setText **之前** —— setTextSize 自身就会 requestLayout，
            // 先给字号再给文本，少一次多余的重新排版。
            //⚠️ 用 COMPLEX_UNIT_SP 而不是 PX：sp 会再乘上系统的 fontScale，
            //   用户在「设置 → 显示 → 字体大小」里调过的值因此仍然有效
            //   （早期那版覆写 Configuration.fontScale 把系统设置整个顶掉了，已随BaseActivity 一并移除）。
            tvText.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                BASE_BODY_TEXT_SP * textScale
            )
            tvText.text = highlightedText(item, pos, playing)

            // 「正在播放」只由两层高亮表达：整行底色（item_highlight 的 state_selected）
            // + 句内更深底色。行号与行内播放/暂停图标已移除，音频控制统一走
            // 页面下方的 playbackControlBar。
            itemView.isSelected = playing

            itemView.setOnClickListener {
                onItemClick(item, -1, bindingAdapterPosition)
            }

            // ⚠️ tvText 上**不挂** setOnClickListener —— 点击的所有权在
            //   [installTouchOffsetProbe] 里：textIsSelectable 会让框架吞掉第一次
            //   performClick，而第二次点击框架又会自己触发，两边都挂/都调会查两次词。
            //   详见 installTouchOffsetProbe 的说明。
            // itemView 这一个仍要留：CJK 行走 setTextIsSelectable(false)，
            //   触摸会被 tvText 之外的区域冒泡上来（虽然 tv_text 是 match_parent，
            //   几乎不会发生），留它是为了不留一条静默失败的路径。

            // 复用缓存的回调（仅 item 不同时才重建），避免 onBind 时重复创建对象
            if (cachedItem !== item) {
                cachedItem = item
                cachedCallback = if (textActionModeProvider != null) {
                    textActionModeProvider.getActionModeCallback(tvText, item)
                } else {
                    null  // null 表示使用 TextView 默认行为，不注册回调
                }
            }

            // ⚠️ 两个分支都要写：`?.let` 只在非 null 时赋值，回调为 null 时**不清空**，
            // ViewHolder 被复用到「无自定义菜单」的行上时会残留上一个 item 的回调
            // —— 通过它拿到的 lineNumber 是上一行的，收藏会存到错的行。
            // 这里显式赋值 null 才是「恢复 TextView 默认行为」。
            tvText.customSelectionActionModeCallback = cachedCallback

            // ⚠️ setTextIsSelectable(false) 在 AOSP 里会连带
            //   setFocusable(false) / setClickable(false) / setLongClickable(false)，
            //   所以它必须排在上面 setOnClickListener **之前**执行，
            //   否则「点段落播音频」会被静默清掉（是否还触发取决于 AOSP 内部
            //   performClick 的实现细节，不能拿「点句播」这个主功能去赌）。
            tvText.setTextIsSelectable(true)

            // 🔴🔴 必须清掉**上一次长按残留的选区**，否则本次长按不会建立新选区。
            //
            // ## 症状：整个阅读会话里只有「第一次」长按能出选区与 ActionMode
            //
            // 真机实测（Redmi K30 Pro / release，同一段文字连续长按）：
            // ```
            // 17:21:54  selStart=44 selEnd=45   ← 唯一一次非空选区（系统赢）
            // 17:21:59  selStart=0  selEnd=0    ← 菜单消失后
            // 17:22:06  selStart=0  selEnd=0
            // 17:22:11  selStart=0  selEnd=0
            // 17:22:23  selStart=44 selEnd=44   ← 之后全是折叠光标（兜底赢）
            // 17:22:25  selStart=44 selEnd=44
            // 17:22:26  selStart=45 selEnd=45
            // ```
            // 用户侧描述：「直接长按弹出选中出菜单，点空白使菜单消失后，
            // 再长按就一直是底部弹窗」—— 与上面逐条吻合。
            //
            // ## 原理：AOSP 的「选区粘滞」
            //
            // `SelectionModifierController.startSelection()` 首次执行时会
            // `textView.requestFocus()` 并 `setSelection(start, end)` 建立选区。
            // 之后 ActionMode 结束（点空白）**只关掉浮动工具栏，不清 TextView 上的
            // Selection**。下次再长按时，`startSelection()` 发现本TextView
            // 「已有 selection」⇒ 判定为**续选/拖动**模式 ⇒ 只把光标挪到落点
            // （`selStart == selEnd`）⇒ **既不建立新选区，也不起 ActionMode**。
            //
            // ⚠️ 与「焦点」无关：`focused=true` 的三次里有两次照样失败（`44/44`）。
            // 也与中文分词无关：成功那次精确框住了 3 个字（`44`~`45`），BreakIterator 正常。
            //
            // ## 为什么静态审查与 lint 都看不出来
            //
            // 属性合法、代码路径正确、运行时也不崩溃不报错，只是「第二次起就不灵了」。
            // 与本文件 `item_line.xml` 注释里记的 `blocksDescendants` 属同一类坑。
            //
            // ⚠️ **必须在 setTextIsSelectable(true) 之后清**：setTextIsSelectable 会重建
            // ArrowKeyMovementMethod，先清后设等于清了个空。
            //
            // 🔴 2026-10-13 修正：这里原先调`Selection.setSelection(text, 0, 0)`，
            // 那是「在位置 0 放折叠光标」而不是「清掉选区」—— `getSelectionStart()`
            // 仍返回 0（不是 -1）⇒ `SelectionModifierController` 判「已有选区」
            // ⇒ 继续走续选分支 ⇒ 不建新选区也不起 ActionMode。
            // 真机日志逐条印证（见 [clearResidualSelection] 的 KDoc）。
            // 现已改为 `Selection.removeSelection(text)`。
            //
            // ⚠️⚠️ `clearResidualSelection` 里的 `clearFocus()` 也一并删掉了（2026-10-13），
            // 它会让长按落在折行非首行时起不了ActionMode。
            // **别再加回来**，理由见该方法内的长注释。
            clearResidualSelection(tvText)

            // ⭐ **CJK 行也走系统选区**（手柄由此而来）。
            //
            // ## 为什么改成和英文行一样
            //
            // 曾经的判断是「纯中文行靠系统选词必然失败（BreakIterator 定不出词边界）⇒整行
            // `setTextIsSelectable(false)`」，依据是一组设备端实测：3 个中文段 × 2 行 × 9 个
            // 落点 = 27 个全灭，而同屏 11 个英文段全部成功。
            //
            // ⚠️ **但那组实测是无效的**，结论建立在一个自相矛盾的观察上：
            // 记录写着「在 `bind()` 里显式 `setTextIsSelectable(true)` 也无效」，
            // 而 `bind()` 本来就在 CJK 分支**之前**无条件 `setTextIsSelectable(true)`，
            // 紧接着这个 CJK 分支又把它改回 `false`。
            // ⇒ 「显式设 true」的那一行**必然被下面的 CJK 分支覆盖**，
            // 所谓「设了也没用」实际测的是「设了又被自己关掉」，等于什么都没验证。
            //
            // 而且那组实测做于[disableTextClassification] 修掉 200ms 超时**之前**——
            // 中文分词路径比英文慢，在那条超时里必然次次赶不上窗口，
            // 于是表现为「全灭」。超时被掐断后，这个前提已不成立。
            //
            // 所以这里统一走系统选区：长按由框架起 ActionMode + 选择手柄，
            // 菜单由 [TextActionModeProvider.getActionModeCallback] 提供。
            //
            // ## ⚠️ 不再挂任何 OnLongClickListener（2026-10-13 移除自绘底部弹窗兜底）
            //
            // 曾经对 CJK 行挂一个返回 `false` 的 [OnLongClickListener]，用延迟任务
            // 探测「系统选区起不来」时弹 [TextActionSheet] 兜底。真机证明这条兜底
            // **弊大于利**，三种形态都被它引入：
            // ① 底部弹窗与系统浮动工具栏**同屏**（用户截图实证）——系统起 ActionMode
            //    要跑 `BreakIterator` 分词，耗时可达数百毫秒，存在
            //    「ActionMode 已起但选区尚未写入 TextView」的中间态；
            // ② 折叠光标（`selStart == selEnd`）被误判成「已选中」⇒ 菜单不弹 ⇒「没反应」；
            // ③ 每次长按的落点/选区语义在两条路径间摇摆。
            //
            // ⇒ 单一路径：系统选区起得来就用（拿得到手柄）；起不来（真机定案：
            // MIUI 选词器对部分行静默失败）由 [scheduleSelectionFallback] 兜底，
            // 弹的是**同一个浮动工具栏菜单**，不再另开一套 UI。
            tvText.setOnLongClickListener(null)
            // 兜底：setTextIsSelectable 可能清掉 clickable，显式补回，保证「点句播」在任何分支下都成立。
            tvText.isClickable = true
        }

        /**
         * 行内高亮：把「正在读的那一句」与「搜索命中的那一段」在这一行里占据的区间涂上底色。
         *
         * 一行原文里往往压着好几个音频片段，只高亮整行看不出在读哪句；
         * 区间由 EbookSegmentMap 逐行算好（[playingSpans]），没有映射（无音频）时
         * 退化为纯文本。⚠️ 片段跨行时**两行各自**有一段：上一行只剩余尾那一小截
         * （常常就是一个标点），下一行才是正在念的正文。
         */
        private fun highlightedText(item: LineItem, position: Int, playing: Boolean): CharSequence {
            val playSpan = if (playing) playingSpans[position] else null
            val searchSpan = searchSpans[position]
            val pointSpan = pointReadSpans[position]
            val readAloudSpan = readAloudSpans[position]
            if (playSpan == null && searchSpan == null && pointSpan == null && readAloudSpan == null) {
                return item.text
            }
            return SpannableString(item.text).apply {
                // 用比整行底色更深一档的颜色：同行底色也是浅蓝，同色叠上去就看不出来了
                spanRange(playSpan, R.color.highlight_sentence)
                spanRange(searchSpan, R.color.highlight_search)
                // 点读选中词**最后**画 ⇒ 叠在播放/搜索底色之上。
                // 它回答的是「我刚才点了哪个词」，必须永远看得见；
                // 若被先画的播放底色盖住，用户会以为点击没生效。
                spanRange(pointSpan, R.color.highlight_point_read)
                // 朗读区间画在**最上层**：它回答「此刻正在念到哪个字」，
                // 「从此处读」时首行只亮「选中处→行尾」，一眼能看出从哪儿起念。
                // 与播放句内共用同一档颜色：两者语义都是「正在读」，同色不冲突。
                spanRange(readAloudSpan, R.color.highlight_sentence)
            }
        }

        /**
         * 给行内闭区间涂底色。
         * 越界或空区间直接跳过 —— 区间是换算出来的，宁可少画一笔也不能抛下标越界
         * （RecyclerView 绑定期抛异常会直接崩在滑动中）。
         */
        private fun SpannableString.spanRange(range: IntRange?, colorRes: Int) {
            if (range == null) return
            val from = range.first
            val to = range.last + 1
            if (from < 0 || from >= to || to > length) return
            setSpan(
                BackgroundColorSpan(ContextCompat.getColor(itemView.context, colorRes)),
                from,
                to,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    class LineDiffCallback : DiffUtil.ItemCallback<LineItem>() {
        override fun areItemsTheSame(oldItem: LineItem, newItem: LineItem): Boolean {
            return oldItem.id == newItem.id && oldItem.lineNumber == newItem.lineNumber
        }

        override fun areContentsTheSame(oldItem: LineItem, newItem: LineItem): Boolean {
            return oldItem.text == newItem.text
        }
    }

    companion object {
        /**
         * 正文字号基准（sp），实际字号 = 它 × [textScale]。
         *
         * 取 16 是为了与 `item_line.xml` 中 `tv_text` 的 `android:textSize="16sp"` 一致 ——
         * 布局里那行textSize 只是给「首帧 & 预览」用的兜底，真正生效的是 bind() 里这次
         * `setTextSize`。改基准时**两处一起改**，否则会出现「一进来是 16sp、滑一下变 18sp」的跳变。
         */
        private const val BASE_BODY_TEXT_SP = 16f

        /**
         * 「轻点」判据的位移阈值（dp），与
         * `BookContentActivity.TAP_SLOP_DP` **必须保持一致**。
         *
         * 两边独立定义只因为 Activity 那边是 `private const`；若要把它们合到一个
         * 共享常量文件，请连同 [TAP_TIMEOUT_MS_FALLBACK_MS] 一起改。
         * 不一致的后果：框架在滚动手势里也调 `onItemClick`，或框架认为在轻点而我们
         * 判成滚动 —— 两种错位都会让用户看到「该响应的没响应」。
         */
        private const val TAP_SLOP_DP = 12

        /**
         * 「轻点」判据的时长阈值（ms），与
         * `BookContentActivity.TAP_TIMEOUT_MS` 保持一致。
         *
         * 取 300 而非系统 `getLongPressTimeout()`（通常 500）：点读的点击判定必须
         * 早于长按选词触发，否则按住 300~500ms 抬手时既被判成长按（无点击）
         * 又已弹了选词菜单。手感上 300ms 仍宽裕，且短于长按阈值，语义清晰。
         * Activity 那边会用 `getLongPressTimeout()` 修正它的
         * [BookContentActivity.setupChromeToggle]，但那是为了保护**长按选词**；
         * 本探针只判断「点击」，与它错开反而不会互相误伤。
         */
        private const val TAP_TIMEOUT_MS_FALLBACK_MS = 300L

        /**
         * 兜底选词的时长门槛（ms）：UP 时按住不足此时长的不视为「长按手势」，
         * 不安排兜底检查。轻点（点读/双击切栏）与长按选词以此分界。
         * 取 400 而不是系统 longPressTimeout（部分设备为 300ms）：留出手感余量；
         * 300~400ms 的夹缝只是「无动作」，两侧语义都不受影响。
         */
        private const val SELECTION_FALLBACK_MIN_PRESS_MS = 400L

        /**
         * 兜底检查的延迟（ms）：系统路径在 UP 后约 40ms 就建好菜单，
         * 300ms 足以让「系统成功」先落地（届时已有有效选区，兜底自动跳过）。
         * ⚠️ 不能更短：太短会在系统菜单出现前抢跑 ⇒ 两张菜单。
         */
        private const val SELECTION_FALLBACK_DELAY_MS = 300L

    }
}
