package com.socraticmind.common.port;

import java.time.Instant;
import java.util.Optional;

/**
 * 一次性分享链接的存储端口。
 *
 * 只认 {@code tokenHash}：明文 token 只在生成时返回一次，服务端不保存它。
 * 这样即使库被读走，也拿不到能直接打开的链接。
 */
public interface ShareLinkStore {

    /** 一条链接的全部状态。usedAt / revokedAt 为空表示"还没用过 / 没被撤回"。 */
    record ShareLink(String tokenHash, String sessionId, String ownerUserId,
                     Instant createdAt, Instant expiresAt, Instant usedAt, Instant revokedAt) {

        public boolean usable(Instant now) {
            return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
        }
    }

    void save(ShareLink link);

    Optional<ShareLink> find(String tokenHash);

    /**
     * 原子地标记"已看过"，返回是否抢到这一次。
     * 两个标签页同时打开同一条链接时，只能有一个成功——不然"一次性"就只是文案。
     */
    boolean markUsed(String tokenHash, Instant at);

    /** 撤回某条会话的全部有效链接，返回条数。 */
    int revokeBySession(String sessionId, String ownerUserId, Instant at);
}
