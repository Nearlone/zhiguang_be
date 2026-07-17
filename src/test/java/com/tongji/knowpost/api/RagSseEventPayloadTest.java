package com.tongji.knowpost.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.knowpost.api.dto.RagSseEventPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagSseEventPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesOnlyFieldsUsedByCurrentEvent() throws Exception {
        assertThat(objectMapper.writeValueAsString(RagSseEventPayload.meta("req-1")))
                .isEqualTo("{\"requestId\":\"req-1\"}");
        assertThat(objectMapper.writeValueAsString(
                RagSseEventPayload.error("RAG_RETRIEVAL_UNAVAILABLE", "请稍后重试", true)))
                .isEqualTo("{\"code\":\"RAG_RETRIEVAL_UNAVAILABLE\",\"message\":\"请稍后重试\",\"retryable\":true}");
    }

    @Test
    void acceptsOnlyBoundedRequestIdsFromClient() {
        assertThat(KnowPostRagController.resolveRequestId("eval_Q-1")).isEqualTo("eval_Q-1");
        assertThat(KnowPostRagController.resolveRequestId("含中文")).isNotEqualTo("含中文");
        assertThat(KnowPostRagController.resolveRequestId("x".repeat(65))).hasSize(36);
    }
}
