package com.audiobook.audiobookplayer

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.text.Layout
import android.text.Spannable
import android.text.Selection
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * ⭐ 兜底选区的「拖拽扩选手柄」（2026-10-14 方案 A 补充）。
 *
 * 背景：系统选区的拖动手柄由框架 `Editor` 内部管理（真机定案：MIUI 还重写过 Editor，
 * 无任何公开入口），因此 [BookContentActivity.startFallbackSelection] 用
 * `Selection.setSelection` 建的选区**天然没有手柄**，用户只能选中落点单字/英文整词、
 * 没法拖拽扩选。本类在应用侧补一对自绘手柄：
 *
 * - 两个 [HandleView] 直接 addView 到 **decorView**（真实子 View，能收到触摸事件）；
 *   ⚠️ 不能用 `ViewGroupOverlay` —— AOSP 源码中 OverlayViewGroup "is not parented
 *   to the host view"、不参与触摸分发，挂上去的手柄**收不到事件**（已核对 android-12 源码）。
 * - 手柄锚定选区首/尾字符的下方（锚点 = 尖端），拖动时把手指位置经
 *   `getOffsetForPosition` 反算成字符 offset，改写 Selection span 实现扩选；
 * - 拖动中经 [onSelectionChanged] 通知宿主刷新浮动工具栏定位（invalidateContentRect），
 *   拖动结束经 [onDragEnd] 通知宿主重建菜单（「查字典」按单字显隐须走 onPrepareActionMode）；
 * - 选区所在行被回收（滚动出屏）时经 [onInvalid] 通知宿主整体收掉兜底菜单。
 *
 * 坐标系约定：手柄 translationX/Y 是 **decorView 窗口坐标**；事件 rawX/rawY 与
 * `getLocationOnScreen` 是**屏幕坐标**；两者相差 decorView 自身的屏幕偏移
 * （通常为 0，但不做此假设，换算处统一用 [decorScreenLoc]）。
 *
 * 生命周期由宿主驱动：attach 于兜底菜单创建成功后、detach 于其销毁（或 Activity onDestroy）。
 * 所有方法须在主线程调用。
 */
class FallbackSelectionHandles(private val activity: Activity) {

    /** 兜底选区变化（拖动手柄期间每帧）—— 宿主用来刷新浮动工具栏定位 */
    var onSelectionChanged: (() -> Unit)? = null

    /** 一次拖动结束 —— 宿主用来刷新菜单项（「查字典」按单字显隐） */
    var onDragEnd: (() -> Unit)? = null

    /** 选区所在行已不可用（宿主 TextView 被回收/detach）—— 宿主用来收掉兜底菜单 */
    var onInvalid: (() -> Unit)? = null

    private var tv: TextView? = null
    private var startHandle: HandleView? = null
    private var endHandle: HandleView? = null

    /**
     * 两个手柄各自锚定的**视觉行号**（由 [reposition] 维护）。
     * 拖拽时按它把手指 y 换成该行中线 —— 见 [offsetForScreen] 的长注释。
     */
    private var startAnchorLine: Int = 0
    private var endAnchorLine: Int = 0
    private var attachListener: View.OnAttachStateChangeListener? = null
    private val decorScreenLoc = IntArray(2)
    private val tvLoc = IntArray(2)

    // ---- 尺寸/配色（dp → px） ----
    private fun dp(v: Float): Int = Math.round(v * activity.resources.displayMetrics.density)

    /**
     * 手柄整体尺寸（含触控面积）。视觉主体画在内部，靠这个外框把命中区做大。
     *
     * ⚠️ 全部必须 `by lazy`：本类在 Activity **构造期**被字段初始化器实例化，
     * 那时 `attach()` 还没执行、base context 为 null，构造器里碰
     * `activity.resources` 直接 NPE（真机崩溃实证：打开阅读页必闪退，
     * `T0.f4.<init> → BookContentActivity.<init>`）。
     * lazy 把换算推迟到首次使用（attach/绘制期，必在 onCreate 之后）。
     */
    private val handleWidthPx by lazy { dp(26f) }

    /** 圆点直径 20dp ≈ 一个字宽（对齐目标截图：红点落在文字行上），外框留少量命中余量 */
    private val handleHeightPx by lazy { dp(26f) }
    private val circleRadiusPx by lazy { dp(10f) }

