package com.socraticmind.agent.cbt.safety;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 安全规则的发布校验。
 *
 * v0.1.2 之前这张用例表写在 Java 里、并且要求 self_harm / harm_others 两条规则必须存在——
 * 运维要加一条本地口语、或删掉一条不再适用的规则，都得改代码。
 * 现在规则和用例都在配置里，代码只保留**下限**：
 *
 * 1. 结构：level 合法、id 唯一、正则能编译、长度有上限；
 * 2. 下限：至少一条启用的 hold 规则；用例表至少保留 5 条"必须暂停"、2 条"必须放过"；
 * 3. 回放：用配置里的用例逐条跑，结果必须符合期望。
 *
 * 想把"不想活"从自伤规则里删掉可以，但用例表会立刻指出失效的那几条；
 * 想连用例一起删干净也不行——下限会拦住。这是"可编辑"与"可放水"之间的分界。
 */
public final class SafetyRulesValidator {

    public enum Expect {
        /** 必须暂停。 */
        HOLD,
        /** 不允许暂停（可以复核）。 */
        NOT_HOLD,
        /** 不允许当普通消息放过（暂停或复核都行）。 */
        NOT_PASS,
        /** 必须当普通消息放过：既不暂停也不复核。 */
        PASS
    }

    private static final int MAX_PATTERN = 2000;
    private static final int MAX_LABEL = 24;
    private static final int MAX_NOTE = 240;
    private static final int MAX_CASE_TEXT = 200;
    private static final int MAX_CASE_WHY = 120;

    /** 下限而不是内容：用例可以被改写、被替换，但不能被删空。 */
    private static final int MIN_HOLD_CASES = 5;
    private static final int MIN_PASS_CASES = 2;

    private static final Set<String> LEVELS = Set.of(
            SafetyRule.HOLD, SafetyRule.CONTEXT, SafetyRule.SIGNAL, SafetyRule.IGNORE);

    private SafetyRulesValidator() {
    }

    public static List<String> validateAll(SafetyRules rules) {
        List<String> errors = new ArrayList<>(validateStructure(rules));
        errors.addAll(validateCases(rules));
        if (errors.isEmpty()) errors.addAll(replay(rules));
        return errors;
    }

    public static List<String> validateStructure(SafetyRules rules) {
        List<String> errors = new ArrayList<>();
        if (rules == null || rules.rules().isEmpty()) {
            errors.add("规则集不能为空");
            return errors;
        }

        Set<String> ids = new HashSet<>();
        int enabledHolds = 0;
        for (SafetyRule r : rules.rules()) {
            if (r == null) {
                errors.add("存在空规则");
                continue;
            }
            String id = r.id() == null ? "" : r.id().trim();
            if (id.isEmpty()) {
                errors.add("规则 id 不能为空");
            } else if (!ids.add(id)) {
                errors.add("规则 id 重复：" + id);
            }
            if (r.level() == null || !LEVELS.contains(r.level())) {
                errors.add(id + "：level 必须是 hold / context / signal / ignore");
            }
            String pattern = r.pattern() == null ? "" : r.pattern().trim();
            if (pattern.isEmpty()) {
                errors.add(id + "：正则不能为空");
            } else {
                if (pattern.length() > MAX_PATTERN) errors.add(id + "：正则超过 " + MAX_PATTERN + " 字");
                String compileError = SafetyScreener.compileError(pattern);
                if (compileError != null) errors.add(id + "：正则不合法 —— " + compileError);
            }
            if (r.label() != null && r.label().length() > MAX_LABEL) {
                errors.add(id + "：名称超过 " + MAX_LABEL + " 字");
            }
            if (r.note() != null && r.note().length() > MAX_NOTE) {
                errors.add(id + "：说明超过 " + MAX_NOTE + " 字");
            }
            if (r.on() && SafetyRule.HOLD.equals(r.level())) enabledHolds++;
        }

        if (enabledHolds == 0) {
            errors.add("至少要有一条启用的 hold 规则——否则等于关掉了安全分流");
        }
        return errors;
    }

    public static List<String> validateCases(SafetyRules rules) {
        List<String> errors = new ArrayList<>();
        List<SafetyCase> cases = rules == null || rules.cases() == null ? List.of() : rules.cases();
        if (cases.isEmpty()) {
            errors.add("用例表不能为空：发布校验靠它，删空等于绕过");
            return errors;
        }

        int hold = 0;
        int pass = 0;
        for (SafetyCase c : cases) {
            if (c == null) {
                errors.add("存在空用例");
                continue;
            }
            String text = c.text() == null ? "" : c.text().trim();
            if (text.isEmpty()) {
                errors.add("用例内容不能为空");
            } else if (text.length() > MAX_CASE_TEXT) {
                errors.add("用例「" + text.substring(0, 12) + "…」超过 " + MAX_CASE_TEXT + " 字");
            }
            if (!c.validExpect()) {
                errors.add("用例「" + text + "」的期望值不合法，只能是 HOLD / NOT_HOLD / NOT_PASS / PASS");
            }
            if (c.why() != null && c.why().length() > MAX_CASE_WHY) {
                errors.add("用例「" + text + "」的说明超过 " + MAX_CASE_WHY + " 字");
            }
            if (SafetyCase.HOLD.equals(c.expect())) hold++;
            if (SafetyCase.PASS.equals(c.expect())) pass++;
        }

        if (hold < MIN_HOLD_CASES) {
            errors.add("「必须暂停」的用例至少保留 " + MIN_HOLD_CASES + " 条，当前 " + hold + " 条");
        }
        if (pass < MIN_PASS_CASES) {
            errors.add("「必须放过」的用例至少保留 " + MIN_PASS_CASES + " 条，当前 " + pass + " 条");
        }
        return errors;
    }

    public static List<String> replay(SafetyRules rules) {
        List<String> errors = new ArrayList<>();
        if (rules == null || rules.cases() == null) return List.of("用例表为空，无法回放");
        for (SafetyCase c : rules.cases()) {
            if (c == null || !c.validExpect()) continue;
            SafetyScreener.Screen s = SafetyScreener.screen(rules, c.text());
            if (!matches(c.expect(), s)) {
                errors.add("用例「" + c.text() + "」应" + label(c.expect())
                        + "，当前结果是" + describe(s)
                        + (c.why() == null || c.why().isBlank() ? "" : "（" + c.why() + "）"));
            }
        }
        return errors;
    }

    private static boolean matches(String expect, SafetyScreener.Screen s) {
        return switch (expect) {
            case SafetyCase.HOLD -> s.hold();
            case SafetyCase.NOT_HOLD -> !s.hold();
            case SafetyCase.NOT_PASS -> s.hold() || s.review();
            case SafetyCase.PASS -> !s.hold() && !s.review();
            default -> true;
        };
    }

    private static String label(String expect) {
        return switch (expect) {
            case SafetyCase.HOLD -> "暂停并转介";
            case SafetyCase.NOT_HOLD -> "不暂停";
            case SafetyCase.NOT_PASS -> "至少进人工复核";
            case SafetyCase.PASS -> "按普通消息放过";
            default -> expect;
        };
    }

    private static String describe(SafetyScreener.Screen s) {
        if (s.hold()) return "暂停（" + s.rule() + "）";
        if (s.review()) return "复核（" + s.rule() + "）";
        return "放过";
    }
}
