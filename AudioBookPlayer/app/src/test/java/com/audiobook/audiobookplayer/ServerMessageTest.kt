package com.audiobook.audiobookplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 服务端 `message` 的可用性判定测试。
 *
 * ## 为什么必须拦字符串 `"null"`
 *
 * 服务端三处 `put("message", e.getMessage())` 里，`getMessage()` 可空：
 * 异常没有文案时响应体是 `{"message": null}`。Android 的 `JSONObject.optString`
 * 会把它读成**字符串** `"null"`，`isNotBlank()` 判真 —— 兜底文案被跳过，
 * 用户看到的就是屏幕上孤零零一个 `null`（不崩、不闪，只是很荒谬）。
 *
 * 这条边界用 `extractServerMessage` 测不了（org.json 在 JVM 单测里是桩实现），
 * 所以判定被抽成纯函数 [usableServerMessage]。
 */
class ServerMessageTest {

    @Test
    fun `正常中文文案原样返回`() {
        assertEquals("余额不足，请先充值", usableServerMessage("余额不足，请先充值"))
    }

    @Test
    fun `字面量null视为没有文案`() {
        assertNull(usableServerMessage("null"))
        assertNull("大小写与空白都不该漏过去", usableServerMessage(" NULL "))
    }

    @Test
    fun `空白与null引用都视为没有文案`() {
        assertNull(usableServerMessage(""))
        assertNull(usableServerMessage("   "))
        assertNull(usableServerMessage(null))
    }

    @Test
    fun `含null但另有内容的文案要保留`() {
        // 只拦「整条就是 null」，别把正常文案里带 null 字样的误杀
        assertEquals("null pointer 相关错误", usableServerMessage("null pointer 相关错误"))
    }
}
