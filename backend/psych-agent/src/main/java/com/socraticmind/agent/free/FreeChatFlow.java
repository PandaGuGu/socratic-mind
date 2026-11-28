package com.socraticmind.agent.free;

import com.socraticmind.agent.cbt.safety.SafetyScreener;
import com.socraticmind.common.guard.ModelOutputRejectedException;
import com.socraticmind.common.guard.PolicyProfiles;
import com.socraticmind.common.port.ChatPort;
import com.socraticmind.common.prompt.PromptSettings;
import com.socraticmind.common.support.Ids;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CrisisCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 门外：不走流程的自由对话。
 *
 * 与 {@link com.socraticmind.agent.cbt.CbtFlow} 的分工：那边负责"把一件事走完"，
 * 这边只负责"接住对方说的话"。所以这里没有 stage 推进、没有待确认、没有行动计划，
 * 只有一串 user/assistant 消息落在同一个会话表里（窗外才能一起回看）。
 *
 * 两条底线仍然照旧，一条都不放弃：
 * 1. **每轮都过安全筛查**。走出门不等于绕过自伤识别，触发照样暂停并给危机卡片；
 * 2. **提示词边界不变**。见 {@code prompts/free/companion.md}，禁止诊断/用药/承诺疗效。
 *
 * 关于模型：没有配置真实对话模型时（modelId 以 stub 开头），桩生成器只会从证据段落里
 * 摘句子，自由对话里它一句话也生不出来。所以那种情况下退回一组固定的"接话"话术，
 * 让人至少能感受到对话的节奏；接了真模型之后，这一段自动不再生效。
 */
@Service
public class FreeChatFlow {

    private static final Logger log = LoggerFactory.getLogger(FreeChatFlow.class);

    /** 带进提示词的历史条数：太多会稀释当前这句，太少又接不上上下文。 */
    private static final int HISTORY_WINDOW = 12;

    private static final String COMPANION_FALLBACK =
            "你是一个愿意听人说话的人。一次最多问一个问题；不诊断、不用药、不承诺疗效，也不替对方下结论。";

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_SAFETY_HOLD = "safety_hold";

    /** 安全暂停时的固定话术。不生成、不改写：这种时刻少说一句比多说一句好。 */
    private static final String HOLD_REPLY =
            "先停一下。你刚才说到的这部分，我不想草草接过去。"
            + "如果此刻你身边有人，可以叫一下他；如果危险就在眼前，请先联系急救。"
            + "下面的卡片里有你可以直接用的方式，用不用由你决定。";

    private static final String GREETING =
            "（门在身后轻轻合上）外面没什么人，也不用按顺序说什么。想从哪儿开始都行。";

    private final CbtSessionStore store;
    private final ChatPort chat;
    private final SafetyScreener screener;
    private final PromptSettings prompts;

    private static final int LOCK_STRIPES = 64;
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public FreeChatFlow(CbtSessionStore store, ChatPort chat, SafetyScreener screener,
                        PromptSettings prompts) {
        this.store = store;
        this.chat = chat;
        this.screener = screener;
        this.prompts = prompts;
        for (int i = 0; i < LOCK_STRIPES; i++) locks[i] = new ReentrantLock();
    }

    /** 一次门外往返的结果。字段与 CBT 的 TurnResult 对齐的部分，前端可以直接复用渲染。 */
    public record FreeTurn(
            String sessionId,
            String reply,
            String status,
            String safetyStatus,
            CrisisCard crisis,
            String model,
            List<CbtMessage> messages
    ) {
    }

    public FreeTurn start(String userId, String accountId) {
        String id = Ids.of("free");
        CbtSession session = new CbtSession(id, "thread_" + id, userId == null ? "anonymous" : userId);
        session.setAccountId(accountId);
        session.setStage(CbtStage.FREE);
        session.setAwaitingUser(false);
        session.addMessage(CbtMessage.assistant(CbtStage.FREE, GREETING));
        store.save(session);
        return view(session, GREETING, STATUS_OPEN, null);
    }

    /**
     * 最近一轮还没被安全暂停的门外对话。走到门口再回来时接着上次说，
     * 而不是每次都从"外面没什么人"重新开始。
     */
    public Optional<FreeTurn> resume(String userId, String accountId) {
        return store.findVisiblePage(userId, accountId, null, null, 20).stream()
                .filter(s -> s.stage() == CbtStage.FREE)
                .filter(s -> !"hold".equals(s.safetyStatus()))
                .filter(s -> !s.messages().isEmpty())
                .findFirst()
                .map(s -> view(s, null, STATUS_OPEN, null));
    }

