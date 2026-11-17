package com.socraticmind.common.guard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 输出策略：一份可以被运维保存、备份、回滚的数据。
 *
 * 它不参与流程编排，只回答"模型这次说的话能不能出去"。
 * 版本号每次保存 +1，配合 updatedBy 形成最简审计。
 */
public record OutputPolicy(
        int version,
        String updatedAt,
        String updatedBy,
        Map<String, ProfileRule> profiles
) {

    public OutputPolicy {
        // 用 LinkedHashMap 保留声明顺序：策略页的档位顺序应当是稳定的（检验 → 总结 → 问答），
        // Map.copyOf 不保证顺序，会让运维每次刷新看到不同的排列。
        profiles = profiles == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    /** 没配过的档位等于不设防，避免新增调用点时被静默拦截。 */
    public ProfileRule ruleOf(String profile) {
        if (profile == null) return null;
        return profiles.get(profile);
    }

    public static OutputPolicy defaults() {
        Map<String, ProfileRule> profiles = new LinkedHashMap<>();
        profiles.put(PolicyProfiles.CBT_BALANCE, new ProfileRule(
                true, 8, 80,
                List.of("你应该", "你必须", "加油", "抱抱", "别担心", "！", "!"),
                0.35, 1,
                "认知检验的候选说法：不许说教、不许安慰、不许引入原想法与新信息里没有的内容"));
        profiles.put(PolicyProfiles.CBT_SUMMARY, new ProfileRule(
                true, 20, 200,
                List.of("你应该", "你必须", "加油", "抱抱", "诊断", "！", "!"),
                0.25, 1,
                "本轮总结：只陈述事实，不评价、不建议、不诊断"));
        profiles.put(PolicyProfiles.RAG_ANSWER, new ProfileRule(
                false, 10, 1200,
                List.of("你应该", "包治", "保证治愈", "！"),
                0.15, 0,
                "知识问答：默认关闭——它已有引用校验；需要时再启用"));
        return new OutputPolicy(1, null, "system", profiles);
    }
}
