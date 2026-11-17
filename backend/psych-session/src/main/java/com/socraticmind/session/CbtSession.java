package com.socraticmind.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次 CBT 会话的全部状态。
 *
 * 两条硬规则：
 * 1. candidateThought（系统提议）与 userConfirmedThought（用户认可）必须是两个字段，
 *    否则"用户改了想法"这件事在数据上无法区分（ADR-0005）。
 * 2. awaitingUser 为 true 时流程必须停住，不接受普通消息推进——这是中断点。
 */
public class CbtSession {

    private final String id;
    private final String threadId;
    private final String userId;
    private final Instant createdAt;
    private Instant updatedAt;

    private CbtStage stage = CbtStage.PROBLEM_IDENTIFICATION;
    private String safetyStatus = "normal";
    private String safetyReason;

    private String targetProblem;
    private String situation;
    private String automaticThought;
    private final List<String> emotions = new ArrayList<>();
    private final List<String> evidenceFor = new ArrayList<>();
    private final List<String> evidenceAgainst = new ArrayList<>();

    private String candidateThought;
    private String userConfirmedThought;
    private String interventionType;
    private String actionPlan;
    private String actionResult;
    private String summary;

    private String pendingQuestion;
    private boolean awaitingUser;
    private final List<CbtMessage> messages = new ArrayList<>();

    public CbtSession(String id, String threadId, String userId) {
        this.id = id;
        this.threadId = threadId;
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void addMessage(CbtMessage message) {
        messages.add(message);
        updatedAt = Instant.now();
    }

    public String id() { return id; }
    public String threadId() { return threadId; }
    public String userId() { return userId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public CbtStage stage() { return stage; }
    public void setStage(CbtStage stage) { this.stage = stage; updatedAt = Instant.now(); }

    public String safetyStatus() { return safetyStatus; }
    public void setSafetyStatus(String safetyStatus) { this.safetyStatus = safetyStatus; }
    public String safetyReason() { return safetyReason; }
    public void setSafetyReason(String safetyReason) { this.safetyReason = safetyReason; }

    public String targetProblem() { return targetProblem; }
    public void setTargetProblem(String targetProblem) { this.targetProblem = targetProblem; }

    public String situation() { return situation; }
    public void setSituation(String situation) { this.situation = situation; }

    public String automaticThought() { return automaticThought; }
    public void setAutomaticThought(String automaticThought) { this.automaticThought = automaticThought; }

    public List<String> emotions() { return emotions; }
    public List<String> evidenceFor() { return evidenceFor; }
    public List<String> evidenceAgainst() { return evidenceAgainst; }

    public String candidateThought() { return candidateThought; }
    public void setCandidateThought(String candidateThought) { this.candidateThought = candidateThought; }

    public String userConfirmedThought() { return userConfirmedThought; }
    public void setUserConfirmedThought(String userConfirmedThought) { this.userConfirmedThought = userConfirmedThought; }

    public String interventionType() { return interventionType; }
    public void setInterventionType(String interventionType) { this.interventionType = interventionType; }

    public String actionPlan() { return actionPlan; }
    public void setActionPlan(String actionPlan) { this.actionPlan = actionPlan; }

    public String actionResult() { return actionResult; }
    public void setActionResult(String actionResult) { this.actionResult = actionResult; }

    public String summary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String pendingQuestion() { return pendingQuestion; }
    public void setPendingQuestion(String pendingQuestion) { this.pendingQuestion = pendingQuestion; }

    public boolean awaitingUser() { return awaitingUser; }
    public void setAwaitingUser(boolean awaitingUser) { this.awaitingUser = awaitingUser; }

    public List<CbtMessage> messages() { return List.copyOf(messages); }

    /** 历史是业务事实，与 Graph 的 checkpoint 不是一回事：前者可导出给咨询师看。 */
    public List<CbtMessage> history() {
        return List.copyOf(messages);
    }
}
