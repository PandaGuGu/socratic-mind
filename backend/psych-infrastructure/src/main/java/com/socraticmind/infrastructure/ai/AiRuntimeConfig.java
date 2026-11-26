package com.socraticmind.infrastructure.ai;

import java.util.Locale;

/**
 * 后台可改的模型接入配置。
 *
 * mode 只有三种：stub（不联网）、cloud（OpenAI 兼容的云端端点）、local（本机 Ollama）。
 * 向量模型不在这里切：改维度要重建整个知识库，不能靠一个按钮悄悄完成。
 */
public record AiRuntimeConfig(
        String mode,
        String baseUrl,
        String chatModel,
        String apiKey,
        double temperature,
        boolean noThink,
        boolean thinkingDisabled,
        int readTimeoutSeconds
) {

    public static final String STUB = "stub";
    public static final String CLOUD = "cloud";
    public static final String LOCAL = "local";
    public static final String LOCAL_BASE_URL = "http://localhost:11434/v1";

    public AiRuntimeConfig {
        mode = normalizeMode(mode);
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        chatModel = chatModel == null ? "" : chatModel.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        if (temperature < 0 || temperature > 2) throw new IllegalArgumentException("温度必须在 0 到 2 之间");
        if (readTimeoutSeconds < 10 || readTimeoutSeconds > 600) {
            throw new IllegalArgumentException("读取超时必须在 10 到 600 秒之间");
        }
    }

    public static String normalizeMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case CLOUD, LOCAL, STUB -> value;
            default -> STUB;
        };
    }

    /** 从启动参数/环境变量推出的一份配置，作为页面默认值。 */
    public static AiRuntimeConfig from(AiProperties props) {
        boolean http = "http".equalsIgnoreCase(props.getProvider())
                || "http".equalsIgnoreCase(props.getChatProvider());
        if (!http) {
            return new AiRuntimeConfig(STUB, "", "stub", "", props.getTemperature(),
                    props.isNoThink(), props.isThinkingDisabled(), props.getReadTimeoutSeconds());
        }
        String base = props.getBaseUrl() == null ? "" : props.getBaseUrl();
        String mode = isLocal(base) ? LOCAL : CLOUD;
        return new AiRuntimeConfig(mode, base, props.getChatModel(), props.getApiKey(),
                props.getTemperature(), props.isNoThink(), props.isThinkingDisabled(),
                props.getReadTimeoutSeconds());
    }

    public static boolean isLocal(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        return value.contains("localhost") || value.contains("127.0.0.1") || value.contains("11434");
    }

    public boolean hasApiKey() {
        return !apiKey.isBlank();
    }

    /** 换新配置时，页面留空的密钥沿用已保存的那一份。 */
    public AiRuntimeConfig withFallbackKey(String savedKey) {
        if (hasApiKey() || savedKey == null || savedKey.isBlank()) return this;
        return new AiRuntimeConfig(mode, baseUrl, chatModel, savedKey, temperature,
                noThink, thinkingDisabled, readTimeoutSeconds);
    }

    public AiRuntimeConfig withoutKey() {
        return new AiRuntimeConfig(mode, baseUrl, chatModel, "", temperature,
                noThink, thinkingDisabled, readTimeoutSeconds);
    }

    public void requireComplete() {
        if (STUB.equals(mode)) return;
        if (baseUrl.isBlank()) throw new IllegalArgumentException("需要填写 Base URL");
        if (chatModel.isBlank()) throw new IllegalArgumentException("需要填写模型名");
        if (apiKey.isBlank()) throw new IllegalArgumentException("需要填写 API Key（本地 Ollama 可填 ollama）");
    }
}
