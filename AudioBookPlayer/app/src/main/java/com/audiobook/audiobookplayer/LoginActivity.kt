package com.audiobook.audiobookplayer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.audiobook.audiobookplayer.network.AuthManager
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LoginActivity"

class LoginActivity : BaseActivity() {

    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var btnRegister: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        AuthManager.init(this)

        if (AuthManager.isAuthenticated) {
            navigateToMain()
            return
        }

        initViews()
        setupListeners()
    }

    private fun initViews() {
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnRegister = findViewById(R.id.btnRegister)
        progressBar = findViewById(R.id.progressBar)
        setupLegalNotice()
    }

    /**
     * 登录页底部的协议声明：「登录即表示同意《用户协议》和《隐私政策》」。
     *
     * 这是应用商店审核的硬性卡点：登录/注册页必须能看到协议入口，且点得开正文。
     * 只放纯文本不行 —— 审核会逐条点。所以两处书名号各挂一个 [ClickableSpan]，
     * 分别打开对应的本地协议页（正文在 assets/legal/ 下，离线可读）。
     *
     * 用 ClickableSpan 而不是两个并排的按钮：这句话是**一句话**，
     * 拆成按钮会读成「同意」+ 两个按钮，文案支离破碎；审核看的是语境完整。
     */
    private fun setupLegalNotice() {
        val tv = findViewById<TextView>(R.id.tvLegalNotice)
        val termsText = "《用户协议》"
        val privacyText = "《隐私政策》"
        // ⚠️ 模板必须带花括号：Kotlin 的标识符允许中文字符，
        // 「$termsText 后面直接跟中文」会被当成一个叫 termsText和 的变量，
        // 编译期报 Unresolved reference: termsText和。
        val full = "登录即表示同意${termsText}和${privacyText}"
        val spannable = SpannableString(full)
        // 链接色取主题 accent：浅色主题是暖金，夜间主题由 values-night 覆盖成亮一档，
        // 硬写 @color/accent 的十六进制会在夜间主题下对比度不足。
        val linkColor = ContextCompat.getColor(this, R.color.accent)

        fun attach(target: String, kind: String) {
            val start = full.indexOf(target)
            if (start < 0) return
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        startActivity(LegalActivity.intent(this@LoginActivity, kind))
                    }

                    // 默认 ClickableSpan 是蓝色 + 下划线，与整页暖金调性冲突；
                    // 下划线去掉后靠颜色区分，句子里本来就有书名号，可点性依然清楚。
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = linkColor
                        ds.isUnderlineText = false
                    }
                },
                start,
                start + target.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        attach(termsText, LegalActivity.KIND_TERMS)
        attach(privacyText, LegalActivity.KIND_PRIVACY)

        tv.text = spannable
        tv.movementMethod = LinkMovementMethod.getInstance()
        // LinkMovementMethod 会带一个选中高亮底色，点完在页面上留一块灰斑
        tv.highlightColor = Color.TRANSPARENT
    }

    private fun setupListeners() {
        btnLogin.setOnClickListener {
            val username = etUsername.text?.toString()?.trim()
            val password = etPassword.text?.toString()?.trim()

            if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
                Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            performLogin(username, password)
        }

        btnRegister.setOnClickListener {
            showRegisterDialog()
        }
    }

    private fun performLogin(username: String, password: String) {
        setLoading(true)

        lifecycleScope.launch {
            val result = AuthManager.login(username, password)

            withContext(Dispatchers.Main) {
                setLoading(false)

                result.onSuccess { response ->
                    if (response.success) {
                        Toast.makeText(this@LoginActivity, "登录成功", Toast.LENGTH_SHORT).show()
                        navigateToMain()
                    } else {
                        showError(response.message?.takeIf { it.isNotBlank() } ?: "登录失败，请稍后重试")
                    }
                }.onFailure { exception ->
                    showError("登录失败：${exception.toUserMessage()}")
                }
            }
        }
    }

    private fun showRegisterDialog() {
        android.util.Log.d(TAG, "开始创建注册对话框")

        try {
            val dialogView = layoutInflater.inflate(R.layout.dialog_register, null)
            android.util.Log.d(TAG, "对话框视图创建成功: $dialogView")

            val etRegUsername = dialogView.findViewById<TextInputEditText>(R.id.etRegUsername)
            val etRegPassword = dialogView.findViewById<TextInputEditText>(R.id.etRegPassword)
            val etRegEmail = dialogView.findViewById<TextInputEditText>(R.id.etRegEmail)

            val builder = AlertDialog.Builder(this)
            builder.setTitle("注册新用户")
                .setView(dialogView)
                .setPositiveButton("注册") { _, _ ->
                    val username = etRegUsername.text?.toString()?.trim()
                    val password = etRegPassword.text?.toString()?.trim()
                    val email = etRegEmail.text?.toString()?.trim()

                    if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
                        Toast.makeText(this, "请填写必填项", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (username.length < 3 || username.length > 50) {
                        Toast.makeText(this, "用户名长度需为 3-50 位", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (!username.matches(Regex("^[a-zA-Z0-9_]{3,50}$"))) {
                        Toast.makeText(this, "用户名只能包含字母、数字和下划线", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (password.length < 6) {
                        Toast.makeText(this, "密码长度至少为 6 位", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (email != null && email.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                        Toast.makeText(this, "邮箱格式不正确", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    performRegister(username, password, email ?: "")
                }
                .setNegativeButton("取消") { dialog, _ ->
                    android.util.Log.d(TAG, "对话框取消按钮被点击")
                    dialog.dismiss()
                }

            val dialog = builder.create()
            android.util.Log.d(TAG, "对话框创建成功，准备显示")

            // 在显示前设置对话框属性
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)

            // 先显示对话框
            dialog.show()

            // 使用 post 确保对话框完全显示后再调整窗口大小
            dialog.window?.decorView?.post {
                dialog.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.9).toInt(),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                android.util.Log.d(TAG, "对话框窗口已调整，宽度: ${dialog.window?.attributes?.width}")
            }

            android.util.Log.d(TAG, "对话框已调用 show()")
        } catch (e: Exception) {
            Log.e(TAG, "显示注册对话框失败", e)
            showError("显示对话框失败：${e.toUserMessage()}")
        }
    }

    private fun performRegister(username: String, password: String, email: String) {
        setLoading(true)

        lifecycleScope.launch {
            val result = AuthManager.register(username, password, email)

            withContext(Dispatchers.Main) {
                setLoading(false)

                result.onSuccess { response ->
                    if (response.success) {
                        Toast.makeText(this@LoginActivity, "注册成功", Toast.LENGTH_SHORT).show()
                        navigateToMain()
                    } else {
                        showError(response.message?.takeIf { it.isNotBlank() } ?: "注册失败，请稍后重试")
                    }
                }.onFailure { exception ->
                    showError("注册失败：${exception.toUserMessage()}")
                }
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !isLoading
        btnRegister.isEnabled = !isLoading
    }

    private fun navigateToMain() {
        // 登录成功即补一次「服务端任务 → 本地 tasks 表」同步。
        //
        // 为什么放在这里：LoginActivity 有三个入口（游客门禁 requireLogin、「我的」页、
        // 帖子详情），但"登录确实成功"只有这一个收口。游客启动时 MainActivity.onCreate
        // 的那次同步会因未登录而跳过，不在这里补的话用户要等下次冷启动才看得到历史任务。
        // 与启动那次由 TaskSyncManager 内部的在途闸门去重。
        TaskSyncManager.syncAsync(application)
        // 登录成功即补一次跨端单值偏好的拉+推（游客启动时那次因未登录跳过）。
        UserSettingsSyncManager.syncAsync(application)
        // 由游客门禁（MainActivity.requireLogin）以「仅返回结果」方式拉起时：
        // 登录/注册成功不新建 MainActivity（否则叠一个实例），而是回 RESULT_OK 让调用方
        // 继续执行它被拦截的原操作（自动返回原功能）。
        if (intent.getBooleanExtra("RETURN_ONLY", false)) {
            setResult(RESULT_OK)
            finish()
            return
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

