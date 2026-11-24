package com.audiobook.audiobookplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `queuedCount` 解析测试。
 *
 * ## 为什么值得为一行解析单独写用例
 *
 * 这个值决定书架上「排队中」行显示什么：
 * - 解析成 `null` → 保留上一次的值（界面不变）；
 * - 解析成 `0`   → 界面**认为队伍清空了**，那句「队列约 N 个任务」直接消失。
 *
 * 「字段缺失」被兜底成 0 是最容易写出来的版本（`as? Number ?: 0`），
 * 症状是用户升级 App 后队列文案凭空消失、不崩不闪，线上只能靠客诉发现。
 *
 * 另一个坑是 Gson：JSON 数字进 `Map<String, Any>` 后是 `Double`，
 * 用 `as? Int` 取会永远拿到 null —— 表现是「同步成功但队列数永远是 0」。
 */
class TaskSyncQueueCountTest {

    @Test
    fun `字段缺失返回null而不是0`() {
        assertNull("老服务端不带这个字段，不能当成「无人排队」", parseQueuedCount(mapOf("success" to true)))
    }

    @Test
    fun `null值与缺失同等处理`() {
        assertNull(parseQueuedCount(mapOf("queuedCount" to null)))
    }

    @Test
    fun `真正的0要解析成0`() {
        // 与「缺失」区分开：0 是「确实没人排队」，界面该把队列文案收起来。
        assertEquals(0, parseQueuedCount(mapOf("queuedCount" to 0)))
    }

    @Test
    fun `Gson解析出的Double也能取到整数`() {
        // Gson 把 12 反序列化进 Map 时会变成 12.0（Double），这条用例钉住 as? Number。
        assertEquals(12, parseQueuedCount(mapOf("queuedCount" to 12.0)))
    }

    @Test
    fun `负数按0处理`() {
        assertEquals(0, parseQueuedCount(mapOf("queuedCount" to -3)))
    }

    @Test
    fun `类型不符时返回null以保留旧值`() {
        assertNull(parseQueuedCount(mapOf("queuedCount" to "12")))
    }
}
