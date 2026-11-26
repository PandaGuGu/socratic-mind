package com.socraticmind.app;

import com.socraticmind.agent.dto.SearchResult;
import com.socraticmind.agent.knowledge.KnowledgeFlow;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.model.RagAnswer;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.common.support.DocumentVisibility;
import com.socraticmind.knowledge.dto.IngestCommand;
import com.socraticmind.knowledge.service.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * 语料级权限：范围之外的人既检索不到、也引用不到。
 *
 * 关键点是"在检索之前过滤"：如果先取回来再在生成后删引用，
 * 越权内容已经进了模型上下文，答案里可能已经泄露了它的意思。
 */
@SpringBootTest
class DocumentAclTest {

    @Autowired
    KnowledgeFlow flow;

    @Autowired
    IngestionService ingestion;

    @Autowired
    KnowledgeStore store;

    private String ingestScoped() {
        var result = ingestion.ingest(new IngestCommand("acl", "暴露疗法适合什么人", "测试来源", null, "zh",
                true, "# 暴露疗法\n\n暴露疗法在安全条件下逐步接触所回避的情境，适合以恐惧与回避为主的困扰。"));
        store.updateDocumentStatus(result.documentId(), KnowledgeDocument.PUBLISHED);
        store.updateAcl(result.documentId(), "team:cbt");
        return result.documentId();
    }

    @Test
    void scopedDocumentIsVisibleOnlyToItsScope() {
        String docId = ingestScoped();

        SearchResult open = flow.search("暴露疗法适合什么人", 5, null);
        assertTrue(open.hits().stream().anyMatch(h -> docId.equals(h.documentId())),
                "不带范围（null）应当能看到公开与全部范围");

        SearchResult otherTeam = flow.search("暴露疗法适合什么人", 5, Set.of("team:other"));
        assertFalse(otherTeam.hits().stream().anyMatch(h -> docId.equals(h.documentId())),
                "范围外的人检索不到这篇");

        SearchResult ownTeam = flow.search("暴露疗法适合什么人", 5, Set.of("team:cbt"));
        assertTrue(ownTeam.hits().stream().anyMatch(h -> docId.equals(h.documentId())),
                "范围内的人照常检索");
    }

    @Test
    void scopedOutQuestionNeverCitesTheHiddenDocument() {
        String docId = ingestScoped();
        RagAnswer denied = flow.run("暴露疗法适合什么人", Set.of("team:other"));

        // 不能断言"必然拒答"：这个问法也可能被别的公开资料以弱相关答上（那是另一回事）。
        // 权限要保证的是一条更窄、也更硬的性质：越权文档一个字都不能进引用。
        assertFalse(denied.citations().stream().anyMatch(c -> docId.equals(c.documentId())),
                "越权文档不能出现在引用里，哪怕它被检索到了也必须先被过滤");
        // 用正文里的特征短语，而不是问题里的词——轨迹本来就会回显问题，
        // 拿"暴露疗法"去比对，命中的是 rewrite_query 那一行，测不出泄露。
        assertFalse(denied.trace().stream().anyMatch(t -> t.contains("安全条件下逐步接触")),
                "轨迹里也不该出现被过滤文档的正文片段");
    }

    @Test
    void blankAclMeansPublic() {
        assertNull(DocumentVisibility.normalizeAcl("  "), "空 ACL 归一化为 null（公开）");
        assertEquals("team:cbt,role:clinician",
                DocumentVisibility.normalizeAcl(" team:cbt ; role:clinician team:cbt "),
                "重复与分隔符要被归一化，否则同一条规则会写成两种字符串，权限判断随之分叉");
    }
}
