package com.audiobook.server.service;

import com.audiobook.server.model.UserAudioCoverage;
import com.audiobook.server.repository.UserAudioCoverageRepository;
import com.audiobook.server.util.BookKeys;
import com.audiobook.server.util.DoneRanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 覆盖图 bookKey 归一化迁移服务（一次性数据修正，幂等）。
 *
 * <p><b>为什么需要：</b>Phase3 首次上线时 {@code n:} 分支的 bookKey 只是 {@code trim(displayName)}，
 * 保留了扩展名、大小写与内部空白差异。真机实测已经落库形如
 * {@code n:《富国的伪善…》.pdf} 的行，与后续跨端合并所需的
 * {@code n:《富国的伪善…》}（去扩展名 + 小写 + 压缩空白）不一致，导致同一本书在服务端裂成两行、
 * 客户端按新键查不到旧行的覆盖图 → 徽章回退到"云端未合成"。
 *
 * <p><b>本服务做什么：</b>扫描所有 {@code n:} 前缀的覆盖行，按 {@link BookKeys#normalize(String)}
 * 重算键，同 {@code (userId, source, 归一后 bookKey)} 的行合并（doneRanges 取并集、totalSegments 取最大、
 * storeBookId/serverTaskId 取先出现的非空值），保留 id 最小的作为幸存者，其余删除。归一后为空的病态键
 * （例如 {@code n:.pdf}）直接删除。
 *
 * <p><b>幂等：</b>归一化后再调用本服务，所有行的 bookKey 都已等于其归一形，分组退化为单元素且
 * {@code changed=false}，一次 SELECT + 零 UPDATE，安全。
 *
 * <p>调用方：{@link com.audiobook.server.migration.AudioCoverageBookKeyMigrationRunner}（应用启动时跑一次）。
 */
@Service
public class AudioCoverageBookKeyMigrationService {

    private static final Logger log = LoggerFactory.getLogger(AudioCoverageBookKeyMigrationService.class);

    private final UserAudioCoverageRepository repo;

    public AudioCoverageBookKeyMigrationService(UserAudioCoverageRepository repo) {
        this.repo = repo;
    }

    /** 迁移统计，供 Runner 打日志。 */
    public static final class Stats {
        public int scanned;
        public int groups;
        public int updated;
        public int merged;
        public int deleted;

        @Override
        public String toString() {
            return "scanned=" + scanned + " groups=" + groups
                    + " updated=" + updated + " merged=" + merged + " deleted=" + deleted;
        }
    }

    @Transactional
    public Stats migrate() {
        Stats stats = new Stats();
        List<UserAudioCoverage> legacy = repo.findByBookKeyStartingWith(BookKeys.PREFIX_NAME);
        stats.scanned = legacy.size();
        if (legacy.isEmpty()) {
            log.info("覆盖图 bookKey 归一化迁移：无 n: 前缀行，跳过");
            return stats;
        }

        // 分组：(userId, source, normalizedKey) → rows
        // 注意：normalizedKey 可能为空串（病态键），单独走删除分支
        Map<GroupKey, List<UserAudioCoverage>> groups = new LinkedHashMap<>();
        for (UserAudioCoverage row : legacy) {
            String norm = BookKeys.normalize(row.getBookKey());
            GroupKey k = new GroupKey(row.getUserId(), row.getSource(), norm);
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(row);
        }
        stats.groups = groups.size();

        for (Map.Entry<GroupKey, List<UserAudioCoverage>> e : groups.entrySet()) {
            GroupKey k = e.getKey();
            List<UserAudioCoverage> rows = e.getValue();

            if (k.normalizedKey.isEmpty()) {
                // 病态键（例如 "n:.pdf"、"n:   "）：整组删除
                for (UserAudioCoverage r : rows) {
                    repo.delete(r);
                    stats.deleted++;
                }
                continue;
            }

            // 幸存者 = id 最小（最早入库），保留 createdAt 语义
            rows.sort(Comparator.comparingLong(UserAudioCoverage::getId));
            UserAudioCoverage survivor = rows.get(0);

            // 合并所有行的 doneRanges（并集）与 totalSegments（最大值）与外链 id（首个非空）
            List<long[]> unionRanges = new ArrayList<>();
            int maxTotal = 0;
            Long storeBookId = null;
            Long serverTaskId = null;
            for (UserAudioCoverage r : rows) {
                try {
                    unionRanges.addAll(DoneRanges.parse(nullSafe(r.getDoneRanges())));
                } catch (RuntimeException ex) {
                    // 老数据里若有畸形 doneRanges（理论上 DTO 校验已拦住），迁移期宽容处理：当作空集
                    log.warn("覆盖图迁移：忽略畸形 doneRanges id={} bookKey={} err={}",
                            r.getId(), r.getBookKey(), ex.getMessage());
                }
                if (r.getTotalSegments() != null && r.getTotalSegments() > maxTotal) {
                    maxTotal = r.getTotalSegments();
                }
                if (storeBookId == null && r.getStoreBookId() != null && r.getStoreBookId() > 0) {
                    storeBookId = r.getStoreBookId();
                }
                if (serverTaskId == null && r.getServerTaskId() != null && r.getServerTaskId() > 0) {
                    serverTaskId = r.getServerTaskId();
                }
            }
            String mergedRanges = DoneRanges.encode(DoneRanges.normalize(unionRanges));
            int mergedCount = DoneRanges.count(mergedRanges);

            // 先删除非幸存者并 flush，避免与幸存者 UPDATE 撞 uk_cov_user_book_src
            for (int i = 1; i < rows.size(); i++) {
                repo.delete(rows.get(i));
                stats.deleted++;
            }
            if (rows.size() > 1) {
                repo.flush();
            }

            // 判断幸存者是否需要更新（避免无变化的行触发 @PreUpdate 刷新 updatedAt）
            boolean changed = false;
            if (!Objects.equals(survivor.getBookKey(), k.normalizedKey)) {
                survivor.setBookKey(k.normalizedKey);
                changed = true;
                stats.updated++;
            }
            if (!Objects.equals(nullSafe(survivor.getDoneRanges()), mergedRanges)) {
                survivor.setDoneRanges(mergedRanges);
                survivor.setDoneCount(mergedCount);
                changed = true;
                stats.merged++;
            } else if (survivor.getDoneCount() == null || survivor.getDoneCount() != mergedCount) {
                // doneRanges 相同但 doneCount 冗余字段漂了：一并修正
                survivor.setDoneCount(mergedCount);
                changed = true;
            }
            if (survivor.getTotalSegments() == null || survivor.getTotalSegments() < maxTotal) {
                survivor.setTotalSegments(maxTotal);
                changed = true;
            }
            if (survivor.getStoreBookId() == null && storeBookId != null) {
                survivor.setStoreBookId(storeBookId);
                changed = true;
            }
            if (survivor.getServerTaskId() == null && serverTaskId != null) {
                survivor.setServerTaskId(serverTaskId);
                changed = true;
            }

            if (changed) {
                repo.save(survivor);
            }
        }

        log.info("覆盖图 bookKey 归一化迁移完成：{}", stats);
        return stats;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** 分组键：三元组 (userId, source, 归一后的 bookKey)。 */
    private static final class GroupKey {
        final Long userId;
        final String source;
        final String normalizedKey;

        GroupKey(Long userId, String source, String normalizedKey) {
            this.userId = userId;
            this.source = source;
            this.normalizedKey = normalizedKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GroupKey)) return false;
            GroupKey that = (GroupKey) o;
            return Objects.equals(userId, that.userId)
                    && Objects.equals(source, that.source)
                    && Objects.equals(normalizedKey, that.normalizedKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, source, normalizedKey);
        }
    }
}
