package com.socraticmind.agent.knowledge;

import com.socraticmind.common.model.BadCase;
import com.socraticmind.common.model.EvalSummary;
import com.socraticmind.common.model.QueryOutcome;
import com.socraticmind.common.model.RagAnswer;
import com.socraticmind.common.port.EvalStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 在线评测：把"每一轮发生了什么"变成一张能看的表。
 *
 * 三个刻意的设计：
 * 1. **不合成总分**。回答率、拒答率、引用有效率分开报。揉成一个 0.83 只会让人以为在变好，
 *    看不出是哪一段在变差；
 * 2. **拒答进 badcase 池**。拒答不等于错误，但它永远值得看一眼：
 *    要么语料缺了，要么改写没覆盖这种问法，要么门槛太紧。三件事的修法完全不同；
 * 3. **抽样是确定性的**。按固定步长取，不随机——随机会让"今天看的样本"和"昨天看的"不可比，
 *    而人工复核最怕的就是每次看的不是同一批。
 */
@Service
public class OnlineEvalService {

    private final EvalStore store;

    public OnlineEvalService(EvalStore store) {
        this.store = store;
    }

    /** @param answerText 原文只在进 badcase 时才落库，正常轮次不存整段回答。 */
    public void record(QueryOutcome outcome, String answerText) {
        store.record(outcome);

        String reason = null;
        if ("error".equals(outcome.status())) {
            reason = "pipeline_error";
        } else if (RagAnswer.INSUFFICIENT.equals(outcome.status())) {
            reason = "insufficient_evidence";
        } else if (!outcome.citationsValid()) {
            reason = "citation_invalid";
        }
        if (reason != null && !hasOpenCase(outcome.query())) {
            store.addBadCase(outcome.query(), answerText, reason, BadCase.AUTO);
        }
    }

    public EvalSummary summarize(int days) {
        int window = Math.max(1, days);
        Instant since = Instant.now().minus(window, ChronoUnit.DAYS);
        List<QueryOutcome> all = store.recent(since);

        int total = all.size();
        int answered = (int) all.stream().filter(o -> RagAnswer.ANSWERED.equals(o.status())).count();
        int refused = (int) all.stream().filter(o -> RagAnswer.INSUFFICIENT.equals(o.status())).count();
        int failed = total - answered - refused;

        List<QueryOutcome> answeredList = all.stream()
                .filter(o -> RagAnswer.ANSWERED.equals(o.status())).toList();
        int validCitations = (int) answeredList.stream().filter(QueryOutcome::citationsValid).count();

        long[] latencies = all.stream().mapToLong(QueryOutcome::latencyMs).sorted().toArray();

        Map<String, Integer> byStatus = new LinkedHashMap<>();
        all.stream()
                .collect(Collectors.groupingBy(QueryOutcome::status, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> byStatus.put(e.getKey(), e.getValue().intValue()));

        return new EvalSummary(window, total, answered, refused, failed,
                rate(answered, total), rate(refused, total), rate(validCitations, answeredList.size()),
                percentile(latencies, 50), percentile(latencies, 95),
                (int) store.openBadCases(), byStatus,
                topQueries(all.stream().filter(o -> RagAnswer.INSUFFICIENT.equals(o.status())).toList()),
                topQueries(answeredList.stream().filter(o -> !o.citationsValid()).toList()));
    }

    /** 确定性抽样：按步长跨整个窗口取，避免只看最近几条。 */
    public List<QueryOutcome> sample(int days, int n) {
        List<QueryOutcome> all = store.recent(Instant.now().minus(Math.max(1, days), ChronoUnit.DAYS));
        if (all.isEmpty() || n <= 0) return List.of();
        int step = Math.max(1, all.size() / n);
        List<QueryOutcome> out = new ArrayList<>();
        for (int i = 0; i < all.size() && out.size() < n; i += step) {
            out.add(all.get(i));
        }
        return out;
    }

    public List<BadCase> badCases(boolean unresolvedOnly) {
        return store.badCases(unresolvedOnly);
    }

    public boolean resolve(String id) {
        return store.resolveBadCase(id);
    }

    public BadCase report(String query, String answer, String note) {
        return store.addBadCase(query, answer, note == null || note.isBlank() ? "manual" : note,
                BadCase.MANUAL);
    }

    private boolean hasOpenCase(String query) {
        return store.badCases(true).stream().anyMatch(b -> b.query().equals(query));
    }

    private static double rate(int part, int total) {
        return total == 0 ? 0 : Math.round(part * 1000.0 / total) / 1000.0;
    }

    /** 最近秩法分位：小样本下比插值更稳，也不会给出"P95 = 0"这种假象。 */
    static long percentile(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static List<String> topQueries(List<QueryOutcome> outcomes) {
        return outcomes.stream()
                .collect(Collectors.groupingBy(QueryOutcome::query, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 仅供测试：把窗口内的记录按时间排一下，便于断言。 */
    List<QueryOutcome> recentForTest(int days) {
        return store.recent(Instant.now().minus(Math.max(1, days), ChronoUnit.DAYS)).stream()
                .sorted(Comparator.comparing(QueryOutcome::at))
                .toList();
    }
}
