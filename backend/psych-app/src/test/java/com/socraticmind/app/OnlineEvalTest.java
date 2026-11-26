package com.socraticmind.app;

import com.socraticmind.agent.knowledge.KnowledgeFlow;
import com.socraticmind.agent.knowledge.OnlineEvalService;
import com.socraticmind.common.model.BadCase;
import com.socraticmind.common.model.EvalSummary;
import com.socraticmind.common.model.QueryOutcome;
import com.socraticmind.common.model.RagAnswer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在线评测闭环：每轮留痕、可疑样本自动进池、能按窗口看指标、能标记已处理。
 *
 * 断言都用 >= 而不是等号：评测存储与知识库共享同一个上下文，
 * 别的测试类跑过的问答也会计入。用等号会变成"只有单独跑这个类才通过"的测试。
 */
@SpringBootTest
class OnlineEvalTest {

    @Autowired
    KnowledgeFlow flow;

    @Autowired
    OnlineEvalService eval;

    @Test
    void outcomesAreRecordedAndRefusalsBecomeBadCases() {
        flow.run("自动思维和核心信念有什么区别？");
        flow.run("自行车链条掉了怎么装回去");

        EvalSummary summary = eval.summarize(1);
        assertTrue(summary.total() >= 2, "每一轮都要留痕");
        assertTrue(summary.refused() >= 1, "无资料的问题应当计入拒答");
        assertTrue(summary.byStatus().containsKey(RagAnswer.INSUFFICIENT),
                "按状态分组要能看出拒答占多少");
        assertTrue(summary.openBadCases() >= 1,
                "拒答要进待复核池：拒答不等于错误，但永远值得看一眼");
    }

    @Test
    void badCaseCanBeResolved() {
        flow.run("量子纠缠和蝴蝶效应之间有什么关系");
        List<BadCase> open = eval.badCases(true);
        assertFalse(open.isEmpty());

        String id = open.get(0).id();
        assertTrue(eval.resolve(id));
        assertFalse(eval.badCases(true).stream().anyMatch(b -> b.id().equals(id)),
                "标记已处理之后不该再出现在待复核列表里");
    }

    @Test
    void sampleIsDeterministicAndSpansTheWindow() {
        for (int i = 0; i < 6; i++) {
            flow.run("认知重构的步骤是什么？");
        }
        List<QueryOutcome> first = eval.sample(1, 3);
        List<QueryOutcome> second = eval.sample(1, 3);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).query(), second.get(i).query(),
                    "抽样必须确定性：随机抽样会让今天和昨天看的不是同一批");
        }
    }
}
