package com.socraticmind.infrastructure.ai;

import com.socraticmind.common.port.ChatPort;

/**
 * 给任何对话实现计时并记账。放在 GuardedChatPort 内层：
 * 守卫触发的重写也是一次真实的模型调用，应该被算进去，而不是只统计"成功的业务调用"。
 */
public class MeteredChatPort implements ChatPort {

    private final ChatPort delegate;
    private final ModelCallLog log;

    public MeteredChatPort(ChatPort delegate, ModelCallLog log) {
        this.delegate = delegate;
        this.log = log;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return measured(systemPrompt, userPrompt);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, String profile) {
        return measured(systemPrompt, userPrompt);
    }

    private String measured(String systemPrompt, String userPrompt) {
        long t0 = System.nanoTime();
        int in = length(systemPrompt) + length(userPrompt);
        try {
            String out = delegate.chat(systemPrompt, userPrompt);
            log.record("chat", delegate.modelId(), ms(t0), in, length(out), "ok");
            return out;
        } catch (RuntimeException e) {
            log.record("chat", delegate.modelId(), ms(t0), in, 0, "error:" + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    private static long ms(long t0) {
        return Math.max(0, (System.nanoTime() - t0) / 1_000_000);
    }
}
