package com.socraticmind.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 模型提供方配置。provider=stub 时不需要任何网络与密钥。 */
@ConfigurationProperties(prefix = "psych.ai")
public class AiProperties {

    /** stub | http */
    private String provider = "stub";

    /** 可选：单独指定对话提供方；为空时沿用 provider */
    private String chatProvider = "";

    /** 可选：单独指定向量提供方；为空时沿用 provider。DeepSeek 没有 embeddings 端点，就用 stub 顶着 */
    private String embeddingProvider = "";

    /** OpenAI 兼容端点，例如 https://api.deepseek.com/v1 或 https://dashscope.aliyuncs.com/compatible-mode/v1 */
    private String baseUrl = "";

    private String apiKey = "";

    /** 向量服务可以和对话服务不同源：留空时回退到 baseUrl / apiKey */
    private String embeddingBaseUrl = "";

    private String embeddingApiKey = "";

    private String chatModel = "";

    private String embeddingModel = "";

    /** 桩向量的维度；真实模型由接口返回决定，此项仅对 stub 生效 */
    private int embeddingDimension = 256;

    /** 采样温度。部分模型只接受固定值（例如 kimi-k2.6 关闭思考时只允许 0.6）。 */
    private double temperature = 0.2;

    /** 是否在请求体里显式关闭思考（Kimi K2.6 支持 thinking.type=disabled）。 */
    private boolean thinkingDisabled = false;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getChatProvider() { return chatProvider; }
    public void setChatProvider(String chatProvider) { this.chatProvider = chatProvider; }
    public String getEmbeddingProvider() { return embeddingProvider; }
    public void setEmbeddingProvider(String embeddingProvider) { this.embeddingProvider = embeddingProvider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getEmbeddingBaseUrl() { return embeddingBaseUrl; }
    public void setEmbeddingBaseUrl(String embeddingBaseUrl) { this.embeddingBaseUrl = embeddingBaseUrl; }
    public String getEmbeddingApiKey() { return embeddingApiKey; }
    public void setEmbeddingApiKey(String embeddingApiKey) { this.embeddingApiKey = embeddingApiKey; }
    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public boolean isThinkingDisabled() { return thinkingDisabled; }
    public void setThinkingDisabled(boolean thinkingDisabled) { this.thinkingDisabled = thinkingDisabled; }
}
