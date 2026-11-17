package com.socraticmind.agent.cbt;

import com.socraticmind.agent.cbt.flow.BranchRule;
import com.socraticmind.agent.cbt.flow.ConditionWhen;
import com.socraticmind.agent.cbt.flow.FlowParamsStore;
import com.socraticmind.agent.cbt.flow.StageSpec;
import com.socraticmind.common.guard.ModelOutputRejectedException;
import com.socraticmind.common.guard.PolicyProfiles;
import com.socraticmind.common.port.ChatPort;
import com.socraticmind.common.support.Ids;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CbtTurnEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    private static final String BALANCE_SYSTEM = """
            你在协助一个人做 CBT 的认知检验。
            给定"原想法"和"新信息"，写一句更接近事实的说法，不超过 40 个字。
            要求：保留原想法的关切，但不要下结论式断言；不要安慰、不要说教、不要用"你应该"；
            不要添加给定信息里没有的内容；只输出这句话本身，不要引号或解释。
            """;

    private static final String SUMMARY_SYSTEM = """
            你是一个 CBT 练习的记录整理者。
            只根据用户提供的字段写一段不超过 120 字的中文总结：
            陈述事实（情境、当时的想法、后来看到的信息、行动），不评价、不建议、不诊断。
            不要使用"你应该""你必须"这类句式。不要添加字段里没有的内容。
            """;

    private final CbtSessionStore store;
    private final ChatPort chat;
    private final SafetyScreener screener;
    private final FlowParamsStore flow;

    public CbtFlow(CbtSessionStore store, ChatPort chat, SafetyScreener screener, FlowParamsStore flow) {
        this.store = store;
        this.chat = chat;
        this.screener = screener;
        this.flow = flow;
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
        long t0 = System.nanoTime();
        CbtSession session = require(sessionId);
        CbtStage from = session.stage();

        if (from.isTerminal()) {
            boolean hold = from == CbtStage.SAFETY_HOLD;
            String reply = hold
                    ? ask(CbtStage.SAFETY_HOLD, Map.of())
                    : "这一轮已经结束了。如果想继续，开一轮新的。";
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

        // 命中风险词但被第三人称语境放行：放行也要留痕，否则误报与漏判都无法复核。
        String safetyRule = screen.reason() == null ? null : "context_guard";

        if (session.awaitingUser()) {
            session.addMessage(CbtMessage.user(session.stage(), message));
            String reply = "这句话我先没写进记录——它需要你确认。\n\n" + session.pendingQuestion();
            session.addMessage(CbtMessage.assistant(session.stage(), reply));
            return finish(session, from, CbtTurnEvent.BLOCKED, TurnResult.AWAITING_CONFIRMATION,
                    reply, safetyRule, CbtTurnEvent.NONE, t0);
        }

        session.addMessage(CbtMessage.user(from, message));

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
            default -> reply = "这一轮已经结束了。如果想继续，开一轮新的。";
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
        String reply = "好，已经记下：\n· 你的说法：「" + confirmed + "」\n· 行动计划：「"
                + safe(session.actionPlan()) + "」\n\n" + question;
        session.setPendingQuestion(question);
        session.addMessage(CbtMessage.assistant(CbtStage.ACTION_REVIEW, reply));
        return finish(session, from, CbtTurnEvent.CONFIRM, TurnResult.AWAITING_USER, reply,
                null, CbtTurnEvent.NONE, t0);
    }

    public TurnResult end(String sessionId) {
        long t0 = System.nanoTime();
        CbtSession session = require(sessionId);
        CbtStage from = session.stage();
        session.setStage(CbtStage.ENDED);
        session.setAwaitingUser(false);
        return finish(session, from, CbtTurnEvent.END, TurnResult.ENDED, "这一轮结束了。",
                null, CbtTurnEvent.NONE, t0);
    }

    public CbtSession require(String sessionId) {
        return store.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在：" + sessionId));
    }

    public List<CbtMessage> history(String sessionId) {
        return require(sessionId).history();
    }

    /** 取当前流程定义里这个阶段的话术，并把占位符替换成真实内容。 */
    private String ask(CbtStage stage, Map<String, String> vars) {
        String text = flow.get().textOf(stage);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            text = text.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return text;
    }

    /** 前往下一步：先看分支规则，再退回默认去向；判断节点会被连续求值，不等待用户输入。 */
    private void advance(CbtSession session, CbtStage from) {
        String target = resolve(session, flow.get().stage(from));
        int guard = 0;
        while (target != null && !target.isBlank() && guard++ < 4) {
            CbtStage stage = CbtStage.valueOf(target);
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
        return new TurnResult(session.id(), session.stage(), session.stage().label(),
                session.stage().visibleIndex(), status, response,
                session.awaitingUser(), session.safetyStatus(), session.userConfirmedThought());
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
        String template = "「" + thought + "」是一个预测，不是事实。从现有信息看，另一种可能是："
                + trim(newEvidence, 40) + "。";
        if (!modelEnabled(CbtStage.COGNITIVE_REVIEW)) {
            return new Gen(template, CbtTurnEvent.TEMPLATE, null);
        }
        try {
            String prompt = """
                    原想法：%s
                    新信息：%s
                    """.formatted(thought, newEvidence);
            String out = chat.chat(BALANCE_SYSTEM, prompt, PolicyProfiles.CBT_BALANCE);
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
        String template = String.format(
                "这一轮：情境是「%s」，当时的想法是「%s」，你后来看到「%s」。行动计划：%s。",
                safe(s.situation()), safe(s.automaticThought()), safe(s.candidateThought()), safe(s.actionPlan()));
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
            String out = chat.chat(SUMMARY_SYSTEM, prompt, PolicyProfiles.CBT_SUMMARY);
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
