package com.hmdp.exception;

/**
 * 过载拒绝(M5 隔离/削峰)。租户信号量名额耗尽时抛出,由 {@code WebExceptionAdvice} 映射为 HTTP 503。
 * <p>
 * 注:chat 链路实际多以「降级兜底」消化过载(返回 FAQ 话术而非报错),此异常用于需要硬拒绝的场景。
 */
public class OverloadException extends RuntimeException {
    public OverloadException(String message) {
        super(message);
    }
}
