package com.socraticmind.app.auth;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * 一次性恢复码：没有发信通道时的"自救钥匙"。
 *
 * 12 位、32 字符表（去掉 0/O/1/I 这类抄错就废的字符），约 60 bit 熵。
 * 对在线爆破来说足够，因为验证接口有限流；对离线撞库来说，库泄露者拿到的只是 PBKDF2 摘要。
 *
 * 只显示一次，用一次即作废；重新生成会让旧码立刻失效。这不是"邮箱验证"的替代品——
 * 它证明的是"你拿着注册时抄下来的那张纸"，不是"你能收这个邮箱的信"。
 */
public final class RecoveryCodes {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LENGTH = 12;

    private RecoveryCodes() {
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH + 2);
        for (int i = 0; i < LENGTH; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** 用户抄写时可能带空格、小写、全角横线；只保留字母数字并统一大写。 */
    public static String normalize(String code) {
        if (code == null) return "";
        StringBuilder sb = new StringBuilder(code.length());
        for (char c : code.toUpperCase(Locale.ROOT).toCharArray()) {
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')) sb.append(c);
        }
        return sb.toString();
    }
}
