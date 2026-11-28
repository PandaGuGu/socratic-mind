package com.socraticmind.common.guard;

/** 档位名。字符串常量集中在这里，避免调用点各写一份拼错的字面量。 */
public final class PolicyProfiles {

    public static final String CBT_BALANCE = "cbt_balance";
    public static final String CBT_SUMMARY = "cbt_summary";
    public static final String FREE_CHAT = "free_chat";
    public static final String RAG_ANSWER = "rag_answer";

    private PolicyProfiles() {
    }
}
