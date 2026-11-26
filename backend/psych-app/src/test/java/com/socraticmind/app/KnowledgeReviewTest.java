package com.socraticmind.app;

import com.socraticmind.app.review.KnowledgeReviewStore;
import com.socraticmind.app.web.KnowledgeController;
import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.knowledge.dto.IngestCommand;
import com.socraticmind.knowledge.dto.SyncResult;
import com.socraticmind.knowledge.service.DocumentSyncService;
import com.socraticmind.knowledge.service.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档审核留痕：谁、什么时候、哪一版、当时给谁看。
 *
 * 这一组测试存在的理由是一个很容易被忽略的事实：
 * 状态从 reviewing 变成 published，本身不构成一次审核。
 * 没有这四个字段，"改版即重审"只是状态机的一句承诺，事后无法证明它发生过。
 */
@SpringBootTest
class KnowledgeReviewTest {

    @Autowired
    KnowledgeController controller;

    @Autowired
    IngestionService ingestion;

    @Autowired
    DocumentSyncService sync;

    @Autowired
    KnowledgeStore store;

    @Autowired
    KnowledgeReviewStore reviews;

    private String publishable(String body) {
        var result = ingestion.ingest(new IngestCommand("rv", "正念行走", "测试来源", null, "zh", true, body));
        store.updateDocumentStatus(result.documentId(), KnowledgeDocument.REVIEWING);
        return result.documentId();
    }

    @Test
    void reviewRecordsVersionReviewerAndAclSnapshot() {
        String docId = publishable("# 正念行走\n\n正念行走要求把注意力放在脚底与地面的接触上。");

        var body = controller.review(docId, new KnowledgeController.ReviewRequest("approve", "李医生", "这一版可以发布"), null).getBody();

        var review = (KnowledgeReviewStore.DocReview) body.get("review");
        assertEquals("v1", review.version(), "签字必须落在具体版本上");
        assertEquals("李医生", review.reviewer());
        assertNull(review.aclScope(), "当时是公开的，快照就应当是空，而不是补一个默认值");
        assertEquals(KnowledgeDocument.PUBLISHED, body.get("status"));

        List<KnowledgeReviewStore.DocReview> history = reviews.forDocument(docId);
        assertEquals(1, history.size());
        assertEquals("approve", history.get(0).decision());
    }

    @Test
    void aclChangeIsNotBackfilledIntoTheSignature() {
        String docId = publishable("# 正念饮食\n\n正念饮食要求放慢速度，注意味道与饱腹感的变化。");

        controller.review(docId, new KnowledgeController.ReviewRequest("approve", "王医生", null), null);

        // 只改权限、不重新签字：记录里的快照必须保持原样。
        // 这条是"快照"与"关联查询当前值"的分界线——后者会让历史签字随权限漂移。
        store.updateAcl(docId, "team:cbt");
        assertNull(reviews.forDocument(docId).get(0).aclScope(),
                "改权限本身不能改写已有签字；要改签字就得重新审一次");

        // 重新签字：同一版本覆盖为最新结论，快照取那一刻的范围。
        controller.review(docId, new KnowledgeController.ReviewRequest("approve", "王医生", "改为内部团队可见"), null);
        List<KnowledgeReviewStore.DocReview> history = reviews.forDocument(docId);
        assertEquals(1, history.size(), "同一版本只保留一条结论：重审是覆盖，不是追加");
        assertEquals("team:cbt", history.get(0).aclScope());
    }

    @Test
    void blankReviewerIsRecordedAsUnsignedRatherThanInvented() {
        String docId = publishable("# 正念倾听\n\n正念倾听要求在对方说话时不急着给建议。");

        var body = controller.review(docId, new KnowledgeController.ReviewRequest("review", "  ", "先转待审"), null).getBody();

        var review = (KnowledgeReviewStore.DocReview) body.get("review");
        assertNull(review.reviewer(), "没有署名就存空，页面显示「未署名」，不能替人编一个名字");
    }

    @Test
    void newVersionNeedsItsOwnSignature() {
        String key = "rv-version-test";
        SyncResult created = sync.sync(key, new IngestCommand("rv", "正念呼吸", "测试来源", null, "zh", true,
                "# 正念呼吸\n\n把注意力放在呼吸上，走神时温和地带回来。"));
        String docId = created.documentId();
        controller.review(docId, new KnowledgeController.ReviewRequest("approve", "赵医生", null), null);

        sync.sync(key, new IngestCommand("rv", "正念呼吸", "测试来源", null, "zh", true,
                "# 正念呼吸\n\n把注意力放在呼吸上，走神时温和地带回来。\n\n## 常见困难\n\n困倦时可以睁眼练习。"));

        List<KnowledgeReviewStore.DocReview> history = reviews.forDocument(docId);
        assertEquals(1, history.size(), "旧签字只对 v1 有效");
        assertEquals("v1", history.get(0).version());
        assertTrue(reviews.find(docId, "v2") == null, "v2 没有被审过——这正是它应当回到待审的原因");
    }
}
