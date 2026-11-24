package com.audiobook.audiobookplayer

import android.content.Context
import android.widget.Toast
import com.audiobook.audiobookplayer.BuildConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Response

/*
 * 统一网络错误封装。
 *
 * 各 Fragment/Activity 各自在 catch 里写 `Toast.makeText(... "${e.message}" ...)`，
 * 文案不统一、且服务端的业务 message（如 402 的「余额不足」）不会被解析展示。
 * 这里收敛为：
 *  - [Throwable.toUserMessage]：把任意异常映射成用户可读的中文文案，
 *    优先采用服务端响应体里的 `message` 字段（按业务 code/message 映射）。
 *  - [Context.showError]：统一的 Toast 提示；`silent=true` 时不展示，
 *    专供 TtsFragment 批量提交被「余额不足」中断等无需打扰用户的场景。
 *
 * 注意：401/402 已在 RetrofitClient 的拦截器里统一处理（清登录态 / 弹充值引导），
 * 本类只承接其余通用错误；若 HttpException 体里带了业务 message，仍会优先展示服务端原文。
 */
/**
 * **业务性失败**：`message` 本身就是面向用户的成品文案（中文、可展示），
 * 例如「《xx》已在列表中，无需重复添加」「请先登录」「下载失败：HTTP 404」。
 *
 * ## 为什么需要单独的类型
 *
 * 这类失败既不是网络异常也不是 HTTP 错误 —— 它是仓库层在做本地前置校验时
 * 主动拒绝的（去重命中、未登录、服务端返回 success=false 等）。
 * 早先它们用 `IllegalStateException` 承载，而 [toUserMessage] 的 `else` 分支
 * 在 release 包里一律输出「未知错误，请稍后重试」，于是：
 *
 * - 用户重复添加一条参考音频 → 看到「未知错误，请稍后重试」（完全误导，
 *   会以为 App 坏了而反复重试）；
 * - 真正的技术性 RuntimeException（如 `Fragment not attached`）与业务提示
 *   无法区分，只能统一压成兜底文案。
 *
 * 显式声明类型后，两边各归各位：`BusinessException` 原样上屏，
 * 其余异常继续走兜底（不泄露英文堆栈与内部 URL）。
 *
 * ⚠️ 不要用它包装技术异常 —— 只用于「你其实想告诉用户这件事」的场景。
 */
class BusinessException(message: String) : Exception(message)

/** [toUserMessage] 的兜底文案（BusinessException 的 message 为空时也用它）。 */
private const val FALLBACK_MESSAGE = "操作未完成，请稍后重试"

/**
 * 把任意异常映射成用户可读的中文文案（映射规则见文件头说明）。
 *
 * 顺序有意义：[BusinessException] 必须最先命中 —— 它的 message 是成品文案，
 * 落到后面的兜底分支就会被压成「未知错误」。
 */
fun Throwable.toUserMessage(): String {
    return when (this) {
        // 业务失败：`message` 是写给用户看的中文原文（如「《xx》已在列表中」），
        // 必须原样上屏。放在最前面，避免被下面 IOException / else 兜底吞成「未知错误」。
        is BusinessException -> message?.takeIf { it.isNotBlank() } ?: FALLBACK_MESSAGE
        is UnknownHostException -> "网络连接失败，请检查网络后重试"
        is SocketTimeoutException -> "连接超时，请稍后重试"
        is HttpException -> {
            val serverMsg = readServerMessage(this)
            val code = code()
            when (code) {
                401 -> serverMsg.ifBlank { "登录已过期，请重新登录" }
                402 -> serverMsg.ifBlank { "余额不足，请先充值" }
                403 -> serverMsg.ifBlank { "没有权限执行该操作" }
                404 -> serverMsg.ifBlank { "请求的资源不存在" }
                500 -> serverMsg.ifBlank { "服务器开小差了，请稍后再试" }
                502, 503, 504 -> serverMsg.ifBlank { "服务暂时不可用，请稍后重试" }
                else -> serverMsg.ifBlank { "请求失败（HTTP $code）" }
            }
        }
        // IOException / 兜底分支不能把 OkHttp 英文异常原文（可能含完整 URL）上屏。
        // release 包只留中文兜底；原始 message 仅在 BuildConfig.DEBUG 下附加以利排查。
        is IOException -> if (BuildConfig.DEBUG) "网络异常：${message ?: ""}" else "网络异常，请检查连接"
        else -> if (BuildConfig.DEBUG) "未知错误：${message ?: ""}" else "未知错误，请稍后重试"
    }
}

