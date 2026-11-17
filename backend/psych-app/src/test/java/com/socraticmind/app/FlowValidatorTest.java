package com.socraticmind.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.agent.cbt.flow.FlowDefinition;
import com.socraticmind.agent.cbt.flow.FlowParamsStore;
import com.socraticmind.agent.cbt.flow.FlowValidator;
import com.socraticmind.agent.cbt.flow.StageSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画布发布的守门规则。流程一旦可编辑，这些就是替代编译期检查的那道闸。
 */
class FlowValidatorTest {

    @TempDir
    Path dir;

    private FlowDefinition defaults() {
        return new FlowParamsStore(dir.resolve("flow.json").toString(), new ObjectMapper()).defaults();
    }

    private FlowDefinition withNext(FlowDefinition base, String from, String to) {
        Map<String, StageSpec> stages = new LinkedHashMap<>(base.stages());
        StageSpec s = stages.get(from);
        stages.put(from, new StageSpec(s.title(), s.text(), to, s.model(), s.x(), s.y()));
        return new FlowDefinition(base.version(), base.updatedAt(), base.updatedBy(), stages, base.intervention());
    }

    private FlowDefinition withText(FlowDefinition base, String from, String text) {
        Map<String, StageSpec> stages = new LinkedHashMap<>(base.stages());
        StageSpec s = stages.get(from);
        stages.put(from, new StageSpec(s.title(), text, s.next(), s.model(), s.x(), s.y()));
        return new FlowDefinition(base.version(), base.updatedAt(), base.updatedBy(), stages, base.intervention());
    }

    @Test
    void defaultsPassTheirOwnValidation() {
        assertTrue(FlowValidator.validate(defaults()).isEmpty());
    }

    @Test
    void confirmationCannotBeBypassed() {
        FlowDefinition bad = withNext(defaults(), "INTERVENTION_DECISION", "ACTION_REVIEW");
        List<String> errors = FlowValidator.validate(bad);
        assertTrue(errors.stream().anyMatch(e -> e.contains("ACTION_REVIEW")), "确认点必须不能被绕过：" + errors);
    }

    @Test
    void jumpingStraightToSummaryIsRejected() {
        FlowDefinition bad = withNext(defaults(), "COGNITIVE_EXTRACTION", "SESSION_SUMMARY");
        assertTrue(FlowValidator.validate(bad).stream().anyMatch(e -> e.contains("不能直接连到")));
    }

    @Test
    void unknownPlaceholderIsRejected() {
        FlowDefinition bad = withText(defaults(), "COGNITIVE_REVIEW", "我们先把「{{thougt}}」当成一个预测");
        assertTrue(FlowValidator.validate(bad).stream()
                .anyMatch(e -> e.contains("不认识")), "拼错占位符会原样漏给用户");
    }

    @Test
    void emptyTextIsRejected() {
        FlowDefinition bad = withText(defaults(), "COGNITIVE_REVIEW", "   ");
        assertTrue(FlowValidator.validate(bad).stream().anyMatch(e -> e.contains("话术不能为空")));
    }

    @Test
    void modelCanOnlyBindWhereItMakesSense() {
        Map<String, StageSpec> stages = new LinkedHashMap<>(defaults().stages());
        StageSpec s = stages.get("PROBLEM_IDENTIFICATION");
        stages.put("PROBLEM_IDENTIFICATION", new StageSpec(s.title(), s.text(), s.next(), "cbt_balance", s.x(), s.y()));
        FlowDefinition bad = new FlowDefinition(1, null, "test", stages, defaults().intervention());

        assertTrue(FlowValidator.validate(bad).stream().anyMatch(e -> e.contains("不能绑定输出策略")));
    }

    @Test
    void rewiringToASemanticallyInvalidTargetIsRejected() {
        // 检验写的是 candidateThought，退回想法会让"自动想法"被证据覆盖 —— v1 直接禁止
        FlowDefinition bad = withNext(defaults(), "COGNITIVE_REVIEW", "COGNITIVE_EXTRACTION");
        assertTrue(FlowValidator.validate(bad).stream().anyMatch(e -> e.contains("不能直接连到")));
    }
}
