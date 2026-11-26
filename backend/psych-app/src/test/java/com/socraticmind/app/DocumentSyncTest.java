package com.socraticmind.app;

import com.socraticmind.common.model.KnowledgeDocument;
import com.socraticmind.common.port.KnowledgeStore;
import com.socraticmind.knowledge.dto.IngestCommand;
import com.socraticmind.knowledge.dto.SyncResult;
import com.socraticmind.knowledge.service.DocumentSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档生命周期：同步去重、改版重审、软删除、物理清理。
 *
 * 每一条都对应一类"看起来正常但是错的"线上事故：
 * 同一篇资料出现两条记录、内容改了还在用旧块、删了还能检索到、删错了捞不回来。
 */
@SpringBootTest
class DocumentSyncTest {

    @Autowired
    DocumentSyncService sync;

    @Autowired
    KnowledgeStore store;

    private static IngestCommand cmd(String title, String content) {
        return new IngestCommand("test", title, "测试来源", null, "zh", true, content);
    }

    @Test
    void syncIsIdempotentWhenContentUnchanged() {
        String key = "ticket-1001";
        String body = "# 箱庭疗法\n\n箱庭疗法使用沙盘与微缩模型，适合不擅长言语表达的来访者。";

        SyncResult first = sync.sync(key, cmd("箱庭疗法简介", body));
        assertEquals("created", first.action());
        assertTrue(first.needsReview(), "首次同步也要过审核：外部系统说的话不等于可信证据");

        SyncResult again = sync.sync(key, cmd("箱庭疗法简介", body));
        assertEquals("unchanged", again.action());
        assertFalse(again.changed(), "内容没变就不能重做切分与向量化");
        assertEquals(first.documentId(), again.documentId(), "同一外部键必须是同一篇文档");
    }

    @Test
    void contentChangeRebuildsChunksAndRequiresReview() {
        String key = "ticket-1002";
        SyncResult created = sync.sync(key, cmd("正念呼吸",
                "# 正念呼吸\n\n正念呼吸要求把注意力放在呼吸上，走神时温和地带回来。"));

        String docId = created.documentId();
        store.updateDocumentStatus(docId, KnowledgeDocument.PUBLISHED);

        SyncResult updated = sync.sync(key, cmd("正念呼吸",
                "# 正念呼吸\n\n正念呼吸要求把注意力放在呼吸上，走神时温和地带回来。\n\n"
                        + "## 常见困难\n\n困倦和烦躁都很常见，可以改为睁眼练习或缩短时长。"));

        assertEquals("updated", updated.action());
        assertEquals("v2", updated.version());
        assertTrue(updated.needsReview());
        assertEquals(KnowledgeDocument.REVIEWING, updated.status(),
                "内容变了必须回到待审核：旧签字是对旧文本签的");

        List<String> versions = store.listChunks(docId).stream().map(c -> c.documentVersionId()).distinct().toList();
        assertEquals(List.of(docId + "_v2"), versions, "旧块必须清掉，否则会新旧同时在索引里");
    }

    @Test
    void retiredDocumentDisappearsFromRetrievalButKeepsRow() {
        String key = "ticket-1003";
        SyncResult created = sync.sync(key, cmd("沙盘游戏",
                "# 沙盘游戏\n\n沙盘游戏让来访者在沙箱中摆放微缩模型，用空间 arrangement 表达内在状态。"));
        store.updateDocumentStatus(created.documentId(), KnowledgeDocument.PUBLISHED);

        assertTrue(store.findVisibleDocumentIds(null).contains(created.documentId()));

        assertTrue(sync.retire(key).isPresent());
        KnowledgeDocument doc = store.findDocument(created.documentId()).orElseThrow();
        assertTrue(doc.deleted(), "软删除要留下 deletedAt，才可能恢复");
        assertEquals(KnowledgeDocument.ARCHIVED, doc.status());
        assertFalse(store.findVisibleDocumentIds(null).contains(created.documentId()),
                "已删除的文档不能出现在检索候选里");

        int purged = sync.purge(0);
        assertTrue(purged >= 1);
        assertTrue(store.findDocument(created.documentId()).isEmpty(), "物理清理后行也要消失");
    }
}
