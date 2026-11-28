package com.socraticmind.app.web;

import com.socraticmind.agent.cbt.CbtFlow;
import com.socraticmind.agent.cbt.TurnResult;
import com.socraticmind.agent.cbt.flow.BranchRule;
import com.socraticmind.agent.cbt.flow.ConditionWhen;
import com.socraticmind.agent.cbt.flow.FlowDefinition;
import com.socraticmind.agent.cbt.flow.FlowParamsStore;
import com.socraticmind.agent.cbt.flow.FlowValidator;
import com.socraticmind.agent.cbt.flow.StageSpec;
import com.socraticmind.agent.cbt.safety.SafetyCase;
import com.socraticmind.agent.cbt.safety.SafetyRule;
import com.socraticmind.agent.cbt.safety.SafetyRules;
import com.socraticmind.agent.cbt.safety.SafetyRulesStore;
import com.socraticmind.agent.cbt.safety.SafetyRulesValidator;
import com.socraticmind.agent.cbt.safety.SafetyScreener;
import com.socraticmind.agent.config.ConfigVersion;
import com.socraticmind.agent.config.RuntimeLimits;
import com.socraticmind.agent.config.RuntimeLimitsStore;
import com.socraticmind.app.lexicon.LexiconStore;
import com.socraticmind.app.web.support.ForbiddenException;
import com.socraticmind.app.review.ReviewStore;
import com.socraticmind.common.account.Account;
import com.socraticmind.common.guard.GuardRules;
import com.socraticmind.common.guard.GuardViolation;
import com.socraticmind.common.guard.OutputPolicy;
import com.socraticmind.common.guard.ProfileRule;
import com.socraticmind.common.model.Operator;
import com.socraticmind.common.port.AccountStore;
import com.socraticmind.common.port.EmbeddingPort;
import com.socraticmind.infrastructure.guard.GuardAuditEntry;
import com.socraticmind.infrastructure.guard.GuardAuditLog;
import com.socraticmind.infrastructure.ai.AiConfigStore;
import com.socraticmind.infrastructure.ai.AiProperties;
import com.socraticmind.infrastructure.ai.AiRuntimeConfig;
import com.socraticmind.infrastructure.ai.ModelCallLog;
import com.socraticmind.infrastructure.guard.OutputPolicyStore;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CbtTurnEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import com.socraticmind.app.security.RequirePerm;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 内部只读控制台接口。它不是产品功能，读者是作者与评审：
 * - 只读：不提供任何改写会话状态的入口（确认仍然只能走 /api/cbt/sessions/{id}/confirm）；
 * - 诚实：把"内存档 / 桩模型 / 无鉴权"三件事显式暴露，避免演示时被误认为生产可用；
 * - 可复现：演示会话走真实流程，不是前端编造的假数据。
 *
 * 设计说明见 docs/03-设计/03-内部后台.md。**默认关闭**：
 * 只有显式 {@code psych.internal.enabled=true} 才会装配，且必须同时配置
 * {@code psych.internal.token}，否则启动失败——宁可起不来，也不要裸奔。
 */
@RestController
@RequestMapping("/api/internal")
@ConditionalOnProperty(name = "psych.internal.enabled", havingValue = "true")
public class InternalController {

    private final CbtFlow flow;
    private final CbtSessionStore store;
    private final OutputPolicyStore policyStore;
    private final FlowParamsStore flowParams;
    private final SafetyRulesStore safetyRules;
    private final RuntimeLimitsStore limits;
    private final GuardAuditLog guardAudit;
    private final LexiconStore lexiconStore;
    private final AccountStore accounts;
    private final ReviewStore reviews;
    private final ModelCallLog modelLog;
    private final EmbeddingPort embeddingPort;
    private final AiProperties aiProperties;
    private final AiConfigStore aiConfigStore;
    private final boolean authEnabled;
    private final boolean orgEnabled;

    public InternalController(CbtFlow flow,
                              CbtSessionStore store,
                              OutputPolicyStore policyStore,
                              FlowParamsStore flowParams,
                              SafetyRulesStore safetyRules,
                              RuntimeLimitsStore limits,
                              GuardAuditLog guardAudit,
                              LexiconStore lexiconStore,
                              AccountStore accounts,
                              ReviewStore reviews,
                              ModelCallLog modelLog,
                              EmbeddingPort embeddingPort,
                              AiProperties aiProperties,
                              AiConfigStore aiConfigStore,
                              @Value("${psych.internal.token:}") String internalToken,
                              @Value("${psych.org.enabled:false}") boolean orgEnabled) {
        this.flow = flow;
        this.store = store;
        this.policyStore = policyStore;
        this.flowParams = flowParams;
        this.safetyRules = safetyRules;
        this.limits = limits;
        this.guardAudit = guardAudit;
        this.lexiconStore = lexiconStore;
        this.accounts = accounts;
        this.reviews = reviews;
        this.modelLog = modelLog;
        this.embeddingPort = embeddingPort;
        this.aiProperties = aiProperties;
        this.aiConfigStore = aiConfigStore;
        // 开启内部接口时令牌是必填的（见 InternalAuthInterceptor），这里如实反映"有没有钥匙"
        this.authEnabled = internalToken != null && !internalToken.isBlank();
        this.orgEnabled = orgEnabled;
    }

    public record RuntimeView(
            String store,
            boolean durable,
            String aiProvider,
            String chatModel,
            boolean stubModel,
            boolean authEnabled,
            int sessions,
            int safetyHolds,
            int pendingReview,
            int awaiting,
            int events,
            int policyVersion,
            int guardRejections,
            int flowVersion,
            Map<String, Integer> stageCounts,
            Instant serverTime
    ) {
    }

    public record SessionRow(
            String sessionId,
            String userId,
            CbtStage stage,
            String stageLabel,
            int visibleIndex,
            boolean terminal,
            boolean awaiting,
            String safetyStatus,
            String interventionType,
            String targetProblem,
            int turns,
            int messages,
            boolean demo,
            Instant createdAt,
            Instant updatedAt
    ) {
        static SessionRow of(CbtSession s, boolean withContent) {
            return new SessionRow(
                    s.id(), s.userId(), s.stage(), s.stage().label(), s.stage().visibleIndex(),
                    s.stage().isTerminal(), s.awaitingUser(), s.safetyStatus(), s.interventionType(),
                    withContent ? s.targetProblem() : null,
                    s.trace().size(), s.history().size(), isDemo(s.userId()),
                    s.createdAt(), s.updatedAt());
        }

        private static boolean isDemo(String userId) {
            return userId != null && userId.startsWith("demo");
        }
    }

