package com.socraticmind.infrastructure.store;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.EvidenceItem;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.model.RetrievedChunk;
import com.socraticmind.common.port.KnowledgeStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL + pgvector 实现。
 *
 * 已知限制（第一版刻意接受）：中文没有分词扩展，词法路用 ILIKE 逐词匹配近似替代；
 * 数据量上来后应换 pg_bigm 或应用侧分词，接口不变。
 */
@Component
@ConditionalOnProperty(name = "psych.store", havingValue = "postgres")
public class PgKnowledgeStore implements KnowledgeStore {

    private final JdbcTemplate jdbc;

    public PgKnowledgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(KnowledgeDocument d, List<Chunk> chunks, List<EvidenceItem> evidence) {
        // acl_scope 用 COALESCE 保旧值：重新入库（内容变更）不该顺手把权限改成"公开"。
        // 权限只能由 updateAcl 这一条显式路径改，避免"同步一次就泄露一次"。
        jdbc.update("""
                INSERT INTO kb_document
                    (id, collection, title, source, source_url, version, language, status, created_at,
                     acl_scope, content_hash, external_key, deleted_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NULL,now())
                ON CONFLICT (id) DO UPDATE SET
                    title = EXCLUDED.title,
                    version = EXCLUDED.version,
                    status = EXCLUDED.status,
                    content_hash = EXCLUDED.content_hash,
                    external_key = EXCLUDED.external_key,
                    acl_scope = COALESCE(EXCLUDED.acl_scope, kb_document.acl_scope),
                    deleted_at = NULL,
                    updated_at = now()
                """, d.id(), d.collection(), d.title(), d.source(), d.sourceUrl(), d.version(),
                d.language(), d.status(), Timestamp.from(d.createdAt() == null ? Instant.now() : d.createdAt()),
                d.aclScope(), d.contentHash(), d.externalKey());

        for (Chunk c : chunks) {
            jdbc.update("""
                    INSERT INTO kb_chunk (id, document_id, document_version_id, parent_id, chunk_index,
                        title_path, section_path, content, embedding, page_start, page_end, language, is_parent)
                    VALUES (?,?,?,?,?,?,?,?,?::vector,?,?,?,?)
                    ON CONFLICT (id) DO NOTHING
                    """, c.id(), c.documentId(), c.documentVersionId(), c.parentId(), c.index(),
                    c.titlePath(), c.sectionPath(), c.content(), toVector(c.embedding()),
                    c.pageStart(), c.pageEnd(), c.language(), c.parent());
        }

        for (EvidenceItem e : evidence) {
            jdbc.update("""
                    INSERT INTO kb_evidence (id, chunk_id, evidence_type, condition_text, population,
                        intervention, recommendation_strength, evidence_quality, limitations, review_status)
                    VALUES (?,?,?,?,?,?,?,?,?::text[],?)
                    ON CONFLICT (id) DO NOTHING
                    """, e.id(), e.chunkId(), e.evidenceType(), e.condition(), e.population(),
                    e.intervention(), e.recommendationStrength(), e.evidenceQuality(),
                    toArray(e.limitations()), e.reviewStatus());
        }
    }

    @Override
    public void updateDocumentStatus(String documentId, String status) {
        jdbc.update("UPDATE kb_document SET status = ?, updated_at = now() WHERE id = ?", status, documentId);
    }

    @Override
    public Optional<KnowledgeDocument> findByExternalKey(String externalKey) {
        if (externalKey == null || externalKey.isBlank()) return Optional.empty();
        return jdbc.query("SELECT * FROM kb_document WHERE external_key = ?", DOC_MAPPER, externalKey)
                .stream().findFirst();
    }

    /**
     * 可见文档的 id 集合。
     *
     * 这里刻意把 ACL 过滤放在应用侧而不是拼进检索 SQL：范围表达式一旦进了 SQL，
     * 内存档与 PG 档就会各写一遍，然后慢慢分叉——而这个分叉只会表现为"某些人看到了不该看的"，
     * 不会报错。文档数量级在几千以内时，一次轻量查询加内存过滤是更安全的选择。
     */
    @Override
    public List<String> findVisibleDocumentIds(Set<String> scopes) {
        List<KnowledgeDocument> docs = jdbc.query(
                "SELECT * FROM kb_document WHERE status = 'published' AND deleted_at IS NULL", DOC_MAPPER);
        return com.socraticmind.common.support.DocumentVisibility.visibleIds(docs, scopes);
    }

