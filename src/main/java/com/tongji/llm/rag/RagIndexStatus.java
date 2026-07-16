package com.tongji.llm.rag;

/**
 * 单篇知文的 RAG 索引生命周期状态，同时作为数据库和接口中的稳定枚举值。
 */
public enum RagIndexStatus {
    NOT_INDEXED,
    PENDING,
    INDEXING,
    READY,
    FAILED,
    SKIPPED
}
