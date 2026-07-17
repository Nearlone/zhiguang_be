package com.tongji.llm.rag;

import org.junit.jupiter.api.Test;

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
}
