package com.audiobook.server.service.payment;

import com.audiobook.server.model.CreditOrder;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付渠道抽象。
 *
 * 每个渠道只负责两件事：
 * 1. {@link #prepay} 向支付平台下单，换回一个能让用户付钱的入口（跳转链接或二维码内容）；
 * 2. {@link #parseNotify} 验签并解析异步通知，告诉上层「哪个订单付成功了、平台流水号是多少」。
 *
 * 订单状态机、积分入账、幂等控制全部由 CreditOrderService 统一处理，
 * Provider 不碰数据库 —— 这样新增渠道（云闪付、Apple 内购）不会牵动记账逻辑。
 */
public interface PaymentProvider {

    /** 渠道标识：ALIPAY / WECHAT / MOCK */
    String channel();

    /**
     * 是否已配置好可用的商户密钥。
     * 未配置时由 PaymentService 自动降级到 MOCK，避免本地开发因缺密钥完全无法走通充值流程。
     */
    boolean isConfigured();

    /**
     * 向支付平台下单。
     *
     * @param order 已落库的待支付订单（订单号即商户单号）
     * @return 支付入口信息
     */
    PrepayResult prepay(CreditOrder order);

    /**
     * 验签并解析异步通知。
     *
     * @param params  表单参数（支付宝用；微信为空）
     * @param rawBody 原始请求体（微信用；验签必须用未经反序列化的原文）
     * @param headers 请求头（微信验签需要 Wechatpay-* 头）
     * @return 解析结果；验签失败或报文非法时返回 null（上层据此返回失败应答，让平台重投）
     */
    NotifyResult parseNotify(Map<String, String> params, String rawBody, Map<String, String> headers);

    /** 通知处理成功后，回给支付平台的响应体（平台据此停止重投） */
    String successResponse();

    /** 通知处理失败时回给平台的响应体 */
    String failureResponse();

    /**
     * 支付入口信息。
     *
     * @param channel  渠道
     * @param payType  前端渲染方式：redirect（整页跳转）/ qrcode（渲染二维码）/ mock（模拟支付按钮）
     * @param payUrl   跳转地址或二维码内容
     * @param orderNo  商户订单号
     * @param amount   应付金额（元）
     */
    record PrepayResult(String channel, String payType, String payUrl, String orderNo, BigDecimal amount) {}

    /**
     * 通知解析结果。
     *
     * @param orderNo 商户订单号
     * @param tradeNo 支付平台交易号
     * @param amount  实付金额（元）
     * @param paid    是否为「支付成功」终态（其他状态如 CLOSED 直接忽略）
     */
    record NotifyResult(String orderNo, String tradeNo, BigDecimal amount, boolean paid) {}
}
