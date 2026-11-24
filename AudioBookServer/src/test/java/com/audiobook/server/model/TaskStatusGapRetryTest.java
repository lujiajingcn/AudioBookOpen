package com.audiobook.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「什么时候允许补齐缺失段（断点续跑）」的判定。
 *
 * <p><b>为什么单独钉住</b>：线上出现过一本书 6513 / 6517 段，只差 4 段因
 * {@code Connection prematurely closed} 落在 failed，任务状态是 partial。
 * 此时既不能「继续」（那是 paused 专用路径），也不能让管理员重头合成
 * （6513 段白烧）。唯一正确动作是只把 failed / pending 的段重排 —— 这条判定
 * 决定按钮给不给，判错的两个方向都很贵：漏给 = 书永久卡死；多给 = 会把
 * completed 里的可用音频重跑一遍。
 */
class TaskStatusGapRetryTest {

    @Test
    @DisplayName("partial / failed 允许补齐（已成功的段保留，只补没成功的）")
    void partialAndFailedAllowGapRetry() {
        assertThat(TaskStatus.allowsGapRetry("partial")).isTrue();
        assertThat(TaskStatus.allowsGapRetry("failed")).isTrue();
    }

    @Test
    @DisplayName("paused 不在此列（它走「继续合成」清暂停标记，不重置任何段落）")
    void pausedIsHandledByResumePath() {
        assertThat(TaskStatus.allowsGapRetry("paused")).isFalse();
    }

    @Test
    @DisplayName("completed 不在此列（没有缺失段可补）")
    void completedIsRejected() {
        assertThat(TaskStatus.allowsGapRetry("completed")).isFalse();
    }

    @Test
    @DisplayName("未完成三态不在此列（还在跑，重排片段会与工作线程抢同一批段）")
    void unfinishedIsRejected() {
        for (String s : new String[]{"pending", "queued", "processing"}) {
            assertThat(TaskStatus.allowsGapRetry(s))
                    .as("状态 %s 不应允许补齐缺失段", s)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("null / 未知值兜底为不允许")
    void unknownIsRejected() {
        assertThat(TaskStatus.allowsGapRetry(null)).isFalse();
        assertThat(TaskStatus.allowsGapRetry("")).isFalse();
        assertThat(TaskStatus.allowsGapRetry("weird-status")).isFalse();
    }

    @Test
    @DisplayName("与 allowsResynthesis 的关系：两者都放行 partial / failed，但 paused 只在后者之外")
    void relationshipWithResynthesis() {
        // 能补齐的状态必然是「没成功」的状态，反之亦然；
        // 差异只有 paused：允许从头重开（会解绑旧任务），但不走补齐路径。
        for (String s : new String[]{"partial", "failed"}) {
            assertThat(TaskStatus.allowsGapRetry(s)).isEqualTo(TaskStatus.allowsResynthesis(s));
        }
        assertThat(TaskStatus.allowsResynthesis("paused")).isFalse();
    }
}
