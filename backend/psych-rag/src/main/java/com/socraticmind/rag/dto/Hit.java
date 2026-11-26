package com.socraticmind.rag.dto;

import com.socraticmind.common.model.Chunk;

/**
 * 检索命中（融合 + 重排后）。
 *
 * vectorScore / textScore 保留单路原始分，rerankScore 保留重排分，三者都不覆盖：
 * 出问题时要能回答"是没召回到，还是召回到了没排上来"——这两个结论对应完全不同的修法。
 *
 * rerankScore = -1 表示"重排没判这一条"，不是"0 分"。
 */
public record Hit(Chunk chunk, double score, String via, double vectorScore, double textScore,
                  double rerankScore) {

    /** 未重排时的构造方式：rerankScore 记为 -1（未判断），而不是 0。 */
    public Hit(Chunk chunk, double score, String via, double vectorScore, double textScore) {
        this(chunk, score, via, vectorScore, textScore, -1);
    }

    public boolean reranked() {
        return rerankScore >= 0;
    }

    public Hit withRerank(double newRerankScore) {
        return new Hit(chunk, score, via, vectorScore, textScore, newRerankScore);
    }
}
