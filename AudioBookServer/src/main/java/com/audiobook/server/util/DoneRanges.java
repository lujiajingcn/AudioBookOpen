package com.audiobook.server.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 有声覆盖图 doneRanges 编解码工具（服务端）。
 *
 * <p>格式：1-based 闭区间压缩串，逗号分隔，升序且相邻/重叠合并；单点写 {@code "41"}，
 * 区间写 {@code "41-50"}。例：{@code "1-40,41,45-50"}。
 *
 * <p><b>算法必须与客户端（PC {@code app/util/coverage.py}、Android）逐字符一致</b>：
 * 给定相同完成段集合，各端 encode 出的规范串必须完全相同，否则跨端并集/去重会漂。
 * 规范做法：所有区间归一为 (start,end) 整数对 → 按 start 升序 → 相邻/重叠合并 →
 * 输出 {@code start==end ? "n" : "s-e"} 逗号连接。
 */
public final class DoneRanges {

    private DoneRanges() {
    }

    /** 解析为区间列表 [start,end]（1-based 闭区间）。非法 token 视为格式错误 → 抛异常。 */
    public static List<long[]> parse(String s) {
        List<long[]> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return out;
        }
        for (String token : t.split(",")) {
            String tok = token.trim();
            if (tok.isEmpty()) {
                continue;
            }
            int dash = tok.indexOf('-');
            if (dash < 0) {
                long n = parseLong(tok);
                out.add(new long[]{n, n});
            } else {
                long a = parseLong(tok.substring(0, dash));
                long b = parseLong(tok.substring(dash + 1));
                if (a > b) {
                    throw new IllegalArgumentException("doneRanges 区间起点大于终点: " + tok);
                }
                out.add(new long[]{a, b});
            }
        }
        return out;
    }

    private static long parseLong(String tok) {
        // 只允许纯数字（≥1）
        if (tok.isEmpty()) {
            throw new IllegalArgumentException("doneRanges 含空数字");
        }
        for (int i = 0; i < tok.length(); i++) {
            if (!Character.isDigit(tok.charAt(i))) {
                throw new IllegalArgumentException("doneRanges 含非数字: " + tok);
            }
            if (i == 0 && tok.charAt(i) == '0') {
                throw new IllegalArgumentException("doneRanges 数字不得以 0 开头: " + tok);
            }
        }
        long n = Long.parseLong(tok);
        if (n < 1) {
            throw new IllegalArgumentException("doneRanges 段号必须 ≥1: " + tok);
        }
        return n;
    }

    /** 并集：合并多组区间（自动排序 + 相邻/重叠合并）。 */
    @SafeVarargs
    public static List<long[]> union(List<long[]>... groups) {
        List<long[]> all = new ArrayList<>();
        for (List<long[]> g : groups) {
            if (g != null) {
                all.addAll(g);
            }
        }
        return normalize(all);
    }

    /** 排序 + 相邻/重叠合并，得到规范区间列表。 */
    public static List<long[]> normalize(List<long[]> raw) {
        List<long[]> list = new ArrayList<>(raw);
        list.sort((x, y) -> {
            int c = Long.compare(x[0], y[0]);
            return c != 0 ? c : Long.compare(x[1], y[1]);
        });
        List<long[]> merged = new ArrayList<>();
        for (long[] r : list) {
            if (merged.isEmpty()) {
                merged.add(new long[]{r[0], r[1]});
            } else {
                long[] last = merged.get(merged.size() - 1);
                // 相邻(下一段紧接)或重叠都合并
                if (r[0] <= last[1] + 1) {
                    last[1] = Math.max(last[1], r[1]);
                } else {
                    merged.add(new long[]{r[0], r[1]});
                }
            }
        }
        return merged;
    }

    /** 规范串 encode。 */
    public static String encode(List<long[]> ranges) {
        StringBuilder sb = new StringBuilder();
        for (long[] r : ranges) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            if (r[0] == r[1]) {
                sb.append(r[0]);
            } else {
                sb.append(r[0]).append('-').append(r[1]);
            }
        }
        return sb.toString();
    }

    /** 两串并集 → 规范串。 */
    public static String merge(String a, String b) {
        return encode(union(parse(a), parse(b)));
    }

    /** 覆盖段数。 */
    public static int count(String s) {
        int n = 0;
        for (long[] r : parse(s)) {
            n += (int) (r[1] - r[0] + 1);
        }
        return n;
    }

    /** 校验（用于 DTO @AssertTrue）：能 parse 且不超长。 */
    public static boolean isValid(String s) {
        if (s == null) {
            return true;
        }
        if (s.length() > 8192) {
            return false;
        }
        try {
            parse(s);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
