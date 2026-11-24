package com.socraticmind.app.security;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌本身的往返与防篡改。这条测试不启 Spring：
 * 令牌是纯函数（HMAC + base64），启容器只会把失败原因藏起来。
 */
class UserTokenServiceTest {

    private final UserTokenService tokens = new UserTokenService("0123456789abcdef0123456789abcdef");

    @Test
    void deviceTokenRoundTrip() {
        String token = tokens.issue("u-abc123");
        assertTrue(token.startsWith("v1."));
        Optional<UserTokenService.Identity> identity = tokens.verifyIdentity(token);
        assertTrue(identity.isPresent());
        assertEquals("u-abc123", identity.get().userId());
        assertFalse(identity.get().hasAccount());
    }

    @Test
    void accountTokenCarriesIdsAndEpoch() {
        String token = tokens.issueAccount("u-abc123", "acc_1750000000_zzzz", 7);
        assertTrue(token.startsWith("v2."));
        UserTokenService.Identity identity = tokens.verifyIdentity(token).orElseThrow();
        assertEquals("u-abc123", identity.userId());
        assertEquals("acc_1750000000_zzzz", identity.accountId());
        assertEquals(7, identity.epoch());
        assertTrue(identity.hasAccount());
    }

    @Test
    void tamperedOrForeignTokenIsRejected() {
        String token = tokens.issueAccount("u-abc123", "acc_1", 0);
        String[] parts = token.split("\\.");
        assertTrue(tokens.verifyIdentity(parts[0] + "." + parts[1] + "." + parts[2] + "x").isEmpty());
        assertTrue(tokens.verifyIdentity("v1." + parts[1] + "." + parts[2]).isEmpty(), "改版本号必须失效");
        assertTrue(tokens.verifyIdentity("v1.YWJj.ZGVm").isEmpty());
        assertTrue(tokens.verifyIdentity(null).isEmpty());
        assertTrue(tokens.verifyIdentity("").isEmpty());
    }

    @Test
    void anotherSecretCannotVerify() {
        String token = tokens.issue("u-abc123");
        UserTokenService other = new UserTokenService("ffffffffffffffffffffffffffffffff");
        assertTrue(other.verifyIdentity(token).isEmpty());
    }
}
