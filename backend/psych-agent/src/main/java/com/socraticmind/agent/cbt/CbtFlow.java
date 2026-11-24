package com.socraticmind.agent.cbt;

import com.socraticmind.agent.cbt.flow.BranchRule;
import com.socraticmind.agent.cbt.flow.ConditionWhen;
import com.socraticmind.agent.cbt.flow.FlowMessages;
import com.socraticmind.agent.cbt.flow.FlowParamsStore;
import com.socraticmind.agent.cbt.flow.StageSpec;
import com.socraticmind.agent.cbt.safety.SafetyScreener;
import com.socraticmind.common.guard.ModelOutputRejectedException;
import com.socraticmind.common.guard.PolicyProfiles;
import com.socraticmind.common.guard.SensitiveLexicon;
import com.socraticmind.common.port.ChatPort;
import com.socraticmind.common.support.Ids;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CbtTurnEvent;
import com.socraticmind.session.CrisisCard;
import com.socraticmind.session.MoodPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CbtGraph 的流程实现。
 *
 * 与 KnowledgeFlow 的关键差别：这里**有中断**。走到 ACTION_CONFIRM 时 awaitingUser=true，
 * 只能用 /confirm 推进，普通消息推不动——这就是 docs/01-方案/05 §5.3 第 6 节点的落地形态。
 *
 * 第一版没有用框架 Graph 的 checkpoint：状态就在 CbtSession 里，进程内可恢复；
 * 换成真正的持久化 Checkpoint 时，CbtSession 作为业务表继续保留（§6.3 的两层分法）。
 *
 * 每一轮结束都写一条 {@link CbtTurnEvent}：只存当前字段的话，
 * "第几轮为什么走了这一步""这句总结是模型写的还是模板兜底"事后都无从复核。
 */
@Service
public class CbtFlow {

    private static final Logger log = LoggerFactory.getLogger(CbtFlow.class);

    /**
     * 共享前言：从 prompts/cbt/counselor.md 读入，拼在每处模型调用的最前面。
     * 之前它只是两条分散的任务说明，缺一段"不许做什么"的统一约束；
     * 现在改边界只改一个文件，两处调用同时生效。
     */
    private static final String COUNSELOR = loadCounselor();

    private static final String BALANCE_TASK = """
            你在协助一个人做 CBT 的认知检验。
            给定"原想法"和"新信息"，写一句更接近事实的说法，不超过 40 个字。
            要求：保留原想法的关切，但不要下结论式断言；不要安慰、不要说教、不要用"你应该"；
            不要添加给定信息里没有的内容；只输出这句话本身，不要引号或解释。
            """;

    private static final String SUMMARY_TASK = """
            你是一个 CBT 练习的记录整理者。
            只根据用户提供的字段写一段不超过 120 字的中文总结：
            陈述事实（情境、当时的想法、后来看到的信息、行动），不评价、不建议、不诊断。
            不要使用"你应该""你必须"这类句式。不要添加字段里没有的内容。
            """;

    private static String loadCounselor() {
        try (java.io.InputStream in = CbtFlow.class.getResourceAsStream("/prompts/cbt/counselor.md")) {
            if (in == null) throw new IllegalStateException("缺少系统提示词：/prompts/cbt/counselor.md");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取系统提示词失败", e);
        }
    }

    private static String system(String task) {
        return COUNSELOR + "\n\n---\n\n" + task;
    }

    private final CbtSessionStore store;
    private final ChatPort chat;
    private final SafetyScreener screener;
    private final FlowParamsStore flow;

    /**
     * 按会话分段的锁：同一会话的"读状态 → 改状态 → 整轮落库"必须串行。
     * 不加锁时，两个并发的消息请求会各自读到同一个 stage，然后先后覆盖，
     * 结果是丢一条消息、或者同一步推进两次。分段而不是每会话一把锁，
     * 是为了避免锁表随着会话数无限增长。
     */
    private static final int LOCK_STRIPES = 64;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public CbtFlow(CbtSessionStore store, ChatPort chat, SafetyScreener screener, FlowParamsStore flow) {
        this.store = store;
        this.chat = chat;
        this.screener = screener;
        this.flow = flow;
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new ReentrantLock();
    }

