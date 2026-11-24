package com.audiobook.server.exception;

/**
 * 积分不足，无法创建合成任务。
 *
 * 单独定义异常类型而不复用 IllegalArgumentException，原因有两点：
 * 1. 语义上这不是「参数不合法」而是「余额不够」，前端要据此弹出充值引导而非表单报错；
 * 2. 需要独立的 HTTP 状态码 402 Payment Required，让三端（Web/Android/后台）用同一个
 *    判定条件拦截并跳转充值页，不必解析错误文案（文案会随规则说明变化）。
 *
 * 继承 RuntimeException 保证在 createTtsTask 的 @Transactional 内抛出时能触发回滚，
 * 从而让已落盘的电子书/参考音频文件被 afterCompletion 清理钩子回收，不留孤儿文件。
 */
public class InsufficientCreditsException extends RuntimeException {

    /** 本次操作需要的积分 */
    private final int required;

    /** 用户当前余额 */
    private final int balance;

    public InsufficientCreditsException(int required, int balance) {
        super("积分不足：本次合成需要 " + required + " 积分，当前余额 " + balance + " 积分，请先充值");
        this.required = required;
        this.balance = balance;
    }

    public int getRequired() {
        return required;
    }

    public int getBalance() {
        return balance;
    }

    /** 还差多少积分，供前端直接展示「还需 X 积分」 */
    public int getShortage() {
        return Math.max(0, required - balance);
    }
}
