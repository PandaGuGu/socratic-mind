package com.socraticmind.agent.cbt;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 安全分流：规则通道。
 *
 * 设计立场（docs/02-设计/安全分流规则.md）：
 * - 只判断"是否需要转介"，不做风险等级；
 * - 宁可在模糊处保守，但不能把引用歌词/第三人称叙述当成危险信号（误报会让人不敢说真话）。
 *
 * 第一版是关键词 + 上下文排除，必须由专业人员复核后才能上线。
 */
@Component
public class SafetyScreener {

    private record Rule(String label, Pattern pattern) {
    }

    private static final List<Rule> RISK = List.of(
            new Rule("self_harm", Pattern.compile("自杀|自残|自伤|不想活|活不下去|活着没意思|结束生命|跳楼|割腕|安眠药")),
            new Rule("harm_others", Pattern.compile("杀了他|杀了她|伤害别人|报复所有人"))
    );

    /** 第三人称 / 引用语境：出现这些词时不做直接转介，避免把"书里主人公自伤"误判。 */
    private static final Pattern CONTEXT_GUARD = Pattern.compile(
            "歌词|电影|小说|书里|剧中|新闻|研究表明|论文|朋友|同学|家人|别人|他|她|患者|来访者");

    public record Screen(boolean hold, String rule, String reason) {
        public static Screen pass() {
            return new Screen(false, null, null);
        }
    }

    public Screen screen(String text) {
        if (text == null || text.isBlank()) return Screen.pass();
        for (Rule rule : RISK) {
            if (!rule.pattern().matcher(text).find()) continue;
            boolean guarded = CONTEXT_GUARD.matcher(text).find();
            if (guarded) {
                return new Screen(false, null, "命中风险词但存在第三人称语境，按普通消息处理（需人工复核）");
            }
            return new Screen(true, rule.label(), "命中安全规则：" + rule.label());
        }
        return Screen.pass();
    }
}
