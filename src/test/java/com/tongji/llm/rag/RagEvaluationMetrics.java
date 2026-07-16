package com.tongji.llm.rag;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 离线评测指标，不调用真实向量库和模型，可用于汇总人工评测样本的结果。
 */
final class RagEvaluationMetrics {

    // 引用格式由 Prompt 固定为 [postId#position]，例如 [123#2]。
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[([^\\[\\]\\s]+#\\d+)]");

    private RagEvaluationMetrics() {
    }

    static CaseResult evaluate(boolean expectedAnswerable,
                               Set<String> expectedChunkIds,
                               List<RagContext> retrievedContexts,
                               String answer) {
        Set<String> retrievedIds = new HashSet<>();
        for (RagContext context : retrievedContexts) {
            retrievedIds.add(context.chunkId());
        }
        Set<String> citations = extractCitations(answer);

        boolean retrievalHitAtK = expectedChunkIds.stream().anyMatch(retrievedIds::contains);
        double citationPrecision = ratio(intersectionSize(citations, retrievedIds), citations.size());
        double evidenceCitationRecall = ratio(intersectionSize(citations, expectedChunkIds), expectedChunkIds.size());
        boolean refused = answer != null && answer.contains(RagMessages.NO_CONTEXT_ANSWER);
        // 可回答样本必须真正产生非空回答；不可回答样本则必须命中统一拒答文案。
        boolean hasAnswer = answer != null && !answer.isBlank();
        boolean refusalCorrect = expectedAnswerable ? hasAnswer && !refused : refused;

        return new CaseResult(expectedAnswerable, retrievalHitAtK, citationPrecision,
                evidenceCitationRecall, refusalCorrect);
    }

    static Summary summarize(List<CaseResult> results) {
        List<CaseResult> answerable = results.stream().filter(CaseResult::expectedAnswerable).toList();
        return new Summary(
                averageBooleans(answerable.stream().map(CaseResult::retrievalHitAtK).toList()),
                averageDoubles(answerable.stream().map(CaseResult::citationPrecision).toList()),
                averageDoubles(answerable.stream().map(CaseResult::evidenceCitationRecall).toList()),
                averageBooleans(results.stream().map(CaseResult::refusalCorrect).toList()));
    }

    static Set<String> extractCitations(String answer) {
        Set<String> citations = new HashSet<>();
        if (answer == null) {
            return citations;
        }
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            citations.add(matcher.group(1));
        }
        return citations;
    }

    private static int intersectionSize(Set<String> left, Set<String> right) {
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return intersection.size();
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double averageBooleans(List<Boolean> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        return values.stream().filter(Boolean::booleanValue).count() / (double) values.size();
    }

    private static double averageDoubles(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    record CaseResult(
            boolean expectedAnswerable,
            boolean retrievalHitAtK,
            double citationPrecision,
            double evidenceCitationRecall,
            boolean refusalCorrect
    ) {
    }

    record Summary(
            double hitAtK,
            double citationPrecision,
            double evidenceCitationRecall,
            double refusalAccuracy
    ) {
    }
}
