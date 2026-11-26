package com.socraticmind.rag.retrieval;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.RetrievedChunk;
import com.socraticmind.common.port.EmbeddingPort;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.rag.dto.Hit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索 + RRF 融合。
 *
 * 两路各自取 topK*2 再融合，是刻意的：单路召回不稳定的场景（专业术语 vs 口语表达），
 * 融合比调权重更稳。排序不在这里做——见 {@link RerankingRetriever}。
 *
 * scopes 非 null 时，先解析成"可见文档 id 集合"再进检索。
 * 权限必须在检索之前生效：先取回来再过滤，会让"取过什么"本身成为可观测的泄露。
 */
@Component
public class HybridRetriever {

    private static final int RRF_K = 60;

    private final KnowledgeStore store;
    private final EmbeddingPort embedding;

    public HybridRetriever(KnowledgeStore store, EmbeddingPort embedding) {
        this.store = store;
        this.embedding = embedding;
    }

    public List<Hit> retrieve(String query, int topK) {
        return retrieve(query, topK, null);
    }

    public List<Hit> retrieve(String query, int topK, Set<String> scopes) {
        int perRoute = Math.max(topK, 1) * 2;
        float[] queryVector = embedding.embed(query);

        Set<String> allowed = scopes == null ? null : Set.copyOf(store.findVisibleDocumentIds(scopes));

        List<RetrievedChunk> vectorHits = store.searchVector(queryVector, perRoute, allowed);
        List<RetrievedChunk> textHits = store.searchText(query, perRoute, allowed);

        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, Chunk> chunks = new LinkedHashMap<>();
        Map<String, String[]> via = new LinkedHashMap<>();
        Map<String, Double> vectorScores = new LinkedHashMap<>();
        Map<String, Double> textScores = new LinkedHashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            RetrievedChunk rc = vectorHits.get(i);
            fused.merge(rc.chunk().id(), 1.0 / (RRF_K + i + 1), Double::sum);
            chunks.putIfAbsent(rc.chunk().id(), rc.chunk());
            via.computeIfAbsent(rc.chunk().id(), k -> new String[]{"", ""})[0] = "vector";
            vectorScores.merge(rc.chunk().id(), rc.score(), Math::max);
        }
        for (int i = 0; i < textHits.size(); i++) {
            RetrievedChunk rc = textHits.get(i);
            fused.merge(rc.chunk().id(), 1.0 / (RRF_K + i + 1), Double::sum);
            chunks.putIfAbsent(rc.chunk().id(), rc.chunk());
            via.computeIfAbsent(rc.chunk().id(), k -> new String[]{"", ""})[1] = "text";
            textScores.merge(rc.chunk().id(), rc.score(), Math::max);
        }

        List<Hit> hits = new ArrayList<>();
        fused.forEach((id, score) -> {
            String[] routes = via.get(id);
            String path = routes[0].isEmpty() ? "text" : routes[1].isEmpty() ? "vector" : "both";
            hits.add(new Hit(chunks.get(id), score, path,
                    vectorScores.getOrDefault(id, 0.0), textScores.getOrDefault(id, 0.0)));
        });
        // RRF 分很粗，同分很常见。兜底排序必须用**内容**而不用 chunk id：
        // id 是随机生成的，重建语料后就变了，用它兜底会让"同一份语料、同样的查询"在不同次启动里
        // 排出不同顺序——看起来像检索不稳定，实际是排序键选错了。而这个抖动会直接毁掉重排的回归测试。
        hits.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            if (byScore != 0) return byScore;
            int byVector = Double.compare(b.vectorScore(), a.vectorScore());
            if (byVector != 0) return byVector;
            return stableKey(a.chunk()).compareTo(stableKey(b.chunk()));
        });
        return hits.size() > topK ? hits.subList(0, topK) : hits;
    }

    private static String stableKey(Chunk chunk) {
        String hash = com.socraticmind.common.support.Hashes.shortSha(
                (chunk.titlePath() == null ? "" : chunk.titlePath()) + "|"
                        + (chunk.sectionPath() == null ? "" : chunk.sectionPath()) + "|" + chunk.content());
        return hash == null ? chunk.id() : hash;
    }
}
