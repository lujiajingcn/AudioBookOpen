package com.audiobook.server.service.payment;

import com.audiobook.server.config.PaymentProperties;
import com.audiobook.server.model.CreditOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 微信支付 APIv3 —— Native（扫码）支付。
 *
 * 与支付宝网页支付不同，微信 Native 必须先由服务端调用
 * POST /v3/pay/transactions/native 换取 code_url，再由前端渲染成二维码，
 * 所以这里需要真实发起一次 HTTPS 请求。用 JDK 自带 HttpClient，
 * 不引微信 SDK（理由见 {@link PaymentCryptoUtil} 注释）。
 *
 * 认证签名（Authorization 头）的待签串格式，顺序错一行就 401：
 * <pre>
 * HTTP方法\n
 * URL路径（含 query）\n
 * 时间戳\n
 * 随机串\n
 * 请求报文主体\n
 * </pre>
 *
 * 回调验签的待签串格式（注意与请求签名不同，只有三行）：
 * <pre>
 * 时间戳\n
 * 随机串\n
 * 报文主体\n
 * </pre>
 * 验签通过后，业务数据还需用 APIv3 密钥做 AES-256-GCM 解密才能读到 out_trade_no。
 */
@Component
public class WechatPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(WechatPaymentProvider.class);
    private static final String NATIVE_PATH = "/v3/pay/transactions/native";

    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 复用单例 HttpClient：每次新建会各自维护连接池与 SSL 上下文，
     * 高频下单时会积累大量 TIME_WAIT 连接。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WechatPaymentProvider(PaymentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String channel() {
        return "WECHAT";
    }

    @Override
    public boolean isConfigured() {
        PaymentProperties.Wechat cfg = properties.getWechat();
        return notBlank(cfg.getAppId()) && notBlank(cfg.getMchId())
                && notBlank(cfg.getSerialNo()) && notBlank(cfg.getPrivateKey())
                && notBlank(cfg.getApiV3Key());
    }

    @Override
    public PrepayResult prepay(CreditOrder order) {
        PaymentProperties.Wechat cfg = properties.getWechat();
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("appid", cfg.getAppId());
            body.put("mchid", cfg.getMchId());
            body.put("description", buildDescription(order));
            body.put("out_trade_no", order.getOrderNo());
            body.put("notify_url", properties.getNotifyBaseUrl() + "/api/payment/notify/wechat");
            ObjectNode amount = body.putObject("amount");
            // 微信金额单位是「分」且必须是整数，元转分务必用 BigDecimal 放大，
            // 用 double * 100 会出现 45.60 -> 4559 这种浮点误差导致金额不符
            amount.put("total", toFen(order.getAmount()));
            amount.put("currency", "CNY");

            String payload = objectMapper.writeValueAsString(body);
            String authorization = buildAuthorization("POST", NATIVE_PATH, payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.getApiUrl() + NATIVE_PATH))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", authorization)
                    .header("User-Agent", "AudioBookServer/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.error("微信下单失败: orderNo={}, status={}, body={}",
                        order.getOrderNo(), response.statusCode(), response.body());
                throw new IllegalStateException("微信下单失败: " + extractErrorMessage(response.body()));
            }

            JsonNode result = objectMapper.readTree(response.body());
            String codeUrl = result.path("code_url").asText(null);
            if (codeUrl == null || codeUrl.isBlank()) {
                throw new IllegalStateException("微信未返回 code_url");
            }

            log.info("微信 Native 下单成功: orderNo={}, amount={}", order.getOrderNo(), order.getAmount());
            return new PrepayResult(channel(), "qrcode", codeUrl, order.getOrderNo(), order.getAmount());

        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            // 恢复中断标志，否则线程池里的中断信号会被吞掉
            Thread.currentThread().interrupt();
            log.error("微信下单被中断: orderNo={}", order.getOrderNo(), e);
            throw new IllegalStateException("微信下单被中断", e);
        } catch (Exception e) {
            log.error("微信下单异常: orderNo={}, 原因: {}", order.getOrderNo(), e.getMessage(), e);
            throw new IllegalStateException("微信下单失败: " + e.getMessage(), e);
        }
    }

    @Override
    public NotifyResult parseNotify(Map<String, String> params, String rawBody, Map<String, String> headers) {
        PaymentProperties.Wechat cfg = properties.getWechat();
        if (rawBody == null || rawBody.isBlank()) {
            log.warn("微信通知报文为空");
            return null;
        }
        if (!notBlank(cfg.getApiV3Key())) {
            log.error("未配置微信 APIv3 密钥，无法解密回调，拒绝处理");
            return null;
        }

        try {
            // 平台证书公钥缺失就无法验签。宁可让微信重投（我方返回失败），
            // 也绝不接受未验签的报文 —— 否则任何人 POST 一段 JSON 就能白拿积分。
            if (!notBlank(cfg.getPlatformPublicKey())) {
                log.error("未配置微信支付平台证书公钥，无法验证回调签名，拒绝处理");
                return null;
            }
            if (!verifySignature(headers, rawBody, cfg)) {
                return null;
            }

            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode resource = root.path("resource");
            if (resource.isMissingNode()) {
                log.warn("微信通知缺少 resource 节点");
                return null;
            }

            String plain = PaymentCryptoUtil.aesGcmDecrypt(
                    cfg.getApiV3Key(),
                    resource.path("associated_data").asText(null),
                    resource.path("nonce").asText(null),
                    resource.path("ciphertext").asText(null));

            JsonNode data = objectMapper.readTree(plain);

            // appid / mchid 必须存在且与本商户一致：防止他人商户的通知被转投过来，
            // 字段缺失同样拒绝，不做「缺失即放行」的宽容处理
            String appId = data.path("appid").asText(null);
            String mchId = data.path("mchid").asText(null);
            if (appId == null || !appId.equals(cfg.getAppId())) {
                log.error("微信通知 appid 缺失或不匹配: expected={}, actual={}", cfg.getAppId(), appId);
                return null;
            }
            if (mchId == null || !mchId.equals(cfg.getMchId())) {
                log.error("微信通知 mchid 缺失或不匹配: expected={}, actual={}", cfg.getMchId(), mchId);
                return null;
            }

            String orderNo = data.path("out_trade_no").asText(null);
            String tradeNo = data.path("transaction_id").asText(null);
            boolean paid = "SUCCESS".equals(data.path("trade_state").asText(null));
            // payer_total 是用户实付（可能含优惠），对账应以 total 应付为准。
            // amount.total 缺失（报文异常）时返回 null，让 CreditOrderService.markOrderPaid
            // 跳过金额一致性校验、改用订单快照金额入账，与支付宝 parseAmount 缺字段返回 null 的口径对齐。
            // 用 asInt(0) 会得到 0.00（非 null），导致「应付≠0」被永久拒付、用户付款却不到账、
            // 微信无限重投。amount.total 为微信必填字段，正常回调不会走到此分支。
            JsonNode amountTotal = data.path("amount").path("total");
            BigDecimal amount;
            if (amountTotal.isMissingNode() || amountTotal.isNull()) {
                amount = null;
            } else {
                int fen = amountTotal.asInt(0);
                amount = BigDecimal.valueOf(fen).movePointLeft(2);
            }

            return new NotifyResult(orderNo, tradeNo, amount, paid);

        } catch (Exception e) {
            log.error("解析微信通知异常: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String successResponse() {
        // 微信要求 200 + 该 JSON 结构；其他响应视为处理失败并按退避策略重投
        return "{\"code\":\"SUCCESS\",\"message\":\"成功\"}";
    }

    @Override
    public String failureResponse() {
        return "{\"code\":\"FAIL\",\"message\":\"处理失败\"}";
    }

    // ==================== 内部实现 ====================

    /** 构造 APIv3 Authorization 头 */
    private String buildAuthorization(String method, String urlPath, String body) throws Exception {
        PaymentProperties.Wechat cfg = properties.getWechat();
        long timestamp = System.currentTimeMillis() / 1000;
        String nonce = UUID.randomUUID().toString().replace("-", "").toUpperCase();

        // 每一行都必须以 \n 结尾，包括最后一行 body
        String message = method + "\n" + urlPath + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        PrivateKey privateKey = PaymentCryptoUtil.loadPrivateKey(cfg.getPrivateKey());
        String signature = PaymentCryptoUtil.sign(message, privateKey);

        return "WECHATPAY2-SHA256-RSA2048 "
                + "mchid=\"" + cfg.getMchId() + "\","
                + "nonce_str=\"" + nonce + "\","
                + "signature=\"" + signature + "\","
                + "timestamp=\"" + timestamp + "\","
                + "serial_no=\"" + cfg.getSerialNo() + "\"";
    }

    /** 用微信支付平台证书公钥验证回调签名 */
    private boolean verifySignature(Map<String, String> headers, String rawBody, PaymentProperties.Wechat cfg)
            throws Exception {
        String timestamp = header(headers, "Wechatpay-Timestamp");
        String nonce = header(headers, "Wechatpay-Nonce");
        String signature = header(headers, "Wechatpay-Signature");

        if (timestamp == null || nonce == null || signature == null) {
            log.error("微信通知缺少验签必需的头: timestamp={}, nonce={}, signature={}",
                    timestamp != null, nonce != null, signature != null);
            return false;
        }

        // 时间戳偏移超过 5 分钟视为重放攻击（微信官方建议）
        try {
            long delta = Math.abs(System.currentTimeMillis() / 1000 - Long.parseLong(timestamp));
            if (delta > 300) {
                log.error("微信通知时间戳偏差过大({}s)，按重放攻击拒绝", delta);
                return false;
            }
        } catch (NumberFormatException e) {
            log.error("微信通知时间戳格式非法: {}", timestamp);
            return false;
        }

        String message = timestamp + "\n" + nonce + "\n" + rawBody + "\n";
        PublicKey publicKey = PaymentCryptoUtil.loadPublicKey(cfg.getPlatformPublicKey());
        boolean ok = PaymentCryptoUtil.verify(message, signature, publicKey);
        if (!ok) {
            log.error("微信通知验签失败");
        }
        return ok;
    }

    /** HTTP 头名大小写不敏感，容器给出的 key 大小写不固定，需要忽略大小写查找 */
    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 元 → 分（整数），四舍五入到分 */
    private static int toFen(BigDecimal yuan) {
        return yuan.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
    }

    /** 商品描述：微信限制 127 字符 */
    private String buildDescription(CreditOrder order) {
        String name = order.getPackageName() == null ? "积分充值" : order.getPackageName();
        String desc = "AudioBook 积分充值 - " + name;
        return desc.length() > 100 ? desc.substring(0, 100) : desc;
    }

    /** 从微信错误响应体里提取 message，失败则回退原文 */
    private String extractErrorMessage(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            String message = node.path("message").asText(null);
            return message != null && !message.isBlank() ? message : body;
        } catch (Exception e) {
            return body;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
