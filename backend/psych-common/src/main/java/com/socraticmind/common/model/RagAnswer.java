package com.socraticmind.common.model;

import java.util.List;

/**
 * 一次知识问答的结果。status 为 INSUFFICIENT 时属于正常业务结果，不是异常。
 *
 * @param generatedBy 生成方标识（stub / http:model-name），用于区分"桩输出"与真实模型输出
 * @param trace       节点轨迹，按执行顺序记录，用于排查与评估
 */
public record RagAnswer(
        String query,
        String status,
        String answer,
        List<Citation> citations,
        List<EvidenceItem> evidence,
        Verification verification,
        String generatedBy,
        List<String> trace
) {
    public static final String ANSWERED = "answered";
    public static final String INSUFFICIENT = "insufficient_evidence";
}
