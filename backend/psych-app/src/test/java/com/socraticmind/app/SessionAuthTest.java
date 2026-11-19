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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话归属：没有令牌、拿别人的令牌、伪造令牌，都必须读不到。
 *
 * 这是 v0.1.1 修掉的那个洞的回归测试——此前接口无鉴权、id 自增，
 * 按序枚举就能读到任何人的情境与想法。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionAuthTest {

    private static final String TOKEN = "X-User-Token";

    @Autowired
    TestRestTemplate rest;

    @Test
    void startIssuesTokenAndRequiresItAfterwards() {
        ResponseEntity<Map> started = post("/api/cbt/sessions", null, "{}");
        assertEquals(HttpStatus.OK, started.getStatusCode());
        String token = started.getHeaders().getFirst(TOKEN);
        assertNotNull(token, "首次开口必须签发身份令牌");
        String id = (String) started.getBody().get("sessionId");

        // 不带令牌推进：401
        ResponseEntity<Map> anonymous = post("/api/cbt/sessions/" + id + "/messages", null,
                "{\"message\":\"你好\"}");
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.getStatusCode());

        // 伪造令牌：同样 401
        ResponseEntity<Map> forged = post("/api/cbt/sessions/" + id + "/messages", "v1.YWJj.ZGVm",
                "{\"message\":\"你好\"}");
        assertEquals(HttpStatus.UNAUTHORIZED, forged.getStatusCode());

        // 带自己的令牌：正常推进
        ResponseEntity<Map> mine = post("/api/cbt/sessions/" + id + "/messages", token,
                "{\"message\":\"下周汇报，一想到就手心出汗\"}");
        assertEquals(HttpStatus.OK, mine.getStatusCode());
    }

    @Test
    void otherUsersSessionLooksLikeItDoesNotExist() {
        String tokenA = startSessionForNewUser();
        String idA = lastSessionId(tokenA);

        String tokenB = startSessionForNewUser();

        ResponseEntity<Map> read = get("/api/cbt/sessions/" + idA, tokenB, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, read.getStatusCode(), "别人的会话应当表现为不存在");

        ResponseEntity<Map> history = get("/api/cbt/sessions/" + idA + "/history", tokenB, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, history.getStatusCode());

        ResponseEntity<Map> write = post("/api/cbt/sessions/" + idA + "/messages", tokenB,
                "{\"message\":\"我想看看别人说了什么\"}");
        assertEquals(HttpStatus.NOT_FOUND, write.getStatusCode());

        // 自己的令牌仍然可以读
        ResponseEntity<Map> own = get("/api/cbt/sessions/" + idA, tokenA, Map.class);
        assertEquals(HttpStatus.OK, own.getStatusCode());
    }

    @Test
    void listOnlyReturnsOwnSessions() {
        String tokenA = startSessionForNewUser();
        startSessionForNewUser();

        ResponseEntity<List> mine = rest.exchange("/api/cbt/sessions", HttpMethod.GET,
                new HttpEntity<>(headers(tokenA)), List.class);
        assertEquals(HttpStatus.OK, mine.getStatusCode());
        assertNotNull(mine.getBody());
        assertTrue(mine.getBody().size() >= 1);

        // 不带 userId 也只会拿到自己的：旧实现这里会返回全库
        ResponseEntity<String> anonymous = rest.getForEntity("/api/cbt/sessions", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.getStatusCode());
    }

    @Test
    void startReusesIdentityWhenTokenPresent() {
        String token = startSessionForNewUser();
        ResponseEntity<Map> second = post("/api/cbt/sessions", token, "{}");
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertEquals(token, second.getHeaders().getFirst(TOKEN), "带令牌再开一轮不应换人");
    }

    private String startSessionForNewUser() {
        ResponseEntity<Map> started = post("/api/cbt/sessions", null, "{}");
        assertEquals(HttpStatus.OK, started.getStatusCode());
        return started.getHeaders().getFirst(TOKEN);
    }

    @SuppressWarnings("unchecked")
    private String lastSessionId(String token) {
        ResponseEntity<List> sessions = rest.exchange("/api/cbt/sessions", HttpMethod.GET,
                new HttpEntity<>(headers(token)), List.class);
        assertEquals(HttpStatus.OK, sessions.getStatusCode());
        List<Map<String, Object>> rows = sessions.getBody();
        assertNotNull(rows);
        assertTrue(!rows.isEmpty());
        return (String) rows.get(0).get("sessionId");
    }

    private ResponseEntity<Map> post(String path, String token, String json) {
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, h), Map.class);
    }

    private <T> ResponseEntity<T> get(String path, String token, Class<T> type) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), type);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set(TOKEN, token);
        return h;
    }
}
