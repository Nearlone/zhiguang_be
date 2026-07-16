package com.tongji.llm.rag;

/**
 * RAG 模块内复用的用户可见文案。
 *
 * <p>空召回属于正常业务结果而不是 HTTP 异常，因此不放入全局 ErrorCode。</p>
 */
final class RagMessages {

    static final String NO_CONTEXT_ANSWER = "当前知文中没有足够信息回答这个问题。";

    private RagMessages() {
    }
}
