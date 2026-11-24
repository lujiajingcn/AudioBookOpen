package com.audiobook.server.service;

import com.audiobook.server.dto.BookMetaItemRequest;
import com.audiobook.server.dto.UserSettingItemRequest;
import com.audiobook.server.model.UserBookMeta;
import com.audiobook.server.model.UserSetting;
import com.audiobook.server.repository.UserBookMetaRepository;
import com.audiobook.server.repository.UserSettingRepository;
import com.audiobook.server.util.LwwVersions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨端「单值偏好 / 每书元数据」的 LWW 合并语义。
 *
 * <p>为什么必须有这组测试：现有 9 类同步都是集合型 add-no-delete 并集，
 * 若把单值偏好也按并集处理，会出现「旧值永远赢 —— 用户改了倍速同步不过去」的静默 bug。
 * 这里锁住的核心不变式是：
 * <ol>
 *   <li>版本号严格单调递增（同毫秒并发 / 时钟回拨也不能倒退）；</li>
 *   <li>只有客户端版本严格大于服务端版本才覆盖，否则服务端一次库写入都不做；</li>
 *   <li>键白名单与值校验必须静默忽略非法项，不能让老客户端多传字段把整个请求打挂；</li>
 *   <li>bookKey 归一化后去重，避免同一本书裂成两行元数据。</li>
 * </ol>
 */
class LwwSyncTest {

    private final UserSettingRepository settingsRepo = mock(UserSettingRepository.class);
    private final UserSettingsService settingsService = new UserSettingsService(settingsRepo);
    private final UserBookMetaRepository metaRepo = mock(UserBookMetaRepository.class);
    private final BookMetaService metaService = new BookMetaService(metaRepo);

    // ---------------------------------------------------------------- 版本号工具

    @Test
    @DisplayName("LWW：上报版本必须严格大于现有版本才有资格覆盖")
    void lwwShouldOverwriteOnlyWhenStrictlyNewer() {
        assertThat(LwwVersions.shouldOverwrite(101L, 100L)).isTrue();
        assertThat(LwwVersions.shouldOverwrite(100L, 100L)).isFalse();
        assertThat(LwwVersions.shouldOverwrite(99L, 100L)).isFalse();
        assertThat(LwwVersions.shouldOverwrite(null, 0L)).isFalse();
        assertThat(LwwVersions.shouldOverwrite(-5L, 0L)).isFalse();
    }

    @Test
    @DisplayName("LWW：新版本严格大于旧版本，免疫同毫秒并发与时钟回拨")
    void lwwVersionIsStrictlyMonotonic() {
        assertThat(LwwVersions.nextVersion(1000L, 999L)).isEqualTo(1000L);
        // 客户端时钟回拨：上报版本比服务端还旧，服务端至少 +1
        assertThat(LwwVersions.nextVersion(10L, 5000L)).isEqualTo(5001L);
        assertThat(LwwVersions.nextVersion(null, 5000L)).isEqualTo(5001L);
        long before = System.currentTimeMillis();
        assertThat(LwwVersions.firstVersion(0L)).isGreaterThanOrEqualTo(before);
        assertThat(LwwVersions.firstVersion(before + 10_000L)).isEqualTo(before + 10_000L);
    }

    // ---------------------------------------------------------------- 用户设置

    @Test
    @DisplayName("首次上报：落库且版本不小于当前时钟")
    void firstSyncInsertsWithMonotonicVersion() {
        when(settingsRepo.findByUserIdAndSettingKey(7L, "playback_speed")).thenReturn(Optional.empty());
        when(settingsRepo.findByUserId(7L)).thenReturn(List.of());
        long before = System.currentTimeMillis();

        settingsService.sync(7L, List.of(
                new UserSettingItemRequest("playback_speed", "1.5", 100L)));

        ArgumentCaptor<UserSetting> cap = ArgumentCaptor.forClass(UserSetting.class);
        verify(settingsRepo).save(cap.capture());
        assertThat(cap.getValue().getSettingValue()).isEqualTo("1.5");
        assertThat(cap.getValue().getUpdatedAt()).isGreaterThanOrEqualTo(before);
    }

