package com.socraticmind.agent.cbt.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全规则"可编辑"的边界。
 *
 * 三条必须成立：
 * 1. 想加词、加变体 —— 能保存；
 * 2. 想把自伤规则改窄/删掉 —— 保存被拒，旧规则继续生效；
 * 3. 保存过的每一版都能查、能回滚。
 */
class SafetyRulesStoreTest {

    @TempDir
    Path dir;

    private SafetyRulesStore store() {
        return new SafetyRulesStore(
                dir.resolve("safety-rules.json").toString(),
                dir.resolve("history").toString(),
                new ObjectMapper());
    }

    private SafetyRules withPattern(SafetyRules base, String id, String pattern) {
        List<SafetyRule> rules = new ArrayList<>(base.rules());
        for (int i = 0; i < rules.size(); i++) {
            SafetyRule r = rules.get(i);
            if (r.id().equals(id)) {
                rules.set(i, new SafetyRule(r.id(), r.label(), r.level(), pattern, true, r.note()));
            }
        }
        return new SafetyRules(base.version(), base.updatedAt(), base.updatedBy(), rules, base.cases());
    }

    private SafetyRules withRules(SafetyRules base, List<SafetyRule> rules) {
        return new SafetyRules(base.version(), base.updatedAt(), base.updatedBy(), rules, base.cases());
    }

    private SafetyRules withCases(SafetyRules base, List<SafetyCase> cases) {
        return new SafetyRules(base.version(), base.updatedAt(), base.updatedBy(), base.rules(), cases);
    }

    @Test
    void canAddVariantsButNotWeakenTheBottomLine() {
        SafetyRulesStore store = store();
        SafetyRules base = store.get();

        // 加一个变体：允许
        String widened = base.byId("self_harm").pattern() + "|想不开";
        SafetyRules saved = store.save(withPattern(base, "self_harm", widened), "test");
        assertEquals(2, saved.version());
        assertTrue(SafetyScreener.screen(saved, "我真的想不开了").hold(), "新加的变体应生效");

        // 把自伤规则改窄：拒绝
        SafetyRules narrow = withPattern(store.get(), "self_harm", "只有这句话才算|zzzz");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.save(narrow, "test"));
        assertTrue(e.getMessage().contains("应暂停"), "应指回放用例失败：" + e.getMessage());

        // 旧规则仍然生效
        assertTrue(SafetyScreener.screen(store.get(), "我不想活了").hold());
        assertEquals(2, store.get().version(), "失败的保存不能推进版本");
    }

    @Test
    void canAddAndDeleteCustomRules() {
        SafetyRulesStore store = store();
        SafetyRules base = store.get();

        // 新增一条本地口语规则：允许
        List<SafetyRule> added = new ArrayList<>(base.rules());
        added.add(new SafetyRule("local_slang", "本地口语", SafetyRule.HOLD,
                "扛不住了想走|想一了百了", true, "运维自建"));
        store.save(withRules(base, added), "ops");
        assertTrue(SafetyScreener.screen(store.get(), "我真的想一了百了").hold(), "新规则应生效");

        // 再删掉它：允许
        List<SafetyRule> removed = new ArrayList<>(store.get().rules());
        removed.removeIf(r -> r.id().equals("local_slang"));
        SafetyRules afterDelete = store.save(withRules(store.get(), removed), "ops");
        assertFalse(SafetyScreener.screen(afterDelete, "我真的想一了百了").hold(), "删掉后不该再命中");
    }

    @Test
    void deletingTheOnlyCoverageIsCaughtByCasesNotByHardCodedIds() {
        SafetyRulesStore store = store();
        SafetyRules base = store.get();
        List<SafetyRule> without = base.rules().stream()
                .filter(r -> !r.id().equals("self_harm"))
                .toList();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.save(withRules(base, without), "test"));
        assertTrue(e.getMessage().contains("用例"), "应由用例回放挡住，而不是写死 id：" + e.getMessage());
        assertTrue(e.getMessage().contains("不想活"), e.getMessage());
    }

    @Test
    void cannotEmptyTheCaseTable() {
        SafetyRulesStore store = store();
        SafetyRules base = store.get();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.save(withCases(base, List.of()), "test"));
        assertTrue(e.getMessage().contains("用例"), e.getMessage());
    }

    @Test
    void cannotDropBelowTheHoldCaseFloor() {
        SafetyRulesStore store = store();
        SafetyRules base = store.get();
        List<SafetyCase> few = base.cases().stream()
                .limit(2)
                .toList();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.save(withCases(base, few), "test"));
        assertTrue(e.getMessage().contains("至少保留"), e.getMessage());
    }

    @Test
    void brokenRegexIsRejectedWithTheRuleId() {
        SafetyRulesStore store = store();
        SafetyRules base = store.get();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.save(withPattern(base, "self_harm", "自杀("), "test"));
        assertTrue(e.getMessage().contains("self_harm"), e.getMessage());
        assertTrue(e.getMessage().contains("正则"), e.getMessage());
    }

    @Test
    void everyVersionIsSnapshottedAndRollbackIsANewSave() {
        SafetyRulesStore store = store();
        SafetyRules base = store.get();
        String original = base.byId("self_harm").pattern();

        store.save(withPattern(base, "self_harm", original + "|想不开"), "alice");     // v2
        store.save(withPattern(store.get(), "self_harm", original + "|不想起床"), "alice"); // v3

        assertEquals(List.of(3, 2), store.history());

        SafetyRules rolled = store.rollback(2, "bob");
        assertEquals(4, rolled.version(), "回滚是新的保存，版本继续往前走");
        assertEquals(3, store.history().size());
        assertTrue(store.get().updatedBy().contains("rollback"), "回滚要在记录里说明来源");
        assertTrue(store.get().byId("self_harm").pattern().contains("想不开"), "应回到 v2 的内容");
        assertFalse(store.get().byId("self_harm").pattern().contains("不想起床"), "v3 的改动应被撤掉");
    }
}
