package com.tongji.llm.rag;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 统一处理 RAG Token 的调用前估算和调用后统计。
 *
 * <p>JTokkit 使用 CL100K_BASE 编码，只能用于预算和趋势观察；DeepSeek 的准确计费
 * 必须以 {@link ChatResponse} 中模型服务商返回的 usage 为准。</p>
 */
@Component
public class RagTokenTracker {

    private final TokenCountEstimator estimator = new JTokkitTokenCountEstimator();

    /**
     * 估算 system + user 文本的 Token 数，不包含模型协议额外增加的消息开销。
     */
    public int estimatePromptTokens(String systemPrompt, String userPrompt) {
        return estimateText(systemPrompt) + estimateText(userPrompt);
    }

    public int estimateText(String text) {
        return estimator.estimate(text);
    }

    /**
     * 从流式响应 metadata 中提取服务商 usage；全零表示当前响应没有提供用量信息。
     */
    public Optional<RagTokenUsage> readProviderUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return Optional.empty();
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new RagTokenUsage(
                valueOrZero(usage.getPromptTokens()),
                valueOrZero(usage.getCompletionTokens()),
                usage.getTotalTokens()));
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
