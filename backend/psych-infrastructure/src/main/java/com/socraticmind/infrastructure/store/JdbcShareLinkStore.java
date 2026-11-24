package com.socraticmind.infrastructure.store;

import com.socraticmind.common.port.ShareLinkStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 分享链接的 JDBC 实现。DDL 见 {@code db/schema-cbt.sql} 的 share_link。 */
@Component
@ConditionalOnProperty(name = "psych.db.enabled", havingValue = "true")
public class JdbcShareLinkStore implements ShareLinkStore {

    private final JdbcTemplate jdbc;

    public JdbcShareLinkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ShareLink link) {
        jdbc.update("""
                INSERT INTO share_link (token_hash, session_id, owner_user_id, created_at, expires_at,
                  used_at, revoked_at)
                VALUES (?,?,?,?,?,?,?)
                """, link.tokenHash(), link.sessionId(), link.ownerUserId(),
                Timestamp.from(link.createdAt()), Timestamp.from(link.expiresAt()),
                link.usedAt() == null ? null : Timestamp.from(link.usedAt()),
                link.revokedAt() == null ? null : Timestamp.from(link.revokedAt()));
    }

    @Override
    public Optional<ShareLink> find(String tokenHash) {
        List<ShareLink> rows = jdbc.query("SELECT * FROM share_link WHERE token_hash = ?",
                (rs, i) -> read(rs), tokenHash);
        return rows.stream().findFirst();
    }

    /**
     * 一条 UPDATE 完成"检查 + 占用"：并发打开同一条链接时，数据库的行锁保证只有一个请求把
     * used_at 从 NULL 改成时间；另一个拿到 0 行，按"已经看过"处理。
     */
    @Override
    public boolean markUsed(String tokenHash, Instant at) {
        return jdbc.update("""
                UPDATE share_link SET used_at = ?
                WHERE token_hash = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > ?
                """, Timestamp.from(at), tokenHash, Timestamp.from(at)) == 1;
    }

    @Override
    public int revokeBySession(String sessionId, String ownerUserId, Instant at) {
        return jdbc.update("""
                UPDATE share_link SET revoked_at = ?
                WHERE session_id = ? AND owner_user_id = ? AND used_at IS NULL AND revoked_at IS NULL
                """, Timestamp.from(at), sessionId, ownerUserId);
    }

    private static ShareLink read(ResultSet rs) throws java.sql.SQLException {
        return new ShareLink(rs.getString("token_hash"), rs.getString("session_id"),
                rs.getString("owner_user_id"), instant(rs, "created_at"), instant(rs, "expires_at"),
                instant(rs, "used_at"), instant(rs, "revoked_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
