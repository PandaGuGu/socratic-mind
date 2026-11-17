package com.socraticmind.session;

import java.util.List;
import java.util.Optional;

/** 会话存储端口。内存实现用于开发；PostgreSQL 实现按 kb_* 同样的方式追加。 */
public interface CbtSessionStore {

    void save(CbtSession session);

    Optional<CbtSession> find(String sessionId);

    List<CbtSession> findByUser(String userId);
}