    @Override
    public void deleteChunksOf(String documentId) {
        jdbc.update("DELETE FROM kb_evidence WHERE chunk_id IN (SELECT id FROM kb_chunk WHERE document_id = ?)",
                documentId);
        jdbc.update("DELETE FROM kb_chunk WHERE document_id = ?", documentId);
    }

    @Override
    public void markDeleted(String documentId, Instant at) {
        jdbc.update("UPDATE kb_document SET status = 'archived', deleted_at = ?, updated_at = now() WHERE id = ?",
                Timestamp.from(at == null ? Instant.now() : at), documentId);
    }

    @Override
    public void updateAcl(String documentId, String aclScope) {
        jdbc.update("UPDATE kb_document SET acl_scope = ?, updated_at = now() WHERE id = ?",
                aclScope, documentId);
    }

    @Override
    public int purgeDeletedBefore(Instant cutoff) {
        Timestamp at = Timestamp.from(cutoff == null ? Instant.now() : cutoff);
        // <= 而不是 <：purge(0) 表示"清掉此刻为止已删除的"，边界要包含同一毫秒内标删的行。
        String doomed = "SELECT id FROM kb_document WHERE deleted_at IS NOT NULL AND deleted_at <= ?";
        jdbc.update("DELETE FROM kb_evidence WHERE chunk_id IN (SELECT id FROM kb_chunk WHERE document_id IN ("
                + doomed + "))", at);
        jdbc.update("DELETE FROM kb_chunk WHERE document_id IN (" + doomed + ")", at);
        return jdbc.update("DELETE FROM kb_document WHERE deleted_at IS NOT NULL AND deleted_at <= ?", at);
    }

    @Override
    public List<KnowledgeDocument> findDocuments() {
        return jdbc.query("SELECT * FROM kb_document ORDER BY created_at DESC", DOC_MAPPER);
    }

    @Override
    public Optional<KnowledgeDocument> findDocument(String documentId) {
        return jdbc.query("SELECT * FROM kb_document WHERE id = ?", DOC_MAPPER, documentId)
                .stream().findFirst();
    }

    @Override
    public List<RetrievedChunk> searchVector(float[] query, int limit, Set<String> allowedDocumentIds) {
        if (query == null || query.length == 0) return List.of();
        String vec = toVector(query);
        String filter = allowedDocumentIds == null ? "" : " AND c.document_id = ANY(?::text[])";
        String sql = """
                SELECT c.id, 1 - (c.embedding <=> ?::vector) AS score
                FROM kb_chunk c JOIN kb_document d ON d.id = c.document_id
                WHERE c.is_parent = false AND c.embedding IS NOT NULL AND d.status = 'published'
                """ + filter + " ORDER BY c.embedding <=> ?::vector, c.id LIMIT ?";

        List<Map<String, Object>> rows = allowedDocumentIds == null
                ? jdbc.queryForList(sql, vec, vec, limit)
                : jdbc.queryForList(sql, vec, toArray(allowedDocumentIds), vec, limit);

        List<String> ids = rows.stream().map(r -> (String) r.get("id")).toList();
        Map<String, Double> scores = new LinkedHashMap<>();
        rows.forEach(r -> scores.put((String) r.get("id"), ((Number) r.get("score")).doubleValue()));

        List<RetrievedChunk> out = new ArrayList<>();
        for (Chunk c : chunksByIds(ids)) {
            out.add(new RetrievedChunk(c, scores.getOrDefault(c.id(), 0.0), "vector"));
        }
        return out;
    }

    @Override
    public List<RetrievedChunk> searchText(String query, int limit, Set<String> allowedDocumentIds) {
        List<String> tokens = com.socraticmind.common.support.TextUtils.tokens(query);
        if (tokens.isEmpty()) return List.of();
        String[] patterns = tokens.stream().map(t -> "%" + t + "%").toArray(String[]::new);
        String filter = allowedDocumentIds == null ? "" : " AND c.document_id = ANY(?::text[])";
        String sql = """
                SELECT c.id FROM kb_chunk c JOIN kb_document d ON d.id = c.document_id
                WHERE c.is_parent = false AND d.status = 'published'
                  AND (c.content ILIKE ANY(?::text[]) OR c.title_path ILIKE ANY(?::text[]))
                """ + filter + " ORDER BY c.id LIMIT ?";

        List<String> ids = allowedDocumentIds == null
                ? jdbc.queryForList(sql, String.class, toArray(Arrays.asList(patterns)), toArray(Arrays.asList(patterns)), limit)
                : jdbc.queryForList(sql, String.class, toArray(Arrays.asList(patterns)), toArray(Arrays.asList(patterns)),
                        toArray(allowedDocumentIds), limit);

        Map<String, Double> scores = new LinkedHashMap<>();
        int rank = ids.size();
        for (String id : ids) {
            scores.put(id, rank-- / (double) Math.max(1, ids.size()));
        }
        List<RetrievedChunk> out = new ArrayList<>();
        for (Chunk c : chunksByIds(ids)) {
            out.add(new RetrievedChunk(c, scores.getOrDefault(c.id(), 0.0), "text"));
        }
        return out;
    }

