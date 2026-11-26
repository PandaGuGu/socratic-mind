package com.socraticmind.app.security;

import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.OrgStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 操作者登录：口令 → 会话 cookie。
 *
 * 为什么会话要落库而不是签在 cookie 里：登出必须立刻生效。
 * 自签名 cookie 只能等过期，"点了登出但令牌还能用两小时"在审计上是不能接受的。
 *
 * 两个 cookie 分工：
 * - {@code sm_sid}  HttpOnly，脚本读不到，防 XSS 偷会话；
 * - {@code sm_csrf} 故意让脚本能读，用于双提交校验（攻击者能发请求，但读不到这个值）。
 */
@Service
public class OperatorSessionService {

    public static final String SID_COOKIE = "sm_sid";
    public static final String CSRF_COOKIE = "sm_csrf";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TTL = Duration.ofHours(8);

    private final OrgStore orgStore;
    private final PasswordHasher hasher;
    private final boolean secureCookie;

    public OperatorSessionService(OrgStore orgStore, PasswordHasher hasher,
                                  @Value("${psych.org.cookie-secure:false}") boolean secureCookie) {
        this.orgStore = orgStore;
        this.hasher = hasher;
        this.secureCookie = secureCookie;
    }

    /** 登录失败一律抛这个，调用方只回一句"用户名或口令不对"，不区分是哪一样错。 */
    public static class LoginFailed extends RuntimeException {
        public LoginFailed() {
            super("用户名或口令不对");
        }
    }

    public static class PasswordRejected extends RuntimeException {
        public PasswordRejected(String message) {
            super(message);
        }
    }

    public Operator login(String username, String password, HttpServletResponse response) {
        Optional<OrgStore.Credential> found = orgStore.credential(username);
        if (found.isEmpty() || found.get().passwordHash() == null || found.get().revokedAt() != null) {
            orgStore.recordLogin(username, false, "no_such_account");
            throw new LoginFailed();
        }
        OrgStore.Credential cred = found.get();
        if (!hasher.matches(password, cred.passwordHash())) {
            orgStore.recordLogin(username, false, "bad_password");
            throw new LoginFailed();
        }

        String csrf = randomToken();
        String sid = orgStore.createSession(cred.memberId(), csrf, Instant.now().plus(TTL));
        writeCookie(response, SID_COOKIE, sid, true);
        writeCookie(response, CSRF_COOKIE, csrf, false);
        orgStore.recordLogin(username, true, null);
        return cred.toOperator();
    }

    public Optional<OrgStore.SessionInfo> current(HttpServletRequest request) {
        String sid = cookie(request, SID_COOKIE);
        return sid == null ? Optional.empty() : orgStore.findSession(sid);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String sid = cookie(request, SID_COOKIE);
        if (sid != null) orgStore.revokeSession(sid);
        writeCookie(response, SID_COOKIE, "", true);
        writeCookie(response, CSRF_COOKIE, "", false);
    }

    /** 改口令 → epoch +1 → 所有旧会话失效。不是"顺便踢一下"，这是"改口令"的定义之一。 */
    public void changePassword(Operator operator, String oldPassword, String newPassword) {
        OrgStore.Credential cred = orgStore.credentialByMember(operator.id())
                .orElseThrow(() -> new PasswordRejected("这个账号还没有设置口令"));
        if (cred.passwordHash() == null || !hasher.matches(oldPassword, cred.passwordHash())) {
            throw new PasswordRejected("原口令不对");
        }
        // 比前台注册多一条：后台口令不允许纯数字。
        // 共用的 PasswordHasher.acceptable 只看长度，改它会牵动普通用户注册，
        // 而"能改生产配置的账号"值得更严一档。
        if (!PasswordHasher.acceptable(newPassword)
                || newPassword.chars().allMatch(Character::isDigit)) {
            throw new PasswordRejected("新口令至少 8 位，且不能是纯数字");
        }
        orgStore.updatePassword(operator.id(), hasher.hash(newPassword));
        orgStore.bumpEpoch(operator.id());
        orgStore.revokeSessionsOf(operator.id());
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void writeCookie(HttpServletResponse response, String name, String value, boolean httpOnly) {
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        cookie.setHttpOnly(httpOnly);
        cookie.setSecure(secureCookie);
        // None 才能跨站携带，但 None 必须配 Secure；本机开发用 Lax 即可。
        cookie.setAttribute("SameSite", "Lax");
        cookie.setMaxAge(value.isEmpty() ? 0 : (int) TTL.toSeconds());
        response.addCookie(cookie);
    }

    static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    /** 从请求里读操作者：cookie 会话优先，其次操作者令牌（脚本/API 用）。 */
    public Optional<OrgStore.SessionInfo> resolve(HttpServletRequest request) {
        return current(request);
    }
}
