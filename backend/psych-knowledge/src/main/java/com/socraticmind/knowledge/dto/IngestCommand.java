package com.socraticmind.knowledge.dto;

/** 入库请求：一条知识资料的原文与元数据。 */
public record IngestCommand(
        String collection,
        String title,
        String source,
        String sourceUrl,
        String language,
        boolean publish,
        String content
) {
    public static IngestCommand of(String title, String content, boolean publish) {
        return new IngestCommand("default", title, "未标注来源", null, "zh", publish, content);
    }
}
