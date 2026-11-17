package com.socraticmind.agent.cbt;

import com.socraticmind.session.CbtStage;

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
        String confirmedThought
) {
    public static final String AWAITING_USER = "awaiting_user";
    public static final String AWAITING_CONFIRMATION = "awaiting_confirmation";
    public static final String SAFETY_HOLD = "safety_hold";
    public static final String ENDED = "ended";
}
