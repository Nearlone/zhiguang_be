package com.tongji.llm.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.util.OutboxMessageUtil;
import com.tongji.relation.outbox.OutboxTopics;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 独立 Outbox 消费者：发布事务提交后异步构建向量索引。
 *
 * <p>使用独立消费组，Embedding 的耗时或失败不会阻塞搜索索引消费者。</p>
 */
@Service
@RequiredArgsConstructor
public class CanalOutboxConsumerRag {
    private static final Logger log = LoggerFactory.getLogger(CanalOutboxConsumerRag.class);
    private static final String PUBLISHED_EVENT = "KNOWPOST_PUBLISHED";
    private static final String VISIBILITY_EVENT = "KNOWPOST_VISIBILITY_CHANGED";
    private static final String DELETED_EVENT = "KNOWPOST_DELETED";

    private final ObjectMapper objectMapper;
    private final RagIndexService indexService;

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "rag-index-consumer")
    public void onMessage(String message, Acknowledgment ack) {
        try {
            List<JsonNode> rows = OutboxMessageUtil.extractRows(objectMapper, message);
            for (JsonNode row : rows) {
                JsonNode payloadNode = row.get("payload");
                if (payloadNode == null) {
                    continue;
                }
                JsonNode payload = objectMapper.readTree(payloadNode.asText());
                String event = text(payload.get("event"));
                Long postId = asLong(payload.get("id"));
                if (postId == null) {
                    continue;
                }

                if (PUBLISHED_EVENT.equals(event)
                        || (VISIBILITY_EVENT.equals(event) && "public".equals(text(payload.get("visible"))))) {
                    RagIndexResult result = indexService.reindexSinglePost(postId);
                    log.info("RAG outbox processed: postId={}, status={}, chunks={}",
                            postId, result.status(), result.chunkCount());
                } else if (DELETED_EVENT.equals(event) || VISIBILITY_EVENT.equals(event)) {
                    indexService.removeIndex(postId);
                    log.info("RAG outbox removed index: postId={}, event={}", postId, event);
                }
            }
            // 业务失败已持久化为 FAILED；只有解析或数据库异常才抛出并保留 Kafka 位点。
            ack.acknowledge();
        } catch (RuntimeException e) {
            log.error("RAG outbox processing failed", e);
            throw e;
        } catch (Exception e) {
            log.error("RAG outbox parsing failed", e);
            throw new IllegalStateException("RAG outbox parsing failed", e);
        }
    }

    private static String text(JsonNode node) {
        return node == null ? null : node.asText();
    }

    private static Long asLong(JsonNode node) {
        if (node == null || !node.canConvertToLong()) {
            return null;
        }
        return node.longValue();
    }
}
