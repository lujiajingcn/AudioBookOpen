package com.audiobook.server.service;

import com.audiobook.server.model.TtsEngineType;
import com.audiobook.server.model.TtsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TTS 多节点负载均衡：每次合成调用前选出一台机器，并在它失效时把请求转到别的机器。
 *
 * <p><b>为什么按「每次请求」选点，而不是给任务绑定一台机器。</b>
 * 一个合成任务会逐段串行调用引擎，一本书动辄数百上千段、耗时以小时计。
 * 若在任务开始时绑定机器，一台机器上会长期堆积若干个长任务，而另一台可能空转 ——
 * 任务粒度太粗，填不平负载。改成每次请求选点后，耗时差异很大的段落
 * （短句几百毫秒、长段落几十秒）也能被自然摊平。
 *
 * <p><b>为什么用「最少在途」而不是轮询。</b>
 * 轮询假设每个请求耗时相近，但 TTS 单段耗时相差可达两个数量级。
 * 纯轮询会把长段落不断排到同一台机器上，该机器在途数越堆越高，
 * 而「大致平均」的实际含义是负载平均、不是请求数平均。
 * 在途请求数正是负载最直接的代理指标。
 *
 * <p><b>与 {@code app.tts.concurrency} 的关系。</b>
 * 批量合成的在途请求数受 {@code app.tts.concurrency}（{@code ttsTaskExecutor} 线程池）
 * 约束：一个任务串行逐段调用引擎，同时最多有该值个任务在跑，因此批量路径的同时在飞数
 * 不会超过它。若它仍是默认的 2，配 3 台机器时批量任务永远只有 2 台同时干活，
 * 负载均衡在批量场景下形同虚设；加机器必须同步调大该值。
 * 这层依赖在 {@code /api/admin/tts/nodes} 的响应里以 {@code ttsConcurrency} 字段
 * 显式暴露给管理端，避免配了机器却看不到效果。
 *
 * <p><b>但并发上限不等于在途上限。</b>交互式端点（{@code /clone}、{@code /synthesize}）
 * 直接在 Tomcat 工作线程上同步调用 {@code callTtsEngine}，不经过 {@code ttsTaskExecutor}，
 * 因而不受 {@code app.tts.concurrency} 约束（它们受的是 Tomcat 线程池与
 * {@code moss.tts.sync-timeout-seconds} 的约束）。所以：
 * <ul>
 *   <li>{@code inFlight} 之和可能超过 {@code app.tts.concurrency}，这是正常的，不是计数错误；</li>
 *   <li>管理端那条「并发上限不匹配」提示只在<b>批量</b>合成场景下成立 ——
 *       交互式流量大时即使多节点也可能各自繁忙，此时提示不出现并不代表没有问题。</li>
 * </ul>
 * 选点用的是实测在途数（含交互式请求），因此两种流量混跑时分配依然是准的。
 *
 * <p>线程安全：并入 {@link NodeRuntime}，全部用原子类型；选点本身无锁。
 */
