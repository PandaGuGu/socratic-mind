package com.socraticmind.infrastructure.ai;

import com.socraticmind.common.port.ChatPort;
import com.socraticmind.common.port.EmbeddingPort;
import com.socraticmind.infrastructure.guard.GuardAuditLog;
import com.socraticmind.infrastructure.guard.GuardedChatPort;
import com.socraticmind.infrastructure.guard.OutputPolicyStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型装配。对话与向量的提供方可以不同：
 * DeepSeek 只有 chat、没有 embeddings，因此常见形态是「chat=http + embedding=stub」。
 *
 * provider=http 时要求 baseUrl/apiKey/model 齐全，宁可启动失败也不要静默降级——
 * 静默降级会让"测试通过"变成假象。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    @Bean
    EmbeddingPort embeddingPort(AiProperties props, ModelCallLog callLog) {
        String provider = resolve(props.getEmbeddingProvider(), props.getProvider());
        if (!"http".equalsIgnoreCase(provider)) {
            return new MeteredEmbeddingPort(new HashEmbeddingAdapter(props.getEmbeddingDimension()), callLog);
        }
        String baseUrl = resolve(props.getEmbeddingBaseUrl(), props.getBaseUrl());
        String apiKey = resolve(props.getEmbeddingApiKey(), props.getApiKey());
        require(baseUrl, "psych.ai.embedding-base-url");
        require(apiKey, "psych.ai.embedding-api-key");
        require(props.getEmbeddingModel(), "psych.ai.embedding-model");
        return new MeteredEmbeddingPort(
                new HttpEmbeddingAdapter(baseUrl, apiKey, props.getEmbeddingModel()), callLog);
    }

    @Bean
    ChatPort chatPort(AiProperties props, OutputPolicyStore policyStore, GuardAuditLog auditLog,
                      ModelCallLog callLog) {
        String provider = resolve(props.getChatProvider(), props.getProvider());
        ChatPort delegate;
        if (!"http".equalsIgnoreCase(provider)) {
            delegate = new StubChatAdapter();
        } else {
            require(props.getBaseUrl(), "psych.ai.base-url");
            require(props.getApiKey(), "psych.ai.api-key");
            require(props.getChatModel(), "psych.ai.chat-model");
            delegate = new HttpChatAdapter(props.getBaseUrl(), props.getApiKey(), props.getChatModel(),
                    props.getTemperature(), props.isThinkingDisabled(), props.isNoThink(),
                    props.getReadTimeoutSeconds());
        }
        // 先计时（含守卫触发的重写），再进输出守卫：所有模型调用统一过同一道闸。
        return new GuardedChatPort(new MeteredChatPort(delegate, callLog), policyStore, auditLog);
    }

    private static String resolve(String specific, String fallback) {
        return specific == null || specific.isBlank() ? fallback : specific;
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("psych.ai 使用 http 提供方时必须配置 " + key);
        }
    }
}
