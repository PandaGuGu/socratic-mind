package com.socraticmind.app.share;

import com.socraticmind.common.port.ShareLinkStore;
import com.socraticmind.common.port.ShareLinkStore.ShareLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 一次性分享链接：把一轮记录交给"某一个人"，而不是交给整个互联网。
 *
 * 三条硬约束：
 * 1. 明文 token 只返回一次，库里只有 SHA-256；
 * 2. 只能看一次——{@code markUsed} 是原子操作，两个标签页同时打开也只有一个能成；
 * 3. 本人可以随时撤回，过期时间最长 7 天。
 *
 * 它不是"公开链接"：没有列表页、没有搜索、没有账号就能生成；
 * 拿到链接的人不需要登录，这也意味着链接本身就是那把钥匙，转发要慎重。
 */
@Service
public class ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareService.class);
    private static final int TOKEN_BYTES = 24;
    private static final int DEFAULT_HOURS = 24;
    private static final int MAX_HOURS = 24 * 7;

    public record Created(String token, Instant expiresAt) {
    }

    public record Opened(String sessionId, Instant expiresAt) {
    }

    private final ShareLinkStore store;
    private final SecureRandom random = new SecureRandom();

    public ShareService(ShareLinkStore store) {
        this.store = store;
    }

    public Created create(String sessionId, String ownerUserId, Integer ttlHours) {
        int hours = ttlHours == null ? DEFAULT_HOURS : Math.max(1, Math.min(ttlHours, MAX_HOURS));
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(hours * 3600L);
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.save(new ShareLink(hash(token), sessionId, ownerUserId, now, expiresAt, null, null));
        log.info("生成一次性分享链接 session={}，{} 小时后过期", sessionId, hours);
        return new Created(token, expiresAt);
    }

    /** 只看不占用：用于先确认会话还在，再决定是否消耗这一次。 */
    public Optional<Opened> preview(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return store.find(hash(token))
                .filter(link -> link.usable(Instant.now()))
                .map(link -> new Opened(link.sessionId(), link.expiresAt()));
    }

    /** 打开一次：成功即占用；已用、过期、撤回都回 empty，不区分具体原因。 */
    public Optional<Opened> open(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String hash = hash(token);
        Optional<ShareLink> found = store.find(hash);
        if (found.isEmpty()) return Optional.empty();
        ShareLink link = found.get();
        Instant now = Instant.now();
        if (!link.usable(now)) return Optional.empty();
        if (!store.markUsed(hash, now)) return Optional.empty();
        return Optional.of(new Opened(link.sessionId(), link.expiresAt()));
    }

    public int revoke(String sessionId, String ownerUserId) {
        return store.revokeBySession(sessionId, ownerUserId, Instant.now());
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算分享令牌摘要", e);
        }
    }
}
