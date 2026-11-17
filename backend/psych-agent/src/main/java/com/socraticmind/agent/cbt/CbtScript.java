package com.socraticmind.agent.cbt;

import com.socraticmind.session.CbtStage;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CBT 对话脚本。话术与代码分离，理由是这些话术要经专业审核、要能版本化对比，
 * 而不是散落在 if 分支里（docs/01-方案/07-提示词组织.md）。
 */
public class CbtScript {

    private static final Pattern BLOCK =
            Pattern.compile("\\[stage:([a-z_]+)]\\s*(.+?)(?=\\n\\[stage:|\\z)", Pattern.DOTALL);

    private final Map<CbtStage, String> templates = new EnumMap<>(CbtStage.class);

    public CbtScript() {
        String raw = load();
        Matcher m = BLOCK.matcher(raw);
        while (m.find()) {
            CbtStage stage = byKey(m.group(1));
            if (stage != null) {
                templates.put(stage, m.group(2).trim());
            }
        }
    }

    public String question(CbtStage stage, Map<String, String> vars) {
        String template = templates.get(stage);
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    public String question(CbtStage stage) {
        return question(stage, Map.of());
    }

    private static CbtStage byKey(String key) {
        return switch (key) {
            case "problem_identification" -> CbtStage.PROBLEM_IDENTIFICATION;
            case "cognitive_extraction" -> CbtStage.COGNITIVE_EXTRACTION;
            case "cognitive_review" -> CbtStage.COGNITIVE_REVIEW;
            case "intervention_decision" -> CbtStage.INTERVENTION_DECISION;
            case "action_confirm" -> CbtStage.ACTION_CONFIRM;
            case "action_review" -> CbtStage.ACTION_REVIEW;
            case "safety_hold" -> CbtStage.SAFETY_HOLD;
            default -> null;
        };
    }

    private String load() {
        try (InputStream in = new ClassPathResource("prompts/cbt/stages.md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("CBT 脚本缺失：prompts/cbt/stages.md", e);
        }
    }
}
