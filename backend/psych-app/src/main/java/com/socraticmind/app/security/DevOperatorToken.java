package com.socraticmind.app.security;

import org.springframework.stereotype.Component;

/**
 * 本机开发档的操作者令牌。
 *
 * 只在"组织模式 + dev-hint"同时打开时由种子任务写入，用来让 /internal 页面自动带上
 * X-Operator-Token——否则每个页面第一次操作都会被 401 拦住，验收时会以为是功能坏了。
 *
 * 正式环境不播种、不提示：令牌应该由管理员创建成员时一次性发放。
 */
@Component
public class DevOperatorToken {

    private volatile String token;

    public void set(String value) {
        this.token = value;
    }

    public String get() {
        return token;
    }
}
