package com.audiobook.server.controller;

import com.audiobook.server.dto.CreditPackageForm;
import com.audiobook.server.dto.CreditRuleForm;
import com.audiobook.server.model.CreditOrder;
import com.audiobook.server.model.CreditPackage;
import com.audiobook.server.model.CreditRule;
import com.audiobook.server.model.CreditTransaction;
import com.audiobook.server.repository.CreditOrderRepository;
import com.audiobook.server.service.CreditOrderService;
import com.audiobook.server.service.CreditPackageService;
import com.audiobook.server.service.CreditService;
import com.audiobook.server.util.PageUtil;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台积分管理接口（AudioBookManager 调用）。
 *
 * 路径挂在 /api/admin/credits 下，由 SecurityConfig 的
 * {@code requestMatchers("/api/admin/**").hasRole("ADMIN")} 统一鉴权，
 * 因此本类内部不再重复做管理员判断 —— 鉴权规则只保留一处，避免两边不一致。
 */
@RestController
@RequestMapping("/api/admin/credits")
public class AdminCreditController {

    private static final Logger log = LoggerFactory.getLogger(AdminCreditController.class);

    private final CreditService creditService;
    private final CreditPackageService creditPackageService;
    private final CreditOrderService creditOrderService;
    private final CreditOrderRepository creditOrderRepository;

    public AdminCreditController(CreditService creditService,
                                 CreditPackageService creditPackageService,
                                 CreditOrderService creditOrderService,
                                 CreditOrderRepository creditOrderRepository) {
        this.creditService = creditService;
        this.creditPackageService = creditPackageService;
        this.creditOrderService = creditOrderService;
        this.creditOrderRepository = creditOrderRepository;
    }

    // ===================== 积分规则 =====================

