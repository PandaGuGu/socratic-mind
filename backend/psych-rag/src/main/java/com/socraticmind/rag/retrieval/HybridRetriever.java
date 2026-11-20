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

/**
 * 混合检索 + RRF 融合。
 *
 * 两路各自取 topK*2 再融合，是刻意的：单路召回不稳定的场景（专业术语 vs 口语表达），
 * 融合比调权重更稳。Reranker 留到有评估数据后再加（docs/01-方案/04 §4.5）。
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
        int perRoute = Math.max(topK, 1) * 2;
        float[] queryVector = embedding.embed(query);

        List<RetrievedChunk> vectorHits = store.searchVector(queryVector, perRoute, null);
        List<RetrievedChunk> textHits = store.searchText(query, perRoute, null);

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
        // RRF 分很粗，同分很常见；用单路向量分和 chunk id 兜底，保证同一语料每次排序一致。
        hits.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            if (byScore != 0) return byScore;
            int byVector = Double.compare(b.vectorScore(), a.vectorScore());
            if (byVector != 0) return byVector;
            return a.chunk().id().compareTo(b.chunk().id());
        });
        return hits.size() > topK ? hits.subList(0, topK) : hits;
    }
}
