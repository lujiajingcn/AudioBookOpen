package com.audiobook.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 「服务正在停止」这件事的全局信号。
 *
 * <p><b>为什么需要它。</b>部署/重启会打断正在跑的 TTS 段。旧行为把被打断的段一律写成
 * {@code failed}，而 {@code failed} 是终态、{@link TtsTaskRecoveryRunner} 只捞
 * {@code pending} 段 —— 于是<b>每次部署都会在书里永久留一个缺失段</b>：它不会自己重跑，
 * 只能人工把状态改回 pending（2026-09-27 实测：一次热补丁重启产生段 832，
 * 报错 {@code TTS 合成异常: event executor terminated}）。
 *
 * <p>合成链路拿到这个信号后，关闭期间<b>不落终态</b>：段保持 {@code pending}
 * （或中断时停留的 {@code processing}）、任务保持 {@code processing}，
 * 由下次启动的恢复器断点续跑 —— 而 {@code PROCESSING} 正在
 * {@link com.audiobook.server.model.TaskStatus#unfinishedValues()} 里，必然被捞。
 *
 * <p><b>为什么用 {@link ContextClosedEvent} 而不是 {@code @PreDestroy}。</b>
 * 容器关闭的顺序是「先发事件、再 destroyBeans」，而跑合成任务的线程池本身就是个 bean ——
 * 它的 shutdown 发生在 destroyBeans 阶段。所以事件到达时线程池必然还活着，
 * 信号一定先于打断写入；{@code @PreDestroy} 可能晚于线程池关闭，来不及。
 *
 * <p><b>为什么不看 {@code Thread.currentThread().isInterrupted()}。</b>
 * 那个标志在 {@code Thread.sleep} 抛 InterruptedException 时会被清掉
 * （见 {@code VoiceCloningTtsService.sleepQuietly}），也可能被网络层的超时中断误设 ——
 * 两种情况都会把「真正的合成失败」误判成「正在关闭」，让任务永远停在 processing。
 * 主判据只认容器关闭这一个来源。
 */
@Component
public class ShutdownSignal implements ApplicationListener<ContextClosedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ShutdownSignal.class);

    private volatile boolean shuttingDown = false;

    /**
     * 必须排在 {@code LoggingApplicationListener}（其 order 是
     * {@code HIGHEST_PRECEDENCE + 20}）<b>之前</b>执行。
     *
     * <p>否则会得到一个最坏组合：<b>信号置位成功、但日志一行都看不到</b> ——
     * 那个监听器收到 {@code ContextClosedEvent} 时会把日志系统
     * {@code beforeShutdown()} 掉，排它后面的监听器再 {@code log.info} 就是写给空气看。
     * 第一版就是默认最低优先级，部署后「关闭信号日志为空」，
     * 让人误判成「监听器根本没生效」。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (!shuttingDown) {
            shuttingDown = true;
            log.info("🛑 服务正在关闭：未完成的段落保持 pending、任务保持 processing，" +
                    "由下次启动的恢复器断点续跑（不再写成 failed 终态）");
        }
    }

    /** 容器是否已进入关闭流程。 */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * 这次失败是不是「关闭打断」造成的（而非业务失败）。
     *
     * <p>以容器关闭信号为主判据；再补一层兜底：关闭过程中 App 侧的 Netty event loop、
     * Redis 连接工厂、连接池会先于工作线程被拆掉，此时在途的引擎调用会立刻抛错。
     * 这些错误全都只产生于关闭流程，命中哪一条都算关闭打断；都没命中才算真正的合成失败
     * —— 也就是旧行为。
     */
    public boolean isShutdownInterruption(Throwable t) {
        if (shuttingDown) {
            return true;
        }
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                return true;
            }
            // 按类名后缀匹配而不是 instanceof：Netty 另有一个
            // io.netty.util.concurrent.RejectedExecutionException，它并不继承
            // java.util.concurrent 的那个（而是 extends IllegalStateException），
            // 而 event loop 被关掉时抛的正是它 —— 用 instanceof 会漏。
            if (cause.getClass().getName().endsWith("RejectedExecutionException")) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && (message.contains("event executor terminated")      // Netty event loop 已停
                    || message.contains("has been STOPPED")                            // Lettuce 连接工厂已停
                    || message.contains("Connection pool shut down"))) {               // Reactor Netty 连接池已关
                return true;
            }
        }
        return false;
    }
}
