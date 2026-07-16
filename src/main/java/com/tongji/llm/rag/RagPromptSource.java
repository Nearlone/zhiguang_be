package com.tongji.llm.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 提供给 Prompt 的短来源别名及其真实切片信息。
 *
 * <p>模型只看到 {@code S1}、{@code S2} 等短别名，后端保留别名到真实
 * {@code chunkId} 的映射，避免模型复制长 ID 时截断或改写。</p>
 */
record RagPromptSource(
        String alias,
        String chunkId,
        int position,
        String title,
        String content
) {

    static List<RagPromptSource> fromContexts(List<RagContext> contexts) {
        List<RagPromptSource> sources = new ArrayList<>(contexts.size());
        for (int i = 0; i < contexts.size(); i++) {
            RagContext context = contexts.get(i);
            sources.add(new RagPromptSource(
                    "S" + (i + 1),
                    context.chunkId(),
                    context.position(),
                    context.title(),
                    context.content()));
        }
        return List.copyOf(sources);
    }
}
