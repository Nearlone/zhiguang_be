package com.tongji.knowpost.api.dto;

import java.time.Instant;

/**
 * 前端查询或手动重建后展示的 RAG 索引状态。
 */
public record RagIndexStatusResponse(
        String postId,
        String status,
        int chunkCount,
        Instant indexedAt,
        String message
) {
}
