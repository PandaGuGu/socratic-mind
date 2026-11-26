package com.socraticmind.app;

import com.socraticmind.app.security.OperatorSessionService;
import com.socraticmind.app.security.PasswordHasher;
import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.OrgStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台登录：口令 → 会话 → 登出/改口令失效。
 *
 * 这里不测"页面能不能登进去"，测的是四条不变量：
 * 口令错了不能给会话、登出立刻失效、改口令让所有旧会话失效、失败要留痕。
 * 前三条任何一条不成立，登录就只是装饰。
 */
@SpringBootTest(properties = {"psych.org.enabled=true"})
class OperatorLoginTest {

    @Autowired
    OperatorSessionService sessions;

    @Autowired
    OrgStore orgStore;

    @Autowired
    PasswordHasher hasher;

    private Operator account(String username, String password) {
        var tenant = orgStore.createTenant("登录测试租户" + username);
        var issued = orgStore.createMember(tenant.id(), null, "测试" + username, Operator.ADMIN);
        orgStore.setCredential(issued.member().id(), username, hasher.hash(password));
        return orgStore.findByToken(issued.token()).orElseThrow();
    }

    private static MockHttpServletRequest requestWith(String sid) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (sid != null) req.setCookies(new Cookie(OperatorSessionService.SID_COOKIE, sid));
        return req;
    }

    @Test
    void correctPasswordCreatesUsableSession() {
        account("u_login", "pass-123456");
        MockHttpServletResponse res = new MockHttpServletResponse();

        Operator op = sessions.login("u_login", "pass-123456", res);
        assertEquals("测试u_login", op.name());

        Cookie sid = res.getCookie(OperatorSessionService.SID_COOKIE);
        assertNotNull(sid, "登录必须下发会话 cookie");
        assertTrue(sid.isHttpOnly(), "会话 cookie 必须 HttpOnly，否则 XSS 能直接偷");
        Cookie csrf = res.getCookie(OperatorSessionService.CSRF_COOKIE);
        assertNotNull(csrf);
        assertFalse(csrf.isHttpOnly(), "CSRF cookie 必须能被脚本读到，否则双提交无从谈起");

        Optional<OrgStore.SessionInfo> current = sessions.current(requestWith(sid.getValue()));
        assertTrue(current.isPresent());
        assertEquals(op.id(), current.get().operator().id());
        assertEquals(csrf.getValue(), current.get().csrfToken(), "会话里的 CSRF 值要能和 cookie 对上");
    }

    @Test
    void wrongPasswordGivesNoSessionAndIsRecorded() {
        account("u_bad", "right-pass");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThrows(OperatorSessionService.LoginFailed.class,
                () -> sessions.login("u_bad", "wrong-pass", res));
        assertEquals(null, res.getCookie(OperatorSessionService.SID_COOKIE), "失败不能下发会话");

        boolean recorded = orgStore.loginAttempts(50).stream()
                .anyMatch(a -> a.username().equals("u_bad") && !a.ok() && "bad_password".equals(a.reason()));
        assertTrue(recorded, "失败必须留痕，否则撞库时看不出异常");
    }

    @Test
    void logoutInvalidatesSessionImmediately() {
        account("u_out", "pass-123456");
        MockHttpServletResponse res = new MockHttpServletResponse();
        sessions.login("u_out", "pass-123456", res);
        String sid = res.getCookie(OperatorSessionService.SID_COOKIE).getValue();

        assertTrue(sessions.current(requestWith(sid)).isPresent());
        sessions.logout(requestWith(sid), new MockHttpServletResponse());
        assertTrue(sessions.current(requestWith(sid)).isEmpty(),
                "登出必须立刻生效——这正是会话落库而不是签在 cookie 里的理由");
    }

    @Test
    void changingPasswordKicksEverySession() {
        account("u_pwd", "old-pass-1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        Operator op = sessions.login("u_pwd", "old-pass-1", res);
        String sid = res.getCookie(OperatorSessionService.SID_COOKIE).getValue();
        assertTrue(sessions.current(requestWith(sid)).isPresent());

        sessions.changePassword(op, "old-pass-1", "new-pass-2");

        assertTrue(sessions.current(requestWith(sid)).isEmpty(), "改口令后旧会话必须失效");
        MockHttpServletResponse again = new MockHttpServletResponse();
        assertThrows(OperatorSessionService.LoginFailed.class,
                () -> sessions.login("u_pwd", "old-pass-1", again), "旧口令不能再登录");
        assertNotNull(sessions.login("u_pwd", "new-pass-2", new MockHttpServletResponse()));
    }

    @Test
    void weakPasswordIsRejected() {
        Operator op = account("u_weak", "old-pass-1");
        assertThrows(OperatorSessionService.PasswordRejected.class,
                () -> sessions.changePassword(op, "old-pass-1", "12345678"));
    }
}
