package com.audiobook.server.util;

/**
 * 分页参数统一规范。
 *
 * <p>分页 size 来自前端，不设上限会被一次拉全表（含每行 DTO 转换）打爆堆内存。
 * 本类收敛了原先散落在 AdminController / AdminCreditController / CreditController /
 * PaymentController 的 {@code MAX_PAGE_SIZE} 常量，以及 CreditService /
 * CreditOrderService 各自私有的 {@code clampSize}。
 */
public final class PageUtil {

    /** 分页 size 上限 */
    public static final int MAX_PAGE_SIZE = 100;

    /** size 未传或非正时的默认值 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private PageUtil() {
    }

    /**
     * 把 size 钳制到 {@code [1, MAX_PAGE_SIZE]}；{@code size <= 0} 时回落到
     * {@link #DEFAULT_PAGE_SIZE}。
     */
    public static int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * {@code null} 保持 {@code null}（调用方据此保留「不传 page/size 返回全量」的契约），
     * 否则钳制到 {@code [1, MAX_PAGE_SIZE]}。
     */
    public static Integer clampSizeOrNull(Integer size) {
        return size == null ? null : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
