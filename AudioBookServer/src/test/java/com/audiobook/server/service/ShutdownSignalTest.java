package com.audiobook.server.service;

import com.audiobook.server.model.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShutdownSignal} 的回归测试。
 *
 * <p>这个判据一旦写错，后果是双向的：判宽了（把真实失败当关闭）任务会永远停在
 * processing；判严了（把关闭当真实失败）段被写成 failed 终态、永久缺失 ——
 * 也就是本类要修的那个缺陷。所以两个方向都要有用例。
 */
class ShutdownSignalTest {

    private static ContextClosedEvent closedEvent() {
        return new ContextClosedEvent(new StaticApplicationContext());
    }

    @Test
    @DisplayName("初始状态：未关闭；收到 ContextClosedEvent 后置位且不可逆")
    void setsFlagOnContextClosed() {
        ShutdownSignal signal = new ShutdownSignal();
        assertThat(signal.isShuttingDown()).isFalse();

        signal.onApplicationEvent(closedEvent());

        assertThat(signal.isShuttingDown()).isTrue();
        // 重复事件不应改变结论（幂等），也不应把状态翻回去
        signal.onApplicationEvent(closedEvent());
        assertThat(signal.isShuttingDown()).isTrue();
    }

    @Test
    @DisplayName("未关闭时：普通合成失败不算关闭打断")
    void ordinaryFailureIsNotShutdown() {
        ShutdownSignal signal = new ShutdownSignal();

        assertThat(signal.isShutdownInterruption(new RuntimeException("连接被拒"))).isFalse();
        assertThat(signal.isShutdownInterruption(
                new IllegalStateException("空音频", new RuntimeException("engine 500")))).isFalse();
        assertThat(signal.isShutdownInterruption(null)).isFalse();
    }

    @Test
    @DisplayName("未关闭时：线程池拒绝 / 线程中断仍判为关闭打断（兜底判据）")
    void executorAndInterruptFailuresAreShutdown() {
        ShutdownSignal signal = new ShutdownSignal();

        assertThat(signal.isShutdownInterruption(new RejectedExecutionException("Task rejected"))).isTrue();
        assertThat(signal.isShutdownInterruption(new InterruptedException("sleep interrupted"))).isTrue();
        // 生产实证：热补丁重启打断在途段时，App 侧 Netty 已停 -> 包装成 RuntimeException 抛出。
        // 这条消息出自 Netty 的 GlobalEventExecutor，是关闭的确定性标志。
        assertThat(signal.isShutdownInterruption(new RuntimeException(
                "TTS 合成异常: node=http://x/api/generate, event executor terminated"))).isTrue();
        // 同一时刻 Redis 连接工厂已停（Lettuce），也是关闭标志
        assertThat(signal.isShutdownInterruption(new RuntimeException(
                "LettuceConnectionFactory has been STOPPED. Use start() to initialize it"))).isTrue();
        // Netty 自己的 RejectedExecutionException 不继承 java.util.concurrent 那个，须按类名认出
        assertThat(signal.isShutdownInterruption(new NettyLikeRejectedExecutionException("terminated"))).isTrue();
        // 被包装在 cause 链里也要能认出来（重试链路会把原异常包一层再抛）
        assertThat(signal.isShutdownInterruption(
                new RuntimeException("TTS 合成异常", new InterruptedException("interrupted")))).isTrue();
    }

    @Test
    @DisplayName("已关闭时：任何异常都按关闭打断处理，一律不落终态")
    void afterCloseEverythingIsShutdown() {
        ShutdownSignal signal = new ShutdownSignal();
        signal.onApplicationEvent(closedEvent());

        assertThat(signal.isShutdownInterruption(new RuntimeException("连接被拒"))).isTrue();
        assertThat(signal.isShutdownInterruption(null)).isTrue();
    }

    @Test
    @DisplayName("前提不变式：关闭时保持 processing 的任务必须仍会被启动恢复器捞起")
    void processingStillCountsAsUnfinished() {
        // 本修复的整个安全性都建立在这一条上：关闭期间把任务留在 processing（不落终态）
        // 之所以安全，是因为 TtsTaskRecoveryRunner 按 unfinishedValues() 捞任务。
        // 谁把 PROCESSING 从这里删掉，关闭时被打断的任务就再也没人接手了。
        assertThat(TaskStatus.unfinishedValues()).contains(TaskStatus.PROCESSING.toString());
    }

    /**
     * 替身：Netty 的 {@code io.netty.util.concurrent.RejectedExecutionException}
     * 与 JUC 的同名类没有继承关系（它 extends IllegalStateException），
     * 用来钉住「按类名后缀识别」这条判据。
     */
    private static class NettyLikeRejectedExecutionException extends IllegalStateException {
        NettyLikeRejectedExecutionException(String message) {
            super(message);
        }
    }
}