/**
 * 服务端 `message` 能不能直接上屏：空白视作没有，**字面量 `"null"` 同样视作没有**。
 *
 * ## 为什么要拦 `"null"`
 *
 * 服务端有三处 `put("message", e.getMessage())`（AuthController / GlobalExceptionHandler /
 * TtsControllerSupport），而 `Throwable.getMessage()` 本身**可空** —— 异常没有 message 时
 * 响应体就是 `{"success":false,"message":null}`。Android 的 `JSONObject.optString`
 * 走 `String.valueOf(JSONObject.NULL)`，读出来是字符串 `"null"`（不是空串），
 * 于是 `isNotBlank()` 判真、兜底文案被跳过，用户看到的就是一个孤零零的 `null`。
 *
 * @return 可上屏的文案；不可用时返回 null，由调用方回退到通用文案
 */
internal fun usableServerMessage(raw: String?): String? =
    raw?.takeIf { it.isNotBlank() && !it.trim().equals("null", ignoreCase = true) }

/**
 * 从错误响应体 JSON 文本中提取服务端业务 `message` 字段。
 *
 * 服务端统一以 `{success, message}` 结构返回错误；本函数只取 message，
 * 不把整个 errorBody 回传给 UI（可能含堆栈等内部信息）。
 * 收敛了原先 AuthManager.parseServerMessage 与 BookListFragment.parseErrorMessage
 * 两份等价实现。
 *
 * @return message 原文；body 为空 / 无该字段 / 解析失败时返回 null，由调用方回退到通用文案
 */
fun extractServerMessage(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return try {
        usableServerMessage(JSONObject(body).optString("message", ""))
    } catch (_: Exception) {
        null
    }
}

/** 尝试从 HttpException 的错误体里取出服务端返回的 `message` 业务字段。 */
private fun readServerMessage(e: HttpException): String {
    return try {
        val body = e.response()?.errorBody() ?: return ""
        // errorBody 只能消费一次；用 source().peek() 读取而不消耗底层流，
        // 使同一个 Response 多次调用 errorMessage() 仍能拿到服务端文案（否则第二次静默为空）。
        val text = body.source().peek().readString(Charsets.UTF_8)
        extractServerMessage(text) ?: ""
    } catch (_: Exception) {
        ""
    }
}

/**
 * 非 2xx 响应（`response.isSuccessful == false`，尚不是异常）的用户文案。
 *
 * 转成 HttpException 后复用 [toUserMessage]，这样才能解析服务端 errorBody 里的
 * 业务 message —— 不要直接用 `response.message()`，那是 HTTP 状态行的文本
 * （"Bad Request" / "Internal Server Error"），对用户毫无信息量，
 * 还把服务端真正的失败原因（如「余额不足」）整个吞掉了。
 */
fun <T> Response<T>.errorMessage(): String = HttpException(this).toUserMessage()

/** 统一的错误提示（Toast）。[silent]=true 时不展示（用于无需打扰用户的中断场景）。 */
fun Context.showError(message: String, silent: Boolean = false) {
    if (silent) return
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

/** 统一错误提示的重载：直接吃一个异常，内部映射成用户文案。 */
fun Context.showError(throwable: Throwable, silent: Boolean = false) {
    showError(throwable.toUserMessage(), silent)
}
