package com.socraticmind.app.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把内部接口的钥匙挂到路由上。
 *
 * 覆盖两类入口：
 * 1. /api/internal/** —— 运维控制台（读会话、改策略、改流程）；
 * 2. 知识库的写入与文档列表 —— 入库即"证据"，不能匿名投毒。
 *
 * 产品侧的 /api/cbt/** 不走这里：它用的是另一套匿名用户令牌（UserTokenService），
 * 两者互不通用——运维的钥匙不该能读用户会话的写接口，反过来也一样。
 */
@Configuration
@ConditionalOnProperty(name = "psych.internal.enabled", havingValue = "true")
public class InternalSecurityConfig implements WebMvcConfigurer {

    private final String token;

    public InternalSecurityConfig(@Value("${psych.internal.token:}") String token) {
        this.token = token;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new InternalAuthInterceptor(token))
                .addPathPatterns(
                        "/api/internal/**",
                        "/api/knowledge/documents",
                        "/api/knowledge/documents/**",
                        "/api/knowledge/stats");
    }
}
