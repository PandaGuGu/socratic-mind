package com.socraticmind.infrastructure.store;

import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.OrgStore;
import com.socraticmind.common.support.Hashes;
import com.socraticmind.common.support.Ids;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC 实现：H2 与 PostgreSQL 共用一份 SQL，DDL 见 {@code db/schema-cbt.sql}。 */
@Component
@ConditionalOnProperty(name = "psych.org.store", havingValue = "jdbc")
public class JdbcOrgStore implements OrgStore {

    private final JdbcTemplate jdbc;

    public JdbcOrgStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Tenant createTenant(String name) {
        Tenant t = new Tenant(Ids.of("ten"), require(name, "租户名"), Instant.now());
        jdbc.update("INSERT INTO org_tenant (id, name, created_at) VALUES (?,?,?)",
                t.id(), t.name(), Timestamp.from(t.createdAt()));
        return t;
    }

    @Override
    public Team createTeam(String tenantId, String name) {
        if (!tenantExists(tenantId)) throw new IllegalArgumentException("租户不存在：" + tenantId);
        Team t = new Team(Ids.of("team"), tenantId, require(name, "团队名"), Instant.now());
        jdbc.update("INSERT INTO org_team (id, tenant_id, name, created_at) VALUES (?,?,?,?)",
                t.id(), t.tenantId(), t.name(), Timestamp.from(t.createdAt()));
        return t;
    }

    @Override
    public Issued createMember(String tenantId, String teamId, String name, String role) {
        return createMemberWithToken(tenantId, teamId, name, role, InMemoryOrgStore.newToken());
    }

    @Override
    public Issued createMemberWithToken(String tenantId, String teamId, String name, String role,
                                        String rawToken) {
        if (!tenantExists(tenantId)) throw new IllegalArgumentException("租户不存在：" + tenantId);
        if (teamId != null && !teamExists(tenantId, teamId)) throw new IllegalArgumentException("团队不存在：" + teamId);
        if (rawToken == null || rawToken.isBlank()) throw new IllegalArgumentException("令牌不能为空");
        String token = rawToken.strip();
        Member m = new Member(Ids.of("mem"), tenantId, teamId, require(name, "成员名"),
                role == null ? Operator.REVIEWER : role, Instant.now(), null);
        jdbc.update("""
                INSERT INTO org_member (id, tenant_id, team_id, name, role, token_hash, created_at, revoked_at)
                VALUES (?,?,?,?,?,?,?,NULL)
                """, m.id(), m.tenantId(), m.teamId(), m.name(), m.role(),
                Hashes.sha256Hex(token), Timestamp.from(m.createdAt()));
        return new Issued(m, token);
    }

