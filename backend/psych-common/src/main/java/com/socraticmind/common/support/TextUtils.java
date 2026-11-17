package com.socraticmind.common.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文本工具。中文按二元组切词、英文按词切——足够支撑第一版的词法检索与引用覆盖率计算。
 * 不引入分词器是刻意的：这一层的可控性比"分得更准"重要，真正的语义匹配交给向量路。
 */
public final class TextUtils {

    private TextUtils() {
    }

    public static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}，。！？；：、“”‘’（）《》【】…—·～`]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static boolean hasCjk(String text) {
        if (text == null) return false;
        return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    /** 中文二元组 + 英文单词（长度 >= 2）。 */
    public static List<String> tokens(String text) {
        String norm = normalize(text);
        Set<String> out = new LinkedHashSet<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder latin = new StringBuilder();

        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            boolean isHan = Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
            if (isHan) {
                flushLatin(latin, out);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, out);
                latin.append(c);
            } else {
                flushCjk(cjk, out);
                flushLatin(latin, out);
            }
        }
        flushCjk(cjk, out);
        flushLatin(latin, out);
        return new ArrayList<>(out);
    }

    private static void flushCjk(StringBuilder sb, Set<String> out) {
        if (sb.isEmpty()) return;
        String run = sb.toString();
        if (run.length() == 1) {
            out.add(run);
        } else {
            for (int i = 0; i + 1 < run.length(); i++) {
                out.add(run.substring(i, i + 2));
            }
        }
        sb.setLength(0);
    }

    private static void flushLatin(StringBuilder sb, Set<String> out) {
        if (sb.isEmpty()) return;
        String w = sb.toString();
        if (w.length() >= 2) out.add(w);
        sb.setLength(0);
    }

    /** 词法打分：命中词数占查询词数比例，并对标题命中加权。 */
    public static double lexicalScore(String query, String titlePath, String content) {
        List<String> q = tokens(query);
        if (q.isEmpty()) return 0;
        String c = normalize(content);
        String t = normalize(titlePath == null ? "" : titlePath);
        int hit = 0;
        double weighted = 0;
        for (String token : q) {
            boolean inContent = c.contains(token);
            boolean inTitle = t.contains(token);
            if (inContent || inTitle) hit++;
            if (inContent) weighted += 1.0;
            if (inTitle) weighted += 0.5;
        }
        if (hit == 0) return 0;
        return Math.min(1.0, (weighted / q.size()) / 1.5);
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 按中文标点切句，用于引用覆盖率计算。 */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String part : text.split("[。！？；\\n]+")) {
            String s = part.trim();
            if (s.length() >= 6) out.add(s);
        }
        return out;
    }

    public static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
