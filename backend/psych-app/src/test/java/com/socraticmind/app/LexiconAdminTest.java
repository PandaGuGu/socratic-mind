package com.socraticmind.app;

import com.socraticmind.common.guard.SensitiveLexicon;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词库后台：能改、改完真的生效、改坏会被拒。
 *
 * 最后一条是最重要的：把 block 词删光、把范围全改成 out，页面看起来都能保存成功，
 * 但输入侧会变成不设防。这里逐条证明服务端不接受那两种草稿。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "psych.internal.enabled=true",
                "psych.internal.token=test-internal-token",
                "psych.lexicon.file=target/test-lexicon/lexicon.txt",
                "psych.config.history-dir=target/test-lexicon/history"
        })
class LexiconAdminTest {

    private static final String INTERNAL = "X-Internal-Token";
    private static final String INTERNAL_VALUE = "test-internal-token";
    private static final String USER = "X-User-Token";

    @Autowired
    TestRestTemplate rest;

    /**
     * 上一次跑留下的文件会被 LexiconStore 在启动时加载，词条里可能已经有"代开发票"。
     * 先删文件、再装回基线，让这组用例从同一个起点开始（版本号不回退，只影响显示）。
     */
    @BeforeAll
    static void cleanSlate() throws Exception {
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("target/test-lexicon/lexicon.txt"));
        SensitiveLexicon.install(SensitiveLexicon.builtIn());
    }

    /** 词库是进程级单例：这一组用例改了它，跑完还原，避免影响别的测试类。 */
    @AfterAll
    static void restoreBuiltIn() {
        SensitiveLexicon.install(SensitiveLexicon.builtIn());
    }

    @Test
    void requiresInternalToken() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                rest.exchange("/api/internal/lexicon", HttpMethod.GET,
                        new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void editsTakeEffectInTheFlow() {
        Map<String, Object> view = internalGet();
        List<Map<String, Object>> entries = new ArrayList<>((List<Map<String, Object>>) view.get("entries"));
        assertTrue(entries.size() >= 10);

        // 先确认这个词原本不拦
        assertFalse(test("想找人代开发票").get("blocked").equals(Boolean.TRUE));

        entries.add(Map.of("scope", "in", "category", "违规广告", "action", "block", "word", "代开发票"));
        ResponseEntity<Map> saved = put(entries);
        assertEquals(HttpStatus.OK, saved.getStatusCode(), String.valueOf(saved.getBody()));
        assertNotNull(saved.getBody());
        assertTrue(((Number) saved.getBody().get("version")).intValue() >= 1);
        assertEquals(Boolean.TRUE, saved.getBody().get("persisted"));

        // 接口立刻生效
        assertTrue(test("想找人代开发票").get("blocked").equals(Boolean.TRUE));

        // 真实会话也立刻生效：不是"后台显示成功"，而是下一句话就被拦下
        String token = startSession();
        String sessionId = (String) lastSession(token).get("sessionId");
        ResponseEntity<Map> blocked = post("/api/cbt/sessions/" + sessionId + "/messages", token,
                "{\"message\":\"想找人代开发票\"}");
        assertEquals(HttpStatus.OK, blocked.getStatusCode());
        assertEquals("content_blocked", blocked.getBody().get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollbackIsANewSaveNotARewrite() {
        Map<String, Object> view = internalGet();
        List<Map<String, Object>> entries = new ArrayList<>((List<Map<String, Object>>) view.get("entries"));
        String marker = "回滚标记词";
        entries.add(Map.of("scope", "in", "category", "测试", "action", "block", "word", marker));

        ResponseEntity<Map> saved = put(entries);
        assertEquals(HttpStatus.OK, saved.getStatusCode());
        int markedVersion = ((Number) saved.getBody().get("version")).intValue();
        assertEquals(Boolean.TRUE, test(marker).get("blocked"));

        // 恢复基线 → 标记词消失
        ResponseEntity<Map> reset = internalPost("/api/internal/lexicon/reset", Map.of());
        assertEquals(HttpStatus.OK, reset.getStatusCode());
        assertEquals(Boolean.FALSE, test(marker).get("blocked"));

        // 回滚到带标记的那一版 → 标记词回来，且生成的是"新版本"，不是回到旧编号
        ResponseEntity<Map> rolled = internalPost("/api/internal/lexicon/rollback", Map.of("version", markedVersion));
        assertEquals(HttpStatus.OK, rolled.getStatusCode());
        assertEquals(Boolean.TRUE, test(marker).get("blocked"));
        assertTrue(((Number) rolled.getBody().get("version")).intValue() > markedVersion,
                "回滚必须是新版本，历史只追加不改写");
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesDraftsThatTurnTheFilterOff() {
        Map<String, Object> view = internalGet();
        List<Map<String, Object>> entries = new ArrayList<>((List<Map<String, Object>>) view.get("entries"));

        // 1. 把 block 删光
        List<Map<String, Object>> noBlock = entries.stream()
                .map(e -> "BLOCK".equals(e.get("action"))
                        ? Map.of("scope", e.get("scope"), "category", e.get("category"),
                        "action", "review", "word", e.get("word"))
                        : e)
                .toList();
        ResponseEntity<Map> rejected = put(noBlock);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatusCode());
        assertTrue(String.valueOf(rejected.getBody().get("message")).contains("block"));

        // 2. 把输入侧关掉：全改成 out
        List<Map<String, Object>> outOnly = entries.stream()
                .map(e -> Map.of("scope", "out", "category", e.get("category"),
                        "action", e.get("action"), "word", e.get("word")))
                .toList();
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, put(outOnly).getStatusCode());

        // 3. 删到只剩几条
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                put(entries.subList(0, 3)).getStatusCode());

        // 4. 重复词条
        List<Map<String, Object>> duplicated = new ArrayList<>(entries);
        duplicated.add(entries.get(0));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, put(duplicated).getStatusCode());
    }

    // ---------- helpers ----------

    private Map<String, Object> internalGet() {
        ResponseEntity<Map> res = rest.exchange("/api/internal/lexicon", HttpMethod.GET,
                new HttpEntity<>(internalHeaders()), Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        return res.getBody();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> internalPost(String path, Map<String, Object> body) {
        HttpHeaders h = internalHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<Map> put(List<Map<String, Object>> entries) {
        return rest.exchange("/api/internal/lexicon", HttpMethod.PUT,
                new HttpEntity<>(Map.of("entries", entries, "updatedBy", "tester"), internalHeaders()),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> test(String text) {
        ResponseEntity<Map> res = rest.exchange("/api/internal/lexicon/test", HttpMethod.POST,
                new HttpEntity<>(Map.of("text", text, "side", "in"), internalHeaders()), Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        return res.getBody();
    }

    private String startSession() {
        ResponseEntity<Map> started = post("/api/cbt/sessions", null, "{}");
        assertEquals(HttpStatus.OK, started.getStatusCode());
        return started.getHeaders().getFirst(USER);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastSession(String token) {
        ResponseEntity<Map[]> list = rest.exchange("/api/cbt/sessions", HttpMethod.GET,
                new HttpEntity<>(userHeaders(token)), Map[].class);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertNotNull(list.getBody());
        assertTrue(list.getBody().length > 0);
        return list.getBody()[0];
    }

    private ResponseEntity<Map> post(String path, String token, String json) {
        HttpHeaders h = token == null ? new HttpHeaders() : userHeaders(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, h), Map.class);
    }

    private static HttpHeaders internalHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set(INTERNAL, INTERNAL_VALUE);
        return h;
    }

    private static HttpHeaders userHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set(USER, token);
        return h;
    }
}
