package com.socraticmind.app.web;

import com.socraticmind.agent.free.FreeChatFlow;
import com.socraticmind.app.security.UserTokenService;
import com.socraticmind.app.web.support.NotFoundException;
import com.socraticmind.app.web.support.RateLimitPort;
import com.socraticmind.app.web.support.TooManyRequestsException;
import com.socraticmind.app.web.support.UnauthorizedException;
import com.socraticmind.common.port.AccountStore;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CrisisCard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 门外：没有步骤的直接对聊。
 *
 * 和 {@code /api/cbt/sessions} 是两条并行的入口，不是它的子路径：
 * 那边每一次请求都在推动一个状态机，这边只有"我说了一句、它回了一句"。
 * 身份、限流、归属校验三件事沿用同一套做法（令牌 + 每设备每分钟上限 + 不属于你就 404）。
 */
@RestController
@RequestMapping("/api/free")
public class FreeChatController {

    public static final String USER_TOKEN_HEADER = "X-User-Token";

    private static final int ABSOLUTE_MESSAGE_CEILING = 20000;

    private final FreeChatFlow flow;
    private final CbtSessionStore store;
    private final UserTokenService tokens;
    private final AccountStore accounts;
    private final RateLimitPort limiter;

    public FreeChatController(FreeChatFlow flow, CbtSessionStore store, UserTokenService tokens,
                              AccountStore accounts, RateLimitPort limiter) {
        this.flow = flow;
        this.store = store;
        this.tokens = tokens;
        this.accounts = accounts;
        this.limiter = limiter;
    }

    public record SayRequest(
            @NotBlank @Size(max = ABSOLUTE_MESSAGE_CEILING, message = "超出系统允许的最大长度")
            String message,
            @Size(max = 64) String clientMessageId
    ) {
    }

    public record MessageView(String role, String content, String at) {
        static MessageView of(CbtMessage m) {
            return new MessageView(m.role(), m.content(), m.at() == null ? null : m.at().toString());
        }
    }

    public record FreeView(
            String sessionId,
            String reply,
            String status,
            String safetyStatus,
            CrisisCard crisis,
            String model,
            List<MessageView> messages
    ) {
        static FreeView of(FreeChatFlow.FreeTurn t) {
            return new FreeView(t.sessionId(), t.reply(), t.status(), t.safetyStatus(),
                    t.crisis(), t.model(),
                    t.messages().stream().map(MessageView::of).toList());
        }
    }

    /**
     * 走到门外。带令牌时按令牌认人；第一次来的设备在这里领一枚。
     * 已经在门外的人再点一次门，回到上一段对话，而不是又开一段。
     */
    @PostMapping("/start")
    public ResponseEntity<FreeView> start(
            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        UserTokenService.Identity identity = tokens.verifyIdentity(token).orElse(null);
        boolean fresh = identity == null;
        if (fresh) identity = UserTokenService.Identity.device(tokens.newUserId());
        else checkAccount(identity);

        var existing = flow.resume(identity.userId(), identity.accountId());
        if (existing.isEmpty()) {
            FreeView created = FreeView.of(flow.start(identity.userId(), identity.accountId()));
            return ResponseEntity.ok()
                    .header(USER_TOKEN_HEADER, fresh ? tokens.issue(identity.userId()) : token)
                    .body(created);
        }
        return ResponseEntity.ok()
                .header(USER_TOKEN_HEADER, fresh ? tokens.issue(identity.userId()) : token)
                .body(FreeView.of(existing.get()));
    }

    @PostMapping("/{id}/messages")
    public FreeView say(@PathVariable String id,
                        @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
                        @Valid @RequestBody SayRequest request) {
        UserTokenService.Identity who = identity(token);
        owned(id, who);
        if (!limiter.allow("free:" + who.userId(), 30, Duration.ofMinutes(1))) {
            throw new TooManyRequestsException("说得有点快，缓一下再继续");
        }
        return FreeView.of(flow.say(id, request.message()));
    }

    /** 刷新页面后接着上一段：没有就返回 404，前端照常从"走到门外"开始。 */
    @GetMapping("/current")
    public ResponseEntity<FreeView> current(
            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        UserTokenService.Identity who = identity(token);
        return flow.resume(who.userId(), who.accountId())
                .map(t -> ResponseEntity.ok(FreeView.of(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public FreeView get(@PathVariable String id,
                        @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        UserTokenService.Identity who = identity(token);
        owned(id, who);
        CbtSession session = store.find(id).orElseThrow(() -> new NotFoundException("这段对话不存在"));
        return new FreeView(session.id(), null, FreeChatFlow.STATUS_OPEN, session.safetyStatus(),
                null, null, session.messages().stream().map(MessageView::of).toList());
    }

    private UserTokenService.Identity identity(String token) {
        UserTokenService.Identity who = tokens.verifyIdentity(token)
                .orElseThrow(() -> new UnauthorizedException("缺少或无效的身份令牌"));
        checkAccount(who);
        return who;
    }

    private void checkAccount(UserTokenService.Identity who) {
        if (!who.hasAccount()) return;
        var account = accounts.findById(who.accountId());
        if (account.isEmpty()) throw new UnauthorizedException("账号已注销，令牌随之失效");
        if (account.get().tokenEpoch() != who.epoch()) {
            throw new UnauthorizedException("登录状态已失效，请重新登录");
        }
    }

    /** 不是你的，一律当作不存在：404 不会泄露"这个 id 真的存在"。 */
    private void owned(String sessionId, UserTokenService.Identity who) {
        CbtSession session = store.find(sessionId)
                .orElseThrow(() -> new NotFoundException("这段对话不存在"));
        if (session.stage() != CbtStage.FREE) {
            throw new NotFoundException("这段对话不存在");
        }
        boolean visible = who.hasAccount()
                ? who.accountId().equals(session.accountId())
                : who.userId().equals(session.userId());
        if (!visible) throw new NotFoundException("这段对话不存在");
    }
}
