package com.socraticmind.agent.cbt.safety;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 安全分流：规则通道。
 *
 * 规则不再写死在代码里，而是来自 {@link SafetyRulesStore}（可由后台编辑、带版本与回滚）。
 * 但"可编辑"不等于"随便编辑"：保存前必须通过 {@link SafetyRulesValidator} 的
 * 结构校验 + 固定用例回放，删掉自伤规则或把它改得太窄都会被拒绝。
 *
 * 三种结果：
 * 1. HOLD：疑似本人当下的自伤/伤人意图 → 立即转介，对话暂停；
 * 2. REVIEW：命中风险词但语境指向他人/引用 → 不暂停，进人工复核队列；
 * 3. PASS：没有命中。
 */
@Component
public class SafetyScreener {

    public record Screen(boolean hold, boolean review, String rule, String reason) {
        public static Screen pass() {
            return new Screen(false, false, null, null);
        }

        static Screen hold(String rule, String reason) {
            return new Screen(true, false, rule, reason);
        }

        static Screen review(String rule, String reason) {
            return new Screen(false, true, rule, reason);
        }
    }

    private record Compiled(String id, String label, String note, Pattern pattern) {
    }

    private record RuleSet(SafetyRules rules, List<Compiled> holds, List<Compiled> contexts,
                           List<Compiled> signals, List<Compiled> ignores) {

        static RuleSet of(SafetyRules rules) {
            return new RuleSet(rules,
                    compile(rules, SafetyRule.HOLD),
                    compile(rules, SafetyRule.CONTEXT),
                    compile(rules, SafetyRule.SIGNAL),
                    compile(rules, SafetyRule.IGNORE));
        }

        private static List<Compiled> compile(SafetyRules rules, String level) {
            List<Compiled> out = new ArrayList<>();
            for (SafetyRule r : rules.enabled(level)) {
                out.add(new Compiled(r.id(), r.label(), r.note(),
                        Pattern.compile(r.pattern(), Pattern.CASE_INSENSITIVE)));
            }
            return out;
        }
    }

    private final SafetyRulesStore store;
    private volatile RuleSet cached;

    public SafetyScreener(SafetyRulesStore store) {
        this.store = store;
    }

    public Screen screen(String text) {
        SafetyRules rules = store.get();
        RuleSet set = cached;
        if (set == null || set.rules() != rules) {
            set = RuleSet.of(rules);
            cached = set;
        }
        return decide(set, text);
    }

    /** 供校验与测试使用：直接按给定规则跑一次，不依赖 Spring 容器。 */
    public static Screen screen(SafetyRules rules, String text) {
        return decide(RuleSet.of(rules), text);
    }

    private static Screen decide(RuleSet set, String text) {
        if (text == null || text.isBlank()) return Screen.pass();

        // 中文里空格是噪音，英文里空格是语法：两个变体都跑，避免 "I want to die" 被压成
        // "Iwanttodie" 之后漏判。
        String spaced = text.replaceAll("\\s+", " ").trim();
        String compact = spaced.replace(" ", "");
        String[] variants = {strip(set.ignores(), spaced), strip(set.ignores(), compact)};
        Compiled risk = firstHit(set.holds(), variants);
        if (risk == null) return Screen.pass();

        boolean firstPerson = firstHit(set.signals(), variants) != null;

        if (!firstPerson) {
            Compiled context = firstHit(set.contexts(), variants);
            if (context != null) {
                String why = context.note() == null || context.note().isBlank()
                        ? "命中语境规则 " + context.id() + "，转人工复核"
                        : context.note() + "（规则 " + context.id() + "）";
                return Screen.review(risk.id(), "风险词命中，但" + why);
            }
        }
        return Screen.hold(risk.id(), "命中安全规则：" + risk.id() + "，已暂停并给出求助渠道");
    }

    private static String strip(List<Compiled> ignores, String text) {
        String out = text;
        for (Compiled ignore : ignores) {
            out = ignore.pattern().matcher(out).replaceAll("");
        }
        return out;
    }

    private static Compiled firstHit(List<Compiled> rules, String[] variants) {
        for (Compiled rule : rules) {
            for (String variant : variants) {
                if (rule.pattern().matcher(variant).find()) return rule;
            }
        }
        return null;
    }

    /** 单个正则能否编译；后台保存前用它给出精确到规则的错误。 */
    static String compileError(String pattern) {
        try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription() + "（位置 " + e.getIndex() + "）";
        }
    }
}
