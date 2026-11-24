package com.audiobook.server.model;

import com.audiobook.server.service.VoiceCloningTtsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「暂停 / 继续合成」的状态语义与内存标记行为。
 *
 * <p><b>为什么这些判定要钉住</b>：paused 夹在「还在跑」与「已结束」之间，
 * 任何一处把它归错类都会出事——
 * <ul>
 *   <li>归进 {@code isUnfinished}：挂起任务占着队列容量不放，别的书再也排不上；</li>
 *   <li>归进 {@code isTerminal}：恢复器与清理逻辑会把它当完成态，界面显示「已完成」；
 *       更糟的是 {@code allowsResynthesis} 会放行，管理员可能直接把半成品顶掉重来；</li>
 *   <li>心跳不设防：管理员点完暂停，工作线程下一次心跳就把 paused 写回 processing，
 *       而线程随即在检查点收手 —— 任务永远停在「看起来在跑、实际没人跑」的 processing。</li>
 * </ul>
 */
class TaskStatusPausedTest {

    @Test
    @DisplayName("paused 不是终态（管理员还能继续，清理/恢复逻辑不能当它已完成）")
    void pausedIsNotTerminal() {
        assertThat(TaskStatus.PAUSED.isTerminal()).isFalse();
        assertThat(TaskStatus.isTerminal("paused")).isFalse();
    }

    @Test
    @DisplayName("paused 不算未完成：挂起任务不占线程、不计队列容量")
    void pausedIsNotUnfinished() {
        assertThat(TaskStatus.PAUSED.isUnfinished()).isFalse();
        assertThat(TaskStatus.isUnfinished("paused")).isFalse();
        assertThat(TaskStatus.unfinishedValues())
                .as("启动恢复器与队列容量校验都按 unfinishedValues 取值，paused 必须不在其中，"
                        + "否则服务重启会把管理员明确暂停的任务偷偷跑起来")
                .doesNotContain("paused")
                .containsExactly("pending", "queued", "processing");
    }

    @Test
    @DisplayName("paused 不允许重新合成（应走「继续」断点续跑，不该丢弃已完成段落）")
    void pausedDisallowsResynthesis() {
        assertThat(TaskStatus.allowsResynthesis("paused"))
                .as("允许重合成会让管理员一键把已合成的段落全丢掉，违背断点续跑的初衷")
                .isFalse();
    }

    @Test
    @DisplayName("isPaused 仅认 paused，未知值与 null 一律不算")
    void isPausedIsStrict() {
        assertThat(TaskStatus.isPaused("paused")).isTrue();
        assertThat(TaskStatus.isPaused("processing")).isFalse();
        assertThat(TaskStatus.isPaused(null)).isFalse();
        assertThat(TaskStatus.isPaused("PAUSED")).as("大小写不同即为脏数据，不当暂停处理").isFalse();
    }

    @Test
    @DisplayName("内存暂停标记：登记后可查到，清除后立刻查不到")
    void pauseMarkerLifecycle() {
        long taskId = 9_900_001L;
        try {
            VoiceCloningTtsService.markTaskPaused(taskId);
            assertThat(VoiceCloningTtsService.isTaskPaused(taskId)).isTrue();

            VoiceCloningTtsService.clearTaskPaused(taskId);
            assertThat(VoiceCloningTtsService.isTaskPaused(taskId))
                    .as("「继续合成」与「删除语音」都依赖清除标记后工作线程不再收手")
                    .isFalse();
        } finally {
            VoiceCloningTtsService.clearTaskPaused(taskId);
        }
    }

    @Test
    @DisplayName("暂停标记与删除标记互不干扰")
    void pauseAndDeleteMarkersAreIndependent() {
        long taskId = 9_900_002L;
        try {
            VoiceCloningTtsService.markTaskPaused(taskId);
            VoiceCloningTtsService.markTaskDeleted(taskId);
            assertThat(VoiceCloningTtsService.isTaskPaused(taskId)).isTrue();
            assertThat(VoiceCloningTtsService.isTaskDeleted(taskId)).isTrue();

            // 「继续合成」只清暂停标记，不该顺手把删除标记也清掉
            VoiceCloningTtsService.clearTaskPaused(taskId);
            assertThat(VoiceCloningTtsService.isTaskDeleted(taskId))
                    .as("清掉删除标记会让仍在收尾的工作线程以为任务还在，去写已被删除的目录")
                    .isTrue();
        } finally {
            VoiceCloningTtsService.clearTaskPaused(taskId);
        }
    }
}
