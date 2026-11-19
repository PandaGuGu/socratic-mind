package com.socraticmind.app.web.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 进程内滑动窗口限流。
 *
 * 只防"一个人把库和模型打爆"，不做配额计费。多实例部署时每个实例各算一份，
 * 真正的分布式限流要落到网关——这里先保证单机不会被打穿。
 * 键的数量有上限（LRU），避免限流器自己变成内存泄漏。
 */
public class RateLimiter {

    private static final int MAX_KEYS = 20_000;

    private final Map<String, Deque<Instant>> hits = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
            return size() > MAX_KEYS;
        }
    };

    public boolean allow(String key, int limit, Duration window) {
        if (key == null) return false;
        Instant now = Instant.now();
        Instant floor = now.minus(window);
        synchronized (hits) {
            Deque<Instant> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
            while (!q.isEmpty() && q.peekFirst().isBefore(floor)) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
            return true;
        }
    }
}
