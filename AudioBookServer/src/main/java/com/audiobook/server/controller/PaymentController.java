package com.audiobook.server.controller;

import com.audiobook.server.model.CreditOrder;
import com.audiobook.server.service.CreditOrderService;
import com.audiobook.server.util.PageUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付相关接口。
 *
 * 路由分两类，安全策略完全不同：
 * - /api/payment/**（除 notify）：需要登录，操作当前用户自己的订单；
 * - /api/payment/notify/**：由支付平台服务器直接回调，没有 JWT，
 *   必须在 SecurityConfig 中放行；安全性靠渠道验签保证，绝不靠登录态。
 */
@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final CreditOrderService creditOrderService;

    public PaymentController(CreditOrderService creditOrderService) {
        this.creditOrderService = creditOrderService;
    }

    // ===================== 用户端 =====================

    /** 可用支付渠道（未配置密钥的渠道不会出现在列表里） */
    @GetMapping("/channels")
    public ResponseEntity<Map<String, Object>> channels() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("channels", creditOrderService.listAvailableChannels());
        return ResponseEntity.ok(result);
    }

    /**
     * 创建充值订单并获取支付入口。
     *
     * 返回体中的 payType 决定前端行为：
     * redirect → window.location 跳转 payUrl；
     * qrcode   → 把 payUrl 渲染成二维码让用户扫；
     * mock     → 显示「模拟支付完成」按钮，POST /api/payment/mock/confirm。
     */
    @PostMapping("/prepay")
    public ResponseEntity<Map<String, Object>> prepay(@RequestBody Map<String, Object> body,
                                                      Authentication authentication) {
        try {
            Long packageId = parseLong(body.get("packageId"));
            if (packageId == null) {
                return badRequest("缺少参数 packageId");
            }
            String channel = body.get("channel") == null ? null : String.valueOf(body.get("channel"));

            Map<String, Object> data = creditOrderService.createOrder(authentication.getName(), packageId, channel);
            data.put("success", true);
            return ResponseEntity.ok(data);

        } catch (IllegalArgumentException e) {
            // 业务校验消息（套餐已下架/无权操作等）原样返回给用户，
            // 与 GlobalExceptionHandler 的 400 设计保持一致，避免用户把校验失败当瞬态故障反复重试
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            log.error("创建充值订单失败: user={}, 原因: {}", authentication.getName(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        } catch (Exception e) {
            log.error("创建充值订单异常: user={}", authentication.getName(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 我的充值订单（分页） */
    @GetMapping("/orders")
    public ResponseEntity<Map<String, Object>> myOrders(@RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        Authentication authentication) {
        try {
            size = Math.min(size, PageUtil.MAX_PAGE_SIZE);
            Page<CreditOrder> orderPage = creditOrderService.listMyOrders(authentication.getName(), page, size);
            List<Map<String, Object>> content = new ArrayList<>();
            for (CreditOrder o : orderPage.getContent()) {
                content.add(toOrderMap(o));
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("content", content);
            result.put("totalPages", orderPage.getTotalPages());
            result.put("totalElements", orderPage.getTotalElements());
            result.put("currentPage", page);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("查询充值订单失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 查询单笔订单状态，供支付完成后前端轮询到账结果 */
    @GetMapping("/orders/{orderNo}")
    public ResponseEntity<Map<String, Object>> orderStatus(@PathVariable String orderNo,
                                                           Authentication authentication) {
        try {
            Map<String, Object> data = creditOrderService.getOrderStatus(orderNo, authentication.getName());
            data.put("success", true);
            return ResponseEntity.ok(data);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("查询订单状态失败: orderNo={}", orderNo, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 取消未支付订单 */
    @PostMapping("/orders/{orderNo}/cancel")
    public ResponseEntity<Map<String, Object>> cancelOrder(@PathVariable String orderNo,
                                                           Authentication authentication) {
        try {
            creditOrderService.cancelOrder(orderNo, authentication.getName());
            return ResponseEntity.ok(Map.of("success", true, "message", "订单已取消"));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("取消订单失败: orderNo={}", orderNo, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /**
     * 模拟支付确认（仅 app.payment.allow-mock=true 时可用）。
     *
     * 这个端点故意放在需要登录的路径下，并在 Service 层校验订单归属，
     * 避免变成「谁都能刷积分」的后门。
     */
    @PostMapping("/mock/confirm")
    public ResponseEntity<Map<String, Object>> mockConfirm(@RequestParam String orderNo,
                                                           Authentication authentication) {
        try {
            creditOrderService.confirmMockPay(orderNo, authentication.getName());
            Map<String, Object> data = creditOrderService.getOrderStatus(orderNo, authentication.getName());
            data.put("success", true);
            data.put("message", "模拟支付成功，积分已到账");
            return ResponseEntity.ok(data);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            // IllegalStateException 携带明确业务原因（"当前环境已禁用模拟支付"），
            // 吞成"请稍后重试"会误导用户反复重试；403 表示服务端主动禁用该功能，
            // 文案必须透传真实原因
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message",
                            e.getMessage() != null && !e.getMessage().isBlank()
                                    ? e.getMessage() : "模拟支付不可用"));
        } catch (Exception e) {
            log.error("模拟支付失败: orderNo={}", orderNo, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    // ===================== 支付平台回调 =====================

    /**
     * 支付宝异步通知（application/x-www-form-urlencoded）。
     *
     * 必须原样收下全部表单参数：验签要对「除 sign/sign_type 外的所有参数」
     * 按 ASCII 排序拼串，漏掉任何一个（包括未来支付宝新增的字段）都会验签失败。
     * 所以这里用 @RequestParam Map 全量接收，而不是逐个声明字段。
     */
    @PostMapping(value = "/notify/alipay", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> alipayNotify(@RequestParam Map<String, String> params) {
        log.info("收到支付宝异步通知: out_trade_no={}, trade_status={}",
                params.get("out_trade_no"), params.get("trade_status"));
        String response = creditOrderService.handleNotify("ALIPAY", params, null, null);
        return ResponseEntity.ok(response);
    }

    /**
     * 微信支付 APIv3 异步通知（application/json）。
     *
     * 必须用 @RequestBody String 接收原始文本：验签的待签串包含「报文主体原文」，
     * 一旦反序列化成对象再序列化回去，字段顺序/空格都可能变化，验签必然失败。
     */
    @PostMapping(value = "/notify/wechat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> wechatNotify(@RequestBody String rawBody, HttpServletRequest request) {
        Map<String, String> headers = extractHeaders(request);
        log.info("收到微信支付异步通知: serial={}, timestamp={}",
                headers.get("Wechatpay-Serial"), headers.get("Wechatpay-Timestamp"));

        String response = creditOrderService.handleNotify("WECHAT", null, rawBody, headers);
        boolean ok = response != null && response.contains("SUCCESS");
        // 微信要求处理失败时返回 4xx/5xx，仅靠响应体不足以让它重投
        return ok ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    // ===================== 内部工具 =====================

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private Map<String, Object> toOrderMap(CreditOrder o) {
        Map<String, Object> map = new HashMap<>();
        map.put("orderNo", o.getOrderNo());
        map.put("packageName", o.getPackageName());
        map.put("credits", o.getCredits());
        map.put("bonusCredits", o.getBonusCredits());
        map.put("totalCredits", o.getTotalCredits());
        map.put("amount", o.getAmount());
        map.put("status", o.getStatus());
        map.put("payChannel", o.getPayChannel());
        map.put("paidAt", o.getPaidAt());
        map.put("expireAt", o.getExpireAt());
        map.put("createdAt", o.getCreatedAt());
        return map;
    }

    private static Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", message));
    }
}
