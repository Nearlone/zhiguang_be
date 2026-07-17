package com.tongji.llm.rag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * RAG 工程参数：把需要用评测集校准的阈值和外部模型超时放到配置中，避免散落为魔法数字。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    @Valid
    private final Retrieval retrieval = new Retrieval();

    @Valid
    private final Embedding embedding = new Embedding();

    @Valid
    private final RateLimit rateLimit = new RateLimit();

    @Data
    public static class Retrieval {
        /** 低于该余弦相似度的向量不进入混合检索，也不会触发大模型生成。 */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double similarityThreshold = 0.5;
    }

    @Data
    public static class Embedding {
        /** 建立到 Embedding 服务的最长等待时间。 */
        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(5);

        /** Embedding 服务建立连接后返回结果的最长等待时间。 */
        @NotNull
        private Duration readTimeout = Duration.ofSeconds(20);
    }

    @Data
    public static class RateLimit {
        /** 本地排障时可关闭；生产环境应保持开启。 */
        private boolean enabled = true;

        /** 匿名 IP 每分钟最多发起的问答数。 */
        @Min(1)
        private int anonymousPermitsPerMinute = 10;

        /** 登录用户每分钟最多发起的问答数。 */
        @Min(1)
        private int authenticatedPermitsPerMinute = 30;

        /** 同一用户或匿名 IP 同时持有的 SSE 连接上限。 */
        @Min(1)
        private int maxConcurrentPerSubject = 2;

        /** 防止进程异常退出后许可永久泄漏，租期应覆盖一次正常模型调用。 */
        @NotNull
        private Duration concurrencyLease = Duration.ofMinutes(3);

        /** 触发分钟级配额后建议客户端等待的时间。 */
        @NotNull
        private Duration retryAfter = Duration.ofSeconds(60);
    }
}
