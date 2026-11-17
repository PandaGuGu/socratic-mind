package com.socraticmind.app;

import com.socraticmind.common.guard.GuardRules;
import com.socraticmind.common.guard.GuardViolation;
import com.socraticmind.common.guard.ProfileRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫规则的验收。这些断言是"运维点了开关之后到底会发生什么"的唯一说明书。
 */
class GuardRulesTest {

    private static final String SOURCE = "原想法：我肯定会搞砸\n新信息：前三次有两次其实还行，组长只提了数据口径";

    private static final ProfileRule RULE =
            new ProfileRule(true, 8, 40, List.of("你应该", "加油"), 0.3, 1, "测试用");

    @Test
    void outputBuiltFromInputPasses() {
        List<GuardViolation> violations =
                GuardRules.inspect("前三次有两次其实还行，也可能不会搞砸。", SOURCE, RULE);
        assertTrue(violations.isEmpty(), "改写自输入的句子不应该被拦：" + violations);
    }

    @Test
    void bannedPhraseIsCaught() {
        List<GuardViolation> violations = GuardRules.inspect("你应该早点休息，别太紧张。", SOURCE, RULE);
        assertTrue(violations.stream().anyMatch(v -> v.rule().equals(GuardViolation.BANNED)));
    }

    @Test
    void tooShortAndTooLongAreCaught() {
        assertTrue(GuardRules.inspect("好的", SOURCE, RULE).stream()
                .anyMatch(v -> v.rule().equals(GuardViolation.MIN_CHARS)));
        String longText = "前三次有两次其实还行".repeat(5);
        assertTrue(GuardRules.inspect(longText, SOURCE, RULE).stream()
                .anyMatch(v -> v.rule().equals(GuardViolation.MAX_CHARS)));
    }

    @Test
    void inventedContentIsCaughtByOverlap() {
        List<GuardViolation> violations = GuardRules.inspect("你一定是个很有价值、很值得被爱的人。", SOURCE, RULE);
        assertTrue(violations.stream().anyMatch(v -> v.rule().equals(GuardViolation.NOVELTY)));
    }

    @Test
    void noveltyThresholdZeroDisablesTheCheck() {
        ProfileRule loose = new ProfileRule(true, 1, 200, List.of(), 0, 0, "不查编造");
        assertTrue(GuardRules.inspect("你一定是个很有价值、很值得被爱的人。", SOURCE, loose).isEmpty());
    }

    @Test
    void overlapScoreMovesInTheExpectedDirection() {
        double reused = GuardRules.noveltyScore("前三次有两次其实还行", SOURCE);
        double invented = GuardRules.noveltyScore("你值得拥有更好的未来", SOURCE);
        assertTrue(reused > invented, "复用输入的输出，重合度必须更高：" + reused + " vs " + invented);
        assertTrue(reused > 0.95);
    }

    @Test
    void disabledOrMissingRuleIsAPassThrough() {
        ProfileRule off = new ProfileRule(false, 8, 40, List.of("你应该"), 0.9, 1, "关掉");
        assertTrue(GuardRules.inspect("你应该早点休息。", SOURCE, off).isEmpty());
        assertTrue(GuardRules.inspect("你应该早点休息。", SOURCE, null).isEmpty());
    }

    @Test
    void emptyOutputIsItsOwnViolation() {
        List<GuardViolation> violations = GuardRules.inspect("   ", SOURCE, RULE);
        assertEquals(1, violations.size());
        assertEquals(GuardViolation.EMPTY, violations.get(0).rule());
    }

    @Test
    void emptyOutputDoesNotThrowOnNullSource() {
        assertFalse(GuardRules.inspect("前三次有两次其实还行", null, RULE).stream()
                .anyMatch(v -> v.rule().equals(GuardViolation.NOVELTY)),
                "没有参考输入时不应该判编造");
    }
}
