package com.socraticmind.knowledge.service;

import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.common.support.Hashes;
import com.socraticmind.common.support.Ids;
import com.socraticmind.knowledge.dto.IngestCommand;
import com.socraticmind.knowledge.dto.IngestResult;
import com.socraticmind.knowledge.dto.SyncResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 文档同步：外部系统改了什么，索引就跟着改什么。
 *
 * 三条不变量，缺一条就会出"答案看起来正常但是错的"：
 * 1. **外部键唯一**：同一篇资料只有一条文档记录，改标题不会变成新文档；
 * 2. **内容哈希决定要不要重做**：哈希没变就不切分、不向量化。每晚全量重建索引的成本，
 *    大多数团队撑不过一个月——不是因为钱，是因为每次重建都会让新语料延迟生效几小时；
 * 3. **改版即重审**：内容变了，状态一律回到 REVIEWING。审核签字签的是那一版文本，
 *    不是这篇文档。自动继承审核结论，等于让一次未审的改动借用上一次的签字。
 */
@Service
public class DocumentSyncService {

    private final KnowledgeStore store;
    private final IngestionService ingestion;

    public DocumentSyncService(KnowledgeStore store, IngestionService ingestion) {
        this.store = store;
        this.ingestion = ingestion;
    }

    public SyncResult sync(String externalKey, IngestCommand cmd) {
        if (externalKey == null || externalKey.isBlank()) {
            throw new IllegalArgumentException("externalKey 不能为空：没有它就无法判断是同一篇资料");
        }
        if (cmd.content() == null || cmd.content().isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }

        String hash = Hashes.sha256Hex(cmd.content());
        Optional<KnowledgeDocument> found = store.findByExternalKey(externalKey);

        if (found.isEmpty()) {
            IngestResult r = ingestion.ingestAs(cmd, Ids.of("doc"), externalKey, hash, null);
            return new SyncResult(r.documentId(), "created", true, true,
                    r.status(), versionOf(r.versionId()), r.childChunks());
        }

        KnowledgeDocument doc = found.get();
        if (!doc.deleted() && hash != null && hash.equals(doc.contentHash())) {
            // 未变：一个字节都不写。返回 unchanged 而不是"假装更新了"，
            // 因为同步任务的成功率统计要能区分"做了"和"没必要做"。
            return new SyncResult(doc.id(), "unchanged", false, false, doc.status(), doc.version(), 0);
        }

        // 变了：先摘掉旧块，再按同一文档 id 写新版本。顺序不能反——
        // 反过来会出现"新旧块同时在索引里"的窗口，而检索正好在窗口里跑。
        store.deleteChunksOf(doc.id());
        IngestResult r = ingestion.ingestAs(cmd, doc.id(), externalKey, hash, KnowledgeDocument.REVIEWING);
        return new SyncResult(doc.id(), "updated", true, true,
                r.status(), versionOf(r.versionId()), r.childChunks());
    }

    /** 外部系统删了这篇资料：软删除，立刻从检索范围消失，块先留着便于回滚。 */
    public Optional<SyncResult> retire(String externalKey) {
        Optional<KnowledgeDocument> found = store.findByExternalKey(externalKey);
        if (found.isEmpty()) return Optional.empty();
        KnowledgeDocument doc = found.get();
        store.markDeleted(doc.id(), Instant.now());
        return Optional.of(new SyncResult(doc.id(), "retired", true, false,
                KnowledgeDocument.ARCHIVED, doc.version(), 0));
    }

    /** 物理清理：只清软删除超过 retainDays 天的。默认 7 天，够回滚一次误删。 */
    public int purge(int retainDays) {
        int days = Math.max(0, retainDays);
        return store.purgeDeletedBefore(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    static String versionOf(String versionId) {
        if (versionId == null) return null;
        int idx = versionId.lastIndexOf('_');
        return idx >= 0 && idx < versionId.length() - 1 ? versionId.substring(idx + 1) : versionId;
    }
}
