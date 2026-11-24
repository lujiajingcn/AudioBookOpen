package com.audiobook.server.service;

import com.audiobook.server.model.TaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class RedisTaskService {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskService.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String TASK_PREFIX = "tts:task:";
    private static final String USER_TASKS_PREFIX = "tts:user_tasks:";
    private static final String SYSTEM_STATS_PREFIX = "tts:system_stats";
    private static final long TASK_EXPIRE_HOURS = 24;

    public RedisTaskService(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 构造新任务的 Hash 字段表。
     *
     * <p><b>所有值必须是 String。</b>这里是 {@link StringRedisTemplate}，它的 Hash 序列化器
     * （{@code StringRedisSerializer}）会对每个值做强转，遇到 {@code Long} / {@code Integer}
     * 直接抛 {@code ClassCastException}。异常落在下面的降级 catch 里，只写一行 ERROR ——
     * 后果是任务键根本没建、用户任务集合没加、系统统计没加，而调用方毫无察觉
     * （任务主流程照常跑完，所以线上表现为「功能正常、统计恒为 0」）。
     *
     * <p>本方法抽出来是为了能被单测直接断言「每个值都是 String」——这类缺陷一旦复发，
     * 表现是静默的，只有断言能拦住。
     */
    static Map<String, Object> buildNewTaskHash(Long taskId, String userId, String bookName) {
        Map<String, Object> taskInfo = new HashMap<>();
        taskInfo.put("taskId", String.valueOf(taskId));
        taskInfo.put("userId", userId);
        taskInfo.put("bookName", bookName);
        taskInfo.put("status", TaskStatus.QUEUED.value());
        taskInfo.put("createdAt", String.valueOf(System.currentTimeMillis()));
        return taskInfo;
    }

    /**
     * 构造状态更新的 Hash 字段表。值的类型要求与 {@link #buildNewTaskHash} 完全相同。
     *
     * @param completedSegments / {@code totalSegments} 为 null 时不写入该字段（「本次不改」语义）
     */
    static Map<String, Object> buildStatusUpdateHash(TaskStatus status, Integer completedSegments,
                                                    Integer totalSegments) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("status", status.value());
        if (completedSegments != null) {
            fields.put("completedSegments", String.valueOf(completedSegments));
        }
        if (totalSegments != null) {
            fields.put("totalSegments", String.valueOf(totalSegments));
        }
        fields.put("updatedAt", String.valueOf(System.currentTimeMillis()));
        return fields;
    }

    /**
     * 记录新任务
     */
    public void recordNewTask(Long taskId, String userId, String bookName) {
        try {
            String taskKey = TASK_PREFIX + taskId;
            Map<String, Object> taskInfo = buildNewTaskHash(taskId, userId, bookName);

            // 任务键用 Redis Hash 存储：Hash + putAll 让每个字段独立原子写入。
            // 整条 JSON 读-改-写时，并发更新同一任务（多片段合成线程各自推进度）
            // 会互相覆盖丢字段。
            stringRedisTemplate.opsForHash().putAll(taskKey, taskInfo);
            stringRedisTemplate.expire(taskKey, Duration.ofHours(TASK_EXPIRE_HOURS));

            // 记录用户任务列表
            String userTasksKey = USER_TASKS_PREFIX + userId;
            stringRedisTemplate.opsForSet().add(userTasksKey, String.valueOf(taskId));
            stringRedisTemplate.expire(userTasksKey, Duration.ofHours(TASK_EXPIRE_HOURS));

            // 更新系统统计
            updateSystemStats("total_tasks", 1);
            updateSystemStats(TaskStatus.QUEUED.value(), 1);
            // today_total 只有本处一个写入点：新任务登记是唯一的"今日新增"
            // 口径，在这里递增，与 queued 计数同处一个 try 降级块，Redis 故障不影响主流程
            updateSystemStats("today_total", 1);

            log.info("✅ Redis记录任务成功: taskId={}, userId={}, 状态={}", taskId, userId, TaskStatus.QUEUED);

        } catch (Exception e) {
            // Redis 只是统计侧，绝不允许它影响任务主流程（任务已入库，必须继续派发）
            log.error("Redis记录任务失败(已降级忽略，不影响任务流程): taskId={}, 原因: {}", taskId, e.getMessage());
        }
    }

    /**
     * 更新任务状态。
     * 参数为 TaskStatus 枚举，调用方不可能传入非法状态值。
     */
    public void updateTaskStatus(Long taskId, TaskStatus status, Integer completedSegments, Integer totalSegments) {
        try {
            String taskKey = TASK_PREFIX + taskId;
            DataType type = stringRedisTemplate.type(taskKey);

            // 旧版 JSON 字符串残留（24h TTL 过渡期）：删掉重建为 Hash，避免 WRONGTYPE
            if (type == DataType.STRING) {
                stringRedisTemplate.delete(taskKey);
                type = null;
            }
            if (type != DataType.HASH) {
                // 键不存在（已过期/已清理）：统计侧无需补偿，直接返回
                return;
            }

            // 先原子读取旧状态，再按字段原子写入。
            // 「整条 JSON 读出 → 改 → 整体写回」非原子：并发写同一任务时
            // 后写者用过期快照整体覆盖先写者的字段更新；且任务被删除时
            // 对应统计桶永远等不到 -1（归还逻辑在 cleanupCompletedTask 补齐）
            Object oldStatusObj = stringRedisTemplate.opsForHash().get(taskKey, "status");
            String oldStatus = oldStatusObj != null ? oldStatusObj.toString() : null;

            Map<String, Object> fields = buildStatusUpdateHash(status, completedSegments, totalSegments);

            stringRedisTemplate.opsForHash().putAll(taskKey, fields);
            stringRedisTemplate.expire(taskKey, Duration.ofHours(TASK_EXPIRE_HOURS));

            if (!status.value().equals(oldStatus)) {
                // 旧状态若是未完成三态之一，从对应桶里减 1（终态/未知值不减）
                TaskStatus oldTs = TaskStatus.of(oldStatus);
                if (oldTs != null && oldTs.isUnfinished()) {
                    updateSystemStats(oldTs.value(), -1);
                }
                // 新状态入桶：QUEUED 在 recordNewTask 时已计数；FAILED 不计入完成统计
                switch (status) {
                    case PENDING -> updateSystemStats(TaskStatus.PENDING.value(), 1);
                    case PROCESSING -> updateSystemStats(TaskStatus.PROCESSING.value(), 1);
                    case COMPLETED, PARTIAL -> updateSystemStats(TaskStatus.COMPLETED.value(), 1);
                    default -> { }
                }
            }

            log.debug("📊 更新任务状态: taskId={}, status={}", taskId, status);
        } catch (Exception e) {
            // 降级：Redis 故障时只记日志，绝不让统计更新中断合成流程
            log.error("Redis更新任务状态失败(已降级忽略): taskId={}, status={}, 原因: {}",
                    taskId, status, e.getMessage());
        }
    }

    /**
     * 读取任务当前状态：优先 Hash 格式，兼容旧版 JSON 字符串（24h TTL 过渡期），键不存在返回 null。
     */
    private String readTaskStatus(String taskKey) {
        DataType type = stringRedisTemplate.type(taskKey);
        if (type == DataType.HASH) {
            Object v = stringRedisTemplate.opsForHash().get(taskKey, "status");
            return v != null ? v.toString() : null;
        }
        if (type == DataType.STRING) {
            String raw = stringRedisTemplate.opsForValue().get(taskKey);
            if (raw != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> taskInfo = objectMapper.readValue(raw, Map.class);
                    Object v = taskInfo.get("status");
                    return v != null ? v.toString() : null;
                } catch (JsonProcessingException e) {
                    log.warn("解析任务信息失败: {}", e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 获取系统当前活跃任务数
     */
    public Map<String, Long> getSystemStats() {
        Map<String, Long> stats = new HashMap<>();

        try {
            String pendingStr = stringRedisTemplate.opsForValue().get(SYSTEM_STATS_PREFIX + ":" + TaskStatus.PENDING.value());
            String processingStr = stringRedisTemplate.opsForValue().get(SYSTEM_STATS_PREFIX + ":" + TaskStatus.PROCESSING.value());
            String queuedStr = stringRedisTemplate.opsForValue().get(SYSTEM_STATS_PREFIX + ":" + TaskStatus.QUEUED.value());
            String todayTotalStr = stringRedisTemplate.opsForValue().get(SYSTEM_STATS_PREFIX + ":today_total");

            stats.put(TaskStatus.PENDING.value(), pendingStr != null ? Long.parseLong(pendingStr) : 0L);
            stats.put(TaskStatus.PROCESSING.value(), processingStr != null ? Long.parseLong(processingStr) : 0L);
            // queued 供客户端展示「排队中」的队列规模（任务状态接口一并下发）
            stats.put(TaskStatus.QUEUED.value(), queuedStr != null ? Long.parseLong(queuedStr) : 0L);
            stats.put("today_total", todayTotalStr != null ? Long.parseLong(todayTotalStr) : 0L);
        } catch (Exception e) {
            // 降级：Redis 不可用时返回全 0，统计展示为空但不影响业务
            log.error("Redis读取系统统计失败(已降级返回0): {}", e.getMessage());
            stats.put(TaskStatus.PENDING.value(), 0L);
            stats.put(TaskStatus.PROCESSING.value(), 0L);
            stats.put(TaskStatus.QUEUED.value(), 0L);
            stats.put("today_total", 0L);
        }

        return stats;
    }

    /**
     * 更新系统统计
     */
    private void updateSystemStats(String key, int delta) {
        try {
            String statsKey = SYSTEM_STATS_PREFIX + ":" + key;
            stringRedisTemplate.opsForValue().increment(statsKey, delta);

            if ("today_total".equals(key)) {
                // 仅在键尚无 TTL（getExpire == -1）时设置 1 天过期：
                // 首次递增确定窗口起点，之后到期的键由下一次递增自然重建新窗口。
                // 每次递增都调 expire 会不断重置 TTL，任务持续进入时键永不过期，
                // "今日"统计会退化成跨天的滚动累计。
                // （键不存在时 increment 会先创建键，所以这里只需处理"存在但无 TTL"）
                Long ttl = stringRedisTemplate.getExpire(statsKey);
                if (ttl != null && ttl == -1) {
                    stringRedisTemplate.expire(statsKey, Duration.ofDays(1));
                }
            }
        } catch (Exception e) {
            log.warn("Redis更新系统统计失败(已降级忽略): key={}, 原因: {}", key, e.getMessage());
        }
    }

    /**
     * 清理已完成的任务记录。
     * 任务即将被删除/结束，这里直接删除任务键并移出用户集合。
     * 无条件把 Redis 里的任务状态改写为 "completed" 会让 failed 任务也被改写，
     * 统计与真实状态不一致。
     */
    public void cleanupCompletedTask(Long taskId, String userId) {
        String taskKey = TASK_PREFIX + taskId;
        String userTasksKey = USER_TASKS_PREFIX + userId;

        try {
            // 删键前先归还统计桶：直接删键时，任务若停在
            // queued/pending/processing 就永远没人给对应桶 -1 ——
            // 用户删除排队中任务后，"队列约 N 个"的展示永久虚高
            String status = readTaskStatus(taskKey);
            TaskStatus ts = TaskStatus.of(status);
            if (ts != null && ts.isUnfinished()) {
                updateSystemStats(ts.value(), -1);
            }

            stringRedisTemplate.delete(taskKey);
            stringRedisTemplate.opsForSet().remove(userTasksKey, String.valueOf(taskId));

            log.info("🗑️ 清理任务记录: taskId={}", taskId);

        } catch (Exception e) {
            log.error("清理任务记录失败(已降级忽略): taskId={}, 原因: {}", taskId, e.getMessage());
        }
    }

    /**
     * 删除用户时清理其全部 Redis 登记：删除该用户下所有任务键及其用户任务集合键。
     * 降级：Redis 不可用时只记日志，绝不让用户删除主流程中断。
     */
    public void cleanupUserTasks(String userId) {
        try {
            String userTasksKey = USER_TASKS_PREFIX + userId;
            Set<String> taskIds = stringRedisTemplate.opsForSet().members(userTasksKey);
            if (taskIds != null) {
                for (String taskId : taskIds) {
                    stringRedisTemplate.delete(TASK_PREFIX + taskId);
                }
            }
            stringRedisTemplate.delete(userTasksKey);
            log.info("🗑️ 清理用户Redis登记: userId={}, 任务数={}", userId,
                    taskIds == null ? 0 : taskIds.size());
        } catch (Exception e) {
            log.error("清理用户Redis登记失败(已降级忽略): userId={}, 原因: {}", userId, e.getMessage());
        }
    }
}
