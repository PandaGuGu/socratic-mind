package com.socraticmind.infrastructure.ai.rerank;

import com.socraticmind.common.port.RerankPort;
import com.socraticmind.common.support.TextUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 词法重排：不调模型，但比 RRF 更懂"这一段到底在不在回答这个问题"。
 *
 * RRF 只用了名次，完全丢掉了"命中了几成查询词、这些词是不是连在一起"。
 * 这个实现把这两件事补回来：
 * - coverage：查询词（中文二元组）在候选段落里的覆盖率；
 * - proximity：查询词是否**相邻**出现。"认知 行为"和"认知…（隔 300 字）…行为"不该同分。
 *
 * 它是 blendWithPrior = true 的：词法不足以单独定生死，
 * 所以最终分 = 0.6 × 重排分 + 0.4 × 召回分，避免把"语义相近但用词不同"的正确段落挤掉。
 * 换成真正的 cross-encoder 后应当 blendWithPrior = false —— 模型的判断不需要召回分来校准。
 */
@Component
@ConditionalOnProperty(name = "psych.ai.rerank.provider", havingValue = "lexical")
public class LexicalReranker implements RerankPort {

    private static final double COVERAGE_WEIGHT = 0.7;
    private static final double PROXIMITY_WEIGHT = 0.3;

    @Override
    public String modelId() {
        return "lexical-v1";
    }

    @Override
    public boolean active() {
        return true;
    }

    @Override
    public double[] score(String query, List<Passage> passages) {
        List<String> q = TextUtils.tokens(query);
        double[] out = new double[passages.size()];
        if (q.isEmpty()) {
            java.util.Arrays.fill(out, -1);
            return out;
        }
        for (int i = 0; i < passages.size(); i++) {
            out[i] = scoreOne(q, passages.get(i).text());
        }
        return out;
    }

    static double scoreOne(List<String> queryTokens, String passage) {
        if (passage == null || passage.isBlank()) return 0;
        String norm = TextUtils.normalize(passage);
        int hit = 0;
        for (String token : queryTokens) {
            if (norm.contains(token)) hit++;
        }
        double coverage = hit / (double) queryTokens.size();

        // 相邻对：查询里相邻的两个词，在段落里也相邻出现，才算真正的短语命中。
        // 单字查询没有相邻对，proximity 记为 coverage 本身，避免把短问题判成 0。
        double proximity = coverage;
        if (queryTokens.size() >= 2) {
            int pairs = queryTokens.size() - 1;
            int adjacent = 0;
            for (int i = 0; i < pairs; i++) {
                if (norm.contains(queryTokens.get(i) + queryTokens.get(i + 1))) adjacent++;
            }
            proximity = adjacent / (double) pairs;
        }
        return Math.min(1.0, COVERAGE_WEIGHT * coverage + PROXIMITY_WEIGHT * proximity);
    }
}
