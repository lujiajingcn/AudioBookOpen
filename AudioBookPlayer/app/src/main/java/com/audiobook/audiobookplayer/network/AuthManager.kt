package com.audiobook.audiobookplayer.network

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.audiobook.audiobookplayer.LoginActivity
import com.audiobook.audiobookplayer.extractServerMessage
import com.audiobook.audiobookplayer.model.AuthResponse
import com.audiobook.audiobookplayer.model.LoginRequest
import com.audiobook.audiobookplayer.model.RegisterRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

private const val TAG = "AuthManager"

object AuthManager {
    private const val PREF_NAME = "auth_prefs_encrypted"
    private const val KEY_TOKEN = "token"
    private const val KEY_USERNAME = "username"

    // 多线程下多个 Activity 并发调用 init()，非 volatile 字段存在
    // 可见性问题——一个线程已完成构建，另一个线程可能仍读到 null 而重复执行
    // 主密钥构建（主线程可达百毫秒），甚至读到「构造中」的半成品引用。
    // @Volatile 保证 happens-before：完成初始化的实例对所有线程立即可见。
    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    private val api by lazy { RetrofitClient.apiService }

    /** 防止 401 跳转登录页被并发请求重复触发 */
    @Volatile
    private var handlingUnauthorized = false

    /**
     * 401 时暂停正在进行的后台听书播放，避免登录过期期间音频继续空放。
     * 由 PlaybackService 在存活期间注册自己的暂停回调、停止/销毁时解绑；
     * 这里只持有一个可空回调，避免与播放服务形成编译期耦合（PlaybackService 已依赖 AuthManager）。
     */
    @Volatile
    private var playbackPauser: (() -> Unit)? = null

    fun setPlaybackPauser(p: (() -> Unit)?) {
        playbackPauser = p
    }

    /**
     * 初始化加密的 SharedPreferences。
     * 幂等：多个 Activity 都会调用 init，重复执行会在主线程重建主密钥（可达百毫秒），
     * 已初始化则直接返回。
     *
     * 双重检查锁（DCL）：外层判空走无锁快路径（已初始化时零开销），
     * 临界区内二次判空保证并发首次调用时 EncryptedSharedPreferences 恰好构建一次。
     * 注意 DCL 必须配合 @Volatile 使用，否则存在「部分构造对象」逃逸的经典缺陷。
     *
     * 注意：依赖为 security-crypto 1.0.0（稳定版），其 API 为 MasterKeys +
     * create(fileName, masterKeyAlias, context, ...) 旧签名；
     * MasterKey.Builder 是 1.1.0-alpha 才有的新 API，此处不可使用。
     */
    fun init(context: Context) {
        if (encryptedPrefs != null) return
        synchronized(this) {
            if (encryptedPrefs != null) return
            appContext = context.applicationContext
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            encryptedPrefs = EncryptedSharedPreferences.create(
                PREF_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    /**
     * 全局 401 处理（由 RetrofitClient 的拦截器调用）：清凭证并跳转登录页。
     * 只弹「获取失败」Toast 不足以让用户知道要重新登录，故统一清凭证并跳转登录页。
     *
     * ⚠️ 仅对「确实带着 token 却收到 401」的**会话过期**生效。游客本就没有 token，
     * 其触发的 401 属预期内（需登录的功能入口已在 UI 层用 requireLogin 拦截），
     * 绝不能把游客弹去强制登录页，也不该 logout 清掉一个本不存在的会话。
     */
    fun handleUnauthorized() {
        if (!isAuthenticated) {
            Log.d(TAG, "未登录状态收到 401：忽略全局跳转")
            return
        }
        logout()
        if (handlingUnauthorized) return
        handlingUnauthorized = true
        val ctx = appContext
        if (ctx == null) {
            handlingUnauthorized = false
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                // 先暂停后台播放（PlaybackService 注册的回调），
                // 避免登录过期期间音频继续空放。回调里会保存当前进度，重登后可续听。
                playbackPauser?.invoke()
                Toast.makeText(ctx, "登录已过期，请重新登录", Toast.LENGTH_LONG).show()
                val intent = Intent(ctx, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                ctx.startActivity(intent)
            } finally {
                // 延迟复位，避免本次跳转期间其他 401 再起一个 Toast
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ handlingUnauthorized = false }, 2000)
            }
        }
    }

    var token: String?
        get() = encryptedPrefs?.getString(KEY_TOKEN, null)
        set(value) {
            encryptedPrefs?.edit()?.putString(KEY_TOKEN, value)?.apply()
        }

    var username: String?
        get() = encryptedPrefs?.getString(KEY_USERNAME, null)
        set(value) {
            encryptedPrefs?.edit()?.putString(KEY_USERNAME, value)?.apply()
        }

    val isAuthenticated: Boolean
        get() = token != null

    suspend fun login(username: String, password: String): Result<AuthResponse> {
        return withContext(Dispatchers.IO) {
            try {
                // 安全：日志禁止携带用户名/邮箱等个人身份信息（设备日志可被其他应用读取）
                Log.d(TAG, "开始登录")
                val response = api.login(LoginRequest(username, password))
                Log.d(TAG, "响应码: ${response.code()}, 成功: ${response.isSuccessful}")

                if (response.isSuccessful && response.body() != null) {
                    val authResponse = response.body()!!
                    Log.d(TAG, "响应内容: success=${authResponse.success}, message=${authResponse.message}")
                    if (authResponse.success) {
                        token = authResponse.token
                        this@AuthManager.username = username
                    }
                    Result.success(authResponse)
                } else {
                    // 只记录状态码，不打印 errorBody 原文（可能含服务端内部信息）；
                    // 但要把服务端的 message 作为业务文案回传给用户，否则用户看到的
                    // 只是「登录失败: Unauthorized」，不知道究竟是密码错还是账号不存在
                    Log.e(TAG, "登录失败: code=${response.code()}")
                    Result.failure(
                        Exception(extractServerMessage(response.errorBody()?.string()) ?: "登录失败: ${response.message()}")
                    )
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "登录异常: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun register(username: String, password: String, email: String): Result<AuthResponse> {
        return withContext(Dispatchers.IO) {
            try {
                // 安全：日志禁止携带用户名/邮箱等个人身份信息
                Log.d(TAG, "开始注册")
                val response = api.register(RegisterRequest(username, password, email))
                Log.d(TAG, "响应码: ${response.code()}, 成功: ${response.isSuccessful}")

                if (response.isSuccessful && response.body() != null) {
                    val authResponse = response.body()!!
                    Log.d(TAG, "响应内容: success=${authResponse.success}, message=${authResponse.message}")
                    if (authResponse.success) {
                        token = authResponse.token
                        this@AuthManager.username = username
                    }
                    Result.success(authResponse)
                } else {
                    Log.e(TAG, "注册失败: code=${response.code()}")
                    Result.failure(
                        Exception(extractServerMessage(response.errorBody()?.string()) ?: "注册失败: ${response.message()}")
                    )
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "注册异常: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    fun logout() {
        encryptedPrefs?.edit()?.clear()?.apply()
    }
}

