package com.socraticmind.app.web.support;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多实例限流的 JDBC 语义。
 *
 * 测的是"计数真的落到同一行"：同名桶在两个实例上应当共享额度，
 * 而这正是进程内实现做不到、也是扩实例时最先出事的地方。
 */
class JdbcRateLimiterTest {

    private JdbcTemplate template() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:rl" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        DataSource dataSource = ds;
        new ResourceDatabasePopulator(new ClassPathResource("db/schema-cbt.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    @Test
    void sameBucketSharesQuotaAcrossInstances() {
        JdbcTemplate jdbc = template();
        JdbcRateLimiter instanceA = new JdbcRateLimiter(jdbc);
        JdbcRateLimiter instanceB = new JdbcRateLimiter(jdbc);
        Duration window = Duration.ofMinutes(1);

        assertTrue(instanceA.allow("cbt:msg:u1", 3, window));
        assertTrue(instanceB.allow("cbt:msg:u1", 3, window), "换一个实例也要消耗同一个配额");
        assertTrue(instanceA.allow("cbt:msg:u1", 3, window));
        assertFalse(instanceB.allow("cbt:msg:u1", 3, window), "第 4 次必须被挡住");

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM rate_limit_counter", Integer.class),
                "同一个窗口只应有一行计数，而不是每个实例各写一行");
    }

    @Test
    void differentKeysDoNotInterfere() {
        JdbcRateLimiter limiter = new JdbcRateLimiter(template());
        Duration window = Duration.ofMinutes(1);

        assertTrue(limiter.allow("cbt:msg:u1", 1, window));
        assertFalse(limiter.allow("cbt:msg:u1", 1, window));
        assertTrue(limiter.allow("cbt:msg:u2", 1, window), "不同用户互不影响");
    }

    @Test
    void expiredBucketsCanBePurged() {
        JdbcTemplate jdbc = template();
        JdbcRateLimiter limiter = new JdbcRateLimiter(jdbc);
        limiter.allow("cbt:msg:u1", 1, Duration.ofMinutes(1));

        jdbc.update("UPDATE rate_limit_counter SET expires_at = now() - interval '2' hour");
        assertTrue(limiter.purgeExpired() >= 1);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rate_limit_counter", Integer.class));
    }
}
