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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库后台的两条边界：
 * 1. 切分预览是管理端视图，必须持钥，且只读；
 * 2. 审核动作能把文档从"待审核"推进到"可检索"。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "psych.internal.enabled=true",
                "psych.internal.token=test-internal-token"
        })
class KnowledgeAdminTest {

    private static final String TOKEN = "test-internal-token";
    private static final String HEADER = "X-Internal-Token";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    IngestionService ingestion;

    @Autowired
    KnowledgeStore store;

    @Test
    @SuppressWarnings("rawtypes")
    void chunksPreviewRequiresTokenAndReturnsParentsAndChildren() {
        String probe = "切分预览探针" + System.nanoTime();
        IngestResult result = ingestion.ingest(new IngestCommand("test", "管理端测试：" + probe,
                "单元测试", null, "zh", false,
                "# " + probe + "\n\n## 第一节\n\n这是用于验证切分预览的正文，包含关键词 " + probe + "。\n\n## 第二节\n\n第二节正文。"));

        String path = "/api/knowledge/documents/" + result.documentId() + "/chunks";

        ResponseEntity<String> anonymous = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.getStatusCode(), "切分预览不得匿名读取");

        ResponseEntity<List> ok = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers()), List.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertNotNull(ok.getBody());
        assertEquals(result.parentChunks() + result.childChunks(), ok.getBody().size());

        boolean hasParent = ok.getBody().stream()
                .anyMatch(o -> Boolean.TRUE.equals(((Map) o).get("parent")));
        boolean hasChild = ok.getBody().stream()
                .anyMatch(o -> Boolean.FALSE.equals(((Map) o).get("parent")));
        boolean containsProbe = ok.getBody().stream()
                .anyMatch(o -> String.valueOf(((Map) o).get("content")).contains(probe));

        assertTrue(hasParent, "应包含父块");
        assertTrue(hasChild, "应包含子块");
        assertTrue(containsProbe, "子块内容应保留原文");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void reviewEndpointPublishesAndMakesDocumentSearchable() {
        String probe = "审核发布探针" + System.nanoTime();
        IngestResult result = ingestion.ingest(new IngestCommand("test", "管理端测试：" + probe,
                "单元测试", null, "zh", true,
                "# " + probe + "\n\n## 唯一章节\n\n这段文字只在审核通过后才应被检索到，关键词是 " + probe + "。"));
        assertEquals(KnowledgeDocument.REVIEWING, result.status());

        ResponseEntity<Map> res = rest.exchange(
                "/api/knowledge/documents/" + result.documentId() + "/review",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("decision", "approve"), jsonHeaders()),
                Map.class);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(KnowledgeDocument.PUBLISHED, res.getBody().get("status"));

        List<RetrievedChunk> hits = store.searchText(probe, 10, null);
        assertTrue(hits.stream().anyMatch(h -> h.chunk().documentId().equals(result.documentId())),
                "审核通过后应进入检索候选集");
    }

    private static HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.set(HEADER, TOKEN);
        return h;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = headers();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
