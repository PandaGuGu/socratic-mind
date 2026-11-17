package com.socraticmind.agent.cbt.flow;

import java.util.List;

/**
 * 判断节点能用的条件。只保留"能从会话状态确定性地算出来"的几条：
 * 干预类型、是否已确认、兜底。
 *
 * 不提供"模型判断"这类条件——那会把路由交回给模型，与第 5 章"路由由代码决定"直接冲突。
 */
public final class ConditionWhen {

    public static final String INTERVENTION_BEHAVIORAL = "intervention=behavioral_experiment";
    public static final String INTERVENTION_COGNITIVE = "intervention=cognitive_restructuring";
    public static final String INTERVENTION_OBSERVE = "intervention=self_monitoring";
    public static final String CONFIRMED = "confirmed";
    public static final String NOT_CONFIRMED = "not_confirmed";
    public static final String ELSE = "else";

    public static final List<String> ALL = List.of(
            INTERVENTION_BEHAVIORAL, INTERVENTION_COGNITIVE, INTERVENTION_OBSERVE,
            CONFIRMED, NOT_CONFIRMED, ELSE);

    private ConditionWhen() {
    }

    public static String label(String when) {
        return switch (when == null ? "" : when) {
            case INTERVENTION_BEHAVIORAL -> "干预类型 = 行为实验";
            case INTERVENTION_COGNITIVE -> "干预类型 = 认知重构";
            case INTERVENTION_OBSERVE -> "干预类型 = 自我观察";
            case CONFIRMED -> "用户已确认说法";
            case NOT_CONFIRMED -> "用户尚未确认";
            case ELSE -> "其它情况（兜底）";
            default -> when;
        };
    }
}