    public FreeTurn say(String sessionId, String message) {
        ReentrantLock lock = locks[Math.floorMod(sessionId.hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            return sayLocked(sessionId, message);
        } finally {
            lock.unlock();
        }
    }

    private FreeTurn sayLocked(String sessionId, String message) {
        CbtSession session = store.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("这段对话不存在"));
        if (session.stage() != CbtStage.FREE) {
            throw new IllegalStateException("这一段不在门外，请在房间里继续");
        }
        if ("hold".equals(session.safetyStatus())) {
            // 暂停后不再生成任何回应：说了什么都不会被接住，反而不如停住。
            return view(session, null, STATUS_SAFETY_HOLD, null);
        }

        SafetyScreener.Screen screen = screener.screen(message);
        if (screen.hold()) {
            session.setSafetyStatus("hold");
            session.setSafetyReason(screen.reason());
            session.addMessage(CbtMessage.user(CbtStage.FREE, message));
            session.addMessage(CbtMessage.assistant(CbtStage.FREE, HOLD_REPLY));
            store.save(session);
            log.warn("门外安全分流 session={} rule={}", session.id(), screen.rule());
            return view(session, HOLD_REPLY, STATUS_SAFETY_HOLD,
                    CrisisCard.of(session, session.safetyReason()));
        }
        if (screen.review()) {
            // 语境指向他人/引用：不暂停，但留痕，和屋里同一套处理。
            session.setSafetyStatus("review");
            session.setSafetyReason(screen.reason());
        }

        String reply = reply(session, message);
        session.addMessage(CbtMessage.user(CbtStage.FREE, message));
        session.addMessage(CbtMessage.assistant(CbtStage.FREE, reply));
        store.save(session);
        return view(session, reply, STATUS_OPEN, null);
    }

    private FreeTurn view(CbtSession session, String reply, String status, CrisisCard crisis) {
        return new FreeTurn(session.id(), reply, status, session.safetyStatus(), crisis,
                chat.modelId(), List.copyOf(session.messages()));
    }

    private String reply(CbtSession session, String message) {
        if (isStub()) return echo(session, message);
        StringBuilder ctx = new StringBuilder();
        List<CbtMessage> history = session.messages();
        int from = Math.max(0, history.size() - HISTORY_WINDOW);
        for (int i = from; i < history.size(); i++) {
            CbtMessage m = history.get(i);
            ctx.append("user".equals(m.role()) ? "我：" : "你：").append(m.content()).append('\n');
        }
        ctx.append("我：").append(message);
        try {
            // 门外和房间里用不同的档位：门外没有流程，语气更松，但"不劝、不诊断、不承诺"一样要过闸。
            return chat.chat(companionPrompt(), ctx.toString(), PolicyProfiles.FREE_CHAT);
        } catch (ModelOutputRejectedException e) {
            log.warn("门外回应未通过输出策略，改用接话：rule={} {}", e.rule(), e.getMessage());
            return echo(session, message);
        } catch (RuntimeException e) {
            log.warn("门外回应生成失败，改用接话：{}", e.getMessage());
            return echo(session, message);
        }
    }

    private boolean isStub() {
        String id = chat.modelId();
        return id == null || id.startsWith("stub");
    }

    /** 没有真模型时的接话：轮流用四句，不评价、不追问第二遍。 */
    /**
     * 没有真模型时的接话：按"对方说了什么"挑句子，而不是按轮次轮播模板。
     * 具体规则（认出问候 / 情绪 / 可引用的话，每三轮有一轮不追问）见 {@link FreeEcho}。
     */
    private String echo(CbtSession session, String message) {
        int said = Math.max(0, (session.messages().size() - 1) / 2);
        List<CbtMessage> history = session.messages();
        List<String> recent = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && recent.size() < 3; i--) {
            if (!"assistant".equals(history.get(i).role())) continue;
            String text = history.get(i).content();
            if (text != null && !text.equals(GREETING)) recent.add(text);
        }
        return FreeEcho.reply(message, said, recent);
    }

    /** 提示词由后台维护；没配置时退回内置版本，保证门外始终能接话。 */
    private String companionPrompt() {
        String text = prompts == null ? "" : prompts.get().freeChat();
        return text == null || text.isBlank() ? COMPANION_FALLBACK : text;
    }
}
