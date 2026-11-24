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
 * 身份令牌：把"这台设备是谁 / 这个账号是谁"变成一个服务端可验证的字符串。
 *
 * 两种令牌，同一把 HMAC 钥匙：
 * <ul>
 *   <li>{@code v1.<userId>.<sig>} —— 设备令牌。没有账号时也能完整使用，令牌即身份。</li>
 *   <li>{@code v2.<userId|accountId>.<sig>} —— 账号令牌。两个 id 都签进去：
 *       设备 id 决定"这一轮记在哪台设备名下"，账号 id 决定"还能看到哪些会话"。</li>
 * </ul>
 *
 * 为什么把设备 id 也装进账号令牌：若只签账号，登录后新开的会话就没有设备归属，
 * 退出登录时会从本机"消失"——而它本来属于这台设备。分开签，登录与退出都可逆。
 *
 * 令牌格式不可枚举（随机 id + HMAC），也不含明文邮箱；泄露一枚令牌即可读该身份的全部会话，
 * 这与"无账号的匿名令牌"是同一个风险等级，区别只在于账号令牌覆盖更多会话。
 *
 * 密钥：{@code psych.security.secret}（生产必须显式配置）。
 * 没配时启动生成临时密钥并告警——安全但重启后旧令牌失效，宁可让人重新开始，
 * 也不能用固定默认值让所有部署共享同一把钥匙。
 */
@Component
public class UserTokenService {

    /**
     * 一次请求解析出来的身份：设备 id 必有，账号 id 可空。
     *
     * {@code epoch} 只对账号令牌有意义：它必须等于账号当前的 {@code token_epoch}，
     * 否则说明这枚令牌签发于改口令/退出其他设备之前，应当失效。
     * 设备令牌没有 epoch（恒为 0）。
     */
    public record Identity(String userId, String accountId, int epoch) {

        public boolean hasAccount() {
            return accountId != null && !accountId.isBlank();
        }

        public static Identity device(String userId) {
            return new Identity(userId, null, 0);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(UserTokenService.class);
    private static final String V1 = "v1";
    private static final String V2 = "v2";
    private static final String ALG = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 16;
    private static final String SEP = "|";

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

    /** 设备令牌：只签设备 id。 */
    public String issue(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return sign(V1, encode(userId.getBytes(StandardCharsets.UTF_8)));
    }

    /** 账号令牌：设备 id、账号 id 与令牌代数一起签，三者都不可单独篡改。 */
    public String issueAccount(String userId, String accountId, int epoch) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        String payload = encode((userId + SEP + accountId + SEP + epoch).getBytes(StandardCharsets.UTF_8));
        return sign(V2, payload);
    }

    /** 校验令牌；任何格式错误、签名不符都返回 empty，调用方一律当作未登录处理。 */
    public Optional<Identity> verifyIdentity(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) return Optional.empty();
        String version = parts[0];
        if (!V1.equals(version) && !V2.equals(version)) return Optional.empty();

        String expected = hmac(version + "." + parts[1]);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            if (decoded.isBlank()) return Optional.empty();
            if (V1.equals(version)) return Optional.of(Identity.device(decoded));

            String[] fields = decoded.split("\\|", 3);
            if (fields.length < 2) return Optional.empty();
            String userId = fields[0];
            String accountId = fields[1];
            if (userId.isBlank() || accountId.isBlank()) return Optional.empty();
            // v0.5.0 的账号令牌只有两段：按 epoch 0 处理，仍然可用；下一次改口令后自然失效。
            int epoch = 0;
            if (fields.length == 3) {
                try {
                    epoch = Integer.parseInt(fields[2]);
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
                if (epoch < 0) return Optional.empty();
            }
            return Optional.of(new Identity(userId, accountId, epoch));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 只关心设备 id 的地方（限流键、日志）用这个；账号归属请用 {@link #verifyIdentity}。 */
    public Optional<String> verify(String token) {
        return verifyIdentity(token).map(Identity::userId);
    }

    /** 返回完整令牌：版本、载荷、签名三段，签名覆盖"版本.载荷"。 */
    private String sign(String version, String payload) {
        return version + "." + payload + "." + hmac(version + "." + payload);
    }

    private String hmac(String payload) {
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
