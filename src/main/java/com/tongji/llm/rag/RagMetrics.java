package com.tongji.llm.rag;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * RAG 运行指标的统一入口。
 *
 * <p>所有标签均为有限枚举值，不使用 requestId、postId 或用户 ID，避免监控系统出现高基数。</p>
 */
@Component
public class RagMetrics {

    private final MeterRegistry registry;

    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    static RagMetrics noop() {
        return new RagMetrics(new SimpleMeterRegistry());
    }

    public void requestCompleted(String status, long totalMs) {
        Counter.builder("rag.requests")
                .tag("status", normalizeTag(status))
                .register(registry)
                .increment();
        Timer.builder("rag.total.duration")
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(Math.max(0L, totalMs)));
    }

    public void retrievalCompleted(long retrievalMs, boolean empty) {
        Timer.builder("rag.retrieval.duration")
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(Math.max(0L, retrievalMs)));
        if (empty) {
            registry.counter("rag.retrieval.empty").increment();
        }
    }

    public void modelCalled() {
        registry.counter("rag.model.calls").increment();
    }

    public void firstToken(long firstTokenMs) {
        Timer.builder("rag.first.token.duration")
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(Math.max(0L, firstTokenMs)));
    }

    public void error(String stage) {
        Counter.builder("rag.errors")
                .tag("stage", normalizeTag(stage))
                .register(registry)
                .increment();
    }

    public void rateLimited(String reason) {
        Counter.builder("rag.rate.limited")
                .tag("reason", normalizeTag(reason))
                .register(registry)
                .increment();
    }

    public void providerTokens(RagTokenUsage usage) {
        if (usage == null) {
            return;
        }
        DistributionSummary.builder("rag.provider.tokens")
                .baseUnit("tokens")
                .register(registry)
                .record(Math.max(0, usage.totalTokens()));
    }

    private static String normalizeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
