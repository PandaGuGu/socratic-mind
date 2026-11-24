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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次性分享链接：能看一次、不能重复看、本人能撤回、别人不能替别人生成。
 *
 * 一次性不是文案：两个请求同时打开时只能有一个成功（服务端 markUsed 是原子的）。
 * 这里串行验证"第二次 410"，并发那条由存储层的条件 UPDATE 保证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShareLinkTest {

    private static final String TOKEN = "X-User-Token";

    @Autowired
    TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void linkCanBeOpenedOnce() {
        String device = startSession();
        String sessionId = firstSessionId(device);

        ResponseEntity<Map> shared = post("/api/cbt/sessions/" + sessionId + "/share", device,
                "{\"ttlHours\":1}");
        assertEquals(HttpStatus.OK, shared.getStatusCode(), String.valueOf(shared.getBody()));
        String path = (String) shared.getBody().get("path");
        assertNotNull(path);
        assertTrue(path.startsWith("/share/?t="));
        String shareToken = path.substring("/share/?t=".length());
        assertNotNull(shared.getBody().get("expiresAt"));

        // 不需要登录也能看
        ResponseEntity<Map> opened = rest.exchange("/api/share/" + shareToken, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertEquals(HttpStatus.OK, opened.getStatusCode(), String.valueOf(opened.getBody()));
        Map<String, Object> session = (Map<String, Object>) opened.getBody().get("session");
        assertNotNull(session);
        assertFalse(session.containsKey("sessionId"), "公开记录不暴露可枚举的会话 id");
        assertFalse(session.containsKey("contactPhone"), "分享默认不带联系人电话");

        // 第二次：410
        assertEquals(HttpStatus.GONE, rest.exchange("/api/share/" + shareToken, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode());
    }

    @Test
    void onlyOwnerCanShareAndRevoke() {
        String owner = startSession();
        String sessionId = firstSessionId(owner);
        String stranger = startSession();

        assertEquals(HttpStatus.NOT_FOUND, post("/api/cbt/sessions/" + sessionId + "/share", stranger, "{}")
                .getStatusCode());

        ResponseEntity<Map> shared = post("/api/cbt/sessions/" + sessionId + "/share", owner, "{}");
        assertEquals(HttpStatus.OK, shared.getStatusCode());
        String shareToken = ((String) shared.getBody().get("path")).substring("/share/?t=".length());

        assertEquals(HttpStatus.NOT_FOUND,
                rest.exchange("/api/cbt/sessions/" + sessionId + "/shares", HttpMethod.DELETE,
                        new HttpEntity<>(headers(stranger)), Map.class).getStatusCode());

        ResponseEntity<Map> revoked = rest.exchange("/api/cbt/sessions/" + sessionId + "/shares",
                HttpMethod.DELETE, new HttpEntity<>(headers(owner)), Map.class);
        assertEquals(HttpStatus.OK, revoked.getStatusCode());
        assertEquals(1, ((Number) revoked.getBody().get("revoked")).intValue());

        assertEquals(HttpStatus.GONE, rest.exchange("/api/share/" + shareToken, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode());
    }

    @Test
    void accountTokenCanShareItsClaimedSession() {
        String device = startSession();
        String sessionId = firstSessionId(device);
        String email = "share-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        ResponseEntity<Map> registered = post("/api/auth/register", device,
                "{\"email\":\"" + email + "\",\"password\":\"share-password\"}");
        String accountToken = registered.getHeaders().getFirst(TOKEN);
        post("/api/auth/claim", accountToken, "{}");

        assertEquals(HttpStatus.OK, post("/api/cbt/sessions/" + sessionId + "/share", accountToken, "{}")
                .getStatusCode());
    }

    // ---------- helpers ----------

    private String startSession() {
        ResponseEntity<Map> started = post("/api/cbt/sessions", null, "{}");
        assertEquals(HttpStatus.OK, started.getStatusCode());
        return started.getHeaders().getFirst(TOKEN);
    }

    private String firstSessionId(String token) {
        ResponseEntity<Map[]> list = rest.exchange("/api/cbt/sessions", HttpMethod.GET,
                new HttpEntity<>(headers(token)), Map[].class);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertNotNull(list.getBody());
        assertTrue(list.getBody().length > 0);
        return (String) list.getBody()[0].get("sessionId");
    }

    private ResponseEntity<Map> post(String path, String token, String json) {
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, h), Map.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set(TOKEN, token);
        return h;
    }
}
