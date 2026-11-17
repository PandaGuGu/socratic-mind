package com.socraticmind.rag.dto;

import com.socraticmind.common.model.Chunk;
import com.socraticmind.common.model.Citation;
import com.socraticmind.common.model.EvidenceItem;

/** 一条可被引用的证据：定位 + 结构化属性 + 原文块 + 父块上下文。 */
public record PackItem(
        Citation citation,
        EvidenceItem evidence,
        Chunk chunk,
        String parentContext
) {
}
