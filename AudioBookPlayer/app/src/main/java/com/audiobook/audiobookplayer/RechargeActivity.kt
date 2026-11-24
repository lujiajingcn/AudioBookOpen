package com.audiobook.audiobookplayer

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.audiobook.audiobookplayer.model.CreditPackageResp
import com.audiobook.audiobookplayer.model.PaymentChannel
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import com.audiobook.audiobookplayer.network.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

private const val TAG = "RechargeActivity"

class RechargeActivity : BaseActivity() {

    private lateinit var tvBalance: TextView
    private lateinit var llPackages: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    private var packages: List<CreditPackageResp> = emptyList()
    private var channels: List<PaymentChannel> = emptyList()

    /** 正在轮询的订单号，用于取消轮询 */
    private var pollingOrderNo: String? = null

    /** 当前轮询协程句柄；每次重新轮询前先取消旧的，避免多个循环并发打接口 */
    private var pollingJob: Job? = null

    /** 创建订单协程句柄：防止用户连点套餐触发多次下单 */
    private var orderJob: Job? = null

    /** 当前展示中的对话框：统一由 showManaged 管理，避免叠加 + onDestroy 回收 */
    private var currentDialog: AlertDialog? = null

    /** 支付弹窗中的 WebView 引用（onDestroy 时必须 destroy，否则泄漏 Activity） */
    private var paymentWebView: WebView? = null

    /**
     * 支付地址白名单：仅信任「应用自身后端」与「已知支付/授权域名」。
     * 校验不通过的 payUrl 不交给 WebView 加载，避免加载任意第三方 JS（钓鱼/投毒）。
     */
    private val PAYMENT_TRUSTED_HOSTS = setOf(
        "api.weixin.qq.com", "wx.qq.com", "qpay.qq.com", "wxpay.wxutil.com",
        // 微信 H5/JSAPI 支付的标准跳转域名
        "wx.tenpay.com",
        // 支付宝各收银台子域（精确匹配不覆盖子域，必须逐个列出真实支付域名）
        "alipay.com", "www.alipay.com", "openapi.alipay.com", "mapi.alipay.com",
        "mclient.alipay.com", "excashier.alipay.com", "authweb.alipay.com"
    )

    /**
     * 除 HTTPS 外，仅允许可信支付域名配套的官方 App scheme（支付宝 alipays://、微信 weixin://）。
     * intent:// / market:// / file:// 等会被系统路由到任意 App 或本地文件，一律不放行。
     */
    private val PAYMENT_ALLOWED_APP_SCHEMES = setOf("alipays", "weixin")

    /** payUrl 是否来自可信域名（同源后端或已知支付域名） */
    private fun isPayUrlTrusted(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return try {
            val host = Uri.parse(url).host ?: return false
            // ServerConfig 只保证补尾部斜杠、不保证带 scheme：用户输入 192.168.1.5:8080
            // 时 Uri.parse().host 为 null，同源后端会被误判不可信——解析失败时补 http:// 重试
            var serverHost = Uri.parse(ServerConfig.baseUrl).host
            if (serverHost == null && !ServerConfig.baseUrl.startsWith("http", ignoreCase = true)) {
                serverHost = Uri.parse("http://" + ServerConfig.baseUrl).host
            }
            if (serverHost != null && host.equals(serverHost, ignoreCase = true)) return true
            host in PAYMENT_TRUSTED_HOSTS
        } catch (e: Exception) {
            false
        }
    }

