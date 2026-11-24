package com.audiobook.audiobookplayer

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.audiobook.audiobookplayer.update.UpdateDialog
import com.audiobook.audiobookplayer.update.UpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 启动页（LAUNCHER 入口）。
 *
 * 展示品牌启动图约 [SPLASH_DELAY_MS] 毫秒后跳转 [BookListActivity]（真正的登录态
 * 检查 / 主入口）。期间并发做一次版本检查：
 *
 * - 有更新 → 先弹更新对话框（强制更新不可跳过）；用户选择「稍后再说」再进应用；
 * - 无更新 / 检查失败 / 超时 → 正常进入应用。**任何失败都不拦截启动**——
 *   服务器不可达时把用户按在启动页，比让他用旧版本更糟。
 *
 * 检查有 [CHECK_TIMEOUT_MS] 上限：服务器不可达时不能让启动页无限等待，
 * 超时即放弃检查（已在下载中的进度由 UpdateDialog 自己负责）。
 *
 * 视觉部分（几何、配色、底部纯色区）见 [SplashArtworkView] 与
 * res/layout/activity_splash.xml；本类只管「入场动画」和「离场跳转」。
 */
class SplashActivity : BaseActivity() {

    /** 防止「超时后跳转」与「用户选择稍后」两条路径重复启动主界面 */
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // 底部纯色区与图内淡出带必须同色，否则两者之间会显出一条色带。
        // 一处取自 colors.xml、一处由代码下发，避免两个地方各写一份十六进制。
        findViewById<SplashArtworkView>(R.id.splash_artwork).setFooterColor(
            ContextCompat.getColor(this, R.color.splash_bottom)
        )

        // 版本号从 BuildConfig 注入。启动图里原本烤死过「版本号：V 2.4.0」，
        // 与 build.gradle.kts 的 versionName 毫无关系，发版后必然对不上；
        // 现在版本号只有 build.gradle.kts 一个来源。
        findViewById<TextView>(R.id.splash_version).text =
            getString(R.string.splash_version, BuildConfig.VERSION_NAME)

        playIntro()

        val startedAt = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            val info = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                UpdateManager.checkForUpdate(this@SplashActivity)
            }
            if (isFinishing || isDestroyed) return@launch

            // 「本次忽略」过的版本不再提示（新版本发布后自动失效）
            val pending = info?.takeIf {
                !UpdateManager.isIgnored(this@SplashActivity, it.versionCode)
            }

            if (pending == null) {
                // 无更新时补足最小展示时长，避免网络极快时品牌页一闪而过。
                // 这里补的是「从 onCreate 起算」的总时长：入场动画本身约占
                // INTRO_DELAY_MS + INTRO_MS ≈ 680ms，1500ms 里留给动画的时间是够的。
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed < SPLASH_DELAY_MS) delay(SPLASH_DELAY_MS - elapsed)
                if (isFinishing || isDestroyed) return@launch
                goMain()
            } else {
                UpdateDialog.show(
                    this@SplashActivity,
                    pending,
                    onSkip = { goMain() },
                    // 强制更新的唯一出口：退出应用
                    onForceExit = { finishAffinity() },
                    // 更新下载失败 / 初始弹窗被取消 → 进入现有安装版本，绝不卡在启动页
                    onGiveUp = { goMain() }
                )
            }
        }
    }

    /**
     * 入场动画：品牌图淡入 + 轻微放大落定，底部两行文字稍晚一步淡入上浮。
     *
     * 刻意做得短（[INTRO_DELAY_MS] + [INTRO_MS] ≈ 680ms）：启动页每多停留 100ms，
     * 用户每次打开应用都要多等 100ms。动画只要让人看出「是有意设计的」即可，
     * 不该抢戏，更不该让人以为应用卡住了。
     *
     * 用 ViewPropertyAnimator（`View.animate()`）而不是补间动画 XML：
     * 前者只改属性、不重绘整棵子树，也不会像旧补间动画那样在结束后把 View
     * 弹回原始位置（fillAfter 的经典闪烁）。
     */
    private fun playIntro() {
        val artwork = findViewById<View>(R.id.splash_artwork)
        val footer = findViewById<View>(R.id.splash_footer)

        // 品牌图：0.94 → 1.00 配合淡入。图中主体（标题与插图）四周留白充足，
        // 6% 的缩放不会把内容推出可视区。
        artwork.alpha = 0f
        artwork.scaleX = INTRO_SCALE_FROM
        artwork.scaleY = INTRO_SCALE_FROM
        artwork.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(INTRO_DELAY_MS)
            .setDuration(INTRO_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 底部文字：晚半步入场，从下往上浮 12dp，权重明显低于品牌图
        val rise = resources.displayMetrics.density * 12f
        footer.alpha = 0f
        footer.translationY = rise
        footer.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(INTRO_DELAY_MS + INTRO_MS / 2)
            .setDuration(INTRO_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun goMain() {
        if (navigated || isFinishing) return
        navigated = true
        startActivity(Intent(this, BookListActivity::class.java))
        finish()
        playExitTransition()
    }

    /**
     * 离场：启动页整体放大淡出，避免跳转时「啪」地硬切。
     *
     * 用 overridePendingTransition（API 1 起可用）而不是 API 34 才提供的
     * overrideActivityTransition —— 后者在 minSdk 24 的机器上直接不生效，
     * 会出现「新版本有动画、老机器没有」的分裂表现。
     */
    @Suppress("DEPRECATION")
    private fun playExitTransition() {
        overridePendingTransition(0, R.anim.splash_exit)
    }

    /**
     * 返回键退出时同样播放淡出，否则用户看到的是启动页突然消失。
     * 这里不经过 [goMain]，只补视觉。
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        playExitTransition()
    }

    companion object {
        /** 品牌页最短展示时长（从 onCreate 起算，含入场动画） */
        private const val SPLASH_DELAY_MS = 1500L
        private const val CHECK_TIMEOUT_MS = 2000L

        /** 入场动画：起始延迟 + 时长 ≈ 680ms */
        private const val INTRO_DELAY_MS = 60L
        private const val INTRO_MS = 620L
        private const val INTRO_SCALE_FROM = 0.94f
    }
}
