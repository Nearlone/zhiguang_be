package com.tongji.llm.rag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
}
