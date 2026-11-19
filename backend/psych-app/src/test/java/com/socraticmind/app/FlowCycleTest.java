package com.socraticmind.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.agent.cbt.CbtFlow;
import com.socraticmind.agent.cbt.safety.SafetyRulesStore;
import com.socraticmind.agent.cbt.safety.SafetyScreener;
import com.socraticmind.agent.cbt.flow.BranchRule;
import com.socraticmind.agent.cbt.flow.ConditionWhen;
import com.socraticmind.agent.cbt.flow.FlowDefinition;
import com.socraticmind.agent.cbt.flow.FlowParamsStore;
import com.socraticmind.agent.cbt.flow.StageSpec;
import com.socraticmind.infrastructure.ai.StubChatAdapter;
import com.socraticmind.infrastructure.store.InMemoryCbtSessionStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程成环时必须报错，而不是"静默停在判断节点"。
 *
 * 旧实现用 guard &lt; 4 兜底：一旦运维把 CONDITION 连回 INTERVENTION_DECISION，
 * 循环耗尽后会话停在 CONDITION，而 CONDITION 没有话术——用户看到的是一张空白纸片，
 * 服务端没有任何报错。这个测试守住"宁可 503，也不要空白"。
 */
class FlowCycleTest {

    @Test
    void cycleIsReportedInsteadOfSilentBlank() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path dir = Files.createTempDirectory("flow-cycle");
        Path file = dir.resolve("flow-params.json");

        // 先取内置默认拓扑，再把 CONDITION 的兜底分支改回 INTERVENTION_DECISION，制造一个环
        FlowParamsStore seed = new FlowParamsStore(dir.resolve("absent.json").toString(), mapper);
        FlowDefinition base = seed.defaults();
        Map<String, StageSpec> stages = new LinkedHashMap<>(base.stages());
        stages.put("CONDITION", new StageSpec(
                "判断", "", null, "none",
                List.of(new BranchRule(ConditionWhen.ELSE, "INTERVENTION_DECISION")),
                300, 400));
        mapper.writeValue(file.toFile(), new FlowDefinition(1, null, "test", stages, base.intervention()));

        FlowParamsStore store = new FlowParamsStore(file.toString(), mapper);
        SafetyRulesStore safety = new SafetyRulesStore(
                dir.resolve("safety-rules.json").toString(),
                dir.resolve("history").toString(), mapper);
        CbtFlow flow = new CbtFlow(new InMemoryCbtSessionStore(), new StubChatAdapter(),
                new SafetyScreener(safety), store);

        String id = flow.start("u-cycle").sessionId();
        flow.handle(id, "下周汇报，一想到就手心出汗");
        flow.handle(id, "我肯定会搞砸");
        flow.handle(id, "前几次其实还行");

        // "观察"不落在行为实验/认知重构的关键词里 → self_monitoring →
        // CONDITION → INTERVENTION_DECISION → CONDITION …
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> flow.handle(id, "先观察一下自己的反应"));
        assertTrue(e.getMessage().contains("环"), "必须明确指出流程成环：" + e.getMessage());
    }
}
