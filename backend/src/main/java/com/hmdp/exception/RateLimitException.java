package com.hmdp.exception;

/**
 * 限流拒绝(M5)。令牌桶无可用令牌时抛出,由 {@code WebExceptionAdvice} 映射为 HTTP 429。
 */
public class RateLimitException extends RuntimeException {
    public RateLimitException(String message) {
        super(message);
    }
}