    /** 干预决策下的分支。taken 只由 interventionType 推出，不靠文案猜。 */
    public record Branch(String key, String label, boolean taken) {
    }

    public record SessionTrace(
            CbtController.SessionView session,
            List<CbtMessage> messages,
            List<CbtTurnEvent> trace,
            List<Branch> branches
    ) {
    }

    /**
     * 谁能读一段对话的原文。
     *
     * 默认只有管理员：这个产品的承诺是"不写业务状态、不替用户判断"，
     * 后台能随手翻所有人的原话，这个承诺就不成立了。
     * 唯一的例外是审核者可以对**已进入安全流程**的会话读原文——那些是他必须看的。
     */
    private static boolean canReadContent(Operator me, CbtSession s) {
        if (me == null) return true; // 内部令牌：破窗钥匙，不代表某个人
        if (me.allows(Operator.Perm.SESSIONS_CONTENT)) return true;
        boolean marked = s.stage() == CbtStage.SAFETY_HOLD
                || "hold".equals(s.safetyStatus())
                || "review".equals(s.safetyStatus())
                || "watch".equals(s.safetyStatus());
        return marked && me.allows(Operator.Perm.CONTENT_REVIEW);
    }

    @GetMapping("/overview")
    public RuntimeView overview() {
        List<CbtSession> all = store.findByUser(null);
        Map<String, Integer> stageCounts = new LinkedHashMap<>();
        for (CbtStage stage : CbtStage.values()) {
            stageCounts.put(stage.name(), 0);
        }
        int holds = 0;
        int review = 0;
        int awaiting = 0;
        int events = 0;
        for (CbtSession s : all) {
            stageCounts.merge(s.stage().name(), 1, Integer::sum);
            if (s.stage() == CbtStage.SAFETY_HOLD) holds++;
            if ("review".equals(s.safetyStatus())) review++;
            if (s.awaitingUser()) awaiting++;
            events += s.trace().size();
        }
        String storeName = store.getClass().getSimpleName();
        boolean durable = !storeName.toLowerCase().contains("memory");
        int guardRejections = (int) guardAudit.recent(200).stream()
                .filter(e -> GuardAuditEntry.REJECTED.equals(e.outcome()))
                .count();
        AiRuntimeConfig ai = aiConfigStore.current();
        return new RuntimeView(storeName, durable, ai.mode(), ai.chatModel(),
                AiRuntimeConfig.STUB.equals(ai.mode()), authEnabled,
                all.size(), holds, review, awaiting, events,
                policyStore.get().version(), guardRejections, flowParams.get().version(),
                stageCounts, Instant.now());
    }

    @GetMapping("/sessions")
    @RequirePerm(Operator.Perm.SESSIONS_READ)
    public List<SessionRow> sessions(@RequestParam(required = false) String userId,
                                     @RequestParam(required = false) String stage,
                                     @RequestParam(defaultValue = "false") boolean safetyOnly,
                                     @RequestAttribute(name = "sm.operator", required = false) Operator me) {
        List<SessionRow> rows = new ArrayList<>();
        for (CbtSession s : store.findByUser(userId)) {
            if (safetyOnly && s.stage() != CbtStage.SAFETY_HOLD && !"review".equals(s.safetyStatus())) continue;
            if (stage != null && !stage.isBlank() && !s.stage().name().equalsIgnoreCase(stage)) continue;
            // 列表只回答"有哪些会话"；"他说了什么"要另外一道门（见 session()）
            rows.add(SessionRow.of(s, canReadContent(me, s)));
        }
        return rows;
    }

    @GetMapping("/sessions/{id}")
    @RequirePerm(Operator.Perm.SESSIONS_READ)
    public SessionTrace session(@PathVariable String id,
                                @RequestAttribute(name = "sm.operator", required = false) Operator me) {
        CbtSession s = flow.require(id);
        if (!canReadContent(me, s)) {
            throw new ForbiddenException("这一轮没有安全标记：原文、轨迹与导出只对管理员开放；"
                    + "进入安全暂停或待复核的会话，审核可以查看");
        }
        return new SessionTrace(
                CbtController.SessionView.of(s),
                s.history(),
                s.trace(),
                branches(s));
    }

    /**
     * 待复核队列：命中风险词但被判为他人/引用语境的会话。
     *
     * "放行"不是终点——这里存在的意义是让某个人真的能看到它。
     * 没有这个出口，规则里的 context_guard 只是写了一个没人读的字符串。
     */
    public record SafetyReviewRow(
            String sessionId,
            String userId,
            CbtStage stage,
            String safetyStatus,
            String safetyReason,
            String targetProblem,
            int turns,
            Instant updatedAt,
            String contactName,
            String contactPhone,
            Instant safetyAckAt,
            Integer moodLast
    ) {
    }

    @GetMapping("/safety/review")
    @RequirePerm(Operator.Perm.CONTENT_REVIEW)
    public List<SafetyReviewRow> safetyReview() {
        List<SafetyReviewRow> rows = new ArrayList<>();
        for (CbtSession s : store.findByUser(null)) {
            boolean review = "review".equals(s.safetyStatus());
            boolean watch = "watch".equals(s.safetyStatus());
            boolean hold = s.stage() == CbtStage.SAFETY_HOLD;
            if (!review && !hold && !watch) continue;
            Integer moodLast = s.moodPoints().isEmpty()
                    ? null
                    : s.moodPoints().get(s.moodPoints().size() - 1).score();
            rows.add(new SafetyReviewRow(s.id(), s.userId(), s.stage(), s.safetyStatus(),
                    s.safetyReason(), s.targetProblem(), s.trace().size(), s.updatedAt(),
                    s.contactName(), s.contactPhone(), s.safetyAckAt(), moodLast));
        }
        rows.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return rows;
    }

    /**
     * 标记"这条危机已经有人处理过"。
     * 没有任何自动通知——这一步的意义是留痕：谁在什么时候看过，之后复查有据可依。
     */
    @PostMapping("/sessions/{id}/ack")
    @RequirePerm(Operator.Perm.CONTENT_REVIEW)
    public Map<String, Object> ack(@PathVariable String id,
                                   @RequestBody(required = false) Map<String, String> body) {
        CbtSession s = flow.require(id);
        String by = body == null ? null : body.get("by");
        s.ackSafety(by == null || by.isBlank() ? "operator" : by);
        store.save(s);
        return Map.of("sessionId", s.id(), "ackedAt", s.safetyAckAt(), "by", s.safetyAckBy());
    }

