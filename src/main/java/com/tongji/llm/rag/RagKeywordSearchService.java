package com.tongji.llm.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tongji.config.EsProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文章内关键词检索：补充向量检索容易遗漏的术语、参数名和枚举项。
 * 查询失败时返回空列表，由主链路自然降级为纯向量检索。
 */
@Service
@RequiredArgsConstructor
public class RagKeywordSearchService {
    private static final Logger log = LoggerFactory.getLogger(RagKeywordSearchService.class);

    private final ElasticsearchClient es;
    private final EsProperties esProperties;

    List<RagContext> search(String postId, String query, int limit) {
        if (!StringUtils.hasText(esProperties.getIndex()) || !StringUtils.hasText(query) || limit <= 0) {
            return List.of();
        }
        try {
            SearchResponse<Map> response = es.search(s -> s
                            .index(esProperties.getIndex())
                            .size(limit)
                            .query(q -> q.bool(b -> b
                                    .filter(f -> f.term(t -> t
                                            .field("metadata.postId")
                                            .value(postId)))
                                    .must(m -> m.match(mm -> mm
                                            .field("content")
                                            .query(query))))),
                    Map.class);
            List<RagContext> contexts = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                RagContext context = toContext(hit.source(), postId, contexts.size());
                if (context != null) {
                    contexts.add(context);
                }
            }
            return contexts;
        } catch (Exception e) {
            // BM25 是补充召回，故障时不能让整条问答链路不可用。
            log.warn("RAG keyword search degraded to vector-only: postId={}, error={}", postId, e.getMessage());
            return List.of();
        }
    }

    static RagContext toContext(Map<?, ?> source, String expectedPostId, int fallbackPosition) {
        if (source == null || !(source.get("metadata") instanceof Map<?, ?> metadata)) {
            return null;
        }
        Object postId = metadata.get("postId");
        Object content = source.get("content");
        if (postId == null || !expectedPostId.equals(String.valueOf(postId))
                || !StringUtils.hasText(content == null ? null : String.valueOf(content))) {
            return null;
        }
        int position = asInt(metadata.get("position"), fallbackPosition);
        String chunkId = asText(metadata.get("chunkId"), expectedPostId + "#" + position);
        String title = asText(metadata.get("title"), "当前知文");
        return new RagContext(chunkId, position, title, String.valueOf(content).trim());
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String asText(Object value, String fallback) {
        String text = value == null ? null : String.valueOf(value);
        return StringUtils.hasText(text) ? text : fallback;
    }
}
