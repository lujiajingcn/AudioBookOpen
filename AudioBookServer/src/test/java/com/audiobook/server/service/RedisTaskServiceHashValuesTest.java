package com.audiobook.server.service;

import com.audiobook.server.model.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redis 任务 Hash 字段值的类型守卫。
 *
 * <p>生产实测（2026-09-22 一次真实合成任务的日志）：
 * <pre>
 * ERROR RedisTaskService: Redis记录任务失败(已降级忽略，不影响任务流程): taskId=2,
 *       原因: class java.lang.Long cannot be cast to class java.lang.String
 * </pre>
 * 根因是把 {@code Long} / {@code Integer} 直接放进了 {@link StringRedisTemplate} 的 Hash：
 * 值序列化器是 {@code StringRedisSerializer}，只接受 String。异常被降级 catch 吞掉，
 * 于是<b>任务键没建、用户任务集合没加、系统统计没加</b>，而任务主流程照常跑完 ——
 * 线上表现是「功能正常，但 Redis 侧统计恒为 0」，非常难发现。
 *
 * <p>而且两处缺陷会互相掩盖：{@code recordNewTask} 失败后任务键不存在，
 * {@code updateTaskStatus} 每次都在「键不存在就直接 return」处提前返回，
 * 所以第二处的 {@code completedSegments} / {@code totalSegments} / {@code updatedAt}
 * 一直没被触发过 —— 只修第一处，第二处会立刻开始刷 ERROR。本测试把两处一起盯住。
 */
class RedisTaskServiceHashValuesTest {

    @Test
    @DisplayName("新任务 Hash 的每个值都必须是 String")
    void newTaskHashValuesAreStrings() {
        Map<String, Object> hash = RedisTaskService.buildNewTaskHash(42L, "1", "钟鼓楼-节选几段");

        assertThat(hash).allSatisfy((key, value) ->
                assertThat(value).as("字段 %s 的值", key).isInstanceOf(String.class));

        assertThat(hash)
                .containsEntry("taskId", "42")
                .containsEntry("userId", "1")
                .containsEntry("status", TaskStatus.QUEUED.value());
        assertThat((String) hash.get("createdAt")).matches("\\d+");
    }

    @Test
    @DisplayName("状态更新 Hash 的每个值都必须是 String")
    void statusUpdateHashValuesAreStrings() {
        Map<String, Object> hash = RedisTaskService.buildStatusUpdateHash(TaskStatus.PROCESSING, 3, 8);

        assertThat(hash).allSatisfy((key, value) ->
                assertThat(value).as("字段 %s 的值", key).isInstanceOf(String.class));

        assertThat(hash)
                .containsEntry("status", TaskStatus.PROCESSING.value())
                .containsEntry("completedSegments", "3")
                .containsEntry("totalSegments", "8");
        assertThat((String) hash.get("updatedAt")).matches("\\d+");
    }

    @Test
    @DisplayName("片段数为 null 时字段不写入（「本次不改」语义）")
    void statusUpdateHashOmitsNullCounts() {
        Map<String, Object> hash = RedisTaskService.buildStatusUpdateHash(TaskStatus.QUEUED, null, null);

        assertThat(hash).doesNotContainKeys("completedSegments", "totalSegments");
        assertThat(hash).containsKey("status");
        assertThat(hash).containsKey("updatedAt");
    }

    @Test
    @DisplayName("机制：StringRedisSerializer 对非 String 值会抛 ClassCastException（所以值必须转成 String）")
    void nonStringValueWouldBreakSerializer() {
        StringRedisSerializer serializer = new StringRedisSerializer();

        // 与 RedisTemplate 内部行为等价：参数声明为 String，编译器插入 checkcast
        assertThatThrownBy(() -> serializer.serialize((String) (Object) Long.valueOf(1)))
                .as("非 String 值在真实写入时会抛 CCE，绝不能直接放进 StringRedisTemplate 的 Hash")
                .isInstanceOf(ClassCastException.class);
    }
}
