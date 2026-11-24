package com.audiobook.server.controller;

import com.audiobook.server.model.Task;
import com.audiobook.server.model.TaskStatus;
import com.audiobook.server.model.User;
import com.audiobook.server.repository.TaskRepository;
import com.audiobook.server.repository.UserRepository;
import com.audiobook.server.service.RedisTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

@RestController
@RequestMapping("/api/tts/stream")
public class TaskStreamController {

    private static final Logger log = LoggerFactory.getLogger(TaskStreamController.class);

    /**
     * 全局共享的心跳调度器。
     *
     * 每个 SSE 连接各自 newSingleThreadScheduledExecutor() 时，线程数 = 在线连接数，
     * 高并发下线程资源线性耗尽。这里全部连接共用一个守护线程，每 30 秒轮流给各连接发心跳；
     * 心跳只是极小的保活写，单线程足以支撑；即便某个连接 send 短暂阻塞，
     * 也只延后其他心跳，不影响进度推送（广播走工作线程路径，与本调度器无关）。
     */
    private static final ScheduledExecutorService HEARTBEAT_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 把一个 SSE 连接与其心跳任务绑定，确保连接结束时心跳任务一定被取消。
     *
     * 设计原因：心跳调度器只在心跳 send() 失败时才关闭、而 onCompletion/onTimeout/onError
     * 仅移除 emitter 时，调度器线程会长期驻留。心跳任务运行在共享调度器上，
     * 连接关闭时取消自己那个 ScheduledFuture 即可。
     */
    private static final class EmitterHolder {
        final SseEmitter emitter;
        /** 保护 send/complete 的锁，避免心跳线程与工作线程并发 send 同一 emitter 的竞态 */
        private final Object sendLock = new Object();
        /** 终态事件已发送并 complete 后置位，心跳线程据此跳过，避免对已关闭 emitter 再 send */
        private volatile boolean completed = false;
        /** 本连接在共享调度器上的心跳任务，连接关闭时取消 */
        private volatile ScheduledFuture<?> heartbeatTask;

        EmitterHolder(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void setHeartbeatTask(ScheduledFuture<?> task) {
            this.heartbeatTask = task;
        }

        /** 取消本连接的心跳任务。可被多个生命周期回调安全重复调用。 */
        void cancelHeartbeat() {
            ScheduledFuture<?> task = heartbeatTask;
            if (task != null) {
                task.cancel(false);
                heartbeatTask = null;
            }
        }

        /** 线程安全的发送：已 complete 则直接跳过，且一次只允许一个线程进入。 */
        void send(SseEmitter.SseEventBuilder builder) throws IOException {
            synchronized (sendLock) {
                if (completed) {
                    return;
                }
                emitter.send(builder);
            }
        }

        /**
         * 原子地「发送终态事件 + complete」。
         *
         * 必须在同一个临界区内完成：若拆成 send() 与 complete() 两次加锁，中间存在窗口
         * 让心跳线程或另一次终态广播插进来 —— 前者会在终态事件之后再塞一个 heartbeat，
         * 后者会重复推送终态。已 complete 的连接直接跳过，保证终态只发一次。
         */
        void sendTerminalAndComplete(SseEmitter.SseEventBuilder builder) throws IOException {
            synchronized (sendLock) {
                if (completed) {
                    return;
                }
                emitter.send(builder);
                completed = true;
                emitter.complete();
            }
        }

        /**
         * 连接已被容器关闭（onCompletion/onTimeout/onError）时置位。
         * 只改标志、不再触碰 emitter，让仍在途的工作线程 send() 直接短路返回，
         * 而不是撞出 IllegalStateException。
         */
        void markClosed() {
            synchronized (sendLock) {
                completed = true;
            }
        }
    }

    /** taskId -> Set of EmitterHolder (支持多个客户端同时订阅同一任务) */
    private static final Map<Long, Set<EmitterHolder>> emitters = new ConcurrentHashMap<>();

    private final TaskRepository taskRepository;
    private final RedisTaskService redisTaskService;
    private final UserRepository userRepository;

    public TaskStreamController(TaskRepository taskRepository,
                                RedisTaskService redisTaskService,
                                UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.redisTaskService = redisTaskService;
        this.userRepository = userRepository;
    }

    @GetMapping(value = "/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTaskProgress(@PathVariable Long taskId,
                                         Authentication authentication) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        EmitterHolder holder = new EmitterHolder(emitter);

