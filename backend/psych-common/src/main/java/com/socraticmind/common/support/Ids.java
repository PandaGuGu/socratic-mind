package com.socraticmind.common.support;

import java.security.SecureRandom;

/**
 * 轻量 ID 生成：前缀 + 时间（base36）+ 随机后缀。
 *
 * 随机后缀不是装饰：会话 id 曾经是纯自增的，配合无鉴权接口，
 * 按序枚举就能读到别人的记录。现在接口已经要求身份令牌，
 * 但"猜不到的 id"仍是第二道防线，成本几乎为零。
 *
 * 真正上线换 ULID/Snowflake 时只改这一处。
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private Ids() {
    }

    public static String of(String prefix) {
        return prefix + "_" + Long.toString(System.currentTimeMillis(), 36) + "_" + suffix(8);
    }

    private static String suffix(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
