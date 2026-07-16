package com.tongji.llm.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CanalOutboxConsumerRagTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RecordingRagIndexService indexService;
    private CanalOutboxConsumerRag consumer;

    @BeforeEach
    void setUp() {
        indexService = new RecordingRagIndexService();
        consumer = new CanalOutboxConsumerRag(objectMapper, indexService);
    }

    @Test
    void indexesPublishedKnowPostAndAcknowledgesMessage() throws Exception {
        AtomicBoolean acknowledged = new AtomicBoolean(false);

        consumer.onMessage(outboxMessage(Map.of(
                "entity", "knowpost",
                "op", "upsert",
                "event", "KNOWPOST_PUBLISHED",
                "id", 123L)), () -> acknowledged.set(true));

        assertThat(indexService.indexedPostIds).containsExactly(123L);
        assertThat(acknowledged).isTrue();
    }

    @Test
    void ignoresMetadataEventsWithoutTriggeringEmbedding() throws Exception {
        AtomicBoolean acknowledged = new AtomicBoolean(false);

        consumer.onMessage(outboxMessage(Map.of(
                "entity", "knowpost",
                "op", "upsert",
                "id", 123L)), () -> acknowledged.set(true));

        assertThat(indexService.indexedPostIds).isEmpty();
        assertThat(acknowledged).isTrue();
    }

    @Test
    void removesVectorsAfterKnowPostDeletion() throws Exception {
        AtomicBoolean acknowledged = new AtomicBoolean(false);

        consumer.onMessage(outboxMessage(Map.of(
                "entity", "knowpost",
                "op", "delete",
                "event", "KNOWPOST_DELETED",
                "id", 123L)), () -> acknowledged.set(true));

        assertThat(indexService.removedPostIds).containsExactly(123L);
        assertThat(acknowledged).isTrue();
    }

    private String outboxMessage(Map<String, Object> payload) throws Exception {
        String payloadJson = objectMapper.writeValueAsString(payload);
        return objectMapper.writeValueAsString(Map.of(
                "table", "outbox",
                "type", "INSERT",
                "data", List.of(Map.of("payload", payloadJson))));
    }

    private static final class RecordingRagIndexService extends RagIndexService {
        private final List<Long> indexedPostIds = new java.util.ArrayList<>();
        private final List<Long> removedPostIds = new java.util.ArrayList<>();

        private RecordingRagIndexService() {
            super(null, null, null, null);
        }

        @Override
        public RagIndexResult reindexSinglePost(long postId) {
            indexedPostIds.add(postId);
            return RagIndexResult.ready(3);
        }

        @Override
        public void removeIndex(long postId) {
            removedPostIds.add(postId);
        }
    }
}
