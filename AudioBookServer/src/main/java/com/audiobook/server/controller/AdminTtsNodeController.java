package com.audiobook.server.controller;

import com.audiobook.server.model.TtsNode;
import com.audiobook.server.service.TtsLoadBalancer;
import com.audiobook.server.service.TtsNodeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台管理：MOSS-TTS-Nano 合成节点增删启停。
 *
 * <p>权限由 SecurityConfig 的 {@code /api/admin/** hasRole("ADMIN")} 统一保证，
 * 无需在本类再做校验。
 *
 * <p><b>为什么这个接口是「不重启加机器」的关键。</b>
 * 写入 {@code tts_nodes} 表后立即调用 {@link TtsNodeRegistry#reload()}，
 * 快照当场更新，下一次合成就会用上新节点。整个过程不触碰应用上下文，
 * 因此<b>不会打断正在进行的合成任务</b> —— 而重启服务会把
 * {@code app.tts.concurrency} 个在途任务直接截断。
 *
 * <p>列表接口额外返回运行指标：在途请求数、冷却状态、探活结果。
 * 没有这些指标，「配了多台机器但负载不均」是看不出来的。
 */
@RestController
@RequestMapping("/api/admin/tts/nodes")
public class AdminTtsNodeController {

    private static final Logger log = LoggerFactory.getLogger(AdminTtsNodeController.class);

    private final TtsNodeRegistry registry;
    private final TtsLoadBalancer loadBalancer;

    /** 后台合成并发上限，用于提示「加了机器但并发没跟上」这一最常见的误配 */
    @Value("${app.tts.concurrency:2}")
    private int ttsConcurrency;

    @Value("${moss.tts.nodes.health-check-enabled:true}")
    private boolean healthCheckEnabled;

    @Value("${moss.tts.nodes.refresh-millis:5000}")
    private long refreshMillis;

    public AdminTtsNodeController(TtsNodeRegistry registry, TtsLoadBalancer loadBalancer) {
        this.registry = registry;
        this.loadBalancer = loadBalancer;
    }

    /** 节点列表：配置字段 + 实时运行指标 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        try {
            Map<Long, TtsLoadBalancer.NodeStat> stats = loadBalancer.stats();
            List<Map<String, Object>> items = new ArrayList<>();
            int enabledCount = 0;
            int schedulableCount = 0;

            for (TtsNode node : registry.findAll()) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", node.getId());
                m.put("name", node.getName());
                m.put("url", node.getUrl());
                m.put("engineType", node.engineTypeValue().name());
                m.put("language", node.getLanguage());
                m.put("weight", node.getWeight());
                m.put("enabled", node.isEnabled());
                m.put("sortOrder", node.getSortOrder());
                m.put("remark", node.getRemark());
                m.put("createdAt", node.getCreatedAt());
                m.put("updatedAt", node.getUpdatedAt());

                TtsLoadBalancer.NodeStat stat = stats.get(node.getId());
                if (stat == null) {
                    // 刚写入数据库但快照尚未刷新到该节点：按「无任何指标」呈现，
                    // 而不是伪造一份全零指标误导管理员
                    m.put("inFlight", 0);
                    m.put("coolingDown", false);
                    m.put("cooldownRemainingMillis", 0L);
                    m.put("consecutiveFailures", 0);
                    m.put("totalRequests", 0L);
                    m.put("totalFailures", 0L);
                    m.put("reachable", true);
                    m.put("lastProbeAt", null);
                    m.put("lastSuccessAt", null);
                    m.put("lastError", null);
                } else {
                    m.put("inFlight", stat.inFlight());
                    m.put("coolingDown", stat.coolingDown());
                    m.put("cooldownRemainingMillis", stat.cooldownRemainingMillis());
                    m.put("consecutiveFailures", stat.consecutiveFailures());
                    m.put("totalRequests", stat.totalRequests());
                    m.put("totalFailures", stat.totalFailures());
                    m.put("reachable", stat.reachable());
                    m.put("lastProbeAt", stat.lastProbeAt());
                    m.put("lastSuccessAt", stat.lastSuccessAt());
                    m.put("lastError", stat.lastError());
                }

                boolean schedulable = node.isEnabled()
                        && (stat == null || (!stat.coolingDown() && stat.reachable()));
                if (node.isEnabled()) {
                    enabledCount++;
                }
                if (schedulable) {
                    schedulableCount++;
                }
                m.put("status", statusOf(node, stat));

                items.add(m);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("nodes", items);
            response.put("total", items.size());
            response.put("enabledCount", enabledCount);
            response.put("schedulableCount", schedulableCount);
            response.put("ttsConcurrency", ttsConcurrency);
            response.put("healthCheckEnabled", healthCheckEnabled);
            response.put("refreshMillis", refreshMillis);
            response.put("concurrencyHint", concurrencyHint(schedulableCount));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询 TTS 节点列表失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "查询失败，请稍后重试"));
        }
    }

    /** 新增节点。写入即时生效，无需重启。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        try {
            TtsNode node = registry.create(
                    asString(body.get("name")),
                    asString(body.get("url")),
                    asInteger(body.get("weight")),
                    asInteger(body.get("sortOrder")),
                    asString(body.get("remark")),
                    asString(body.get("engineType")),
                    asString(body.get("language")));
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("id", node.getId());
            response.put("message", "节点已添加，立即生效");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(failure(e));
        } catch (Exception e) {
            log.error("新增 TTS 节点失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "新增失败，请稍后重试"));
        }
    }

    /**
     * 修改节点。未提交的字段保持原值（null 表示「本次不改」），
     * 因此前端可以做局部更新而不必回传完整表单。
     *
     * <p>唯一的例外是 {@code remark}：它可被显式清空，故必须区分
     * 「key 不存在 = 不改」与「key 存在但为空 = 清空」，见 {@link #asClearableString}。
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                      @RequestBody Map<String, Object> body) {
        try {
            registry.update(id,
                    asString(body.get("name")),
                    asString(body.get("url")),
                    asInteger(body.get("weight")),
                    asInteger(body.get("sortOrder")),
                    asClearableString(body, "remark"),
                    asBoolean(body.get("enabled")),
                    asString(body.get("engineType")),
                    asClearableString(body, "language"));
            return ResponseEntity.ok(Map.of("success", true, "message", "节点已更新，立即生效"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(failure(e));
        } catch (Exception e) {
            log.error("更新 TTS 节点失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "更新失败，请稍后重试"));
        }
    }

    /**
     * 启用/停用节点。
     *
     * <p>停用是这个接口最常用的操作：它立刻让节点不再承接新请求，
     * 但<b>不会打断已经在途的那次调用</b>（选点是每次请求做的，不是任务级绑定）。
     * 需要下线一台机器做维护时，先停用、再等在途数归零，是最平滑的摘除方式。
     */
    @PutMapping("/{id}/enabled")
    public ResponseEntity<Map<String, Object>> setEnabled(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        Object raw = body.get("enabled");
        if (raw == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "缺少 enabled 参数"));
        }
        try {
            boolean enabled = parseBoolean(raw);
            registry.update(id, null, null, null, null, null, enabled, null, null);
            return ResponseEntity.ok(Map.of("success", true,
                    "message", enabled ? "节点已启用，立即参与调度" : "节点已停用，不再承接新任务"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(failure(e));
        } catch (Exception e) {
            log.error("切换 TTS 节点启用状态失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "操作失败，请稍后重试"));
        }
    }

    /**
     * 物理删除节点。
     *
     * <p>与「停用」的区别：停用保留记录与历史统计，删除则一并消失。
     * 绝大多数下线场景应该用停用 —— 删掉之后，「这台机器最近是否稳定」
     * 这类排障依据就没了。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            registry.delete(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "节点已删除，立即生效"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(failure(e));
        } catch (Exception e) {
            log.error("删除 TTS 节点失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "删除失败，请稍后重试"));
        }
    }

    /**
     * 节点状态。区分「已停用」与「探活不可达」很重要：
     * 前者是管理员的主动选择，后者是需要排障的故障。
     */
    private String statusOf(TtsNode node, TtsLoadBalancer.NodeStat stat) {
        if (!node.isEnabled()) {
            return "disabled";
        }
        if (stat == null) {
            return "pending";
        }
        if (!stat.reachable()) {
            return "unreachable";
        }
        if (stat.coolingDown()) {
            return "cooling";
        }
        if (stat.totalRequests() == 0) {
            return "idle";
        }
        return "online";
    }

    /**
     * 并发与节点数的匹配提示。
     *
     * <p>这是多节点改造里最容易踩的坑：加了机器却忘了调 {@code app.tts.concurrency}
     * （默认 2），结果是<b>批量合成</b>同时只有 2 个请求在飞，第 3 台机器永远吃不到活，
     * 表现上「加了机器但一点没快」。把这条依赖显式提示出来，
     * 比让它变成一个要靠人回忆的隐含前提要好。
     *
     * <p>提示只覆盖批量路径：交互式端点（{@code /clone}、{@code /synthesize}）在
     * Tomcat 线程上同步调用引擎，不受 {@code app.tts.concurrency} 约束。
     * 因此本提示不出现<b>不等于</b>负载一定均衡 —— 交互式流量大时多台机器同样可能各自繁忙，
     * 那属于正常现象，判断依据应看列表里的「在途」列。
     */
    private String concurrencyHint(int schedulableCount) {
        if (schedulableCount > ttsConcurrency) {
            return "当前调度中节点 " + schedulableCount + " 台，但 app.tts.concurrency="
                    + ttsConcurrency + "，批量合成同一时刻最多只有 " + ttsConcurrency
                    + " 个请求在飞，部分节点会闲置。建议将其提升至 "
                    + (schedulableCount * 2) + " 左右（需确认各节点机器可承受）。";
        }
        return null;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 读取「可以被显式清空」的文本字段，用于 {@code remark}。
     *
     * <p>返回 {@code null} 表示「key 未提交，本次不改」；返回空串表示
     * 「key 已提交且为空，要清空该字段」。
     *
     * <p><b>不能复用 {@link #asString}。</b>它把空串归一成 {@code null}，
     * 于是「用户把备注删空后保存」和「用户没提交备注」在服务端变得无法区分，
     * 两者都落入「本次不改」分支 —— 表现为备注<b>永远删不掉</b>，
     * 而前端看起来保存成功了。{@link TtsNodeRegistry#update} 用
     * {@code trimToNull} 把空串落库为 NULL，因此这里透传空串即可完成清空。
     */
    private static String asClearableString(Map<String, Object> body, String key) {
        if (!body.containsKey(key)) {
            return null;
        }
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数值字段格式非法: " + text);
        }
    }

    private static Boolean asBoolean(Object value) {
        return value == null ? null : parseBoolean(value);
    }

    /**
     * 严格解析布尔值：只接受 true / false（大小写不敏感），其余一律报错。
     *
     * <p>刻意不用 {@code Boolean.parseBoolean}：它把任何无法识别的字符串都当成
     * {@code false}，于是一个笔误（如把 {@code true} 误写成 {@code "ture"}）会
     * <b>静默把节点停用</b>，而管理员看到的是一次「操作成功」——
     * 生产上这种无声的配置错误比直接报错难排查得多。
     */
    private static boolean parseBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new IllegalArgumentException("布尔字段只接受 true 或 false，实际收到: " + text);
    }

    /**
     * 构造 400 响应体。
     *
     * <p>不能直接写 {@code Map.of("message", e.getMessage())}：{@code Map.of} 拒绝
     * null 值，而 {@link IllegalArgumentException} 允许消息为 null（例如
     * {@code new NumberFormatException()} 抛出的那种）。一旦消息为空，
     * 本该返回的 400 会被 NPE 顶掉变成 500，把「参数写错了」误报成「服务器故障」。
     */
    private static Map<String, Object> failure(IllegalArgumentException e) {
        String message = e.getMessage();
        return Map.of("success", false, "message",
                (message == null || message.isBlank()) ? "参数不合法" : message);
    }
}
