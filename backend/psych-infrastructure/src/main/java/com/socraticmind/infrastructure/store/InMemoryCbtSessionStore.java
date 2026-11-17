package com.socraticmind.infrastructure.store;

import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 会话内存存储。重启即丢，只用于开发与测试。 */
@Component
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
}
