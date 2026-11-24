package com.audiobook.audiobookplayer.network

import android.util.Log
import com.audiobook.audiobookplayer.BuildConfig
import com.audiobook.audiobookplayer.InsufficientCreditsNotifier
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

private const val TAG = "RetrofitClient"

/**
 * 动态 base URL。
 *
 * Retrofit 用占位 baseUrl 构建，OkHttp 拦截器在【每次请求时】读取
 * ServerConfig.baseUrl 替换 scheme/host/port——地址修改即刻生效，无需重建单例。
 * 把地址固化在 `by lazy` 的 Retrofit 构建里会让首发请求后地址被锁死，
 * 用户在登录页改了服务器地址再登录，请求仍发往旧地址。
 * 接口注解里的路径（api/...）全部是相对路径，不受替换影响。
 */
object RetrofitClient {
    val BASE_URL: String
        get() = ServerConfig.baseUrl

    // ===== 超时分级 =====
    // 轻接口快速失败：统一的超长超时会让服务器不可达时登录/查列表也要挂起数分钟才失败。
    // 重接口（大文件上传/下载/服务端长耗时处理）
    // 走下方派生的长超时客户端，见 [longClient]。
    /** 轻接口：连接 10s，读写 30s（登录/列表/状态查询等远在此范围内） */
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L
    /** 重接口：需容纳 50MB 大文件慢速网络传输、服务端最长 120s 的参考音频视频转码、整书文本提取 */
    private const val LONG_CONNECT_TIMEOUT_SECONDS = 15L
    private const val LONG_READ_TIMEOUT_SECONDS = 300L
    private const val LONG_WRITE_TIMEOUT_SECONDS = 300L
    /**
     * SSE（任务进度流）：连接一次性建立，10s 足够判断服务器可达；但读取必须长期等待——
     * 服务端按段落推送，两帧之间可能静默数分钟，读超时过短会让长任务跑到一半被判定断开。
     */
    private const val SSE_CONNECT_TIMEOUT_SECONDS = 10L
    private const val SSE_READ_TIMEOUT_SECONDS = 600L

