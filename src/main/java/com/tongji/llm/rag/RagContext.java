package com.tongji.llm.rag;

/**
 * 提供给大模型的单条检索上下文。
 *
 * <p>不再只传递纯文本，是为了在检索、Prompt 别名和最终来源引用之间
 * 保留稳定的切片标识。真实 chunkId 不直接交给模型复制。</p>
 *
 * @param chunkId 索引阶段生成的切片 ID，例如 {@code 123#2}
 * @param position 切片在当前知文中的顺序
 * @param title 知文标题，用于帮助模型理解来源
 * @param content 切片正文
 */
public record RagContext(
        String chunkId,
        int position,
        String title,
        String content
) {
}
