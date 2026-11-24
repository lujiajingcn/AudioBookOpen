package com.audiobook.server.repository;

import com.audiobook.server.model.TtsNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * MOSS-TTS-Nano 节点配置。
 *
 * <p>查询全部按 {@code sortOrder, id} 排序：{@code sortOrder} 决定管理端展示顺序，
 * 同时也是选点算法在同在途数时的稳定初始序；加上 id 兜底是为了让排序结果在
 * sortOrder 全为 0（默认值）时仍然确定，避免数据库返回顺序漂移导致选点忽左忽右。
 */
public interface TtsNodeRepository extends JpaRepository<TtsNode, Long> {

    /** 管理端列表：不区分启用状态，全部返回 */
    List<TtsNode> findAllByOrderBySortOrderAscIdAsc();

    /** 注册中心刷新用：只取参与调度的节点 */
    List<TtsNode> findByEnabledTrueOrderBySortOrderAscIdAsc();

    /** 新增/改地址时查重，避免同一台机器被登记两次导致实际负载翻倍 */
    Optional<TtsNode> findByUrl(String url);
}
