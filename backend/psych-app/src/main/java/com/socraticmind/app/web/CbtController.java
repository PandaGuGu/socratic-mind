package com.socraticmind.app.web;

import com.socraticmind.agent.cbt.CbtFlow;
import com.socraticmind.agent.cbt.TurnResult;
import com.socraticmind.agent.config.RuntimeLimits;
import com.socraticmind.agent.config.RuntimeLimitsStore;
import com.socraticmind.app.security.UserTokenService;
import com.socraticmind.app.web.support.ConflictException;
import com.socraticmind.app.web.support.IdempotencyCache;
import com.socraticmind.app.web.support.NotFoundException;
import com.socraticmind.app.web.support.RateLimiter;
import com.socraticmind.app.web.support.TooManyRequestsException;
import com.socraticmind.app.web.support.UnauthorizedException;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CrisisCard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 产品接口：一次 CBT 会话的开始、推进、确认、结束。
 *
 * 身份：客户端持有一枚服务端签发的匿名令牌（{@link UserTokenService}），
 * 所有会话操作都要先证明"这条会话是你的"，否则一律按不存在处理（404 而不是 403，
 * 避免把"这个 id 存在"变成可探测的信息）。
 *
 * 这条约束是 v0.1.1 补上的：此前会话 id 是自增的、接口没有鉴权，
 * 任何人按序枚举就能读到别人的情境、自动想法与确认内容。
 */
@RestController
@RequestMapping("/api/cbt/sessions")
public class CbtController {

    public static final String USER_TOKEN_HEADER = "X-User-Token";

    /** 绝对上限：即使运维把动态上限调到最大，也不接受超过这个长度的请求体。 */
    private static final int ABSOLUTE_MESSAGE_CEILING = 20000;

    private static final String SESSION_NOT_FOUND = "会话不存在";

    private final CbtFlow flow;
    private final CbtSessionStore store;
    private final UserTokenService tokens;
    private final RuntimeLimitsStore limits;
    private final IdempotencyCache<TurnResult> idempotent = new IdempotencyCache<>(10_000);
    private final RateLimiter limiter = new RateLimiter();

    public CbtController(CbtFlow flow, CbtSessionStore store, UserTokenService tokens,
                         RuntimeLimitsStore limits) {
        this.flow = flow;
        this.store = store;
        this.tokens = tokens;
        this.limits = limits;
    }

    /** userId 字段保留只为兼容旧客户端；服务端一律忽略，身份由令牌决定。 */
    public record StartRequest(String userId) {
    }

    public record MessageRequest(
            @NotBlank @Size(max = ABSOLUTE_MESSAGE_CEILING, message = "超出系统允许的最大长度")
            String message,
            @Size(max = 64) String clientMessageId
    ) {
    }

    public record ConfirmRequest(@Size(max = ABSOLUTE_MESSAGE_CEILING) String thought,
                                 @Size(max = 64) String clientMessageId) {
    }

    /** 情绪自评：分数只能由用户点出来，服务端不接受"模型推断的分"。 */
    public record MoodRequest(@Min(0) @Max(10) int score) {
    }

    /** 紧急联系人：只在安全暂停时回显，不做外呼、不发短信。 */
    public record ContactRequest(
            @Size(max = 32) String name,
            @Size(max = 32) String phone,
            @Size(max = 16) String relation
    ) {
    }

    public record SessionView(
            String sessionId,
            String threadId,
            CbtStage stage,
            String stageLabel,
            int visibleIndex,
            String status,
            String targetProblem,
            String automaticThought,
            String candidateThought,
            String userConfirmedThought,
            String interventionType,
            String actionPlan,
            String actionResult,
            String summary,
            boolean awaitingUser,
            String safetyStatus,
            int turnCount,
            Instant createdAt,
            Instant updatedAt,
            /** 用户自己打的情绪分，按时间顺序；前端拿它画折线，后端不做插值。 */
            java.util.List<Integer> moodSeries,
            java.util.List<String> distortions,
            String contactName,
            String contactPhone,
            String contactRelation,
            Instant safetyAckAt,
            CrisisCard crisis
    ) {
        static SessionView of(CbtSession s) {
            boolean hold = "hold".equals(s.safetyStatus());
            return new SessionView(s.id(), s.threadId(), s.stage(), s.stage().label(),
                    s.stage().visibleIndex(), finished(s.stage()) ? "ended" : "running",
                    s.targetProblem(), s.automaticThought(), s.candidateThought(),
                    s.userConfirmedThought(), s.interventionType(), s.actionPlan(),
                    s.actionResult(), s.summary(), s.awaitingUser(), s.safetyStatus(),
                    s.trace().size(), s.createdAt(), s.updatedAt(),
                    s.moodPoints().stream().map(com.socraticmind.session.MoodPoint::score).toList(),
                    s.distortions(), s.contactName(), s.contactPhone(), s.contactRelation(),
                    s.safetyAckAt(), hold ? CrisisCard.of(s, s.safetyReason()) : null);
        }

        /**
         * 终止判定：安全暂停与显式结束之外，"走到总结"也必须算结束——
         * CbtFlow 在 ACTION_REVIEW 之后把 stage 落在 SESSION_SUMMARY 就不再推进，
         * 若只看 stage.isTerminal()，这一轮会一直被报成 running。
         */
        private static boolean finished(CbtStage stage) {
            return stage.isTerminal() || stage == CbtStage.SESSION_SUMMARY;
        }
    }

