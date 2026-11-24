package com.socraticmind.app.web;

import com.socraticmind.agent.cbt.CbtFlow;
import com.socraticmind.agent.cbt.TurnResult;
import com.socraticmind.agent.config.RuntimeLimits;
import com.socraticmind.agent.config.RuntimeLimitsStore;
import com.socraticmind.app.share.ShareService;
import com.socraticmind.app.security.UserTokenService;
import com.socraticmind.app.web.support.ConflictException;
import com.socraticmind.app.web.support.IdempotencyCache;
import com.socraticmind.app.web.support.NotFoundException;
import com.socraticmind.app.web.support.RateLimiter;
import com.socraticmind.app.web.support.TooManyRequestsException;
import com.socraticmind.app.web.support.UnauthorizedException;
import com.socraticmind.common.port.AccountStore;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 产品接口：一次 CBT 会话的开始、推进、确认、结束。
 *
 * 身份：客户端持有一枚服务端签发的令牌（{@link UserTokenService}）——
 * 未登录是设备令牌（v1），登录后是账号令牌（v2）。所有会话操作都要先证明"这条会话是你的"，
 * 否则一律按不存在处理（404 而不是 403，避免把"这个 id 存在"变成可探测的信息）。
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
    private final AccountStore accounts;
    private final ShareService shares;
    private final IdempotencyCache<TurnResult> idempotent = new IdempotencyCache<>(10_000);
    private final RateLimiter limiter = new RateLimiter();

    public CbtController(CbtFlow flow, CbtSessionStore store, UserTokenService tokens,
                         RuntimeLimitsStore limits, AccountStore accounts, ShareService shares) {
        this.flow = flow;
        this.store = store;
        this.tokens = tokens;
        this.limits = limits;
        this.accounts = accounts;
        this.shares = shares;
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
     * 新开一轮。第一次调用不带令牌：服务端生成设备身份并回发令牌；
     * 带有效令牌则沿用原身份（同一设备连续开几轮仍归同一个人）。
     * 带账号令牌时，这一轮额外挂到账号下——设备归属不变，退出登录后仍在。
     */
    @PostMapping
    public ResponseEntity<TurnResult> start(
            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
            @RequestBody(required = false) StartRequest request,
            HttpServletRequest http) {

        if (!limiter.allow("start:" + ip(http), limits.get().newSessionsPerHour(), Duration.ofHours(1))) {
            throw new TooManyRequestsException("开新会话太频繁，请稍后再试");
        }

        UserTokenService.Identity identity = tokens.verifyIdentity(token).orElse(null);
        boolean fresh = identity == null;
        if (fresh) identity = UserTokenService.Identity.device(tokens.newUserId());

        TurnResult result = flow.start(identity.userId(), identity.accountId());
        return ResponseEntity.ok()
                .header(USER_TOKEN_HEADER, fresh ? tokens.issue(identity.userId()) : token)
                .body(result);
    }

    @PostMapping("/{id}/messages")
    public TurnResult message(@PathVariable String id,
                              @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
                              @Valid @RequestBody MessageRequest request) {
        UserTokenService.Identity who = identity(token);
        owned(id, who);
        RuntimeLimits runtime = limits.get();
        if (request.message().length() > runtime.maxMessageChars()) {
            throw new IllegalArgumentException("单条消息不能超过 " + runtime.maxMessageChars() + " 字");
        }
        if (!limiter.allow("msg:" + who.userId(), runtime.messagesPerMinute(), Duration.ofMinutes(1))) {
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
        UserTokenService.Identity who = identity(token);
        owned(id, who);

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
        UserTokenService.Identity who = identity(token);
        return store.find(id)
                .filter(s -> canSee(s, who))
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
     * 生成一次性分享链接：只对"自己看得到的会话"生效。
     * 返回的 path 是相对地址（/share/?t=…），部署在子路径或反代下也不会拼错域名。
     */
    public record ShareRequest(Integer ttlHours) {
    }

    public record ShareResponse(String path, Instant expiresAt) {
    }

    @PostMapping("/{id}/share")
    public ShareResponse share(@PathVariable String id,
                               @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
                               @RequestBody(required = false) ShareRequest request) {
        UserTokenService.Identity who = identity(token);
        owned(id, who);
        ShareService.Created created = shares.create(id, who.userId(),
                request == null ? null : request.ttlHours());
        return new ShareResponse("/share/?t=" + created.token(), created.expiresAt());
    }

    /** 撤回这条会话还没被打开的全部链接。 */
    @DeleteMapping("/{id}/shares")
    public Map<String, Object> revokeShares(@PathVariable String id,
                                            @RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        UserTokenService.Identity who = identity(token);
        owned(id, who);
        return Map.of("revoked", shares.revoke(id, who.userId()));
    }

    /**
     * 只列自己的会话：未登录看设备名下的，登录后看账号名下的（见 {@link #canSee}）。
     * 之前的实现允许不传 userId 直接列出全库——那不是分页参数，是全量泄漏。
     */
    @GetMapping
    public List<SessionView> list(@RequestHeader(value = USER_TOKEN_HEADER, required = false) String token) {
        UserTokenService.Identity who = identity(token);
        return store.findVisible(who.userId(), who.accountId()).stream().map(SessionView::of).toList();
    }

    /**
     * 分页版列表。窗外只加载"看得见的一屏"，翻页靠游标，不靠 offset——
     * offset 在"一边翻一边新开一轮"时会把同一条记录显示两次或直接跳过。
     *
     * 游标是 (createdAt, id) 的 base64，客户端不需要理解它，原样带回即可。
     */
    public record PageView(List<SessionView> items, String nextCursor, boolean hasMore) {
    }

    @GetMapping("/page")
    public PageView page(@RequestHeader(value = USER_TOKEN_HEADER, required = false) String token,
                         @RequestParam(defaultValue = "20") int limit,
                         @RequestParam(required = false) String cursor) {
        UserTokenService.Identity who = identity(token);
        int size = Math.max(1, Math.min(limit, 50));
        Cursor from = decodeCursor(cursor);
        List<CbtSession> rows = store.findVisiblePage(who.userId(), who.accountId(),
                from == null ? null : from.at(), from == null ? null : from.id(), size + 1);
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        String next = rows.isEmpty() ? null : encodeCursor(rows.get(rows.size() - 1));
        return new PageView(rows.stream().map(SessionView::of).toList(), next, hasMore);
    }

    private record Cursor(Instant at, String id) {
    }

    private static String encodeCursor(CbtSession session) {
        // 用完整精度而不是毫秒：截断到毫秒后，同一毫秒内创建的会话会在翻页时被跳过。
        String raw = session.createdAt() + "|" + session.id();
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(java.util.Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8);
            int cut = raw.indexOf('|');
            if (cut <= 0 || cut == raw.length() - 1) throw new IllegalArgumentException("游标格式不对");
            return new Cursor(Instant.parse(raw.substring(0, cut)), raw.substring(cut + 1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("游标无效，请重新打开窗外");
        }
    }

    /**
     * 跨会话的情绪趋势：把本人（设备或账号）所有会话里用户自己点的分按天聚合。
     *
     * 它只做一件事：把已经存在的点排在一起。不插值、不预测、不给"情绪改善了"的结论——
     * 平均分高低既不等于病情轻重，也不等于治疗有效（见需求对齐 §9 的边界）。
     */
    public record MoodDay(String date, double average, int min, int max, int count) {
    }

    public record MoodTrend(int days, String from, String to, int points, int sessions, List<MoodDay> daily) {
    }

    /** 由 {@link MoodController} 暴露在 /api/cbt/mood/trend；这里只保留实现。 */
    public MoodTrend moodTrend(String token, int days) {
        UserTokenService.Identity who = identity(token);
        int span = Math.max(1, Math.min(days, 180));
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        java.time.LocalDate start = today.minusDays(span - 1L);

        // 每天四个数：和、次数、最小、最大。用数组而不是 Map<String, 自定义对象>，
        // 是因为这里只需要一次遍历，字段多一层包装反而更容易写错。
        Map<java.time.LocalDate, int[]> buckets = new java.util.TreeMap<>();
        int points = 0;
        int sessions = 0;
        for (CbtSession s : store.findVisible(who.userId(), who.accountId())) {
            boolean counted = false;
            for (com.socraticmind.session.MoodPoint p : s.moodPoints()) {
                java.time.LocalDate day = p.at().atZone(zone).toLocalDate();
                if (day.isBefore(start) || day.isAfter(today)) continue;
                int[] agg = buckets.computeIfAbsent(day, d -> new int[]{0, 0, p.score(), p.score()});
                agg[0] += p.score();
                agg[1] += 1;
                agg[2] = Math.min(agg[2], p.score());
                agg[3] = Math.max(agg[3], p.score());
                points++;
                counted = true;
            }
            if (counted) sessions++;
        }
        List<MoodDay> daily = new ArrayList<>();
        for (Map.Entry<java.time.LocalDate, int[]> e : buckets.entrySet()) {
            int[] agg = e.getValue();
            double avg = Math.round((double) agg[0] / agg[1] * 10.0) / 10.0;
            daily.add(new MoodDay(e.getKey().toString(), avg, agg[2], agg[3], agg[1]));
        }
        return new MoodTrend(span, start.toString(), today.toString(), points, sessions, daily);
    }


    /**
     * 令牌有效 + 账号仍然存在，才算登录。
     *
     * 第二句是 v0.6.0 补的：账号令牌是无状态签名串，本来删了账号也照样能验签。
     * 注销时把账号删掉，这里自然就 401——于是"注销"同时成为"吊销全部账号令牌"，
     * 不需要另建吊销表。代价是每个请求多一次账号查询（内存档是 Map，JDBC 档是一次主键查）。
     */
    private UserTokenService.Identity identity(String token) {
        UserTokenService.Identity who = tokens.verifyIdentity(token)
                .orElseThrow(() -> new UnauthorizedException("缺少或无效的身份令牌"));
        if (who.hasAccount()) {
            var account = accounts.findById(who.accountId());
            if (account.isEmpty()) {
                throw new UnauthorizedException("账号已注销，令牌随之失效");
            }
            if (account.get().tokenEpoch() != who.epoch()) {
                throw new UnauthorizedException("登录状态已失效，请重新登录");
            }
        }
        return who;
    }

    private void owned(String sessionId, UserTokenService.Identity who) {
        CbtSession session = store.find(sessionId)
                .orElseThrow(() -> new NotFoundException(SESSION_NOT_FOUND));
        if (!canSee(session, who)) {
            throw new NotFoundException(SESSION_NOT_FOUND);
        }
    }

    /**
     * 两条路，但不叠加：
     * <ul>
     *   <li>设备令牌（未登录）：只认设备归属；</li>
     *   <li>账号令牌：**只认账号归属**，不再走设备这条路。</li>
     * </ul>
     *
     * 第二条是刻意的：账号令牌里也签了设备 id，如果继续放行设备归属，
     * 同一个浏览器换一个账号登录，就能读到前一个账号在这台设备上开的会话。
     * 登录时已经把本机未认领的会话认领掉了，所以"账号看不到自己设备的会话"在实践中不出现。
     */
    private static boolean canSee(CbtSession session, UserTokenService.Identity who) {
        if (who.hasAccount()) {
            return who.accountId().equals(session.accountId());
        }
        return session.userId().equals(who.userId());
    }

    private static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