    /**
     * 手柄配色：固定红（#E53935，Material Red 600，对齐系统选区手柄的醒目红）。
     * ⚠️ 不取主题 `colorPrimary` —— 阅读主题的主色是棕褐色系，画出来的手柄
     * 与正文背景融在一起、几乎看不见（真机截图实证），故固定用红。
     */
    private val accentColor: Int by lazy { Color.parseColor("#E53935") }

    /** 是否处于显示状态（attach 过且未 detach） */
    val isActive: Boolean get() = startHandle != null

    /** 当前挂载的宿主 TextView（未挂载时为 null）—— 宿主做菜单/选区联动时用 */
    val attachedTextView: TextView? get() = tv

    /**
     * 在 [textView] 的当前选区两端挂上手柄并定位。
     * 要求选区已建好（selectionStart/End 有效）；TextView 必须已 attach。
     */
    fun attach(textView: TextView) {
        if (!textView.isAttachedToWindow) return
        detach()
        tv = textView
        val decor = activity.window.decorView as? ViewGroup ?: return
        val sh = HandleView(mirrored = true)
        val eh = HandleView()
        // 固定尺寸的 LayoutParams：DecorView(FrameLayout) 会 measure/layout 它，
        // 实际位置由 translationX/Y 控制（translation 不受 layout pass 影响）
        sh.layoutParams = ViewGroup.LayoutParams(handleWidthPx, handleHeightPx)
        eh.layoutParams = ViewGroup.LayoutParams(handleWidthPx, handleHeightPx)
        decor.addView(sh)
        decor.addView(eh)
        startHandle = sh
        endHandle = eh
        // 行被回收/离屏 ⇒ 手柄失去锚点，交宿主收尾
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                if (v === tv) onInvalid?.invoke()
            }
        }
        textView.addOnAttachStateChangeListener(listener)
        attachListener = listener
        reposition()
    }

    /** 摘除手柄并解监听。幂等，可重复调用 */
    fun detach() {
        val t = tv
        val l = attachListener
        if (t != null && l != null) t.removeOnAttachStateChangeListener(l)
        attachListener = null
        tv = null
        if (startHandle != null || endHandle != null) {
            val decor = try {
                activity.window.decorView as? ViewGroup
            } catch (_: Exception) {
                null
            }
            startHandle?.let { decor?.removeView(it) }
            endHandle?.let { decor?.removeView(it) }
            startHandle = null
            endHandle = null
        }
    }

    /** 按当前选区重新定位两个手柄（选区变化/行位置变化后调用） */
    fun reposition() {
        val textView = tv ?: return
        val layout = textView.layout ?: return
        val s = textView.selectionStart
        val e = textView.selectionEnd
        if (s < 0 || e < 0 || e < s) return
        refreshDecorScreenLoc()
        textView.getLocationOnScreen(tvLoc)
        // TextView 本地坐标 → decorView 窗口坐标的换算基
        val baseX = (tvLoc[0] - decorScreenLoc[0]) + textView.totalPaddingLeft
        val baseY = tvLoc[1] - decorScreenLoc[1]

        // 起始手柄：锚定 start 字符左缘，[alignRightEdge]=true ⇒ 右边界对齐选区左缘
        val startLine = layout.getLineForOffset(s)
        startAnchorLine = startLine
        startHandle?.let {
            // 锚点 y = 行底 + 半径 ⇒ 图标上沿贴着选区行底（特写截图：图标挂在高亮行下方）
            place(it, baseX + layout.getPrimaryHorizontal(s),
                baseY + lineBottom(textView, startLine) + circleRadiusPx.toFloat(),
                alignRightEdge = true)
        }

        // 结束手柄：锚定 end 处的字符右缘。
        // ⚠️ end 恰好落在**折行边界**（下一行行首）时，getPrimaryHorizontal(end)≈0
        // 会让手柄跳到下一行最左——系统手柄在这种情况下锚到上一行行尾，这里对齐该语义。
        var endLine = layout.getLineForOffset(e)
        val endX: Float = if (e > 0 && endLine > 0 && layout.getLineStart(endLine) == e) {
            endLine--
            layout.getLineRight(endLine)
        } else {
            layout.getPrimaryHorizontal(e)
        }
        endAnchorLine = endLine
        endHandle?.let {
            // [alignRightEdge]=false ⇒ 左边界对齐选区右缘（endX = 选区右缘 x）
            place(it, baseX + endX,
                baseY + lineBottom(textView, endLine) + circleRadiusPx.toFloat(),
                alignRightEdge = false)
        }
    }

    /**
     * 判断一次 ACTION_DOWN 是否落在任一手柄的（外扩）命中区内。
     * 宿主的 dispatchTouchEvent 用它区分「拖拽手柄的起点」与「点菜单外关闭」。
     */
    fun wantsTouch(event: MotionEvent): Boolean {
        val sh = startHandle
        val eh = endHandle
        if (sh == null && eh == null) return false
        refreshDecorScreenLoc()
        val slop = dp(10f)
        val x = event.rawX
        val y = event.rawY
        var hit = false
        for (h in listOfNotNull(sh, eh)) {
            // 与 place() 同理：layout pass 未完成时 h.width == 0，须用常量
            val left = h.translationX + decorScreenLoc[0] - slop
            val top = h.translationY + decorScreenLoc[1] - slop
            if (x >= left && x <= left + handleWidthPx + 2 * slop &&
                y >= top && y <= top + handleHeightPx + 2 * slop
            ) {
                hit = true
            }
        }
        return hit
    }

    // ---- 内部实现 ----

    private fun refreshDecorScreenLoc() {
        (activity.window.decorView as? View)?.getLocationOnScreen(decorScreenLoc)
    }

    /** TextView 某视觉行的底部（本地坐标，含 paddingTop，getLineBounds 已处理好） */
    private fun lineBottom(textView: TextView, line: Int): Float {
        val rect = Rect()
        textView.getLineBounds(line, rect)
        return (rect.bottom + dp(2f)).toFloat()
    }

    /**
     * 把图标按 [alignRightEdge] 横向贴到锚点 x、纵向中心对齐到锚点 y（调用方传
     * y = 行底 + 半径，使图标上沿贴着选区行底）。
     *
     * - 左（起始）手柄 [alignRightEdge]=true ⇒ 图标**右边界**落在选区左缘；
     * - 右（结束）手柄 [alignRightEdge]=false ⇒ 图标**左边界**落在选区右缘。
     *
     * ⚠️ 横向必须用常量 [handleWidthPx]，不能用 `handle.width`：
     * attach() 里 `decorView.addView()` 之后**立即**调用 reposition()，
     * 此时 decorView 的 layout pass 还没跑、`handle.width == 0`，
     * `x - width/2` 少减半个宽度 ⇒ view 完成 measure 后位置整体右偏
     * （真机实证：长按后的初始手柄位置不准，拖动中因 reposition 反复
     * 调用被「顺手修正」，松手后又错位）。onMeasure 固定返回
     * [handleWidthPx]，两者必然一致。
     */
    private fun place(handle: HandleView, x: Float, y: Float, alignRightEdge: Boolean) {
        handle.translationX = if (alignRightEdge) x - handleWidthPx else x
        handle.translationY = y - handleHeightPx / 2f
    }

    /**
     * 手指屏幕坐标 → 该 TextView 上的字符 offset（越界时夹到 [0, textLength]）。
     *
     * @param anchorLine 选区当前锚定的**视觉行**。offset 只在这一行上求。
     *
     * ⚠️⚠️ **y 必须换成锚定行的中线，不能用手指的真实 y**。这是「手柄拖不动」的
     * 根因（真机 85 帧 MOVE + 对照实验实证）：
     *
     * ① 手柄按设计挂在选区行**下方**（见 [reposition]：锚点 y = 行底 + 半径），手指 y
     *    天然落在 TextView 内容区之外。AOSP `Layout.getOffsetForPosition` 在 y 超出
     *    所有行时会把结果夹到行首/行尾 ⇒ 手指横移多少 offset 都不变。
     * ② 即便把 y 夹回内容区，仍会错：拖动时手指会自然上下漂移，一旦漂到**别的行**，
     *    offset 就变成那一行的值，与选区所在的行毫无关系。
     *    真机对照实验（同一选区 (208,209)，同一个左拖手势）：
     *      · 手指 x 601→327（左移 274px），offset 只在 **218~223** 之间跳；
     *      · 而选区起点 s=208 ⇒ `coerceIn(0, 208)` 恒等于 208 = s ⇒ 每帧都跳过。
     *    对照右侧手柄同一手势 offset 225→273 正常生效 —— 只因它的夹取上界是
     *    `text.length` 而非贴紧 `s`，属于"蒙对"，并非真的正确。
     *
     * 修法：拖拽扩选的语义本就是「沿选区所在行左右移动」，纵向是噪声 ⇒ 丢掉手指 y，
     * 用锚定行的垂直中线，只保留横向。
     */
    private fun offsetForScreen(
        textView: TextView,
        rawX: Float,
        rawY: Float,
        anchorLine: Int
    ): Int {
        textView.getLocationOnScreen(tvLoc)
        val x = rawX - tvLoc[0]
        val layout = textView.layout
        if (layout == null || layout.lineCount == 0) {
            return textView.getOffsetForPosition(x, rawY - tvLoc[1])
                .coerceIn(0, textView.text.length)
        }
        // 锚定行须夹进有效范围：选区变化后行号可能越界
        val line = anchorLine.coerceIn(0, layout.lineCount - 1)
        // 取该行垂直中线作为 y（本地坐标），保证 getOffsetForPosition 落回同一行
        val bounds = Rect()
        textView.getLineBounds(line, bounds)
        val midY = (bounds.top + bounds.bottom) / 2f
        return textView.getOffsetForPosition(x, midY).coerceIn(0, textView.text.length)
    }

    private fun handleTouch(handle: HandleView, event: MotionEvent): Boolean {
        val textView = tv ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                val text = textView.text
                if (text !is Spannable) return true
                val s = textView.selectionStart
                val e = textView.selectionEnd
                if (s < 0 || e < 0 || e <= s) return true
                val offset = offsetForScreen(
                    textView,
                    event.rawX,
                    event.rawY,
                    if (handle === startHandle) startAnchorLine else endAnchorLine
                )
                if (handle === startHandle) {
                    // 起点不许越过终点：上界取 e-1（保证 s < e，选区不塌缩）。
                    // ⚠️ 下界必须是 0 而不是 s：单字选区时 s == e-1，若把下界也钳在 s
                    //   起点就只能停在原地、永远无法向左扩选。
                    val newStart = offset.coerceIn(0, e - 1)
                    if (newStart != s) {
                        Selection.setSelection(text, newStart, e)
                        reposition()
                        onSelectionChanged?.invoke()
                    }
                } else if (handle === endHandle) {
                    // 终点不允许退到起点之前；最大值取 text.length
                    //（getOffsetForPosition 在行尾会返回 length，属合法锚点）
                    val newEnd = offset.coerceIn(s + 1, text.length)
                    if (newEnd != e) {
                        Selection.setSelection(text, s, newEnd)
                        reposition()
                        onSelectionChanged?.invoke()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onDragEnd?.invoke()
                return true
            }
        }
        return true
    }

    /**
     * 自绘手柄：「实心圆 + 实心方块」结合体 —— 红色圆主体叠加一个方块象限，
     * 形成一条平直竖边、一条平直横边，直角在角上、其余圆润（对齐目标截图样式）。
     * 右侧（结束）手柄直角朝左上；左侧（起始）手柄按竖轴左右镜像，直角朝右上。
     * onMeasure 固定为 [handleWidthPx] × [handleHeightPx] —— DecorView 是 FrameLayout，
     * 会按 LayoutParams 正常 measure/layout；view 整体比视觉主体略大，把触控命中区做大。
     *
     * @param mirrored true = 左右镜像（方块象限由左上改为右上）
     */
    private inner class HandleView(private val mirrored: Boolean = false) : View(activity) {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        private val shapePath = Path()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(handleWidthPx, handleHeightPx)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = circleRadiusPx.toFloat()
            // 同一 Path 里先后画圆与方块（同画笔填充即并集）：方块覆盖圆的一个象限，
            // 产生平直的上边半与竖边半，直角落在圆心的正上方一侧
            shapePath.reset()
            shapePath.addCircle(cx, cy, r, Path.Direction.CW)
            if (mirrored) {
                // 镜像：方块取右上象限，直角落在 (cx+r, cy-r)
                shapePath.addRect(cx, cy - r, cx + r, cy, Path.Direction.CW)
            } else {
                // 默认：方块取左上象限，直角落在 (cx-r, cy-r)
                shapePath.addRect(cx - r, cy - r, cx, cy, Path.Direction.CW)
            }
            canvas.drawPath(shapePath, fillPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // 手柄没有点击语义（只有按下-拖动-抬起）；UP 里调用 performClick
            // 仅为满足 ClickableViewAccessibility —— 探测器只认 onTouchEvent
            // **本体**里的调用（委托出去不算），super 无监听 ⇒ no-op。
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                performClick()
            }
            return handleTouch(this, event)
        }

        /**
         * 手柄没有「点击」语义（只有按下-拖动-抬起），这里不派发任何点击；
         * 覆写仅为满足 ClickableViewAccessibility（自定义 View 覆写了
         * onTouchEvent 就必须覆写 performClick）。
         */
        override fun performClick(): Boolean = super.performClick()
    }
}