    @Override
    public List<Tenant> tenants() {
        return jdbc.query("SELECT * FROM org_tenant ORDER BY created_at ASC",
                (rs, i) -> new Tenant(rs.getString("id"), rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public List<Team> teams(String tenantId) {
        return jdbc.query("SELECT * FROM org_team WHERE tenant_id = ? ORDER BY created_at ASC",
                (rs, i) -> new Team(rs.getString("id"), rs.getString("tenant_id"), rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant()), tenantId);
    }

    @Override
    public List<Member> members(String tenantId) {
        return jdbc.query("SELECT * FROM org_member WHERE tenant_id = ? ORDER BY created_at ASC",
                MEMBER_MAPPER, tenantId);
    }

    @Override
    public Optional<Operator> findByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        return jdbc.query("""
                SELECT * FROM org_member WHERE token_hash = ? AND revoked_at IS NULL
                """, MEMBER_MAPPER, Hashes.sha256Hex(rawToken)).stream()
                .findFirst()
                .map(m -> new Operator(m.id(), m.tenantId(), m.teamId(), m.name(), m.role()));
    }

    @Override
    public boolean revokeMember(String memberId) {
        return jdbc.update("UPDATE org_member SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(Instant.now()), memberId) > 0;
    }

    @Override
    public boolean teamExists(String tenantId, String teamId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM org_team WHERE id = ? AND tenant_id = ?",
                Integer.class, teamId, tenantId);
        return n != null && n > 0;
    }

    @Override
    public boolean isEmpty() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM org_tenant", Integer.class);
        return n == null || n == 0;
    }

    private boolean tenantExists(String tenantId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM org_tenant WHERE id = ?", Integer.class, tenantId);
        return n != null && n > 0;
    }

    private static final RowMapper<Member> MEMBER_MAPPER = (rs, i) -> new Member(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("team_id"),
            rs.getString("name"), rs.getString("role"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant());

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.strip();
    }

    // ---- 登录与会话 ----

    @Override
    public Optional<Credential> credential(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        return jdbc.query("""
                SELECT id, tenant_id, team_id, name, role, password_hash, session_epoch, revoked_at
                FROM org_member WHERE lower(username) = ?
                """, (rs, i) -> new Credential(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("team_id"), rs.getString("name"), rs.getString("role"),
                rs.getString("password_hash"), rs.getInt("session_epoch"),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()),
                username.strip().toLowerCase(java.util.Locale.ROOT)).stream().findFirst();
    }

    @Override
    public Optional<Credential> credentialByMember(String memberId) {
        return jdbc.query("""
                SELECT id, tenant_id, team_id, name, role, password_hash, session_epoch, revoked_at
                FROM org_member WHERE id = ?
                """, (rs, i) -> new Credential(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("team_id"), rs.getString("name"), rs.getString("role"),
                rs.getString("password_hash"), rs.getInt("session_epoch"),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()),
                memberId).stream().findFirst();
    }

    @Override
    public Optional<String> usernameOf(String memberId) {
        return jdbc.query("SELECT username FROM org_member WHERE id = ?",
                        (rs, i) -> rs.getString(1), memberId).stream()
                .filter(u -> u != null && !u.isBlank())
                .findFirst();
    }

    @Override
    public void setCredential(String memberId, String username, String passwordHash) {
        int n = jdbc.update("UPDATE org_member SET username = ?, password_hash = ? WHERE id = ?",
                username.strip(), passwordHash, memberId);
        if (n == 0) throw new IllegalArgumentException("成员不存在：" + memberId);
    }

    @Override
    public void updatePassword(String memberId, String passwordHash) {
        if (jdbc.update("UPDATE org_member SET password_hash = ? WHERE id = ?", passwordHash, memberId) == 0) {
            throw new IllegalArgumentException("成员不存在：" + memberId);
        }
    }

    @Override
    public String createSession(String memberId, String csrfToken, Instant expiresAt) {
        Integer epoch = jdbc.queryForObject("SELECT session_epoch FROM org_member WHERE id = ?",
                Integer.class, memberId);
        String sid = InMemoryOrgStore.newToken();
        jdbc.update("""
                INSERT INTO org_session (sid_hash, member_id, csrf_token, epoch, created_at, expires_at, revoked_at)
                VALUES (?,?,?,?,?,?,NULL)
                """, Hashes.sha256Hex(sid), memberId, csrfToken, epoch == null ? 0 : epoch,
                Timestamp.from(Instant.now()), Timestamp.from(expiresAt));
        return sid;
    }

    @Override
    public Optional<SessionInfo> findSession(String rawSid) {
        if (rawSid == null || rawSid.isBlank()) return Optional.empty();
        // 一句 SQL 同时校验：未撤销、未过期、成员未吊销、epoch 未落后。
        // 分散到 Java 里判断，早晚会漏掉其中一条。
        return jdbc.query("""
                SELECT m.id, m.tenant_id, m.team_id, m.name, m.role, s.csrf_token, s.expires_at
                FROM org_session s JOIN org_member m ON m.id = s.member_id
                WHERE s.sid_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ?
                  AND m.revoked_at IS NULL AND m.session_epoch <= s.epoch
                """, (rs, i) -> new SessionInfo(
                new Operator(rs.getString("id"), rs.getString("tenant_id"), rs.getString("team_id"),
                        rs.getString("name"), rs.getString("role")),
                rs.getString("csrf_token"), rs.getTimestamp("expires_at").toInstant()),
                Hashes.sha256Hex(rawSid), Timestamp.from(Instant.now())).stream().findFirst();
    }

    @Override
    public boolean revokeSession(String rawSid) {
        return jdbc.update("UPDATE org_session SET revoked_at = ? WHERE sid_hash = ? AND revoked_at IS NULL",
                Timestamp.from(Instant.now()), Hashes.sha256Hex(rawSid)) > 0;
    }

    @Override
    public void revokeSessionsOf(String memberId) {
        jdbc.update("UPDATE org_session SET revoked_at = ? WHERE member_id = ? AND revoked_at IS NULL",
                Timestamp.from(Instant.now()), memberId);
    }

    @Override
    public void bumpEpoch(String memberId) {
        jdbc.update("UPDATE org_member SET session_epoch = session_epoch + 1 WHERE id = ?", memberId);
    }

    @Override
    public void recordLogin(String username, boolean ok, String reason) {
        jdbc.update("INSERT INTO org_login_attempt (username, ok, reason, at) VALUES (?,?,?,?)",
                username == null ? "" : username, ok, reason, Timestamp.from(Instant.now()));
    }

    @Override
    public List<LoginAttempt> loginAttempts(int limit) {
        return jdbc.query("SELECT username, ok, reason, at FROM org_login_attempt ORDER BY at DESC LIMIT ?",
                (rs, i) -> new LoginAttempt(rs.getString("username"), rs.getBoolean("ok"),
                        rs.getString("reason"), rs.getTimestamp("at").toInstant()), Math.max(1, limit));
    }
}
