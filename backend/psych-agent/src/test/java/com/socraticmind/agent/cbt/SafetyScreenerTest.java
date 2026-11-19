package com.socraticmind.agent.cbt.safety;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全分流的规则表（用内置默认规则跑）。
 *
 * 这组用例比任何注释都重要：它定义了"什么话会让系统停下来"。
 * 现在规则可以由后台编辑，但同一张表也是发布门禁（{@link SafetyRulesValidator#cases()}），
 * 改规则时必须过同一关——测试与线上校验共用一份期望。
 */
class SafetyScreenerTest {

    private static SafetyScreener.Screen screen(String text) {
        return SafetyScreener.screen(SafetyRuleDefaults.rules(), text);
    }

    @Test
    void holdsFirstPersonIntent() {
        List<String> cases = List.of(
                "我真的不想活了",
                "我想死",
                "最近总想着自杀",
                "活着没意思，想消失",
                "我不想再醒来了",
                "真的撑不下去了",
                "我准备跳楼",
                "我想割腕",
                "I want to die",
                "kill myself",
                "我妈不想活了，我也不想活了",
                "新闻里看到有人跳楼，我也不想活了");
        for (String text : cases) {
            SafetyScreener.Screen s = screen(text);
            assertTrue(s.hold(), "应暂停并转介：" + text);
            assertFalse(s.review(), "HOLD 不应同时是 REVIEW：" + text);
        }
    }

    @Test
    void thirdPartyOrQuotedGoesToReviewNotHold() {
        List<String> cases = List.of(
                "我朋友说他不想活了",
                "同事说他最近总说想死",
                "书里主人公自杀了",
                "看了一部电影，里面有个角色割腕",
                "新闻里看到有人跳楼");
        for (String text : cases) {
            SafetyScreener.Screen s = screen(text);
            assertFalse(s.hold(), "他人/引用语境不应直接暂停：" + text);
            assertTrue(s.review(), "但必须进人工复核：" + text);
        }
    }

    @Test
    void idiomsAndEverydayTalkPass() {
        List<String> cases = List.of(
                "累死了，今天加班到十点",
                "笑死我了",
                "想死你了",
                "他把我气死了",
                "我最近很焦虑，睡不着",
                "明天要汇报，一想到就手心出汗");
        for (String text : cases) {
            SafetyScreener.Screen s = screen(text);
            assertFalse(s.hold(), "日常表达不应暂停：" + text);
            assertFalse(s.review(), "日常表达也不该进复核队列：" + text);
        }
    }

    @Test
    void emptyAndNullAreSafe() {
        assertFalse(screen(null).hold());
        assertFalse(screen("   ").hold());
        assertFalse(screen("").review());
    }
}
