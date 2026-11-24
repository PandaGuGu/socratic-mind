package com.socraticmind.app.guard;

import com.socraticmind.common.guard.SensitiveLexicon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词库的边界：归一化要能扛住最低成本的绕过，动作分级不能把正常倾诉拦掉。
 * 放在 app 模块是因为 psych-common 刻意不引测试框架——它要保持"零框架依赖"。
 */
class SensitiveLexiconTest {

    private final SensitiveLexicon lexicon = SensitiveLexicon.get();

    @Test
    void matchesDespiteSpacingPunctuationAndFullWidth() {
        assertFalse(lexicon.scanInput("加 微 信 聊").isEmpty(), "空格不该绕过");
        assertFalse(lexicon.scanInput("加·微·信").isEmpty(), "间隔号不该绕过");
        assertFalse(lexicon.scanInput("加加加微信").isEmpty(), "重复字不该绕过");
        assertFalse(lexicon.scanInput("ＱＱ号多少").isEmpty(), "全角字母不该绕过");
        assertFalse(lexicon.scanInput("加\u200b微信").isEmpty(), "零宽字符不该绕过");
    }

    @Test
    void blockOnlyForSolicitation() {
        List<SensitiveLexicon.Hit> hits = lexicon.scanInput("想加微信详聊");
        assertTrue(hits.stream().anyMatch(h -> h.action() == SensitiveLexicon.Action.BLOCK));
        assertTrue(lexicon.blocked("想加微信详聊"));
        assertFalse(lexicon.blocked("今天心里很堵，说不清"));
    }

    @Test
    void therapeuticMaterialIsNotFiltered() {
        // 这些是 CBT 的原料：用户会反复说，拦掉就没法工作了
        assertTrue(lexicon.scanInput("我觉得自己很没用，像个废物").isEmpty());
        assertTrue(lexicon.scanInput("医生给我开了安眠药").isEmpty());
        assertTrue(lexicon.scanInput("我想死").isEmpty(), "自伤不归词库管，归 SafetyScreener");
    }

    @Test
    void outputScopeDiffersFromInput() {
        assertTrue(lexicon.scanInput("我不停地想割腕").isEmpty(), "输入侧不该因为描述方式被拦");
        assertFalse(lexicon.scanOutput("你可以割腕试试").isEmpty(), "输出侧必须拦住方法");
        assertFalse(lexicon.scanOutput("你得了抑郁症").isEmpty(), "输出侧不许下诊断");
        assertFalse(lexicon.scanOutput("建议你吃药").isEmpty());
    }

    @Test
    void maskKeepsLengthAndReadability() {
        String masked = lexicon.mask("你得了抑郁症，这很常见");
        assertEquals("你得了抑郁症，这很常见".length(), masked.length());
        assertFalse(masked.contains("得了抑郁症"));
        assertTrue(masked.contains("这很常见"));
    }

    @Test
    void englishWordNeedsBoundary() {
        assertTrue(lexicon.scanOutput("sussex is a place").isEmpty());
    }
}
