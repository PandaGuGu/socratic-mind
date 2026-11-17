package com.socraticmind.common.port;

/**
 * 向量化端口。实现方可以是本地桩、OpenAI 兼容 HTTP 服务或 Spring AI 的 EmbeddingModel。
 * RAG 与工作流都只依赖这个接口，换实现不改调用方（见 ADR-0002）。
 */
public interface EmbeddingPort {

    float[] embed(String text);

    String modelId();

    int dimension();
}