    /* ---------------- 模型：延迟、估算 token、失败 ---------------- */

    public record AiCallView(String kind, String model, long ms, int estTokens,
                             String outcome, Instant at) {
    }

    public record AiSummaryView(int calls, int errors, long p50ms, long p95ms, long avgMs, long estTokens) {
    }

    public record AiView(String provider, String chatModel, String embeddingModel, int dimension,
                         boolean stub, AiSummaryView summary, java.util.List<AiCallView> recent,
                         String costNote) {
    }

    /**
     * 模型层仪表盘。延迟是实测；token 是估算（字符数 / 1.7），成本只在本地模型意义下为 0。
     *
     * 为什么不做"精确计费"：适配器拿到的是文本，不是上游账单；硬凑一个数会比"估算"更误导。
     * 接真实 API 时应当读响应里的 usage，或直接对接上游账单。
     */
    @GetMapping("/ai")
    public AiView ai() {
        ModelCallLog.Summary s = modelLog.summary();
        java.util.List<AiCallView> recent = modelLog.recent(80).stream()
                .map(c -> new AiCallView(c.kind(), c.model(), c.ms(), c.estTokens(), c.outcome(), c.at()))
                .toList();
        int dimension = embeddingPort.dimension();
        if (dimension <= 0) dimension = aiProperties.getEmbeddingDimension();
        String costNote = "本地 Ollama 按 0 元计；token 为估算（字符数 / 1.7），不是上游账单。";
        AiRuntimeConfig ai = aiConfigStore.current();
        return new AiView(ai.mode(), ai.chatModel(),
                aiProperties.getEmbeddingModel() == null || aiProperties.getEmbeddingModel().isBlank()
                        ? "hash-stub" : aiProperties.getEmbeddingModel(),
                dimension, AiRuntimeConfig.STUB.equals(ai.mode()), new AiSummaryView(
                s.calls(), s.errors(), s.p50ms(), s.p95ms(), s.avgMs(), s.estTokens()), recent, costNote);
    }

    /* ---------------- 临床审核记录：谁看过哪一版 ---------------- */

    public record ReviewRow(String kind, String target, String label, int currentVersion, int items,
                            String state, ReviewStore.Review review, int historyCount,
                            List<String> changes) {
    }

    public record ReviewsView(String path, List<ReviewRow> rows, int pending, int pendingSessions) {
    }

    public record ReviewSaveRequest(String kind, String target, String status, String reviewer,
                                    String note, List<String> checks, Boolean reported) {
    }

    /** 通过前必须勾完的清单键；与页面 review.js 里的 KIND_CHECKS 保持一致。 */
    private static final Map<String, List<String>> REQUIRED_CHECKS = Map.of(
            "safety", List.of("self_harm", "referral", "diagnosis"),
            "lexicon", List.of("lexicon", "diagnosis"),
            "flow", List.of("self_harm", "referral", "diagnosis", "distortion", "mood"),
            "policy", List.of("guard", "diagnosis", "referral"),
            "limits", List.of("limits", "mood")
    );

    /** 会话心理复核列表：只列已经触发安全关注/暂停的会话，不是全部会话。 */
    public record SessionReviewRow(String sessionId, String userId, String stage, String safetyStatus,
                                   String safetyReason, String targetProblem, int turns, Instant updatedAt,
                                   Integer moodLast, String contactName, String contactPhone,
                                   Instant safetyAckAt, ReviewStore.Review review, int historyCount) {
    }

    /**
     * 审核状态：当前版本 + 最近一次签字 + 与上次审核版本的差异。
     * 未审核/版本已变时状态会显式返回，页面不隐藏这件事。
     */
    @GetMapping("/reviews")
    public ReviewsView reviews() {
        List<ReviewRow> rows = new ArrayList<>();
        SafetyRules safety = safetyRules.get();
        rows.add(reviewRow("safety", "", "安全规则", safety.version(),
                safety.rules().size() + (safety.cases() == null ? 0 : safety.cases().size())));
        LexiconStore.View lex = lexiconStore.view();
        rows.add(reviewRow("lexicon", "", "敏感词库", lex.version(), lex.count()));
        FlowParamsStore.CanvasMeta canvas = flowParams.activeCanvas();
        FlowDefinition flow = flowParams.published(canvas.id());
        rows.add(reviewRow("flow", canvas.id(), "流程画布与系统话术", flow.version(),
                flow.stages().size() + flow.messages().size()));
        OutputPolicy policy = policyStore.get();
        rows.add(reviewRow("policy", "", "输出策略", policy.version(), policy.profiles().size()));
        RuntimeLimits limitsDef = limits.get();
        rows.add(reviewRow("limits", "", "运行参数", limitsDef.version(), 3));
        int pending = (int) rows.stream().filter(r -> !"approved".equals(r.state())).count();
        return new ReviewsView(reviews.path().toString(), rows, pending, pendingSessionCount());
    }

    @GetMapping("/reviews/sessions")
    public List<SessionReviewRow> sessionReviews() {
        List<SessionReviewRow> out = new ArrayList<>();
        for (CbtSession s : flaggedSessions()) {
            out.add(new SessionReviewRow(s.id(), s.userId(), s.stage().name(), s.safetyStatus(),
                    s.safetyReason(), s.targetProblem(), s.trace().size(), s.updatedAt(),
                    lastMood(s), s.contactName(), s.contactPhone(), s.safetyAckAt(),
                    reviews.find("session", s.id()), reviews.count("session", s.id())));
        }
        return out;
    }

    /** 全部签字历史，供审核页导出；新→旧。 */
    @GetMapping("/reviews/history")
    public List<ReviewStore.Review> reviewHistory() {
        return reviews.historyAll();
    }

    private ReviewRow reviewRow(String kind, String target, String label, int version, int items) {
        ReviewStore.Review review = reviews.find(kind, target);
        String state;
        if (review == null) {
            state = "unreviewed";
        } else if (review.version() != version) {
            state = "stale";
        } else {
            state = review.status();
        }
        List<String> changes = review == null || review.version() == version
                ? List.of() : diff(kind, target, review.version(), version);
        return new ReviewRow(kind, target, label, version, items, state, review,
                reviews.count(kind, target), changes);
    }

