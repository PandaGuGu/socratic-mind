package com.socraticmind.app.security;

import com.socraticmind.common.port.OrgStore;
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
 * 登录本身（/api/operator/**）不在这里：那是"还没有凭据"时唯一能走的路。
 */
@Configuration
@ConditionalOnProperty(name = "psych.internal.enabled", havingValue = "true")
public class InternalSecurityConfig implements WebMvcConfigurer {

    private final String token;
    private final OrgStore orgStore;
    private final OperatorSessionService sessions;
    private final boolean orgEnabled;

    public InternalSecurityConfig(@Value("${psych.internal.token:}") String token,
                                  OrgStore orgStore,
                                  OperatorSessionService sessions,
                                  @Value("${psych.org.enabled:false}") boolean orgEnabled) {
        this.token = token;
        this.orgStore = orgStore;
        this.sessions = sessions;
        this.orgEnabled = orgEnabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new InternalAuthInterceptor(token, orgStore, sessions, orgEnabled))
                .addPathPatterns(
                        "/api/internal/**",
                        "/api/knowledge/documents",
                        "/api/knowledge/documents/**",
                        "/api/knowledge/stats");
    }
}