    /**
     * 新开一轮。第一次调用不带令牌：服务端生成身份并回发令牌；
     * 带有效令牌则沿用原身份（同一设备连续开几轮仍归同一个人）。
     */
    @PostMapping
    public ResponseEntity<TurnResult> start(
            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) StartRequest request,
            HttpServletRequest http) {

        if (!limiter.allow("start:" + ip(http), limits.get().newSessionsPerHour(), Duration.ofHours(1))) {
            throw new TooManyRequestsException("开新会话太频繁，请稍后再试");
        }

        String userId = tokens.verify(token).orElse(null);
        boolean fresh = userId == null;
        if (fresh) userId = tokens.newUserId();

        TurnResult result = flow.start(userId);
        return ResponseEntity.ok()
                .header(USER_TOKEN_HEADER, fresh ? tokens.issue(userId) : token)
                .body(result);
    }

    @PostMapping("/{id}/messages")
    public TurnResult message(@PathVariable String id,
                              @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
                              @Valid @RequestBody MessageRequest request) {
        String userId = identity(token);
        owned(id, userId);
        RuntimeLimits runtime = limits.get();
        if (request.message().length() > runtime.maxMessageChars()) {
            throw new IllegalArgumentException("单条消息不能超过 " + runtime.maxMessageChars() + " 字");
        }
        if (!limiter.allow("msg:" + userId, runtime.messagesPerMinute(), Duration.ofMinutes(1))) {
            throw new TooManyRequestsException("说得太快了，缓一下再继续");
        }

        String key = request.clientMessageId() == null ? null : id + "|" + request.clientMessageId();
        TurnResult previous = idempotent.get(key);
        if (previous != null) return previous;

        TurnResult result = flow.handle(id, request.message());
        idempotent.put(key, result);
        return result;
    }

    /** 唯一的确认入口。前端不能通过 messages 推进这一节点。 */
    @PostMapping("/{id}/confirm")
    public TurnResult confirm(@PathVariable String id,
                              @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
                              @RequestBody(required = false) ConfirmRequest request) {
        String userId = identity(token);
        owned(id, userId);

        String clientMessageId = request == null ? null : request.clientMessageId();
        String key = clientMessageId == null ? null : id + "|confirm|" + clientMessageId;
        TurnResult previous = idempotent.get(key);
        if (previous != null) return previous;

        TurnResult result;
        try {
            result = flow.confirm(id, request == null ? null : request.thought());
        } catch (IllegalStateException e) {
            // 并发下第二次确认会走到这里：不是服务故障，是状态已经变了。
            throw new ConflictException(e.getMessage());
        }
        idempotent.put(key, result);
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionView> get(@PathVariable String id,
                                           @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        String userId = identity(token);
        return store.find(id)
                .filter(s -> s.userId().equals(userId))
                .map(s -> ResponseEntity.ok(SessionView.of(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/history")
    public List<CbtMessage> history(@PathVariable String id,
                                    @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        owned(id, identity(token));
        return flow.history(id);
    }

    /**
     * 记一次情绪自评。低频自评（≤3）会进"关注"状态，只影响后台标记与下次提示，
     * 不改变流程、不触发安全暂停——分数低不等于危机，这条边界不能混。
     */
    @PostMapping("/{id}/mood")
    public SessionView mood(@PathVariable String id,
                            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
                            @Valid @RequestBody MoodRequest request) {
        owned(id, identity(token));
        CbtSession session = store.find(id).orElseThrow(() -> new NotFoundException(SESSION_NOT_FOUND));
        session.addMood(request.score(), session.trace().size());
        if (request.score() <= 3 && !"hold".equals(session.safetyStatus())) {
            session.setSafetyStatus("watch");
            session.setSafetyReason("自评 " + request.score() + "/10，进入关注（不是危机判定）");
        }
        store.save(session);
        return SessionView.of(session);
    }

    /**
     * 设置紧急联系人。允许随时改、允许只填称呼不填号码。
     * 手机号只做基本清洗（保留数字、+、-、空格），不做格式校验——校验失败不该让人填不进去。
     */
    @PutMapping("/{id}/contact")
    public SessionView contact(@PathVariable String id,
                               @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
                               @Valid @RequestBody ContactRequest request) {
        owned(id, identity(token));
        CbtSession session = store.find(id).orElseThrow(() -> new NotFoundException(SESSION_NOT_FOUND));
        String phone = request.phone() == null ? null : request.phone().replaceAll("[^0-9+\\- ]", "");
        session.setContact(trim(request.name()), trim(phone), trim(request.relation()));
        store.save(session);
        return SessionView.of(session);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String t = value.strip();
        return t.isEmpty() ? null : t;
    }

    @PostMapping("/{id}/end")
    public TurnResult end(@PathVariable String id,
                          @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        owned(id, identity(token));
        return flow.end(id);
    }

    /**
     * 只列自己的会话。
     * 之前的实现允许不传 userId 直接列出全库——那不是分页参数，是全量泄漏。
     */
    @GetMapping
    public List<SessionView> list(@RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        String userId = identity(token);
        return store.findByUser(userId).stream().map(SessionView::of).toList();
    }

    private String identity(String token) {
        return tokens.verify(token).orElseThrow(() -> new UnauthorizedException("缺少或无效的身份令牌"));
    }

    private void owned(String sessionId, String userId) {
        CbtSession session = store.find(sessionId)
                .orElseThrow(() -> new NotFoundException(SESSION_NOT_FOUND));
        if (!session.userId().equals(userId)) {
            throw new NotFoundException(SESSION_NOT_FOUND);
        }
    }

    private static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
