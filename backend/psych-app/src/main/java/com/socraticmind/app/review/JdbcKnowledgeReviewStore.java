package com.socraticmind.app.review;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** JDBC 实现：H2 与 PostgreSQL 共用一份 SQL，DDL 见 {@code db/schema-cbt.sql}。 */
@Component
@ConditionalOnProperty(name = "psych.review.store", havingValue = "jdbc")
public class JdbcKnowledgeReviewStore implements KnowledgeReviewStore {

    private final JdbcTemplate jdbc;

    public JdbcKnowledgeReviewStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DocReview> all() {
        return jdbc.query("SELECT * FROM kb_document_review ORDER BY reviewed_at DESC", MAPPER);
    }

    @Override
    public List<DocReview> forDocument(String documentId) {
        return jdbc.query("SELECT * FROM kb_document_review WHERE document_id = ? ORDER BY reviewed_at DESC",
                MAPPER, documentId);
    }

    @Override
    public DocReview find(String documentId, String version) {
        return jdbc.query("SELECT * FROM kb_document_review WHERE document_id = ? AND version = ?",
                MAPPER, documentId, version).stream().findFirst().orElse(null);
    }

    @Override
    public DocReview save(String documentId, String title, String version, String decision,
                          String operatorId, String operatorName, String note, String aclSnapshot) {
        InMemoryKnowledgeReviewStore.validate(documentId, version, decision, operatorName, note);
        DocReview review = new DocReview(documentId, title, version, decision, operatorId,
                operatorName == null || operatorName.isBlank() ? null : operatorName.strip(),
                Instant.now().toString(), note == null || note.isBlank() ? null : note.strip(),
                aclSnapshot);
        // 同一版本重审 = 覆盖。不用 ON CONFLICT ... DO UPDATE：H2 不支持这套语法，
        // 而 h2 档也开着 jdbc。先 UPDATE，没有行再 INSERT，撞主键就退回 UPDATE。
        int updated = jdbc.update("""
                UPDATE kb_document_review
                SET title = ?, decision = ?, operator_id = ?, operator_name = ?,
                    acl_snapshot = ?, note = ?, reviewed_at = ?
                WHERE document_id = ? AND version = ?
                """, review.title(), review.decision(), review.operatorId(), review.operatorName(),
                review.aclSnapshot(), review.note(), Timestamp.from(Instant.parse(review.reviewedAt())),
                review.documentId(), review.version());
        if (updated == 0) {
            try {
                jdbc.update("""
                        INSERT INTO kb_document_review
                            (document_id, version, title, decision, operator_id, operator_name,
                             acl_snapshot, note, reviewed_at)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        """, review.documentId(), review.version(), review.title(), review.decision(),
                        review.operatorId(), review.operatorName(), review.aclSnapshot(), review.note(),
                        Timestamp.from(Instant.parse(review.reviewedAt())));
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发下另一个线程刚插入：改成更新，结论以后写的为准。
                jdbc.update("""
                        UPDATE kb_document_review SET decision = ?, operator_id = ?, operator_name = ?,
                            acl_snapshot = ?, note = ?, reviewed_at = ?
                        WHERE document_id = ? AND version = ?
                        """, review.decision(), review.operatorId(), review.operatorName(),
                        review.aclSnapshot(), review.note(),
                        Timestamp.from(Instant.parse(review.reviewedAt())),
                        review.documentId(), review.version());
            }
        }
        return review;
    }

    @Override
    public void clear() {
        jdbc.update("DELETE FROM kb_document_review");
    }

    private static final RowMapper<DocReview> MAPPER = (rs, i) -> new DocReview(
            rs.getString("document_id"), rs.getString("title"), rs.getString("version"),
            rs.getString("decision"), rs.getString("operator_id"), rs.getString("operator_name"),
            rs.getTimestamp("reviewed_at").toInstant().toString(), rs.getString("note"),
            rs.getString("acl_snapshot"));
}
