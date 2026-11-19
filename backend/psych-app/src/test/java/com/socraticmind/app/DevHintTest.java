package com.socraticmind.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 开发档的"钥匙提示"：给的是提示，不是绕过。
 *
 * 两件事必须同时成立：
 * 1. 本机默认令牌能通过 /api/internal-hint 拿到（省掉手打）；
 * 2. 拿到令牌之前，内部接口仍然是 401——提示接口不会让鉴权失效。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "psych.internal.enabled=true",
                "psych.internal.token=dev-internal-token",
                "psych.internal.dev-hint=true",
                "psych.safety.rules-file=target/test-config/safety-rules.json",
                "psych.limits.file=target/test-config/limits.json",
                "psych.flow.params-file=target/test-config/flow-params.json",
                "psych.config.history-dir=target/test-config/history"
        })
class DevHintTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void hintIsAvailableButAuthStillRequired() {
        ResponseEntity<Map> hint = rest.getForEntity("/api/internal-hint", Map.class);
        assertEquals(HttpStatus.OK, hint.getStatusCode());
        assertEquals("dev-internal-token", hint.getBody().get("token"));

        // 关键：提示接口不需要钥匙，但内部接口仍然需要
        assertEquals(HttpStatus.UNAUTHORIZED,
                rest.getForEntity("/api/internal/overview", String.class).getStatusCode());
    }
}
