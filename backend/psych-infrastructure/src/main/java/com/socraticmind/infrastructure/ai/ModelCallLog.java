package com.socraticmind.infrastructure.ai;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * 最近若干次模型调用的"仪表盘"：延迟、估算 token、成功/失败。
 *
 * 为什么在内存里只留最近 300 条：这是运维与验收用的，不是计费系统。
 * 需要长期账单就该接上游的 usage 日志，而不是在这里攒一个大表。
 *
 * token 是**估算**（按字符数除 1.7），因为适配器只拿文本、不解析 usage；
 * 页面上必须写"估算"，否则一个近似值会被当成账单。
 */
@Component
public class ModelCallLog {

    private static final int CAPACITY = 300;

    public record Call(String kind, String model, long ms, int inputChars, int outputChars,
                       int estTokens, String outcome, Instant at) {
    }

    public record Summary(int calls, int errors, long p50ms, long p95ms, long avgMs, long estTokens) {
    }

    private final Deque<Call> calls = new ArrayDeque<>(CAPACITY);

    public synchronized void record(String kind, String model, long ms,
                                    int inputChars, int outputChars, String outcome) {
        int tokens = estimateTokens(inputChars) + estimateTokens(outputChars);
        calls.addFirst(new Call(kind, model, ms, inputChars, outputChars, tokens, outcome, Instant.now()));
        while (calls.size() > CAPACITY) calls.removeLast();
    }

    public synchronized List<Call> recent(int limit) {
        int max = Math.max(1, Math.min(limit, CAPACITY));
        return calls.stream().limit(max).toList();
    }

    public synchronized Summary summary() {
        if (calls.isEmpty()) return new Summary(0, 0, 0, 0, 0, 0);
        List<Long> durations = new ArrayList<>();
        int errors = 0;
        long tokens = 0;
        long total = 0;
        for (Call c : calls) {
            durations.add(c.ms());
            total += c.ms();
            tokens += c.estTokens();
            if (!"ok".equals(c.outcome())) errors++;
        }
        durations.sort(Comparator.naturalOrder());
        long p50 = percentile(durations, 0.50);
        long p95 = percentile(durations, 0.95);
        return new Summary(calls.size(), errors, p50, p95, total / calls.size(), tokens);
    }

    private static long percentile(List<Long> sorted, double p) {
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    /** 中文约 1.7 字一个 token；英文约 4 字符。取折中，页面标注"估算"。 */
    static int estimateTokens(int chars) {
        return chars <= 0 ? 0 : (int) Math.ceil(chars / 1.7);
    }
}
