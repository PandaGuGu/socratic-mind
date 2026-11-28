package com.socraticmind.app;

import com.socraticmind.common.model.Operator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 职责分离的边界：这里断言的是"谁能签什么字"，不是"谁叫什么"。
 *
 * 三个角色各守一段：运营入资料、医生签字、管理员管人。任何一格写错，
 * 都会让"上传的人顺便把字也签了"变成合法操作——那正是审核失去意义的方式。
 */
class RolePermTest {

    private static Operator who(String role) {
        return new Operator("mem_x", "ten_x", "team_x", "某人", role);
    }

    @Test
    void 管理员对所有事放行() {
        Operator admin = who(Operator.ADMIN);
        for (Operator.Perm p : Operator.Perm.values()) {
            assertTrue(admin.allows(p), "管理员应能做 " + p);
        }
    }

    @Test
    void 运营能入资料但不能确认() {
        Operator ops = who(Operator.OPERATOR);
        assertTrue(ops.allows(Operator.Perm.OPS_CONTENT));
        assertTrue(ops.allows(Operator.Perm.VIEW));
        assertFalse(ops.allows(Operator.Perm.CONTENT_REVIEW), "运营不该确认内容");
        assertFalse(ops.allows(Operator.Perm.MANAGE_MEMBERS), "运营不该改成员");
        assertTrue(ops.allows(Operator.Perm.SESSIONS_READ), "运营能看有哪些会话");
        assertFalse(ops.allows(Operator.Perm.SESSIONS_CONTENT), "运营不该读原文");
    }

    @Test
    void 审核者能确认但不能入资料() {
        Operator doctor = who(Operator.REVIEWER);
        assertTrue(doctor.allows(Operator.Perm.CONTENT_REVIEW));
        assertTrue(doctor.allows(Operator.Perm.VIEW));
        assertFalse(doctor.allows(Operator.Perm.OPS_CONTENT), "医生不该改资料与可见范围");
        assertFalse(doctor.allows(Operator.Perm.MANAGE_MEMBERS), "审核不该管成员");
        assertTrue(doctor.allows(Operator.Perm.SESSIONS_READ));
        assertFalse(doctor.allows(Operator.Perm.SESSIONS_CONTENT), "默认不读原文，例外只给安全会话");
    }

    @Test
    void 只读只读() {
        Operator viewer = who(Operator.VIEWER);
        assertTrue(viewer.allows(Operator.Perm.VIEW));
        assertFalse(viewer.allows(Operator.Perm.OPS_CONTENT));
        assertFalse(viewer.allows(Operator.Perm.CONTENT_REVIEW));
        assertFalse(viewer.allows(Operator.Perm.MANAGE_MEMBERS));
        assertTrue(viewer.isReadOnly());
    }

    @Test
    void 上传的人不能自己确认() {
        // 这条不是"角色不同"，而是流程上的约束：入库与确认分属两个人。
        assertFalse(who(Operator.OPERATOR).allows(Operator.Perm.CONTENT_REVIEW));
        assertTrue(who(Operator.REVIEWER).allows(Operator.Perm.CONTENT_REVIEW));
    }
}
