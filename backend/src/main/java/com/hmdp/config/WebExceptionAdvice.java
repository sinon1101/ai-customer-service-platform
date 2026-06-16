package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.exception.OverloadException;
import com.hmdp.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /** 限流拒绝(M5)→ HTTP 429 */
    @ExceptionHandler(RateLimitException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Result handleRateLimit(RateLimitException e) {
        log.warn("限流拒绝:{}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    /** 过载拒绝(M5 隔离/削峰)→ HTTP 503 */
    @ExceptionHandler(OverloadException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleOverload(OverloadException e) {
        log.warn("过载拒绝:{}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
