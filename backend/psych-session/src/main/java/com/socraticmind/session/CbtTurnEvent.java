package com.socraticmind.session;

import java.time.Instant;

/**
 * 一轮交互的审计事件。
 *
 * 与 {@link CbtMessage} 的分工必须区分开：
 * - message 记录"说了什么"，可导出给咨询师看；
 * - event 记录"为什么这么走"，给复核、回归与内部后台看。
 *
 * 只存快照的话，以下三个问题永远回答不了：
 * 1. 第 3 轮命中的是哪条安全规则，还是被第三人称语境白名单放行；
 * 2. 这轮回复是真实模型写的，还是模板兜底；
 * 3. 这一次推进是正常回答、被中断点挡住，还是终态拦截。
 */
public record CbtTurnEvent(
        int turnNo,
        String action,
        CbtStage stageFrom,
        CbtStage stageTo,
        String status,
        String safetyRule,
        String modelMode,
        String guardRule,
        long latencyMs,
        Instant at
) {

    /** 一次会话里出现过的动作枚举，前端按此渲染文案，不猜。 */
    public static final String START = "start";
    public static final String ANSWER = "answer";
    public static final String BLOCKED = "blocked";
    public static final String SAFETY_HOLD = "safety_hold";
    public static final String CONFIRM = "confirm";
    public static final String END = "end";

    /** 回复的来源，与 {@code psych.ai.provider} 不是一回事：这里说的是这一次是不是模型给的。 */
    public static final String MODEL = "model";
    public static final String TEMPLATE = "template";
    public static final String NONE = "none";

    /**
     * 新增字段的兼容构造：没有过守卫的调用点不必都改。
     * guardRule 为 null 表示"没被拦截"，或这条事件压根不涉及模型输出。
     */
    public CbtTurnEvent(int turnNo, String action, CbtStage stageFrom, CbtStage stageTo,
                        String status, String safetyRule, String modelMode, long latencyMs, Instant at) {
        this(turnNo, action, stageFrom, stageTo, status, safetyRule, modelMode, null, latencyMs, at);
    }
}
