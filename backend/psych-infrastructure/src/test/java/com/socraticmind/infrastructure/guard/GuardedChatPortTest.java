package com.socraticmind.infrastructure.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.common.guard.ModelOutputRejectedException;
import com.socraticmind.common.guard.OutputPolicy;
import com.socraticmind.common.guard.PolicyProfiles;
import com.socraticmind.common.guard.ProfileRule;
import com.socraticmind.common.port.ChatPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装饰器的行为验收：放行、重写、拦截、以及"关掉开关就不再拦"。
 * 这几条决定了运维在策略页点完之后，线上到底发生什么。
 */
class GuardedChatPortTest {

    @TempDir
    Path dir;

    private OutputPolicyStore store(ProfileRule rule) throws Exception {
        Path file = dir.resolve("output-policy.json");
        OutputPolicy policy = new OutputPolicy(1, null, "test",
                Map.of(PolicyProfiles.CBT_BALANCE, rule));
        new ObjectMapper().writeValue(file.toFile(), policy);
        return new OutputPolicyStore(file.toString(), new ObjectMapper());
    }

    private static ProfileRule rule(boolean enabled, int repair) {
        return new ProfileRule(enabled, 8, 60, List.of("你应该", "加油"), 0, repair, "测试");
    }

    @Test
    void passesThroughWithoutProfile() throws Exception {
        ScriptedChat delegate = new ScriptedChat("你应该早点休息。");
        GuardAuditLog audit = new GuardAuditLog();
        GuardedChatPort port = new GuardedChatPort(delegate, store(rule(true, 0)), audit);

        assertEquals("你应该早点休息。", port.chat("sys", "user"), "没有档位的调用不设防");
        assertEquals(1, delegate.calls);
        assertTrue(audit.recent(10).isEmpty(), "未过档的调用不写审计");
    }

    @Test
    void rejectsAndNamesTheRule() throws Exception {
        ScriptedChat delegate = new ScriptedChat("你应该早点休息。");
        GuardAuditLog audit = new GuardAuditLog();
        GuardedChatPort port = new GuardedChatPort(delegate, store(rule(true, 0)), audit);

        ModelOutputRejectedException e = assertThrows(ModelOutputRejectedException.class,
                () -> port.chat("sys", "user", PolicyProfiles.CBT_BALANCE));
        assertEquals("banned", e.rule());
        assertEquals(GuardAuditEntry.REJECTED, audit.recent(1).get(0).outcome());
        assertEquals(1, delegate.calls, "不重试时只调一次模型");
    }

    @Test
    void repairsOnceAndAcceptsTheRewrite() throws Exception {
        ScriptedChat delegate = new ScriptedChat("你应该早点休息。", "从现有信息看，也可能只是这次没准备好。");
        GuardAuditLog audit = new GuardAuditLog();
        GuardedChatPort port = new GuardedChatPort(delegate, store(rule(true, 1)), audit);

        String out = port.chat("sys", "user", PolicyProfiles.CBT_BALANCE);
        assertEquals("从现有信息看，也可能只是这次没准备好。", out);
        assertEquals(2, delegate.calls);
        assertTrue(delegate.lastUser.contains("没有通过校验"), "重试必须带上违规原因，否则等于重摇骰子");
        assertEquals(GuardAuditEntry.REPAIRED, audit.recent(1).get(0).outcome());
    }

    @Test
    void disabledRuleMeansNoInterception() throws Exception {
        ScriptedChat delegate = new ScriptedChat("你应该早点休息。");
        GuardedChatPort port = new GuardedChatPort(delegate, store(rule(false, 1)), new GuardAuditLog());

        assertEquals("你应该早点休息。", port.chat("sys", "user", PolicyProfiles.CBT_BALANCE));
        assertEquals(1, delegate.calls);
    }

    @Test
    void rejectsAfterRetriesExhausted() throws Exception {
        ScriptedChat delegate = new ScriptedChat("你应该早点休息。");
        GuardAuditLog audit = new GuardAuditLog();
        GuardedChatPort port = new GuardedChatPort(delegate, store(rule(true, 2)), audit);

        assertThrows(ModelOutputRejectedException.class,
                () -> port.chat("sys", "user", PolicyProfiles.CBT_BALANCE));
        assertEquals(3, delegate.calls, "1 次原始 + 2 次重写");
        assertEquals(GuardAuditEntry.REJECTED, audit.recent(1).get(0).outcome());
    }

    private static final class ScriptedChat implements ChatPort {

        private final List<String> replies;
        int calls;
        String lastUser;

        ScriptedChat(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            lastUser = userPrompt;
            String reply = replies.get(Math.min(calls, replies.size() - 1));
            calls++;
            return reply;
        }

        @Override
        public String modelId() {
            return "scripted";
        }
    }
}
