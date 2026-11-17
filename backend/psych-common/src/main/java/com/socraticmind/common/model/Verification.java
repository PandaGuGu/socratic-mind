package com.socraticmind.common.model;

import java.util.List;

/**
 * 引用校验结果。
 *
 * @param citationCoverage    带引用的陈述句占比（0~1）
 * @param invalidCitationNums 回答里出现但没有对应证据的引用编号
 * @param unsupportedClaims   未带任何引用的事实性陈述（原文摘录）
 */
public record Verification(
        boolean allCitationsValid,
        double citationCoverage,
        List<Integer> invalidCitationNums,
        List<String> unsupportedClaims
) {
    public static Verification empty() {
        return new Verification(true, 0.0, List.of(), List.of());
    }
}
