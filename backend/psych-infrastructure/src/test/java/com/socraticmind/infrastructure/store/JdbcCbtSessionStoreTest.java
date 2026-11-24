package com.socraticmind.infrastructure.store;

import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CbtTurnEvent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 持久化的验收：写进去、换一个 store 实例读出来，东西必须一模一样。
 *
 * 用 H2 的 PostgreSQL 兼容模式跑同一份 DDL——生产是 PostgreSQL，但"能不能重启还活着"
 * 这件事不需要 Docker 才能测。
 */
class JdbcCbtSessionStoreTest {

    private JdbcTemplate template() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:cbt" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        DataSource dataSource = ds;
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("db/schema-cbt.sql"));
        populator.execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private CbtSession session() {
        CbtSession s = new CbtSession("cbt_test1", "thread_1", "u-1");
        s.setStage(CbtStage.COGNITIVE_REVIEW);
        s.setTargetProblem("下周组会要汇报");
        s.setSituation("周一上午，会议室");
        s.setAutomaticThought("我肯定会搞砸");
        s.evidenceAgainst().add("前三次有两次其实还行");
        s.setCandidateThought("「我肯定会搞砸」是一个预测，不是事实");
        s.setAwaitingUser(false);
        s.addMessage(CbtMessage.user(CbtStage.PROBLEM_IDENTIFICATION, "下周组会要汇报"));
        s.addMessage(CbtMessage.assistant(CbtStage.COGNITIVE_EXTRACTION, "那一刻第一句话是什么？"));
        s.addEvent(new CbtTurnEvent(1, "start", null, CbtStage.PROBLEM_IDENTIFICATION,
                "awaiting_user", null, "none", null, 3, Instant.now()));
        s.addEvent(new CbtTurnEvent(2, "answer", CbtStage.PROBLEM_IDENTIFICATION, CbtStage.COGNITIVE_EXTRACTION,
                "awaiting_user", null, "none", null, 5, Instant.now()));
        return s;
    }

    @Test
    void survivesANewStoreInstance() {
        JdbcTemplate jdbc = template();
        new JdbcCbtSessionStore(jdbc).save(session());

        // 换一个实例读：模拟"进程重启后重新连库"，不是从同一份内存里取
        Optional<CbtSession> loaded = new JdbcCbtSessionStore(jdbc).find("cbt_test1");

        assertTrue(loaded.isPresent());
        CbtSession s = loaded.get();
        assertEquals(CbtStage.COGNITIVE_REVIEW, s.stage());
        assertEquals("下周组会要汇报", s.targetProblem());
        assertEquals("我肯定会搞砸", s.automaticThought());
        assertEquals("「我肯定会搞砸」是一个预测，不是事实", s.candidateThought());
        assertEquals(List.of("前三次有两次其实还行"), s.evidenceAgainst());
        assertEquals(2, s.history().size());
        assertEquals("那一刻第一句话是什么？", s.history().get(1).content());
        assertEquals(2, s.trace().size());
        assertEquals(CbtStage.COGNITIVE_EXTRACTION, s.trace().get(1).stageTo());
    }

    @Test
    void keepsOriginalTimestamps() {
        JdbcTemplate jdbc = template();
        CbtSession original = session();
        original.restoreTimes(Instant.parse("2026-11-01T09:00:00Z"), Instant.parse("2026-11-01T09:30:00Z"));
        new JdbcCbtSessionStore(jdbc).save(original);

        CbtSession loaded = new JdbcCbtSessionStore(jdbc).find("cbt_test1").orElseThrow();
        assertEquals(Instant.parse("2026-11-01T09:00:00Z"), loaded.createdAt(),
                "读出来的会话不能看起来像刚创建的，否则列表排序与日志全乱");
    }

    @Test
    void savingTwiceDoesNotDuplicateMessagesOrTurns() {
        JdbcTemplate jdbc = template();
        JdbcCbtSessionStore store = new JdbcCbtSessionStore(jdbc);
        CbtSession s = session();
        store.save(s);
        s.addMessage(CbtMessage.user(CbtStage.COGNITIVE_REVIEW, "前三次有两次其实还行"));
        s.addEvent(new CbtTurnEvent(3, "answer", CbtStage.COGNITIVE_EXTRACTION, CbtStage.COGNITIVE_REVIEW,
                "awaiting_user", null, "template", "novelty", 7, Instant.now()));
        store.save(s);

        assertEquals(s.history().size(), store.find("cbt_test1").orElseThrow().history().size());
        assertEquals(3, store.find("cbt_test1").orElseThrow().trace().size());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM cbt_message WHERE session_id = ?",
                Integer.class, "cbt_test1"));
    }

    @Test
    void findByUserIsNewestFirst() {
        JdbcTemplate jdbc = template();
        JdbcCbtSessionStore store = new JdbcCbtSessionStore(jdbc);

        CbtSession older = new CbtSession("cbt_old", "t1", "u-2");
        older.restoreTimes(Instant.parse("2026-11-01T09:00:00Z"), Instant.parse("2026-11-01T09:00:00Z"));
        CbtSession newer = new CbtSession("cbt_new", "t2", "u-2");
        newer.restoreTimes(Instant.parse("2026-11-05T09:00:00Z"), Instant.parse("2026-11-05T09:00:00Z"));
        store.save(older);
        store.save(newer);

        List<CbtSession> rows = store.findByUser("u-2");
        assertEquals(2, rows.size());
        assertEquals("cbt_new", rows.get(0).id());
    }

    @Test
    void unknownSessionIsEmptyNotAnError() {
        JdbcTemplate jdbc = template();
        assertTrue(new JdbcCbtSessionStore(jdbc).find("cbt_missing").isEmpty());
    }

    @Test
    void nullableFieldsStayNull() {
        JdbcTemplate jdbc = template();
        CbtSession s = new CbtSession("cbt_bare", "t", "u-3");
        new JdbcCbtSessionStore(jdbc).save(s);

        CbtSession loaded = new JdbcCbtSessionStore(jdbc).find("cbt_bare").orElseThrow();
        assertNull(loaded.userConfirmedThought(), "没确认过就必须是 null，不能变成空字符串");
        assertNull(loaded.interventionType());
        assertTrue(loaded.history().isEmpty());
    }

    /** v0.4.0 的新列：情绪序列带 Instant，必须能落库再读回来（曾因缺 JavaTimeModule 直接 503）。 */
    @Test
    void moodContactAndDistortionsSurvive() {
        JdbcTemplate jdbc = template();
        CbtSession s = new CbtSession("cbt_mood", "t", "u-4");
        s.addMood(3, 1);
        s.addMood(7, 3);
        s.setContact("小周", "13800001111", "室友");
        s.addDistortions(List.of("灾难化", "以偏概全"));
        s.setSafetyStatus("hold");
        s.ackSafety("tester");
        new JdbcCbtSessionStore(jdbc).save(s);

        CbtSession loaded = new JdbcCbtSessionStore(jdbc).find("cbt_mood").orElseThrow();
        assertEquals(2, loaded.moodPoints().size());
        assertEquals(3, loaded.moodPoints().get(0).score());
        assertEquals(7, loaded.moodPoints().get(1).score());
        assertEquals("小周", loaded.contactName());
        assertEquals("13800001111", loaded.contactPhone());
        assertEquals("室友", loaded.contactRelation());
        assertEquals(List.of("灾难化", "以偏概全"), loaded.distortions());
        assertEquals("tester", loaded.safetyAckBy());
    }
}