    private TurnResult locked(String sessionId, java.util.function.Supplier<TurnResult> work) {
        int idx = Math.floorMod(sessionId == null ? 0 : sessionId.hashCode(), LOCK_STRIPES);
        ReentrantLock lock = locks[idx];
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    public TurnResult start(String userId) {
        long t0 = System.nanoTime();
        String id = Ids.of("cbt");
        CbtSession session = new CbtSession(id, "thread_" + id, userId == null ? "anonymous" : userId);
        String question = ask(CbtStage.PROBLEM_IDENTIFICATION, Map.of());
        session.setStage(CbtStage.PROBLEM_IDENTIFICATION);
        session.setPendingQuestion(question);
        session.addMessage(CbtMessage.assistant(CbtStage.PROBLEM_IDENTIFICATION, question));
        return finish(session, null, CbtTurnEvent.START, TurnResult.AWAITING_USER, question,
                null, CbtTurnEvent.NONE, t0);
    }

    public TurnResult handle(String sessionId, String message) {
        return locked(sessionId, () -> handleLocked(sessionId, message));
    }

    private TurnResult handleLocked(String sessionId, String message) {
        long t0 = System.nanoTime();
        CbtSession session = require(sessionId);
        CbtStage from = session.stage();

        if (from.isTerminal()) {
            boolean hold = from == CbtStage.SAFETY_HOLD;
            String reply = hold
                    ? ask(CbtStage.SAFETY_HOLD, Map.of())
                    : msg(FlowMessages.STAGE_ENDED);
            return finish(session, from, CbtTurnEvent.BLOCKED,
                    hold ? TurnResult.SAFETY_HOLD : TurnResult.ENDED,
                    reply, null, CbtTurnEvent.NONE, t0);
        }

        // 安全分流每轮都跑，不是只跑第一轮（docs/02-设计/CBT状态机.md §3.4）
        SafetyScreener.Screen screen = screener.screen(message);
        if (screen.hold()) {
            session.setSafetyStatus("hold");
            session.setSafetyReason(screen.reason());
            session.setStage(CbtStage.SAFETY_HOLD);
            session.setAwaitingUser(false);
            String reply = ask(CbtStage.SAFETY_HOLD, Map.of());
            session.addMessage(CbtMessage.user(CbtStage.SAFETY_HOLD, message));
            session.addMessage(CbtMessage.assistant(CbtStage.SAFETY_HOLD, reply));
            log.warn("安全分流触发 session={} rule={}", session.id(), screen.rule());
            return finish(session, from, CbtTurnEvent.SAFETY_HOLD, TurnResult.SAFETY_HOLD,
                    reply, screen.rule(), CbtTurnEvent.NONE, t0);
        }

        // 命中风险词但语境指向他人/引用：不暂停，但必须留痕并进入人工复核队列。
        // 旧实现只写一个 context_guard 字符串，没有任何人能看到它——"放行"等于消失。
        String safetyRule = null;
        if (screen.review()) {
            session.setSafetyStatus("review");
            session.setSafetyReason(screen.reason());
            safetyRule = screen.rule();
            log.info("安全分流转人工复核 session={} rule={} reason={}",
                    session.id(), screen.rule(), screen.reason());
        }

        // 敏感词：自伤风险已经由上面的安全门处理完，这里只剩"拉客、脏话、违法"。
        // BLOCK 整句拦下且不推进——流程不动，用户换句话就能继续；
        // REVIEW 放行但打上复核标记，因为它可能是求助语境下的正常叙述。
        List<SensitiveLexicon.Hit> lexiconHits = SensitiveLexicon.get().scanInput(message);
        SensitiveLexicon.Hit blockedHit = lexiconHits.stream()
                .filter(h -> h.action() == SensitiveLexicon.Action.BLOCK)
                .findFirst().orElse(null);
        if (blockedHit != null) {
            session.addMessage(CbtMessage.user(from, message));
            String reply = msg(FlowMessages.CONTENT_BLOCKED);
            session.addMessage(CbtMessage.assistant(from, reply));
            log.info("敏感词拦截 session={} category={} word={}",
                    session.id(), blockedHit.category(), blockedHit.word());
            return finish(session, from, CbtTurnEvent.BLOCKED, TurnResult.CONTENT_BLOCKED,
                    reply, null, CbtTurnEvent.NONE, "lexicon:" + blockedHit.category(), t0);
        }
        String lexiconRule = lexiconHits.stream()
                .filter(h -> h.action() == SensitiveLexicon.Action.REVIEW)
                .map(h -> "lexicon:" + h.category())
                .findFirst().orElse(null);
        if (lexiconRule != null) {
            if (session.safetyStatus() == null || "normal".equals(session.safetyStatus())) {
                session.setSafetyStatus("review");
                session.setSafetyReason("敏感词命中：" + lexiconRule);
            }
            if (safetyRule == null) safetyRule = lexiconRule;
        }

        if (session.awaitingUser()) {
            session.addMessage(CbtMessage.user(session.stage(), message));
            String reply = msg(FlowMessages.CONFIRM_REQUIRED) + "\n\n" + session.pendingQuestion();
            session.addMessage(CbtMessage.assistant(session.stage(), reply));
            return finish(session, from, CbtTurnEvent.BLOCKED, TurnResult.AWAITING_CONFIRMATION,
                    reply, safetyRule, CbtTurnEvent.NONE, t0);
        }

        session.addMessage(CbtMessage.user(from, message));
        // 只把用户自己写下的情绪词打成标签（见 EmotionTagger 的边界说明）。
        for (String tag : EmotionTagger.tag(message)) {
            if (!session.emotions().contains(tag)) session.emotions().add(tag);
        }

        String reply;
        String modelMode = CbtTurnEvent.NONE;
        String guardRule = null;
        switch (from) {
            case PROBLEM_IDENTIFICATION -> {
                session.setTargetProblem(message);
                session.setSituation(message);
                advance(session, from);
                reply = ask(session.stage(), Map.of());
            }
            case COGNITIVE_EXTRACTION -> {
                session.setAutomaticThought(message);
                // 偏差标签只用于"提示从哪个方向找不同情况"，不写进总结、不告诉用户"你有XX思维"。
                session.addDistortions(DistortionTagger.tag(message));
                advance(session, from);
                reply = ask(session.stage(), Map.of("thought", message));
            }
            case COGNITIVE_REVIEW -> {
                session.evidenceAgainst().add(message);
                // 候选想法必须是一句"改写后的判断"，不能直接拿证据原句顶替——
                // 否则确认环节记下的是"事实"，不是"用户认可的新说法"。
                Gen balanced = balance(session, message);
                session.setCandidateThought(balanced.text());
                modelMode = balanced.mode();
                guardRule = balanced.rule();
                advance(session, from);
                reply = ask(session.stage(), Map.of());
            }
            case INTERVENTION_DECISION -> {
                session.setInterventionType(classifyIntervention(message));
                session.setActionPlan(message);
                advance(session, from);
                if (session.stage() == CbtStage.ACTION_CONFIRM) {
                    session.setAwaitingUser(true);
                }
                reply = ask(session.stage(), Map.of(
                        "thought", safe(session.candidateThought()),
                        "plan", message));
            }
            case ACTION_REVIEW -> {
                session.setActionResult(message);
                advance(session, from);
                Gen summary = summarize(session);
                session.setSummary(summary.text());
                modelMode = summary.mode();
                guardRule = summary.rule();
                reply = summary.text() + "\n\n这一轮到这里。记录已经存下了。";
            }
            case BEHAVIORAL_EXPERIMENT, PRACTICAL_PROBLEM_SOLVING -> {
                // 分支节点问的是"更具体的那一步"，用它的回答覆盖行动计划
                session.setActionPlan(message);
                advance(session, from);
                if (session.stage() == CbtStage.ACTION_CONFIRM) {
                    session.setAwaitingUser(true);
                }
                reply = ask(session.stage(), Map.of(
                        "thought", safe(session.candidateThought()),
                        "plan", message));
            }
            default -> reply = msg(FlowMessages.STAGE_ENDED);
        }

        session.setPendingQuestion(reply);
        session.addMessage(CbtMessage.assistant(session.stage(), reply));

        String status = session.awaitingUser()
                ? TurnResult.AWAITING_CONFIRMATION
                : session.stage() == CbtStage.SESSION_SUMMARY ? TurnResult.ENDED : TurnResult.AWAITING_USER;
        return finish(session, from, CbtTurnEvent.ANSWER, status, reply, safetyRule, modelMode, guardRule, t0);
    }

    /** 唯一能写入 userConfirmedThought 的入口。普通消息永远改不了这个字段。 */
    public TurnResult confirm(String sessionId, String thought) {
        return locked(sessionId, () -> confirmLocked(sessionId, thought));
    }

    private TurnResult confirmLocked(String sessionId, String thought) {
        long t0 = System.nanoTime();
        CbtSession session = require(sessionId);
        CbtStage from = session.stage();
        if (from != CbtStage.ACTION_CONFIRM || !session.awaitingUser()) {
            throw new IllegalStateException("当前不在待确认状态，无法确认");
        }
        String confirmed = (thought == null || thought.isBlank()) ? session.candidateThought() : thought;
        session.setUserConfirmedThought(confirmed);
        session.setAwaitingUser(false);
        advance(session, from);

        String question = ask(session.stage(), Map.of());
        String reply = msg(FlowMessages.CONFIRM_ACK, Map.of(
                "thought", confirmed,
                "plan", safe(session.actionPlan()))) + "\n\n" + question;
        session.setPendingQuestion(question);
        session.addMessage(CbtMessage.assistant(CbtStage.ACTION_REVIEW, reply));
        return finish(session, from, CbtTurnEvent.CONFIRM, TurnResult.AWAITING_USER, reply,
                null, CbtTurnEvent.NONE, t0);
    }

    public TurnResult end(String sessionId) {
        return locked(sessionId, () -> endLocked(sessionId));
    }

    private TurnResult endLocked(String sessionId) {
        long t0 = System.nanoTime();
        CbtSession session = require(sessionId);
        CbtStage from = session.stage();
        session.setStage(CbtStage.ENDED);
        session.setAwaitingUser(false);
        return finish(session, from, CbtTurnEvent.END, TurnResult.ENDED, msg(FlowMessages.END),
                null, CbtTurnEvent.NONE, t0);
    }

    public CbtSession require(String sessionId) {
        return store.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在：" + sessionId));
    }

    public List<CbtMessage> history(String sessionId) {
        return require(sessionId).history();
    }

    /** 取一句系统话术（非阶段提问），并替换占位符；配置缺失时用内置默认。 */
    private String msg(String key, Map<String, String> vars) {
        String text = flow.get().message(key);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return text;
    }

    private String msg(String key) {
        return msg(key, Map.of());
    }

    /** 取当前流程定义里这个阶段的话术，并把占位符替换成真实内容。 */
    private String ask(CbtStage stage, Map<String, String> vars) {
        String text = flow.get().textOf(stage);
        if (text == null || text.isBlank()) {
            // 流程配置漏了话术时，宁可给一句说明，也不要给用户一张空白纸片。
            log.error("阶段 {} 没有配置话术，已使用兜底文案；请检查流程画布", stage);
            text = "这一步的话术还没有配置好。你的记录已经存下了，可以先去「窗外」看看。";
        }
        for (Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return text;
    }

    /** 前往下一步：先看分支规则，再退回默认去向；判断节点会被连续求值，不等待用户输入。 */
    private void advance(CbtSession session, CbtStage from) {
        String target = resolve(session, flow.get().stage(from));
        Set<CbtStage> visited = new LinkedHashSet<>();
        visited.add(from);
        while (target != null && !target.isBlank()) {
            CbtStage stage;
            try {
                stage = CbtStage.valueOf(target);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("流程配置指向未知节点：" + target);
            }
            // 以前用 guard<4 兜底：成环时会静默停在 CONDITION，而 CONDITION 没有话术，
            // 用户看到的是一张空白纸片。现在宁可显式报错，也不要无声地卡住。
            if (!visited.add(stage)) {
                throw new IllegalStateException("流程配置存在环：" + visited + " → " + stage);
            }
            session.setStage(stage);
            if (stage != CbtStage.CONDITION) return;
            target = resolve(session, flow.get().stage(stage));
        }
    }

    private String resolve(CbtSession session, StageSpec spec) {
        if (spec == null) return null;
        for (BranchRule rule : spec.branches()) {
            if (rule == null || rule.when() == null) continue;
            if (test(session, rule.when())) return rule.to();
        }
        return spec.next();
    }

    private boolean test(CbtSession session, String when) {
        return switch (when) {
            case ConditionWhen.INTERVENTION_BEHAVIORAL -> "behavioral_experiment".equals(session.interventionType());
            case ConditionWhen.INTERVENTION_COGNITIVE -> "cognitive_restructuring".equals(session.interventionType());
            case ConditionWhen.INTERVENTION_OBSERVE -> "self_monitoring".equals(session.interventionType());
            case ConditionWhen.CONFIRMED -> session.userConfirmedThought() != null;
            case ConditionWhen.NOT_CONFIRMED -> session.userConfirmedThought() == null;
            case ConditionWhen.ELSE -> true;
            default -> false;
        };
    }

    /** 这个阶段的输出策略是否启用；关掉就只用模板，不调模型。 */
    private boolean modelEnabled(CbtStage stage) {
        return !StageSpec.MODEL_NONE.equals(flow.get().modelOf(stage));
    }

    /** 落事件 + 存会话 + 组装结果。三件事绑在一起，避免某条分支忘了写轨迹。 */
    private TurnResult finish(CbtSession session, CbtStage from, String action, String status,
                              String response, String safetyRule, String modelMode, long t0) {
        return finish(session, from, action, status, response, safetyRule, modelMode, null, t0);
    }

    private TurnResult finish(CbtSession session, CbtStage from, String action, String status,
                              String response, String safetyRule, String modelMode,
                              String guardRule, long t0) {
        session.addEvent(new CbtTurnEvent(
                session.trace().size() + 1,
                action,
                from,
                session.stage(),
                status,
                safetyRule,
                modelMode,
                guardRule,
                (System.nanoTime() - t0) / 1_000_000,
                Instant.now()));
        store.save(session);
        return result(session, status, response);
    }

    private TurnResult result(CbtSession session, String status, String response) {
        boolean hold = "hold".equals(session.safetyStatus());
        return new TurnResult(session.id(), session.stage(), session.stage().label(),
                session.stage().visibleIndex(), status, response,
                session.awaitingUser(), session.safetyStatus(), session.userConfirmedThought(),
                session.distortions(),
                session.moodPoints().stream().map(MoodPoint::score).toList(),
                hold ? CrisisCard.of(session, session.safetyReason()) : null);
    }

    /**
     * 干预类型判定：关键词表由画布维护，判定顺序固定（行为实验 → 认知重构 → 自我观察），
     * 避免"调整词表"把已有样本的归类悄悄换掉。
     */
    private String classifyIntervention(String plan) {
        if (plan == null) return "self_monitoring";
        Map<String, List<String>> table = flow.get().intervention();
        for (String type : List.of("behavioral_experiment", "cognitive_restructuring")) {
            for (String word : table.getOrDefault(type, List.of())) {
                if (!word.isBlank() && plan.contains(word)) return type;
            }
        }
        return "self_monitoring";
    }

    /** 生成结果 + 来源标识：调用方必须知道这句话是模型写的、模板兜底的，还是被策略挡下的。 */
    private record Gen(String text, String mode, String rule) {
    }

    /** 把"原想法 + 新证据"合成一句更接近事实的说法。模板兜底，模型可用时优先用模型。 */
    private Gen balance(CbtSession s, String newEvidence) {
        String thought = safe(s.automaticThought());
        String template = msg(FlowMessages.BALANCE_TEMPLATE, Map.of(
                "thought", thought,
                "evidence", trim(newEvidence, 40)));
        if (!modelEnabled(CbtStage.COGNITIVE_REVIEW)) {
            return new Gen(template, CbtTurnEvent.TEMPLATE, null);
        }
        try {
            String prompt = """
                    原想法：%s
                    新信息：%s
                    """.formatted(thought, newEvidence);
            String out = chat.chat(system(BALANCE_TASK), prompt, PolicyProfiles.CBT_BALANCE);
            if (out != null) {
                String cleaned = out.strip().replaceAll("^[\"“「]|[\"”」]$", "").strip();
                if (cleaned.length() >= 8 && cleaned.length() <= 80 && !cleaned.contains("资料不足")) {
                    return new Gen(cleaned, CbtTurnEvent.MODEL, null);
                }
            }
        } catch (ModelOutputRejectedException e) {
            log.warn("平衡想法未通过输出策略，退回模板：rule={} {}", e.rule(), e.getMessage());
            return new Gen(template, CbtTurnEvent.TEMPLATE, e.rule());
        } catch (RuntimeException e) {
            log.warn("平衡想法生成失败，退回模板：{}", e.getMessage());
        }
        return new Gen(template, CbtTurnEvent.TEMPLATE, null);
    }

    private static String trim(String v, int max) {
        if (v == null) return "";
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    /** 总结优先用模型，但模型输出不合格时退回模板——记录不能因为模型抽风而没有内容。 */
    private Gen summarize(CbtSession s) {
        String template = msg(FlowMessages.SUMMARY_TEMPLATE, Map.of(
                "situation", safe(s.situation()),
                "thought", safe(s.automaticThought()),
                "evidence", safe(s.candidateThought()),
                "plan", safe(s.actionPlan())));
        if (!modelEnabled(CbtStage.ACTION_REVIEW)) {
            return new Gen(template, CbtTurnEvent.TEMPLATE, null);
        }
        try {
            String prompt = """
                    情境：%s
                    当时的想法：%s
                    后来看到的信息：%s
                    行动：%s
                    """.formatted(safe(s.situation()), safe(s.automaticThought()),
                    safe(s.candidateThought()), safe(s.actionPlan()));
            String out = chat.chat(system(SUMMARY_TASK), prompt, PolicyProfiles.CBT_SUMMARY);
            if (out != null && out.strip().length() >= 20 && !out.contains("资料不足")) {
                return new Gen(out.strip(), CbtTurnEvent.MODEL, null);
            }
        } catch (ModelOutputRejectedException e) {
            log.warn("总结未通过输出策略，退回模板：rule={} {}", e.rule(), e.getMessage());
            return new Gen(template, CbtTurnEvent.TEMPLATE, e.rule());
        } catch (RuntimeException e) {
            log.warn("总结调用模型失败，退回模板：{}", e.getMessage());
        }
        return new Gen(template, CbtTurnEvent.TEMPLATE, null);
    }

    private static String safe(String v) {
        return v == null ? "（未填写）" : v;
    }
}