    @Override
    public List<Chunk> chunksByIds(Collection<String> ids) {
        if (ids.isEmpty()) return List.of();
        List<Chunk> found = jdbc.query("SELECT * FROM kb_chunk WHERE id = ANY(?::text[]) AND is_parent = false",
                CHUNK_MAPPER, toArray(ids));
        Map<String, Chunk> byId = new LinkedHashMap<>();
        found.forEach(c -> byId.put(c.id(), c));
        List<Chunk> ordered = new ArrayList<>();
        for (String id : ids) {
            Chunk c = byId.get(id);
            if (c != null) ordered.add(c);
        }
        return ordered;
    }

    @Override
    public List<Chunk> listChunks(String documentId) {
        return jdbc.query("""
                SELECT * FROM kb_chunk
                WHERE document_id = ?
                ORDER BY section_path ASC NULLS FIRST, is_parent DESC, chunk_index ASC
                """, CHUNK_MAPPER, documentId);
    }

    @Override
    public List<EvidenceItem> evidenceOfChunks(Collection<String> chunkIds) {
        if (chunkIds.isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM kb_evidence WHERE chunk_id = ANY(?::text[])",
                EVIDENCE_MAPPER, toArray(chunkIds));
    }

    @Override
    public long countChunks() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM kb_chunk WHERE is_parent = false", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public void clear() {
        jdbc.update("DELETE FROM kb_evidence");
        jdbc.update("DELETE FROM kb_chunk");
        jdbc.update("DELETE FROM kb_document");
    }

    private static final RowMapper<KnowledgeDocument> DOC_MAPPER = (rs, i) -> new KnowledgeDocument(
            rs.getString("id"), rs.getString("collection"), rs.getString("title"),
            rs.getString("source"), rs.getString("source_url"), rs.getString("version"),
            rs.getString("language"), rs.getString("status"),
            rs.getTimestamp("created_at") == null ? Instant.now() : rs.getTimestamp("created_at").toInstant(),
            rs.getString("acl_scope"), rs.getString("content_hash"), rs.getString("external_key"),
            rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant());

    private static final RowMapper<Chunk> CHUNK_MAPPER = (rs, i) -> new Chunk(
            rs.getString("id"), rs.getString("document_id"), rs.getString("document_version_id"),
            rs.getString("parent_id"), rs.getInt("chunk_index"), rs.getString("title_path"),
            rs.getString("section_path"), rs.getString("content"), parseVector(rs.getString("embedding")),
            (Integer) rs.getObject("page_start"), (Integer) rs.getObject("page_end"),
            rs.getString("language"), rs.getBoolean("is_parent"));

    private static final RowMapper<EvidenceItem> EVIDENCE_MAPPER = (rs, i) -> {
        java.sql.Array array = rs.getArray("limitations");
        String[] limitations = array == null ? new String[0] : toStrings(array);
        return new EvidenceItem(rs.getString("id"), rs.getString("chunk_id"), rs.getString("evidence_type"),
                rs.getString("condition_text"), rs.getString("population"), rs.getString("intervention"),
                rs.getString("recommendation_strength"), rs.getString("evidence_quality"),
                List.of(limitations), rs.getString("review_status"));
    };

    private static String[] toStrings(java.sql.Array array) {
        try {
            Object v = array.getArray();
            if (v instanceof String[] s) return s;
            if (v instanceof Object[] o) return Arrays.stream(o).map(String::valueOf).toArray(String[]::new);
            return new String[0];
        } catch (Exception ex) {
            return new String[0];
        }
    }

    static String toVector(float[] v) {
        if (v == null) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    static float[] parseVector(String s) {
        if (s == null || s.isBlank()) return null;
        String body = s.trim().replace("[", "").replace("]", "");
        if (body.isEmpty()) return null;
        String[] parts = body.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }

    static String toArray(Collection<String> values) {
        if (values == null) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
