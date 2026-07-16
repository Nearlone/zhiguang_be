package com.tongji.llm.rag;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAG 问答查询服务：
 * - 在问答前保障索引，检索相关上下文并构造提示词
 * - 通过 ChatClient 以流式（SSE）方式返回模型输出
 */
@Service
@RequiredArgsConstructor
public class RagQueryService {
    private static final Logger log = LoggerFactory.getLogger(RagQueryService.class);
    private static final String CHAT_MODEL = "deepseek-chat";

    // 向量检索接口（Elasticsearch 向量库封装）
    private final VectorStore vectorStore;
    // 大模型对话客户端（在 LlmConfig 中通过 @Qualifier 绑定 deepSeekChatModel）
    private final ChatClient chatClient;
    // 索引服务：确保帖子在问答前已建立/更新索引
    private final RagIndexService indexService;
    // Prompt 构建器：隔离不可信内容并要求模型输出来源引用
    private final RagPromptBuilder promptBuilder;
    // Token 跟踪器：调用前只做估算，调用后优先记录服务商返回的准确 usage
    private final RagTokenTracker tokenTracker;

    /**
     * 使用 WebFlux 返回回答内容的流。
     */
    public Flux<String> streamAnswerFlux(long postId, String question, int topK, int maxTokens) {
        String requestId = UUID.randomUUID().toString();
        long queryStartedNanos = System.nanoTime();
        // Controller 已完成非空校验；这里统一去掉首尾空格，避免空白影响 Embedding 和 Prompt。
        String normalizedQuestion = question.trim();
        int normalizedTopK = Math.max(1, topK);
        int fetchK = fetchKFor(normalizedTopK);

        // 轻量保障：如索引不存在或指纹未变更则跳过，否则重建
        long indexStartedNanos = System.nanoTime();
        RagIndexResult indexResult;
        try {
            indexResult = indexService.ensureIndexed(postId);
        } catch (RuntimeException e) {
            logSynchronousFailure(requestId, postId, "index", queryStartedNanos, e);
            throw e;
        }
        long indexMs = elapsedMillis(indexStartedNanos);
        if (indexResult.status() != RagIndexStatus.READY) {
            // 索引失败时禁止继续读取可能属于旧版本的向量，避免用过期正文回答用户。
            log.info("RAG query completed requestId={} status=index_unavailable postId={} indexStatus={} totalMs={}",
                    requestId, postId, indexResult.status(), elapsedMillis(queryStartedNanos));
            return Flux.just(indexResult.message());
        }

        // 检索上下文：先宽召回，再按 postId 做服务端过滤
        long retrievalStartedNanos = System.nanoTime();
        List<RagContext> contexts;
        try {
            contexts = searchContexts(String.valueOf(postId), normalizedQuestion, normalizedTopK);
        } catch (RuntimeException e) {
            logSynchronousFailure(requestId, postId, "retrieval", queryStartedNanos, e);
            throw e;
        }
        long retrievalMs = elapsedMillis(retrievalStartedNanos);
        List<String> chunkIds = contexts.stream().map(RagContext::chunkId).toList();
        log.info("RAG retrieval completed requestId={} postId={} topK={} fetchK={} hitCount={} chunkIds={} indexMs={} retrievalMs={}",
                requestId, postId, normalizedTopK, fetchK, contexts.size(), chunkIds, indexMs, retrievalMs);

        if (contexts.isEmpty()) {
            // 没有事实依据时不调用 DeepSeek，既降低幻觉，也节省一次模型生成成本。
            log.info("RAG query completed requestId={} status=no_context postId={} modelCalled=false totalMs={}",
                    requestId, postId, elapsedMillis(queryStartedNanos));
            return Flux.just(RagMessages.NO_CONTEXT_ANSWER);
        }

        // Prompt 组装独立封装，查询服务只负责串联“检索 -> 构造 Prompt -> 流式生成”。
        String system = promptBuilder.buildSystemPrompt();
        String user = promptBuilder.buildUserPrompt(normalizedQuestion, contexts);
        int estimatedPromptTokens = tokenTracker.estimatePromptTokens(system, user);
        QueryLogContext logContext = new QueryLogContext(
                requestId, postId, normalizedTopK, fetchK, chunkIds, indexMs, retrievalMs,
                maxTokens, estimatedPromptTokens, queryStartedNanos);
        log.info("RAG prompt prepared requestId={} postId={} model={} estimatedPromptTokens={} maxTokens={} sourceCount={}",
                requestId, postId, CHAT_MODEL, estimatedPromptTokens, maxTokens, contexts.size());

        // 每次订阅创建独立状态，避免多个订阅者共享首 Token、usage 和回答缓冲区。
        return Flux.defer(() -> {
            AtomicBoolean firstTokenSeen = new AtomicBoolean(false);
            AtomicBoolean terminalLogged = new AtomicBoolean(false);
            AtomicLong firstTokenMs = new AtomicLong(-1L);
            AtomicReference<RagTokenUsage> providerUsage = new AtomicReference<>();
            AtomicReference<String> finishReason = new AtomicReference<>("unknown");
            StringBuilder generatedText = new StringBuilder();

            return chatClient
                    .prompt()
                    .system(system)
                    .user(user)
                    .options(DeepSeekChatOptions.builder()
                            .model(CHAT_MODEL)
                            .temperature(0.2)
                            .maxTokens(maxTokens)
                            .build())
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> captureResponseMetadata(response, providerUsage, finishReason))
                    // handle 在跳过 metadata-only 响应的同时保持模型文本片段原始顺序。
                    .<String>handle((response, sink) -> {
                        String delta = responseText(response);
                        if (delta == null || delta.isEmpty()) {
                            // usage 可能位于不含文本的最后一个流式响应中，因此只跳过输出，不跳过 metadata 处理。
                            return;
                        }
                        generatedText.append(delta);
                        if (firstTokenSeen.compareAndSet(false, true)) {
                            long ttftMs = elapsedMillis(queryStartedNanos);
                            firstTokenMs.set(ttftMs);
                            log.info("RAG first token requestId={} postId={} firstTokenMs={}", requestId, postId, ttftMs);
                        }
                        sink.next(delta);
                    })
                    .doOnComplete(() -> {
                        if (terminalLogged.compareAndSet(false, true)) {
                            logTerminal(logContext, "success", firstTokenMs.get(), finishReason.get(),
                                    generatedText, providerUsage.get());
                        }
                    })
                    .doOnError(error -> {
                        if (terminalLogged.compareAndSet(false, true)) {
                            logStreamFailure(logContext, firstTokenMs.get(), generatedText, providerUsage.get(), error);
                        }
                    })
                    .doOnCancel(() -> {
                        if (terminalLogged.compareAndSet(false, true)) {
                            logTerminal(logContext, "cancelled", firstTokenMs.get(), finishReason.get(),
                                    generatedText, providerUsage.get());
                        }
                    });
        });
    }

    /**
     * 语义检索上下文：
     * - 先进行宽召回（fetchK ≥ 3×topK，至少 20）提高召回率
     * - 再按 metadata.postId 做服务端过滤，避免跨帖子污染
     * - 保留 chunkId、position 和 title，为回答引用提供可追溯来源
     */
    List<RagContext> searchContexts(String postId, String query, int topK) {
        int fetchK = fetchKFor(topK); // 宽召回：扩大初始检索集合
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(fetchK).build() // 语义相似检索
        );
        List<RagContext> out = new ArrayList<>(topK);
        for (Document d : docs) {
            Object pid = d.getMetadata().get("postId");
            if (pid != null && postId.equals(String.valueOf(pid))) { // 仅保留当前帖子对应的切片
                String txt = d.getText();
                if (StringUtils.hasText(txt)) {
                    // metadata 来自历史索引，字段可能缺失或类型变化，因此解析时提供稳定兜底值。
                    int position = asInt(d.getMetadata().get("position"), out.size());
                    String chunkId = asText(d.getMetadata().get("chunkId"), postId + "#" + position);
                    String title = asText(d.getMetadata().get("title"), "当前知文");
                    out.add(new RagContext(chunkId, position, title, txt.trim()));
                    if (out.size() >= topK) break; // 只取前 topK 个上下文
                }
            }
        }
        return out;
    }

    private void captureResponseMetadata(ChatResponse response,
                                         AtomicReference<RagTokenUsage> providerUsage,
                                         AtomicReference<String> finishReason) {
        tokenTracker.readProviderUsage(response).ifPresent(providerUsage::set);
        if (response.getResult() != null && response.getResult().getMetadata() != null) {
            String reason = response.getResult().getMetadata().getFinishReason();
            if (StringUtils.hasText(reason)) {
                finishReason.set(reason);
            }
        }
    }

    static String responseText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private void logTerminal(QueryLogContext context,
                             String status,
                             long firstTokenMs,
                             String finishReason,
                             StringBuilder generatedText,
                             RagTokenUsage providerUsage) {
        int estimatedCompletionTokens = tokenTracker.estimateText(generatedText.toString());
        int estimatedTotalTokens = context.estimatedPromptTokens() + estimatedCompletionTokens;
        log.info("RAG query completed requestId={} status={} postId={} model={} topK={} fetchK={} hitCount={} chunkIds={} "
                        + "indexMs={} retrievalMs={} firstTokenMs={} totalMs={} finishReason={} maxTokens={} "
                        + "estimatedPromptTokens={} estimatedCompletionTokens={} estimatedTotalTokens={} "
                        + "providerPromptTokens={} providerCompletionTokens={} providerTotalTokens={} tokenUsageSource={}",
                context.requestId(), status, context.postId(), CHAT_MODEL, context.topK(), context.fetchK(),
                context.chunkIds().size(), context.chunkIds(), context.indexMs(), context.retrievalMs(), firstTokenMs,
                elapsedMillis(context.queryStartedNanos()), finishReason, context.maxTokens(),
                context.estimatedPromptTokens(), estimatedCompletionTokens, estimatedTotalTokens,
                providerUsage == null ? null : providerUsage.promptTokens(),
                providerUsage == null ? null : providerUsage.completionTokens(),
                providerUsage == null ? null : providerUsage.totalTokens(),
                providerUsage == null ? "unavailable" : "provider");
    }

    private void logStreamFailure(QueryLogContext context,
                                  long firstTokenMs,
                                  StringBuilder generatedText,
                                  RagTokenUsage providerUsage,
                                  Throwable error) {
        int estimatedCompletionTokens = tokenTracker.estimateText(generatedText.toString());
        log.error("RAG query failed requestId={} status=error stage=generation postId={} model={} firstTokenMs={} totalMs={} "
                        + "estimatedPromptTokens={} estimatedCompletionTokens={} providerTotalTokens={} errorType={} message={}",
                context.requestId(), context.postId(), CHAT_MODEL, firstTokenMs,
                elapsedMillis(context.queryStartedNanos()), context.estimatedPromptTokens(), estimatedCompletionTokens,
                providerUsage == null ? null : providerUsage.totalTokens(), error.getClass().getSimpleName(),
                error.getMessage(), error);
    }

    private void logSynchronousFailure(String requestId,
                                       long postId,
                                       String stage,
                                       long queryStartedNanos,
                                       RuntimeException error) {
        log.error("RAG query failed requestId={} status=error stage={} postId={} totalMs={} errorType={} message={}",
                requestId, stage, postId, elapsedMillis(queryStartedNanos), error.getClass().getSimpleName(),
                error.getMessage(), error);
    }

    private static int fetchKFor(int topK) {
        return Math.max(topK * 3, 20);
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /**
     * 固化一次查询的低敏日志上下文，避免在多个 Reactor 回调中重复传递散乱参数。
     */
    private record QueryLogContext(
            String requestId,
            long postId,
            int topK,
            int fetchK,
            List<String> chunkIds,
            long indexMs,
            long retrievalMs,
            int maxTokens,
            int estimatedPromptTokens,
            long queryStartedNanos
    ) {
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 元数据异常时使用检索顺序兜底，避免影响问答主链路。
            }
        }
        return fallback;
    }

    private static String asText(Object value, String fallback) {
        String text = value == null ? null : String.valueOf(value);
        return StringUtils.hasText(text) ? text : fallback;
    }
}
