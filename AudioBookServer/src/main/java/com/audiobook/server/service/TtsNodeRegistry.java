package com.audiobook.server.service;

import com.audiobook.server.model.TtsEngineType;
import com.audiobook.server.model.TtsNode;
import com.audiobook.server.repository.TtsNodeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * MOSS-TTS-Nano 节点注册中心：把「有哪些机器能承接合成任务」从启动期配置
 * 变成运行期可改的数据库记录。
 *
 * <p><b>为什么需要它。</b>改造前节点地址只有 {@code moss.tts.url} 一行配置，
 * 由 {@code @Value} 注入到 {@code VoiceCloningTtsService}，运行期改不了；
 * 想加一台机器就必须重启服务，而重启会打断正在进行的合成任务
 * （{@code app.tts.concurrency} 个任务同时被截断）。
 *
 * <p><b>读写模型。</b>数据库是唯一真源，本地只保留一份<b>不可变快照</b>
 * （{@link #enabledNodes}）。合成路径每次选点都读快照，不查库——否则每次合成
 * 都要多一次数据库往返，且数据库抖动会直接中断合成。快照由
 * {@link #reload()} 整体替换（volatile 写），读到的永远是一份自洽的列表，
 * 不存在「读到一半节点被删了」的中间态。
 *
 * <p><b>刷新时机。</b>两条路径：
 * <ul>
 *   <li>定时刷新——兜底，覆盖「别人直接改数据库」这类绕过管理接口的修改；</li>
 *   <li>写操作后立即刷新——管理端增删启停后马上调用，使改动<b>即时生效</b>，
 *       不必等下一个刷新周期（默认 5 秒）。</li>
 * </ul>
 * 注意写操作刻意不加 {@code @Transactional}：让每个 {@code repository} 调用各自提交，
 * 这样紧随其后的 {@link #reload()} 一定能读到已提交的数据。若包在同一事务里，
 * 快照会先于提交被更新，一旦事务回滚就留下一份与数据库不符的脏快照。
 * （与 {@code VoiceCloningTtsService.applySegmentVoices} 的「不在事务内」同口径。）
 *
 * <p><b>降级。</b>数据库不可用时保留上一次成功的快照继续服务，绝不清空——
 * 清空会让所有合成请求立刻失败，把「数据库抖一下」放大成「合成全线中断」。
 */
@Service
public class TtsNodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(TtsNodeRegistry.class);

    /** 名称与备注的长度上限，与实体列宽一致，避免超长字符串被 MySQL 截断或报错 */
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_REMARK_LENGTH = 255;
    private static final int MAX_LANGUAGE_LENGTH = 32;

    private final TtsNodeRepository repository;

    /**
     * 种子地址：来自旧配置 {@code moss.tts.url}。
     * 仅在 {@code tts_nodes} 表为空（首次部署）时用来初始化第一台机器，
     * 之后节点全部由数据库管理，本配置不再参与调度。
     */
    private final String seedUrl;

    /**
     * 当前快照：节点列表与版本号<b>打包发布</b>，整体替换，永不原地修改元素。
     *
     * <p>两者必须绑在一起，不能拆成两个 volatile 字段：下游的用法是
     * 「拿列表去建立运行时状态，再用版本号标记已同步到哪一版」。
     * 若它们能被分别读到，就会出现「拿到的是旧列表、标记的却是新版本」——
     * 版本号此后不再变化，那条新增节点就永远同步不到运行时状态表，
     * 表现为管理端长期显示「同步中」，且选点把它当成在途数无穷大排到最后。
     *
     * <p>实体在 reload 后处于脱管状态，字段不会再被 Hibernate 改写，
     * 因此并发读到的是稳定对象。
     */
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /**
     * 快照签名，用于判断内容是否真的变了。
     * 内容不变时不递增版本号，从而避免 {@code TtsLoadBalancer}
     * 每 5 秒无谓地重建一次运行时状态表。
     * 只在 {@link #reload()} 的 synchronized 块内读写，无需 volatile。
     */
    private String snapshotSignature = "";

    public TtsNodeRegistry(TtsNodeRepository repository,
                           @Value("${moss.tts.url:}") String seedUrl) {
        this.repository = repository;
        this.seedUrl = seedUrl == null ? "" : seedUrl.trim();
    }

    /**
     * 启动引导：表为空时用 {@code moss.tts.url} 落一条种子节点，然后加载首份快照。
     *
     * <p>把种子「落库」而不是做成虚拟节点，是为了让下游只有一种节点形态：
     * 有真实 id、可被选点、可被管理端看到和修改。否则每个使用节点的地方
     * 都要额外处理一个特例 id，是长期的维护负担。
     *
     * <p>整个过程包在 try/catch 里：引导失败不应该让整个应用起不来，
     * 顶多是「暂时没有可用节点」（合成请求会返回明确错误）。
     */
    @PostConstruct
    void bootstrap() {
        try {
            seedIfEmpty();
        } catch (Exception e) {
            log.error("初始化 TTS 节点种子失败（不影响启动，可在管理端手动添加节点）: {}", e.getMessage(), e);
        }
        reload();
    }

    private void seedIfEmpty() {
        long existing = repository.count();
        if (existing > 0) {
            log.info("TTS 节点注册表已存在 {} 条记录，跳过种子初始化", existing);
            return;
        }
        if (seedUrl.isEmpty()) {
            log.warn("TTS 节点表为空且未配置 moss.tts.url，当前没有任何可用合成节点；"
                    + "请通过 POST /api/admin/tts/nodes 添加，或补上 moss.tts.url 后重启");
            return;
        }
        TtsNode seed = new TtsNode();
        seed.setName("默认节点（由 moss.tts.url 初始化）");
        seed.setUrl(seedUrl);
        seed.setWeight(1);
        seed.setEnabled(true);
        seed.setSortOrder(0);
        seed.setRemark("首次启动自动创建；可在此页面修改或删除");
        repository.save(seed);
        log.info("✅ 已用 moss.tts.url 初始化首个 TTS 节点: {}", seedUrl);
    }

    /**
     * 重新加载启用节点快照。
     * <p>synchronized 只为避免多个刷新线程同时进出造成无谓的重复查询；
     * 合成路径读的是 volatile 字段，不受这把锁影响，永远不会被刷新阻塞。
     */
    public synchronized void reload() {
        try {
            List<TtsNode> fresh = List.copyOf(repository.findByEnabledTrueOrderBySortOrderAscIdAsc());
            String signature = signatureOf(fresh);
            if (signature.equals(snapshotSignature)) {
                return;
            }
            snapshotSignature = signature;
            // 一次性发布「列表 + 新版本号」，下游因此不可能读到不一致的组合
            Snapshot current = snapshot;
            snapshot = new Snapshot(fresh, current.version() + 1);
            log.info("🔄 TTS 节点快照已更新（v{}，启用 {} 台）: {}",
                    snapshot.version(), fresh.size(), signature);
        } catch (Exception e) {
            // 保留旧快照继续服务：数据库抖动不应中断合成
            log.error("⚠️ 刷新 TTS 节点快照失败，继续沿用上一次快照（{} 台启用）: {}",
                    snapshot.nodes().size(), e.getMessage());
        }
    }

    /** 定时兜底刷新，覆盖「绕过管理接口直接改库」的修改 */
    @Scheduled(fixedDelayString = "${moss.tts.nodes.refresh-millis:5000}",
               initialDelayString = "${moss.tts.nodes.refresh-millis:5000}")
    public void scheduledReload() {
        reload();
    }

    /**
     * 当前快照（只读，调用方不得修改）。可能为空——调用方必须处理，
     * 不能假定至少有一台机器。
     *
     * <p><b>需要同时用到列表和版本号时请用本方法，不要分别调
     * {@link #enabledNodes()} 与 {@link #version()}。</b>
     * 那两个方法各自都对，但连在一起读就会看到不一致的组合
     * （见 {@link Snapshot} 的说明）。
     */
    public Snapshot snapshot() {
        return snapshot;
    }

    /**
     * 当前启用节点快照的节点列表。
     *
     * <p>只关心「有哪些机器」时用它；若还要据版本号做去重判断，
     * 请改用 {@link #snapshot()} 以免读到不一致的组合。
     */
    public List<TtsNode> enabledNodes() {
        return snapshot.nodes();
    }

    /** 快照版本号，内容变化时递增。若要与节点列表配套使用，请改用 {@link #snapshot()} */
    public long version() {
        return snapshot.version();
    }

    /**
     * 一次发布的节点快照：启用节点列表 + 版本号。
     *
     * <p>把两者打包成一个不可变对象再整体替换 volatile 引用，是为了让下游
     * 「读列表 → 读版本」变成一次原子读取。若拆成两个 volatile 字段，
     * 中间插进一次 {@link #reload()} 就会出现「旧列表配新版本号」：
     * 下游据此标记「已同步到新版本」，于是新增节点永远不会被建条目。
     */
    public record Snapshot(List<TtsNode> nodes, long version) {

        public static final Snapshot EMPTY = new Snapshot(List.of(), 0L);

        public Snapshot {
            nodes = List.copyOf(nodes);
        }
    }

    // ===================== 管理端写操作 =====================
    // 每个方法写完立即 reload()，使改动对下一次合成生效（无需重启、无需等刷新周期）。

    public TtsNode create(String name, String url, Integer weight, Integer sortOrder, String remark,
                          String engineType, String language) {
        String cleanUrl = normalizeUrl(url);
        String cleanName = requireText(name, "节点名称");
        if (repository.findByUrl(cleanUrl).isPresent()) {
            throw new IllegalArgumentException("该地址已存在，请勿重复添加");
        }
        TtsNode node = new TtsNode();
        node.setName(truncate(cleanName, MAX_NAME_LENGTH));
        node.setUrl(cleanUrl);
        node.setEngineType(TtsEngineType.fromString(engineType).name());
        node.setLanguage(truncate(trimToNull(language), MAX_LANGUAGE_LENGTH));
        node.setWeight(normalizeWeight(weight));
        node.setEnabled(true);
        node.setSortOrder(sortOrder == null ? 0 : sortOrder);
        node.setRemark(truncate(trimToNull(remark), MAX_REMARK_LENGTH));
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        TtsNode saved = repository.save(node);
        reload();
        log.info("✅ 新增 TTS 节点: id={}, name={}, url={}, engine={}, lang={}",
                saved.getId(), saved.getName(), saved.getUrl(),
                saved.engineTypeValue(), saved.getLanguage());
        return saved;
    }

    /**
     * 更新节点。字段为 null 表示「本次不改该项」（与
     * {@code VoiceCloningTtsService.applySegmentVoices} 的 defaultVoiceProvided 语义一致），
     * 避免局部更新把未提交的字段清空。
     */
    public TtsNode update(Long id, String name, String url, Integer weight,
                          Integer sortOrder, String remark, Boolean enabled,
                          String engineType, String language) {
        TtsNode node = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + id));
        if (name != null) {
            node.setName(truncate(requireText(name, "节点名称"), MAX_NAME_LENGTH));
        }
        if (url != null) {
            String cleanUrl = normalizeUrl(url);
            repository.findByUrl(cleanUrl)
                    .filter(other -> !other.getId().equals(id))
                    // 改成一个已被其它节点占用的地址会让调度器把同一台机器算作两台，
                    // 实际负载翻倍；必须在这里拦住
                    .ifPresent(other -> {
                        throw new IllegalArgumentException("该地址已被节点「" + other.getName() + "」占用");
                    });
            node.setUrl(cleanUrl);
        }
        if (weight != null) {
            node.setWeight(normalizeWeight(weight));
        }
        if (sortOrder != null) {
            node.setSortOrder(sortOrder);
        }
        if (remark != null) {
            node.setRemark(truncate(trimToNull(remark), MAX_REMARK_LENGTH));
        }
        if (enabled != null) {
            node.setEnabled(enabled);
        }
        if (engineType != null) {
            node.setEngineType(TtsEngineType.fromString(engineType).name());
        }
        if (language != null) {
            // 传空串即显式清空语言（trimToNull("")→null），与其它可清空字段一致
            node.setLanguage(truncate(trimToNull(language), MAX_LANGUAGE_LENGTH));
        }
        node.setUpdatedAt(LocalDateTime.now());
        TtsNode saved = repository.save(node);
        reload();
        log.info("✅ 更新 TTS 节点: id={}, name={}, url={}, enabled={}, engine={}, lang={}",
                saved.getId(), saved.getName(), saved.getUrl(), saved.isEnabled(),
                saved.engineTypeValue(), saved.getLanguage());
        return saved;
    }

    /**
     * 物理删除。与「停用」的区别：停用保留记录（在途请求自然跑完、统计可追溯），
     * 删除则连记录一起消失。管理端主推停用，删除留给「地址填错要重加」的场景。
     */
    public void delete(Long id) {
        TtsNode node = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + id));
        repository.delete(node);
        reload();
        log.info("🗑️ 删除 TTS 节点: id={}, name={}, url={}", node.getId(), node.getName(), node.getUrl());
    }

    /** 管理端列表：全部节点（含已停用），按 sortOrder, id 排序 */
    public List<TtsNode> findAll() {
        return repository.findAllByOrderBySortOrderAscIdAsc();
    }

    // ===================== 校验工具 =====================
    // 放在注册中心而非 Controller：这些约束是数据完整性的一部分，
    // 任何写入路径都必须遵守，不能只靠某一个入口自觉调用。

    /**
     * 校验并规范化节点地址。
     *
     * <p>只接受 http/https + 非空主机。用 {@link URI} 解析而非字符串前缀判断：
     * 前缀匹配会放过 {@code http://} 这种没有主机的地址，以及
     * {@code http://evil.com#@127.0.0.1} 这类靠片段/用户信息混淆的写法。
     *
     * @throws IllegalArgumentException 地址非法
     */
    public static String normalizeUrl(String url) {
        String raw = requireText(url, "节点地址");
        URI uri;
        try {
            uri = new URI(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("节点地址格式非法: " + raw);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("节点地址必须以 http:// 或 https:// 开头");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("节点地址缺少主机名，例如 http://10.0.0.11:18083/api/generate");
        }
        // 带用户信息的地址（http://user:pass@host/）会把凭据散落在日志与数据库中，
        // 且当前引擎不支持鉴权，属于明确的误配
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("节点地址不支持携带用户名密码");
        }
        if (raw.length() > 255) {
            throw new IllegalArgumentException("节点地址过长（上限 255 字符）");
        }
        return raw;
    }

    private static Integer normalizeWeight(Integer weight) {
        if (weight == null) {
            return 1;
        }
        if (weight < 1) {
            throw new IllegalArgumentException("权重必须大于等于 1");
        }
        return weight;
    }

    private static String requireText(String value, String field) {
        String v = trimToNull(value);
        if (v == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return v;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** 节点内容签名：id|url|weight|sortOrder|engine|language。任一变化都会让 version 递增。 */
    private static String signatureOf(List<TtsNode> nodes) {
        StringJoiner joiner = new StringJoiner(";");
        for (TtsNode n : nodes) {
            joiner.add(n.getId() + "|" + n.getUrl() + "|" + n.getWeight() + "|" + n.getSortOrder()
                    + "|" + n.engineTypeValue() + "|" + n.getLanguage());
        }
        return joiner.toString();
    }
}
