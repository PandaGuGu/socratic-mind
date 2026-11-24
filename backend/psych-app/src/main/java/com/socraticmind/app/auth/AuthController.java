package com.socraticmind.app.auth;

import com.socraticmind.app.security.UserTokenService;
import com.socraticmind.app.web.support.RateLimiter;
import com.socraticmind.app.web.support.UnauthorizedException;
import com.socraticmind.app.web.support.TooManyRequestsException;
import com.socraticmind.common.account.Account;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 账号接口。与 /api/cbt/** 用同一个请求头 {@code X-User-Token}，
 * 区别只在令牌是 v1（设备）还是 v2（账号）。
 *
 * 这里刻意没有 /logout：令牌是无状态 HMAC，服务端没有会话表可删，
 * 写一个"返回 ok 但什么也没做"的接口等于骗人。退出登录 = 客户端丢掉令牌。
 *
 * 但 /delete 是有的：注销删掉账号本身，于是"账号还在不在"成了令牌是否有效的第二道判断，
 * 注销即吊销（见 {@link AuthService#deleteAccount}）。这条边界写在 ADR-0009 的后续里。
 *
 * 仍然没有：邮箱验证、找回口令、改口令。缺的不是接口，是发信通道与账号生命周期策略。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public static final String USER_TOKEN_HEADER = "X-User-Token";

    private final AuthService auth;
    private final UserTokenService tokens;
    private final RateLimiter limiter = new RateLimiter();

    public AuthController(AuthService auth, UserTokenService tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password,
            @Size(max = 32) String displayName) {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {
    }

    public record DeleteRequest(@NotBlank @Size(max = 128) String password) {
    }

    public record PasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                  @NotBlank @Size(max = 128) String newPassword) {
    }

    public record RecoverRequest(@NotBlank @Size(max = 254) String email,
                                 @NotBlank @Size(max = 64) String recoveryCode,
                                 @NotBlank @Size(max = 128) String newPassword) {
    }

    public record DeleteResponse(boolean deleted, int releasedSessions) {
    }

    public record AccountView(String id, String email, String displayName,
                              Instant createdAt, Instant lastLoginAt) {
        static AccountView of(Account a) {
            return new AccountView(a.id(), a.email(), a.displayName(), a.createdAt(), a.lastLoginAt());
        }
    }

    public record AuthResponse(String token, AccountView account, int pendingClaims,
                               String recoveryCode) {
    }

    /** signedIn=false 是正常返回，不是 401：房间页一进来就问，不该在控制台刷一片红。 */
    public record MeResponse(boolean signedIn, AccountView account) {
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest http) {
        // 注册限得比登录更紧：一个 IP 每小时建 20 个账号已经远超正常使用
        if (!limiter.allow("register:" + ip(http), 20, Duration.ofHours(1))) {
            throw new TooManyRequestsException("注册太频繁，请稍后再试");
        }
        return respond(auth.register(request.email(), request.password(), request.displayName(),
                tokens.verifyIdentity(token).orElse(null)));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest http) {
        // 两层限流：按 IP+邮箱挡住对单一账号的撞库；再按 IP 兜住"换着邮箱试"的那种
        if (!limiter.allow("login-ip:" + ip(http), 60, Duration.ofMinutes(1))) {
            throw new TooManyRequestsException("尝试太频繁，请一分钟后再试");
        }
        String key = "login:" + ip(http) + ":" + request.email().strip().toLowerCase(java.util.Locale.ROOT);
        if (!limiter.allow(key, 15, Duration.ofMinutes(1))) {
            throw new TooManyRequestsException("尝试太频繁，请一分钟后再试");
        }
        return respond(auth.login(request.email(), request.password(),
                tokens.verifyIdentity(token).orElse(null)));
    }

    @GetMapping("/me")
    public MeResponse me(@RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        Optional<UserTokenService.Identity> identity = tokens.verifyIdentity(token);
        if (identity.isEmpty()) return new MeResponse(false, null);
        Optional<Account> account = auth.current(identity.get());
        return account.map(a -> new MeResponse(true, AccountView.of(a)))
                .orElseGet(() -> new MeResponse(false, null));
    }

    /**
     * 注销账号。要口令，因为这一步不可逆；限流比登录更紧，避免把这里当成口令试错入口。
     * 注销后本机记录仍在：会话回到设备名下，页面会换回登录前的设备令牌。
     */
    @PostMapping("/delete")
    public DeleteResponse delete(
            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody DeleteRequest request,
            HttpServletRequest http) {
        if (!limiter.allow("delete:" + ip(http), 10, Duration.ofMinutes(10))) {
            throw new TooManyRequestsException("操作太频繁，请稍后再试");
        }
        UserTokenService.Identity who = tokens.verifyIdentity(token)
                .orElseThrow(() -> new UnauthorizedException("请先登录"));
        int released = auth.deleteAccount(who, request.password());
        return new DeleteResponse(true, released);
    }

    /**
     * 认领：登录后用户明确点过"归到这个账号"才调用。
     * 这是 v0.7.0 关掉"共享设备后来者自动接手"窗口的方式——服务端不再替用户做这个决定。
     */
    @PostMapping("/claim")
    public Map<String, Object> claim(@RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        UserTokenService.Identity who = tokens.verifyIdentity(token)
                .orElseThrow(() -> new UnauthorizedException("请先登录"));
        int claimed = auth.claim(who);
        return Map.of("claimed", claimed);
    }

    /** 改口令：当前设备换新令牌，其他设备上的旧令牌立即失效。 */
    @PostMapping("/password")
    public ResponseEntity<AuthResponse> changePassword(
            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody PasswordRequest request,
            HttpServletRequest http) {
        if (!limiter.allow("password:" + ip(http), 10, Duration.ofMinutes(10))) {
            throw new TooManyRequestsException("操作太频繁，请稍后再试");
        }
        UserTokenService.Identity who = tokens.verifyIdentity(token)
                .orElseThrow(() -> new UnauthorizedException("请先登录"));
        return respond(auth.changePassword(who, request.currentPassword(), request.newPassword()));
    }

    /** 退出其他设备：当前设备继续可用，其他设备下一次请求 401。 */
    @PostMapping("/logout-all")
    public ResponseEntity<AuthResponse> logoutAll(
            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        UserTokenService.Identity who = tokens.verifyIdentity(token)
                .orElseThrow(() -> new UnauthorizedException("请先登录"));
        return respond(auth.logoutOtherDevices(who));
    }

    /**
     * 用恢复码重置口令，不需要登录。成功即换到新令牌，
     * 账号当前的口令与所有旧令牌同时作废。
     */
    @PostMapping("/recover")
    public ResponseEntity<AuthResponse> recover(
            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
            @Valid @RequestBody RecoverRequest request,
            HttpServletRequest http) {
        if (!limiter.allow("recover-ip:" + ip(http), 10, Duration.ofMinutes(10))) {
            throw new TooManyRequestsException("尝试太频繁，请稍后再试");
        }
        String key = "recover:" + ip(http) + ":" + request.email().strip().toLowerCase(java.util.Locale.ROOT);
        if (!limiter.allow(key, 5, Duration.ofMinutes(10))) {
            throw new TooManyRequestsException("尝试太频繁，请稍后再试");
        }
        return respond(auth.recover(request.email(), request.recoveryCode(), request.newPassword(),
                tokens.verifyIdentity(token).orElse(null)));
    }

    /** 重新生成恢复码：旧码立即作废，新码只显示这一次。 */
    @PostMapping("/recovery-code")
    public Map<String, String> rotateRecoveryCode(
            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        UserTokenService.Identity who = tokens.verifyIdentity(token)
                .orElseThrow(() -> new UnauthorizedException("请先登录"));
        return Map.of("recoveryCode", auth.rotateRecoveryCode(who));
    }

    private static ResponseEntity<AuthResponse> respond(AuthService.Result result) {
        return ResponseEntity.ok()
                // 与 /api/cbt/sessions 一致：令牌同时放在头里，客户端不必解析响应体
                .header(USER_TOKEN_HEADER, result.token())
                .body(new AuthResponse(result.token(), AccountView.of(result.account()),
                        result.pendingClaims(), result.recoveryCode()));
    }

    private static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
