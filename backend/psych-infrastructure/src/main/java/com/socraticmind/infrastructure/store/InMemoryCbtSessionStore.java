package com.socraticmind.infrastructure.store;

import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 会话内存存储。重启即丢，只用于开发与测试；psych.store=postgres 时由 JDBC 实现接管。 */
@Component
@ConditionalOnProperty(name = "psych.session.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryCbtSessionStore implements CbtSessionStore {

    private final Map<String, CbtSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(CbtSession session) {
        sessions.put(session.id(), session);
    }

    @Override
    public Optional<CbtSession> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public List<CbtSession> findByUser(String userId) {
        return sessions.values().stream()
                .filter(s -> userId == null || userId.equals(s.userId()))
                .sorted(Comparator.comparing(CbtSession::createdAt).reversed())
                .toList();
    }

    @Override
    public List<CbtSession> findVisible(String userId, String accountId) {
        return sessions.values().stream()
                .filter(s -> accountId != null
                        ? accountId.equals(s.accountId())
                        : userId != null && userId.equals(s.userId()))
                .sorted(Comparator.comparing(CbtSession::createdAt).reversed())
                .toList();
    }

    @Override
    public List<CbtSession> findVisiblePage(String userId, String accountId,
                                            java.time.Instant beforeAt, String beforeId, int limit) {
        Comparator<CbtSession> order = Comparator
                .comparing(CbtSession::createdAt).reversed()
                .thenComparing(CbtSession::id, Comparator.reverseOrder());
        return sessions.values().stream()
                .filter(s -> accountId != null
                        ? accountId.equals(s.accountId())
                        : userId != null && userId.equals(s.userId()))
                .filter(s -> afterCursor(s, beforeAt, beforeId))
                .sorted(order)
                .limit(limit)
                .toList();
    }

    /** 严格在游标之后：时间更早，或同一时刻但 id 更小（排序是按 id 倒序）。 */
    private static boolean afterCursor(CbtSession s, java.time.Instant beforeAt, String beforeId) {
        if (beforeAt == null) return true;
        int cmp = s.createdAt().compareTo(beforeAt);
        if (cmp != 0) return cmp < 0;
        return beforeId != null && s.id().compareTo(beforeId) < 0;
    }

    @Override
    public int countClaimable(String userId) {
        if (userId == null) return 0;
        return (int) sessions.values().stream()
                .filter(s -> userId.equals(s.userId()) && s.accountId() == null)
                .count();
    }

    @Override
    public int claim(String userId, String accountId) {
        if (userId == null || accountId == null) return 0;
        int claimed = 0;
        for (CbtSession s : sessions.values()) {
            if (!userId.equals(s.userId()) || s.accountId() != null) continue;
            // 单个会话对象内部判空再写：并发登录时同一轮只应被认领一次
            synchronized (s) {
                if (s.accountId() == null) {
                    s.setAccountId(accountId);
                    claimed++;
                }
            }
        }
        return claimed;
    }

    @Override
    public int releaseAccount(String accountId) {
        if (accountId == null) return 0;
        int released = 0;
        for (CbtSession s : sessions.values()) {
            if (!accountId.equals(s.accountId())) continue;
            synchronized (s) {
                if (accountId.equals(s.accountId())) {
                    s.setAccountId(null);
                    released++;
                }
            }
        }
        return released;
    }
}
