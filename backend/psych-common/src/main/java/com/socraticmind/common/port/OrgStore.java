package com.socraticmind.common.port;

import com.socraticmind.common.model.Operator;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 组织域：租户 → 团队 → 成员，以及成员的登录与会话。
 *
 * 全部落库：多实例部署时，每个实例各读一份配置，"谁签的字"会随负载均衡漂到不同副本，
 * 同一个团队的人在不同机器上看到不同权限——这种错误不会报错，只会让人悄悄看不到东西。
 *
 * 会话放在这里而不是单独开一个端口：它和成员的生命周期绑在一起
 * （改口令 → epoch +1 → 所有会话失效），拆开会让这条不变量跨两个存储。
 */
public interface OrgStore {

    record Tenant(String id, String name, Instant createdAt) {
    }

    record Team(String id, String tenantId, String name, Instant createdAt) {
    }

    record Member(String id, String tenantId, String teamId, String name, String role,
                  Instant createdAt, Instant revokedAt) {
    }

    /** 建成员时只在此刻返回一次令牌明文；库里只存摘要。 */
    record Issued(Member member, String token) {
    }

    /** 登录凭据。passwordHash 为空表示这个成员只能用操作者令牌，不能登录。 */
    record Credential(String memberId, String tenantId, String teamId, String name, String role,
                      String passwordHash, int epoch, Instant revokedAt) {
        public Operator toOperator() {
            return new Operator(memberId, tenantId, teamId, name, role);
        }
    }

    /** 已通过校验的会话：操作者 + 该会话的 CSRF 令牌。 */
    record SessionInfo(Operator operator, String csrfToken, Instant expiresAt) {
    }

    record LoginAttempt(String username, boolean ok, String reason, Instant at) {
    }

    Tenant createTenant(String name);

    Team createTeam(String tenantId, String name);

    Issued createMember(String tenantId, String teamId, String name, String role);

    /**
     * 用指定令牌建成员（令牌由调用方给定）。只给开发档用：
     * 本机重启后要能拿回同一把钥匙，否则每次重启所有写操作都是 401。
     */
    Issued createMemberWithToken(String tenantId, String teamId, String name, String role, String rawToken);

    List<Tenant> tenants();

    List<Team> teams(String tenantId);

    List<Member> members(String tenantId);

    Optional<Operator> findByToken(String rawToken);

    boolean revokeMember(String memberId);

    /** ACL 里写的是团队 id，写之前要确认它真的存在，否则又是一个自由字符串。 */
    boolean teamExists(String tenantId, String teamId);

    boolean isEmpty();

    // ---- 登录 ----

    Optional<Credential> credential(String username);

    /** 按成员反查凭据，用于"改口令"这种手里只有身份、没有登录名的场景。 */
    Optional<Credential> credentialByMember(String memberId);

    /**
     * 按成员查登录名。没设过登录名的成员（只能用令牌）返回空。
     * 单独开这个方法而不是塞进 Credential：登录名是"账号"的属性，
     * 凭据是"能不能登录"的结果，混在一起会让每次判权都多带一个无关字段。
     */
    Optional<String> usernameOf(String memberId);

    /** 设置登录名与口令摘要。登录名冲突由调用方处理。 */
    void setCredential(String memberId, String username, String passwordHash);

    /** 只改口令，不动登录名——改口令的时候手里通常没有登录名。 */
    void updatePassword(String memberId, String passwordHash);

    // ---- 会话 ----

    /** @return 会话 id 明文，只在此刻返回；库里存摘要 */
    String createSession(String memberId, String csrfToken, Instant expiresAt);

    /** 过期、被撤销、或 epoch 落后（改过口令）的会话一律返回空。 */
    Optional<SessionInfo> findSession(String rawSid);

    boolean revokeSession(String rawSid);

    void revokeSessionsOf(String memberId);

    /** epoch +1：旧会话全部失效，用于"改口令后其他设备下线"。 */
    void bumpEpoch(String memberId);

    void recordLogin(String username, boolean ok, String reason);

    List<LoginAttempt> loginAttempts(int limit);
}
