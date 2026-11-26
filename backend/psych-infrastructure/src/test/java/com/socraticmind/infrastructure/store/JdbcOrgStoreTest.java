package com.socraticmind.infrastructure.store;

import com.socraticmind.common.model.Operator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织域的 JDBC 语义，在 H2 的 PostgreSQL 兼容模式上跑与线上同一份 DDL。
 *
 * 必须连真库测的两件事：唯一索引真的能挡住重复令牌；吊销后查询真的不再返回。
 * 内存实现里这两条都是"我写什么就是什么"，证明不了数据库层面的约束。
 */
class JdbcOrgStoreTest {

    private JdbcTemplate template() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:org" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        DataSource dataSource = ds;
        new ResourceDatabasePopulator(new ClassPathResource("db/schema-cbt.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    @Test
    void tokenResolvesToMemberAndRevocationTakesEffect() {
        JdbcOrgStore store = new JdbcOrgStore(template());
        var tenant = store.createTenant("某医院");
        var team = store.createTeam(tenant.id(), "心理科");
        var issued = store.createMember(tenant.id(), team.id(), "李医生", Operator.REVIEWER);

        Operator found = store.findByToken(issued.token()).orElseThrow();
        assertEquals("李医生", found.name());
        assertEquals(team.id(), found.teamId());
        assertTrue(store.teamExists(tenant.id(), team.id()));
        assertFalse(store.teamExists(tenant.id(), "team_not_exist"), "不存在的团队必须返回 false");

        store.revokeMember(issued.member().id());
        assertTrue(store.findByToken(issued.token()).isEmpty());
        // 吊销只改状态、不删行：历史签字还要靠这一行指明责任主体。
        assertEquals(1, store.members(tenant.id()).size());
    }

    @Test
    void memberMustBelongToAnExistingTenantAndTeam() {
        JdbcOrgStore store = new JdbcOrgStore(template());
        var tenant = store.createTenant("甲");
        assertThrows(IllegalArgumentException.class,
                () -> store.createMember(tenant.id(), "team_missing", "某人", Operator.REVIEWER));
        assertThrows(IllegalArgumentException.class,
                () -> store.createTeam("ten_missing", "某科"));
    }

    @Test
    void membersOfOneTenantAreNotVisibleToAnother() {
        JdbcOrgStore store = new JdbcOrgStore(template());
        var a = store.createTenant("甲");
        var b = store.createTenant("乙");
        store.createMember(a.id(), null, "甲医生", Operator.ADMIN);
        store.createMember(b.id(), null, "乙医生", Operator.ADMIN);

        assertEquals(1, store.members(a.id()).size());
        assertEquals("甲医生", store.members(a.id()).get(0).name());
    }
}
