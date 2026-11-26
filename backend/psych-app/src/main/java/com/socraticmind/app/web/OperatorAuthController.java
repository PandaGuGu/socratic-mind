package com.socraticmind.app.web;

import com.socraticmind.app.security.OperatorSessionService;
import com.socraticmind.app.web.support.RateLimitPort;
import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.OrgStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 操作者登录（/api/operator/**）。
 *
 * 刻意不放在 /api/internal/** 下面：那条路由要求内部门令牌，
 * 而登录恰恰是"还没有任何凭据"时唯一能走的入口。
 */
@RestController
@RequestMapping("/api/operator")
public class OperatorAuthController {

    private final OperatorSessionService sessions;
    private final OrgStore orgStore;
    private final RateLimitPort limiter;
    private final boolean orgEnabled;

    public OperatorAuthController(OperatorSessionService sessions, OrgStore orgStore,
                                  RateLimitPort limiter,
                                  @Value("${psych.org.enabled:false}") boolean orgEnabled) {
        this.sessions = sessions;
        this.orgStore = orgStore;
        this.limiter = limiter;
        this.orgEnabled = orgEnabled;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record PasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    private record Who(String id, String name, String role, String tenantId, String teamId) {
        static Who of(Operator op) {
            return new Who(op.id(), op.display(), op.role(), op.tenantId(), op.teamId());
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletResponse response) {
        if (!orgEnabled) {
            return ResponseEntity.status(409).body(Map.of("error", "组织模式未开启，后台仍用令牌访问"));
        }
        // 按"账号 + 口令"两个维度限：只按账号限会被人换账号撞库。
        if (!limiter.allow("operator-login:" + request.username(), 5, Duration.ofMinutes(1))) {
            orgStore.recordLogin(request.username(), false, "rate_limited");
            return ResponseEntity.status(429).body(Map.of("error", "尝试太频繁，等一分钟再试"));
        }
        try {
            Operator op = sessions.login(request.username(), request.password(), response);
            return ResponseEntity.ok(Map.of("user", Who.of(op)));
        } catch (OperatorSessionService.LoginFailed e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.logout(request, response);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request,
                                                  @RequestHeader(value = "X-Operator-Token", required = false)
                                                  String operatorToken,
                                                  @RequestAttribute(name = "sm.operator", required = false)
                                                  Operator operator) {
        var current = sessions.current(request);
        if (current.isPresent()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user", Who.of(current.get().operator()));
            out.put("csrfToken", current.get().csrfToken());
            return ResponseEntity.ok(out);
        }
        // 脚本/令牌模式没有 cookie 会话，但操作者令牌本身就是可验证的身份；
        // 页面靠它显示“署名来自登录身份”，而不是让审核人再手填一次。
        Operator byToken = orgStore.findByToken(operatorToken).orElse(null);
        if (byToken != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user", Who.of(byToken));
            return ResponseEntity.ok(out);
        }
        return ResponseEntity.status(401).body(Map.of("error", "未登录"));
    }

    @PostMapping("/password")
    public ResponseEntity<Map<String, Object>> password(@Valid @RequestBody PasswordRequest request,
                                                        HttpServletRequest http,
                                                        @RequestAttribute(name = "sm.operator", required = false)
                                                        Operator operator) {
        var current = sessions.current(http);
        if (current.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        try {
            sessions.changePassword(current.get().operator(), request.oldPassword(), request.newPassword());
            // 改完口令所有会话都失效，包括当前这个：让用户重新登录，是这里唯一诚实的选择。
            return ResponseEntity.ok(Map.of("ok", true, "note", "口令已改，所有设备已下线，请重新登录"));
        } catch (OperatorSessionService.PasswordRejected e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
