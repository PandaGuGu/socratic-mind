package com.socraticmind.common.model;

/** 引用定位：回答里第 n 条引用指向哪一条证据、哪一页。 */
public record Citation(
        int number,
        String evidenceId,
        String chunkId,
        String documentId,
        String documentTitle,
        String source,
        Integer page,
        String section,
        String sourceUrl
) {
    public String display() {
        StringBuilder sb = new StringBuilder();
        sb.append(documentTitle == null ? "未命名资料" : documentTitle);
        if (section != null && !section.isBlank()) sb.append(" · ").append(section);
        if (page != null) sb.append(" · P.").append(page);
        return sb.toString();
    }
}
