package com.socraticmind.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 匿名用户令牌：把"这台设备是谁"变成一个服务端可验证的字符串。
 *
 * 为什么不是登录：v0.1 没有账号体系，也不该为了做账号而收集手机号。
 * 但"没有登录"不等于"没有身份"——如果 userId 由前端随便填，任何人都能
 * 声称自己是别人。这里用 HMAC 把 userId 签起来，客户端只持有令牌，
 * 令牌不可伪造、不可枚举，服务端据此判断这条会话是不是你的。
 *
 * 令牌格式：{@code v1.<base64url(userId)>.<base64url(hmac)>}
 *
 * 密钥：{@code psych.security.secret}（生产必须显式配置）。
 * 没配时启动生成临时密钥并告警——安全但重启后旧令牌失效，宁可让人重新开始，
 * 也不能用固定默认值让所有部署共享同一把钥匙。
 */
@Component
public class UserTokenService {

    private static final Logger log = LoggerFactory.getLogger(UserTokenService.class);
    private static final String VERSION = "v1";
    private static final String ALG = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 16;

    private final byte[] secret;
    private final SecureRandom random = new SecureRandom();

    public UserTokenService(@Value("${psych.security.secret:}") String configured) {
        if (configured == null || configured.isBlank()) {
            this.secret = new byte[32];
            random.nextBytes(this.secret);
            log.warn("未配置 psych.security.secret，已生成临时密钥：重启后旧令牌全部失效。"
                    + "部署前请设置环境变量 PSYCH_USER_SECRET。");
        } else {
            byte[] bytes = configured.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("psych.security.secret 至少需要 " + MIN_SECRET_BYTES + " 字节");
            }
            this.secret = bytes;
        }
    }

    /** 新建一个匿名身份：16 位十六进制随机数，不可枚举。 */
    public String newUserId() {
        byte[] buf = new byte[8];
        random.nextBytes(buf);
        return "u-" + hex(buf);
    }

    public String issue(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        String payload = encode(userId.getBytes(StandardCharsets.UTF_8));
        return VERSION + "." + payload + "." + sign(VERSION + "." + payload);
    }

    /** 校验令牌；任何格式错误、签名不符都返回 empty，调用方一律当作未登录处理。 */
    public Optional<String> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3 || !VERSION.equals(parts[0])) return Optional.empty();

        String expected = sign(VERSION + "." + parts[1]);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String userId = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return userId.isBlank() ? Optional.empty() : Optional.of(userId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALG);
            mac.init(new SecretKeySpec(secret, ALG));
            return encode(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("令牌签名失败", e);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
