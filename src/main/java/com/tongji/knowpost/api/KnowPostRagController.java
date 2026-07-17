package com.tongji.knowpost.api;

import com.tongji.auth.token.JwtService;
import com.tongji.knowpost.api.dto.RagIndexStatusResponse;
import com.tongji.knowpost.api.dto.RagSseEventPayload;
import com.tongji.llm.rag.RagIndexResult;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.llm.rag.RagIndexStatus;
import com.tongji.llm.rag.RagQueryService;
import com.tongji.llm.rag.RagRateLimitService;
import com.tongji.llm.rag.RagStreamException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/knowposts")
@Validated
@RequiredArgsConstructor
public class KnowPostRagController {
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final RagIndexService indexService;
    private final RagQueryService ragQueryService;
    private final JwtService jwtService;
    private final RagRateLimitService rateLimitService;

    /**
     * 单篇知文 RAG 问答（WebFlux + Flux 流式输出）。
     * 示例：GET /api/v1/knowposts/{id}/qa/stream?question=...&topK=5&maxTokens=1024
     *
     * @param id 当前问答所属的知文 ID，只允许正数
     * @param question 用户问题，限制长度以控制无效请求和 Prompt 成本
     * @param topK 最终提供给模型的相关切片数量
     * @param maxTokens 模型本次最多生成的 Token 数，不包含输入 Token
     * @return 模型回答的文本增量流；空召回时返回固定拒答
     */
    @GetMapping(value = "/{id}/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> qaStream(
            @PathVariable("id") @Positive(message = "知文ID必须大于0") long id,
            @RequestParam("question")
            @NotBlank(message = "问题不能为空")
            @Size(max = 500, message = "问题不能超过500个字符") String question,
            @RequestParam(value = "topK", defaultValue = "5")
            @Min(value = 1, message = "topK不能小于1")
            @Max(value = 10, message = "topK不能大于10") int topK,
            @RequestParam(value = "maxTokens", defaultValue = "1024")
            @Min(value = 64, message = "maxTokens不能小于64")
            @Max(value = 2048, message = "maxTokens不能大于2048") int maxTokens,
            @RequestHeader(value = "X-Request-ID", required = false) String incomingRequestId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = resolveRequestId(incomingRequestId);
        response.setHeader("X-Request-ID", requestId);
        Long userId = jwt == null ? null : jwtService.extractUserId(jwt);
        RagRateLimitService.Lease lease = rateLimitService.acquire(userId, request.getRemoteAddr());
        return ragQueryService.streamAnswerFlux(
                        id, question, topK, maxTokens, requestId, lease.principalType())
                // complete、error、cancel 三种终止信号都会释放并发许可。
                .doFinally(signalType -> lease.close());
    }

    /**
     * 兼容版结构化 SSE。前端迁移完成前保留旧接口，避免一次改动同时破坏现有问答页面。
     *
     * <p>事件顺序为 meta -> delta* -> done；任一阶段失败时改为 meta -> ... -> error。</p>
     */
    @GetMapping(value = "/{id}/qa/stream/v2", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<RagSseEventPayload>> qaStreamV2(
            @PathVariable("id") @Positive(message = "知文ID必须大于0") long id,
            @RequestParam("question")
            @NotBlank(message = "问题不能为空")
            @Size(max = 500, message = "问题不能超过500个字符") String question,
            @RequestParam(value = "topK", defaultValue = "5")
            @Min(value = 1, message = "topK不能小于1")
            @Max(value = 10, message = "topK不能大于10") int topK,
            @RequestParam(value = "maxTokens", defaultValue = "1024")
            @Min(value = 64, message = "maxTokens不能小于64")
            @Max(value = 2048, message = "maxTokens不能大于2048") int maxTokens,
            @RequestHeader(value = "X-Request-ID", required = false) String incomingRequestId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request,
            HttpServletResponse response) {
        String requestId = resolveRequestId(incomingRequestId);
        response.setHeader("X-Request-ID", requestId);
        Long userId = jwt == null ? null : jwtService.extractUserId(jwt);
        RagRateLimitService.Lease lease = rateLimitService.acquire(userId, request.getRemoteAddr());
        AtomicBoolean failed = new AtomicBoolean(false);

        Flux<ServerSentEvent<RagSseEventPayload>> answerEvents = ragQueryService
                .streamAnswerFluxV2(id, question, topK, maxTokens, requestId, lease.principalType())
                .map(delta -> event("delta", RagSseEventPayload.delta(delta)))
                .onErrorResume(error -> {
                    failed.set(true);
                    RagSseEventPayload payload;
                    if (error instanceof RagStreamException streamError) {
                        payload = RagSseEventPayload.error(
                                streamError.getCode(), streamError.getMessage(), streamError.isRetryable());
                    } else {
                        // 供应商原始错误只进服务端日志，SSE 仅返回稳定用户文案。
                        payload = RagSseEventPayload.error(
                                "RAG_GENERATION_UNAVAILABLE",
                                "AI问答服务暂时不可用，请稍后重试。",
                                true);
                    }
                    return Flux.just(event("error", payload));
                })
                .doFinally(signalType -> lease.close());

        Flux<ServerSentEvent<RagSseEventPayload>> doneEvent = Flux.defer(() ->
                failed.get()
                        ? Flux.empty()
                        : Flux.just(event("done", RagSseEventPayload.done("STOP"))));
        return Flux.concat(
                Flux.just(event("meta", RagSseEventPayload.meta(requestId))),
                answerEvents,
                doneEvent);
    }

    /**
     * 作者手动触发单篇索引重建，返回结构化状态而不是含义不明确的整数 0。
     */
    @PostMapping("/{id}/rag/reindex")
    public RagIndexStatusResponse reindex(@PathVariable("id") long id,
                                          @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        RagIndexResult result = indexService.reindexSinglePostForOwner(id, userId);
        if (result.status() == RagIndexStatus.SKIPPED) {
            return new RagIndexStatusResponse(String.valueOf(id), result.status().name(),
                    result.chunkCount(), null, result.message());
        }
        return indexService.getIndexStatus(id, userId);
    }

    /**
     * 发布后状态查询接口；公开知文允许匿名查询，非公开知文仅作者可查看。
     */
    @GetMapping("/{id}/rag/status")
    public RagIndexStatusResponse status(@PathVariable("id") long id,
                                         @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt == null ? null : jwtService.extractUserId(jwt);
        return indexService.getIndexStatus(id, userId);
    }

    static String resolveRequestId(String incomingRequestId) {
        return incomingRequestId != null && REQUEST_ID_PATTERN.matcher(incomingRequestId).matches()
                ? incomingRequestId
                : UUID.randomUUID().toString();
    }

    private static ServerSentEvent<RagSseEventPayload> event(String name, RagSseEventPayload payload) {
        return ServerSentEvent.<RagSseEventPayload>builder()
                .event(name)
                .data(payload)
                .build();
    }
}
