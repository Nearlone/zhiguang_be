package com.tongji.llm.rag;

import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // RRF 只使用两路结果的相对排名；常用平滑常数 60 可避免第一名获得过大的单路优势。
    private static final int RRF_RANK_CONSTANT = 60;

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
    // 引用映射器：把模型使用的短别名安全地转换成前端原有的完整 chunkId
    private final RagCitationMapper citationMapper;
    // BM25 补充召回：优先找回问题中的术语、参数名和并列项，失败时降级为纯向量检索。
    private final RagKeywordSearchService keywordSearchService;
    // 可评测校准的相关性阈值，避免无关问题携带整篇文章上下文进入模型。
    private final RagProperties ragProperties;
    // 单元测试直接 new 服务时使用无状态指标实例；Spring 启动后通过 setter 注入真实 MeterRegistry。
    private RagMetrics metrics = RagMetrics.noop();

    /**
     * 使用 WebFlux 返回回答内容的流。
     */
    public Flux<String> streamAnswerFlux(long postId, String question, int topK, int maxTokens) {
        return streamAnswerFlux(
                postId, question, topK, maxTokens, UUID.randomUUID().toString(), "unknown");
    }

    /**
     * 旧版文本 SSE 使用稳定 requestId，但仍把同步故障转换为兼容文案。
     */
    public Flux<String> streamAnswerFlux(long postId,
                                         String question,
                                         int topK,
                                         int maxTokens,
                                         String requestId,
                                         String principalType) {
        return streamAnswer(postId, question, topK, maxTokens, requestId, principalType, false);
    }

    /**
     * v2 SSE 保留同步错误类型，由 Controller 转换为结构化 error 事件。
     */
    public Flux<String> streamAnswerFluxV2(long postId,
                                           String question,
                                           int topK,
                                           int maxTokens,
                                           String requestId,
                                           String principalType) {
        return streamAnswer(postId, question, topK, maxTokens, requestId, principalType, true);
    }

    @Autowired
    void setMetrics(RagMetrics metrics) {
        this.metrics = metrics;
    }

    private Flux<String> streamAnswer(long postId,
                                      String question,
                                      int topK,
                                      int maxTokens,
                                      String requestId,
                                      String principalType,
                                      boolean structuredErrors) {
        // 同步索引和检索阶段运行在当前线程；用 try-with-resources 确保 MDC 不污染后续请求。
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            return buildAnswerFlux(
                    postId, question, topK, maxTokens, requestId, principalType, structuredErrors);
        }
    }

    private Flux<String> buildAnswerFlux(long postId,
                                         String question,
                                         int topK,
                                         int maxTokens,
                                         String requestId,
                                         String principalType,
                                         boolean structuredErrors) {
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
            metrics.error("index");
            metrics.requestCompleted("index_error", elapsedMillis(queryStartedNanos));
            // 同步异常发生在 Flux 建立前；转换为固定 SSE 文案，前端不会只看到断开的空响应。
            return failureFlux(structuredErrors, "RAG_INDEX_UNAVAILABLE",
                    RagMessages.SERVICE_UNAVAILABLE_ANSWER, true);
        }
        long indexMs = elapsedMillis(indexStartedNanos);
        if (indexResult.status() != RagIndexStatus.READY) {
            // 索引失败时禁止继续读取可能属于旧版本的向量，避免用过期正文回答用户。
            log.info("RAG query completed requestId={} status=index_unavailable postId={} indexStatus={} totalMs={}",
                    requestId, postId, indexResult.status(), elapsedMillis(queryStartedNanos));
            metrics.requestCompleted("index_unavailable", elapsedMillis(queryStartedNanos));
            return failureFlux(structuredErrors, "RAG_INDEX_UNAVAILABLE",
                    indexResult.message(), indexResult.status() != RagIndexStatus.SKIPPED);
        }

        // 检索上下文：先在向量库中限定当前知文，再从该知文内部进行宽召回。
        long retrievalStartedNanos = System.nanoTime();
        SearchOutcome searchOutcome;
        try {
            searchOutcome = searchContextsDetailed(String.valueOf(postId), normalizedQuestion, normalizedTopK);
        } catch (RuntimeException e) {
            logSynchronousFailure(requestId, postId, "retrieval", queryStartedNanos, e);
            metrics.error("retrieval");
            metrics.requestCompleted("retrieval_error", elapsedMillis(queryStartedNanos));
            return failureFlux(structuredErrors, "RAG_RETRIEVAL_UNAVAILABLE",
                    RagMessages.SERVICE_UNAVAILABLE_ANSWER, true);
        }
        long retrievalMs = elapsedMillis(retrievalStartedNanos);
        List<RagContext> contexts = searchOutcome.contexts();
        List<String> chunkIds = contexts.stream().map(RagContext::chunkId).toList();
        log.info("RAG retrieval completed requestId={} postId={} retrievalMode=hybrid_current_post topK={} fetchK={} "
                        + "similarityThreshold={} vectorHitCount={} keywordHitCount={} finalHitCount={} "
                        + "maxVectorScore={} minAcceptedScore={} avgAcceptedScore={} chunkIds={} indexMs={} retrievalMs={}",
                requestId, postId, normalizedTopK, fetchK,
                ragProperties.getRetrieval().getSimilarityThreshold(),
                searchOutcome.vectorHitCount(), searchOutcome.keywordHitCount(), contexts.size(),
                searchOutcome.maxVectorScore(), searchOutcome.minAcceptedScore(),
                searchOutcome.avgAcceptedScore(), chunkIds, indexMs, retrievalMs);
        metrics.retrievalCompleted(retrievalMs, contexts.isEmpty());

        if (contexts.isEmpty()) {
            // 没有事实依据时不调用 DeepSeek，既降低幻觉，也节省一次模型生成成本。
            log.info("RAG query completed requestId={} status=no_context postId={} modelCalled=false totalMs={}",
                    requestId, postId, elapsedMillis(queryStartedNanos));
            metrics.requestCompleted("no_context", elapsedMillis(queryStartedNanos));
            return Flux.just(RagMessages.NO_CONTEXT_ANSWER);
        }

        // Prompt 组装独立封装，查询服务只负责串联“检索 -> 构造 Prompt -> 流式生成”。
        List<RagPromptSource> promptSources = RagPromptSource.fromContexts(contexts);
        String system = promptBuilder.buildSystemPrompt();
        String user = promptBuilder.buildUserPrompt(normalizedQuestion, promptSources);
        int estimatedPromptTokens = tokenTracker.estimatePromptTokens(system, user);
        QueryLogContext logContext = new QueryLogContext(
                requestId, postId, normalizedTopK, fetchK, chunkIds, indexMs, retrievalMs,
                maxTokens, estimatedPromptTokens, queryStartedNanos, principalType);
        log.info("RAG prompt prepared requestId={} postId={} model={} estimatedPromptTokens={} maxTokens={} sourceCount={}",
                requestId, postId, CHAT_MODEL, estimatedPromptTokens, maxTokens, contexts.size());

        // 每次订阅创建独立状态，避免多个订阅者共享首 Token、usage 和回答缓冲区。
        return Flux.defer(() -> {
            metrics.modelCalled();
            AtomicBoolean firstTokenSeen = new AtomicBoolean(false);
            AtomicBoolean terminalLogged = new AtomicBoolean(false);
            AtomicLong firstTokenMs = new AtomicLong(-1L);
            AtomicReference<RagTokenUsage> providerUsage = new AtomicReference<>();
            AtomicReference<String> finishReason = new AtomicReference<>("unknown");
            StringBuilder generatedText = new StringBuilder();

            Flux<String> modelDeltas;
            try {
                modelDeltas = chatClient
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
                            sink.next(delta);
                        });
            } catch (RuntimeException error) {
                // ChatClient 在返回 Flux 前同步失败时，内层 doOnError 尚未建立，需要在这里补记终态。
                terminalLogged.set(true);
                withMdc(requestId, () ->
                        logStreamFailure(logContext, firstTokenMs.get(),
                                generatedText, providerUsage.get(), error));
                return Flux.error(error);
            }

            // 模型只生成 [S1] 等短别名；后端在 SSE 流中映射为真实 chunkId，并过滤未知来源。
            return citationMapper.mapToChunkIds(modelDeltas, promptSources, requestId, postId)
                    .doOnNext(delta -> {
                        generatedText.append(delta);
                        if (firstTokenSeen.compareAndSet(false, true)) {
                            long ttftMs = elapsedMillis(queryStartedNanos);
                            firstTokenMs.set(ttftMs);
                            metrics.firstToken(ttftMs);
                            withMdc(requestId, () ->
                                    log.info("RAG first token requestId={} postId={} firstTokenMs={}",
                                            requestId, postId, ttftMs));
                        }
                    })
                    .doOnComplete(() -> {
                        if (terminalLogged.compareAndSet(false, true)) {
                            withMdc(requestId, () ->
                                    logTerminal(logContext, "success", firstTokenMs.get(), finishReason.get(),
                                            generatedText, providerUsage.get()));
                        }
                    })
                    .doOnError(error -> {
                        if (terminalLogged.compareAndSet(false, true)) {
                            withMdc(requestId, () ->
                                    logStreamFailure(logContext, firstTokenMs.get(),
                                            generatedText, providerUsage.get(), error));
                        }
                    })
                    .doOnCancel(() -> {
                        if (terminalLogged.compareAndSet(false, true)) {
                            withMdc(requestId, () ->
                                    logTerminal(logContext, "cancelled", firstTokenMs.get(), finishReason.get(),
                                            generatedText, providerUsage.get()));
                        }
                    });
        });
    }

    /**
     * 混合检索上下文：
     * - 先用 metadata.postId 将 KNN 检索范围限定为当前知文，避免其他知文挤占候选名额
     * - 在当前知文内宽召回（fetchK ≥ 3×topK，至少 20），为后续重排预留候选
     * - 使用 BM25 补充精确术语和并列项，通过 RRF 融合两路排名后限制上下文数量
     * - Java 层继续校验 postId，防御历史脏数据或向量库过滤异常
     * - 保留 chunkId、position 和 title，为回答引用提供可追溯来源
     */
    List<RagContext> searchContexts(String postId, String query, int topK) {
        return searchContextsDetailed(postId, query, topK).contexts();
    }

    /**
     * 除最终上下文外同时保留低敏聚合分数，供阈值校准和线上排障使用。
     */
    SearchOutcome searchContextsDetailed(String postId, String query, int topK) {
        int fetchK = fetchKFor(topK);
        double similarityThreshold = ragProperties.getRetrieval().getSimilarityThreshold();
        // 使用结构化表达式而不是拼接查询字符串，避免特殊字符破坏 ES 查询语法。
        Filter.Expression currentPostFilter = new FilterExpressionBuilder()
                .eq("postId", postId)
                .build();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(fetchK)
                        .similarityThreshold(similarityThreshold)
                        .filterExpression(currentPostFilter)
                        .build()
        );
        List<RagContext> vectorContexts = new ArrayList<>(fetchK);
        List<Double> vectorScores = new ArrayList<>(fetchK);
        List<Double> acceptedScores = new ArrayList<>(fetchK);
        for (Document d : docs) {
            if (d.getScore() != null) {
                vectorScores.add(d.getScore());
            }
            if (d.getScore() != null && d.getScore() < similarityThreshold) {
                // 二次防线：即使替换 VectorStore 实现后忽略了 SearchRequest 阈值，也不放行低相关切片。
                continue;
            }
            Object pid = d.getMetadata().get("postId");
            if (pid != null && postId.equals(String.valueOf(pid))) { // 仅保留当前帖子对应的切片
                String txt = d.getText();
                if (StringUtils.hasText(txt)) {
                    // metadata 来自历史索引，字段可能缺失或类型变化，因此解析时提供稳定兜底值。
                    int position = asInt(d.getMetadata().get("position"), vectorContexts.size());
                    String chunkId = asText(d.getMetadata().get("chunkId"), postId + "#" + position);
                    String title = asText(d.getMetadata().get("title"), "当前知文");
                    vectorContexts.add(new RagContext(chunkId, position, title, txt.trim()));
                    if (d.getScore() != null) {
                        acceptedScores.add(d.getScore());
                    }
                    if (vectorContexts.size() >= fetchK) break;
                }
            }
        }
        DoubleSummaryStatistics allScoreStats = vectorScores.stream()
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();
        DoubleSummaryStatistics acceptedScoreStats = acceptedScores.stream()
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();
        Double maxVectorScore = vectorScores.isEmpty() ? null : allScoreStats.getMax();
        Double minAcceptedScore = acceptedScores.isEmpty() ? null : acceptedScoreStats.getMin();
        Double avgAcceptedScore = acceptedScores.isEmpty() ? null : acceptedScoreStats.getAverage();

        if (vectorContexts.isEmpty()) {
            // BM25 可补充术语排序，但不能单独证明语义相关；无向量证据时直接拒答并节省生成 Token。
            return new SearchOutcome(
                    List.of(), 0, 0, maxVectorScore, minAcceptedScore, avgAcceptedScore);
        }
        if (keywordSearchService == null) {
            return new SearchOutcome(
                    vectorContexts.stream().limit(topK).toList(),
                    vectorContexts.size(),
                    0,
                    maxVectorScore,
                    minAcceptedScore,
                    avgAcceptedScore);
        }
        List<RagContext> keywordContexts = keywordSearchService.search(postId, query, Math.min(fetchKFor(topK), 10));
        // topK 的接口语义是最终进入 Prompt 的上下文数量，而不是单路召回数量。
        return new SearchOutcome(
                mergeContexts(keywordContexts, vectorContexts, topK),
                vectorContexts.size(),
                keywordContexts.size(),
                maxVectorScore,
                minAcceptedScore,
                avgAcceptedScore);
    }

    /**
     * 使用倒数排名融合（RRF）合并 BM25 与向量召回：
     * - 不直接比较 BM25 分数和向量相似度，因为两者量纲不同；
     * - 同一切片被两路命中时累计排名分数，使共同认可的证据优先；
     * - 单路高排名切片仍可进入结果，避免某一路先填满 limit 后挤掉另一条检索通道；
     * - 以 chunkId 去重，限制最终上下文数量，控制 Prompt Token 开销。
     */
    static List<RagContext> mergeContexts(List<RagContext> keywordContexts,
                                          List<RagContext> vectorContexts,
                                          int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<String, FusionCandidate> candidates = new LinkedHashMap<>();
        addRrfScores(candidates, keywordContexts);
        addRrfScores(candidates, vectorContexts);
        return candidates.values().stream()
                .sorted(Comparator.comparingDouble(FusionCandidate::score).reversed()
                        .thenComparingInt(FusionCandidate::bestRank)
                        .thenComparingInt(FusionCandidate::firstSeenOrder))
                .limit(limit)
                .map(FusionCandidate::context)
                .toList();
    }

    private static void addRrfScores(Map<String, FusionCandidate> candidates, List<RagContext> rankedContexts) {
        if (rankedContexts == null || rankedContexts.isEmpty()) {
            return;
        }
        Set<String> seenInCurrentRetriever = new HashSet<>();
        for (int index = 0; index < rankedContexts.size(); index++) {
            RagContext context = rankedContexts.get(index);
            if (context == null || !StringUtils.hasText(context.chunkId())
                    || !seenInCurrentRetriever.add(context.chunkId())) {
                // 同一路中的脏重复数据不能重复投票，否则会人为放大该切片的融合分数。
                continue;
            }
            int rank = index + 1;
            double scoreContribution = 1.0 / (RRF_RANK_CONSTANT + rank);
            FusionCandidate existing = candidates.get(context.chunkId());
            if (existing == null) {
                candidates.put(context.chunkId(), new FusionCandidate(
                        context, scoreContribution, rank, candidates.size()));
            } else {
                candidates.put(context.chunkId(), new FusionCandidate(
                        existing.context(), existing.score() + scoreContribution,
                        Math.min(existing.bestRank(), rank), existing.firstSeenOrder()));
            }
        }
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
        long totalMs = elapsedMillis(context.queryStartedNanos());
        metrics.requestCompleted(status, totalMs);
        metrics.providerTokens(providerUsage);
        log.info("RAG query completed requestId={} status={} postId={} model={} topK={} fetchK={} hitCount={} chunkIds={} "
                        + "principalType={} indexMs={} retrievalMs={} firstTokenMs={} totalMs={} finishReason={} maxTokens={} "
                        + "estimatedPromptTokens={} estimatedCompletionTokens={} estimatedTotalTokens={} "
                        + "providerPromptTokens={} providerCompletionTokens={} providerTotalTokens={} tokenUsageSource={}",
                context.requestId(), status, context.postId(), CHAT_MODEL, context.topK(), context.fetchK(),
                context.chunkIds().size(), context.chunkIds(), context.principalType(),
                context.indexMs(), context.retrievalMs(), firstTokenMs,
                totalMs, finishReason, context.maxTokens(),
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
        metrics.error("generation");
        metrics.requestCompleted("generation_error", elapsedMillis(context.queryStartedNanos()));
        metrics.providerTokens(providerUsage);
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

    private static Flux<String> failureFlux(boolean structuredErrors,
                                            String code,
                                            String message,
                                            boolean retryable) {
        return structuredErrors
                ? Flux.error(new RagStreamException(code, message, retryable))
                : Flux.just(message);
    }

    private static void withMdc(String requestId, Runnable action) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            action.run();
        }
    }

    /**
     * 一次检索的结果和聚合分数。只记录统计值，不把完整候选分数长期写入业务日志。
     */
    record SearchOutcome(
            List<RagContext> contexts,
            int vectorHitCount,
            int keywordHitCount,
            Double maxVectorScore,
            Double minAcceptedScore,
            Double avgAcceptedScore
    ) {
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
            long queryStartedNanos,
            String principalType
    ) {
    }

    /** RRF 排序的内部候选，不暴露检索分数，避免改变 RagContext 与 Prompt 的既有契约。 */
    private record FusionCandidate(
            RagContext context,
            double score,
            int bestRank,
            int firstSeenOrder
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
