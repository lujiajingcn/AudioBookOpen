package com.audiobook.server.exception;

/**
 * 交互式 TTS 合成超时（/clone、/synthesize）。
 *
 * 单独定义异常类型而不是复用 RuntimeException：超时在 30s 上限下是「常见路径」而非
 * 意外故障，需要：
 * 1. 独立的 HTTP 状态码 504 Gateway Timeout，让客户端能区分「服务器忙/网络慢」与「服务器崩了」；
 * 2. 具体的用户提示（缩短文本/稍后重试），而不是 GlobalExceptionHandler 兜底的
 *    「服务器开小差了」泛化文案——否则用户会反复重试，既浪费 30s 又消耗限流配额。
 */
public class TtsTimeoutException extends RuntimeException {

    public TtsTimeoutException(String message) {
        super(message);
    }

    public TtsTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
