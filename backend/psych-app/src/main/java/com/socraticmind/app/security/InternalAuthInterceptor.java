package com.socraticmind.app.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口的钥匙。
 *
 * v0.1.0 的问题不是"内部后台做得不好"，而是它默认开着、且没有任何拦截——
 * 任何人都能改输出策略（把禁句删空）和流程话术（绕开确认点）。
 * 那两件事一旦可写，"确定性骨架"就只剩骨架。
 *
 * 令牌从 {@code X-Internal-Token} 头取，也接受 {@code ?token=}（方便 curl 与深链）。
 * 用常量时间比较，长度不同直接失败。
 */
public class InternalAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Internal-Token";

    private final byte[] expected;

    public InternalAuthInterceptor(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "psych.internal.enabled=true 但未配置 psych.internal.token：拒绝以无钥匙状态启动");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String provided = request.getHeader(HEADER);
        if (provided == null || provided.isBlank()) {
            provided = request.getParameter("token");
        }
        if (matches(provided)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"内部接口需要令牌\"}");
        return false;
    }

    private boolean matches(String provided) {
        if (provided == null) return false;
        byte[] bytes = provided.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != expected.length) return false;
        return MessageDigest.isEqual(bytes, expected);
    }
}