    /**
     * 登记一次审核。版本号由服务端取当前生效版本；组织模式下署名来自登录身份，
     * 请求体里的 reviewer 只在没有组织域时兜底。
     */
    @PutMapping("/reviews")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public ReviewsView saveReview(@RequestBody ReviewSaveRequest request,
                                  @RequestAttribute(name = "sm.operator", required = false) Operator operator,
                                  @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        if (request == null) throw new IllegalArgumentException("审核内容不能为空");
        String kind = request.kind() == null ? "" : request.kind();
        String target = request.target() == null ? "" : request.target().trim();
        int version;
        switch (kind) {
            case "safety" -> {
                version = safetyRules.get().version();
                target = "";
            }
            case "lexicon" -> {
                version = lexiconStore.view().version();
                target = "";
            }
            case "policy" -> {
                version = policyStore.get().version();
                target = "";
            }
            case "limits" -> {
                version = limits.get().version();
                target = "";
            }
            case "flow" -> {
                FlowParamsStore.CanvasMeta canvas = flowParams.activeCanvas();
                target = canvas.id();
                version = flowParams.published(target).version();
            }
            case "session" -> version = flow.require(target).trace().size();
            default -> throw new IllegalArgumentException(
                    "kind 只能是 safety / lexicon / flow / policy / limits / session");
        }
        if (orgEnabled && operator == null) {
            throw new IllegalArgumentException("组织模式下的审核署名必须来自登录身份，请先登录");
        }
        // "通过"不是一句口头结论：先确认过本对象的清单才允许签。
        if ("approved".equalsIgnoreCase(request.status())) {
            List<String> got = request.checks() == null ? List.of() : request.checks();
            List<String> missing = REQUIRED_CHECKS.getOrDefault(kind, List.of()).stream()
                    .filter(k -> !got.contains(k)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("通过前请先勾完审核清单，缺少：" + String.join(" / ", missing));
            }
        }
        String reviewer = operator == null ? firstNonBlank(request.reviewer(), actor) : operator.display();
        String operatorId = operator == null ? "" : operator.id();
        reviews.save(kind, target, version, request.status(), reviewer, operatorId,
                request.note(), request.checks(), Boolean.TRUE.equals(request.reported()));
        return reviews();
    }

    private int pendingSessionCount() {
        int pending = 0;
        for (CbtSession s : flaggedSessions()) {
            ReviewStore.Review r = reviews.find("session", s.id());
            if (r == null || r.version() != s.trace().size()) pending++;
        }
        return pending;
    }

    private List<CbtSession> flaggedSessions() {
        List<CbtSession> out = new ArrayList<>();
        for (CbtSession s : store.findByUser(null)) {
            boolean review = "review".equals(s.safetyStatus());
            boolean watch = "watch".equals(s.safetyStatus());
            boolean hold = s.stage() == CbtStage.SAFETY_HOLD;
            if (review || hold || watch) out.add(s);
        }
        out.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return out;
    }

    private static Integer lastMood(CbtSession s) {
        return s.moodPoints().isEmpty() ? null : s.moodPoints().get(s.moodPoints().size() - 1).score();
    }

    /* ---------------- 版本差异（只做摘要，不返回整份配置） ---------------- */

    private List<String> diff(String kind, String target, int fromVersion, int toVersion) {
        try {
            return switch (kind) {
                case "safety" -> diffSafety(fromVersion);
                case "lexicon" -> diffLexicon(fromVersion);
                case "flow" -> diffFlow(target, fromVersion);
                case "policy" -> diffPolicy(fromVersion);
                case "limits" -> diffLimits(fromVersion);
                default -> List.of();
            };
        } catch (RuntimeException e) {
            return List.of("差异读取失败：" + e.getMessage());
        }
    }

