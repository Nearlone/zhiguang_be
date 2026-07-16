package com.tongji.llm.rag;

/**
 * 一次 RAG 索引任务的结构化结果，避免用 0 同时表示跳过、失败和空正文。
 */
public record RagIndexResult(
        RagIndexStatus status,
        int chunkCount,
        String message
) {
    public static RagIndexResult ready(int chunkCount) {
        return new RagIndexResult(RagIndexStatus.READY, chunkCount, "AI问答已就绪");
    }

    public static RagIndexResult failed(String message) {
        return new RagIndexResult(RagIndexStatus.FAILED, 0, message);
    }

    public static RagIndexResult skipped(String message) {
        return new RagIndexResult(RagIndexStatus.SKIPPED, 0, message);
    }
}
