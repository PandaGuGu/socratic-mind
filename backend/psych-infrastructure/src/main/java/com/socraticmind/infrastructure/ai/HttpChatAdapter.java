package com.socraticmind.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.common.port.ChatPort;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI 兼容的 /chat/completions 适配器。 */
public class HttpChatAdapter implements ChatPort {

    /** 连接与读取都必须有上限：上游卡住时，不能把 Tomcat 的请求线程一起拖住。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** CBT 的两次生成都很短（一句改写 / 一段总结），限住上限既省钱也防跑飞。 */
    private static final int MAX_TOKENS = 400;

    private final RestClient client;
    private final String model;
    private final double temperature;
    private final boolean thinkingDisabled;
    private final boolean noThink;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpChatAdapter(String baseUrl, String apiKey, String model,
                           double temperature, boolean thinkingDisabled, boolean noThink,
                           int readTimeoutSeconds) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("psych.ai.base-url 未配置：无法调用真实模型");
        }
        this.model = model;
        this.temperature = temperature;
        this.thinkingDisabled = thinkingDisabled;
        this.noThink = noThink;
        HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(Math.max(10, readTimeoutSeconds)));

        this.client = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", MAX_TOKENS);
        // Qwen3 的思考链会吃掉 max_tokens，最后只回一段空内容；/no_think 是它认的开关。
        String user = noThink ? userPrompt + "\n/no_think" : userPrompt;
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", user)
        ));
        // Kimi K2.6 默认开启思考；显式关闭后才是"直接回答"，也才允许 0.6 的温度。
        if (thinkingDisabled) {
            body.put("thinking", Map.of("type", "disabled"));
        }
        String raw = client.post().uri("/chat/completions").body(body).retrieve().body(String.class);
        try {
            JsonNode root = mapper.readTree(raw);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new IllegalStateException("模型返回了空内容");
            }
            return content;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 chat 响应失败", e);
        }
    }

    @Override
    public String modelId() {
        return "http:" + model;
    }
}
