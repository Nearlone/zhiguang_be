package com.tongji.config;

import com.tongji.llm.rag.RagProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AiClientConfigTest {

    @Test
    void mapsRagTimeoutPropertiesToSpringHttpSettings() {
        RagProperties properties = new RagProperties();
        properties.getEmbedding().setConnectTimeout(Duration.ofSeconds(3));
        properties.getEmbedding().setReadTimeout(Duration.ofSeconds(17));

        var settings = AiClientConfig.embeddingHttpSettings(properties);

        // 直接验证传给 requestFactory 的不可变配置，避免测试依赖端口和网络时序。
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(17));
    }
}
