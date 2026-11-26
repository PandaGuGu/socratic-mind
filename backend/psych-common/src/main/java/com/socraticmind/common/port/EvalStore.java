package com.socraticmind.common.port;

import com.socraticmind.common.model.BadCase;
import com.socraticmind.common.model.QueryOutcome;

import java.time.Instant;
import java.util.List;

/**
 * 评测留痕端口：把"每一轮发生了什么"与"人挑出来的坏样本"落到同一处。
 *
 * 只有离线跑分不叫闭环：离线集是固定的，线上遇到的问题是长出来的。
 * 闭环的最小条件是三件事同时存在——留痕、自动挑可疑样本、能按时间窗口看指标。
 */
public interface EvalStore {

    void record(QueryOutcome outcome);

    /** 某时间点之后的记录，按时间正序。 */
    List<QueryOutcome> recent(Instant since);

    BadCase addBadCase(String query, String answer, String reason, String source);

    List<BadCase> badCases(boolean unresolvedOnly);

    boolean resolveBadCase(String id);

    long openBadCases();

    void clear();
}
