package com.socraticmind.rag.retrieval;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.port.RerankPort;
import com.socraticmind.rag.dto.Hit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 检索的第二段：先放大候选池，再重排，最后截断。
 *
 * 顺序是这条链上最容易写错的地方：
 * 1. 召回只取 topK 再重排 = 重排只能在这些里面换位置，救不回被截掉的正确段落，
 *    所以候选池要放大（poolFactor）；放大倍数靠"重排能不能把噪音压下去"来定，
 *    词法重排保守取 3，真模型可以取 5–10；
 * 2. 截断必须发生在重排**之后**。先截再排等于没排；
 * 3. 未判分的条目不能当 0 分。0 分会把它们排到所有低分命中之后，
 *    而"没判"只该保持原相对顺序——两者混用会让 fixture 档的数据看起来比实际差。
 */
@Component
public class RerankingRetriever {

    private final HybridRetriever hybrid;
    private final RerankPort rerank;
    private final int poolFactor;

    public RerankingRetriever(HybridRetriever hybrid, RerankPort rerank,
                              @Value("${psych.ai.rerank.pool-factor:3}") int poolFactor) {
        this.hybrid = hybrid;
        this.rerank = rerank;
        this.poolFactor = Math.max(1, poolFactor);
    }

    public List<Hit> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    public List<Hit> retrieve(String query, int topK, Set<String> scopes) {
        if (!rerank.active()) {
            return hybrid.retrieve(query, topK, scopes);
        }
        int pool = Math.max(topK, 1) * poolFactor;
        List<Hit> candidates = hybrid.retrieve(query, pool, scopes);
        if (candidates.size() <= 1) return candidates;

        List<RerankPort.Passage> passages = new ArrayList<>(candidates.size());
        for (Hit hit : candidates) {
            passages.add(new RerankPort.Passage(hit.chunk().id(), retrievalText(hit.chunk())));
        }
        double[] scores = rerank.score(query, passages);
        if (scores == null || scores.length != candidates.size()) {
            return candidates.size() > topK ? candidates.subList(0, topK) : candidates;
        }

        double maxPrior = candidates.stream().mapToDouble(Hit::score).max().orElse(0);
        List<IndexedHit> ranked = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Hit hit = candidates.get(i);
            double raw = scores[i];
            double scored = Double.isNaN(raw) || raw < 0 ? -1 : raw;
            if (scored < 0) {
                // 未判：保持召回位置，排在所有已判条目之后，但彼此之间不重排。
                ranked.add(new IndexedHit(hit.withRerank(-1), i, false));
                continue;
            }
            double prior = maxPrior > 0 ? hit.score() / maxPrior : 0;
            double combined = rerank.blendWithPrior()
                    ? 0.6 * scored + 0.4 * prior
                    : scored;
            ranked.add(new IndexedHit(hit.withRerank(combined), i, true));
        }

        // 稳定排序：已判在前按分数降序；同一分数、以及未判的条目，都保持原来的召回顺序。
        ranked.sort(Comparator
                .comparing(IndexedHit::judged).reversed()
                .thenComparing(Comparator.comparingDouble(IndexedHit::score).reversed())
                .thenComparingInt(IndexedHit::recallIndex));

        List<Hit> out = new ArrayList<>(Math.min(topK, ranked.size()));
        for (int i = 0; i < ranked.size() && i < topK; i++) {
            out.add(ranked.get(i).hit());
        }
        return out;
    }

    public String modelId() {
        return rerank.modelId();
    }

    public boolean active() {
        return rerank.active();
    }

    /** 与入库时的检索文本保持一致：标题路径 + 章节路径 + 正文。不一致会让重排看的东西和召回看的不一样。 */
    private static String retrievalText(Chunk chunk) {
        return (chunk.titlePath() == null ? "" : chunk.titlePath()) + " "
                + (chunk.sectionPath() == null ? "" : chunk.sectionPath()) + "\n"
                + (chunk.content() == null ? "" : chunk.content());
    }

    private record IndexedHit(Hit hit, int recallIndex, boolean judged) {
        double score() {
            return hit.rerankScore();
        }
    }
}
