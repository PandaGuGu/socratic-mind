package com.socraticmind.app;

import com.socraticmind.agent.cbt.CbtFlow;
import com.socraticmind.agent.cbt.TurnResult;
import com.socraticmind.session.CbtMessage;
import com.socraticmind.session.CbtSession;
import com.socraticmind.session.CbtSessionStore;
import com.socraticmind.session.CbtStage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CBT 流程的验收：状态推进、中断点、用户确认字段分离、安全分流。
 * 对应 docs/02-设计/CBT状态机.md 与 docs/01-方案/10-里程碑与开发顺序.md 的 M4 验收。
 */
@SpringBootTest
class CbtFlowTest {

    @Autowired
    CbtFlow flow;

    @Autowired
    CbtSessionStore store;

    @Test
    void walksThroughFiveStagesAndStopsAtConfirmation() {
        TurnResult started = flow.start("u1");
        assertEquals(CbtStage.PROBLEM_IDENTIFICATION, started.stage());
        assertEquals(TurnResult.AWAITING_USER, started.status());

        TurnResult t1 = flow.handle(started.sessionId(), "下周组会汇报，一想到就手心出汗");
        assertEquals(CbtStage.COGNITIVE_EXTRACTION, t1.stage());

        TurnResult t2 = flow.handle(started.sessionId(), "我肯定会搞砸");
        assertEquals(CbtStage.COGNITIVE_REVIEW, t2.stage());
        assertTrue(t2.response().contains("我肯定会搞砸"), "检验提问必须引用用户原话");

        TurnResult t3 = flow.handle(started.sessionId(), "其实前三次有两次还行");
        assertEquals(CbtStage.INTERVENTION_DECISION, t3.stage());

        CbtSession afterReview = store.find(started.sessionId()).orElseThrow();
        assertNotEquals("其实前三次有两次还行", afterReview.candidateThought(),
                "候选想法应是改写后的说法，不能直接存证据原句");

        // 计划里出现"写"→ 命中行为实验分支：先追问"什么时候做、怎么算完成"，再进确认
        TurnResult t4 = flow.handle(started.sessionId(), "提前写下三条具体预测，会后核对");
        assertEquals(CbtStage.BEHAVIORAL_EXPERIMENT, t4.stage());

        TurnResult t5 = flow.handle(started.sessionId(), "周六上午写三条，会后逐条核对");
        assertEquals(CbtStage.ACTION_CONFIRM, t5.stage());
        assertEquals(TurnResult.AWAITING_CONFIRMATION, t5.status());
        assertTrue(t5.awaitingConfirmation());

        CbtSession session = store.find(started.sessionId()).orElseThrow();
        assertNull(session.userConfirmedThought(), "中断时不能有已确认的结论");
        assertNotNull(session.candidateThought());
    }

    @Test
    void plainMessageCannotAdvanceConfirmationStage() {
        TurnResult started = flow.start("u2");
        flow.handle(started.sessionId(), "和室友闹别扭");
        flow.handle(started.sessionId(), "他肯定讨厌我");
        flow.handle(started.sessionId(), "他可能只是在忙");
        TurnResult pending = flow.handle(started.sessionId(), "找他聊一次");

        TurnResult hijack = flow.handle(started.sessionId(), "行吧就这样");
        assertEquals(CbtStage.ACTION_CONFIRM, hijack.stage(), "普通消息不得推进中断节点");
        assertEquals(TurnResult.AWAITING_CONFIRMATION, hijack.status());

        CbtSession session = store.find(started.sessionId()).orElseThrow();
        assertNull(session.userConfirmedThought(), "未经 /confirm 不得写入用户确认");

        TurnResult confirmed = flow.confirm(started.sessionId(), null);
        assertEquals(CbtStage.ACTION_REVIEW, confirmed.stage());
        CbtSession after = store.find(started.sessionId()).orElseThrow();
        assertEquals(after.candidateThought(), after.userConfirmedThought());
    }

    @Test
    void confirmIsRejectedOutsideConfirmationStage() {
        TurnResult started = flow.start("u3");
        assertThrows(IllegalStateException.class, () -> flow.confirm(started.sessionId(), "随便"));
    }

    @Test
    void summaryIsRecordedAtTheEnd() {
        TurnResult started = flow.start("u4");
        flow.handle(started.sessionId(), "论文一直拖着");
        flow.handle(started.sessionId(), "我根本不适合读研");
        flow.handle(started.sessionId(), "上学期也拖过，最后还是交了");
        flow.handle(started.sessionId(), "先写三百字");
        // 行为实验分支：确认之前还要把行动缩小到可执行的粒度
        flow.handle(started.sessionId(), "明天上午写一百字，写完发给自己");
        flow.confirm(started.sessionId(), null);

        TurnResult end = flow.handle(started.sessionId(), "写了，没有想象中难");
        assertEquals(CbtStage.SESSION_SUMMARY, end.stage());
        assertEquals(TurnResult.ENDED, end.status());

        CbtSession session = store.find(started.sessionId()).orElseThrow();
        assertNotNull(session.summary());
        assertTrue(session.summary().length() > 10);
    }

    @Test
    void safetyRuleHaltsTheFlowAndStaysHalted() {
        TurnResult started = flow.start("u5");
        TurnResult hold = flow.handle(started.sessionId(), "我真的不想活了，想过结束生命");

        assertEquals(CbtStage.SAFETY_HOLD, hold.stage());
        assertEquals(TurnResult.SAFETY_HOLD, hold.status());
        assertEquals("hold", hold.safetyStatus());
        assertTrue(hold.response().contains("120"), "必须给出可执行的求助路径");

        TurnResult again = flow.handle(started.sessionId(), "我还想说点别的");
        assertEquals(CbtStage.SAFETY_HOLD, again.stage(), "暂停后不得恢复普通流程");
    }

    @Test
    void thirdPersonMentionIsNotTreatedAsCrisis() {
        TurnResult started = flow.start("u6");
        TurnResult normal = flow.handle(started.sessionId(), "看了一部电影，里面有个角色割腕");
        assertEquals(CbtStage.COGNITIVE_EXTRACTION, normal.stage(), "第三人称语境不应触发转介");
    }

    @Test
    void historyKeepsEveryTurn() {
        TurnResult started = flow.start("u7");
        flow.handle(started.sessionId(), "考试焦虑");
        List<CbtMessage> history = flow.history(started.sessionId());
        assertTrue(history.size() >= 3);
        assertEquals("assistant", history.get(0).role());
        assertEquals("user", history.get(1).role());
    }
}
