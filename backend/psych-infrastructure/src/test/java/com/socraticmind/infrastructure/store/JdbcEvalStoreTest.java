package com.socraticmind.infrastructure.store;

import com.socraticmind.common.model.BadCase;
import com.socraticmind.common.model.QueryOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测留痕的 JDBC 语义，在 H2 的 PostgreSQL 兼容模式上跑与线上同一份 DDL。
 *
 * 为什么必须连真库测：时间窗口查询与 BOOLEAN 列在内存实现里怎么写都对，
 * 真正会错的是 SQL——而"最近 7 天"算错不会报错，只会让指标安静地变成 0。
 */
class JdbcEvalStoreTest {

    private JdbcTemplate template() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:eval" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        DataSource dataSource = ds;
        new ResourceDatabasePopulator(new ClassPathResource("db/schema-cbt.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private static QueryOutcome outcome(String query, String status, boolean valid, long latency) {
        return new QueryOutcome(query, status, valid, valid, valid ? 0.95 : 0.0, 3, latency, Instant.now());
    }

    @Test
    void recordsWithinWindowOnly() {
        JdbcEvalStore store = new JdbcEvalStore(template());
        store.record(outcome("自动思维是什么", "answered", true, 120));
        store.record(outcome("自行车链条怎么装", "insufficient_evidence", false, 400));

        List<QueryOutcome> recent = store.recent(Instant.now().minusSeconds(60));
        assertEquals(2, recent.size());
        assertEquals("answered", recent.get(0).status(), "窗口内要按时间正序返回");
        assertEquals(400, recent.get(1).latencyMs());

        assertEquals(0, store.recent(Instant.now().plusSeconds(60)).size(),
                "未来时间点起算必须为空——这条用来证明时间过滤真的生效了");
    }

    @Test
    void badCaseLifecycle() {
        JdbcEvalStore store = new JdbcEvalStore(template());
        BadCase bad = store.addBadCase("睡不着怎么办", "现有资料不足", "insufficient_evidence", BadCase.AUTO);

        assertEquals(1, store.openBadCases());
        assertFalse(store.badCases(true).isEmpty());
        assertEquals("insufficient_evidence", store.badCases(true).get(0).reason());

        assertTrue(store.resolveBadCase(bad.id()));
        assertEquals(0, store.openBadCases(), "标记已处理后不该再计入待复核");
        assertFalse(store.resolveBadCase(bad.id()), "重复标记应当返回 false，而不是假装成功");

        assertEquals(1, store.badCases(false).size(), "已处理样本仍要能在全量里查到");
    }
}
