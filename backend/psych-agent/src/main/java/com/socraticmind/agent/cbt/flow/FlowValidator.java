package com.socraticmind.agent.cbt.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 画布的发布校验。它替代了"编译期检查"：一旦流程变成可编辑数据，
 * 这些约束就必须在保存前跑一遍，否则运维一次拖动就能把"确认点"绕过去。
 *
 * 三类规则：
 * 1. 结构：核心节点齐全、边只能落在白名单、能从情境走到总结；
 * 2. 语义：阶段能绑的模型档位、条件谓词、分支目标；
 * 3. 文案：非空、占位符必须认识（防止 {{thougt}} 拼错漏给用户）。
 */
public final class FlowValidator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z_]+)}}");

    public static final Set<String> PLACEHOLDERS = Set.of("thought", "plan");

    public static final List<String> INTERVENTION_TYPES =
            List.of("behavioral_experiment", "cognitive_restructuring", "self_monitoring");

    /** 主干节点：必须在。 */
    public static final Set<String> CORE = Set.of(
            "PROBLEM_IDENTIFICATION", "COGNITIVE_EXTRACTION", "COGNITIVE_REVIEW",
            "INTERVENTION_DECISION", "ACTION_CONFIRM", "ACTION_REVIEW",
            "SESSION_SUMMARY", "SAFETY_HOLD");

    /** 可选节点：由画布"添加节点"引入，每种最多一个（状态按类型存）。 */
    public static final Set<String> OPTIONAL = Set.of(
            "BEHAVIORAL_EXPERIMENT", "PRACTICAL_PROBLEM_SOLVING", "CONDITION");

    /** 没有分支命中时的默认去向。Map.of 最多 10 对，这里超过，用构造器。 */
    public static final Map<String, Set<String>> ALLOWED_NEXT = buildNext();

    private static Map<String, Set<String>> buildNext() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("PROBLEM_IDENTIFICATION", Set.of("COGNITIVE_EXTRACTION"));
        m.put("COGNITIVE_EXTRACTION", Set.of("COGNITIVE_REVIEW"));
        m.put("COGNITIVE_REVIEW", Set.of("INTERVENTION_DECISION"));
        m.put("INTERVENTION_DECISION", Set.of("ACTION_CONFIRM", "BEHAVIORAL_EXPERIMENT", "PRACTICAL_PROBLEM_SOLVING"));
        m.put("BEHAVIORAL_EXPERIMENT", Set.of("ACTION_CONFIRM"));
        m.put("PRACTICAL_PROBLEM_SOLVING", Set.of("ACTION_CONFIRM"));
        m.put("CONDITION", Set.of("ACTION_CONFIRM", "INTERVENTION_DECISION", "ACTION_REVIEW",
                "BEHAVIORAL_EXPERIMENT", "PRACTICAL_PROBLEM_SOLVING"));
        m.put("ACTION_CONFIRM", Set.of("ACTION_REVIEW"));
        m.put("ACTION_REVIEW", Set.of("SESSION_SUMMARY"));
        m.put("SESSION_SUMMARY", Set.of());
        m.put("SAFETY_HOLD", Set.of());
        return Collections.unmodifiableMap(m);
    }

    /** 哪些节点可以挂分支，以及分支能指向谁。 */
    public static final Map<String, Set<String>> ALLOWED_BRANCH = Map.of(
            "INTERVENTION_DECISION", Set.of("BEHAVIORAL_EXPERIMENT", "PRACTICAL_PROBLEM_SOLVING", "ACTION_CONFIRM", "CONDITION"),
            "CONDITION", Set.of("INTERVENTION_DECISION", "ACTION_CONFIRM", "ACTION_REVIEW",
                    "BEHAVIORAL_EXPERIMENT", "PRACTICAL_PROBLEM_SOLVING"));

    /** 每个阶段允许绑定的输出策略档位。 */
    public static final Map<String, Set<String>> ALLOWED_MODEL = buildModel();

    private static Map<String, Set<String>> buildModel() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("PROBLEM_IDENTIFICATION", Set.of("none"));
        m.put("COGNITIVE_EXTRACTION", Set.of("none"));
        m.put("COGNITIVE_REVIEW", Set.of("none", "cbt_balance"));
        m.put("INTERVENTION_DECISION", Set.of("none"));
        m.put("BEHAVIORAL_EXPERIMENT", Set.of("none"));
        m.put("PRACTICAL_PROBLEM_SOLVING", Set.of("none"));
        m.put("CONDITION", Set.of("none"));
        m.put("ACTION_CONFIRM", Set.of("none"));
        m.put("ACTION_REVIEW", Set.of("none", "cbt_summary"));
        m.put("SESSION_SUMMARY", Set.of("none"));
        m.put("SAFETY_HOLD", Set.of("none"));
        return Collections.unmodifiableMap(m);
    }

    /** 终态里只有总结不需要"提问话术"；判断节点不提问。 */
    public static final Set<String> NO_ASK_TEXT = Set.of("SESSION_SUMMARY", "CONDITION");

    /**
     * 可以当起点的节点：必须有提问话术，并且执行器知道收到第一条回答后该写哪个字段。
     * 判断/总结/安全暂停不提问，确认节点则只接受 /confirm，不适合作为第一问。
     */
    public static final Set<String> STARTABLE = Set.of(
            "PROBLEM_IDENTIFICATION", "COGNITIVE_EXTRACTION", "COGNITIVE_REVIEW",
            "INTERVENTION_DECISION", "ACTION_REVIEW",
            "BEHAVIORAL_EXPERIMENT", "PRACTICAL_PROBLEM_SOLVING");

    private FlowValidator() {
    }

    public static List<String> validate(FlowDefinition def) {
        List<String> errors = new ArrayList<>();
        if (def == null) {
            errors.add("流程定义不能为空");
            return errors;
        }

        for (String stage : CORE) {
            if (!def.stages().containsKey(stage)) errors.add("缺少节点：" + stage);
        }
        for (String stage : def.stages().keySet()) {
            if (!CORE.contains(stage) && !OPTIONAL.contains(stage)) errors.add("未知节点：" + stage);
        }
        if (!errors.isEmpty()) return errors;

        errors.addAll(checkStart(def));
        for (Map.Entry<String, StageSpec> entry : def.stages().entrySet()) {
            String id = entry.getKey();
            StageSpec spec = entry.getValue();
            if (spec == null) {
                errors.add(id + "：节点内容为空");
                continue;
            }
            errors.addAll(checkText(id, spec.text()));
            errors.addAll(checkRouting(id, spec, def.stages().keySet()));
            errors.addAll(checkModel(id, spec.model()));
        }
        errors.addAll(checkReachability(def));
        errors.addAll(checkIntervention(def.intervention()));
        errors.addAll(checkMessages(def));
        return errors;
    }

    /**
     * 系统话术：键必须在白名单里，占位符必须是这个键允许的那些。
     * 这样"确认回执"里写 {{thougt}} 会在保存时被拦住，而不是上线后少一个词。
     */
    private static List<String> checkMessages(FlowDefinition def) {
        List<String> errors = new ArrayList<>();
        for (String key : def.messages().keySet()) {
            if (!FlowMessages.PLACEHOLDERS.containsKey(key)) {
                errors.add("未知话术键：" + key + "（可用的是 " + FlowMessages.PLACEHOLDERS.keySet() + "）");
            }
        }
        for (Map.Entry<String, Set<String>> entry : FlowMessages.PLACEHOLDERS.entrySet()) {
            String key = entry.getKey();
            String text = def.message(key);
            if (text == null || text.isBlank()) {
                errors.add("话术 " + key + " 不能为空");
                continue;
            }
            if (text.length() > 800) {
                errors.add("话术 " + key + " 超过 800 字");
            }
            Matcher m = PLACEHOLDER.matcher(text);
            while (m.find()) {
                if (!entry.getValue().contains(m.group(1))) {
                    errors.add("话术 " + key + " 里的 {{" + m.group(1) + "}} 不可用，允许的是 "
                            + (entry.getValue().isEmpty() ? "无占位符" : entry.getValue()));
                }
            }
        }
        return errors;
    }

    private static List<String> checkText(String id, String text) {
        List<String> errors = new ArrayList<>();
        if (NO_ASK_TEXT.contains(id)) return errors;
        if (text == null || text.isBlank()) {
            errors.add(id + "：话术不能为空");
            return errors;
        }
        if (text.length() > 1200) {
            errors.add(id + "：话术超过 1200 字，画布不是写长文的地方");
        }
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            if (!PLACEHOLDERS.contains(m.group(1))) {
                errors.add(id + "：占位符 {{" + m.group(1) + "}} 不认识，可用的是 {{thought}} 与 {{plan}}");
            }
        }
        return errors;
    }

    private static List<String> checkRouting(String id, StageSpec spec, Set<String> nodeIds) {
        List<String> errors = new ArrayList<>();
        Set<String> branchTargets = ALLOWED_BRANCH.getOrDefault(id, Set.of());

        if (spec.hasBranches() && branchTargets.isEmpty()) {
            errors.add(id + "：这个节点不支持分支，只能设一个下一步");
            return errors;
        }

        for (BranchRule rule : spec.branches()) {
            if (rule == null) {
                errors.add(id + "：存在空的分支规则");
                continue;
            }
            if (!ConditionWhen.ALL.contains(rule.when())) {
                errors.add(id + "：条件 " + rule.when() + " 不在可用集合里");
            }
            if (rule.to() == null || !nodeIds.contains(rule.to())) {
                errors.add(id + "：分支指向不存在的节点 " + rule.to());
            } else if (!branchTargets.contains(rule.to())) {
                errors.add(id + "：分支不能连到 " + rule.to());
            }
        }

        // 判断节点必须有兜底分支，否则某个条件下会无路可走
        if ("CONDITION".equals(id)) {
            if (!spec.hasBranches()) {
                errors.add("判断节点至少需要一条分支，并且必须有一条「其它情况」兜底");
            } else {
                boolean hasElse = spec.branches().stream().anyMatch(b -> ConditionWhen.ELSE.equals(b.when()));
                if (!hasElse) errors.add("判断节点缺少「其它情况」兜底分支");
                BranchRule last = spec.branches().get(spec.branches().size() - 1);
                if (hasElse && !ConditionWhen.ELSE.equals(last.when())) {
                    errors.add("判断节点：兜底分支必须放在最后一条");
                }
            }
        }

        Set<String> allowed = ALLOWED_NEXT.getOrDefault(id, Set.of());
        if (!spec.hasBranches()) {
            if (allowed.isEmpty()) {
                if (spec.next() != null && !spec.next().isBlank()) {
                    errors.add(id + "：终态节点不能有下一步");
                }
                return errors;
            }
            if (spec.next() == null || spec.next().isBlank()) {
                errors.add(id + "：必须选择下一步");
                return errors;
            }
            if (!nodeIds.contains(spec.next())) {
                errors.add(id + "：下一步指向不存在的节点 " + spec.next());
            } else if (!allowed.contains(spec.next())) {
                errors.add(id + "：不能直接连到 " + spec.next() + "，可选的是 " + String.join(" / ", allowed));
            }
            return errors;
        }

        if (spec.next() != null && !spec.next().isBlank() && !nodeIds.contains(spec.next())) {
            errors.add(id + "：兜底去向指向不存在的节点 " + spec.next());
        }
        return errors;
    }

    private static List<String> checkModel(String id, String model) {
        String value = model == null || model.isBlank() ? StageSpec.MODEL_NONE : model;
        Set<String> allowed = ALLOWED_MODEL.getOrDefault(id, Set.of("none"));
        if (!allowed.contains(value)) {
            return List.of(id + "：这个阶段不能绑定输出策略 " + value + "，可选的是 " + String.join(" / ", allowed));
        }
        return List.of();
    }

    /** 起点必须在节点集合里，且不能是终态——否则一轮对话刚开始就结束了。 */
    private static List<String> checkStart(FlowDefinition def) {
        String start = def.start();
        if (!def.stages().containsKey(start)) {
            return List.of("起点指向不存在的节点 " + start);
        }
        if (!STARTABLE.contains(start)) {
            return List.of("「" + start + "」不适合当起点：它不提问，或第一轮回答没有对应的字段处理");
        }
        return List.of();
    }

    private static List<String> checkReachability(FlowDefinition def) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(def.start());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) continue;
            StageSpec spec = def.stages().get(id);
            if (spec == null) continue;
            for (BranchRule rule : spec.branches()) {
                if (rule != null && rule.to() != null) queue.add(rule.to());
            }
            if (spec.next() != null && !spec.next().isBlank()) queue.add(spec.next());
        }
        if (!seen.contains("SESSION_SUMMARY")) {
            return List.of("从起点「" + def.start() + "」出发走不到「总结」：检查某一步的下一条或分支是否形成了环");
        }
        return List.of();
    }

    private static List<String> checkIntervention(Map<String, List<String>> intervention) {
        List<String> errors = new ArrayList<>();
        for (String type : INTERVENTION_TYPES) {
            if (!intervention.containsKey(type)) {
                errors.add("干预类型缺少 " + type + "（可以是空列表，但键必须存在）");
            }
        }
        for (Map.Entry<String, List<String>> entry : intervention.entrySet()) {
            if (!INTERVENTION_TYPES.contains(entry.getKey())) {
                errors.add("未知干预类型：" + entry.getKey());
                continue;
            }
            List<String> words = entry.getValue();
            if (words == null) {
                errors.add(entry.getKey() + "：关键词列表不能为 null");
                continue;
            }
            if (words.size() > 20) errors.add(entry.getKey() + "：关键词最多 20 个");
            for (String word : words) {
                if (word == null || word.isBlank()) {
                    errors.add(entry.getKey() + "：存在空关键词");
                } else if (word.length() > 12) {
                    errors.add(entry.getKey() + "：关键词「" + word + "」太长，判定用的是包含匹配");
                }
            }
        }
        return errors;
    }

    /** 给画布用：新增节点时的默认位置与标题。 */
    public static StageSpec blank(String id, int x, int y) {
        String title = switch (id) {
            case "BEHAVIORAL_EXPERIMENT" -> "行为实验";
            case "PRACTICAL_PROBLEM_SOLVING" -> "问题解决";
            case "CONDITION" -> "判断";
            default -> id;
        };
        String text = switch (id) {
            case "BEHAVIORAL_EXPERIMENT" ->
                    "把这一步再缩小一点：具体到什么时候做、做什么、怎么算完成？";
            case "PRACTICAL_PROBLEM_SOLVING" ->
                    "这件事里哪些是你能直接决定的，哪些只能影响、哪些完全不由你？先从能决定的那部分挑一件。";
            case "CONDITION" -> "";
            default -> "";
        };
        List<BranchRule> branches = "CONDITION".equals(id)
                ? List.of(new BranchRule(ConditionWhen.ELSE, "ACTION_CONFIRM"))
                : List.of();
        return new StageSpec(title, text, "ACTION_CONFIRM", StageSpec.MODEL_NONE, branches, x, y);
    }
}
