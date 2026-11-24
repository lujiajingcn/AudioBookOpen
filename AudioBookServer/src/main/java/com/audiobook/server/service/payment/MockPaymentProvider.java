package com.audiobook.server.service.payment;

import com.audiobook.server.config.PaymentProperties;
import com.audiobook.server.model.CreditOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 模拟支付渠道（仅开发/演示用）。
 *
 * 存在的意义：支付宝、微信的商户资质与密钥不是每个开发环境都有，
 * 没有降级通道的话「充值 → 到账 → 扣费」这条链路在本地根本跑不通，
 * 前端也无法联调。这里提供一个不与外部交互的假支付：
 * 下单后返回一个本地确认地址，用户点一下即视为付款成功。
 *
 * 安全约束（三重）：
 * 1. 只有 {@code app.payment.allow-mock=true} 时本渠道才可用（生产必须置 false）；
 * 2. 确认接口需要登录态，且 CreditOrderService 会校验订单归属，
 *    不能替别人的订单「付款」；
 * 3. 通知不来自外部，走的是内部调用路径，因此不存在验签问题，
 *    但同样复用订单行锁与幂等逻辑，行为与真实渠道完全一致。
 */
@Component
public class MockPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentProvider.class);

    private final PaymentProperties properties;

    public MockPaymentProvider(PaymentProperties properties) {
        this.properties = properties;
    }

    @Override
    public String channel() {
        return "MOCK";
    }

    @Override
    public boolean isConfigured() {
        // 「配置可用」等价于「允许模拟支付」
        return properties.isAllowMock();
    }

    @Override
    public PrepayResult prepay(CreditOrder order) {
        if (!properties.isAllowMock()) {
            throw new IllegalStateException("当前环境已禁用模拟支付");
        }
        // payType=mock：前端据此渲染一个「模拟支付完成」按钮，点击后 POST 该地址
        String payUrl = "/api/payment/mock/confirm?orderNo=" + order.getOrderNo();
        log.warn("⚠️ 使用模拟支付下单（生产环境请配置真实渠道并设置 app.payment.allow-mock=false）: orderNo={}, amount={}",
                order.getOrderNo(), order.getAmount());
        return new PrepayResult(channel(), "mock", payUrl, order.getOrderNo(), order.getAmount());
    }

    @Override
    public NotifyResult parseNotify(Map<String, String> params, String rawBody, Map<String, String> headers) {
        if (!properties.isAllowMock()) {
            log.error("模拟支付已禁用，拒绝处理模拟通知");
            return null;
        }
        if (params == null) {
            return null;
        }
        String orderNo = params.get("orderNo");
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        // 金额传 null：CreditOrderService 对 MOCK 渠道跳过金额一致性校验，
        // 真实金额以订单快照为准，避免模拟通道被拿来伪造金额。
        return new NotifyResult(orderNo, "MOCK-" + orderNo, null, true);
    }

    @Override
    public String successResponse() {
        return "success";
    }

    @Override
    public String failureResponse() {
        return "failure";
    }
}
