package com.socraticmind.common.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 操作者：签字的责任主体。
 *
 * 之前审核人是一个输入框，谁都能填"李医生"。现在它来自令牌解析出来的成员记录——
 * 记录的是"谁签了名"，而名字不再由请求方自称。
 *
 * scopes 把身份翻译成与文档 ACL 同一套语法（{@code tenant:xxx} / {@code team:yyy}），
 * 这样可见性判断只有一份实现（{@link com.socraticmind.common.support.DocumentVisibility}），
 * 不需要为"人"和"文档"各写一套匹配逻辑。
 */
public record Operator(String id, String tenantId, String teamId, String name, String role) {

    public static final String ADMIN = "admin";
    public static final String REVIEWER = "reviewer";
    public static final String VIEWER = "viewer";

    /** 能签字的人：管理员与审核者。只读成员看得到、但签不了。 */
    public boolean canReview() {
        return ADMIN.equals(role) || REVIEWER.equals(role);
    }

    public Set<String> scopes() {
        Set<String> out = new LinkedHashSet<>();
        if (tenantId != null && !tenantId.isBlank()) out.add("tenant:" + tenantId);
        if (teamId != null && !teamId.isBlank()) out.add("team:" + teamId);
        return out;
    }

    public String display() {
        return name == null || name.isBlank() ? "未署名" : name;
    }
}
