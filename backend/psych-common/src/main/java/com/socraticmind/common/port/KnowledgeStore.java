package com.socraticmind.common.port;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.EvidenceItem;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.model.RetrievedChunk;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 知识存储端口。检索只允许在"已发布、未删除、调用方可见"的文档范围内进行：
 * allowedDocumentIds 为 null 表示不过滤；为空集合表示无可用文档，必须返回空。
 *
 * 生命周期相关的五个方法不是"管理功能"，而是检索正确性的一部分：
 * 一篇已经改了内容的文档如果还能被检索到旧块，系统就会用旧证据回答新问题，
 * 而且回答看起来完全正常——这类错误没有任何指标能自动发现。
 */
public interface KnowledgeStore {

    void save(KnowledgeDocument document, List<Chunk> chunks, List<EvidenceItem> evidence);

    void updateDocumentStatus(String documentId, String status);

    List<KnowledgeDocument> findDocuments();

    Optional<KnowledgeDocument> findDocument(String documentId);

    /** 按外部键找文档：同步的唯一入口，避免"每次同步都新建一篇"。 */
    Optional<KnowledgeDocument> findByExternalKey(String externalKey);

    /**
     * 当前调用方可见的文档 id 集合。
     *
     * @param scopes 调用方持有的范围；null 表示不按范围过滤（仍只含已发布且未删除的）
     */
    List<String> findVisibleDocumentIds(Set<String> scopes);

    /** 删除某文档的全部块与证据（用于内容变更后重建，不删文档本身）。 */
    void deleteChunksOf(String documentId);

    /** 软删除：从检索范围里摘掉，但行还在，可恢复。 */
    void markDeleted(String documentId, Instant at);

    void updateAcl(String documentId, String aclScope);

    /**
     * 物理清理：删除 deleted_at 早于 cutoff 的文档及其块，返回清理掉的文档数。
     * 软删除负责"不再被检索到"，这个方法负责"不再占空间"，两件事分开做，
     * 因为删错了要能捞回来，而捞回来只需要几小时，不需要永久保留。
     */
    int purgeDeletedBefore(Instant cutoff);

    List<RetrievedChunk> searchVector(float[] query, int limit, Set<String> allowedDocumentIds);

    List<RetrievedChunk> searchText(String query, int limit, Set<String> allowedDocumentIds);

    List<Chunk> chunksByIds(Collection<String> ids);

    /** 管理侧读取：返回该文档的全部块（含父块），按章节与序号排序。不参与检索。 */
    List<Chunk> listChunks(String documentId);

    List<EvidenceItem> evidenceOfChunks(Collection<String> chunkIds);

    long countChunks();

    void clear();
}
