package com.socraticmind.agent.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模型自述"资料不足"必须映射成拒答状态，不能一边说没资料一边记 answered。 */
class KnowledgeFlowRefusalTest {

    @Test
    void detectsSelfRefusalAtTheBeginning() {
        assertTrue(KnowledgeFlow.selfRefused("现有资料不足以回答这一点。\n\n原因：……"));
        assertTrue(KnowledgeFlow.selfRefused("资料不足，无法判断。"));
        assertTrue(KnowledgeFlow.selfRefused("提供的证据未提及 CBT 的疗程次数。"));
    }

    @Test
    void doesNotMistakeACaveatForRefusal() {
        assertFalse(KnowledgeFlow.selfRefused("认知检验分五步：记录情境、写下想法、列证据、形成平衡说法、重新评分 [1]。"));
        assertFalse(KnowledgeFlow.selfRefused(""));
        assertFalse(KnowledgeFlow.selfRefused(null));
        // 可答题里正好在描述"系统会拒答"，不能在全文里搜"资料不足"
        assertFalse(KnowledgeFlow.selfRefused(
                "这个系统提供心理知识科普和结构化的 CBT 自助练习，并在证据不足时明确说明 [1]。"));
        assertFalse(KnowledgeFlow.selfRefused(
                "当证据不足时，系统会明确说明“资料不足以回答”，而不是给出推测 [1]。"));
    }
}
