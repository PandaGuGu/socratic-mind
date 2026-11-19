package com.socraticmind.app;

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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 内部接口必须有钥匙。
 *
 * 旧实现里 /api/internal/** 是开放的：谁能访问到端口，谁就能把输出策略里的禁句删空、
 * 把流程的确认节点改掉。这里把"无钥匙 → 401"钉住。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "psych.internal.enabled=true",
                "psych.internal.token=test-internal-token"
        })
class InternalAuthTest {

    private static final String HEADER = "X-Internal-Token";

    @Autowired
    TestRestTemplate rest;

    @Test
    void overviewRequiresToken() {
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/internal/overview", null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/internal/overview", "wrong").getStatusCode());
        assertEquals(HttpStatus.OK, get("/api/internal/overview", "test-internal-token").getStatusCode());
    }

    @Test
    void knowledgeWritesRequireToken() {
        String body = "{\"title\":\"越权测试\",\"content\":\"匿名不该能写证据库\",\"publish\":true}";

        assertEquals(HttpStatus.UNAUTHORIZED, post("/api/knowledge/documents", null, body).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, post("/api/knowledge/documents", "wrong", body).getStatusCode());

        ResponseEntity<Map> ok = post("/api/knowledge/documents", "test-internal-token", body);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertNotNull(ok.getBody());
        assertEquals("reviewing", ok.getBody().get("status"), "带令牌入库也只是提交审核");
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(String path, String token, String json) {
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, h), Map.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set(HEADER, token);
        return h;
    }
}
