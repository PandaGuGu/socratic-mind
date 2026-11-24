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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 窗外翻页与跨会话情绪趋势。
 *
 * 两条边界：
 * 1. 翻页不能重复、不能漏——用 (createdAt, id) 游标而不是 offset；
 * 2. 趋势只统计"本人看得到的会话"，别人设备上点过的分不许混进来。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionPagingTest {

    private static final String TOKEN = "X-User-Token";

    @Autowired
    TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void pagesDoNotRepeatOrDrop() {
        String device = startSession();
        startSession(device);
        startSession(device);

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        for (int i = 0; i < 10; i++) {
            String path = "/api/cbt/sessions/page?limit=1" + (cursor == null ? "" : "&cursor=" + cursor);
            Map<String, Object> page = getOk(path, device);
            List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
            assertFalse(items.isEmpty(), "还有下一页时不该返回空页");
            for (Map<String, Object> item : items) {
                assertTrue(seen.add((String) item.get("sessionId")), "同一条会话被翻出来两次");
            }
            pages++;
            if (!Boolean.TRUE.equals(page.get("hasMore"))) break;
            cursor = (String) page.get("nextCursor");
            assertNotNull(cursor);
        }
        assertEquals(3, seen.size(), "三页应当正好覆盖三条会话");
        assertEquals(3, pages);
    }

    @Test
    void pageRequiresIdentityAndRejectsBadCursor() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                rest.exchange("/api/cbt/sessions/page", HttpMethod.GET,
                        new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode());

        String device = startSession();
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                rest.exchange("/api/cbt/sessions/page?cursor=not-a-cursor", HttpMethod.GET,
                        new HttpEntity<>(headers(device)), Map.class).getStatusCode(),
                "无效游标应当是 422，而不是静默回到第一页");
    }

    @Test
    @SuppressWarnings("unchecked")
    void moodTrendAggregatesOnlyVisibleSessions() {
        String device = startSession();
        String session = firstSessionId(device);
        assertEquals(HttpStatus.OK, post("/api/cbt/sessions/" + session + "/mood", device, "{\"score\":2}").getStatusCode());
        assertEquals(HttpStatus.OK, post("/api/cbt/sessions/" + session + "/mood", device, "{\"score\":8}").getStatusCode());

        // 另一台设备的自评不能混进来
        String other = startSession();
        post("/api/cbt/sessions/" + firstSessionId(other) + "/mood", other, "{\"score\":10}");

        Map<String, Object> trend = getOk("/api/cbt/mood/trend?days=30", device);
        assertEquals(2, ((Number) trend.get("points")).intValue());
        assertEquals(1, ((Number) trend.get("sessions")).intValue());
        List<Map<String, Object>> daily = (List<Map<String, Object>>) trend.get("daily");
        assertEquals(1, daily.size());
        assertEquals(5.0, ((Number) daily.get(0).get("average")).doubleValue(), 0.001);
        assertEquals(2, ((Number) daily.get(0).get("min")).intValue());
        assertEquals(8, ((Number) daily.get(0).get("max")).intValue());
    }

    @SuppressWarnings("unchecked")
    private String firstSessionId(String token) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) getOk(
                "/api/cbt/sessions/page?limit=1", token).get("items");
        assertFalse(items.isEmpty());
        return (String) items.get(0).get("sessionId");
    }

    // ---------- helpers ----------

    private String startSession() {
        return startSession(null);
    }

    private String startSession(String token) {
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> started = rest.exchange("/api/cbt/sessions", HttpMethod.POST,
                new HttpEntity<>("{}", h), Map.class);
        assertEquals(HttpStatus.OK, started.getStatusCode());
        String issued = started.getHeaders().getFirst(TOKEN);
        return issued == null ? token : issued;
    }

    private ResponseEntity<Map> post(String path, String token, String json) {
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOk(String path, String token) {
        ResponseEntity<Map> res = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), path);
        assertNotNull(res.getBody());
        return res.getBody();
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set(TOKEN, token);
        return h;
    }
}
