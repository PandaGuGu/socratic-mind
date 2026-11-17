package com.socraticmind.agent.cbt.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.agent.cbt.CbtScript;
import com.socraticmind.session.CbtStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程定义的存储：一份 JSON，画布改完保存即热加载。
 *
 * 与输出策略同一套约定：文件即真值、按 mtime 热加载、坏文件不生效。
 * 差别是这里多了发布校验——结构错的流程比一条坏规则危险得多。
 */
@Component
public class FlowParamsStore {

    private static final Logger log = LoggerFactory.getLogger(FlowParamsStore.class);

    private final Path path;
    private final ObjectMapper mapper;
    private final CbtScript script = new CbtScript();

    private volatile FlowDefinition current;
    private volatile long seenMtime = -1L;

    public FlowParamsStore(@Value("${psych.flow.params-file:config/flow-params.json}") String file,
                           ObjectMapper mapper) {
        this.path = Path.of(file).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.current = defaults();
        reloadIfChanged();
    }

    public FlowDefinition get() {
        reloadIfChanged();
        return current;
    }

    public Path path() {
        return path;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    public synchronized FlowDefinition save(FlowDefinition incoming, String updatedBy) {
        FlowDefinition normalized = normalize(incoming);
        List<String> errors = FlowValidator.validate(normalized);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("；", errors));
        }
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            FlowDefinition next = new FlowDefinition(
                    normalized.version() + 1,
                    Instant.now().toString(),
                    updatedBy == null || updatedBy.isBlank() ? "ops" : updatedBy,
                    normalized.stages(),
                    normalized.intervention());
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), next);
            current = next;
            seenMtime = Files.getLastModifiedTime(path).toMillis();
            log.info("流程定义已保存：版本 {}，写入 {}", next.version(), path);
            return next;
        } catch (IOException e) {
            throw new IllegalStateException("流程文件写入失败：" + e.getMessage(), e);
        }
    }

    /** 第一次启动时的默认值：话术取自脚本资产，其余与代码里的既有行为一致。 */
    public FlowDefinition defaults() {
        Map<String, StageSpec> stages = new LinkedHashMap<>();
        stages.put(CbtStage.PROBLEM_IDENTIFICATION.name(), spec(CbtStage.PROBLEM_IDENTIFICATION,
                "情境", "COGNITIVE_EXTRACTION", "none", 40, 40));
        stages.put(CbtStage.COGNITIVE_EXTRACTION.name(), spec(CbtStage.COGNITIVE_EXTRACTION,
                "想法", "COGNITIVE_REVIEW", "none", 300, 40));
        stages.put(CbtStage.COGNITIVE_REVIEW.name(), spec(CbtStage.COGNITIVE_REVIEW,
                "检验", "INTERVENTION_DECISION", "cbt_balance", 560, 40));
        stages.put(CbtStage.INTERVENTION_DECISION.name(), spec(CbtStage.INTERVENTION_DECISION,
                "行动", "ACTION_CONFIRM", "none", 820, 40));
        stages.put(CbtStage.ACTION_CONFIRM.name(), spec(CbtStage.ACTION_CONFIRM,
                "确认", "ACTION_REVIEW", "none", 820, 250));
        stages.put(CbtStage.ACTION_REVIEW.name(), spec(CbtStage.ACTION_REVIEW,
                "复盘", "SESSION_SUMMARY", "cbt_summary", 560, 250));
        stages.put(CbtStage.SESSION_SUMMARY.name(), spec(CbtStage.SESSION_SUMMARY,
                "总结", null, "none", 300, 250));
        stages.put(CbtStage.SAFETY_HOLD.name(), spec(CbtStage.SAFETY_HOLD,
                "安全暂停", null, "none", 40, 250));

        Map<String, List<String>> intervention = new LinkedHashMap<>();
        intervention.put("behavioral_experiment", List.of("试", "练习", "做", "写"));
        intervention.put("cognitive_restructuring", List.of("换", "重新", "想"));
        intervention.put("self_monitoring", List.of());

        return new FlowDefinition(1, null, "system", stages, intervention);
    }

    private StageSpec spec(CbtStage stage, String title, String next, String model, int x, int y) {
        String text = script.templates().getOrDefault(stage, "");
        return new StageSpec(title, text, next, model, x, y);
    }

    private synchronized void reloadIfChanged() {
        if (!Files.exists(path)) return;
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (mtime == seenMtime) return;
            FlowDefinition loaded = mapper.readValue(path.toFile(), FlowDefinition.class);
            List<String> errors = FlowValidator.validate(loaded);
            if (!errors.isEmpty()) {
                throw new IllegalStateException(String.join("；", errors));
            }
            current = normalize(loaded);
            seenMtime = mtime;
            log.info("流程定义已热加载：版本 {}", loaded.version());
        } catch (Exception e) {
            seenMtime = lastMtimeOrNow();
            log.warn("流程文件不可用，继续使用内存中的版本 {}：{}", current.version(), e.getMessage());
        }
    }

    private long lastMtimeOrNow() {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    private static FlowDefinition normalize(FlowDefinition def) {
        if (def == null) throw new IllegalArgumentException("流程定义不能为空");
        Map<String, StageSpec> stages = new LinkedHashMap<>();
        if (def.stages() != null) {
            for (Map.Entry<String, StageSpec> entry : def.stages().entrySet()) {
                StageSpec s = entry.getValue();
                if (s == null) continue;
                String id = entry.getKey() == null ? "" : entry.getKey().trim();
                stages.put(id, new StageSpec(
                        s.title() == null ? "" : s.title().trim(),
                        s.text() == null ? "" : s.text().strip(),
                        s.next() == null || s.next().isBlank() ? null : s.next().trim(),
                        s.model() == null || s.model().isBlank() ? StageSpec.MODEL_NONE : s.model().trim(),
                        s.branches(),
                        s.x(), s.y()));
            }
        }
        Map<String, List<String>> intervention = new LinkedHashMap<>();
        if (def.intervention() != null) {
            for (Map.Entry<String, List<String>> entry : def.intervention().entrySet()) {
                List<String> words = entry.getValue() == null ? List.of() : entry.getValue().stream()
                        .filter(w -> w != null && !w.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
                intervention.put(entry.getKey(), words);
            }
        }
        return new FlowDefinition(def.version(), def.updatedAt(), def.updatedBy(), stages, intervention);
    }
}
