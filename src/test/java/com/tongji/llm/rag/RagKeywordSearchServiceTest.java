package com.tongji.llm.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagKeywordSearchServiceTest {

    @Test
    void mapsOnlyExpectedPostSourcesToContexts() {
        Map<String, Object> source = Map.of(
                "content", "  布隆过滤器：说不存在一定不存在  ",
                "metadata", Map.of(
                        "postId", "123",
                        "chunkId", "123#6",
                        "position", 6,
                        "title", "缓存专题"));

        assertThat(RagKeywordSearchService.toContext(source, "123", 0))
                .isEqualTo(new RagContext("123#6", 6, "缓存专题", "布隆过滤器：说不存在一定不存在"));
        assertThat(RagKeywordSearchService.toContext(source, "999", 0)).isNull();
    }

    @Test
    void extractsTechnicalIdentifiersWithoutExpandingOrdinaryEnglishWords() {
        assertThat(RagKeywordSearchService.extractTechnicalTerms(
                "比较 ArrayBlockingQueue、LinkedBlockingQueue 和 SynchronousQueue 的 risk and behavior"))
                .containsExactly("ArrayBlockingQueue", "LinkedBlockingQueue", "SynchronousQueue");
        assertThat(RagKeywordSearchService.extractTechnicalTerms(
                "BM25、RRF 与 text-embedding-v4 有什么区别"))
                .containsExactly("BM25", "RRF", "text-embedding-v4");
    }

    @Test
    void keepsOneCandidatePerTechnicalTermBeforeGeneralBm25Results() {
        RagContext arrayQueue = context("123#6");
        RagContext linkedQueue = context("123#7");
        RagContext synchronousQueue = context("123#8");
        RagContext generalFirst = context("123#18");

        List<RagContext> merged = RagKeywordSearchService.mergeDiversifiedResults(
                List.of(List.of(arrayQueue), List.of(linkedQueue), List.of(synchronousQueue)),
                List.of(generalFirst, linkedQueue),
                4);

        assertThat(merged).containsExactly(arrayQueue, linkedQueue, synchronousQueue, generalFirst);
    }

    private static RagContext context(String chunkId) {
        return new RagContext(chunkId, 0, "线程池", "内容-" + chunkId);
    }
}
