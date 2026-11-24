package com.audiobook.server.service;

import com.audiobook.server.model.TtsNode;
import com.audiobook.server.repository.TtsNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多节点选点算法测试。
 *
 * <p>重点验证的是「分配是否真的平均」和「坏节点会不会被及时摘掉」——
 * 这两点光靠读代码看不出来，必须在可控条件下把大量选点跑一遍统计分布。
 * 因此这里不启动 Spring 容器，直接用 Mockito 造一个假仓库把节点列表喂给注册中心，
 * 再直接 new 出 TtsLoadBalancer，让每次「选点」都是纯粹的方法调用，
 * 可以跑几百上千轮而毫秒级完成。
 */
class TtsLoadBalancerTest {

    private static final long COOLDOWN_SECONDS = 30L;
    private static final int MAX_CONSECUTIVE_FAILURES = 2;
    private static final int MAX_FAILOVER_ATTEMPTS = 1;
    private static final String SEED_URL = "http://seed:18083/api/generate";

    private TtsNodeRepository repository;
    private TtsNodeRegistry registry;
    private TtsLoadBalancer balancer;

    /** 注册中心「数据库」里的启用节点；测试直接改这个列表来模拟增删节点 */
    private List<TtsNode> enabledNodes;

    @BeforeEach
    void setUp() {
        repository = mock(TtsNodeRepository.class);
        enabledNodes = new ArrayList<>();
        // 每次查询返回一份拷贝：注册中心持有的是不可变快照，
        // 若共享同一个 list 引用，测试中修改列表会绕过快照更新语义，
        // 让「节点变更 → 快照刷新 → 运行时状态同步」这条链路失去验证意义
        when(repository.findByEnabledTrueOrderBySortOrderAscIdAsc())
                .thenAnswer(invocation -> new ArrayList<>(enabledNodes));

        registry = new TtsNodeRegistry(repository, SEED_URL);
        // 探活关掉：本测试只关心选点与熔断，避免依赖真实 TCP 连接
        balancer = new TtsLoadBalancer(registry, COOLDOWN_SECONDS, MAX_CONSECUTIVE_FAILURES,
                MAX_FAILOVER_ATTEMPTS, false, 2000);
    }

    private static TtsNode node(long id) {
        TtsNode node = new TtsNode();
        node.setId(id);
        node.setName("node-" + id);
        node.setUrl("http://10.0.0." + id + ":18083/api/generate");
        node.setWeight(1);
        node.setEnabled(true);
        node.setSortOrder((int) id);
        return node;
    }

    private void enableNodes(int count) {
        for (long id = 1; id <= count; id++) {
            enabledNodes.add(node(id));
        }
        registry.reload();
    }

    private TtsNode nodeById(long id) {
        return registry.enabledNodes().stream()
                .filter(n -> n.getId() == id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("节点不存在: " + id));
    }

    /**
     * 触发一次运行时状态表同步。
     *
     * <p>每个节点的运行时条目（在途计数、熔断状态）是在<b>首次选点</b>时按当前
     * 快照惰性创建的，而不是在 {@link TtsNodeRegistry#reload()} 里创建。
     * 因此任何直接对节点调用 onAcquire/onFailure/onSuccess 的测试，
     * 都必须先选点一次把条目建出来，否则那些调用会因找不到条目而静默失效。
     *
     * <p>（{@link TtsLoadBalancer#stats()} 也会补齐条目，见
     * {@code statsCreatesRuntimeEntriesForNewNodes}，但这里刻意只用选点这一条路径，
     * 让测试更贴近真实合成流程。）
     */
    private void primeRuntimeState() {
        balancer.candidates();
    }

    @Test
    @DisplayName("管理端查询统计会为新节点补齐运行时状态，不会把可用节点显示成「同步中」")
    void statsCreatesRuntimeEntriesForNewNodes() {
        assertThat(balancer.stats()).isEmpty();

        enableNodes(2);

        // 刻意不调用 candidates()：模拟管理员刚添加完节点就刷新列表。
        // 运行时条目若不在 stats() 里补齐，新节点会一直显示成 pending，
        // 而它实际上已经可以被调度了
        assertThat(balancer.stats()).containsKeys(1L, 2L);
    }

    /** 模拟一次已完成的合成调用：取节点 → 在途 +1 → 成功 → 在途 -1 */
    private TtsNode runOneCall() {
        List<TtsNode> candidates = balancer.candidates();
        assertThat(candidates).isNotEmpty();
        TtsNode chosen = candidates.get(0);
        balancer.onAcquire(chosen);
        balancer.onSuccess(chosen);
        balancer.onRelease(chosen);
        return chosen;
    }

    // ===================== 分配均衡性 =====================

