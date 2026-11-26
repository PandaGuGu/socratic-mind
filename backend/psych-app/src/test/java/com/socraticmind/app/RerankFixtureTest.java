package com.socraticmind.app;

import com.socraticmind.agent.knowledge.KnowledgeFlow;
import com.socraticmind.common.model.RagAnswer;
import com.socraticmind.rag.dto.Hit;
import com.socraticmind.rag.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重排档 = fixture：分数来自"子代理扮演 cross-encoder"的离线标注。
 *
 * 这组断言的是**排错了会怎样**，不是"分数准不准"：
 * 问"能不能替代心理治疗"，召回把"可以做的事"排在第一——它含"心理治疗"这几个字，
 * 但答案在"不能做的事"那一段。重排层要做的正是这件事。
 */
@SpringBootTest(properties = {"psych.ai.rerank.provider=fixture"})
class RerankFixtureTest {

    @Autowired
    KnowledgeFlow flow;

    @Autowired
    RagService rag;

    /**
     * 重排负责的是**排序**，不是引用选择——桩生成器引用第几条是它自己的事。
     * 所以这里断言检索结果的第一条，而不是回答里第一条引用：
     * 把两件事混在一句断言里，改桩生成器就会让重排的测试莫名其妙地红。
     */
    @Test
    void rerankPutsTheAnswerSegmentFirst() {
        List<Hit> hits = rag.retrieve("这个系统能替代心理治疗吗？", 4);

        assertFalse(hits.isEmpty());
        assertEquals("不能做的事", hits.get(0).chunk().sectionPath(),
                "重排后第一条应当是正面回答「能不能替代」的那一段，而不是含关键词的「可以做的事」");
        assertTrue(hits.get(0).rerankScore() > 0.9, "模型分要落在高分档：" + hits.get(0).rerankScore());

        RagAnswer answer = flow.run("这个系统能替代心理治疗吗？");
        assertEquals(RagAnswer.ANSWERED, answer.status());
    }

    @Test
    void rerankModelShowsUpInTrace() {
        RagAnswer answer = flow.run("焦虑是怎么维持下去的？");
        assertTrue(answer.trace().stream().anyMatch(t -> t.contains("agent-subagent-v1")),
                "轨迹里必须写明用的是哪一档重排；否则出了排序问题会先怀疑检索：" + answer.trace());
    }

    @Test
    void rerankCannotInventKnowledge() {
        // 语料里没有失眠干预内容，重排再准也只能把最接近的段落排上来，不能把"答不了"变成"答得出"。
        RagAnswer answer = flow.run("睡不着的时候可以做什么？");
        assertEquals(RagAnswer.INSUFFICIENT, answer.status(),
                "重排是排序层，不改变证据是否充分；这一条用来防止把拒答的功劳记到重排头上");
    }
}
