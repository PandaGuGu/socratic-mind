package com.socraticmind.session;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
    private Instant createdAt;
    private Instant updatedAt;

    private CbtStage stage = CbtStage.PROBLEM_IDENTIFICATION;
    private String safetyStatus = "normal";
    private String safetyReason;

    private String targetProblem;
    private String situation;
    private String automaticThought;
    /**
     * 这四个列表会被"读接口"和"写流程"同时碰到：
     * 控制台在序列化历史时，用户可能正在追加一条消息。
     * 用 CopyOnWriteArrayList 让迭代拿到快照，避免偶发的
     * ConcurrentModificationException（代价是每次追加复制一份，几十条量级可忽略）。
     */
    private final List<String> emotions = new CopyOnWriteArrayList<>();
    private final List<String> evidenceFor = new CopyOnWriteArrayList<>();
    private final List<String> evidenceAgainst = new CopyOnWriteArrayList<>();

    /**
     * 用户自己打的情绪分（0–10）。与 emotions 的区别：
     * emotions 是"他说了哪些情绪词"，由文本标签得到；
     * moodPoints 是"他给自己打了多少分"，只能由用户点击产生。
     * 前者不能当曲线用（无强度、无方向），后者能。
     */
    private final List<MoodPoint> moodPoints = new CopyOnWriteArrayList<>();

    /** 自动思维里标出的候选认知偏差（见 DistortionTagger）。只是线索，不是诊断。 */
    private final List<String> distortions = new CopyOnWriteArrayList<>();

    /** 紧急联系人：用户自己填，只在安全暂停时回显。手机号不落明文日志。 */
    private String contactName;
    private String contactPhone;
    private String contactRelation;

    /** 危机事件的处置时间：后台点过"已联系"才写，没写就是待处理。 */
    private Instant safetyAckAt;
    private String safetyAckBy;

    private String candidateThought;
    private String userConfirmedThought;
    private String interventionType;
    private String actionPlan;
    private String actionResult;
    private String summary;

    private String pendingQuestion;
    private boolean awaitingUser;
    private final List<CbtMessage> messages = new CopyOnWriteArrayList<>();

    /** 审计轨迹：每一轮为什么这么走。与 messages 分开存，导出时不必带上。 */
    private final List<CbtTurnEvent> trace = new CopyOnWriteArrayList<>();

    public CbtSession(String id, String threadId, String userId) {
        this.id = id;
        this.threadId = threadId;
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * 从存储重建时用：保留原时间戳。
     * 不补这一步，"读出来的会话"会看起来是刚创建的，日志与列表排序全乱。
     */
    public void restoreTimes(Instant createdAt, Instant updatedAt) {
        if (createdAt != null) this.createdAt = createdAt;
        if (updatedAt != null) this.updatedAt = updatedAt;
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

    public List<MoodPoint> moodPoints() { return List.copyOf(moodPoints); }

    /** 唯一写入口：分数来自用户点击，turn 用来对齐对话轮次。 */
    public void addMood(int score, int turn) {
        moodPoints.add(MoodPoint.of(turn, score));
        updatedAt = Instant.now();
    }

    public List<String> distortions() { return distortions; }

    /** 认知偏差只增不减：用户原话没变，标签就不该被后一轮覆盖掉。 */
    public void addDistortions(List<String> tags) {
        if (tags == null) return;
        for (String tag : tags) {
            if (!distortions.contains(tag)) distortions.add(tag);
        }
        updatedAt = Instant.now();
    }

    public String contactName() { return contactName; }
    public String contactPhone() { return contactPhone; }
    public String contactRelation() { return contactRelation; }

    public void setContact(String name, String phone, String relation) {
        this.contactName = name;
        this.contactPhone = phone;
        this.contactRelation = relation;
        updatedAt = Instant.now();
    }

    public boolean hasContact() {
        return (contactName != null && !contactName.isBlank())
                || (contactPhone != null && !contactPhone.isBlank());
    }

    public Instant safetyAckAt() { return safetyAckAt; }
    public String safetyAckBy() { return safetyAckBy; }

    public void ackSafety(String by) {
        this.safetyAckAt = Instant.now();
        this.safetyAckBy = by;
        updatedAt = this.safetyAckAt;
    }

    /** 从库里读回时用：保留原来的自评时间与轮次，不刷新 updatedAt。 */
    public void restoreMood(java.util.List<MoodPoint> points) {
        if (points != null) moodPoints.addAll(points);
    }

    public void restoreAck(Instant at, String by) {
        this.safetyAckAt = at;
        this.safetyAckBy = by;
    }

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

    public void addEvent(CbtTurnEvent event) {
        trace.add(event);
        updatedAt = Instant.now();
    }

    public List<CbtTurnEvent> trace() {
        return List.copyOf(trace);
    }
}
