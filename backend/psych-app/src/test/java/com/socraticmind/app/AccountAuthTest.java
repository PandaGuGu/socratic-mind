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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号链路的验收：注册、登录、认领确认、改口令、退出其他设备、恢复码、注销。
 *
 * 这里刻意区分三种身份：
 * <ul>
 *   <li>设备令牌（v1）——只看得到这台设备开的会话；</li>
 *   <li>账号令牌（v2）——看得到已认领到账号的全部会话；</li>
 *   <li>陌生设备——两者都读不到，一律 404（不暴露"这条会话存在"）。</li>
 * </ul>
 *
 * v0.7.0 的两条新边界：
 * 1. 登录/注册不再自动认领，必须显式调 /claim；
 * 2. 令牌带着 epoch，改口令 / 退出其他设备 / 重置口令会让旧令牌立即 401。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountAuthTest {

    private static final String TOKEN = "X-User-Token";

    @Autowired
    TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void registerAsksBeforeClaimingDeviceSessions() {
        String deviceToken = startSession();
        String sessionId = lastSessionId(deviceToken);
        String email = uniqueEmail("claim");

        ResponseEntity<Map> registered = register(deviceToken, email, "a-long-password", "小周");
        assertEquals(HttpStatus.OK, registered.getStatusCode());
        String accountToken = registered.getHeaders().getFirst(TOKEN);
        assertNotNull(accountToken);
        assertTrue(accountToken.startsWith("v2."), "登录后应当拿到账号令牌，而不是继续用设备令牌");

        Map<String, Object> account = (Map<String, Object>) registered.getBody().get("account");
        assertEquals(email, account.get("email"));
        assertEquals("小周", account.get("displayName"));
        assertEquals(1, ((Number) registered.getBody().get("pendingClaims")).intValue(),
                "只报告可认领数量，不替用户认领");
        assertNotNull(registered.getBody().get("recoveryCode"), "注册时必须给一次恢复码");

        // 还没点确认：账号令牌看不到这台设备上的匿名会话
        assertEquals(HttpStatus.NOT_FOUND, get("/api/cbt/sessions/" + sessionId, accountToken).getStatusCode());

        Map<String, Object> claimed = postOk("/api/auth/claim", accountToken, "{}");
        assertEquals(1, ((Number) claimed.get("claimed")).intValue());
        assertEquals(HttpStatus.OK, get("/api/cbt/sessions/" + sessionId, accountToken).getStatusCode());

        Map<String, Object> me = getOk("/api/auth/me", accountToken);
        assertEquals(Boolean.TRUE, me.get("signedIn"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginOnSecondDeviceBringsClaimedSessionsAndNotOthers() {
        String deviceA = startSession();
        String sessionA = lastSessionId(deviceA);
        String email = uniqueEmail("cross");
        String password = "another-good-password";
        String accountToken = register(deviceA, email, password, null).getHeaders().getFirst(TOKEN);
        postOk("/api/auth/claim", accountToken, "{}");

        // 第二台设备：先匿名开一轮（未认领）
        ResponseEntity<Map> startedB = post("/api/cbt/sessions", null, "{}");
        String deviceB = startedB.getHeaders().getFirst(TOKEN);
        String sessionB = (String) startedB.getBody().get("sessionId");

        // 账号在另一台设备上，读不到 B 这台设备尚未认领的会话
        assertEquals(HttpStatus.NOT_FOUND, get("/api/cbt/sessions/" + sessionB, accountToken).getStatusCode());

        // 在 B 上登录：B 的会话仍在等确认，A 已认领的照常可见
        ResponseEntity<Map> login = login(deviceB, email, password);
        assertEquals(HttpStatus.OK, login.getStatusCode());
        assertEquals(1, ((Number) login.getBody().get("pendingClaims")).intValue());
        String tokenB = login.getHeaders().getFirst(TOKEN);

        assertEquals(HttpStatus.OK, get("/api/cbt/sessions/" + sessionA, tokenB).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/api/cbt/sessions/" + sessionB, tokenB).getStatusCode());
        postOk("/api/auth/claim", tokenB, "{}");
        assertEquals(HttpStatus.OK, get("/api/cbt/sessions/" + sessionB, tokenB).getStatusCode());

        // 第三台设备（未登录）两轮都读不到
        String stranger = startSession();
        assertEquals(HttpStatus.NOT_FOUND, get("/api/cbt/sessions/" + sessionA, stranger).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/api/cbt/sessions/" + sessionB, stranger).getStatusCode());
    }

    @Test
    void rejectsWeakInputAndKeepsLoginErrorsVague() {
        String email = uniqueEmail("dup");
        assertEquals(HttpStatus.OK, register(null, email, "first-good-password", null).getStatusCode());

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, register(null, email, "first-good-password", null).getStatusCode(),
                "同一邮箱重复注册必须被拒");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, login(null, email, "wrong-password").getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, register(null, uniqueEmail("short"), "123", null).getStatusCode(),
                "短口令必须被拒");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, register(null, "not-an-email", "long-enough-pass", null).getStatusCode());

        // 没有令牌时 /me 只是"没登录"，不是 401——否则房间页一打开就报错
        Map<String, Object> me = getOk("/api/auth/me", null);
        assertEquals(Boolean.FALSE, me.get("signedIn"));
    }

    @Test
    void accountTokenCannotReadAnotherAccountsSession() {
        String emailA = uniqueEmail("a");
        String tokenA = register(null, emailA, "password-for-a", null).getHeaders().getFirst(TOKEN);

        String deviceB = startSession();
        String sessionB = lastSessionId(deviceB);

        assertEquals(HttpStatus.NOT_FOUND, get("/api/cbt/sessions/" + sessionB, tokenA).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/cbt/sessions/" + sessionB + "/history", tokenA).getStatusCode());
    }

    /**
     * 同一台浏览器换账号：新账号不能靠"设备归属"读到前一个账号的记录。
     * 现在连"自动认领"都没有了，后一个账号连可认领列表都看不到（A 已认领）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void switchingAccountsOnTheSameDeviceDoesNotLeak() {
        String device = startSession();
        String sessionA = lastSessionId(device);
        String emailA = uniqueEmail("switch-a");
        String tokenA = register(device, emailA, "password-for-a", null).getHeaders().getFirst(TOKEN);
        postOk("/api/auth/claim", tokenA, "{}");
        assertEquals(HttpStatus.OK, get("/api/cbt/sessions/" + sessionA, tokenA).getStatusCode());

        String emailB = uniqueEmail("switch-b");
        ResponseEntity<Map> registered = register(tokenA, emailB, "password-for-b", null);
        String tokenB = registered.getHeaders().getFirst(TOKEN);
        assertTrue(!tokenA.equals(tokenB));
        assertEquals(0, ((Number) registered.getBody().get("pendingClaims")).intValue(),
                "A 已认领，B 不应再看到可认领记录");

        assertEquals(HttpStatus.NOT_FOUND, get("/api/cbt/sessions/" + sessionA, tokenB).getStatusCode(),
                "换账号后不应还能读到前一个账号在这台设备上的记录");
    }

    @Test
    void changingPasswordRevokesOtherDevicesButKeepsThisOne() {
        String deviceA = startSession();
        String email = uniqueEmail("passwd");
        String password = "original-password";
        String tokenA = register(deviceA, email, password, null).getHeaders().getFirst(TOKEN);

        String deviceB = startSession();
        String tokenB = login(deviceB, email, password).getHeaders().getFirst(TOKEN);

        ResponseEntity<Map> changed = post("/api/auth/password", tokenA,
                "{\"currentPassword\":\"" + password + "\",\"newPassword\":\"brand-new-password\"}");
        assertEquals(HttpStatus.OK, changed.getStatusCode(), String.valueOf(changed.getBody()));
        String renewed = changed.getHeaders().getFirst(TOKEN);
        assertNotNull(renewed);
        assertFalse(renewed.equals(tokenA), "改口令后当前设备也要换一枚新令牌");

        assertEquals(HttpStatus.UNAUTHORIZED, statusOf("/api/cbt/sessions", tokenA), "旧令牌必须失效");
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf("/api/cbt/sessions", tokenB), "其他设备必须失效");
        assertEquals(HttpStatus.OK, statusOf("/api/cbt/sessions", renewed));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, login(null, email, password).getStatusCode());
        assertEquals(HttpStatus.OK, login(null, email, "brand-new-password").getStatusCode());
    }

    @Test
    void logoutOtherDevicesKeepsCurrentTokenWorking() {
        String email = uniqueEmail("logout");
        String password = "logout-all-password";
        String tokenA = register(startSession(), email, password, null).getHeaders().getFirst(TOKEN);
        String tokenB = login(startSession(), email, password).getHeaders().getFirst(TOKEN);

        ResponseEntity<Map> result = post("/api/auth/logout-all", tokenA, "{}");
        assertEquals(HttpStatus.OK, result.getStatusCode());
        String renewed = result.getHeaders().getFirst(TOKEN);
        assertEquals(HttpStatus.OK, statusOf("/api/cbt/sessions", renewed));
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf("/api/cbt/sessions", tokenB));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoveryCodeIsSingleUseAndResetsEverything() {
        String email = uniqueEmail("recover");
        String password = "forgotten-password";
        ResponseEntity<Map> registered = register(startSession(), email, password, null);
        String oldToken = registered.getHeaders().getFirst(TOKEN);
        String code = (String) registered.getBody().get("recoveryCode");
        assertNotNull(code);

        ResponseEntity<Map> recovered = post("/api/auth/recover", null,
                "{\"email\":\"" + email + "\",\"recoveryCode\":\"" + code + "\",\"newPassword\":\"recovered-password\"}");
        assertEquals(HttpStatus.OK, recovered.getStatusCode(), String.valueOf(recovered.getBody()));
        String newToken = recovered.getHeaders().getFirst(TOKEN);
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf("/api/cbt/sessions", oldToken),
                "重置后旧令牌全部失效");
        assertEquals(HttpStatus.OK, statusOf("/api/cbt/sessions", newToken));

        // 同一枚恢复码不能再用
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, post("/api/auth/recover", null,
                "{\"email\":\"" + email + "\",\"recoveryCode\":\"" + code + "\",\"newPassword\":\"another-password\"}")
                .getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, login(null, email, password).getStatusCode());
        assertEquals(HttpStatus.OK, login(null, email, "recovered-password").getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotatingRecoveryCodeInvalidatesTheOldOne() {
        String email = uniqueEmail("rotate");
        String password = "rotate-password";
        ResponseEntity<Map> registered = register(null, email, password, null);
        String token = registered.getHeaders().getFirst(TOKEN);
        String oldCode = (String) registered.getBody().get("recoveryCode");

        ResponseEntity<Map> rotated = post("/api/auth/recovery-code", token, "{}");
        assertEquals(HttpStatus.OK, rotated.getStatusCode());
        String newCode = (String) rotated.getBody().get("recoveryCode");
        assertNotNull(newCode);
        assertFalse(newCode.equals(oldCode));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, post("/api/auth/recover", null,
                "{\"email\":\"" + email + "\",\"recoveryCode\":\"" + oldCode + "\",\"newPassword\":\"x-new-password\"}")
                .getStatusCode());
        assertEquals(HttpStatus.OK, post("/api/auth/recover", null,
                "{\"email\":\"" + email + "\",\"recoveryCode\":\"" + newCode + "\",\"newPassword\":\"x-new-password\"}")
                .getStatusCode());
    }

    /**
     * 注销：账号删掉、令牌立刻失效、记录回到设备名下。
     */
    @Test
    void deletionRevokesAccountTokensAndKeepsDeviceSessions() {
        String device = startSession();
        String sessionId = lastSessionId(device);
        String email = uniqueEmail("delete");
        String password = "delete-me-password";
        String accountToken = register(device, email, password, null).getHeaders().getFirst(TOKEN);
        postOk("/api/auth/claim", accountToken, "{}");
        assertEquals(HttpStatus.OK, get("/api/cbt/sessions/" + sessionId, accountToken).getStatusCode());

        // 口令不对：拒绝，且账号还在
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                post("/api/auth/delete", accountToken, "{\"password\":\"not-my-password\"}").getStatusCode());
        assertEquals(HttpStatus.OK, get("/api/cbt/sessions/" + sessionId, accountToken).getStatusCode());

        ResponseEntity<Map> deleted = post("/api/auth/delete", accountToken,
                "{\"password\":\"" + password + "\"}");
        assertEquals(HttpStatus.OK, deleted.getStatusCode());
        assertEquals(Boolean.TRUE, deleted.getBody().get("deleted"));
        assertTrue(((Number) deleted.getBody().get("releasedSessions")).intValue() >= 1);

        // 同一枚账号令牌立刻变成 401：注销即吊销
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/cbt/sessions/" + sessionId, accountToken).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf("/api/cbt/sessions", accountToken));
        Map<String, Object> me = getOk("/api/auth/me", accountToken);
        assertEquals(Boolean.FALSE, me.get("signedIn"));

        // 记录还在，回到设备名下
        assertEquals(HttpStatus.OK, get("/api/cbt/sessions/" + sessionId, device).getStatusCode());

        // 登录不再可用，错误与其他登录失败一致
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, login(null, email, password).getStatusCode());
    }

    /**
     * 账号级紧急联系人：登录后可读写，保存时只做字符清洗，传空即清空。
     * "新会话自动带上"由 SessionContacts 的单测覆盖——这里不再开会话，
     * 否则会撞上"每小时新会话数"的限流（那条限流在真实环境里是对的）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void accountContactIsSavedSanitizedAndCanBeCleared() {
        String email = uniqueEmail("contact");
        String accountToken = register(null, email, "contact-password", null).getHeaders().getFirst(TOKEN);

        Map<String, Object> saved = putOk("/api/auth/contact", accountToken,
                "{\"name\":\" 小周 \",\"phone\":\"138-0000-1111 转 12\",\"relation\":\"室友\"}");
        assertEquals("小周", saved.get("name"));
        assertTrue(String.valueOf(saved.get("phone")).contains("138-0000-1111"),
                "只保留数字与常见分隔符，去掉无关字符");
        assertEquals("室友", saved.get("relation"));

        Map<String, Object> read = getOk("/api/auth/contact", accountToken);
        assertEquals("小周", read.get("name"), "重新读取时应当拿到保存过的联系人");

        putOk("/api/auth/contact", accountToken, "{}");
        assertNull(getOk("/api/auth/contact", accountToken).get("name"), "传空即清空");

        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/auth/contact", null).getStatusCode(), "未登录不能读写联系人");
    }

    // ---------- helpers ----------

    private ResponseEntity<Map> register(String token, String email, String password, String displayName) {
        String body = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"displayName\":\""
                + (displayName == null ? "" : displayName) + "\"}";
        return post("/api/auth/register", token, body);
    }

    private ResponseEntity<Map> login(String token, String email, String password) {
        return post("/api/auth/login", token,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    /**
     * 导出：账号信息与本人能看到的会话一起下载；换一个身份看不到别人的。
     */
    @Test
    void exportContainsOwnAccountAndSessionsOnly() {
        String device = startSession();
        String sessionId = lastSessionId(device);
        String email = uniqueEmail("export");
        String password = "export-password-123";
        String accountToken = register(device, email, password, "导出测试").getHeaders().getFirst(TOKEN);
        postOk("/api/auth/claim", accountToken, "{}");

        ResponseEntity<String> json = rest.exchange("/api/export/me", HttpMethod.GET,
                new HttpEntity<>(headers(accountToken)), String.class);
        assertEquals(HttpStatus.OK, json.getStatusCode());
        assertTrue(json.getBody().contains(email), "导出必须包含账号信息");
        assertTrue(json.getBody().contains(sessionId), "导出必须包含自己的会话");
        assertTrue(json.getBody().contains("\"kind\" : \"account\""));

        ResponseEntity<String> markdown = rest.exchange("/api/export/me?format=md", HttpMethod.GET,
                new HttpEntity<>(headers(accountToken)), String.class);
        assertEquals(HttpStatus.OK, markdown.getStatusCode());
        assertTrue(markdown.getBody().startsWith("# Socratic Mind 数据导出"));
        assertTrue(markdown.getBody().contains(email));

        assertEquals(HttpStatus.UNAUTHORIZED, rest.exchange("/api/export/me", HttpMethod.GET,
                new HttpEntity<>(headers(null)), String.class).getStatusCode());
    }

    private String startSession() {
        ResponseEntity<Map> started = post("/api/cbt/sessions", null, "{}");
        assertEquals(HttpStatus.OK, started.getStatusCode());
        return started.getHeaders().getFirst(TOKEN);
    }

    private String lastSessionId(String token) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> postOk(String path, String token, String json) {
        ResponseEntity<Map> res = post(path, token, json);
        assertEquals(HttpStatus.OK, res.getStatusCode(), path + " -> " + res.getBody());
        assertNotNull(res.getBody());
        return res.getBody();
    }

    private Map<String, Object> putOk(String path, String token, String json) {
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> res = rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(json, h), Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode(), path + " -> " + res.getBody());
        assertNotNull(res.getBody());
        return res.getBody();
    }

    private Map<String, Object> getOk(String path, String token) {
        ResponseEntity<Map> res = get(path, token);
        assertEquals(HttpStatus.OK, res.getStatusCode(), path);
        assertNotNull(res.getBody());
        return res.getBody();
    }

    /** 只关心状态码的请求：列表接口返回数组，不能按 Map 反序列化。 */
    private HttpStatus statusOf(String path, String token) {
        int code = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), String.class)
                .getStatusCode().value();
        return HttpStatus.valueOf(code);
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), Map.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set(TOKEN, token);
        return h;
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }
}
