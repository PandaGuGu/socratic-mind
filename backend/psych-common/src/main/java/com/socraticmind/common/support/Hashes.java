package com.socraticmind.common.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 内容指纹。只用来判断"同一份资料内容变没变"，不做安全用途。 */
public final class Hashes {

    private Hashes() {
    }

    /**
     * 内容键：取 SHA-256 前 16 个十六进制字符。
     *
     * 用它而不是 chunk id 来做离线标注的索引，是因为 id 每次重建语料都会变，
     * 而"这段话"不变。用 id 做键的标注文件，第二天就对不上任何一条——
     * 这种失效是静默的：查不到分只会退回原顺序，不会报错。
     */
    public static String shortSha(String text) {
        String full = sha256Hex(text);
        return full == null ? null : full.substring(0, 16);
    }

    public static String sha256Hex(String text) {
        if (text == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须自带 SHA-256", e);
        }
    }
}
