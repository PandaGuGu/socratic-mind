package com.socraticmind.infrastructure.store;

import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.OrgStore;
import com.socraticmind.common.support.Hashes;
import com.socraticmind.common.support.Ids;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存实现：测试与单机开发用。语义与 JDBC 档一致，尤其是"只存令牌摘要"这条。 */
@Component
@ConditionalOnProperty(name = "psych.org.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryOrgStore implements OrgStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();
    private final Map<String, Team> teams = new ConcurrentHashMap<>();
    private final Map<String, Member> members = new ConcurrentHashMap<>();
    private final Map<String, String> tokenToMember = new ConcurrentHashMap<>();
    private final Map<String, Credential> credentials = new ConcurrentHashMap<>();
    private final Map<String, Integer> epochs = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final java.util.Deque<LoginAttempt> attempts = new java.util.ArrayDeque<>();

    private record Session(String memberId, String csrf, int epoch, Instant expiresAt, Instant revokedAt) {
    }

    static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "op_" + HexFormat.of().formatHex(bytes);
    }

    @Override
    public Tenant createTenant(String name) {
        Tenant t = new Tenant(Ids.of("ten"), require(name, "租户名"), Instant.now());
        tenants.put(t.id(), t);
        return t;
    }

    @Override
    public Team createTeam(String tenantId, String name) {
        if (!tenants.containsKey(tenantId)) throw new IllegalArgumentException("租户不存在：" + tenantId);
        Team t = new Team(Ids.of("team"), tenantId, require(name, "团队名"), Instant.now());
        teams.put(t.id(), t);
        return t;
    }

    @Override
    public Issued createMember(String tenantId, String teamId, String name, String role) {
        return createMemberWithToken(tenantId, teamId, name, role, newToken());
    }

    @Override
    public Issued createMemberWithToken(String tenantId, String teamId, String name, String role,
                                        String rawToken) {
        if (!tenants.containsKey(tenantId)) throw new IllegalArgumentException("租户不存在：" + tenantId);
        if (teamId != null && !teams.containsKey(teamId)) throw new IllegalArgumentException("团队不存在：" + teamId);
        if (rawToken == null || rawToken.isBlank()) throw new IllegalArgumentException("令牌不能为空");
        String token = rawToken.strip();
        Member m = new Member(Ids.of("mem"), tenantId, teamId, require(name, "成员名"),
                role == null ? Operator.REVIEWER : role, Instant.now(), null);
        members.put(m.id(), m);
        tokenToMember.put(m.id(), Hashes.sha256Hex(token));
        return new Issued(m, token);
    }

    @Override
    public List<Tenant> tenants() {
        return new ArrayList<>(tenants.values());
    }

    @Override
    public List<Team> teams(String tenantId) {
        return teams.values().stream().filter(t -> t.tenantId().equals(tenantId)).toList();
    }

    @Override
    public List<Member> members(String tenantId) {
        return members.values().stream().filter(m -> m.tenantId().equals(tenantId)).toList();
    }

    @Override
    public Optional<Operator> findByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        String hash = Hashes.sha256Hex(rawToken);
        for (Member m : members.values()) {
            if (m.revokedAt() == null && hash.equals(tokenToMember.get(m.id()))) {
                return Optional.of(new Operator(m.id(), m.tenantId(), m.teamId(), m.name(), m.role()));
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean revokeMember(String memberId) {
        Member m = members.get(memberId);
        if (m == null || m.revokedAt() != null) return false;
        members.put(memberId, new Member(m.id(), m.tenantId(), m.teamId(), m.name(), m.role(),
                m.createdAt(), Instant.now()));
        return true;
    }

    @Override
    public boolean teamExists(String tenantId, String teamId) {
        Team t = teams.get(teamId);
        return t != null && t.tenantId().equals(tenantId);
    }

    @Override
    public boolean isEmpty() {
        return tenants.isEmpty();
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.strip();
    }

    // ---- 登录与会话 ----

    @Override
    public Optional<Credential> credential(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        Credential c = credentials.get(username.strip().toLowerCase(java.util.Locale.ROOT));
        if (c == null) return Optional.empty();
        Member m = members.get(c.memberId());
        if (m == null) return Optional.empty();
        // epoch 从当前状态现取：放在凭据里会在改口令后变成过期值。
        return Optional.of(new Credential(c.memberId(), c.tenantId(), c.teamId(), c.name(), c.role(),
                c.passwordHash(), epochs.getOrDefault(c.memberId(), 0), m.revokedAt()));
    }

    @Override
    public Optional<Credential> credentialByMember(String memberId) {
        Member m = members.get(memberId);
        if (m == null) return Optional.empty();
        String hash = credentials.values().stream()
                .filter(c -> c.memberId().equals(memberId))
                .map(Credential::passwordHash)
                .findFirst().orElse(null);
        return Optional.of(new Credential(m.id(), m.tenantId(), m.teamId(), m.name(), m.role(),
                hash, epochs.getOrDefault(m.id(), 0), m.revokedAt()));
    }

    @Override
    public void setCredential(String memberId, String username, String passwordHash) {
        Member m = members.get(memberId);
        if (m == null) throw new IllegalArgumentException("成员不存在：" + memberId);
        credentials.put(username.strip().toLowerCase(java.util.Locale.ROOT),
                new Credential(m.id(), m.tenantId(), m.teamId(), m.name(), m.role(), passwordHash, 0, null));
    }

    @Override
    public void updatePassword(String memberId, String passwordHash) {
        credentials.replaceAll((k, c) -> c.memberId().equals(memberId)
                ? new Credential(c.memberId(), c.tenantId(), c.teamId(), c.name(), c.role(), passwordHash, c.epoch(), c.revokedAt())
                : c);
    }

    @Override
    public String createSession(String memberId, String csrfToken, Instant expiresAt) {
        String sid = newToken();
        sessions.put(Hashes.sha256Hex(sid), new Session(memberId, csrfToken,
                epochs.getOrDefault(memberId, 0), expiresAt, null));
        return sid;
    }

    @Override
    public Optional<SessionInfo> findSession(String rawSid) {
        if (rawSid == null || rawSid.isBlank()) return Optional.empty();
        Session s = sessions.get(Hashes.sha256Hex(rawSid));
        if (s == null || s.revokedAt() != null || s.expiresAt().isBefore(Instant.now())) return Optional.empty();
        Member m = members.get(s.memberId());
        if (m == null || m.revokedAt() != null) return Optional.empty();
        if (epochs.getOrDefault(m.id(), 0) > s.epoch()) return Optional.empty();
        return Optional.of(new SessionInfo(
                new Operator(m.id(), m.tenantId(), m.teamId(), m.name(), m.role()), s.csrf(), s.expiresAt()));
    }

    @Override
    public boolean revokeSession(String rawSid) {
        if (rawSid == null || rawSid.isBlank()) return false;
        String key = Hashes.sha256Hex(rawSid);
        Session s = sessions.get(key);
        if (s == null || s.revokedAt() != null) return false;
        sessions.put(key, new Session(s.memberId(), s.csrf(), s.epoch(), s.expiresAt(), Instant.now()));
        return true;
    }

    @Override
    public void revokeSessionsOf(String memberId) {
        sessions.forEach((k, s) -> {
            if (s.memberId().equals(memberId) && s.revokedAt() == null) {
                sessions.put(k, new Session(s.memberId(), s.csrf(), s.epoch(), s.expiresAt(), Instant.now()));
            }
        });
    }

    @Override
    public void bumpEpoch(String memberId) {
        epochs.merge(memberId, 1, Integer::sum);
    }

    @Override
    public void recordLogin(String username, boolean ok, String reason) {
        synchronized (attempts) {
            attempts.addFirst(new LoginAttempt(username, ok, reason, Instant.now()));
            while (attempts.size() > 500) attempts.removeLast();
        }
    }

    @Override
    public List<LoginAttempt> loginAttempts(int limit) {
        synchronized (attempts) {
            return attempts.stream().limit(Math.max(1, limit)).toList();
        }
    }
}
