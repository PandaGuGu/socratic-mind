package com.socraticmind.app;

import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.model.RetrievedChunk;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.knowledge.dto.IngestCommand;
import com.socraticmind.knowledge.dto.IngestResult;
import com.socraticmind.knowledge.service.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布闸门：入库接口不能再"带个 publish=true 就直接成为证据"。
 *
 * 这条测试守的是产品边界"不做无据生成"——如果"据"可以匿名写进去，
 * 那"有据"只是一句形式上的话。
 */
@SpringBootTest
class KnowledgePublishTest {

    @Autowired
    IngestionService ingestion;

    @Autowired
    KnowledgeStore store;

    @Test
    void uploadWithPublishFlagStillWaitsForReview() {
        String probe = "量子土豆协同过滤";

        IngestResult result = ingestion.ingest(new IngestCommand(
                "test", "测试资料：待审核闸门", "单元测试",
                null, "zh", true,
                "这段文字只用于验证发布闸门。关键词是" + probe + "。它不应该在审核通过前被检索到。"));

        assertEquals(KnowledgeDocument.REVIEWING, result.status(), "publish=true 只应表示提交审核");

        assertEquals(0, hitsOf(result.documentId(), probe),
                "未审核的内容不得进入检索结果");

        // 人工审核通过之后才可用
        store.updateDocumentStatus(result.documentId(), KnowledgeDocument.PUBLISHED);
        assertEquals(1, hitsOf(result.documentId(), probe), "审核通过后应可被检索到");
    }

    private long hitsOf(String documentId, String query) {
        List<RetrievedChunk> hits = store.searchText(query, 10, null);
        return hits.stream().filter(h -> h.chunk().documentId().equals(documentId)).count();
    }
}
