package com.socraticmind.infrastructure.store;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.EvidenceItem;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.model.RetrievedChunk;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.common.support.TextUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现：用于开发与测试，保证链路在没有数据库时也能跑通。
 * 语义与 PostgreSQL 实现保持一致——尤其是"只检索已发布文档"这条。
 */
@Component
@ConditionalOnProperty(name = "psych.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryKnowledgeStore implements KnowledgeStore {

    private final Map<String, KnowledgeDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, Chunk> chunks = new ConcurrentHashMap<>();
    private final Map<String, EvidenceItem> evidence = new ConcurrentHashMap<>();

    @Override
    public void save(KnowledgeDocument document, List<Chunk> newChunks, List<EvidenceItem> newEvidence) {
        documents.put(document.id(), document);
        newChunks.forEach(c -> chunks.put(c.id(), c));
        newEvidence.forEach(e -> evidence.put(e.id(), e));
    }

    @Override
    public void updateDocumentStatus(String documentId, String status) {
        KnowledgeDocument d = documents.get(documentId);
        if (d == null) return;
        documents.put(documentId, new KnowledgeDocument(d.id(), d.collection(), d.title(), d.source(),
                d.sourceUrl(), d.version(), d.language(), status, d.createdAt()));
    }

    @Override
    public List<KnowledgeDocument> findDocuments() {
        return documents.values().stream()
                .sorted(Comparator.comparing(KnowledgeDocument::createdAt).reversed())
                .toList();
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(String documentId) {
        return Optional.ofNullable(documents.get(documentId));
    }

    private Set<String> publishedIds() {
        return documents.values().stream()
                .filter(d -> KnowledgeDocument.PUBLISHED.equals(d.status()))
                .map(KnowledgeDocument::id)
                .collect(java.util.stream.Collectors.toSet());
    }

    private boolean allowed(String documentId, Set<String> allowed, Set<String> published) {
        if (!published.contains(documentId)) return false;
        return allowed == null || allowed.contains(documentId);
    }

    @Override
    public List<RetrievedChunk> searchVector(float[] query, int limit, Set<String> allowedDocumentIds) {
        if (query == null || query.length == 0) return List.of();
        Set<String> published = publishedIds();
        List<RetrievedChunk> hits = new ArrayList<>();
        for (Chunk c : chunks.values()) {
            if (c.parent() || c.embedding() == null) continue;
            if (!allowed(c.documentId(), allowedDocumentIds, published)) continue;
            double score = TextUtils.cosine(query, c.embedding());
            if (score > 0) hits.add(new RetrievedChunk(c, score, "vector"));
        }
        hits.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                .thenComparing(h -> h.chunk().id()));
        return hits.stream().limit(limit).toList();
    }

    @Override
    public List<RetrievedChunk> searchText(String query, int limit, Set<String> allowedDocumentIds) {
        Set<String> published = publishedIds();
        List<RetrievedChunk> hits = new ArrayList<>();
        for (Chunk c : chunks.values()) {
            if (c.parent()) continue;
            if (!allowed(c.documentId(), allowedDocumentIds, published)) continue;
            double score = TextUtils.lexicalScore(query, c.titlePath() + " " + c.sectionPath(), c.content());
            if (score > 0) hits.add(new RetrievedChunk(c, score, "text"));
        }
        hits.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                .thenComparing(h -> h.chunk().id()));
        return hits.stream().limit(limit).toList();
    }

    @Override
    public List<Chunk> chunksByIds(Collection<String> ids) {
        Map<String, Chunk> found = new LinkedHashMap<>();
        for (String id : ids) {
            Chunk c = chunks.get(id);
            if (c != null) found.put(id, c);
        }
        return new ArrayList<>(found.values());
    }

    @Override
    public List<Chunk> listChunks(String documentId) {
        return chunks.values().stream()
                .filter(c -> c.documentId().equals(documentId))
                .sorted(Comparator.comparing(Chunk::sectionPath,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Chunk::parent, Comparator.reverseOrder())
                        .thenComparingInt(Chunk::index))
                .toList();
    }

    @Override
    public List<EvidenceItem> evidenceOfChunks(Collection<String> chunkIds) {
        Set<String> set = Set.copyOf(chunkIds);
        return evidence.values().stream().filter(e -> set.contains(e.chunkId())).toList();
    }

    @Override
    public long countChunks() {
        return chunks.values().stream().filter(c -> !c.parent()).count();
    }

    @Override
    public void clear() {
        documents.clear();
        chunks.clear();
        evidence.clear();
    }
}
