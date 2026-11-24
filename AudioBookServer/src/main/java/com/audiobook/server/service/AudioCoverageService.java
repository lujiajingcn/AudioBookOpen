package com.audiobook.server.service;

import com.audiobook.server.dto.AudioCoverageItemRequest;
import com.audiobook.server.model.UserAudioCoverage;
import com.audiobook.server.repository.UserAudioCoverageRepository;
import com.audiobook.server.util.BookKeys;
import com.audiobook.server.util.DoneRanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 有声覆盖图服务（Phase3 跨端同步真源，仅元数据）。
 *
 * <p>身份 = {@code (userId, bookKey, source)}。合并语义 = doneRanges **并集、只增不减**：
 * 覆盖图是"全局感知层"（哪些段在哪儿以什么来源合成过），不是"本机可播清单"，
 * 所以并集安全——另一端读到的 server 源覆盖本就能经现有接口下载播放，
 * local 源覆盖仅作提示/续合，可播与否由客户端结合自己的本地文件判定。
 *
 * <p>v1 无删除端点：单机删本地音频不应抹掉其它设备的"已合成"感知。下架/删书级联留待后续。
 */
@Service
public class AudioCoverageService {

    private static final Logger log = LoggerFactory.getLogger(AudioCoverageService.class);

    private final UserAudioCoverageRepository coverageRepository;

    public AudioCoverageService(UserAudioCoverageRepository coverageRepository) {
        this.coverageRepository = coverageRepository;
    }

    @Transactional(readOnly = true)
    public List<UserAudioCoverage> list(Long userId) {
        return coverageRepository.findByUserIdOrderByUpdatedAtDescIdDesc(userId);
    }

    /**
     * 计算覆盖图响应的强 ETag（P1 #3：增量同步 / 免流量 304）。
     *
     * <p>格式：{@code "cov-<userId>-<count>-<maxUpdatedAtEpochSecond>"}（含引号，符合 RFC 7232 强验证器语法）。
     * userId 显式拼进值里，保证**跨用户永不撞**——即便两人覆盖图完全相同，ETag 也不同，
     * 客户端切换账号后不会误命中缓存。
     *
     * <p>{@code rows} 通常直接来自 {@link #list(Long)}（已按 updatedAt desc 排序），首行即最新。
     * 空列表时 maxUpdatedAt 用 0，count 用 0，得到形如 {@code "cov-42-0-0"} 的稳定值——
     * 空覆盖图的 ETag 也需稳定，否则客户端每次都要重新拉。
     *
     * <p>不用 hashCode / MD5：字段拼串本身就够短够稳定，加哈希反而多一层不确定。
     */
    public static String computeEtag(Long userId, List<UserAudioCoverage> rows) {
        long count = rows == null ? 0L : rows.size();
        long maxSec = 0L;
        if (rows != null && !rows.isEmpty()) {
            for (UserAudioCoverage r : rows) {
                if (r == null || r.getUpdatedAt() == null) {
                    continue;
                }
                long sec = r.getUpdatedAt().toEpochSecond(java.time.ZoneOffset.UTC);
                if (sec > maxSec) {
                    maxSec = sec;
                }
            }
        }
        return "\"cov-" + (userId == null ? 0L : userId) + "-" + count + "-" + maxSec + "\"";
    }

    /**
     * 批量并集合并，返回合并后的全量。
     * 单条：find-or-create (user,bookKey,source)，doneRanges 并集、totalSegments 取 max、
     * storeBookId/serverTaskId 以服务端已有为优先（避免被空覆盖）。并发撞唯一约束回查再合并。
     */
    @Transactional
    public List<UserAudioCoverage> sync(Long userId, List<AudioCoverageItemRequest> items) {
        if (items != null) {
            for (AudioCoverageItemRequest item : items) {
                if (item == null) {
                    continue;
                }
                mergeOne(userId, item);
            }
        }
        return list(userId);
    }

    private void mergeOne(Long userId, AudioCoverageItemRequest item) {
        // 防御性归一化：老客户端仍发送未归一的 n: 键时，也能与新客户端产出的键在服务端汇合，
        // 避免同一本书在库里裂成多行。s:/t: 前缀原样返回，未知前缀也原样返回。
        String bookKey = BookKeys.normalize(item.getBookKey());
        if (bookKey.isEmpty()) {
            // n: 尾部归一后为空（例如上报了 "n:.pdf" 这种病态键）：直接跳过，不落脏行
            log.debug("覆盖图上报 bookKey 归一化后为空，跳过 userId={} raw={}", userId, item.getBookKey());
            return;
        }
        String source = item.getSource().trim();
        String incoming = normalizeOrNull(item.getDoneRanges());

        UserAudioCoverage row = coverageRepository
                .findByUserIdAndBookKeyAndSource(userId, bookKey, source)
                .orElseGet(() -> {
                    UserAudioCoverage fresh = new UserAudioCoverage(userId, bookKey, source);
                    fresh.setDoneRanges("");
                    fresh.setDoneCount(0);
                    fresh.setTotalSegments(0);
                    return fresh;
                });

        String merged = DoneRanges.merge(nullSafe(row.getDoneRanges()), nullSafe(incoming));
        row.setDoneRanges(merged);
        row.setDoneCount(DoneRanges.count(merged));

        if (item.getTotalSegments() != null && item.getTotalSegments() > 0) {
            int cur = row.getTotalSegments() == null ? 0 : row.getTotalSegments();
            row.setTotalSegments(Math.max(cur, item.getTotalSegments()));
        }
        if (row.getStoreBookId() == null && item.getStoreBookId() != null && item.getStoreBookId() > 0) {
            row.setStoreBookId(item.getStoreBookId());
        }
        if (row.getServerTaskId() == null && item.getServerTaskId() != null && item.getServerTaskId() > 0) {
            row.setServerTaskId(item.getServerTaskId());
        }

        try {
            coverageRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            // 并发新建撞 uk_cov_user_book_src：回查后重跑一次合并（此时行必已存在）
            log.debug("覆盖图并发插入撞唯一约束，回查重合并 userId={} bookKey={} source={}",
                    userId, bookKey, source);
            UserAudioCoverage existing = coverageRepository
                    .findByUserIdAndBookKeyAndSource(userId, bookKey, source).orElseThrow(() -> e);
            String remerged = DoneRanges.merge(nullSafe(existing.getDoneRanges()), nullSafe(incoming));
            existing.setDoneRanges(remerged);
            existing.setDoneCount(DoneRanges.count(remerged));
            if (item.getTotalSegments() != null && item.getTotalSegments() > 0) {
                int cur = existing.getTotalSegments() == null ? 0 : existing.getTotalSegments();
                existing.setTotalSegments(Math.max(cur, item.getTotalSegments()));
            }
            coverageRepository.save(existing);
        }
    }

    public Map<String, Object> toItem(UserAudioCoverage row) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", row.getId());
        item.put("bookKey", row.getBookKey());
        item.put("source", row.getSource());
        item.put("storeBookId", row.getStoreBookId());
        item.put("serverTaskId", row.getServerTaskId());
        item.put("totalSegments", row.getTotalSegments());
        item.put("doneRanges", row.getDoneRanges());
        item.put("doneCount", row.getDoneCount());
        item.put("hasAudio", row.getDoneCount() != null && row.getDoneCount() > 0);
        item.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        return item;
    }

    public List<Map<String, Object>> toItems(List<UserAudioCoverage> rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (UserAudioCoverage r : rows) {
            out.add(toItem(r));
        }
        return out;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String normalizeOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
