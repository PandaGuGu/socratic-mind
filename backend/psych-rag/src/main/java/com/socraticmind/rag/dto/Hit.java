package com.socraticmind.rag.dto;

import com.socraticmind.common.model.Chunk;

/** 检索命中（融合后）。vectorScore/textScore 保留单路原始分，便于判断是哪一路在起作用。 */
public record Hit(Chunk chunk, double score, String via, double vectorScore, double textScore) {
}
