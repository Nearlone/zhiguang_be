package com.tongji.llm.rag;

import com.tongji.config.EsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagIndexServiceTest {

    private RecordingVectorStore recordingStore;
    private RagIndexService service;

    @BeforeEach
    void setUp() {
        recordingStore = new RecordingVectorStore();
        // 空索引名会跳过真实 ES 删除，测试只观察分批写入和失败清理行为。
        service = new RagIndexService(recordingStore.proxy(), null, null, new EsProperties());
    }

    @Test
    void splitsDocumentsIntoDashScopeCompatibleBatches() {
        int indexed = service.replaceDocuments(123L, documents(47));

        assertThat(recordingStore.batchSizes()).containsExactly(10, 10, 10, 10, 7);
        assertThat(indexed).isEqualTo(47);
    }

    @Test
    void allowsExactlyOneHundredChunks() {
        int indexed = service.replaceDocuments(123L, documents(100));

        assertThat(recordingStore.batchSizes()).containsExactly(
                10, 10, 10, 10, 10, 10, 10, 10, 10, 10);
        assertThat(indexed).isEqualTo(100);
    }

    @Test
    void rejectsArticleExceedingOneHundredChunksWithoutWritingPartialContent() {
        int indexed = service.replaceDocuments(123L, documents(101));

        assertThat(recordingStore.batchSizes()).isEmpty();
        assertThat(recordingStore.deletedIds).isEmpty();
        assertThat(indexed).isZero();
    }

    @Test
    void cleansWrittenDocumentsWhenALaterBatchFails() {
        recordingStore.failOnAddCall = 2;
        List<Document> documents = documents(47);

        int indexed = service.replaceDocuments(123L, documents);

        assertThat(recordingStore.batchSizes()).containsExactly(10, 10);
        assertThat(recordingStore.deletedIds).containsExactlyElementsOf(
                documents.stream().map(Document::getId).toList());
        assertThat(indexed).isZero();
    }

    @Test
    void decodesMarkdownBytesAsUtf8AndRemovesBom() {
        byte[] markdown = ("\uFEFF# 缓存穿透\n参数校验与空值缓存")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(RagIndexService.decodeUtf8Content(markdown))
                .isEqualTo("# 缓存穿透\n参数校验与空值缓存");
        assertThat(RagIndexService.decodeUtf8Content(new byte[0])).isNull();
    }

    @Test
    void rebuildsMetadataWithoutCurrentIndexVersion() {
        assertThat(RagIndexService.hasCurrentIndexVersion(Map.of())).isFalse();
        assertThat(RagIndexService.hasCurrentIndexVersion(Map.of("ragIndexVersion", "1"))).isFalse();
        assertThat(RagIndexService.hasCurrentIndexVersion(Map.of(
                "ragIndexVersion", RagIndexService.RAG_INDEX_VERSION))).isTrue();
    }

    @Test
    void carriesParentHeadingsIntoChildSectionsAndSkipsHeadingOnlyChunks() {
        String markdown = """
                # 缓存专题

                ## 缓存穿透
                ### 空值缓存
                空值 TTL 要短。

                ### 布隆过滤器
                说不存在，一定不存在。
                """;

        List<String> chunks = RagIndexService.chunkMarkdown(markdown);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0))
                .contains("# 缓存专题", "## 缓存穿透", "### 空值缓存", "空值 TTL 要短");
        assertThat(chunks.get(1))
                .contains("# 缓存专题", "## 缓存穿透", "### 布隆过滤器", "说不存在，一定不存在");
        assertThat(chunks).noneMatch(chunk -> chunk.strip().equals("## 缓存穿透"));
    }

    @Test
    void doesNotTreatHashesInsideCodeFenceAsMarkdownHeadings() {
        String markdown = """
                # 示例
                ```text
                # 这是代码内容
                ```
                代码块之后的说明。
                """;

        List<String> chunks = RagIndexService.chunkMarkdown(markdown);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst()).contains("# 示例", "# 这是代码内容", "代码块之后的说明");
    }

    private static List<Document> documents(int count) {
        List<Document> documents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String chunkId = "123#" + i;
            documents.add(new Document(chunkId, "chunk-" + i, Map.of(
                    "postId", "123",
                    "chunkId", chunkId,
                    "position", i)));
        }
        return documents;
    }

    private static final class RecordingVectorStore {
        private final List<List<Document>> addedBatches = new ArrayList<>();
        private List<String> deletedIds = List.of();
        private int failOnAddCall = -1;

        private VectorStore proxy() {
            // 动态代理只实现当前测试需要的方法，避免 Mockito Agent 对本机 JVM 的依赖。
            return (VectorStore) Proxy.newProxyInstance(
                    VectorStore.class.getClassLoader(),
                    new Class<?>[]{VectorStore.class},
                    (proxy, method, args) -> {
                        if ("add".equals(method.getName())) {
                            @SuppressWarnings("unchecked")
                            List<Document> batch = List.copyOf((List<Document>) args[0]);
                            addedBatches.add(batch);
                            if (addedBatches.size() == failOnAddCall) {
                                throw new IllegalStateException("embedding unavailable");
                            }
                            return null;
                        }
                        if ("delete".equals(method.getName()) && args[0] instanceof List<?> ids) {
                            deletedIds = ids.stream().map(String::valueOf).toList();
                            return null;
                        }
                        throw new AssertionError("Unexpected VectorStore call: " + method.getName());
                    });
        }

        private List<Integer> batchSizes() {
            return addedBatches.stream().map(List::size).toList();
        }
    }
}
