package com.socraticmind.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 模型提供方配置。provider=stub 时不需要任何网络与密钥。 */
@ConfigurationProperties(prefix = "psych.ai")
public class AiProperties {

    /** stub | http */
    private String provider = "stub";

    /** OpenAI 兼容端点，例如 https://api.deepseek.com/v1 或 https://dashscope.aliyuncs.com/compatible-mode/v1 */
    private String baseUrl = "";

    private String apiKey = "";

    private String chatModel = "";

    private String embeddingModel = "";

    /** 桩向量的维度；真实模型由接口返回决定，此项仅对 stub 生效 */
    private int embeddingDimension = 256;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
}