    /** 读取当前积分规则 */
    @GetMapping("/rule")
    public ResponseEntity<Map<String, Object>> getRule() {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("rule", toRuleMap(creditService.getRule()));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("读取积分规则失败", e);
            return serverError(e);
        }
    }

    /**
     * 更新积分规则。
     *
     * 传什么改什么（未传字段保持原值），因此前端可以只提交被修改的开关，
     * 也不用担心并发编辑时互相把对方的配置覆盖回默认值。
     */
    @PutMapping("/rule")
    public ResponseEntity<Map<String, Object>> updateRule(@Valid @RequestBody CreditRuleForm form,
                                                          Authentication authentication) {
        try {
            CreditRule saved = creditService.updateRule(toRuleEntity(form), authentication.getName());
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "积分规则已更新");
            result.put("rule", toRuleMap(saved));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            // 业务校验消息（价格/积分非法等）原样返回，供管理端直接展示
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("更新积分规则失败", e);
            return serverError(e);
        }
    }

    // ===================== 充值套餐 =====================

    /** 套餐列表（含下架） */
    @GetMapping("/packages")
    public ResponseEntity<Map<String, Object>> listPackages() {
        try {
            List<Map<String, Object>> content = new ArrayList<>();
            for (CreditPackage p : creditPackageService.listAll()) {
                content.add(toPackageMap(p));
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("packages", content);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询套餐列表失败", e);
            return serverError(e);
        }
    }

    @PostMapping("/packages")
    public ResponseEntity<Map<String, Object>> createPackage(@Valid @RequestBody CreditPackageForm form) {
        try {
            CreditPackage saved = creditPackageService.create(toPackageEntity(form));
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "套餐已创建");
            result.put("package", toPackageMap(saved));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            // 业务校验消息（价格/积分非法等）原样返回，供管理端直接展示
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("创建套餐失败", e);
            return serverError(e);
        }
    }

    @PutMapping("/packages/{id}")
    public ResponseEntity<Map<String, Object>> updatePackage(@PathVariable Long id,
                                                             @Valid @RequestBody CreditPackageForm form) {
        try {
            CreditPackage saved = creditPackageService.update(id, toPackageEntity(form));
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "套餐已更新");
            result.put("package", toPackageMap(saved));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            // 业务校验消息（价格/积分非法等）原样返回，供管理端直接展示
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("更新套餐失败: id={}", id, e);
            return serverError(e);
        }
    }

    @DeleteMapping("/packages/{id}")
    public ResponseEntity<Map<String, Object>> deletePackage(@PathVariable Long id) {
        try {
            creditPackageService.delete(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "套餐已删除"));
        } catch (IllegalArgumentException e) {
            // 业务校验消息（价格/积分非法等）原样返回，供管理端直接展示
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("删除套餐失败: id={}", id, e);
            return serverError(e);
        }
    }

    // ===================== 流水与订单 =====================

    /** 积分流水（可按用户 / 类型筛选） */
    @GetMapping("/transactions")
    public ResponseEntity<Map<String, Object>> listTransactions(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            size = Math.min(size, PageUtil.MAX_PAGE_SIZE);
            Page<CreditTransaction> txPage = creditService.listTransactionsForAdmin(userId, type, page, size);

            List<Map<String, Object>> content = new ArrayList<>();
            for (CreditTransaction tx : txPage.getContent()) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", tx.getId());
                map.put("userId", tx.getUserId());
                map.put("username", tx.getUsername());
                map.put("type", tx.getType());
                map.put("credits", tx.getCredits());
                map.put("balanceAfter", tx.getBalanceAfter());
                map.put("taskId", tx.getTaskId());
                map.put("orderNo", tx.getOrderNo());
                map.put("remark", tx.getRemark());
                map.put("operator", tx.getOperator());
                map.put("createdAt", tx.getCreatedAt());
                content.add(map);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("content", content);
            result.put("totalPages", txPage.getTotalPages());
            result.put("totalElements", txPage.getTotalElements());
            result.put("currentPage", page);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询积分流水失败", e);
            return serverError(e);
        }
    }

    /** 充值订单（可按状态筛选：PENDING / PAID / CLOSED） */
    @GetMapping("/orders")
    public ResponseEntity<Map<String, Object>> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            size = Math.min(size, PageUtil.MAX_PAGE_SIZE);
            Page<CreditOrder> orderPage = creditOrderService.listOrdersForAdmin(status, page, size);

            List<Map<String, Object>> content = new ArrayList<>();
            for (CreditOrder o : orderPage.getContent()) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", o.getId());
                map.put("orderNo", o.getOrderNo());
                map.put("userId", o.getUserId());
                map.put("username", o.getUsername());
                map.put("packageName", o.getPackageName());
                map.put("credits", o.getCredits());
                map.put("bonusCredits", o.getBonusCredits());
                map.put("totalCredits", o.getTotalCredits());
                map.put("amount", o.getAmount());
                map.put("status", o.getStatus());
                map.put("payChannel", o.getPayChannel());
                map.put("payTradeNo", o.getPayTradeNo());
                map.put("paidAt", o.getPaidAt());
                map.put("expireAt", o.getExpireAt());
                map.put("createdAt", o.getCreatedAt());
                content.add(map);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("content", content);
            result.put("totalPages", orderPage.getTotalPages());
            result.put("totalElements", orderPage.getTotalElements());
            result.put("currentPage", page);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询充值订单失败", e);
            return serverError(e);
        }
    }

    // ===================== 手动调整与统计 =====================

    /**
     * 手动为用户增减积分（客诉补偿、异常纠正）。
     *
     * 操作人会写入流水的 operator 字段，便于事后审计「谁给谁加了多少分」。
     */
    @PostMapping("/users/{userId}/adjust")
    public ResponseEntity<Map<String, Object>> adjust(@PathVariable Long userId,
                                                      @RequestBody Map<String, Object> body,
                                                      Authentication authentication) {
        try {
            Integer delta = parseInt(body.get("delta"));
            if (delta == null) {
                return badRequest("缺少参数 delta（正数为增加，负数为扣减）");
            }
            String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));

            int balance = creditService.adminAdjust(userId, delta, remark, authentication.getName());

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", (delta > 0 ? "已增加 " : "已扣减 ") + Math.abs(delta) + " 积分");
            result.put("balance", balance);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            // 业务校验消息（价格/积分非法等）原样返回，供管理端直接展示
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("调整用户积分失败: userId={}", userId, e);
            return serverError(e);
        }
    }

    /** 积分概览统计（发放/消耗/退还/存量 + 订单数与营收） */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        try {
            Map<String, Object> result = new HashMap<>(creditService.getStats());
            result.put("success", true);
            result.put("paidOrderCount", creditOrderRepository.countByStatus(CreditOrder.STATUS_PAID));
            result.put("pendingOrderCount", creditOrderRepository.countByStatus(CreditOrder.STATUS_PENDING));
            result.put("paidAmount", creditOrderRepository.sumPaidAmount());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询积分统计失败", e);
            return serverError(e);
        }
    }

    // ===================== 内部工具 =====================

    private Map<String, Object> toRuleMap(CreditRule rule) {
        Map<String, Object> map = new HashMap<>();
        map.put("enabled", rule.getEnabledSafe());
        map.put("creditsPerTask", rule.getCreditsPerTaskSafe());
        map.put("freeCreditsOnRegister", rule.getFreeCreditsOnRegisterSafe());
        map.put("refundOnTaskFailure", rule.getRefundOnTaskFailureSafe());
        map.put("adminExempt", rule.getAdminExemptSafe());
        map.put("description", rule.getDescription());
        map.put("updatedBy", rule.getUpdatedBy());
        map.put("updatedAt", rule.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toPackageMap(CreditPackage p) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", p.getId());
        map.put("name", p.getName());
        map.put("credits", p.getCredits());
        map.put("bonusCredits", p.getBonusCredits());
        map.put("totalCredits", p.getTotalCredits());
        map.put("price", p.getPrice());
        map.put("description", p.getDescription());
        map.put("enabled", p.getEnabled());
        map.put("sortOrder", p.getSortOrder());
        map.put("createdAt", p.getCreatedAt());
        map.put("updatedAt", p.getUpdatedAt());
        return map;
    }

    /**
     * 把部分更新的表单映射成 JPA 实体：只拷贝被提交的（非 null）字段，
     * 保持「传什么改什么」语义，避免把缺省字段覆盖成 null。
     */
    private static CreditRule toRuleEntity(CreditRuleForm f) {
        CreditRule e = new CreditRule();
        if (f.getEnabled() != null) {
            e.setEnabled(f.getEnabled());
        }
        if (f.getCreditsPerTask() != null) {
            e.setCreditsPerTask(f.getCreditsPerTask());
        }
        if (f.getFreeCreditsOnRegister() != null) {
            e.setFreeCreditsOnRegister(f.getFreeCreditsOnRegister());
        }
        if (f.getRefundOnTaskFailure() != null) {
            e.setRefundOnTaskFailure(f.getRefundOnTaskFailure());
        }
        if (f.getAdminExempt() != null) {
            e.setAdminExempt(f.getAdminExempt());
        }
        if (f.getDailyFreeTasks() != null) {
            e.setDailyFreeTasks(f.getDailyFreeTasks());
        }
        if (f.getDescription() != null) {
            e.setDescription(f.getDescription());
        }
        return e;
    }

    /** 与 {@link #toRuleEntity} 同理：只拷贝被提交的字段，保留部分更新语义 */
    private static CreditPackage toPackageEntity(CreditPackageForm f) {
        CreditPackage e = new CreditPackage();
        if (f.getName() != null) {
            e.setName(f.getName());
        }
        if (f.getCredits() != null) {
            e.setCredits(f.getCredits());
        }
        if (f.getBonusCredits() != null) {
            e.setBonusCredits(f.getBonusCredits());
        }
        if (f.getPrice() != null) {
            e.setPrice(f.getPrice());
        }
        if (f.getDescription() != null) {
            e.setDescription(f.getDescription());
        }
        if (f.getEnabled() != null) {
            e.setEnabled(f.getEnabled());
        }
        if (f.getSortOrder() != null) {
            e.setSortOrder(f.getSortOrder());
        }
        return e;
    }

    private static Integer parseInt(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", message));
    }

    private static ResponseEntity<Map<String, Object>> serverError(Exception e) {
        // 统一为单一文案：两个分支均为固定文案时（如"请稍后重试"/"服务器内部错误"），
        // 分支条件无实际意义且令人困惑。
        // 异常细节只进日志（各调用点已 log.error），绝不透传给客户端。
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "服务器内部错误，请稍后重试"));
    }
}
