package com.tongji.knowpost.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 结构化 SSE 的统一数据体；不同事件只序列化自身需要的非空字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RagSseEventPayload(
        String requestId,
        String text,
        String code,
        String message,
        Boolean retryable,
        String finishReason
) {
    public static RagSseEventPayload meta(String requestId) {
        return new RagSseEventPayload(requestId, null, null, null, null, null);
    }

    public static RagSseEventPayload delta(String text) {
        return new RagSseEventPayload(null, text, null, null, null, null);
    }

    public static RagSseEventPayload done(String finishReason) {
        return new RagSseEventPayload(null, null, null, null, null, finishReason);
    }

    public static RagSseEventPayload error(String code, String message, boolean retryable) {
        return new RagSseEventPayload(null, null, code, message, retryable, null);
    }
}
