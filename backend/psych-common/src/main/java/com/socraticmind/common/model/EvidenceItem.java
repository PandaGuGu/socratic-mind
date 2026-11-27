package com.socraticmind.common.model;

import java.util.List;

/**
 * 证据单元：把普通文本转成带证据属性的知识单元。
 * 无法确认的字段一律 null —— 不允许模型补全（见 docs/02-架构/03-RAG与知识库.md §2）。
 */
public record EvidenceItem(
        String id,
        String chunkId,
        String evidenceType,
        String condition,
        String population,
        String intervention,
        String recommendationStrength,
        String evidenceQuality,
        List<String> limitations,
        String reviewStatus
) {
    public static EvidenceItem plain(String id, String chunkId, String reviewStatus) {
        return new EvidenceItem(id, chunkId, "descriptive_text", null, null, null, null, null,
                List.of(), reviewStatus);
    }

    public boolean structured() {
        return condition != null || population != null || intervention != null
                || recommendationStrength != null;
    }
}