        emitter.onCompletion(() -> {
            log.info("✅ SSE连接关闭: taskId={}", taskId);
            removeHolder(taskId, holder);
        });

        emitter.onTimeout(() -> {
            log.warn("⏰ SSE连接超时: taskId={}", taskId);
            removeHolder(taskId, holder);
        });

        emitter.onError((ex) -> {
            log.info("SSE连接断开: taskId={}", taskId);
            removeHolder(taskId, holder);
        });

        try {
            Task task = taskRepository.findById(taskId).orElse(null);
            if (task == null) {
                log.warn("SSE订阅失败，任务不存在: taskId={}", taskId);
                sendErrorAndComplete(emitter, holder, "TASK_NOT_FOUND", "任务不存在: " + taskId);
                return emitter;
            }

            // 鉴权必须是「默认拒绝」。若写成 if (authentication != null) 才校验归属，
            // 认证信息为空时整个归属校验被静默跳过——当前由
            // SecurityConfig.anyRequest().authenticated() 兜底保证非空，但安全
            // 语义不能依赖外层配置恰好正确；路由规则一变，这里就会变成
            // 任意订阅他人任务进度的越权通道。此处：无认证一律拒绝。
            if (authentication == null || authentication.getName() == null) {
                log.warn("SSE订阅被拒绝（未认证）: taskId={}", taskId);
                sendErrorAndComplete(emitter, holder, "UNAUTHORIZED", "未登录或登录已过期");
                return emitter;
            }

            User user = userRepository.findByUsername(authentication.getName()).orElse(null);
            if (user == null || !user.getId().equals(task.getUserId())) {
                log.warn("SSE订阅越权: taskId={}, user={}", taskId,
                        user != null ? user.getUsername() : "unknown");
                sendErrorAndComplete(emitter, holder, "ACCESS_DENIED", "无权访问该任务");
                return emitter;
            }

            emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArraySet<>()).add(holder);

            holder.send(SseEmitter.event().name("init").data(progressDataOf(task)));

            Task latest = taskRepository.findById(taskId).orElse(null);
            if (latest != null && isTerminal(latest.getStatus())) {
                holder.sendTerminalAndComplete(SseEmitter.event()
                        .name("complete")
                        .data(progressDataOf(latest)));
                holder.cancelHeartbeat();
                removeHolder(taskId, holder);
            } else {
                startHeartbeat(holder, taskId);
            }
        } catch (IOException e) {
            log.error("SSE连接初始化失败: taskId={}", taskId, e);
            sendErrorAndComplete(emitter, holder, "INIT_FAILED", "SSE连接初始化失败");
        } catch (Exception e) {
            log.error("SSE连接初始化异常: taskId={}", taskId, e);
            sendErrorAndComplete(emitter, holder, "INTERNAL_ERROR", "服务器内部错误");
        }

