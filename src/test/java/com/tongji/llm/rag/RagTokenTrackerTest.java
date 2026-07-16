package com.tongji.llm.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagTokenTrackerTest {

    private final RagTokenTracker tokenTracker = new RagTokenTracker();

    @Test
    void estimatesPromptTokensForBudgetObservation() {
        int estimated = tokenTracker.estimatePromptTokens(
                "你是中文知识助手。",
                "请根据上下文回答 RAG 的含义。");

        // 估算值只用于预算和趋势判断，因此这里只验证它能稳定产生正数。
        assertThat(estimated).isPositive();
    }

    @Test
    void readsExactUsageReturnedByProvider() {
        ChatResponse response = new ChatResponse(List.of(), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(120, 30, 150))
                .build());

        assertThat(tokenTracker.readProviderUsage(response))
                .contains(new RagTokenUsage(120, 30, 150));
    }

    @Test
    void treatsEmptyStreamingUsageAsUnavailable() {
        // Spring AI 在流式响应没有 usage 时会给出全零 EmptyUsage，不能把它记录成真实消耗。
        assertThat(tokenTracker.readProviderUsage(new ChatResponse(List.of()))).isEmpty();
    }
}
