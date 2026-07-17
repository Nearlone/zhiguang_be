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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文章内关键词检索：补充向量检索容易遗漏的术语、参数名和枚举项。
 * 查询失败时返回空列表，由主链路自然降级为纯向量检索。
 */
@Service
@RequiredArgsConstructor
public class RagKeywordSearchService {
    private static final Logger log = LoggerFactory.getLogger(RagKeywordSearchService.class);
    private static final Pattern TECHNICAL_TERM_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{2,}");
    // 限制额外查询数量，避免一条包含大量英文单词的问题放大 Elasticsearch 压力。
    private static final int MAX_TECHNICAL_TERM_QUERIES = 4;

    private final ElasticsearchClient es;
    private final EsProperties esProperties;

    List<RagContext> search(String postId, String query, int limit) {
        if (!StringUtils.hasText(esProperties.getIndex()) || !StringUtils.hasText(query) || limit <= 0) {
            return List.of();
        }
        try {
            List<List<RagContext>> technicalTermResults = new ArrayList<>();
            List<String> technicalTerms = extractTechnicalTerms(query);
            if (technicalTerms.size() >= 2) {
                /*
                 * 多实体对比题不能只执行一次整句 BM25。整句中的“风险、区别”等公共词可能把某个实体的
                 * 其他章节排在前面，挤掉其余实体的直接证据；每个技术标识符先保留一个候选可保证证据覆盖。
                 */
                for (String technicalTerm : technicalTerms) {
                    technicalTermResults.add(searchOnce(postId, technicalTerm, 1));
                }
            }
            List<RagContext> generalResults = searchOnce(postId, query, limit);
            return mergeDiversifiedResults(technicalTermResults, generalResults, limit);
        } catch (Exception e) {
            // BM25 是补充召回，故障时不能让整条问答链路不可用。
            log.warn("RAG keyword search degraded to vector-only: postId={}, error={}", postId, e.getMessage());
            return List.of();
        }
    }

    private List<RagContext> searchOnce(String postId, String query, int limit) throws Exception {
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
    }

    /**
     * 提取 CamelCase、全大写缩写、带数字或连接符的技术标识符。
     * 普通小写英文单词不会触发额外查询，防止英文长问题产生过多 Elasticsearch 请求。
     */
    static List<String> extractTechnicalTerms(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        Map<String, String> uniqueTerms = new LinkedHashMap<>();
        Matcher matcher = TECHNICAL_TERM_PATTERN.matcher(query);
        while (matcher.find() && uniqueTerms.size() < MAX_TECHNICAL_TERM_QUERIES) {
            String term = matcher.group();
            boolean hasUppercase = term.chars().anyMatch(Character::isUpperCase);
            boolean hasDigit = term.chars().anyMatch(Character::isDigit);
            boolean hasSeparator = term.indexOf('_') >= 0 || term.indexOf('.') >= 0 || term.indexOf('-') >= 0;
            if (hasUppercase || hasDigit || hasSeparator) {
                uniqueTerms.putIfAbsent(term.toLowerCase(Locale.ROOT), term);
            }
        }
        return List.copyOf(uniqueTerms.values());
    }

    /**
     * 各技术标识符的首个结果优先，然后使用整句 BM25 结果补满；chunkId 去重并保持稳定顺序。
     */
    static List<RagContext> mergeDiversifiedResults(List<List<RagContext>> technicalTermResults,
                                                    List<RagContext> generalResults,
                                                    int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<String, RagContext> merged = new LinkedHashMap<>();
        if (technicalTermResults != null) {
            for (List<RagContext> contexts : technicalTermResults) {
                if (contexts != null && !contexts.isEmpty()) {
                    RagContext first = contexts.get(0);
                    if (first != null && StringUtils.hasText(first.chunkId())) {
                        merged.putIfAbsent(first.chunkId(), first);
                    }
                }
                if (merged.size() >= limit) {
                    return merged.values().stream().limit(limit).toList();
                }
            }
        }
        if (generalResults != null) {
            for (RagContext context : generalResults) {
                if (context != null && StringUtils.hasText(context.chunkId())) {
                    merged.putIfAbsent(context.chunkId(), context);
                }
                if (merged.size() >= limit) {
                    break;
                }
            }
        }
        return merged.values().stream().limit(limit).toList();
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
