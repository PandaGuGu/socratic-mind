package com.socraticmind.infrastructure.ai;

import com.socraticmind.common.port.ChatPort;

/**
 * 可在运行时切换后端的对话端口。
 *
 * 外层仍然是 GuardedChatPort + MeteredChatPort：换模型不能绕过输出守卫，
 * 也不能让调用日志断掉。这里只负责把"当前用哪个适配器"变成可替换的。
 */
public class SwitchableChatPort implements ChatPort {

    private volatile ChatPort delegate;

    public SwitchableChatPort(ChatPort initial) {
        this.delegate = initial;
    }

    public void switchTo(ChatPort next) {
        if (next != null) this.delegate = next;
    }

    public ChatPort delegate() {
        return delegate;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return delegate.chat(systemPrompt, userPrompt);
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }
}
