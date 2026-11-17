package com.socraticmind.rag.dto;

import com.socraticmind.common.model.Chunk;

/** 检索命中（融合后）。 */
public record Hit(Chunk chunk, double score, String via) {
}
