package com.socraticmind.knowledge.dto;

public record IngestResult(
        String documentId,
        String versionId,
        int parentChunks,
        int childChunks,
        int evidenceCount,
        String status
) {
}
