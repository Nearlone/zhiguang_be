package com.tongji.knowpost.api.dto;

/**
 * 发布结果将内容状态与 RAG 状态分开，向量服务故障不会被误报为发布失败。
 */
public record KnowPostPublishResponse(
        String postId,
        String publishStatus,
        String ragIndexStatus,
        String ragMessage
) {
}
