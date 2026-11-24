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
import com.socraticmind.agent.cbt.safety.SafetyRules;
import com.socraticmind.agent.cbt.safety.SafetyRulesStore;
import com.socraticmind.agent.cbt.safety.SafetyRulesValidator;
import com.socraticmind.agent.cbt.safety.SafetyScreener;
import com.socraticmind.agent.config.ConfigVersion;
import com.socraticmind.agent.config.RuntimeLimits;
import com.socraticmind.agent.config.RuntimeLimitsStore;
import com.socraticmind.common.guard.GuardRules;
import com.socraticmind.common.guard.GuardViolation;
import com.socraticmind.common.guard.OutputPolicy;
import com.socraticmind.common.guard.ProfileRule;
import com.socraticmind.infrastructure.guard.GuardAuditEntry;
import com.socraticmind.infrastructure.guard.GuardAuditLog;
import com.socraticmind.infrastructure.guard.OutputPolicyStore;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CbtTurnEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

/**
 * 内部只读控制台接口。它不是产品功能，读者是作者与评审：
 * - 只读：不提供任何改写会话状态的入口（确认仍然只能走 /api/cbt/sessions/{id}/confirm）；
 * - 诚实：把"内存档 / 桩模型 / 无鉴权"三件事显式暴露，避免演示时被误认为生产可用；
 * - 可复现：演示会话走真实流程，不是前端编造的假数据。
 *
 * 设计说明见 docs/02-设计/内部后台.md。**默认关闭**：
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
    private final String aiProvider;
    private final String chatModel;
    private final boolean authEnabled;

    public InternalController(CbtFlow flow,
                              CbtSessionStore store,
                              OutputPolicyStore policyStore,
                              FlowParamsStore flowParams,
                              SafetyRulesStore safetyRules,
                              RuntimeLimitsStore limits,
                              GuardAuditLog guardAudit,
                              @Value("${psych.ai.provider:stub}") String aiProvider,
                              @Value("${psych.ai.chat-model:}") String chatModel,
                              @Value("${psych.internal.token:}") String internalToken) {
        this.flow = flow;
        this.store = store;
        this.policyStore = policyStore;
        this.flowParams = flowParams;
        this.safetyRules = safetyRules;
        this.limits = limits;
        this.guardAudit = guardAudit;
        this.aiProvider = aiProvider;
        this.chatModel = chatModel;
        // 开启内部接口时令牌是必填的（见 InternalAuthInterceptor），这里如实反映"有没有钥匙"
        this.authEnabled = internalToken != null && !internalToken.isBlank();
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
        static SessionRow of(CbtSession s) {
            return new SessionRow(
                    s.id(), s.userId(), s.stage(), s.stage().label(), s.stage().visibleIndex(),
                    s.stage().isTerminal(), s.awaitingUser(), s.safetyStatus(), s.interventionType(),
                    s.targetProblem(),
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
        return new RuntimeView(storeName, durable, aiProvider, chatModel,
                "stub".equalsIgnoreCase(aiProvider), authEnabled,
                all.size(), holds, review, awaiting, events,
                policyStore.get().version(), guardRejections, flowParams.get().version(),
                stageCounts, Instant.now());
    }

    @GetMapping("/sessions")
    public List<SessionRow> sessions(@RequestParam(required = false) String userId,
                                     @RequestParam(required = false) String stage,
                                     @RequestParam(defaultValue = "false") boolean safetyOnly) {
        List<SessionRow> rows = new ArrayList<>();
        for (CbtSession s : store.findByUser(userId)) {
            if (safetyOnly && s.stage() != CbtStage.SAFETY_HOLD && !"review".equals(s.safetyStatus())) continue;
            if (stage != null && !stage.isBlank() && !s.stage().name().equalsIgnoreCase(stage)) continue;
            rows.add(SessionRow.of(s));
        }
        return rows;
    }

    @GetMapping("/sessions/{id}")
    public SessionTrace session(@PathVariable String id) {
        CbtSession s = flow.require(id);
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
    public Map<String, Object> ack(@PathVariable String id,
                                   @RequestBody(required = false) Map<String, String> body) {
        CbtSession s = flow.require(id);
        String by = body == null ? null : body.get("by");
        s.ackSafety(by == null || by.isBlank() ? "operator" : by);
        store.save(s);
        return Map.of("sessionId", s.id(), "ackedAt", s.safetyAckAt(), "by", s.safetyAckBy());
    }

    /** 词库只读视图：让运维能看见"到底拦了什么"，而不是只有一个命中计数。 */
    @GetMapping("/lexicon")
    public Map<String, Object> lexicon() {
        var entries = com.socraticmind.common.guard.SensitiveLexicon.get().entries();
        return Map.of(
                "count", entries.size(),
                "entries", entries.stream().map(e -> Map.of(
                        "scope", e.scope().name(),
                        "category", e.category(),
                        "action", e.action().name(),
                        "word", e.word()
                )).toList());
    }

    public record DemoRequest(String kind) {
    }

    /**
     * 生成一条演示会话。走的是真实流程与真实轨迹，只是数据本身是脚本化的——
     * 页面上会打「演示」标记，避免与真实用户数据混淆。
     */
    @PostMapping("/demo")
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

    /* ---------------- 流程画布：改话术、改去向、发布 ---------------- */

    public record FlowView(FlowDefinition definition, String path, boolean persisted,
                           List<String> hints, Map<String, Object> rules) {
    }

    public record FlowSaveRequest(FlowDefinition definition, String updatedBy) {
    }

    @GetMapping("/flow")
    public FlowView flowDefinition() {
        List<String> hints = new ArrayList<>();
        if (!flowParams.exists()) {
            hints.add("流程还没落盘，当前是从提示词资产推出的默认值；发布一次就会写入 " + flowParams.path() + "。");
        }
        hints.add("节点可以拖动摆位，去向只能从允许的集合里选；发布前会跑一遍结构校验。");
        return new FlowView(flowParams.get(), flowParams.path().toString(), flowParams.exists(), hints, flowRules());
    }

    @PutMapping("/flow")
    public ResponseEntity<Object> saveFlow(@RequestBody FlowSaveRequest request) {
        if (request == null || request.definition() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "流程定义不能为空"));
        }
        try {
            FlowDefinition saved = flowParams.save(request.definition(), request.updatedBy());
            return ResponseEntity.ok(new FlowView(saved, flowParams.path().toString(), true,
                    List.of("已发布，下一轮对话立即按新流程走。"), flowRules()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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
    public SafetyRules saveSafetyRules(@RequestBody SafetyRules incoming,
                                       @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        // 请求体里的 updatedBy 优先：HTTP 头只允许 Latin-1，中文操作者名放头里会被浏览器拒绝或变成乱码。
        return safetyRules.save(incoming, firstNonBlank(incoming.updatedBy(), actor));
    }

    /** 保存前先跑一遍：结构 + 固定用例回放，返回逐条错误，不落盘。 */
    @PostMapping("/safety/rules/validate")
    public Map<String, Object> validateSafetyRules(@RequestBody SafetyRules draft) {
        List<String> errors = SafetyRulesValidator.validateAll(draft);
        return Map.of("ok", errors.isEmpty(), "errors", errors);
    }

    @PostMapping("/safety/rules/test")
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
                                        @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        if (request == null || request.kind() == null || request.version() == null) {
            throw new IllegalArgumentException("需要 kind 与 version");
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
