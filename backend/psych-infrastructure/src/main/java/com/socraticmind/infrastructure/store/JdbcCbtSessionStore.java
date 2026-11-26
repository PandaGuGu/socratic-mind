package com.socraticmind.infrastructure.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CbtTurnEvent;
import com.socraticmind.session.MoodPoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话的 JDBC 实现。DDL 见 {@code db/schema-cbt.sql}，刻意只用可移植语法，
 * 生产跑 PostgreSQL、测试跑 H2（PostgreSQL 兼容模式），同一份建表语句。
 *
 * 两个刻意的取舍：
 * 1. **整轮覆盖写**：每次 save 先删该会话的消息与轨迹再重建。
 *    会话规模是"几十条"量级，简单换来"内存与库永远一致"；真要追加式，
 *    等 cbt_message 上百万行再说（那时接口不变，换实现即可）。
 *    覆盖写必须在一个事务里：UPDATE 成功、DELETE 成功、batch INSERT 失败
 *    会留下一条"有会话、没消息"的空壳记录，而那恰好是最难察觉的坏数据。
 * 2. **只依赖 CbtSessionStore 端口**：换库不改业务代码，这也是当初把存储做成端口的理由。
 */
@Component
@ConditionalOnProperty(name = "psych.session.store", havingValue = "jdbc")
public class JdbcCbtSessionStore implements CbtSessionStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<MoodPoint>> MOOD_LIST = new TypeReference<>() {
    };

    private static final String UPDATE = """
            UPDATE cbt_session SET thread_id = ?, user_id = ?, account_id = ?, stage = ?, awaiting_user = ?,
              safety_status = ?, safety_reason = ?, target_problem = ?, situation = ?, automatic_thought = ?,
              emotions = ?, evidence_for = ?, evidence_against = ?, candidate_thought = ?,
              user_confirmed_thought = ?, intervention_type = ?, action_plan = ?, action_result = ?,
              summary = ?, pending_question = ?, mood_points = ?, distortions = ?,
              contact_name = ?, contact_phone = ?, contact_relation = ?,
              safety_ack_at = ?, safety_ack_by = ?,
              created_at = ?, updated_at = ?
            WHERE id = ?
            """;

    private static final String INSERT = """
            INSERT INTO cbt_session (thread_id, user_id, account_id, stage, awaiting_user,
              safety_status, safety_reason,
              target_problem, situation, automatic_thought, emotions, evidence_for, evidence_against,
              candidate_thought, user_confirmed_thought, intervention_type, action_plan, action_result,
              summary, pending_question, mood_points, distortions,
              contact_name, contact_phone, contact_relation, safety_ack_at, safety_ack_by,
              created_at, updated_at, id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private final JdbcTemplate jdbc;

    public JdbcCbtSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void save(CbtSession s) {
        Object[] args = new Object[]{
                s.threadId(), s.userId(), s.accountId(), s.stage().name(), s.awaitingUser(),
                s.safetyStatus(), s.safetyReason(), s.targetProblem(), s.situation(), s.automaticThought(),
                json(s.emotions()), json(s.evidenceFor()), json(s.evidenceAgainst()), s.candidateThought(),
                s.userConfirmedThought(), s.interventionType(), s.actionPlan(), s.actionResult(),
                s.summary(), s.pendingQuestion(),
                json(s.moodPoints()), json(s.distortions()),
                s.contactName(), s.contactPhone(), s.contactRelation(),
                s.safetyAckAt() == null ? null : Timestamp.from(s.safetyAckAt()), s.safetyAckBy(),
                Timestamp.from(s.createdAt()), Timestamp.from(s.updatedAt()), s.id()
        };
        if (jdbc.update(UPDATE, args) == 0) {
            jdbc.update(INSERT, args);
        }
        replaceMessages(s);
        replaceTurns(s);
    }

    private void replaceMessages(CbtSession s) {
        jdbc.update("DELETE FROM cbt_message WHERE session_id = ?", s.id());
        List<CbtMessage> messages = s.history();
        List<Object[]> rows = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            CbtMessage m = messages.get(i);
            rows.add(new Object[]{s.id(), i + 1, m.role(), m.stage().name(), m.content(),
                    Timestamp.from(m.at() == null ? Instant.now() : m.at())});
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO cbt_message (session_id, seq, role, stage, content, at)
                    VALUES (?,?,?,?,?,?)
                    """, rows);
        }
    }

    private void replaceTurns(CbtSession s) {
        jdbc.update("DELETE FROM cbt_turn WHERE session_id = ?", s.id());
        List<CbtTurnEvent> events = s.trace();
        List<Object[]> rows = new ArrayList<>(events.size());
        for (CbtTurnEvent e : events) {
            rows.add(new Object[]{s.id(), e.turnNo(), e.action(),
                    e.stageFrom() == null ? null : e.stageFrom().name(),
                    e.stageTo() == null ? null : e.stageTo().name(),
                    e.status(), e.safetyRule(), e.modelMode(), e.guardRule(), e.latencyMs(),
                    Timestamp.from(e.at() == null ? Instant.now() : e.at())});
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO cbt_turn (session_id, turn_no, action, stage_from, stage_to, status,
                      safety_rule, model_mode, guard_rule, latency_ms, at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, rows);
        }
    }

    @Override
    public Optional<CbtSession> find(String sessionId) {
        List<CbtSession> found = jdbc.query("SELECT * FROM cbt_session WHERE id = ?",
                (rs, i) -> readSession(rs), sessionId);
        if (found.isEmpty()) return Optional.empty();
        CbtSession s = found.get(0);
        loadMessages(s);
        loadTurns(s);
        return Optional.of(s);
    }

    @Override
    public List<CbtSession> findByUser(String userId) {
        // PostgreSQL 无法推断 `? IS NULL` 的类型，所以按是否传 userId 分成两条 SQL。
        String sql = userId == null
                ? "SELECT id FROM cbt_session ORDER BY created_at DESC"
                : "SELECT id FROM cbt_session WHERE user_id = ? ORDER BY created_at DESC";
        List<String> ids = userId == null
                ? jdbc.query(sql, (rs, i) -> rs.getString("id"))
                : jdbc.query(sql, (rs, i) -> rs.getString("id"), userId);
        List<CbtSession> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            find(id).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public List<CbtSession> findVisible(String userId, String accountId) {
        // 分支而不是 OR：账号令牌只看账号名下的，设备令牌只看设备名下的（见接口注释）
        String sql = accountId != null
                ? "SELECT id FROM cbt_session WHERE account_id = ? ORDER BY created_at DESC"
                : "SELECT id FROM cbt_session WHERE user_id = ? ORDER BY created_at DESC";
        List<String> ids = jdbc.query(sql, (rs, i) -> rs.getString("id"),
                accountId != null ? accountId : userId);
        List<CbtSession> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            find(id).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public List<CbtSession> findVisiblePage(String userId, String accountId,
                                            Instant beforeAt, String beforeId, int limit) {
        // 与 findVisible 一样分支而不是 OR：账号令牌只看账号名下的。
        String owner = accountId != null ? "account_id = ?" : "user_id = ?";
        String key = accountId != null ? accountId : userId;
        List<String> ids;
        if (beforeAt == null) {
            ids = jdbc.query("SELECT id FROM cbt_session WHERE " + owner
                            + " ORDER BY created_at DESC, id DESC LIMIT ?",
                    (rs, i) -> rs.getString("id"), key, limit);
        } else {
            Timestamp at = Timestamp.from(beforeAt);
            ids = jdbc.query("SELECT id FROM cbt_session WHERE " + owner
                            + " AND (created_at < ? OR (created_at = ? AND id < ?))"
                            + " ORDER BY created_at DESC, id DESC LIMIT ?",
                    (rs, i) -> rs.getString("id"), key, at, at, beforeId, limit);
        }
        List<CbtSession> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            find(id).ifPresent(out::add);
        }
        return out;
    }

    /**
     * 只认领 account_id 为空的会话：同一台设备换人登录时，
     * 上一个人已经认领过的记录不会被第二个人接手。
     */
    @Override
    public int countClaimable(String userId) {
        if (userId == null) return 0;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cbt_session WHERE user_id = ? AND account_id IS NULL",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    @Override
    public int claim(String userId, String accountId) {
        if (userId == null || accountId == null) return 0;
        return jdbc.update(
                "UPDATE cbt_session SET account_id = ? WHERE user_id = ? AND account_id IS NULL",
                accountId, userId);
    }

    /** 注销账号时把钥匙还回去：会话仍在库里，只是不再属于任何账号。 */
    @Override
    public int releaseAccount(String accountId) {
        if (accountId == null) return 0;
        return jdbc.update("UPDATE cbt_session SET account_id = NULL WHERE account_id = ?", accountId);
    }

    private CbtSession readSession(ResultSet rs) throws java.sql.SQLException {
        CbtSession s = new CbtSession(rs.getString("id"), rs.getString("thread_id"), rs.getString("user_id"));
        s.setAccountId(rs.getString("account_id"));
        s.setStage(CbtStage.valueOf(rs.getString("stage")));
        s.setAwaitingUser(rs.getBoolean("awaiting_user"));
        s.setSafetyStatus(rs.getString("safety_status"));
        s.setSafetyReason(rs.getString("safety_reason"));
        s.setTargetProblem(rs.getString("target_problem"));
        s.setSituation(rs.getString("situation"));
        s.setAutomaticThought(rs.getString("automatic_thought"));
        s.emotions().addAll(list(rs.getString("emotions")));
        s.evidenceFor().addAll(list(rs.getString("evidence_for")));
        s.evidenceAgainst().addAll(list(rs.getString("evidence_against")));
        s.setCandidateThought(rs.getString("candidate_thought"));
        s.setUserConfirmedThought(rs.getString("user_confirmed_thought"));
        s.setInterventionType(rs.getString("intervention_type"));
        s.setActionPlan(rs.getString("action_plan"));
        s.setActionResult(rs.getString("action_result"));
        s.setSummary(rs.getString("summary"));
        s.setPendingQuestion(rs.getString("pending_question"));
        s.restoreMood(moods(rs.getString("mood_points")));
        s.addDistortions(list(rs.getString("distortions")));
        s.setContact(rs.getString("contact_name"), rs.getString("contact_phone"), rs.getString("contact_relation"));
        s.restoreAck(instant(rs, "safety_ack_at"), rs.getString("safety_ack_by"));
        // 时间戳最后恢复：前面的 set 与 add 都会把 updatedAt 刷成"现在"
        s.restoreTimes(instant(rs, "created_at"), instant(rs, "updated_at"));
        return s;
    }

    private void loadMessages(CbtSession s) {
        List<CbtMessage> rows = jdbc.query("""
                SELECT role, stage, content, at FROM cbt_message WHERE session_id = ? ORDER BY seq
                """, (rs, i) -> new CbtMessage(rs.getString("role"),
                CbtStage.valueOf(rs.getString("stage")), rs.getString("content"), instant(rs, "at")), s.id());
        rows.forEach(s::addMessage);
        s.restoreTimes(s.createdAt(), s.updatedAt());
    }

    private void loadTurns(CbtSession s) {
        List<CbtTurnEvent> rows = jdbc.query("""
                SELECT turn_no, action, stage_from, stage_to, status, safety_rule, model_mode,
                       guard_rule, latency_ms, at
                FROM cbt_turn WHERE session_id = ? ORDER BY turn_no
                """, (rs, i) -> new CbtTurnEvent(
                rs.getInt("turn_no"),
                rs.getString("action"),
                stage(rs.getString("stage_from")),
                stage(rs.getString("stage_to")),
                rs.getString("status"),
                rs.getString("safety_rule"),
                rs.getString("model_mode"),
                rs.getString("guard_rule"),
                rs.getLong("latency_ms"),
                instant(rs, "at")), s.id());
        rows.forEach(s::addEvent);
        s.restoreTimes(s.createdAt(), s.updatedAt());
    }

    private static CbtStage stage(String name) {
        return name == null || name.isBlank() ? null : CbtStage.valueOf(name);
    }

    private static Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static String json(List<String> values) {
        try {
            return JSON.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("序列化列表失败", e);
        }
    }

    /** 情绪序列存的是对象而不是字符串，单独一组重载。 */
    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化情绪序列失败", e);
        }
    }

    private static List<MoodPoint> moods(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, MOOD_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> list(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }
}
