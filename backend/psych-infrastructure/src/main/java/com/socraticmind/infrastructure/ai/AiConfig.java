package com.socraticmind.infrastructure.ai;

import com.socraticmind.common.port.ChatPort;
import com.socraticmind.common.port.EmbeddingPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型装配。provider=http 时要求 baseUrl/apiKey/model 齐全，宁可启动失败也不要静默降级——
 * 静默降级会让"测试通过"变成假象。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    @Bean
    EmbeddingPort embeddingPort(AiProperties props) {
        if (!"http".equalsIgnoreCase(props.getProvider())) {
            return new HashEmbeddingAdapter(props.getEmbeddingDimension());
        }
        require(props.getBaseUrl(), "psych.ai.base-url");
        require(props.getApiKey(), "psych.ai.api-key");
        require(props.getEmbeddingModel(), "psych.ai.embedding-model");
        return new HttpEmbeddingAdapter(props.getBaseUrl(), props.getApiKey(), props.getEmbeddingModel());
    }

    @Bean
    ChatPort chatPort(AiProperties props) {
        if (!"http".equalsIgnoreCase(props.getProvider())) {
            return new StubChatAdapter();
        }
        require(props.getBaseUrl(), "psych.ai.base-url");
        require(props.getApiKey(), "psych.ai.api-key");
        require(props.getChatModel(), "psych.ai.chat-model");
        return new HttpChatAdapter(props.getBaseUrl(), props.getApiKey(), props.getChatModel());
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("psych.ai.provider=http 时必须配置 " + key);
        }
    }
}
