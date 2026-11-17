package com.socraticmind.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.agent.cbt.CbtFlow;
import com.socraticmind.agent.cbt.SafetyScreener;
import com.socraticmind.agent.cbt.TurnResult;
import com.socraticmind.agent.cbt.flow.FlowDefinition;
import com.socraticmind.agent.cbt.flow.FlowParamsStore;
import com.socraticmind.agent.cbt.flow.StageSpec;
import com.socraticmind.infrastructure.ai.StubChatAdapter;
import com.socraticmind.infrastructure.store.InMemoryCbtSessionStore;
import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CbtTurnEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画布改完之后，执行真的会变——这是"可视化编辑"和"可视化展示"的分界线。
 */
class FlowCanvasTest {

    @TempDir
    Path dir;

    private FlowParamsStore store() {
        return new FlowParamsStore(dir.resolve("flow.json").toString(), new ObjectMapper());
    }

    private FlowDefinition with(FlowParamsStore params, String id, String next, String model) {
        FlowDefinition base = params.defaults();
        Map<String, StageSpec> stages = new LinkedHashMap<>(base.stages());
        StageSpec s = stages.get(id);
        stages.put(id, new StageSpec(s.title(), s.text(), next, model, s.x(), s.y()));
        FlowDefinition edited = new FlowDefinition(base.version(), null, "test", stages, base.intervention());
        return params.save(edited, "test");
    }

    private CbtFlow flow(FlowParamsStore params) {
        return new CbtFlow(new InMemoryCbtSessionStore(), new StubChatAdapter(), new SafetyScreener(), params);
    }

    @Test
    void editingTextChangesWhatTheUserSees() {
        FlowParamsStore params = store();
        FlowDefinition base = params.defaults();
        Map<String, StageSpec> stages = new LinkedHashMap<>(base.stages());
        StageSpec s = stages.get("COGNITIVE_EXTRACTION");
        stages.put("COGNITIVE_EXTRACTION", new StageSpec(s.title(),
                "换个说法：那一刻你脑子里冒出来的第一句话是什么？", s.next(), s.model(), s.x(), s.y()));
        params.save(new FlowDefinition(base.version(), null, "test", stages, base.intervention()), "test");

        CbtFlow flow = flow(params);
        TurnResult started = flow.start("canvas-u1");
        TurnResult next = flow.handle(started.sessionId(), "下周组会要汇报");

        assertEquals("换个说法：那一刻你脑子里冒出来的第一句话是什么？", next.response(),
                "画布改的话术必须原样出现在下一问里");
    }

    @Test
    void turningOffModelKeepsTemplateWithoutCallingGuard() {
        FlowParamsStore params = store();
        with(params, "COGNITIVE_REVIEW", "INTERVENTION_DECISION", "none");
        CbtFlow flow = flow(params);

        TurnResult started = flow.start("canvas-u2");
        String id = started.sessionId();
        flow.handle(id, "下周组会要汇报");
        flow.handle(id, "我肯定会搞砸");
        flow.handle(id, "前三次有两次其实还行");

        List<CbtTurnEvent> trace = flow.require(id).trace();
        CbtTurnEvent review = trace.get(trace.size() - 1);
        assertEquals(CbtTurnEvent.TEMPLATE, review.modelMode());
        assertNull(review.guardRule(), "关掉模型就不该再走输出策略，也不该留下拦截记录");
        assertTrue(flow.require(id).candidateThought().contains("是一个预测"), "模板兜底仍然要给出候选说法");
    }

    @Test
    void editingKeywordsChangesInterventionClassification() {
        FlowParamsStore params = store();
        FlowDefinition base = params.defaults();
        Map<String, List<String>> intervention = new LinkedHashMap<>(base.intervention());
        intervention.put("cognitive_restructuring", List.of("核对", "验证"));
        intervention.put("behavioral_experiment", List.of("试试看"));
        params.save(new FlowDefinition(base.version(), null, "test", base.stages(), intervention), "test");

        CbtFlow flow = flow(params);
        TurnResult started = flow.start("canvas-u3");
        String id = started.sessionId();
        flow.handle(id, "下周组会要汇报");
        flow.handle(id, "我肯定会搞砸");
        flow.handle(id, "前三次有两次其实还行");
        flow.handle(id, "先去核对一下上次的数据口径");

        assertEquals("cognitive_restructuring", flow.require(id).interventionType(),
                "关键词由画布维护，改了词表就该改掉归类");
    }
}
