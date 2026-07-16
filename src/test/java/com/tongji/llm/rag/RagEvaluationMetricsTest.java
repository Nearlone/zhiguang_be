package com.tongji.llm.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvaluationMetricsTest {

    @Test
    void evaluatesRetrievalCitationsAndRefusalSeparately() {
        RagEvaluationMetrics.CaseResult answerableHit = RagEvaluationMetrics.evaluate(
                true,
                Set.of("123#2", "123#3"),
                List.of(new RagContext("123#2", 2, "RAG", "正文")),
                "答案依据有效来源 [123#2]，但这里还模拟了一个错误引用 [123#9]。");
        RagEvaluationMetrics.CaseResult answerableMiss = RagEvaluationMetrics.evaluate(
                true,
                Set.of("123#5"),
                List.of(new RagContext("123#1", 1, "RAG", "无关正文")),
                "没有引用的普通回答。");
        RagEvaluationMetrics.CaseResult unanswerable = RagEvaluationMetrics.evaluate(
                false,
                Set.of(),
                List.of(),
                RagMessages.NO_CONTEXT_ANSWER);

        assertThat(answerableHit.retrievalHitAtK()).isTrue();
        assertThat(answerableHit.citationPrecision()).isEqualTo(0.5);
        assertThat(answerableHit.evidenceCitationRecall()).isEqualTo(0.5);
        assertThat(answerableHit.refusalCorrect()).isTrue();
        assertThat(answerableMiss.retrievalHitAtK()).isFalse();
        assertThat(unanswerable.refusalCorrect()).isTrue();

        RagEvaluationMetrics.Summary summary = RagEvaluationMetrics.summarize(
                List.of(answerableHit, answerableMiss, unanswerable));
        assertThat(summary.hitAtK()).isEqualTo(0.5);
        assertThat(summary.citationPrecision()).isEqualTo(0.25);
        assertThat(summary.evidenceCitationRecall()).isEqualTo(0.25);
        assertThat(summary.refusalAccuracy()).isEqualTo(1.0);
    }

    @Test
    void extractsOnlyStructuredChunkCitations() {
        assertThat(RagEvaluationMetrics.extractCitations(
                "正确引用 [123#2]，重复引用 [123#2]，普通方括号 [说明] 不算。"))
                .containsExactly("123#2");
    }

    @Test
    void treatsMissingAnswerAsIncorrectForAnswerableCase() {
        // 防止模型异常返回空内容时，被误计为“正确地没有拒答”。
        RagEvaluationMetrics.CaseResult result = RagEvaluationMetrics.evaluate(
                true,
                Set.of("123#2"),
                List.of(new RagContext("123#2", 2, "RAG", "正文")),
                null);

        assertThat(result.refusalCorrect()).isFalse();
    }
}
