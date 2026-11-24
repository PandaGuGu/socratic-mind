package com.socraticmind.infrastructure.store;

import com.socraticmind.common.account.Account;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号表的持久化语义：能存、能按邮箱找、重复邮箱被数据库挡住、登录时间能更新。
 *
 * 用 H2 的 PostgreSQL 兼容模式跑同一份 DDL，理由与 {@link JdbcCbtSessionStoreTest} 相同：
 * "唯一索引真的生效"这件事，必须在真库上验证，内存实现的 putIfAbsent 证明不了。
 */
class JdbcAccountStoreTest {

    private JdbcTemplate template() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:acct" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        DataSource dataSource = ds;
        new ResourceDatabasePopulator(new ClassPathResource("db/schema-cbt.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private Account account(String id, String email) {
        return new Account(id, email, "pbkdf2-sha256$1$x$y", "小周",
                Instant.parse("2026-11-24T10:00:00Z"), null, 0, null, null);
    }

    @Test
    void savesAndFindsByEmail() {
        JdbcAccountStore store = new JdbcAccountStore(template());
        store.save(account("acc_1", "a@example.com"));

        Account loaded = store.findByEmail("a@example.com").orElseThrow();
        assertEquals("acc_1", loaded.id());
        assertEquals("小周", loaded.displayName());
        assertEquals(Instant.parse("2026-11-24T10:00:00Z"), loaded.createdAt());
        assertTrue(store.findById("acc_missing").isEmpty());
    }

    @Test
    void duplicateEmailIsRejectedByTheIndex() {
        JdbcAccountStore store = new JdbcAccountStore(template());
        store.save(account("acc_1", "a@example.com"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.save(account("acc_2", "a@example.com")));
        assertTrue(e.getMessage().contains("已经注册"));
    }

    @Test
    void touchLoginUpdatesTimestamp() {
        JdbcAccountStore store = new JdbcAccountStore(template());
        store.save(account("acc_1", "a@example.com"));

        Instant login = Instant.parse("2026-11-25T08:30:00Z");
        store.touchLogin("acc_1", login);

        assertEquals(login, store.findById("acc_1").orElseThrow().lastLoginAt());
    }

    @Test
    void securityFieldsRoundTripAndBumpTheEpoch() {
        JdbcAccountStore store = new JdbcAccountStore(template());
        Account created = account("acc_1", "a@example.com")
                .withRecovery("pbkdf2-sha256$1$r$e");
        store.save(created);

        Account loaded = store.findById("acc_1").orElseThrow();
        assertEquals(0, loaded.tokenEpoch());
        assertEquals("pbkdf2-sha256$1$r$e", loaded.recoveryHash());

        Instant used = Instant.parse("2026-12-01T00:00:00Z");
        store.updateSecurity(loaded.withRecoveredPassword("pbkdf2-sha256$1$n$p", 3, used));

        Account after = store.findById("acc_1").orElseThrow();
        assertEquals(3, after.tokenEpoch());
        assertEquals("pbkdf2-sha256$1$n$p", after.passwordHash());
        assertEquals(used, after.recoveryUsedAt());
        assertEquals("a@example.com", after.email(), "改安全字段不能碰邮箱");
    }
}
