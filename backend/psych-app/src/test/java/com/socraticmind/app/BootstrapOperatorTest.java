package com.socraticmind.app;

import com.socraticmind.app.security.PasswordHasher;
import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.OrgStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器档（dev-hint 关闭）必须有一个能登录的初始账号。
 *
 * 之前的坑：dev-hint 一关，登录页就是一张空表单——没有账号可登，
 * 控制台等于进不去。这里锁住两条：
 * 1. 按启动参数播种出来的账号真的能校验通过；
 * 2. 再跑一次播种不会改口令（幂等），否则重启就把人家改过的口令顶掉了。
 */
@SpringBootTest(properties = {
        "psych.org.enabled=true",
        "psych.internal.dev-hint=false",
        "psych.org.bootstrap.username=admin",
        "psych.org.bootstrap.password=bootstrap-pass-1234"
})
class BootstrapOperatorTest {

    @Autowired
    OrgStore orgStore;

    @Autowired
    PasswordHasher hasher;

    @Autowired
    @Qualifier("seedOrgRunner")
    ApplicationRunner seedOrgRunner;

    @Test
    void seedsLoginAccountWhenDevHintIsOff() {
        var credential = orgStore.credential("admin").orElse(null);
        assertNotNull(credential, "dev-hint 关闭时必须播种初始账号，否则登录页没有账号可用");
        assertEquals(Operator.ADMIN, credential.role());
        assertTrue(hasher.matches("bootstrap-pass-1234", credential.passwordHash()));
    }

    @Test
    void reseedingDoesNotOverwriteThePassword() {
        var before = orgStore.credential("admin").orElseThrow();
        assertDoesNotThrow(() -> seedOrgRunner.run(null));

        var after = orgStore.credential("admin").orElseThrow();
        assertEquals(before.memberId(), after.memberId());
        assertEquals(before.passwordHash(), after.passwordHash());
        assertTrue(hasher.matches("bootstrap-pass-1234", after.passwordHash()));
    }
}
