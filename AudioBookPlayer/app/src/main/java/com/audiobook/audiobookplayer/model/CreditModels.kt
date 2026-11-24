package com.audiobook.audiobookplayer.model

import java.math.BigDecimal

/**
 * 积分相关接口的响应模型（与服务端 CreditController / PaymentController 对齐）。
 *
 * 字段全部给默认值，方便 Gson 反射实例化（与本项目其它 Map 解析风格不同，这里用强类型
 * 让调用处可读性好；后端返回的 key 均为驼峰，与字段名一致）。
 */
data class CreditBalanceResponse(
    val success: Boolean = false,
    val balance: Int = 0,
    val enabled: Boolean = false,
    val creditsPerTask: Int = 0,
    val refundOnTaskFailure: Boolean = false,
    val description: String? = null,
    val enoughForOneTask: Boolean = true,
    /** 每日免费试用合成次数上限（0 表示不提供试用） */
    val dailyFreeTasks: Int = 0,
    /** 当日剩余免费试用次数 */
    val freeTrialsRemaining: Int = 0,
)

data class CreditPackageResp(
    val id: Long = 0,
    val name: String? = null,
    val credits: Int = 0,
    val bonusCredits: Int = 0,
    val totalCredits: Int = 0,
    // 金额用 BigDecimal 而非 Double，避免后续做求和/折扣/对账时出现 0.01 级浮点漂移。
    // Gson 原生支持 BigDecimal（TypeAdapters.BIG_DECIMAL），服务端下发的小数（如 9.90）可直接解析。
    // 字段可空 + 默认值 null：本工程 Gson 走 UnsafeAllocator 绕开主构造，缺省值不生效，
    // 服务端若漏下发价格，可空避免非可空字段被写入 null 后在 %.2f 处 NPE。
    val price: BigDecimal? = null,
    val description: String? = null,
)

data class CreditPackageListResponse(
    val success: Boolean = false,
    val packages: List<CreditPackageResp> = emptyList(),
)

data class CreditTransactionResp(
    val id: Long = 0,
    val type: String? = null,
    val typeLabel: String? = null,
    val credits: Int = 0,
    val balanceAfter: Int = 0,
    val taskId: Long? = null,
    val orderNo: String? = null,
    val remark: String? = null,
    val createdAt: String? = null,
)

data class CreditTransactionListResponse(
    val success: Boolean = false,
    val content: List<CreditTransactionResp> = emptyList(),
    val totalElements: Int = 0,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
)

/** 可用支付渠道（listAvailableChannels 返回 channel + name） */
data class PaymentChannel(
    val channel: String? = null,
    val name: String? = null,
)

data class ChannelListResponse(
    val success: Boolean = false,
    val channels: List<PaymentChannel> = emptyList(),
)

/**
 * 创建充值订单的响应（createOrder 返回）。
 * payType 决定前端行为：qrcode=二维码/链接扫码，mock=模拟支付按钮。
 */
data class PrepayResponse(
    val success: Boolean = false,
    val orderNo: String? = null,
    // 应付金额同样用 BigDecimal（理由同 CreditPackageResp.price）
    val amount: BigDecimal? = null,
    val credits: Int = 0,
    val bonusCredits: Int = 0,
    val totalCredits: Int = 0,
    val packageName: String? = null,
    val channel: String? = null,
    val payType: String? = null,
    val payUrl: String? = null,
    val expireAt: String? = null,
    val message: String? = null,
)
