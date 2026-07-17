package com.tongji.llm.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RagQueryServiceTest {

    private RecordingRagIndexService indexService;
    private RagQueryService ragQueryService;

    @BeforeEach
    void setUp() {
        indexService = new RecordingRagIndexService();
        // chatClient 故意传 null：若空召回分支仍调用模型，测试会立刻失败。
        ragQueryService = new RagQueryService(
                vectorStoreReturning(List.of()), null, indexService, new RagPromptBuilder(), new RagTokenTracker(),
                new RagCitationMapper(), null, new RagProperties());
    }

    @Test
    void returnsRefusalWithoutCallingModelWhenNoContextIsRetrieved() {
        List<String> answer = ragQueryService
                .streamAnswerFlux(123L, "  文章的核心观点是什么？  ", 5, 1024)
                .collectList()
                .block();

        assertThat(answer).containsExactly("当前知文中没有足够信息回答这个问题。");
        assertThat(indexService.indexedPostId).isEqualTo(123L);
    }

    @Test
    void returnsIndexFailureWithoutSearchingStaleVectors() {
        indexService.result = RagIndexResult.failed("向量索引创建失败，可稍后重试");

        List<String> answer = ragQueryService
                .streamAnswerFlux(123L, "文章讲了什么？", 5, 1024)
                .collectList()
                .block();

        assertThat(answer).containsExactly("向量索引创建失败，可稍后重试");
    }

    @Test
    void mapsCurrentPostDocumentsToStructuredContexts() {
        Document foreignPost = new Document("其他知文内容", Map.of(
                "postId", "999", "chunkId", "999#0", "position", 0, "title", "其他知文"));
        Document currentPost = new Document("  当前知文的有效内容  ", Map.of(
                "postId", "123", "chunkId", "123#2", "position", 2, "title", "RAG工程化"));
        RagQueryService service = new RagQueryService(
                vectorStoreReturning(List.of(foreignPost, currentPost)), null, null,
                new RagPromptBuilder(), new RagTokenTracker(), new RagCitationMapper(), null, new RagProperties());

        List<RagContext> contexts = service.searchContexts("123", "问题", 5);

        assertThat(contexts).containsExactly(
                new RagContext("123#2", 2, "RAG工程化", "当前知文的有效内容"));
    }

    @Test
    void limitsVectorSearchToCurrentPostBeforeKnnRecall() {
        AtomicReference<SearchRequest> capturedRequest = new AtomicReference<>();
        RagQueryService service = new RagQueryService(
                vectorStoreReturning(List.of(), capturedRequest), null, null,
                new RagPromptBuilder(), new RagTokenTracker(), new RagCitationMapper(), null, new RagProperties());

        service.searchContexts("123", "MVCC解决了什么问题？", 5);

        SearchRequest request = capturedRequest.get();
        assertThat(request).isNotNull();
        assertThat(request.getQuery()).isEqualTo("MVCC解决了什么问题？");
        assertThat(request.getTopK()).isEqualTo(20);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.5);
        assertThat(request.getFilterExpression()).isEqualTo(
                new FilterExpressionBuilder().eq("postId", "123").build());
    }

    @Test
    void rejectsLowSimilarityResultsBeforeKeywordFusion() {
        Document lowSimilarity = Document.builder()
                .text("文章中的普通内容")
                .metadata(Map.of("postId", "123", "chunkId", "123#0", "position", 0))
                .score(0.49)
                .build();
        RagKeywordSearchService keywordSearch = keywordSearchReturning(List.of(context("K1")));
        RagQueryService service = new RagQueryService(
                vectorStoreReturning(List.of(lowSimilarity)), null, null,
                new RagPromptBuilder(), new RagTokenTracker(), new RagCitationMapper(), keywordSearch,
                new RagProperties());

        // BM25 不能单独绕过最低语义证据，否则无关问题中的常见词仍会触发模型调用。
        assertThat(service.searchContexts("123", "完全无关的问题", 5)).isEmpty();
    }

    @Test
    void hybridSearchNeverReturnsMoreThanTopK() {
        List<Document> vectors = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> Document.builder()
                        .text("向量内容-" + index)
                        .metadata(Map.of("postId", "123", "chunkId", "V" + index, "position", index))
                        .score(0.8)
                        .build())
                .toList();
        RagQueryService service = new RagQueryService(
                vectorStoreReturning(vectors), null, null,
                new RagPromptBuilder(), new RagTokenTracker(), new RagCitationMapper(),
                keywordSearchReturning(contexts("K", 10)), new RagProperties());

        assertThat(service.searchContexts("123", "相关问题", 5)).hasSize(5);
    }

    @Test
    void returnsFriendlyMessageWhenEmbeddingRetrievalFails() {
        RagQueryService service = new RagQueryService(
                vectorStoreThrowing(new IllegalStateException("embedding timeout")), null, indexService,
                new RagPromptBuilder(), new RagTokenTracker(), new RagCitationMapper(), null, new RagProperties());

        List<String> answer = service.streamAnswerFlux(123L, "文章讲了什么？", 5, 1024)
                .collectList()
                .block();

        assertThat(answer).containsExactly("AI问答服务暂时不可用，请稍后重试。");
    }

    @Test
    void extractsTextFromStreamingChatResponse() {
        // 切换到 chatResponse() 读取 usage 后，仍要保证 SSE 能收到原始文本片段。
        ChatResponse textResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("回答片段"))));

        assertThat(RagQueryService.responseText(textResponse)).isEqualTo("回答片段");
        assertThat(RagQueryService.responseText(new ChatResponse(List.of()))).isNull();
    }

    @Test
    void mergesKeywordAndVectorContextsWithoutDuplicateChunks() {
        RagContext keyword = new RagContext("123#6", 6, "缓存", "空值缓存注意点");
        RagContext duplicateVector = new RagContext("123#6", 6, "缓存", "重复内容");
        RagContext semantic = new RagContext("123#1", 1, "缓存", "缓存穿透概述");

        assertThat(RagQueryService.mergeContexts(
                List.of(keyword), List.of(duplicateVector, semantic), 2))
                .containsExactly(keyword, semantic);
    }

    @Test
    void keepsHighRankedResultsFromBothRetrieversWhenKeywordResultsFillLimit() {
        List<RagContext> keywords = contexts("K", 10);
        List<RagContext> vectors = contexts("V", 5);

        List<RagContext> merged = RagQueryService.mergeContexts(keywords, vectors, 10);

        // 旧的顺序拼接会只留下 K1-K10；RRF 应让两路的高排名独有证据都进入 Prompt。
        assertThat(merged).extracting(RagContext::chunkId)
                .containsExactly("K1", "V1", "K2", "V2", "K3", "V3", "K4", "V4", "K5", "V5");
    }

    @Test
    void promotesChunksConfirmedByBothRetrievers() {
        RagContext keywordOnly = context("K1");
        RagContext sharedFromKeyword = context("S1");
        RagContext sharedFromVector = new RagContext("S1", 99, "向量标题", "向量返回的同一切片");
        RagContext vectorOnly = context("V1");

        List<RagContext> merged = RagQueryService.mergeContexts(
                List.of(keywordOnly, sharedFromKeyword),
                List.of(sharedFromVector, vectorOnly),
                3);

        // S1 获得两路排名分数并排到首位；去重后沿用首次出现的完整上下文。
        assertThat(merged).containsExactly(sharedFromKeyword, keywordOnly, vectorOnly);
    }

    @Test
    void fallsBackToAvailableRetrieverAndRespectsBoundaryInputs() {
        List<RagContext> vectors = contexts("V", 3);

        assertThat(RagQueryService.mergeContexts(List.of(), vectors, 2))
                .containsExactly(vectors.get(0), vectors.get(1));
        assertThat(RagQueryService.mergeContexts(null, vectors, 0)).isEmpty();
    }

    @Test
    void ignoresDuplicateVotesFromTheSameRetriever() {
        RagContext duplicate = context("D1");
        RagContext secondKeyword = context("K2");
        RagContext vectorFirst = context("V1");

        List<RagContext> merged = RagQueryService.mergeContexts(
                List.of(duplicate, duplicate, secondKeyword),
                List.of(vectorFirst),
                3);

        assertThat(merged).containsExactly(duplicate, vectorFirst, secondKeyword);
    }

    private static List<RagContext> contexts(String prefix, int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> context(prefix + index))
                .toList();
    }

    private static RagContext context(String chunkId) {
        return new RagContext(chunkId, 0, "测试", "内容-" + chunkId);
    }

    private static VectorStore vectorStoreReturning(List<Document> documents) {
        return vectorStoreReturning(documents, new AtomicReference<>());
    }

    private static VectorStore vectorStoreReturning(List<Document> documents,
                                                     AtomicReference<SearchRequest> capturedRequest) {
        // 动态代理只实现本测试需要的 similaritySearch，避免连接真实 Elasticsearch。
        return (VectorStore) Proxy.newProxyInstance(
                VectorStore.class.getClassLoader(),
                new Class<?>[]{VectorStore.class},
                (proxy, method, args) -> {
                    if ("similaritySearch".equals(method.getName())) {
                        // 捕获真实 SearchRequest，验证过滤发生在向量库检索阶段，而不是只在 Java 返回后过滤。
                        capturedRequest.set((SearchRequest) args[0]);
                        return documents;
                    }
                    throw new AssertionError("Unexpected VectorStore call: " + method.getName());
                });
    }

    private static VectorStore vectorStoreThrowing(RuntimeException failure) {
        return (VectorStore) Proxy.newProxyInstance(
                VectorStore.class.getClassLoader(),
                new Class<?>[]{VectorStore.class},
                (proxy, method, args) -> {
                    if ("similaritySearch".equals(method.getName())) {
                        throw failure;
                    }
                    throw new AssertionError("Unexpected VectorStore call: " + method.getName());
                });
    }

    private static RagKeywordSearchService keywordSearchReturning(List<RagContext> contexts) {
        return new RagKeywordSearchService(null, null) {
            @Override
            List<RagContext> search(String postId, String query, int limit) {
                // 固定结果只用于验证融合边界，不连接真实 Elasticsearch。
                return contexts.stream().limit(limit).toList();
            }
        };
    }

    private static final class RecordingRagIndexService extends RagIndexService {

        private long indexedPostId;
        private RagIndexResult result = RagIndexResult.ready(1);

        private RecordingRagIndexService() {
            super(null, null, null, null);
        }

        @Override
        public RagIndexResult ensureIndexed(long postId) {
            // 只记录调用参数，不执行数据库查询、正文下载和向量写入。
            indexedPostId = postId;
            return result;
        }
    }
}
