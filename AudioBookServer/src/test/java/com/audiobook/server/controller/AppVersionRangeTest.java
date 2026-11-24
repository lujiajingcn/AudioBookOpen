package com.audiobook.server.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AppVersionController#parseRange} 的边界契约。
 *
 * <p>用反射调private static 方法：这个方法本身没有 Spring 依赖，测它不需要起容器
 * （起容器要连 DB/Redis，而本机离线仓库连 surefire 都缺）。
 *
 * <p>⚠️ 这组用例的由来：上一版实现有**三个**真实缺陷，而它们在「响应头看起来正常」时
 * 完全不会暴露 ——
 * <ol>
 *   <li>空文件返回 {@code {0, -1}}（非 null）⇒ 调用方拼出 {@code Content-Range: bytes 0--1/0}，
 *       {@code end < start} 是语法非法的头；</li>
 *   <li>多区间（逗号分隔）只取第一段却回 206 ⇒ 并行下载的每一路都拿到同一段重复数据，
 *       拼出的APK 损坏但 **MD5 校验会通过**（各路字节确实一致），装机才报「解析失败」；</li>
 *   <li>Range 数字溢出 long 时静默 416，线上无法区分客户端 bug 与扫描器。</li>
 * </ol>
 */
class AppVersionRangeTest {

    private static final long LEN = 29_690_764L; // 1.24 的真实包大小

    /** null 表示「应回 416」；否则是 {start, end} 闭区间。 */
    private static long[] parse(String rangeHeader, long length) throws Exception {
        Method m = AppVersionController.class
                .getDeclaredMethod("parseRange", String.class, long.class);
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
    @DisplayName("end 超出总长要夹回而不是报错：curl -C - 传的就是「我已要这么多」")
    void endBeyondLengthIsClamped() throws Exception {
        assertArrayEquals(new long[]{1000, LEN - 1}, parse("bytes=1000-99999999", LEN));
    }

    @Test
    @DisplayName("无 Range 头 → 整份 {0, len-1}")
    void noRangeHeaderMeansWholeFile() throws Exception {
        assertArrayEquals(new long[]{0, LEN - 1}, parse(null, LEN));
        assertArrayEquals(new long[]{0, LEN - 1}, parse("   ", LEN));
    }

    @Test
    @DisplayName("start == len-1（最后一个字节）合法")
    void lastByte() throws Exception {
        assertArrayEquals(new long[]{LEN - 1, LEN - 1}, parse("bytes=" + (LEN - 1) + "-", LEN));
    }

    // ---------- 必须 416 的情形 ----------

    @Test
    @DisplayName("⚠️ 多区间必须 416：取第一段会让并行下载的每一路都拿到同一段数据")
    void multiRangeMustBeRejected() throws Exception {
        // 这正是实测用的三路并行请求
        String spec = "bytes=0-6294347,6294348-12588695,12588696-18989043";
        assertNull(parse(spec, LEN), "多区间必须返回 null（走 416），不能只取第一段");
    }

    @Test
    @DisplayName("⚠️ 空文件带 Range 必须 416：否则会拼出非法的 Content-Range: bytes 0--1/0")
    void emptyFileWithRangeMustBeRejected() throws Exception {
        // 磁盘写满 / rsync 中断留下的 0 字节包会触发
        assertNull(parse("bytes=0-", 0L), "空文件不应声称有区间可取");
        assertNull(parse("bytes=0-99", 0L));
    }

    @Test
    @DisplayName("start 越界（>= 总长）→ 416")
    void startOutOfBounds() throws Exception {
        assertNull(parse("bytes=" + LEN + "-", LEN));
        assertNull(parse("bytes=99999999-", LEN));
    }

    @Test
    @DisplayName("end < start → 416")
    void endBeforeStart() throws Exception {
        assertNull(parse("bytes=100-50", LEN));
    }

    @Test
    @DisplayName("非 bytes 单位 → 416")
    void unsupportedUnit() throws Exception {
        assertNull(parse("items=0-99", LEN));
        assertNull(parse("0-99", LEN));
    }

    @Test
    @DisplayName("无 dash → 416")
    void noDash() throws Exception {
        assertNull(parse("bytes=100", LEN));
    }

    @Test
    @DisplayName("suffix <= 0 → 416（bytes=-0 / bytes=-）")
    void nonPositiveSuffix() throws Exception {
        assertNull(parse("bytes=-0", LEN));
        assertNull(parse("bytes=-", LEN));
    }

    @Test
    @DisplayName("⚠️ 数字溢出 long → 416，且不抛异常")
    void numericOverflowRejected() throws Exception {
        // 旧实现会抛 NumberFormatException 上来（方法只 catch 不住的地方）
        assertNull(parse("bytes=0-99999999999999999999", LEN));
        assertNull(parse("bytes=99999999999999999999-", LEN));
    }

    // ---------- 契约不变量 ----------

    @Test
    @DisplayName("不变量：任何非 null 结果都满足 0 <= start <= end < length")
    void neverProducesIllegalRange() throws Exception {
        // 契约前提本身也要断言：非法区间会拼出 end<start 的头，
        // OkHttp 解析首字节位置时直接抛 IllegalArgumentException。
        String[] specs = {
            "bytes=0-99", "bytes=1000-", "bytes=-100", "bytes=" + (LEN - 1) + "-",
            "bytes=1000-99999999", "bytes=-99999999999", "bytes=0-0",
            "bytes=0-99,200-299", "bytes=", "bytes=-", "bytes=abc-def", "bytes=--",
        };
        for (String spec : specs) {
            long[] r = parse(spec, LEN);
            if (r == null) continue; // null = 走 416，合法
            assertTrue(isLegalRange(r), "spec=" + spec + " 产出非法区间: ["
                    + r[0] + ", " + r[1] + "]");
        }
    }

    /** 区间合法性：长度 2、元素非负、start <= end、end < 总长。 */
    private static boolean isLegalRange(long[] r) {
        if (r.length != 2) return false;
        if (r[0] < 0 || r[1] < 0) return false;
        if (r[0] > r[1]) return false;
        return r[1] < LEN;
    }
}
