package com.socraticmind.agent.cbt.flow;

import com.socraticmind.session.CbtStage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可编辑的 CBT 流程定义：一张画布对应的一份 JSON。
 *
 * 它能改：每个节点说什么、下一步去哪、要不要调模型、干预类型的关键词、节点位置。
 * 它不能改：节点集合（八个阶段是执行器的词汇表）、字段语义（谁写 targetProblem 是代码决定的）、
 * 安全门（不在画布上，每轮硬跑）。
 *
 * 这不是能力不足，是刻意的：流程可以可视化，但"什么话能说、什么状态能跳"必须由代码守。
 */
public record FlowDefinition(
        int version,
        String updatedAt,
        String updatedBy,
        Map<String, StageSpec> stages,
        Map<String, List<String>> intervention,
        Map<String, String> messages
) {

    public FlowDefinition {
        stages = stages == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(stages));
        intervention = intervention == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(intervention));
        messages = messages == null
                ? FlowMessages.defaults()
                : Collections.unmodifiableMap(new LinkedHashMap<>(messages));
    }

    /** 兼容旧调用：没有自定义 messages 的构造。 */
    public FlowDefinition(int version, String updatedAt, String updatedBy,
                          Map<String, StageSpec> stages, Map<String, List<String>> intervention) {
        this(version, updatedAt, updatedBy, stages, intervention, FlowMessages.defaults());
    }

    /** 取一句系统话术；缺失时退回内置默认，避免"配置漏了一个键就没有话" 。 */
    public String message(String key) {
        String value = messages.get(key);
        if (value != null && !value.isBlank()) return value;
        return FlowMessages.defaults().getOrDefault(key, "");
    }

    public StageSpec stage(CbtStage stage) {
        return stages.get(stage.name());
    }

    public String textOf(CbtStage stage) {
        StageSpec spec = stage(stage);
        return spec == null || spec.text() == null ? "" : spec.text();
    }

    public String nextOf(CbtStage stage) {
        StageSpec spec = stage(stage);
        return spec == null ? null : spec.next();
    }

    public String modelOf(CbtStage stage) {
        StageSpec spec = stage(stage);
        return spec == null ? StageSpec.MODEL_NONE : spec.model();
    }
}
