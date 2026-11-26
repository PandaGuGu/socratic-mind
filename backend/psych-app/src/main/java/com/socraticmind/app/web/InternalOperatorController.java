package com.socraticmind.app.web;

import com.socraticmind.common.port.OrgStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 登录留痕查询（内部令牌保护）：成功与失败都记，出事时要能查"最后一次正常登录是什么时候"。 */
@RestController
@RequestMapping("/api/internal/operator")
public class InternalOperatorController {

    private final OrgStore orgStore;

    public InternalOperatorController(OrgStore orgStore) {
        this.orgStore = orgStore;
    }

    @GetMapping("/login-attempts")
    public List<OrgStore.LoginAttempt> attempts() {
        return orgStore.loginAttempts(50);
    }
}