@Component
public class TtsLoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(TtsLoadBalancer.class);

    /** 错误信息落内存日志字段时的截断长度，防止一条超长堆栈把状态表撑爆 */
    private static final int MAX_ERROR_LENGTH = 200;

    private final TtsNodeRegistry registry;

    /** 节点失败后的冷却时长：冷却期内不再分配新请求（全部冷却时降级为半开探测） */
    private final long failureCooldownMillis;

    /** 连续失败多少次进入冷却（连接被拒这类「硬失败」会立即冷却，不看该阈值） */
    private final int maxConsecutiveFailures;

    /** 单次合成最多额外换几台机器重试。0 表示不换节点 */
    private final int maxFailoverAttempts;

    private final boolean healthCheckEnabled;
    private final int healthCheckTimeoutMillis;

    /** 节点 id → 运行时状态。节点被删除/停用后会随快照同步被清除 */
    private final ConcurrentHashMap<Long, NodeRuntime> runtimes = new ConcurrentHashMap<>();

    /**
     * 轮转游标。用于「在途数相同」时打散选择顺序 ——
     * 稳态下大多数节点的在途数都是 0，若排序后永远取第一个，
     * 所有请求都会落到同一台机器上，这是纯轮询也会踩的坑。
     */
    private final AtomicLong rotation = new AtomicLong();

    /** 已同步到运行时状态表的注册中心快照版本，避免每 5 秒无谓重建 */
    private volatile long syncedVersion = -1L;

    public TtsLoadBalancer(
            TtsNodeRegistry registry,
            @Value("${moss.tts.nodes.failure-cooldown-seconds:30}") long failureCooldownSeconds,
            @Value("${moss.tts.nodes.max-consecutive-failures:2}") int maxConsecutiveFailures,
            @Value("${moss.tts.nodes.max-failover-attempts:1}") int maxFailoverAttempts,
            @Value("${moss.tts.nodes.health-check-enabled:true}") boolean healthCheckEnabled,
            @Value("${moss.tts.nodes.health-check-timeout-millis:2000}") int healthCheckTimeoutMillis) {
        this.registry = registry;
        this.failureCooldownMillis = Math.max(1, failureCooldownSeconds) * 1000L;
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        this.maxFailoverAttempts = Math.max(0, maxFailoverAttempts);
        this.healthCheckEnabled = healthCheckEnabled;
        this.healthCheckTimeoutMillis = Math.max(200, healthCheckTimeoutMillis);
        log.info("========== TTS 多节点调度初始化 ==========");
        log.info("   失败冷却: {}ms, 连续失败阈值: {}, 最多换节点: {}",
                this.failureCooldownMillis, this.maxConsecutiveFailures, this.maxFailoverAttempts);
        log.info("   主动探活: {}", healthCheckEnabled ? "开启（超时 " + this.healthCheckTimeoutMillis + "ms）" : "关闭（仅被动熔断）");
    }

    // ===================== 选点 =====================

    /**
     * 按优先级返回本次可以尝试的节点列表（已按在途数升序、已剔除不可用节点）。
     *
     * <p>返回的是<b>候选序列</b>而不是单个节点：调用方从队首开始试，
     * 失败就顺延到下一个 —— 这是「故障切换」的实现基础。
     * 长度被 {@code max-failover-attempts} 限制，避免一台机器失败后把
     * 剩余所有机器都挨个试一遍（每个都要等超时，会把一次合成拖到无法接受的长度）。
     *
     * <p>可能返回空列表（一台可用节点都没有），调用方必须处理。
     */
    public List<TtsNode> candidates() {
        return candidates(node -> true);
    }

    /**
     * 只返回指定引擎类型的候选节点。
     *
     * <p>多引擎并存（MOSS + Pocket）后，这是「不把中文请求派发到 Pocket 机器、
     * 也不把外语请求派发到 MOSS 机器」的关键闸门——两种引擎的请求/响应协议不通用，
     * 发错机器会直接 400。选点在请求级做（与原有「每次请求选点」一致），因此
     * 引擎类型也在这里按请求所需过滤，而不是给任务绑定。
     */
    public List<TtsNode> candidates(TtsEngineType et) {
        return candidates(node -> node.engineTypeValue() == et);
    }

    /**
     * 按优先级返回本次可以尝试的节点列表（已按在途数升序、已剔除不可用节点），
     * 只保留满足 {@code filter} 的节点。
     *
     * <p>返回的是<b>候选序列</b>而不是单个节点：调用方从队首开始试，
     * 失败就顺延到下一个 —— 这是「故障切换」的实现基础。
     * 长度被 {@code max-failover-attempts} 限制，避免一台机器失败后把
     * 剩余所有机器都挨个试一遍（每个都要等超时，会把一次合成拖到无法接受的长度）。
     *
     * <p>可能返回空列表（一台可用节点都没有），调用方必须处理。
     */
    private List<TtsNode> candidates(java.util.function.Predicate<TtsNode> filter) {
        TtsNodeRegistry.Snapshot snapshot = registry.snapshot();
        List<TtsNode> all = snapshot.nodes();
        if (all.isEmpty()) {
            return List.of();
        }
        // 先按引擎类型过滤：过滤后为空说明「这类引擎一台都没登记」，交由调用方给出明确提示
        List<TtsNode> nodes = new ArrayList<>(all.size());
        for (TtsNode node : all) {
            if (filter.test(node)) {
                nodes.add(node);
            }
        }
        if (nodes.isEmpty()) {
            return List.of();
        }
        syncRuntimes(snapshot);

        long now = System.currentTimeMillis();
        List<TtsNode> usable = new ArrayList<>(nodes.size());
        List<TtsNode> cooling = new ArrayList<>(nodes.size());
        for (TtsNode node : nodes) {
            NodeRuntime rt = runtimes.get(node.getId());
            if (rt == null || rt.isCoolingDown(now) || !rt.reachable) {
                cooling.add(node);
            } else {
                usable.add(node);
            }
        }

        // 全部不可用时不是直接失败，而是「半开探测」：挑冷却最早到期的节点试一次。
        // 否则所有节点同时熔断（如一次网络抖动）后，整个冷却期内每段合成都立刻失败，
        // 而引擎很可能早已恢复 —— 白白把一次抖动放大成一段时间的全线不可用。
        List<TtsNode> pool = usable.isEmpty() ? cooling : usable;

        // 先轮转再稳定排序：在途数不同时按负载排，相同时则按轮转顺序轮流当队首。
        // 两步的顺序不能颠倒 —— 先排序再轮转会把排序结果打乱。
        Collections.rotate(pool, (int) Math.floorMod(rotation.getAndIncrement(), pool.size()));
        pool.sort(Comparator
                .comparingInt((TtsNode n) -> inFlightOf(n))
                .thenComparing(Comparator.comparingInt(TtsLoadBalancer::weightOf).reversed()));

        int limit = Math.min(pool.size(), maxFailoverAttempts + 1);
        return List.copyOf(pool.subList(0, limit));
    }

    /**
     * 当前是否至少有一台「未冷却且可达」的节点。
     *
     * <p><b>为什么不能拿 {@link #candidates()} 是否为空来判断。</b>
     * 全部节点都不可用时 {@code candidates()} 会降级成「半开探测」并照常返回候选，
     * 于是它只要 {@code tts_nodes} 里还有记录就<b>永远非空</b> —— 它回答的是
     * 「接下来该去哪台机器试」，不是「现在到底有没有机器能干活」。
     *
     * <p>批量合成必须能回答后者。节点全挂时若继续逐段往下跑，每一段都是
     * 「连上就报错、立刻放弃」：实测一次引擎重启的 5 秒窗口内，3 分钟就把
     * 1450 段永久烧成 {@code failed}（平均每秒 8 段）。停下来等节点恢复
     * （systemd {@code RestartSec=5}）之后，剩下的段落全都还能正常合成。
     */
    public boolean hasHealthyNode() {
        TtsNodeRegistry.Snapshot snapshot = registry.snapshot();
        List<TtsNode> nodes = snapshot.nodes();
        if (nodes.isEmpty()) {
            return false;
        }
        syncRuntimes(snapshot);

        long now = System.currentTimeMillis();
        for (TtsNode node : nodes) {
            NodeRuntime rt = runtimes.get(node.getId());
            if (rt != null && !rt.isCoolingDown(now) && rt.reachable) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把注册中心快照同步到运行时状态表。
     *
     * <p>已存在节点<b>保留原状态对象</b>（在途计数、熔断冷却不能因为一次刷新就归零），
     * 只对新增节点建条目、对移除节点清条目。清条目是必要的：否则「删掉某地址
     * 再重新加回来」会继承上一次的熔断冷却，表现为「刚加回来就不可用」。
     *
     * <p>用版本号做闸门，内容没变时直接返回，不做任何同步（默认每 5 秒一次刷新）。
     *
     * <p><b>入参必须是整个 {@link TtsNodeRegistry.Snapshot} 而不是节点列表。</b>
     * 本方法末尾会把 {@code syncedVersion} 标记成入参快照的版本号，表示
     * 「该版本的节点列表已建好条目」。若版本号另行从注册中心读取，
     * 一旦两次读之间发生了 reload，就会拿旧列表建条目、却标记成新版本已同步，
     * 于是新版本里的新增节点永远建不出条目 —— 管理端长期显示「同步中」，
     * 选点也把它当成在途数无穷大排到最后。绑成一次读取即可根除该竞态。
     */
    private void syncRuntimes(TtsNodeRegistry.Snapshot snapshot) {
        long version = snapshot.version();
        if (version == syncedVersion) {
            return;
        }
        synchronized (this) {
            if (version == syncedVersion) {
                return;
            }
            Set<Long> alive = new HashSet<>();
            for (TtsNode node : snapshot.nodes()) {
                alive.add(node.getId());
                runtimes.computeIfAbsent(node.getId(), NodeRuntime::new);
            }
            runtimes.keySet().retainAll(alive);
            syncedVersion = version;
        }
    }

    // ===================== 运行时状态上报 =====================
    // 所有方法都对「运行时条目已被并发清除」做空值保护：
    // 请求在途期间节点被删除/停用是正常场景（管理端随时可操作），
    // 不能在收尾时因为拿不到状态对象就抛异常，把一次本该成功的合成带崩。

    /** 请求即将发往该节点：在途数 +1 */
    public void onAcquire(TtsNode node) {
        NodeRuntime rt = runtimes.get(node.getId());
        if (rt == null) {
            return;
        }
        rt.inFlight.incrementAndGet();
        rt.totalRequests.incrementAndGet();
    }

    /** 请求结束（无论成败）：在途数 -1。带回绕保护，避免计数变负后被永久优先选中 */
    public void onRelease(TtsNode node) {
        NodeRuntime rt = runtimes.get(node.getId());
        if (rt == null) {
            return;
        }
        rt.inFlight.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    /** 调用成功：清空失败计数与冷却，节点立刻回到正常调度 */
    public void onSuccess(TtsNode node) {
        NodeRuntime rt = runtimes.get(node.getId());
        if (rt == null) {
            return;
        }
        rt.consecutiveFailures.set(0);
        rt.cooldownUntil = 0L;
        rt.reachable = true;
        rt.lastSuccessAt = System.currentTimeMillis();
        rt.lastError = null;
    }

    /**
     * 调用失败：累计失败并可能让节点进入冷却。
     *
     * @param immediateCooldown 连接被拒/域名解析失败这类「硬失败」传 true ——
     *                          它们明确说明机器不可达，无需再等第二次失败；
     *                          5xx、超时这类「软失败」传 false，靠累计次数触发，
     *                          避免一次偶发抖动就把一台好机器摘掉。
     */
    public void onFailure(TtsNode node, Throwable error, boolean immediateCooldown) {
        NodeRuntime rt = runtimes.get(node.getId());
        if (rt == null) {
            return;
        }
        rt.totalFailures.incrementAndGet();
        rt.lastError = abbreviate(error);
        int failures = rt.consecutiveFailures.incrementAndGet();
        if (immediateCooldown || failures >= maxConsecutiveFailures) {
            rt.cooldownUntil = System.currentTimeMillis() + failureCooldownMillis;
            rt.consecutiveFailures.set(0);
            log.warn("⚠️ TTS 节点进入冷却 {}ms: id={}, url={}, 触发原因={}",
                    failureCooldownMillis, node.getId(), node.getUrl(), rt.lastError);
        }
    }

    /**
     * 管理端展示用的状态快照（按节点 id 索引）。
     *
     * <p>先做一次运行时状态同步：新节点（管理端刚添加、尚未承接任何请求）的运行时条目
     * 不存在，若不在此处补齐，它会在管理页面上一直显示成「同步中」——而实际上它早已
     * 可用。管理端查询是「想知道现在到底什么状态」，不能依赖某次合成恰好发生过。
     */
    public Map<Long, NodeStat> stats() {
        TtsNodeRegistry.Snapshot snapshot = registry.snapshot();
        if (!snapshot.nodes().isEmpty()) {
            syncRuntimes(snapshot);
        }
        long now = System.currentTimeMillis();
        Map<Long, NodeStat> result = new HashMap<>();
        runtimes.forEach((id, rt) -> result.put(id, rt.snapshot(now)));
        return result;
    }

    /**
     * 主动探活：以 TCP 能否建连判断节点是否在线。
     *
     * <p>不依赖引擎自身的健康检查端点 —— MOSS-TTS-Nano 不同版本/不同启动方式
     * （FastAPI、Gradio）暴露的路径并不一致，写死一个路径会在升级后静默失效。
     * TCP 建连成功说明机器在线且端口有进程监听，对「这个地址填对了没」这个
     * 核心问题已经足够；引擎是否就绪由被动熔断兜底。
     *
     * <p>刻意不做「探活成功就解除冷却」：引擎过载时往往 TCP 正常但返回 5xx，
     * 若探活一成功就清冷却，熔断器等于被探活绕过，永远拦不住持续报错的节点。
     * 冷却由时间自动到期，由一次真正成功的合成 {@link #onSuccess} 提前解除。
     */
    @Scheduled(fixedDelayString = "${moss.tts.nodes.health-check-interval-millis:10000}",
               initialDelayString = "${moss.tts.nodes.health-check-interval-millis:10000}")
    public void probeAll() {
        if (!healthCheckEnabled) {
            return;
        }
        TtsNodeRegistry.Snapshot snapshot = registry.snapshot();
        List<TtsNode> nodes = snapshot.nodes();
        if (nodes.isEmpty()) {
            return;
        }
        syncRuntimes(snapshot);
        for (TtsNode node : nodes) {
            NodeRuntime rt = runtimes.get(node.getId());
            if (rt == null) {
                continue;
            }
            boolean alive = probeTcp(node.getUrl());
            rt.reachable = alive;
            rt.lastProbeAt = System.currentTimeMillis();
            if (!alive) {
                rt.cooldownUntil = System.currentTimeMillis() + failureCooldownMillis;
                log.warn("⚠️ TTS 节点探活失败，暂时移出调度: id={}, url={}", node.getId(), node.getUrl());
            }
        }
    }

    /**
     * TCP 建连探测。
     *
     * <p>刻意区分「连接超时」与其它异常：连接超时按不可达处理。
     * 这里不像 HTTP 探测那样存在「连上了但读响应超时」的歧义 ——
     * TCP 三次握手本身不涉及应用层处理，超时就是网络不通。
     */
    private boolean probeTcp(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            int port = uri.getPort();
            if (port <= 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), healthCheckTimeoutMillis);
                return true;
            }
        } catch (Exception e) {
            log.debug("TTS 节点探活失败: url={}, 原因={}", url, e.getMessage());
            return false;
        }
    }

    private int inFlightOf(TtsNode node) {
        NodeRuntime rt = runtimes.get(node.getId());
        return rt == null ? Integer.MAX_VALUE : rt.inFlight.get();
    }

    private static int weightOf(TtsNode node) {
        Integer w = node.getWeight();
        return (w == null || w < 1) ? 1 : w;
    }

    private static String abbreviate(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        String text = (message == null || message.isBlank())
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }

    /** 单节点的运行时状态 */
    private static final class NodeRuntime {

        private final Long nodeId;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong totalRequests = new AtomicLong();
        private final AtomicLong totalFailures = new AtomicLong();

        /** 冷却截止时刻（epoch millis），0 表示未冷却。冷却到期自动失效，无需清理任务 */
        private volatile long cooldownUntil;
        /** TCP 探活结论。探活关闭时恒为 true，即不参与筛选 */
        private volatile boolean reachable = true;
        private volatile long lastProbeAt;
        private volatile long lastSuccessAt;
        private volatile String lastError;

        private NodeRuntime(Long nodeId) {
            this.nodeId = nodeId;
        }

        private boolean isCoolingDown(long now) {
            return cooldownUntil > now;
        }

        private NodeStat snapshot(long now) {
            return new NodeStat(
                    nodeId,
                    inFlight.get(),
                    isCoolingDown(now),
                    Math.max(0L, cooldownUntil - now),
                    consecutiveFailures.get(),
                    totalRequests.get(),
                    totalFailures.get(),
                    reachable,
                    lastProbeAt,
                    lastSuccessAt,
                    lastError);
        }
    }

    /** 管理端展示用的节点运行时状态（与数据库中的配置记录按 nodeId 关联） */
    public record NodeStat(
            Long nodeId,
            int inFlight,
            boolean coolingDown,
            long cooldownRemainingMillis,
            int consecutiveFailures,
            long totalRequests,
            long totalFailures,
            boolean reachable,
            long lastProbeAt,
            long lastSuccessAt,
            String lastError) {
    }
}
