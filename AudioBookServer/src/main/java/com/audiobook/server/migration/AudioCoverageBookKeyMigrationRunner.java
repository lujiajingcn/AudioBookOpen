package com.audiobook.server.migration;

import com.audiobook.server.service.AudioCoverageBookKeyMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动时执行的一次性数据迁移：把 {@code user_audio_coverage.book_key} 中的 {@code n:} 分支
 * 按 {@link com.audiobook.server.util.BookKeys#normalize(String)} 重写为归一形，合并同
 * {@code (userId, source, 归一后 bookKey)} 的重复行。详见
 * {@link AudioCoverageBookKeyMigrationService}。
 *
 * <p><b>幂等：</b>归一化后再启动，SELECT 到的 n: 行都已等于自身归一形，无 UPDATE。
 * 每次启动仅多一次 LIKE 'n:%' 扫描，对生产量级（每人每源一本书一行）可忽略。
 *
 * <p><b>失败不阻塞启动：</b>迁移异常仅记 error 日志，不重抛——覆盖图是感知层，
 * 迁移失败最坏情况是老行仍与新行共存（不会掉数据），下次启动继续尝试即可。
 *
 * <p>可通过 {@code audiobook.coverage-book-key-migration.enabled=false} 显式关闭。
 */
@Component
public class AudioCoverageBookKeyMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AudioCoverageBookKeyMigrationRunner.class);

    private final AudioCoverageBookKeyMigrationService service;
    private final boolean enabled;

    public AudioCoverageBookKeyMigrationRunner(
            AudioCoverageBookKeyMigrationService service,
            @Value("${audiobook.coverage-book-key-migration.enabled:true}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("覆盖图 bookKey 归一化迁移已被配置禁用，跳过");
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            AudioCoverageBookKeyMigrationService.Stats stats = service.migrate();
            long dt = System.currentTimeMillis() - t0;
            log.info("覆盖图 bookKey 归一化迁移结束，耗时 {}ms，{}", dt, stats);
        } catch (Exception e) {
            // 不阻塞启动：迁移失败老行仍在，只是新老键并存导致徽章退化，下一次启动继续尝试
            log.error("覆盖图 bookKey 归一化迁移失败（不阻塞启动，请人工排查）", e);
        }
    }
}
