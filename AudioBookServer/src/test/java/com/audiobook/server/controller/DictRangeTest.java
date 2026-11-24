package com.audiobook.server.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DictController#parseRange} 的边界契约。
 *
 * <p>口径必须与 {@code AppVersionController#parseRange}（已由
 * {@link AppVersionRangeTest} 锁定）**完全一致** —— 两处若有偏差，
 * 客户端的同一个下载器会出现「APK 能续传、词典不能」这类极难定位的不一致。
 * 因此本测试刻意把APK 那份已验证的用例集**再跑一遍**在词典这份上。
 *
 * <p>用反射调 private static 方法：本方法没有 Spring 依赖，
 * 测它不需要起容器（起容器要连 DB/Redis）。
 */
class DictRangeTest {

    private static final long LEN = 24_253_536L; // dict.db.gz 的真实字节数

    private static long[] parse(String rangeHeader, long length) throws Exception {
        Method m = DictController.class.getDeclaredMethod("parseRange", String.class, long.class);
        m.setAccessible(true);
        return (long[]) m.invoke(null, rangeHeader, length);
    }

    // ---------- 正常区间 ----------

    @Test
    @DisplayName("bytes=0-99 → {0,99}")
    void headRange() throws Exception {
        assertArrayEquals(new long[]{0, 99}, parse("bytes=0-99", LEN));
    }

    @Test
    @DisplayName("bytes=1000- （开区间）→ 夹到文件末尾")
    void openEndedRange() throws Exception {
        assertArrayEquals(new long[]{1000, LEN - 1}, parse("bytes=1000-", LEN));
    }

    @Test
    @DisplayName("bytes=-100 （尾部 N 字节）")
    void suffixRange() throws Exception {
        assertArrayEquals(new long[]{LEN - 100, LEN - 1}, parse("bytes=-100", LEN));
    }

    @Test
    @DisplayName("end 超出总长要夹回而不是报错（curl -C - 传的就是「我已要这么多」）")
    void endBeyondLengthIsClamped() throws Exception {
        assertArrayEquals(new long[]{1000, LEN - 1}, parse("bytes=1000-99999999", LEN));
    }

    @Test
    @DisplayName("无 Range 头 → 整份 {0, len-1}")
    void noRangeHeaderMeansWholeFile() throws Exception {
        assertArrayEquals(new long[]{0, LEN - 1}, parse(null, LEN));
        assertArrayEquals(new long[]{0, LEN - 1}, parse("   ", LEN));
    }

    // ---------- 必须 416 的情形 ----------

    @Test
    @DisplayName("⚠️ 多区间必须 416：取第一段会让并行下载的每一路都拿到同一段重复数据")
    void multiRangeMustBeRejected() throws Exception {
        String spec = "bytes=0-8084459,8084460-16168918";
        assertNull(parse(spec, LEN), "多区间必须返回 null（走 416）");
    }

    @Test
    @DisplayName("⚠️ 空文件带 Range 必须 416：否则会拼出非法的 Content-Range: bytes 0--1/0")
    void emptyFileWithRangeMustBeRejected() throws Exception {
        assertNull(parse("bytes=0-", 0L));
        assertNull(parse("bytes=0-99", 0L));
        // ⚠️ 这一形态才是真正会产出 {0, -1} 的：suffix 分支算
        //   {Math.max(0, 0-100), 0-1} = {0, -1}，end 为负。
        // 曾只测上面两种（它们恰好都被 start >= length 拦下），
        // 于是「已修复」的说法与实际保护范围不符——测试通过但目标未达成。
        assertNull(parse("bytes=-100", 0L));
    }

    @Test
    @DisplayName("⚠️ length==0 时任何 Range 形态都返回 null（守卫在 isBlank 判断之前）")
    void emptyFileRejectsEveryRangeForm() throws Exception {
        String[] specs = {
            "bytes=0-", "bytes=0-0", "bytes=0-99", "bytes=-1", "bytes=-100",
            "bytes=1-", "bytes=100-200", "bytes=-0", "bytes=abc-def", "bytes=0-1,5-6",
        };
        for (String spec : specs) {
            assertNull(parse(spec, 0L), "空文件下spec=" + spec + " 必须返回 null（走 416）");
        }
        // 无 Range 头同样不能给出 {0, -1}
        assertNull(parse(null, 0L), "空文件 + 无 Range 头必须返回 null");
        assertNull(parse("   ", 0L), "空文件 + 空白 Range 头必须返回 null");
    }

    @Test
    @DisplayName("不变量：length==0 时绝不产出任何非 null 结果")
    void emptyFileNeverProducesRange() throws Exception {
        String[] specs = {
            null, "", "   ", "bytes=0-", "bytes=0-99", "bytes=-1", "bytes=-99999999999",
            "bytes=1-", "bytes=-0", "bytes=-", "bytes=", "bytes=--", "bytes=0-1,5-6",
        };
        for (String spec : specs) {
            assertNull(parse(spec, 0L), "spec=" + spec + " 在空文件下产出了非 null 区间");
        }
    }

    @Test
    @DisplayName("start 越界 → 416")
    void startOutOfBounds() throws Exception {
        assertNull(parse("bytes=" + LEN + "-", LEN));
    }

    @Test
    @DisplayName("end < start → 416")
    void endBeforeStart() throws Exception {
        assertNull(parse("bytes=100-50", LEN));
    }

    @Test
    @DisplayName("非 bytes 单位 / 无 dash → 416")
    void malformedSpec() throws Exception {
        assertNull(parse("items=0-99", LEN));
        assertNull(parse("0-99", LEN));
        assertNull(parse("bytes=100", LEN));
    }

    @Test
    @DisplayName("数字溢出 long → 416，且不抛异常")
    void numericOverflowRejected() throws Exception {
        assertNull(parse("bytes=0-99999999999999999999", LEN));
        assertNull(parse("bytes=99999999999999999999-", LEN));
    }

    @Test
    @DisplayName("suffix <= 0 → 416（bytes=-0 / bytes=-）")
    void nonPositiveSuffix() throws Exception {
        assertNull(parse("bytes=-0", LEN));
        assertNull(parse("bytes=-", LEN));
    }

    // ---------- 契约不变量 ----------

    @Test
    @DisplayName("不变量：任何非 null 结果都满足 0 <= start <= end < length")
    void neverProducesIllegalRange() throws Exception {
        String[] specs = {
            "bytes=0-99", "bytes=1000-", "bytes=-100", "bytes=" + (LEN - 1) + "-",
            "bytes=1000-99999999", "bytes=-99999999999", "bytes=0-0",
            "bytes=0-99,200-299", "bytes=", "bytes=-", "bytes=abc-def", "bytes=--",
        };
        for (String spec : specs) {
            long[] r = parse(spec, LEN);
            if (r == null) {
                continue; // null = 走 416，合法
            }
            assertTrue(isLegalRange(r), "spec=" + spec + " 产出非法区间: [" + r[0] + ", " + r[1] + "]");
        }
    }

    private static boolean isLegalRange(long[] r) {
        return isLegalRange(r, LEN);
    }

    /** 真正的判据：结果非 null 时必须满足 0 <= start <= end < length */
    private static boolean isLegalRange(long[] r, long length) {
        if (r.length != 2) return false;
        if (r[0] < 0 || r[1] < 0) return false;
        if (r[0] > r[1]) return false;
        return r[1] < length;
    }
}