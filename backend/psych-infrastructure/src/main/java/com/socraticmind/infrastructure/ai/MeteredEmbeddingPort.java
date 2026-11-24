package com.socraticmind.infrastructure.ai;

import com.socraticmind.common.port.EmbeddingPort;

/** 向量调用同样记账：检索延迟经常被忽略，但它直接决定问答的体感。 */
public class MeteredEmbeddingPort implements EmbeddingPort {

    private final EmbeddingPort delegate;
    private final ModelCallLog log;

    public MeteredEmbeddingPort(EmbeddingPort delegate, ModelCallLog log) {
        this.delegate = delegate;
        this.log = log;
    }

    @Override
    public float[] embed(String text) {
        long t0 = System.nanoTime();
        int in = text == null ? 0 : text.length();
        try {
            float[] out = delegate.embed(text);
            log.record("embedding", delegate.modelId(), ms(t0), in, 0, "ok");
            return out;
        } catch (RuntimeException e) {
            log.record("embedding", delegate.modelId(), ms(t0), in, 0, "error:" + e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    private static long ms(long t0) {
        return Math.max(0, (System.nanoTime() - t0) / 1_000_000);
    }
}
