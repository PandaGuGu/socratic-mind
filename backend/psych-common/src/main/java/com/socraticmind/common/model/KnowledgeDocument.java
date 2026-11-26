package com.socraticmind.common.model;

import java.time.Instant;

/**
 * 原始文档。status 未到 published 的文档不得进入检索候选集。
 *
 * 后四个字段是一组"生命周期 + 权限"契约，缺一个就会在上线三个月内出事：
 * - {@code contentHash}：内容指纹。同步时先比哈希，一致就不重新切分与向量化；
 * - {@code externalKey}：外部系统的主键（工单号、仓库路径）。没有它，同步只能靠标题猜，
 *   改个标题就变成一篇新文档，旧块永远留在索引里；
 * - {@code aclScope}：逗号分隔的可见范围（如 {@code team:cbt,role:clinician}），空表示公开；
 * - {@code deletedAt}：软删除时间。检索先看它，物理删另给一条命令——删错了还能捞回来。
 */
public record KnowledgeDocument(
        String id,
        String collection,
        String title,
        String source,
        String sourceUrl,
        String version,
        String language,
        String status,
        Instant createdAt,
        String aclScope,
        String contentHash,
        String externalKey,
        Instant deletedAt
) {
    public static final String DRAFT = "draft";
    public static final String REVIEWING = "reviewing";
    public static final String PUBLISHED = "published";
    public static final String ARCHIVED = "archived";

    /** 旧调用点保持可用：不传新字段时按"公开、未删除、无外部键"处理。 */
    public KnowledgeDocument(String id, String collection, String title, String source, String sourceUrl,
                             String version, String language, String status, Instant createdAt) {
        this(id, collection, title, source, sourceUrl, version, language, status, createdAt,
                null, null, null, null);
    }

    public boolean deleted() {
        return deletedAt != null;
    }

    /** 内容变更后必须重新走审核：旧结论是人对旧文本签的字，不能自动继承。 */
    public KnowledgeDocument nextVersion(String newVersion, String newHash, String newStatus) {
        return new KnowledgeDocument(id, collection, title, source, sourceUrl, newVersion, language,
                newStatus, createdAt, aclScope, newHash, externalKey, deletedAt);
    }

    public KnowledgeDocument withAcl(String newAclScope) {
        return new KnowledgeDocument(id, collection, title, source, sourceUrl, version, language,
                status, createdAt, newAclScope, contentHash, externalKey, deletedAt);
    }
}
