package com.socraticmind.agent.cbt.flow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行器里那些"要对用户说、但不是某个阶段提问"的句子。
 *
 * v0.1.1 之前它们写死在 CbtFlow 里：一句话推进被拦时的提示、结束语、确认回执、
 * 平衡想法与总结的模板。结果是画布能改"问什么"，却改不了"系统在你点错时说什么"——
 * 运维看到一句不合适的话，只能改代码。
 *
 * 现在它们进 flow 配置的 {@code messages}，与阶段话术一起版本化、可回滚。
 */
public final class FlowMessages {

    public static final String STAGE_ENDED = "stage_ended";
    public static final String CONFIRM_REQUIRED = "confirm_required";
    public static final String CONFIRM_ACK = "confirm_ack";
    public static final String END = "end";
    public static final String BALANCE_TEMPLATE = "balance_template";
    public static final String SUMMARY_TEMPLATE = "summary_template";

    /** 每个键允许的占位符。空集合表示这句话不该带占位符。 */
    public static final Map<String, java.util.Set<String>> PLACEHOLDERS = Map.of(
            STAGE_ENDED, java.util.Set.of(),
            CONFIRM_REQUIRED, java.util.Set.of(),
            CONFIRM_ACK, java.util.Set.of("thought", "plan"),
            END, java.util.Set.of(),
            BALANCE_TEMPLATE, java.util.Set.of("thought", "evidence"),
            SUMMARY_TEMPLATE, java.util.Set.of("situation", "thought", "evidence", "plan"));

    private FlowMessages() {
    }

    public static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(STAGE_ENDED, "这一轮已经结束了。如果想继续，开一轮新的。");
        m.put(CONFIRM_REQUIRED, "这句话我先没写进记录——它需要你确认。");
        m.put(CONFIRM_ACK, "好，已经记下：\n· 你的说法：「{{thought}}」\n· 行动计划：「{{plan}}」");
        m.put(END, "这一轮结束了。");
        m.put(BALANCE_TEMPLATE,
                "「{{thought}}」是一个预测，不是事实。从现有信息看，另一种可能是：{{evidence}}。");
        m.put(SUMMARY_TEMPLATE,
                "这一轮：情境是「{{situation}}」，当时的想法是「{{thought}}」，"
                        + "你后来看到「{{evidence}}」。行动计划：{{plan}}。");
        return Map.copyOf(m);
    }
}