    /**
     * redirect 交给系统浏览器前的地址校验：
     * 先过 [isPayUrlTrusted] 的域名白名单，再校验 scheme——
     * - 统一要求 https：http 明文会被中间人篡改/钓鱼，intent://、market://、
     *   file:// 等非 http scheme 会被系统直接路由到任意 App，构成意图劫持；
     * - 唯一例外是白名单支付域名自己的官方 App scheme（alipays:// / weixin://），
     *   这是 H5 收银台唤起支付宝/微信 App 的正常链路。
     * 校验不通过时绝不 startActivity，降级为「复制链接」引导。
     */
    private fun isRedirectUrlAllowed(url: String?): Boolean {
        if (url.isNullOrEmpty() || !isPayUrlTrusted(url)) return false
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme == "https") return true
            val host = uri.host ?: return false
            host in PAYMENT_TRUSTED_HOSTS && scheme in PAYMENT_ALLOWED_APP_SCHEMES
        } catch (e: Exception) {
            false
        }
    }

    /**
     * payUrl 未通过跳转校验时的降级引导：展示原文链接 + 「复制链接」，
     * 与 WebView 拒绝加载非白名单地址的口径保持一致，由用户自行确认域名后完成支付。
     */
    private fun showUntrustedPayUrlDialog(payUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("无法自动跳转支付")
            .setMessage(
                "支付地址不在信任列表内，或不是 HTTPS 地址，为保证资金安全已阻止自动跳转。\n\n" +
                    "请复制链接后粘贴到浏览器中，确认域名无误再完成支付：\n$payUrl"
            )
            .setPositiveButton("复制链接") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("payUrl", payUrl))
                Toast.makeText(this, "支付链接已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create().also { showManaged(it) }
    }

    /**
     * 统一展示并追踪对话框。
     * 先解散上一个未关闭的对话框（避免叠加），记录新对话框到 [currentDialog]，
     * 并在 onDestroy 时一并 dismiss——否则对话框持有的 Activity 引用会在退出后泄漏
     * （WindowLeaked），configuration change 时尤为明显。
     */
    private fun showManaged(dialog: AlertDialog) {
        currentDialog?.takeIf { it.isShowing }?.dismiss()
        currentDialog = dialog
        dialog.setOnDismissListener { if (currentDialog === dialog) currentDialog = null }
        dialog.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recharge)

        tvBalance = findViewById(R.id.tvBalance)
        llPackages = findViewById(R.id.llPackages)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        findViewById<View>(R.id.btnLedger).setOnClickListener {
            // AdminCredits 台账前端展示入口
            startActivity(Intent(this, CreditLedgerActivity::class.java))
        }

        loadAll()
    }

    private fun loadAll() {
        val token = AuthManager.token
        if (token == null) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val balanceResp = RetrofitClient.creditApi.getMyCredits()
                val pkgResp = RetrofitClient.creditApi.getPackages()
                val chResp = RetrofitClient.creditApi.getChannels()

                if (balanceResp.isSuccessful) {
                    val b = balanceResp.body()
                    withContext(Dispatchers.Main) {
                        tvBalance.text = (b?.balance ?: 0).toString()
                    }
                }
                if (pkgResp.isSuccessful) {
                    packages = pkgResp.body()?.packages ?: emptyList()
                }
                if (chResp.isSuccessful) {
                    channels = chResp.body()?.channels ?: emptyList()
                }
                withContext(Dispatchers.Main) {
                    renderPackages()
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "加载失败：${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
            }
        }
    }

    private fun renderPackages() {
        llPackages.removeAllViews()
        if (packages.isEmpty()) {
            val tv = TextView(this)
            tv.text = "暂无可用套餐"
            tv.setPadding(0, 16, 0, 16)
            llPackages.addView(tv)
            return
        }
        for (pkg in packages) {
            llPackages.addView(buildPackageRow(pkg))
        }
    }

    private fun buildPackageRow(pkg: CreditPackageResp): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundResource(android.R.drawable.btn_default)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 12)
            layoutParams = params
        }

        val title = TextView(this).apply {
            text = pkg.name ?: "套餐"
            textSize = 18f
            setTextColor(0xFF000000.toInt())
        }
        val sub = TextView(this).apply {
            text = "¥%.2f　到账 %d 积分（含赠送 %d）".format(
                pkg.price ?: BigDecimal.ZERO, pkg.totalCredits, pkg.bonusCredits
            )
            textSize = 14f
            setTextColor(0xFF666666.toInt())
        }
        val btn = Button(this).apply {
            text = "充值"
            setOnClickListener { onPackageClick(pkg) }
        }
        row.addView(title)
        row.addView(sub)
        row.addView(btn)
        return row
    }

    private fun onPackageClick(pkg: CreditPackageResp) {
        if (channels.isEmpty()) {
            Toast.makeText(this, "暂无可用的支付方式", Toast.LENGTH_SHORT).show()
            return
        }
        if (channels.size == 1) {
            createOrder(pkg, channels[0].channel)
            return
        }
        val names = channels.map { it.name ?: it.channel ?: "未知" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择支付方式")
            .setItems(names) { _, which -> createOrder(pkg, channels[which].channel) }
            .setNegativeButton("取消", null)
            .create().also { showManaged(it) }
    }

    private fun createOrder(pkg: CreditPackageResp, channel: String?) {
        // 订单创建进行中忽略重复点击，连点套餐会创建出多笔订单、重复扣费。
        if (orderJob?.isActive == true) return
        AuthManager.token ?: return  // token 由 AuthInterceptor 统一注入，无需手传
        progressBar.visibility = View.VISIBLE
        llPackages.isEnabled = false // 下单期间禁用套餐列表，等价于禁用提交按钮
        orderJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = mutableMapOf<String, Any>("packageId" to pkg.id)
                // 未选渠道时务必传 null（省略字段），后端以 null 视为“未指定渠道”；
                // 传空串 "" 会被当成真实渠道名导致下单失败。
                if (channel != null) body["channel"] = channel
                val resp = RetrofitClient.creditApi.createOrder(body)
                withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }

                if (resp.isSuccessful && resp.body()?.success == true) {
                    val data = resp.body()!!
                    // handlePrepay 内部会 inflate 布局 / 弹 AlertDialog / 改进度条 / 弹 Toast，
                    // 全部是 UI 操作，必须切回主线程——此处协程运行在 Dispatchers.IO，
                    // 直接调用会抛 CalledFromWrongThreadException，qrcode/mock 支付路径必崩。
                    withContext(Dispatchers.Main) {
                        handlePrepay(data)
                    }
                } else {
                    val msg = resp.body()?.message
                        ?: resp.errorBody()?.string()
                        ?: "创建订单失败"
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@RechargeActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showError("创建订单异常：${e.toUserMessage()}")
                }
            } finally {
                orderJob = null
                withContext(Dispatchers.Main) { llPackages.isEnabled = true }
            }
        }
    }

    private fun handlePrepay(data: com.audiobook.audiobookplayer.model.PrepayResponse) {
        val orderNo = data.orderNo ?: run {
            Toast.makeText(this, "订单号缺失", Toast.LENGTH_SHORT).show()
            return
        }
        when (data.payType) {
            "mock" -> {
                // 模拟支付：直接确认后轮询
                confirmMock(orderNo)
            }
            "redirect" -> {
                // redirect 必须走顶层浏览器跳转，绝不能用 WebView/iframe
                // （否则构成钓鱼面，且违反后端契约）。同时后台轮询到账。
                //
                // 跳转前必须过白名单 + scheme 校验：任意 payUrl（intent:// / market:// /
                // 任意 http 地址）一旦被 startActivity(ACTION_VIEW) 交给系统，就可拉起任意
                // App 或跳到钓鱼页；不通过校验就绝不跳转，降级为「复制链接」引导。
                val payUrl = data.payUrl
                if (payUrl.isNullOrEmpty()) {
                    Log.w(TAG, "redirect 分支缺少 payUrl，仅轮询订单状态")
                } else if (isRedirectUrlAllowed(payUrl)) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(payUrl)))
                } else {
                    Log.w(TAG, "拒绝跳转到非白名单/非 HTTPS 支付地址: $payUrl")
                    showUntrustedPayUrlDialog(payUrl)
                }
                startPolling(orderNo)
            }
            else -> {
                // qrcode：用 ZXing 渲染二维码（showPaymentDialog 内部处理），并提供「我已支付」轮询
                showPaymentDialog(data)
                startPolling(orderNo)
            }
        }
    }

    private fun confirmMock(orderNo: String) {
        AuthManager.token ?: return  // token 由 AuthInterceptor 统一注入，无需手传
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.creditApi.confirmMock(orderNo)
                withContext(Dispatchers.Main) { progressBar.visibility = View.GONE }
                if (resp.isSuccessful) {
                    onPaidSuccess("模拟支付成功，积分已到账")
                } else {
                    val msg = resp.errorBody()?.string() ?: "模拟支付失败"
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@RechargeActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showError("模拟支付异常：${e.toUserMessage()}")
                }
            }
        }
    }

    private fun showPaymentDialog(data: com.audiobook.audiobookplayer.model.PrepayResponse) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_payment, null)
        val webPay = dialogView.findViewById<WebView>(R.id.webPay)
        paymentWebView = webPay
        // 未设 WebViewClient 时，WebView 默认把导航交给系统浏览器，
        // 支付页根本不会显示在对话框里。设默认 WebViewClient 让导航在 WebView 内处理，
        // 仅白名单内的跳转放行（其余交给系统浏览器打开）
        webPay.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView?,
                request: android.webkit.WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString()
                return if (url != null && isRedirectUrlAllowed(url)) false else true
            }
        }
        val ivQr = dialogView.findViewById<ImageView>(R.id.ivQr)
        val tvPayUrl = dialogView.findViewById<TextView>(R.id.tvPayUrl)
        val tvAmount = dialogView.findViewById<TextView>(R.id.tvPayAmount)
        val btnCopy = dialogView.findViewById<Button>(R.id.btnCopy)
        val btnPaid = dialogView.findViewById<Button>(R.id.btnPaid)
        val tvPayStatus = dialogView.findViewById<TextView>(R.id.tvPayStatus)

        val payUrl = data.payUrl ?: ""
        tvAmount.text = "套餐：${data.packageName ?: ""}　应付：¥%.2f".format(data.amount ?: BigDecimal.ZERO)
        tvPayUrl.text = payUrl
        // 仅对白名单内可信域名开启 JavaScript，避免加载任意第三方 JS
        val payUrlTrusted = isPayUrlTrusted(payUrl)
        webPay.settings.javaScriptEnabled = payUrlTrusted
        // 关闭文件/内容访问与混合内容，避免 WebView 越权读本地文件或降级到明文加载
        webPay.settings.apply {
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        // 两 setter 在 API 30 标记弃用（默认即 false）；仅 API < 30 需显式关闭，避免旧系统上的不安全默认
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            webPay.settings.apply {
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }
        }
        if (payUrl.isNotEmpty() && data.payType == "qrcode") {
            // 微信 Native：code_url 是 weixin:// 协议，WebView 打不开也展示不了，
            // 用 ZXing 生成二维码让用户扫码支付
            webPay.visibility = View.GONE
            tvPayUrl.visibility = View.GONE
            ivQr.visibility = View.VISIBLE
            renderQrCode(ivQr, payUrl)
        } else if (payUrl.isNotEmpty()) {
            if (payUrlTrusted) {
                webPay.loadUrl(payUrl)
            } else {
                // 非白名单地址：拒绝交给 WebView 加载，引导用户复制链接后到浏览器完成支付
                Log.w(TAG, "拒绝加载非白名单支付地址: $payUrl")
                webPay.loadData(
                    "<p>支付地址不在信任列表内，无法在本页直接完成支付。</p>" +
                        "<p>请点击下方「复制链接」按钮，然后粘贴到浏览器中打开完成支付。</p>",
                    "text/html", "utf-8"
                )
            }
        } else {
            webPay.loadData("<p>无支付链接</p>", "text/html", "utf-8")
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .setNegativeButton("稍后支付") { d, _ -> d.dismiss() }
            .create().also { showManaged(it) }

        btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("payUrl", payUrl))
            Toast.makeText(this, "支付链接已复制", Toast.LENGTH_SHORT).show()
        }
        btnPaid.setOnClickListener {
            val orderNo = pollingOrderNo
            if (orderNo == null) {
                tvPayStatus.text = "请先完成下单"
                return@setOnClickListener
            }
            tvPayStatus.text = "正在确认支付结果..."
            // 立即触发一次轮询；启动失败（登录过期等）时同步复位状态文案，
            // 避免永远停在「正在确认支付结果...」
            if (!startPolling(orderNo)) {
                tvPayStatus.text = "登录已过期，请重新登录"
            }
        }
    }

    /**
     * 用 ZXing 把支付链接渲染成二维码 Bitmap。
     * 逐像素构造走 IntArray + createBitmap 单次写入；外围留 40px 白边（静区），
     * 否则贴边二维码部分扫码器识别率很低。计算量在后台线程做。
     */
    private fun renderQrCode(target: ImageView, content: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val qrSize = 700
                val margin = 40
                val total = qrSize + margin * 2
                val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                    content, com.google.zxing.BarcodeFormat.QR_CODE, qrSize, qrSize
                )
                val pixels = IntArray(total * total)
                for (y in 0 until total) {
                    val my = y - margin
                    val rowOffset = y * total
                    for (x in 0 until total) {
                        val mx = x - margin
                        val dark = my in 0 until qrSize && mx in 0 until qrSize && matrix.get(mx, my)
                        pixels[rowOffset + x] = if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    }
                }
                val bitmap = android.graphics.Bitmap.createBitmap(
                    pixels, total, total, android.graphics.Bitmap.Config.RGB_565
                )
                withContext(Dispatchers.Main) { target.setImageBitmap(bitmap) }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@RechargeActivity,
                        "二维码生成失败，可复制链接到微信打开",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** 启动订单状态轮询；返回是否成功启动（token 缺失时失败并提示） */
    private fun startPolling(orderNo: String): Boolean {
        val token = AuthManager.token
        if (token == null) {
            // 未登录（token 过期被清）：不进入轮询状态，避免显示假的「正在确认」
            Toast.makeText(this, "登录已过期，请重新登录后再确认支付", Toast.LENGTH_LONG).show()
            return false
        }
        pollingOrderNo = orderNo
        // 取消上一次轮询（用户可能多次点击「我已支付」），否则会叠加多个循环
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            var attempts = 0
            while (isActive && attempts < 40) {
                attempts++
                try {
                    val resp = RetrofitClient.creditApi.getOrderStatus(orderNo)
                    if (resp.isSuccessful) {
                        val status = resp.body()?.get("status") as? String
                        if (status == "PAID") {
                            onPaidSuccess("支付成功，积分已到账")
                            return@launch
                        } else if (status == "CLOSED") {
                            // 明确告知失败原因（用户取消 / 超时 / 渠道下单失败），避免用户对着「已关闭」不知所措
                            val failReason = resp.body()?.get("failReason") as? String
                            val msg = if (!failReason.isNullOrBlank()) failReason else "订单已关闭，支付未完成"
                            withContext(Dispatchers.Main) {
                                showError(msg)
                            }
                            return@launch
                        }
                    }
                } catch (e: CancellationException) {
                    // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                    throw e
                } catch (e: Exception) {
                    // 轮询失败不中断，继续重试
                }
                delay(3000)
            }
            // 40 次（约 2 分钟）仍未得到终态：给出明确反馈，
            // 避免页面永远停在「正在确认支付结果...」却没有任何后续动作
            if (isActive) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@RechargeActivity,
                        "支付结果确认超时，请回到本页后重新点击「我已支付」或稍后刷新余额",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        return true
    }

    /**
     * 从浏览器支付返回时恢复轮询：redirect 流程在浏览器打开时就开始了 2 分钟轮询，
     * 用户在支付宝/微信里停留超过该窗口后回到本页会停在「正在确认」——这里检测到
     * 仍有订单在途且轮询已结束时自动重启，避免用户卡死。
     */
    override fun onResume() {
        super.onResume()
        val orderNo = pollingOrderNo
        if (orderNo != null && (pollingJob == null || !pollingJob!!.isActive)) {
            Log.d(TAG, "onResume 恢复订单轮询: $orderNo")
            startPolling(orderNo)
        }
    }

    private fun onPaidSuccess(message: String) {
        pollingOrderNo = null
        // 刷新余额后返回结果，让来源页（个人中心/合成页）刷新积分
        val token = AuthManager.token
        lifecycleScope.launch(Dispatchers.IO) {
            if (token != null) {
                try {
                    val resp = RetrofitClient.creditApi.getMyCredits()
                    if (resp.isSuccessful) {
                        val b = resp.body()?.balance ?: 0
                        withContext(Dispatchers.Main) { tvBalance.text = b.toString() }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* ignore */ }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@RechargeActivity, message, Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    override fun onDestroy() {
        pollingOrderNo = null
        pollingJob?.cancel()
        orderJob?.cancel()
        // 回收对话框，释放其对 Activity 的强引用，避免 WindowLeaked / 内存泄漏
        currentDialog?.takeIf { it.isShowing }?.dismiss()
        currentDialog = null
        // 销毁 WebView，释放其对 Activity 的强引用，避免内存泄漏
        paymentWebView?.apply {
            loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
            clearHistory()
            removeAllViews()
            destroy()
        }
        paymentWebView = null
        super.onDestroy()
    }
}

