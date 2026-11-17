package com.socraticmind.agent.cbt.flow;

/**
 * 一条分支规则：满足 when 就去 to。
 *
 * when 不是自由表达式，而是白名单里的谓词（见 {@link ConditionWhen}）——
 * 画布给运维的是选择，不是写代码的机会。
 */
public record BranchRule(String when, String to) {
}
