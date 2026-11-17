package com.socraticmind.common.support;

import java.util.concurrent.atomic.AtomicLong;

/** 轻量 ID 生成：可读、有序、够用就好。真正上线换 ULID/Snowflake 时只改这一处。 */
public final class Ids {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 100_000);

    private Ids() {
    }

    public static String of(String prefix) {
        return prefix + "_" + Long.toString(SEQ.incrementAndGet(), 36);
    }
}
