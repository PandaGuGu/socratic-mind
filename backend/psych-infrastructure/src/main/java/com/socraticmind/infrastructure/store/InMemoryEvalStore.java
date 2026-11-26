package com.socraticmind.infrastructure.store;

import com.socraticmind.common.model.BadCase;
import com.socraticmind.common.model.QueryOutcome;
import com.socraticmind.common.port.EvalStore;
import com.socraticmind.common.support.Ids;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现：开发与测试用。
 *
 * 记录上限是刻意设的（{@value #MAX_OUTCOMES} 条）：评测留痕不能无限长，
 * 否则第一次上线就会把内存吃满；要看更长的窗口应该开 JDBC 档。
 */
@Component
@ConditionalOnProperty(name = "psych.eval.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryEvalStore implements EvalStore {

    private static final int MAX_OUTCOMES = 5_000;

    private final Deque<QueryOutcome> outcomes = new ArrayDeque<>();
    private final Map<String, BadCase> badCases = new ConcurrentHashMap<>();

    @Override
    public synchronized void record(QueryOutcome outcome) {
        outcomes.addLast(outcome);
        while (outcomes.size() > MAX_OUTCOMES) {
            outcomes.removeFirst();
        }
    }

    @Override
    public synchronized List<QueryOutcome> recent(Instant since) {
        List<QueryOutcome> out = new ArrayList<>();
        for (QueryOutcome o : outcomes) {
            if (since == null || !o.at().isBefore(since)) out.add(o);
        }
        return out;
    }

    @Override
    public BadCase addBadCase(String query, String answer, String reason, String source) {
        BadCase bad = new BadCase(Ids.of("bad"), query, answer, reason, source, Instant.now(), false, null);
        badCases.put(bad.id(), bad);
        return bad;
    }

    @Override
    public List<BadCase> badCases(boolean unresolvedOnly) {
        return badCases.values().stream()
                .filter(b -> !unresolvedOnly || !b.resolved())
                .sorted(Comparator.comparing(BadCase::createdAt).reversed())
                .toList();
    }

    @Override
    public boolean resolveBadCase(String id) {
        BadCase existing = badCases.get(id);
        if (existing == null) return false;
        badCases.put(id, existing.resolve(Instant.now()));
        return true;
    }

    @Override
    public long openBadCases() {
        return badCases.values().stream().filter(b -> !b.resolved()).count();
    }

    @Override
    public void clear() {
        outcomes.clear();
        badCases.clear();
    }
}