    private List<String> diffSafety(int fromVersion) {
        SafetyRules old = safetyRules.snapshot(fromVersion);
        if (old == null) return missingSnapshot("safety", fromVersion);
        SafetyRules cur = safetyRules.get();
        Map<String, SafetyRule> before = new LinkedHashMap<>();
        for (SafetyRule r : old.rules()) before.put(r.id(), r);
        Map<String, SafetyRule> after = new LinkedHashMap<>();
        for (SafetyRule r : cur.rules()) after.put(r.id(), r);
        List<String> out = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String id : unionKeys(before, after)) {
            SafetyRule a = before.get(id);
            SafetyRule b = after.get(id);
            if (a == null) added.add(id);
            else if (b == null) removed.add(id);
            else if (!Objects.equals(a.pattern(), b.pattern())
                    || !Objects.equals(a.level(), b.level())
                    || a.on() != b.on()
                    || !Objects.equals(a.note(), b.note())) changed.add(id);
        }
        if (!added.isEmpty()) out.add("新增规则：" + joinShort(added));
        if (!removed.isEmpty()) out.add("删除规则：" + joinShort(removed));
        if (!changed.isEmpty()) out.add("修改规则：" + joinShort(changed));
        List<SafetyCase> oc = old.cases() == null ? List.of() : old.cases();
        List<SafetyCase> cc = cur.cases() == null ? List.of() : cur.cases();
        Set<String> ocText = new java.util.LinkedHashSet<>(oc.stream().map(SafetyCase::text).toList());
        Set<String> ccText = new java.util.LinkedHashSet<>(cc.stream().map(SafetyCase::text).toList());
        int caseAdded = (int) ccText.stream().filter(t -> !ocText.contains(t)).count();
        int caseRemoved = (int) ocText.stream().filter(t -> !ccText.contains(t)).count();
        if (caseAdded > 0) out.add("新增用例 " + caseAdded + " 条");
        if (caseRemoved > 0) out.add("删除用例 " + caseRemoved + " 条");
        if (out.isEmpty()) out.add("规则内容与已审核版本一致（只有版本号变化）");
        return out;
    }

    private List<String> diffLexicon(int fromVersion) {
        LexiconStore.Snapshot old = lexiconStore.snapshot(fromVersion);
        if (old == null) return missingSnapshot("lexicon", fromVersion);
        Set<String> before = new java.util.LinkedHashSet<>();
        for (LexiconStore.Row r : old.entries()) before.add(rowKey(r));
        Set<String> after = new java.util.LinkedHashSet<>();
        for (LexiconStore.Row r : lexiconStore.view().entries()) after.add(rowKey(r));
        List<String> added = after.stream().filter(x -> !before.contains(x)).toList();
        List<String> removed = before.stream().filter(x -> !after.contains(x)).toList();
        List<String> out = new ArrayList<>();
        if (!added.isEmpty()) out.add("新增词条：" + joinShort(added));
        if (!removed.isEmpty()) out.add("删除词条：" + joinShort(removed));
        if (out.isEmpty()) out.add("词条集合与已审核版本一致（可能只有顺序变化）");
        return out;
    }

    private List<String> diffFlow(String canvasId, int fromVersion) {
        FlowDefinition old = flowParams.snapshot(canvasId, fromVersion);
        if (old == null) return missingSnapshot("flow", fromVersion);
        FlowDefinition cur = flowParams.published(canvasId);
        List<String> out = new ArrayList<>();
        if (!Objects.equals(old.start(), cur.start())) {
            out.add("起点：" + old.start() + " → " + cur.start());
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String id : unionKeys(old.stages(), cur.stages())) {
            StageSpec a = old.stages().get(id);
            StageSpec b = cur.stages().get(id);
            if (a == null) added.add(id);
            else if (b == null) removed.add(id);
            else if (!Objects.equals(a.text(), b.text())
                    || !Objects.equals(a.next(), b.next())
                    || !Objects.equals(a.model(), b.model())
                    || !Objects.equals(a.branches(), b.branches())) changed.add(id);
        }
        if (!added.isEmpty()) out.add("新增节点：" + joinShort(added));
        if (!removed.isEmpty()) out.add("删除节点：" + joinShort(removed));
        if (!changed.isEmpty()) out.add("修改节点：" + joinShort(changed));
        List<String> msgChanged = new ArrayList<>();
        Set<String> msgKeys = new java.util.LinkedHashSet<>(old.messages().keySet());
        msgKeys.addAll(cur.messages().keySet());
        for (String key : msgKeys) {
            if (!Objects.equals(old.messages().get(key), cur.messages().get(key))) msgChanged.add(key);
        }
        if (!msgChanged.isEmpty()) out.add("修改系统话术：" + joinShort(msgChanged));
        if (out.isEmpty()) out.add("画布内容与已审核版本一致");
        return out;
    }

    private List<String> diffPolicy(int fromVersion) {
        OutputPolicy old = policyStore.snapshot(fromVersion);
        if (old == null) return missingSnapshot("policy", fromVersion);
        OutputPolicy cur = policyStore.get();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String id : unionKeys(old.profiles(), cur.profiles())) {
            ProfileRule a = old.profiles().get(id);
            ProfileRule b = cur.profiles().get(id);
            if (a == null) added.add(id);
            else if (b == null) removed.add(id);
            else if (!a.equals(b)) changed.add(id);
        }
        List<String> out = new ArrayList<>();
        if (!added.isEmpty()) out.add("新增档位：" + joinShort(added));
        if (!removed.isEmpty()) out.add("删除档位：" + joinShort(removed));
        if (!changed.isEmpty()) out.add("修改档位：" + joinShort(changed));
        if (out.isEmpty()) out.add("输出策略与已审核版本一致");
        return out;
    }

    private List<String> diffLimits(int fromVersion) {
        RuntimeLimits old = limits.snapshot(fromVersion);
        if (old == null) return missingSnapshot("limits", fromVersion);
        RuntimeLimits cur = limits.get();
        List<String> out = new ArrayList<>();
        if (old.maxMessageChars() != cur.maxMessageChars()) {
            out.add("单条消息上限：" + old.maxMessageChars() + " → " + cur.maxMessageChars());
        }
        if (old.messagesPerMinute() != cur.messagesPerMinute()) {
            out.add("每分钟消息数：" + old.messagesPerMinute() + " → " + cur.messagesPerMinute());
        }
        if (old.newSessionsPerHour() != cur.newSessionsPerHour()) {
            out.add("每小时新会话：" + old.newSessionsPerHour() + " → " + cur.newSessionsPerHour());
        }
        if (out.isEmpty()) out.add("运行参数与已审核版本一致");
        return out;
    }

    private static List<String> missingSnapshot(String kind, int version) {
        return List.of("找不到 " + kind + " v" + version + " 的快照，只能按当前版本重新审");
    }

    private static String rowKey(LexiconStore.Row r) {
        return r.scope() + "|" + r.category() + "|" + r.action() + "|" + r.word();
    }

    private static Set<String> unionKeys(Map<String, ?> a, Map<String, ?> b) {
        Set<String> out = new java.util.LinkedHashSet<>(a.keySet());
        out.addAll(b.keySet());
        return out;
    }

    private static String joinShort(List<String> values) {
        List<String> limited = values.size() > 6 ? values.subList(0, 6) : values;
        return String.join("、", limited) + (values.size() > 6 ? " 等 " + values.size() + " 项" : "");
    }

    /* ---------------- 账号视图：只读，不代客注销 ---------------- */

    public record AccountRow(String id, String email, String displayName,
                             Instant createdAt, Instant lastLoginAt,
                             int sessions, Instant lastSessionAt, int attention) {
    }

    public record AccountsView(int total, int active7d, int withoutSessions, List<AccountRow> rows) {
    }

    /**
     * 账号进入运营视图，但仍然只读。
     *
     * 为什么不提供"后台注销某个账号"：注销需要本人口令，代客注销等于把"删除别人账号"的能力
     * 交给任何一个拿到内部令牌的人。运营需要的是"看见谁在用、哪一轮需要人看"，
     * 不是替用户做不可逆决定。确实要删，走用户自己的 /account/。
     *
     * attention 统计的是"这一账号名下需要人看一眼的会话"（安全暂停、关注、待复核），
     * 不是风险评级——数量多不等于这个人更危险。
     */
    @GetMapping("/accounts")
    public AccountsView accounts() {
        List<Account> all = accounts.findAll();
        Instant weekAgo = Instant.now().minus(java.time.Duration.ofDays(7));
        List<AccountRow> rows = new ArrayList<>();
        int active = 0;
        int empty = 0;
        for (Account a : all) {
            List<CbtSession> owned = store.findVisible(null, a.id());
            Instant lastSession = owned.stream()
                    .map(CbtSession::updatedAt)
                    .max(Instant::compareTo)
                    .orElse(null);
            int attention = (int) owned.stream()
                    .filter(s -> s.stage() == CbtStage.SAFETY_HOLD
                            || "hold".equals(s.safetyStatus())
                            || "watch".equals(s.safetyStatus())
                            || "review".equals(s.safetyStatus()))
                    .count();
            if ((a.lastLoginAt() != null && a.lastLoginAt().isAfter(weekAgo))
                    || (lastSession != null && lastSession.isAfter(weekAgo))) {
                active++;
            }
            if (owned.isEmpty()) empty++;
            rows.add(new AccountRow(a.id(), a.email(), a.displayName(),
                    a.createdAt(), a.lastLoginAt(), owned.size(), lastSession, attention));
        }
        return new AccountsView(all.size(), active, empty, rows);
    }

    /** 词库：看全表、改、试跑。v0.6.0 起可写——此前只能看，改词要发版。
     *
     * 保存的门槛在 {@link LexiconStore#save}：结构校验 + "至少一条 block、至少一条输入侧"，
     * 不通过就拒绝，旧词库继续生效。这是"运维能改，但改不坏"的同一套做法。
     */
    public record LexiconSaveRequest(List<LexiconStore.Row> entries, String updatedBy) {
    }

    public record LexiconTestRequest(String text, String side) {
    }

    @GetMapping("/lexicon")
    public LexiconStore.View lexicon() {
        return lexiconStore.view();
    }

    @PostMapping("/lexicon/validate")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public Map<String, Object> validateLexicon(@RequestBody LexiconSaveRequest request) {
        List<String> errors = lexiconStore.validate(request == null ? null : request.entries());
        return Map.of("ok", errors.isEmpty(), "errors", errors);
    }

    @PutMapping("/lexicon")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public LexiconStore.View saveLexicon(@RequestBody LexiconSaveRequest request,
                                         @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        if (request == null) throw new IllegalArgumentException("词库不能为空");
        return lexiconStore.save(request.entries(), firstNonBlank(request.updatedBy(), actor));
    }

    public record LexiconRollbackRequest(Integer version, String updatedBy) {
    }

    @PostMapping("/lexicon/rollback")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public LexiconStore.View rollbackLexicon(@RequestBody LexiconRollbackRequest request,
                                             @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        if (request == null || request.version() == null) {
            throw new IllegalArgumentException("需要 version");
        }
        return lexiconStore.rollback(request.version(), firstNonBlank(request.updatedBy(), actor));
    }

    @PostMapping("/lexicon/reset")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public LexiconStore.View resetLexicon(@RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return lexiconStore.reset(firstNonBlank(actor, "ops"));
    }

    /** 用**当前生效**的词库跑一次：输入侧与输出侧分开，因为两边的词本来就不该混。 */
    @PostMapping("/lexicon/test")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public Map<String, Object> testLexicon(@RequestBody(required = false) LexiconTestRequest request) {
        String text = request == null || request.text() == null ? "" : request.text();
        boolean output = request != null && "out".equalsIgnoreCase(request.side());
        var hits = output
                ? com.socraticmind.common.guard.SensitiveLexicon.get().scanOutput(text)
                : com.socraticmind.common.guard.SensitiveLexicon.get().scanInput(text);
        boolean blocked = hits.stream()
                .anyMatch(h -> h.action() == com.socraticmind.common.guard.SensitiveLexicon.Action.BLOCK);
        return Map.of("side", output ? "out" : "in", "blocked", blocked, "hits", hits);
    }

    public record DemoRequest(String kind) {
    }

    /**
     * 生成一条演示会话。走的是真实流程与真实轨迹，只是数据本身是脚本化的——
     * 页面上会打「演示」标记，避免与真实用户数据混淆。
     */
    @PostMapping("/demo")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public Map<String, String> demo(@RequestBody(required = false) DemoRequest request) {
        String kind = request == null || request.kind() == null ? "flow" : request.kind();
        String sessionId = "safety".equalsIgnoreCase(kind) ? safetyDemo() : flowDemo();
        return Map.of("sessionId", sessionId, "kind", kind);
    }

    private String flowDemo() {
        TurnResult started = flow.start("demo");
        String id = started.sessionId();
        List<String> lines = List.of(
                "下周组会要汇报，一想到就手心出汗",
                "我肯定会搞砸",
                "前三次有两次其实还行，组长只提了数据口径",
                "提前写下三条具体预测，会后逐条核对",
                "汇报前把三条预测写在便签上",
                "写了，汇报比想象中顺，大家照常问口径");
        for (String line : lines) {
            CbtStage stage = flow.require(id).stage();
            if (stage == CbtStage.SESSION_SUMMARY || stage.isTerminal()) break;
            TurnResult turn = flow.handle(id, line);
            if (turn.awaitingConfirmation()) {
                flow.confirm(id, null);
            }
        }
        return id;
    }

    private String safetyDemo() {
        TurnResult started = flow.start("demo-safety");
        String id = started.sessionId();
        flow.handle(id, "最近总是觉得自己做什么都不行");
        flow.handle(id, "我真的不想活了，想过结束生命");
        return id;
    }

    /* ---------------- 输出策略：运维改，改完即时生效 ---------------- */

    public record PolicyView(OutputPolicy policy, String path, boolean persisted, List<String> hints) {
    }

    public record PolicySaveRequest(OutputPolicy policy, String updatedBy) {
    }

    public record PolicyTestRequest(String profile, ProfileRule rule, String text, String source) {
    }

    public record PolicyTestResult(boolean ok, String profile, boolean guarded, double novelty,
                                   List<GuardViolation> violations) {
    }

    @GetMapping("/policy")
    public PolicyView policy() {
        List<String> hints = new ArrayList<>();
        if (!policyStore.exists()) {
            hints.add("策略还没落盘，当前用的是内置默认值；点一次保存就会写入 " + policyStore.path() + "。");
        }
        hints.add("规则在服务端生效，保存即热加载，不需要重启；文件可以直接进 git、review 与回滚。");
        return new PolicyView(policyStore.get(), policyStore.path().toString(), policyStore.exists(), hints);
    }

    @PutMapping("/policy")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public ResponseEntity<Object> savePolicy(@RequestBody PolicySaveRequest request) {
        if (request == null || request.policy() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "策略不能为空"));
        }
        try {
            OutputPolicy saved = policyStore.save(request.policy(), request.updatedBy());
            return ResponseEntity.ok(new PolicyView(saved, policyStore.path().toString(), true,
                    List.of("已保存并生效。")));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 试一下：用未保存的规则跑一次校验，让运维在点保存之前就看到结果。 */
    @PostMapping("/policy/test")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public PolicyTestResult testPolicy(@RequestBody PolicyTestRequest request) {
        ProfileRule rule = request.rule() != null ? request.rule() : policyStore.get().ruleOf(request.profile());
        boolean guarded = rule != null && rule.enabled();
        List<GuardViolation> violations = guarded
                ? GuardRules.inspect(request.text(), request.source(), rule)
                : List.of();
        return new PolicyTestResult(violations.isEmpty(), request.profile(), guarded,
                GuardRules.noveltyScore(request.text(), request.source()), violations);
    }

    @GetMapping("/guard/audit")
    public List<GuardAuditEntry> guardLog(@RequestParam(defaultValue = "50") int limit) {
        return guardAudit.recent(Math.max(1, Math.min(limit, 200)));
    }

    /* ---------------- 流程画布：多画布、草稿与启用 ---------------- */

    public record FlowView(FlowDefinition definition, String path, boolean persisted,
                           List<String> hints, Map<String, Object> rules,
                           String canvasId, String canvasName,
                           List<FlowParamsStore.CanvasMeta> canvases,
                           int publishedVersion, boolean draftUnpublished) {
    }

    public record FlowSaveRequest(String canvasId, FlowDefinition definition, String updatedBy) {
    }

    public record FlowPublishRequest(String canvasId, String updatedBy) {
    }

    public record CanvasCreateRequest(String name, String description, String copyFrom, String updatedBy) {
    }

    public record CanvasMetaRequest(String name, String description) {
    }

    public record FlowMessagesRequest(Map<String, String> messages, String updatedBy) {
    }

    @GetMapping("/flow")
    public FlowView flowDefinition(@RequestParam(required = false) String canvasId) {
        String id = canvasId == null || canvasId.isBlank() ? flowParams.activeId() : canvasId;
        FlowParamsStore.CanvasMeta meta = flowParams.canvas(id);
        if (meta == null) throw new IllegalArgumentException("没有这张画布：" + id);
        List<String> hints = new ArrayList<>();
        if (!flowParams.exists() && meta.publishedVersion() == 0) {
            hints.add("流程还没落盘，当前是从提示词资产推出的默认值；启用一次就会写入 " + flowParams.path() + "。");
        }
        if (meta.draftVersion() != meta.publishedVersion()) {
            hints.add("当前显示的是草稿 v" + meta.draftVersion()
                    + "，线上仍是 v" + meta.publishedVersion() + "；点「启用」后才会切换。");
        }
        hints.add("节点可以拖动摆位，去向只能从允许的集合里选；启用前会跑一遍结构校验。");
        return flowView(meta, hints);
    }

    @PutMapping("/flow")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public FlowView saveFlowDraft(@RequestBody FlowSaveRequest request) {
        if (request == null || request.definition() == null) {
            throw new IllegalArgumentException("流程定义不能为空");
        }
        String id = request.canvasId() == null || request.canvasId().isBlank()
                ? flowParams.activeId() : request.canvasId();
        flowParams.saveDraft(id, request.definition(), request.updatedBy());
        FlowParamsStore.CanvasMeta meta = flowParams.canvas(id);
        return flowView(meta, List.of("草稿已保存，线上流程没有变化；点「启用」后下一轮才会按它走。"));
    }

    @PutMapping("/flow/publish")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public FlowView publishFlow(@RequestBody FlowPublishRequest request) {
        String id = request == null || request.canvasId() == null || request.canvasId().isBlank()
                ? flowParams.activeId() : request.canvasId();
        flowParams.publish(id, request == null ? null : request.updatedBy());
        FlowParamsStore.CanvasMeta meta = flowParams.canvas(id);
        return flowView(meta, List.of("已启用，下一轮对话立即按这张画布走。"));
    }

    @PostMapping("/flow/canvases")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public FlowView createCanvas(@RequestBody CanvasCreateRequest request) {
        FlowParamsStore.CanvasMeta created = flowParams.createCanvas(
                request == null ? null : request.name(),
                request == null ? null : request.description(),
                request == null ? null : request.copyFrom(),
                request == null ? null : request.updatedBy());
        return flowView(created, List.of("画布已创建。它现在还是草稿，启用后才会切换线上。"));
    }

    @PutMapping("/flow/canvases/{id}")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public FlowView updateCanvas(@PathVariable String id, @RequestBody CanvasMetaRequest request) {
        FlowParamsStore.CanvasMeta updated = flowParams.updateCanvas(id,
                request == null ? null : request.name(),
                request == null ? null : request.description());
        return flowView(updated, List.of("画布名称与说明已更新。"));
    }

    @DeleteMapping("/flow/canvases/{id}")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public FlowView deleteCanvas(@PathVariable String id) {
        flowParams.deleteCanvas(id);
        FlowParamsStore.CanvasMeta active = flowParams.activeCanvas();
        return flowView(active, List.of("画布已删除。删掉的是草稿与发布文件，历史快照也一并清理。"));
    }

    @PostMapping("/flow/canvases/{id}/activate")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public FlowView activateCanvas(@PathVariable String id,
                                   @RequestBody(required = false) Map<String, String> body) {
        String by = body == null ? null : body.get("updatedBy");
        flowParams.publish(id, by);
        FlowParamsStore.CanvasMeta meta = flowParams.canvas(id);
        return flowView(meta, List.of("已切换到这张画布，下一轮对话立即生效。"));
    }

    /** 系统话术属于流程：保存后立即发布，保持设置页原来的“保存即生效”。 */
    @PutMapping("/flow/messages")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public FlowView saveMessages(@RequestBody FlowMessagesRequest request) {
        if (request == null) throw new IllegalArgumentException("话术不能为空");
        // 空表也算一次"保存"：不拦的话，误点一下就会把版本号推上去、留下一条什么都没改的记录，
        // 事后回滚时分不清"哪一版是真空的"。
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new IllegalArgumentException("话术表是空的：要改哪一句就只提交那一句，清空请走回滚");
        }
        flowParams.saveMessages(request.messages(), request.updatedBy());
        FlowParamsStore.CanvasMeta meta = flowParams.activeCanvas();
        return flowView(meta, List.of("系统话术已发布，下一轮立即生效。"));
    }

    private FlowView flowView(FlowParamsStore.CanvasMeta meta, List<String> hints) {
        FlowDefinition draft = flowParams.draft(meta.id());
        int publishedVersion = flowParams.published(meta.id()).version();
        boolean unpublished = draft.version() != publishedVersion;
        return new FlowView(draft, flowParams.pathOf(meta.id()).toString(), publishedVersion > 0,
                hints, flowRules(), meta.id(), meta.name(), flowParams.canvases(),
                publishedVersion, unpublished);
    }

    // ---------------------------------------------------------------- 安全规则

    /** 谁改的：控制台会带 X-Internal-Actor；不带就记 ops。 */
    private static final String ACTOR_HEADER = "X-Internal-Actor";

    public record SafetyRulesView(SafetyRules definition, String path, List<Integer> history, int cases) {
    }

    public record TextRequest(String text) {
    }

    public record RollbackRequest(String kind, Integer version, String actor) {
    }

    @GetMapping("/safety/rules")
    public SafetyRulesView safetyRules() {
        SafetyRules current = safetyRules.get();
        return new SafetyRulesView(current, safetyRules.path().toString(),
                safetyRules.history(), current.cases().size());
    }

    @PutMapping("/safety/rules")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public SafetyRules saveSafetyRules(@RequestBody SafetyRules incoming,
                                       @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        // 请求体里的 updatedBy 优先：HTTP 头只允许 Latin-1，中文操作者名放头里会被浏览器拒绝或变成乱码。
        return safetyRules.save(incoming, firstNonBlank(incoming.updatedBy(), actor));
    }

    /** 保存前先跑一遍：结构 + 固定用例回放，返回逐条错误，不落盘。 */
    @PostMapping("/safety/rules/validate")
    @RequirePerm(Operator.Perm.CONTENT_REVIEW)
    public Map<String, Object> validateSafetyRules(@RequestBody SafetyRules draft) {
        List<String> errors = SafetyRulesValidator.validateAll(draft);
        return Map.of("ok", errors.isEmpty(), "errors", errors);
    }

    @PostMapping("/safety/rules/test")
    @RequirePerm(Operator.Perm.CONTENT_REVIEW)
    public SafetyScreener.Screen testSafetyRules(@RequestBody(required = false) TextRequest request) {
        return SafetyScreener.screen(safetyRules.get(), request == null ? "" : request.text());
    }

    @GetMapping("/safety/cases")
    public List<SafetyCase> safetyCases() {
        return safetyRules.get().cases();
    }

    // ---------------------------------------------------------------- 运行参数

    public record LimitsView(RuntimeLimits limits, String path, List<Integer> history) {
    }

    @GetMapping("/limits")
    public LimitsView limits() {
        return new LimitsView(limits.get(), limits.path().toString(), limits.history());
    }

    @PutMapping("/limits")
    @RequirePerm(Operator.Perm.OPS_CONTENT)
    public RuntimeLimits saveLimits(@RequestBody RuntimeLimits incoming,
                                    @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return limits.save(incoming, firstNonBlank(incoming.updatedBy(), actor));
    }

    // ---------------------------------------------------------------- 历史与回滚

    @GetMapping("/config/history")
    public Map<String, List<ConfigVersion>> configHistory() {
        return Map.of(
                "flow", flowParams.historyEntries(),
                "safety", safetyRules.historyEntries(),
                "limits", limits.historyEntries());
    }

    /**
     * 取某一版的内容，同时带上当前生效版本——页面据此做对比高亮。
     * 只读：不改变任何状态；回滚仍要走 /config/rollback。
     */
    @GetMapping("/config/snapshot")
    public Map<String, Object> configSnapshot(@RequestParam String kind, @RequestParam int version) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("version", version);
        switch (kind) {
            case "flow" -> {
                out.put("value", require(flowParams.snapshot(version), kind, version));
                out.put("current", flowParams.get());
            }
            case "safety" -> {
                out.put("value", require(safetyRules.snapshot(version), kind, version));
                out.put("current", safetyRules.get());
            }
            case "limits" -> {
                out.put("value", require(limits.snapshot(version), kind, version));
                out.put("current", limits.get());
            }
            default -> throw new IllegalArgumentException("kind 只能是 flow / safety / limits");
        }
        return out;
    }

    private static Object require(Object snapshot, String kind, int version) {
        if (snapshot == null) {
            throw new IllegalArgumentException("没有这个历史版本：" + kind + " v" + version);
        }
        return snapshot;
    }

    /** 回滚不是覆盖：它把旧快照当成一次新的保存，所以版本号继续往前走。 */
    @PostMapping("/config/rollback")
    public Map<String, Object> rollback(@RequestBody RollbackRequest request,
                                        @RequestHeader(value = ACTOR_HEADER, required = false) String actor,
                                        @RequestAttribute(name = "sm.operator", required = false) Operator me) {
        if (request == null || request.kind() == null || request.version() == null) {
            throw new IllegalArgumentException("需要 kind 与 version");
        }
        // 回滚也是一种修改：流程/话术/规则/参数都归运营，回滚必须同一道门。
        // 只在保存那侧做权限、回滚那侧不做，等于给了一条绕过路径。
        Operator.Perm need = Operator.Perm.OPS_CONTENT;
        if (me != null && !me.allows(need)) {
            throw new ForbiddenException("回滚「" + request.kind() + "」需要「" + need.label() + "」职责");
        }
        int version = request.version();
        String who = firstNonBlank(request.actor(), actor);
        switch (request.kind()) {
            case "flow" -> flowParams.rollback(version, who);
            case "safety" -> safetyRules.rollback(version, who);
            case "limits" -> limits.rollback(version, who);
            default -> throw new IllegalArgumentException("kind 只能是 flow / safety / limits");
        }
        return Map.of("rolledBackTo", version, "history", configHistory());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        return second;
    }

    private static Map<String, Object> flowRules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("allowedNext", FlowValidator.ALLOWED_NEXT);
        rules.put("allowedModel", FlowValidator.ALLOWED_MODEL);
        rules.put("allowedBranch", FlowValidator.ALLOWED_BRANCH);
        rules.put("interventionTypes", FlowValidator.INTERVENTION_TYPES);
        rules.put("startable", FlowValidator.STARTABLE);
        rules.put("defaultStart", FlowDefinition.DEFAULT_START);
        rules.put("placeholders", FlowValidator.PLACEHOLDERS);
        rules.put("messageKeys", com.socraticmind.agent.cbt.flow.FlowMessages.PLACEHOLDERS);
        rules.put("predicates", ConditionWhen.ALL.stream()
                .map(w -> Map.of("when", w, "label", ConditionWhen.label(w)))
                .toList());
        rules.put("optionalNodes", FlowValidator.OPTIONAL.stream()
                .map(id -> {
                    StageSpec spec = FlowValidator.blank(id, 0, 0);
                    return Map.of("id", id, "title", spec.title(), "text", spec.text(),
                            "branches", spec.branches());
                })
                .toList());
        return rules;
    }

    private static List<Branch> branches(CbtSession s) {
        String taken = s.interventionType();
        return List.of(
                new Branch("behavioral_experiment", "行为实验", "behavioral_experiment".equals(taken)),
                new Branch("cognitive_restructuring", "认知重构", "cognitive_restructuring".equals(taken)),
                new Branch("self_monitoring", "自我观察", "self_monitoring".equals(taken)),
                new Branch("professional_support", "专业支持（未实现）", false));
    }
}
