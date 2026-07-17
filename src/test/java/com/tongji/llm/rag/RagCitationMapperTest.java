package com.tongji.llm.rag;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagCitationMapperTest {

    private final RagCitationMapper mapper = new RagCitationMapper();
    private final List<RagPromptSource> sources = RagPromptSource.fromContexts(List.of(
            new RagContext("123#2", 2, "RAG", "第一段"),
            new RagContext("123#7", 7, "RAG", "第二段")));

    @Test
    void mapsAliasesEvenWhenSseSplitsCitationAcrossDeltas() {
        Flux<String> deltas = Flux.just("结论[", "S", "1", "]，补充[", "S2", "]。");

        String answer = mapper.mapToChunkIds(deltas, sources, "request-1", 123L)
                .collectList()
                .map(parts -> String.join("", parts))
                .block();

        assertThat(answer).isEqualTo("结论[123#2]，补充[123#7]。");
    }

    @Test
    void rejectsUnknownAliasesAndUntrustedChunkIdsButKeepsNormalBrackets() {
        Flux<String> deltas = Flux.just(
                "正确[S1]，未知[S9]，直出[123#2]，伪造[999#4]，旧格式[chunkId=1#1]，普通[说明]。");

        String answer = mapper.mapToChunkIds(deltas, sources, "request-2", 123L)
                .collectList()
                .map(parts -> String.join("", parts))
                .block();

        assertThat(answer).isEqualTo("正确[123#2]，未知，直出，伪造，旧格式，普通[说明]。");
    }

    @Test
    void flushesUnclosedNormalBracketAtEndOfStream() {
        String answer = mapper.mapToChunkIds(Flux.just("正文[未闭合"), sources, "request-3", 123L)
                .collectList()
                .map(parts -> String.join("", parts))
                .block();

        assertThat(answer).isEqualTo("正文[未闭合");
    }

    @Test
    void keepsStateIndependentForEachSubscription() {
        Flux<String> mapped = mapper.mapToChunkIds(Flux.just("答案[S1]"), sources, "request-4", 123L);

        assertThat(mapped.collectList().block()).containsExactly("答案[123#2]");
        assertThat(mapped.collectList().block()).containsExactly("答案[123#2]");
    }
}