    @Test
    @DisplayName("本地改过设置 ⇒ 客户端版本更大 ⇒ 覆盖服务端旧值")
    void newerClientOverwritesServerValue() {
        UserSetting existing = new UserSetting(7L, "playback_speed", "1.0", 1000L);
        when(settingsRepo.findByUserIdAndSettingKey(7L, "playback_speed"))
                .thenReturn(Optional.of(existing));
        when(settingsRepo.findByUserId(7L)).thenReturn(List.of(existing));

        settingsService.sync(7L, List.of(
                new UserSettingItemRequest("playback_speed", "2.0", 2000L)));

        assertThat(existing.getSettingValue()).isEqualTo("2.0");
        assertThat(existing.getUpdatedAt()).isEqualTo(2000L);
        verify(settingsRepo).save(existing);
    }

    @Test
    @DisplayName("对端改过设置 ⇒ 客户端版本更旧 ⇒ 服务端一次库写入都不做")
    void olderClientDoesNotOverwrite() {
        UserSetting existing = new UserSetting(7L, "playback_speed", "1.75", 9000L);
        when(settingsRepo.findByUserIdAndSettingKey(7L, "playback_speed"))
                .thenReturn(Optional.of(existing));
        when(settingsRepo.findByUserId(7L)).thenReturn(List.of(existing));

        settingsService.sync(7L, List.of(
                new UserSettingItemRequest("playback_speed", "1.0", 1000L)));

        assertThat(existing.getSettingValue()).isEqualTo("1.75");
        assertThat(existing.getUpdatedAt()).isEqualTo(9000L);
        verify(settingsRepo, never()).save(any(UserSetting.class));
    }

    @Test
    @DisplayName("每轮同步上报相同版本不应把版本号无限抬高")
    void repeatedIdenticalSyncIsNoOp() {
        UserSetting existing = new UserSetting(7L, "default_voice_id", "Junhao", 5000L);
        when(settingsRepo.findByUserIdAndSettingKey(7L, "default_voice_id"))
                .thenReturn(Optional.of(existing));
        when(settingsRepo.findByUserId(7L)).thenReturn(List.of(existing));

        settingsService.sync(7L, List.of(
                new UserSettingItemRequest("default_voice_id", "Junhao", 5000L)));

        assertThat(existing.getUpdatedAt()).isEqualTo(5000L);
        verify(settingsRepo, never()).save(any(UserSetting.class));
    }

    @Test
    @DisplayName("键白名单：任意 key / 非法倍速静默忽略，不让整个请求失败")
    void illegalKeysAndValuesAreIgnored() {
        when(settingsRepo.findByUserId(7L)).thenReturn(List.of());

        settingsService.sync(7L, List.of(
                new UserSettingItemRequest("moss_python_exe", "C:\\secret", 9999L),
                new UserSettingItemRequest("playback_speed", "999", 9999L),
                new UserSettingItemRequest("playback_speed", "not-a-number", 9999L)));

        verify(settingsRepo, never()).save(any(UserSetting.class));
        verify(settingsRepo, never()).findByUserIdAndSettingKey(anyLong(), anyString());
    }

    @Test
    @DisplayName("倍速值归一化：1 / 1.0 / 1.00 收敛成同一表示")
    void speedIsNormalized() {
        assertThat(UserSettingsService.sanitize("playback_speed", "1")).isEqualTo("1.0");
        assertThat(UserSettingsService.sanitize("playback_speed", "1.00")).isEqualTo("1.0");
        assertThat(UserSettingsService.sanitize("playback_speed", " 1.25 ")).isEqualTo("1.25");
        assertThat(UserSettingsService.sanitize("playback_speed", "0.1")).isNull();
        assertThat(UserSettingsService.sanitize("playback_speed", "4.0")).isNull();
        assertThat(UserSettingsService.sanitize("playback_speed", "NaN")).isNull();
        assertThat(UserSettingsService.sanitize("unknown_key", "x")).isNull();
    }

