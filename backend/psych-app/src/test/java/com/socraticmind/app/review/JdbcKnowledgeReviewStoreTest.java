package com.socraticmind.app.review;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 审核留痕落库后的语义：同版本覆盖、跨版本累积、ACL 快照原样保存。
 *
 * 在 H2 上跑同一份 DDL：h2 档也开着 jdbc 存储，如果只有 PG 能跑，
 * 本机开发会一路正常、上线才发现写不进去。
 */
class JdbcKnowledgeReviewStoreTest {

    private JdbcTemplate template() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:rev" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        DataSource dataSource = ds;
        new ResourceDatabasePopulator(new ClassPathResource("db/schema-cbt.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    @Test
    void sameVersionOverwritesAndNewVersionAccumulates() {
        JdbcKnowledgeReviewStore store = new JdbcKnowledgeReviewStore(template());

        store.save("doc_1", "某资料", "v1", "approve", "mem_1", "李医生", "首审", null);
        store.save("doc_1", "某资料", "v1", "reject", "mem_2", "王医生", "改为退回", "team:t1");

        assertEquals(1, store.forDocument("doc_1").size(), "同一版本只留一条结论");
        var current = store.find("doc_1", "v1");
        assertEquals("reject", current.decision());
        assertEquals("王医生", current.operatorName(), "覆盖时署名也要跟着换，否则签的人和结论对不上");
        assertEquals("team:t1", current.aclSnapshot());

        store.save("doc_1", "某资料", "v2", "approve", "mem_1", "李医生", null, null);
        assertEquals(2, store.forDocument("doc_1").size(), "改版必须留下新的一条：旧签字只对 v1 有效");
        assertNull(store.find("doc_1", "v2").aclSnapshot(), "没设范围就是公开，快照记空，不补默认值");
    }
}
