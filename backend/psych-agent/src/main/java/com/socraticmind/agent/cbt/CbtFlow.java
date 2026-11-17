package com.socraticmind.agent.cbt;

import com.socraticmind.common.port.ChatPort;
import com.socraticmind.common.support.Ids;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final CbtScript script = new CbtScript();

    public CbtFlow(CbtSessionStore store, ChatPort chat, SafetyScreener screener) {
        this.store = store;
        this.chat = chat;
        this.screener = screener;
    }

    public TurnResult start(String userId) {
        String id = Ids.of("cbt");
        CbtSession session = new CbtSession(id, "thread_" + id, userId == null ? "anonymous" : userId);
        String question = script.question(CbtStage.PROBLEM_IDENTIFICATION);
        session.setStage(CbtStage.PROBLEM_IDENTIFICATION);
        session.setPendingQuestion(question);
        session.addMessage(CbtMessage.assistant(CbtStage.PROBLEM_IDENTIFICATION, question));
        store.save(session);
        return result(session, TurnResult.AWAITING_USER, question);
    }

    public TurnResult handle(String sessionId, String message) {
        CbtSession session = require(sessionId);

        if (session.stage().isTerminal()) {
            String reply = session.stage() == CbtStage.SAFETY_HOLD
                    ? script.question(CbtStage.SAFETY_HOLD)
                    : "这一轮已经结束了。如果想继续，开一轮新的。";
            return result(session, session.stage() == CbtStage.SAFETY_HOLD
                    ? TurnResult.SAFETY_HOLD : TurnResult.ENDED, reply);
        }

        // 安全分流每轮都跑，不是只跑第一轮（docs/02-设计/CBT状态机.md §3.4）
        SafetyScreener.Screen screen = screener.screen(message);
        if (screen.hold()) {
            session.setSafetyStatus("hold");
            session.setSafetyReason(screen.reason());
            session.setStage(CbtStage.SAFETY_HOLD);
            session.setAwaitingUser(false);
            session.addMessage(CbtMessage.user(session.stage(), message));
            String reply = script.question(CbtStage.SAFETY_HOLD);
            session.addMessage(CbtMessage.assistant(CbtStage.SAFETY_HOLD, reply));
            store.save(session);
            log.warn("安全分流触发 session={} rule={}", session.id(), screen.rule());
            return result(session, TurnResult.SAFETY_HOLD, reply);
        }

        if (session.awaitingUser()) {
            session.addMessage(CbtMessage.user(session.stage(), message));
            String reply = "这句话我先没写进记录——它需要你确认。\n\n" + session.pendingQuestion();
            session.addMessage(CbtMessage.assistant(session.stage(), reply));
            store.save(session);
            return result(session, TurnResult.AWAITING_CONFIRMATION, reply);
        }

        session.addMessage(CbtMessage.user(session.stage(), message));

        String reply;
        CbtStage previous = session.stage();
        switch (previous) {
            case PROBLEM_IDENTIFICATION -> {
                session.setTargetProblem(message);
                session.setSituation(message);
                session.setStage(CbtStage.COGNITIVE_EXTRACTION);
                reply = script.question(CbtStage.COGNITIVE_EXTRACTION);
            }
            case COGNITIVE_EXTRACTION -> {
                session.setAutomaticThought(message);
                session.setStage(CbtStage.COGNITIVE_REVIEW);
                reply = script.question(CbtStage.COGNITIVE_REVIEW, Map.of("thought", message));
            }
            case COGNITIVE_REVIEW -> {
                session.evidenceAgainst().add(message);
                // 候选想法必须是一句"改写后的判断"，不能直接拿证据原句顶替——
                // 否则确认环节记下的是"事实"，不是"用户认可的新说法"。
                session.setCandidateThought(balance(session, message));
                session.setStage(CbtStage.INTERVENTION_DECISION);
                reply = script.question(CbtStage.INTERVENTION_DECISION);
            }
            case INTERVENTION_DECISION -> {
                session.setInterventionType(classifyIntervention(message));
                session.setActionPlan(message);
                session.setStage(CbtStage.ACTION_CONFIRM);
                session.setAwaitingUser(true);
                reply = script.question(CbtStage.ACTION_CONFIRM, Map.of(
                        "thought", safe(session.candidateThought()),
                        "plan", message));
            }
            case ACTION_REVIEW -> {
                session.setActionResult(message);
                session.setStage(CbtStage.SESSION_SUMMARY);
                session.setSummary(summarize(session));
                reply = session.summary() + "\n\n这一轮到这里。记录已经存下了。";
            }
            default -> reply = "这一轮已经结束了。如果想继续，开一轮新的。";
        }

        session.setPendingQuestion(reply);
        session.addMessage(CbtMessage.assistant(session.stage(), reply));
        store.save(session);

        String status = session.awaitingUser()
                ? TurnResult.AWAITING_CONFIRMATION
                : session.stage() == CbtStage.SESSION_SUMMARY ? TurnResult.ENDED : TurnResult.AWAITING_USER;
        return result(session, status, reply);
    }

    /** 唯一能写入 userConfirmedThought 的入口。普通消息永远改不了这个字段。 */
    public TurnResult confirm(String sessionId, String thought) {
        CbtSession session = require(sessionId);
        if (session.stage() != CbtStage.ACTION_CONFIRM || !session.awaitingUser()) {
            throw new IllegalStateException("当前不在待确认状态，无法确认");
        }
        String confirmed = (thought == null || thought.isBlank()) ? session.candidateThought() : thought;
        session.setUserConfirmedThought(confirmed);
        session.setAwaitingUser(false);
        session.setStage(CbtStage.ACTION_REVIEW);

        String question = script.question(CbtStage.ACTION_REVIEW);
        String reply = "好，已经记下：\n· 你的说法：「" + confirmed + "」\n· 行动计划：「"
                + safe(session.actionPlan()) + "」\n\n" + question;
        session.setPendingQuestion(question);
        session.addMessage(CbtMessage.assistant(CbtStage.ACTION_REVIEW, reply));
        store.save(session);
        return result(session, TurnResult.AWAITING_USER, reply);
    }

    public TurnResult end(String sessionId) {
        CbtSession session = require(sessionId);
        session.setStage(CbtStage.ENDED);
        session.setAwaitingUser(false);
        store.save(session);
        return result(session, TurnResult.ENDED, "这一轮结束了。");
    }

    public CbtSession require(String sessionId) {
        return store.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在：" + sessionId));
    }

    public List<CbtMessage> history(String sessionId) {
        return require(sessionId).history();
    }

    private TurnResult result(CbtSession session, String status, String response) {
        return new TurnResult(session.id(), session.stage(), session.stage().label(),
                session.stage().visibleIndex(), status, response,
                session.awaitingUser(), session.safetyStatus(), session.userConfirmedThought());
    }

    private static String classifyIntervention(String plan) {
        if (plan == null) return "self_monitoring";
        if (plan.contains("试") || plan.contains("练习") || plan.contains("做") || plan.contains("写")) {
            return "behavioral_experiment";
        }
        if (plan.contains("换") || plan.contains("重新") || plan.contains("想")) {
            return "cognitive_restructuring";
        }
        return "self_monitoring";
    }

    /** 把"原想法 + 新证据"合成一句更接近事实的说法。模板兜底，模型可用时优先用模型。 */
    private String balance(CbtSession s, String newEvidence) {
        String thought = safe(s.automaticThought());
        String template = "「" + thought + "」是一个预测，不是事实。从现有信息看，另一种可能是："
                + trim(newEvidence, 40) + "。";
        try {
            String prompt = """
                    原想法：%s
                    新信息：%s
                    """.formatted(thought, newEvidence);
            String out = chat.chat(BALANCE_SYSTEM, prompt);
            if (out != null) {
                String cleaned = out.strip().replaceAll("^[\"“「]|[\"”」]$", "").strip();
                if (cleaned.length() >= 8 && cleaned.length() <= 80 && !cleaned.contains("资料不足")) {
                    return cleaned;
                }
            }
        } catch (RuntimeException e) {
            log.warn("平衡想法生成失败，退回模板：{}", e.getMessage());
        }
        return template;
    }

    private static String trim(String v, int max) {
        if (v == null) return "";
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    /** 总结优先用模型，但模型输出不合格时退回模板——记录不能因为模型抽风而没有内容。 */
    private String summarize(CbtSession s) {
        String template = String.format(
                "这一轮：情境是「%s」，当时的想法是「%s」，你后来看到「%s」。行动计划：%s。",
                safe(s.situation()), safe(s.automaticThought()), safe(s.candidateThought()), safe(s.actionPlan()));
        try {
            String prompt = """
                    情境：%s
                    当时的想法：%s
                    后来看到的信息：%s
                    行动：%s
                    """.formatted(safe(s.situation()), safe(s.automaticThought()),
                    safe(s.candidateThought()), safe(s.actionPlan()));
            String out = chat.chat(SUMMARY_SYSTEM, prompt);
            if (out != null && out.strip().length() >= 20 && !out.contains("资料不足")) {
                return out.strip();
            }
        } catch (RuntimeException e) {
            log.warn("总结调用模型失败，退回模板：{}", e.getMessage());
        }
        return template;
    }

    private static String safe(String v) {
        return v == null ? "（未填写）" : v;
    }
}
