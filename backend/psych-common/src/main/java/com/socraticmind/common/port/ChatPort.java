package com.socraticmind.common.port;

/**
 * 对话模型端口。刻意保持最小：只有"给系统提示 + 用户输入，拿回一段文本"。
 * 节点级的分工（谁调模型、谁不调）由 agent 层决定，不在这里体现。
 */
public interface ChatPort {

    String chat(String systemPrompt, String userPrompt);

    String modelId();
}
