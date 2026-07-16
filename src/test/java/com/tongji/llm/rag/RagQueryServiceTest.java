package com.tongji.llm.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

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
                new RagCitationMapper());
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
                new RagPromptBuilder(), new RagTokenTracker(), new RagCitationMapper());

        List<RagContext> contexts = service.searchContexts("123", "问题", 5);

        assertThat(contexts).containsExactly(
                new RagContext("123#2", 2, "RAG工程化", "当前知文的有效内容"));
    }

    @Test
    void extractsTextFromStreamingChatResponse() {
        // 切换到 chatResponse() 读取 usage 后，仍要保证 SSE 能收到原始文本片段。
        ChatResponse textResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("回答片段"))));

        assertThat(RagQueryService.responseText(textResponse)).isEqualTo("回答片段");
        assertThat(RagQueryService.responseText(new ChatResponse(List.of()))).isNull();
    }

    private static VectorStore vectorStoreReturning(List<Document> documents) {
        // 动态代理只实现本测试需要的 similaritySearch，避免连接真实 Elasticsearch。
        return (VectorStore) Proxy.newProxyInstance(
                VectorStore.class.getClassLoader(),
                new Class<?>[]{VectorStore.class},
                (proxy, method, args) -> {
                    if ("similaritySearch".equals(method.getName())) {
                        return documents;
                    }
                    throw new AssertionError("Unexpected VectorStore call: " + method.getName());
                });
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
