package com.tongji.knowpost.api;

import com.tongji.auth.token.JwtService;
import com.tongji.knowpost.api.dto.RagIndexStatusResponse;
import com.tongji.llm.rag.RagIndexResult;
import com.tongji.llm.rag.RagIndexService;
import com.tongji.llm.rag.RagIndexStatus;
import com.tongji.llm.rag.RagQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/knowposts")
@Validated
@RequiredArgsConstructor
public class KnowPostRagController {

    private final RagIndexService indexService;
    private final RagQueryService ragQueryService;
    private final JwtService jwtService;

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
            @Max(value = 2048, message = "maxTokens不能大于2048") int maxTokens) {
        return ragQueryService.streamAnswerFlux(id, question, topK, maxTokens);
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
}
