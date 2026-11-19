package com.socraticmind.app.web.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有界幂等表：同一 clientMessageId 重试不重复推进流程。
 *
 * 之前是一个裸 ConcurrentHashMap —— 每次发送都新增一个永不淘汰的键，
 * 长跑进程里就是内存泄漏。这里改成有界 LRU，并显式说明代价：
 * 重启或淘汰后，同一个 id 会再执行一次（幂等只保"短时间内重试"）。
 */
public class IdempotencyCache<V> {

    private final int capacity;
    private final Map<String, V> entries;

    public IdempotencyCache(int capacity) {
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > IdempotencyCache.this.capacity;
            }
        };
    }

    public synchronized V get(String key) {
        return key == null ? null : entries.get(key);
    }

    public synchronized void put(String key, V value) {
        if (key == null || value == null) return;
        entries.put(key, value);
    }

    public synchronized int size() {
        return entries.size();
    }
}
