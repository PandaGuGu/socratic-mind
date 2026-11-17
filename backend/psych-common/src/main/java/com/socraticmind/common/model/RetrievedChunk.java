package com.socraticmind.common.model;

/** 检索命中：块 + 分数 + 来自哪一路检索（vector / text / fused）。 */
public record RetrievedChunk(Chunk chunk, double score, String via) {
    public RetrievedChunk withScore(double newScore, String newVia) {
        return new RetrievedChunk(chunk, newScore, newVia);
    }
}
