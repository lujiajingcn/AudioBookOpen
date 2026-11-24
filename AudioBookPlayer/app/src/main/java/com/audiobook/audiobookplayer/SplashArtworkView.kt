package com.audiobook.audiobookplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 启动页的品牌图视图（直接作为启动页根布局，铺满全屏）。
 *
 * **为什么不用 ImageView**：原布局是 `scaleType="fitXY"`，把 1024x1024 的方图硬拉到
 * 整屏（1080x2400 上纵向放大 2.37 倍、横向仅 1.05 倍），图标被拉成细长条。
 * 换成 ImageView 的其它 scaleType 也解决不了：`fitCenter` 上下留白难看，
 * `centerCrop` 会把主标题和插图裁掉。所以由本视图自己控制几何。
 *
 * 几何规则（任何屏幕比例下都不变形、不裁主体，且底部版本号永远有位置）：
 *
 *  1. **等比缩放**：scale = min(w/iw, h/ih)，两个方向都不会超出，因此绝不拉伸。
 *     1024x1280 的图在 20:9 手机上按宽度铺满，在 16:9 上按高度铺满。
 *  2. **水平居中**。
 *  3. **垂直方向受版本号约束**：图片底边不高于「控件高度 - [footerMinSpace]」，
 *     即底部始终留出版本号的位置；同时图片底边不低于那个位置，
 *     也就是图片尽量往下贴。两种情形：
 *       - 图比可用空间矮（20:9）：底边**恰好**落在版本号区上沿，
 *         图上方留白 —— 而图顶边与根布局渐变同色，所以看不出接缝；
 *       - 图比可用空间高（16:9、横屏）：底边停在版本号区上沿，**顶部**溢出屏幕，
 *         被裁掉的是图最上面的留白（品牌图标之上原本就有大片空白）。
 *     这个「宁可裁顶部也不裁底部」的顺序很重要：底部有插图主体，顶部只有留白。
 *  4. 图片下沿向上叠一条纯色淡出带，盖掉图中烤死的旧版信息
 *     （「版本号：V 2.4.0」「© 2024」）与卡片下方的阴影，并把
 *     「图片 → 底部纯色区」的过渡变成渐变而不是硬边。
 *  5. 图片以下铺满纯色，版本号文字由布局叠在这块纯色上。
 *
 * 换启动图时要重新对齐：[footerColor]、[footerFadeRatio]，
 * 以及 values/colors.xml 里 splash_bg_* 三个颜色的取色点。
 */
class SplashArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bitmap: Bitmap? = BitmapFactory.decodeResource(resources, R.drawable.splash_screen)

    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val fillPaint = Paint(Paint.DITHER_FLAG)

    /**
     * 底部纯色区的颜色，与根布局渐变最后一站（@color/splash_bottom）一致。
     *
     * 图片下沿的淡出带用「同色 + alpha 由 255 线性降到 0」铺一层，
     * 所以这里的色值必须等于图中底部背景色，否则淡出带会显出一条色带。
     */
    private var footerColor: Int = 0xFFF3CAA0.toInt()

    /**
     * 图片底部的纯色淡出带，占「图片显示高度」的比例。
     *
     * 原图底部 y=940..989 是作图工具的「千问AI生成」水印、y=1003..1014 是装饰声波与
     * 假手势条 —— 这些在生成阶段已裁掉；剩下的是卡片下方的柔和阴影，
     * 2.4% 高的淡出带足够盖住，同时确保图里烤死的旧版信息不会透出来。
     */
    private var footerFadeRatio: Float = 0.024f

    /** 底部必须留给版本号的最小高度（px），见 res/values/dimens.xml */
    private val footerMinSpace: Int =
        resources.getDimensionPixelSize(R.dimen.splash_footer_min_space)

    private val srcRect = Rect()
    private val dstRect = Rect()
    private var dstTop = 0

    /**
     * 下沿淡出带用的着色器缓存。
     *
     * onDraw 里**不能**每帧 new 一个 LinearGradient：启动页只画一次，看着无所谓，
     * 但 lint 的 DrawAllocation 会一直报，而且一旦以后有人给这个 View 加了重绘
     * （比如加动画），每帧分配就是实打实的卡顿。这里按 (高度, 颜色) 缓存，
     * 尺寸或颜色没变就直接复用。
     */
    private var fadeShader: LinearGradient? = null
    private var fadeShaderKey: Long = -1L

    fun setFooterColor(color: Int) {
        if (footerColor != color) {
            footerColor = color
            fadeShader = null          // 颜色变了，缓存的渐变作废
            fadeShaderKey = -1L
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bmp = bitmap ?: return
        if (w <= 0 || h <= 0) return

        // 等比缩放：min 保证两个方向都不超出，所以绝不拉伸
        val scale = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val dw = (bmp.width * scale).roundToInt()
        val dh = (bmp.height * scale).roundToInt()

        val left = (w - dw) / 2
        val maxBottom = max(0, h - footerMinSpace)   // 底部给版本号留位
        val bottom = min(dh, maxBottom)              // 图矮就贴到版本号区上沿，图高就裁顶部
        dstTop = bottom - dh                         // 可能为负 = 顶部溢出被裁

        dstRect.set(left, dstTop, left + dw, bottom)
        srcRect.set(0, 0, bmp.width, bmp.height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (dstRect.isEmpty) return

        canvas.drawBitmap(bmp, srcRect, dstRect, artPaint)

        // 1) 图片下沿的淡出带：纯色 → 全透明
        val fade = (dstRect.height() * footerFadeRatio).roundToInt().coerceAtLeast(1)
        val imageBottom = dstRect.bottom
        val fadeStart = imageBottom - fade            // 可能为负，越界部分被 canvas 自动裁掉

        val key = (imageBottom.toLong() shl 32) or fade.toLong()
        if (fadeShader == null || fadeShaderKey != key) {
            fadeShader = LinearGradient(
                0f, imageBottom.toFloat(), 0f, fadeStart.toFloat(),
                footerColor,
                Color.argb(
                    0, Color.red(footerColor), Color.green(footerColor), Color.blue(footerColor)
                ),
                Shader.TileMode.CLAMP
            )
            fadeShaderKey = key
        }
        fillPaint.shader = fadeShader
        canvas.drawRect(0f, fadeStart.toFloat(), width.toFloat(), imageBottom.toFloat(), fillPaint)
        fillPaint.shader = null

        // 2) 图片以下是整块纯色，版本号叠在它上面
        if (imageBottom < height) {
            fillPaint.color = footerColor
            canvas.drawRect(0f, imageBottom.toFloat(), width.toFloat(), height.toFloat(), fillPaint)
        }
    }
}
