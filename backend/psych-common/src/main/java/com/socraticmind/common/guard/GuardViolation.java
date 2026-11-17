package com.socraticmind.common.guard;

import java.util.List;

/**
 * 一次校验发现的问题。rule 是机器可读的规则名，detail 给人看，fragment 是命中的原文片段。
 */
public record GuardViolation(String rule, String detail, String fragment) {

    public static final String EMPTY = "empty";
    public static final String MIN_CHARS = "min_chars";
    public static final String MAX_CHARS = "max_chars";
    public static final String BANNED = "banned";
    public static final String NOVELTY = "novelty";

    public static String label(String rule) {
        return switch (rule) {
            case EMPTY -> "输出为空";
            case MIN_CHARS -> "太短";
            case MAX_CHARS -> "太长";
            case BANNED -> "禁句";
            case NOVELTY -> "疑似编造";
            default -> rule;
        };
    }
}
