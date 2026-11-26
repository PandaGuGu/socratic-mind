package com.socraticmind.infrastructure.store;

import com.socraticmind.common.model.BadCase;
import com.socraticmind.common.model.QueryOutcome;
import com.socraticmind.common.port.EvalStore;
import com.socraticmind.common.support.Ids;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** JDBC 实现：H2 与 PostgreSQL 同一份 SQL，DDL 见 {@code db/schema-cbt.sql}。 */
@Component
@ConditionalOnProperty(name = "psych.eval.store", havingValue = "jdbc")
public class JdbcEvalStore implements EvalStore {

    private final JdbcTemplate jdbc;

    public JdbcEvalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(QueryOutcome o) {
        jdbc.update("""
                INSERT INTO eval_query_log
                    (query_text, status, sufficient, citations_valid, coverage, hits, latency_ms, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, o.query(), o.status(), o.sufficient(), o.citationsValid(), o.coverage(),
                o.hits(), o.latencyMs(), Timestamp.from(o.at() == null ? Instant.now() : o.at()));
    }

    @Override
    public List<QueryOutcome> recent(Instant since) {
        // 必须带 id 兜底排序：同一毫秒内的多条记录在 created_at 上没有先后，
        // 只按时间排会让"正序"变成数据库的自由发挥——而抽样与分位都依赖这个顺序。
        return jdbc.query("""
                SELECT query_text, status, sufficient, citations_valid, coverage, hits, latency_ms, created_at
                FROM eval_query_log WHERE created_at >= ? ORDER BY created_at ASC, id ASC
                """, OUTCOME_MAPPER, Timestamp.from(since == null ? Instant.EPOCH : since));
    }

    @Override
    public BadCase addBadCase(String query, String answer, String reason, String source) {
        BadCase bad = new BadCase(Ids.of("bad"), query, answer, reason, source, Instant.now(), false, null);
        jdbc.update("""
                INSERT INTO eval_badcase (id, query_text, answer, reason, source, created_at, resolved, resolved_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, bad.id(), bad.query(), bad.answer(), bad.reason(), bad.source(),
                Timestamp.from(bad.createdAt()), false, null);
        return bad;
    }

    @Override
    public List<BadCase> badCases(boolean unresolvedOnly) {
        String sql = """
                SELECT id, query_text, answer, reason, source, created_at, resolved, resolved_at
                FROM eval_badcase
                """ + (unresolvedOnly ? " WHERE resolved = FALSE " : "") + " ORDER BY created_at DESC";
        return jdbc.query(sql, BADCASE_MAPPER);
    }

    @Override
    public boolean resolveBadCase(String id) {
        return jdbc.update("UPDATE eval_badcase SET resolved = TRUE, resolved_at = ? WHERE id = ? AND resolved = FALSE",
                Timestamp.from(Instant.now()), id) > 0;
    }

    @Override
    public long openBadCases() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM eval_badcase WHERE resolved = FALSE", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public void clear() {
        jdbc.update("DELETE FROM eval_query_log");
        jdbc.update("DELETE FROM eval_badcase");
    }

    private static final RowMapper<QueryOutcome> OUTCOME_MAPPER = (rs, i) -> new QueryOutcome(
            rs.getString("query_text"), rs.getString("status"), rs.getBoolean("sufficient"),
            rs.getBoolean("citations_valid"), rs.getDouble("coverage"), rs.getInt("hits"),
            rs.getLong("latency_ms"),
            rs.getTimestamp("created_at") == null ? Instant.now() : rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<BadCase> BADCASE_MAPPER = (rs, i) -> new BadCase(
            rs.getString("id"), rs.getString("query_text"), rs.getString("answer"),
            rs.getString("reason"), rs.getString("source"),
            rs.getTimestamp("created_at") == null ? Instant.now() : rs.getTimestamp("created_at").toInstant(),
            rs.getBoolean("resolved"),
            rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant());
}
