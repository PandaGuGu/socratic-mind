package com.socraticmind.common.model;

import java.util.List;

/**
 * 知识块。父子两级：父块承载章节上下文，子块是可检索的最小单元。
 *
 * @param parent           是否为父块（父块本身不参与向量检索）
 * @param embedding        向量；父块通常为 null
 * @param documentVersionId 版本标识——指南更新时旧块必须保留，不能覆盖
 */
public record Chunk(
        String id,
        String documentId,
        String documentVersionId,
        String parentId,
        int index,
        String titlePath,
        String sectionPath,
        String content,
        float[] embedding,
        Integer pageStart,
        Integer pageEnd,
        String language,
        boolean parent
) {
    public static Chunk parent(String id, String documentId, String versionId, int index,
                               String titlePath, String sectionPath, String content, String language) {
        return new Chunk(id, documentId, versionId, null, index, titlePath, sectionPath,
                content, null, null, null, language, true);
    }

    public static Chunk child(String id, String documentId, String versionId, String parentId, int index,
                              String titlePath, String sectionPath, String content, float[] embedding,
                              Integer page, String language) {
        return new Chunk(id, documentId, versionId, parentId, index, titlePath, sectionPath,
                content, embedding, page, page, language, false);
    }

    public String location() {
        List<String> parts = new java.util.ArrayList<>();
        if (titlePath != null && !titlePath.isBlank()) parts.add(titlePath);
        if (sectionPath != null && !sectionPath.isBlank()) parts.add(sectionPath);
        if (pageStart != null) parts.add("P." + pageStart);
        return String.join(" · ", parts);
    }
}
