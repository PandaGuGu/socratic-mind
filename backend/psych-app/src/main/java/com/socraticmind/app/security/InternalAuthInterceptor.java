package com.socraticmind.app.security;

import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.OrgStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口的两条通路。
 *
 * 一、浏览器：会话 cookie（{@code sm_sid}）。登录后就有了，不再需要任何头。
 * 二、脚本 / API：{@code X-Internal-Token} + {@code X-Operator-Token}。
 *
 * 两条通路都归结到同一个 {@link Operator}，放进请求属性 {@code sm.operator}——
 * 后面的控制器只认这一个来源，不关心你是用 cookie 还是用令牌进来的。
 *
 * CSRF：只有 cookie 认证需要校验（令牌认证的请求本身不带浏览器自动附带的凭据，
 * 不存在"被别的站点借用身份"的问题）。GET/HEAD 不校验，否则页面加载就会被拦。
 */
public class InternalAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Internal-Token";
    public static final String OPERATOR_HEADER = "X-Operator-Token";
    public static final String OPERATOR_ATTR = "sm.operator";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private final byte[] expected;
    private final OrgStore orgStore;
    private final OperatorSessionService sessions;
    private final boolean orgEnabled;

    public InternalAuthInterceptor(String token, OrgStore orgStore, OperatorSessionService sessions,
                                   boolean orgEnabled) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "psych.internal.enabled=true 但未配置 psych.internal.token：拒绝以无钥匙状态启动");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
        this.orgStore = orgStore;
        this.sessions = sessions;
        this.orgEnabled = orgEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        var session = sessions.current(request);
        Operator operator = session.map(OrgStore.SessionInfo::operator).orElse(null);
        boolean cookieAuth = operator != null;
        if (!cookieAuth) {
            operator = orgStore.findByToken(request.getHeader(OPERATOR_HEADER)).orElse(null);
        }

        boolean internalOk = matches(request.getHeader(HEADER)) || matches(request.getParameter("token"));
        boolean safeMethod = "GET".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod());

        if (operator == null && !internalOk) {
            return deny(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "UNAUTHORIZED", "需要登录或内部令牌");
        }
        if (operator == null && orgEnabled && !safeMethod) {
            // 写操作必须有身份：没有身份的记录只能记成"未署名"，那种记录不该被当审核用。
            // 读操作放宽：查登录日志这件事，不该要求你先能登录——
            // 登录坏了的时候，恰恰是唯一需要看日志的时候。
            return deny(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "OPERATOR_REQUIRED", "这个操作需要操作者令牌（X-Operator-Token）");
        }
        if (cookieAuth && !safeMethod) {
            String expectedCsrf = session.get().csrfToken();
            String provided = request.getHeader(CSRF_HEADER);
            if (expectedCsrf == null || !expectedCsrf.equals(provided)) {
                return deny(response, HttpServletResponse.SC_FORBIDDEN,
                        "CSRF", "缺少或错误的 CSRF 令牌，请刷新页面后重试");
            }
        }
        if (orgEnabled && operator != null && !safeMethod && !operator.canReview()) {
            return deny(response, HttpServletResponse.SC_FORBIDDEN,
                    "READ_ONLY", "当前操作者只有只读权限，不能签署或修改");
        }

        if (operator != null) request.setAttribute(OPERATOR_ATTR, operator);
        return true;
    }

    private boolean deny(HttpServletResponse response, int status, String code, String message)
            throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
        return false;
    }

    private boolean matches(String provided) {
        if (provided == null) return false;
        byte[] bytes = provided.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != expected.length) return false;
        return MessageDigest.isEqual(bytes, expected);
    }
}
