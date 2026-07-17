package com.tongji.llm.rag;

import lombok.Getter;

/**
 * 结构化 SSE 使用的内部异常，只保留稳定错误码、用户文案和是否可重试。
 */
@Getter
public class RagStreamException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public RagStreamException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }
}
