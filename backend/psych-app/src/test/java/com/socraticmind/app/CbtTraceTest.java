package com.socraticmind.app;

import com.socraticmind.agent.cbt.CbtFlow;
import com.socraticmind.agent.cbt.TurnResult;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import com.socraticmind.session.CbtTurnEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轮次轨迹的验收：内部后台的全部判断都建立在它之上。
 *
 * 对应 docs/02-设计/内部后台.md §3：只存字段快照的话，
 * 「第几轮为什么走这一步」「这句总结是不是模板兜底」事后都无从复核。
 */
@SpringBootTest
class CbtTraceTest {

    @Autowired
    CbtFlow flow;

    @Autowired
    CbtSessionStore store;

    private List<CbtTurnEvent> traceOf(String sessionId) {
        return store.find(sessionId).orElseThrow().trace();
    }

    @Test
    void startWritesTheFirstEvent() {
        TurnResult started = flow.start("trace-u1");
        List<CbtTurnEvent> trace = traceOf(started.sessionId());

        assertEquals(1, trace.size());
        CbtTurnEvent first = trace.get(0);
        assertEquals(CbtTurnEvent.START, first.action());
        assertNull(first.stageFrom(), "首轮没有来源阶段");
        assertEquals(CbtStage.PROBLEM_IDENTIFICATION, first.stageTo());
        assertEquals(TurnResult.AWAITING_USER, first.status());
        assertNull(first.safetyRule());
    }

    @Test
    void everyAnswerIsNumberedAndCarriesItsTransition() {
        TurnResult started = flow.start("trace-u2");
        String id = started.sessionId();
        flow.handle(id, "下周组会要汇报，一想到就手心出汗");
        flow.handle(id, "我肯定会搞砸");
        flow.handle(id, "前三次有两次其实还行");
        flow.handle(id, "提前写下三条预测，会后核对");

        List<CbtTurnEvent> trace = traceOf(id);
        assertEquals(5, trace.size());
        for (int i = 0; i < trace.size(); i++) {
            assertEquals(i + 1, trace.get(i).turnNo(), "轮次必须连续，后台按它排序");
        }

        CbtTurnEvent review = trace.get(3);
        assertEquals(CbtStage.COGNITIVE_REVIEW, review.stageFrom());
        assertEquals(CbtStage.INTERVENTION_DECISION, review.stageTo());
        assertEquals(CbtTurnEvent.TEMPLATE, review.modelMode(),
                "默认桩模型返回「资料不足」，必须记成模板兜底而不是模型生成");
        assertNotNull(review.guardRule(), "被输出策略拦下时必须留痕，否则运维看不到规则到底在不在生效");
        assertTrue(review.latencyMs() >= 0);
    }

    @Test
    void safetyHoldIsRecordedWithTheMatchedRule() {
        TurnResult started = flow.start("trace-u3");
        TurnResult hold = flow.handle(started.sessionId(), "我真的不想活了，想过结束生命");

        CbtTurnEvent last = traceOf(started.sessionId()).get(1);
        assertEquals(CbtTurnEvent.SAFETY_HOLD, last.action());
        assertEquals(TurnResult.SAFETY_HOLD, last.status());
        assertEquals("self_harm", last.safetyRule(), "必须记下命中的规则，否则无法复核误报与漏判");
        assertEquals(CbtStage.SAFETY_HOLD, last.stageTo());
        assertEquals(CbtStage.SAFETY_HOLD, hold.stage());
    }

    @Test
    void contextGuardPassIsRecordedTooAllowingFalseNegativeReview() {
        TurnResult started = flow.start("trace-u4");
        flow.handle(started.sessionId(), "看了一部电影，里面有个角色割腕");

        CbtTurnEvent last = traceOf(started.sessionId()).get(1);
        assertEquals(CbtTurnEvent.ANSWER, last.action());
        assertEquals("context_guard", last.safetyRule(),
                "被第三人称语境放行也要留痕，否则漏判无法回溯");
    }

    @Test
    void blockedTurnAndConfirmAreDistinguishable() {
        TurnResult started = flow.start("trace-u5");
        String id = started.sessionId();
        flow.handle(id, "和室友闹别扭");
        flow.handle(id, "他肯定讨厌我");
        flow.handle(id, "他可能只是在忙");
        flow.handle(id, "找他聊一次");

        flow.handle(id, "行吧就这样");
        CbtTurnEvent blocked = traceOf(id).get(traceOf(id).size() - 1);
        assertEquals(CbtTurnEvent.BLOCKED, blocked.action(), "普通消息被中断点挡住，要能与正常回答区分");
        assertEquals(CbtStage.ACTION_CONFIRM, blocked.stageFrom());
        assertEquals(CbtStage.ACTION_CONFIRM, blocked.stageTo());

        flow.confirm(id, null);
        CbtTurnEvent confirm = traceOf(id).get(traceOf(id).size() - 1);
        assertEquals(CbtTurnEvent.CONFIRM, confirm.action());
        assertEquals(CbtStage.ACTION_REVIEW, confirm.stageTo());
        assertEquals(TurnResult.AWAITING_USER, confirm.status());
    }

    @Test
    void endIsRecordedOnceAndTerminalTurnsAreBlocked() {
        TurnResult started = flow.start("trace-u6");
        String id = started.sessionId();
        flow.end(id);

        List<CbtTurnEvent> afterEnd = traceOf(id);
        assertEquals(CbtTurnEvent.END, afterEnd.get(afterEnd.size() - 1).action());

        flow.handle(id, "还想说点什么");
        List<CbtTurnEvent> afterTalk = traceOf(id);
        assertEquals(afterEnd.size() + 1, afterTalk.size());
        assertEquals(CbtTurnEvent.BLOCKED, afterTalk.get(afterTalk.size() - 1).action());
        assertEquals(TurnResult.ENDED, afterTalk.get(afterTalk.size() - 1).status());
    }

    @Test
    void traceDoesNotPolluteMessages() {
        TurnResult started = flow.start("trace-u7");
        String id = started.sessionId();
        flow.handle(id, "考试焦虑");

        CbtSession session = store.find(id).orElseThrow();
        assertFalse(session.trace().isEmpty());
        assertEquals(3, session.history().size(), "轨迹与消息分开存：消息只记说过什么");
        assertNotNull(session.trace().get(0).at());
    }
}
