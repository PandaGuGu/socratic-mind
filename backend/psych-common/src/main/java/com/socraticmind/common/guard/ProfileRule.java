package com.socraticmind.common.guard;

import java.util.List;

/**
 * 一个输出档位的约束。字段刻意都可序列化成 JSON——它是给运维改的数据，不是给开发改的常量。
 *
 * 只放"能确定性判断"的规则：长度、禁句、与输入的重合度。
 * "说得是否有洞见"这种判断不进来，它无法在运行时稳定复现。
 *
 * @param enabled        档位总开关，关掉即完全放行（用于故障时快速止血）
 * @param minChars       最短字数，0 表示不限
 * @param maxChars       最长字数，0 表示不限
 * @param banned         禁句表，子串匹配；命中即违规
 * @param novelty        与输入的重合度下限（0 表示关闭）。低于它视为"引入了输入里没有的内容"
 * @param repairAttempts 违规后带反馈重写的次数，0 表示不重试、直接交给调用方兜底
 * @param note           给运维看的说明，规则改了为什么改
 */
public record ProfileRule(
        boolean enabled,
        int minChars,
        int maxChars,
        List<String> banned,
        double novelty,
        int repairAttempts,
        String note
) {

    public ProfileRule {
        banned = banned == null ? List.of() : List.copyOf(banned);
    }

    public static ProfileRule off() {
        return new ProfileRule(false, 0, 0, List.of(), 0, 0, "未启用");
    }
}
