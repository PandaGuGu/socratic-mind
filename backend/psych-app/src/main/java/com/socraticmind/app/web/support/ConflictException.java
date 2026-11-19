package com.socraticmind.app.web.support;

/** 状态冲突：例如重复确认、并发生效的那一次已经推进过流程。映射 409。 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
