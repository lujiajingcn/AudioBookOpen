package com.audiobook.server.model;

/**
 * 音频片段（AudioSegment）状态枚举。
 *
 * 状态机：pending → processing → completed / failed（失败段可被重试重置回 pending）。
 * value() 返回小写字符串，与数据库存量值一致。
 */
public enum SegmentStatus {
    PENDING("pending"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String value;

    SegmentStatus(String value) {
        this.value = value;
    }

    /** 数据库 / 对外 JSON 使用的字符串值 */
    public String value() {
        return value;
    }

    /** 字符串反解为枚举；未知/空值返回 null */
    public static SegmentStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (SegmentStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}