package com.socraticmind.app;

import com.socraticmind.agent.knowledge.KnowledgeFlow;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.model.RagAnswer;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.knowledge.dto.IngestCommand;
import com.socraticmind.knowledge.service.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全链路冒烟：示例语料入库 → 检索 → 证据 → 生成 → 引用校验。
 * 这组断言就是 M2 的验收标准（docs/06-评估/02-评估与测试集.md）。
 */
@SpringBootTest
class KnowledgeChainTest {

    @Autowired
    KnowledgeFlow flow;

    @Autowired
    IngestionService ingestion;

    @Autowired
    KnowledgeStore store;

    @Test
    void answersWithTraceableCitations() {
        RagAnswer answer = flow.run("自动思维和核心信念有什么区别？");

        assertEquals(RagAnswer.ANSWERED, answer.status());
        assertFalse(answer.citations().isEmpty(), "回答必须带引用");
        assertTrue(answer.verification().allCitationsValid(), "引用编号必须都能对应到证据");
        assertTrue(answer.citations().get(0).documentTitle() != null);
        assertFalse(answer.trace().isEmpty(), "流程轨迹不能为空");
    }

    @Test
    void refusesWhenKnowledgeIsMissing() {
        RagAnswer answer = flow.run("自行车链条掉了怎么装回去");
        assertEquals(RagAnswer.INSUFFICIENT, answer.status(), "无资料时必须明确拒答，而不是编");
    }

    @Test
    void draftDocumentsAreNotRetrievable() {
        ingestion.ingest(new IngestCommand("test", "草稿：量子纠缠与蝴蝶效应",
                "未审核来源", null, "zh", false,
                "# 草稿\n\n量子纠缠与蝴蝶效应之间存在某种尚待审核的关联描述。"));

        RagAnswer answer = flow.run("量子纠缠和蝴蝶效应是什么关系");
        assertEquals(RagAnswer.INSUFFICIENT, answer.status(), "未发布文档不得进入检索候选集");
    }

    @Test
    void documentBecomesSearchableOnlyAfterReview() {
        var result = ingestion.ingest(new IngestCommand("test", "示例：注意力恢复",
                "内部示例", null, "zh", true,
                "# 注意力恢复\n\n## 自然暴露\n\n在自然环境中停留一段时间，有助于注意力的恢复，这一现象常被称为注意力恢复理论。"));

        // publish=true 只是"提交审核"：入库不产生可引用的证据
        assertEquals(KnowledgeDocument.REVIEWING, result.status());
        assertEquals(RagAnswer.INSUFFICIENT, flow.run("自然环境对注意力恢复有什么帮助").status(),
                "未审核的内容不得成为依据");

        store.updateDocumentStatus(result.documentId(), KnowledgeDocument.PUBLISHED);
        RagAnswer answer = flow.run("自然环境对注意力恢复有什么帮助");
        assertEquals(RagAnswer.ANSWERED, answer.status());
        assertFalse(answer.citations().isEmpty());
    }
}
