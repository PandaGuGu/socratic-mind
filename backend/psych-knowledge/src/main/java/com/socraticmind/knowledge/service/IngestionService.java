package com.socraticmind.knowledge.service;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.EvidenceItem;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.port.EmbeddingPort;
import com.socraticmind.common.port.KnowledgeStore;
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

/** 入库编排：解析 → 切分 → 向量化 → 证据抽取 → 落库。 */
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
        if (cmd.content() == null || cmd.content().isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }

        String documentId = Ids.of("doc");
        String versionId = documentId + "_v1";
        String language = cmd.language() == null ? "zh" : cmd.language();
        // 入库永远不直接发布。publish 只表示"提交审核"，能不能成为证据由人工审核决定。
        // 旧实现允许 publish=true 直通 PUBLISHED，而检索只看 published——
        // 于是任何匿名调用方都能把一段话塞进"证据库"，再被系统当作依据引用。
        String status = cmd.publish() ? KnowledgeDocument.REVIEWING : KnowledgeDocument.DRAFT;

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
                cmd.source() == null ? "未标注来源" : cmd.source(), cmd.sourceUrl(), "v1", language,
                status, Instant.now());

        store.save(doc, chunks, evidence);
        return new IngestResult(documentId, versionId, chunked.parentCount(), chunked.childCount(),
                evidence.size(), status);
    }
}
