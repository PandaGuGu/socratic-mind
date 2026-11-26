package com.socraticmind.common.model;

import java.util.List;
import java.util.Map;

/**
 * 在线评测摘要。刻意不合成一个"总分"：一个把答对率、拒答率、引用有效率揉成 0.83 的数字，
 * 只会让人以为系统在变好，而看不出是哪一段在变差。
 *
 * @param answeredRate       回答率（answered / total）
 * @param citationValidRate  已回答样本里引用全部有效的比例
 * @param refusalRate        拒答率（insufficient / total）
 */
public record EvalSummary(
        int days,
        int total,
        int answered,
        int refused,
        int failed,
        double answeredRate,
        double refusalRate,
        double citationValidRate,
        long p50LatencyMs,
        long p95LatencyMs,
        int openBadCases,
        Map<String, Integer> byStatus,
        List<String> topRefusedQueries,
        List<String> topUncitedQueries
) {
}
