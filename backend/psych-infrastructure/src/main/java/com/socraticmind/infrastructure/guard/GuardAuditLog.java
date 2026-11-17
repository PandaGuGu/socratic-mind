package com.socraticmind.infrastructure.guard;

import com.socraticmind.common.guard.GuardViolation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 守卫审计的内存环形缓冲。它不承担持久化，只保证"运维打开页面时能看到刚刚发生了什么"。
 *
 * 与轮次轨迹的分工：轨迹记"这一轮用了模板还是模型"，审计记"模型被哪条规则挡了几次"。
 * 后者跨会话聚合才有意义，所以单独存。
 */
@Component
public class GuardAuditLog {

    private static final int CAPACITY = 200;

    private final Deque<GuardAuditEntry> entries = new ConcurrentLinkedDeque<>();

    public void ok(String profile) {
        push(profile, GuardAuditEntry.OK, List.of(), "", "");
    }

    public void repaired(String profile, List<GuardViolation> violations, String text) {
        push(profile, GuardAuditEntry.REPAIRED, rules(violations), detail(violations), snippet(text));
    }

    public void rejected(String profile, List<GuardViolation> violations, String text) {
        push(profile, GuardAuditEntry.REJECTED, rules(violations), detail(violations), snippet(text));
    }

    public List<GuardAuditEntry> recent(int limit) {
        List<GuardAuditEntry> all = new ArrayList<>(entries);
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    private void push(String profile, String outcome, List<String> rules, String detail, String snippet) {
        entries.addFirst(new GuardAuditEntry(Instant.now(), profile, outcome, rules, detail, snippet));
        while (entries.size() > CAPACITY) {
            entries.pollLast();
        }
    }

    private static List<String> rules(List<GuardViolation> violations) {
        return violations.stream().map(GuardViolation::rule).distinct().toList();
    }

    private static String detail(List<GuardViolation> violations) {
        return violations.stream().map(GuardViolation::detail).reduce((a, b) -> a + "；" + b).orElse("");
    }

    private static String snippet(String text) {
        if (text == null) return "";
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
}
