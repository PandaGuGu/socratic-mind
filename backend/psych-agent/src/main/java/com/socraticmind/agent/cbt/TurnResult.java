package com.socraticmind.agent.cbt;

import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CrisisCard;

import java.util.List;

/** 一轮交互的结果：前端只读这些字段，不写状态。 */
public record TurnResult(
        String sessionId,
        CbtStage stage,
        String stageLabel,
        int visibleIndex,
        String status,
        String response,
        boolean awaitingConfirmation,
        String safetyStatus,
        String confirmedThought,
        /** 自动思维里标出的候选认知偏差；只在检验阶段之前及之后有内容。 */
        List<String> distortions,
        /** 用户自己打的情绪分序列，按时间顺序，供前端画折线。 */
        List<Integer> moodSeries,
        /** 仅安全暂停时非空。 */
        CrisisCard crisis
) {
    public static final String AWAITING_USER = "awaiting_user";
    public static final String AWAITING_CONFIRMATION = "awaiting_confirmation";
    public static final String SAFETY_HOLD = "safety_hold";
    public static final String ENDED = "ended";
    /** 命中敏感词被整句拦下：流程不推进，换句话就能继续。 */
    public static final String CONTENT_BLOCKED = "content_blocked";
}