    @Test
    @DisplayName("3 台节点轮流承接任务，几百轮下来分配大致平均")
    void distributesCallsEvenlyAcrossNodes() {
        enableNodes(3);

        int rounds = 300;
        Map<Long, Integer> picked = new HashMap<>();
        for (int i = 0; i < rounds; i++) {
            picked.merge(runOneCall().getId(), 1, Integer::sum);
        }

        // 每台都接到了活（不能出现某台完全闲置）
        assertThat(picked).hasSize(3);
        // 300 / 3 = 100，允许 ±20% 的偏差。
        // 全部节点在途数都归零时，轮转游标会严格轮流当队首，实际应恰好各 100 次；
        // 留出余量是为了让断言表达「大致平均」这个需求本身，而不是绑死实现细节
        for (long id = 1; id <= 3; id++) {
            assertThat(picked.get(id))
                    .as("节点 %d 承接的任务数", id)
                    .isBetween(80, 120);
        }
    }

    @Test
    @DisplayName("单台节点时全部任务都落到它身上")
    void singleNodeTakesAllCalls() {
        enableNodes(1);
        for (int i = 0; i < 20; i++) {
            assertThat(runOneCall().getId()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("有节点正在处理长任务时，新任务优先绕开它")
    void prefersNodeWithFewestInFlight() {
        enableNodes(3);
        primeRuntimeState();

        // 让 1 号节点进入「长任务」状态：acquire 之后不 release，在途数恒为 1
        TtsNode busy = nodeById(1);
        balancer.onAcquire(busy);

        for (int i = 0; i < 30; i++) {
            List<TtsNode> candidates = balancer.candidates();
            assertThat(candidates.get(0).getId())
                    .as("在途数最多的节点不应被优先选中")
                    .isNotEqualTo(busy.getId());
        }

        balancer.onRelease(busy);
    }

    @Test
    @DisplayName("候选项数量受 max-failover-attempts 限制")
    void candidateCountIsCappedByFailoverAttempts() {
        enableNodes(5);
        // maxFailoverAttempts = 1 → 1 个首选 + 最多 1 个替补。
        // 不设上限的话，一台机器挂掉会把后面所有机器逐个试一遍，
        // 每次都要等满超时，一次合成会被拖到无法接受的时长
        assertThat(balancer.candidates()).hasSize(2);
    }

    // ===================== 熔断与降级 =====================

    @Test
    @DisplayName("连接类硬失败一次即让节点进入冷却并被移出候选")
    void hardFailureEjectsNodeImmediately() {
        enableNodes(3);
        primeRuntimeState();
        TtsNode bad = nodeById(1);

        balancer.onAcquire(bad);
        balancer.onFailure(bad, new RuntimeException("Connection refused"), true);
        balancer.onRelease(bad);

        assertThat(balancer.stats().get(bad.getId()).coolingDown()).isTrue();

        for (int i = 0; i < 30; i++) {
            for (TtsNode candidate : balancer.candidates()) {
                assertThat(candidate.getId())
                        .as("冷却中的节点不应出现在候选里")
                        .isNotEqualTo(bad.getId());
            }
        }
    }

    @Test
    @DisplayName("软失败要累计到阈值才熔断，单次抖动不摘除节点")
    void softFailureNeedsThreshold() {
        enableNodes(3);
        primeRuntimeState();
        TtsNode node = nodeById(1);

        balancer.onFailure(node, new RuntimeException("500 Internal Server Error"), false);
        assertThat(balancer.stats().get(node.getId()).coolingDown())
                .as("第一次软失败不应立即熔断")
                .isFalse();

        balancer.onFailure(node, new RuntimeException("500 Internal Server Error"), false);
        assertThat(balancer.stats().get(node.getId()).coolingDown())
                .as("连续失败达到阈值后应熔断")
                .isTrue();
    }

    @Test
    @DisplayName("一次成功调用立即解除冷却，节点马上回到调度")
    void successClearsCooldown() {
        enableNodes(3);
        primeRuntimeState();
        TtsNode node = nodeById(1);

        balancer.onFailure(node, new RuntimeException("boom"), true);
        assertThat(balancer.stats().get(node.getId()).coolingDown()).isTrue();

        balancer.onSuccess(node);
        assertThat(balancer.stats().get(node.getId()).coolingDown()).isFalse();
        assertThat(balancer.stats().get(node.getId()).consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("全部节点都在冷却时仍返回候选（半开探测），而不是直接判定无节点可用")
    void halfOpenWhenAllNodesCooling() {
        enableNodes(3);
        primeRuntimeState();
        for (long id = 1; id <= 3; id++) {
            balancer.onFailure(nodeById(id), new RuntimeException("down"), true);
        }

        // 直接返回空会让整个冷却期内每段合成立刻失败，
        // 而引擎可能早已恢复 —— 半开探测让请求有机会自愈
        assertThat(balancer.candidates()).isNotEmpty();
    }

    @Test
    @DisplayName("节点被停用后运行时状态一并清理，重新加入不会继承旧的冷却")
    void runtimeStatePurgedWhenNodeRemoved() {
        enableNodes(3);
        primeRuntimeState();
        TtsNode removed = nodeById(1);
        balancer.onFailure(removed, new RuntimeException("refused"), true);
        assertThat(balancer.stats().get(removed.getId()).coolingDown()).isTrue();

        // 管理端停用/删除该节点后，注册中心刷新快照
        enabledNodes.removeIf(n -> n.getId().equals(removed.getId()));
        registry.reload();
        balancer.candidates();

        // 若运行时状态不被清理，「删掉地址再加回来」会表现为「刚加回来就不可用」
        assertThat(balancer.stats()).doesNotContainKey(removed.getId());
    }

    @Test
    @DisplayName("在途计数带回绕保护，不会因为重复释放而变成负数")
    void inFlightCountNeverGoesNegative() {
        enableNodes(1);
        primeRuntimeState();
        TtsNode node = nodeById(1);

        // 重复 release（例如节点在请求在途期间被删除后状态被清理等边界场景）
        balancer.onRelease(node);
        balancer.onRelease(node);

        assertThat(balancer.stats().get(node.getId()).inFlight()).isZero();
    }

    @Test
    @DisplayName("选点无节点可用时返回空列表，由调用方给出明确报错")
    void returnsEmptyWhenNoNodeEnabled() {
        assertThat(balancer.candidates()).isEmpty();
    }

    // ===================== 快照发布的一致性 =====================

    @Test
    @DisplayName("快照把节点列表与版本号打包发布，两者永远来自同一次刷新")
    void snapshotPairsNodesWithVersion() {
        enableNodes(3);
        TtsNodeRegistry.Snapshot first = registry.snapshot();
        assertThat(first.nodes()).hasSize(3);

        enabledNodes.add(node(4));
        registry.reload();

        // 列表与版本号必须成对：下游靠「拿列表建状态、用版本号标记已同步」，
        // 若两者能被分别读到，中间插进一次 reload 就会用旧列表建状态却标记成新版本，
        // 新增节点因此永远建不出运行时条目（管理端长期显示「同步中」）
        TtsNodeRegistry.Snapshot second = registry.snapshot();
        assertThat(second.nodes()).hasSize(4);
        assertThat(second.version()).isGreaterThan(first.version());

        // 内容没变时版本号不递增，否则下游每 5 秒都会白重建一次运行时状态表
        registry.reload();
        assertThat(registry.snapshot().version()).isEqualTo(second.version());
    }

    @Test
    @DisplayName("备注可被显式清空；未提交备注时保持原值")
    void remarkCanBeClearedButNotByOmission() {
        enableNodes(1);
        TtsNode node = nodeById(1);
        node.setRemark("机房A-3090");
        when(repository.findById(1L)).thenReturn(Optional.of(node));
        when(repository.save(any(TtsNode.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // 未提交 remark（null）→ 保持原值，避免局部更新顺手把备注清掉
        registry.update(1L, null, null, null, null, null, null, null, null);
        assertThat(node.getRemark()).as("未提交备注时不应改动").isEqualTo("机房A-3090");

        // 显式提交空串 → 清空。
        // 控制器必须原样透传空串（不能走会把空串归一成 null 的 asString），
        // 否则这一步会退化成「本次不改」，备注就永远删不掉
        registry.update(1L, null, null, null, null, "", null, null, null);
        assertThat(node.getRemark()).as("显式提交空串应清空备注").isNull();
    }

    // ===================== 地址校验 =====================

    @Test
    @DisplayName("节点地址校验：拒绝非法协议、缺主机、带凭据与空值")
    void rejectsInvalidNodeUrls() {
        assertThatThrownBy(() -> TtsNodeRegistry.normalizeUrl("ftp://10.0.0.1/x"))
                .isInstanceOf(IllegalArgumentException.class);
        // 只有前缀没有主机：看起来像 URL，实际不可用
        assertThatThrownBy(() -> TtsNodeRegistry.normalizeUrl("http://"))
                .isInstanceOf(IllegalArgumentException.class);
        // 带凭据会把账号散落到日志与数据库中，且引擎不支持鉴权
        assertThatThrownBy(() -> TtsNodeRegistry.normalizeUrl("http://user:pass@10.0.0.1/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TtsNodeRegistry.normalizeUrl("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TtsNodeRegistry.normalizeUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("节点地址校验：合法地址去掉首尾空白后原样保留")
    void acceptsValidNodeUrl() {
        assertThat(TtsNodeRegistry.normalizeUrl("  http://10.0.0.11:18083/api/generate  "))
                .isEqualTo("http://10.0.0.11:18083/api/generate");
        assertThat(TtsNodeRegistry.normalizeUrl("https://tts.internal/api/generate"))
                .isEqualTo("https://tts.internal/api/generate");
    }
}
