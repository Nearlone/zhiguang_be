package com.tongji.llm.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 将模型输出的短来源引用映射为真实 chunkId，并过滤未经后端授权的引用。
 *
 * <p>SSE 文本可能把 {@code [S1]} 拆成多个 delta，因此这里使用订阅级状态机，
 * 在不缓存完整回答的前提下跨分片识别引用，保留原有流式体验。</p>
 */
@Component
public class RagCitationMapper {
    private static final Logger log = LoggerFactory.getLogger(RagCitationMapper.class);
    private static final Pattern SOURCE_ALIAS = Pattern.compile("\\[S\\d+]");
    private static final Pattern CHUNK_LIKE_CITATION = Pattern.compile("\\[[^\\[\\]\\s]*#\\d+]");
    private static final int MAX_BRACKET_LENGTH = 128;

    public Flux<String> mapToChunkIds(Flux<String> deltas,
                                      List<RagPromptSource> sources,
                                      String requestId,
                                      long postId) {
        Map<String, String> sourceMap = new LinkedHashMap<>();
        for (RagPromptSource source : sources) {
            sourceMap.put(source.alias(), source.chunkId());
        }
        Map<String, String> allowedSources = Map.copyOf(sourceMap);

        // Flux.defer 确保每个 SSE 订阅者拥有独立缓冲区，避免并发请求互相污染。
        return Flux.defer(() -> {
            CitationState state = new CitationState(allowedSources, requestId, postId);
            Flux<String> mapped = deltas.<String>handle((delta, sink) -> {
                String output = state.accept(delta);
                if (!output.isEmpty()) {
                    sink.next(output);
                }
            });
            return mapped.concatWith(Flux.defer(() -> {
                String tail = state.finish();
                return tail.isEmpty() ? Flux.empty() : Flux.just(tail);
            }));
        });
    }

    private static final class CitationState {
        private final Map<String, String> allowedSources;
        private final String requestId;
        private final long postId;
        private final StringBuilder bracket = new StringBuilder();
        private boolean collectingBracket;

        private CitationState(Map<String, String> allowedSources, String requestId, long postId) {
            this.allowedSources = allowedSources;
            this.requestId = requestId;
            this.postId = postId;
        }

        private String accept(String delta) {
            if (delta == null || delta.isEmpty()) {
                return "";
            }
            StringBuilder output = new StringBuilder(delta.length());
            for (int i = 0; i < delta.length(); i++) {
                char current = delta.charAt(i);
                if (!collectingBracket) {
                    if (current == '[') {
                        collectingBracket = true;
                        bracket.append(current);
                    } else {
                        output.append(current);
                    }
                    continue;
                }

                bracket.append(current);
                if (current == ']') {
                    appendResolvedBracket(output);
                    resetBracket();
                } else if (bracket.length() > MAX_BRACKET_LENGTH) {
                    // 超长或未闭合的普通方括号不是来源引用，原样放行，避免无限缓存。
                    output.append(bracket);
                    resetBracket();
                }
            }
            return output.toString();
        }

        private void appendResolvedBracket(StringBuilder output) {
            String candidate = bracket.toString();
            if (SOURCE_ALIAS.matcher(candidate).matches()) {
                String alias = candidate.substring(1, candidate.length() - 1);
                String chunkId = allowedSources.get(alias);
                if (chunkId != null) {
                    output.append('[').append(chunkId).append(']');
                } else {
                    log.warn("RAG citation rejected requestId={} postId={} citation={} reason=unknown_alias",
                            requestId, postId, alias);
                }
                return;
            }
            if (CHUNK_LIKE_CITATION.matcher(candidate).matches()) {
                // 即使模型直接输出了一个看似合法的长 ID，也不能绕过本次召回来源白名单。
                log.warn("RAG citation rejected requestId={} postId={} reason=untrusted_chunk_id",
                        requestId, postId);
                return;
            }
            output.append(candidate);
        }

        private String finish() {
            // 非完整方括号可能只是普通文本，流结束时必须原样返回，不能误删用户可见内容。
            String tail = bracket.toString();
            resetBracket();
            return tail;
        }

        private void resetBracket() {
            bracket.setLength(0);
            collectingBracket = false;
        }
    }
}
