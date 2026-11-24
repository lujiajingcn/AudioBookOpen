package com.audiobook.server.service;

import com.audiobook.server.model.UserAudioCoverage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AudioCoverageService#computeEtag(Long, List)} 行为测试（P1 #3：增量同步 / 免流量 304）。
 *
 * <p>验证四件事：
 * <ol>
 *   <li><b>格式稳定：</b>相同 (userId, rows) 必得相同 ETag；空列表也稳定（不能每次不同）。</li>
 *   <li><b>跨用户不撞：</b>userId 显式拼进值里，两个用户即便覆盖图完全相同 ETag 也不同——
 *       客户端切换账号时缓存自动失效。</li>
 *   <li><b>变化敏感：</b>行数变化 / 最新 updatedAt 变化都会让 ETag 变。</li>
 *   <li><b>RFC 7232 语法：</b>强验证器必须带双引号，不含 {@code W/} 前缀。</li>
 * </ol>
 */
class AudioCoverageEtagTest {

    // ---------- 辅助 ----------

    private static UserAudioCoverage row(String bookKey, String source, LocalDateTime updatedAt) {
        UserAudioCoverage r = new UserAudioCoverage(1L, bookKey, source);
        r.setDoneRanges("1-10");
        r.setDoneCount(10);
        r.setTotalSegments(20);
        // 绕开 @PrePersist/@PreUpdate：直接 setter 落时间戳（这里只测 computeEtag 的读取逻辑）
        r.setCreatedAt(updatedAt);
        r.setUpdatedAt(updatedAt);
        return r;
    }

    // ---------- 1) 格式稳定 ----------

    @Test
    void emptyListYieldsStableEtag() {
        String e1 = AudioCoverageService.computeEtag(42L, Collections.emptyList());
        String e2 = AudioCoverageService.computeEtag(42L, Collections.emptyList());
        assertEquals(e1, e2, "同一 userId + 空列表必须稳定");
        assertEquals("\"cov-42-0-0\"", e1, "空覆盖图的 ETag 应形如 cov-<uid>-0-0");
    }

    @Test
    void nullListTreatedAsEmpty() {
        String e = AudioCoverageService.computeEtag(42L, null);
        assertEquals("\"cov-42-0-0\"", e);
    }

    @Test
    void sameRowsYieldSameEtag() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 5, 10, 30, 0);
        List<UserAudioCoverage> rows = Arrays.asList(
                row("s:1", "server", t),
                row("n:book", "local", t.minusHours(1))
        );
        String e1 = AudioCoverageService.computeEtag(7L, rows);
        String e2 = AudioCoverageService.computeEtag(7L, new ArrayList<>(rows));
        assertEquals(e1, e2);
    }

    // ---------- 2) 跨用户不撞 ----------

    @Test
    void differentUsersNeverCollide() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 5, 10, 30, 0);
        List<UserAudioCoverage> rows = Collections.singletonList(row("s:1", "server", t));
        String userA = AudioCoverageService.computeEtag(1L, rows);
        String userB = AudioCoverageService.computeEtag(2L, rows);
        assertNotEquals(userA, userB, "userId 不同必须得到不同 ETag（防跨账号缓存误命中）");
        assertTrue(userA.contains("-1-"), "userA 的 ETag 应包含其 userId");
        assertTrue(userB.contains("-2-"), "userB 的 ETag 应包含其 userId");
    }

    @Test
    void nullUserIdFallsBackToZero() {
        // 防御性：controller 已保证 user != null 才走到这里，但 helper 自身不能 NPE
        String e = AudioCoverageService.computeEtag(null, Collections.emptyList());
        assertEquals("\"cov-0-0-0\"", e);
    }

    // ---------- 3) 变化敏感 ----------

    @Test
    void rowCountChangeBumpsEtag() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 5, 10, 30, 0);
        String one = AudioCoverageService.computeEtag(1L,
                Collections.singletonList(row("s:1", "server", t)));
        String two = AudioCoverageService.computeEtag(1L, Arrays.asList(
                row("s:1", "server", t),
                row("s:2", "server", t)));
        assertNotEquals(one, two, "行数变化必须让 ETag 变化");
    }

    @Test
    void maxUpdatedAtChangeBumpsEtag() {
        LocalDateTime t1 = LocalDateTime.of(2026, 11, 5, 10, 30, 0);
        LocalDateTime t2 = t1.plusSeconds(1);
        String e1 = AudioCoverageService.computeEtag(1L,
                Collections.singletonList(row("s:1", "server", t1)));
        String e2 = AudioCoverageService.computeEtag(1L,
                Collections.singletonList(row("s:1", "server", t2)));
        assertNotEquals(e1, e2, "maxUpdatedAt 变化必须让 ETag 变化");
    }

    @Test
    void picksMaxUpdatedAtAcrossRows() {
        LocalDateTime older = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 11, 5, 10, 30, 0);
        // 乱序：新行不在首位，验证算法扫全表取 max 而不是取首行
        List<UserAudioCoverage> rows = Arrays.asList(
                row("s:1", "server", older),
                row("s:2", "server", newer),
                row("s:3", "server", older.plusHours(1))
        );
        String e = AudioCoverageService.computeEtag(1L, rows);
        long expectedSec = newer.toEpochSecond(ZoneOffset.UTC);
        assertTrue(e.endsWith("-" + expectedSec + "\""),
                "ETag 应以全表 maxUpdatedAt 的 epoch 秒结尾，实际: " + e);
    }

    @Test
    void rowsWithNullUpdatedAtSkipped() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 5, 10, 30, 0);
        UserAudioCoverage nullRow = new UserAudioCoverage(1L, "s:9", "server");
        nullRow.setUpdatedAt(null);
        List<UserAudioCoverage> rows = Arrays.asList(nullRow, row("s:1", "server", t));
        String e = AudioCoverageService.computeEtag(1L, rows);
        long expectedSec = t.toEpochSecond(ZoneOffset.UTC);
        assertTrue(e.endsWith("-" + expectedSec + "\""),
                "updatedAt 为 null 的行应被跳过，实际: " + e);
    }

    // ---------- 4) RFC 7232 语法 ----------

    @Test
    void etagIsQuotedStrongValidator() {
        String e = AudioCoverageService.computeEtag(1L, Collections.emptyList());
        assertTrue(e.startsWith("\""), "强 ETag 必须以双引号开头");
        assertTrue(e.endsWith("\""), "强 ETag 必须以双引号结尾");
        assertFalse(e.startsWith("W/"), "强 ETag 不能带 W/ 前缀");
        assertFalse(e.startsWith("\"W/"), "强 ETag 不能带 \"W/ 前缀");
    }

    @Test
    void etagDoesNotContainComma() {
        // 逗号是 If-None-Match 多值分隔符，ETag 值本身含逗号会破坏 header 解析
        LocalDateTime t = LocalDateTime.of(2026, 11, 5, 10, 30, 0);
        String e = AudioCoverageService.computeEtag(1L,
                Collections.singletonList(row("s:1", "server", t)));
        assertFalse(e.contains(","), "ETag 值不能含逗号，实际: " + e);
    }
}
