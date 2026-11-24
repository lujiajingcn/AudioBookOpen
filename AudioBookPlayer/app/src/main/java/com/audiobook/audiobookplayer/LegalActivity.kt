package com.audiobook.audiobookplayer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView

/**
 * 用户协议 / 隐私政策的阅读页。
 *
 * 为什么用本地 assets 而不是服务端网页：
 * 协议必须在**离线、未登录**状态下也能打开 —— 登录页（协议入口所在处）本身
 * 就是未登录状态，而这台设备可能压根连不上网。挂在服务端上的协议页在
 * 「第一次装完、还没联网就点开」时是一片白屏，商店审核会直接判为无协议。
 *
 * 为什么关掉 JS：
 * 正文是纯静态 HTML，不需要脚本。协议页是被审核方反复查看的页面，
 * 开 JS 只是白白扩大攻击面（WebView + 远程内容曾是最常见的注入链）。
 */
class LegalActivity : BaseActivity() {

    companion object {
        private const val TAG = "LegalActivity"
        private const val EXTRA_KIND = "LEGAL_KIND"

        const val KIND_TERMS = "terms"
        const val KIND_PRIVACY = "privacy"

        fun intent(context: Context, kind: String): Intent =
            Intent(context, LegalActivity::class.java).putExtra(EXTRA_KIND, kind)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legal)

        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_TERMS
        val isPrivacy = kind == KIND_PRIVACY
        findViewById<TextView>(R.id.tvLegalTitle).text =
            if (isPrivacy) "隐私政策" else "用户协议"

        val web = findViewById<WebView>(R.id.webView)
        web.settings.apply {
            javaScriptEnabled = false          // 纯静态正文，不需要脚本
            allowFileAccess = false            // android_asset 不受此开关限制，别为了它放开全盘文件
            allowContentAccess = false
            // 不跟随系统字体缩放：协议正文按 CSS 排版，放大后容易把长表格挤成一条
            textZoom = 100
        }
        // 协议正文是本地资源，不允许任何跳转把用户带离本页（外部链接一律交给系统浏览器）
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // 返回 true = 本 WebView 不加载，交给系统处理
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                }.getOrDefault(true)
            }
        }
        // 与页面背景同色，避免深色模式下加载瞬间闪一下白底
        web.setBackgroundColor(Color.TRANSPARENT)

        web.loadUrl("file:///android_asset/legal/${if (isPrivacy) "privacy" else "terms"}.html")
        Log.d(TAG, "打开协议页：$kind")
    }

    override fun onDestroy() {
        // WebView 持有 Activity 的 Context，不显式销毁就是一次确定的内存泄漏
        findViewById<WebView?>(R.id.webView)?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        super.onDestroy()
    }
}
