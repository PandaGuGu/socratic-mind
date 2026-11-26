package com.socraticmind.infrastructure.ai.rerank;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词法重排的三条性质。这里不测"分数准不准"——词法重排的分数没有绝对意义，
 * 只测它必须满足的排序关系：连在一起的短语 > 散落的词 > 主题相近但没答问题。
 */
class LexicalRerankerTest {

    private final LexicalReranker reranker = new LexicalReranker();

    @Test
    void adjacentPhraseScoresHigherThanScatteredTerms() {
        List<String> query = List.of("认知", "知重", "重构");
        double adjacent = LexicalReranker.scoreOne(query, "认知重构分为四步：识别、检验、替代、巩固。");
        double scattered = LexicalReranker.scoreOne(query,
                "认知模型分三层。重构这件事要慢慢来，中间还夹着别的内容和很长的铺垫。");
        assertTrue(adjacent > scattered,
                "相邻出现必须高于散落出现：adjacent=" + adjacent + " scattered=" + scattered);
    }

    @Test
    void topicalButUnrelatedPassageScoresLow() {
        // 问"睡不着怎么办"，段落只提到"影响睡眠"——这正是召回最容易给高名次的一类噪音。
        List<String> query = List.of("睡不", "不着", "着怎", "怎么", "么办");
        double noise = LexicalReranker.scoreOne(query,
                "如果焦虑持续两周以上、明显影响睡眠或工作学习，建议到精神科门诊评估。");
        assertTrue(noise < 0.45, "只沾到边缘词不应拿高分，实际 " + noise);
    }

    @Test
    void emptyQueryIsUnjudgedNotZero() {
        double[] scores = reranker.score("", List.of(new com.socraticmind.common.port.RerankPort.Passage("a", "任意内容")));
        assertEquals(1, scores.length);
        assertEquals(-1, scores[0], "空查询应当返回 -1（未判断），而不是 0（判为无关）");
    }
}
