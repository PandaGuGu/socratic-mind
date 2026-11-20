package com.socraticmind.rag.evidence;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.Citation;
import com.socraticmind.common.model.EvidenceItem;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.port.EmbeddingPort;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.common.support.TextUtils;
import com.socraticmind.rag.dto.EvidencePack;
import com.socraticmind.rag.dto.Hit;
import com.socraticmind.rag.dto.PackItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 证据组装：命中 → 可引用证据。
 *
 * "证据是否充分"由规则判定：至少一条命中，且（两路都命中，或命中数 >= 3）。
 * 不让模型自己说"够不够"——它总会说够（见 docs/01-方案/05 §5.2.1）。
 */
@Component
public class EvidenceAssembler {

    private static final int MAX_ITEMS = 4;
    private static final int PARENT_CONTEXT_CHARS = 320;

    /**
     * 语义向量的相关性门槛。仅在真实 embedding（非 stub）时启用；
     * 0.55 是在 BGE-M3 + WHO 中文语料上校准的起点，换模型后要重新测。
     */
    private static final double MIN_VECTOR_RELEVANCE = 0.55;

    private final KnowledgeStore store;
    private final boolean semanticVectors;

    public EvidenceAssembler(KnowledgeStore store, EmbeddingPort embedding) {
        this.store = store;
        this.semanticVectors = !embedding.modelId().startsWith("stub");
    }

    public EvidencePack assemble(String query, List<Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return EvidencePack.empty(query, "没有检索到任何知识块");
        }

        List<PackItem> items = new ArrayList<>();
        int number = 0;
        for (Hit hit : hits) {
            if (items.size() >= MAX_ITEMS) break;
            Chunk chunk = hit.chunk();
            List<EvidenceItem> evidences = store.evidenceOfChunks(List.of(chunk.id()));
            EvidenceItem evidence = evidences.isEmpty()
                    ? EvidenceItem.plain("ev_" + chunk.id(), chunk.id(), "pending")
                    : evidences.get(0);

            Optional<KnowledgeDocument> doc = store.findDocument(chunk.documentId());
            String title = doc.map(KnowledgeDocument::title).orElse("未命名资料");
            String source = doc.map(KnowledgeDocument::source).orElse(null);
            String url = doc.map(KnowledgeDocument::sourceUrl).orElse(null);

            String parentContext = null;
            if (chunk.parentId() != null) {
                List<Chunk> parents = store.chunksByIds(List.of(chunk.parentId()));
                if (!parents.isEmpty()) {
                    parentContext = TextUtils.truncate(parents.get(0).content(), PARENT_CONTEXT_CHARS);
                }
            }

            Citation citation = new Citation(++number, evidence.id(), chunk.id(), chunk.documentId(),
                    title, source, chunk.pageStart(), chunk.sectionPath(), url);
            items.add(new PackItem(citation, evidence, chunk, parentContext));
        }

        if (items.isEmpty()) {
            return EvidencePack.empty(query, "候选块无法组装为证据");
        }

        // 只数命中数量是不够的：向量路总会给出"最近"的块，哪怕毫不相关。
        // 词法重合度对口语化问题太苛刻，真实 embedding 下再看一眼语义相似度；
        // 哈希桩不参与，避免"共同词多"被误当成语义相关。
        double bestOverlap = 0;
        for (PackItem item : items) {
            bestOverlap = Math.max(bestOverlap, TextUtils.lexicalScore(query,
                    item.chunk().titlePath() + " " + item.chunk().sectionPath(), item.chunk().content()));
        }
        Set<String> itemIds = items.stream().map(item -> item.chunk().id()).collect(Collectors.toSet());
        double bestVector = hits.stream()
                .filter(h -> itemIds.contains(h.chunk().id()))
                .mapToDouble(Hit::vectorScore)
                .max().orElse(0);
        boolean dualRoute = hits.stream().anyMatch(h -> "both".equals(h.via()));
        boolean relevant = bestOverlap >= 0.12
                || (semanticVectors && bestVector >= MIN_VECTOR_RELEVANCE);
        boolean sufficient = relevant && (dualRoute || items.size() >= 2);

        String reason = sufficient
                ? "命中 " + items.size() + " 条，词法 " + round(bestOverlap) + "，向量 " + round(bestVector)
                        + "，双路一致=" + dualRoute
                : "相关性不足（词法 " + round(bestOverlap) + "，向量 " + round(bestVector)
                        + "，命中 " + items.size() + " 条），按保守策略降级";

        return new EvidencePack(query, items, sufficient, reason);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
