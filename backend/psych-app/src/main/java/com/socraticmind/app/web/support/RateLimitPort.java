package com.socraticmind.app.web.support;

import java.time.Duration;

/**
 * 限流端口。
 *
 * 抽这一层是为了多实例：进程内的滑动窗口在每个副本上各算一份，扩到两台机器时
 * 实际配额直接翻倍——攻击者只要撞到不同的实例就能绕过。
 *
 * 两档实现：memory 单机（默认，够开发用）与 jdbc（多实例共享计数）。
 * 真正的生产方案通常是网关或 Redis；这里给的是"没有额外中间件时也能一致"的退路。
 */
public interface RateLimitPort {

    /**
     * @param key    限流维度（如 {@code cbt:msg:<user>}），不同维度互不影响
     * @param limit  窗口内允许的次数
     * @param window 窗口长度
     * @return 本次是否放行
     */
    boolean allow(String key, int limit, Duration window);
}
