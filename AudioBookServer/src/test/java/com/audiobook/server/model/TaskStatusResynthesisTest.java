package com.audiobook.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「什么时候允许重新合成」的判定（剩余缺陷 #1）。
 *
 * <p><b>为什么必须有</b>：书城书的 `task_id` 是在**建任务时**就回绑的（早于异步合成结果）。
 * 判据一旦写成"task_id 非空就拒绝"，一次失败就把这本书永久卡死 —— 线上只能人工改库。
 * 反过来说，若把 `completed` 也放行，重新合成会顶掉已有可用音频。两个方向都错得很贵，
 * 所以这条判定单独钉住。
 */
class TaskStatusResynthesisTest {

    @Test
    @DisplayName("failed / partial 允许重新合成（上一次没成功）")
    void failedAndPartialAllowResynthesis() {
        assertThat(TaskStatus.allowsResynthesis("failed")).isTrue();
        assertThat(TaskStatus.allowsResynthesis("partial")).isTrue();
    }

    @Test
    @DisplayName("completed 不允许（已有可用音频，重来会覆盖旧任务）")
    void completedIsRejected() {
        assertThat(TaskStatus.allowsResynthesis("completed")).isFalse();
    }

    @Test
    @DisplayName("未完成三态不允许（还在跑，不能再压一个任务）")
    void unfinishedIsRejected() {
        for (String s : new String[]{"pending", "queued", "processing"}) {
            assertThat(TaskStatus.allowsResynthesis(s))
                    .as("状态 %s 不应允许重新合成", s)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("null / 未知值兜底为不允许（状态不明时宁可不放行）")
    void unknownIsRejected() {
        assertThat(TaskStatus.allowsResynthesis(null)).isFalse();
        assertThat(TaskStatus.allowsResynthesis("")).isFalse();
        assertThat(TaskStatus.allowsResynthesis("weird-status")).isFalse();
    }
}
