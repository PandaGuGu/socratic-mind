package com.socraticmind.infrastructure.store;

import com.socraticmind.common.port.ShareLinkStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 分享链接内存实现：默认档与测试用，重启即丢。 */
@Component
@ConditionalOnProperty(name = "psych.db.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryShareLinkStore implements ShareLinkStore {

    private final Map<String, ShareLink> links = new ConcurrentHashMap<>();

    @Override
    public void save(ShareLink link) {
        links.put(link.tokenHash(), link);
    }

    @Override
    public Optional<ShareLink> find(String tokenHash) {
        return Optional.ofNullable(links.get(tokenHash));
    }

    @Override
    public boolean markUsed(String tokenHash, Instant at) {
        AtomicBoolean won = new AtomicBoolean(false);
        links.computeIfPresent(tokenHash, (key, link) -> {
            if (!link.usable(at)) return link;
            won.set(true);
            return new ShareLink(link.tokenHash(), link.sessionId(), link.ownerUserId(),
                    link.createdAt(), link.expiresAt(), at, link.revokedAt());
        });
        return won.get();
    }

    @Override
    public int revokeBySession(String sessionId, String ownerUserId, Instant at) {
        int[] count = {0};
        links.replaceAll((key, link) -> {
            if (!sessionId.equals(link.sessionId()) || !ownerUserId.equals(link.ownerUserId())) return link;
            if (link.usedAt() != null || link.revokedAt() != null) return link;
            count[0]++;
            return new ShareLink(link.tokenHash(), link.sessionId(), link.ownerUserId(),
                    link.createdAt(), link.expiresAt(), link.usedAt(), at);
        });
        return count[0];
    }
}
