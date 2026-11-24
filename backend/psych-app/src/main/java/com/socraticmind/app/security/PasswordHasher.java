package com.socraticmind.app.security;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 口令摘要：PBKDF2-HmacSHA256，不引入额外依赖（JDK 自带）。
 *
 * 为什么不是 BCrypt：那要再进一个库，而本项目一直用离线构建；
 * PBKDF2 是 NIST 认可的口令派生函数，配合足够迭代次数与随机盐，够用且可解释。
 * 为什么不是 SHA-256 加盐：那是"一次哈希"，GPU 每秒能试几十亿次；PBKDF2 把每次尝试的成本抬到毫秒级。
 *
 * 存储格式：{@code pbkdf2-sha256$<iterations>$<saltB64>$<hashB64>}。
 * 迭代次数写进字符串，将来调高参数时旧口令仍可校验（用记录里的次数，而不是当前常量）。
 * 校验用常数时间比较，避免按字节提前返回带来的时间侧信道。
 */
@Component
public class PasswordHasher {

    private static final String SCHEME = "pbkdf2-sha256";
    private static final String ALG = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    public String hash(String raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("口令不能为空");
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] key = derive(raw, salt, ITERATIONS);
        return SCHEME + "$" + ITERATIONS + "$" + b64(salt) + "$" + b64(key);
    }

    /** 任何格式异常都当作"不匹配"，不把"这条记录坏了"暴露成 500。 */
    public boolean matches(String raw, String stored) {
        if (raw == null || raw.isEmpty() || stored == null || stored.isBlank()) return false;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !SCHEME.equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1 || iterations > 5_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(raw, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String raw, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALG).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令摘要失败", e);
        } finally {
            // 用完即清：char[] 是口令在堆里停留最久的一份副本
            spec.clearPassword();
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** 口令长度下限在这里统一表达，接口层与测试都引用同一常量，避免两处不一致。 */
    public static boolean acceptable(String raw) {
        if (raw == null) return false;
        String trimmed = raw.strip();
        if (trimmed.length() < 8) return false;
        // 口令长度按字节也要有上限：PBKDF2 对超长输入不做保护，1 MB 的口令会变成一次小型的 CPU 攻击
        return raw.getBytes(StandardCharsets.UTF_8).length <= 256;
    }
}
