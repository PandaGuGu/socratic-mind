package com.socraticmind.app.web.support;

/** 缺少或伪造令牌：401。 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
