package com.socraticmind.infrastructure.ai;

import com.socraticmind.common.port.EmbeddingPort;
import com.socraticmind.common.support.TextUtils;

import java.util.List;

/**
 * 确定性桩向量：把词哈希到固定维度并做 L2 归一化。
 *
 * 它不产生语义，只保证"相同文本得到相同向量""有共同词的文本相似度更高"。
 * 用途是让链路在没有 API key 时也能跑通、让测试可重复。真实环境必须切到 http 实现。
 * 输出带 modelId=stub-hash-256，调用方据此可以判断结果不能用于效果评估。
 */
public class HashEmbeddingAdapter implements EmbeddingPort {

    private final int dimension;

    public HashEmbeddingAdapter(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dimension];
        List<String> tokens = TextUtils.tokens(text);
        if (tokens.isEmpty()) return v;
        for (String token : tokens) {
            int h = hash(token);
            int idx = Math.floorMod(h, dimension);
            v[idx] += 1.0f;
            // 第二个落点降低碰撞影响
            v[Math.floorMod(h >>> 8, dimension)] += 0.5f;
        }
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
        }
        return v;
    }

    private static int hash(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }

    @Override
    public String modelId() {
        return "stub-hash-" + dimension;
    }

    @Override
    public int dimension() {
        return dimension;
    }
}
