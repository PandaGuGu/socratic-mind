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
    /** 运营：管资料入库、文档可见范围、词库、模型配置；不确认内容。 */
    public static final String OPERATOR = "operator";
    /** 审核者：回答"这份资料能不能被引用、这段话能不能这么说"。不是医疗签字，是内容把关。 */
    public static final String REVIEWER = "reviewer";
    public static final String VIEWER = "viewer";

    /**
     * 一件具体的事，而不是一个角色名。
     *
     * 按角色写 {@code if (role.equals("admin"))} 会把判断散落到每个控制器里，
     * 加一个角色就要回头找一遍；按"能做什么"写，权限矩阵只有下面这一处。
     * 角色是组织里的位置，权限是这个位置能签的字——两件事分开，才不会出现
     * "给他改了岗位，结果他还能签字"这种没人发现的事。
     */
    public enum Perm {
        /** 成员、登录名、口令：只有管理员。 */
        MANAGE_MEMBERS("成员管理"),
        /** 知识入库、文档可见范围、词库、模型配置：运营。 */
        OPS_CONTENT("运营内容"),
        /** 知识发布/退回、安全提示确认、话术与安全规则、流程：由审核者确认。 */
        CONTENT_REVIEW("内容审核"),
        /** 会话列表与元数据（谁、第几步、多久）：有身份就能看。 */
        SESSIONS_READ("会话列表"),
        /** 会话原文、轨迹、原始 JSON、导出：隐私，默认只给管理员。 */
        SESSIONS_CONTENT("会话原文"),
        /** 只读。 */
        VIEW("查看");

        private final String label;

        Perm(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 管理员对所有事都放行：这是刻意的"破窗"设定——组织里只剩一个人时，
     * 不能让系统把自己锁在门外。但代签会在审核记录里留下管理员的名字，
     * "谁签的字"不会因为权限大就变模糊。
     */
    public boolean allows(Perm perm) {
        if (ADMIN.equals(role)) return true;
        return switch (perm) {
            case VIEW -> true;
            // "有哪些会话"可以看；"里面说了什么"不行。这一条是隐私边界，不是权限口味。
            case SESSIONS_READ -> true;
            case SESSIONS_CONTENT -> false;
            case OPS_CONTENT -> OPERATOR.equals(role);
            case CONTENT_REVIEW -> REVIEWER.equals(role);
            case MANAGE_MEMBERS -> false;
        };
    }

    /** 能签字的人：管理员与审核者。只读成员看得到、但签不了。 */
    public boolean canReview() {
        return allows(Perm.CONTENT_REVIEW);
    }

    /**
     * 只读成员。拦截器用它做"写操作一律拒绝"的第一道闸——
     * 这道闸只管 viewer：运营和医生能不能写，取决于具体是哪个接口（见 {@link Perm}）。
     */
    public boolean isReadOnly() {
        return VIEWER.equals(role);
    }

    public String roleLabel() {
        if (ADMIN.equals(role)) return "管理员";
        if (OPERATOR.equals(role)) return "运营";
        if (REVIEWER.equals(role)) return "审核";
        if (VIEWER.equals(role)) return "只读";
        return role == null || role.isBlank() ? "未分配" : role;
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
