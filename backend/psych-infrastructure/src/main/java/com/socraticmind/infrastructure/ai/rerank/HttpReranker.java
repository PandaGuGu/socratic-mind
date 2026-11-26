package com.socraticmind.infrastructure.ai.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.common.port.RerankPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 远程 cross-encoder 重排（Jina / Cohere / TEI / Infinity 的 {@code /rerank} 协议）。
 *
 * 三处刻意的取舍：
 * 1. **超时 8 秒，超时就放弃**。重排是"锦上添花"的一层，不能因为它把整轮问答拖到不可用；
 * 2. **失败不抛异常，返回 -1**：调用方按"未判断"处理，退回召回顺序。宁可排得差，不可答不出；
 * 3. **不重试**：同一轮里重试只会让延迟翻倍，而失败原因（服务没起、模型没拉、鉴权错）
 *    重试都不会变。要修的是部署，不是调用方。
 *
 * blendWithPrior = false：cross-encoder 直接看"问题 + 段落"的交互，分数是校准过的，
 * 再拿 RRF 名次去掺，等于用粗排序污染细排序。
 */
@Component
@ConditionalOnProperty(name = "psych.ai.rerank.provider", havingValue = "http")
public class HttpReranker implements RerankPort {

    private static final Logger log = LoggerFactory.getLogger(HttpReranker.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final String url;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public HttpReranker(@Value("${psych.ai.rerank.url:}") String url,
                        @Value("${psych.ai.rerank.api-key:}") String apiKey,
                        @Value("${psych.ai.rerank.model:bge-reranker-v2-m3}") String model,
                        @Value("${psych.ai.rerank.timeout-seconds:8}") long timeoutSeconds) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("psych.ai.rerank.provider=http 时必须配置 psych.ai.rerank.url");
        }
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @Override
    public String modelId() {
        return "rerank:" + model;
    }

    @Override
    public boolean active() {
        return true;
    }

    @Override
    public boolean blendWithPrior() {
        return false;
    }

    @Override
    public double[] score(String query, List<Passage> passages) {
        double[] out = new double[passages.size()];
        java.util.Arrays.fill(out, -1);
        if (passages.isEmpty()) return out;
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "query", query,
                    "documents", passages.stream().map(Passage::text).toList(),
                    "top_n", passages.size()));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("重排服务返回 {}，本轮退回召回顺序", response.statusCode());
                return out;
            }
            JsonNode results = mapper.readTree(response.body()).path("results");
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index >= 0 && index < out.length) {
                    out[index] = item.path("relevance_score").asDouble(-1);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("重排调用失败（{}），本轮退回召回顺序", e.getClass().getSimpleName());
            return out;
        }
    }
}
