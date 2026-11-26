package com.socraticmind.infrastructure.ai.rerank;

import com.socraticmind.common.port.RerankPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 不装重排。active() 返回 false，调用方必须保留召回顺序。
 *
 * 这个实现存在的意义不是"兜底"，而是让"没有重排"成为一个**可观测的状态**：
 * 内部页面上写着 "rerank: none" 和写着 "rerank: lexical" 是两件事，
 * 前者出问题时要先怀疑排序，后者要先怀疑重排本身。
 */
@Component
@ConditionalOnProperty(name = "psych.ai.rerank.provider", havingValue = "none")
public class NoopReranker implements RerankPort {

    @Override
    public String modelId() {
        return "none";
    }

    @Override
    public boolean active() {
        return false;
    }

    @Override
    public double[] score(String query, List<Passage> passages) {
        double[] out = new double[passages.size()];
        java.util.Arrays.fill(out, -1);
        return out;
    }
}
