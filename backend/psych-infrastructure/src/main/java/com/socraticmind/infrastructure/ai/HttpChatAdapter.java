package com.socraticmind.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.common.port.ChatPort;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** OpenAI 兼容的 /chat/completions 适配器。 */
public class HttpChatAdapter implements ChatPort {

    private final RestClient client;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpChatAdapter(String baseUrl, String apiKey, String model) {
        this.model = model;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        String raw = client.post().uri("/chat/completions").body(body).retrieve().body(String.class);
        try {
            JsonNode root = mapper.readTree(raw);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("解析 chat 响应失败", e);
        }
    }

    @Override
    public String modelId() {
        return "http:" + model;
    }
}
