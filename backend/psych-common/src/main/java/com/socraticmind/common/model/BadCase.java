package com.socraticmind.common.model;

import java.time.Instant;

/**
 * 待复核样本。
 *
 * 来源只有两种，都要记：auto（管道自己判可疑：拒答、引用无效、证据不足）与
 * manual（用户或运营点出来）。只留 manual 会漏掉"系统觉得自己答得挺好"的那一类，
 * 而那正是最该看的一类。
 */
public record BadCase(
        String id,
        String query,
        String answer,
        String reason,
        String source,
        Instant createdAt,
        boolean resolved,
        Instant resolvedAt
) {
    public static final String AUTO = "auto";
    public static final String MANUAL = "manual";

    public BadCase resolve(Instant at) {
        return new BadCase(id, query, answer, reason, source, createdAt, true, at);
    }
}
