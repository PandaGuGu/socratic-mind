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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台改规则与参数的接口。
 *
 * 两条边界要同时成立：
 * 1. 有令牌能改（加词会通过，版本会前进，历史能回滚）；
 * 2. 没令牌改不了，且"改坏"会被服务端拒绝——不是前端不让你点，而是后端不接受。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "psych.internal.enabled=true",
                "psych.internal.token=test-internal-token",
                "psych.safety.rules-file=target/test-config/safety-rules.json",
                "psych.limits.file=target/test-config/limits.json",
                "psych.flow.params-file=target/test-config/flow-params.json",
                "psych.config.history-dir=target/test-config/history"
        })
class SettingsApiTest {

    private static final String TOKEN = "X-Internal-Token";
    private static final String AUTH = "test-internal-token";

    @Autowired
    TestRestTemplate rest;

    @Test
    void safetyRulesRequireTokenAndRejectWeakening() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/internal/safety/rules", null).getStatusCode());

        Map<String, Object> view = getMap("/api/internal/safety/rules");
        Map<String, Object> definition = asMap(view.get("definition"));
        assertEquals("hold", rule(definition, "self_harm").get("level"));

        // 改窄：拒绝
        Map<String, Object> narrowed = copy(definition);
        rule(narrowed, "self_harm").put("pattern", "只有这句话才算|zzz");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, put("/api/internal/safety/rules", AUTH, narrowed).getStatusCode());

        // 加变体：接受，版本前进
        Map<String, Object> widened = copy(definition);
        String original = String.valueOf(rule(widened, "self_harm").get("pattern"));
        rule(widened, "self_harm").put("pattern", original + "|想不开");
        ResponseEntity<Map> saved = put("/api/internal/safety/rules", AUTH, widened);
        assertEquals(HttpStatus.OK, saved.getStatusCode());
        assertNotNull(saved.getBody());
        assertTrue(((Number) saved.getBody().get("version")).intValue() > 1,
                "保存成功必须推进版本：" + saved.getBody().get("version"));
    }

    @Test
    void draftValidationExplainsWhyItWouldFail() {
        Map<String, Object> view = getMap("/api/internal/safety/rules");
        Map<String, Object> definition = copy(asMap(view.get("definition")));
        rule(definition, "self_harm").put("pattern", "(");

        ResponseEntity<Map> result = post("/api/internal/safety/rules/validate", AUTH, definition);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(Boolean.FALSE, result.getBody().get("ok"));
        List<?> errors = (List<?>) result.getBody().get("errors");
        assertFalse(errors.isEmpty());
        assertTrue(String.valueOf(errors).contains("self_harm"));
    }

    @Test
    void canCreateAndDeleteRulesFromTheConsole() {
        Map<String, Object> definition = copy(asMap(getMap("/api/internal/safety/rules").get("definition")));

        // 新增一条运维自建规则
        ((List<Object>) definition.get("rules")).add(Map.of(
                "id", "local_slang", "label", "本地口语", "level", "hold",
                "pattern", "扛不住了想走", "enabled", true, "note", "运维自建"));
        ResponseEntity<Map> added = put("/api/internal/safety/rules", AUTH, definition);
        assertEquals(HttpStatus.OK, added.getStatusCode(), String.valueOf(added.getBody()));

        // 再删掉它
        Map<String, Object> next = copy(asMap(getMap("/api/internal/safety/rules").get("definition")));
        ((List<Object>) next.get("rules")).removeIf(r -> "local_slang".equals(cast(r).get("id")));
        ResponseEntity<Map> deleted = put("/api/internal/safety/rules", AUTH, next);
        assertEquals(HttpStatus.OK, deleted.getStatusCode(), String.valueOf(deleted.getBody()));
    }

    @Test
    void caseTableIsEditableButCannotBeEmptied() {
        Map<String, Object> definition = copy(asMap(getMap("/api/internal/safety/rules").get("definition")));

        // 加一条用例：允许
        ((List<Object>) definition.get("cases")).add(Map.of(
                "text", "我真的不想活了", "expect", "HOLD", "why", "运维补充"));
        assertEquals(HttpStatus.OK, put("/api/internal/safety/rules", AUTH, definition).getStatusCode());

        // 清空用例：拒绝
        Map<String, Object> empty = copy(asMap(getMap("/api/internal/safety/rules").get("definition")));
        ((List<Object>) empty.get("cases")).clear();
        ResponseEntity<Map> rejected = put("/api/internal/safety/rules", AUTH, empty);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatusCode());
        assertTrue(String.valueOf(rejected.getBody()).contains("用例"), String.valueOf(rejected.getBody()));
    }

    @Test
    void limitsAcceptSaneValuesAndRejectZero() {
        ResponseEntity<Map> bad = put("/api/internal/limits", AUTH, Map.of(
                "maxMessageChars", 2000, "messagesPerMinute", 0, "newSessionsPerHour", 30));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, bad.getStatusCode());

        ResponseEntity<Map> ok = put("/api/internal/limits", AUTH, Map.of(
                "maxMessageChars", 1200, "messagesPerMinute", 45, "newSessionsPerHour", 20));
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertEquals(45, ok.getBody().get("messagesPerMinute"));

        Map<String, Object> view = getMap("/api/internal/limits");
        assertEquals(45, asMap(view.get("limits")).get("messagesPerMinute"));
    }

    @Test
    void historyShowsWhoAndWhenAndEveryVersionIsReadable() {
        // 原样保存一次，确保至少有一个快照（内容不变也允许保存）
        Map<String, Object> definition = copy(asMap(getMap("/api/internal/safety/rules").get("definition")));
        definition.put("updatedBy", "tester");
        assertEquals(HttpStatus.OK, put("/api/internal/safety/rules", AUTH, definition).getStatusCode());

        Map<String, Object> history = getMap("/api/internal/config/history");
        List<?> safety = (List<?>) history.get("safety");
        assertTrue(!safety.isEmpty(), "保存后必须有历史");

        Map<String, Object> latest = cast(safety.get(0));
        assertNotNull(latest.get("version"));
        assertNotNull(latest.get("updatedAt"));
        assertEquals("tester", latest.get("updatedBy"));

        int version = ((Number) latest.get("version")).intValue();
        Map<String, Object> snap = getMap("/api/internal/config/snapshot?kind=safety&version=" + version);
        assertEquals(version, snap.get("version"));
        assertNotNull(snap.get("value"));
        assertNotNull(snap.get("current"));

        ResponseEntity<Map> rolled = post("/api/internal/config/rollback", AUTH,
                Map.of("kind", "safety", "version", version));
        assertEquals(HttpStatus.OK, rolled.getStatusCode());
        assertNotNull(rolled.getBody());
        assertEquals(version, rolled.getBody().get("rolledBackTo"));
    }

    @Test
    void flowHistoryExistsForCanvasChanges() {
        Map<String, Object> history = getMap("/api/internal/config/history");
        assertTrue(history.containsKey("flow"));
        assertTrue(history.containsKey("limits"));
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> asMap(Object value) {
        assertTrue(value instanceof Map, "期望对象，实际 " + value);
        return cast(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>(source);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Object r : (List<?>) source.get("rules")) {
            rules.add(new LinkedHashMap<>(cast(r)));
        }
        out.put("rules", rules);

        List<Map<String, Object>> cases = new ArrayList<>();
        for (Object c : (List<?>) source.get("cases")) {
            cases.add(new LinkedHashMap<>(cast(c)));
        }
        out.put("cases", cases);
        return out;
    }

    private Map<String, Object> rule(Map<String, Object> definition, String id) {
        for (Object r : (List<?>) definition.get("rules")) {
            Map<String, Object> map = cast(r);
            if (id.equals(map.get("id"))) return map;
        }
        throw new AssertionError("没有这条规则：" + id);
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(String path) {
        ResponseEntity<Map> res = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(AUTH)), Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), "GET " + path);
        return res.getBody();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> put(String path, String token, Object body) {
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Internal-Actor", "tester");
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String path, String token, Object body) {
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set(TOKEN, token);
        return h;
    }
}
