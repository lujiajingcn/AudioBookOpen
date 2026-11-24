package com.audiobook.server.model;

import java.util.List;

/**
 * TTS 任务状态枚举（消除散布于 10+ 文件的魔法字符串）。
 *
 * 状态机：
 *   pending（已建任务未派发）→ queued（已入队）→ processing（合成中）
 *   → paused（管理员暂停，可继续）/ completed（全部成功）/ partial（部分成功）/ failed（全部失败）
 *
 * paused 由管理员在「书城管理」显式挂起：工作线程在下一个段落检查点收手（已完成段与
 * mp3 全部保留），由管理员点「继续合成」重新派发续跑。它**不是终态**
 * （{@link #isTerminal()} 为 false），也**不算未完成**（{@link #isUnfinished()} 为 false）——
 * 挂起的任务不占用线程池槽位，不该计入队列容量校验，更不该被启动恢复器自动捞起
 * （管理员说停就停，重启也不偷偷继续跑）。
 *
 * value() 返回小写字符串，与数据库/Redis 存量值完全一致，不做数据迁移。
 */
public enum TaskStatus {
    PENDING("pending"),
    QUEUED("queued"),
    PROCESSING("processing"),
    /** 管理员暂停：工作线程已在检查点收手，可由管理员继续合成 */
    PAUSED("paused"),
    COMPLETED("completed"),
    PARTIAL("partial"),
    FAILED("failed");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    /** 数据库 / Redis / 对外 JSON 使用的字符串值 */
    public String value() {
        return value;
    }

    /** 字符串反解为枚举；未知/空值返回 null（调用方需容错历史脏数据） */
    public static TaskStatus of(String value) {
        if (value == null) {
            return null;
        }
        for (TaskStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        return null;
    }

    /** 终态：completed / partial / failed（paused 是「挂起」而非终态，管理员可继续） */
    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED;
    }

    /**
     * 未完成：pending / queued / processing。
     *
     * <p>刻意**不含 paused**：本方法与 {@link #unfinishedValues()} 一起用于「队列容量前置校验」
     * 与「未完成任务计数」——挂起的任务已经没有工作线程在跑，不该继续占用配额。
     */
    public boolean isUnfinished() {
        return this == PENDING || this == QUEUED || this == PROCESSING;
    }

    /** 已挂起（管理员暂停，等待继续合成） */
    public boolean isPaused() {
        return this == PAUSED;
    }

    /**
     * 该状态的任务是否允许「重新发起合成」（即书城书解绑重来）。
     *
     * <p>语义：**没成功**才允许重来。
     *  - {@code failed} / {@code partial}：上一次没跑成（或只成了一部分），必须允许重试，
     *    否则一次失败就把这本书永久卡死（store_books.task_id 在建任务时就已回绑）；
     *  - {@code completed}：已有可用音频，重新合成会覆盖旧任务，不属于「修复」范畴；
     *  - {@code paused}：已合成了一部分且随时可续跑，走「继续合成」比重开更合适（不丢已有段）；
     *  - 未完成态（pending/queued/processing）：还在跑，不能再起第二个。
     *
     * <p>null / 未知值（历史脏数据）一律**不放行** —— 宁可让管理员来问，
     * 也不要在状态不明时再压一个任务上去。
     */
    public static boolean allowsResynthesis(String value) {
        TaskStatus s = of(value);
        return s == FAILED || s == PARTIAL;
    }

    /**
     * 该状态的任务能否「补齐缺失段」—— 即断点续跑，只重跑没成功的段。
     *
     * <p>与 {@link #allowsResynthesis} 的区别是**代价**，不是能不能修：
     *  - 本方法返回 true 时，已成功的段与 mp3 原样保留，只把 failed / pending 重置为待处理，
     *    再派发一次即可（书城管理端的「继续合成」走这条路）；
     *  - {@link #allowsResynthesis} 则是解绑重开一个新任务，整本书从头合成 ——
     *    一本 6000 段的书里只差 4 段时，那样做等于白烧几小时算力。
     *
     * <p>因此 {@code paused} 不在此列（它有自己的一条路径：清暂停标记后原样续跑，
     * 不需要重置任何段落）；{@code completed} 也没有什么可补的。
     */
    public static boolean allowsGapRetry(String value) {
        TaskStatus s = of(value);
        return s == PARTIAL || s == FAILED;
    }

    /** 字符串版终态判定（容忍 null / 未知值） */
    public static boolean isTerminal(String value) {
        TaskStatus s = of(value);
        return s != null && s.isTerminal();
    }

    /** 字符串版暂停判定（容忍 null / 未知值） */
    public static boolean isPaused(String value) {
        return of(value) == PAUSED;
    }

    /** 字符串版未完成判定（容忍 null / 未知值） */
    public static boolean isUnfinished(String value) {
        TaskStatus s = of(value);
        return s != null && s.isUnfinished();
    }

    /**
     * 未完成状态值列表，供 repository 的 status in (...) 查询使用。
     *
     * <p>不含 paused：挂起任务不占线程、不计队列容量，也不会被启动恢复器捞起。
     */
    public static List<String> unfinishedValues() {
        return List.of(PENDING.value, QUEUED.value, PROCESSING.value);
    }

    @Override
    public String toString() {
        return value;
    }
}