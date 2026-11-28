package com.socraticmind.common.prompt;

/**
 * 可运营的提示词与检索参数。
 *
 * 门外与知识问答的默认提示词原来写死在 classpath，管理员改不了；这里把它们变成一份可保存的数据，
 * 与规则/话术一样由后台维护。版本号每次保存 +1，配合 updatedBy 留痕。
 */
public record PromptConfig(
        int version,
        String updatedAt,
        String updatedBy,
        String freeChat,
        String knowledgeAnswer,
        int knowledgeTopK
) {

    public static final int DEFAULT_TOP_K = 4;

    public PromptConfig {
        freeChat = freeChat == null ? "" : freeChat;
        knowledgeAnswer = knowledgeAnswer == null ? "" : knowledgeAnswer;
        if (knowledgeTopK <= 0) knowledgeTopK = DEFAULT_TOP_K;
    }
}
