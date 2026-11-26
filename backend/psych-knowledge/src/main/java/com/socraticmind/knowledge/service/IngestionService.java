package com.socraticmind.knowledge.service;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.EvidenceItem;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.port.EmbeddingPort;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.common.support.Hashes;
import com.socraticmind.common.support.Ids;
import com.socraticmind.knowledge.chunk.ParentChildChunker;
import com.socraticmind.knowledge.dto.IngestCommand;
import com.socraticmind.knowledge.dto.IngestResult;
import com.socraticmind.knowledge.metadata.EvidenceExtractor;
import com.socraticmind.knowledge.parse.MarkdownParser;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 入库编排：解析 → 切分 → 向量化 → 证据抽取 → 落库。
 *
 * 两个入口：
 * - {@link #ingest} 新建一篇（id 由系统生成）；
 * - {@link #ingestAs} 写进指定的文档。同步场景必须走这条，否则"内容变了"会变成两篇文档，
 *   旧块永远留在索引里，而且因为它也标着 published，检索会把它当合法证据。
 *   这类错误不会有任何指标报警——它只会表现为"系统偶尔引用过期说法"。
 */
@Service
public class IngestionService {

    private final KnowledgeStore store;
    private final EmbeddingPort embedding;
    private final MarkdownParser parser = new MarkdownParser();
    private final ParentChildChunker chunker = new ParentChildChunker();
    private final EvidenceExtractor evidenceExtractor = new EvidenceExtractor();

    public IngestionService(KnowledgeStore store, EmbeddingPort embedding) {
        this.store = store;
        this.embedding = embedding;
    }

    public IngestResult ingest(IngestCommand cmd) {
        String status = cmd.publish() ? KnowledgeDocument.REVIEWING : KnowledgeDocument.DRAFT;
        return ingestAs(cmd, Ids.of("doc"), null, Hashes.sha256Hex(cmd.content()), status);
    }

    /**
     * @param status 目标状态；null 时按 publish 推导。同步改版时调用方传 REVIEWING——
     *               内容变了就要重新审，旧的审核结论是人对旧文本签的字。
     */
    public IngestResult ingestAs(IngestCommand cmd, String documentId, String externalKey,
                                 String contentHash, String status) {
        if (cmd.content() == null || cmd.content().isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }

        KnowledgeDocument existing = store.findDocument(documentId).orElse(null);
        String version = nextVersion(existing == null ? null : existing.version());
        String versionId = documentId + "_" + version;
        String language = cmd.language() == null ? "zh" : cmd.language();
        // 入库永远不直接发布。publish 只表示"提交审核"，能不能成为证据由人工审核决定。
        // 旧实现允许 publish=true 直通 PUBLISHED，而检索只看 published——
        // 于是任何匿名调用方都能把一段话塞进"证据库"，再被系统当作依据引用。
        String target = status != null
                ? status
                : (cmd.publish() ? KnowledgeDocument.REVIEWING : KnowledgeDocument.DRAFT);

        MarkdownParser.ParsedDocument parsed = parser.parse(cmd.title(), cmd.content());
        ParentChildChunker.Result chunked = chunker.chunk(documentId, versionId, language, parsed.sections());

        List<Chunk> chunks = new ArrayList<>(chunked.chunks().size());
        List<EvidenceItem> evidence = new ArrayList<>();
        for (Chunk c : chunked.chunks()) {
            if (c.parent()) {
                chunks.add(c);
                continue;
            }
            String retrievalText = c.titlePath() + " " + c.sectionPath() + "\n" + c.content();
            float[] vector = embedding.embed(retrievalText);
            Chunk embedded = new Chunk(c.id(), c.documentId(), c.documentVersionId(), c.parentId(), c.index(),
                    c.titlePath(), c.sectionPath(), c.content(), vector, c.pageStart(), c.pageEnd(),
                    c.language(), false);
            chunks.add(embedded);
            // 证据的"已审核"属性不在这里给：此刻还没有人看过它。
            evidence.add(evidenceExtractor.extract(embedded, "pending"));
        }

        KnowledgeDocument doc = new KnowledgeDocument(documentId, cmd.collection(), parsed.title(),
                cmd.source() == null ? "未标注来源" : cmd.source(), cmd.sourceUrl(), version, language,
                target, existing == null ? Instant.now() : existing.createdAt(),
                existing == null ? null : existing.aclScope(),
                contentHash, externalKey, null);

        store.save(doc, chunks, evidence);
        return new IngestResult(documentId, versionId, chunked.parentCount(), chunked.childCount(),
                evidence.size(), target);
    }

    /** v1 → v2；没有旧版本或版本号认不出来时给 v1（认不出来也不能卡住入库）。 */
    static String nextVersion(String current) {
        if (current == null || current.isBlank()) return "v1";
        String digits = current.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "v1";
        try {
            return "v" + (Integer.parseInt(digits) + 1);
        } catch (NumberFormatException e) {
            return "v1";
        }
    }
}
