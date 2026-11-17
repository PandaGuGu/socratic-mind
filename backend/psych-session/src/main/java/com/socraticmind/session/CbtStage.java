package com.socraticmind.session;

/**
 * CBT 阶段。对外呈现压到 5 段（对应界面上那五颗石子），内部保留更细的节点，
 * 因为"用户确认"与"行动计划"是两件事，混在一段里就无法表达中断。
 */
public enum CbtStage {

    PROBLEM_IDENTIFICATION("情境", 0),
    COGNITIVE_EXTRACTION("想法", 1),
    COGNITIVE_REVIEW("检验", 2),
    INTERVENTION_DECISION("行动", 3),
    ACTION_CONFIRM("确认", 3),
    ACTION_REVIEW("复盘", 4),
    SESSION_SUMMARY("总结", 4),
    SAFETY_HOLD("暂停", -1),
    ENDED("结束", -1);

    private final String label;
    private final int visibleIndex;

    CbtStage(String label, int visibleIndex) {
        this.label = label;
        this.visibleIndex = visibleIndex;
    }

    public String label() {
        return label;
    }

    /** 0~4；安全暂停与结束返回 -1（界面上不显示进度）。 */
    public int visibleIndex() {
        return visibleIndex;
    }

    public boolean isTerminal() {
        return this == ENDED || this == SAFETY_HOLD;
    }
}
