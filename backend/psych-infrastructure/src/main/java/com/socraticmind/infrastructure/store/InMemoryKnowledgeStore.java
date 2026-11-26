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
        // 与 PG 档同一条规则：重新入库不覆盖已有 ACL（acl 为空表示"沿用"）。
        KnowledgeDocument previous = documents.get(document.id());
        KnowledgeDocument toStore = document.aclScope() == null && previous != null
                ? document.withAcl(previous.aclScope())
                : document;
        documents.put(toStore.id(), toStore);
        newChunks.forEach(c -> chunks.put(c.id(), c));
        newEvidence.forEach(e -> evidence.put(e.id(), e));
    }

    @Override
    public void updateDocumentStatus(String documentId, String status) {
        KnowledgeDocument d = documents.get(documentId);
        if (d == null) return;
        documents.put(documentId, new KnowledgeDocument(d.id(), d.collection(), d.title(), d.source(),
                d.sourceUrl(), d.version(), d.language(), status, d.createdAt(),
                d.aclScope(), d.contentHash(), d.externalKey(), d.deletedAt()));
    }

    @Override
    public Optional<KnowledgeDocument> findByExternalKey(String externalKey) {
        if (externalKey == null || externalKey.isBlank()) return Optional.empty();
        return documents.values().stream()
                .filter(d -> externalKey.equals(d.externalKey()))
                .findFirst();
    }

    @Override
    public List<String> findVisibleDocumentIds(Set<String> scopes) {
        return com.socraticmind.common.support.DocumentVisibility.visibleIds(new ArrayList<>(documents.values()), scopes);
    }

    @Override
    public void deleteChunksOf(String documentId) {
        chunks.values().removeIf(c -> c.documentId().equals(documentId));
        evidence.values().removeIf(e -> !chunks.containsKey(e.chunkId()));
    }

    @Override
    public void markDeleted(String documentId, java.time.Instant at) {
        KnowledgeDocument d = documents.get(documentId);
        if (d == null) return;
        documents.put(documentId, new KnowledgeDocument(d.id(), d.collection(), d.title(), d.source(),
                d.sourceUrl(), d.version(), d.language(), KnowledgeDocument.ARCHIVED, d.createdAt(),
                d.aclScope(), d.contentHash(), d.externalKey(),
                at == null ? java.time.Instant.now() : at));
    }

    @Override
    public void updateAcl(String documentId, String aclScope) {
        KnowledgeDocument d = documents.get(documentId);
        if (d == null) return;
        documents.put(documentId, d.withAcl(aclScope));
    }

    @Override
    public int purgeDeletedBefore(java.time.Instant cutoff) {
        java.time.Instant at = cutoff == null ? java.time.Instant.now() : cutoff;
        // 边界用"不晚于"，不用"早于"：purge(0) 的语义是"清掉现在为止所有已删除的"，
        // 而 markDeleted 与 purge 可能落在同一毫秒里，用 isBefore 会把它们漏掉。
        List<String> doomed = documents.values().stream()
                .filter(d -> d.deleted() && !d.deletedAt().isAfter(at))
                .map(KnowledgeDocument::id)
                .toList();
        for (String id : doomed) {
            deleteChunksOf(id);
            documents.remove(id);
        }
        return doomed.size();
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
