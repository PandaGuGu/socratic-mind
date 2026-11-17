package com.socraticmind.agent.cbt.flow;

import java.util.List;

/** 画布上的一个节点。它决定"这一步说什么、下一步去哪、要不要调模型"，不决定"写进哪个字段"。 */
public record StageSpec(
        String title,
        String text,
        String next,
        String model,
        List<BranchRule> branches,
        int x,
        int y
) {

    public static final String MODEL_NONE = "none";

    public StageSpec {
        branches = branches == null ? List.of() : List.copyOf(branches);
    }

    /** 兼容旧调用：没有分支的节点。 */
    public StageSpec(String title, String text, String next, String model, int x, int y) {
        this(title, text, next, model, List.of(), x, y);
    }

    public boolean hasBranches() {
        return !branches.isEmpty();
    }
}
