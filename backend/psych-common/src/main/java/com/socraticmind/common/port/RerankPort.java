package com.socraticmind.common.port;

import java.util.List;

/**
 * 重排端口：给"查询 × 候选段落"打相关性分。
 *
 * 为什么单独抽一层：召回与排序是两件事。召回回答"有没有进来"，
 * 重排回答"排得对不对"。桩向量与真向量在召回上都接近饱和（Recall@5 = 1.0），
 * 却在 MRR 上互相反转——这正是只看召回看不出、只有重排能改的问题。
 *
 * 实现可以是模型（cross-encoder / bge-reranker），也可以是不调模型的词法重排，
 * 还可以是"外部已判好的分"（用于没有算力时验证管道）。调用方不关心是哪一种，只关心：
 * - {@link #active()} 为 false 时，顺序原样保留（明确列为"没装重排"）；
 * - 返回 -1 或 NaN 表示"这一条我没判断"，调用方不能把它当成 0 分。
 */
public interface RerankPort {

    /** 一条待判段落。带 id 是为了让"外部已判好的分"能按块对齐，而不是靠文本比对。 */
    record Passage(String id, String text) {
    }

    String modelId();

    /** 是否真的改变了排序。false 时调用方必须保留原始顺序，不能假装排过。 */
    boolean active();

    /** 模型分与召回分是否应该混合。词法重排不够权威，混合更稳；真模型应独立给分。 */
    default boolean blendWithPrior() {
        return true;
    }

    /**
     * @param passages 候选段落，顺序与召回顺序一致
     * @return 与 passages 等长的分数数组，越大越相关；-1 或 NaN 表示未判断
     */
    double[] score(String query, List<Passage> passages);
}
