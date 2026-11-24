package com.audiobook.server.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * MOSS-TTS-Nano 合成节点（一台部署了语音合成引擎的机器）。
 *
 * <p>本表是「有哪些机器可以承接合成任务」的<b>唯一真源</b>。改造前这个信息只有
 * {@code moss.tts.url} 一行配置，改地址必须重启服务；改造后管理端通过
 * {@code /api/admin/tts/nodes} 增删启停，注册中心定时刷新快照，运行期即刻生效。
 *
 * <p>这里<b>只存配置</b>，不存运行状态。在途请求数、熔断冷却、探活结果都是进程内
 * 内存态（见 {@code TtsLoadBalancer.NodeRuntime}），原因有二：
 * <ul>
 *   <li>它们随进程生死，落库后重启会留下「上次进程的陈旧在途数」，反而误导调度；</li>
 *   <li>每次合成都要读写，落库会把这个纯内存操作变成高频数据库写入。</li>
 * </ul>
 *
 * <p>{@code url} 是完整的合成端点（含 {@code /api/generate} 这类路径），与
 * {@code moss.tts.url} 的语义完全一致，因此种子节点可以直接把该配置搬进来。
 * 加唯一约束是为了防止同一台机器被登记两次——重复登记会让调度器把它算作两个节点，
 * 实际负载翻倍，是纯噪音。
 */
@Entity
@Table(
    name = "tts_nodes",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_tts_node_url", columnNames = {"url"})
    },
    indexes = {
        // 注册中心定时刷新走的是 enabled=true + sort_order 排序，走这条索引避免全表扫描
        @Index(name = "idx_tts_node_enabled_sort", columnList = "enabled, sort_order")
    }
)
public class TtsNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 展示名，管理端列表用；重名无害（与 url 不同，不参与调度） */
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /**
     * 完整合成端点，例如 {@code http://10.0.0.11:18083/api/generate}。
     * 长度 255 足够容纳 IPv6 地址 + 较长路径；同时 255×4=1020 字节仍在 InnoDB
     * 唯一索引键长上限（3072 字节）之内，加唯一约束不会导致建表失败。
     */
    @Column(name = "url", nullable = false, length = 255)
    private String url;

    /**
     * 引擎类型：{@code MOSS}（默认）/ {@code POCKET}。
     *
     * <p>决定选点时该把请求派发到这台机器、以及用哪种请求/响应协议
     * （MOSS 是 {@code /api/generate} + JSON(base64 WAV)，Pocket 是 {@code /tts} + 裸 WAV）。
     *
     * <p>列可空且代码层<b>把 null/未知一律当 MOSS</b>（见 {@link #engineTypeValue()}），
     * 因此加列之前的存量节点无需数据迁移即可继续按 MOSS 调度。
     */
    @Column(name = "engine_type", length = 16)
    private String engineType = "MOSS";

    /**
     * 该节点承载的自然语言（如 {@code english}、{@code french}），仅对 POCKET 节点有意义：
     * Pocket 每个 serve 实例只加载一种语言的模型，多语言=多实例，本列用于按语言选点与运维辨识。
     * MOSS 节点留空即可。
     */
    @Column(name = "language", length = 32)
    private String language;

    /**
     * 权重。当前选点算法（最少在途优先）只在「在途数相同」时用它做次级排序，
     * 因此权重大 = 同负载下优先被选中，适合机器性能不均（如 3090 与 4090 混部）的场景。
     * 至少为 1，0 或负数无意义。
     */
    @Column(name = "weight", nullable = false)
    private Integer weight = 1;

    /**
     * 是否参与调度。删除节点一律走「停用」而非物理删除：
     * 停用立即停止分配新请求，但保留记录便于观察与恢复；
     * 物理删除会导致该节点的历史运行统计一并消失，排障时无从对照。
     */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /** 升序排列即为管理端展示顺序，也让选点在同分时有一个稳定的初始序 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 备注（机器用途、机房、GPU 型号等），仅管理端可见 */
    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public TtsNode() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    /** 供 JPA/Jackson 之外的调用方直接判真；null 一律视为未启用 */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** 引擎类型枚举视图：null/未知一律回落 MOSS，保证存量节点无需迁移即可正确调度 */
    public TtsEngineType engineTypeValue() {
        return TtsEngineType.fromString(engineType);
    }

    public String getEngineType() {
        return engineType;
    }

    public void setEngineType(String engineType) {
        this.engineType = engineType;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
