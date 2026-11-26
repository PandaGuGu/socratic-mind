package com.socraticmind.common.model;

import java.time.Instant;

/**
 * 一次问答的结果留痕。不是埋点数据，是"这一轮到底发生了什么"的最小复现单元。
 *
 * 为什么要存 sufficient 和 citationsValid 两个布尔，而不是只存 status：
 * 前者是检索前的判定，后者是生成后的校验。两者都通过却仍然答错，才是模型的问题；
 * 两者之一没通过，问题在管道。混在一起就永远分不清该修哪一段。
 */
public record QueryOutcome(
        String query,
        String status,
        boolean sufficient,
        boolean citationsValid,
        double coverage,
        int hits,
        long latencyMs,
        Instant at
) {
}
