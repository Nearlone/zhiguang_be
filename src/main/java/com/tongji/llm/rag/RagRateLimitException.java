package com.tongji.llm.rag;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * RAG 入口保护异常，只携带稳定错误码和用户文案，不向客户端暴露 Redis 细节。
 */
@Getter
public class RagRateLimitException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final long retryAfterSeconds;

    private RagRateLimitException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static RagRateLimitException exceeded(String code, String message, long retryAfterSeconds) {
        return new RagRateLimitException(HttpStatus.TOO_MANY_REQUESTS, code, message, retryAfterSeconds);
    }

    public static RagRateLimitException backendUnavailable() {
        return new RagRateLimitException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RAG_RATE_LIMIT_UNAVAILABLE",
                "AI问答服务暂时不可用，请稍后重试。",
                5);
    }
}
