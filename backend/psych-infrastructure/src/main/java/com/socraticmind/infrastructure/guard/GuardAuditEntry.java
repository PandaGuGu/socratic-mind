package com.socraticmind.infrastructure.guard;

import java.time.Instant;
import java.util.List;

/** 一条守卫审计：谁在哪个档位被挡下、挡在哪儿。 */
public record GuardAuditEntry(
        Instant at,
        String profile,
        String outcome,
        List<String> rules,
        String detail,
        String snippet
) {

    public static final String OK = "ok";
    public static final String REPAIRED = "repaired";
    public static final String REJECTED = "rejected";
}