        return emitter;
    }

    /**
     * 通过 SSE 事件通道发送结构化错误并关闭连接。
     *
     * 直接 throw RuntimeException 会落入 GlobalExceptionHandler 兜底返回 500 + JSON，
     * 但 SSE 客户端期望 text/event-stream 格式，无法解析 JSON；前端 SseClient 对非 401/403/404
     * 的错误会指数退避重试 10 次——任务不存在或无权限属于永久性错误，重试毫无意义。
     *
     * 因此发送 event:error + 结构化 data（含 code 和 message），客户端可据此判断错误类型。
     * holder 未被注册到 emitters Map，因此需手动清理调度器。
     */
    private void sendErrorAndComplete(SseEmitter emitter, EmitterHolder holder,
                                      String code, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("code", code, "message", message)));
        } catch (IOException e) {
            log.debug("发送SSE错误事件失败", e);
        } finally {
            holder.markClosed();
            holder.cancelHeartbeat();
            try { emitter.complete(); } catch (Exception ignored) {}
        }
    }

    /** 任务是否已进入终态（与 broadcastProgress 的判定保持一致） */
    private static boolean isTerminal(String status) {
        return TaskStatus.isTerminal(status);
    }

    /**
     * 构造与 broadcastProgress 同结构的进度数据。
     * completed/total 为 NULL 的历史行按 0 处理——Map.of 不接受 null，
     * 直接放入会抛 NPE（且发生在 emitter 已注册之后，会留下死连接直到超时）
     */
    private static Map<String, Object> progressDataOf(Task task) {
        int completed = task.getCompletedSegments() != null ? task.getCompletedSegments() : 0;
        int total = task.getTotalSegments() != null ? task.getTotalSegments() : 0;
        return Map.of(
                "taskId", task.getId(),
                "status", task.getStatus(),
                "completedSegments", completed,
                "totalSegments", total,
                "progress", String.format("%d/%d", completed, total)
        );
    }

    private void startHeartbeat(EmitterHolder holder, Long taskId) {
        ScheduledFuture<?> future = HEARTBEAT_SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                Map<String, Object> heartbeatData = Map.of(
                    "type", "heartbeat",
                    "timestamp", System.currentTimeMillis()
                );
                holder.send(SseEmitter.event()
                    .name("heartbeat")
                    .data(heartbeatData));
                log.debug("💓 发送心跳: taskId={}", taskId);
            } catch (IllegalStateException | IOException e) {
                log.debug("SSE连接已关闭，停止心跳: taskId={}", taskId);
                holder.cancelHeartbeat();
            }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
        holder.setHeartbeatTask(future);
    }

    /** 从集合移除指定连接的 emitter，取消其心跳任务，集合变空则移除整个 taskId 键 */
    private void removeHolder(Long taskId, EmitterHolder holder) {
        // 先置位再摘除：保证任何拿到该 holder 引用的线程都不会再向已关闭的 emitter 发送
        holder.markClosed();
        Set<EmitterHolder> set = emitters.get(taskId);
        if (set != null) {
            set.remove(holder);
            // 无论当前集合是否还有其它连接，都取消该连接的心跳任务（幂等，可重复调用）
            holder.cancelHeartbeat();
            // computeIfPresent 与注册用的 computeIfAbsent 在同一桶上互斥，
            // 避免「判空 → 摘键」之间刚好有新订阅注册进来而被误删
            emitters.computeIfPresent(taskId, (k, s) -> s.isEmpty() ? null : s);
        } else {
            holder.cancelHeartbeat();
        }
    }

    public static void broadcastProgress(Long taskId, String status,
                                         int completedSegments, int totalSegments) {
        Set<EmitterHolder> taskEmitters = emitters.get(taskId);
        if (taskEmitters == null || taskEmitters.isEmpty()) {
            return;
        }

        Map<String, Object> progressData = Map.of(
                "taskId", taskId,
                "status", status,
                "completedSegments", completedSegments,
                "totalSegments", totalSegments,
                "progress", String.format("%d/%d", completedSegments, totalSegments)
        );

        boolean terminal = TaskStatus.isTerminal(status);

        for (EmitterHolder holder : taskEmitters) {
            try {
                holder.send(SseEmitter.event()
                        .name("progress")
                        .data(progressData));

                if (terminal) {
                    // 终态事件与 complete 在同一临界区内原子完成，保证事件一定在
                    // emitter 关闭、连接从 Map 移除之前送达，不会因心跳并发而丢失。
                    holder.sendTerminalAndComplete(SseEmitter.event()
                            .name("complete")
                            .data(progressData));
                    // 终态已送达，立即取消该连接心跳，避免对已关闭 emitter 继续 send
                    holder.cancelHeartbeat();
                    // 只摘掉确认已收到终态的连接，不整键清空
                    taskEmitters.remove(holder);
                }
                log.debug("📡 SSE推送成功: taskId={}, progress={}/{}",
                        taskId, completedSegments, totalSegments);
            } catch (IOException | IllegalStateException e) {
                // 客户端断开连接后，心跳和进度推送均会抛 IOException，这是正常现象
                log.debug("SSE推送失败（客户端已断开）: taskId={}", taskId);
                // 同时取消该连接的心跳任务
                holder.cancelHeartbeat();
                taskEmitters.remove(holder);
            }
        }

        if (terminal) {
            // 无条件 emitters.remove(taskId) 会让本轮遍历期间新订阅进来的连接被
            // 连带丢弃，既收不到终态也不会被 complete，只能干等 30 分钟超时。
            // 因此仅在集合确实空了时才摘键；computeIfPresent 与注册用的 computeIfAbsent
            // 在 ConcurrentHashMap 同一桶上互斥，不会与新订阅产生竞态。
            emitters.computeIfPresent(taskId, (k, set) -> set.isEmpty() ? null : set);
        }
    }
}
