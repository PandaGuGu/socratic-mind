package com.socraticmind.app.security;

import com.socraticmind.common.model.Operator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 这个接口代表哪一种职责。
 *
 * 写在方法上而不是在控制器里 {@code if (op.allows(...))}：权限判断散在业务代码里，
 * 半年后没人说得清"哪些接口是医生才能调的"。注解集中一处，再由拦截器统一执行，
 * 新增接口忘了标也会被下面的"默认拒绝写"兜住（见 InternalAuthInterceptor）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    Operator.Perm value();
}