    /** 每次请求时把 URL 的 scheme/host/port 替换为当前配置的服务器地址 */
    private class DynamicBaseUrlInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val configured = BASE_URL.toHttpUrlOrNull()
                ?: return chain.proceed(request) // 配置非法时按原样发出，让错误可见
            // 子路径部署修复：以「配置的 URL」为基座，保留其 path 前缀（如 /audiobook），
            // 再拼接本次请求自身的 path 与 query，避免子路径部署下全部 404。
            val basePath = configured.encodedPath.trimEnd('/')
            val requestPath = request.url.encodedPath
            // 幂等保护：若请求 path 已包含基座前缀（如重定向/回放请求），不再重复拼接，
            // 避免变成 /audiobook/audiobook/... 导致 404（按路径段边界判断，
            // 防止 /audiobook2 之类的路径被误判）
            val newPath = if (basePath.isNotEmpty() &&
                (requestPath == basePath || requestPath.startsWith("$basePath/"))
            ) {
                requestPath
            } else {
                basePath + requestPath
            }
            val newUrl = configured.newBuilder()
                .encodedPath(newPath)
                .apply {
                    // 显式处理 query：newBuilder() 会继承配置基址自带的 query（如 ?token=），
                    // 请求无 query 时会残留到所有请求、有 query 时基址参数被整体覆盖丢弃
                    val q = request.url.encodedQuery
                    if (q != null) encodedQuery(q) else query(null)
                }
                .build()
            // 自定义地址若为 http:// 仅记录告警，避免强制 https 破坏本地 http 调试服务
            if (configured.scheme == "http") {
                Log.w(
                    TAG,
                    "服务器地址使用明文 http://（${configured.host}），生产环境建议改用 https://"
                )
            }
            return chain.proceed(request.newBuilder().url(newUrl).build())
        }
    }

    /**
     * 余额不足（HTTP 402）统一拦截。
     * 这里解析 {success,message,...} 后转交 [InsufficientCreditsNotifier] 在【当前前台 Activity】
     * 上弹「去充值」引导，覆盖所有页面，不依赖单页注册。原始响应仍原样返回，使调用方原有
     * 错误分支（如 TtsFragment 的中断逻辑）继续生效。
     */
    private class InsufficientCreditsInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val response = chain.proceed(chain.request())
            if (response.code == 402) {
                val message = try {
                    // peekBody 必须限长，Long.MAX_VALUE 会把整个响应体读进内存（含大响应）。
                    // 402 业务 message 很短，64KB 足够；超限部分丢弃也不影响解析。
                    val bodyStr = response.peekBody(64 * 1024L).string()
                    Gson().fromJson(bodyStr, JsonObject::class.java)
                        ?.get("message")?.asString ?: "余额不足，请先充值"
                } catch (e: Exception) {
                    Log.w(TAG, "解析 402 响应失败: ${e.message}")
                    "余额不足，请先充值"
                }
                Log.w(TAG, "收到 402（余额不足）: $message")
                // 切主线程弹窗由 InsufficientCreditsNotifier 内部处理；覆盖所有前台页面
                InsufficientCreditsNotifier.notify(message)
            }
            return response
        }
    }

    /** 认证接口（登录/注册）自身的 401 不算登录过期，跳过全局处理 */
    private class UnauthorizedInterceptor(private val onUnauthorized: () -> Unit) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.code == 401
                && !request.url.encodedPath.contains("/api/auth/")
            ) {
                // token 过期/失效：交给 AuthManager 统一清凭证+跳登录页。
                // 响应原样返回，调用方仍按 401 走各自错误分支。
                onUnauthorized()
            }
            return response
        }
    }

    /**
     * 鉴权头统一注入：在【请求发出前】从 [AuthManager] 读取当前 token 注入 `Authorization` 头，
     * 以本拦截器为权威来源（无论调用方是否手传，都按当前登录态覆盖）。
     * 由各调用方自行拼 `"Bearer $token"` 容易漏传，而漏传会被 [UnauthorizedInterceptor]
     * 误判为登录过期。
     *
     * 登录/注册为公开端点，不带 token；`validateToken` 仍需 token，故不排除。
     * 版本检查（permitAll）同样不注入，避免向公开端点泄露登录态。
     */
    private class AuthInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            val path = request.url.encodedPath
            val skip = path.endsWith("/api/auth/login") ||
                path.endsWith("/api/auth/register") ||
                path.contains("/api/app/version/")
            val token = AuthManager.token
            return if (!skip && !token.isNullOrEmpty()) {
                chain.proceed(
                    request.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                )
            } else {
                chain.proceed(request)
            }
        }
    }

    private fun buildClient(): OkHttpClient {
        // E 风险修复：HEADERS 级别会把 Authorization: Bearer <JWT> 原样打进 logcat，
        // 任何能读日志的应用/人都能拿走登录态。保留请求头日志的调试价值，
        // 但对敏感头脱敏（输出为 ██），Cookie 一并处理。
        val logging = HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.HEADERS)
            redactHeader("Authorization")
            redactHeader("Cookie")
        }
        return OkHttpClient.Builder()
            .addInterceptor(DynamicBaseUrlInterceptor())
            .addInterceptor(AuthInterceptor())
            .addInterceptor(UnauthorizedInterceptor { AuthManager.handleUnauthorized() })
            .addInterceptor(InsufficientCreditsInterceptor())
            // HttpLoggingInterceptor 即便已对 Authorization/Cookie 脱敏，HEADERS 级仍会把
            // 全部请求 URL（含 taskId / orderNo）+ 响应头打进 logcat。release 包必须关闭，
            // 否则任何能读日志的应用都能拿到这些敏感路径参数。仅 debug 构建保留。
            .apply { if (BuildConfig.DEBUG) addInterceptor(logging) }
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val baseClient: OkHttpClient by lazy { buildClient() }

    /**
     * 长超时客户端：由 [baseClient] 派生（newBuilder 共享全部拦截器、连接池与调度器，
     * 仅覆盖超时参数），不会多占线程/连接资源。
     */
    private val longClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(LONG_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(LONG_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(LONG_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * SSE 长连接客户端：由 [baseClient] 派生，因此同样具备动态 base URL、401 登出、
     * 402 余额引导三件套。
     *
     * 说明：[longClient] 是 private，SSE 不能直接用；但也确实不该直接用——
     * SSE 的读超时语义与普通请求不同（见 [SSE_READ_TIMEOUT_SECONDS]），连接超时也必须
     * 单独设短，否则服务器不可达时要一直等到读超时才报错。
     */
    val sseClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(SSE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SSE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private fun buildRetrofit(client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            // 占位地址，真实地址由 DynamicBaseUrlInterceptor 在请求时替换
            .baseUrl("http://localhost/")
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            // 必须 serializeNulls()：「清除段落预设音色」语义依赖显式传 null ——
            // segmentVoices 里 value 为 null 表示该段清除预设、回退到克隆参考音频，
            // defaultVoiceId 为 null 表示不修改任务默认音色。
            // Gson 默认（serializeNulls=false）会直接丢弃这些 null 键，服务端收不到，
            // 导致「清除预设」在 Android 上静默无效、而 Web（axios 保留 null）有效 —— 跨端行为分叉。
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
            .build()

    private val retrofit: Retrofit by lazy { buildRetrofit(baseClient) }
    private val retrofitLong: Retrofit by lazy { buildRetrofit(longClient) }

    val apiService: ApiService by lazy { retrofit.create(ApiService::class.java) }

    /** 重型端点（上传/下载/文本提取），走长超时客户端 */
    val heavyApi: HeavyApiService by lazy { retrofitLong.create(HeavyApiService::class.java) }

    val creditApi: CreditApiService by lazy { retrofit.create(CreditApiService::class.java) }
}

