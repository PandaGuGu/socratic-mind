package com.socraticmind.app.web.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * 多实例限流：计数落库，靠数据库的原子 upsert 换掉进程内的 synchronized。
 *
 * 两条刻意的取舍：
 * 1. **先加后读**：upsert 里用 CASE 处理窗口过期，再读回计数比较上限。
 *    两个语句之间有一个极短窗口，可能多放行一个请求——限流允许这种误差，
 *    而换成"先读后写"会引入更大的误判（并发时集体通过）。
 * 2. **不做精确滑动**：窗口按 {@code epoch / window} 取整，属于固定窗口。
 *    代价是窗口边界可能瞬时放行 2 倍配额；收益是每个请求只碰一行。
 *    要精确滑动就得存每次请求的时间戳，那是 Redis 的活，不是这张表的活。
 */
@Component
@ConditionalOnProperty(name = "psych.rate-limit.store", havingValue = "jdbc")
public class JdbcRateLimiter implements RateLimitPort {

    private final JdbcTemplate jdbc;

    public JdbcRateLimiter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean allow(String key, int limit, Duration window) {
        if (key == null || limit <= 0) return false;
        long seconds = Math.max(1, window.getSeconds());
        long slot = Instant.now().getEpochSecond() / seconds;
        String bucket = key + "|" + seconds + "|" + slot;
        Instant now = Instant.now();
        Instant expires = Instant.ofEpochSecond((slot + 1) * seconds);

        // 不用 ON CONFLICT ... DO UPDATE：H2 只认 MERGE，PG 只认 ON CONFLICT，
        // 一条语句覆盖不了两档，而 h2 档恰恰也开着 jdbc。改成"过期重置 → 自增 → 没有就插入"，
        // 三种数据库都跑得动。并发时插入可能撞主键，撞了就退回自增——损失一次判断，不丢正确性。
        int reset = jdbc.update("""
                UPDATE rate_limit_counter SET hits = 1, expires_at = ?
                WHERE bucket = ? AND expires_at <= ?
                """, Timestamp.from(expires), bucket, Timestamp.from(now));
        if (reset == 0) {
            int bumped = jdbc.update("UPDATE rate_limit_counter SET hits = hits + 1 WHERE bucket = ?", bucket);
            if (bumped == 0) {
                try {
                    jdbc.update("INSERT INTO rate_limit_counter (bucket, hits, expires_at) VALUES (?, 1, ?)",
                            bucket, Timestamp.from(expires));
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    jdbc.update("UPDATE rate_limit_counter SET hits = hits + 1 WHERE bucket = ?", bucket);
                }
            }
        }

        Integer hits = jdbc.queryForObject("SELECT hits FROM rate_limit_counter WHERE bucket = ?",
                Integer.class, bucket);
        return hits == null || hits <= limit;
    }

    /** 清理过期桶：由调用方按低频调度（如每小时一次），不放在请求路径上。 */
    public int purgeExpired() {
        return jdbc.update("DELETE FROM rate_limit_counter WHERE expires_at < ?",
                Timestamp.from(Instant.now().minusSeconds(3600)));
    }
}
