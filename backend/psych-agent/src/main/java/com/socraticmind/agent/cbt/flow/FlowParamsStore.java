package com.socraticmind.agent.cbt.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socraticmind.agent.cbt.CbtScript;
import com.socraticmind.agent.config.ConfigHistory;
import com.socraticmind.agent.config.ConfigVersion;
import com.socraticmind.session.CbtStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
    private final Path historyRoot;
    private final ObjectMapper mapper;
    private final CbtScript script = new CbtScript();

    private volatile FlowDefinition current;
    private volatile long seenMtime = -1L;

    @org.springframework.beans.factory.annotation.Autowired
    public FlowParamsStore(@Value("${psych.flow.params-file:config/flow-params.json}") String file,
                           @Value("${psych.config.history-dir:config/history}") String historyDir,
                           ObjectMapper mapper) {
        this.path = Path.of(file).toAbsolutePath().normalize();
        this.historyRoot = Path.of(historyDir).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.current = defaults();
        // 内置默认值也要过一遍发布校验：以前漏了节点却没人发现，因为它从来没被检查过。
        List<String> fallbackErrors = FlowValidator.validate(current);
        if (!fallbackErrors.isEmpty()) {
            throw new IllegalStateException("内置默认流程不合法：" + String.join("；", fallbackErrors));
        }
        if (!Files.exists(path)) {
            log.warn("流程配置文件不存在（{}），使用内置默认流程；在画布上保存会创建该文件", path);
        }
        reloadIfChanged();
        ensureBaseline();
    }

    /** 当前生效的版本如果没有快照（例如配置文件是随包分发的），补一份基线，回滚才有起点。 */
    private void ensureBaseline() {
        if (!Files.exists(path)) return;
        try {
            FlowDefinition v = current;
            if (!history().contains(v.version())) {
                ConfigHistory.snapshot(historyRoot, "flow", v.version(), v, mapper);
            }
        } catch (RuntimeException e) {
            log.warn("写流程基线快照失败（不影响运行）：{}", e.getMessage());
        }
    }

    /** 无容器环境（测试、脚本）用：历史目录取配置文件同级。 */
    public FlowParamsStore(String file, ObjectMapper mapper) {
        this(file, siblingHistory(file), mapper);
    }

    private static String siblingHistory(String file) {
        Path parent = Path.of(file).toAbsolutePath().normalize().getParent();
        return (parent == null ? Path.of("config") : parent).resolve("history").toString();
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

    public List<Integer> history() {
        return ConfigHistory.versions(historyRoot, "flow");
    }

    /** 带时间与操作者的历史列表；只取最近 50 条。 */
    public List<ConfigVersion> historyEntries() {
        List<ConfigVersion> out = new ArrayList<>();
        for (Integer v : history()) {
            if (out.size() >= 50) break;
            FlowDefinition s = snapshot(v);
            if (s != null) out.add(new ConfigVersion(v, s.updatedAt(), s.updatedBy()));
        }
        return out;
    }

    public FlowDefinition snapshot(int version) {
        return ConfigHistory.read(historyRoot, "flow", version, FlowDefinition.class, mapper);
    }

    /** 回滚 = 把历史快照当成一次新的发布，历史只追加、不改写。 */
    public synchronized FlowDefinition rollback(int version, String actor) {
        FlowDefinition snapshot = snapshot(version);
        if (snapshot == null) throw new IllegalArgumentException("没有这个历史版本：v" + version);
        String who = actor == null || actor.isBlank() ? "ops" : actor.trim();
        return save(snapshot, who + " · rollback v" + version);
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
            // 版本号基于"当前生效版本"推进，而不是传入定义的版本：
            // 回滚时传入的是旧快照（版本更小），若按它 +1 会覆盖掉中间那一版。
            FlowDefinition next = new FlowDefinition(
                    current.version() + 1,
                    Instant.now().toString(),
                    updatedBy == null || updatedBy.isBlank() ? "ops" : updatedBy,
                    normalized.stages(),
                    normalized.intervention(),
                    normalized.messages());
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), next);
            ConfigHistory.snapshot(historyRoot, "flow", next.version(), next, mapper);
            current = next;
            seenMtime = Files.getLastModifiedTime(path).toMillis();
            log.info("流程定义已保存：版本 {}，写入 {}", next.version(), path);
            return next;
        } catch (IOException e) {
            throw new IllegalStateException("流程文件写入失败：" + e.getMessage(), e);
        }
    }

    /**
     * 第一次启动时的默认值：话术取自脚本资产，其余与代码里的既有行为一致。
     *
     * 这里必须与 config/flow-params.json 保持同样的拓扑——包括行为实验与判断节点。
     * 旧版本漏了这两个节点和三条分支：一旦部署目录里没有 config/（jar 换个工作目录启动就会发生），
     * "行为实验"这条分支会静默消失，用户不再被追问"什么时候做、怎么算完成"，而且没有任何报错。
     */
    public FlowDefinition defaults() {
        Map<String, StageSpec> stages = new LinkedHashMap<>();
        stages.put(CbtStage.PROBLEM_IDENTIFICATION.name(), spec(CbtStage.PROBLEM_IDENTIFICATION,
                "情境", "COGNITIVE_EXTRACTION", "none", 40, 40));
        stages.put(CbtStage.COGNITIVE_EXTRACTION.name(), spec(CbtStage.COGNITIVE_EXTRACTION,
                "想法", "COGNITIVE_REVIEW", "none", 300, 40));
        stages.put(CbtStage.COGNITIVE_REVIEW.name(), spec(CbtStage.COGNITIVE_REVIEW,
                "检验", "INTERVENTION_DECISION", "cbt_balance", 560, 40));
        stages.put(CbtStage.INTERVENTION_DECISION.name(), new StageSpec(
                "行动",
                script.question(CbtStage.INTERVENTION_DECISION),
                "ACTION_CONFIRM",
                "none",
                List.of(
                        new BranchRule(ConditionWhen.INTERVENTION_BEHAVIORAL, "BEHAVIORAL_EXPERIMENT"),
                        new BranchRule(ConditionWhen.INTERVENTION_OBSERVE, "CONDITION"),
                        new BranchRule(ConditionWhen.ELSE, "ACTION_CONFIRM")),
                820, 40));
        stages.put(CbtStage.ACTION_CONFIRM.name(), spec(CbtStage.ACTION_CONFIRM,
                "确认", "ACTION_REVIEW", "none", 820, 250));
        stages.put(CbtStage.ACTION_REVIEW.name(), spec(CbtStage.ACTION_REVIEW,
                "复盘", "SESSION_SUMMARY", "cbt_summary", 560, 250));
        stages.put(CbtStage.SESSION_SUMMARY.name(), spec(CbtStage.SESSION_SUMMARY,
                "总结", null, "none", 300, 250));
        stages.put(CbtStage.SAFETY_HOLD.name(), spec(CbtStage.SAFETY_HOLD,
                "安全暂停", null, "none", 40, 250));
        stages.put(CbtStage.BEHAVIORAL_EXPERIMENT.name(), spec(CbtStage.BEHAVIORAL_EXPERIMENT,
                "行为实验", "ACTION_CONFIRM", "none", 560, 400));
        stages.put(CbtStage.CONDITION.name(), new StageSpec(
                "判断", "", null, "none",
                List.of(
                        new BranchRule(ConditionWhen.CONFIRMED, "ACTION_REVIEW"),
                        new BranchRule(ConditionWhen.ELSE, "ACTION_CONFIRM")),
                300, 400));

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
        return new FlowDefinition(def.version(), def.updatedAt(), def.updatedBy(),
                stages, intervention, mergeMessages(def.messages()));
    }

    /** 配置里没写的系统话术用内置默认补齐，避免"少一个键就少一句话"。 */
    private static Map<String, String> mergeMessages(Map<String, String> incoming) {
        Map<String, String> out = new LinkedHashMap<>(FlowMessages.defaults());
        if (incoming != null) {
            for (Map.Entry<String, String> e : incoming.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                out.put(e.getKey().trim(), e.getValue().strip());
            }
        }
        return out;
    }
}
