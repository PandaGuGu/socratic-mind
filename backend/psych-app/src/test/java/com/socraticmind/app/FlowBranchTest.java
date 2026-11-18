package com.socraticmind.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.agent.cbt.CbtFlow;
import com.socraticmind.agent.cbt.SafetyScreener;
import com.socraticmind.agent.cbt.TurnResult;
import com.socraticmind.agent.cbt.flow.BranchRule;
import com.socraticmind.agent.cbt.flow.ConditionWhen;
import com.socraticmind.agent.cbt.flow.FlowDefinition;
import com.socraticmind.agent.cbt.flow.FlowParamsStore;
import com.socraticmind.agent.cbt.flow.FlowValidator;
import com.socraticmind.agent.cbt.flow.StageSpec;
import com.socraticmind.infrastructure.ai.StubChatAdapter;
import com.socraticmind.infrastructure.store.InMemoryCbtSessionStore;
import com.socraticmind.session.CbtStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分支与判断节点的验收：画布上新加的节点必须真的改变路由。
 */
class FlowBranchTest {

    @TempDir
    Path dir;

    private FlowParamsStore store() {
        return new FlowParamsStore(dir.resolve("flow.json").toString(), new ObjectMapper());
    }

    /** 在"行动"后面插一个判断节点，行为实验走分支，其余直接去确认。 */
    private FlowDefinition withBranchGraph(FlowParamsStore params) {
        FlowDefinition base = params.defaults();
        Map<String, StageSpec> stages = new LinkedHashMap<>(base.stages());

        StageSpec decision = stages.get("INTERVENTION_DECISION");
        stages.put("INTERVENTION_DECISION", new StageSpec(decision.title(), decision.text(), null, "none",
                List.of(new BranchRule(ConditionWhen.INTERVENTION_BEHAVIORAL, "CONDITION"),
                        new BranchRule(ConditionWhen.ELSE, "ACTION_CONFIRM")), decision.x(), decision.y()));

        stages.put("CONDITION", new StageSpec("判断", "", null, "none",
                List.of(new BranchRule(ConditionWhen.INTERVENTION_BEHAVIORAL, "BEHAVIORAL_EXPERIMENT"),
                        new BranchRule(ConditionWhen.ELSE, "ACTION_CONFIRM")), 820, 400));

        StageSpec exp = FlowValidator.blank("BEHAVIORAL_EXPERIMENT", 560, 400);
        stages.put("BEHAVIORAL_EXPERIMENT", new StageSpec(exp.title(), exp.text(), "ACTION_CONFIRM",
                "none", List.of(), exp.x(), exp.y()));

        return params.save(new FlowDefinition(base.version(), null, "test", stages, base.intervention()), "test");
    }

    private CbtFlow flow(FlowParamsStore params) {
        return new CbtFlow(new InMemoryCbtSessionStore(), new StubChatAdapter(), new SafetyScreener(), params);
    }

    @Test
    void behavioralPlanRoutesThroughTheConditionIntoTheExperimentNode() {
        FlowParamsStore params = store();
        withBranchGraph(params);
        CbtFlow flow = flow(params);

        TurnResult started = flow.start("branch-u1");
        String id = started.sessionId();
        flow.handle(id, "下周组会要汇报");
        flow.handle(id, "我肯定会搞砸");
        flow.handle(id, "前三次有两次其实还行");

        TurnResult routed = flow.handle(id, "提前写下三条具体预测，会后核对");
        assertEquals(CbtStage.BEHAVIORAL_EXPERIMENT, routed.stage(),
                "干预类型是行为实验时，必须经过判断节点落到行为实验");
        assertTrue(routed.response().contains("什么时候做"), "问的是行为实验节点的话术，且不能和「行动」重复");

        TurnResult confirm = flow.handle(id, "汇报前写下三条预测");
        assertEquals(CbtStage.ACTION_CONFIRM, confirm.stage());
        assertTrue(confirm.awaitingConfirmation());
        assertEquals("汇报前写下三条预测", flow.require(id).actionPlan(), "分支节点的回答覆盖行动计划");
    }

    @Test
    void cognitivePlanTakesTheElseBranchStraightToConfirm() {
        FlowParamsStore params = store();
        withBranchGraph(params);
        CbtFlow flow = flow(params);

        TurnResult started = flow.start("branch-u2");
        String id = started.sessionId();
        flow.handle(id, "和室友闹别扭");
        flow.handle(id, "他肯定讨厌我");
        flow.handle(id, "他可能只是在忙");

        TurnResult routed = flow.handle(id, "重新想一下他可能的处境");
        assertEquals(CbtStage.ACTION_CONFIRM, routed.stage(),
                "认知重构不命中行为实验分支，应当直接去确认");
    }

    @Test
    void conditionWithoutElseIsRejected() {
        FlowParamsStore params = store();
        FlowDefinition base = params.defaults();
        Map<String, StageSpec> stages = new LinkedHashMap<>(base.stages());
        stages.put("CONDITION", new StageSpec("判断", "", null, "none",
                List.of(new BranchRule(ConditionWhen.CONFIRMED, "ACTION_CONFIRM")), 0, 0));
        FlowDefinition bad = new FlowDefinition(base.version(), null, "test", stages, base.intervention());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> params.save(bad, "test"));
        assertTrue(e.getMessage().contains("兜底"), "没有兜底分支的判断节点必须被拒：" + e.getMessage());
    }

    @Test
    void branchMustTargetAnExistingNode() {
        FlowParamsStore params = store();
        FlowDefinition base = params.defaults();
        Map<String, StageSpec> stages = new LinkedHashMap<>(base.stages());
        StageSpec decision = stages.get("INTERVENTION_DECISION");
        stages.put("INTERVENTION_DECISION", new StageSpec(decision.title(), decision.text(), null, "none",
                List.of(new BranchRule(ConditionWhen.ELSE, "BEHAVIORAL_EXPERIMENT")), decision.x(), decision.y()));

        FlowDefinition bad = new FlowDefinition(base.version(), null, "test", stages, base.intervention());
        assertTrue(FlowValidator.validate(bad).stream()
                .anyMatch(err -> err.contains("不存在的节点")), "连到没建的节点必须报出来");
    }
}
