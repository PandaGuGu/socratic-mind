package com.socraticmind.common.port;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.EvidenceItem;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.model.RetrievedChunk;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 知识存储端口。检索只允许在"已发布文档"范围内进行：
 * allowedDocumentIds 为 null 表示不过滤；为空集合表示无可用文档，必须返回空。
 */
public interface KnowledgeStore {

    void save(KnowledgeDocument document, List<Chunk> chunks, List<EvidenceItem> evidence);

    void updateDocumentStatus(String documentId, String status);

    List<KnowledgeDocument> findDocuments();

    Optional<KnowledgeDocument> findDocument(String documentId);

    List<RetrievedChunk> searchVector(float[] query, int limit, Set<String> allowedDocumentIds);

    List<RetrievedChunk> searchText(String query, int limit, Set<String> allowedDocumentIds);

    List<Chunk> chunksByIds(Collection<String> ids);

    /** 管理侧读取：返回该文档的全部块（含父块），按章节与序号排序。不参与检索。 */
    List<Chunk> listChunks(String documentId);

    List<EvidenceItem> evidenceOfChunks(Collection<String> chunkIds);

    long countChunks();

    void clear();
}
