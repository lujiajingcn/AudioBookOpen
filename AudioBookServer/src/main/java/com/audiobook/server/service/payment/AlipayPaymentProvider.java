package com.audiobook.server.service.payment;

import com.audiobook.server.config.PaymentProperties;
import com.audiobook.server.model.CreditOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付宝电脑网站支付（alipay.trade.page.pay）。
 *
 * 走「拼装带签名的 GET 链接 → 前端整页跳转」的经典方案：不需要服务端与支付宝做
 * 任何前置 HTTP 交互，避免了本地开发时服务器出网受限就完全跑不通的问题。
 *
 * 签名规则（RSA2）：
 * 1. 取所有非空业务参数（不含 sign），按参数名 ASCII 升序排列；
 * 2. 用 k=v&k=v 拼成待签名串（此处用原始值，不做 URL 编码 —— 这是最容易踩错的一步）；
 * 3. SHA256withRSA 用商户私钥签名，Base64 输出；
 * 4. 最终 URL 里所有参数值（含 sign）才做 URL 编码。
 */
@Component
public class AlipayPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(AlipayPaymentProvider.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * 异步通知允许的时间偏差窗口（分钟）。
     * 只用来拦截「明显陈旧」的报文（数小时前的历史通知被重投），不做精确对时：
     * 1. 报文时间固定 GMT+8，服务器时钟偏差几十分钟就会误杀合法通知；
     * 2. markOrderPaid 按订单幂等（行锁 + 流水查重），重复投递同一通知不会重复入账，
     *    因此窗口放宽到 24h 不会引入资金风险，而 ±15 分钟会在时钟偏差/重投延迟下
     *    把合法支付永久拒付（用户付款不到账且无自动恢复）。
     */
    private static final int NOTIFY_TOLERANCE_MINUTES = 24 * 60;
    /** 支付宝报文中的时间字段固定为 GMT+8 的 yyyy-MM-dd HH:mm:ss，不能按服务器本地时区解析 */
    private static final ZoneId ALIPAY_ZONE = ZoneId.of("Asia/Shanghai");

    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;

    public AlipayPaymentProvider(PaymentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channel() {
        return "ALIPAY";
    }

    @Override
    public boolean isConfigured() {
        PaymentProperties.Alipay cfg = properties.getAlipay();
        // 除下单所需的 appId/私钥外，还必须校验验签所需的支付宝公钥。
        // 缺公钥也判定「已配置」时，支付链接会正常生成、用户真实付款后
        // parseNotify 因无法验签全部拒绝，订单永不到账，只能人工对账。
        // 三项齐全才算可用，否则降级为 MOCK（未开启 mock 时下单直接报「渠道未配置」）。
        return notBlank(cfg.getAppId()) && notBlank(cfg.getPrivateKey())
                && notBlank(cfg.getPublicKey());
    }

    @Override
    public PrepayResult prepay(CreditOrder order) {
        PaymentProperties.Alipay cfg = properties.getAlipay();
        try {
            ObjectNode bizContent = objectMapper.createObjectNode();
            bizContent.put("out_trade_no", order.getOrderNo());
            // 金额必须两位小数字符串，支付宝对 "45" 这种写法会直接报参数错误
            bizContent.put("total_amount", order.getAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
            bizContent.put("subject", buildSubject(order));
            bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");

            Map<String, String> params = new TreeMap<>();
            params.put("app_id", cfg.getAppId());
            params.put("method", "alipay.trade.page.pay");
            params.put("charset", "utf-8");
            params.put("sign_type", "RSA2");
            params.put("timestamp", LocalDateTime.now().format(TS));
            params.put("version", "1.0");
            params.put("notify_url", properties.getNotifyBaseUrl() + "/api/payment/notify/alipay");
            params.put("return_url", properties.getReturnUrl());
            params.put("biz_content", objectMapper.writeValueAsString(bizContent));

            String signContent = joinForSign(params);
            String sign = PaymentCryptoUtil.sign(signContent, PaymentCryptoUtil.loadPrivateKey(cfg.getPrivateKey()));

            StringBuilder url = new StringBuilder(cfg.getGatewayUrl()).append('?');
            for (Map.Entry<String, String> e : params.entrySet()) {
                url.append(e.getKey()).append('=').append(urlEncode(e.getValue())).append('&');
            }
            url.append("sign=").append(urlEncode(sign));

            log.info("支付宝下单链接已生成: orderNo={}, amount={}", order.getOrderNo(), order.getAmount());
            return new PrepayResult(channel(), "redirect", url.toString(), order.getOrderNo(), order.getAmount());

        } catch (Exception e) {
            log.error("支付宝下单失败: orderNo={}, 原因: {}", order.getOrderNo(), e.getMessage(), e);
            throw new IllegalStateException("支付宝下单失败: " + e.getMessage(), e);
        }
    }

    @Override
    public NotifyResult parseNotify(Map<String, String> params, String rawBody, Map<String, String> headers) {
        if (params == null || params.isEmpty()) {
            log.warn("支付宝通知参数为空");
            return null;
        }
        PaymentProperties.Alipay cfg = properties.getAlipay();
        if (!notBlank(cfg.getPublicKey())) {
            // 没有支付宝公钥就无法验签。此时绝不能「信任」通知直接加积分 ——
            // 那等于把加积分的接口公开给了整个互联网。
            log.error("未配置支付宝公钥，无法验证通知签名，拒绝处理: orderNo={}", params.get("out_trade_no"));
            return null;
        }

        try {
            String sign = params.get("sign");
            if (sign == null || sign.isBlank()) {
                log.warn("支付宝通知缺少 sign");
                return null;
            }

            // 验签前必须剔除 sign 与 sign_type，其余参数按 ASCII 升序拼接
            Map<String, String> toVerify = new TreeMap<>(params);
            toVerify.remove("sign");
            toVerify.remove("sign_type");

            PublicKey publicKey = PaymentCryptoUtil.loadPublicKey(cfg.getPublicKey());
            if (!PaymentCryptoUtil.verify(joinForSign(toVerify), sign, publicKey)) {
                log.error("支付宝通知验签失败: orderNo={}", params.get("out_trade_no"));
                return null;
            }

            // app_id 必须存在且与本商户一致：既可能是他人应用的通知被转投过来，
            // 也可能是非法报文 —— 缺失同样拒绝，不做「字段缺失即放行」的宽容处理
            String appId = params.get("app_id");
            if (appId == null || !appId.equals(cfg.getAppId())) {
                log.error("支付宝通知 app_id 缺失或不匹配: expected={}, actual={}", cfg.getAppId(), appId);
                return null;
            }

            // 防重放：验签只能证明报文出自支付宝，不能证明它「刚刚」才发出。
            // 一份被抓取的历史通知可以被无限重投，因此必须校验通知时间落在窗口内
            // （与微信通知一致的思路，只是支付宝用明文的 notify_time/gmt_payment）。
            if (!withinReplayWindow(params)) {
                log.error("支付宝通知时间超出允许窗口({}分钟)，按重放攻击拒绝: orderNo={}, notify_time={}",
                        NOTIFY_TOLERANCE_MINUTES, params.get("out_trade_no"), params.get("notify_time"));
                return null;
            }

            String tradeStatus = params.get("trade_status");
            boolean paid = "TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus);
            BigDecimal amount = parseAmount(params.get("total_amount"));

            return new NotifyResult(params.get("out_trade_no"), params.get("trade_no"), amount, paid);

        } catch (Exception e) {
            log.error("解析支付宝通知异常: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String successResponse() {
        // 支付宝要求返回纯文本 success，返回其他内容会被判定为处理失败并持续重投
        return "success";
    }

    @Override
    public String failureResponse() {
        return "failure";
    }

    /** 商品标题：支付宝对长度有限制，截断到 100 字符以内 */
    private String buildSubject(CreditOrder order) {
        String name = order.getPackageName() == null ? "积分充值" : order.getPackageName();
        String subject = "AudioBook 积分充值 - " + name;
        return subject.length() > 100 ? subject.substring(0, 100) : subject;
    }

    /**
     * 校验通知时间是否落在 ±{@value #NOTIFY_TOLERANCE_MINUTES} 分钟窗口内。
     * 严格只认 notify_time：每次重投都会重新生成，代表「通知」本身的新鲜度。
     * gmt_payment/gmt_create 是订单支付/创建时间，不代表通知新鲜度——下单很久后才付款的
     * 合法通知会因 gmt_create 过旧被误判重放，导致已付款却永远不入账，故不再作为回退字段。
     * notify_time 缺失/非法时按重放拒绝（支付宝异步通知必带该字段，缺失即异常报文）。
     */
    private static boolean withinReplayWindow(Map<String, String> params) {
        String raw = params.get("notify_time");
        if (raw == null || raw.isBlank()) {
            log.error("支付宝通知缺少 notify_time，无法校验重放，拒绝处理");
            return false;
        }
        try {
            Instant notifyAt = LocalDateTime.parse(raw.trim(), TS).atZone(ALIPAY_ZONE).toInstant();
            long deltaMinutes = Math.abs(Duration.between(notifyAt, Instant.now()).toMinutes());
            return deltaMinutes <= NOTIFY_TOLERANCE_MINUTES;
        } catch (DateTimeParseException e) {
            log.error("支付宝通知时间格式非法: {}", raw);
            return false;
        }
    }

    /** 按 ASCII 升序拼接非空参数为 k=v&k=v（值不做 URL 编码） */
    private String joinForSign(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 解析通知金额。缺失/非法时返回 null（而不是 0）：
     * null 表示「无金额可比对」，markOrderPaid 会跳过一致性校验、按订单快照入账；
     * 若返回 0，一笔合法但缺金额字段的通知会因「应付≠0」被判金额不符而永久拒绝，
     * 支付平台只能无意义地无限重投。能走到这里的通知均已通过 RSA2 验签，
     * 信任其指向的订单号并以订单快照金额入账是安全的。
     */
    private static BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            log.warn("支付宝通知金额格式非法: {}", value);
            return null;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
