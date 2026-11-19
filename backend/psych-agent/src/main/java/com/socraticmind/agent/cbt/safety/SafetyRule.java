package com.socraticmind.agent.cbt.safety;

/**
 * 一条安全规则。
 *
 * level 决定它参与哪一步判断：
 * - hold：命中即暂停并转介（自伤、伤人）；
 * - context：语境线索（指向他人、引用），只影响"暂停"还是"转人工复核"；
 * - signal：本人意图信号，用来压过 context，避免"我妈不想活了，我也不想活了"被放行；
 * - ignore：先于匹配抹掉的口语用法（"累死了""想死你了"）。
 *
 * pattern 是 Java 正则。它由后台编辑，保存前必须过 {@link SafetyRulesValidator}：
 * 结构合法、能编译、且固定用例表回放结果不变。
 */
public record SafetyRule(
        String id,
        String label,
        String level,
        String pattern,
        Boolean enabled,
        String note
) {

    public static final String HOLD = "hold";
    public static final String CONTEXT = "context";
    public static final String SIGNAL = "signal";
    public static final String IGNORE = "ignore";

    public boolean on() {
        return enabled == null || enabled;
    }
}
