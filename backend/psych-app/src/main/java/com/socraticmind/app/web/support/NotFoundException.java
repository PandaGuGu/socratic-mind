package com.socraticmind.app.web.support;

/**
 * 对象不存在，或存在但不属于当前身份：404。
 * 两者用同一个响应，避免把"这条会话存在"变成可探测的信息。
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
