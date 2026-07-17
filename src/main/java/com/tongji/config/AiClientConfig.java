package com.tongji.config;

import com.tongji.llm.rag.RagProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring AI 同步 HTTP 客户端的连接策略。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagProperties.class)
public class AiClientConfig {

    @Bean
    RestClientCustomizer aiRestClientTimeoutCustomizer(RagProperties properties) {
        var settings = embeddingHttpSettings(properties);

        // OpenAI Embedding 自动配置会克隆 Spring Boot 的 RestClient.Builder，
        // 因此在这里设置 requestFactory 才能真正约束 DashScope 的网络长尾。
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(settings));
    }

    static ClientHttpRequestFactorySettings embeddingHttpSettings(RagProperties properties) {
        return ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(properties.getEmbedding().getConnectTimeout())
                .withReadTimeout(properties.getEmbedding().getReadTimeout());
    }
}
