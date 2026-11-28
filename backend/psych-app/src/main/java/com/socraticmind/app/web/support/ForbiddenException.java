package com.socraticmind.app.web.support;

/**
 * 身份够了、但这件事不归你：映射 403。
 *
 * 与 {@link UnauthorizedException} 分开：401 是"你还没证明你是谁"，403 是
 * "知道你是谁，但这一页不该给你看"。混成一个码，前端就没法区分"去登录"
 * 和"别点了"。
 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
