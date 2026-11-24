package com.audiobook.audiobookplayer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 积分明细时间格式化的金标用例。
 *
 * 重点不在「正常串能不能格式化」，而在**异常串不能被吞成空**：
 * 明细页上少一行时间，用户只会以为数据丢了。
 */
class LedgerTimeFormatTest {

    @Test
    fun formatsLocalDateTimeWithMicros() {
        assertEquals("2026-11-02 11:46", formatLedgerTime("2026-11-02T11:46:51.239836"))
    }

    @Test
    fun formatsLocalDateTimeWithoutFraction() {
        assertEquals("2026-10-24 21:03", formatLedgerTime("2026-10-24T21:03:00"))
    }

    @Test
    fun formatsSpaceSeparated() {
        assertEquals("2026-10-24 21:03", formatLedgerTime("2026-10-24 21:03:00"))
    }

    @Test
    fun keepsDateOnly() {
        assertEquals("2026-10-24", formatLedgerTime("2026-10-24"))
    }

    @Test
    fun keepsUnrecognized() {
        assertEquals("昨天 21:03", formatLedgerTime("昨天 21:03"))
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", formatLedgerTime(null))
        assertEquals("", formatLedgerTime("   "))
    }
}
