package com.audiobook.audiobookplayer.network

import com.audiobook.audiobookplayer.model.*
import retrofit2.Response
import retrofit2.http.*

interface CreditApiService {

    /** 我的积分概况（余额 + 计费规则）。token 由 AuthInterceptor 统一注入，无需调用方手传 */
    @GET("api/credits/me")
    suspend fun getMyCredits(): Response<CreditBalanceResponse>

    /** 可购买的充值套餐（仅上架）。token 由 AuthInterceptor 统一注入，无需调用方手传 */
    @GET("api/credits/packages")
    suspend fun getPackages(): Response<CreditPackageListResponse>

    /** 可用支付渠道。token 由 AuthInterceptor 统一注入，无需调用方手传 */
    @GET("api/payment/channels")
    suspend fun getChannels(): Response<ChannelListResponse>

    /**
     * 创建充值订单。
     *
     * **`@JvmSuppressWildcards` 是必需的，不能删**（充值下单 100% 失败的元凶）：
     *
     * Kotlin 的 `Map<K, out V>` 是**声明点协变**，出现在**参数位置**时会被编译成
     * Java 的 `Map<String, ? extends Object>`。Retrofit 在构建请求前会对每个参数类型调用
     * `Utils.hasUnresolvableType`，遇到 `WildcardType` 直接返回 true，于是抛
     * `IllegalArgumentException: Parameter type must not include a type variable or wildcard`。
     *
     * 由于 `validateEagerly` 默认关闭，这个异常**不在创建 Retrofit 实例时抛出，
     * 而是在第一次真正调用该接口时**才炸；再被 `RechargeActivity` 的 `catch (e: Exception)`
     * 吞掉，用户只看到一句英文技术错误，而订单从来没有创建成功过。
     *
     * 只有**参数位置**会中招：返回位置的 `Response<Map<String, Any>>` 处于 out 位置，
     * 不生成通配符，是安全的（本文件其余几处返回类型都不用加）。
     */
    @POST("api/payment/prepay")
    suspend fun createOrder(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<PrepayResponse>

    /** 查询单笔订单状态（轮询到账用）。token 由 AuthInterceptor 统一注入 */
    @GET("api/payment/orders/{orderNo}")
    suspend fun getOrderStatus(
        @Path("orderNo") orderNo: String
    ): Response<Map<String, Any>>

    /** 模拟支付确认（仅 allow-mock=true 时可用）。token 由 AuthInterceptor 统一注入 */
    @POST("api/payment/mock/confirm")
    suspend fun confirmMock(
        @Query("orderNo") orderNo: String
    ): Response<Map<String, Any>>

    /** 我的积分流水（AdminCredits 台账前端展示）。token 由 AuthInterceptor 统一注入 */
    @GET("api/credits/transactions")
    suspend fun getMyTransactions(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<CreditTransactionListResponse>
}
