package com.socraticmind.knowledge.dto;

/**
 * 同步结果。
 *
 * @param action created / updated / unchanged / retired
 * @param needsReview 是否重新进入待审核。内容变了必然为 true——
 *                    审核签字是对"那一版文本"的，不是对"这篇文档"的
 */
public record SyncResult(
        String documentId,
        String action,
        boolean changed,
        boolean needsReview,
        String status,
        String version,
        int childChunks
) {
}
