package com.socraticmind.app.web.support;

/** 触发限流：429。 */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
