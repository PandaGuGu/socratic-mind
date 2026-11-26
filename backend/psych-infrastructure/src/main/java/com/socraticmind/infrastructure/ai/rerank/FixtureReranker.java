package com.socraticmind.infrastructure.ai.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.common.port.RerankPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定分重排：分数来自一份离线判好的 JSON（{@code psych.ai.rerank.fixture}）。
 *
 * 用途不是上线，而是**在没有可用的重排模型时，仍然能把重排这一层验证完**：
 * 管道接得对不对、混合权重会不会把好段落挤掉、topK 截断发生在重排前还是后——
 * 这些问题不需要模型也能答，只需要一份"标准答案"。
 *
 * 这份分数的来源要写清楚：当前仓库里的 agent-v1.json 是**子代理扮演 cross-encoder**
 * 逐条判出来的，不是 bge-reranker 的真实输出。等真模型接上，换成 http 档，
 * 这份文件退化成回归测试的基准，不再参与线上排序。
 *
 * blendWithPrior = false：这是"模型分"，不该再拿名次去校准它，否则测的就不是重排了。
 */
@Component
@ConditionalOnProperty(name = "psych.ai.rerank.provider", havingValue = "fixture")
public class FixtureReranker implements RerankPort {

    private static final Logger log = LoggerFactory.getLogger(FixtureReranker.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, Double>> scores;
    private final String modelId;

    public FixtureReranker(@Value("${psych.ai.rerank.fixture:classpath:rerank/agent-v1.json}") String location) {
        Resource resource = location.startsWith("classpath:")
                ? new ClassPathResource(location.substring("classpath:".length()))
                : new FileSystemResource(location);
        if (!resource.exists()) {
            // 配置指了 fixture 档却没有文件，是配置错误，不是运行期可降级的情况：
            // 静默变成"没有重排"会让所有对照实验失去意义，而且没人会发现。
            throw new IllegalStateException("重排分数文件不存在：" + location);
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = mapper.readTree(in);
            this.modelId = root.path("model").asText("fixture");
            Map<String, Map<String, Double>> parsed = new HashMap<>();
            JsonNode queries = root.path("queries");
            queries.fieldNames().forEachRemaining(query -> {
                Map<String, Double> perChunk = new HashMap<>();
                queries.path(query).fields().forEachRemaining(e ->
                        perChunk.put(e.getKey(), e.getValue().asDouble(-1)));
                parsed.put(query, perChunk);
            });
            this.scores = Map.copyOf(parsed);
            log.info("重排分数已加载：{} 个查询（model={}）", scores.size(), modelId);
        } catch (Exception e) {
            throw new IllegalStateException("重排分数文件解析失败：" + location, e);
        }
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public boolean active() {
        return !scores.isEmpty();
    }

    @Override
    public boolean blendWithPrior() {
        return false;
    }

    @Override
    public double[] score(String query, List<Passage> passages) {
        double[] out = new double[passages.size()];
        Map<String, Double> perChunk = lookup(query);
        for (int i = 0; i < passages.size(); i++) {
            // 先按内容键查（跨重建稳定），查不到再退回 id（手写 fixture 时可读性更好）。
            String key = com.socraticmind.common.support.Hashes.shortSha(passages.get(i).text());
            Double score = key == null ? null : perChunk.get(key);
            if (score == null) score = perChunk.get(passages.get(i).id());
            out[i] = score == null ? -1.0 : score;
        }
        return out;
    }

    /**
     * 三级回退：整句 → 最长前缀 → 去标点后整句。
     *
     * 前缀这一级是必需的：检索前会做同义词改写（"睡不着" 后面追加 "失眠 睡眠"），
     * 标注文件按**原始问题**记录。不做前缀匹配，凡是触发过改写的查询都会整组查不到分，
     * 然后静默退回原顺序——看起来像"重排没效果"，实际是键对不上。
     */
    private Map<String, Double> lookup(String query) {
        if (query == null) return Map.of();
        Map<String, Double> hit = scores.get(query);
        if (hit != null) return hit;

        String longest = null;
        for (String key : scores.keySet()) {
            if (query.startsWith(key) && (longest == null || key.length() > longest.length())) {
                longest = key;
            }
        }
        if (longest != null) return scores.get(longest);

        String loose = loose(query);
        for (Map.Entry<String, Map<String, Double>> e : scores.entrySet()) {
            if (loose(e.getKey()).equals(loose)) return e.getValue();
        }
        return Map.of();
    }

    private static String loose(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\p{Punct}，。！？；：]+", "");
    }
}
