package com.socraticmind.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.common.port.EmbeddingPort;
import com.socraticmind.common.support.TextUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** OpenAI 兼容的 /embeddings 适配器。DeepSeek、通义、OpenAI、本地 vLLM 都可用，只改 baseUrl。 */
public class HttpEmbeddingAdapter implements EmbeddingPort {

    private final RestClient client;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile int dimension = -1;

    public HttpEmbeddingAdapter(String baseUrl, String apiKey, String model) {
        this.model = model;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public float[] embed(String text) {
        Map<String, Object> body = Map.of("model", model, "input", text);
        String raw = client.post().uri("/embeddings").body(body).retrieve().body(String.class);
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode arr = root.path("data").path(0).path("embedding");
            if (!arr.isArray() || arr.isEmpty()) {
                // 空向量比报错更危险：写进 pgvector 会得到 "vector must have at least 1 dimension"，
                // 或者更糟——被当成"这条资料没有向量"悄悄排除在检索之外。
                throw new IllegalStateException("embedding 服务返回了空向量（model=" + model
                        + "），响应片段：" + TextUtils.truncate(String.valueOf(raw), 160));
            }
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) v[i] = (float) arr.get(i).asDouble();
            dimension = v.length;
            return v;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 embedding 响应失败", e);
        }
    }

    @Override
    public String modelId() {
        return "http:" + model;
    }

    @Override
    public int dimension() {
        return dimension > 0 ? dimension : -1;
    }

    public List<Float> unusedPlaceholder() {
        return List.of();
    }
}
