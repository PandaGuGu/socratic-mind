package com.socraticmind.agent.cbt.safety;

/**
 * 一条发布用例：一句话 + 期望的判定结果。
 *
 * 它既是测试，也是保存规则时的回放清单。v0.1.2 之前这张表写在 Java 里，
 * 运维想加一条"我们这里常这么说"的口语，只能发版；现在它跟规则一起存在配置里，
 * 后台可增删改，只有"至少留几条"的下限是常量。
 */
public record SafetyCase(String text, String expect, String why) {

    public static final String HOLD = "HOLD";
    public static final String NOT_HOLD = "NOT_HOLD";
    public static final String NOT_PASS = "NOT_PASS";
    public static final String PASS = "PASS";

    public boolean validExpect() {
        return HOLD.equals(expect) || NOT_HOLD.equals(expect)
                || NOT_PASS.equals(expect) || PASS.equals(expect);
    }
}
