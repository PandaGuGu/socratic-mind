package com.socraticmind.common.model;

import java.time.Instant;

/** 原始文档。status 未到 published 的文档不得进入检索候选集。 */
public record KnowledgeDocument(
        String id,
        String collection,
        String title,
        String source,
        String sourceUrl,
        String version,
        String language,
        String status,
        Instant createdAt
) {
    public static final String DRAFT = "draft";
    public static final String REVIEWING = "reviewing";
    public static final String PUBLISHED = "published";
    public static final String ARCHIVED = "archived";
}
