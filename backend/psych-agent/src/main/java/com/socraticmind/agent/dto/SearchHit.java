package com.socraticmind.agent.dto;

/** 检索调试结果：给 /api/knowledge/search 用，能看到每一路的命中与得分。 */
public record SearchHit(
        String chunkId,
        String documentId,
        String documentTitle,
        String section,
        double score,
        String via,
        String preview
) {
}
