package com.socraticmind.rag.dto;

import java.util.List;

/**
 * 证据包：生成节点的唯一输入。
 *
 * @param evidenceSufficient 由规则判定（命中数、最高分、来源是否可用），不是模型说了算
 */
public record EvidencePack(
        String query,
        List<PackItem> items,
        boolean evidenceSufficient,
        String reason
) {
    public static EvidencePack empty(String query, String reason) {
        return new EvidencePack(query, List.of(), false, reason);
    }
}
