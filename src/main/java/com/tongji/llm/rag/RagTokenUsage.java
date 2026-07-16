package com.tongji.llm.rag;

/**
 * 模型服务商返回的准确 Token 用量。
 *
 * @param promptTokens 输入 Prompt 消耗的 Token
 * @param completionTokens 模型生成消耗的 Token
 * @param totalTokens 输入与输出 Token 总量
 */
public record RagTokenUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens
) {
}
