package com.audiobook.server.controller;

import com.audiobook.server.model.CreditPackage;
import com.audiobook.server.model.CreditRule;
import com.audiobook.server.model.CreditTransaction;
import com.audiobook.server.service.CreditPackageService;
import com.audiobook.server.service.CreditService;
import com.audiobook.server.util.PageUtil;
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
 * 用户端积分接口。
 *
 * 只暴露「读」与「我自己的数据」：余额、规则文案、流水、可购套餐。
 * 任何会改变余额的操作都不在这里 —— 加积分只能通过支付回调或管理员调整，
 * 扣积分只能由合成任务触发，防止出现可被前端直接调用的加分入口。
 */
@RestController
@RequestMapping("/api/credits")
public class CreditController {

    private static final Logger log = LoggerFactory.getLogger(CreditController.class);

    private final CreditService creditService;
    private final CreditPackageService creditPackageService;

    public CreditController(CreditService creditService, CreditPackageService creditPackageService) {
        this.creditService = creditService;
        this.creditPackageService = creditPackageService;
    }

    /**
     * 我的积分概况：余额 + 当前计费规则。
     *
     * 前端在合成页与充值页都要用它：
     * enabled=false 时应隐藏全部积分相关 UI（等同免费模式）；
     * creditsPerTask 用于合成前提示「本次将消耗 N 积分」。
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> myCredits(Authentication authentication) {
        try {
            int balance = creditService.getBalance(authentication.getName());
            CreditRule rule = creditService.getRule();
            // 当日剩余免费试用次数，前端据此提示「今日还可免费合成 N 次」
            int freeTrialsRemaining = creditService.getFreeTrialsRemaining(authentication.getName());

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("balance", balance);
            result.put("enabled", rule.getEnabledSafe());
            result.put("creditsPerTask", rule.getCreditsPerTaskSafe());
            result.put("refundOnTaskFailure", rule.getRefundOnTaskFailureSafe());
            result.put("description", rule.getDescription());
            // 免费试用配置与剩余次数
            result.put("dailyFreeTasks", rule.getDailyFreeTasksSafe());
            result.put("freeTrialsRemaining", freeTrialsRemaining);
            // 够不够再做一次合成：余额足够、或当日仍有免费试用额度，都算「够」
            result.put("enoughForOneTask",
                    !rule.getEnabledSafe() || balance >= rule.getCreditsPerTaskSafe() || freeTrialsRemaining > 0);
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("查询积分概况失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 我的积分流水（分页） */
    @GetMapping("/transactions")
    public ResponseEntity<Map<String, Object>> myTransactions(@RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size,
                                                              Authentication authentication) {
        try {
            size = Math.min(size, PageUtil.MAX_PAGE_SIZE);
            Page<CreditTransaction> txPage = creditService.listMyTransactions(authentication.getName(), page, size);

            List<Map<String, Object>> content = new ArrayList<>();
            for (CreditTransaction tx : txPage.getContent()) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", tx.getId());
                map.put("type", tx.getType());
                map.put("typeLabel", typeLabel(tx.getType()));
                map.put("credits", tx.getCredits());
                map.put("balanceAfter", tx.getBalanceAfter());
                map.put("taskId", tx.getTaskId());
                map.put("orderNo", tx.getOrderNo());
                map.put("remark", tx.getRemark());
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

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("查询积分流水失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 可购买的充值套餐（仅上架） */
    @GetMapping("/packages")
    public ResponseEntity<Map<String, Object>> packages() {
        try {
            List<Map<String, Object>> content = new ArrayList<>();
            for (CreditPackage p : creditPackageService.listEnabled()) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", p.getId());
                map.put("name", p.getName());
                map.put("credits", p.getCredits());
                map.put("bonusCredits", p.getBonusCredits());
                map.put("totalCredits", p.getTotalCredits());
                map.put("price", p.getPrice());
                map.put("description", p.getDescription());
                content.add(map);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("packages", content);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询充值套餐失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 流水类型的中文展示名，避免三端各写一套映射导致文案不一致 */
    private static String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case CreditTransaction.TYPE_RECHARGE -> "充值到账";
            case CreditTransaction.TYPE_CONSUME -> "合成消耗";
            case CreditTransaction.TYPE_REFUND -> "失败退还";
            case CreditTransaction.TYPE_ADMIN_ADJUST -> "管理员调整";
            case CreditTransaction.TYPE_REGISTER_BONUS -> "注册赠送";
            case CreditTransaction.TYPE_FREE_TRIAL -> "免费试用";
            default -> type;
        };
    }
}