    @Test
    @DisplayName("同一批次重复 key 只保留最后一条（不自我覆盖）")
    void duplicateKeysInOneBatchKeepLast() {
        when(settingsRepo.findByUserIdAndSettingKey(7L, "playback_speed"))
                .thenReturn(Optional.empty());
        when(settingsRepo.findByUserId(7L)).thenReturn(List.of());

        settingsService.sync(7L, List.of(
                new UserSettingItemRequest("playback_speed", "1.0", 100L),
                new UserSettingItemRequest("playback_speed", "1.5", 200L)));

        ArgumentCaptor<UserSetting> cap = ArgumentCaptor.forClass(UserSetting.class);
        verify(settingsRepo, times(1)).save(cap.capture());
        assertThat(cap.getValue().getSettingValue()).isEqualTo("1.5");
        assertThat(cap.getValue().getUpdatedAt()).isGreaterThanOrEqualTo(200L);
    }

    // ---------------------------------------------------------------- 每书元数据

    @Test
    @DisplayName("bookKey 归一化后写库，空 key 跳过")
    void bookKeyIsNormalizedAndEmptySkipped() {
        when(metaRepo.findByUserId(7L)).thenReturn(List.of());
        when(metaRepo.findByUserIdAndBookKey(anyLong(), anyString())).thenReturn(Optional.empty());

        metaService.sync(7L, List.of(
                new BookMetaItemRequest("n:My  Book.TXT", 3, 1000L, 10L),
                new BookMetaItemRequest("   ", 1, 0L, 10L),
                new BookMetaItemRequest("", 1, 0L, 10L)));

        ArgumentCaptor<UserBookMeta> cap = ArgumentCaptor.forClass(UserBookMeta.class);
        verify(metaRepo, times(1)).save(cap.capture());
        assertThat(cap.getValue().getBookKey()).isEqualTo("n:my book");
        assertThat(cap.getValue().getSortOrder()).isEqualTo(3);
        assertThat(cap.getValue().getLastReadAt()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("归一化后同 key 的重复项只保留最后一条")
    void duplicateBookKeysCollapseAfterNormalize() {
        when(metaRepo.findByUserId(7L)).thenReturn(List.of());
        when(metaRepo.findByUserIdAndBookKey(anyLong(), anyString())).thenReturn(Optional.empty());

        metaService.sync(7L, List.of(
                new BookMetaItemRequest("n:Book.TXT", 1, 0L, 10L),
                new BookMetaItemRequest("n:book", 9, 5000L, 20L)));

        verify(metaRepo, times(1)).save(any(UserBookMeta.class));
    }

    @Test
    @DisplayName("元数据 LWW：版本更旧不覆盖，负时间戳夹到 0，排序值夹到上限")
    void bookMetaLwwAndClamping() {
        UserBookMeta existing = new UserBookMeta(7L, "s:45", 7, 1234L, 9000L);
        when(metaRepo.findByUserIdAndBookKey(7L, "s:45")).thenReturn(Optional.of(existing));
        when(metaRepo.findByUserId(7L)).thenReturn(List.of(existing));

        metaService.sync(7L, List.of(
                new BookMetaItemRequest("s:45", 999_999_999, -50L, 1000L)));
        assertThat(existing.getSortOrder()).isEqualTo(7);
        assertThat(existing.getLastReadAt()).isEqualTo(1234L);
        verify(metaRepo, never()).save(any(UserBookMeta.class));

        metaService.sync(7L, List.of(
                new BookMetaItemRequest("s:45", 999_999_999, -50L, 10_000L)));
        assertThat(existing.getSortOrder()).isEqualTo(1_000_000);
        assertThat(existing.getLastReadAt()).isZero();
        assertThat(existing.getUpdatedAt()).isEqualTo(10_000L);
    }
}
