package com.socraticmind.agent.cbt.safety;

import java.util.List;

/** 一份安全规则集：规则 + 发布用例，带版本、可整份回滚。 */
public record SafetyRules(
        int version,
        String updatedAt,
        String updatedBy,
        List<SafetyRule> rules,
        List<SafetyCase> cases
) {

    public SafetyRules {
        rules = rules == null ? List.of() : List.copyOf(rules);
        // null 表示"这份数据没带 cases"（旧快照 / 客户端只传了规则），空列表表示"明确删空"。
        // 两者要区分：前者补出厂用例，后者必须被校验拒绝，否则"删光用例"就成了绕过校验的办法。
        cases = cases == null ? null : List.copyOf(cases);
    }

    /** 兼容旧快照：没有 cases 的历史版本。 */
    public SafetyRules(int version, String updatedAt, String updatedBy, List<SafetyRule> rules) {
        this(version, updatedAt, updatedBy, rules, null);
    }

    public List<SafetyRule> enabled(String level) {
        return rules.stream()
                .filter(SafetyRule::on)
                .filter(r -> level.equals(r.level()))
                .toList();
    }

    public SafetyRule byId(String id) {
        return rules.stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    }
}
