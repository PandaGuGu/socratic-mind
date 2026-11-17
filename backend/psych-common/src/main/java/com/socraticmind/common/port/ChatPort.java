package com.socraticmind.common.port;

/**
 * 对话模型端口。刻意保持最小：只有"给系统提示 + 用户输入，拿回一段文本"。
 * 节点级的分工（谁调模型、谁不调）由 agent 层决定，不在这里体现。
 *
 * 第三个参数是输出档位（{@code cbt_balance} / {@code cbt_summary} …）：
 * 它不改变模型调用本身，只是告诉实现方"这次产出该按哪套规则校验"。
 * 不关心档位的实现（桩、HTTP 适配器）用默认方法即可。
 */
public interface ChatPort {

    String chat(String systemPrompt, String userPrompt);

    default String chat(String systemPrompt, String userPrompt, String profile) {
        return chat(systemPrompt, userPrompt);
    }

    String modelId();
}
