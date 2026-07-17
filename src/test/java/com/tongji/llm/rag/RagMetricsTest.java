package com.tongji.llm.rag;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagMetricsTest {

    @Test
    void recordsLowCardinalityRequestRetrievalAndTokenMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagMetrics metrics = new RagMetrics(registry);

        metrics.requestCompleted("success", 120);
        metrics.retrievalCompleted(30, true);
        metrics.modelCalled();
        metrics.firstToken(80);
        metrics.error("generation");
        metrics.providerTokens(new RagTokenUsage(100, 20, 120));

        assertThat(registry.get("rag.requests").tag("status", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rag.retrieval.empty").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rag.model.calls").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rag.errors").tag("stage", "generation").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("rag.provider.tokens").summary().totalAmount()).isEqualTo(120.0);
    }
}
